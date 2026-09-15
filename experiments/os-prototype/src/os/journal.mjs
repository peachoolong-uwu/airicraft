import { Worker } from 'node:worker_threads';
import { copyMessage } from './value.mjs';
import { validNativeId } from './native-id.mjs';

const identity = value => typeof value === 'string' && value.length > 0 && value.length <= 256;

/** Durable native intent, on a dedicated writer so fsync cannot stall lease heartbeats. */
export class EffectJournal {
  #worker;
  #pending = new Map();
  #sequence = 0;
  #failure = null;
  #closed = false;

  static async open(path) {
    const journal = new EffectJournal(path);
    try { await journal.#request('ready'); return journal; }
    catch (error) { await journal.close(); throw error; }
  }
  constructor(path) {
    // The file worker must not inherit entry-point flags such as --input-type or --inspect-brk.
    this.#worker = new Worker(new URL('./journal-writer.mjs', import.meta.url), { workerData: { path }, execArgv: [] });
    this.#worker.on('message', ({ sequence, result, error }) => {
      const pending = this.#pending.get(sequence);
      if (!pending) return;
      clearTimeout(pending.timer);
      this.#pending.delete(sequence);
      error ? pending.reject(Error(error)) : pending.resolve(result);
    });
    this.#worker.on('error', error => this.#fail(error));
    this.#worker.on('exit', code => { if (!this.#closed) this.#fail(Error(`journal_writer_exited:${code}`)); });
  }
  async record(intent) {
    intent = copyMessage(intent);
    if (!intent || !validNativeId(intent.id) || !/^[a-f0-9]{64}$/.test(intent.payloadHash) || !identity(intent.definition) ||
      !identity(intent.invocation) || !intent.request || typeof intent.request !== 'object' || Array.isArray(intent.request)) throw Error('invalid_journal_intent');
    return this.#request('record', intent);
  }
  async inspect(id) {
    id = copyMessage(id);
    if (!validNativeId(id)) throw Error('invalid_journal_intent');
    return this.#request('inspect', id);
  }
  unfinished() { return this.#request('unfinished'); }
  recent() { return this.#request('recent'); }
  async settle(id, receipt) {
    id = copyMessage(id); receipt = copyMessage(receipt);
    if (!validNativeId(id)) throw Error('invalid_journal_intent');
    if (!receipt || typeof receipt.released !== 'boolean' || typeof receipt.accountingComplete !== 'boolean') throw Error('invalid_release_evidence');
    return this.#request('settle', { id, receipt });
  }
  async close() {
    if (this.#closed) return;
    this.#closed = true;
    if (!this.#failure) {
      try { await this.#request('close'); }
      catch { /* The caller's pending operations retain their failure. */ }
    }
    await this.#worker.terminate();
  }
  #request(operation, payload = null) {
    if (this.#failure) return Promise.reject(this.#failure);
    if (this.#closed && operation !== 'close') return Promise.reject(Error('journal_closed'));
    if (this.#pending.size >= 16) return Promise.reject(Error('journal_queue_capacity'));
    const sequence = ++this.#sequence;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.#fail(Error('journal_writer_timeout')); void this.#worker.terminate(); }, 5000);
      this.#pending.set(sequence, { resolve, reject, timer });
      this.#worker.postMessage({ sequence, operation, payload });
    });
  }
  #fail(error) {
    this.#failure ??= error;
    for (const pending of this.#pending.values()) { clearTimeout(pending.timer); pending.reject(this.#failure); }
    this.#pending.clear();
  }
}
