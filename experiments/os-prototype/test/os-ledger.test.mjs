import test from 'node:test';
import assert from 'node:assert/strict';
import { ResourceLedger } from '../src/os/ledger.mjs';

test('read-only bundle assessment shares admission checks without creating or releasing a claim', () => {
  const ledger = new ResourceLedger();
  ledger.observe({ epoch: 'world', revision: 'assessment', stocks: { wheat: 4 }, assets: { shears: 2 }, capacities: { output: 4 }, targets: ['pen'] });
  ledger.target('other', 'wheat', 2);
  const bundle = { inputs: { wheat: 2 }, assets: { shears: 1 }, capacities: { output: 2 }, targets: ['pen'] };
  assert.doesNotThrow(() => ledger.assess('caller', bundle, 'assessment'));
  assert.doesNotThrow(() => ledger.assess('caller', bundle, 'assessment'));
  assert.equal(ledger.needsObservation, false);
  ledger.reserve('act', 'caller', bundle, 'assessment');
  assert.throws(() => ledger.assess('caller', bundle, 'assessment'), /resource_unavailable/);
  assert.throws(() => ledger.assess('caller', {}, 'old'), /stale_observation/);
  assert.equal(ledger.claim('act').state, 'reserved');
});

const frame = stocks => ({ epoch: 'world-1', revision: 'capture-1', stocks, assets: {}, capacities: {}, targets: [] });

test('allocation continuity distinguishes an intervening reservation or target change from an idempotent refresh', () => {
  const ledger = new ResourceLedger();
  ledger.observe(frame({ wheat: 4 }));
  const initial = ledger.allocationRevision;
  assert.ok(Number.isSafeInteger(initial));
  ledger.target('sheep', 'wheat', 2);
  const protectedStock = ledger.allocationRevision;
  assert.ok(protectedStock > initial);
  ledger.target('sheep', 'wheat', 2);
  ledger.observe({ ...frame({ wheat: 4 }), revision: 'capture-2' });
  ledger.assess('sheep', { inputs: { wheat: 2 } }, 'capture-2');
  assert.equal(ledger.allocationRevision, protectedStock);
  ledger.target('sheep', 'wheat', 0);
  ledger.target('sheep', 'wheat', 2);
  assert.ok(ledger.allocationRevision > protectedStock);
  const beforeClaim = ledger.allocationRevision;
  ledger.reserve('feeding', 'sheep', { inputs: { wheat: 2 } }, 'capture-2');
  ledger.settle('feeding', { released: true, accountingComplete: true });
  ledger.observe({ ...frame({ wheat: 4 }), revision: 'capture-3' });
  assert.ok(ledger.allocationRevision > beforeClaim);
  assert.equal(ledger.stock('wheat').quantity, 4);
});

test('activity claims spend their own stock floors without counting protection twice', () => {
  const ledger = new ResourceLedger();
  ledger.observe(frame({ wheat: 4, seeds: 20 }));
  ledger.target('sheep-A', 'wheat', 2);
  ledger.target('sheep-B', 'wheat', 2);
  ledger.target('farm', 'seeds', 8);
  ledger.reserve('feed-A', 'sheep-A', { inputs: { wheat: 2 } }, 'capture-1');
  ledger.reserve('plant', 'farm', { inputs: { seeds: 6 } }, 'capture-1');
  assert.deepEqual(ledger.stock('wheat'), { known: true, quantity: 4, surplus: 0, allocated: { 'sheep-A': 2, 'sheep-B': 2 }, unmet: {} });
  assert.equal(ledger.stock('seeds').surplus, 12);
  assert.throws(() => ledger.reserve('steal', 'sheep-A', { inputs: { wheat: 1 } }, 'capture-1'), /resource_unavailable/);
  ledger.reserve('feed-B', 'sheep-B', { inputs: { wheat: 2 } }, 'capture-1');
  assert.equal(ledger.claim('feed-B').inputs.wheat, 2);
});

test('shared supply allocation changes break continuity while idempotent retries preserve it', () => {
  const ledger = new ResourceLedger();
  ledger.observe(frame({ seeds: 0 }));
  let revision = ledger.allocationRevision;
  const changed = () => { assert.ok(ledger.allocationRevision > revision); revision = ledger.allocationRevision; };
  const request = { consumer: 'farm', resource: 'seeds', quantity: 4, methods: ['chest'] };
  ledger.requestDelivery(1, request); changed();
  ledger.requestDelivery(1, request);
  assert.equal(ledger.allocationRevision, revision);
  ledger.requestDelivery(2, { ...request, consumer: 'sheep' }); changed();
  const supply = { epoch: 'world-1', resource: 'seeds', method: 'chest', expected: 8, deliveries: [{ id: 1, quantity: 4 }] };
  ledger.beginSupply(1, supply); changed();
  ledger.beginSupply(1, supply);
  assert.equal(ledger.allocationRevision, revision);
  ledger.joinSupply(1, 2, 4); changed();
  ledger.cancelDelivery(2); changed();
  ledger.cancelDelivery(2);
  assert.equal(ledger.allocationRevision, revision);
  const evidence = { effectId: 'native:1', quantity: 4, accountingComplete: true };
  ledger.creditSupply(1, evidence); changed();
  ledger.creditSupply(1, evidence);
  assert.equal(ledger.allocationRevision, revision);
  ledger.settleSupply(1, { released: false, accountingComplete: false }); changed();
  ledger.settleSupply(1, { released: false, accountingComplete: false });
  assert.equal(ledger.allocationRevision, revision);
  ledger.settleSupply(1, { released: true, accountingComplete: true }); changed();
  ledger.settleSupply(1, { released: true, accountingComplete: true });
  assert.equal(ledger.allocationRevision, revision);
  ledger.closeSupply(1); changed();
  ledger.closeDelivery(1); changed();
});

test('items, tools, destination space, and targets are reserved as one atomic bundle', () => {
  const ledger = new ResourceLedger();
  const observed = { ...frame({ saplings: 4 }), assets: { axe: 20 }, capacities: { logs: 8 }, targets: ['birch-1'] };
  ledger.observe(observed);
  const bundle = { inputs: { saplings: 1 }, assets: { axe: 6 }, capacities: { logs: 4 }, targets: ['birch-1'] };
  assert.throws(() => ledger.reserve('bad-tool', 'tree', { ...bundle, assets: { missing: 6 } }, 'capture-1'), /asset_unavailable/);
  assert.equal(ledger.stock('saplings').surplus, 4);
  assert.throws(() => ledger.reserve('bad-space', 'tree', { ...bundle, capacities: { logs: 9 } }, 'capture-1'), /capacity_unavailable/);
  assert.throws(() => ledger.reserve('bad-target', 'tree', { ...bundle, targets: ['birch-2'] }, 'capture-1'), /target_unavailable/);
  ledger.reserve('harvest', 'tree', bundle, 'capture-1');
  assert.throws(() => ledger.reserve('duplicate-axe', 'other', { assets: { axe: 1 } }, 'capture-1'), /asset_unavailable/);
  assert.throws(() => ledger.reserve('duplicate-tree', 'other', { targets: ['birch-1'] }, 'capture-1'), /target_unavailable/);
  ledger.reserve('remaining-space', 'storage', { capacities: { logs: 4 } }, 'capture-1');
  assert.throws(() => ledger.reserve('overflow', 'storage', { capacities: { logs: 1 } }, 'capture-1'), /capacity_unavailable/);
});

test('shortages preserve active allocations before higher priority floors and expose unmet demand', () => {
  const ledger = new ResourceLedger();
  ledger.observe(frame({ wheat: 4 }));
  ledger.target('existing', 'wheat', 2, { priority: 0, waitingSince: 0 });
  ledger.reserve('admitted', 'existing', { inputs: { wheat: 2 } }, 'capture-1');
  ledger.target('urgent', 'wheat', 4, { priority: 10, waitingSince: 10 });
  assert.deepEqual(ledger.stock('wheat').allocated, { existing: 2, urgent: 2 });
  assert.deepEqual(ledger.stock('wheat').unmet, { urgent: 2 });
  const other = new ResourceLedger();
  other.observe(frame({ wheat: 2 }));
  other.target('first', 'wheat', 2, { priority: 0, waitingSince: 0 });
  other.target('urgent', 'wheat', 2, { priority: 10, waitingSince: 10 });
  assert.equal(other.stock('wheat').allocated.urgent, 2);
  other.target('urgent', 'wheat', 2, { priority: 10, waitingSince: 10 });
  assert.equal(other.stock('wheat').unmet.first, 2);
});

test('external loss quarantines affected claims while unrelated resources remain usable', () => {
  const ledger = new ResourceLedger();
  ledger.observe(frame({ wheat: 4, seeds: 20 }));
  ledger.reserve('feed', 'sheep', { inputs: { wheat: 2 } }, 'capture-1');
  ledger.observe({ ...frame({ wheat: 1, seeds: 20 }), revision: 'capture-2' });
  assert.equal(ledger.claim('feed').state, 'reconciling');
  assert.throws(() => ledger.reserve('double-spend', 'other', { inputs: { wheat: 1 } }, 'capture-2'), /resource_reconciling/);
  ledger.reserve('plant', 'farm', { inputs: { seeds: 1 } }, 'capture-2');
  assert.equal(ledger.settle('feed', { released: false, accountingComplete: true, consumed: { wheat: 1 } }), false);
  assert.equal(ledger.claim('feed').state, 'reconciling');
  assert.equal(ledger.settle('feed', { released: true, accountingComplete: true, consumed: { wheat: 1 } }), true);
  assert.throws(() => ledger.reserve('stale', 'sheep', { inputs: { wheat: 1 } }, 'capture-2'), /stale_observation/);
  ledger.observe({ ...frame({ wheat: 1, seeds: 20 }), revision: 'capture-3' });
  ledger.reserve('fresh', 'sheep', { inputs: { wheat: 1 } }, 'capture-3');
  assert.equal(ledger.claim('plant').state, 'reserved');
});

test('invalid quantities, unknown fields and reused observations cannot manufacture stock', () => {
  const ledger = new ResourceLedger();
  ledger.observe(frame({ wheat: 2 }));
  for (const quantity of [-1, 0.5, '2'])
    assert.throws(() => ledger.reserve('bad', 'sheep', { inputs: { wheat: quantity } }, 'capture-1'), /invalid_bundle/);
  assert.throws(() => ledger.reserve('bad', 'sheep', { outputs: { wheat: 10 } }, 'capture-1'), /invalid_bundle/);
  assert.throws(() => ledger.target('sheep', 'wheat', -1), /invalid_target/);
  assert.throws(() => ledger.observe({ ...frame({ wheat: 200 }) }), /observation_conflict/);
  assert.equal(ledger.stock('wheat').quantity, 2);
  ledger.reserve('valid', 'sheep', { inputs: { wheat: 2 } }, 'capture-1');
  assert.throws(() => ledger.settle('valid', { released: true, accountingComplete: true, consumed: { wheat: 3 } }), /invalid_consumption_evidence/);
  assert.equal(ledger.claim('valid').inputs.wheat, 2);
});

test('distinct finite deliveries add, retries do not, and one shared transfer credits each output once', () => {
  const ledger = new ResourceLedger();
  ledger.observe(frame({ seeds: 0 }));
  const request = { consumer: 'farm-A', resource: 'seeds', quantity: 4, methods: ['home-chest'] };
  ledger.requestDelivery(1, request);
  ledger.requestDelivery(1, request);
  ledger.requestDelivery(2, { ...request, consumer: 'farm-B' });
  ledger.beginSupply(1, { epoch: 'world-1', resource: 'seeds', method: 'home-chest', expected: 8, deliveries: [{ id: 1, quantity: 4 }] });
  ledger.joinSupply(1, 2, 4);
  assert.equal(ledger.stock('seeds').quantity, 0);
  const evidence = { effectId: 'native-request:1', quantity: 8, accountingComplete: true };
  assert.deepEqual(ledger.creditSupply(1, evidence).credits, [{ demandId: 1, quantity: 4 }, { demandId: 2, quantity: 4 }]);
  assert.deepEqual(ledger.creditSupply(1, evidence).credits, []);
  assert.equal(ledger.delivery(1).credited, 4);
  assert.equal(ledger.delivery(2).credited, 4);
  assert.equal(ledger.delivery(2).state, 'fulfilled');
  assert.equal(ledger.stock('seeds').quantity, 0, 'credit is not an inventory observation');
  assert.throws(() => ledger.requestDelivery(1, { ...request, quantity: 5 }), /demand_conflict/);
});

test('withdrawing one delivery preserves other subscribers and only frees its uncredited share', () => {
  const ledger = new ResourceLedger();
  ledger.observe(frame({ seeds: 0 }));
  for (const id of [1, 2]) ledger.requestDelivery(id, { consumer: `farm-${id}`, resource: 'seeds', quantity: 4, methods: ['chest'] });
  ledger.beginSupply(1, { epoch: 'world-1', resource: 'seeds', method: 'chest', expected: 8,
    deliveries: [{ id: 1, quantity: 4 }, { id: 2, quantity: 4 }] });
  ledger.creditSupply(1, { effectId: 'native:1', quantity: 2, accountingComplete: true });
  assert.deepEqual(ledger.cancelDelivery(1).stoppingSupplies, []);
  ledger.requestDelivery(3, { consumer: 'farm-3', resource: 'seeds', quantity: 2, methods: ['chest'] });
  ledger.joinSupply(1, 3, 2);
  assert.deepEqual(ledger.creditSupply(1, { effectId: 'native:1', quantity: 8, accountingComplete: true }).credits,
    [{ demandId: 2, quantity: 4 }, { demandId: 3, quantity: 2 }]);
  assert.equal(ledger.delivery(1).state, 'cancelled');
  assert.equal(ledger.delivery(1).credited, 2);
  assert.equal(ledger.supply(1).state, 'running');
  ledger.cancelDelivery(2);
  assert.deepEqual(ledger.cancelDelivery(3).stoppingSupplies, [1]);
  ledger.requestDelivery(4, { consumer: 'late', resource: 'seeds', quantity: 1, methods: ['chest'] });
  assert.throws(() => ledger.joinSupply(1, 4, 1), /supply_stopping/);
});

test('partial supply releases unfilled commitments only after reconciliation and never replays a retired delivery', () => {
  const ledger = new ResourceLedger();
  ledger.observe(frame({ seeds: 0 }));
  const request = { consumer: 'farm', resource: 'seeds', quantity: 5, methods: ['chest'] };
  ledger.requestDelivery(1, request);
  ledger.beginSupply(1, { epoch: 'world-1', resource: 'seeds', method: 'chest', expected: 3, deliveries: [{ id: 1, quantity: 3 }] });
  ledger.creditSupply(1, { effectId: 'native:1', quantity: 2, accountingComplete: true });
  assert.equal(ledger.settleSupply(1, { released: false, accountingComplete: false }), false);
  const next = { epoch: 'world-1', resource: 'seeds', method: 'chest', expected: 3, deliveries: [{ id: 1, quantity: 3 }] };
  assert.throws(() => ledger.beginSupply(2, next), /supply_capacity_unavailable/);
  assert.throws(() => ledger.closeSupply(1), /supply_unsettled/);
  assert.equal(ledger.settleSupply(1, { released: true, accountingComplete: true }), true);
  assert.throws(() => ledger.creditSupply(1, { effectId: 'native:1', quantity: 3, accountingComplete: true }), /supply_settled/);
  ledger.beginSupply(2, next);
  ledger.creditSupply(2, { effectId: 'native:2', quantity: 3, accountingComplete: true });
  ledger.settleSupply(2, { released: true, accountingComplete: true });
  assert.equal(ledger.delivery(1).credited, 5);
  ledger.closeDelivery(1);
  ledger.closeSupply(1);
  ledger.closeSupply(2);
  assert.throws(() => ledger.requestDelivery(1, request), /demand_retired/);
  assert.throws(() => ledger.beginSupply(2, next), /supply_retired/);
});

test('delivery capacity is reclaimed on consumption without allowing old IDs to create new work', () => {
  const ledger = new ResourceLedger();
  ledger.observe(frame({ seeds: 0 }));
  const request = { consumer: 'farm', resource: 'seeds', quantity: 1, methods: ['chest'] };
  for (let id = 1; id <= 300; id++) {
    ledger.requestDelivery(id, request);
    ledger.beginSupply(id, { epoch: 'world-1', resource: 'seeds', method: 'chest', expected: 1, deliveries: [{ id, quantity: 1 }] });
    ledger.creditSupply(id, { effectId: `native:${id}`, quantity: 1, accountingComplete: true });
    ledger.settleSupply(id, { released: true, accountingComplete: true });
    ledger.closeDelivery(id);
    ledger.closeSupply(id);
  }
  assert.throws(() => ledger.requestDelivery(1, request), /demand_retired/);
  for (let id = 301; id <= 556; id++) ledger.requestDelivery(id, request);
  assert.throws(() => ledger.requestDelivery(557, request), /demand_capacity/);
  assert.equal(ledger.delivery(301).outstanding, 1);
});

test('in-flight joins require current applicable unallocated output and unverified output earns no credit', () => {
  const ledger = new ResourceLedger();
  ledger.observe(frame({ seeds: 0 }));
  const request = { consumer: 'farm', resource: 'seeds', quantity: 2, methods: ['chest'] };
  ledger.requestDelivery(1, request);
  ledger.requestDelivery(2, { ...request, methods: ['craft'] });
  ledger.requestDelivery(3, request);
  ledger.beginSupply(1, { epoch: 'world-1', resource: 'seeds', method: 'chest', expected: 4, deliveries: [{ id: 1, quantity: 2 }] });
  ledger.creditSupply(1, { effectId: 'native:1', quantity: 4, accountingComplete: false });
  assert.equal(ledger.delivery(1).credited, 0);
  assert.throws(() => ledger.joinSupply(1, 2, 2), /supply_inapplicable/);
  ledger.creditSupply(1, { effectId: 'native:1', quantity: 4, accountingComplete: true });
  assert.deepEqual(ledger.joinSupply(1, 3, 2).credits, [{ demandId: 3, quantity: 2 }]);
  ledger.requestDelivery(4, request);
  assert.throws(() => ledger.joinSupply(1, 4, 2), /supply_capacity_unavailable/);
  ledger.observe({ ...frame({ seeds: 0 }), epoch: 'world-2', revision: 'capture-2' });
  assert.throws(() => ledger.joinSupply(1, 4, 1), /supply_inapplicable/);
});
