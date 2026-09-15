import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { InvocationBroker } from '../src/os/broker.mjs';
import { ResourceLedger } from '../src/os/ledger.mjs';
import { EffectJournal } from '../src/os/journal.mjs';
import { EffectBroker } from '../src/os/effects.mjs';
import { ActivityCoordinator } from '../src/os/activities.mjs';
import { ContainerTransfer } from '../src/os/container-transfer.mjs';
import { WorkService } from '../src/os/work.mjs';
import { DecisionTrace } from '../src/os/trace.mjs';
import { guestResult } from '../src/os/guest-effects.mjs';
import { frame } from '../src/os/runner-wire.mjs';
import { NativeContainer } from './fixtures/native-container.mjs';

async function fixture(run, { traced = false } = {}) {
  const directory = await mkdtemp(join(tmpdir(), 'airicraft-work-'));
  const journal = await EffectJournal.open(join(directory, 'effects.sqlite'));
  const trace = traced ? await DecisionTrace.open(join(directory, 'trace'), { runId: 'work-test' }) : undefined;
  const native = new NativeContainer(), invocations = new InvocationBroker(), ledger = new ResourceLedger();
  const transfer = new ContainerTransfer({ scope: 'home', windowId: 'home-window' });
  const operations = { low: transfer, high: transfer };
  const effects = new EffectBroker({ native, journal, expectedWorld: 'fixture' });
  let now = 0;
  try {
    await effects.start();
    const activities = new ActivityCoordinator({ invocations, ledger, effects, operations });
    const work = new WorkService({ invocations, activities, operations, epoch: 'world', now: () => now, trace,
      rules: { low: { priority: 1, kind: 'land', context: null }, high: { priority: 12, kind: 'land', context: null } } });
    const root = (grants = ['container:home']) => invocations.install({ definition: 'work-fixture', grants });
    const request = (owner, sequence = 1, operation = 'low', extra = {}) => work.request(owner, sequence,
      { operation, arguments: { direction: 'withdraw', itemId: 'minecraft:string', quantity: 2 }, context: null, ...extra });
    const publish = (id, extra = {}) => work.publish(id,
      { epoch: 'world', captureId: 'assessment-1', captureSequence: 1, readiness: 'ready', ageUpperBoundMillis: 0, ...extra });
    const pulse = async () => {
      const decision = work.tick({ authority: 'available' });
      const deadline = performance.now() + 3000;
      while (work.state().busy) { if (performance.now() > deadline) assert.fail('work service stalled'); await delay(1); }
      return decision;
    };
    await run({ work, invocations, ledger, activities, native, journal, trace, root, request, publish, pulse, time: value => { now = value; } });
  } finally { await effects.stop(); await journal.close(); await trace?.close(); await rm(directory, { recursive: true, force: true }); }
}

test('queued work uses trusted priority, fresh coordinator admission and verified release before completion', async () => fixture(async ({ work, invocations, native, journal, root, request, publish, pulse }) => {
  const a = root(), b = root(), low = request(a), high = request(b, 1, 'high');
  publish(low); publish(high);
  assert.equal(work.tick().kind, 'wait');
  assert.equal(native.submissions, 0);
  assert.equal((await pulse()).offerId, high);
  assert.deepEqual(invocations.activity().subscribers, [b]);
  assert.equal((await journal.unfinished())[0].intent.provenance.workId, high);
  assert.equal(work.take(b, high).status, 'pending');
  native.progress(2, 2, false); await pulse();
  assert.equal(work.take(b, high).status, 'pending');
  native.progress(2, 2, true); await pulse();
  assert.equal(work.take(b, high).status, 'success');
  assert.equal((await pulse()).kind, 'wait'); // The completed effect invalidates the queued feasibility snapshot.
  assert.equal(native.submissions, 1);
  assert.deepEqual(await journal.unfinished(), []);
  invocations.cancel(a); work.poll();
  assert.equal(work.state().requests, 0);
}));

test('recurring offers retain identity, deduplicate and require the same observation for authoring and readiness', async () => fixture(async ({ work, root, publish, pulse, native }) => {
  const owner = root(), offer = { operation: 'low', arguments: { direction: 'withdraw', itemId: 'minecraft:string', quantity: 2 }, context: null };
  const basis = { epoch: 'world', captureId: 'assessment-1', captureSequence: 1, generation: work.observationGeneration };
  const first = work.replaceOffers(owner, 1, [offer, offer], basis);
  assert.equal(first.ids.length, 1);
  assert.deepEqual(work.replaceOffers(owner, 1, [offer, offer], basis), first);
  publish(first.ids[0], { captureId: 'assessment-2', captureSequence: 2 });
  assert.equal((await pulse()).kind, 'wait');
  const next = work.replaceOffers(owner, 2, [offer], { ...basis, captureId: 'assessment-2', captureSequence: 2 });
  assert.deepEqual(next.ids, first.ids);
  assert.equal((await pulse()).offerId, first.ids[0]);
  native.progress(2, 2, true); await pulse();
  assert.equal((await pulse()).kind, 'wait');
  assert.equal(native.submissions, 1);
  assert.throws(() => work.replaceOffers(owner, 3, [offer], basis), /offer_basis_stale/);
  work.replaceOffers(owner, 3, [offer], { ...basis, captureId: 'assessment-3', captureSequence: 3, generation: work.observationGeneration });
  publish(first.ids[0], { captureId: 'assessment-3', captureSequence: 3 });
  assert.equal((await pulse()).offerId, first.ids[0]);
  native.progress(2, 2, true); await pulse();
  work.replaceOffers(owner, 4, [], { ...basis, captureId: 'assessment-4', captureSequence: 4, generation: work.observationGeneration });
  assert.equal(work.state().requests, 0);
}));

test('refreshing the same capture cannot rejuvenate expired feasibility', async () => fixture(async ({ work, native, root, request, publish, pulse, time, invocations }) => {
  const owner = root(), id = request(owner);
  publish(id); time(2000);
  assert.equal((await pulse()).kind, 'wait');
  publish(id);
  assert.equal((await pulse()).kind, 'wait');
  assert.equal(native.submissions, 0);
  invocations.cancel(owner); work.poll();
}));

test('replacing offers validates the entire batch before changing an existing declaration set', async () => fixture(async ({ work, root, native }) => {
  const owner = root(), unauthorized = root([]);
  const offer = { operation: 'low', arguments: { direction: 'withdraw', itemId: 'minecraft:string', quantity: 2 }, context: null };
  const basis = { epoch: 'world', captureId: 'capture-1', captureSequence: 1, generation: work.observationGeneration };
  const first = work.replaceOffers(owner, 1, [offer], basis);
  assert.throws(() => work.replaceOffers(owner, 2, [offer, { ...offer, operation: 'missing' }], basis), /operation_unknown/);
  assert.throws(() => work.replaceOffers(unauthorized, 1, [offer], basis), /operation_not_granted/);
  assert.throws(() => work.replaceOffers(owner, 2, Array(33).fill(offer), basis), /invalid_work_offers/);
  assert.deepEqual(work.pending().map(record => record.id), first.ids);
  assert.throws(() => work.take(owner, first.ids[0]), /work_not_finite/);
  work.replaceOffers(owner, 2, [], basis);
  assert.throws(() => work.replaceOffers(owner, 1, [offer], basis), /work_retired/);
  assert.equal(work.state().requests, 0);
  assert.equal(native.submissions, 0);
}));

test('an offer batch fitting the guest wire also fits its trusted trace envelope', async () => fixture(async ({ work, root, trace }) => {
  const owner = root(), effects = [{ kind: 'work', operation: 'low',
    arguments: { direction: 'withdraw', itemId: 'minecraft:string', quantity: 2, padding: Array(2022).fill(null) }, context: null }];
  const accepted = guestResult(effects, 'offers');
  assert.doesNotThrow(() => frame({ requestId: 1, ok: true, result: accepted, cpuMicros: 1 }));
  const declarations = accepted.map(({ kind, ...request }) => request);
  const basis = { epoch: 'world', captureId: 'capture-1', captureSequence: 1, generation: work.observationGeneration };
  const result = work.replaceOffers(owner, 1, declarations, basis);
  assert.equal(result.ids.length, 1);
  await trace.flush();
  assert.equal(trace.status().incomplete, false);
  assert.doesNotThrow(() => trace.record('unrelated.root', { ok: true }));
}, { traced: true }));

test('withdrawing an admitted offer keeps its bounded attempt until release while replacing queued declarations', async () => fixture(async ({ work, root, publish, pulse, native, invocations, journal }) => {
  const owner = root(), offer = { operation: 'low', arguments: { direction: 'withdraw', itemId: 'minecraft:string', quantity: 2 }, context: null };
  const basis = { epoch: 'world', captureId: 'assessment-1', captureSequence: 1, generation: work.observationGeneration };
  const [id] = work.replaceOffers(owner, 1, [offer], basis).ids;
  publish(id); await pulse();
  const replacements = Array.from({ length: 32 }, (_, index) => ({ ...offer, arguments: { ...offer.arguments, quantity: index + 3 } }));
  work.replaceOffers(owner, 2, replacements, { ...basis, captureId: 'assessment-2', captureSequence: 2, generation: work.observationGeneration });
  assert.equal(work.state().recurringOffers, 32);
  assert.equal(work.state().retiringOffers, 1);
  assert.equal(invocations.activity().stopRequested, false);
  await pulse(); assert.equal(native.submissions, 1); assert.equal(native.cancellations, 0);
  native.progress(2, 2, false); await pulse();
  assert.equal(work.state().retiringOffers, 1);
  native.progress(2, 2, true); await pulse();
  assert.equal(work.state().retiringOffers, 0);
  assert.equal(work.state().requests, 32);
  assert.deepEqual(await journal.unfinished(), []);
  invocations.cancel(owner); work.poll();
  assert.equal(work.state().requests, 0);
}));

test('withdrawing and reintroducing an offer cannot evade its consumer and operation retry delay', async () => fixture(async ({ work, root, publish, pulse, native, ledger, time, invocations }) => {
  const owner = root(), protector = root(), transfer = new ContainerTransfer({ scope: 'home', windowId: 'home-window' });
  const resource = transfer.resource('container', 'minecraft:string', '');
  ledger.target(protector, resource, 4);
  const offer = { operation: 'low', arguments: { direction: 'withdraw', itemId: 'minecraft:string', quantity: 2 }, context: null };
  const basis = sequence => ({ epoch: 'world', captureId: `assessment-${sequence}`, captureSequence: sequence, generation: work.observationGeneration });
  const [first] = work.replaceOffers(owner, 1, [offer], basis(1)).ids;
  publish(first); await pulse();
  assert.equal(native.submissions, 0);
  work.replaceOffers(owner, 2, [], basis(2));
  ledger.target(protector, resource, 0);
  const [next] = work.replaceOffers(owner, 3, [offer], basis(3)).ids;
  publish(next, { captureId: 'assessment-3', captureSequence: 3 });
  assert.equal((await pulse()).kind, 'wait');
  time(4999); assert.equal((await pulse()).kind, 'wait');
  time(5000);
  work.replaceOffers(owner, 4, [offer], basis(4));
  publish(next, { captureId: 'assessment-4', captureSequence: 4 });
  assert.equal((await pulse()).offerId, next);
  native.progress(2, 2, true); await pulse();
  invocations.cancel(owner); invocations.cancel(protector); work.poll();
  assert.equal(work.state().requests, 0);
}));

test('covered eligibility reaches the same overdue policy before native admission', async () => fixture(async ({ work, invocations, native, root, request, publish, pulse }) => {
  const a = root(), b = root(), low = request(a), high = request(b, 1, 'high');
  publish(low); publish(high);
  work.advance({ epoch: 'world', fromTick: 0, toTick: 2400, eligibleRoots: [a], covered: true, ordinaryAllowed: true });
  const decision = await pulse();
  assert.equal(decision.offerId, low); assert.equal(decision.reason, 'overdue');
  native.progress(2, 2, true); await pulse();
  assert.equal(work.take(a, low).status, 'success');
  invocations.cancel(b); work.poll();
}));

test('cancellation during a delayed admission reply retains exclusion through verified partial cleanup', async () => fixture(async ({ work, invocations, native, journal, root, request, publish, pulse }) => {
  const a = root(), b = root(), first = request(a, 1, 'high'), second = request(b);
  publish(first); publish(second);
  let release;
  const barrier = new Promise(resolve => { release = resolve; });
  native.beforeReply = async name => { if (name === 'os_submit') await barrier; };
  try {
    work.tick({ authority: 'available' });
    const deadline = performance.now() + 3000;
    while (!native.submissions) { if (performance.now() > deadline) assert.fail('admission stalled'); await delay(1); }
    invocations.cancel(a); work.poll();
    assert.equal(work.tick({ authority: 'available' }).kind, 'wait');
    assert.equal(invocations.inspect(a).outcome, null);
    assert.equal(work.state().requests, 2);
    release();
    while (work.state().busy) await delay(1);
    await pulse();
    assert.equal(native.cancellations, 1);
    assert.equal(native.submissions, 1);
    native.progress(2, 1, false); await pulse();
    assert.equal(invocations.inspect(a).outcome, null);
    assert.equal(work.take(b, second).status, 'pending');
    native.progress(2, 1, true, 'CANCELLED'); await pulse();
    assert.equal(invocations.inspect(a).outcome.status, 'cancelled');
    assert.equal(work.state().requests, 1);
    assert.equal(publish(second), false);
    assert.equal((await pulse()).kind, 'wait');
    publish(second, { captureId: 'assessment-2', captureSequence: 2 }); await pulse();
    native.progress(2, 2, true); await pulse();
    assert.equal(work.take(b, second).status, 'success');
    assert.deepEqual(await journal.unfinished(), []);
  } finally { release(); }
}));

test('work identities, grants and retained capacity reject before native execution and reclaim after cancellation', async () => fixture(async ({ work, invocations, native, root, request }) => {
  const parent = root(), unauthorized = root([]);
  assert.throws(() => request(unauthorized), /operation_not_granted/);
  const first = request(parent), second = request(parent, 2);
  assert.equal(request(parent), first);
  assert.notEqual(first, second);
  assert.throws(() => request(parent, 1, 'high'), /work_conflict/);
  assert.throws(() => request(parent, 3, 'absent'), /operation_unknown/);
  assert.throws(() => request(parent, 3, 'low', { context: { id: 'foreign-window' } }), /invalid_work/);
  for (let sequence = 3; sequence <= 32; sequence++) request(parent, sequence);
  assert.throws(() => request(parent, 33), /invocation_work_capacity/);
  const owners = [parent, unauthorized];
  // Give the second root a permitted child so all 32 live invocation slots can own work.
  invocations.cancel(unauthorized); invocations.uninstall(unauthorized); owners.pop();
  while (owners.length < 32) owners.push(invocations.spawn(parent, { definition: 'bounded-work-child', grants: ['container:home'] }).id);
  for (const owner of owners.slice(1)) for (let sequence = 1; sequence <= 32; sequence++) request(owner, sequence);
  assert.equal(work.state().requests, 1024);
  assert.throws(() => request(parent, 33), /work_capacity/);
  assert.equal(native.submissions, 0);
  invocations.cancel(parent); work.poll();
  assert.equal(work.state().requests, 0);
}));

test('a ready scheduling hint cannot bypass another consumer stock floor at fresh native admission', async () => fixture(async ({ work, invocations, ledger, native, root, request, publish, pulse }) => {
  const caller = root(), protectedRoot = root();
  const transfer = new ContainerTransfer({ scope: 'home', windowId: 'home-window' });
  ledger.target(protectedRoot, transfer.resource('container', 'minecraft:string', ''), 4);
  const id = request(caller); publish(id); await pulse();
  assert.deepEqual(work.take(caller, id), { status: 'rejected', reason: 'resource_unavailable' });
  assert.equal(work.state().fault, null);
  assert.equal(invocations.inspect(caller).phase, 'running');
  assert.equal(native.submissions, 0);
  assert.throws(() => request(caller), /work_retired/);
}));

test('a native transport fault stops dispatch while the journalled activity still needs release', async () => fixture(async ({ work, invocations, native, root, request, publish, pulse }) => {
  const a = root(), b = root(), first = request(a, 1, 'high'), second = request(b);
  publish(first); publish(second); await pulse();
  let fail = true;
  native.beforeReply = async name => { if (name === 'os_inspect' && fail) { fail = false; throw Error('transport_lost'); } };
  await pulse();
  assert.equal(work.state().fault, 'transport_lost');
  assert.equal(work.state().active, first);
  assert.equal(invocations.inspect(a).outcome, null);
  assert.equal(native.submissions, 1);
  native.progress(2, 1, true, 'CANCELLED'); await pulse();
  assert.equal(invocations.inspect(a).outcome.status, 'failure');
  publish(second, { captureId: 'assessment-2', captureSequence: 2 });
  assert.equal((await pulse()).reason, 'work_service_failed');
  assert.equal(native.submissions, 1);
  invocations.cancel(b); work.poll();
}));

test('a delayed admission does not freeze newly ready roots out of covered eligibility', async () => fixture(async ({ work, native, root, request, publish, pulse, invocations }) => {
  const a = root(), first = request(a, 1, 'high'); publish(first);
  let release;
  const barrier = new Promise(resolve => { release = resolve; });
  native.beforeReply = async name => { if (name === 'os_submit') await barrier; };
  try {
    const selected = work.tick({ authority: 'available' });
    while (!native.submissions) await delay(1);
    const b = root(), second = request(b); publish(second);
    work.advance({ epoch: 'world', fromTick: 0, toTick: 2400, eligibleRoots: [b], covered: true, ordinaryAllowed: true });
    assert.equal(work.state().scheduling.roots.find(root => root.id === b).ageTicks, 2400);
    selected.roots.splice(0, selected.roots.length, b); // Returned decisions cannot rewrite pending admission credit.
    release(); while (work.state().busy) await delay(1);
    assert.equal(work.state().scheduling.roots.find(root => root.id === b).ageTicks, 2400);
    native.progress(2, 2, true); await pulse(); work.take(a, first);
    publish(second, { captureId: 'assessment-2', captureSequence: 2 });
    const decision = await pulse();
    assert.equal(decision.offerId, second); assert.equal(decision.reason, 'overdue');
    native.progress(2, 2, true); await pulse(); work.take(b, second);
    assert.equal(work.state().fault, null);
  } finally { release(); }
}));

test('a native post-identity preflight rejection keeps its typed reason and zero-effect evidence', async () => fixture(async ({ work, native, root, request, publish, pulse }) => {
  const owner = root(), id = request(owner); publish(id);
  native.beforeReply = async name => {
    if (name === 'os_submit') Object.assign(native.receipt, { state: 'FAILED', phase: 'admission_rejected', reason: 'container_changed', released: true,
      effects: { admitted: false, accountingComplete: true, releaseEvidence: { verified: true } } });
  };
  await pulse();
  const result = work.take(owner, id);
  assert.equal(result.status, 'rejected');
  assert.equal(result.reason, 'container_changed');
  assert.equal(result.activity.evidence.released, true);
  assert.deepEqual(result.activity.evidence.consumed, {});
}));
