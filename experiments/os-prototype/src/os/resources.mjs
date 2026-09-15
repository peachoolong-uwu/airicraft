import { copyMessage } from './value.mjs';
import { contentDigest } from './content.mjs';
import { supplyOperations } from './supplies.mjs';

const name = value => typeof value === 'string' && value.length > 0 && value.length <= 256;
const quantity = value => Number.isSafeInteger(value) && value >= 0 && value <= 2_147_483_647;
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);

/** Owned declarations over the one ledger. Supply selection and physical admission remain OS work. */
export class ResourceService {
  #invocations;
  #ledger;
  #resources;
  #operations;
  #trace;
  #targets = new Map();
  #deliveries = new Map();
  #history = new Map();
  #order = 0;

  constructor({ invocations, ledger, resources, operations, trace }) {
    resources = copyMessage(resources);
    this.#operations = supplyOperations(operations);
    if (!object(resources) || Object.keys(resources).length > 64) throw Error('invalid_resource_configuration');
    const keys = new Set();
    for (const [alias, spec] of Object.entries(resources)) {
      if (!name(alias) || !object(spec) || Object.keys(spec).some(key => !['key', 'grant', 'methods', 'priority'].includes(key)) ||
          !name(spec.key) || !name(spec.grant) || keys.has(spec.key) || !quantity(spec.priority ?? 0) ||
          !Array.isArray(spec.methods) || spec.methods.length > 32 || new Set(spec.methods).size !== spec.methods.length ||
          spec.methods.some(method => !name(method) || !this.#operations.has(method) || !name(this.#operations.get(method).grant))) throw Error('invalid_resource_configuration');
      keys.add(spec.key); spec.priority ??= 0;
    }
    this.#resources = new Map(Object.entries(resources));
    this.#invocations = invocations; this.#ledger = ledger; this.#trace = trace;
  }
  target(owner, resource, amount) {
    const { spec, consumer } = this.#authorize(owner, resource);
    if (!quantity(amount)) throw Error('invalid_target');
    const key = JSON.stringify([consumer, spec.key]), previous = this.#targets.get(key);
    if (!previous && amount > 0 && this.#targets.size >= 1024) throw Error('target_capacity');
    if (!previous && amount > 0 && this.#order === 2_147_483_647) throw Error('target_sequence_exhausted');
    const waitingSince = previous?.waitingSince ?? (amount > 0 ? ++this.#order : 0);
    this.#ledger.target(consumer, spec.key, amount, { priority: spec.priority, waitingSince });
    if (amount === 0) this.#targets.delete(key);
    else this.#targets.set(key, { consumer, name: resource, resource: spec.key, quantity: amount, waitingSince });
    this.#trace?.record('resource.target', { owner, consumer, resource, key: spec.key, quantity: amount, waitingSince });
    return { status: 'registered', resource, quantity: amount };
  }
  request(owner, sequence, request) {
    request = copyMessage(request);
    if (!Number.isSafeInteger(sequence) || sequence < 1 || !object(request) ||
        Object.keys(request).some(key => !['resource', 'quantity', 'methods'].includes(key)) || !quantity(request.quantity) || request.quantity === 0) throw Error('invalid_demand');
    const { spec, consumer } = this.#authorize(owner, request.resource);
    const methods = this.#methods(owner, spec, request.methods ?? []);
    if (!methods.length) throw Error('supply_method_unavailable');
    this.poll();
    const digest = contentDigest({ resource: request.resource, quantity: request.quantity, methods }), history = this.#history.get(owner);
    const existing = [...this.#deliveries.values()].find(record => record.owner === owner && record.sequence === sequence);
    if (existing) {
      if (existing.digest !== digest) throw Error('demand_conflict');
      return existing.id;
    }
    if (history && sequence <= history.sequence) {
      throw Error('demand_retired');
    }
    if ([...this.#deliveries.values()].filter(record => record.owner === owner).length >= 32) throw Error('invocation_demand_capacity');
    const delivery = this.#ledger.createDelivery({ consumer, resource: spec.key, quantity: request.quantity, methods });
    this.#deliveries.set(delivery.id, { id: delivery.id, owner, name: request.resource, consumer, reason: null, sequence, digest });
    this.#history.set(owner, { sequence });
    this.#trace?.record('resource.requested', { id: delivery.id, owner, consumer, sequence, digest, epoch: delivery.epoch,
      resource: spec.key, quantity: request.quantity, methods });
    return delivery.id;
  }
  take(owner, id) {
    const record = this.#deliveries.get(id);
    if (!record || record.owner !== owner) throw Error('demand_unknown');
    this.#authorize(owner, record.name);
    const { delivery } = this.#update(record);
    if (delivery.state === 'pending') return { status: 'pending' };
    if (!this.#remove(record)) return { status: 'pending' };
    const outcome = { status: record.reason ?? delivery.state, id, resource: record.name, epoch: delivery.epoch,
      quantity: delivery.spec.quantity, credited: delivery.credited };
    this.#trace?.record('resource.delivered', { owner, outcome });
    return outcome;
  }
  poll() {
    for (const [key, target] of this.#targets) {
      if (['running', 'closing'].includes(this.#phase(target.consumer))) continue;
      this.#ledger.target(target.consumer, target.resource, 0); this.#targets.delete(key);
      this.#trace?.record('resource.target_retired', target, { cleanup: true });
    }
    for (const record of this.#deliveries.values()) {
      const { abandoned } = this.#update(record);
      if (abandoned) this.#remove(record);
    }
    for (const owner of this.#history.keys()) if (this.#phase(owner) !== 'running' &&
        ![...this.#deliveries.values()].some(record => record.owner === owner)) this.#history.delete(owner);
  }
  /** Trusted scheduler view, bounded by 256 retained requests; never a guest response. */
  pending() {
    this.poll();
    return [...this.#deliveries.values()].flatMap(record => {
      const delivery = this.#ledger.delivery(record.id);
      if (record.reason || delivery.unallocated === 0) return [];
      return [{ id: record.id, owner: record.owner, consumer: record.consumer, name: record.name, resource: delivery.spec.resource,
        epoch: delivery.epoch, quantity: delivery.unallocated, methods: [...delivery.spec.methods], priority: this.#resources.get(record.name).priority }];
    });
  }
  /** Targets protect a root consumer; they do not convert missing/last-seen stock into production. */
  shortages() {
    this.poll();
    const stocks = new Map();
    return [...this.#targets.values()].flatMap(target => {
      if (!stocks.has(target.resource)) stocks.set(target.resource, this.#ledger.stock(target.resource));
      const stock = stocks.get(target.resource), missing = stock.known && !this.#ledger.needsObservation
        ? Math.max(0, target.quantity - (stock.allocated[target.consumer] ?? 0)) : null;
      if (missing === 0) return [];
      const spec = this.#resources.get(target.name), running = this.#phase(target.consumer) === 'running';
      return [{ ...target, epoch: this.#ledger.epoch, missing, owner: running ? target.consumer : null,
        methods: running ? this.#methods(target.consumer, spec, []) : [], priority: spec.priority }];
    });
  }
  /** Union demand estimates, not stock: outstanding deliveries can also satisfy their own consumer's floor. */
  procurement(routes = null) {
    if (routes !== null) {
      routes = copyMessage(routes);
      if (!object(routes) || Object.keys(routes).length > 64 || Object.entries(routes).some(([resource, methods]) =>
        !name(resource) || !Array.isArray(methods) || methods.length > 32 || new Set(methods).size !== methods.length ||
        methods.some(method => !name(method) || !this.#operations.has(method)))) throw Error('invalid_supply_routes');
    }
    this.poll();
    const committed = new Map();
    for (const delivery of this.#ledger.pendingDeliveries()) {
      if (delivery.epoch !== this.#ledger.epoch) continue;
      const key = JSON.stringify([delivery.spec.consumer, delivery.spec.resource]);
      const methods = routes && Object.hasOwn(routes, delivery.spec.resource) ? routes[delivery.spec.resource] : [];
      const applicable = routes === null || delivery.spec.methods.some(method => methods.includes(method));
      committed.set(key, (committed.get(key) ?? 0) + (applicable ? delivery.outstanding : delivery.allocated));
    }
    return { deliveries: this.pending(), targets: this.shortages().map(target => ({ ...target,
      quantity: target.missing === null ? null : Math.max(0, target.missing - (committed.get(JSON.stringify([target.consumer, target.resource])) ?? 0))
    })), targetSlots: this.#ledger.availableDeliverySlots };
  }
  state() { return { targets: this.#targets.size, deliveries: this.#deliveries.size, ownerCursors: this.#history.size }; }
  get resourceKeys() { return [...this.#resources.values()].map(spec => spec.key); }
  #authorize(owner, resource) {
    const spec = this.#resources.get(resource);
    if (!spec) throw Error('resource_unknown');
    const { consumer } = this.#invocations.authorize(owner, spec.grant);
    return { spec, consumer };
  }
  #methods(owner, spec, requested) {
    if (!Array.isArray(requested) || requested.length > 32 || requested.some(method => !name(method))) throw Error('invalid_demand');
    const explicit = requested.length > 0, methods = [...new Set(explicit ? requested : spec.methods)].sort(), granted = [];
    for (const method of methods) {
      if (!spec.methods.includes(method)) throw Error('supply_method_unavailable');
      try { this.#invocations.authorize(owner, this.#operations.get(method).grant); granted.push(method); }
      catch (error) { if (explicit || error.message !== 'operation_not_granted') throw error; }
    }
    return granted;
  }
  #remove(record) {
    try { this.#ledger.closeDelivery(record.id); }
    catch (error) { if (error.message === 'demand_unsettled') return false; throw error; }
    this.#deliveries.delete(record.id);
    return true;
  }
  #update(record) {
    let delivery = this.#ledger.delivery(record.id);
    const abandoned = this.#phase(record.owner) !== 'running';
    if (abandoned || delivery.epoch !== this.#ledger.epoch) {
      record.reason ??= abandoned ? 'cancelled' : 'epoch_changed';
      if (!delivery.withdrawn) {
        delivery = this.#ledger.cancelDelivery(record.id).delivery;
        this.#trace?.record('resource.withdrawn', { id: record.id, owner: record.owner, reason: record.reason, credited: delivery.credited }, { cleanup: true });
      }
    }
    return { delivery, abandoned };
  }
  #phase(id) {
    try { return this.#invocations.execution(id).phase; }
    catch (error) { if (error.message === 'invocation_unknown') return null; throw error; }
  }
}
