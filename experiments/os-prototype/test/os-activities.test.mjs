import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { InvocationBroker } from '../src/os/broker.mjs';
import { ResourceLedger } from '../src/os/ledger.mjs';
import { EffectJournal } from '../src/os/journal.mjs';
import { EffectBroker } from '../src/os/effects.mjs';
import { ActivityCoordinator } from '../src/os/activities.mjs';
import { ContainerTransfer } from '../src/os/container-transfer.mjs';

class NativeContainer {
  lease = null;
  receipt = null;
  submissions = 0;
  cancellations = 0;
  quantity = 4;
  capture = 0;
  beforeReply = async () => {};
  async call(name, args = {}) {
    let result = {};
    if (name === 'os_observe') result.frame = { epoch: 'world', captureId: `capture-${++this.capture}`, world: { worldId: 'fixture', dimension: 'overworld', alive: true },
      facts: { supportedItems: ['minecraft:string'], window: { open: true, windowId: 'home-window', syncId: 1, cursor: { count: 0 }, slots: [
        { id: 0, container: true, itemId: 'minecraft:string', variant: '', count: this.quantity, maxCount: 64 },
        { id: 1, container: false, itemId: '', variant: '', count: 0, maxCount: 64 }
      ] } } };
    else if (name === 'os_lease') {
      if (args.action === 'acquire') this.lease = { epoch: 'world', generation: 1, hostId: args.hostId };
      if (args.action === 'release') this.lease = null;
      result.lease = this.lease;
    } else if (name === 'os_submit') {
      this.submissions++;
      this.receipt = { id: args.id, state: 'ACCEPTED', released: false, basis: { operation: args.operation, captureId: args.captureId, payloadHash: args.payloadHash }, effects: {} };
      result.receipt = this.receipt;
    } else if (name === 'os_inspect') result.receipt = this.receipt;
    else if (name === 'os_cancel') { this.cancellations++; this.receipt.state = 'RECONCILING'; result.receipt = this.receipt; }
    else throw Error('unknown_native_method');
    await this.beforeReply(name);
    return { status: 'ok', ...result };
  }
  progress(quantity, transferred, released, state = 'SUCCEEDED') {
    Object.assign(this.receipt, { state: released ? state : 'RUNNING', released,
      effects: { quantity, transferred, remaining: quantity - transferred, accountingComplete: true, releaseEvidence: { verified: released } } });
  }
}

test('the integrated admission path enforces stock protection and owns the player until journalled release', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'airicraft-activities-'));
  let journal, effects;
  try {
    journal = await EffectJournal.open(join(directory, 'effects.sqlite'));
    const native = new NativeContainer(), invocations = new InvocationBroker(), ledger = new ResourceLedger();
    const operation = new ContainerTransfer({ scope: 'home', windowId: 'home-window' });
    const root = invocations.install({ definition: 'rods@digest', grants: ['container:home'] });
    const other = invocations.install({ definition: 'other@digest', grants: ['container:home'] });
    const source = operation.resource('container', 'minecraft:string', '');
    ledger.target(root, source, 2); ledger.target(other, source, 2);
    effects = new EffectBroker({ native, journal, expectedWorld: 'fixture' });
    await effects.start();
    const activities = new ActivityCoordinator({ invocations, ledger, effects, operations: { transfer: operation } });
    const request = { owner: root, operation: 'transfer', arguments: { direction: 'withdraw', itemId: 'minecraft:string', quantity: 3 } };
    await assert.rejects(activities.admit(request), /resource_unavailable/);
    assert.equal(native.submissions, 0);
    assert.equal(invocations.activity(), null);
    request.arguments.quantity = 2;
    await activities.admit(request);
    invocations.returned(root, 'delivery completed');
    assert.equal(invocations.inspect(root).outcome, null);
    await assert.rejects(activities.admit({ ...request, owner: other }), /player_owned/);
    native.progress(2, 2, false);
    await activities.poll();
    assert.equal(invocations.inspect(root).outcome, null);
    assert.equal((await journal.unfinished()).length, 1);
    native.progress(2, 2, true);
    await activities.poll();
    assert.equal(invocations.inspect(root).outcome.status, 'success');
    assert.equal(invocations.activity(), null);
    assert.deepEqual(await journal.unfinished(), []);
    assert.equal(native.submissions, 1);
  } finally { await effects?.stop(); await journal?.close(); await rm(directory, { recursive: true, force: true }); }
});

test('shared deliveries use one native attempt and cancellation drains only after the last subscriber withdraws', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'airicraft-activities-'));
  let journal, effects;
  try {
    journal = await EffectJournal.open(join(directory, 'effects.sqlite'));
    const native = new NativeContainer(), invocations = new InvocationBroker(), ledger = new ResourceLedger();
    const operation = new ContainerTransfer({ scope: 'home', windowId: 'home-window' });
    const first = invocations.install({ definition: 'rods-A', grants: ['container:home'] });
    const second = invocations.install({ definition: 'rods-B', grants: ['container:home'] });
    const destination = operation.resource('player', 'minecraft:string', '');
    ledger.observe({ epoch: 'world', revision: 'initial', stocks: {}, assets: {}, capacities: {}, targets: [] });
    ledger.requestDelivery(1, { consumer: first, resource: destination, quantity: 2, methods: ['transfer'] });
    ledger.requestDelivery(2, { consumer: second, resource: destination, quantity: 2, methods: ['transfer'] });
    effects = new EffectBroker({ native, journal, expectedWorld: 'fixture' });
    await effects.start();
    const activities = new ActivityCoordinator({ invocations, ledger, effects, operations: { transfer: operation } });
    await activities.admit({ owner: first, operation: 'transfer', arguments: { direction: 'withdraw', itemId: 'minecraft:string', quantity: 4 },
      deliveries: [{ id: 1, owner: first, quantity: 2 }, { id: 2, owner: second, quantity: 2 }] });
    invocations.cancel(first);
    await activities.poll();
    assert.equal(native.cancellations, 0);
    assert.equal(ledger.delivery(1).state, 'cancelled');
    native.progress(4, 2, false);
    await activities.poll();
    assert.equal(ledger.delivery(1).credited, 0);
    assert.equal(ledger.delivery(2).credited, 2);
    assert.equal(invocations.inspect(second).outcome, null);
    invocations.cancel(second);
    await activities.poll();
    assert.equal(native.cancellations, 1);
    assert.equal(invocations.inspect(second).outcome, null);
    native.progress(4, 2, true, 'CANCELLED');
    await activities.poll();
    assert.equal(invocations.inspect(second).outcome.status, 'cancelled');
    assert.equal(invocations.activity(), null);
    assert.equal(native.submissions, 1);
    assert.deepEqual(await journal.unfinished(), []);
  } finally { await effects?.stop(); await journal?.close(); await rm(directory, { recursive: true, force: true }); }
});

test('a journal failure during progress polling revokes native authority and blocks replacement work', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'airicraft-activities-'));
  let journal, effects;
  try {
    journal = await EffectJournal.open(join(directory, 'effects.sqlite'));
    let broken = false;
    const storage = { unfinished: () => journal.unfinished(), record: intent => journal.record(intent), settle: (...args) => {
      if (broken) return Promise.reject(Error('journal_writer_failed'));
      return journal.settle(...args);
    } };
    const native = new NativeContainer(), invocations = new InvocationBroker(), ledger = new ResourceLedger();
    const root = invocations.install({ definition: 'rods', grants: ['container:home'] });
    effects = new EffectBroker({ native, journal: storage, expectedWorld: 'fixture' });
    await effects.start();
    const activities = new ActivityCoordinator({ invocations, ledger, effects,
      operations: { transfer: new ContainerTransfer({ scope: 'home', windowId: 'home-window' }) } });
    const request = { owner: root, operation: 'transfer', arguments: { direction: 'withdraw', itemId: 'minecraft:string', quantity: 2 } };
    await activities.admit(request);
    native.progress(2, 1, false);
    broken = true;
    await assert.rejects(activities.poll(), /journal_writer_failed/);
    assert.equal(native.lease, null);
    await assert.rejects(activities.admit(request), /coordinator_failed/);
    assert.equal(invocations.inspect(root).outcome, null);
  } finally { await effects?.stop(); await journal?.close(); await rm(directory, { recursive: true, force: true }); }
});

test('cancellation during an admission or inspection reply is reconciled before assigning delivery credit', async () => {
  for (const blockedReply of ['os_submit', 'os_inspect']) {
    const directory = await mkdtemp(join(tmpdir(), 'airicraft-activities-'));
    let journal, effects, allowReply, replyEntered;
    const entered = new Promise(resolve => { replyEntered = resolve; });
    const allowed = new Promise(resolve => { allowReply = resolve; });
    try {
      journal = await EffectJournal.open(join(directory, 'effects.sqlite'));
      const native = new NativeContainer(), invocations = new InvocationBroker(), ledger = new ResourceLedger();
      const operation = new ContainerTransfer({ scope: 'home', windowId: 'home-window' });
      const a = invocations.install({ definition: 'A', grants: ['container:home'] });
      const b = invocations.install({ definition: 'B', grants: ['container:home'] });
      ledger.observe({ epoch: 'world', revision: 'initial', stocks: {}, assets: {}, capacities: {}, targets: [] });
      for (const [id, consumer] of [[1, a], [2, b]]) ledger.requestDelivery(id, {
        consumer, resource: operation.resource('player', 'minecraft:string', ''), quantity: 2, methods: ['transfer'] });
      effects = new EffectBroker({ native, journal, expectedWorld: 'fixture' });
      await effects.start();
      const activities = new ActivityCoordinator({ invocations, ledger, effects, operations: { transfer: operation } });
      native.beforeReply = async name => { if (name === blockedReply) { replyEntered(); await allowed; } };
      const admission = activities.admit({ owner: a, operation: 'transfer', arguments: { direction: 'withdraw', itemId: 'minecraft:string', quantity: 4 },
        deliveries: [{ id: 1, owner: a, quantity: 2 }, { id: 2, owner: b, quantity: 2 }] });
      if (blockedReply === 'os_inspect') await admission;
      const response = blockedReply === 'os_submit' ? admission : activities.poll();
      await entered;
      invocations.cancel(a);
      native.progress(4, 4, true);
      allowReply();
      await response;
      assert.equal(ledger.delivery(1).credited, 0);
      assert.equal(ledger.delivery(1).state, 'cancelled');
      assert.equal(ledger.delivery(2).credited, 2);
      assert.equal(invocations.activity(), null);
    } finally { allowReply(); await effects?.stop(); await journal?.close(); await rm(directory, { recursive: true, force: true }); }
  }
});

test('a queryable rejected admission releases its unused claim and permits a later fresh attempt', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'airicraft-activities-'));
  let journal, effects;
  try {
    journal = await EffectJournal.open(join(directory, 'effects.sqlite'));
    const native = new NativeContainer(), invocations = new InvocationBroker(), ledger = new ResourceLedger();
    const root = invocations.install({ definition: 'rods', grants: ['container:home'] });
    effects = new EffectBroker({ native, journal, expectedWorld: 'fixture' });
    await effects.start();
    const activities = new ActivityCoordinator({ invocations, ledger, effects,
      operations: { transfer: new ContainerTransfer({ scope: 'home', windowId: 'home-window' }) } });
    const request = { owner: root, operation: 'transfer', arguments: { direction: 'withdraw', itemId: 'minecraft:string', quantity: 2 } };
    native.beforeReply = async name => {
      if (name !== 'os_submit') return;
      Object.assign(native.receipt, { state: 'FAILED', phase: 'admission_rejected', reason: 'observation_stale', released: true,
        effects: { admitted: false, accountingComplete: true, releaseEvidence: { verified: true, source: 'admission_not_started' } } });
      throw Error('lost_rejection_response');
    };
    const rejected = await activities.admit(request);
    assert.equal(rejected.receipt.reason, 'observation_stale');
    assert.equal(rejected.evidence.released, true);
    assert.deepEqual(rejected.evidence.consumed, {});
    assert.equal(invocations.activity(), null);
    assert.deepEqual(await journal.unfinished(), []);
    native.beforeReply = async () => {};
    await activities.admit(request);
    native.progress(2, 2, true);
    await activities.poll();
    assert.equal(native.submissions, 2);
    assert.deepEqual(await journal.unfinished(), []);
  } finally { await effects?.stop(); await journal?.close(); await rm(directory, { recursive: true, force: true }); }
});

test('receipt field order does not change the identity credited for a partially delivered supply', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'airicraft-activities-'));
  let journal, effects;
  try {
    journal = await EffectJournal.open(join(directory, 'effects.sqlite'));
    const native = new NativeContainer(), invocations = new InvocationBroker(), ledger = new ResourceLedger();
    const operation = new ContainerTransfer({ scope: 'home', windowId: 'home-window' });
    const root = invocations.install({ definition: 'rods', grants: ['container:home'] });
    ledger.observe({ epoch: 'world', revision: 'initial', stocks: {}, assets: {}, capacities: {}, targets: [] });
    ledger.requestDelivery(1, { consumer: root, resource: operation.resource('player', 'minecraft:string', ''), quantity: 4, methods: ['transfer'] });
    effects = new EffectBroker({ native, journal, expectedWorld: 'fixture' });
    await effects.start();
    const activities = new ActivityCoordinator({ invocations, ledger, effects, operations: { transfer: operation } });
    await activities.admit({ owner: root, operation: 'transfer', arguments: { direction: 'withdraw', itemId: 'minecraft:string', quantity: 4 },
      deliveries: [{ id: 1, owner: root, quantity: 4 }] });
    native.progress(4, 2, false);
    await activities.poll();
    const { epoch, generation, sequence } = native.receipt.id;
    native.receipt.id = { sequence, generation, epoch };
    native.progress(4, 4, true);
    await activities.poll();
    assert.equal(ledger.delivery(1).credited, 4);
    assert.equal(ledger.delivery(1).state, 'fulfilled');
    assert.equal(native.submissions, 1);
    assert.equal(invocations.activity(), null);
    assert.deepEqual(await journal.unfinished(), []);
  } finally { await effects?.stop(); await journal?.close(); await rm(directory, { recursive: true, force: true }); }
});

test('a granted subscriber can join spare output in flight and keep shared work alive', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'airicraft-activities-'));
  let journal, effects;
  try {
    journal = await EffectJournal.open(join(directory, 'effects.sqlite'));
    const native = new NativeContainer(), invocations = new InvocationBroker(), ledger = new ResourceLedger();
    const operation = new ContainerTransfer({ scope: 'home', windowId: 'home-window' });
    const first = invocations.install({ definition: 'A', grants: ['container:home'] });
    const second = invocations.install({ definition: 'B', grants: ['container:home'] });
    const denied = invocations.install({ definition: 'C', grants: [] });
    ledger.observe({ epoch: 'world', revision: 'initial', stocks: {}, assets: {}, capacities: {}, targets: [] });
    for (const [id, consumer] of [[1, first], [2, second], [3, denied]]) ledger.requestDelivery(id, {
      consumer, resource: operation.resource('player', 'minecraft:string', ''), quantity: 2, methods: ['transfer'] });
    effects = new EffectBroker({ native, journal, expectedWorld: 'fixture' });
    await effects.start();
    const activities = new ActivityCoordinator({ invocations, ledger, effects, operations: { transfer: operation } });
    const { activityId } = await activities.admit({ owner: first, operation: 'transfer',
      arguments: { direction: 'withdraw', itemId: 'minecraft:string', quantity: 4 }, deliveries: [{ id: 1, owner: first, quantity: 2 }] });
    native.progress(4, 2, false);
    await activities.poll();
    assert.throws(() => activities.join({ activityId, owner: denied, deliveryId: 3, quantity: 2 }), /operation_not_granted/);
    const request = { activityId, owner: second, deliveryId: 2, quantity: 2 };
    activities.join(request);
    activities.join(request);
    assert.throws(() => activities.join({ ...request, quantity: 1 }), /delivery_join_conflict/);
    assert.equal(ledger.delivery(2).credited, 0);
    invocations.cancel(first);
    await activities.poll();
    assert.equal(native.cancellations, 0);
    native.progress(4, 4, true);
    await activities.poll();
    assert.equal(ledger.delivery(1).credited, 2);
    assert.equal(ledger.delivery(2).credited, 2);
    assert.equal(ledger.delivery(3).credited, 0);
    assert.equal(invocations.activity(), null);
    assert.equal(native.submissions, 1);
  } finally { await effects?.stop(); await journal?.close(); await rm(directory, { recursive: true, force: true }); }
});

test('journal failure revokes the lease even when failing one subscriber retires its sibling handle', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'airicraft-activities-'));
  let journal, effects;
  try {
    journal = await EffectJournal.open(join(directory, 'effects.sqlite'));
    let broken = false;
    const storage = { unfinished: () => journal.unfinished(), record: intent => journal.record(intent), settle: (...args) => {
      if (broken) return Promise.reject(Error('journal_writer_failed'));
      return journal.settle(...args);
    } };
    const native = new NativeContainer(), invocations = new InvocationBroker(), ledger = new ResourceLedger();
    const spec = { definition: 'rods', grants: ['container:home'] };
    const root = invocations.install(spec), other = invocations.install(spec);
    const first = invocations.spawn(root, spec).id, second = invocations.spawn(root, spec).id;
    const operation = new ContainerTransfer({ scope: 'home', windowId: 'home-window' });
    ledger.observe({ epoch: 'world', revision: 'initial', stocks: {}, assets: {}, capacities: {}, targets: [] });
    for (const [id, consumer] of [[1, root], [2, root], [3, other]]) ledger.requestDelivery(id, {
      consumer, resource: operation.resource('player', 'minecraft:string', ''), quantity: 1, methods: ['transfer'] });
    effects = new EffectBroker({ native, journal: storage, expectedWorld: 'fixture' });
    await effects.start();
    const activities = new ActivityCoordinator({ invocations, ledger, effects, operations: { transfer: operation } });
    await activities.admit({ owner: first, operation: 'transfer', arguments: { direction: 'withdraw', itemId: 'minecraft:string', quantity: 3 },
      deliveries: [{ id: 1, owner: first, quantity: 1 }, { id: 2, owner: second, quantity: 1 }, { id: 3, owner: other, quantity: 1 }] });
    broken = true;
    await assert.rejects(activities.poll(), /journal_writer_failed/);
    assert.equal(native.lease, null);
    assert.equal(invocations.inspect(other).outcome, null);
    assert.equal(effects.state().unresolved, true);
  } finally { await effects?.stop(); await journal?.close(); await rm(directory, { recursive: true, force: true }); }
});

test('native revocation prevents late subscribers from claiming output while cleanup is unresolved', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'airicraft-activities-'));
  let journal, effects;
  try {
    journal = await EffectJournal.open(join(directory, 'effects.sqlite'));
    const native = new NativeContainer(), invocations = new InvocationBroker(), ledger = new ResourceLedger();
    const operation = new ContainerTransfer({ scope: 'home', windowId: 'home-window' });
    const first = invocations.install({ definition: 'A', grants: ['container:home'] });
    const second = invocations.install({ definition: 'B', grants: ['container:home'] });
    ledger.observe({ epoch: 'world', revision: 'initial', stocks: {}, assets: {}, capacities: {}, targets: [] });
    for (const [id, consumer] of [[1, first], [2, second]]) ledger.requestDelivery(id, {
      consumer, resource: operation.resource('player', 'minecraft:string', ''), quantity: 2, methods: ['transfer'] });
    effects = new EffectBroker({ native, journal, expectedWorld: 'fixture' });
    await effects.start();
    const activities = new ActivityCoordinator({ invocations, ledger, effects, operations: { transfer: operation } });
    const { activityId } = await activities.admit({ owner: first, operation: 'transfer',
      arguments: { direction: 'withdraw', itemId: 'minecraft:string', quantity: 4 }, deliveries: [{ id: 1, owner: first, quantity: 2 }] });
    native.progress(4, 1, false);
    Object.assign(native.receipt, { state: 'RECONCILING', phase: 'cancel_requested', reason: 'lease_expired' });
    await activities.poll();
    assert.throws(() => activities.join({ activityId, owner: second, deliveryId: 2, quantity: 2 }), /supply_stopping/);
    assert.equal(ledger.delivery(2).credited, 0);
    assert.equal(invocations.activity().subscribers.includes(second), false);
    native.progress(4, 1, true, 'CANCELLED');
    await activities.poll();
    assert.equal(ledger.delivery(1).credited, 1);
    assert.equal(invocations.activity(), null);
  } finally { await effects?.stop(); await journal?.close(); await rm(directory, { recursive: true, force: true }); }
});
