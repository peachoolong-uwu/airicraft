import { copyMessage } from './value.mjs';
import { contentDigest } from './content.mjs';
import { supplyOperations } from './supplies.mjs';
import { ObservationFrames } from './observations.mjs';
import { observeInventory } from './item-observation.mjs';
import { progressScopes as validateProgressScopes, projectProgress } from './progress-observation.mjs';

const text = value => typeof value === 'string' && value.length > 0 && value.length <= 256;
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const blocked = new Set(['resource_reconciling', 'resource_unavailable', 'asset_unavailable', 'capacity_unavailable',
  'target_unavailable', 'claim_capacity', 'ambiguous_item_components', 'supply_output_mismatch']);
const metadata = ['schemaVersion', 'sessionId', 'epoch', 'captureId', 'captureSequence', 'capturedAtNanos', 'clockDomain',
  'source', 'clientTick', 'serverTick', 'receivedAtHostMillis', 'captureAgeUpperBoundMillis'];

/** Single-flight native reads feed owned waits and scheduling hints. Admission still observes again. */
export class NativeObservationFeed {
  #native; #effects; #work; #invocations; #ledger; #resources; #waits; #operations;
  #epoch; #expectedWorld; #views; #now; #trace;
  #progressScopes; #scopeNames;
  #job = null; #closed = false; #fault = null; #latest = null; #capture = null; #session = null; #next = 0;

  constructor({ native, effects, work, invocations, ledger, resources, waits, operations, epoch, expectedWorld, views, progressScopes = [],
    now = () => performance.now(), trace }) {
    const catalog = supplyOperations(operations), copied = copyMessage(views);
    const locations = new Set(['player', ...[...catalog.values()].map(operation => operation.grant)]);
    this.#progressScopes = validateProgressScopes(progressScopes);
    if (!text(epoch) || !text(expectedWorld) || !object(copied) || Object.keys(copied).length > 32 ||
        [...catalog.values()].some(operation => typeof operation.observe !== 'function')) throw Error('invalid_native_views');
    this.#scopeNames = [...Object.keys(copied), ...this.#progressScopes.map(scope => scope.scope)];
    if (!this.#scopeNames.length || this.#scopeNames.length > 32 || new Set(this.#scopeNames).size !== this.#scopeNames.length) throw Error('invalid_native_views');
    for (const [scope, view] of Object.entries(copied)) {
      if (!text(scope) || scope.length > 128 || !object(view) || Object.keys(view).length !== 2 || !locations.has(view.location) ||
          !object(view.resources) || Object.keys(view.resources).length > 32 || Object.entries(view.resources).some(([alias, key]) =>
            !text(alias) || alias.length > 64 || !text(key) || !key.startsWith(`${view.location}/`))) throw Error('invalid_native_views');
    }
    this.#native = native; this.#effects = effects; this.#work = work; this.#invocations = invocations; this.#ledger = ledger;
    this.#resources = resources; this.#waits = waits; this.#operations = catalog; this.#epoch = epoch;
    this.#expectedWorld = expectedWorld; this.#views = copied; this.#now = now; this.#trace = trace;
  }
  tick() {
    if (this.#closed || this.#fault) return;
    // Declarations can arrive after their capture. Assess them against that same ledger basis,
    // without waiting for a newer capture that would invalidate their authoring decision.
    try { this.#assessPending(); }
    catch (error) { void this.#onError(error).catch(() => {}); return; }
    if (!this.#closed && !this.#fault && !this.#job && this.#now() >= this.#next) void this.refresh().catch(() => {});
  }
  refresh() {
    if (this.#closed || this.#fault) return Promise.reject(Error(this.#fault ?? 'observation_feed_closed'));
    if (this.#job) return this.#job;
    const generation = this.#work.observationGeneration;
    this.#job = this.#read(generation).catch(async error => {
      await this.#onError(error);
      throw error;
    }).finally(() => { this.#job = null; this.#next = this.#now() + 1000; });
    return this.#job;
  }
  availability() {
    if (!this.#current()) return 'unknown';
    return this.#effects.canDispatch(this.#latest.authority) ? 'available' : 'unavailable';
  }
  offerView(owner) {
    if (!this.#current()) return null;
    const { frame, generation } = this.#latest;
    const view = this.#waits.grantedView(owner, this.#scopeNames);
    if (view.epoch !== frame.epoch || view.scopes.some(scope => scope.frame?.captureId !== frame.captureId ||
      scope.frame?.captureSequence !== frame.captureSequence)) return null;
    return { basis: { epoch: frame.epoch, captureId: frame.captureId, captureSequence: frame.captureSequence, generation }, view };
  }
  isCurrentOfferBasis(basis) {
    return this.#current() && basis.epoch === this.#epoch && basis.generation === this.#latest.generation &&
      basis.captureId === this.#latest.frame.captureId && basis.captureSequence === this.#latest.frame.captureSequence;
  }
  close() {
    if (this.#closed) return;
    this.#closed = true; this.#latest = null;
    this.#ledger.invalidate(); this.#work.invalidate(); this.#waits.gap('observation_feed_closed');
  }
  state() { return { closed: this.#closed, busy: this.#job !== null, fault: this.#fault, captureId: this.#capture?.id ?? null }; }
  async #read(generation) {
    const response = await this.#native.call('os_observe', this.#progressScopes.length ? { progressScopes: this.#progressScopes } : {});
    if (this.#closed) return false;
    if (response?.status !== 'ok') throw Error(response?.code ?? 'invalid_native_response');
    const frame = copyMessage(response.frame, 524_288, { maximumNodes: 8192 });
    if (frame.epoch !== this.#epoch || response.authority?.epoch !== frame.epoch) throw Error('stale_epoch');
    if (this.#session !== null && frame.sessionId !== this.#session) throw Error('observation_session_changed');
    if (!frame.world?.alive || frame.world.worldId !== this.#expectedWorld || frame.world.reflexActive || frame.world.controllerBusy)
      throw Error('world_not_ready');
    if (this.#age(frame) >= 2000) throw Error('observation_stale');
    if (generation !== this.#work.observationGeneration) return false;
    const { receivedAtHostMillis, captureAgeUpperBoundMillis, ...identity } = frame;
    const signature = contentDigest(identity, 524_288, { maximumNodes: 8192 });
    if (this.#capture && frame.captureSequence <= this.#capture.sequence) {
      if (frame.captureSequence === this.#capture.sequence && signature !== this.#capture.signature) throw Error('capture_conflict');
      return false;
    }
    const known = [...new Set([...(this.#resources?.resourceKeys ?? []), ...Object.values(this.#views).flatMap(view => Object.values(view.resources))])];
    if (known.length > 128) throw Error('native_view_capacity');
    const observation = { epoch: frame.epoch, revision: frame.captureId, stocks: observeInventory(frame, known), assets: {}, capacities: {}, targets: [] };
    for (const operation of new Set(this.#operations.values())) {
      const seen = operation.observe(frame, known);
      for (const field of ['stocks', 'assets', 'capacities']) for (const [key, value] of Object.entries(seen[field])) {
        if (Object.hasOwn(observation[field], key) && observation[field][key] !== value) throw Error('observation_conflict');
        observation[field][key] = value;
      }
      observation.targets.push(...seen.targets.filter(target => !observation.targets.includes(target)));
    }
    const projections = this.#project(frame, observation);
    // Validate every projection before publishing any part of the capture.
    const validator = new ObservationFrames({ epoch: this.#epoch, scopes: this.#scopeNames, now: this.#now });
    for (const projection of projections) validator.publish(projection);
    this.#session = frame.sessionId;
    this.#capture = { sequence: frame.captureSequence, id: frame.captureId, signature };
    // Never replace the admitted bundle's basis from a parallel passive read during an effect.
    const assessable = this.#quiescent();
    if (assessable) {
      this.#ledger.observe(observation);
    }
    for (const projection of projections) this.#waits.publish(projection);
    this.#latest = { frame, authority: copyMessage(response.authority), generation, assessable, assessed: new Set() };
    this.#assessPending();
    this.#trace?.record('observation.projected', { epoch: frame.epoch, captureId: frame.captureId, captureSequence: frame.captureSequence,
      scopes: this.#scopeNames, stockKeys: Object.keys(observation.stocks).length });
    return true;
  }
  #project(frame, observation) {
    const provenance = Object.fromEntries(metadata.map(key => [key, frame[key]]));
    const items = Object.entries(this.#views).map(([scope, view]) => {
      const available = view.location === 'player' ? frame.facts?.inventory?.available === true : observation.targets.includes(view.location);
      return { ...provenance, scope, coverage: { available, complete: available, truncated: false },
        facts: Object.entries(view.resources).map(([alias, key]) => Object.hasOwn(observation.stocks, key)
          ? { path: ['stock', alias], known: true, value: observation.stocks[key] }
          : { path: ['stock', alias], known: false, reason: 'location_unobserved' }),
        // A sampled server tick is not evidence of a continuously eligible growth interval.
        progress: { clockId: `native-items:${scope}`, eligibleTicks: null } };
    });
    return [...items, ...projectProgress(frame, this.#progressScopes, provenance)];
  }
  #assess(request, frame) {
    let readiness = 'ready';
    try {
      const operation = this.#operations.get(request.request.operation);
      this.#invocations.authorize(request.owner, operation.grant);
      const prepared = operation.prepare(frame, request.request.arguments);
      if (request.supply && (prepared.destination !== request.supply.resource || prepared.quantity !== request.supply.quantity))
        throw Error('supply_output_mismatch');
      this.#ledger.assess(request.consumer, prepared.bundle, frame.captureId);
    } catch (error) {
      if (['invalid_transfer', 'unsupported_transfer_item'].includes(error.message)) { this.#work.reject(request.id, error.message); return; }
      if (blocked.has(error.message)) readiness = 'blocked';
      else if (['container_changed', 'stale_observation'].includes(error.message)) readiness = 'unknown';
      else throw error;
    }
    this.#work.publish(request.id, { epoch: frame.epoch, captureId: frame.captureId, captureSequence: frame.captureSequence,
      readiness, ageUpperBoundMillis: this.#age(frame) });
  }
  #assessPending() {
    if (!this.#current() || !this.#latest.assessable || !this.#quiescent()) return;
    const requests = this.#work.pending(), ids = new Set(requests.map(request => request.id));
    for (const id of this.#latest.assessed) if (!ids.has(id)) this.#latest.assessed.delete(id);
    for (const request of requests) if (!request.deferred && !this.#latest.assessed.has(request.id)) {
      this.#assess(request, this.#latest.frame);
      this.#latest.assessed.add(request.id);
    }
  }
  #quiescent() {
    const state = this.#work.state();
    return !state.busy && !state.active && !this.#invocations.activity() && !this.#effects.state().unresolved;
  }
  #current() {
    return !this.#closed && !this.#fault && this.#latest !== null && this.#age(this.#latest.frame) < 2000 &&
      this.#latest.generation === this.#work.observationGeneration;
  }
  async #onError(error) {
    if (this.#closed) return;
    this.#latest = null; this.#work.invalidate(); this.#ledger.invalidate(); this.#waits.gap(String(error.message).slice(0, 256));
    if (error.message !== 'observation_stale') {
      this.#fault = String(error.message).slice(0, 256);
      await this.#effects.stop();
    }
  }
  #age(frame) {
    const age = frame.captureAgeUpperBoundMillis, received = frame.receivedAtHostMillis, now = this.#now();
    if (!Number.isFinite(age) || age < 0 || !Number.isFinite(received) || received < 0 || received > now) throw Error('invalid_native_observation');
    return age + now - received;
  }
}
