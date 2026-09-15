import test from 'node:test';
import assert from 'node:assert/strict';
import { GuestInvocation } from '../src/os/guest.mjs';

test('a generator yields copied typed effects and has no host capabilities', async () => {
  const input = { scope: { name: 'farm' } };
  const guest = await GuestInvocation.create({ source: `
    function* main(os, input) {
      const frame = yield os.observe({ scope: input.scope.name });
      const child = yield os.spawn('harvest@digest', { cell: frame.cell });
      const result = yield os.join(child);
      return { result, host: [typeof process, typeof require, typeof fetch], frozen: Object.isFrozen(input.scope) };
    }`, input, definition: 'farmer@digest' });
  try {
    input.scope.name = 'changed';
    assert.deepEqual(guest.resume().result, { done: false, value: { kind: 'observe', query: { scope: 'farm' } } });
    assert.equal(guest.resume({ cell: 3 }).result.value.definition, 'harvest@digest');
    const handle = { id: 'child', parentId: 'root', sequence: 1 };
    assert.deepEqual(guest.resume(handle).result.value, { kind: 'join', handle });
    assert.deepEqual(guest.resume('done').result, { done: true, value: {
      result: 'done', host: ['undefined', 'undefined', 'undefined'], frozen: true
    } });
    assert.throws(() => guest.resume(), /guest_finished/);
  } finally { guest.dispose(); }
});

test('guest mutation of serialization intrinsics cannot change the boundary encoder', async () => {
  const guest = await GuestInvocation.create({ source: `
    function* main(os) {
      JSON.stringify = () => '{"done":true}';
      Object.prototype.toJSON = () => 'forged';
      Array.prototype.toJSON = () => 'forged';
      String.prototype.charCodeAt = () => 0;
      yield os.work('plant', { cells: [1, 2] });
      return 'safe';
    }`, definition: 'mutator', input: null });
  try {
    assert.deepEqual(guest.resume().result.value, { kind: 'work', operation: 'plant', arguments: { cells: [1, 2] }, context: null });
    assert.equal(guest.resume().result.value, 'safe');
  } finally { guest.dispose(); }
});

test('source, input, output and closed effect limits reject before further guest work', async () => {
  await assert.rejects(GuestInvocation.create({ source: ' '.repeat(65_537), input: null, definition: 'large' }), /guest_source_limit/);
  await assert.rejects(GuestInvocation.create({ source: 'function* main() {}', input: 'x'.repeat(16_385), definition: 'input' }), /message_limit/);
  for (const [body, reason] of [
    ["yield { kind: 'native', operation: 'os_submit' };", /effect_unknown/],
    ["yield { kind: 'work', operation: 'plant', arguments: {}, context: null, owner: 'another-root' };", /invalid_effect/],
    ["return 'x'.repeat(17000);", /guest_message_limit/],
    ["return '魚'.repeat(6000);", /guest_message_limit|guest_cpu_limit/],
    ["return { get value() { while (true) {} } };", /guest_invalid_json/]
  ]) {
    const guest = await GuestInvocation.create({ source: `function* main() { ${body} }`, input: null, definition: 'invalid' });
    try { assert.throws(() => guest.resume(), reason); assert.throws(() => guest.resume(), /guest_failed/); }
    finally { guest.dispose(); }
  }
});

test('loop and allocation failures stay in their invocation and leave other VMs usable', async () => {
  // Either independent guard can win when the test host is descheduled by the full suite.
  await assert.rejects(GuestInvocation.create({ source: 'while (true) {}', input: null, definition: 'init-loop' }), /guest_(cpu|wall)_limit/);
  const healthy = await GuestInvocation.create({ source: 'function* main() { yield { kind: "observe", query: {} }; return 7; }', input: null, definition: 'healthy' });
  try {
    for (const body of ['while (true) {}', 'const values = []; while (true) values.push(new Array(10000).fill(3));']) {
      const guest = await GuestInvocation.create({ source: `function* main() { ${body} }`, input: null, definition: 'faulty' });
      try { assert.throws(() => guest.resume(), /guest_(cpu|wall)_limit|out of memory/); }
      finally { guest.dispose(); }
    }
    assert.equal(healthy.resume().result.value.kind, 'observe');
    assert.equal(healthy.resume().result.value, 7);
  } finally { healthy.dispose(); }
});

test('offer evaluations are bounded work declarations and async generators are not another scheduler', async () => {
  await assert.rejects(GuestInvocation.create({ source: 'async function* main() { yield 1; }', input: null, definition: 'async' }), /main_must_be_synchronous_generator/);
  const guest = await GuestInvocation.create({ source: 'function offers(os, input, view) { return Array.from({ length: view.count }, () => os.work("plant", {})); }',
    mode: 'offers', input: null, definition: 'offers' });
  try {
    assert.equal(guest.offers({ count: 32 }).result.length, 32);
    assert.throws(() => guest.offers({ count: 33 }), /work_offer_limit/);
  } finally { guest.dispose(); }
});

test('guest exception accessors are not evaluated while collecting bounded diagnostics', async () => {
  const guest = await GuestInvocation.create({ source: `function* main() {
    const error = { message: 'fixture_failure', stack: 'bounded-stack' };
    Object.defineProperty(error, 'name', { get() { while (true) {} } });
    throw error;
  }`, input: null, definition: 'diagnostic' });
  try { assert.throws(() => guest.resume(), error => error.message.includes('fixture_failure') && error.detail.stack === 'bounded-stack'); }
  finally { guest.dispose(); }
});
