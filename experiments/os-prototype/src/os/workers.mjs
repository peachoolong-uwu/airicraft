import { copyMessage } from './value.mjs';
import { compileContract } from './contracts.mjs';
import { contentDigest } from './content.mjs';
import { HttpWorkers } from './worker-http.mjs';
import { workerPolicy as policy } from './worker-policy.mjs';

const providerFailures = new Set(['worker_unconfigured', 'worker_timeout', 'worker_cancelled', 'worker_provider_failure',
  'worker_transport_failure', 'worker_response_limit', 'worker_malformed', 'worker_invalid_output', 'worker_token_limit']);

/** Owned stateless calls. Worker results are interpretations, never action authority. */
export class WorkerService {
  #host; #invocations; #runners; #transport; #observations; #now; #trace;
  #requests = new Map(); #history = new Map(); #sequence = 0; #closed = false;
  #jobs = new Map(); #active = new Set(); #cache = new Map(); #calls = 0;
  #started; #timer; #charged = 0; #unknown = 0;
  #failures = new Map(); #faultReason = null; #unlisten;
  constructor({ installations, invocations, runners, transport = new HttpWorkers(), observations, now = () => performance.now(), trace }) {
    this.#host = installations; this.#invocations = invocations; this.#runners = runners; this.#transport = transport;
    this.#observations = observations; this.#now = now; this.#trace = trace;
    this.#started = now();
    this.#timer = setInterval(() => this.poll(), 50); this.#timer.unref();
    this.#unlisten = trace?.onFailure(error => this.#fault(error));
  }
  request(owner, sequence, alias, input) {
    this.poll();
    if (this.#closed) throw Error('workers_closed');
    if (!Number.isSafeInteger(sequence) || sequence < 1) throw Error('invalid_worker_request');
    const worker = this.#host.workerDefinition(owner, alias);
    input = compileContract(worker.definition.inputContract)(copyMessage(input, policy.inputBytes));
    const digest = contentDigest({ definition: worker.digest, input }, 32768);
    const previous = [...this.#requests.values()].find(call => call.owner === owner);
    if (previous) {
      if (previous.sequence === sequence && previous.digest === digest) return previous.id;
      throw Error(previous.sequence === sequence ? 'worker_request_conflict' : 'worker_outstanding');
    }
    if (sequence <= (this.#history.get(owner) ?? 0)) throw Error('worker_request_retired');
    const basis = this.#basis(owner);
    if (!basis) throw Error('worker_evidence_unavailable');
    const fingerprint = this.#fingerprint(worker, input);
    if (this.#sequence === Number.MAX_SAFE_INTEGER) throw Error('worker_id_exhausted');
    const handle = this.#invocations.spawn(owner, { definition: worker.digest, grants: [], failurePolicy: 'collect_all' });
    const call = { id: `worker:${++this.#sequence}`, owner, sequence, digest, worker, input, basis, handle, phase: 'waiting', created: this.#now() };
    this.#requests.set(call.id, call); this.#history.set(owner, sequence);
    this.#trace?.record('worker.requested', { id: call.id, owner, definition: worker.digest, inputDigest: contentDigest(input), basis });
    const profile = this.#transport.profile(worker.definition.profile);
    if (!profile) this.#startFallback(call, 'worker_unconfigured');
    else {
      const key = contentDigest({ digest, epoch: basis.epoch, basis: basis.signature, profile: profile.identity });
      const reconsideration = fingerprint === null ? null : contentDigest({ definition: worker.digest, fingerprint, epoch: basis.epoch,
        basis: basis.signature, profile: profile.identity });
      const cached = this.#cache.get(key);
      const shared = this.#jobs.get(key);
      if (shared) { shared.subscribers.add(call.id); call.job = shared; }
      else if (this.#cooling(key, basis) || this.#cooling(reconsideration, basis) || reconsideration !== null &&
          [...this.#jobs.values()].some(job => job.reconsideration === reconsideration)) this.#startFallback(call, 'worker_reconsider_later');
      else if (cached) {
        this.#rememberKey(reconsideration, basis.progress);
        this.#finish(call, { status: 'success', value: cached.value, model: cached.model, source: 'cache', computationId: cached.id });
      }
      else {
        if (this.state().queued >= policy.queued) this.#startFallback(call, 'worker_queue_full');
        else {
          const job = { key, reconsideration, id: call.id, subscribers: new Set([call.id]), controller: new AbortController(), worker, input, profile, basis,
            created: this.#now(), phase: 'queued' };
          this.#jobs.set(key, job); call.job = job;
          this.#drain();
        }
      }
    }
    return call.id;
  }
  take(owner, id) {
    this.poll();
    const call = this.#requests.get(id);
    if (!call || call.owner !== owner) throw Error('worker_request_unknown');
    const outcome = this.#invocations.join(owner, call.handle);
    if (outcome.status === 'pending') return outcome;
    this.#requests.delete(id);
    if (outcome.status !== 'success') return { status: 'cancelled', reason: outcome.cause?.reason ?? 'worker_cancelled' };
    if (['success', 'fallback'].includes(outcome.value.status) && !this.#sameBasis(call)) {
      this.#trace?.record('worker.discarded', { requestId: id, reason: 'worker_evidence_changed' }, { cleanup: true });
      return { status: 'stale', reason: 'worker_evidence_changed', evidence: outcome.value.evidence };
    }
    return outcome.value;
  }
  poll() {
    try { this.#poll(); } catch (error) { this.#fault(error); }
  }
  #poll() {
    if (this.#closed) return;
    for (const [id, call] of this.#requests) {
      if (!this.#running(call.owner)) {
        if (this.#running(call.handle.id)) this.#invocations.cancel(call.handle.id, 'worker_owner_closed');
        this.#detach(call);
        this.#requests.delete(id);
      } else if (call.phase === 'waiting' && !this.#sameBasis(call)) {
        this.#detach(call);
        this.#finish(call, { status: 'stale', reason: 'worker_evidence_changed' });
      }
    }
    for (const owner of this.#history.keys()) if (!this.#running(owner)) this.#history.delete(owner);
    for (const job of [...this.#jobs.values()]) {
      if (this.#now() - this.#started >= policy.runMillis) this.#abandon(job, 'worker_run_expired');
      else if (job.phase === 'queued' && this.#now() - job.created >= policy.queueMillis) this.#abandon(job, 'worker_queue_timeout');
      else if (job.phase === 'active' && this.#now() - job.started >= policy.inferenceMillis) this.#abandon(job, 'worker_timeout');
    }
    this.#drain();
  }
  close() {
    if (this.#closed) return;
    this.#closed = true;
    clearInterval(this.#timer); this.#unlisten?.();
    for (const job of this.#active) { this.#account(job, null); job.controller.abort(); }
    this.#jobs.clear(); this.#cache.clear(); this.#failures.clear();
    for (const call of this.#requests.values()) if (this.#running(call.handle.id)) this.#invocations.cancel(call.handle.id, 'workers_closed');
    this.#requests.clear(); this.#history.clear();
  }
  state() { return { closed: this.#closed, fault: this.#faultReason, requests: this.#requests.size, calls: this.#calls, active: this.#active.size,
    queued: [...this.#jobs.values()].filter(job => job.phase === 'queued').length, cached: this.#cache.size,
    outputTokensCharged: this.#charged, unknownUsage: this.#unknown }; }
  #drain() {
    if (this.#closed) return;
    try {
      for (const job of this.#jobs.values()) {
        if (job.phase !== 'queued') continue;
        if (this.#now() - this.#started >= policy.runMillis) { this.#abandon(job, 'worker_run_expired'); continue; }
        if (this.#calls >= policy.calls) { this.#abandon(job, 'worker_call_budget'); continue; }
        if (this.#active.size >= policy.active) continue;
        // Complete fallible admission setup before consuming a provider slot or budget.
        this.#trace?.record('worker.started', { computationId: job.id, definition: job.worker.digest, profile: job.worker.definition.profile,
          model: job.profile.model, reservedOutputTokens: policy.outputTokens, calls: this.#calls + 1, policy: policy.version });
        this.#rememberKey(job.reconsideration, job.basis.progress);
        job.phase = 'active'; job.started = this.#now();
        this.#active.add(job); this.#calls++; this.#charged += policy.outputTokens;
        void this.#compute(job).catch(error => this.#fault(error));
      }
    } catch (error) { this.#fault(error); }
  }
  #account(job, usage) {
    if (job.accounted) return;
    job.accounted = true;
    if (!usage || !Number.isSafeInteger(usage.outputTokens) || usage.outputTokens < 0 || usage.outputTokens > policy.outputTokens) this.#unknown++;
    else this.#charged -= policy.outputTokens - usage.outputTokens;
  }
  #abandon(job, reason) {
    if (this.#jobs.get(job.key) === job) this.#jobs.delete(job.key);
    if (job.phase === 'active') { this.#account(job, null); this.#rememberFailure(job); }
    job.phase = 'abandoned'; job.controller.abort();
    for (const id of job.subscribers) {
      const call = this.#requests.get(id);
      if (call) { delete call.job; this.#startFallback(call, reason); }
    }
    job.subscribers.clear();
  }
  #rememberFailure(job) {
    const owner = [...job.subscribers].map(id => this.#requests.get(id)?.owner).find(id => id && this.#running(id));
    const progress = (owner ? this.#basis(owner) : job.basis)?.progress ?? null;
    this.#rememberKey(job.key, progress);
    this.#rememberKey(job.reconsideration, progress);
  }
  #rememberKey(key, progress) {
    if (key === null) return;
    this.#failures.set(key, { progress });
    if (this.#failures.size > policy.cache) this.#failures.delete(this.#failures.keys().next().value);
  }
  #cooling(key, basis) {
    const failure = this.#failures.get(key);
    if (!failure) return false;
    const before = failure.progress, after = basis.progress;
    return !before || !after || before.clockId !== after.clockId || after.eligibleTicks - before.eligibleTicks < policy.reconsiderTicks;
  }
  #detach(call) {
    const job = call.job;
    if (!job) return;
    job.subscribers.delete(call.id); delete call.job;
    if (!job.subscribers.size) this.#abandon(job, 'worker_cancelled');
  }
  async #compute(job) {
    let validationStarted = null;
    try {
      const { definition } = job.worker;
      const response = await this.#transport.complete(definition.profile, { prompt: definition.prompt, input: job.input,
        outputContract: definition.outputContract }, job.controller.signal);
      validationStarted = this.#now();
      const value = copyMessage(compileContract(definition.outputContract)(response.value), policy.outputBytes);
      this.poll();
      const usage = this.#usage(response.usage), model = typeof response.model === 'string' && response.model.length <= 256 ? response.model : job.profile.model;
      this.#settled(job, { outcome: 'success', usage, model, outputDigest: contentDigest(value) }, response.latency, validationStarted);
      if (job.controller.signal.aborted || this.#closed) {
        return;
      }
      this.#account(job, usage);
      this.#rememberKey(job.reconsideration, job.basis.progress);
      this.#cache.set(job.key, { id: job.id, value, model });
      if (this.#cache.size > policy.cache) this.#cache.delete(this.#cache.keys().next().value);
      for (const id of job.subscribers) {
        const call = this.#requests.get(id); delete call.job;
        this.#finish(call, { status: 'success', value, model, source: 'provider', computationId: job.id });
      }
    } catch (error) {
      this.poll();
      this.#account(job, null);
      const reason = providerFailures.has(error.message) ? error.message : validationStarted !== null ? 'worker_invalid_output' : 'worker_transport_failure';
      this.#settled(job, { outcome: 'failure', reason, usage: null, model: job.profile.model }, error.latency, validationStarted);
      if (!this.#closed && !job.controller.signal.aborted) {
        this.#rememberFailure(job);
        for (const id of job.subscribers) {
          const call = this.#requests.get(id);
          if (call) { delete call.job; this.#startFallback(call, reason); }
        }
      }
    } finally {
      this.#active.delete(job);
      if (this.#jobs.get(job.key) === job) this.#jobs.delete(job.key);
      this.#drain();
    }
  }
  #usage(value) {
    return value && ['inputTokens', 'outputTokens', 'totalTokens'].every(key => Number.isSafeInteger(value[key]) && value[key] >= 0) &&
      value.totalTokens === value.inputTokens + value.outputTokens && value.outputTokens <= policy.outputTokens
      ? { inputTokens: value.inputTokens, outputTokens: value.outputTokens, totalTokens: value.totalTokens } : null;
  }
  #fingerprint(worker, input) {
    const path = worker.definition.reconsideration?.fingerprint;
    if (!path) return null;
    let value = input;
    for (const field of path) {
      if (value === null || typeof value !== 'object' || !Object.hasOwn(value, field)) throw Error('invalid_worker_fingerprint');
      value = value[field];
    }
    if (typeof value !== 'string' || !value.length || value.length > 256) throw Error('invalid_worker_fingerprint');
    return value;
  }
  #settled(job, outcome, timing, validationStarted) {
    const valid = timing && ['providerMillis', 'validationMillis'].every(key => Number.isFinite(timing[key]) && timing[key] >= 0);
    const validationMillis = validationStarted === null ? 0 : this.#now() - validationStarted;
    this.#trace?.record('worker.provider_settled', { computationId: job.id, ...outcome, late: this.#closed || job.controller.signal.aborted,
      latency: { queueMillis: job.started - job.created, providerMillis: valid ? timing.providerMillis : (validationStarted ?? this.#now()) - job.started,
        validationMillis: (valid ? timing.validationMillis : 0) + validationMillis } }, { cleanup: true });
  }
  #basis(owner) {
    const basis = this.#observations?.workerEvidence(owner);
    if (!basis) return null;
    const copy = copyMessage(basis, 4096);
    if (typeof copy.epoch !== 'string' || !copy.epoch.length || typeof copy.signature !== 'string' || !copy.signature.length ||
        !Array.isArray(copy.captures) || copy.captures.length > 32 || copy.captures.some(value => typeof value !== 'string' || value.length > 256) ||
        copy.progress !== null && (!copy.progress || typeof copy.progress.clockId !== 'string' || !copy.progress.clockId.length ||
          !Number.isSafeInteger(copy.progress.eligibleTicks) || copy.progress.eligibleTicks < 0)) throw Error('invalid_worker_evidence');
    return copy;
  }
  #current(call) {
    if (this.#closed || this.#requests.get(call.id) !== call || !this.#running(call.owner) || !this.#running(call.handle.id)) return false;
    return this.#sameBasis(call);
  }
  #sameBasis(call) {
    const basis = this.#basis(call.owner);
    const same = basis?.epoch === call.basis.epoch && basis.signature === call.basis.signature;
    if (same && call.job) call.job.basis = basis;
    return same;
  }
  #startFallback(call, reason) { void this.#fallback(call, reason).catch(error => this.#fault(error)); }
  async #fallback(call, reason) {
    call.phase = 'fallback';
    let handle;
    try {
      if (!this.#current(call)) return this.#finish(call, { status: 'stale', reason: 'worker_evidence_changed' });
      const { digest, definition } = call.worker.fallback;
      handle = this.#invocations.spawn(call.handle.id, { definition: digest, grants: [], outputContract: definition.outputContract });
      await this.#runners.create(handle.id, { definition: digest, source: definition.source, mode: 'generator', input: call.input });
      const response = await this.#runners.resume(handle.id);
      if (!response.result.done) throw Error('worker_fallback_yielded');
      const outcome = this.#invocations.join(call.handle.id, handle); handle = null;
      if (outcome.status !== 'success') throw Error('worker_fallback_failed');
      const value = copyMessage(compileContract(call.worker.definition.outputContract)(outcome.value), policy.outputBytes);
      this.#finish(call, { status: 'fallback', reason, value });
    } catch {
      this.#finish(call, { status: 'unavailable', reason: 'worker_fallback_failed', cause: reason });
    } finally {
      if (handle) {
        try { this.#runners.cancel(handle.id, 'worker_fallback_finished'); this.#invocations.join(call.handle.id, handle); } catch { /* Parent cancellation can consume the child. */ }
      }
    }
  }
  #finish(call, outcome) {
    if (this.#closed || this.#requests.get(call.id) !== call || call.phase === 'finished' || !this.#running(call.handle.id)) return;
    if (!this.#current(call)) outcome = { status: 'stale', reason: 'worker_evidence_changed' };
    call.phase = 'finished';
    const result = { ...outcome, evidence: { interpretation: true, epoch: call.basis.epoch, basis: call.basis.signature, captures: call.basis.captures,
      definition: call.worker.digest, profile: call.worker.definition.profile, model: outcome.model ?? null,
      inputDigest: contentDigest(call.input), requestId: call.id } };
    this.#trace?.record('worker.result', { owner: call.owner, result }, { cleanup: true });
    this.#invocations.returned(call.handle.id, result);
  }
  #running(id) {
    try { return this.#invocations.execution(id).phase === 'running'; }
    catch (error) { if (error.message !== 'invocation_unknown') throw error; return false; }
  }
  #fault(error) {
    if (this.#closed) return;
    this.#faultReason = String(error.message ?? 'worker_service_fault').slice(0, 256);
    const owners = new Set([...this.#requests.values()].map(call => call.owner));
    this.close();
    for (const owner of owners) if (this.#running(owner)) {
      try { this.#runners.fail(owner, 'worker_service_fault'); }
      catch { this.#invocations.failed(owner, 'worker_service_fault'); }
    }
    this.#trace?.record('worker.fault', { reason: this.#faultReason }, { cleanup: true });
  }
}
