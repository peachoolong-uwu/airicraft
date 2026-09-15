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
import { GeneratorLoop } from '../src/os/loop.mjs';

async function fixture(run) {
  const directory = await mkdtemp(join(tmpdir(), 'airicraft-loop-'));
  const library = await DefinitionLibrary.open(directory), invocations = new InvocationBroker(), runners = new RunnerPool({ invocations });
  const grants = ['observe:wheat', 'observe:sheep'];
  const host = new InstallationHost({ library, invocations, runners, grants, environment: {}, runId: 'loop-test', refresh: async () => null });
  const waits = new ConditionWaits({ invocations, scopes: { wheat: 'observe:wheat', sheep: 'observe:sheep' }, epoch: 'world', now: () => 0 });
  const loop = new GeneratorLoop({ installations: host, invocations, runners, waits });
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
      if (performance.now() > deadline) assert.fail(`loop stalled: ${JSON.stringify(loop.state())}`);
      await delay(5);
    }
  };
  try { await run({ loop, host, invocations, runners, waits, library, definition, install, publish, until }); }
  finally { loop.close(); await host.close(); await runners.close(); await rm(directory, { recursive: true, force: true }); }
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

test('generator dispatch rejects an offers-only dependency before allocating its child', async () => fixture(async ({ definition, install, until, invocations }) => {
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
