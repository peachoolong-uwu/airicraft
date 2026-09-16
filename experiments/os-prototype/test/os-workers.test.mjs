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
import { WorkerService } from '../src/os/workers.mjs';
import { DecisionTrace } from '../src/os/trace.mjs';

async function fixture(run, { transport, workerTrace } = {}) {
  const directory = await mkdtemp(join(tmpdir(), 'airicraft-workers-'));
  const records = [];
  const trace = new DecisionTrace({ runId: 'workers-test', writer: { write: async (_, bytes) => records.push(JSON.parse(bytes.toString())), close: async () => {} } });
  const library = await DefinitionLibrary.open(join(directory, 'library')), invocations = new InvocationBroker(), runners = new RunnerPool({ invocations, trace });
  const host = new InstallationHost({ library, invocations, runners, grants: [], environment: {}, runId: 'workers-test', refresh: async () => null });
  let now = 0, basis = { epoch: 'world', signature: 'material-a', captures: ['capture-1'], progress: null };
  const evidence = { workerEvidence: () => structuredClone(basis) };
  const workers = new WorkerService({ installations: host, invocations, runners, transport, observations: evidence, now: () => now, trace: workerTrace ?? trace });
  const waits = new ConditionWaits({ invocations, scopes: {}, epoch: 'world' });
  const loop = new BehaviorLoop({ installations: host, invocations, runners, waits, workers });
  const definition = async (source, overrides = {}) => {
    const candidate = await library.candidate({ schemaVersion: 1, kind: 'behavior', name: 'worker-caller', description: 'worker execution fixture', tags: [],
      capabilities: [], environment: {}, dependencies: {}, inputContract: true, outputContract: true, examples: [], mode: 'generator', source, ...overrides },
      { reason: 'worker seam test', hypothesis: 'inference waits leave unrelated work available' });
    await library.recordValidation(candidate.digest, { passed: true, checks: ['worker_fixture'], artifacts: [] });
    return candidate;
  };
  const fallback = await definition('function* main(os,input) { return "fallback:" + input; }', { inputContract: { type: 'string' }, outputContract: { type: 'string' } });
  const worker = async (overrides = {}) => {
    const candidate = await library.candidate({ schemaVersion: 1, kind: 'worker', name: 'describe', description: 'stateless description', tags: [], capabilities: [],
      environment: {}, dependencies: { fallback: fallback.digest }, inputContract: { type: 'string' }, outputContract: { type: 'string' }, examples: [],
      prompt: 'Describe the input.', profile: 'test', fallback: 'fallback', ...overrides }, { reason: 'test', hypothesis: 'typed interpretation only' });
    await library.recordValidation(candidate.digest, { passed: true, checks: ['worker_fixture'], artifacts: [] }); return candidate;
  };
  const install = async (revision, input = 'pen', attach = true) => { const item = await host.install(revision.digest, { input }); if (attach) loop.attach(item.rootId); return item.rootId; };
  const until = async predicate => {
    const deadline = performance.now() + 5000;
    while (!predicate()) {
      loop.tick();
      if (performance.now() > deadline) assert.fail(`worker loop stalled: ${JSON.stringify({ workers: workers.state(),
        roots: loop.state().roots.map(id => invocations.inspect(id)) })}`);
      await delay(5);
    }
  };
  try { await run({ workers, loop, host, invocations, runners, definition, worker, install, until,
    time: value => { now = value; }, basis: value => { basis = value; }, records: async () => { await trace.flush(); return records; } }); }
  catch (error) { await trace.flush(); error.workerDiagnostics = records.filter(record => ['runner.memory_unavailable', 'runner.root_failed'].includes(record.type)); throw error; }
  finally { loop.close(); workers.close(); await host.close(); await runners.close(); await trace.close(); await rm(directory, { recursive: true, force: true }); }
}

function provider() {
  const calls = [];
  return { calls, profile: () => ({ model: 'test', identity: 'test-profile-v1' }), complete(profile, request, signal) {
    return new Promise((resolve, reject) => calls.push({ profile, request, signal, resolve, reject }));
  } };
}
const caller = 'function* main(os,input) { return yield os.worker("describe",input); }';

test('an unconfigured worker executes its pinned pure fallback through a supervised VM', async () => fixture(async ({ worker, definition, install, until, invocations, workers }) => {
  const model = await worker();
  const owner = await install(await definition('function* main(os,input) { return yield os.worker("describe",input); }', { dependencies: { describe: model.digest } }));
  await until(() => invocations.inspect(owner).outcome);
  const result = invocations.inspect(owner).outcome.value;
  assert.equal(result.status, 'fallback'); assert.equal(result.value, 'fallback:pen'); assert.equal(result.reason, 'worker_unconfigured');
  assert.equal(result.evidence.epoch, 'world'); assert.equal(result.evidence.definition, model.digest);
  assert.equal(invocations.activity(), null); assert.equal(invocations.capacity().retainedChildren, 0);
  assert.equal(workers.state().calls, 0);
}));

test('two supervised callers share inference with independent cancellation while another root runs', async () => {
  const transport = provider();
  await fixture(async ({ worker, definition, install, until, invocations, loop, workers }) => {
    const model = await worker(), behavior = await definition(caller, { dependencies: { describe: model.digest } });
    const a = await install(behavior), b = await install(behavior);
    const independent = await install(await definition('function* main() { return "other duty ran"; }'));
    await until(() => transport.calls.length === 1 && workers.state().requests === 2 && invocations.inspect(independent).outcome);
    assert.equal(invocations.inspect(independent).outcome.value, 'other duty ran');
    assert.equal(invocations.activity(), null);
    loop.cancel(a); assert.equal(transport.calls[0].signal.aborted, false);
    transport.calls[0].resolve({ value: 'two sheep', model: 'test', usage: { inputTokens: 5, outputTokens: 3, totalTokens: 8 } });
    await until(() => invocations.inspect(b).outcome);
    assert.equal(invocations.inspect(a).outcome.status, 'cancelled');
    assert.equal(invocations.inspect(b).outcome.value.value, 'two sheep');
    assert.equal(invocations.inspect(b).outcome.value.status, 'success');
    assert.equal(workers.state().calls, 1);
    const cached = await install(behavior);
    await until(() => invocations.inspect(cached).outcome);
    assert.equal(invocations.inspect(cached).outcome.value.source, 'cache');
    assert.equal(transport.calls.length, 1);
  }, { transport });
});

test('queue and inference deadlines bound waiting while cancelled transport keeps its active slot and charge', async () => {
  const transport = provider();
  await fixture(async ({ worker, definition, install, until, invocations, workers, time }) => {
    const model = await worker(), behavior = await definition(caller, { dependencies: { describe: model.digest } });
    const inputs = ['a', 'b', 'c', 'd'], roots = [];
    for (const input of inputs) roots.push(await install(behavior, input));
    await until(() => workers.state().requests === 4);
    assert.equal(transport.calls.length, 2); assert.equal(workers.state().queued, 2);
    const active = transport.calls.map(call => roots[inputs.indexOf(call.request.input)]);
    const queued = roots.filter(id => !active.includes(id));
    assert.equal(queued.length, 2);
    time(5000); workers.poll();
    await until(() => queued.every(id => invocations.inspect(id).outcome));
    for (const id of queued) assert.equal(invocations.inspect(id).outcome.value.reason, 'worker_queue_timeout');
    time(20000); workers.poll();
    await until(() => roots.every(id => invocations.inspect(id).outcome));
    for (const id of active) assert.equal(invocations.inspect(id).outcome.value.reason, 'worker_timeout');
    assert.equal(workers.state().active, 2, 'unsettled cancellation cannot manufacture provider capacity');
    assert.equal(workers.state().outputTokensCharged, 1024);
    assert.equal(workers.state().unknownUsage, 2);
    assert.equal(transport.calls.every(call => call.signal.aborted), true);
    const next = await install(behavior, 'e'); await until(() => workers.state().queued === 1);
    transport.calls[0].resolve({ value: 'late value', usage: null });
    await until(() => transport.calls.length === 3);
    transport.calls[2].resolve({ value: 'fresh value', usage: null });
    await until(() => invocations.inspect(next).outcome);
    assert.equal(invocations.inspect(next).outcome.value.value, 'fresh value');
    assert.equal(invocations.inspect(active[0]).outcome.value.value, `fallback:${transport.calls[0].request.input}`);
    transport.calls[1].resolve({ value: 'another late value', usage: null });
    await until(() => workers.state().active === 0);
  }, { transport });
});

test('the run admits at most twelve paid attempts and never renews its thirty minute budget', async () => {
  const transport = { profile: () => ({ model: 'test', identity: 'test' }), complete: async (_, request) => ({ value: request.input, usage: null }) };
  await fixture(async ({ worker, definition, install, until, invocations, workers, time }) => {
    const model = await worker();
    const root = await install(await definition('function* main(os) { const results=[]; for(let i=0;i<13;i++) { const r=yield os.worker("describe",String(i)); results.push({status:r.status,reason:r.reason||null}); } return results; }',
      { dependencies: { describe: model.digest } }));
    await until(() => invocations.inspect(root).outcome);
    const result = invocations.inspect(root).outcome.value;
    assert.equal(result.filter(item => item.status === 'success').length, 12);
    assert.deepEqual(result[12], { status: 'fallback', reason: 'worker_call_budget' });
    assert.equal(workers.state().calls, 12); assert.equal(workers.state().outputTokensCharged, 6144);
    time(30 * 60 * 1000);
    const later = await install(await definition(caller, { dependencies: { describe: model.digest } }), 'later');
    await until(() => invocations.inspect(later).outcome);
    assert.equal(invocations.inspect(later).outcome.value.reason, 'worker_run_expired');
    assert.equal(workers.state().calls, 12);
  }, { transport });
});

test('changed evidence revokes a pending subscriber and also discards an already finished recommendation before delivery', async () => {
  const transport = provider();
  await fixture(async ({ worker, definition, install, until, invocations, workers, basis }) => {
    const model = await worker(), behavior = await definition(caller, { dependencies: { describe: model.digest } });
    const root = await install(behavior, 'pen', false), id = workers.request(root, 1, 'describe', 'pen');
    assert.equal(workers.request(root, 1, 'describe', 'pen'), id);
    assert.throws(() => workers.request(root, 2, 'describe', 'other'), /worker_outstanding/);
    basis({ epoch: 'world', signature: 'material-b', captures: ['capture-2'], progress: null }); workers.poll();
    assert.equal(transport.calls[0].signal.aborted, true);
    assert.equal(workers.take(root, id).status, 'stale');
    transport.calls[0].resolve({ value: 'obsolete', usage: null });
    await until(() => workers.state().active === 0);
    assert.equal(workers.state().cached, 0);
    const next = workers.request(root, 2, 'describe', 'pen');
    transport.calls[1].resolve({ value: 'current when completed', usage: null });
    await until(() => workers.state().active === 0);
    basis({ epoch: 'other-world', signature: 'material-b', captures: ['capture-3'], progress: null });
    assert.equal(workers.take(root, next).status, 'stale');
    assert.equal(invocations.capacity().retainedChildren, 0);
    assert.throws(() => workers.request(root, 2, 'describe', 'pen'), /worker_request_retired/);
  }, { transport });
});

test('a failed fingerprint backs off by eligible world progress, preserves its typed cause, and permits material reconsideration', async () => {
  const transport = provider();
  await fixture(async ({ worker, definition, install, until, workers, basis, time }) => {
    const model = await worker(), behavior = await definition(caller, { dependencies: { describe: model.digest } });
    const owner = await install(behavior, 'pen', false);
    const evidence = ticks => ({ epoch: 'world', signature: 'material-a', captures: ['same-world'], progress: { clockId: 'growth', eligibleTicks: ticks } });
    basis(evidence(100));
    const first = workers.request(owner, 1, 'describe', 'pen');
    transport.calls[0].reject(Error('worker_malformed'));
    let result; await until(() => (result = workers.take(owner, first)).status !== 'pending');
    assert.equal(result.reason, 'worker_malformed');
    time(100000); basis(evidence(1299));
    const second = workers.request(owner, 2, 'describe', 'pen');
    await until(() => (result = workers.take(owner, second)).status !== 'pending');
    assert.equal(result.reason, 'worker_reconsider_later'); assert.equal(transport.calls.length, 1);
    basis(evidence(1300));
    const third = workers.request(owner, 3, 'describe', 'pen');
    assert.equal(transport.calls.length, 2);
    transport.calls[1].reject(Error('worker_transport_failure'));
    await until(() => (result = workers.take(owner, third)).status !== 'pending');
    basis({ ...evidence(1300), signature: 'different-eligible-targets' });
    const fourth = workers.request(owner, 4, 'describe', 'pen');
    assert.equal(transport.calls.length, 3);
    transport.calls[2].resolve({ value: 'fresh alternative', usage: null });
    await until(() => (result = workers.take(owner, fourth)).status !== 'pending');
    assert.equal(result.value, 'fresh alternative');
  }, { transport });
});

test('worker traces separate queue, provider and validation time, budget reservations and late evidence', async () => {
  const transport = provider();
  await fixture(async ({ worker, definition, install, workers, time, until, records, loop }) => {
    const model = await worker(), behavior = await definition(caller, { dependencies: { describe: model.digest } });
    const owners = [];
    for (const input of ['a','b','c']) owners.push(await install(behavior, input, false));
    const ids = owners.map((owner, index) => workers.request(owner, 1, 'describe', String(index)));
    time(1000); transport.calls[0].resolve({ value: 'accepted', model: 'actual-model', usage: { inputTokens: 21, outputTokens: 10, totalTokens: 31 },
      latency: { providerMillis: 990, validationMillis: 10 } });
    await until(() => transport.calls.length === 3);
    assert.equal(workers.take(owners[0], ids[0]).evidence.model, 'actual-model');
    time(1200); transport.calls[2].resolve({ value: 'after queue', usage: null, latency: { providerMillis: 198, validationMillis: 2 } });
    await until(() => workers.state().active === 1);
    loop.attach(owners[1]); loop.cancel(owners[1]);
    transport.calls[1].resolve({ value: 'obsolete response', model: 'actual-model', usage: { inputTokens: 1, outputTokens: 1, totalTokens: 2 } });
    await until(() => workers.state().active === 0);
    const events = await records(), settled = events.filter(event => event.type === 'worker.provider_settled').map(event => event.data);
    assert.equal(settled.length, 3);
    assert.deepEqual(settled.find(event => event.computationId === ids[2]).latency, { queueMillis: 1000, providerMillis: 198, validationMillis: 2 });
    assert.equal(settled.find(event => event.computationId === ids[1]).late, true);
    assert.equal(workers.state().outputTokensCharged, 1034, 'late usage cannot refund an ambiguous cancellation');
    assert.equal(workers.state().unknownUsage, 2);
    assert.equal(events.filter(event => event.type === 'worker.started').length, 3);
  }, { transport });
});

test('a fallback that tries to perform effects is contained and cannot fail an independent duty', async () => fixture(async ({ worker, definition, install, until, invocations }) => {
  const fallback = await definition('function* main(os) { yield os.work("chest",{}); return "wrong"; }',
    { inputContract: { type: 'string' }, outputContract: { type: 'string' } });
  const model = await worker({ dependencies: { fallback: fallback.digest } });
  const root = await install(await definition(caller, { dependencies: { describe: model.digest } }));
  const independent = await install(await definition('function* main() { return "still running"; }'));
  await until(() => invocations.inspect(root).outcome && invocations.inspect(independent).outcome);
  assert.equal(invocations.inspect(root).outcome.value.reason, 'worker_fallback_failed');
  assert.equal(invocations.inspect(root).outcome.value.status, 'unavailable');
  assert.equal(invocations.inspect(independent).outcome.value, 'still running');
  assert.equal(invocations.activity(), null); assert.equal(invocations.capacity().retainedChildren, 0);
}));

test('cancelled inference retains its last verified progress basis for later reconsideration', async () => {
  const transport = provider();
  await fixture(async ({ worker, definition, install, until, workers, loop, basis }) => {
    const model = await worker(), behavior = await definition(caller, { dependencies: { describe: model.digest } });
    const evidence = ticks => ({ epoch: 'world', signature: 'same', captures: ['capture'], progress: { clockId: 'growth', eligibleTicks: ticks } });
    basis(evidence(100)); const first = await install(behavior, 'pen', false);
    workers.request(first, 1, 'describe', 'pen'); loop.attach(first); loop.cancel(first);
    transport.calls[0].resolve({ value: 'late', usage: null }); await until(() => workers.state().active === 0);
    basis(evidence(1300)); const next = await install(behavior, 'pen', false);
    const request = workers.request(next, 1, 'describe', 'pen');
    assert.equal(transport.calls.length, 2);
    transport.calls[1].resolve({ value: 'fresh reconsideration', usage: null });
    let result; await until(() => (result = workers.take(next, request)).status !== 'pending');
    assert.equal(result.value, 'fresh reconsideration');
  }, { transport });
});

test('a declared stuck-work fingerprint gates successful and cached advice despite reworded input', async () => {
  const transport = provider();
  await fixture(async ({ worker, definition, install, until, workers, basis }) => {
    const inputContract = { type: 'object', properties: { fingerprint: { type: 'string' }, detail: { type: 'string' } }, required: ['fingerprint', 'detail'] };
    const fallback = await definition('function* main() { return "defer"; }', { inputContract, outputContract: { type: 'string' } });
    const model = await worker({ inputContract, dependencies: { fallback: fallback.digest }, reconsideration: { fingerprint: ['fingerprint'] } });
    const root = await install(await definition(caller, { dependencies: { describe: model.digest } }), null, false);
    const evidence = ticks => ({ epoch: 'world', signature: 'stuck-material', captures: ['same'], progress: { clockId: 'growth', eligibleTicks: ticks } });
    basis(evidence(0));
    const input = { fingerprint: 'sheep-route', detail: 'blocked' }, first = workers.request(root, 1, 'describe', input);
    transport.calls[0].resolve({ value: 'retry', usage: null });
    let result; await until(() => (result = workers.take(root, first)).status !== 'pending');
    assert.equal(result.value, 'retry');
    const second = workers.request(root, 2, 'describe', input);
    await until(() => (result = workers.take(root, second)).status !== 'pending');
    assert.equal(result.reason, 'worker_reconsider_later');
    const changed = { ...input, detail: 'still blocked' }, third = workers.request(root, 3, 'describe', changed);
    await until(() => (result = workers.take(root, third)).status !== 'pending');
    assert.equal(result.reason, 'worker_reconsider_later'); assert.equal(transport.calls.length, 1);
    basis(evidence(1200)); const fourth = workers.request(root, 4, 'describe', changed);
    assert.equal(transport.calls.length, 2);
    transport.calls[1].resolve({ value: 'reconsidered', usage: null });
    await until(() => (result = workers.take(root, fourth)).status !== 'pending');
    assert.equal(result.value, 'reconsidered');
  }, { transport });
});

test('a broken decision trace contains queued dispatch and background faults without orphaning capacity', async () => {
  const transport = provider(); let broken = false;
  const trace = new DecisionTrace({ runId: 'failed-writer', writer: { write: async () => { if (broken) throw Error('fixture_disk_failure'); }, close: async () => {} } });
  try {
    await fixture(async ({ worker, definition, install, until, workers, invocations }) => {
      const model = await worker(), behavior = await definition(caller, { dependencies: { describe: model.digest } });
      const owners = [];
      for (const input of ['a', 'b', 'c']) owners.push(await install(behavior, input, false));
      owners.forEach((owner, index) => workers.request(owner, 1, 'describe', String(index)));
      assert.equal(transport.calls.length, 2); assert.equal(workers.state().queued, 1);
      await trace.flush(); broken = true; trace.record('fixture.disk_failure', {});
      await delay(5);
      transport.calls[0].resolve({ value: 'cannot qualify', usage: null });
      await until(() => owners.every(owner => invocations.inspect(owner).outcome));
      for (const owner of owners) {
        assert.equal(invocations.inspect(owner).outcome.status, 'failure');
        assert.equal(invocations.inspect(owner).outcome.cause.reason, 'worker_service_fault');
      }
      assert.equal(workers.state().fault, 'fixture_disk_failure');
      assert.equal(workers.state().calls, 2); assert.equal(transport.calls.length, 2);
      assert.equal(transport.calls[1].signal.aborted, true);
      transport.calls[1].resolve({ value: 'late', usage: null });
      await until(() => workers.state().active === 0);
      assert.equal(invocations.capacity().retainedChildren, 0);
    }, { transport, workerTrace: trace });
  } finally { await trace.close().catch(() => {}); }
});
