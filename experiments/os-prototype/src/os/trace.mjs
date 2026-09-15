import { mkdir, open } from 'node:fs/promises';
import { join } from 'node:path';
import { copyMessage } from './value.mjs';

const maximum = { segmentBytes: 16 * 1024 * 1024, budgetBytes: 64 * 1024 * 1024,
  cleanupBytes: 1024 * 1024, queueBytes: 4 * 1024 * 1024 };

/** An append-only run trace. Pressure invalidates a run; it never rotates essential events away. */
export class DecisionTrace {
  #writer;
  #runId;
  #limits;
  #queue = [];
  #queuedBytes = 0;
  #position = 0;
  #sequence = 0;
  #writtenThrough = 0;
  #writing = null;
  #failure = null;
  #writerBroken = false;
  #listeners = new Set();
  #closing = null;

  static async open(directory, options) {
    const files = new Map();
    const writer = {
      write: async (segment, bytes) => {
        let file = files.get(segment);
        if (!file) { file = await open(join(directory, `trace-${segment}.jsonl`), 'wx', 0o600); files.set(segment, file); }
        await file.writeFile(bytes);
      },
      close: async () => { await Promise.all([...files.values()].map(file => file.close())); }
    };
    const trace = new DecisionTrace({ ...options, writer });
    await mkdir(directory, { mode: 0o700 });
    return trace;
  }
  constructor({ writer, runId, limits = {} }) {
    if (!writer || typeof writer.write !== 'function' || typeof writer.close !== 'function' ||
        typeof runId !== 'string' || !runId.length || runId.length > 256) throw Error('invalid_trace');
    this.#limits = { ...maximum, ...limits };
    for (const [key, value] of Object.entries(this.#limits)) {
      if (!Number.isSafeInteger(value) || value < 256 || value > (maximum[key] ?? 0)) throw Error('invalid_trace_limits');
    }
    const { segmentBytes, budgetBytes, cleanupBytes, queueBytes } = this.#limits;
    if (budgetBytes > 4 * segmentBytes || cleanupBytes >= budgetBytes || queueBytes < 1024) throw Error('invalid_trace_limits');
    this.#writer = writer; this.#runId = runId;
  }
  assertHealthy() {
    if (this.#failure) throw this.#failure;
    if (this.#closing) throw Error('trace_closed');
  }
  onFailure(listener) {
    this.#listeners.add(listener);
    if (this.#failure) this.#notify(listener);
    return () => this.#listeners.delete(listener);
  }
  record(type, data, { cleanup = false } = {}) {
    try {
      if (this.#closing) throw Error('trace_closed');
      if (this.#writerBroken || (this.#failure && !cleanup)) throw this.#failure;
      if (typeof type !== 'string' || !/^[a-z][a-z0-9_.]{0,79}$/.test(type)) throw Error('invalid_trace_event');
      const seqNo = this.#sequence + 1;
      const event = { schemaVersion: 1, runId: this.#runId, seqNo, hostMonoMillis: performance.now(), atMillis: Date.now(),
        // Trusted metadata wraps a bounded guest value; keep node headroom as well as byte headroom.
        type, data: copyMessage(data, 64 * 1024, { maximumNodes: 4096 }) };
      const bytes = Buffer.from(JSON.stringify(event) + '\n');
      const { segmentBytes, budgetBytes, cleanupBytes, queueBytes } = this.#limits;
      if (bytes.length > Math.min(64 * 1024, segmentBytes)) throw Error('trace_event_limit');
      const reserve = Math.min(cleanupBytes, 64 * 1024, Math.floor(queueBytes / 4));
      if (this.#queuedBytes + bytes.length > queueBytes - (cleanup ? 0 : reserve)) throw Error('trace_queue_capacity');
      let segment = Math.floor(this.#position / segmentBytes), offset = this.#position % segmentBytes;
      if (offset + bytes.length > segmentBytes) { segment++; offset = 0; }
      const end = segment * segmentBytes + offset + bytes.length;
      if (segment >= 4 || end > budgetBytes - (cleanup ? 0 : cleanupBytes)) throw Error('trace_budget_exhausted');
      this.#position = end; this.#sequence = seqNo;
      this.#queue.push({ segment, bytes, seqNo }); this.#queuedBytes += bytes.length;
      this.#startWriter();
      return seqNo;
    } catch (error) {
      this.#fail(error);
      if (cleanup) return null;
      throw error;
    }
  }
  status() {
    return { runId: this.#runId, through: this.#sequence, writtenThrough: this.#writtenThrough, queuedBytes: this.#queuedBytes,
      reservedBytes: this.#position, incomplete: this.#failure !== null, reason: this.#failure?.message ?? null,
      writerBroken: this.#writerBroken, closing: this.#closing !== null };
  }
  async flush() {
    while (this.#writing) await this.#writing;
    if (this.#writerBroken) throw this.#failure;
  }
  close() {
    if (this.#closing) return this.#closing;
    this.record('trace.closed', { complete: this.#failure === null }, { cleanup: true });
    this.#closing = (async () => {
      try { await this.flush(); }
      finally { await this.#writer.close(); this.#listeners.clear(); }
      return this.status();
    })();
    return this.#closing;
  }
  #startWriter() {
    if (this.#writing || this.#writerBroken) return;
    this.#writing = this.#writeAll().finally(() => {
      this.#writing = null;
      if (this.#queue.length && !this.#writerBroken) this.#startWriter();
    });
  }
  async #writeAll() {
    try {
      while (this.#queue.length) {
        const entry = this.#queue[0];
        await this.#writer.write(entry.segment, entry.bytes);
        this.#queue.shift(); this.#queuedBytes -= entry.bytes.length; this.#writtenThrough = entry.seqNo;
      }
    } catch (error) { this.#writerBroken = true; this.#fail(error); }
  }
  #fail(error) {
    if (this.#failure) return;
    this.#failure = Error(String(error?.message ?? 'trace_failed').slice(0, 256));
    this.record('trace.gap', { reason: this.#failure.message, writtenThrough: this.#writtenThrough }, { cleanup: true });
    for (const listener of this.#listeners) this.#notify(listener);
  }
  #notify(listener) {
    queueMicrotask(() => { try { Promise.resolve(listener(this.#failure)).catch(() => {}); } catch { /* Preserve trace failure. */ } });
  }
}
