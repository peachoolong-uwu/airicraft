import { copyMessage } from './value.mjs';
import { guestEffect } from './guest-effects.mjs';
import { frame } from './runner-wire.mjs';
import { contentDigest } from './content.mjs';
import { executionPolicy as policy } from './execution-policy.mjs';

const rejections = new Set([
  'operation_not_granted', 'observation_scope_unknown', 'invalid_observation_scopes', 'invalid_observation_page', 'invalid_observation_query',
  'invalid_wait_options', 'invalid_wait_deadline', 'invalid_wait_cursor', 'invalid_condition', 'condition_limit',
  'global_wait_capacity', 'invocation_wait_capacity', 'invalid_child_handle', 'already_joined', 'dependency_not_declared',
  'unsupported_definition_mode', 'invalid_spawn_options', 'capability_escalation', 'capability_missing', 'contract_mismatch',
  'child_result_capacity', 'live_invocation_capacity', 'invocation_depth', 'message_limit', 'resource_unknown',
  'supply_method_unavailable', 'invalid_target', 'invalid_demand', 'target_capacity', 'demand_capacity',
  'invocation_demand_capacity', 'demand_conflict', 'demand_retired', 'stale_observation',
  'invalid_work', 'operation_unknown', 'work_conflict', 'work_retired', 'work_capacity', 'invocation_work_capacity'
]);
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);

/** One pending evaluation per invocation. Passive waits and offer duties never own the player. */
export class BehaviorLoop {
  #host;
  #invocations;
  #runners;
  #waits;
  #resources;
  #work;
  #observations;
  #trace;
  #states = new Map();
  #roots = new Set();
  #jobs = new Set();
  #closed = false;

  constructor({ installations, invocations, runners, waits, resources, work, observations, trace }) {
    this.#host = installations; this.#invocations = invocations; this.#runners = runners; this.#waits = waits; this.#resources = resources; this.#work = work; this.#trace = trace;
    this.#observations = observations;
  }
  attach(id) {
    if (this.#closed) throw Error('execution_closed');
    this.#sweep();
    if (this.#states.has(id)) throw Error('execution_already_attached');
    if (this.#states.size >= policy.invocations) throw Error('execution_capacity');
    const owner = this.#invocations.execution(id), definition = this.#host.describe(id);
    this.#supported(definition);
    if (owner.parentId !== null && !this.#roots.has(owner.rootId)) throw Error('execution_root_not_attached');
    this.#trace?.record('execution.attached', { ...owner, digest: definition.digest });
    this.#roots.add(owner.rootId);
    this.#states.set(id, { id, rootId: owner.rootId, mode: definition.mode, phase: definition.mode === 'offers' ? 'offers_waiting' : 'ready', input: null, effect: null, sequence: 0 });
  }
  tick() {
    if (this.#closed) return;
    this.#sweep(); this.#pollPassive();
    for (const state of this.#states.values()) {
      try {
        if (!this.#running(state.id)) continue;
        if (state.phase === 'yielded') {
          const effect = state.yielded;
          delete state.yielded;
          this.#dispatch(state, effect);
        }
        if (['waiting', 'joining', 'delivery', 'working'].includes(state.phase)) this.#poll(state);
        if (this.#jobs.size < policy.invocations) {
          if (state.phase === 'ready') this.#resume(state);
          else if (state.phase === 'spawn_ready') this.#spawn(state);
          else if (state.phase === 'offers_waiting') this.#offers(state);
        }
      } catch (error) { this.#rejectOrFail(state, error); }
    }
  }
  cancel(id, reason = 'execution_cancelled') {
    const owner = this.#invocations.execution(id);
    if (!this.#roots.has(owner.rootId)) throw Error('execution_not_attached');
    if (owner.phase !== 'terminal') this.#runners.cancel(id, reason);
    this.#sweep(); this.#pollPassive();
  }
  close() {
    if (this.#closed) return;
    this.#closed = true;
    for (const id of this.#roots) {
      if (this.#exists(id) && this.#invocations.execution(id).phase !== 'terminal') this.#runners.cancel(id, 'execution_closed');
    }
    this.#states.clear(); this.#pollPassive();
  }
  state() {
    return { closed: this.#closed, jobs: this.#jobs.size, roots: [...this.#roots], invocations: [...this.#states.values()].map(state =>
      ({ id: state.id, rootId: state.rootId, phase: state.phase, effect: state.effect?.kind ?? null, sequence: state.sequence })) };
  }
  #resume(state) {
    const input = state.input;
    state.phase = 'resuming'; state.input = null;
    this.#launch(state, () => this.#host.resume(state.id, input), result => {
      if (result.result.done) this.#states.delete(state.id);
      else if (this.#running(state.id)) { state.yielded = result.result.value; state.phase = 'yielded'; }
    });
  }
  #offers(state) {
    const observed = this.#observations.offerView(state.id);
    if (!observed) return;
    const { basis, view } = observed, key = contentDigest(basis);
    if (state.lastBasis === key) return;
    state.lastBasis = key; state.phase = 'evaluating_offers';
    this.#launch(state, () => this.#host.offers(state.id, view), result => {
      if (!this.#running(state.id)) return;
      if (this.#observations.isCurrentOfferBasis(basis)) {
        const values = result.result.map(effect => ({ operation: effect.operation, arguments: effect.arguments, context: effect.context }));
        this.#work.replaceOffers(state.id, ++state.sequence, values, basis);
        this.#trace?.record('execution.offers', { owner: state.id, sequence: state.sequence, basis, digest: contentDigest(values) });
      }
      state.phase = 'offers_waiting';
    });
  }
  #dispatch(state, value) {
    const effect = guestEffect(value);
    state.effect = effect; state.sequence++;
    this.#trace?.record('execution.effect', { owner: state.id, sequence: state.sequence, kind: effect.kind, digest: contentDigest(effect) });
    if (effect.kind === 'observe') {
      if (!Object.hasOwn(effect.query, 'scopes') || Object.keys(effect.query).some(key => !['scopes', 'offset'].includes(key))) throw Error('invalid_observation_query');
      this.#ready(state, this.#waits.observe(state.id, effect.query.scopes, { offset: effect.query.offset ?? 0 }));
    } else if (effect.kind === 'wait') {
      state.waitId = this.#waits.wait(state.id, effect.condition, effect.options);
      state.phase = 'waiting'; this.#poll(state);
    } else if (effect.kind === 'join') {
      const handle = effect.handle;
      if (!object(handle) || Object.keys(handle).length !== 3 || !['id', 'parentId', 'sequence'].every(key => Object.hasOwn(handle, key))) throw Error('invalid_child_handle');
      state.phase = 'joining'; this.#poll(state);
    } else if (effect.kind === 'spawn') {
      const definition = this.#host.describe(state.id, effect.definition);
      this.#supported(definition);
      state.phase = 'spawn_ready';
    } else if (effect.kind === 'target' && this.#resources) {
      this.#ready(state, this.#resources.target(state.id, effect.resource, effect.quantity));
    } else if (effect.kind === 'demand' && this.#resources) {
      state.demandId = this.#resources.request(state.id, state.sequence, { resource: effect.resource, quantity: effect.quantity, methods: effect.methods });
      state.phase = 'delivery'; this.#poll(state);
    } else if (effect.kind === 'work' && this.#work) {
      state.workId = this.#work.request(state.id, state.sequence, { operation: effect.operation, arguments: effect.arguments, context: effect.context });
      state.phase = 'working'; this.#poll(state);
    } else this.#ready(state, { status: 'rejected', reason: 'service_unavailable', service: effect.kind });
  }
  #spawn(state) {
    const effect = state.effect;
    state.phase = 'spawning';
    this.#launch(state, () => this.#host.spawn(state.id, effect.definition, effect.input, effect.options), handle => {
      if (!this.#running(state.id)) return;
      this.attach(handle.id);
      this.#ready(state, handle);
    }, true);
  }
  #poll(state) {
    let result;
    if (state.phase === 'waiting') result = this.#waits.take(state.id, state.waitId);
    else if (state.phase === 'delivery') result = this.#resources.take(state.id, state.demandId);
    else if (state.phase === 'working') result = this.#work.take(state.id, state.workId);
    else result = this.#host.join(state.id, state.effect.handle);
    if (result.status !== 'pending') this.#ready(state, result);
  }
  #ready(state, value) {
    let input;
    try {
      input = copyMessage(value);
      // Validate the actual enclosing protocol before consuming another guest step.
      frame({ requestId: Number.MAX_SAFE_INTEGER, invocation: state.id, command: 'resume', input });
    } catch (error) {
      if (error.message !== 'message_limit') throw error;
      input = { status: 'rejected', reason: 'effect_response_limit' };
    }
    this.#trace?.record('execution.response', { owner: state.id, sequence: state.sequence, kind: state.effect.kind, digest: contentDigest(input) });
    state.input = input; state.phase = 'ready'; delete state.waitId; delete state.demandId; delete state.workId;
  }
  #launch(state, operation, completed, canReject = false) {
    const job = Promise.resolve().then(() => {
      if (!this.#current(state) || !this.#running(state.id)) return;
      return operation();
    }).then(result => {
      if (result !== undefined && this.#current(state)) completed(result);
    }, error => {
      if (canReject) this.#rejectOrFail(state, error);
      else this.#fail(state, error);
    }).catch(error => this.#fail(state, error)).finally(() => this.#jobs.delete(job));
    this.#jobs.add(job);
  }
  #rejectOrFail(state, error) {
    if (!this.#current(state)) return;
    if (state.mode === 'generator' && this.#running(state.id) && rejections.has(error.message)) this.#ready(state, { status: 'rejected', reason: error.message });
    else this.#fail(state, error);
  }
  #supported(definition) {
    if (definition.kind !== 'behavior' || definition.mode !== 'generator' &&
      !(definition.mode === 'offers' && this.#work && this.#observations)) throw Error('unsupported_definition_mode');
  }
  #fail(state, error) {
    if (!this.#current(state)) return;
    this.#states.delete(state.id);
    if (this.#running(state.id)) this.#runners.fail(state.id, String(error.message ?? 'execution_failed').slice(0, 256));
    this.#pollPassive();
  }
  #pollPassive() { this.#waits.poll(); this.#resources?.poll(); this.#work?.poll(); }
  #current(state) { return !this.#closed && this.#states.get(state.id) === state; }
  #exists(id) {
    try { this.#invocations.execution(id); return true; }
    catch (error) { if (error.message === 'invocation_unknown') return false; throw error; }
  }
  #running(id) { return this.#exists(id) && this.#invocations.execution(id).phase === 'running'; }
  #sweep() {
    const changedRoots = new Set();
    for (const [id, state] of this.#states) if (!this.#running(id)) { this.#states.delete(id); changedRoots.add(state.rootId); }
    // A mandatory join may have consumed the stopped child's handle; its retained root identity still owns the VM.
    for (const rootId of changedRoots) this.#runners.syncOwnership(rootId);
    for (const id of this.#roots) if (!this.#exists(id)) this.#roots.delete(id);
  }
}
