import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DefinitionLibrary } from '../src/os/library.mjs';
import { InvocationBroker } from '../src/os/broker.mjs';
import { RunnerPool } from '../src/os/runners.mjs';
import { LibraryValidator } from '../src/os/validation.mjs';

const definition = (overrides = {}) => ({ schemaVersion: 1, kind: 'behavior', name: 'bounded-example', description: 'validation fixture',
  tags: [], capabilities: [], environment: {}, dependencies: {}, mode: 'generator', inputContract: { type: 'integer' }, outputContract: { type: 'integer' },
  source: 'function* main(os, input) { const view = yield os.observe({}); return input + view.count; }',
  examples: [{ input: 2, responses: [{ count: 3 }], expected: [{ done: false, value: { kind: 'observe', query: {} } }, { done: true, value: 5 }] }], ...overrides });

async function fixture(run) {
  const directory = await mkdtemp(join(tmpdir(), 'airicraft-validation-'));
  const library = await DefinitionLibrary.open(directory), invocations = new InvocationBroker(), runners = new RunnerPool({ invocations });
  const validator = new LibraryValidator({ library, invocations, runners, environment: {}, grants: [] });
  const candidate = value => library.candidate(value, { reason: 'test', hypothesis: 'the declared example matches bounded execution' });
  try { await run({ library, invocations, runners, validator, candidate }); }
  finally { await runners.close(); await rm(directory, { recursive: true, force: true }); }
}

test('validation runs exact examples through supervised VMs and retains revision-specific evidence', async () => fixture(async ({ library, invocations, validator, candidate }) => {
  const revision = await candidate(definition());
  const result = await validator.validate(revision.digest);
  assert.equal(result.passed, true);
  assert.equal(result.scope, 'deterministic');
  assert.equal(result.liveQualified, false);
  assert.equal((await library.get(revision.digest)).state.validated, true);
  assert.equal(invocations.capacity().roots, 0);
  const wrong = await candidate(definition({ source: 'function* main(os, input) { const view = yield os.observe({}); return input - view.count; }' }));
  const failed = await validator.validate(wrong.digest);
  assert.equal(failed.passed, false);
  assert.match(failed.reason, /example_mismatch/);
  assert.equal((await library.get(wrong.digest)).state.phase, 'retired');
  assert.equal((await library.get(revision.digest)).state.validated, true);
}));

test('declared output contracts are checked even when an example expects the wrong type', async () => fixture(async ({ invocations, validator, candidate }) => {
  const revision = await candidate(definition({ source: 'function* main() { return "wrong"; }', examples: [{ input: 2, responses: [], expected: [{ done: true, value: 'wrong' }] }] }));
  const result = await validator.validate(revision.digest);
  assert.equal(result.passed, false);
  assert.match(result.reason, /invalid_result/);
  assert.deepEqual(invocations.capacity(), { roots: 0, live: 0, retainedChildren: 0 });
}));

test('offers use fresh example views and validation never admits the declared world effects', async () => fixture(async ({ invocations, validator, candidate }) => {
  const work = { kind: 'work', operation: 'crop.plant', arguments: { x: 4 }, context: null };
  const revision = await candidate(definition({ mode: 'offers', source: 'function offers(os, input, view) { return view.ready ? [os.work("crop.plant", {x: input})] : []; }',
    outputContract: { type: 'array', items: true },
    examples: [{ input: 4, responses: [{ ready: false }, { ready: true }], expected: [[], [work]] }] }));
  assert.equal((await validator.validate(revision.digest)).passed, true);
  assert.equal(invocations.activity(), null);
  assert.equal(invocations.capacity().roots, 0);
}));

test('offers cannot validate with an output contract that rejects their example result', async () => fixture(async ({ validator, candidate }) => {
  const revision = await candidate(definition({ mode: 'offers', source: 'function offers() { return []; }',
    examples: [{ input: 2, responses: [null], expected: [[]] }] }));
  const evidence = await validator.validate(revision.digest);
  assert.equal(evidence.passed, false);
  assert.match(evidence.reason, /invalid_result/);
}));

test('invalid initialization retires the candidate without stopping an independent root', async () => fixture(async ({ invocations, validator, candidate }) => {
  const other = invocations.install({ definition: 'unrelated', grants: [] });
  const revision = await candidate(definition({ source: 'function* main( {' }));
  const result = await validator.validate(revision.digest);
  assert.equal(result.passed, false);
  assert.equal(invocations.inspect(other).phase, 'running');
  assert.equal(invocations.capacity().roots, 1);
  invocations.cancel(other); invocations.uninstall(other);
}));

test('worker validation checks result schemas and records that real inference was not exercised', async () => fixture(async ({ validator, candidate }) => {
  const fallback = await candidate(definition({ source: 'function* main(os, input) { return input; }', examples: [{ input: 2, responses: [], expected: [{ done: true, value: 2 }] }] }));
  assert.equal((await validator.validate(fallback.digest)).passed, true);
  const worker = await candidate({ schemaVersion: 1, kind: 'worker', name: 'choose', description: 'typed worker fixture', tags: [], capabilities: [], environment: {},
    dependencies: { fallback: fallback.digest }, inputContract: { type: 'integer' }, outputContract: { type: 'integer' }, prompt: 'Choose a permitted number.',
    profile: 'not-configured', fallback: 'fallback', examples: [{ input: 2, result: 3 }] });
  const result = await validator.validate(worker.digest);
  assert.equal(result.passed, true);
  assert.equal(result.inferenceQualified, false);
  const bad = await candidate({ ...worker.definition, examples: [{ input: 2, result: 'bad' }] });
  assert.equal((await validator.validate(bad.digest)).passed, false);
}));
