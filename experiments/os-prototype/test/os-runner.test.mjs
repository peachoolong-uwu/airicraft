import test from 'node:test';
import assert from 'node:assert/strict';
import { frame, FrameDecoder } from '../src/os/runner-wire.mjs';
import { RootRunner } from '../src/os/runner.mjs';

test('framing accepts fragmented bounded messages and rejects hostile lengths before allocating a body', () => {
  const received = [], decoder = new FrameDecoder(message => received.push(message));
  const bytes = Buffer.concat([frame({ one: '魚' }), frame({ two: 2 })]);
  for (const byte of bytes) decoder.push(Buffer.from([byte]));
  assert.deepEqual(received, [{ one: '魚' }, { two: 2 }]);
  const malicious = Buffer.alloc(4); malicious.writeUInt32BE(0xffffffff);
  assert.throws(() => decoder.push(malicious), /runner_frame_limit/);
  assert.equal(decoder.bufferedBytes, 0);
  assert.throws(() => frame({ data: 'x'.repeat(16_384) }), /message_limit/);
});

test('one root process runs independent child VMs and reports guest failure without releasing host ownership', async () => {
  const runner = await RootRunner.start();
  try {
    assert.notEqual(runner.pid, process.pid);
    await runner.create('invocation:1', { definition: 'root', input: null, source: 'function* main() { yield { kind: "observe", query: {} }; return 7; }' });
    await runner.create('invocation:2', { definition: 'child', input: null, source: 'function* main() { while (true) {} }' });
    await assert.rejects(runner.resume('invocation:2'), /guest_cpu_limit/);
    assert.equal((await runner.resume('invocation:1')).result.value.kind, 'observe');
    assert.equal((await runner.resume('invocation:1')).result.value, 7);
    await runner.dispose('invocation:1');
    assert.equal(runner.state().failed, null);
  } finally { await runner.close(); }
});

test('the external watchdog kills a stopped runner while another root continues', async () => {
  const failed = [], stuck = await RootRunner.start({ onFailure: error => failed.push(error.message) });
  const healthy = await RootRunner.start();
  try {
    await stuck.create('invocation:1', { definition: 'stuck', input: null, source: 'function* main() { return 1; }' });
    await healthy.create('invocation:2', { definition: 'healthy', input: null, source: 'function* main() { return 2; }' });
    process.kill(stuck.pid, 'SIGSTOP');
    await assert.rejects(stuck.resume('invocation:1'), /runner_watchdog/);
    assert.deepEqual(failed, ['runner_watchdog']);
    assert.equal((await healthy.resume('invocation:2')).result.value, 2);
  } finally { await stuck.close(); await healthy.close(); }
});

test('approved source crosses the bounded channel in chunks without exposing a filesystem to the guest', async () => {
  const runner = await RootRunner.start();
  try {
    const source = '/*' + '魚'.repeat(21_000) + '*/\nfunction* main() { return typeof importScripts; }';
    assert.ok(Buffer.byteLength(source) > 60_000);
    await runner.create('invocation:1', { definition: 'chunked', source });
    assert.equal((await runner.resume('invocation:1')).result.value, 'undefined');
  } finally { await runner.close(); }
});

test('killing a runner is observable and a full ordinary queue cannot delay shutdown', async () => {
  const runner = await RootRunner.start();
  const calls = [];
  try {
    await runner.create('invocation:1', { definition: 'root', input: null, source: 'function* main() { for (;;) yield { kind: "observe", query: {} }; }' });
    process.kill(runner.pid, 'SIGSTOP');
    for (let index = 0; index < 65; index++) calls.push(runner.resume('invocation:1').catch(error => error.message));
    await runner.close();
    assert.equal(runner.state().failed, 'runner_queue_capacity');
    assert.equal((await Promise.all(calls)).length, 65);
  } finally { await runner.close(); }
});
