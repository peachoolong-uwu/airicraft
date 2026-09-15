import test from 'node:test';
import assert from 'node:assert/strict';
import { InvocationBroker } from '../src/os/broker.mjs';
import { ResourceLedger } from '../src/os/ledger.mjs';
import { ResourceService } from '../src/os/resources.mjs';

function fixture() {
  const invocations = new InvocationBroker(), ledger = new ResourceLedger();
  const operations = { chest: { grant: 'container:home' }, harvest: { grant: 'crop:home' } };
  const resources = { wheat: { key: 'player/wheat/plain', grant: 'resource:wheat', methods: ['chest', 'harvest'], priority: 12 } };
  const service = new ResourceService({ invocations, ledger, resources, operations });
  const root = (grants = ['resource:wheat', 'container:home']) => invocations.install({ definition: 'fixture', grants });
  const observe = (revision, quantity, epoch = 'world') => ledger.observe({ epoch, revision, stocks: { 'player/wheat/plain': quantity }, assets: {}, capacities: {}, targets: [] });
  observe('initial', 0);
  return { invocations, ledger, service, root, observe, operations, resources };
}
const demand = (quantity = 2, methods = []) => ({ resource: 'wheat', quantity, methods });

test('stock targets update one consumer floor and survive helper return until the root stops', () => {
  const { root, service, ledger, invocations, observe } = fixture();
  const a = root(), b = root();
  observe('four-wheat', 4);
  service.target(a, 'wheat', 2); service.target(a, 'wheat', 2); service.target(b, 'wheat', 2);
  assert.equal(ledger.stock('player/wheat/plain').surplus, 0);
  const child = invocations.spawn(a, { definition: 'helper', grants: ['resource:wheat'] });
  service.target(child.id, 'wheat', 3);
  invocations.returned(child.id, null); invocations.join(a, child); service.poll();
  assert.equal(service.state().targets, 2);
  assert.equal(service.shortages().find(need => need.consumer === b).missing, 1);
  ledger.reserve('owned', a, { inputs: { 'player/wheat/plain': 2 } }, 'four-wheat');
  invocations.cancel(a); service.poll();
  assert.equal(service.state().targets, 1);
  assert.equal(ledger.stock('player/wheat/plain').allocated[a], 2);
  assert.equal(ledger.stock('player/wheat/plain').allocated[b], 2);
});

test('resource aliases and each selected supply method require the invocation grant', () => {
  const { root, service } = fixture();
  const denied = root([]), allowed = root();
  assert.throws(() => service.target(denied, 'wheat', 2), /operation_not_granted/);
  assert.throws(() => service.target(allowed, 'unknown', 2), /resource_unknown/);
  assert.throws(() => service.request(allowed, 1, demand(2, ['harvest'])), /operation_not_granted/);
  assert.throws(() => service.request(allowed, 1, demand(2, ['unknown'])), /supply_method_unavailable/);
  const id = service.request(allowed, 1, demand());
  assert.deepEqual(service.pending().find(item => item.id === id).methods, ['chest']);
});

test('a finite request retries by owner and sequence while distinct requests add', () => {
  const { root, service, ledger } = fixture();
  const owner = root();
  const id = service.request(owner, 1, demand());
  assert.equal(service.request(owner, 1, demand()), id);
  assert.throws(() => service.request(owner, 1, demand(3)), /demand_conflict/);
  const next = service.request(owner, 2, demand());
  assert.notEqual(id, next);
  assert.equal(service.pending().reduce((sum, item) => sum + item.quantity, 0), 4);
  assert.equal(service.request(owner, 1, demand()), id);
  assert.equal(ledger.stock('player/wheat/plain').quantity, 0);
});

test('verified shared output is credited once and delivery waits for its supply cleanup', () => {
  const { root, service, ledger } = fixture();
  const a = root(), b = root(), x = service.request(a, 1, demand()), y = service.request(b, 1, demand());
  ledger.beginSupply(1, { epoch: 'world', resource: 'player/wheat/plain', method: 'chest', expected: 4, deliveries: [{ id: x, quantity: 2 }, { id: y, quantity: 2 }] });
  assert.deepEqual(service.pending(), []);
  ledger.creditSupply(1, { effectId: 'native:1', quantity: 4, accountingComplete: true });
  ledger.creditSupply(1, { effectId: 'native:1', quantity: 4, accountingComplete: true });
  assert.equal(service.take(a, x).status, 'pending');
  ledger.settleSupply(1, { released: true, accountingComplete: true }); ledger.closeSupply(1);
  assert.deepEqual(service.take(a, x), { status: 'fulfilled', id: x, resource: 'wheat', epoch: 'world', quantity: 2, credited: 2 });
  assert.equal(service.take(b, y).credited, 2);
  assert.throws(() => service.take(a, x), /demand_unknown/);
  assert.throws(() => service.request(a, 1, demand()), /demand_retired/);
});

test('cancellation removes only its subscriber and retains unsettled demand accounting', () => {
  const { root, service, ledger, invocations } = fixture();
  const a = root(), b = root(), x = service.request(a, 1, demand()), y = service.request(b, 1, demand());
  ledger.beginSupply(1, { epoch: 'world', resource: 'player/wheat/plain', method: 'chest', expected: 4, deliveries: [{ id: x, quantity: 2 }, { id: y, quantity: 2 }] });
  ledger.creditSupply(1, { effectId: 'native:1', quantity: 1, accountingComplete: true });
  invocations.cancel(a); service.poll();
  assert.equal(ledger.delivery(x).credited, 1);
  assert.equal(ledger.delivery(x).withdrawn, true);
  assert.equal(ledger.supply(1).state, 'running');
  assert.equal(service.state().deliveries, 2);
  ledger.creditSupply(1, { effectId: 'native:1', quantity: 3, accountingComplete: true });
  assert.equal(ledger.delivery(y).credited, 2);
  invocations.cancel(b); service.poll();
  assert.equal(ledger.supply(1).state, 'stopping');
  ledger.settleSupply(1, { released: true, accountingComplete: true }); ledger.closeSupply(1); service.poll();
  assert.equal(service.state().deliveries, 0);
});

test('an epoch change retires old demand with explicit epoch outcome and no borrowed credit', () => {
  const { root, service, ledger, observe } = fixture();
  const owner = root(), id = service.request(owner, 1, demand());
  observe('other-world', 64, 'new-world');
  assert.equal(service.take(owner, id).status, 'epoch_changed');
  assert.throws(() => ledger.delivery(id), /demand_unknown/);
  const next = service.request(owner, 2, demand());
  assert.equal(ledger.delivery(next).epoch, 'new-world');
  assert.equal(ledger.delivery(next).credited, 0);
});

test('shortages preserve unknown stock and pending views exclude already allocated output', () => {
  const { root, service, ledger, observe } = fixture();
  const owner = root(); service.target(owner, 'wheat', 4);
  observe('unknown', null);
  assert.equal(service.shortages()[0].missing, null);
  observe('some', 2);
  assert.equal(service.shortages()[0].missing, 2);
  const id = service.request(owner, 1, demand(5));
  ledger.beginSupply(1, { epoch: 'world', resource: 'player/wheat/plain', method: 'chest', expected: 2, deliveries: [{ id, quantity: 2 }] });
  assert.equal(service.pending()[0].quantity, 3);
  const view = service.pending(); view[0].methods.push('harvest');
  assert.deepEqual(service.pending()[0].methods, ['chest']);
});

test('delivery allocation uses the ledger high-water mark instead of restarting an independent sequence', () => {
  const { root, service, ledger } = fixture();
  const owner = root();
  ledger.requestDelivery(100, { consumer: owner, resource: 'player/wheat/plain', quantity: 1, methods: ['chest'] });
  assert.equal(service.request(owner, 1, demand()), 101);
});

test('capacity is bounded per invocation and globally, and cancellation reclaims it', () => {
  const { root, service, invocations } = fixture();
  const first = root();
  for (let sequence = 1; sequence <= 32; sequence++) service.request(first, sequence, demand());
  assert.throws(() => service.request(first, 33, demand()), /invocation_demand_capacity/);
  for (let index = 0; index < 7; index++) {
    const owner = root();
    for (let sequence = 1; sequence <= 32; sequence++) service.request(owner, sequence, demand());
  }
  const ninth = root();
  assert.throws(() => service.request(ninth, 1, demand()), /demand_capacity/);
  invocations.cancel(first); service.poll();
  assert.equal(service.state().deliveries, 224);
  assert.doesNotThrow(() => service.request(ninth, 1, demand()));
});

test('configuration rejects missing methods and ambiguous resource aliases', () => {
  const { invocations, ledger, operations, resources } = fixture();
  assert.throws(() => new ResourceService({ invocations, ledger, operations, resources: { wheat: { ...resources.wheat, methods: ['missing'] } } }), /invalid_resource_configuration/);
  assert.throws(() => new ResourceService({ invocations, ledger, operations, resources: { ...resources, same: resources.wheat } }), /invalid_resource_configuration/);
});

test('a settled physical effect leaves target shortages unknown until stock is observed again', () => {
  const { root, service, ledger, observe } = fixture();
  const owner = root(); service.target(owner, 'wheat', 4); observe('before', 2);
  assert.equal(service.shortages()[0].missing, 2);
  ledger.reserve('consume', owner, { inputs: { 'player/wheat/plain': 1 } }, 'before');
  ledger.settle('consume', { released: true, accountingComplete: true, consumed: { 'player/wheat/plain': 1 } });
  assert.equal(service.shortages()[0].missing, null);
  observe('after', 1);
  assert.equal(service.shortages()[0].missing, 3);
});

test('procurement unions a consumer floor with finite deliveries while preserving distinct consumers and in-flight allocations', () => {
  const { root, service, ledger, invocations, observe } = fixture();
  const a = root(), b = root(), child = invocations.spawn(a, { definition: 'helper', grants: ['resource:wheat', 'container:home'] });
  service.target(a, 'wheat', 6); service.target(b, 'wheat', 2);
  const first = service.request(a, 1, demand(2)), second = service.request(child.id, 1, demand(2));
  let needs = service.procurement();
  assert.equal(needs.deliveries.reduce((sum, item) => sum + item.quantity, 0), 4);
  assert.equal(needs.targets.find(item => item.consumer === a).quantity, 2);
  assert.equal(needs.targets.find(item => item.consumer === b).quantity, 2);
  ledger.beginSupply(1, { epoch: 'world', resource: 'player/wheat/plain', method: 'chest', expected: 2, deliveries: [{ id: first, quantity: 2 }] });
  needs = service.procurement();
  assert.deepEqual(needs.deliveries.map(item => item.id), [second]);
  assert.equal(needs.targets.find(item => item.consumer === a).quantity, 2); // Allocated future delivery is not requested twice.
  assert.equal(ledger.stock('player/wheat/plain').quantity, 0); // The union never invents stock.
  invocations.cancel(child.id); service.poll();
  assert.equal(service.procurement().targets.find(item => item.consumer === a).quantity, 4);
  observe('unknown', null);
  assert.equal(service.procurement().targets.find(item => item.consumer === a).quantity, null);
});
