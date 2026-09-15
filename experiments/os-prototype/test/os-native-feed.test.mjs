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
import { EffectJournal } from '../src/os/journal.mjs';
import { EffectBroker } from '../src/os/effects.mjs';
import { ActivityCoordinator } from '../src/os/activities.mjs';
import { ContainerTransfer } from '../src/os/container-transfer.mjs';
import { WorkService } from '../src/os/work.mjs';
import { ConditionWaits } from '../src/os/waits.mjs';
import { NativeObservationFeed } from '../src/os/native-feed.mjs';
import { NativeContainer } from './fixtures/native-container.mjs';

async function fixture(run) {
  const directory = await mkdtemp(join(tmpdir(), 'airicraft-feed-'));
  const journal = await EffectJournal.open(join(directory, 'effects.sqlite'));
  let now = 0;
  const clock = () => now, native = new NativeContainer({ itemId: 'minecraft:wheat', now: clock });
  const invocations = new InvocationBroker(), ledger = new ResourceLedger();
  const transfer = new ContainerTransfer({ scope: 'home', windowId: 'home-window' }), operations = { chest: transfer };
  const key = transfer.resource('player', 'minecraft:wheat', ''), chestKey = transfer.resource('container', 'minecraft:wheat', '');
  const resources = new ResourceService({ invocations, ledger, operations,
    resources: { wheat: { key, grant: 'resource:wheat', methods: ['chest'] } } });
  const supplies = new SupplyPlanner({ resources, operations, epoch: 'world',
    rules: { wheat: { resource: key, operation: 'chest', arguments: { direction: 'withdraw', itemId: 'minecraft:wheat' }, maximum: 64 } } });
  const effects = new EffectBroker({ native, journal, expectedWorld: 'fixture' });
  const activities = new ActivityCoordinator({ invocations, ledger, effects, operations, supplies });
  const work = new WorkService({ invocations, activities, operations, supplies, epoch: 'world', now: clock,
    rules: { chest: { priority: 12, kind: 'land', context: null } } });
  const waits = new ConditionWaits({ invocations, scopes: { carried: 'observe:carried', home: 'container:home' }, epoch: 'world', now: clock });
  const feed = new NativeObservationFeed({ native, effects, work, invocations, ledger, resources, waits, operations,
    epoch: 'world', expectedWorld: 'fixture', now: clock,
    views: { carried: { location: 'player', resources: { wheat: key } }, home: { location: 'container:home', resources: { wheat: chestKey } } } });
  const root = () => invocations.install({ definition: 'feed-fixture', grants: ['resource:wheat', 'container:home', 'observe:carried'] });
  const pulse = async () => {
    const decision = work.tick({ authority: feed.availability() });
    const deadline = performance.now() + 3000;
    while (work.state().busy) { if (performance.now() > deadline) assert.fail('work stalled'); await delay(1); }
    return decision;
  };
  try {
    await effects.start();
    await run({ feed, native, invocations, ledger, resources, work, waits, effects, journal, operations, key, chestKey, root, pulse, time: value => { now = value; } });
  } finally { feed.close(); await effects.stop(); await journal.close(); await rm(directory, { recursive: true, force: true }); }
}

test('one native inventory capture drives passive waits and automatic shared supply without authored readiness', async () => fixture(async ({ feed, native, resources, ledger, work, waits, invocations, root, key, pulse, journal }) => {
  const a = root(), b = root();
  await feed.refresh();
  assert.equal(ledger.stock(key).quantity, 0);
  const wait = waits.wait(a, { scope: 'carried', path: ['stock', 'wheat'], atLeast: 3 });
  const first = resources.request(a, 1, { resource: 'wheat', quantity: 2 });
  const second = resources.request(b, 1, { resource: 'wheat', quantity: 1 });
  await feed.refresh();
  assert.equal(native.submissions, 0);
  assert.equal((await pulse()).kind, 'select');
  assert.deepEqual(new Set(invocations.activity().subscribers), new Set([a, b]));
  native.progress(3, 3, false); await pulse();
  assert.equal(waits.take(a, wait).status, 'pending');
  assert.equal(ledger.needsObservation, true);
  native.progress(3, 3, true); await pulse();
  assert.equal(resources.take(a, first).credited, 2);
  assert.equal(resources.take(b, second).credited, 1);
  native.playerQuantity = 3; native.quantity = 1; native.windowOpen = false;
  await feed.refresh();
  assert.equal(ledger.stock(key).quantity, 3);
  assert.equal(waits.take(a, wait).status, 'met');
  assert.equal(invocations.activity(), null);
  assert.deepEqual(await journal.unfinished(), []);
  assert.equal(work.state().fault, null);
}));

test('offers use one granted native view and old declarations cannot run against a newer capture', async () => fixture(async ({ feed, native, work, invocations, pulse, time }) => {
  const owner = invocations.install({ definition: 'offers', grants: ['container:home'] });
  assert.equal(feed.offerView(owner), null);
  await feed.refresh();
  const initial = feed.offerView(owner);
  assert.deepEqual(initial.view.scopes.map(scope => scope.scope), ['home']);
  const offer = { operation: 'chest', arguments: { direction: 'withdraw', itemId: 'minecraft:wheat', quantity: 2 }, context: null };
  const [id] = work.replaceOffers(owner, 1, [offer], initial.basis).ids;
  await feed.refresh();
  assert.equal(feed.isCurrentOfferBasis(initial.basis), false);
  assert.equal((await pulse()).kind, 'wait');
  assert.equal(native.submissions, 0);
  const next = feed.offerView(owner);
  assert.deepEqual(work.replaceOffers(owner, 2, [offer], next.basis).ids, [id]);
  feed.tick();
  assert.equal((await pulse()).kind, 'select');
  assert.equal(feed.offerView(owner), null);
  assert.equal(feed.isCurrentOfferBasis(next.basis), false);
  native.progress(2, 2, true); await pulse();
  await feed.refresh();
  assert.notEqual(feed.offerView(owner), null);
  time(2000); assert.equal(feed.offerView(owner), null);
}));

test('host pulses assess new requests once per native basis instead of repeatedly scanning unchanged inventory', async () => fixture(async ({ feed, work, root, operations }) => {
  const owner = root(), prepare = operations.chest.prepare.bind(operations.chest);
  let preparations = 0;
  operations.chest.prepare = (...args) => { preparations++; return prepare(...args); };
  await feed.refresh();
  const request = { operation: 'chest', arguments: { direction: 'withdraw', itemId: 'minecraft:wheat', quantity: 2 }, context: null };
  work.request(owner, 1, request);
  for (let i = 0; i < 20; i++) feed.tick();
  assert.equal(preparations, 1);
  work.request(owner, 2, request); feed.tick();
  assert.equal(preparations, 2);
  await feed.refresh();
  assert.equal(preparations, 4);
}));

test('stock protection blocks readiness without a claim and closed containers leave their contents unknown', async () => fixture(async ({ feed, native, ledger, work, waits, root, chestKey, pulse }) => {
  const owner = root(), protectedOwner = root();
  ledger.target(protectedOwner, chestKey, 4);
  const id = work.request(owner, 1, { operation: 'chest', arguments: { direction: 'withdraw', itemId: 'minecraft:wheat', quantity: 2 }, context: null });
  await feed.refresh();
  assert.equal((await pulse()).kind, 'wait');
  assert.equal(ledger.stock(chestKey).surplus, 0);
  assert.equal(native.submissions, 0);
  native.quantity = 6;
  await feed.refresh();
  assert.equal((await pulse()).kind, 'select');
  native.progress(2, 2, true); await pulse();
  assert.equal(work.take(owner, id).status, 'success');
  native.windowOpen = false;
  await feed.refresh();
  assert.equal(ledger.stock(chestKey).known, false);
  const seen = waits.observe(owner, ['home']).scopes[0];
  assert.equal(seen.current, false);
  assert.equal(seen.frame.facts[0].known, false);
}));

test('a capture delayed across physical admission and release cannot refresh the consumed stock basis', async () => fixture(async ({ feed, native, ledger, work, root, key, pulse }) => {
  const owner = root(), id = work.request(owner, 1, { operation: 'chest', arguments: { direction: 'withdraw', itemId: 'minecraft:wheat', quantity: 2 }, context: null });
  await feed.refresh();
  let release, entered;
  const gate = new Promise(resolve => { release = resolve; }), held = new Promise(resolve => { entered = resolve; });
  let once = true;
  native.beforeReply = async name => { if (name === 'os_observe' && once) { once = false; entered(); await gate; } };
  const refreshing = feed.refresh();
  try {
    await held;
    assert.equal((await pulse()).kind, 'select');
    native.progress(2, 2, true); await pulse();
    assert.equal(work.take(owner, id).status, 'success');
    assert.equal(ledger.needsObservation, true);
    release();
    assert.equal(await refreshing, false);
    assert.equal(ledger.needsObservation, true);
    assert.equal(feed.availability(), 'unknown');
    native.quantity = 2; native.playerQuantity = 2;
    await feed.refresh();
    assert.equal(ledger.stock(key).quantity, 2);
    assert.equal(ledger.needsObservation, false);
  } finally { release(); await refreshing; }
}));

test('stale captures create a gap and sampled server tick jumps never advance a growth deadline', async () => fixture(async ({ feed, native, ledger, waits, root, time }) => {
  const owner = root();
  await feed.refresh();
  const wait = waits.wait(owner, { scope: 'carried', path: ['stock', 'wheat'], atLeast: 3 }, { deadline: { clock: 'eligible_ticks', ticks: 10, scope: 'carried' } });
  native.serverTick = 10000;
  await feed.refresh();
  assert.equal(waits.inspect(owner, wait).eligibleTicks, 0);
  assert.equal(waits.take(owner, wait).status, 'pending');
  native.beforeReply = async name => { if (name === 'os_observe') time(2000); };
  await assert.rejects(feed.refresh(), /observation_stale/);
  assert.equal(feed.state().fault, null);
  assert.equal(feed.availability(), 'unknown');
  assert.equal(ledger.needsObservation, true);
  assert.equal(waits.take(owner, wait).status, 'gap');
  native.beforeReply = async () => {};
  await feed.refresh();
  assert.equal(feed.availability(), 'available');
}));

test('single-flight reads ignore late completion after close', async () => fixture(async ({ feed, native, ledger }) => {
  await feed.refresh();
  const capture = feed.state().captureId;
  let release;
  const gate = new Promise(resolve => { release = resolve; });
  native.beforeReply = async name => { if (name === 'os_observe') await gate; };
  const first = feed.refresh(), second = feed.refresh();
  assert.equal(first, second);
  feed.tick();
  feed.close(); release();
  assert.equal(await first, false);
  assert.equal(feed.state().captureId, capture);
  assert.equal(feed.availability(), 'unknown');
  assert.equal(ledger.needsObservation, true);
  await assert.rejects(feed.refresh(), /observation_feed_closed/);
}));

test('replayed captures cannot restore freshness and changed bridge sessions revoke the host lease', async () => fixture(async ({ feed, native, time }) => {
  let old;
  native.beforeReply = async (name, result) => { if (name === 'os_observe') old = structuredClone(result.frame); };
  await feed.refresh();
  time(2000);
  native.beforeReply = async (name, result) => { if (name === 'os_observe') result.frame = { ...old, receivedAtHostMillis: 2000 }; };
  assert.equal(await feed.refresh(), false);
  assert.equal(feed.availability(), 'unknown');
  native.beforeReply = async () => {};
  native.sessionId = 'different-bridge';
  await assert.rejects(feed.refresh(), /observation_session_changed/);
  assert.equal(native.lease, null);
  assert.equal(feed.state().fault, 'observation_session_changed');
}));

test('malformed finite work returns a typed rejection while another root can still make progress', async () => fixture(async ({ feed, native, work, root, pulse }) => {
  const a = root(), b = root();
  const bad = work.request(a, 1, { operation: 'chest', arguments: { direction: 'withdraw', itemId: 'minecraft:wheat', quantity: 0 }, context: null });
  const good = work.request(b, 1, { operation: 'chest', arguments: { direction: 'withdraw', itemId: 'minecraft:wheat', quantity: 2 }, context: null });
  await feed.refresh();
  assert.deepEqual(work.take(a, bad), { status: 'rejected', reason: 'invalid_transfer' });
  assert.equal((await pulse()).offerId, good);
  native.progress(2, 2, true); await pulse();
  assert.equal(work.take(b, good).status, 'success');
  assert.equal(feed.state().fault, null);
}));

test('foreign native ownership prevents selection even when the resources are ready', async () => fixture(async ({ feed, native, work, root, pulse }) => {
  const owner = root();
  work.request(owner, 1, { operation: 'chest', arguments: { direction: 'withdraw', itemId: 'minecraft:wheat', quantity: 2 }, context: null });
  native.lease = { epoch: 'world', generation: 2, hostId: 'other-host' };
  await feed.refresh();
  assert.equal(feed.availability(), 'unavailable');
  assert.equal((await pulse()).kind, 'wait');
  assert.equal(native.submissions, 0);
}));

test('a full component-aware inventory and chest fit the trusted capture budget without widening guest views', async () => fixture(async ({ feed, native, ledger, waits, root }) => {
  let size;
  native.beforeReply = async (name, result) => {
    if (name !== 'os_observe') return;
    const slot = (id, container, variant) => ({ id, container, variant: String(variant).padStart(64, '0'), itemId: 'minecraft:wheat', count: 1, maxCount: 64 });
    result.frame.facts.inventory.slots = Array.from({ length: 36 }, (_, id) => slot(id, false, id));
    result.frame.facts.window.slots = [
      ...Array.from({ length: 54 }, (_, id) => slot(id, true, id + 36)),
      ...Array.from({ length: 36 }, (_, id) => slot(id + 54, false, id))
    ];
    size = Buffer.byteLength(JSON.stringify(result.frame));
  };
  assert.equal(await feed.refresh(), true);
  assert.ok(size > 16384);
  assert.equal(ledger.needsObservation, false);
  const projection = waits.observe(root(), ['carried']).scopes[0];
  assert.equal(projection.current, true);
  assert.equal(projection.frame.facts[0].value, 0);
  assert.ok(Buffer.byteLength(JSON.stringify(projection)) < 12288);
}));

test('a supply for different components stays blocked while independent finite work remains usable', async () => fixture(async ({ feed, native, resources, work, invocations, root, pulse }) => {
  const owner = root(), other = root();
  await feed.refresh();
  const demand = resources.request(owner, 1, { resource: 'wheat', quantity: 2 });
  native.beforeReply = async (name, result) => {
    if (name === 'os_observe') result.frame.facts.window.slots[0].variant = 'different';
  };
  await feed.refresh();
  assert.equal((await pulse()).kind, 'wait');
  assert.equal(native.submissions, 0);
  const id = work.request(other, 1, { operation: 'chest', arguments: { direction: 'withdraw', itemId: 'minecraft:wheat', quantity: 1 }, context: null });
  await feed.refresh();
  assert.equal((await pulse()).offerId, id);
  native.progress(1, 1, true); await pulse();
  assert.equal(work.take(other, id).status, 'success');
  assert.equal(resources.take(owner, demand).status, 'pending');
  assert.equal(work.state().fault, null);
  assert.equal(invocations.inspect(owner).phase, 'running');
}));

test('an output variant change at fresh admission backs off only that supply rule', async () => fixture(async ({ feed, native, resources, work, invocations, root, pulse }) => {
  const owner = root();
  await feed.refresh();
  resources.request(owner, 1, { resource: 'wheat', quantity: 2 });
  await feed.refresh();
  native.beforeReply = async (name, result) => {
    if (name === 'os_observe') result.frame.facts.window.slots[0].variant = 'different';
  };
  assert.equal((await pulse()).kind, 'select');
  assert.equal(native.submissions, 0);
  assert.equal(work.state().fault, null);
  assert.equal(invocations.inspect(owner).phase, 'running');
  assert.equal(work.state().supplyDeferrals[0].reason, 'supply_output_mismatch');
}));

test('a fatal observation during work revokes authority but retains the actor until cleanup is proved', async () => fixture(async ({ feed, native, work, invocations, journal, root, pulse }) => {
  const owner = root();
  work.request(owner, 1, { operation: 'chest', arguments: { direction: 'withdraw', itemId: 'minecraft:wheat', quantity: 2 }, context: null });
  await feed.refresh(); await pulse();
  native.beforeReply = async (name, result) => { if (name === 'os_observe') result.frame.world.alive = false; };
  await assert.rejects(feed.refresh(), /world_not_ready/);
  assert.equal(native.lease, null);
  assert.equal(feed.availability(), 'unknown');
  assert.notEqual(invocations.activity(), null);
  assert.equal((await journal.unfinished()).length, 1);
  invocations.cancel(owner, 'run_stopped');
  native.progress(2, 1, true, 'CANCELLED'); await pulse();
  assert.equal(invocations.activity(), null);
  assert.equal(invocations.inspect(owner).outcome.status, 'cancelled');
  assert.deepEqual(await journal.unfinished(), []);
}));
