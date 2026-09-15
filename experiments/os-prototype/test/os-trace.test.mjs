import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, readdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DecisionTrace } from '../src/os/trace.mjs';
import { InvocationBroker } from '../src/os/broker.mjs';

test('a trace persists copied ordered events and closes without overwriting a previous run', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'airicraft-trace-'));
  let trace;
  try {
    const path = join(directory, 'run');
    trace = await DecisionTrace.open(path, { runId: 'run-A' });
    const event = { owner: 'root-A', quantity: 2 };
    trace.record('activity.admitted', event);
    event.quantity = 999;
    trace.record('activity.released', { owner: 'root-A', released: true }, { cleanup: true });
    await trace.close();
    const records = (await readFile(join(path, 'trace-0.jsonl'), 'utf8')).trim().split('\n').map(JSON.parse);
    assert.equal(records[0].data.quantity, 2);
    assert.deepEqual(records.map(event => event.seqNo), [1, 2, 3]);
    assert.ok(records.every(event => event.runId === 'run-A' && Number.isFinite(event.hostMonoMillis)));
    assert.equal(records.at(-1).type, 'trace.closed');
    assert.equal(records.at(-1).data.complete, true);
    assert.equal(trace.status().writtenThrough, 3);
    assert.equal(trace.status().queuedBytes, 0);
    await assert.rejects(DecisionTrace.open(path, { runId: 'run-B' }), /EEXIST/);
    assert.deepEqual(await readdir(path), ['trace-0.jsonl']);
  } finally { await trace?.close(); await rm(directory, { recursive: true, force: true }); }
});

test('mandatory joins preserve each child outcome and the shared cleanup owner in the trace', async () => {
  const records = [];
  const trace = new DecisionTrace({ runId: 'ownership', writer: { write: async (_, bytes) => records.push(JSON.parse(bytes)), close: async () => {} } });
  const broker = new InvocationBroker({ trace });
  const spec = { definition: 'fixture@digest', grants: ['container:home'] };
  const root = broker.install(spec), a = broker.spawn(root, spec), b = broker.spawn(root, spec);
  broker.trackActivity('shared', [a.id, b.id]);
  broker.failed(a.id, 'broken_child');
  broker.settleActivity('shared', { released: false, accountingComplete: true });
  assert.equal(broker.inspect(root).outcome, null);
  broker.settleActivity('shared', { released: true, accountingComplete: true });
  await trace.close();
  const outcomes = records.filter(event => event.type === 'invocation.terminal');
  assert.deepEqual(outcomes.map(event => [event.data.id, event.data.outcome.status]), [[a.id, 'failure'], [b.id, 'cancelled'], [root, 'failure']]);
  const joins = records.filter(event => event.type === 'invocation.joined');
  assert.deepEqual(joins.map(event => event.data.childId), [a.id, b.id]);
  assert.ok(joins.every(event => event.data.mandatory && event.data.parentId === root));
  const release = records.filter(event => event.type === 'activity.released');
  assert.equal(release.length, 1);
  assert.deepEqual(release[0].data.cleanupOwners, [b.id]);
  assert.equal(release[0].data.evidence.released, true);
});

test('disk quota stops ordinary records while retaining an explicit gap and reserved cleanup', async () => {
  const records = [], sizes = new Map();
  const trace = new DecisionTrace({ runId: 'quota', limits: { segmentBytes: 1024, budgetBytes: 4096, cleanupBytes: 1024, queueBytes: 4096 },
    writer: { write: async (segment, bytes) => { sizes.set(segment, (sizes.get(segment) ?? 0) + bytes.length); records.push(JSON.parse(bytes)); }, close: async () => {} } });
  let failure;
  for (let i = 0; i < 20; i++) {
    try { trace.record('decision', { text: '🌾'.repeat(100) }); await trace.flush(); }
    catch (error) { failure = error; break; }
  }
  assert.match(failure.message, /trace_budget_exhausted/);
  assert.throws(() => trace.assertHealthy(), /trace_budget_exhausted/);
  trace.record('activity.released', { released: true, accountingComplete: true }, { cleanup: true });
  await trace.close();
  assert.equal(trace.status().incomplete, true);
  assert.ok(records.some(event => event.type === 'trace.gap'));
  assert.ok(records.some(event => event.type === 'activity.released'));
  assert.equal(records.at(-1).data.complete, false);
  assert.ok(sizes.size <= 4 && [...sizes.values()].every(bytes => bytes <= 1024));
  assert.ok(trace.status().reservedBytes <= 4096);
});

test('a stalled writer has bounded queued bytes and keeps room for cancellation evidence', async () => {
  let resume;
  const allowed = new Promise(resolve => { resume = resolve; });
  const records = [];
  const trace = new DecisionTrace({ runId: 'pressure', limits: { queueBytes: 1024 },
    writer: { write: async (_, bytes) => { await allowed; records.push(JSON.parse(bytes)); }, close: async () => {} } });
  try {
    trace.record('decision', { text: 'x'.repeat(300) });
    assert.throws(() => trace.record('decision', { text: 'x'.repeat(300) }), /trace_queue_capacity/);
    trace.record('invocation.stop_requested', { id: 'root' }, { cleanup: true });
    assert.ok(trace.status().queuedBytes <= 1024);
    resume(); await trace.flush();
    assert.ok(records.some(event => event.type === 'trace.gap'));
    assert.ok(records.some(event => event.type === 'invocation.stop_requested'));
    assert.equal(trace.status().queuedBytes, 0);
  } finally { resume(); await trace.close(); }
});
