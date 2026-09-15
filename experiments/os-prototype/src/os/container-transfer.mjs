import { copyMessage } from './value.mjs';
import { itemResource, itemSlots, itemStocks, observeInventory } from './item-observation.mjs';

const text = value => typeof value === 'string' && value.length > 0 && value.length <= 256;
const count = value => Number.isSafeInteger(value) && value >= 0 && value <= 64;
const capacityFor = (slots, container, item) => slots.filter(slot => slot.container === container).reduce((sum, slot) => sum +
  (slot.count === 0 ? item.maxCount : slot.itemId === item.itemId && slot.variant === item.variant ? slot.maxCount - slot.count : 0), 0);

/** First domain adapter: a transfer in one explicitly bound, already-open container. */
export class ContainerTransfer {
  #scope;
  #windowId;
  constructor({ scope, windowId }) {
    if (!text(scope) || scope.length > 64 || scope.includes('/') || !text(windowId)) throw Error('invalid_container_binding');
    this.#scope = scope; this.#windowId = windowId;
  }
  get grant() { return `container:${this.#scope}`; }
  get nativeOperation() { return 'transfer_container'; }
  resource(side, itemId, variant) {
    if (!['container', 'player'].includes(side)) throw Error('invalid_item_identity');
    return itemResource(side === 'container' ? this.grant : 'player', itemId, variant);
  }
  observe(frame, knownKeys = []) {
    const observation = { epoch: frame.epoch, revision: frame.captureId, stocks: observeInventory(frame, knownKeys), assets: {}, capacities: {}, targets: [] };
    const window = frame.facts?.window;
    if (!window?.open || window.windowId !== this.#windowId) return observation;
    const slots = itemSlots(window.slots);
    Object.assign(observation.stocks, itemStocks(slots.filter(slot => slot.container), this.grant, knownKeys));
    // The open handler is authoritative for transfer capacity. Its player slots are separate from main-inventory IDs.
    for (const item of slots.filter(slot => slot.count > 0)) for (const side of [true, false]) {
      const key = this.resource(side ? 'container' : 'player', item.itemId, item.variant);
      observation.capacities[key] = capacityFor(slots, side, item);
    }
    observation.targets = [this.grant];
    return observation;
  }
  prepare(frame, args) {
    args = copyMessage(args);
    if (!args || !['deposit', 'withdraw'].includes(args.direction) || !text(args.itemId) || !count(args.quantity) || args.quantity < 1 ||
        Object.keys(args).some(key => !['direction', 'itemId', 'quantity'].includes(key))) throw Error('invalid_transfer');
    if (!frame.facts?.supportedItems?.includes(args.itemId)) throw Error('unsupported_transfer_item');
    const window = copyMessage(frame.facts.window, 131_072);
    if (!window?.open || window.windowId !== this.#windowId || !Number.isSafeInteger(window.syncId) || window.syncId < 0 ||
        window.cursor?.count !== 0 || !Array.isArray(window.slots) || window.slots.length > 90 ||
        new Set(window.slots.map(slot => slot.id)).size !== window.slots.length) throw Error('container_changed');
    itemSlots(window.slots);
    const fromContainer = args.direction === 'withdraw';
    const sources = window.slots.filter(slot => slot.container === fromContainer && slot.count > 0 && slot.itemId === args.itemId);
    if (!sources.length) throw Error('resource_unavailable');
    const variant = sources[0].variant, maxCount = sources[0].maxCount;
    // Native transfer currently chooses by item ID. Do not authorize it across unlike components.
    if (sources.some(slot => slot.variant !== variant || slot.maxCount !== maxCount)) throw Error('ambiguous_item_components');
    const source = this.resource(fromContainer ? 'container' : 'player', args.itemId, variant);
    const destination = this.resource(fromContainer ? 'player' : 'container', args.itemId, variant);
    const stocks = { [source]: 0, [destination]: 0 };
    for (const slot of window.slots) if (slot.count > 0) {
      const resource = this.resource(slot.container ? 'container' : 'player', slot.itemId, slot.variant);
      stocks[resource] = (stocks[resource] ?? 0) + slot.count;
    }
    const capacity = capacityFor(window.slots, !fromContainer, sources[0]);
    return { source, destination, quantity: args.quantity,
      observation: { epoch: frame.epoch, revision: frame.captureId, stocks, assets: {}, capacities: { [destination]: capacity }, targets: [this.grant] },
      bundle: { inputs: { [source]: args.quantity }, capacities: { [destination]: args.quantity }, targets: [this.grant] },
      arguments: { ...args, windowId: window.windowId, syncId: window.syncId,
        allowance: { sourceItems: args.quantity, destinationItems: args.quantity } } };
  }
  account(prepared, receipt) {
    const effects = receipt.effects ?? {};
    if (receipt.phase === 'admission_rejected' && receipt.state === 'FAILED' && effects.admitted === false &&
        receipt.released === true && effects.accountingComplete === true && effects.releaseEvidence?.verified === true)
      return { released: true, accountingComplete: true, consumed: {}, produced: {}, nativeId: receipt.id,
        nativeState: receipt.state, releaseEvidence: effects.releaseEvidence };
    if (receipt.released !== true && !Object.keys(effects).length)
      return { released: false, accountingComplete: false, consumed: {}, produced: {} };
    if (!count(effects.transferred) || effects.quantity !== prepared.quantity || effects.remaining !== prepared.quantity - effects.transferred ||
        effects.transferred > prepared.quantity) throw Error('invalid_transfer_accounting');
    return { released: receipt.released === true && effects.releaseEvidence?.verified === true, accountingComplete: effects.accountingComplete === true,
      consumed: { [prepared.source]: effects.transferred }, produced: { [prepared.destination]: effects.transferred },
      nativeId: receipt.id, nativeState: receipt.state, releaseEvidence: effects.releaseEvidence ?? { verified: false } };
  }
}
