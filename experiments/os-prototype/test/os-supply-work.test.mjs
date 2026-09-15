import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { InvocationBroker } from '../src/os/broker.mjs';
import { ResourceLedger } from '../src/os/ledger.mjs';
import { ResourceService } from '../src/os/resources.mjs';
import { SupplyPlanner } from '../src/os/supply-planner.mjs';
import { ContainerTransfer } from '../src/os/container-transfer.mjs';
import { ActivityCoordinator } from '../src/os/activities.mjs';
import { EffectBroker } from '../src/os/effects.mjs';
import { EffectJournal } from '../src/os/journal.mjs';
import { NativeContainer } from './fixtures/native-container.mjs';
import { WorkService } from '../src/os/work.mjs';

async function fixture(run) {
  const directory = await mkdtemp(join(tmpdir(), 'airicraft-supply-work-'));
  const journal = await EffectJournal.open(join(directory, 'effects.sqlite'));
  const native = new NativeContainer({ itemId: 'minecraft:wheat' }), invocations = new InvocationBroker(), ledger = new ResourceLedger();
  const transfer = new ContainerTransfer({ scope: 'home', windowId: 'home-window' });
  const operations = { chest: transfer, low: transfer }, key = transfer.resource('player', 'minecraft:wheat', '');
  ledger.observe({ epoch: 'world', revision: 'initial', stocks: { [key]: 0 }, assets: {}, capacities: {}, targets: [] });
  const resources = new ResourceService({ invocations, ledger, operations,
    resources: { wheat: { key, grant: 'resource:wheat', methods: ['chest'], priority: 12 } } });
  const supplies = new SupplyPlanner({ resources, operations, epoch: 'world',
    rules: { wheat: { resource: key, operation: 'chest', arguments: { direction: 'withdraw', itemId: 'minecraft:wheat' }, maximum: 64 } } });
  const effects = new EffectBroker({ native, journal, expectedWorld: 'fixture' });
  const activities = new ActivityCoordinator({ invocations, ledger, effects, operations, supplies });
  let now = 0;
  const work = new WorkService({ invocations, activities, operations, supplies, epoch: 'world', now: () => now,
    rules: { chest: { priority: 12, kind: 'land', context: null }, low: { priority: 1, kind: 'land', context: null } } });
  const publish = (id, sequence = 1) => work.publish(id, { epoch: 'world', captureId: `assessment-${sequence}`, captureSequence: sequence,
    readiness: 'ready', ageUpperBoundMillis: 0 });
  const pulse = async () => {
    const result = work.tick({ authority: 'available' }), deadline = performance.now() + 3000;
    while (work.state().busy) { if (performance.now() > deadline) assert.fail('supply work stalled'); await delay(1); }
    return result;
  };
  const root = () => invocations.install({ definition: 'supply-fixture', grants: ['resource:wheat', 'container:home'] });
  const demand = (owner, sequence, quantity) => resources.request(owner, sequence, { resource: 'wheat', quantity, methods: ['chest'] });
  try {
    await effects.start();
    await run({ invocations, ledger, resources, supplies, activities, native, journal, operations, key, root, demand,
      work, publish, pulse, time: value => { now = value; } });
  } finally { await effects.stop(); await journal.close(); await rm(directory, { recursive: true, force: true }); }
}

test('one identified supply proposal admits finite and target shares together and retires internal target records after release', async () => fixture(async ({ root, demand, resources, supplies, activities, invocations, ledger, native, journal }) => {
  const a = root(), b = root();
  resources.target(a, 'wheat', 2); resources.target(b, 'wheat', 2);
  const delivery = demand(a, 1, 2), proposal = supplies.offers().find(offer => offer.anchor === a);
  assert.equal(proposal.quantity, 4);
  await activities.admit({ supplyOfferId: proposal.id });
  assert.deepEqual(invocations.activity().subscribers, [a, b]);
  assert.equal(native.submissions, 1);
  const provenance = (await journal.unfinished())[0].intent.provenance;
  assert.equal(provenance.supplyOfferId, proposal.id);
  assert.equal(provenance.targetDeliveryIds.length, 1);
  const targetId = provenance.targetDeliveryIds[0];
  assert.equal(ledger.delivery(targetId).spec.consumer, b);
  assert.equal(resources.procurement().targets.find(target => target.consumer === b).quantity, 0);
  native.progress(4, 4, false); await activities.poll();
  assert.equal(resources.take(a, delivery).status, 'pending');
  assert.equal(ledger.delivery(targetId).credited, 2);
  assert.equal(resources.shortages().find(target => target.consumer === b).missing, null);
  native.progress(4, 4, true); await activities.poll();
  assert.equal(resources.take(a, delivery).status, 'fulfilled');
  assert.throws(() => ledger.delivery(targetId), /demand_unknown/);
  assert.equal(invocations.activity(), null);
  assert.deepEqual(await journal.unfinished(), []);
}));

test('a full shared batch retains all 32 finite allocations and twelve target shares within one native attempt', async () => fixture(async ({ root, demand, resources, supplies, activities, invocations, ledger, native, journal }) => {
  native.quantity = 64;
  const roots = Array.from({ length: 12 }, () => root()), deliveries = [];
  for (let index = 0; index < 20; index++) {
    const owner = invocations.spawn(roots[0], { definition: 'helper', grants: ['resource:wheat', 'container:home'] }).id;
    deliveries.push({ owner, id: demand(owner, 1, 1) });
  }
  roots.forEach((owner, index) => { deliveries.push({ owner, id: demand(owner, 1, 1) }); resources.target(owner, 'wheat', index === 0 ? 23 : 3); });
  const proposal = supplies.offers()[0];
  assert.equal(proposal.quantity, 56);
  await activities.admit({ supplyOfferId: proposal.id });
  const provenance = (await journal.unfinished())[0].intent.provenance;
  assert.equal(provenance.deliveries.length, 44);
  assert.equal(provenance.targetDeliveryIds.length, 12);
  assert.equal(invocations.activity().subscribers.length, 32);
  native.progress(56, 56, true); await activities.poll();
  for (const { owner, id } of deliveries) assert.equal(resources.take(owner, id).credited, 1);
  for (const id of provenance.targetDeliveryIds) assert.throws(() => ledger.delivery(id), /demand_unknown/);
  assert.deepEqual(ledger.pendingDeliveries(), []);
  assert.equal(native.submissions, 1);
}));

test('automatic supply proposals compete with finite work through the same scheduler and native owner', async () => fixture(async ({ root, demand, resources, invocations, native, work, publish, pulse }) => {
  const a = root(), b = root(), other = root();
  resources.target(a, 'wheat', 2); resources.target(b, 'wheat', 2);
  const delivery = demand(a, 1, 2);
  const low = work.request(other, 1, { operation: 'low', arguments: { direction: 'withdraw', itemId: 'minecraft:wheat', quantity: 1 }, context: null });
  const pending = work.pending();
  assert.equal(pending.filter(item => item.supply).length, 2);
  for (const item of pending) publish(item.id);
  const selected = await pulse();
  assert.notEqual(selected.offerId, low);
  assert.deepEqual(selected.roots, [a, b]);
  assert.deepEqual(invocations.activity().subscribers, [a, b]);
  assert.equal(native.submissions, 1);
  assert.throws(() => work.take(a, selected.offerId), /work_unknown/);
  native.progress(4, 4, false); await pulse();
  assert.equal(resources.take(a, delivery).status, 'pending');
  assert.equal(work.take(other, low).status, 'pending');
  native.progress(4, 4, true); await pulse();
  assert.equal(resources.take(a, delivery).status, 'fulfilled');
  assert.equal(work.state().active, null);
  assert.equal((await pulse()).kind, 'wait');
  assert.equal(native.submissions, 1);
  invocations.cancel(other); work.poll();
}));

test('failed supply probes back off without failing their consumer or spinning native observations', async () => fixture(async ({ root, demand, invocations, native, work, publish, pulse, time, resources }) => {
  const owner = root(), delivery = demand(owner, 1, 2);
  native.quantity = 0;
  const id = work.pending()[0].id; publish(id); await pulse();
  const captures = native.capture;
  assert.equal(work.state().fault, null);
  assert.equal(invocations.inspect(owner).phase, 'running');
  assert.equal(work.pending()[0].deferred.reason, 'resource_unavailable');
  assert.equal(publish(id), false);
  publish(id, 2);
  for (let index = 0; index < 4; index++) assert.equal((await pulse()).kind, 'wait');
  assert.equal(native.capture, captures);
  assert.equal(resources.take(owner, delivery).status, 'pending');
  time(5000); native.quantity = 4;
  publish(id, 3); assert.equal((await pulse()).kind, 'select');
  native.progress(2, 2, true); await pulse();
  assert.equal(resources.take(owner, delivery).status, 'fulfilled');
}));

test('fresh native stock can retire an obsolete target batch before any action or internal allocation', async () => fixture(async ({ root, resources, work, publish, pulse, native, ledger, invocations, key, journal }) => {
  const owner = root(); resources.target(owner, 'wheat', 4);
  const id = work.pending()[0].id;
  native.playerQuantity = 4; publish(id); await pulse();
  assert.equal(native.submissions, 0);
  assert.equal(work.state().fault, null);
  assert.equal(invocations.activity(), null);
  assert.equal(ledger.stock(key).quantity, 4);
  assert.deepEqual(ledger.pendingDeliveries(), []);
  assert.deepEqual(work.pending(), []);
  assert.deepEqual(await journal.unfinished(), []);
}));

test('reserved target output cannot be borrowed by a new delivery and keeps its own subscriber alive', async () => fixture(async ({ root, demand, resources, supplies, activities, invocations, ledger, native, journal }) => {
  const a = root(), b = root(), c = root();
  const first = demand(a, 1, 2); resources.target(b, 'wheat', 2);
  const { activityId } = await activities.admit({ supplyOfferId: supplies.offers().find(offer => offer.anchor === a).id });
  const late = demand(c, 1, 2);
  assert.throws(() => activities.join({ activityId, owner: c, deliveryId: late, quantity: 2 }), /supply_capacity_unavailable/);
  invocations.cancel(a); await activities.poll();
  assert.equal(native.cancellations, 0);
  assert.equal(ledger.delivery(first).state, 'cancelled');
  native.progress(4, 2, false); await activities.poll();
  const target = (await journal.unfinished())[0].intent.provenance.targetDeliveryIds[0];
  assert.equal(ledger.delivery(target).credited, 2);
  assert.equal(ledger.delivery(late).credited, 0);
  invocations.cancel(b); await activities.poll();
  assert.equal(native.cancellations, 1);
  assert.equal(invocations.inspect(b).outcome, null);
  native.progress(4, 2, true, 'CANCELLED'); await activities.poll();
  assert.equal(invocations.inspect(b).outcome.status, 'cancelled');
  assert.throws(() => ledger.delivery(target), /demand_unknown/);
  invocations.cancel(c); resources.poll();
  assert.deepEqual(ledger.pendingDeliveries(), []);
}));

test('stock admission failure rolls back target-share records and leaves the coordinator reusable at the delivery limit', async () => fixture(async ({ root, resources, supplies, activities, invocations, ledger, native, journal }) => {
  const a = root(), b = root(); resources.target(a, 'wheat', 1); resources.target(b, 'wheat', 1);
  const held = [];
  for (let index = 0; index < 254; index++) held.push(ledger.createDelivery({ consumer: a, resource: 'unrelated', quantity: 1, methods: ['chest'] }).id);
  const proposal = supplies.offers()[0];
  native.quantity = 1;
  await assert.rejects(activities.admit({ supplyOfferId: proposal.id }), /resource_unavailable/);
  assert.equal(native.submissions, 0);
  assert.equal(invocations.activity(), null);
  assert.equal(ledger.pendingDeliveries().length, 254);
  assert.deepEqual(await journal.unfinished(), []);
  native.quantity = 2;
  await activities.admit({ supplyOfferId: proposal.id });
  native.progress(2, 2, true); await activities.poll();
  assert.equal(ledger.pendingDeliveries().length, 254);
  assert.equal(native.submissions, 1);
}));

test('reserved automatic-supply slots keep the combined queue within 1024 offers at full invocation capacity', async () => fixture(async ({ root, demand, work, invocations, native, publish, pulse }) => {
  const roots = Array.from({ length: 12 }, () => root()), owners = [...roots];
  while (owners.length < 32) owners.push(invocations.spawn(roots[0], { definition: 'helper', grants: ['container:home'] }).id);
  for (const owner of roots) demand(owner, 1, 1);
  const request = { operation: 'low', arguments: { direction: 'withdraw', itemId: 'minecraft:wheat', quantity: 1 }, context: null };
  let total = 0;
  for (const owner of owners) for (let sequence = 1; sequence <= 32 && total < 1012; sequence++, total++) work.request(owner, sequence, request);
  assert.throws(() => work.request(owners.at(-1), 33, request), /work_capacity/);
  const pending = work.pending();
  assert.equal(pending.length, 1024);
  assert.equal(pending.filter(item => item.supply).length, 12);
  assert.equal(work.state().maximumWork, 1012);
  for (const item of pending) publish(item.id);
  native.quantity = 64;
  assert.equal((await pulse()).roots.length, 12);
  native.progress(12, 12, true); await pulse();
  for (const owner of roots) invocations.cancel(owner);
  work.poll();
  assert.equal(work.state().requests, 0);
  assert.equal(work.state().supplyRequests, 0);
}));

test('cancelling the primary owner during an admission reply keeps the remaining target subscriber and physical exclusion', async () => fixture(async ({ root, demand, resources, work, invocations, native, publish, pulse }) => {
  const a = root(), b = root(); demand(a, 1, 2); resources.target(b, 'wheat', 2);
  for (const item of work.pending()) publish(item.id);
  let release;
  const barrier = new Promise(resolve => { release = resolve; });
  native.beforeReply = async name => { if (name === 'os_submit') await barrier; };
  try {
    work.tick({ authority: 'available' });
    const deadline = performance.now() + 3000;
    while (!native.submissions) { if (performance.now() > deadline) assert.fail('admission stalled'); await delay(1); }
    invocations.cancel(a); work.poll();
    assert.equal(work.tick({ authority: 'available' }).reason, 'work_transport_pending');
    assert.equal(invocations.inspect(a).outcome.status, 'cancelled');
    assert.deepEqual(invocations.activity().subscribers, [b]);
    work.pending(); // Refreshing alternatives must retain the unresolved selected activity.
    release();
    while (work.state().busy) { if (performance.now() > deadline) assert.fail('reply stalled'); await delay(1); }
    await pulse();
    assert.equal(native.cancellations, 0);
    assert.equal(native.submissions, 1);
    assert.deepEqual(invocations.activity().subscribers, [b]);
    native.progress(4, 4, true); await pulse();
    assert.equal(invocations.inspect(a).outcome.status, 'cancelled');
    assert.equal(invocations.activity(), null);
    assert.equal(work.state().fault, null);
  } finally { release(); }
}));

test('a transport fault after the original supply owner leaves retains the remaining cleanup owner', async () => fixture(async ({ root, demand, resources, work, invocations, native, publish, pulse, journal }) => {
  const a = root(), b = root(); demand(a, 1, 2); resources.target(b, 'wheat', 2);
  for (const item of work.pending()) publish(item.id);
  const selected = await pulse();
  invocations.cancel(a); await pulse();
  let fail = true;
  native.beforeReply = async name => { if (name === 'os_inspect' && fail) { fail = false; throw Error('transport_lost'); } };
  await pulse();
  assert.equal(work.state().fault, 'transport_lost');
  assert.equal(work.state().active, selected.offerId);
  assert.equal(invocations.inspect(b).outcome, null);
  assert.deepEqual(invocations.activity().cleanupOwners, [b]);
  native.progress(4, 1, true, 'CANCELLED'); await pulse();
  assert.equal(invocations.inspect(b).outcome.status, 'failure');
  assert.equal(invocations.activity(), null);
  assert.equal(work.state().supplyRequests, 0);
  assert.deepEqual(await journal.unfinished(), []);
}));

test('a later subscriber keeps shared cleanup tracked after all original supply subscribers withdraw', async () => fixture(async ({ root, demand, resources, work, invocations, native, publish, pulse, journal }) => {
  const a = root(), b = root(), c = root(); demand(a, 1, 2); resources.target(b, 'wheat', 2);
  for (const item of work.pending()) publish(item.id);
  const selected = await pulse();
  invocations.cancel(a); await pulse();
  const late = demand(c, 1, 2);
  work.join({ activityId: invocations.activity().id, owner: c, deliveryId: late, quantity: 2 });
  invocations.cancel(b); await pulse();
  let fail = true;
  native.beforeReply = async name => { if (name === 'os_inspect' && fail) { fail = false; throw Error('transport_lost'); } };
  await pulse();
  assert.equal(work.state().fault, 'transport_lost');
  assert.equal(work.state().active, selected.offerId);
  assert.equal(invocations.inspect(c).outcome, null);
  native.progress(4, 1, true, 'CANCELLED'); await pulse();
  assert.equal(invocations.inspect(c).outcome.status, 'failure');
  assert.equal(invocations.activity(), null);
  assert.deepEqual(await journal.unfinished(), []);
}));

test('a full delivery queue serves existing finite demands without waiting for target-share metadata', async () => fixture(async ({ root, demand, resources, work, native, publish, pulse, journal }) => {
  native.quantity = 64;
  const roots = Array.from({ length: 8 }, () => root()), deliveries = new Map();
  for (const owner of roots) {
    resources.target(owner, 'wheat', 64);
    deliveries.set(owner, Array.from({ length: 32 }, (_, index) => demand(owner, index + 1, 1)));
  }
  const targetOnly = root(); resources.target(targetOnly, 'wheat', 1);
  const pending = work.pending();
  assert.equal(pending.length, 8);
  assert.ok(pending.every(item => item.supply.quantity > 0 && item.supply.consumers.includes(item.supply.anchor)));
  for (const item of pending) publish(item.id);
  const selected = await pulse();
  assert.equal(native.submissions, 1);
  assert.equal((await journal.unfinished())[0].intent.request.arguments.quantity, 32);
  assert.deepEqual(selected.roots, [roots[0]]);
  native.progress(32, 32, true); await pulse();
  for (const id of deliveries.get(roots[0])) assert.equal(resources.take(roots[0], id).status, 'fulfilled');
  assert.equal(resources.state().deliveries, 224);
}));

test('one remaining metadata slot admits one target share while preserving every root alternative', async () => fixture(async ({ root, resources, supplies, activities, ledger, native }) => {
  const roots = [root(), root(), root()];
  for (const owner of roots) resources.target(owner, 'wheat', 1);
  for (let index = 0; index < 255; index++) ledger.createDelivery({ consumer: roots[0], resource: 'unrelated', quantity: 1, methods: ['chest'] });
  const offers = supplies.offers();
  assert.equal(offers.length, 3);
  for (const offer of offers) {
    assert.equal(offer.quantity, 1);
    assert.equal(offer.targets.length, 1);
    assert.deepEqual(offer.consumers, [offer.anchor]);
  }
  await activities.admit({ supplyOfferId: offers[0].id });
  assert.equal(ledger.availableDeliverySlots, 0);
  native.progress(1, 1, true); await activities.poll();
  assert.equal(ledger.availableDeliverySlots, 1);
}));

test('a late supply join resets only the newly served consumer once, including idempotent join retries', async () => fixture(async ({ root, demand, work, invocations, native, publish, pulse }) => {
  const a = root(), b = root(), c = root(); demand(a, 1, 2); demand(b, 1, 2);
  const queued = work.request(c, 1, { operation: 'low', arguments: { direction: 'withdraw', itemId: 'minecraft:wheat', quantity: 1 }, context: null });
  const initial = work.pending();
  for (const item of initial.filter(item => item.id !== queued)) publish(item.id);
  await pulse();
  const late = demand(c, 1, 2);
  publish(queued, 2);
  work.advance({ epoch: 'world', fromTick: 0, toTick: 2400, eligibleRoots: [c], covered: true, ordinaryAllowed: true });
  assert.equal(work.state().scheduling.roots.find(root => root.id === c).ageTicks, 2400);
  invocations.cancel(a); await pulse();
  const request = { activityId: invocations.activity().id, owner: c, deliveryId: late, quantity: 2 };
  work.join(request);
  assert.equal(work.state().scheduling.roots.find(root => root.id === c).ageTicks, 0);
  work.advance({ epoch: 'world', fromTick: 2400, toTick: 2700, eligibleRoots: [c], covered: true, ordinaryAllowed: true });
  work.join(request);
  assert.equal(work.state().scheduling.roots.find(root => root.id === c).ageTicks, 300);
  native.progress(4, 4, true); await pulse();
}));
