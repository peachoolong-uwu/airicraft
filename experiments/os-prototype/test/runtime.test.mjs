import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { BehaviorSandbox } from '../src/sandbox.mjs';
import { SkillRuntime } from '../src/runtime.mjs';
import { SimulationAdapter } from '../src/simulation.mjs';
import { parseToolResult, validateConfig } from '../src/driver.mjs';

const library = name => readFile(new URL(`../library/${name}.js`, import.meta.url), 'utf8');
const drain = () => new Promise(resolve => setImmediate(resolve));

test('guest has no host APIs; a runaway resume is interrupted', async () => {
  const sandbox = await BehaviorSandbox.create(`function* main() {
    yield [typeof process, typeof require, typeof fetch, typeof console, typeof setTimeout];
    while (true) {}
  }`, {});
  try {
    assert.deepEqual(sandbox.next().value, ['undefined', 'undefined', 'undefined', 'undefined', 'undefined']);
    const started = performance.now();
    assert.throws(() => sandbox.next(), /interrupted/);
    assert.ok(performance.now() - started < 2000);
  } finally { sandbox.dispose(); }
});

test('guest allocation and outgoing data have bounds', async () => {
  const allocation = await BehaviorSandbox.create("function* main() { const a = []; for (let i=0; i<1000000; i++) a.push({i, x: 'abcdefghij'.repeat(30)}); yield a; }", {});
  try { assert.throws(() => allocation.next(), /out of memory|interrupted/); }
  finally { allocation.dispose(); }
  const output = await BehaviorSandbox.create("function* main() { yield 'a'.repeat(20000); }", {});
  try { assert.throws(() => output.next(), /output_limit/); }
  finally { output.dispose(); }
});

test('crop waits release the player; fishing fills them; ready crops win at the next cast boundary', async () => {
  const trace = [];
  const adapter = new SimulationAdapter();
  const runtime = new SkillRuntime(adapter, { emit: (type, data) => trace.push({ type, ...data }), clock: () => adapter.time * 1000 });
  try {
    await runtime.install({ id: 'farm', source: await library('farm'), config: { plot: 'wheat' }, priority: 10, actions: ['tend_crops'], plots: ['wheat'] });
    await runtime.install({ id: 'fishing', source: await library('fishing'), config: { site: 'beach' }, priority: 0, actions: ['fish_once'], sites: ['beach'] });
    for (let i = 0; i < 100; i++) {
      adapter.step(); runtime.update(adapter.observe()); runtime.tick(); await drain();
    }
    assert.ok(adapter.harvests >= 3);
    assert.ok(adapter.fish >= 3);
    assert.equal(adapter.maximumOwners, 1);
    assert.ok(trace.some(event => event.type === 'state' && event.id === 'farm' && event.phase === 'WAITING_WORLD'));
    const contested = trace.filter(event => event.type === 'player_granted' && event.eligible.length === 2);
    assert.ok(contested.length >= 2);
    assert.ok(contested.every(event => event.id === 'farm'));
    let owner = null;
    for (const event of trace) {
      if (event.type === 'player_granted') { assert.equal(owner, null); owner = event.id; }
      if (event.type === 'player_released') { assert.equal(owner, event.id); owner = null; }
    }
  } finally { await runtime.stop(); }
});

test('a failed guest cannot acquire another instance capabilities or block a healthy instance', async () => {
  const performed = [];
  const runtime = new SkillRuntime({ perform: async effect => { performed.push(effect); return {}; } });
  try {
    await runtime.install({ id: 'bad', source: "function* main(os) { yield os.action('tend_crops', {plot:'secret'}); }", config: {}, priority: 100, actions: ['tend_crops'], plots: ['own'] });
    await runtime.install({ id: 'good', source: await library('fishing'), config: { site: 'beach' }, priority: 0, actions: ['fish_once'], sites: ['beach'] });
    runtime.update({}); runtime.tick(); await drain();
    assert.equal(runtime.instances[0].phase, 'FAILED');
    assert.equal(performed.length, 1);
    assert.equal(performed[0].name, 'fish_once');
  } finally { await runtime.stop(); }
});

test('uncertain native completion retains the lease and prevents further dispatch', async () => {
  let calls = 0;
  const runtime = new SkillRuntime({ perform: async () => { calls++; throw Error('release_unconfirmed'); } });
  try {
    await runtime.install({ id: 'fish', source: await library('fishing'), config: { site: 'beach' }, priority: 0, actions: ['fish_once'], sites: ['beach'] });
    runtime.update({}); runtime.tick(); await drain(); runtime.tick();
    assert.equal(runtime.owner.id, 'fish');
    assert.match(runtime.fatal, /release_unconfirmed/);
    for (let i = 0; i < 10; i++) { runtime.update({}); runtime.tick(); }
    assert.equal(calls, 1);
  } finally { await runtime.stop(); }
});

test('completion cannot immediately redispatch against a stale ready observation', async () => {
  let calls = 0;
  const runtime = new SkillRuntime({ perform: async () => { calls++; return {}; } });
  try {
    await runtime.install({ id: 'farm', source: await library('farm'), config: { plot: 'wheat' }, priority: 10, actions: ['tend_crops'], plots: ['wheat'] });
    runtime.update({ wheat: { known: true, ready: true } }); runtime.tick(); await drain();
    runtime.tick(); runtime.tick();
    assert.equal(calls, 1);
    runtime.update({ wheat: { known: true, ready: false } }); runtime.tick();
    assert.equal(runtime.instances[0].phase, 'WAITING_WORLD');
    assert.equal(calls, 1);
  } finally { await runtime.stop(); }
});

test('CLI result decoding preserves JSON escapes and configuration is bounded', () => {
  assert.deepEqual(parseToolResult('status: ok\nresult: Tool result for inspect_work: {"message":"a\\nb","state":"SUCCEEDED"}\n'), { message: 'a\nb', state: 'SUCCEEDED' });
  assert.throws(() => parseToolResult('status: error'), /missing/);
  assert.throws(() => validateConfig({ plots: [] }), /1_to_3/);
});
