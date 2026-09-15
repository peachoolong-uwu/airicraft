import test from 'node:test';
import assert from 'node:assert/strict';
import { compileContract } from '../src/os/contracts.mjs';
import { InvocationBroker } from '../src/os/broker.mjs';

test('closed bounded contracts validate nested values without coercion or undeclared fields', () => {
  const validate = compileContract({ type: 'object', properties: {
    choice: { type: 'string', enum: ['retry', 'skip'] },
    cells: { type: 'array', maxItems: 2, items: { type: 'integer', minimum: 0, maximum: 100 } }
  }, required: ['choice', 'cells'], additionalProperties: false });
  const input = { choice: 'retry', cells: [1, 2] }, result = validate(input);
  input.cells[0] = 99;
  assert.deepEqual(result, { choice: 'retry', cells: [1, 2] });
  for (const invalid of [{ choice: 'retry' }, { choice: 'other', cells: [] }, { choice: 'skip', cells: ['1'] },
    { choice: 'skip', cells: [-1] }, { choice: 'skip', cells: [1, 2, 3] }, { choice: 'skip', cells: [], tool: 'mine' }])
    assert.throws(() => validate(invalid), /contract_mismatch/);
});

test('unsupported schemas and unbounded traversal are rejected rather than partially interpreted', () => {
  for (const schema of [{ type: 'string', pattern: '.*' }, { $ref: 'https://example.test/schema' },
    { type: 'object', required: ['missing'] }, { type: ['string'] }, { type: 'array' }, { type: 'string', minLength: 3, maxLength: 2 }])
    assert.throws(() => compileContract(schema), /invalid_contract/);
  let nested = { type: 'null' };
  for (let index = 0; index < 12; index++) nested = { type: 'array', items: nested };
  assert.throws(() => compileContract(nested), /contract_limit/);
  const any = compileContract(true);
  assert.throws(() => any({ get token() { throw Error('should_not_execute'); } }), /invalid_json_value/);
});

test('a declared result contract fails before a body return can become success or release an activity', () => {
  const broker = new InvocationBroker();
  const root = broker.install({ definition: 'typed', grants: [], outputContract: { type: 'integer' } });
  broker.trackActivity('owned', [root]);
  broker.returned(root, 'not-an-integer');
  assert.equal(broker.inspect(root).outcome, null);
  assert.equal(broker.activity().stopRequested, true);
  broker.settleActivity('owned', { released: true, accountingComplete: true });
  assert.equal(broker.inspect(root).outcome.status, 'failure');
  assert.equal(broker.inspect(root).outcome.cause.reason, 'invalid_result');
});
