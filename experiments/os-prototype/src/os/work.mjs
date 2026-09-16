import { copyMessage } from './value.mjs';
import { contentDigest } from './content.mjs';
import { supplyOperations } from './supplies.mjs';
import { SchedulingPolicy } from './scheduling.mjs';
import { executionPolicy } from './execution-policy.mjs';

const name = value => typeof value === 'string' && value.length > 0 && value.length <= 256;
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
export const workPolicy = Object.freeze({ version: 'os-work-v3', supplyRetryMillis: 5000, recurringRetryMillis: 5000 });
const admissionRejections = new Set(['operation_not_granted', 'invocation_closing', 'invocation_unknown', 'stale_observation',
  'resource_reconciling', 'resource_unavailable', 'asset_unavailable', 'capacity_unavailable', 'target_unavailable',
  'container_changed', 'invalid_transfer', 'unsupported_transfer_item', 'ambiguous_item_components', 'supply_offer_stale',
  'demand_unknown', 'demand_capacity', 'supply_capacity', 'supply_capacity_unavailable', 'supply_inapplicable', 'supply_output_mismatch']);

/** Owned work and supply scheduling. Only the coordinator may turn a selected offer into physical admission. */
export class WorkService {
  #invocations;
  #activities;
  #rules;
  #policy;
  #now;
  #epoch;
  #trace;
  #supplies;
  #maximumWork;
  #requests = new Map();
  #supplyRequests = new Map();
  #supplyDeferrals = new Map();
  #recurringDeferrals = new Map();
  #history = new Map();
  #offerHistory = new Map();
  #roots = new Set();
  #sequence = 0;
  #active = null;
  #job = null;
  #fault = null;
  #capture = null;
  #invalidatedThrough = 0;
  #observationGeneration = 0;
  #availabilityClock = null;
  #availabilityWitness = null;
  #availabilityCapture = 0;

  constructor({ invocations, activities, operations, rules, supplies, epoch, now = () => performance.now(), trace }) {
    const catalog = supplyOperations(operations), copied = copyMessage(rules);
    if (!object(copied) || Object.keys(copied).length > 32) throw Error('invalid_work_rules');
    this.#rules = new Map();
    for (const [operation, rule] of Object.entries(copied)) {
      const native = catalog.get(operation);
      // Context declarations must resolve to a trusted adapter, never a guest native identity.
      if (!native || !name(native.grant) || !object(rule) || rule.kind !== 'land' ||
          (rule.context !== null && (!name(rule.context) || rule.context !== native.contextKey || typeof native.prepareContext !== 'function')) ||
          !Number.isInteger(rule.priority) || rule.priority < 0 || rule.priority > 2_147_483_647 ||
          Object.keys(rule).some(key => !['priority', 'kind', 'context'].includes(key))) throw Error('invalid_work_rules');
      this.#rules.set(operation, { ...rule, grant: native.grant });
    }
    if (supplies && (!Number.isSafeInteger(supplies.maximumOffers) || supplies.maximumOffers < 0 ||
        supplies.maximumOffers > executionPolicy.roots * 32 || supplies.operations.some(operation => !this.#rules.has(operation))))
      throw Error('invalid_work_supply_rules');
    this.#supplies = supplies;
    this.#maximumWork = executionPolicy.invocations * executionPolicy.offers - (supplies?.maximumOffers ?? 0);
    this.#invocations = invocations; this.#activities = activities; this.#epoch = epoch; this.#now = now; this.#trace = trace;
    this.#policy = new SchedulingPolicy({ epoch });
    trace?.onFailure(error => { this.#fault ??= String(error.message).slice(0, 256); });
  }
  request(owner, sequence, value) {
    if (this.#fault) throw Error('work_service_failed');
    this.poll();
    const request = copyMessage(value);
    if (!Number.isSafeInteger(sequence) || sequence < 1) throw Error('invalid_work');
    const { rule, consumer, digest } = this.#declaration(owner, request);
    const existing = [...this.#requests.values()].find(record => record.owner === owner && record.sequence === sequence);
    if (existing) {
      if (existing.digest !== digest) throw Error('work_conflict');
      return existing.id;
    }
    if (sequence <= (this.#history.get(owner) ?? 0)) throw Error('work_retired');
    if (this.#budgetedRequests().length >= this.#maximumWork) throw Error('work_capacity');
    if (this.#budgetedRequests().filter(record => record.owner === owner).length >= executionPolicy.offers) throw Error('invocation_work_capacity');
    if (this.#sequence === Number.MAX_SAFE_INTEGER) throw Error('work_id_exhausted');
    const id = `work:${++this.#sequence}`;
    this.#trace?.record('work.requested', { id, owner, consumer, sequence, request, digest });
    this.#requests.set(id, { id, owner, consumer, sequence, digest, request, rule, phase: 'queued', assessment: null });
    this.#history.set(owner, sequence); this.#roots.add(consumer);
    return id;
  }
  /** Replace queued declarations atomically. An admitted bounded attempt still owns its physical cleanup. */
  replaceOffers(owner, sequence, values, basis) {
    if (this.#fault) throw Error('work_service_failed');
    this.poll();
    values = copyMessage(values); basis = copyMessage(basis);
    if (!Number.isSafeInteger(sequence) || sequence < 1 || !Array.isArray(values) || values.length > executionPolicy.offers) throw Error('invalid_work_offers');
    if (!basis || basis.epoch !== this.#epoch || !name(basis.captureId) || !Number.isSafeInteger(basis.captureSequence) || basis.captureSequence < 1 ||
        Object.keys(basis).length !== 4 || !Number.isSafeInteger(basis.generation) || basis.generation !== this.#observationGeneration ||
        basis.captureSequence <= this.#invalidatedThrough) throw Error('offer_basis_stale');
    const invocation = this.#invocations.execution(owner);
    if (invocation.phase !== 'running') throw Error('invocation_closing');
    const digest = contentDigest({ values, basis }, 32_768, { maximumNodes: 4096 }), previous = this.#offerHistory.get(owner);
    if (previous?.sequence === sequence) {
      if (previous.digest !== digest) throw Error('work_conflict');
      return { ids: [...previous.ids] };
    }
    if (sequence <= (previous?.sequence ?? 0)) throw Error('work_retired');
    const declarations = new Map();
    for (const request of values) {
      const declaration = this.#declaration(owner, request);
      declarations.set(declaration.digest, declaration);
    }
    const existing = [...this.#requests.values()].filter(record => record.owner === owner && record.recurring);
    const others = this.#budgetedRequests().filter(record => record.owner !== owner || !record.recurring);
    if (others.length + declarations.size > this.#maximumWork) throw Error('work_capacity');
    if (others.filter(record => record.owner === owner).length + declarations.size > executionPolicy.offers) throw Error('invocation_work_capacity');
    const newCount = [...declarations.keys()].filter(key => !existing.some(record => record.digest === key)).length;
    if (!Number.isSafeInteger(this.#sequence + newCount)) throw Error('work_id_exhausted');
    this.#trace?.record('work.offers_replaced', { owner, sequence, digest, basis, offers: [...declarations.values()].map(value => value.request) });
    for (const record of existing) {
      record.desired = declarations.has(record.digest);
      if (!record.desired && !['admitting', 'active'].includes(record.phase)) this.#requests.delete(record.id);
    }
    const ids = [];
    for (const declaration of declarations.values()) {
      let record = existing.find(record => record.digest === declaration.digest);
      if (!record) {
        const id = `work:${++this.#sequence}`;
        record = { id, owner, ...declaration, sequence: null, phase: 'queued', assessment: null, recurring: true, desired: true };
        this.#requests.set(id, record);
      } else if (record.phase === 'finished' && !this.#deferral(record)) {
        record.phase = 'queued'; record.assessment = null; delete record.result;
      }
      record.offerBasis = basis;
      ids.push(record.id); this.#roots.add(record.consumer);
    }
    this.#offerHistory.set(owner, { sequence, digest, ids });
    return { ids: [...ids] };
  }
  publish(id, value) {
    const record = this.#get(id), assessment = copyMessage(value);
    if (record.phase !== 'queued') throw Error('work_not_queued');
    if (!assessment || assessment.epoch !== this.#epoch) throw Error('stale_scheduler_epoch');
    if (!name(assessment.captureId) || !Number.isSafeInteger(assessment.captureSequence) || assessment.captureSequence < 1 ||
        !['ready', 'blocked', 'unknown'].includes(assessment.readiness) ||
        !Number.isFinite(assessment.ageUpperBoundMillis) || assessment.ageUpperBoundMillis < 0 ||
        Object.keys(assessment).some(key => !['epoch', 'captureId', 'captureSequence', 'readiness', 'ageUpperBoundMillis'].includes(key))) throw Error('invalid_work_assessment');
    if (assessment.captureSequence <= this.#invalidatedThrough || assessment.captureSequence < (this.#capture?.sequence ?? 0)) return false;
    if (assessment.captureSequence === this.#capture?.sequence) {
      if (assessment.captureId !== this.#capture.id) throw Error('capture_conflict');
      if (record.assessment?.captureSequence === assessment.captureSequence) return false;
    } else {
      if (assessment.captureId === this.#capture?.id) throw Error('capture_conflict');
      this.#capture = { sequence: assessment.captureSequence, id: assessment.captureId, receivedAt: this.#now(), age: assessment.ageUpperBoundMillis };
    }
    record.assessment = { ...assessment, ageUpperBoundMillis: Math.max(assessment.ageUpperBoundMillis, this.#capture.age), receivedAt: this.#capture.receivedAt };
    return true;
  }
  get observationGeneration() { return this.#observationGeneration; }
  invalidate() { this.#invalidate(); }
  reject(id, reason) {
    const record = this.#get(id);
    if (record.phase !== 'queued') throw Error('work_not_queued');
    if (!['invalid_transfer', 'unsupported_transfer_item'].includes(reason)) throw Error('invalid_work_rejection');
    record.phase = 'finished'; record.result = { status: 'rejected', reason };
    if (record.recurring) this.#deferRecurring(record, reason);
    if (record.supply) this.#defer(record, reason);
    this.#trace?.record('work.rejected', { id, reason, fault: null }, { cleanup: true });
  }
  pending() {
    this.poll(); this.#refreshSupplies();
    return this.#records().filter(record => record.phase === 'queued').map(record =>
      ({ id: record.id, owner: record.owner, consumer: record.consumer, request: copyMessage(record.request),
        ...(record.recurring ? { recurring: true, deferred: this.#deferral(record) ? { ...this.#deferral(record) } : null } : {}),
        ...(record.supply ? { supply: copyMessage(record.supply), deferred: this.#deferral(record) ? { ...this.#deferral(record) } : null } : {}) }));
  }
  take(owner, id) {
    const record = this.#requests.get(id);
    if (!record || record.owner !== owner) throw Error('work_unknown');
    if (record.recurring) throw Error('work_not_finite');
    this.#invocations.authorize(owner, record.rule.grant);
    if (record.phase !== 'finished') return { status: 'pending' };
    this.#requests.delete(id);
    return structuredClone(record.result);
  }
  join(value) {
    if (this.#fault) throw Error('work_service_failed');
    const request = copyMessage(value), record = this.#active && this.#get(this.#active);
    if (!record || !request || request.activityId !== record.activityId) throw Error('activity_unknown');
    const delivery = this.#activities.join(request), consumer = delivery.spec.consumer;
    if (!record.servedRoots.has(consumer)) {
      this.#policy.served({ ...record.selection, roots: [consumer] });
      record.servedRoots.add(consumer);
      this.#trace?.record('work.join_served', { id: record.id, activityId: record.activityId, consumer, deliveryId: delivery.id });
    }
    return delivery;
  }
  poll() {
    for (const record of this.#requests.values()) if ((!this.#running(record.owner) || record.recurring && !record.desired) &&
      !['admitting', 'active'].includes(record.phase)) this.#requests.delete(record.id);
    for (const record of this.#supplyRequests.values()) if (record.phase === 'finished' ||
      (record.phase === 'queued' && record.supply.owners.some(owner => !this.#running(owner)))) this.#supplyRequests.delete(record.id);
    for (const owner of this.#history.keys()) if (!this.#running(owner) && ![...this.#requests.values()].some(record => record.owner === owner)) this.#history.delete(owner);
    for (const owner of this.#offerHistory.keys()) if (!this.#running(owner) && ![...this.#requests.values()].some(record => record.owner === owner)) this.#offerHistory.delete(owner);
    for (const root of this.#roots) {
      try { if (this.#invocations.execution(root).phase === 'terminal') this.#roots.delete(root); }
      catch (error) { if (error.message !== 'invocation_unknown') throw error; this.#roots.delete(root); }
    }
    for (const [key, deferred] of this.#supplyDeferrals) if (!this.#roots.has(deferred.consumer) || this.#now() >= deferred.retryAt) this.#supplyDeferrals.delete(key);
    for (const [key, deferred] of this.#recurringDeferrals) if (!this.#roots.has(deferred.consumer) || this.#now() >= deferred.retryAt) this.#recurringDeferrals.delete(key);
  }
  tick({ authority = 'unknown' } = {}) {
    this.poll();
    if (this.#job) return { kind: 'wait', reason: 'work_transport_pending' };
    if (this.#active) {
      const record = this.#get(this.#active);
      this.#launch(record, () => this.#activities.poll(), false);
      return { kind: 'wait', reason: 'activity_in_progress' };
    }
    if (this.#invocations.activity()) return { kind: 'wait', reason: 'player_owned' };
    const context = this.#activities.context();
    if (context && context.phase !== 'ready') {
      this.#launchContext(() => this.#activities.pollContext());
      return { kind: 'wait', reason: 'context_not_ready' };
    }
    if (this.#fault) return { kind: 'wait', reason: 'work_service_failed' };
    this.#updateSchedule();
    const decision = this.#policy.decide({ authority, context });
    if (decision.kind === 'close_context' && decision.reason === 'no_feasible_work') {
      // Entry/completion invalidates authorship. Give live duties a chance to reevaluate
      // the new material view; this never admits old declarations or defeats the visit budget.
      const pending = this.#updateSchedule(true).some(offer => offer.context === context.id && offer.readiness === 'ready') ||
        [...this.#requests.values()].some(record => record.recurring && record.desired && record.phase === 'finished' &&
          record.rule.context === context.id && !this.#deferral(record) && record.offerBasis.generation !== this.#observationGeneration);
      this.#updateSchedule();
      if (pending) return { kind: 'wait', reason: 'context_authorship_pending' };
    }
    if (decision.kind === 'close_context') {
      this.#trace?.record('work.context_closing', { decision, context });
      this.#launchContext(() => this.#activities.pollContext({ close: true }));
    }
    if (decision.kind === 'select') {
      const record = this.#get(decision.offerId);
      if (decision.context !== null && !context) {
        this.#trace?.record('work.context_entering', { decision, owner: record.owner, basis: record.assessment });
        this.#launchContext(() => this.#activities.retainContext({ owner: record.owner, ...record.request, workId: record.id }), record);
        return { ...decision, kind: 'enter_context' };
      }
      this.#trace?.record('work.selected', { decision, owner: record.owner, basis: record.assessment });
      record.phase = 'admitting'; record.selection = copyMessage(decision);
      this.#observationGeneration++;
      this.#launch(record, () => this.#activities.admit(record.supply ? { supplyOfferId: record.id, workId: record.id, context: record.rule.context }
        : { owner: record.owner, ...record.request, workId: record.id }), true);
    }
    return decision;
  }
  advance(interval) {
    this.poll();
    this.#updateSchedule();
    this.#policy.advance(interval);
  }
  /** Trusted native evidence plus host allocation continuity; no guest can publish elapsed time. */
  progress({ proof, allocationRevision, generation, captureId, captureSequence, receivedAtHostMillis, ageUpperBoundMillis, operations }) {
    if (!proof) { this.#availabilityWitness = null; return true; }
    const clock = this.#availabilityClock;
    if (clock && proof.clockId !== clock.id) throw Error('eligibility_clock_changed');
    if (clock && proof.throughTick < clock.throughTick) throw Error('eligibility_clock_regressed');
    this.#availabilityClock = { id: proof.clockId, throughTick: proof.throughTick };
    if (captureSequence <= this.#availabilityCapture) return true;
    this.poll();
    // Retained intent can earn elapsed age from native feasibility before its VM reevaluates.
    // The ordinary selection view still requires fresh authorship before it can execute.
    const offers = this.#updateSchedule(true), records = this.#records();
    const ids = new Set(offers.filter(offer => offer.readiness === 'ready' && records.some(record => record.id === offer.id &&
      record.assessment?.captureId === captureId && operations.includes(record.request.operation))).map(offer => offer.id));
    const previous = this.#availabilityWitness, now = this.#now();
    const covered = proof.available && previous?.stamp === proof.stamp && previous.generation === generation &&
      previous.allocationRevision === allocationRevision && now >= previous.receivedAt &&
      previous.age + now - previous.receivedAt < 2000 && now >= receivedAtHostMillis && ageUpperBoundMillis + now - receivedAtHostMillis < 2000;
    const eligibleRoots = covered ? [...new Set(offers.filter(offer => ids.has(offer.id) && previous.ids.has(offer.id)).flatMap(offer => offer.roots))] : [];
    const interval = { epoch: this.#epoch, fromTick: covered ? Math.max(previous.throughTick, proof.fromTick) : proof.throughTick,
      toTick: proof.throughTick, eligibleRoots, covered: Boolean(covered), ordinaryAllowed: proof.available };
    try { this.#policy.advance(interval); }
    finally { this.#updateSchedule(); }
    this.#availabilityWitness = proof.available ? { stamp: proof.stamp, throughTick: proof.throughTick, allocationRevision, generation, ids,
      receivedAt: receivedAtHostMillis, age: ageUpperBoundMillis } : null;
    this.#availabilityCapture = captureSequence;
    this.#trace?.record('work.eligibility', { ...interval, captureId, clockId: proof.clockId, allocationRevision });
    return true;
  }
  state() {
    return { requests: this.#requests.size, supplyRequests: this.#supplyRequests.size, maximumWork: this.#maximumWork,
      recurringOffers: this.#budgetedRequests().filter(record => record.recurring).length, retiringOffers: this.#requests.size - this.#budgetedRequests().length,
      supplyDeferrals: [...this.#supplyDeferrals.values()].map(value => ({ ...value })),
      recurringDeferrals: [...this.#recurringDeferrals.values()].map(value => ({ ...value })),
      active: this.#active, context: this.#activities.context(), busy: this.#job !== null, fault: this.#fault, scheduling: this.#policy.state() };
  }
  #launchContext(operation, record) {
    this.#invalidate();
    this.#job = Promise.resolve().then(operation).catch(error => {
      const reason = String(error?.message ?? 'context_failed').slice(0, 256);
      if (record && admissionRejections.has(reason) && !this.#activities.context()) {
        record.phase = 'finished'; record.result = { status: 'rejected', reason };
        if (record.recurring) this.#deferRecurring(record, reason);
        if (record.supply) this.#defer(record, reason);
      } else {
        this.#fault = reason;
        if (record && this.#running(record.owner)) this.#invocations.failed(record.owner, reason);
      }
      this.#trace?.record('work.context_failed', { reason, context: this.#activities.context() }, { cleanup: true });
    }).finally(() => { this.#job = null; this.#invalidate(); this.poll(); });
  }
  #launch(record, operation, admitting) {
    this.#job = Promise.resolve().then(operation).then(result => {
      record.activityId = result.activityId;
      if (admitting && !result.receipt.disposition && result.receipt.phase !== 'admission_rejected') {
        this.#policy.served(record.selection);
        record.servedRoots = new Set(record.selection.roots);
      }
      if (result.evidence.released !== true || result.evidence.accountingComplete !== true) {
        record.phase = 'active'; this.#active = record.id; return;
      }
      const rejected = Boolean(result.receipt.disposition) || result.receipt.phase === 'admission_rejected';
      const status = rejected ? 'rejected' : result.receipt.state === 'SUCCEEDED' ? 'success' : result.receipt.state === 'CANCELLED' ? 'cancelled' : 'failure';
      record.result = { status, id: record.id, activity: copyMessage(result),
        ...(rejected ? { reason: name(result.receipt.reason) ? result.receipt.reason : result.receipt.disposition ?? 'admission_rejected' } : {}) };
      record.phase = 'finished'; this.#active = null;
      if (record.recurring && status !== 'success') this.#deferRecurring(record, record.result.reason ?? status);
      // An effect changes the feasibility basis even when its final material result is failure.
      this.#invalidate();
      this.#trace?.record('work.finished', record.result, { cleanup: true });
      if (record.supply && (status !== 'success' || (result.evidence.produced?.[record.supply.resource] ?? 0) < record.supply.quantity))
        this.#defer(record, record.result.reason ?? status);
    }).catch(error => {
      const reason = String(error?.message ?? 'work_failed').slice(0, 256), active = this.#invocations.activity();
      if (admitting && reason === 'context_budget' && !active) {
        record.phase = 'queued'; this.#invalidate();
        this.#trace?.record('work.context_budget', { id: record.id, context: this.#activities.context() }, { cleanup: true });
        return;
      }
      const owners = record.supply?.owners ?? [record.owner];
      // Shared subscribers can outlive the proposer, including a later join after the admission reply.
      if (active && (active.id === record.activityId || [...active.subscribers, ...active.cleanupOwners].some(owner => owners.includes(owner)))) {
        record.phase = 'active'; this.#active = record.id; this.#fault = reason;
      } else {
        record.phase = 'finished'; this.#active = null;
        record.result = { status: 'rejected', reason };
        if (record.recurring) this.#deferRecurring(record, reason);
        if (!admissionRejections.has(reason)) this.#fault = reason;
      }
      if (this.#fault && this.#running(record.owner)) this.#invocations.failed(record.owner, reason);
      if (record.supply) this.#defer(record, reason);
      this.#trace?.record('work.rejected', { id: record.id, reason, fault: this.#fault }, { cleanup: true });
    }).finally(() => { this.#job = null; this.poll(); });
  }
  #updateSchedule(forProgress = false) {
    this.#refreshSupplies();
    const now = this.#now();
    const offers = this.#records().filter(record => record.phase === 'queued').map(record => {
      const seen = record.assessment, current = seen && now >= seen.receivedAt && seen.ageUpperBoundMillis + now - seen.receivedAt < 2000;
      const authored = !record.recurring || record.offerBasis.generation === this.#observationGeneration &&
        record.offerBasis.captureId === seen?.captureId && record.offerBasis.captureSequence === seen?.captureSequence;
      return { id: record.id, roots: record.supply?.consumers ?? [record.consumer], kind: record.rule.kind,
        priority: Math.max(record.rule.priority, record.supply?.priority ?? 0), context: record.rule.context,
        readiness: this.#deferral(record) ? 'blocked' : current && (authored || forProgress) ? seen.readiness : 'unknown' };
    });
    this.#policy.update({ roots: [...this.#roots], offers });
    return offers;
  }
  #refreshSupplies() {
    if (!this.#supplies) return;
    const offers = this.#supplies.offers(), current = new Set(offers.map(offer => offer.id));
    for (const [id, record] of this.#supplyRequests) if (record.phase === 'queued' && !current.has(id)) this.#supplyRequests.delete(id);
    for (const supply of offers) {
      if (this.#supplyRequests.has(supply.id)) continue;
      this.#trace?.record('supply.proposed', supply);
      this.#supplyRequests.set(supply.id, { id: supply.id, owner: supply.owner, consumer: supply.anchor, supply,
        request: { operation: supply.operation, arguments: supply.arguments, context: this.#rules.get(supply.operation).context },
        rule: this.#rules.get(supply.operation), phase: 'queued', assessment: null });
      for (const root of supply.consumers) this.#roots.add(root);
    }
  }
  #invalidate() {
    this.#observationGeneration++;
    this.#invalidatedThrough = this.#capture?.sequence ?? 0;
    for (const queued of this.#records()) queued.assessment = null;
  }
  #defer(record, reason) {
    this.#invalidate();
    // Changed demand/ownership needs a fresh selection, rather than a source-availability retry timer.
    if (['supply_offer_stale', 'invocation_closing', 'invocation_unknown', 'demand_unknown'].includes(reason)) return;
    const deferred = { consumer: record.supply.anchor, rule: record.supply.rule, reason, retryAt: this.#now() + workPolicy.supplyRetryMillis };
    this.#supplyDeferrals.set(this.#supplyKey(record), deferred);
    this.#trace?.record('supply.deferred', { id: record.id, ...deferred }, { cleanup: true });
  }
  #supplyKey(record) { return JSON.stringify([record.supply.rule, record.supply.anchor]); }
  #declaration(owner, request) {
    if (!object(request) || !name(request.operation) || !object(request.arguments) || (request.context !== null && !name(request.context)) ||
        Object.keys(request).some(key => !['operation', 'arguments', 'context'].includes(key))) throw Error('invalid_work');
    const rule = this.#rules.get(request.operation);
    if (!rule) throw Error('operation_unknown');
    if (request.context !== rule.context) throw Error('invalid_work_context');
    const { consumer } = this.#invocations.authorize(owner, rule.grant);
    return { request, rule, consumer, digest: contentDigest(request) };
  }
  #recurringKey(record) { return JSON.stringify([record.consumer, record.request.operation]); }
  #deferRecurring(record, reason) {
    // At most twelve consumers times thirty-two operations. Changing arguments, children or
    // declaration IDs cannot turn a rejected attempt into an unbounded native retry loop.
    const deferred = { consumer: record.consumer, operation: record.request.operation, reason, retryAt: this.#now() + workPolicy.recurringRetryMillis };
    this.#recurringDeferrals.set(this.#recurringKey(record), deferred);
    this.#trace?.record('work.deferred', { id: record.id, ...deferred }, { cleanup: true });
  }
  #deferral(record) {
    return record.supply ? this.#supplyDeferrals.get(this.#supplyKey(record))
      : record.recurring ? this.#recurringDeferrals.get(this.#recurringKey(record)) : null;
  }
  #records() { return [...this.#requests.values(), ...this.#supplyRequests.values()]; }
  #budgetedRequests() { return [...this.#requests.values()].filter(record => !record.recurring || record.desired || !['admitting', 'active'].includes(record.phase)); }
  #get(id) { const record = this.#requests.get(id) ?? this.#supplyRequests.get(id); if (!record) throw Error('work_unknown'); return record; }
  #running(id) {
    try { return this.#invocations.execution(id).phase === 'running'; }
    catch (error) { if (error.message !== 'invocation_unknown') throw error; return false; }
  }
}
