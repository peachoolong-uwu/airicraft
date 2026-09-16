import { NativeContainer } from './native-container.mjs';

/** Simulated native transport with separate context/child receipts and explicit completion. */
export class NativeContextContainer extends NativeContainer {
  context = null;
  receipts = new Map();
  requests = [];
  generation = 0;
  admissionSequence = 0;
  loseReply = false;
  beforeSubmit = async () => {};
  beforeCancel = async () => {};
  afterCancel = async () => {};
  authority() {
    return structuredClone({ epoch: 'world', generation: this.generation, admissionSequence: this.admissionSequence,
      lease: this.lease, active: this.receipt?.released === false ? this.receipt : null, context: this.context });
  }
  async call(name, args = {}) {
    let result = {};
    if (name === 'os_observe') result = await super.call(name, args);
    else if (name === 'os_lease') {
      if (args.action === 'acquire') {
        if (this.context || this.receipt?.released === false) return { status: 'rejected', code: 'player_owned', authority: this.authority() };
        this.lease = { epoch: 'world', generation: ++this.generation, hostId: args.hostId };
      }
      if (args.action === 'release') { this.lease = null; if (this.context) this.context.state = 'RECONCILING'; }
      result.lease = this.lease;
    } else if (name === 'os_submit') {
      await this.beforeSubmit(args);
      if (this.lease?.generation !== args.id.generation) return { status: 'rejected', code: 'stale_fence', authority: this.authority() };
      const key = JSON.stringify(args.id);
      if (!this.receipts.has(key)) {
        this.requests.push(structuredClone(args)); this.submissions++; this.admissionSequence = args.id.sequence;
        const receipt = { id: args.id, state: 'ACCEPTED', phase: 'accepted', released: false,
          basis: { operation: args.operation, captureId: args.captureId, payloadHash: args.payloadHash }, effects: {} };
        if (args.operation === 'retain_container') {
          if (this.context || !this.windowOpen) throw Error('context_unavailable');
          this.context = receipt;
          receipt.effects = { contextId: `context-${args.id.sequence}` };
        } else {
          if (this.context && args.arguments.contextId !== this.context.effects.contextId) throw Error('wrong_context');
          this.receipt = receipt;
          if (this.context) receipt.effects.contextId = this.context.effects.contextId;
        }
        this.receipts.set(key, receipt);
      }
      result.receipt = this.receipts.get(key);
      if (this.loseReply) { this.loseReply = false; throw Error('lost_reply'); }
    } else if (name === 'os_inspect') {
      result.receipt = this.receipts.get(JSON.stringify(args.id));
      if (!result.receipt) return { status: 'rejected', code: 'outcome_unknown', authority: this.authority() };
    } else if (name === 'os_cancel') {
      await this.beforeCancel(args);
      result.receipt = this.receipts.get(JSON.stringify(args.id));
      result.receipt.state = 'RECONCILING'; this.cancellations++;
      await this.afterCancel(args);
    } else throw Error('unknown_native_method');
    return structuredClone({ ...result, status: 'ok', authority: this.authority() });
  }
  ready() { this.context.state = 'RUNNING'; this.context.phase = 'context_ready'; Object.assign(this.context.effects, { contextReady: true, completedOperations: 0 }); }
  closeContext() {
    if (this.receipt?.released === false) throw Error('child_unreleased');
    Object.assign(this.context, { state: 'CANCELLED', released: true, phase: 'context_closed',
      effects: { ...this.context.effects, contextReady: false, accountingComplete: true, releaseEvidence: { verified: true } } });
    this.context = null; this.windowOpen = false;
  }
  progress(quantity, transferred, released, state = 'SUCCEEDED') {
    const before = this.receipt.effects.transferred ?? 0;
    super.progress(quantity, transferred, released, state);
    this.quantity -= transferred - before; this.playerQuantity += transferred - before;
    if (this.context) {
      this.receipt.effects.contextId = this.context.effects.contextId;
      this.receipt.effects.windowRetained = released;
      if (released) this.context.effects.completedOperations++;
    } else if (released) this.windowOpen = false;
  }
}
