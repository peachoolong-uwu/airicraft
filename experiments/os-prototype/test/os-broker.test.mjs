import test from 'node:test';
import assert from 'node:assert/strict';
import { InvocationBroker } from '../src/os/broker.mjs';

test('returning a parent waits for its owned child before publishing an outcome', () => {
  const broker = new InvocationBroker();
  const parent = broker.install({ definition: 'farm', grants: ['crop:home'] });
  const child = broker.spawn(parent, { definition: 'wait-and-harvest', grants: ['crop:home'] });
  broker.returned(parent, 'farm complete');
  assert.equal(broker.inspect(parent).phase, 'closing');
  assert.equal(broker.inspect(parent).outcome, null);
  broker.returned(child.id, { harvested: 6 });
  assert.deepEqual(broker.inspect(parent).outcome, { status: 'success', value: 'farm complete' });
  assert.equal(broker.capacity().live, 0);
  assert.equal(broker.capacity().retainedChildren, 0);
});

test('a failed child cancels siblings and waits for physical release without stopping another root', () => {
  const broker = new InvocationBroker();
  const parent = broker.install({ definition: 'parallel chores', grants: [] });
  const broken = broker.spawn(parent, { definition: 'birch', grants: [] });
  const fishing = broker.spawn(parent, { definition: 'fishing', grants: [] });
  const independent = broker.install({ definition: 'farm', grants: [] });
  broker.trackActivity('cast-1', [fishing.id]);
  broker.failed(broken.id, 'invalid_birch_target');
  assert.equal(broker.inspect(parent).phase, 'stopping');
  assert.equal(broker.inspect(parent).outcome, null);
  assert.equal(broker.activity().stopRequested, true);
  assert.equal(broker.inspect(independent).phase, 'running');
  broker.returned(fishing.id, 'late catch');
  broker.settleActivity('cast-1', { released: false, accountingComplete: true });
  assert.equal(broker.inspect(parent).outcome, null);
  broker.settleActivity('cast-1', { released: true, accountingComplete: true });
  const outcome = broker.inspect(parent).outcome;
  assert.equal(outcome.status, 'failure');
  assert.equal(outcome.cause.childId, broken.id);
  assert.equal(outcome.cause.reason, 'invalid_birch_target');
  assert.equal(broker.activity(), null);
});

test('collect-all keeps siblings running and join consumes distinct child results exactly once', () => {
  const broker = new InvocationBroker();
  const parent = broker.install({ definition: 'collect chores', grants: [], failurePolicy: 'collect_all' });
  const first = broker.spawn(parent, { definition: 'supply', grants: [] });
  const second = broker.spawn(parent, { definition: 'supply', grants: [] });
  broker.failed(first.id, 'source_empty');
  assert.equal(broker.inspect(second.id).phase, 'running');
  assert.deepEqual(broker.join(parent, second), { status: 'pending' });
  const failure = broker.join(parent, first);
  assert.equal(failure.status, 'failure');
  assert.throws(() => broker.join(parent, first), /already_joined/);
  broker.returned(second.id, { delivered: 4 });
  assert.deepEqual(broker.join(parent, second), { status: 'success', value: { delivered: 4 } });
  broker.returned(parent, [failure.status, 'success']);
  assert.deepEqual(broker.inspect(parent).outcome.value, ['failure', 'success']);
  assert.equal(broker.capacity().retainedChildren, 0);
});

test('cancelling a shared subscriber preserves other service, and the last waits for accounted cleanup', () => {
  const broker = new InvocationBroker();
  const a = broker.install({ definition: 'wheat supply A', grants: [] });
  const b = broker.install({ definition: 'wheat supply B', grants: [] });
  broker.trackActivity('withdraw-1', [a, b]);
  broker.cancel(a);
  assert.equal(broker.inspect(a).outcome.status, 'cancelled');
  assert.equal(broker.activity().stopRequested, false);
  assert.deepEqual(broker.activity().subscribers, [b]);
  broker.cancel(b);
  broker.settleActivity('withdraw-1', { released: true, accountingComplete: false, transferred: 4 });
  assert.equal(broker.inspect(b).outcome, null);
  assert.throws(() => broker.trackActivity('replacement', [b]), /player_owned/);
  broker.settleActivity('withdraw-1', { released: true, accountingComplete: true, transferred: 4 });
  assert.equal(broker.inspect(b).outcome.status, 'cancelled');
  assert.equal(broker.inspect(b).outcome.lastActivity.evidence.transferred, 4);
});

test('sequential calls reclaim live capacity while unjoined child outcomes remain bounded', () => {
  const broker = new InvocationBroker();
  const parent = broker.install({ definition: 'recurring farm', grants: [] });
  for (let n = 0; n < 80; n++) {
    const child = broker.spawn(parent, { definition: 'finite pass', grants: [] });
    broker.returned(child.id, n);
    assert.equal(broker.join(parent, child).value, n);
  }
  const held = [];
  for (let n = 0; n < 64; n++) {
    const child = broker.spawn(parent, { definition: 'finite pass', grants: [] });
    broker.returned(child.id, n);
    held.push(child);
  }
  assert.equal(broker.capacity().live, 1);
  assert.equal(broker.capacity().retainedChildren, 64);
  assert.throws(() => broker.spawn(parent, { definition: 'overflow', grants: [] }), /child_result_capacity/);
  broker.join(parent, held[0]);
  const next = broker.spawn(parent, { definition: 'next pass', grants: [] });
  assert.equal(broker.capacity().live, 2);
  broker.returned(next.id, 'done');
  broker.returned(parent, 'done');
  assert.equal(broker.capacity().retainedChildren, 0);
});

test('children cannot expand grants, exceed depth eight, or exceed 32 live invocations', () => {
  const broker = new InvocationBroker();
  const root = broker.install({ definition: 'farm', grants: ['crop:home'] });
  assert.throws(() => broker.spawn(root, { definition: 'intruder', grants: ['sheep:other'] }), /capability_escalation/);
  let parent = root;
  for (let depth = 2; depth <= 8; depth++) parent = broker.spawn(parent, { definition: 'child', grants: [] }).id;
  assert.throws(() => broker.spawn(parent, { definition: 'too deep', grants: [] }), /invocation_depth/);
  for (let n = 8; n < 32; n++) broker.spawn(root, { definition: 'waiting child', grants: [] });
  assert.throws(() => broker.spawn(root, { definition: 'overflow', grants: [] }), /live_invocation_capacity/);
  broker.cancel(root);
  assert.equal(broker.capacity().live, 0);
});

test('installed roots and global result retention have separate bounded lifetimes', () => {
  const broker = new InvocationBroker();
  const roots = Array.from({ length: 12 }, () => broker.install({ definition: 'duty', grants: [] }));
  assert.throws(() => broker.install({ definition: 'overflow', grants: [] }), /root_capacity/);
  for (const root of roots.slice(0, 4)) for (let n = 0; n < 64; n++) {
    const child = broker.spawn(root, { definition: 'one pass', grants: [] });
    broker.returned(child.id, 'done');
  }
  assert.equal(broker.capacity().retainedChildren, 256);
  assert.throws(() => broker.spawn(roots[4], { definition: 'overflow', grants: [] }), /child_result_capacity/);
  assert.throws(() => broker.uninstall(roots[0]), /invocation_unsettled/);
  broker.cancel(roots[0]);
  broker.uninstall(roots[0]);
  assert.equal(broker.capacity().retainedChildren, 192);
  const replacement = broker.install({ definition: 'replacement', grants: [] });
  assert.equal(broker.inspect(replacement).phase, 'running');
});

test('malformed or oversized results fail without bypassing cleanup or publishing oversized outcomes', () => {
  const broker = new InvocationBroker();
  assert.throws(() => broker.install({ definition: 'bad policy', grants: [], failurePolicy: 'ignore' }), /invalid_invocation/);
  const root = broker.install({ definition: 'faulty producer', grants: [] });
  broker.trackActivity('pending-transfer', [root]);
  broker.returned(root, 'x'.repeat(20_000));
  assert.equal(broker.inspect(root).phase, 'stopping');
  assert.equal(broker.activity().stopRequested, true);
  broker.settleActivity('pending-transfer', { released: true, accountingComplete: true, transferred: 3 });
  assert.equal(broker.inspect(root).outcome.status, 'failure');
  assert.equal(broker.inspect(root).outcome.cause.reason, 'invalid_result');
  assert.ok(Buffer.byteLength(JSON.stringify(broker.inspect(root).outcome)) <= 16_384);
  const other = broker.install({ definition: 'non JSON', grants: [] });
  broker.returned(other, { count: NaN });
  assert.equal(broker.inspect(other).outcome.status, 'failure');
});

test('cancellation wins over a late return and malformed failure data stays bounded', () => {
  const broker = new InvocationBroker();
  const root = broker.install({ definition: 'late producer', grants: [] });
  broker.trackActivity('pending', [root]);
  broker.cancel(root, 'operator_stop');
  broker.returned(root, 'late success');
  broker.failed(root, 'x'.repeat(40_000));
  broker.settleActivity('pending', { released: true, accountingComplete: true });
  assert.equal(broker.inspect(root).outcome.status, 'cancelled');
  assert.equal(broker.inspect(root).outcome.cause.reason, 'operator_stop');
  const malformed = broker.install({ definition: 'bad error', grants: [] });
  broker.failed(malformed, 'x'.repeat(40_000));
  assert.ok(Buffer.byteLength(JSON.stringify(broker.inspect(malformed).outcome)) <= 16_384);
});

test('truthy strings cannot acknowledge physical release', () => {
  const broker = new InvocationBroker();
  const root = broker.install({ definition: 'root', grants: [] });
  broker.trackActivity('pending', [root]);
  broker.cancel(root);
  broker.settleActivity('pending', { released: 'true', accountingComplete: 'true' });
  assert.equal(broker.inspect(root).outcome, null);
  assert.notEqual(broker.activity(), null);
});
