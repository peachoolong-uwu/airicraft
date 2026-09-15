import { executionPolicy as policy } from './execution-policy.mjs';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { copyMessage } from './value.mjs';
import { RootRunner } from './runner.mjs';
import { RootBudget } from './runner-budget.mjs';

const exec = promisify(execFile);
async function residentBytes(pids) {
  const { stdout } = await exec('/bin/ps', ['-o', 'pid=,rss=', '-p', pids.join(',')], { timeout: policy.rssReadMillis, maxBuffer: policy.messageBytes });
  return new Map(stdout.trim().split('\n').filter(Boolean).map(line => {
    const [pid, kib] = line.trim().split(/\s+/).map(Number);
    if (!Number.isSafeInteger(pid) || !Number.isSafeInteger(kib) || kib < 0) throw Error('runner_rss_invalid');
    return [pid, kib * 1024];
  }));
}

/** Root-bound compute supervision. Physical ownership remains in InvocationBroker. */
export class RunnerPool {
  #invocations;
  #trace;
  #ownershipChanged;
  #roots = new Map();
  #pending = new Set();
  #cursor = 0;
  #timer = null;
  #scheduledAt = Infinity;
  #monitor;
  #sampling = null;
  #readRss;
  #closed = false;

  constructor({ invocations, trace, onOwnershipChanged = () => {}, readRss = residentBytes }) {
    this.#invocations = invocations; this.#trace = trace; this.#ownershipChanged = onOwnershipChanged; this.#readRss = readRss;
    this.#monitor = setInterval(() => this.sample(), policy.rssSampleMillis); this.#monitor.unref();
    trace?.onFailure(error => { for (const root of this.#roots.values()) this.#failRoot(root, error); });
  }
  async open(rootId) {
    if (this.#closed) throw Error('runners_closed');
    this.#trace?.assertHealthy();
    const owner = this.#invocations.execution(rootId);
    if (owner.parentId !== null || owner.phase !== 'running' || this.#roots.has(rootId)) throw Error('invalid_runner_root');
    if (this.#roots.size >= policy.roots) throw Error('runner_root_capacity');
    const root = { id: rootId, runner: null, ready: false, failed: null, queue: [], active: false, guests: new Set(), budget: new RootBudget() };
    root.runner = new RootRunner({ onFailure: error => this.#failRoot(root, error) });
    this.#roots.set(rootId, root);
    try {
      await root.runner.ready();
      if (this.#closed) throw Error('runners_closed');
      if (root.failed) throw root.failed;
      root.ready = true;
      this.#trace?.record('runner.started', { rootId, pid: root.runner.pid });
      return this.state(rootId);
    } catch (error) { this.#failRoot(root, error); throw error; }
  }
  create(id, spec) {
    const owner = this.#invocations.execution(id), root = this.#get(owner.rootId);
    if (owner.phase !== 'running') return Promise.reject(Error('invocation_closing'));
    if (this.#closed || root.failed || !root.ready) return Promise.reject(root.failed ?? Error('runner_not_ready'));
    if (owner.definition !== spec.definition || root.guests.has(id)) return Promise.reject(Error('runner_definition_mismatch'));
    if (typeof spec.source !== 'string' || Buffer.byteLength(spec.source) > policy.sourceBytes) return Promise.reject(Error('guest_source_limit'));
    const approved = { source: spec.source, definition: spec.definition, mode: spec.mode ?? 'generator', input: copyMessage(spec.input ?? null) };
    root.guests.add(id);
    return this.#enqueue(root, id, 'create', policy.initializationCpuMicros, () => root.runner.create(id, approved));
  }
  resume(id, input = null) { return this.#evaluation(id, 'resume', input); }
  offers(id, input) { return this.#evaluation(id, 'offers', input); }
  cancel(id, reason = 'cancelled') { this.#stop(id, reason, false); }
  fail(id, reason) { this.#stop(id, reason, true); }
  #stop(id, reason, failed) {
    const { rootId } = this.#invocations.execution(id);
    if (failed) this.#invocations.failed(id, reason);
    else this.#invocations.cancel(id, reason);
    this.syncOwnership(rootId);
  }
  syncOwnership(rootId) {
    const root = this.#roots.get(rootId);
    if (!root) return false; // Retirement can precede reconciliation of its durable metadata reply.
    this.#sweep(root); this.#changed(rootId);
    if (this.#invocations.execution(rootId).phase === 'stopping') { root.ready = false; root.runner.close().catch(() => {}); }
    this.#schedule();
    return true;
  }
  async retire(rootId) {
    const root = this.#get(rootId);
    if (!this.#invocations.inspect(rootId).outcome) throw Error('invocation_unsettled');
    root.ready = false; this.#rejectQueued(root, Error('runner_retired'));
    await root.runner.close(); this.#roots.delete(rootId);
  }
  state(rootId) {
    const root = this.#get(rootId);
    return { rootId, pid: root.runner.pid, ready: root.ready, failed: root.failed?.message ?? null,
      queued: root.queue.length, active: root.active, guests: root.guests.size, budget: root.budget.state() };
  }
  sample() {
    if (this.#closed) return Promise.resolve();
    if (this.#sampling) return this.#sampling;
    this.#sampling = this.#sample().finally(() => { this.#sampling = null; });
    return this.#sampling;
  }
  async #sample() {
    const roots = [...this.#roots.values()].filter(root => !root.failed && !root.runner.state().closing);
    if (!roots.length) return;
    const started = performance.now();
    try {
      const measured = await this.#readRss(roots.map(root => root.runner.pid));
      if (this.#closed) return;
      let total = 0;
      const readings = [];
      for (const root of roots) {
        if (root.failed || root.runner.state().closing) continue;
        const bytes = measured.get(root.runner.pid);
        if (!Number.isSafeInteger(bytes) || bytes < 0) throw Error('runner_rss_unavailable');
        readings.push({ rootId: root.id, pid: root.runner.pid, bytes });
        if (bytes > policy.rootRssBytes) this.#failRoot(root, Error('runner_rss_limit'));
        else total += bytes;
      }
      this.#trace?.record('runner.memory_sample', { readMillis: performance.now() - started, readings });
      if (total > policy.groupRssBytes) for (const root of roots) this.#failRoot(root, Error('runner_group_rss_limit'));
    } catch (error) {
      if (this.#closed) return;
      this.#trace?.record('runner.memory_unavailable', { readMillis: performance.now() - started,
        reason: String(error.message ?? 'unavailable').slice(0, 512), code: String(error.code ?? 'unavailable').slice(0, 80),
        signal: String(error.signal ?? '').slice(0, 80) }, { cleanup: true });
      for (const root of roots) this.#failRoot(root, Error('runner_rss_unavailable'));
    }
  }
  async close() {
    this.#closed = true; clearInterval(this.#monitor); clearTimeout(this.#timer);
    const closing = [];
    for (const root of this.#roots.values()) {
      try { this.#invocations.cancel(root.id, 'runner_pool_closed'); } catch { /* Already consumed root. */ }
      root.ready = false; this.#rejectQueued(root, Error('runners_closed')); this.#changed(root.id);
      closing.push(root.runner.close());
    }
    await Promise.all(closing); await this.#sampling;
  }
  #get(rootId) {
    const root = this.#roots.get(rootId);
    if (!root) throw Error('runner_root_unknown');
    return root;
  }
  #evaluation(id, command, input) {
    const owner = this.#invocations.execution(id), root = this.#get(owner.rootId);
    if (owner.phase !== 'running') return Promise.reject(Error('invocation_closing'));
    if (!root.guests.has(id)) return Promise.reject(Error('runner_invocation_unknown'));
    input = copyMessage(input);
    return this.#enqueue(root, id, command, policy.resumeCpuMicros, () => root.runner[command](id, input));
  }
  #enqueue(root, id, command, allowance, operation) {
    if (this.#closed || root.failed || !root.ready) return Promise.reject(root.failed ?? Error('runner_not_ready'));
    if (this.#pending.has(id)) return Promise.reject(Error('runner_invocation_busy'));
    if (root.queue.length >= policy.queuedControls) { this.#failRoot(root, Error('runner_queue_capacity')); return Promise.reject(root.failed); }
    this.#pending.add(id);
    const promise = new Promise((resolve, reject) => root.queue.push({ id, command, allowance, operation, resolve, reject, waited: false }));
    this.#schedule();
    return promise;
  }
  #schedule(delay = 0) {
    if (this.#closed) return;
    const at = performance.now() + delay;
    if (this.#timer && at >= this.#scheduledAt) return;
    clearTimeout(this.#timer); this.#scheduledAt = at;
    this.#timer = setTimeout(() => {
      this.#timer = null; this.#scheduledAt = Infinity;
      try { this.#dispatch(); } catch (error) { for (const root of this.#roots.values()) this.#failRoot(root, error); }
    }, delay);
  }
  #dispatch() {
    const roots = [...this.#roots.values()], start = this.#cursor;
    let delay = Infinity;
    for (let offset = 0; offset < roots.length; offset++) {
      const index = (start + offset) % roots.length, root = roots[index];
      if (!root.ready || root.failed || root.active || !root.queue.length) continue;
      this.#sweep(root);
      const job = root.queue[0];
      if (!job) continue;
      const permit = root.budget.reserve(job.allowance, job.command !== 'create');
      if (!permit.admitted) {
        delay = Math.min(delay, permit.waitMillis);
        if (!job.waited) { this.#trace?.record('runner.budget_wait', { rootId: root.id, invocation: job.id, command: job.command, waitMillis: permit.waitMillis }); job.waited = true; }
        continue;
      }
      root.queue.shift(); root.active = true; this.#cursor = (index + 1) % roots.length;
      this.#run(root, job).catch(error => this.#failRoot(root, error));
    }
    if (Number.isFinite(delay)) this.#schedule(delay);
  }
  async #run(root, job) {
    let actual = job.allowance;
    try {
      this.#trace?.record('runner.dispatched', { rootId: root.id, invocation: job.id, command: job.command, reservedMicros: job.allowance });
      const result = await job.operation(); actual = result.cpuMicros;
      if (this.#invocations.execution(job.id).phase !== 'running') throw Error('invocation_closing');
      if (job.command === 'offers') result.result = this.#invocations.validateResult(job.id, result.result);
      if (job.command === 'resume' && result.result.done) this.#invocations.returned(job.id, result.result.value);
      this.#trace?.record('runner.completed', { rootId: root.id, invocation: job.id, command: job.command, cpuMicros: actual },
        { cleanup: job.command === 'resume' && result.result.done });
      job.resolve(result);
    } catch (error) {
      actual = error.cpuMicros ?? actual;
      try { this.#invocations.failed(job.id, String(error.message).slice(0, 256)); } catch { /* A sibling may already have consumed its handle. */ }
      this.#trace?.record('runner.invocation_failed', { rootId: root.id, invocation: job.id, reason: String(error.message).slice(0, 256) }, { cleanup: true });
      job.reject(error);
    } finally {
      root.budget.charge(job.allowance, actual); this.#pending.delete(job.id); root.active = false;
      this.#sweep(root); this.#changed(root.id); this.#schedule();
    }
  }
  #sweep(root) {
    for (const id of root.guests) {
      let running = false;
      try { running = this.#invocations.inspect(id).phase === 'running'; } catch { /* Joined or cancelled sibling. */ }
      if (!running) { root.guests.delete(id); root.runner.dispose(id).catch(() => {}); }
    }
    root.queue = root.queue.filter(job => {
      if (root.guests.has(job.id)) return true;
      this.#pending.delete(job.id); job.reject(Error('invocation_closing')); return false;
    });
    if (this.#invocations.inspect(root.id).outcome) {
      root.ready = false; this.#rejectQueued(root, Error('invocation_closing')); root.runner.close().catch(() => {});
    }
  }
  #rejectQueued(root, error) {
    for (const job of root.queue.splice(0)) { this.#pending.delete(job.id); job.reject(error); }
  }
  #failRoot(root, error) {
    if (root.failed || this.#closed) return;
    root.failed = error; root.ready = false;
    this.#rejectQueued(root, error);
    try { this.#invocations.failed(root.id, String(error.message).slice(0, 256)); } catch { /* Already consumed root. */ }
    root.runner.kill(error.message);
    this.#trace?.record('runner.root_failed', { rootId: root.id, pid: root.runner.pid, reason: String(error.message).slice(0, 256) }, { cleanup: true });
    this.#changed(root.id);
  }
  #changed(rootId) {
    try { Promise.resolve(this.#ownershipChanged(rootId)).catch(() => {}); } catch { /* Owners remain in the broker. */ }
  }
}
