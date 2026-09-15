import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DefinitionLibrary, resolveClosure } from '../src/os/library.mjs';

const behavior = (name, overrides = {}) => ({ schemaVersion: 1, kind: 'behavior', name, description: 'fixture definition', tags: ['fixture'],
  capabilities: [], environment: { minecraft: '1.21.8' }, dependencies: {}, inputContract: true, outputContract: { type: 'integer' },
  mode: 'generator', source: 'function* main() { return 1; }', examples: [{ input: null, responses: [], expected: [{ done: true, value: 1 }] }], ...overrides });
const proposal = { reason: 'fixture', hypothesis: 'a bounded reusable definition' };
const validation = { passed: true, checks: ['syntax', 'examples'], artifacts: [] };

test('definitions have immutable content identities including exact dependencies and worker prompts', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'airicraft-library-'));
  try {
    const library = await DefinitionLibrary.open(directory), spec = behavior('one');
    const first = await library.candidate(spec, proposal);
    const reordered = Object.fromEntries(Object.entries(spec).reverse());
    assert.equal((await library.candidate(reordered, proposal)).digest, first.digest);
    spec.source = 'function* main() { return 2; }';
    assert.notEqual((await library.candidate(spec, proposal)).digest, first.digest);
    assert.equal((await library.get(first.digest)).definition.source, behavior('one').source);
    const worker = { schemaVersion: 1, kind: 'worker', name: 'choose', description: 'pick a permitted option', tags: [], capabilities: [],
      environment: {}, dependencies: { fallback: first.digest }, inputContract: true, outputContract: { type: 'integer' },
      prompt: 'Choose one permitted option.', profile: 'text-worker', fallback: 'fallback', examples: [] };
    const original = await library.candidate(worker, proposal);
    assert.notEqual((await library.candidate({ ...worker, prompt: 'Choose the safest permitted option.' }, proposal)).digest, original.digest);
    assert.equal((await library.list({ name: 'one' })).items.length, 2);
    assert.equal((await library.list({ name: 'one', environment: {} })).items.length, 0);
    assert.equal((await library.list({ name: 'one', environment: { minecraft: '1.21.8' } })).items.length, 2);
    await assert.rejects(library.list({ environment: null }), /invalid_library_query/);
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test('dependency closure requires validated exact revisions, compatible environment and sufficient grants', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'airicraft-library-'));
  try {
    const library = await DefinitionLibrary.open(directory);
    const child = await library.candidate(behavior('child', { capabilities: ['container:home'] }), proposal);
    const parent = await library.candidate(behavior('parent', { capabilities: ['container:home'], dependencies: { child: child.digest } }), proposal);
    await library.recordValidation(parent.digest, validation);
    await assert.rejects(library.resolve(parent.digest, { grants: ['container:home'], environment: { minecraft: '1.21.8' } }), /definition_unvalidated/);
    await library.recordValidation(child.digest, validation);
    await assert.rejects(library.resolve(parent.digest, { grants: [], environment: { minecraft: '1.21.8' } }), /capability_missing/);
    await assert.rejects(library.resolve(parent.digest, { grants: ['container:home'], environment: { minecraft: 'other' } }), /environment_incompatible/);
    const resolved = await library.resolve(parent.digest, { grants: ['container:home'], environment: { minecraft: '1.21.8' } });
    assert.deepEqual(resolved.revisions.map(item => item.digest), [child.digest, parent.digest]);
    assert.equal(resolved.root.digest, parent.digest);
    assert.equal((await library.get(parent.digest)).definition.dependencies.child, child.digest);
    const underdeclared = await library.candidate(behavior('underdeclared', { dependencies: { child: child.digest } }), proposal);
    await assert.rejects(library.resolve(underdeclared.digest, { grants: ['container:home'], environment: { minecraft: '1.21.8' }, allowCandidate: true }), /dependency_capability_undeclared/);
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test('cycles and unavailable dependency locks include a concrete path', async () => {
  const a = 'sha256:' + 'a'.repeat(64), b = 'sha256:' + 'b'.repeat(64);
  const records = new Map([[a, { digest: a, definition: behavior('a', { dependencies: { b } }), state: { validated: true } }],
    [b, { digest: b, definition: behavior('b', { dependencies: { a } }), state: { validated: true } }]]);
  await assert.rejects(resolveClosure(a, { load: async id => records.get(id), grants: [], environment: { minecraft: '1.21.8' } }),
    error => error.message === 'dependency_cycle' && error.path.join(' -> ') === [a, b, a].join(' -> '));
  records.delete(b);
  await assert.rejects(resolveClosure(a, { load: async id => records.get(id), grants: [], environment: { minecraft: '1.21.8' } }),
    error => error.message === 'dependency_unavailable' && error.path.at(-1) === b);
});

test('multiple installations pin one revision and retirement preserves validated rollback content', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'airicraft-library-'));
  try {
    const library = await DefinitionLibrary.open(directory);
    const revision = await library.candidate(behavior('reusable'), proposal);
    const binding = { runId: 'run-one', rootId: 'invocation:1', inputDigest: 'sha256:' + 'c'.repeat(64), grants: [] };
    await assert.rejects(library.recordInstall(revision.digest, 'site-a', binding), /definition_unvalidated/);
    await library.recordValidation(revision.digest, validation);
    await library.recordInstall(revision.digest, 'site-a', binding);
    await library.recordInstall(revision.digest, 'site-b', { ...binding, rootId: 'invocation:2' });
    await library.recordRetired(revision.digest, 'site-a', { status: 'cancelled', released: true });
    const afterRetirement = (await library.get(revision.digest)).state.records;
    await library.recordRetired(revision.digest, 'site-a', { released: true, status: 'cancelled' });
    assert.equal((await library.get(revision.digest)).state.records, afterRetirement);
    await assert.rejects(library.recordRetired(revision.digest, 'site-a', { status: 'success', released: true }), /retirement_conflict/);
    assert.equal((await library.get(revision.digest)).state.phase, 'installed');
    await library.recordRetired(revision.digest, 'site-b', { status: 'success', released: true });
    assert.equal((await library.get(revision.digest)).state.phase, 'retired');
    await library.recordInstall(revision.digest, 'site-c', { ...binding, runId: 'run-two' });
    assert.equal((await library.get(revision.digest)).state.installations.length, 1);
    const reopened = await DefinitionLibrary.open(directory);
    assert.equal((await reopened.get(revision.digest)).definition.source, behavior('reusable').source);
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test('content tampering and corrupt lifecycle records fail closed without accepting a different revision', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'airicraft-library-'));
  try {
    const library = await DefinitionLibrary.open(directory), revision = await library.candidate(behavior('sealed'), proposal);
    const path = join(directory, 'revisions', revision.digest.slice(7), 'definition.json');
    const original = await readFile(path);
    await writeFile(path, JSON.stringify(behavior('tampered')));
    await assert.rejects(library.get(revision.digest), /definition_integrity/);
    await writeFile(path, original);
    await writeFile(join(directory, 'revisions', revision.digest.slice(7), 'records', '000002.json'), '{');
    await assert.rejects(library.get(revision.digest), /library_record_invalid/);
    await assert.rejects(library.get('../elsewhere'), /invalid_definition_digest/);
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test('installation records reserve bounded space for every active binding to retire', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'airicraft-library-capacity-'));
  try {
    const library = await DefinitionLibrary.open(directory), revision = await library.candidate(behavior('bounded-history'), proposal);
    await library.recordValidation(revision.digest, validation);
    const binding = { runId: 'run', rootId: 'invocation:1', inputDigest: 'sha256:' + 'd'.repeat(64), grants: [] };
    const active = [];
    for (let index = 0; index < 64; index++) {
      try { await library.recordInstall(revision.digest, `site-${index}`, binding); active.push(`site-${index}`); }
      catch (error) { assert.equal(error.message, 'library_record_capacity'); break; }
    }
    assert.equal(active.length, 63);
    for (const id of active) await library.recordRetired(revision.digest, id, { status: 'success', released: true });
    const state = (await library.get(revision.digest)).state;
    assert.equal(state.records, 128);
    assert.equal(state.installations.length, 0);
  } finally { await rm(directory, { recursive: true, force: true }); }
});
