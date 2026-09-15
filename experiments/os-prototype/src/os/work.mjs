import { copyMessage } from './value.mjs';
import { contentDigest } from './content.mjs';
import { supplyOperations } from './supplies.mjs';
import { SchedulingPolicy } from './scheduling.mjs';
import { executionPolicy } from './execution-policy.mjs';

const name = value => typeof value === 'string' && value.length > 0 && value.length <= 256;
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
export const workPolicy = Object.freeze({ version: 'os-work-v1', supplyRetryMillis: 5000 });
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
  #history = new Map();
  #roots = new Set();
  #sequence = 0;
  #active = null;
  #job = null;
  #fault = null;
  #capture = null;
  #invalidatedThrough = 0;
  #observationGeneration = 0;

  constructor({ invocations, activities, operations, rules, supplies, epoch, now = () => performance.now(), trace }) {
    const catalog = supplyOperations(operations), copied = copyMessage(rules);
    if (!object(copied) || Object.keys(copied).length > 32) throw Error('invalid_work_rules');
    this.#rules = new Map();
    for (const [operation, rule] of Object.entries(copied)) {
      const native = catalog.get(operation);
      // Context retention and fishing handover require their own native adapters before registration here.
      if (!native || !name(native.grant) || !object(rule) || rule.kind !== 'land' || rule.context !== null ||
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
  }
  request(owner, sequence, value) {
    if (this.#fault) throw Error('work_service_failed');
    this.poll();
    const request = copyMessage(value);
    if (!Number.isSafeInteger(sequence) || sequence < 1 || !object(request) || !name(request.operation) || !object(request.arguments) ||
        request.context !== null || Object.keys(request).some(key => !['operation', 'arguments', 'context'].includes(key))) throw Error('invalid_work');
    const rule = this.#rules.get(request.operation);
    if (!rule) throw Error('operation_unknown');
    const { consumer } = this.#invocations.authorize(owner, rule.grant), digest = contentDigest(request);
    const existing = [...this.#requests.values()].find(record => record.owner === owner && record.sequence === sequence);
    if (existing) {
      if (existing.digest !== digest) throw Error('work_conflict');
      return existing.id;
    }
    if (sequence <= (this.#history.get(owner) ?? 0)) throw Error('work_retired');
    if (this.#requests.size >= this.#maximumWork) throw Error('work_capacity');
    if ([...this.#requests.values()].filter(record => record.owner === owner).length >= executionPolicy.offers) throw Error('invocation_work_capacity');
    if (this.#sequence === Number.MAX_SAFE_INTEGER) throw Error('work_id_exhausted');
    const id = `work:${++this.#sequence}`;
    this.#trace?.record('work.requested', { id, owner, consumer, sequence, request, digest });
    this.#requests.set(id, { id, owner, consumer, sequence, digest, request, rule, phase: 'queued', assessment: null });
    this.#history.set(owner, sequence); this.#roots.add(consumer);
    return id;
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
    if (record.supply) this.#defer(record, reason);
    this.#trace?.record('work.rejected', { id, reason, fault: null }, { cleanup: true });
  }
  pending() {
    this.poll(); this.#refreshSupplies();
    return this.#records().filter(record => record.phase === 'queued').map(record =>
      ({ id: record.id, owner: record.owner, consumer: record.consumer, request: copyMessage(record.request),
        ...(record.supply ? { supply: copyMessage(record.supply), deferred: this.#deferral(record) ? { ...this.#deferral(record) } : null } : {}) }));
  }
  take(owner, id) {
    const record = this.#requests.get(id);
    if (!record || record.owner !== owner) throw Error('work_unknown');
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
    for (const record of this.#requests.values()) if (!this.#running(record.owner) && !['admitting', 'active'].includes(record.phase)) this.#requests.delete(record.id);
    for (const record of this.#supplyRequests.values()) if (record.phase === 'finished' ||
      (record.phase === 'queued' && record.supply.owners.some(owner => !this.#running(owner)))) this.#supplyRequests.delete(record.id);
    for (const owner of this.#history.keys()) if (!this.#running(owner) && ![...this.#requests.values()].some(record => record.owner === owner)) this.#history.delete(owner);
    for (const root of this.#roots) {
      try { if (this.#invocations.execution(root).phase === 'terminal') this.#roots.delete(root); }
      catch (error) { if (error.message !== 'invocation_unknown') throw error; this.#roots.delete(root); }
    }
    for (const [key, deferred] of this.#supplyDeferrals) if (!this.#roots.has(deferred.consumer) || this.#now() >= deferred.retryAt) this.#supplyDeferrals.delete(key);
  }
  tick({ authority = 'unknown' } = {}) {
    this.poll();
    if (this.#job) return { kind: 'wait', reason: 'work_transport_pending' };
    if (this.#active) {
      const record = this.#get(this.#active);
      this.#launch(record, () => this.#activities.poll(), false);
      return { kind: 'wait', reason: 'activity_in_progress' };
    }
    if (this.#fault) return { kind: 'wait', reason: 'work_service_failed' };
    if (this.#invocations.activity()) return { kind: 'wait', reason: 'player_owned' };
    this.#updateSchedule();
    const decision = this.#policy.decide({ authority });
    if (decision.kind === 'select') {
      const record = this.#get(decision.offerId);
      this.#trace?.record('work.selected', { decision, owner: record.owner, basis: record.assessment });
      record.phase = 'admitting'; record.selection = copyMessage(decision);
      this.#observationGeneration++;
      this.#launch(record, () => this.#activities.admit(record.supply ? { supplyOfferId: record.id, workId: record.id }
        : { owner: record.owner, operation: record.request.operation, arguments: record.request.arguments, workId: record.id }), true);
    }
    return decision;
  }
  advance(interval) {
    this.poll();
    this.#updateSchedule();
    this.#policy.advance(interval);
  }
  state() {
    return { requests: this.#requests.size, supplyRequests: this.#supplyRequests.size, maximumWork: this.#maximumWork,
      supplyDeferrals: [...this.#supplyDeferrals.values()].map(value => ({ ...value })),
      active: this.#active, busy: this.#job !== null, fault: this.#fault, scheduling: this.#policy.state() };
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
      // An effect changes the feasibility basis even when its final material result is failure.
      this.#invalidate();
      this.#trace?.record('work.finished', record.result, { cleanup: true });
      if (record.supply && (status !== 'success' || (result.evidence.produced?.[record.supply.resource] ?? 0) < record.supply.quantity))
        this.#defer(record, record.result.reason ?? status);
    }).catch(error => {
      const reason = String(error?.message ?? 'work_failed').slice(0, 256), active = this.#invocations.activity();
      const owners = record.supply?.owners ?? [record.owner];
      // Shared subscribers can outlive the proposer, including a later join after the admission reply.
      if (active && (active.id === record.activityId || [...active.subscribers, ...active.cleanupOwners].some(owner => owners.includes(owner)))) {
        record.phase = 'active'; this.#active = record.id; this.#fault = reason;
      } else {
        record.phase = 'finished'; this.#active = null;
        record.result = { status: 'rejected', reason };
        if (!admissionRejections.has(reason)) this.#fault = reason;
      }
      if (this.#fault && this.#running(record.owner)) this.#invocations.failed(record.owner, reason);
      if (record.supply) this.#defer(record, reason);
      this.#trace?.record('work.rejected', { id: record.id, reason, fault: this.#fault }, { cleanup: true });
    }).finally(() => { this.#job = null; this.poll(); });
  }
  #updateSchedule() {
    this.#refreshSupplies();
    const now = this.#now();
    this.#policy.update({ roots: [...this.#roots], offers: this.#records().filter(record => record.phase === 'queued').map(record => {
      const seen = record.assessment, current = seen && now >= seen.receivedAt && seen.ageUpperBoundMillis + now - seen.receivedAt < 2000;
      return { id: record.id, roots: record.supply?.consumers ?? [record.consumer], kind: record.rule.kind,
        priority: Math.max(record.rule.priority, record.supply?.priority ?? 0), context: null,
        readiness: this.#deferral(record) ? 'blocked' : current ? seen.readiness : 'unknown' };
    }) });
  }
  #refreshSupplies() {
    if (!this.#supplies) return;
    const offers = this.#supplies.offers(), current = new Set(offers.map(offer => offer.id));
    for (const [id, record] of this.#supplyRequests) if (record.phase === 'queued' && !current.has(id)) this.#supplyRequests.delete(id);
    for (const supply of offers) {
      if (this.#supplyRequests.has(supply.id)) continue;
      this.#trace?.record('supply.proposed', supply);
      this.#supplyRequests.set(supply.id, { id: supply.id, owner: supply.owner, consumer: supply.anchor, supply,
        request: { operation: supply.operation, arguments: supply.arguments, context: null }, rule: this.#rules.get(supply.operation), phase: 'queued', assessment: null });
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
  #deferral(record) { return record.supply ? this.#supplyDeferrals.get(this.#supplyKey(record)) : null; }
  #records() { return [...this.#requests.values(), ...this.#supplyRequests.values()]; }
  #get(id) { const record = this.#requests.get(id) ?? this.#supplyRequests.get(id); if (!record) throw Error('work_unknown'); return record; }
  #running(id) {
    try { return this.#invocations.execution(id).phase === 'running'; }
    catch (error) { if (error.message !== 'invocation_unknown') throw error; return false; }
  }
}
