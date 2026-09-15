import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { EffectJournal } from '../src/os/journal.mjs';
import { EffectBroker } from '../src/os/effects.mjs';

class NativeFixture {
  sessionId = 'native-1';
  epoch = 'world-1';
  generation = 0;
  admissionSequence = 0;
  lease = null;
  transferred = 0;
  receipts = new Map();
  beforeSubmit = async () => {};
  beforeAcquire = async () => {};
  beforeHeartbeat = async () => {};
  beforeRelease = async () => {};
  loseResponse = false;
  heartbeatCount = 0;
  rejectHeartbeat = false;
  authority() { return JSON.parse(JSON.stringify({ sessionId: this.sessionId, epoch: this.epoch, generation: this.generation, admissionSequence: this.admissionSequence, lease: this.lease, active: null })); }
  async call(name, args = {}) {
    let result = {};
    if (name === 'os_observe') result.frame = { sessionId: this.sessionId, epoch: this.epoch, captureId: 'capture-1',
      world: { worldId: 'fixture', dimension: 'overworld', alive: true, controllerBusy: false, reflexActive: false }, facts: {} };
    else if (name === 'os_lease') {
      if (args.action === 'heartbeat') {
        await this.beforeHeartbeat();
        this.heartbeatCount++;
        if (this.rejectHeartbeat) return { status: 'rejected', code: 'stale_fence', authority: this.authority() };
      }
      if (args.action === 'acquire') { await this.beforeAcquire(); this.lease = { epoch: this.epoch, generation: ++this.generation, hostId: args.hostId }; }
      if (args.action === 'release') { await this.beforeRelease(); this.lease = null; }
      result.lease = this.lease;
    } else if (name === 'os_submit') {
      await this.beforeSubmit(args);
      if (this.lease?.epoch !== args.id.epoch || this.lease?.generation !== args.id.generation)
        return { status: 'rejected', code: 'stale_fence', authority: this.authority() };
      const key = JSON.stringify(args.id);
      if (!this.receipts.has(key)) {
        this.transferred += args.arguments.quantity;
        this.admissionSequence = args.id.sequence;
        this.receipts.set(key, { id: args.id, state: 'SUCCEEDED', released: true,
          basis: { payloadHash: args.payloadHash, operation: args.operation, captureId: args.captureId },
          effects: { transferred: args.arguments.quantity, accountingComplete: true, releaseEvidence: { verified: true } } });
      }
      result.receipt = this.receipts.get(key);
      if (this.loseResponse) { this.loseResponse = false; throw Error('connection_lost_after_admission'); }
    } else if (name === 'os_inspect') {
      result.receipt = this.receipts.get(JSON.stringify(args.id));
      if (!result.receipt) return { status: 'rejected', code: 'outcome_unknown', authority: this.authority() };
    } else throw Error(`unsupported_fake_operation:${name}`);
    return { status: 'ok', ...result, authority: this.authority() };
  }
}

test('a lost admission reply is reconciled by ID, with durable intent recorded before the only transfer', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'airicraft-effects-'));
  let journal, broker;
  try {
    journal = await EffectJournal.open(join(directory, 'effects.sqlite'));
    const native = new NativeFixture();
    native.loseResponse = true;
    native.beforeSubmit = async request => {
      const entry = await journal.inspect(request.id);
      assert.equal(entry.intent.payloadHash, request.payloadHash);
      assert.deepEqual(entry.intent.request, request);
    };
    broker = new EffectBroker({ native, journal, expectedWorld: 'fixture' });
    await broker.start();
    const receipt = await broker.execute({ definition: 'supply@digest', invocation: 'invocation:1',
      operation: 'transfer_container', arguments: { quantity: 6 } });
    assert.equal(receipt.released, true);
    assert.equal(native.transferred, 6);
    assert.deepEqual(await journal.unfinished(), []);
    assert.equal((await journal.recent()).length, 1);
  } finally { await broker?.stop(); await journal?.close(); await rm(directory, { recursive: true, force: true }); }
});

const unfinishedIntent = () => {
  const id = { epoch: 'world-1', generation: 1, sequence: 1 };
  const request = { schemaVersion: 1, id, operation: 'transfer_container', arguments: { quantity: 6 }, captureId: 'capture-1', payloadHash: 'a'.repeat(64) };
  return { id, request, payloadHash: request.payloadHash, definition: 'supply@digest', invocation: 'invocation:old' };
};

test('restart resolves a never-admitted request only under its expired generation and lower high-water mark', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'airicraft-effects-'));
  let journal, broker;
  try {
    journal = await EffectJournal.open(join(directory, 'effects.sqlite'));
    await journal.record(unfinishedIntent());
    const native = new NativeFixture();
    native.generation = 1;
    broker = new EffectBroker({ native, journal, expectedWorld: 'fixture' });
    await broker.start();
    assert.deepEqual(await journal.unfinished(), []);
    assert.equal((await journal.recent())[0].receipt.disposition, 'not_admitted');
    assert.equal(native.transferred, 0);
    assert.equal(native.generation, 2);
  } finally { await broker?.stop(); await journal?.close(); await rm(directory, { recursive: true, force: true }); }
});

test('missing receipts under advanced, changed, live or incomplete authority never authorize replay', async () => {
  for (const change of [
    native => { native.admissionSequence = 1; },
    native => { native.generation = 2; },
    native => { native.epoch = 'world-2'; },
    native => { native.lease = { epoch: 'world-1', generation: 1, hostId: 'old-host' }; },
    native => { native.lease = undefined; }
  ]) {
    const directory = await mkdtemp(join(tmpdir(), 'airicraft-effects-'));
    let journal, broker;
    try {
      journal = await EffectJournal.open(join(directory, 'effects.sqlite'));
      await journal.record(unfinishedIntent());
      const native = new NativeFixture(); native.generation = 1; change(native);
      broker = new EffectBroker({ native, journal, expectedWorld: 'fixture' });
      await assert.rejects(broker.start(), /reconciliation_required/);
      assert.equal((await journal.unfinished()).length, 1);
      assert.equal(native.transferred, 0);
      assert.equal((await broker.stop()).released, false);
    } finally { await journal?.close(); await rm(directory, { recursive: true, force: true }); }
  }
});

test('restart reconciles a retained completed transfer without performing it again', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'airicraft-effects-'));
  let journal, broker;
  try {
    journal = await EffectJournal.open(join(directory, 'effects.sqlite'));
    const intent = unfinishedIntent();
    await journal.record(intent);
    const native = new NativeFixture(); native.generation = 1;
    native.lease = { epoch: native.epoch, generation: 1, hostId: 'previous' };
    await native.call('os_submit', intent.request);
    native.lease = null;
    broker = new EffectBroker({ native, journal, expectedWorld: 'fixture' });
    await broker.start();
    assert.deepEqual(await journal.unfinished(), []);
    assert.equal(native.transferred, 6);
  } finally { await broker?.stop(); await journal?.close(); await rm(directory, { recursive: true, force: true }); }
});

test('a matching receipt with a nonterminal state cannot acknowledge released work', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'airicraft-effects-'));
  let journal, broker;
  try {
    journal = await EffectJournal.open(join(directory, 'effects.sqlite'));
    const intent = unfinishedIntent();
    await journal.record(intent);
    const native = new NativeFixture(); native.generation = 1;
    native.lease = { epoch: native.epoch, generation: 1, hostId: 'previous' };
    await native.call('os_submit', intent.request);
    native.lease = null;
    native.receipts.get(JSON.stringify(intent.id)).state = 'RUNNING';
    broker = new EffectBroker({ native, journal, expectedWorld: 'fixture' });
    await assert.rejects(broker.start(), /invalid_native_receipt/);
    assert.equal((await journal.unfinished()).length, 1);
    assert.equal(native.generation, 1);
  } finally { await broker?.stop(); await journal?.close(); await rm(directory, { recursive: true, force: true }); }
});


test('lease renewal continues independently and a rejected heartbeat fences new submissions', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'airicraft-effects-'));
  let journal, broker;
  const intervals = new Map();
  const timers = { setInterval: (callback, milliseconds) => { const id = intervals.size + 1; intervals.set(id, { callback, milliseconds }); return id; },
    clearInterval: id => intervals.delete(id) };
  try {
    journal = await EffectJournal.open(join(directory, 'effects.sqlite'));
    const native = new NativeFixture();
    broker = new EffectBroker({ native, journal, expectedWorld: 'fixture', timers });
    await broker.start();
    assert.equal(intervals.size, 1);
    const renewal = [...intervals.values()][0];
    assert.equal(renewal.milliseconds, 1000);
    await renewal.callback();
    await renewal.callback();
    assert.equal(native.heartbeatCount, 2);
    native.rejectHeartbeat = true;
    await renewal.callback();
    await assert.rejects(broker.execute({ definition: 'farm', invocation: 'i', operation: 'transfer_container', arguments: { quantity: 6 } }), /stale_fence/);
    assert.equal(native.transferred, 0);
    await broker.stop();
    assert.equal(intervals.size, 0);
  } finally { await broker?.stop(); await journal?.close(); await rm(directory, { recursive: true, force: true }); }
});


test('stopping during lease acquisition drains the late lease instead of starting a new owner', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'airicraft-effects-'));
  let journal, broker, releaseAcquire;
  const acquireAllowed = new Promise(resolve => { releaseAcquire = resolve; });
  let signalEntered;
  const entered = new Promise(resolve => { signalEntered = resolve; });
  try {
    journal = await EffectJournal.open(join(directory, 'effects.sqlite'));
    const native = new NativeFixture();
    native.beforeAcquire = async () => { signalEntered(); await acquireAllowed; };
    broker = new EffectBroker({ native, journal, expectedWorld: 'fixture' });
    const starting = broker.start();
    await entered;
    const stopped = broker.stop();
    releaseAcquire();
    await assert.rejects(starting, /broker_stopping/);
    assert.equal((await stopped).released, true);
    assert.equal(native.lease, null);
  } finally { releaseAcquire(); await broker?.stop(); await journal?.close(); await rm(directory, { recursive: true, force: true }); }
});


test('stop revokes the lease without waiting for a stalled heartbeat reply', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'airicraft-effects-'));
  let journal, broker, releaseHeartbeat, renewal;
  const heartbeatAllowed = new Promise(resolve => { releaseHeartbeat = resolve; });
  const timers = { setInterval: callback => { renewal = callback; return 1; }, clearInterval: () => {} };
  try {
    journal = await EffectJournal.open(join(directory, 'effects.sqlite'));
    const native = new NativeFixture();
    broker = new EffectBroker({ native, journal, expectedWorld: 'fixture', timers });
    await broker.start();
    native.beforeHeartbeat = () => heartbeatAllowed;
    const renewing = renewal();
    const stopping = broker.stop();
    await Promise.resolve();
    assert.equal(native.lease, null);
    releaseHeartbeat();
    await renewing;
    assert.equal((await stopping).released, true);
  } finally { releaseHeartbeat(); await broker?.stop(); await journal?.close(); await rm(directory, { recursive: true, force: true }); }
});

test('stop during the durable append prevents late submission and settles the unsent intent', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'airicraft-effects-'));
  let journal, broker, allowRecord, recordEntered;
  const recorded = new Promise(resolve => { recordEntered = resolve; });
  const allowed = new Promise(resolve => { allowRecord = resolve; });
  try {
    journal = await EffectJournal.open(join(directory, 'effects.sqlite'));
    const native = new NativeFixture();
    const delayedJournal = {
      unfinished: () => journal.unfinished(), settle: (...args) => journal.settle(...args),
      record: async intent => { await journal.record(intent); recordEntered(); await allowed; }
    };
    broker = new EffectBroker({ native, journal: delayedJournal, expectedWorld: 'fixture' });
    await broker.start();
    const executing = broker.execute({ definition: 'farm', invocation: 'i', operation: 'transfer_container', arguments: { quantity: 6 } });
    await recorded;
    const stopping = broker.stop();
    allowRecord();
    const [execution, shutdown] = await Promise.allSettled([executing, stopping]);
    assert.equal(execution.status, 'rejected');
    assert.match(execution.reason.message, /broker_stopping/);
    assert.deepEqual(shutdown, { status: 'fulfilled', value: { released: true } });
    assert.equal(native.transferred, 0);
    assert.deepEqual(await journal.unfinished(), []);
    assert.equal((await journal.recent())[0].receipt.disposition, 'not_submitted');
  } finally { allowRecord(); await broker?.stop().catch(() => {}); await journal?.close(); await rm(directory, { recursive: true, force: true }); }
});

test('a lost revocation retains the lease obligation until a later confirmed release', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'airicraft-effects-'));
  let journal, broker;
  try {
    journal = await EffectJournal.open(join(directory, 'effects.sqlite'));
    const native = new NativeFixture();
    broker = new EffectBroker({ native, journal, expectedWorld: 'fixture' });
    await broker.start();
    native.beforeRelease = async () => { throw Error('transport_unavailable'); };
    assert.equal((await broker.stop()).released, false);
    assert.equal((await broker.stop()).released, false);
    assert.notEqual(native.lease, null);
    native.beforeRelease = async () => {};
    assert.equal((await broker.stop()).released, true);
    assert.equal(native.lease, null);
  } finally { await broker?.stop().catch(() => {}); await journal?.close(); await rm(directory, { recursive: true, force: true }); }
});

test('shutdown fences an in-flight submission and reconciles its never-admitted identity', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'airicraft-effects-'));
  let journal, broker, allowSubmit, submissionEntered;
  const entered = new Promise(resolve => { submissionEntered = resolve; });
  const allowed = new Promise(resolve => { allowSubmit = resolve; });
  try {
    journal = await EffectJournal.open(join(directory, 'effects.sqlite'));
    const native = new NativeFixture();
    native.beforeSubmit = async () => { submissionEntered(); await allowed; };
    broker = new EffectBroker({ native, journal, expectedWorld: 'fixture' });
    await broker.start();
    const executing = broker.execute({ definition: 'farm', invocation: 'i', operation: 'transfer_container', arguments: { quantity: 6 } });
    await entered;
    const stopping = broker.stop();
    allowSubmit();
    const [, shutdown] = await Promise.allSettled([executing, stopping]);
    assert.deepEqual(shutdown, { status: 'fulfilled', value: { released: true } });
    assert.equal(native.transferred, 0);
    assert.deepEqual(await journal.unfinished(), []);
    assert.equal((await journal.recent())[0].receipt.disposition, 'not_admitted');
  } finally { allowSubmit(); await broker?.stop().catch(() => {}); await journal?.close(); await rm(directory, { recursive: true, force: true }); }
});

test('failed startup cannot acknowledge release before discovering old journal obligations', async () => {
  for (const unavailable of [false, true]) {
    const directory = await mkdtemp(join(tmpdir(), 'airicraft-effects-'));
    let journal, broker;
    try {
      journal = await EffectJournal.open(join(directory, 'effects.sqlite'));
      await journal.record(unfinishedIntent());
      const native = unavailable ? { call: async () => { throw Error('transport_unavailable'); } } : new NativeFixture();
      broker = new EffectBroker({ native, journal, expectedWorld: 'different-world' });
      await assert.rejects(broker.start(), /transport_unavailable|world_not_ready/);
      assert.equal((await broker.stop()).released, false);
      assert.equal((await journal.unfinished()).length, 1);
    } finally { await broker?.stop(); await journal?.close(); await rm(directory, { recursive: true, force: true }); }
  }
});
