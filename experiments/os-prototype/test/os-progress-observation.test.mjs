import test from 'node:test';
import assert from 'node:assert/strict';
import { progressScopes, projectProgress } from '../src/os/progress-observation.mjs';

const declared = [{ scope: 'farm', chunks: [{ x: 1, z: 2 }] }];
const current = overrides => ({ facts: { progress: { available: true, source: 'native_completed_scope_ticks', clockSession: 'session', throughTick: 40,
  scopes: [{ scope: 'farm', kind: 'random_tick_chunks', clockId: 'clock', eligibleTicks: 20, lastTickEligible: true }], ...overrides } } });

test('progress registrations enforce copied identities, source counts and disjoint target types', () => {
  const copied = progressScopes(declared);
  copied[0].chunks[0].x = 3;
  assert.equal(declared[0].chunks[0].x, 1);
  const full = Array.from({ length: 32 }, (_, n) => ({ scope: `area-${n}`, chunks: Array.from({ length: 4 }, (_, x) => ({ x, z: 0 })) }));
  assert.equal(progressScopes(full).length, 32);
  full[0].chunks.push({ x: 4, z: 0 });
  assert.throws(() => progressScopes(full), /invalid_progress_scopes/);
  for (const invalid of [[...declared, ...declared], [{ ...declared[0], entities: ['00000000-0000-0000-0000-000000000001'] }],
    [{ scope: 'farm', chunks: [{ x: 1.5, z: 0 }] }], [{ scope: 'farm', entities: ['bad'] }], [{ scope: 'farm', chunks: [] }]])
    assert.throws(() => progressScopes(invalid), /invalid_progress_scopes/);
});

test('projection uses explicit native counters and preserves unavailability rather than sampled game time', () => {
  const [frame] = projectProgress(current(), declared, { captureId: 'capture' });
  assert.equal(frame.progress.eligibleTicks, 20);
  assert.equal(frame.coverage.available, true);
  assert.equal(frame.facts[0].value, true);
  const [unavailable] = projectProgress(current({ available: false, scopes: [] }), declared, {});
  assert.equal(unavailable.progress.eligibleTicks, null);
  assert.equal(unavailable.coverage.available, false);
  const unknown = current(); unknown.facts.progress.scopes[0].eligibleTicks = null; unknown.facts.progress.scopes[0].lastTickEligible = null;
  assert.equal(projectProgress(unknown, declared, {})[0].progress.eligibleTicks, null);
  assert.notEqual(projectProgress(current({ clockSession: 'new-session' }), declared, {})[0].progress.clockId, frame.progress.clockId);
});

test('missing, duplicate or contradictory native clock evidence rejects the entire projection', () => {
  const row = current().facts.progress.scopes[0];
  for (const data of [current({ scopes: [] }), current({ scopes: [row, row] }), current({ source: 'sampled_ticks' }),
    current({ throughTick: 10 }), current({ scopes: [{ ...row, kind: 'passive_entities' }] }),
    current({ scopes: [{ ...row, eligibleTicks: null }] }), current({ scopes: [{ ...row, lastTickEligible: null }] })])
    assert.throws(() => projectProgress(data, declared, {}), /invalid_native_progress/);
});
