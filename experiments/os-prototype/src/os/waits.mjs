import { copyMessage } from './value.mjs';
import { compileCondition } from './conditions.mjs';
import { ObservationFrames } from './observations.mjs';

const integer = value => Number.isSafeInteger(value) && value >= 0;

/** Owned level waits. No callback, refresh, or condition evaluation acquires physical ownership. */
export class ConditionWaits {
  #invocations;
  #grants;
  #frames;
  #now;
  #trace;
  #waits = new Map();
  #waitSequence = 0;
  #sequence = 0;
  #gapSequence = 0;

  constructor({ invocations, scopes, epoch, now = () => performance.now(), trace }) {
    scopes = copyMessage(scopes);
    if (!scopes || typeof scopes !== 'object' || Array.isArray(scopes) || Object.keys(scopes).length > 32 ||
        Object.entries(scopes).some(([scope, grant]) => !scope.length || scope.length > 128 || typeof grant !== 'string' || !grant.length || grant.length > 256)) throw Error('invalid_observation_scopes');
    this.#invocations = invocations; this.#grants = new Map(Object.entries(scopes)); this.#now = now; this.#trace = trace;
    this.#frames = new ObservationFrames({ epoch, scopes: [...this.#grants.keys()], now });
  }
  cursor() { return { epoch: this.#frames.epoch, sequence: this.#sequence }; }
  publish(frame) {
    const accepted = this.#frames.publish(frame);
    if (accepted) { this.#sequence++; this.poll(frame.scope); }
    return { accepted, cursor: this.cursor() };
  }
  setEpoch(epoch) {
    if (epoch === this.#frames.epoch) return;
    const oldEpoch = this.#frames.epoch;
    this.#frames.setEpoch(epoch); this.#sequence++;
    for (const wait of this.#waits.values()) if (!wait.outcome) this.#settle(wait, 'epoch_changed', { oldEpoch, newEpoch: epoch });
  }
  gap(reason) {
    if (typeof reason !== 'string' || !reason.length || reason.length > 256) throw Error('invalid_gap');
    this.#gapSequence = ++this.#sequence; this.#frames.invalidate();
    for (const wait of this.#waits.values()) if (!wait.outcome) this.#settle(wait, 'gap', { reason });
  }
  observe(owner, scopes, options = {}) {
    scopes = copyMessage(scopes);
    if (!Array.isArray(scopes) || !scopes.length || scopes.length > 32 || new Set(scopes).size !== scopes.length) throw Error('invalid_observation_scopes');
    this.#authorize(owner, scopes);
    options = copyMessage(options);
    const offset = options?.offset ?? 0;
    if (!options || Array.isArray(options) || typeof options !== 'object' || Object.keys(options).some(key => key !== 'offset') ||
        !integer(offset) || offset >= scopes.length) throw Error('invalid_observation_page');
    return copyMessage({ ...this.cursor(), scopes: this.#frames.observe([scopes[offset]]), nextOffset: offset + 1 < scopes.length ? offset + 1 : null });
  }
  /** A producer-selected view for a pure offer evaluation; ungranted scopes never cross the VM boundary. */
  grantedView(owner, scopes) {
    scopes = copyMessage(scopes);
    if (!Array.isArray(scopes) || scopes.length > 32 || new Set(scopes).size !== scopes.length) throw Error('invalid_observation_scopes');
    if (!this.#running(owner)) throw Error('invocation_closing');
    const granted = scopes.filter(scope => {
      if (!this.#grants.has(scope)) throw Error('observation_scope_unknown');
      try { this.#invocations.authorize(owner, this.#grants.get(scope)); return true; }
      catch (error) { if (error.message === 'operation_not_granted') return false; throw error; }
    });
    return copyMessage({ ...this.cursor(), scopes: this.#frames.observe(granted) }, 12_288, { maximumDepth: 10, maximumNodes: 1900 });
  }
  wait(owner, condition, options = {}) {
    options = copyMessage(options);
    if (!options || Object.keys(options).some(key => !['cursor', 'deadline'].includes(key))) throw Error('invalid_wait_options');
    const predicate = compileCondition(condition), scopes = [...predicate.scopes];
    const deadline = options.deadline ?? null;
    if (deadline !== null) {
      if (deadline.clock === 'wall') {
        if (Object.keys(deadline).length !== 2 || !integer(deadline.milliseconds) || deadline.milliseconds === 0) throw Error('invalid_wait_deadline');
      } else if (deadline.clock === 'eligible_ticks') {
        if (Object.keys(deadline).length !== 3 || !integer(deadline.ticks) || deadline.ticks === 0) throw Error('invalid_wait_deadline');
        if (!scopes.includes(deadline.scope)) scopes.push(deadline.scope);
      } else throw Error('invalid_wait_deadline');
    }
    if (scopes.length > 32) throw Error('condition_limit');
    this.#authorize(owner, scopes);
    const cursor = options.cursor ?? this.cursor();
    if (!cursor || Object.keys(cursor).length !== 2 || typeof cursor.epoch !== 'string' || !cursor.epoch.length || cursor.epoch.length > 256 || !integer(cursor.sequence) ||
        cursor.epoch === this.#frames.epoch && cursor.sequence > this.#sequence) throw Error('invalid_wait_cursor');
    if (this.#waits.size >= 256) throw Error('global_wait_capacity');
    if ([...this.#waits.values()].filter(wait => wait.owner === owner).length >= 32) throw Error('invocation_wait_capacity');
    if (this.#waitSequence === Number.MAX_SAFE_INTEGER) throw Error('wait_id_exhausted');
    const wait = { id: `wait:${++this.#waitSequence}`, owner, predicate, scopes, epoch: this.#frames.epoch, cursor: { ...cursor },
      startedAt: this.#now(), evaluatedAt: null, deadline, eligibleTicks: 0, progress: null, truth: 'unknown', outcome: null };
    this.#trace?.record('wait.created', { id: wait.id, owner, condition: predicate.digest, scopes, cursor, deadline });
    this.#waits.set(wait.id, wait);
    if (cursor.epoch !== this.#frames.epoch) this.#settle(wait, 'epoch_changed', { oldEpoch: cursor.epoch, newEpoch: this.#frames.epoch });
    else if (cursor.sequence < Math.max(0, this.#sequence - 512, this.#gapSequence)) this.#settle(wait, 'gap', { reason: 'cursor_expired' });
    else this.#evaluate(wait);
    return wait.id;
  }
  take(owner, id) {
    const wait = this.#get(owner, id);
    if (!this.#running(owner)) { this.#waits.delete(id); return { status: 'cancelled', id }; }
    if (!wait.outcome) return { status: 'pending' };
    this.#waits.delete(id);
    if (wait.epoch !== this.#frames.epoch) return { status: 'epoch_changed', id, oldEpoch: wait.epoch, newEpoch: this.#frames.epoch };
    return copyMessage(wait.outcome);
  }
  inspect(owner, id) {
    const wait = this.#get(owner, id);
    return copyMessage({ id, owner, epoch: wait.epoch, condition: wait.predicate.digest, scopes: wait.scopes, cursor: wait.cursor,
      truth: wait.truth, evaluatedAt: wait.evaluatedAt, eligibleTicks: wait.eligibleTicks, deadline: wait.deadline, status: wait.outcome?.status ?? 'pending' });
  }
  poll(changedScope = null) {
    for (const wait of this.#waits.values()) {
      if (!this.#running(wait.owner)) {
        this.#waits.delete(wait.id);
        this.#trace?.record('wait.cancelled', { id: wait.id, owner: wait.owner }, { cleanup: true });
      } else if (!wait.outcome && (changedScope === null || wait.scopes.includes(changedScope))) this.#evaluate(wait);
    }
  }
  state() { return { epoch: this.#frames.epoch, cursor: this.cursor(), waits: this.#waits.size, frames: this.#frames.size }; }
  #authorize(owner, scopes) {
    for (const scope of scopes) {
      if (!this.#grants.has(scope)) throw Error('observation_scope_unknown');
      this.#invocations.authorize(owner, this.#grants.get(scope));
    }
  }
  #get(owner, id) {
    const wait = this.#waits.get(id);
    if (!wait || wait.owner !== owner) throw Error('wait_unknown');
    return wait;
  }
  #running(owner) {
    try { return this.#invocations.execution(owner).phase === 'running'; }
    catch (error) { if (error.message === 'invocation_unknown') return false; throw error; }
  }
  #evaluate(wait) {
    const now = this.#now();
    wait.evaluatedAt = now;
    if (wait.deadline?.clock === 'eligible_ticks') {
      const progress = this.#frames.progress(wait.deadline.scope, now);
      if (progress.eligibleTicks !== null && wait.progress?.eligibleTicks !== null && wait.progress?.clockId === progress.clockId)
        wait.eligibleTicks = Math.min(wait.deadline.ticks, wait.eligibleTicks + Math.max(0, progress.eligibleTicks - wait.progress.eligibleTicks));
      wait.progress = progress;
    }
    wait.truth = wait.predicate.evaluate((scope, path) => this.#frames.read(scope, path, now));
    if (wait.truth === 'met') this.#settle(wait, 'met');
    else if (wait.deadline?.clock === 'wall' && now - wait.startedAt >= wait.deadline.milliseconds ||
      wait.deadline?.clock === 'eligible_ticks' && wait.eligibleTicks >= wait.deadline.ticks) this.#settle(wait, 'deadline');
  }
  #settle(wait, status, extra = {}) {
    if (wait.outcome) return;
    const outcome = copyMessage({ status, id: wait.id, epoch: wait.epoch, cursor: this.cursor(), basis: this.#frames.basis(wait.scopes), ...extra });
    this.#trace?.record('wait.completed', { owner: wait.owner, condition: wait.predicate.digest, outcome }, { cleanup: ['gap', 'epoch_changed'].includes(status) });
    wait.outcome = outcome;
  }
}
