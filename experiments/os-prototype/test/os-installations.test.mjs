import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DefinitionLibrary } from '../src/os/library.mjs';
import { LibraryValidator } from '../src/os/validation.mjs';
import { InvocationBroker } from '../src/os/broker.mjs';
import { RunnerPool } from '../src/os/runners.mjs';
import { InstallationHost } from '../src/os/installations.mjs';

const definition = (name, overrides = {}) => ({ schemaVersion: 1, kind: 'behavior', name, description: 'installation fixture', tags: [],
  capabilities: ['observe'], environment: {}, dependencies: {}, mode: 'generator', inputContract: { type: 'integer' }, outputContract: { type: 'integer' },
  source: 'function* main(os, input) { yield os.observe({}); return input; }',
  examples: [{ input: 2, responses: [null], expected: [{ done: false, value: { kind: 'observe', query: {} } }, { done: true, value: 2 }] }], ...overrides });

async function fixture(run, { refresh } = {}) {
  const directory = await mkdtemp(join(tmpdir(), 'airicraft-installations-'));
  const library = await DefinitionLibrary.open(directory), invocations = new InvocationBroker(), runners = new RunnerPool({ invocations });
  const validator = new LibraryValidator({ library, invocations, runners, environment: {}, grants: ['observe', 'work:crop'] });
  const snapshots = [];
  const host = new InstallationHost({ library, invocations, runners, environment: {}, grants: ['observe', 'work:crop'], runId: 'fixture',
    refresh: refresh ?? (async input => { snapshots.push(input); return { captureId: `fresh-${snapshots.length}` }; }) });
  const validated = async value => {
    const record = await library.candidate(value, { reason: 'test', hypothesis: 'safe installation and replacement' });
    const evidence = await validator.validate(record.digest);
    assert.equal(evidence.passed, true, JSON.stringify(evidence));
    return record;
  };
  try { await run({ library, invocations, runners, host, snapshots, validated }); }
  finally { await host.close(); await runners.close(); await rm(directory, { recursive: true, force: true }); }
}

test('one digest can run against distinct input bindings with separate joined outcomes', async () => fixture(async ({ host, library, validated }) => {
  const revision = await validated(definition('reused'));
  const a = await host.install(revision.digest, { input: 11 }), b = await host.install(revision.digest, { input: 22 });
  assert.equal(a.digest, b.digest);
  assert.notEqual(a.inputDigest, b.inputDigest);
  assert.notEqual(a.rootId, b.rootId);
  await host.resume(a.rootId); await host.resume(b.rootId);
  await host.resume(a.rootId); await host.resume(b.rootId);
  const ended = await host.advance();
  assert.deepEqual(ended.map(item => item.outcome.value).sort((x, y) => x - y), [11, 22]);
  assert.equal((await library.get(revision.digest)).state.phase, 'retired');
}));

test('children resolve only locked aliases and their inputs and grants are checked before a VM is created', async () => fixture(async ({ host, invocations, validated }) => {
  const child = await validated(definition('child'));
  const parent = await validated(definition('parent', { dependencies: { child: child.digest } }));
  const installed = await host.install(parent.digest, { input: 1 });
  await assert.rejects(host.spawn(installed.rootId, 'floating-latest', 2), /dependency_not_declared/);
  await assert.rejects(host.spawn(installed.rootId, 'child', 'wrong'), /contract_mismatch/);
  await assert.rejects(host.spawn(installed.rootId, 'child', 2, { grants: ['observe', 'work:crop'] }), /capability_escalation/);
  const handle = await host.spawn(installed.rootId, 'child', 3);
  assert.equal(invocations.execution(handle.id).definition, child.digest);
  await host.resume(handle.id); await host.resume(handle.id);
  assert.deepEqual(host.join(installed.rootId, handle), { status: 'success', value: 3 });
  assert.equal(invocations.capacity().retainedChildren, 0);
}));

test('replacement waits for owned child cleanup, then starts a fresh invocation and observation', async () => fixture(async ({ host, invocations, snapshots, library, validated }) => {
  const child = await validated(definition('child'));
  const old = await validated(definition('old', { dependencies: { child: child.digest } }));
  const next = await validated(definition('new'));
  const installed = await host.install(old.digest, { input: 4 });
  const handle = await host.spawn(installed.rootId, 'child', 4);
  invocations.trackActivity('in-flight', [handle.id]);
  await host.replace(installed.installationId, next.digest, { input: 5 });
  assert.equal(invocations.activity().stopRequested, true);
  assert.deepEqual(await host.advance(), []);
  assert.equal(snapshots.length, 1);
  assert.equal((await library.get(old.digest)).state.phase, 'installed');
  await assert.rejects(host.resume(installed.rootId), /installation_stopping/);
  invocations.settleActivity('in-flight', { released: false, accountingComplete: true });
  assert.deepEqual(await host.advance(), []);
  invocations.settleActivity('in-flight', { released: true, accountingComplete: true });
  const [transition] = await host.advance();
  assert.equal(transition.status, 'replaced');
  assert.notEqual(transition.replacement.rootId, installed.rootId);
  assert.equal(transition.replacement.digest, next.digest);
  assert.deepEqual(snapshots, [4, 5]);
  assert.equal((await library.get(old.digest)).state.phase, 'retired');
  assert.throws(() => invocations.inspect(handle.id), /invocation_unknown/);
}));

test('withdrawing one shared subscriber permits its replacement without cancelling the remaining owner', async () => fixture(async ({ host, invocations, validated }) => {
  const revision = await validated(definition('shared'));
  const a = await host.install(revision.digest, { input: 1 }), b = await host.install(revision.digest, { input: 2 });
  invocations.trackActivity('shared-effect', [a.rootId, b.rootId]);
  await host.replace(a.installationId, revision.digest, { input: 3 });
  const [transition] = await host.advance();
  assert.equal(transition.status, 'replaced');
  assert.deepEqual(invocations.activity().subscribers, [b.rootId]);
  assert.equal(invocations.activity().stopRequested, false);
  assert.equal((await host.resume(b.rootId)).result.done, false);
  invocations.settleActivity('shared-effect', { released: true, accountingComplete: true });
}));

test('replacement cannot silently expand the old grants, and rollback creates a new invocation', async () => fixture(async ({ host, validated }) => {
  const old = await validated(definition('limited'));
  const expanded = await validated(definition('expanded', { capabilities: ['observe', 'work:crop'] }));
  const installed = await host.install(old.digest, { input: 1 });
  await assert.rejects(host.replace(installed.installationId, expanded.digest), /capability_missing/);
  assert.equal(host.inspect(installed.installationId).phase, 'installed');
  await host.replace(installed.installationId, old.digest, { input: 9 });
  const [transition] = await host.advance();
  assert.equal(transition.replacement.digest, old.digest);
  assert.notEqual(transition.replacement.installationId, installed.installationId);
}));

test('a failed initial refresh retires the attempted installation and releases its process and root slot', async () => fixture(async ({ host, library, invocations, validated }) => {
  const revision = await validated(definition('refresh-fails'));
  await assert.rejects(host.install(revision.digest, { input: 1 }), /world_unavailable/);
  assert.deepEqual(invocations.capacity(), { roots: 0, live: 0, retainedChildren: 0 });
  assert.equal((await library.get(revision.digest)).state.phase, 'retired');
}, { refresh: async () => { throw Error('world_unavailable'); } }));

test('a returned parent does not prevent its owned child from finishing', async () => fixture(async ({ host, invocations, validated }) => {
  const child = await validated(definition('child-after-return'));
  const parent = await validated(definition('parent-before-child', { dependencies: { child: child.digest } }));
  const installed = await host.install(parent.digest, { input: 1 });
  const handle = await host.spawn(installed.rootId, 'child', 2);
  await host.resume(installed.rootId); await host.resume(installed.rootId);
  assert.equal(invocations.inspect(installed.rootId).phase, 'closing');
  await host.resume(handle.id); await host.resume(handle.id);
  const [transition] = await host.advance();
  assert.equal(transition.outcome.status, 'success');
  assert.equal(transition.outcome.value, 1);
}));

test('explicit stop discards a pending replacement instead of restarting after cleanup', async () => fixture(async ({ host, snapshots, validated }) => {
  const revision = await validated(definition('stop-replacement'));
  const installed = await host.install(revision.digest, { input: 1 });
  await host.replace(installed.installationId, revision.digest, { input: 2 });
  host.stop(installed.installationId, 'operator_stop');
  const [transition] = await host.advance();
  assert.equal(transition.status, 'retired');
  assert.deepEqual(snapshots, [1]);
}));

test('closing during an asynchronous refresh cannot publish an installed root afterward', async () => {
  let observed, release;
  const seen = new Promise(resolve => { observed = resolve; }), delayed = new Promise(resolve => { release = resolve; });
  await fixture(async ({ host, invocations, validated }) => {
    const revision = await validated(definition('late-refresh'));
    const installation = host.install(revision.digest, { input: 1 });
    const rejected = assert.rejects(installation, /installation_stopping/);
    await seen;
    const closed = host.close();
    release({ captureId: 'too-late' });
    await rejected; await closed;
    assert.deepEqual(invocations.capacity(), { roots: 0, live: 0, retainedChildren: 0 });
  }, { refresh: async () => { observed(); return delayed; } });
});

test('close retires a stopped root even while replacement dependency resolution is pending', async () => fixture(async ({ host, library, invocations, validated }) => {
  const revision = await validated(definition('slow-replacement'));
  const installed = await host.install(revision.digest, { input: 1 });
  let entered, release;
  const started = new Promise(resolve => { entered = resolve; }), wait = new Promise(resolve => { release = resolve; });
  const original = library.resolve.bind(library);
  library.resolve = async (...args) => { entered(); await wait; return original(...args); };
  const replacing = assert.rejects(host.replace(installed.installationId, revision.digest), /installation_stopping/);
  try {
    await started;
    await host.close();
    assert.equal(invocations.capacity().roots, 0);
    assert.equal((await library.get(revision.digest)).state.phase, 'retired');
  } finally { release(); await replacing; }
}));

test('large valid physical evidence fits the retirement record envelope after release', async () => fixture(async ({ host, invocations, validated }) => {
  const revision = await validated(definition('large-release'));
  const installed = await host.install(revision.digest, { input: 2 });
  invocations.trackActivity('large-proof', [installed.rootId]);
  await host.resume(installed.rootId); await host.resume(installed.rootId);
  const evidence = { released: true, accountingComplete: true, padding: '' };
  const overhead = Buffer.byteLength(JSON.stringify({ status: 'success', value: 2, lastActivity: { id: 'large-proof', evidence } }));
  evidence.padding = 'x'.repeat(16_380 - overhead);
  invocations.settleActivity('large-proof', evidence);
  assert.equal(Buffer.byteLength(JSON.stringify(invocations.inspect(installed.rootId).outcome)), 16_380);
  const [transition] = await host.advance();
  assert.equal(transition.outcome.status, 'success');
  assert.equal(invocations.capacity().roots, 0);
}));

test('an installed offers definition enforces its output schema at every evaluation', async () => fixture(async ({ host, invocations, validated }) => {
  const revision = await validated(definition('typed-offers', { mode: 'offers', outputContract: { type: 'array', maxItems: 0, items: true },
    source: 'function offers(os, input) { return input === 1 ? [] : [os.work("crop.plant", {})]; }',
    examples: [{ input: 1, responses: [{}], expected: [[]] }] }));
  const installed = await host.install(revision.digest, { input: 2 });
  await assert.rejects(host.offers(installed.rootId, {}), /invalid_result/);
  assert.equal(invocations.inspect(installed.rootId).outcome.status, 'failure');
  assert.equal((await host.advance())[0].outcome.status, 'failure');
}));

test('retirement is retryable if its durable record succeeds but the reply is lost', async () => fixture(async ({ host, library, invocations, validated }) => {
  const revision = await validated(definition('retirement-reply'));
  const installed = await host.install(revision.digest, { input: 1 });
  await host.resume(installed.rootId); await host.resume(installed.rootId);
  const original = library.recordRetired.bind(library);
  let loseReply = true;
  library.recordRetired = async (...args) => {
    const result = await original(...args);
    if (loseReply) { loseReply = false; throw Error('retirement_reply_lost'); }
    return result;
  };
  await assert.rejects(host.advance(), /retirement_reply_lost/);
  assert.equal(invocations.capacity().roots, 1);
  assert.equal((await host.advance())[0].status, 'retired');
  assert.equal(invocations.capacity().roots, 0);
}));

test('nested valid results retain their depth allowance inside lifecycle metadata', async () => fixture(async ({ host, validated }) => {
  const revision = await validated(definition('nested-result', { outputContract: true,
    source: 'function* main(os, input) { let result = 0; for (let n = 0; n < input; n++) result = [result]; return result; }',
    examples: [{ input: 0, responses: [], expected: [{ done: true, value: 0 }] }] }));
  const installed = await host.install(revision.digest, { input: 14 });
  await host.resume(installed.rootId);
  const [transition] = await host.advance();
  assert.equal(transition.outcome.status, 'success');
  let value = transition.outcome.value;
  for (let n = 0; n < 14; n++) value = value[0];
  assert.equal(value, 0);
}));

test('a full valid result node count can still retire inside the record envelope', async () => fixture(async ({ host, invocations, validated }) => {
  const revision = await validated(definition('many-result-nodes', { outputContract: true }));
  const installed = await host.install(revision.digest, { input: 2 });
  // Host body completion is the broker seam; no enlarged guest-wire limit is used.
  invocations.returned(installed.rootId, Array.from({ length: 2043 }, () => 0));
  assert.equal(invocations.inspect(installed.rootId).outcome.status, 'success');
  assert.equal((await host.advance())[0].outcome.value.length, 2043);
}));

test('an ambiguously acknowledged installation record is reconciled before its root is forgotten', async () => fixture(async ({ host, library, invocations, validated }) => {
  const revision = await validated(definition('installation-reply'));
  const original = library.recordInstall.bind(library);
  library.recordInstall = async (...args) => { await original(...args); throw Error('installation_reply_lost'); };
  await assert.rejects(host.install(revision.digest, { input: 1 }), /installation_reply_lost/);
  assert.equal(invocations.capacity().roots, 0);
  assert.equal((await library.get(revision.digest)).state.installations.length, 0);
  assert.equal((await library.get(revision.digest)).state.phase, 'retired');
}));
