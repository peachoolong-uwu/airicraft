import { copyMessage } from './value.mjs';
import { contentDigest } from './content.mjs';
import { supplyOperations } from './supplies.mjs';
import { SchedulingPolicy } from './scheduling.mjs';
import { executionPolicy } from './execution-policy.mjs';

const name = value => typeof value === 'string' && value.length > 0 && value.length <= 256;
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const admissionRejections = new Set(['operation_not_granted', 'invocation_closing', 'invocation_unknown', 'stale_observation',
  'resource_reconciling', 'resource_unavailable', 'asset_unavailable', 'capacity_unavailable', 'target_unavailable',
  'container_changed', 'invalid_transfer', 'unsupported_transfer_item', 'ambiguous_item_components']);

/** Owned finite work waits. Only the coordinator may turn a selected offer into physical admission. */
export class WorkService {
  #invocations;
  #activities;
  #rules;
  #policy;
  #now;
  #epoch;
  #trace;
  #requests = new Map();
  #history = new Map();
  #roots = new Set();
  #sequence = 0;
  #active = null;
  #job = null;
  #fault = null;
  #capture = null;
  #invalidatedThrough = 0;

  constructor({ invocations, activities, operations, rules, epoch, now = () => performance.now(), trace }) {
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
    if (this.#requests.size >= executionPolicy.invocations * executionPolicy.offers) throw Error('work_capacity');
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
  pending() {
    this.poll();
    return [...this.#requests.values()].filter(record => record.phase === 'queued').map(record =>
      ({ id: record.id, owner: record.owner, consumer: record.consumer, request: copyMessage(record.request) }));
  }
  take(owner, id) {
    const record = this.#get(id);
    if (record.owner !== owner) throw Error('work_unknown');
    this.#invocations.authorize(owner, record.rule.grant);
    if (record.phase !== 'finished') return { status: 'pending' };
    this.#requests.delete(id);
    return structuredClone(record.result);
  }
  poll() {
    for (const record of this.#requests.values()) if (!this.#running(record.owner) && !['admitting', 'active'].includes(record.phase)) this.#requests.delete(record.id);
    for (const owner of this.#history.keys()) if (!this.#running(owner) && ![...this.#requests.values()].some(record => record.owner === owner)) this.#history.delete(owner);
    for (const root of this.#roots) {
      try { if (this.#invocations.execution(root).phase === 'terminal') this.#roots.delete(root); }
      catch (error) { if (error.message !== 'invocation_unknown') throw error; this.#roots.delete(root); }
    }
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
      this.#launch(record, () => this.#activities.admit({ owner: record.owner, operation: record.request.operation, arguments: record.request.arguments, workId: record.id }), true);
    }
    return decision;
  }
  advance(interval) {
    this.poll();
    this.#updateSchedule();
    this.#policy.advance(interval);
  }
  state() {
    return { requests: this.#requests.size, active: this.#active, busy: this.#job !== null, fault: this.#fault, scheduling: this.#policy.state() };
  }
  #launch(record, operation, admitting) {
    this.#job = Promise.resolve().then(operation).then(result => {
      if (admitting && !result.receipt.disposition && result.receipt.phase !== 'admission_rejected') this.#policy.served(record.selection);
      if (result.evidence.released !== true || result.evidence.accountingComplete !== true) {
        record.phase = 'active'; this.#active = record.id; return;
      }
      const rejected = Boolean(result.receipt.disposition) || result.receipt.phase === 'admission_rejected';
      const status = rejected ? 'rejected' : result.receipt.state === 'SUCCEEDED' ? 'success' : result.receipt.state === 'CANCELLED' ? 'cancelled' : 'failure';
      record.result = { status, id: record.id, activity: copyMessage(result),
        ...(rejected ? { reason: name(result.receipt.reason) ? result.receipt.reason : result.receipt.disposition ?? 'admission_rejected' } : {}) };
      record.phase = 'finished'; this.#active = null;
      // An effect changes the feasibility basis even when its final material result is failure.
      this.#invalidatedThrough = this.#capture?.sequence ?? 0;
      for (const queued of this.#requests.values()) queued.assessment = null;
      this.#trace?.record('work.finished', record.result, { cleanup: true });
    }).catch(error => {
      const reason = String(error?.message ?? 'work_failed').slice(0, 256), active = this.#invocations.activity();
      if (active && [...active.subscribers, ...active.cleanupOwners].includes(record.owner)) {
        record.phase = 'active'; this.#active = record.id; this.#fault = reason;
      } else {
        record.phase = 'finished'; this.#active = null;
        record.result = { status: 'rejected', reason };
        if (!admissionRejections.has(reason)) this.#fault = reason;
      }
      if (this.#fault && this.#running(record.owner)) this.#invocations.failed(record.owner, reason);
      this.#trace?.record('work.rejected', { id: record.id, reason, fault: this.#fault }, { cleanup: true });
    }).finally(() => { this.#job = null; this.poll(); });
  }
  #updateSchedule() {
    const now = this.#now();
    this.#policy.update({ roots: [...this.#roots], offers: [...this.#requests.values()].filter(record => record.phase === 'queued').map(record => {
      const seen = record.assessment, current = seen && now >= seen.receivedAt && seen.ageUpperBoundMillis + now - seen.receivedAt < 2000;
      return { id: record.id, roots: [record.consumer], kind: record.rule.kind, priority: record.rule.priority, context: null,
        readiness: current ? seen.readiness : 'unknown' };
    }) });
  }
  #get(id) { const record = this.#requests.get(id); if (!record) throw Error('work_unknown'); return record; }
  #running(id) {
    try { return this.#invocations.execution(id).phase === 'running'; }
    catch (error) { if (error.message !== 'invocation_unknown') throw error; return false; }
  }
}
