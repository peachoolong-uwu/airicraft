import test from 'node:test';
import assert from 'node:assert/strict';
import { InvocationBroker } from '../src/os/broker.mjs';
import { ConditionWaits } from '../src/os/waits.mjs';
import { copyMessage } from '../src/os/value.mjs';
import { frame as runnerFrame, FrameDecoder } from '../src/os/runner-wire.mjs';

const ready = { scope: 'wheat', path: ['mature'], equals: true };
function fixture() {
  let time = 0;
  const invocations = new InvocationBroker(), owner = invocations.install({ definition: 'farm', grants: ['observe:wheat'] });
  const waits = new ConditionWaits({ invocations, scopes: { wheat: 'observe:wheat', sheep: 'observe:sheep' }, epoch: 'epoch-1', now: () => time });
  const frame = (sequence, facts = [{ path: ['mature'], known: true, value: false }], overrides = {}) => ({ schemaVersion: 1,
    sessionId: 'session', epoch: 'epoch-1', scope: 'wheat', captureId: `capture-${sequence}`, captureSequence: sequence,
    capturedAtNanos: String(sequence * 1000), clockDomain: 'native:session', source: 'fixture', clientTick: sequence,
    serverTick: sequence, receivedAtHostMillis: time, captureAgeUpperBoundMillis: 0,
    coverage: { available: true, complete: true, truncated: false }, facts,
    progress: { clockId: 'eligible-wheat', eligibleTicks: sequence }, ...overrides });
  return { invocations, owner, waits, frame, advance: amount => { time += amount; } };
}

test('wait registration uses current facts, wakes once, and owns no player', () => {
  const { invocations, owner, waits, frame } = fixture();
  waits.publish(frame(1));
  const pending = waits.wait(owner, ready);
  assert.equal(waits.take(owner, pending).status, 'pending');
  assert.equal(invocations.activity(), null);
  waits.publish(frame(2, [{ path: ['mature'], known: true, value: true }]));
  assert.equal(waits.take(owner, pending).status, 'met');
  assert.throws(() => waits.take(owner, pending), /wait_unknown/);
  const alreadyReady = waits.wait(owner, ready);
  assert.equal(waits.take(owner, alreadyReady).status, 'met');
  assert.equal(waits.state().waits, 0);
});

test('missing, stale and unavailable facts remain unknown and do not assert readiness', () => {
  const { owner, waits, frame, advance } = fixture();
  waits.publish(frame(1, [], { coverage: { available: true, complete: false, truncated: true } }));
  const pending = waits.wait(owner, { not: ready });
  assert.equal(waits.inspect(owner, pending).truth, 'unknown');
  waits.publish(frame(2, [{ path: ['mature'], known: true, value: true }]));
  assert.equal(waits.inspect(owner, pending).truth, 'unmet');
  advance(2000); waits.poll();
  assert.equal(waits.inspect(owner, pending).truth, 'unknown');
  assert.equal(waits.take(owner, pending).status, 'pending');
  assert.throws(() => waits.wait(owner, { scope: 'sheep', path: ['ready'], equals: true }), /operation_not_granted/);
});

test('epochs and explicit history gaps invalidate pending waits without inventing missed events', () => {
  const { owner, waits, frame } = fixture();
  waits.publish(frame(1));
  const cursor = waits.cursor(), first = waits.wait(owner, ready, { cursor });
  waits.gap('native_history_gap');
  assert.equal(waits.take(owner, first).status, 'gap');
  const second = waits.wait(owner, ready, { cursor });
  assert.equal(waits.take(owner, second).status, 'gap');
  const third = waits.wait(owner, ready);
  waits.setEpoch('epoch-2');
  assert.equal(waits.take(owner, third).status, 'epoch_changed');
  assert.throws(() => waits.publish(frame(2)), /stale_epoch/);
});

test('retention gaps and duplicate native captures are explicit', () => {
  const { owner, waits, frame } = fixture();
  waits.publish(frame(1));
  const cursor = waits.cursor();
  assert.equal(waits.publish(frame(1)).accepted, false);
  for (let sequence = 2; sequence <= 514; sequence++) waits.publish(frame(sequence));
  const expired = waits.wait(owner, ready, { cursor });
  assert.equal(waits.take(owner, expired).status, 'gap');
  assert.equal(waits.state().frames, 1);
});

test('eligible game deadlines use a declared native progress counter and wall deadlines keep their own clock', () => {
  const { owner, waits, frame, advance } = fixture();
  waits.publish(frame(1));
  const growing = waits.wait(owner, ready, { deadline: { clock: 'eligible_ticks', scope: 'wheat', ticks: 20 } });
  const wall = waits.wait(owner, ready, { deadline: { clock: 'wall', milliseconds: 5000 } });
  advance(5000); waits.poll();
  assert.equal(waits.take(owner, wall).status, 'deadline');
  assert.equal(waits.take(owner, growing).status, 'pending');
  waits.publish(frame(2, undefined, { progress: { clockId: 'eligible-wheat', eligibleTicks: 1 } }));
  waits.publish(frame(3, undefined, { progress: { clockId: 'eligible-wheat', eligibleTicks: null } }));
  waits.publish(frame(4, undefined, { progress: { clockId: 'eligible-wheat', eligibleTicks: 100 } }));
  assert.equal(waits.inspect(owner, growing).eligibleTicks, 0);
  waits.publish(frame(5, undefined, { progress: { clockId: 'eligible-wheat', eligibleTicks: 120 } }));
  assert.equal(waits.take(owner, growing).status, 'deadline');
});

test('wait capacity includes unconsumed results and cancellation removes owned subscriptions', () => {
  const { invocations, owner, waits } = fixture();
  for (let i = 0; i < 32; i++) waits.wait(owner, ready);
  assert.throws(() => waits.wait(owner, ready), /invocation_wait_capacity/);
  invocations.cancel(owner);
  waits.poll();
  assert.equal(waits.state().waits, 0);
  const roots = Array.from({ length: 8 }, (_, index) => invocations.install({ definition: `farm-${index}`, grants: ['observe:wheat'] }));
  for (const root of roots) for (let i = 0; i < 32; i++) waits.wait(root, ready);
  const ninth = invocations.install({ definition: 'ninth', grants: ['observe:wheat'] });
  assert.throws(() => waits.wait(ninth, ready), /global_wait_capacity/);
  assert.equal(waits.state().waits, 256);
});

test('a history gap requires a newer capture before cached facts become current again', () => {
  const { owner, waits, frame } = fixture();
  const mature = frame(1, [{ path: ['mature'], known: true, value: true }]);
  waits.publish(mature); waits.gap('native_gap');
  const waiting = waits.wait(owner, ready);
  assert.equal(waits.publish(mature).accepted, false);
  assert.equal(waits.observe(owner, ['wheat']).scopes[0].current, false);
  assert.equal(waits.take(owner, waiting).status, 'pending');
  waits.publish(frame(2, mature.facts));
  assert.equal(waits.take(owner, waiting).status, 'met');
});

test('read responses distinguish stale last-seen evidence from current facts', () => {
  const { owner, waits, frame, advance } = fixture();
  waits.publish(frame(1));
  advance(2500);
  const [observation] = waits.observe(owner, ['wheat']).scopes;
  assert.equal(observation.current, false);
  assert.equal(observation.ageUpperBoundMillis, 2500);
  assert.equal(observation.frame.captureId, 'capture-1');
});

test('foreign epochs and bridge sessions cannot borrow current snapshot identity', () => {
  const { owner, waits, frame } = fixture();
  waits.publish(frame(1));
  assert.throws(() => waits.publish(frame(2, undefined, { sessionId: 'different-session' })), /observation_session_changed/);
  const foreign = waits.wait(owner, ready, { cursor: { epoch: 'old-service-epoch', sequence: 999 } });
  assert.equal(waits.take(owner, foreign).status, 'epoch_changed');
});

test('a scoped update wakes only dependent waits while the regular poll services clocks', () => {
  const { owner, waits, frame, advance } = fixture();
  waits.publish(frame(1));
  const waiting = waits.wait(owner, ready);
  advance(100);
  waits.publish(frame(2, [], { scope: 'sheep', progress: { clockId: 'eligible-sheep', eligibleTicks: 100 } }));
  assert.equal(waits.inspect(owner, waiting).evaluatedAt, 0);
  waits.poll();
  assert.equal(waits.inspect(owner, waiting).evaluatedAt, 100);
});

test('projection admission reserves byte, depth and node space for its guest response envelope', () => {
  let deep = true;
  for (let i = 0; i < 11; i++) deep = { nested: deep };
  for (const value of ['x'.repeat(15_000), deep, Array(1900).fill(0)]) {
    const { waits, frame } = fixture();
    const candidate = frame(1, [{ path: ['large'], known: true, value }]);
    assert.doesNotThrow(() => copyMessage(candidate));
    assert.throws(() => waits.publish(candidate), /message_limit/);
    assert.equal(waits.state().frames, 0);
  }
});

test('observation pages carry one independently captured scope through the bounded runner wire', () => {
  const { invocations, waits, frame } = fixture();
  const owner = invocations.install({ definition: 'mixed', grants: ['observe:wheat', 'observe:sheep'] });
  const scopes = ['wheat', 'sheep'], value = Array(1800).fill(0);
  waits.publish(frame(1, [{ path: ['large'], known: true, value }]));
  waits.publish(frame(2, [{ path: ['large'], known: true, value }], { scope: 'sheep' }));
  let offset = 0;
  for (const scope of scopes) {
    const observation = waits.observe(owner, scopes, { offset });
    assert.equal(observation.scopes.length, 1);
    assert.equal(observation.scopes[0].scope, scope);
    const message = { requestId: 1, invocation: owner, command: 'resume', input: { status: 'ok', value: observation } };
    let received;
    new FrameDecoder(decoded => { received = decoded; }).push(runnerFrame(message));
    assert.deepEqual(received, message);
    observation.scopes[0].frame.facts[0].value[0] = 99;
    assert.equal(waits.observe(owner, scopes, { offset }).scopes[0].frame.facts[0].value[0], 0);
    offset = observation.nextOffset;
  }
  assert.equal(offset, null);
  assert.throws(() => waits.observe(owner, scopes, { offset: 2 }), /invalid_observation_page/);
});
