import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { EffectJournal } from '../src/os/journal.mjs';
import { EffectBroker } from '../src/os/effects.mjs';
import { NativeContextContainer } from './fixtures/native-context-container.mjs';
import { setTimeout as delay } from 'node:timers/promises';
import { InvocationBroker } from '../src/os/broker.mjs';
import { ResourceLedger } from '../src/os/ledger.mjs';
import { ActivityCoordinator } from '../src/os/activities.mjs';
import { ContainerTransfer } from '../src/os/container-transfer.mjs';
import { WorkService } from '../src/os/work.mjs';
import { NativeObservationFeed } from '../src/os/native-feed.mjs';
import { ConditionWaits } from '../src/os/waits.mjs';
import { DecisionTrace } from '../src/os/trace.mjs';

const contextIntent = { definition: 'os:context', invocation: 'os:visit', operation: 'retain_container', arguments: { windowId: 'home-window', syncId: 1 } };
const childIntent = { definition: 'supply', invocation: 'caller', operation: 'transfer_container', arguments: { quantity: 2 } };
async function fixture(run, { trace } = {}) {
  const directory = await mkdtemp(join(tmpdir(), 'airicraft-contexts-'));
  const journal = await EffectJournal.open(join(directory, 'effects.sqlite'));
  const native = new NativeContextContainer();
  const effects = new EffectBroker({ native, journal, expectedWorld: 'fixture', trace });
  try { await effects.start(); await run({ native, journal, effects, trace }); }
  finally { await effects.stop(); await journal.close(); await rm(directory, { recursive: true, force: true }); }
}

test('a lost context reply retains a durable visit across independent operations until verified closure', async () => fixture(async ({ native, journal, effects }) => {
  native.loseReply = true;
  native.beforeSubmit = async request => assert.deepEqual((await journal.inspect(request.id)).intent.request, request);
  await effects.retainContext('container:home', contextIntent);
  assert.equal(effects.context().phase, 'entering');
  native.ready(); await effects.pollContext();
  assert.equal(effects.context().phase, 'ready');
  await assert.rejects(effects.execute(childIntent), /context_mismatch/);
  await effects.execute(childIntent, { context: 'container:home' });
  native.progress(2, 2, true); await effects.poll();
  assert.equal(effects.state().unresolved, true, 'the visit outlives the child');
  assert.equal(effects.state().operationUnresolved, false);
  assert.equal((await journal.unfinished()).length, 1);
  await effects.execute({ ...childIntent, invocation: 'another-caller' }, { context: 'container:home' });
  native.progress(2, 2, true); await effects.poll();
  assert.equal(effects.context().operations, 2);
  assert.equal(native.playerQuantity, 4);
  assert.deepEqual(native.requests.map(request => request.operation), ['retain_container', 'transfer_container', 'transfer_container']);
  await effects.closeContext();
  assert.equal(effects.context().phase, 'exiting');
  assert.equal(effects.canDispatch(native.authority()), false);
  native.closeContext(); await effects.pollContext();
  assert.equal(effects.context(), null);
  assert.deepEqual(await journal.unfinished(), []);
  assert.equal((await effects.stop()).released, true);
}));

test('the admission capture cannot extend a visit beyond its advancing-tick budget', async () => hostFixture(async ({ native, work, feed, root, request, pulse }) => {
  const owner = root(); request(owner);
  await feed.refresh(); await pulse(); native.ready(); await pulse();
  native.serverTick = 1199; await feed.refresh();
  native.serverTick = 1200;
  await pulse();
  assert.equal(native.requests.length, 1, 'fresh admission must not dispatch a child after the visit budget');
  await feed.refresh();
  assert.equal((await pulse()).reason, 'context_budget');
  native.closeContext(); await pulse();
  assert.equal(work.state().context, null);
}));

test('a ready visit waits for fresh recurring authorship without replaying or discarding compatible work', async () => hostFixture(async ({ native, work, feed, root, pulse }) => {
  const owner = root();
  const offer = { operation: 'chest', arguments: { direction: 'withdraw', itemId: 'minecraft:string', quantity: 1 }, context: 'container:home' };
  await feed.refresh();
  work.replaceOffers(owner, 1, [offer], feed.offerView(owner).basis); feed.tick();
  await pulse(); native.ready(); await pulse(); await feed.refresh();
  assert.equal((await pulse()).reason, 'context_authorship_pending');
  assert.equal(native.requests.length, 1);
  work.replaceOffers(owner, 2, [offer], feed.offerView(owner).basis); feed.tick();
  assert.equal((await pulse()).kind, 'select');
  native.progress(1, 1, true); await pulse();
  await feed.refresh();
  assert.equal((await pulse()).reason, 'context_authorship_pending', 'completion also needs a fresh recurring decision');
  work.replaceOffers(owner, 3, [], feed.offerView(owner).basis);
  assert.equal((await pulse()).kind, 'close_context');
  native.closeContext(); await pulse();
}));

async function hostFixture(run, { trace } = {}) {
  await fixture(async base => {
    const { native, effects } = base;
    const invocations = new InvocationBroker({ trace }), ledger = new ResourceLedger();
    const transfer = new ContainerTransfer({ scope: 'home', windowId: 'home-window' });
    const operations = { chest: transfer, outside: transfer };
    const activities = new ActivityCoordinator({ invocations, ledger, effects, operations, trace });
    const work = new WorkService({ invocations, activities, operations, epoch: 'world', trace, rules: {
      chest: { priority: 12, kind: 'land', context: 'container:home' }, outside: { priority: 1, kind: 'land', context: null }
    } });
    const waits = new ConditionWaits({ invocations, scopes: { carried: 'observe:carried' }, epoch: 'world' });
    const feed = new NativeObservationFeed({ native, effects, work, invocations, ledger, waits, operations, epoch: 'world', expectedWorld: 'fixture',
      views: { carried: { location: 'player', resources: { string: transfer.resource('player', 'minecraft:string', '') } } } });
    const root = () => invocations.install({ definition: 'shared-visit', grants: ['container:home', 'observe:carried'] });
    const request = (owner, sequence = 1, operation = 'chest') => work.request(owner, sequence, { operation,
      arguments: { direction: 'withdraw', itemId: 'minecraft:string', quantity: 1 }, context: operation === 'chest' ? 'container:home' : null });
    const pulse = async () => {
      const decision = work.tick({ authority: feed.availability() });
      const deadline = performance.now() + 3000;
      while (work.state().busy) { if (performance.now() > deadline) assert.fail('work stalled'); await delay(1); }
      assert.equal(work.state().fault, null);
      return decision;
    };
    try { await run({ ...base, invocations, ledger, work, feed, root, request, pulse }); }
    finally { feed.close(); }
  }, { trace });
}

test('the host automatically shares one visit across independent roots and closes it after their work', async () => hostFixture(async ({
  native, effects, journal, invocations, work, feed, root, request, pulse
}) => {
  const a = root(), b = root(), first = request(a), second = request(b);
  await feed.refresh();
  assert.equal((await pulse()).kind, 'enter_context');
  assert.equal(invocations.activity(), null, 'entry belongs to the OS');
  native.ready(); await pulse(); await feed.refresh();
  assert.equal((await pulse()).offerId, first);
  native.progress(1, 1, true); await pulse();
  assert.equal(work.take(a, first).status, 'success');
  invocations.returned(a, 'done');
  assert.equal(invocations.execution(a).phase, 'terminal', 'the next duty does not own the first caller');
  await feed.refresh();
  assert.equal((await pulse()).offerId, second);
  assert.equal(native.requests.filter(request => request.operation === 'retain_container').length, 1);
  native.progress(1, 1, true); await pulse();
  assert.equal(work.take(b, second).status, 'success');
  await feed.refresh();
  assert.equal((await pulse()).kind, 'close_context');
  assert.equal(effects.state().unresolved, true);
  native.closeContext(); await pulse();
  assert.equal(effects.context(), null);
  assert.deepEqual(await journal.unfinished(), []);
  assert.equal(native.playerQuantity, 2);
}));

test('eight completed operations end a visit and preserve the owed outside turn after verified exit', async () => hostFixture(async ({
  native, work, feed, root, request, pulse
}) => {
  native.quantity = 20;
  const owner = root(), other = root();
  for (let i = 1; i <= 9; i++) request(owner, i);
  const outside = request(other, 1, 'outside');
  await feed.refresh(); await pulse(); native.ready(); await pulse();
  for (let i = 0; i < 8; i++) {
    await feed.refresh(); assert.equal((await pulse()).kind, 'select');
    native.progress(1, 1, true); await pulse();
  }
  await feed.refresh();
  assert.equal((await pulse()).reason, 'context_budget');
  assert.equal(native.requests.length, 9);
  await pulse(); assert.equal(native.requests.length, 9, 'exit is still unresolved');
  native.closeContext(); await pulse();
  // This adapter requires an already-open window; fixture setup supplies the next one.
  native.windowOpen = true; await feed.refresh();
  const decision = await pulse();
  assert.equal(decision.offerId, outside); assert.equal(decision.reason, 'outside_turn');
  native.progress(1, 1, true); await pulse();
  assert.equal(work.take(other, outside).status, 'success');
}));

test('caller cancellation during entry does not abandon the OS visit or duplicate entry for another root', async () => hostFixture(async ({
  native, invocations, work, feed, root, request, pulse
}) => {
  const a = root(), b = root(); request(a); const remaining = request(b);
  await feed.refresh(); await pulse(); invocations.cancel(a);
  native.ready(); await pulse(); await feed.refresh();
  assert.equal((await pulse()).offerId, remaining);
  native.progress(1, 1, true); await pulse();
  assert.equal(work.take(b, remaining).status, 'success');
  assert.equal(native.requests.length, 2);
  await feed.refresh(); await pulse(); native.closeContext(); await pulse();
}));

test('restart waits for both recorded owners and reconciles their exact receipts without replay', async () => fixture(async ({ native, effects, journal }) => {
  await effects.retainContext('container:home', contextIntent);
  native.ready(); await effects.pollContext();
  await effects.execute(childIntent, { context: 'container:home' });
  assert.equal((await effects.stop()).released, false);
  assert.equal((await journal.unfinished()).length, 2);
  const replacement = new EffectBroker({ native, journal, expectedWorld: 'fixture' });
  try {
    await assert.rejects(replacement.start(), /reconciliation_required/);
    native.progress(2, 1, true, 'CANCELLED'); native.closeContext();
    await replacement.start();
    assert.deepEqual(await journal.unfinished(), []);
    assert.equal(native.requests.length, 2);
    assert.equal(native.playerQuantity, 1);
  } finally { await replacement.stop(); }
}));

test('paused captures do not spend visit ticks, and a changed native clock revokes instead of extending a visit', async () => hostFixture(async ({
  native, effects, feed, root, request, pulse
}) => {
  request(root()); await feed.refresh(); await pulse(); native.ready(); await pulse();
  for (let i = 0; i < 3; i++) await feed.refresh();
  assert.equal(effects.context().elapsedTicks, 0);
  native.serverTick = 100; await feed.refresh();
  assert.equal(effects.context().elapsedTicks, 100);
  native.serverTick = 99;
  await assert.rejects(feed.refresh(), /invalid_context_clock/);
  assert.equal(native.lease, null);
  assert.equal(effects.state().unresolved, true);
  native.closeContext(); assert.equal((await effects.stop()).released, true);
}));

test('externally revoked ready contexts enter reconciliation and never leave the scheduler silently waiting', async () => hostFixture(async ({
  native, effects, work, feed, root, request, pulse
}) => {
  request(root()); await feed.refresh(); await pulse(); native.ready(); await pulse();
  native.context.state = 'RECONCILING'; native.context.effects.contextReady = false;
  await feed.refresh();
  work.tick({ authority: feed.availability() });
  const deadline = performance.now() + 3000;
  while (work.state().busy) { assert.ok(performance.now() < deadline); await delay(1); }
  assert.equal(work.state().fault, 'context_revoked');
  assert.equal(native.lease, null);
  assert.equal(effects.state().unresolved, true);
  native.closeContext(); assert.equal((await effects.stop()).released, true);
}));

test('polling native context progress before child accounting does not count one operation twice', async () => fixture(async ({ native, effects }) => {
  await effects.retainContext('container:home', contextIntent); native.ready(); await effects.pollContext();
  await effects.execute(childIntent, { context: 'container:home' }); native.progress(2, 2, true);
  await effects.pollContext(); await effects.poll();
  assert.equal(effects.context().operations, 1);
  await effects.closeContext(); native.closeContext(); await effects.pollContext();
}));

for (const hook of ['beforeCancel', 'afterCancel']) test(`lost context cancellation ${hook} revokes the lease and retains cleanup`, async () => hostFixture(async ({
  native, effects, invocations, work, feed, root, request, pulse
}) => {
  const owner = root(); request(owner); await feed.refresh(); await pulse(); native.ready(); await pulse();
  invocations.cancel(owner); await feed.refresh();
  native[hook] = async () => { throw Error('cancel_transport_failed'); };
  work.tick({ authority: feed.availability() });
  const deadline = performance.now() + 3000;
  while (work.state().busy) { assert.ok(performance.now() < deadline); await delay(1); }
  assert.equal(work.state().fault, 'cancel_transport_failed');
  assert.equal(native.lease, null);
  assert.equal(effects.state().unresolved, true);
  native.closeContext(); assert.equal((await effects.stop()).released, true);
}));

test('an older passive reply cannot regress the context clock learned from a newer admission capture', async () => fixture(async ({ native, effects }) => {
  await effects.retainContext('container:home', contextIntent); native.ready(); await effects.pollContext();
  const earlier = await native.call('os_observe');
  native.serverTick = 2; const later = await native.call('os_observe');
  effects.observeContext(later.frame, later.authority);
  effects.observeContext(earlier.frame, earlier.authority);
  assert.equal(effects.context().elapsedTicks, 2);
  native.serverTick = 1; const regressed = await native.call('os_observe');
  assert.throws(() => effects.observeContext(regressed.frame, regressed.authority), /invalid_context_clock/);
  await effects.closeContext(); native.closeContext(); await effects.pollContext();
}));

test('trace failure during a fresh context-budget rejection drains without an unhandled scheduler rejection', async () => {
  const trace = new DecisionTrace({ runId: 'budget-failure', writer: {
    write: (_, bytes) => { if (JSON.parse(bytes).type === 'activity.rejected') throw Error('disk_failed'); }, close: async () => {}
  } });
  try {
    await hostFixture(async ({ native, effects, work, feed, root, request, pulse }) => {
      request(root()); await feed.refresh(); await pulse(); native.ready(); await pulse();
      native.serverTick = 1199; await feed.refresh(); native.serverTick = 1200;
      work.tick({ authority: feed.availability() });
      const deadline = performance.now() + 3000;
      while (work.state().busy || native.lease !== null) { assert.ok(performance.now() < deadline); await delay(1); }
      assert.equal(trace.status().incomplete, true);
      assert.equal(work.state().fault, 'disk_failed');
      assert.equal(effects.state().unresolved, true);
      native.closeContext();
      while (!(await effects.stop()).released) { assert.ok(performance.now() < deadline); await delay(1); }
    }, { trace });
  } finally { await trace.close().catch(() => {}); }
});
