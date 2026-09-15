import test from 'node:test';
import assert from 'node:assert/strict';
import { compileCondition } from '../src/os/conditions.mjs';

test('conditions combine known and unknown facts without treating omitted facts as absence', () => {
  const crop = { scope: 'wheat', path: ['mature'], equals: true };
  const sheep = { scope: 'sheep', path: ['readyPairs'], atLeast: 1 };
  const reads = new Map([['wheat', { known: false }], ['sheep', { known: true, value: 2 }]]);
  const read = scope => reads.get(scope) ?? { known: false };
  assert.equal(compileCondition({ any: [crop, sheep] }).evaluate(read), 'met');
  assert.equal(compileCondition({ all: [crop, sheep] }).evaluate(read), 'unknown');
  assert.equal(compileCondition({ not: crop }).evaluate(read), 'unknown');
  reads.set('sheep', { known: true, value: 0 });
  assert.equal(compileCondition({ all: [crop, sheep] }).evaluate(read), 'unmet');
  assert.equal(compileCondition({ any: [crop, sheep] }).evaluate(read), 'unknown');
  reads.set('wheat', { known: true, value: false });
  assert.equal(compileCondition({ any: [crop, sheep] }).evaluate(read), 'unmet');
});

test('conditions retain their copied literals and bounded dependency paths', () => {
  const value = { scope: 'crop', path: ['cells', 0, 'state'], equals: { mature: true } };
  const condition = compileCondition(value);
  value.equals.mature = false; value.path[0] = 'different';
  let queried;
  assert.equal(condition.evaluate((scope, path) => {
    queried = { scope, path }; return { known: true, value: { mature: true } };
  }), 'met');
  assert.deepEqual(queried, { scope: 'crop', path: ['cells', 0, 'state'] });
  assert.deepEqual(condition.scopes, ['crop']);
  assert.equal(compileCondition({ scope: 'crop', path: [], known: true }).evaluate(() => ({ known: false })), 'unmet');
});

test('invalid or excessive condition programs fail before they subscribe', () => {
  for (const condition of [{ all: [] }, { any: [] }, { source: '() => true' }, { scope: 'crop', path: [], atLeast: '1' },
    { scope: 'crop', path: ['x'], equals: true, extra: true }, { scope: 'crop', path: [], equals: true, atLeast: 1 },
    { scope: 'crop', path: [-1], equals: 1 }, { scope: 'crop', path: [], known: false }])
    assert.throws(() => compileCondition(condition), /invalid_condition/);
  let deep = { scope: 'crop', path: [], equals: true };
  for (let i = 0; i < 10; i++) deep = { not: deep };
  assert.throws(() => compileCondition(deep), /condition_limit/);
  assert.throws(() => compileCondition({ all: Array.from({ length: 65 }, () => ({ scope: 'crop', path: [], equals: 1 })) }), /condition_limit/);
});

test('structural equality ignores field order and never executes observation accessors', () => {
  const condition = compileCondition({ scope: 'crop', path: [], equals: { ready: [1, true], label: 'ready' } });
  assert.equal(condition.evaluate(() => ({ known: true, value: { label: 'ready', ready: [1, true] } })), 'met');
  assert.equal(condition.evaluate(() => ({ known: true, value: { ready: [1, true], label: 'ready', extra: true } })), 'unmet');
  const bad = { get ready() { throw Error('executed_accessor'); }, label: 'ready' };
  assert.throws(() => condition.evaluate(() => ({ known: true, value: bad })), /invalid_observation_cell/);
  assert.throws(() => condition.evaluate(() => ({ known: true, get value() { throw Error('executed_accessor'); } })), /invalid_observation_cell/);
});
