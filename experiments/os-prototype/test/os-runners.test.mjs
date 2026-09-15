import test from 'node:test';
import assert from 'node:assert/strict';
import { InvocationBroker } from '../src/os/broker.mjs';
import { RootBudget } from '../src/os/runner-budget.mjs';
import { RunnerPool } from '../src/os/runners.mjs';

test('root buckets bound many cheap child yields and charge overruns without minting compute', () => {
  let time = 0;
  const budget = new RootBudget(() => time), other = new RootBudget(() => time);
  for (let childResume = 0; childResume < 64; childResume++) {
    const permit = budget.reserve(25_000);
    assert.equal(permit.admitted, true);
    budget.charge(permit.reservedMicros, 1000);
  }
  assert.equal(budget.reserve(25_000).admitted, false);
  assert.equal(other.reserve(250_000, false).admitted, true);
  time += 16;
  assert.equal(budget.reserve(25_000).admitted, true);
  budget.charge(25_000, 300_000);
  assert.ok(budget.state().cpuMicros < 0);
  time += 5000;
  assert.equal(budget.state().cpuMicros, 250_000);
  assert.equal(budget.state().messages, 64);
});

test('the broker binds children to one root runner and parent completion still joins their outcomes', async () => {
  const invocations = new InvocationBroker(), pool = new RunnerPool({ invocations });
  const root = invocations.install({ definition: 'parent', grants: [] });
  const child = invocations.spawn(root, { definition: 'child', grants: [] });
  const laterChild = invocations.spawn(root, { definition: 'later-child', grants: [] });
  try {
    await pool.open(root);
    await pool.create(root, { definition: 'parent', source: 'function* main() { return "parent body ended"; }' });
    await pool.create(laterChild.id, { definition: 'later-child', source: 'function* main() { return 10; }' });
    await pool.create(child.id, { definition: 'child', source: 'function* main() { return 9; }' });
    assert.equal(invocations.execution(child.id).rootId, root);
    assert.equal(pool.state(root).guests, 3);
    await pool.resume(root);
    assert.equal(invocations.inspect(root).outcome, null);
    const childResult = await pool.resume(child.id);
    assert.equal(childResult.result.value, 9);
    await assert.rejects(pool.create(child.id, { definition: 'child', source: 'function* main() { return 11; }' }), /invocation_closing/);
    assert.equal(invocations.inspect(root).outcome, null);
    await pool.resume(laterChild.id);
    assert.equal(invocations.inspect(root).outcome.status, 'success');
    await pool.retire(root);
  } finally { await pool.close(); }
});

test('runner death fails its subtree while retaining unresolved player ownership', async () => {
  const invocations = new InvocationBroker();
  const root = invocations.install({ definition: 'physical', grants: [] });
  const other = invocations.install({ definition: 'independent', grants: [] });
  let changed;
  const noticed = new Promise(resolve => { changed = resolve; });
  const pool = new RunnerPool({ invocations, onOwnershipChanged: id => {
    if (id === root && invocations.activity()?.stopRequested) changed();
  } });
  try {
    await pool.open(root); await pool.open(other);
    await pool.create(root, { definition: 'physical', source: 'function* main() { yield { kind: "work", operation: "plant", arguments: {}, context: null }; }' });
    await pool.create(other, { definition: 'independent', source: 'function* main() { return 42; }' });
    await pool.resume(root);
    invocations.trackActivity('physical-effect', [root]);
    process.kill(pool.state(root).pid, 'SIGKILL');
    await noticed;
    assert.equal(invocations.inspect(root).outcome, null);
    assert.deepEqual(invocations.activity().cleanupOwners, [root]);
    await assert.rejects(pool.retire(root), /invocation_unsettled/);
    assert.equal((await pool.resume(other)).result.value, 42);
    invocations.settleActivity('physical-effect', { released: true, accountingComplete: true });
    assert.equal(invocations.inspect(root).outcome.status, 'failure');
    await pool.retire(root);
  } finally { await pool.close(); }
});

test('cancelling a returned parent can still stop its waiting child without claiming physical release', async () => {
  const invocations = new InvocationBroker(), pool = new RunnerPool({ invocations });
  const root = invocations.install({ definition: 'parent', grants: [] });
  const child = invocations.spawn(root, { definition: 'child', grants: [] });
  try {
    await pool.open(root);
    await pool.create(root, { definition: 'parent', source: 'function* main() { return 1; }' });
    await pool.create(child.id, { definition: 'child', source: 'function* main() { yield { kind: "observe", query: {} }; }' });
    invocations.trackActivity('owned', [child.id]);
    await pool.resume(root);
    pool.cancel(root);
    assert.equal(invocations.inspect(root).outcome, null);
    assert.equal(invocations.activity().stopRequested, true);
    invocations.settleActivity('owned', { released: true, accountingComplete: true });
    assert.equal(invocations.inspect(root).outcome.status, 'cancelled');
  } finally { await pool.close(); }
});

test('RSS supervision stops an attributed offender and stops the group when aggregate pressure has no unique owner', async () => {
  const invocations = new InvocationBroker();
  const roots = Array.from({ length: 4 }, (_, index) => invocations.install({ definition: `root-${index}`, grants: [] }));
  let readings = null;
  const pool = new RunnerPool({ invocations, readRss: async pids => new Map(pids.map(pid => [pid, readings?.get(pid) ?? 64 * 1024 * 1024])) });
  try {
    for (const root of roots) await pool.open(root);
    readings = new Map([[pool.state(roots[0]).pid, 257 * 1024 * 1024]]);
    await pool.sample();
    assert.equal(pool.state(roots[0]).failed, 'runner_rss_limit');
    assert.equal(pool.state(roots[1]).failed, null);
    readings = new Map(roots.slice(1).map(root => [pool.state(root).pid, 256 * 1024 * 1024]));
    // Three at the exact individual threshold fit the group threshold.
    await pool.sample();
    assert.equal(pool.state(roots[1]).failed, null);
  } finally { await pool.close(); }
  const groupOwners = new InvocationBroker();
  const group = Array.from({ length: 4 }, (_, index) => groupOwners.install({ definition: `group-${index}`, grants: [] }));
  let pressure = false;
  const groupPool = new RunnerPool({ invocations: groupOwners, readRss: async pids => new Map(pids.map(pid => [pid, (pressure ? 193 : 64) * 1024 * 1024])) });
  try {
    for (const root of group) await groupPool.open(root);
    pressure = true; await groupPool.sample();
    for (const root of group) assert.equal(groupPool.state(root).failed, 'runner_group_rss_limit');
  } finally { await groupPool.close(); }
});
