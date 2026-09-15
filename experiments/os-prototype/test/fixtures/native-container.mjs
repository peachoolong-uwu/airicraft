export class NativeContainer {
  constructor({ itemId = 'minecraft:string', now = () => performance.now() } = {}) { this.itemId = itemId; this.now = now; }
  lease = null;
  receipt = null;
  submissions = 0;
  cancellations = 0;
  quantity = 4;
  playerQuantity = 0;
  capture = 0;
  windowOpen = true;
  serverTick = 0;
  sessionId = 'bridge';
  beforeReply = async () => {};
  async call(name, args = {}) {
    let result = {};
    if (name === 'os_observe') result.frame = { schemaVersion: 1, sessionId: this.sessionId, epoch: 'world', captureId: `capture-${++this.capture}`,
      captureSequence: this.capture, capturedAtNanos: String(this.capture), clockDomain: 'native:bridge', source: 'fixture',
      clientTick: this.capture, serverTick: this.serverTick, receivedAtHostMillis: this.now(), captureAgeUpperBoundMillis: 0,
      world: { worldId: 'fixture', dimension: 'overworld', alive: true, controllerBusy: false, reflexActive: false },
      facts: { inventory: { available: true, slots: Array.from({ length: 36 }, (_, id) => ({ id, container: false,
        itemId: id === 0 && this.playerQuantity ? this.itemId : '', variant: '', count: id === 0 ? this.playerQuantity : 0, maxCount: 64 })) },
      supportedItems: [this.itemId], window: { open: this.windowOpen, windowId: 'home-window', syncId: 1, cursor: { count: 0 }, slots: [
        { id: 0, container: true, itemId: this.itemId, variant: '', count: this.quantity, maxCount: 64 },
        { id: 1, container: false, itemId: this.playerQuantity ? this.itemId : '', variant: '', count: this.playerQuantity, maxCount: 64 }
      ] } } };
    else if (name === 'os_lease') {
      if (args.action === 'acquire') this.lease = { epoch: 'world', generation: 1, hostId: args.hostId };
      if (args.action === 'release') this.lease = null;
      result.lease = this.lease;
    } else if (name === 'os_submit') {
      this.submissions++;
      this.receipt = { id: args.id, state: 'ACCEPTED', released: false, basis: { operation: args.operation, captureId: args.captureId, payloadHash: args.payloadHash }, effects: {} };
      result.receipt = this.receipt;
    } else if (name === 'os_inspect') result.receipt = this.receipt;
    else if (name === 'os_cancel') { this.cancellations++; this.receipt.state = 'RECONCILING'; result.receipt = this.receipt; }
    else throw Error('unknown_native_method');
    const authority = { epoch: 'world', lease: this.lease && { ...this.lease }, active: this.receipt?.released === false ? this.receipt.id : null };
    await this.beforeReply(name, result);
    // Like the JSON transport, a delivered response cannot change when later native progress arrives.
    return structuredClone({ status: 'ok', authority, ...result });
  }
  progress(quantity, transferred, released, state = 'SUCCEEDED') {
    Object.assign(this.receipt, { state: released ? state : 'RUNNING', released,
      effects: { quantity, transferred, remaining: quantity - transferred, accountingComplete: true, releaseEvidence: { verified: released } } });
  }
}
