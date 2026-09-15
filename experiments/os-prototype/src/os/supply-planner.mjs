import { copyMessage } from './value.mjs';
import { contentDigest } from './content.mjs';
import { supplyOperations } from './supplies.mjs';

const name = value => typeof value === 'string' && value.length > 0 && value.length <= 256;
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);

/** Read-only bounded procurement proposals. They neither reserve output nor admit physical work. */
export class SupplyPlanner {
  #resources;
  #rules;
  #routes;
  #epoch;

  constructor({ resources, operations, rules, epoch }) {
    const catalog = supplyOperations(operations), copied = copyMessage(rules);
    if (!name(epoch) || !object(copied) || Object.keys(copied).length > 32) throw Error('invalid_supply_rules');
    const routes = new Map();
    for (const [id, rule] of Object.entries(copied)) {
      if (!name(id) || !object(rule) || !name(rule.resource) || !catalog.has(rule.operation) || !name(catalog.get(rule.operation).grant) ||
          !object(rule.arguments) || Object.hasOwn(rule.arguments, 'quantity') ||
          !Number.isSafeInteger(rule.maximum) || rule.maximum < 1 || rule.maximum > 64 ||
          Object.keys(rule).some(key => !['resource', 'operation', 'arguments', 'maximum'].includes(key))) throw Error('invalid_supply_rules');
      copyMessage(rule.arguments, 4096, { maximumNodes: 1024, maximumDepth: 12 });
      if (!routes.has(rule.resource)) routes.set(rule.resource, new Set());
      routes.get(rule.resource).add(rule.operation);
    }
    this.#routes = Object.fromEntries([...routes].map(([resource, methods]) => [resource, [...methods]]));
    this.#resources = resources; this.#rules = copied; this.#epoch = epoch;
  }
  offers() {
    const needs = this.#resources.procurement(this.#routes), offers = [];
    const rows = [...needs.deliveries.map(value => ({ ...value, kind: 'delivery' })),
      ...needs.targets.map(value => ({ ...value, kind: 'target' }))];
    for (const [id, rule] of Object.entries(this.#rules)) {
      const eligible = rows.filter(need => need.epoch === this.#epoch && need.owner !== null && need.quantity > 0 &&
        need.resource === rule.resource && need.methods.includes(rule.operation));
      const consumers = [...new Set(eligible.map(need => need.consumer))];
      // Every eligible consumer can anchor a bounded batch; an earlier large demand cannot hide another root.
      for (const anchor of consumers) {
        const ordered = [...eligible].sort((a, b) => Number(b.consumer === anchor) - Number(a.consumer === anchor) ||
          b.priority - a.priority || Number(a.kind === 'target') - Number(b.kind === 'target'));
        const offer = { rule: id, epoch: this.#epoch, anchor, operation: rule.operation, resource: rule.resource, owner: null,
          consumers: [], owners: [], deliveries: [], targets: [], quantity: 0, priority: 0 };
        for (const need of ordered) {
          if (offer.quantity === rule.maximum) break;
          if (need.kind === 'delivery' && offer.deliveries.length === 32) continue;
          const quantity = Math.min(need.quantity, rule.maximum - offer.quantity);
          offer.owner ??= need.owner;
          if (!offer.owners.includes(need.owner)) offer.owners.push(need.owner);
          if (!offer.consumers.includes(need.consumer)) offer.consumers.push(need.consumer);
          if (need.kind === 'delivery') offer.deliveries.push({ id: need.id, owner: need.owner, quantity });
          else offer.targets.push({ owner: need.owner, consumer: need.consumer, quantity });
          offer.quantity += quantity; offer.priority = Math.max(offer.priority, need.priority);
        }
        offer.arguments = { ...rule.arguments, quantity: offer.quantity };
        offers.push(copyMessage({ id: `supply:${contentDigest(offer)}`, ...offer }));
      }
    }
    return offers;
  }
  resolve(id) {
    const offer = this.offers().find(offer => offer.id === id);
    if (!offer) throw Error('supply_offer_stale');
    return offer;
  }
}
