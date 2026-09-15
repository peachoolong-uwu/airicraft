import test from 'node:test';
import assert from 'node:assert/strict';
import { SchedulingPolicy } from '../src/os/scheduling.mjs';

const offer = (id, root, options = {}) => ({ id, roots: [root], kind: 'land', readiness: 'ready', priority: 10, context: null, ...options });
const available = { authority: 'available' };
const advance = (policy, fromTick, toTick, eligibleRoots, options = {}) => policy.advance({
  epoch: 'world', fromTick, toTick, eligibleRoots, covered: true, ordinaryAllowed: true, ...options
});

test('ready land work excludes fishing and unknown or blocked work cannot win', () => {
  const policy = new SchedulingPolicy({ epoch: 'world' });
  policy.update({ roots: ['crops', 'fish', 'sheep'], offers: [
    offer('fish', 'fish', { kind: 'fishing', priority: 100 }),
    offer('shear', 'sheep', { readiness: 'unknown', priority: 100 }),
    offer('harvest', 'crops'),
    offer('feed', 'sheep', { readiness: 'blocked', priority: 100 })
  ] });
  assert.equal(policy.decide(available).offerId, 'harvest');
  policy.update({ roots: ['crops', 'fish', 'sheep'], offers: [offer('fish', 'fish', { kind: 'fishing' })] });
  assert.equal(policy.decide(available).offerId, 'fish');
  policy.update({ roots: ['crops', 'fish', 'sheep'], offers: [] });
  assert.deepEqual(policy.decide(available), { kind: 'wait', reason: 'no_feasible_work' });
});

test('eligible age and overdue FIFO belong to roots, so duplicate offers cannot multiply service', () => {
  const policy = new SchedulingPolicy({ epoch: 'world' });
  const offers = [offer('older', 'old', { priority: 1 }), offer('younger', 'young', { priority: 100 }),
    ...Array.from({ length: 32 }, (_, index) => offer(`duplicate-${index}`, 'busy', { priority: 1000 }))];
  policy.update({ roots: ['old', 'young', 'busy'], offers });
  advance(policy, 0, 100, ['old']);
  advance(policy, 100, 2400, ['old', 'young', 'busy']);
  assert.equal(policy.decide(available).offerId, 'older');
  assert.equal(policy.decide(available).reason, 'overdue');
  advance(policy, 2400, 2500, ['old', 'young', 'busy']);
  policy.served('older');
  assert.equal(policy.decide(available).offerId, 'younger');
  policy.served('younger');
  assert.equal(policy.decide(available).offerId, 'duplicate-0');
  policy.served('duplicate-0');
  assert.deepEqual(policy.state().roots.map(root => root.ageTicks), [0, 0, 0]);
  assert.equal(policy.decide(available).reason, 'score');
  policy.update({ roots: ['old', 'young', 'busy'], offers: [offer('older', 'old', { priority: 1 }), offer('younger', 'young', { priority: 2 })] });
  advance(policy, 2500, 3100, ['old']);
  assert.equal(policy.decide(available).offerId, 'older');
});

test('eligibility requires covered advancing intervals and rejects stale epochs or replayed ticks atomically', () => {
  const policy = new SchedulingPolicy({ epoch: 'world' });
  policy.update({ roots: ['crops', 'fish', 'blocked'], offers: [offer('crop', 'crops'), offer('fish', 'fish', { kind: 'fishing' }),
    offer('blocked', 'blocked', { readiness: 'unknown' })] });
  advance(policy, 0, 100, ['crops']);
  advance(policy, 100, 100, ['crops']);
  advance(policy, 100, 1000, ['crops'], { covered: false });
  advance(policy, 1000, 2000, ['crops'], { ordinaryAllowed: false });
  advance(policy, 3000, 3100, ['crops']); // The absent 2000..3000 interval grants no age.
  const before = policy.state();
  assert.equal(before.roots[0].ageTicks, 200);
  assert.throws(() => advance(policy, 3000, 3100, ['crops']), /invalid_scheduler_progress/);
  assert.throws(() => advance(policy, 3100, 3200, ['crops'], { epoch: 'other-world' }), /stale_scheduler_epoch/);
  assert.throws(() => advance(policy, 3100, 3200, ['crops', 'blocked']), /ineligible_scheduler_root/);
  assert.throws(() => advance(policy, 3100, 3200, ['fish']), /ineligible_scheduler_root/);
  assert.deepEqual(policy.state(), before);
  advance(policy, Number.MAX_SAFE_INTEGER - 1, Number.MAX_SAFE_INTEGER, ['crops']);
  assert.equal(policy.state().roots[0].ageTicks, 201);
});

const visit = (id, options = {}) => ({ id, phase: 'ready', operations: 0, elapsedTicks: 0, ...options });

test('scores expose bounded context and travel bonuses, and incompatible winners first close the visit', () => {
  const policy = new SchedulingPolicy({ epoch: 'world' });
  const sheep = offer('shear', 'sheep', { priority: 12, context: 'pen' });
  const crops = offer('harvest', 'crops', { priority: 11, context: 'plot', travel: { bonus: 3, source: 'configured', uncertain: true } });
  policy.update({ roots: ['sheep', 'crops'], offers: [sheep, crops] });
  assert.equal(policy.decide(available).offerId, 'harvest');
  const decision = policy.decide({ ...available, context: visit('pen') });
  assert.equal(decision.offerId, 'shear');
  assert.deepEqual(decision.score, { base: 12, age: 0, context: 3, travel: 0, total: 15, travelSource: 'unknown', travelUncertain: true });
  advance(policy, 0, 600, ['crops']);
  assert.deepEqual(policy.decide({ ...available, context: visit('pen') }), { kind: 'close_context', context: 'pen', reason: 'incompatible_work', nextOfferId: 'harvest' });
  assert.throws(() => policy.update({ roots: ['crops'], offers: [offer('bad-travel', 'crops', { travel: { bonus: 4, source: 'configured', uncertain: true } })] }), /invalid_schedule/);
  assert.equal(policy.decide(available).offerId, 'harvest');
});

test('expired visits close before overdue service and preserve an owed outside turn across same-context reentry', () => {
  for (const exhausted of [{ operations: 8 }, { elapsedTicks: 1200 }]) {
    const policy = new SchedulingPolicy({ epoch: 'world' });
    policy.update({ roots: ['shear', 'breed', 'crop'], offers: [
      offer('shear', 'shear', { context: 'pen', priority: 100 }),
      offer('breed', 'breed', { context: 'pen', priority: 1 }),
      offer('crop', 'crop', { context: 'plot', priority: 1 })
    ] });
    advance(policy, 0, 2400, ['breed']);
    assert.deepEqual(policy.decide({ ...available, context: visit('pen', exhausted) }), { kind: 'close_context', context: 'pen', reason: 'context_budget' });
    assert.equal(policy.decide({ ...available, context: visit('pen', { phase: 'exiting' }) }).kind, 'wait');
    assert.equal(policy.decide(available).offerId, 'breed');
    policy.served('breed');
    assert.deepEqual(policy.decide({ ...available, context: visit('pen') }), { kind: 'close_context', context: 'pen', reason: 'incompatible_work', nextOfferId: 'crop' });
    assert.equal(policy.decide(available).reason, 'outside_turn');
    policy.served('crop');
    assert.equal(policy.decide(available).offerId, 'shear');
    assert.equal(policy.state().outsideContext, null);
  }
});

test('a fishing yield preserves a bite opportunity for at most five advancing seconds and never declares release', () => {
  const policy = new SchedulingPolicy({ epoch: 'world' });
  policy.update({ roots: ['crops', 'fish'], offers: [offer('crop', 'crops')] });
  advance(policy, 0, 200, ['crops']);
  const activity = { id: 'cast-1', kind: 'fishing', phase: 'waiting', biteReady: false };
  const decide = changes => policy.decide({ ...available, activity: { ...activity, ...changes } });
  assert.deepEqual(decide(), { kind: 'yield_fishing', activityId: 'cast-1', deadlineTick: 300, action: 'wait_for_bite' });
  advance(policy, 200, 200, ['crops']);
  assert.equal(decide().action, 'wait_for_bite');
  advance(policy, 200, 299, ['crops']);
  assert.equal(decide().action, 'wait_for_bite');
  assert.equal(decide({ biteReady: true }).action, 'retrieve_bite');
  policy.update({ roots: ['crops', 'fish'], offers: [] });
  advance(policy, 299, 300, []);
  assert.equal(decide().action, 'reel_in'); // A sent yield stays pending even if the land opportunity disappears.
  assert.deepEqual(decide({ phase: 'releasing' }), { kind: 'wait', reason: 'activity_in_progress' });
  assert.equal(decide({ id: 'cast-2', phase: 'preparing' }).kind, 'wait');
  policy.update({ roots: ['crops', 'fish'], offers: [offer('crop', 'crops')] });
  assert.equal(decide({ id: 'cast-2', phase: 'preparing' }).action, 'yield_before_cast');
  assert.equal(policy.decide({ ...available, activity: { id: 'crop-action', kind: 'land', phase: 'acting' } }).kind, 'wait');
  assert.equal(policy.decide({ authority: 'reflex', activity }).kind, 'wait');
  assert.equal(policy.decide(available).offerId, 'crop');
});

test('all twelve overdue roots receive a turn before a repeat at the full offer bound', () => {
  const policy = new SchedulingPolicy({ epoch: 'world' });
  const roots = Array.from({ length: 12 }, (_, index) => `root-${index}`);
  const offers = Array.from({ length: 1024 }, (_, index) => offer(`work-${index}`, roots[index % 12], { priority: 1000 - index % 12 }));
  policy.update({ roots, offers });
  advance(policy, 0, 2400, roots);
  const served = [];
  for (let turn = 0; turn < 12; turn++) {
    const decision = policy.decide(available);
    assert.equal(decision.reason, 'overdue');
    served.push(...decision.roots); policy.served(decision.offerId);
  }
  assert.deepEqual(served, roots);
  assert.equal(policy.decide(available).reason, 'score');
  assert.throws(() => policy.update({ roots, offers: [...offers, offer('excess', roots[0])] }), /invalid_schedule/);
  assert.equal(policy.state().offers, 1024);
});

test('offer refresh preserves request order but cannot reuse an identity for another consumer or context', () => {
  const policy = new SchedulingPolicy({ epoch: 'world' });
  const first = offer('first', 'a'), second = offer('second', 'b');
  policy.update({ roots: ['a', 'b'], offers: [first, second] });
  policy.update({ roots: ['a', 'b'], offers: [second, first] });
  assert.equal(policy.decide(available).offerId, 'first');
  const before = policy.state();
  assert.throws(() => policy.update({ roots: ['a', 'b'], offers: [offer('first', 'b')] }), /offer_identity_conflict/);
  assert.throws(() => policy.update({ roots: ['a', 'b'], offers: [offer('first', 'a', { context: 'pen' })] }), /offer_identity_conflict/);
  assert.deepEqual(policy.state(), before);
});
