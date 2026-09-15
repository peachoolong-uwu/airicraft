import { copyMessage } from './value.mjs';
import { executionPolicy } from './execution-policy.mjs';

const identity = value => typeof value === 'string' && value.length > 0 && value.length <= 256;
const amount = (value, minimum = 0) => Number.isSafeInteger(value) && value >= minimum && value <= 2_147_483_647;
const amountMap = (value, minimum = 0, unknown = false) => value && typeof value === 'object' && !Array.isArray(value) &&
  Object.keys(value).length <= 256 && Object.entries(value).every(([key, quantity]) => identity(key) && ((unknown && quantity === null) || amount(quantity, minimum)));
const count = (values, key) => Object.hasOwn(values, key) ? values[key] : 0;
const supplyAllocations = executionPolicy.offers + executionPolicy.roots;

/** One stock/claim ledger. Resource keys include location and component identity. */
export class ResourceLedger {
  #frame = null;
  #targets = new Map();
  #claims = new Map();
  #needsObservation = true;
  #deliveries = new Map();
  #supplies = new Map();
  #deliverySequence = 0;
  #supplySequence = 0;

  get epoch() { return this.#frame?.epoch ?? null; }
  get needsObservation() { return this.#needsObservation; }
  get availableDeliverySlots() { return 256 - this.#deliveries.size; }
  invalidate() { this.#needsObservation = true; }

  observe(frame) {
    // Trusted native projection only. The three maps remain bounded; guest messages keep their 16 KiB limit.
    frame = copyMessage(frame, 524_288);
    if (!frame || !identity(frame.epoch) || !identity(frame.revision) || !amountMap(frame.stocks, 0, true) ||
      !amountMap(frame.assets, 0, true) || !amountMap(frame.capacities, 0, true) || !Array.isArray(frame.targets) ||
      frame.targets.length > 256 || !frame.targets.every(identity)) throw Error('invalid_observation');
    if (this.#frame?.revision === frame.revision) {
      if (JSON.stringify(this.#frame) !== JSON.stringify(frame)) throw Error('observation_conflict');
      return;
    }
    this.#frame = frame;
    this.#needsObservation = false;
    const claims = [...this.#claims.values()];
    for (const claim of claims) {
      const lostStock = Object.keys(claim.inputs).some(resource => {
        const quantity = frame.stocks[resource];
        const total = [...this.#claimed(resource).values()].reduce((sum, amount) => sum + amount, 0);
        return !Number.isSafeInteger(quantity) || quantity < total;
      });
      const lostAsset = Object.entries(claim.assets).some(([asset, durability]) => !Object.hasOwn(frame.assets, asset) || frame.assets[asset] < durability);
      const lostCapacity = Object.keys(claim.capacities).some(space => !Object.hasOwn(frame.capacities, space) ||
        frame.capacities[space] < claims.reduce((sum, other) => sum + count(other.capacities, space), 0));
      if (claim.epoch !== frame.epoch || lostStock || lostAsset || lostCapacity || claim.targets.some(target => !frame.targets.includes(target)))
        claim.state = 'reconciling';
    }
  }
  target(consumer, resource, quantity, { priority = 0, waitingSince = 0 } = {}) {
    if (!identity(consumer) || !identity(resource) || !amount(quantity) || !amount(priority) || !amount(waitingSince)) throw Error('invalid_target');
    const key = JSON.stringify([consumer, resource]);
    if (quantity === 0) { this.#targets.delete(key); return; }
    if (!this.#targets.has(key) && this.#targets.size >= 1024) throw Error('target_capacity');
    this.#targets.set(key, { consumer, resource, quantity, priority, waitingSince });
  }
  stock(resource) {
    const quantity = this.#frame?.stocks[resource];
    if (!Number.isSafeInteger(quantity) || quantity < 0) return { known: false };
    const claimed = this.#claimed(resource);
    const floors = [...this.#targets.values()].filter(target => target.resource === resource);
    const consumers = [...new Set([...claimed.keys(), ...floors.map(target => target.consumer)])].sort();
    const allocated = new Map();
    let available = quantity;
    for (const consumer of consumers) {
      const amount = Math.min(available, claimed.get(consumer) ?? 0);
      allocated.set(consumer, amount); available -= amount;
    }
    const unmet = new Map();
    const ordered = [...consumers].sort((a, b) => {
      const left = floors.find(target => target.consumer === a), right = floors.find(target => target.consumer === b);
      return (right?.priority ?? 0) - (left?.priority ?? 0) || (left?.waitingSince ?? 0) - (right?.waitingSince ?? 0) || (a < b ? -1 : a > b ? 1 : 0);
    });
    for (const consumer of ordered) {
      const floor = floors.find(target => target.consumer === consumer)?.quantity ?? 0;
      const desired = Math.max(floor, claimed.get(consumer) ?? 0);
      const added = Math.min(available, Math.max(0, desired - allocated.get(consumer)));
      allocated.set(consumer, allocated.get(consumer) + added); available -= added;
      if (allocated.get(consumer) < desired) unmet.set(consumer, desired - allocated.get(consumer));
    }
    return { known: true, quantity, surplus: available, allocated: Object.fromEntries(allocated), unmet: Object.fromEntries(unmet) };
  }
  reserve(id, consumer, bundle, revision) {
    if (this.#claims.has(id)) throw Error('claim_exists');
    if (!identity(id)) throw Error('invalid_claim');
    const checked = this.assess(consumer, bundle, revision);
    const claim = { id, consumer, ...checked, epoch: this.#frame.epoch, revision, state: 'reserved' };
    this.#claims.set(id, claim);
    return copyMessage(claim);
  }
  /** The readiness producer uses the same checks without reserving or changing observation validity. */
  assess(consumer, bundle, revision) {
    if (!this.#frame || this.#needsObservation || this.#frame.revision !== revision) throw Error('stale_observation');
    if (!identity(consumer)) throw Error('invalid_claim');
    if (this.#claims.size >= 32) throw Error('claim_capacity');
    bundle = copyMessage(bundle);
    if (!bundle || Array.isArray(bundle) || Object.keys(bundle).some(key => !['inputs', 'assets', 'capacities', 'targets'].includes(key))) throw Error('invalid_bundle');
    const inputs = bundle.inputs ?? {};
    const assets = bundle.assets ?? {}, capacities = bundle.capacities ?? {}, targets = bundle.targets ?? [];
    if (!amountMap(inputs, 1) || !amountMap(assets, 1) || !amountMap(capacities, 1) || !Array.isArray(targets) ||
        targets.length > 256 || !targets.every(identity) || new Set(targets).size !== targets.length) throw Error('invalid_bundle');
    for (const claim of this.#claims.values()) {
      if (claim.state === 'reconciling' && (Object.keys(inputs).some(key => Object.hasOwn(claim.inputs, key)) ||
        Object.keys(assets).some(key => Object.hasOwn(claim.assets, key)) || Object.keys(capacities).some(key => Object.hasOwn(claim.capacities, key)) ||
        targets.some(target => claim.targets.includes(target)))) throw Error('resource_reconciling');
    }
    for (const [resource, quantity] of Object.entries(inputs)) {
      const stock = this.stock(resource);
      const own = count(stock.allocated ?? {}, consumer) - (this.#claimed(resource).get(consumer) ?? 0);
      if (!stock.known || quantity > own + stock.surplus) throw Error('resource_unavailable');
    }
    const claims = [...this.#claims.values()];
    for (const [asset, durability] of Object.entries(assets)) {
      if (!amount(this.#frame.assets[asset]) || this.#frame.assets[asset] < durability || claims.some(claim => Object.hasOwn(claim.assets, asset)))
        throw Error('asset_unavailable');
    }
    for (const [space, quantity] of Object.entries(capacities)) {
      const reserved = claims.reduce((total, claim) => total + count(claim.capacities, space), 0);
      if (!amount(this.#frame.capacities[space]) || this.#frame.capacities[space] - reserved < quantity) throw Error('capacity_unavailable');
    }
    for (const target of targets) {
      if (!this.#frame.targets.includes(target) || claims.some(claim => claim.targets.includes(target))) throw Error('target_unavailable');
    }
    return { inputs, assets, capacities, targets };
  }
  claim(id) {
    const claim = this.#claims.get(id);
    if (!claim) throw Error('claim_unknown');
    return copyMessage(claim);
  }
  settle(id, evidence) {
    const claim = this.#claims.get(id);
    if (!claim) throw Error('claim_unknown');
    evidence = copyMessage(evidence);
    for (const [resource, quantity] of Object.entries(evidence.consumed ?? {})) {
      if (!Number.isSafeInteger(quantity) || quantity < 0 || !Object.hasOwn(claim.inputs, resource) || quantity > claim.inputs[resource])
        throw Error('invalid_consumption_evidence');
    }
    if (evidence.released !== true || evidence.accountingComplete !== true) {
      claim.state = 'reconciling';
      return false;
    }
    this.#claims.delete(id);
    this.#needsObservation = true;
    return true;
  }
  /** IDs are host-issued monotonic sequence numbers, separate from native admission IDs. */
  createDelivery(spec) {
    if (this.#deliverySequence === Number.MAX_SAFE_INTEGER) throw Error('demand_id_exhausted');
    return this.requestDelivery(this.#deliverySequence + 1, spec);
  }
  requestDelivery(id, spec) {
    spec = copyMessage(spec);
    if (!Number.isSafeInteger(id) || id < 1 || !spec || !identity(spec.consumer) || !identity(spec.resource) || !amount(spec.quantity, 1) ||
        !Array.isArray(spec.methods) || !spec.methods.length || spec.methods.length > 32 || !spec.methods.every(identity) ||
        Object.keys(spec).some(key => !['consumer', 'resource', 'quantity', 'methods'].includes(key))) throw Error('invalid_demand');
    spec = { consumer: spec.consumer, resource: spec.resource, quantity: spec.quantity, methods: [...new Set(spec.methods)].sort() };
    const existing = this.#deliveries.get(id);
    if (existing) {
      if (JSON.stringify(existing.spec) !== JSON.stringify(spec)) throw Error('demand_conflict');
      return this.delivery(id);
    }
    if (id <= this.#deliverySequence) throw Error('demand_retired');
    if (!this.#frame) throw Error('stale_observation');
    if (this.#deliveries.size >= 256) throw Error('demand_capacity');
    this.#deliveries.set(id, { id, spec, epoch: this.#frame.epoch, credited: 0, state: 'pending', withdrawn: false });
    this.#deliverySequence = id;
    return this.delivery(id);
  }
  delivery(id) {
    const demand = this.#delivery(id);
    const outstanding = demand.state === 'pending' ? demand.spec.quantity - demand.credited : 0, allocated = this.#allocated(id);
    return copyMessage({ ...demand, outstanding, allocated, unallocated: Math.max(0, outstanding - allocated) });
  }
  /** Trusted bounded view includes internal per-attempt target shares as well as guest deliveries. */
  pendingDeliveries() { return [...this.#deliveries.values()].filter(demand => demand.state === 'pending').map(demand => this.delivery(demand.id)); }
  cancelDelivery(id) {
    const demand = this.#delivery(id);
    demand.withdrawn = true;
    if (demand.state === 'pending') demand.state = 'cancelled';
    const stoppingSupplies = [];
    for (const supply of this.#supplies.values()) {
      if (supply.state === 'settled') continue;
      const allocation = supply.allocations.find(allocation => allocation.demandId === id);
      if (!allocation) continue;
      allocation.quantity = allocation.credited;
      if (supply.state === 'running' && supply.allocations.every(allocation => this.#delivery(allocation.demandId).withdrawn)) {
        supply.state = 'stopping';
        stoppingSupplies.push(supply.id);
      }
    }
    return { delivery: this.delivery(id), stoppingSupplies };
  }
  closeDelivery(id) {
    const demand = this.#delivery(id);
    if (demand.state === 'pending' || [...this.#supplies.values()].some(supply => supply.state !== 'settled' &&
        supply.allocations.some(allocation => allocation.demandId === id))) throw Error('demand_unsettled');
    this.#deliveries.delete(id);
  }
  beginSupply(id, spec) {
    spec = copyMessage(spec);
    if (!Number.isSafeInteger(id) || id < 1 || !spec || !identity(spec.epoch) || !identity(spec.resource) || !identity(spec.method) ||
        !amount(spec.expected, 1) || spec.expected > 64 || !Array.isArray(spec.deliveries) || !spec.deliveries.length || spec.deliveries.length > supplyAllocations ||
        new Set(spec.deliveries.map(entry => entry?.id)).size !== spec.deliveries.length ||
        Object.keys(spec).some(key => !['epoch', 'resource', 'method', 'expected', 'deliveries'].includes(key))) throw Error('invalid_supply');
    const existing = this.#supplies.get(id);
    if (existing) {
      if (JSON.stringify(existing.spec) !== JSON.stringify(spec)) throw Error('supply_conflict');
      return this.supply(id);
    }
    if (id <= this.#supplySequence) throw Error('supply_retired');
    if (this.#supplies.size >= 32) throw Error('supply_capacity');
    const supply = { id, spec, state: 'running', produced: 0, effectId: null, allocations: [] };
    for (const delivery of spec.deliveries) {
      if (!delivery || Object.keys(delivery).some(key => !['id', 'quantity'].includes(key))) throw Error('invalid_supply');
      this.#canJoin(supply, delivery.id, delivery.quantity);
      supply.allocations.push({ demandId: delivery.id, quantity: delivery.quantity, credited: 0 });
    }
    this.#supplies.set(id, supply);
    this.#supplySequence = id;
    return this.supply(id);
  }
  joinSupply(id, demandId, quantity) {
    const supply = this.#supply(id);
    this.#canJoin(supply, demandId, quantity);
    supply.allocations.push({ demandId, quantity, credited: 0 });
    const credits = this.#distribute(supply);
    return { supply: this.supply(id), credits };
  }
  supply(id) { return copyMessage(this.#supply(id)); }
  /** Called only with cumulative, verified output from the trusted native receipt adapter. */
  creditSupply(id, evidence) {
    const supply = this.#supply(id);
    evidence = copyMessage(evidence);
    if (!evidence || !identity(evidence.effectId) || !amount(evidence.quantity) || evidence.quantity > supply.spec.expected ||
        evidence.quantity < supply.produced || (supply.effectId !== null && supply.effectId !== evidence.effectId)) throw Error('invalid_output_evidence');
    if (supply.state === 'settled') {
      if (evidence.quantity !== supply.produced || evidence.accountingComplete !== true) throw Error('supply_settled');
      return { credits: [] };
    }
    if (evidence.accountingComplete !== true) return { credits: [], unresolved: true };
    if (evidence.quantity > supply.produced) this.#needsObservation = true;
    supply.effectId = evidence.effectId;
    supply.produced = evidence.quantity;
    return { credits: this.#distribute(supply) };
  }
  settleSupply(id, evidence) {
    const supply = this.#supply(id);
    evidence = copyMessage(evidence);
    if (supply.state === 'settled') return true;
    if (evidence.released !== true || evidence.accountingComplete !== true) {
      supply.state = 'reconciling';
      return false;
    }
    supply.state = 'settled';
    supply.releaseEvidence = evidence;
    return true;
  }
  closeSupply(id) {
    if (this.#supply(id).state !== 'settled') throw Error('supply_unsettled');
    this.#supplies.delete(id);
  }
  #delivery(id) {
    const demand = this.#deliveries.get(id);
    if (!demand) throw Error('demand_unknown');
    return demand;
  }
  #supply(id) {
    const supply = this.#supplies.get(id);
    if (!supply) throw Error('supply_unknown');
    return supply;
  }
  #canJoin(supply, demandId, quantity) {
    const demand = this.#delivery(demandId);
    if (!amount(quantity, 1)) throw Error('invalid_demand_allocation');
    if (supply.state !== 'running') throw Error('supply_stopping');
    if (supply.allocations.length >= supplyAllocations) throw Error('supply_capacity_unavailable');
    if (supply.spec.epoch !== this.#frame?.epoch || demand.epoch !== supply.spec.epoch || demand.state !== 'pending' || demand.withdrawn ||
        demand.spec.resource !== supply.spec.resource || !demand.spec.methods.includes(supply.spec.method)) throw Error('supply_inapplicable');
    if (supply.allocations.some(allocation => allocation.demandId === demandId)) throw Error('demand_already_joined');
    const allocated = this.#allocated(demandId);
    if (quantity > demand.spec.quantity - demand.credited - allocated ||
        quantity > supply.spec.expected - supply.allocations.reduce((sum, allocation) => sum + allocation.quantity, 0)) throw Error('supply_capacity_unavailable');
  }
  #distribute(supply) {
    let available = supply.produced - supply.allocations.reduce((sum, allocation) => sum + allocation.credited, 0);
    const credits = [];
    for (const allocation of supply.allocations) {
      const demand = this.#delivery(allocation.demandId);
      if (demand.state !== 'pending') continue;
      const quantity = Math.min(available, allocation.quantity - allocation.credited, demand.spec.quantity - demand.credited);
      if (!quantity) continue;
      allocation.credited += quantity; demand.credited += quantity; available -= quantity;
      if (demand.credited === demand.spec.quantity) demand.state = 'fulfilled';
      credits.push({ demandId: demand.id, quantity });
    }
    return credits;
  }
  #claimed(resource) {
    const totals = new Map();
    for (const claim of this.#claims.values()) {
      const quantity = count(claim.inputs, resource);
      if (quantity) totals.set(claim.consumer, (totals.get(claim.consumer) ?? 0) + quantity);
    }
    return totals;
  }
  #allocated(demandId) {
    return [...this.#supplies.values()].filter(attempt => attempt.state !== 'settled').flatMap(attempt => attempt.allocations)
      .filter(allocation => allocation.demandId === demandId).reduce((sum, allocation) => sum + allocation.quantity - allocation.credited, 0);
  }
}
