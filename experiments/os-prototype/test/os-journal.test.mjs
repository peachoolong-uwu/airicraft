import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { EffectJournal } from '../src/os/journal.mjs';

const intent = sequence => ({ id: { epoch: 'world-1', generation: 1, sequence }, payloadHash: 'a'.repeat(64),
  request: { operation: 'transfer_container', arguments: { quantity: 6 } }, definition: 'supply@digest', invocation: 'invocation:1' });

test('unfinished exact requests survive journal close and reopen for reconciliation', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'airicraft-journal-'));
  let journal;
  try {
    journal = await EffectJournal.open(join(directory, 'effects.sqlite'));
    const request = intent(1);
    await journal.record(request);
    assert.deepEqual((await journal.unfinished()).map(entry => entry.intent), [request]);
    await journal.close();
    journal = await EffectJournal.open(join(directory, 'effects.sqlite'));
    assert.deepEqual((await journal.unfinished()).map(entry => entry.intent), [request]);
    await journal.settle(request.id, { released: true, accountingComplete: true, transferred: 6 });
    assert.deepEqual(await journal.unfinished(), []);
    assert.equal((await journal.inspect(request.id)).receipt.transferred, 6);
  } finally { await journal?.close(); await rm(directory, { recursive: true, force: true }); }
});

test('retries preserve exact intent and unresolved receipts remain unfinished', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'airicraft-journal-'));
  let journal;
  try {
    journal = await EffectJournal.open(join(directory, 'effects.sqlite'));
    const request = intent(1);
    await journal.record(request);
    await journal.record(request);
    assert.equal((await journal.unfinished()).length, 1);
    await assert.rejects(journal.record({ ...request, request: { operation: 'different' } }), /journal_request_conflict/);
    await journal.settle(request.id, { released: false, accountingComplete: true, transferred: 2 });
    assert.equal((await journal.unfinished()).length, 1);
    await journal.settle(request.id, { released: true, accountingComplete: false, transferred: 2 });
    assert.equal((await journal.unfinished()).length, 1);
    const receipt = { released: true, accountingComplete: true, transferred: 2 };
    await journal.settle(request.id, receipt);
    await journal.settle(request.id, receipt);
    await assert.rejects(journal.settle(request.id, { ...receipt, transferred: 6 }), /journal_outcome_conflict/);
    assert.equal((await journal.inspect(request.id)).receipt.transferred, 2);
  } finally { await journal?.close(); await rm(directory, { recursive: true, force: true }); }
});

test('the journal refuses a 1025th unfinished effect and retains 256 recent settlements', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'airicraft-journal-'));
  let journal;
  try {
    journal = await EffectJournal.open(join(directory, 'effects.sqlite'));
    for (let sequence = 1; sequence <= 1024; sequence++) await journal.record(intent(sequence));
    await assert.rejects(journal.record(intent(1025)), /journal_capacity/);
    assert.equal((await journal.unfinished()).length, 1024);
    for (let sequence = 1; sequence <= 258; sequence++)
      await journal.settle(intent(sequence).id, { released: true, accountingComplete: true, transferred: 0 });
    await journal.record(intent(1025));
    assert.equal((await journal.unfinished()).length, 767);
    assert.equal((await journal.recent()).length, 256);
    assert.equal(await journal.inspect(intent(1).id), null);
    assert.equal((await journal.inspect(intent(258).id)).settled, true);
    assert.equal((await journal.inspect(intent(1025).id)).settled, false);
  } finally { await journal?.close(); await rm(directory, { recursive: true, force: true }); }
});


test('an acknowledged unfinished request survives abrupt host process exit', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'airicraft-journal-'));
  const path = join(directory, 'effects.sqlite');
  let journal, child;
  try {
    const source = `import { EffectJournal } from ${JSON.stringify(new URL('../src/os/journal.mjs', import.meta.url).href)};
      const journal = await EffectJournal.open(${JSON.stringify(path)});
      await journal.record(${JSON.stringify(intent(1))});
      process.stdout.write('committed\\n');`;
    child = spawn(process.execPath, ['--input-type=module', '-e', source], { stdio: ['ignore', 'pipe', 'pipe'] });
    let stderr = '';
    child.stderr.on('data', data => { stderr = (stderr + data).slice(-4096); });
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(Error('child_commit_timeout')), 5000);
      child.once('error', error => { clearTimeout(timer); reject(error); });
      child.once('exit', code => { clearTimeout(timer); reject(Error(`child_exited:${code}:${stderr}`)); });
      child.stdout.once('data', data => { clearTimeout(timer); data.toString().includes('committed') ? resolve() : reject(Error('unexpected_child_output')); });
    });
    const exited = once(child, 'exit');
    child.kill('SIGKILL');
    await exited;
    journal = await EffectJournal.open(path);
    assert.deepEqual((await journal.unfinished()).map(entry => entry.intent), [intent(1)]);
    await assert.rejects(EffectJournal.open(join(directory, 'missing', 'effects.sqlite')));
  } finally {
    if (child?.exitCode === null && child?.signalCode === null) child.kill('SIGKILL');
    await journal?.close();
    await rm(directory, { recursive: true, force: true });
  }
});

test('malformed identities and release evidence are rejected before changing the journal', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'airicraft-journal-'));
  let journal;
  try {
    journal = await EffectJournal.open(join(directory, 'effects.sqlite'));
    await assert.rejects(async () => journal.record({ ...intent(1), id: { epoch: 'world-1', generation: 1, sequence: 0 } }), /invalid_journal_intent/);
    await assert.rejects(async () => journal.record({ ...intent(1), payloadHash: 'not-a-hash' }), /invalid_journal_intent/);
    assert.deepEqual(await journal.unfinished(), []);
    await journal.record(intent(1));
    await assert.rejects(async () => journal.settle(intent(1).id, { released: 'yes', accountingComplete: true }), /invalid_release_evidence/);
    assert.equal((await journal.unfinished()).length, 1);
  } finally { await journal?.close(); await rm(directory, { recursive: true, force: true }); }
});
