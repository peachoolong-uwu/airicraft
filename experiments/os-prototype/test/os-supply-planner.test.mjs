import test from 'node:test';
import assert from 'node:assert/strict';
import { InvocationBroker } from '../src/os/broker.mjs';
import { ResourceLedger } from '../src/os/ledger.mjs';
import { ResourceService } from '../src/os/resources.mjs';
import { ContainerTransfer } from '../src/os/container-transfer.mjs';
import { SupplyPlanner } from '../src/os/supply-planner.mjs';
import { SchedulingPolicy } from '../src/os/scheduling.mjs';

function fixture() {
  const invocations = new InvocationBroker(), ledger = new ResourceLedger();
  const transfer = new ContainerTransfer({ scope: 'home', windowId: 'home-window' });
  const key = transfer.resource('player', 'minecraft:wheat', '');
  const operations = { chest: transfer, harvest: { grant: 'crop:home' } };
  const configuration = { wheat: { key, grant: 'resource:wheat', methods: ['chest', 'harvest'], priority: 12 } };
  const resources = new ResourceService({ invocations, ledger, resources: configuration, operations });
  const rules = { homeWheat: { resource: key, operation: 'chest', arguments: { direction: 'withdraw', itemId: 'minecraft:wheat' }, maximum: 64 } };
  const planner = new SupplyPlanner({ resources, operations, rules, epoch: 'world' });
  const root = (grants = ['resource:wheat', 'container:home']) => invocations.install({ definition: 'supply-fixture', grants });
  const observe = (revision, quantity, epoch = 'world') => ledger.observe({ epoch, revision, stocks: { [key]: quantity }, assets: {}, capacities: {}, targets: [] });
  const demand = (owner, sequence, quantity, methods = []) => resources.request(owner, sequence, { resource: 'wheat', quantity, methods });
  observe('initial', 0);
  return { invocations, ledger, resources, planner, operations, rules, root, observe, demand, key };
}

test('declared supply rules merge compatible consumers without counting their floors and deliveries twice', () => {
  const { root, resources, planner, demand, ledger, key } = fixture();
  const a = root(), b = root();
  resources.target(a, 'wheat', 6); resources.target(b, 'wheat', 2);
  const first = demand(a, 1, 2), second = demand(a, 2, 2);
  const offers = planner.offers(), offer = offers.find(offer => offer.anchor === a);
  assert.equal(offers.length, 2);
  assert.equal(offer.quantity, 8);
  assert.deepEqual(offer.arguments, { direction: 'withdraw', itemId: 'minecraft:wheat', quantity: 8 });
  assert.deepEqual(offer.deliveries, [{ id: first, owner: a, quantity: 2 }, { id: second, owner: a, quantity: 2 }]);
  assert.deepEqual(offer.targets, [{ owner: a, consumer: a, quantity: 2 }, { owner: b, consumer: b, quantity: 2 }]);
  assert.deepEqual(offer.consumers, [a, b]);
  assert.equal(offer.priority, 12);
  assert.equal(ledger.stock(key).quantity, 0);
  assert.equal(ledger.delivery(first).allocated, 0);
  offer.arguments.quantity = 64; offer.deliveries.length = 0;
  assert.equal(planner.offers().find(offer => offer.anchor === a).quantity, 8);
  assert.equal(planner.offers().find(offer => offer.anchor === a).deliveries.length, 2);
});

test('resolving a proposal rechecks current needs and cannot reuse an obsolete quantity or cancelled owner', () => {
  const { root, resources, planner, observe, invocations } = fixture();
  const owner = root(); resources.target(owner, 'wheat', 6);
  const offer = planner.offers()[0];
  assert.equal(planner.resolve(offer.id).quantity, 6);
  observe('some-wheat', 4);
  assert.throws(() => planner.resolve(offer.id), /supply_offer_stale/);
  const smaller = planner.offers()[0];
  assert.equal(smaller.quantity, 2);
  invocations.cancel(owner);
  assert.throws(() => planner.resolve(smaller.id), /supply_offer_stale/);
  assert.deepEqual(planner.offers(), []);
});

test('each root can anchor a bounded shared batch even when an earlier root has a large demand', () => {
  const { root, resources, planner, demand } = fixture();
  const a = root(), b = root();
  for (let sequence = 1; sequence <= 32; sequence++) demand(a, sequence, 1);
  resources.target(a, 'wheat', 100);
  demand(b, 1, 2);
  const offers = planner.offers();
  for (const offer of offers) {
    assert.equal(offer.quantity, 64);
    assert.equal(offer.deliveries.length, 32);
  }
  assert.deepEqual(offers.find(offer => offer.anchor === a).consumers, [a]);
  const forB = offers.find(offer => offer.anchor === b);
  assert.deepEqual(forB.consumers, [b, a]);
  assert.equal(forB.deliveries[0].owner, b);
  const policy = new SchedulingPolicy({ epoch: 'world' });
  policy.update({ roots: [a, b], offers: offers.map(offer => ({ id: offer.id, roots: offer.consumers,
    kind: 'land', readiness: 'ready', priority: offer.priority, context: null })) });
  policy.advance({ epoch: 'world', fromTick: 0, toTick: 2400, eligibleRoots: [b], covered: true, ordinaryAllowed: true });
  assert.equal(policy.decide({ authority: 'available' }).offerId, forB.id);
});

test('unknown stock, unavailable methods and closing roots do not create speculative target production', () => {
  const { root, resources, planner, demand, invocations, observe } = fixture();
  const denied = root(['resource:wheat']), harvestOnly = root(['resource:wheat', 'crop:home']);
  resources.target(denied, 'wheat', 4); demand(harvestOnly, 1, 2);
  assert.deepEqual(planner.offers(), []);
  const parent = root(), child = invocations.spawn(parent, { definition: 'helper', grants: ['resource:wheat', 'container:home'] });
  resources.target(parent, 'wheat', 8); const id = demand(child.id, 1, 2);
  observe('unknown', null);
  assert.equal(planner.offers()[0].quantity, 2);
  assert.deepEqual(planner.offers()[0].targets, []);
  observe('known', 0); invocations.returned(parent, null);
  const finite = planner.offers()[0];
  assert.equal(finite.quantity, 2);
  assert.equal(finite.owner, child.id);
  assert.deepEqual(finite.deliveries, [{ id, owner: child.id, quantity: 2 }]);
  assert.deepEqual(finite.targets, []);
  invocations.cancel(child.id);
  assert.deepEqual(planner.offers(), []);
  observe('foreign-world', 0, 'other-world');
  assert.deepEqual(planner.offers(), []);
});

test('different permitted methods share the demand estimate without creating a duplicate floor batch', () => {
  const { root, resources, operations, rules, demand } = fixture();
  const planner = new SupplyPlanner({ resources, operations, rules: { ...rules,
    harvestWheat: { ...rules.homeWheat, operation: 'harvest', arguments: {} } }, epoch: 'world' });
  const owner = root(['resource:wheat', 'container:home', 'crop:home']);
  resources.target(owner, 'wheat', 4);
  const chest = demand(owner, 1, 2, ['chest']); demand(owner, 2, 2, ['harvest']);
  const offer = planner.offers()[0];
  assert.equal(offer.quantity, 2);
  assert.deepEqual(offer.deliveries, [{ id: chest, owner, quantity: 2 }]);
  assert.deepEqual(offer.targets, []);
});

test('a delivery without an installed route cannot hide independently permitted floor restocking', () => {
  const { root, resources, planner, demand, ledger, key } = fixture();
  const owner = root(['resource:wheat', 'container:home', 'crop:home']);
  resources.target(owner, 'wheat', 4);
  const id = demand(owner, 1, 4, ['harvest']);
  const offer = planner.offers()[0];
  assert.equal(offer.quantity, 4);
  assert.deepEqual(offer.deliveries, []);
  assert.deepEqual(offer.targets, [{ owner, consumer: owner, quantity: 4 }]);
  assert.deepEqual(resources.pending()[0].methods, ['harvest']);
  // A real already-allocated attempt can cover the estimate even if its rule is no longer installed.
  ledger.beginSupply(1, { epoch: 'world', resource: key, method: 'harvest', expected: 4, deliveries: [{ id, quantity: 4 }] });
  assert.deepEqual(planner.offers(), []);
  assert.equal(ledger.stock(key).quantity, 0);
});

test('argument admission reserves traversal headroom for a full shared proposal', () => {
  const { resources, operations, rules, invocations, root, demand } = fixture();
  const configured = padding => ({ ...rules.homeWheat, arguments: { ...rules.homeWheat.arguments, padding } });
  assert.throws(() => new SupplyPlanner({ resources, operations, rules: { full: configured(Array(2000).fill(0)) }, epoch: 'world' }), /message_limit/);
  const planner = new SupplyPlanner({ resources, operations, rules: { bounded: configured(Array(900).fill(0)) }, epoch: 'world' });
  const roots = Array.from({ length: 12 }, () => root());
  for (let index = 0; index < 20; index++) {
    const child = invocations.spawn(roots[0], { definition: 'helper', grants: ['resource:wheat', 'container:home'] });
    demand(child.id, 1, 1);
  }
  roots.forEach((owner, index) => { demand(owner, 1, 1); resources.target(owner, 'wheat', index === 0 ? 23 : 3); });
  const offers = planner.offers();
  assert.equal(offers.length, 12);
  for (const offer of offers) {
    assert.equal(offer.quantity, 56);
    assert.equal(offer.deliveries.length, 32);
    assert.equal(offer.targets.length, 12);
    assert.equal(offer.owners.length, 32);
  }
});

test('supply rule limits and copies bound all twelve consumers without mutable configuration', () => {
  const { resources, operations, rules, root, demand } = fixture();
  const base = rules.homeWheat;
  const maximumRules = Object.fromEntries(Array.from({ length: 32 }, (_, index) => [`rule${index}`, base]));
  const planner = new SupplyPlanner({ resources, operations, rules: maximumRules, epoch: 'world' });
  for (let index = 0; index < 12; index++) demand(root(), 1, 1);
  maximumRules.rule0.maximum = 1;
  const offers = planner.offers();
  assert.equal(offers.length, 384);
  assert.equal(new Set(offers.map(offer => offer.id)).size, 384);
  assert.equal(offers[0].quantity, 12);
  const invalid = rule => assert.throws(() => new SupplyPlanner({ resources, operations, rules: { invalid: rule }, epoch: 'world' }), /invalid_supply_rules/);
  invalid({ ...base, operation: 'missing' });
  invalid({ ...base, maximum: 65 });
  invalid({ ...base, arguments: { quantity: 1 } });
  assert.throws(() => new SupplyPlanner({ resources, operations, rules: { ...maximumRules, tooMany: base }, epoch: 'world' }), /invalid_supply_rules/);
});
