import test from 'node:test';
import assert from 'node:assert/strict';
import { NativeTransport } from '../src/os/native-transport.mjs';

test('the wrapper adapter requires driver mode and exposes only versioned OS methods', async () => {
  const requests = [];
  const authority = { sessionId: 'native', epoch: 'world', generation: 0, lease: null, active: null, admissionSequence: 0 };
  let reply = { schemaVersion: 1, status: 'ok', authority };
  const stream = { request: async body => {
    requests.push(body);
    return { codexDriverActive: true, result: JSON.stringify(reply) };
  }, close: async () => {} };
  const native = await NativeTransport.open({ stream });
  reply = { ...reply, status: 'rejected', code: 'outcome_unknown' };
  assert.equal((await native.call('os_inspect', { id: { epoch: 'world', generation: 1, sequence: 1 } })).code, 'outcome_unknown');
  const before = requests.length;
  await assert.rejects(native.call('attack_entity', {}), /native_method_not_allowed/);
  assert.equal(requests.length, before);
  reply = { ...reply, schemaVersion: 2 };
  await assert.rejects(native.call('os_inspect', {}), /unsupported_native_schema/);
  await native.close();
  await assert.rejects(native.call('os_inspect', {}), /native_transport_closed/);
  await assert.rejects(NativeTransport.open({ stream: { request: async () => ({ codexDriverActive: false }), close: async () => {} } }), /driver_mode_required/);
});

test('observation freshness includes the complete wrapper round trip', async () => {
  let now = 0, elapsed = 40;
  const stream = { request: async body => {
    now += elapsed;
    return { codexDriverActive: true, result: JSON.stringify({ schemaVersion: 1, status: 'ok', authority: { epoch: 'world' },
      frame: { schemaVersion: 1, epoch: 'world', captureAgeMillis: 10 } }) };
  }, close: async () => {} };
  const native = await NativeTransport.open({ stream, clock: () => now });
  try {
    assert.equal((await native.call('os_observe')).frame.captureAgeUpperBoundMillis, 50);
    elapsed = 2000;
    await assert.rejects(native.call('os_observe'), /observation_stale/);
  } finally { await native.close(); }
});

test('startup verifies driver mode with a read-only native handshake without loading planner status', async () => {
  const requests = [];
  const stream = { request: async request => {
    requests.push(request);
    if (request.op !== 'call' || request.name !== 'os_observe') throw Error('unexpected_planner_status_read');
    return { codexDriverActive: true, result: JSON.stringify({ schemaVersion: 1, status: 'ok', authority: {} }) };
  }, close: async () => {} };
  const native = await NativeTransport.open({ stream });
  try { assert.deepEqual(requests, [{ op: 'call', name: 'os_observe', arguments: {} }]); }
  finally { await native.close(); }
});
