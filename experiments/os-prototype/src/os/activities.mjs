import { copyMessage } from './value.mjs';
import { nativeIdKey } from './native-id.mjs';
import { supplyOperations } from './supplies.mjs';

/** One trusted admission path joins invocation ownership, resource claims and native effects. */
export class ActivityCoordinator {
  #invocations;
  #ledger;
  #effects;
  #operations;
  #active = null;
  #sequence = 0;
  #busy = false;
  #fault = false;
  #last = null;
  #trace;

  constructor({ invocations, ledger, effects, operations, trace }) {
    this.#invocations = invocations; this.#ledger = ledger; this.#effects = effects;
    this.#operations = supplyOperations(operations);
    this.#trace = trace;
    trace?.onFailure(error => this.#drain(error));
  }
  async admit(request) {
    const attempt = { request: null, definition: null, consumer: null, basis: null, observation: null, bundle: null };
    try {
      request = copyMessage(request);
      attempt.request = request;
      return await this.#admit(request, attempt);
    } catch (error) {
      if (!attempt.admitted) {
        try { this.#trace?.record('activity.rejected', { ...attempt, reason: String(error?.message ?? 'admission_failed').slice(0, 256) }); }
        catch (traceError) { await this.#drain(traceError); }
      }
      throw error;
    }
  }
  async #admit(request, attempt) {
    if (this.#fault) throw Error('coordinator_failed');
    this.#trace?.assertHealthy();
    if (this.#busy || this.#active || this.#invocations.activity()) throw Error('player_owned');
    if (!request || !Array.isArray(request.deliveries ?? []) || (request.deliveries?.length ?? 0) > 32) throw Error('invalid_activity');
    if (request.workId !== undefined && (typeof request.workId !== 'string' || !request.workId.length || request.workId.length > 256)) throw Error('invalid_activity');
    const operation = this.#operations.get(request.operation);
    if (!operation) throw Error('operation_unknown');
    this.#busy = true;
    try {
      const receipt = await this.#effects.execute(frame => {
        attempt.basis = { epoch: frame.epoch, captureId: frame.captureId };
        const owner = this.#invocations.authorize(request.owner, operation.grant);
        attempt.definition = owner.definition; attempt.consumer = owner.consumer;
        const prepared = operation.prepare(frame, request.arguments);
        attempt.observation = prepared.observation; attempt.bundle = prepared.bundle;
        const deliveries = request.deliveries ?? [];
        for (const delivery of deliveries) {
          const subscriber = this.#invocations.authorize(delivery.owner, operation.grant);
          if (this.#ledger.delivery(delivery.id).spec.consumer !== subscriber.consumer) throw Error('demand_owner_mismatch');
        }
        this.#ledger.observe(prepared.observation);
        const sequence = ++this.#sequence, id = `activity:${sequence}`;
        this.#ledger.reserve(id, owner.consumer, prepared.bundle, prepared.observation.revision);
        let supplyId = null;
        try {
          if (deliveries.length) {
            this.#ledger.beginSupply(sequence, { epoch: frame.epoch, resource: prepared.destination, method: request.operation,
              expected: prepared.quantity, deliveries: deliveries.map(({ id, quantity }) => ({ id, quantity })) });
            supplyId = sequence;
          }
          this.#invocations.trackActivity(id, [...new Set([request.owner, ...deliveries.map(delivery => delivery.owner)])]);
        } catch (error) {
          if (supplyId !== null) { this.#ledger.settleSupply(supplyId, { released: true, accountingComplete: true }); this.#ledger.closeSupply(supplyId); }
          this.#ledger.settle(id, { released: true, accountingComplete: true, consumed: {} });
          throw error;
        }
        this.#active = { id, prepared, operation, supplyId, deliveries, cancelRequested: false };
        attempt.admitted = true;
        this.#trace?.record('activity.admitted', { id, owner: request.owner, definition: owner.definition, consumer: owner.consumer,
          observation: prepared.observation, bundle: prepared.bundle, deliveries, supplyId, ...(request.workId ? { workId: request.workId } : {}) });
        return { definition: owner.definition, invocation: request.owner, operation: operation.nativeOperation, arguments: prepared.arguments,
          provenance: { activityId: id, consumer: owner.consumer, bundle: prepared.bundle, deliveries, ...(request.workId ? { workId: request.workId } : {}) } };
      });
      return this.#account(receipt);
    } catch (error) {
      if (this.#active) {
        const state = this.#effects.state();
        if (!state.unresolved && state.last === null) this.#settle({ released: true, accountingComplete: true, consumed: {}, produced: {}, disposition: 'not_submitted' });
        else await this.#drain(error);
      }
      throw error;
    } finally { this.#busy = false; }
  }
  async poll() {
    if (this.#busy) throw Error('activity_busy');
    if (!this.#active) return this.#last;
    this.#busy = true;
    try {
      const activity = this.#syncWithdrawals();
      if (activity.stopRequested && !this.#active.cancelRequested) {
        const receipt = await this.#effects.cancel();
        this.#active.cancelRequested = true;
        return this.#account(receipt);
      }
      return this.#account(await this.#effects.poll());
    }
    catch (error) { await this.#drain(error); throw error; }
    finally { this.#busy = false; }
  }
  join(request) {
    if (this.#fault) throw Error('coordinator_failed');
    this.#trace?.assertHealthy();
    if (this.#busy) throw Error('activity_busy');
    request = copyMessage(request);
    if (!this.#active || this.#active.id !== request.activityId || this.#active.supplyId === null) throw Error('activity_unknown');
    const activity = this.#syncWithdrawals();
    if (activity.stopRequested) throw Error('activity_stopping');
    const subscriber = this.#invocations.authorize(request.owner, this.#active.operation.grant);
    const demand = this.#ledger.delivery(request.deliveryId);
    if (demand.spec.consumer !== subscriber.consumer) throw Error('demand_owner_mismatch');
    const existing = this.#active.deliveries.find(delivery => delivery.id === request.deliveryId);
    if (existing) {
      if (existing.owner !== request.owner || existing.quantity !== request.quantity) throw Error('delivery_join_conflict');
      return demand;
    }
    if (this.#active.deliveries.length >= 32) throw Error('activity_subscriber_capacity');
    this.#trace?.record('activity.delivery_joining', { ...request, consumer: subscriber.consumer });
    // All checks and both mutations are synchronous: an owner cannot close between them.
    this.#ledger.joinSupply(this.#active.supplyId, request.deliveryId, request.quantity);
    this.#invocations.subscribeActivity(activity.id, request.owner);
    this.#active.deliveries.push({ id: request.deliveryId, owner: request.owner, quantity: request.quantity });
    this.#trace?.record('activity.delivery_joined', { activityId: activity.id, ...request, delivery: this.#ledger.delivery(request.deliveryId) }, { cleanup: true });
    return this.#ledger.delivery(request.deliveryId);
  }
  async #drain(error) {
    this.#fault = true;
    // Start revocation before failure propagation, which can retire sibling handles.
    const stopping = this.#effects.stop();
    try {
      for (const owner of this.#invocations.activity()?.subscribers ?? []) {
        if (this.#invocations.activity()?.subscribers.includes(owner)) this.#invocations.failed(owner, error.message);
      }
    } catch { /* Revocation and unresolved ownership survive a failed notification. */ }
    try {
      await stopping;
      const last = this.#effects.state().last;
      if (last && this.#active) this.#account(last);
    } catch { /* Keep the original failure and every unresolved owner/claim. */ }
  }
  #syncWithdrawals() {
    const activity = this.#invocations.activity();
    if (!activity || activity.id !== this.#active.id) throw Error('activity_owner_mismatch');
    for (const delivery of this.#active.deliveries) if (!activity.subscribers.includes(delivery.owner)) this.#ledger.cancelDelivery(delivery.id);
    return activity;
  }
  #account(receipt) {
    // A subscriber can withdraw while the native response or its journal write is in flight.
    this.#syncWithdrawals();
    const evidence = receipt.disposition === 'not_admitted' || receipt.disposition === 'not_submitted'
      ? { released: receipt.released === true, accountingComplete: receipt.accountingComplete === true, consumed: {}, produced: {}, disposition: receipt.disposition }
      : this.#active.operation.account(this.#active.prepared, receipt);
    if (this.#active.supplyId !== null && !['ACCEPTED', 'RUNNING'].includes(receipt.state)) {
      // Native revocation can precede any subscriber cancellation on the host.
      this.#ledger.settleSupply(this.#active.supplyId, { released: false, accountingComplete: false });
    }
    let credits = [];
    if (this.#active.supplyId !== null && evidence.accountingComplete === true && receipt.id && !receipt.disposition) {
      credits = this.#ledger.creditSupply(this.#active.supplyId, { effectId: nativeIdKey(receipt.id),
        quantity: evidence.produced[this.#active.prepared.destination] ?? 0, accountingComplete: true }).credits;
    }
    this.#last = { activityId: this.#active.id, receipt: copyMessage(receipt), evidence, credits };
    this.#trace?.record('activity.accounted', this.#last,
      { cleanup: this.#fault || evidence.released === true || !['ACCEPTED', 'RUNNING'].includes(receipt.state) });
    this.#settle(evidence);
    return copyMessage(this.#last);
  }
  #settle(evidence) {
    if (!this.#active) return;
    const { id } = this.#active;
    if (evidence.released !== true || evidence.accountingComplete !== true) return;
    this.#trace?.record('claim.released', { activityId: id, supplyId: this.#active.supplyId, evidence }, { cleanup: true });
    this.#ledger.settle(id, evidence);
    if (this.#active.supplyId !== null) {
      this.#ledger.settleSupply(this.#active.supplyId, evidence);
      this.#ledger.closeSupply(this.#active.supplyId);
    }
    this.#invocations.settleActivity(id, evidence);
    this.#active = null;
  }
}
