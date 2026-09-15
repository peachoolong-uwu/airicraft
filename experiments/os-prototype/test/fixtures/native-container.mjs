export class NativeContainer {
  constructor({ itemId = 'minecraft:string' } = {}) { this.itemId = itemId; }
  lease = null;
  receipt = null;
  submissions = 0;
  cancellations = 0;
  quantity = 4;
  capture = 0;
  beforeReply = async () => {};
  async call(name, args = {}) {
    let result = {};
    if (name === 'os_observe') result.frame = { epoch: 'world', captureId: `capture-${++this.capture}`, world: { worldId: 'fixture', dimension: 'overworld', alive: true },
      facts: { supportedItems: [this.itemId], window: { open: true, windowId: 'home-window', syncId: 1, cursor: { count: 0 }, slots: [
        { id: 0, container: true, itemId: this.itemId, variant: '', count: this.quantity, maxCount: 64 },
        { id: 1, container: false, itemId: '', variant: '', count: 0, maxCount: 64 }
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
    await this.beforeReply(name);
    return { status: 'ok', ...result };
  }
  progress(quantity, transferred, released, state = 'SUCCEEDED') {
    Object.assign(this.receipt, { state: released ? state : 'RUNNING', released,
      effects: { quantity, transferred, remaining: quantity - transferred, accountingComplete: true, releaseEvidence: { verified: released } } });
  }
}
