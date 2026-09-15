import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { DefinitionLibrary } from '../src/os/library.mjs';
import { InvocationBroker } from '../src/os/broker.mjs';
import { RunnerPool } from '../src/os/runners.mjs';
import { InstallationHost } from '../src/os/installations.mjs';
import { ConditionWaits } from '../src/os/waits.mjs';
import { BehaviorLoop } from '../src/os/loop.mjs';
import { ResourceLedger } from '../src/os/ledger.mjs';
import { ResourceService } from '../src/os/resources.mjs';
import { ContainerTransfer } from '../src/os/container-transfer.mjs';
import { ActivityCoordinator } from '../src/os/activities.mjs';
import { EffectBroker } from '../src/os/effects.mjs';
import { EffectJournal } from '../src/os/journal.mjs';
import { NativeContainer } from './fixtures/native-container.mjs';
import { WorkService } from '../src/os/work.mjs';
import { SupplyPlanner } from '../src/os/supply-planner.mjs';
import { NativeObservationFeed } from '../src/os/native-feed.mjs';

async function fixture(run, { workEnabled = false, suppliesEnabled = false, feedEnabled = false } = {}) {
  const directory = await mkdtemp(join(tmpdir(), 'airicraft-loop-'));
  const library = await DefinitionLibrary.open(directory), invocations = new InvocationBroker(), runners = new RunnerPool({ invocations });
  const grants = ['observe:wheat', 'observe:sheep', 'resource:wheat', 'container:home'];
  const host = new InstallationHost({ library, invocations, runners, grants, environment: {}, runId: 'loop-test', refresh: async () => null });
  const now = feedEnabled ? () => performance.now() : () => 0;
  const waits = new ConditionWaits({ invocations, scopes: { wheat: 'observe:wheat', sheep: 'observe:sheep' }, epoch: 'world', now });
  const ledger = new ResourceLedger(), operation = new ContainerTransfer({ scope: 'home', windowId: 'home-window' });
  const operations = { chest: operation }, resourceKey = operation.resource('player', 'minecraft:wheat', '');
  if (!feedEnabled) ledger.observe({ epoch: 'world', revision: 'initial', stocks: { [resourceKey]: 0 }, assets: {}, capacities: {}, targets: [] });
  const resources = new ResourceService({ invocations, ledger, operations,
    resources: { wheat: { key: resourceKey, grant: 'resource:wheat', methods: ['chest'], priority: 12 } } });
  let work, workNative, workJournal, workEffects, feed;
  if (workEnabled || suppliesEnabled) {
    workNative = new NativeContainer({ itemId: 'minecraft:wheat', now });
    workJournal = await EffectJournal.open(join(directory, 'work-effects.sqlite'));
    workEffects = new EffectBroker({ native: workNative, journal: workJournal, expectedWorld: 'fixture' });
    await workEffects.start();
    const supplies = suppliesEnabled ? new SupplyPlanner({ resources, operations, epoch: 'world',
      rules: { wheat: { resource: resourceKey, operation: 'chest', arguments: { direction: 'withdraw', itemId: 'minecraft:wheat' }, maximum: 64 } } }) : undefined;
    work = new WorkService({ invocations, operations, supplies, epoch: 'world', now,
      activities: new ActivityCoordinator({ invocations, ledger, effects: workEffects, operations, supplies }),
      rules: { chest: { priority: 12, kind: 'land', context: null } } });
    if (feedEnabled) {
      feed = new NativeObservationFeed({ native: workNative, effects: workEffects, work, invocations, ledger, resources, waits, operations,
        epoch: 'world', expectedWorld: 'fixture', now, views: { wheat: { location: 'player', resources: { wheat: resourceKey } } } });
      await feed.refresh();
    }
  }
  const loop = new BehaviorLoop({ installations: host, invocations, runners, waits, resources, work, observations: feed });
  const definition = async (source, overrides = {}) => {
    const revision = await library.candidate({ schemaVersion: 1, kind: 'behavior', name: 'dispatch-test', description: 'execution seam fixture', tags: [],
      capabilities: grants, environment: {}, dependencies: {}, mode: 'generator', inputContract: true, outputContract: true,
      examples: [], source, ...overrides }, { reason: 'test', hypothesis: 'owned effects compose without blocking unrelated duties' });
    // Validation itself has a separate supervised suite. These trusted fixtures exercise dispatch in real VMs.
    await library.recordValidation(revision.digest, { passed: true, checks: ['execution_fixture'], artifacts: [] });
    return revision;
  };
  const install = async (revision, input = null) => {
    const installed = await host.install(revision.digest, { input });
    loop.attach(installed.rootId);
    return installed;
  };
  const publish = (scope, sequence, mature) => waits.publish({ schemaVersion: 1, sessionId: 'bridge', epoch: 'world', scope,
    captureId: `${scope}-${sequence}`, captureSequence: sequence, capturedAtNanos: String(sequence), clockDomain: 'native:bridge', source: 'fixture',
    clientTick: sequence, serverTick: sequence, receivedAtHostMillis: 0, captureAgeUpperBoundMillis: 0,
    coverage: { available: true, complete: true, truncated: false }, facts: [{ path: ['mature'], known: true, value: mature }],
    progress: { clockId: scope, eligibleTicks: sequence } });
  const until = async predicate => {
    const deadline = performance.now() + 5000;
    while (!predicate()) {
      loop.tick();
      feed?.tick();
      work?.tick({ authority: feed ? feed.availability() : 'available' });
      if (performance.now() > deadline) assert.fail(`loop stalled: ${JSON.stringify(loop.state())}`);
      await delay(5);
    }
  };
  try { await run({ loop, host, invocations, runners, waits, library, definition, install, publish, until, resources, ledger, operations, directory, work, workNative, workJournal, feed }); }
  finally { feed?.close(); loop.close(); await workEffects?.stop(); await workJournal?.close(); await host.close(); await runners.close(); await rm(directory, { recursive: true, force: true }); }
}

const childSource = 'function* main(os, input) { const result = yield os.wait({scope:input,path:["mature"],equals:true}); return {scope:input,wait:result.status}; }';

test('real sandboxed growth waits leave an unrelated root free to complete', async () => fixture(async ({ definition, install, publish, until, loop, invocations, waits, host }) => {
  publish('wheat', 1, false);
  const farm = await install(await definition('function* main(os) { const seen = yield os.observe({scopes:["wheat"]}); const ready = yield os.wait({scope:"wheat",path:["mature"],equals:true},{cursor:{epoch:seen.epoch,sequence:seen.sequence}}); return ready.status; }'));
  const other = await install(await definition('function* main(os) { const seen = yield os.observe({scopes:["wheat"]}); return seen.scopes[0].frame.captureId; }'));
  await until(() => invocations.inspect(other.rootId).outcome && waits.state().waits === 1);
  assert.equal(invocations.inspect(farm.rootId).phase, 'running');
  assert.equal(invocations.inspect(other.rootId).outcome.value, 'wheat-1');
  assert.equal(invocations.activity(), null);
  assert.equal(loop.state().jobs, 0);
  publish('wheat', 2, true);
  await until(() => invocations.inspect(farm.rootId).outcome);
  assert.equal(invocations.inspect(farm.rootId).outcome.value, 'met');
  assert.equal((await host.advance()).length, 2);
}));

test('owned child generators run concurrently and join distinct results through locked dependencies', async () => fixture(async ({ definition, install, publish, until, invocations, loop }) => {
  publish('wheat', 1, false); publish('sheep', 1, false);
  const child = await definition(childSource);
  const parent = await install(await definition('function* main(os) { const a = yield os.spawn("child","wheat"); const b = yield os.spawn("child","sheep"); const x = yield os.join(a); const y = yield os.join(b); return [x.value,y.value]; }', { dependencies: { child: child.digest } }));
  await until(() => loop.state().invocations.filter(entry => entry.phase === 'waiting').length === 2);
  assert.equal(invocations.capacity().retainedChildren, 2);
  publish('sheep', 2, true);
  await until(() => loop.state().invocations.filter(entry => entry.phase === 'waiting').length === 1);
  assert.equal(invocations.inspect(parent.rootId).outcome, null);
  publish('wheat', 2, true);
  await until(() => invocations.inspect(parent.rootId).outcome);
  assert.deepEqual(invocations.inspect(parent.rootId).outcome.value, [{ scope: 'wheat', wait: 'met' }, { scope: 'sheep', wait: 'met' }]);
  assert.equal(invocations.capacity().retainedChildren, 0);
}));

test('a returned parent retains its waiting child, and cancellation preserves physical cleanup ownership', async () => fixture(async ({ definition, install, publish, until, invocations, loop, waits, host }) => {
  publish('wheat', 1, false);
  const child = await definition(childSource);
  const parent = await install(await definition('function* main(os) { yield os.spawn("child","wheat"); return "body-returned"; }', { dependencies: { child: child.digest } }));
  await until(() => invocations.inspect(parent.rootId).phase === 'closing' && waits.state().waits === 1);
  const [waiting] = loop.state().invocations;
  invocations.trackActivity('cleanup-fixture', [waiting.id]);
  loop.cancel(parent.rootId);
  loop.tick(); publish('wheat', 2, true); loop.tick();
  assert.equal(waits.state().waits, 0);
  assert.equal(invocations.activity().stopRequested, true);
  assert.equal(invocations.inspect(parent.rootId).outcome, null);
  assert.deepEqual(await host.advance(), []);
  invocations.settleActivity('cleanup-fixture', { released: true, accountingComplete: true });
  assert.equal((await host.advance())[0].outcome.status, 'cancelled');
  assert.equal(loop.state().invocations.length, 0);
}));

test('a delayed child initialization neither blocks another root nor resumes a cancelled parent', async () => fixture(async ({ definition, install, until, invocations, loop, host }) => {
  const child = await definition('function* main() { return 2; }');
  const parent = await install(await definition('function* main(os) { const child = yield os.spawn("child"); yield os.join(child); return "resumed"; }', { dependencies: { child: child.digest } }));
  let spawned = false, release;
  const delayed = new Promise(resolve => { release = resolve; }), original = host.spawn.bind(host);
  host.spawn = async (...args) => { const handle = await original(...args); spawned = true; await delayed; return handle; };
  try {
    const other = await install(await definition('function* main() { return "independent"; }'));
    await until(() => spawned && invocations.inspect(other.rootId).outcome);
    loop.cancel(parent.rootId);
    release();
    await until(() => loop.state().jobs === 0);
    assert.equal(invocations.inspect(parent.rootId).outcome.status, 'cancelled');
    assert.equal(invocations.capacity().retainedChildren, 0);
    assert.equal(loop.state().invocations.length, 0);
  } finally { release(); }
}));

test('service rejections are typed and an unrelated guest failure stays within its root', async () => fixture(async ({ definition, install, until, invocations }) => {
  const rejected = await install(await definition('function* main(os) { const bad = yield os.observe({scopes:["sheep"]}); const unavailable = yield os.work("not-installed"); return [bad,unavailable]; }', { capabilities: ['observe:wheat'] }));
  const failed = await install(await definition('function* main() { throw Error("broken-root"); }'));
  await until(() => invocations.inspect(rejected.rootId).outcome && invocations.inspect(failed.rootId).outcome);
  assert.deepEqual(invocations.inspect(rejected.rootId).outcome.value, [
    { status: 'rejected', reason: 'operation_not_granted' }, { status: 'rejected', reason: 'service_unavailable', service: 'work' }
  ]);
  assert.equal(invocations.inspect(failed.rootId).outcome.status, 'failure');
  assert.equal(invocations.activity(), null);
}));

test('an offers dependency needs observation and work services before a child can be allocated', async () => fixture(async ({ definition, install, until, invocations }) => {
  const child = await definition('function offers() { return []; }', { mode: 'offers' });
  const parent = await install(await definition('function* main(os) { return yield os.spawn("child"); }', { dependencies: { child: child.digest } }));
  await until(() => invocations.inspect(parent.rootId).outcome);
  assert.deepEqual(invocations.inspect(parent.rootId).outcome.value, { status: 'rejected', reason: 'unsupported_definition_mode' });
  assert.equal(invocations.capacity().retainedChildren, 0);
}));

test('closing removes subscriptions without needing a final observation update', async () => fixture(async ({ definition, install, until, invocations, waits, loop }) => {
  const root = await install(await definition(childSource), 'wheat');
  await until(() => waits.state().waits === 1);
  loop.close();
  assert.equal(waits.state().waits, 0);
  assert.equal(invocations.inspect(root.rootId).outcome.status, 'cancelled');
  assert.throws(() => loop.attach(root.rootId), /execution_closed/);
  assert.equal(loop.state().closed, true);
}));

for (const failurePolicy of ['cancel_siblings', 'collect_all']) test(`a host service fault respects ${failurePolicy} and retains the cleanup owner`, async () => fixture(async ({ definition, install, publish, until, invocations, waits, loop }) => {
  publish('wheat', 1, false); publish('sheep', 1, false);
  const good = await definition(childSource);
  const bad = await definition('function* main(os) { yield os.wait({scope:"sheep",path:["mature"],equals:true}); yield os.observe({scopes:["sheep"]}); return "unreachable"; }');
  const group = await definition('function* main(os) { const good = yield os.spawn("good","wheat"); const bad = yield os.spawn("bad"); const x = yield os.join(bad); const y = yield os.join(good); return [x.status,y.status]; }', { dependencies: { good: good.digest, bad: bad.digest } });
  const root = await install(await definition(`function* main(os) { const group = yield os.spawn("group",null,{failurePolicy:"${failurePolicy}"}); return yield os.join(group); }`, { dependencies: { group: group.digest } }));
  const read = waits.observe.bind(waits);
  waits.observe = (owner, scopes, options) => {
    if (scopes.includes('sheep')) throw Error('observation_source_failed');
    return read(owner, scopes, options);
  };
  await until(() => waits.state().waits === 2);
  const goodId = loop.state().invocations.find(entry => invocations.execution(entry.id).definition === good.digest).id;
  invocations.trackActivity('fault-cleanup', [goodId]);
  publish('sheep', 2, true);
  if (failurePolicy === 'cancel_siblings') {
    await until(() => invocations.inspect(root.rootId).phase === 'stopping');
    assert.equal(invocations.activity().stopRequested, true);
    assert.equal(invocations.inspect(root.rootId).outcome, null);
    invocations.settleActivity('fault-cleanup', { released: true, accountingComplete: true });
    assert.equal(invocations.inspect(root.rootId).outcome.status, 'failure');
  } else {
    await until(() => waits.state().waits === 1 && loop.state().invocations.filter(entry => entry.phase === 'joining').length === 2);
    assert.equal(invocations.activity().stopRequested, false);
    assert.equal(invocations.inspect(root.rootId).phase, 'running');
    invocations.settleActivity('fault-cleanup', { released: true, accountingComplete: true });
    publish('wheat', 2, true);
    await until(() => invocations.inspect(root.rootId).outcome);
    assert.deepEqual(invocations.inspect(root.rootId).outcome.value, { status: 'success', value: ['failure', 'success'] });
  }
}));

test('a joined result that cannot fit its runner envelope is an explicit bounded rejection', async () => fixture(async ({ definition, install, publish, until, invocations, loop }) => {
  publish('wheat', 1, false);
  const child = await definition(childSource);
  const root = await install(await definition('function* main(os) { const child = yield os.spawn("child","wheat"); return yield os.join(child); }', { dependencies: { child: child.digest } }));
  await until(() => loop.state().invocations.some(entry => entry.phase === 'waiting'));
  const childId = loop.state().invocations.find(entry => entry.phase === 'waiting').id;
  invocations.trackActivity('large-proof', [childId]);
  publish('wheat', 2, true);
  await until(() => invocations.inspect(childId).phase === 'closing');
  const evidence = { released: true, accountingComplete: true, padding: '' };
  const outcome = { status: 'success', value: { scope: 'wheat', wait: 'met' }, lastActivity: { id: 'large-proof', evidence } };
  evidence.padding = 'x'.repeat(16_360 - Buffer.byteLength(JSON.stringify(outcome)));
  invocations.settleActivity('large-proof', evidence);
  await until(() => invocations.inspect(root.rootId).outcome);
  assert.deepEqual(invocations.inspect(root.rootId).outcome.value, { status: 'rejected', reason: 'effect_response_limit' });
  assert.equal(invocations.capacity().retainedChildren, 0);
}));

test('loop shutdown tolerates a retired process whose durable metadata reply still needs reconciliation', async () => fixture(async ({ definition, install, until, invocations, loop, host, library }) => {
  const root = await install(await definition('function* main() { return 1; }'));
  await until(() => invocations.inspect(root.rootId).outcome);
  const original = library.recordRetired.bind(library);
  let lost = false;
  library.recordRetired = async (...args) => {
    const result = await original(...args);
    if (!lost) { lost = true; throw Error('retirement_reply_lost'); }
    return result;
  };
  await assert.rejects(host.advance(), /retirement_reply_lost/);
  assert.doesNotThrow(() => loop.close());
  assert.equal(invocations.capacity().roots, 1);
  assert.equal((await host.advance())[0].outcome.value, 1);
}));

test('invalid spawn policies and overlong grant lists reject before allocation without failing the caller', async () => fixture(async ({ definition, install, until, invocations }) => {
  const child = await definition('function* main() { return 1; }', { capabilities: ['observe:wheat'] });
  const root = await install(await definition('function* main(os) { const results = []; for (const options of [{failurePolicy:"typo"},{failurePolicy:""},{failurePolicy:null},{grants:Array(65).fill("observe:wheat")}]) results.push(yield os.spawn("child",null,options)); return results; }', { dependencies: { child: child.digest } }));
  await until(() => invocations.inspect(root.rootId).outcome);
  assert.deepEqual(invocations.inspect(root.rootId).outcome.value, Array.from({ length: 4 }, () => ({ status: 'rejected', reason: 'invalid_spawn_options' })));
  assert.equal(invocations.capacity().retainedChildren, 0);
}));

test('an observation query without scopes is a typed argument rejection', async () => fixture(async ({ definition, install, until, invocations }) => {
  const root = await install(await definition('function* main(os) { const a = yield os.observe({}); const b = yield os.observe({offset:0}); return [a,b]; }'));
  await until(() => invocations.inspect(root.rootId).outcome);
  assert.deepEqual(invocations.inspect(root.rootId).outcome.value, Array.from({ length: 2 }, () => ({ status: 'rejected', reason: 'invalid_observation_query' })));
}));

const supplySource = 'function* main(os) { yield os.target("wheat",2); return yield os.demand("wheat",2,["chest"]); }';

test('an installed recurring offer duty repeats only from fresh observations and stops offering after its condition clears', async () => fixture(async ({ definition, install, until, workNative, workJournal, invocations, work, loop }) => {
  const source = 'function offers(os,input,view) { const seen=view.scopes.find(s=>s.scope==="wheat"); if(!seen?.current || seen.frame.facts[0].value>=4) return []; const offer=os.work("chest",{direction:"withdraw",itemId:"minecraft:wheat",quantity:2}); return [offer,offer]; }';
  const duty = await install(await definition(source, { mode: 'offers' }));
  const waiter = await install(await definition('function* main(os) { return (yield os.wait({scope:"wheat",path:["stock","wheat"],atLeast:4})).status; }'));
  await until(() => workNative.submissions === 1 && !work.state().busy);
  assert.equal(work.state().recurringOffers, 1);
  workNative.progress(2, 2, true); workNative.playerQuantity = 2; workNative.quantity = 2; workNative.windowOpen = false;
  await until(() => !invocations.activity() && !work.state().busy);
  assert.equal(invocations.inspect(waiter.rootId).outcome, null);
  assert.equal(workNative.submissions, 1);
  workNative.windowOpen = true;
  await until(() => workNative.submissions === 2 && !work.state().busy);
  workNative.progress(2, 2, true); workNative.playerQuantity = 4; workNative.quantity = 0; workNative.windowOpen = false;
  await until(() => invocations.inspect(waiter.rootId).outcome && work.state().recurringOffers === 0);
  assert.equal(invocations.inspect(waiter.rootId).outcome.value, 'met');
  assert.equal(invocations.inspect(duty.rootId).phase, 'running');
  assert.equal(workNative.submissions, 2);
  assert.deepEqual(await workJournal.unfinished(), []);
  loop.cancel(duty.rootId);
  assert.equal(invocations.inspect(duty.rootId).outcome.status, 'cancelled');
}, { workEnabled: true, feedEnabled: true }));

test('a late offers evaluation is discarded after a newer capture and a quiet duty evaluates only once per basis', async () => fixture(async ({ definition, install, until, host, feed, workNative, work, loop, invocations }) => {
  let evaluations = 0, release;
  const barrier = new Promise(resolve => { release = resolve; }), original = host.offers.bind(host);
  host.offers = async (...args) => {
    evaluations++;
    const result = await original(...args);
    if (evaluations === 1) await barrier;
    return result;
  };
  try {
    const duty = await install(await definition('function offers(os,input,view) { return view.scopes[0].frame.facts[0].value===0 ? [os.work("chest",{direction:"withdraw",itemId:"minecraft:wheat",quantity:2})] : []; }', { mode: 'offers' }));
    const other = await install(await definition('function* main() { return "independent"; }'));
    await until(() => evaluations === 1 && invocations.inspect(other.rootId).outcome);
    assert.equal(invocations.inspect(other.rootId).outcome.value, 'independent');
    workNative.playerQuantity = 4;
    await feed.refresh(); release();
    await until(() => loop.state().jobs === 0);
    assert.equal(work.state().requests, 0);
    assert.equal(workNative.submissions, 0);
    await until(() => evaluations === 2 && loop.state().jobs === 0);
    for (let i = 0; i < 20; i++) { loop.tick(); await delay(1); }
    assert.equal(evaluations, 2);
    assert.equal(work.state().requests, 0);
    assert.equal(invocations.inspect(duty.rootId).phase, 'running');
  } finally { release(); }
}, { workEnabled: true, feedEnabled: true }));

test('a returned parent retains its recurring child and cancellation awaits the admitted attempt release', async () => fixture(async ({ definition, install, until, workNative, workJournal, work, invocations, loop }) => {
  const child = await definition('function offers(os) { return [os.work("chest",{direction:"withdraw",itemId:"minecraft:wheat",quantity:2})]; }', { mode: 'offers' });
  const parent = await install(await definition('function* main(os) { yield os.spawn("duty"); return "body returned"; }', { dependencies: { duty: child.digest } }));
  await until(() => invocations.inspect(parent.rootId).phase === 'closing' && workNative.submissions === 1 && !work.state().busy);
  assert.equal(invocations.capacity().retainedChildren, 1);
  loop.cancel(parent.rootId);
  await until(() => workNative.cancellations === 1 && !work.state().busy);
  assert.equal(invocations.inspect(parent.rootId).outcome, null);
  assert.equal((await workJournal.unfinished()).length, 1);
  workNative.progress(2, 1, false); loop.tick();
  assert.notEqual(invocations.activity(), null);
  workNative.progress(2, 1, true, 'CANCELLED');
  await until(() => invocations.inspect(parent.rootId).outcome);
  assert.equal(invocations.inspect(parent.rootId).outcome.status, 'cancelled');
  assert.equal(invocations.capacity().retainedChildren, 0);
  assert.equal(work.state().requests, 0);
  assert.deepEqual(await workJournal.unfinished(), []);
}, { workEnabled: true, feedEnabled: true }));

test('a failed offer batch stays within its root while an ungranted observation scope stays outside its VM', async () => fixture(async ({ definition, install, until, invocations, work, workNative }) => {
  const restricted = await install(await definition('function offers(os,input,view) { if(view.scopes.length) throw Error("scope leaked"); return []; }', { mode: 'offers', capabilities: ['container:home'] }));
  const failed = await install(await definition('function offers(os) { return [os.work("chest",{direction:"withdraw",itemId:"minecraft:wheat",quantity:2}),os.work("absent")]; }', { mode: 'offers' }));
  const other = await install(await definition('function* main() { return "independent"; }'));
  await until(() => invocations.inspect(failed.rootId).outcome && invocations.inspect(other.rootId).outcome);
  assert.equal(invocations.inspect(failed.rootId).outcome.status, 'failure');
  assert.equal(invocations.inspect(restricted.rootId).phase, 'running');
  assert.equal(invocations.inspect(other.rootId).outcome.value, 'independent');
  assert.equal(work.state().requests, 0);
  assert.equal(workNative.submissions, 0);
}, { workEnabled: true, feedEnabled: true }));

test('real installed generators receive automatic supplies and observed stock through the native feed', async () => fixture(async ({ definition, install, until, workNative, workJournal, invocations, feed }) => {
  const revision = await definition('function* main(os) { const delivered = yield os.demand("wheat",2,["chest"]); const observed = yield os.wait({scope:"wheat",path:["stock","wheat"],atLeast:4}); return {delivered:delivered.credited,observed:observed.status}; }');
  const a = await install(revision), b = await install(revision);
  await until(() => workNative.submissions === 1);
  assert.deepEqual(new Set(invocations.activity().subscribers), new Set([a.rootId, b.rootId]));
  workNative.progress(4, 4, true); workNative.playerQuantity = 4; workNative.quantity = 0; workNative.windowOpen = false;
  await until(() => invocations.inspect(a.rootId).outcome && invocations.inspect(b.rootId).outcome);
  assert.deepEqual(invocations.inspect(a.rootId).outcome.value, { delivered: 2, observed: 'met' });
  assert.deepEqual(invocations.inspect(b.rootId).outcome.value, { delivered: 2, observed: 'met' });
  assert.equal(workNative.submissions, 1);
  assert.deepEqual(await workJournal.unfinished(), []);
  assert.equal(feed.state().fault, null);
}, { suppliesEnabled: true, feedEnabled: true }));

test('sandboxed demands are supplied automatically while an unrelated root completes and one subscriber cancels', async () => fixture(async ({ definition, install, until, invocations, resources, ledger, loop, work, workNative }) => {
  const source = await definition(supplySource), a = await install(source), b = await install(source);
  const other = await install(await definition('function* main() { return "independent"; }'));
  await until(() => resources.pending().length === 2 && invocations.inspect(other.rootId).outcome);
  const demands = resources.pending(), aId = demands.find(item => item.owner === a.rootId).id, bId = demands.find(item => item.owner === b.rootId).id;
  assert.equal(invocations.inspect(other.rootId).outcome.value, 'independent');
  const pending = work.pending();
  assert.equal(pending.length, 2);
  for (const item of pending) work.publish(item.id, { epoch: 'world', captureId: 'automatic-supply', captureSequence: 1, readiness: 'ready', ageUpperBoundMillis: 0 });
  await until(() => invocations.activity());
  loop.cancel(a.rootId);
  await until(() => ledger.delivery(aId).withdrawn && workNative.submissions === 1 && !work.state().busy);
  assert.equal(workNative.cancellations, 0);
  workNative.progress(4, 4, false);
  await until(() => ledger.delivery(bId).credited === 2);
  assert.equal(invocations.inspect(b.rootId).outcome, null);
  workNative.progress(4, 4, true);
  await until(() => invocations.inspect(b.rootId).outcome && invocations.inspect(a.rootId).outcome);
  assert.equal(invocations.inspect(a.rootId).outcome.status, 'cancelled');
  assert.equal(invocations.inspect(b.rootId).outcome.value.status, 'fulfilled');
  assert.equal(invocations.inspect(b.rootId).outcome.value.credited, 2);
  assert.equal(workNative.submissions, 1);
  assert.equal(resources.state().deliveries, 0);
}, { suppliesEnabled: true }));

test('resource-loop demands from independent VMs share one journalled transfer and wait for release', async () => fixture(async ({ definition, install, until, invocations, resources, ledger, operations, directory }) => {
  const native = new NativeContainer({ itemId: 'minecraft:wheat' }), journal = await EffectJournal.open(join(directory, 'effects.sqlite'));
  const effects = new EffectBroker({ native, journal, expectedWorld: 'fixture' });
  try {
    await effects.start();
    const activities = new ActivityCoordinator({ invocations, ledger, effects, operations });
    const source = await definition(supplySource), a = await install(source), b = await install(source);
    const other = await install(await definition('function* main() { return "independent"; }'));
    await until(() => resources.pending().length === 2 && invocations.inspect(other.rootId).outcome);
    assert.equal(invocations.activity(), null);
    const pending = resources.pending();
    const deliveries = [a, b].map(({ rootId }) => {
      const { id, owner, quantity } = pending.find(item => item.owner === rootId);
      return { id, owner, quantity };
    });
    await activities.admit({ owner: a.rootId, operation: 'chest', arguments: { direction: 'withdraw', itemId: 'minecraft:wheat', quantity: 4 }, deliveries });
    native.progress(4, 4, false); await activities.poll();
    assert.equal(resources.take(a.rootId, deliveries[0].id).status, 'pending');
    assert.equal(invocations.inspect(a.rootId).outcome, null);
    native.progress(4, 4, true); await activities.poll();
    await until(() => invocations.inspect(a.rootId).outcome && invocations.inspect(b.rootId).outcome);
    assert.equal(invocations.inspect(a.rootId).outcome.value.credited, 2);
    assert.equal(invocations.inspect(b.rootId).outcome.value.credited, 2);
    assert.equal(resources.state().deliveries, 0);
    assert.equal(native.submissions, 1);
    assert.deepEqual(await journal.unfinished(), []);
  } finally { await effects.stop(); await journal.close(); }
}));

test('resource-loop cancellation keeps a shared transfer for its remaining VM and retires the cancelled subscription', async () => fixture(async ({ definition, install, until, invocations, resources, ledger, operations, directory, loop }) => {
  const native = new NativeContainer({ itemId: 'minecraft:wheat' }), journal = await EffectJournal.open(join(directory, 'effects.sqlite'));
  const effects = new EffectBroker({ native, journal, expectedWorld: 'fixture' });
  try {
    await effects.start();
    const activities = new ActivityCoordinator({ invocations, ledger, effects, operations });
    const source = await definition(supplySource), a = await install(source), b = await install(source);
    await until(() => resources.pending().length === 2);
    const pending = resources.pending();
    const deliveries = [a, b].map(({ rootId }) => {
      const { id, owner, quantity } = pending.find(item => item.owner === rootId);
      return { id, owner, quantity };
    });
    await activities.admit({ owner: a.rootId, operation: 'chest', arguments: { direction: 'withdraw', itemId: 'minecraft:wheat', quantity: 4 }, deliveries });
    native.progress(4, 1, false); await activities.poll();
    loop.cancel(a.rootId); await activities.poll();
    assert.equal(native.cancellations, 0);
    assert.equal(ledger.delivery(deliveries[0].id).credited, 1);
    native.progress(4, 4, true); await activities.poll();
    await until(() => invocations.inspect(b.rootId).outcome);
    assert.equal(invocations.inspect(a.rootId).outcome.status, 'cancelled');
    assert.equal(invocations.inspect(b.rootId).outcome.value.credited, 2);
    resources.poll();
    assert.equal(resources.state().deliveries, 0);
    assert.equal(native.submissions, 1);
    assert.deepEqual(await journal.unfinished(), []);
  } finally { await effects.stop(); await journal.close(); }
}));

test('a sandboxed work call is scheduled through the journal and releases its caller only after physical handback', async () => fixture(async ({ definition, install, until, invocations, work, workNative, workJournal }) => {
  const caller = await install(await definition('function* main(os) { return yield os.work("chest",{direction:"withdraw",itemId:"minecraft:wheat",quantity:2}); }'));
  const other = await install(await definition('function* main() { return "independent"; }'));
  await until(() => work.pending().length === 1 && invocations.inspect(other.rootId).outcome);
  assert.equal(workNative.submissions, 0);
  const [request] = work.pending();
  work.publish(request.id, { epoch: 'world', captureId: 'work-basis-1', captureSequence: 1, readiness: 'ready', ageUpperBoundMillis: 0 });
  await until(() => workNative.submissions === 1 && !work.state().busy);
  workNative.progress(2, 2, false); work.tick({ authority: 'available' });
  await until(() => !work.state().busy);
  assert.equal(invocations.inspect(caller.rootId).outcome, null);
  assert.equal(work.take(caller.rootId, request.id).status, 'pending');
  workNative.progress(2, 2, true);
  await until(() => invocations.inspect(caller.rootId).outcome);
  assert.equal(invocations.inspect(caller.rootId).outcome.value.status, 'success');
  assert.equal(work.state().requests, 0);
  assert.deepEqual(await workJournal.unfinished(), []);
}, { workEnabled: true }));

test('work argument and grant errors return typed rejections to the sandbox without native effects', async () => fixture(async ({ definition, install, until, invocations, workNative }) => {
  const caller = await install(await definition('function* main(os) { const unknown = yield os.work("absent"); const context = yield os.work("chest",{},{id:"foreign"}); const grant = yield os.work("chest"); return [unknown,context,grant]; }', { capabilities: ['observe:wheat'] }));
  await until(() => invocations.inspect(caller.rootId).outcome);
  assert.deepEqual(invocations.inspect(caller.rootId).outcome.value, [
    { status: 'rejected', reason: 'operation_unknown' }, { status: 'rejected', reason: 'invalid_work' },
    { status: 'rejected', reason: 'operation_not_granted' }
  ]);
  assert.equal(workNative.submissions, 0);
}, { workEnabled: true }));

test('a native work fault retires its suspended VM while physical cleanup remains owned', async () => fixture(async ({ definition, install, until, invocations, runners, loop, work, workNative }) => {
  const caller = await install(await definition('function* main(os) { return yield os.work("chest",{direction:"withdraw",itemId:"minecraft:wheat",quantity:2}); }'));
  await until(() => work.pending().length === 1);
  const [request] = work.pending();
  work.publish(request.id, { epoch: 'world', captureId: 'work-basis-1', captureSequence: 1, readiness: 'ready', ageUpperBoundMillis: 0 });
  await until(() => workNative.submissions === 1 && !work.state().busy);
  let failed = false;
  workNative.beforeReply = async name => { if (name === 'os_inspect' && !failed) { failed = true; throw Error('native_transport_lost'); } };
  await until(() => work.state().fault && loop.state().invocations.length === 0);
  assert.equal(runners.state(caller.rootId).ready, false);
  assert.equal(runners.state(caller.rootId).guests, 0);
  assert.equal(invocations.inspect(caller.rootId).outcome, null);
  assert.equal(invocations.activity().stopRequested, true);
  workNative.progress(2, 1, true, 'CANCELLED');
  await until(() => invocations.inspect(caller.rootId).outcome);
  assert.equal(invocations.inspect(caller.rootId).outcome.status, 'failure');
}, { workEnabled: true }));

for (const collectAll of [false, true]) test(`mandatory join reclaims a failed suspended child after its handle is consumed (collect-all subgroup: ${collectAll})`, async () => fixture(async ({ definition, install, until, invocations, runners, loop, publish, waits, work, workNative }) => {
  publish('wheat', 1, false);
  const bad = await definition('function* main(os) { return yield os.work("chest",{direction:"withdraw",itemId:"minecraft:wheat",quantity:2}); }');
  const group = await definition('function* main(os) { yield os.spawn("bad"); return "group-returned"; }', { dependencies: { bad: bad.digest } });
  const waiting = await definition(childSource);
  const parent = await install(collectAll ? await definition('function* main(os) { yield os.spawn("group",null,{failurePolicy:"collect_all"}); yield os.spawn("waiting","wheat"); return "parent-returned"; }', { dependencies: { group: group.digest, waiting: waiting.digest } }) : group);
  await until(() => invocations.inspect(parent.rootId).phase === 'closing' && work.pending().length === 1 && (!collectAll || waits.state().waits === 1));
  const [request] = work.pending();
  workNative.beforeReply = async name => { if (name === 'os_observe') throw Error('fresh_observation_failed'); };
  work.publish(request.id, { epoch: 'world', captureId: 'work-basis-1', captureSequence: 1, readiness: 'ready', ageUpperBoundMillis: 0 });
  await until(() => work.state().fault && !loop.state().invocations.some(state => state.id === request.owner));
  assert.throws(() => invocations.inspect(request.owner), /invocation_unknown/);
  assert.equal(runners.state(parent.rootId).guests, collectAll ? 1 : 0);
  if (collectAll) {
    assert.equal(invocations.inspect(parent.rootId).outcome, null);
    publish('wheat', 2, true);
    await until(() => invocations.inspect(parent.rootId).outcome);
  } else assert.equal(runners.state(parent.rootId).ready, false);
  assert.equal(workNative.submissions, 0);
}, { workEnabled: true }));
