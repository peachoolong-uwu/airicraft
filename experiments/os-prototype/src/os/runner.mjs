import { executionPolicy as policy } from './execution-policy.mjs';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { copyMessage } from './value.mjs';
import { guestResult } from './guest-effects.mjs';
import { FrameDecoder, frame } from './runner-wire.mjs';

/** A credential-free child process with bounded framing and an independent response watchdog. */
export class RootRunner {
  #child;
  #queue = [];
  #slots = 0;
  #active = false;
  #requestId = 0;
  #pending = null;
  #failure = null;
  #closing = false;
  #onFailure;
  #ready;
  #resolveReady;
  #rejectReady;
  #exited;
  #timer;
  #stderrBytes = 0;

  static async start(options = {}) {
    const runner = new RootRunner(options);
    try { await runner.#ready; return runner; }
    catch (error) { await runner.close(); throw error; }
  }
  constructor({ onFailure = () => {} } = {}) {
    if (process.version !== policy.nodeVersion) throw Error('runner_node_version_mismatch');
    this.#onFailure = onFailure;
    this.#ready = new Promise((resolve, reject) => { this.#resolveReady = resolve; this.#rejectReady = reject; });
    this.#child = spawn(process.execPath, [fileURLToPath(new URL('./runner-main.mjs', import.meta.url))], {
      env: { LANG: 'C.UTF-8', TZ: 'UTC' }, stdio: ['pipe', 'pipe', 'pipe']
    });
    this.#exited = new Promise(resolve => this.#child.once('close', (code, signal) => {
      if (!this.#closing && !this.#failure) this.#fail(Error(`runner_exited:${signal ?? code}`));
      resolve();
    }));
    const decoder = new FrameDecoder(message => this.#response(message));
    this.#child.stdout.on('data', chunk => { try { decoder.push(chunk); } catch (error) { this.#fail(error); } });
    this.#child.stderr.on('data', chunk => {
      this.#stderrBytes += chunk.length;
      if (this.#stderrBytes > policy.stderrBytes) this.#fail(Error('runner_stderr_limit'));
    });
    this.#child.on('error', error => this.#fail(error));
    this.#child.stdin.on('error', error => this.#fail(error));
    this.#timer = setTimeout(() => this.#fail(Error('runner_watchdog')), policy.responseMillis);
  }
  get pid() { return this.#child.pid; }
  ready() { return this.#ready; }
  state() { return { pid: this.pid, queuedMessages: this.#slots, failed: this.#failure?.message ?? null, closing: this.#closing }; }
  create(invocation, spec) {
    let source, begin;
    try {
      if (typeof spec.source !== 'string' || Buffer.byteLength(spec.source) > policy.sourceBytes) throw Error('guest_source_limit');
      source = Buffer.from(spec.source);
      begin = { invocation, command: 'begin', definition: spec.definition, input: copyMessage(spec.input ?? null), mode: spec.mode ?? 'generator',
        sourceBytes: source.length, sourceHash: createHash('sha256').update(source).digest('hex') };
      frame({ requestId: 1, ...begin });
    } catch (error) { return Promise.reject(error); }
    return this.#enqueue(2 + Math.ceil(source.length / policy.sourceChunkBytes), async () => {
      await this.#send(begin);
      for (let offset = 0; offset < source.length; offset += policy.sourceChunkBytes)
        await this.#send({ invocation, command: 'chunk', offset, bytes: source.subarray(offset, offset + policy.sourceChunkBytes).toString('base64') });
      return this.#send({ invocation, command: 'create' });
    });
  }
  resume(invocation, input = null) { return this.#evaluate(invocation, 'resume', 'generator', input); }
  offers(invocation, input) { return this.#evaluate(invocation, 'offers', 'offers', input); }
  dispose(invocation) { return this.#enqueue(1, () => this.#send({ invocation, command: 'dispose' }), true); }
  kill(reason = 'runner_killed') { this.#fail(Error(reason)); }
  async close() {
    if (!this.#closing) {
      this.#closing = true;
      this.#abort(Error('runner_closed'));
      this.#child.kill('SIGKILL');
    }
    await this.#exited;
  }
  #evaluate(invocation, command, mode, input) {
    let message;
    try { message = copyMessage({ invocation, command, input }); frame({ requestId: 1, ...message }); }
    catch (error) { return Promise.reject(error); }
    return this.#enqueue(1, async () => {
      const response = await this.#send(message);
      return { result: guestResult(response.result, mode), cpuMicros: response.cpuMicros };
    });
  }
  #enqueue(slots, operation, control = false) {
    if (this.#failure || this.#closing) return Promise.reject(this.#failure ?? Error('runner_closed'));
    if (this.#slots + slots > (control ? policy.queuedControls : policy.queuedControls - 1)) {
      this.#fail(Error('runner_queue_capacity'));
      return Promise.reject(this.#failure);
    }
    this.#slots += slots;
    const result = new Promise((resolve, reject) => {
      const job = { slots, operation, resolve, reject };
      if (control) this.#queue.unshift(job); else this.#queue.push(job);
    });
    this.#drain();
    return result;
  }
  async #drain() {
    if (this.#active) return;
    this.#active = true;
    try {
      while (this.#queue.length) {
        const job = this.#queue.shift();
        try { job.resolve(await job.operation()); }
        catch (error) { job.reject(error); }
        finally { this.#slots -= job.slots; }
      }
    } finally { this.#active = false; }
  }
  #send(message) {
    if (this.#failure || this.#closing) return Promise.reject(this.#failure ?? Error('runner_closed'));
    const requestId = this.#requestId + 1, bytes = frame({ requestId, ...message });
    this.#requestId = requestId;
    return new Promise((resolve, reject) => {
      this.#pending = { requestId, resolve, reject };
      this.#timer = setTimeout(() => this.#fail(Error('runner_watchdog')), policy.responseMillis);
      this.#child.stdin.write(bytes, error => { if (error) this.#fail(error); });
    });
  }
  #response(message) {
    if (this.#failure || this.#closing) return;
    if (this.#resolveReady) {
      if (message.hello !== 1 || message.pid !== this.pid || message.node !== policy.nodeVersion ||
          message.quickjs !== policy.quickjsVersion || Object.keys(message).length !== 4) throw Error('runner_handshake_failed');
      clearTimeout(this.#timer); this.#resolveReady(); this.#resolveReady = null; this.#rejectReady = null;
      return;
    }
    if (!this.#pending || message.requestId !== this.#pending.requestId || typeof message.ok !== 'boolean' ||
        !Number.isSafeInteger(message.cpuMicros) || message.cpuMicros < 0 || message.cpuMicros > 10_000_000 ||
        Object.keys(message).length !== 4 || !Object.hasOwn(message, message.ok ? 'result' : 'error')) throw Error('runner_protocol_error');
    if (!message.ok && (!message.error || typeof message.error.message !== 'string' || message.error.message.length > 512)) throw Error('runner_protocol_error');
    const pending = this.#pending; this.#pending = null; clearTimeout(this.#timer);
    if (message.ok) pending.resolve(message);
    else {
      pending.reject(Object.assign(Error(message.error.message), { detail: message.error, cpuMicros: message.cpuMicros }));
    }
  }
  #abort(error) {
    clearTimeout(this.#timer); this.#rejectReady?.(error); this.#rejectReady = null;
    this.#pending?.reject(error); this.#pending = null;
    for (const job of this.#queue.splice(0)) { this.#slots -= job.slots; job.reject(error); }
  }
  #fail(error) {
    if (this.#failure || this.#closing) return;
    this.#failure = error;
    this.#abort(error); this.#child.kill('SIGKILL');
    queueMicrotask(() => { try { Promise.resolve(this.#onFailure(error)).catch(() => {}); } catch { /* Root remains failed. */ } });
  }
}
