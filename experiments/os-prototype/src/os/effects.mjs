import { createHash, randomUUID } from 'node:crypto';
import { copyMessage } from './value.mjs';
import { sameNativeId } from './native-id.mjs';

const canonical = value => Array.isArray(value) ? value.map(canonical) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])])) : value;
const fingerprint = payload => createHash('sha256').update(JSON.stringify(canonical(payload))
  .replace(/\u2028/g, '\\u2028').replace(/\u2029/g, '\\u2029')).digest('hex');

/** Trusted native submission boundary. Only callers with an admitted activity use this interface. */
export class EffectBroker {
  #native;
  #journal;
  #expectedWorld;
  #hostId;
  #lease = null;
  #sequence = 0;
  #active = null;
  #last = null;
  #flight = null;
  #shutdown = null;
  #recoveryBlocked = true;
  #timers;
  #renewal;
  #renewing = null;
  #leaseError = null;
  #starting = null;
  #stopping = false;
  #trace;

  constructor({ native, journal, expectedWorld, hostId = randomUUID(), timers = { setInterval, clearInterval }, trace }) {
    this.#native = native; this.#journal = journal; this.#expectedWorld = expectedWorld; this.#hostId = hostId;
    this.#timers = timers;
    this.#trace = trace;
    trace?.onFailure(() => this.stop());
  }
  start() {
    if (this.#stopping) return Promise.reject(Error('broker_stopping'));
    if (this.#starting) return Promise.reject(Error('broker_starting'));
    this.#starting = this.#start();
    return this.#starting.finally(() => { this.#starting = null; });
  }
  async #start() {
    this.#trace?.assertHealthy();
    if (this.#lease) throw Error('broker_started');
    let { frame } = await this.#call('os_observe');
    this.#checkWorld(frame);
    const unfinished = await this.#journal.unfinished();
    for (const entry of unfinished) await this.#recover(entry.intent);
    if ((await this.#journal.unfinished()).length) throw Error('reconciliation_required');
    if (unfinished.length) {
      frame = (await this.#call('os_observe')).frame;
      this.#checkWorld(frame);
    }
    if (this.#stopping) throw Error('broker_stopping');
    this.#lease = (await this.#call('os_lease', { action: 'acquire', hostId: this.#hostId, epoch: frame.epoch })).lease;
    this.#recoveryBlocked = false;
    this.#trace?.record('native.lease_acquired', { lease: this.#lease });
    if (this.#stopping) throw Error('broker_stopping');
    this.#renewal = this.#timers.setInterval(() => this.#renew(), 1000);
    this.#renewal?.unref?.();
  }
  execute(activity) { return this.#exclusive(() => this.#execute(activity)); }
  async #execute(activity) {
    this.#trace?.assertHealthy();
    if (this.#stopping) throw Error('broker_stopping');
    if (!this.#lease) throw Error('broker_not_started');
    if (this.#leaseError) throw this.#leaseError;
    if (this.#active) throw Error('effect_unresolved');
    this.#last = null;
    const { frame } = await this.#call('os_observe');
    this.#checkWorld(frame);
    if (this.#stopping) throw Error('broker_stopping');
    if (this.#leaseError) throw this.#leaseError;
    if (frame.epoch !== this.#lease.epoch) throw Error('stale_epoch');
    // The trusted coordinator admits its ledger bundle against this exact native frame.
    activity = copyMessage(typeof activity === 'function' ? activity(frame) : activity);
    const id = { epoch: this.#lease.epoch, generation: this.#lease.generation, sequence: ++this.#sequence };
    const payload = { operation: activity.operation, arguments: activity.arguments, captureId: frame.captureId };
    const request = { schemaVersion: 1, id, ...payload, payloadHash: fingerprint(payload) };
    this.#active = { id, request, payloadHash: request.payloadHash, definition: activity.definition, invocation: activity.invocation,
      ...(activity.provenance ? { provenance: activity.provenance } : {}) };
    await this.#journal.record(this.#active);
    this.#trace?.record('native.intent', this.#active);
    if (this.#stopping || this.#leaseError) {
      const receipt = { released: true, accountingComplete: true, disposition: 'not_submitted' };
      await this.#journal.settle(id, receipt);
      this.#trace?.record('native.recovered', { id, receipt }, { cleanup: true });
      this.#last = { id, ...receipt };
      this.#active = null;
      throw this.#leaseError ?? Error('broker_stopping');
    }
    let response;
    try { response = await this.#call('os_submit', request); }
    catch { response = await this.#call('os_inspect', { id }); }
    return await this.#receipt(response.receipt);
  }
  state() { return copyMessage({ unresolved: this.#active !== null, last: this.#last }); }
  cancel() {
    return this.#exclusive(async () => {
      if (!this.#active) return this.#last;
      if (!this.#lease) return this.#poll();
      let response;
      try { response = await this.#call('os_cancel', { lease: this.#lease, id: this.#active.id }); }
      catch { return this.#poll(); }
      return this.#receipt(response.receipt);
    });
  }
  poll() {
    if (this.#shutdown) return Promise.reject(Error('broker_stopping'));
    return this.#exclusive(() => this.#poll());
  }
  async #poll() {
    if (!this.#active) return this.#last;
    const resolution = await this.#recover(this.#active);
    if (!resolution) throw Error('reconciliation_required');
    this.#last = copyMessage(resolution.receipt);
    if (resolution.settled) this.#active = null;
    return copyMessage(this.#last);
  }
  stop() {
    if (this.#shutdown) return this.#shutdown;
    this.#stopping = true;
    this.#shutdown = this.#stop();
    return this.#shutdown.finally(() => { this.#shutdown = null; });
  }
  async #stop() {
    this.#timers.clearInterval(this.#renewal);
    if (this.#starting) await this.#starting.catch(() => {});
    const lease = this.#lease;
    let reason;
    if (lease) {
      try {
        await this.#call('os_lease', { action: 'release', lease });
        this.#lease = null;
        this.#trace?.record('native.lease_released', { lease }, { cleanup: true });
      } catch (error) {
        const authority = error.response?.authority;
        if (error.response?.code === 'stale_fence' && authority &&
            (authority.lease === null || (typeof authority.epoch === 'string' && authority.epoch !== lease.epoch) ||
             (Number.isSafeInteger(authority.generation) && authority.generation > lease.generation))) this.#lease = null;
        else reason = 'native_release_unconfirmed';
      }
    }
    await this.#renewing;
    if (this.#flight) await this.#flight.catch(() => {});
    if (this.#active) {
      try { await this.#poll(); } catch { reason ??= 'reconciliation_required'; }
    }
    return { released: this.#lease === null && this.#active === null && !this.#recoveryBlocked,
      ...(reason ? { reason } : {}) };
  }
  async #renew() {
    if (!this.#lease || this.#leaseError || this.#stopping) return;
    if (this.#renewing) return this.#renewing;
    this.#renewing = this.#call('os_lease', { action: 'heartbeat', lease: this.#lease })
      .catch(error => { this.#leaseError = error; }).finally(() => { this.#renewing = null; });
    return this.#renewing;
  }
  #exclusive(operation) {
    if (this.#flight) return Promise.reject(Error('effect_unresolved'));
    this.#flight = operation();
    return this.#flight.finally(() => { this.#flight = null; });
  }
  #checkWorld(frame) {
    if (!frame?.world?.alive || frame.world.worldId !== this.#expectedWorld || frame.world.controllerBusy || frame.world.reflexActive)
      throw Error('world_not_ready');
  }
  async #call(name, args = {}) {
    const response = await this.#native.call(name, args);
    if (response.status !== 'ok') throw Object.assign(Error(response.code ?? 'invalid_native_response'), { response });
    return response;
  }
  async #receipt(receipt) {
    const intent = this.#active;
    const settled = await this.#account(intent, receipt);
    this.#last = copyMessage(receipt);
    if (settled) this.#active = null;
    return copyMessage(receipt);
  }
  async #recover(intent) {
    let response;
    try { response = await this.#call('os_inspect', { id: intent.id }); }
    catch (error) { if (!error.response) throw error; response = error.response; }
    if (response.status === 'ok') return { settled: await this.#account(intent, response.receipt), receipt: response.receipt };
    const authority = response.authority;
    if (response.code === 'outcome_unknown' && authority?.epoch === intent.id.epoch && authority.generation === intent.id.generation &&
      authority.lease === null && authority.active === null && Number.isSafeInteger(authority.admissionSequence) && authority.admissionSequence >= 0 && authority.admissionSequence < intent.id.sequence) {
      const receipt = { released: true, accountingComplete: true, disposition: 'not_admitted', proof: authority };
      await this.#journal.settle(intent.id, receipt);
      this.#trace?.record('native.recovered', { id: intent.id, receipt }, { cleanup: true });
      return { settled: true, receipt: { id: intent.id, ...receipt } };
    }
  }
  async #account(intent, receipt) {
    if (!receipt || !sameNativeId(receipt.id, intent.id) || receipt.basis?.payloadHash !== intent.payloadHash ||
        receipt.basis.operation !== intent.request.operation || receipt.basis.captureId !== intent.request.captureId) throw Error('invalid_native_receipt');
    if (typeof receipt.released !== 'boolean' || !['ACCEPTED', 'RUNNING', 'RECONCILING', 'SUCCEEDED', 'FAILED', 'CANCELLED'].includes(receipt.state) ||
        (receipt.released && !['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(receipt.state))) throw Error('invalid_native_receipt');
    receipt = copyMessage(receipt);
    const released = receipt.released === true && receipt.effects?.releaseEvidence?.verified === true;
    const accountingComplete = receipt.effects?.accountingComplete === true;
    await this.#journal.settle(intent.id, { released, accountingComplete, native: receipt });
    this.#trace?.record('native.receipt', { id: intent.id, released, accountingComplete, native: receipt },
      { cleanup: this.#stopping || !['ACCEPTED', 'RUNNING'].includes(receipt.state) });
    return released && accountingComplete;
  }
}
