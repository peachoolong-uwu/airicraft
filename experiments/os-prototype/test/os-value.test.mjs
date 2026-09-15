import test from 'node:test';
import assert from 'node:assert/strict';
import { copyMessage } from '../src/os/value.mjs';

test('closed JSON copying never executes array accessors or serialization hooks', () => {
  let executed = false;
  const value = [1];
  Object.defineProperty(value, 'toJSON', { value() { executed = true; return 'forged'; } });
  assert.throws(() => copyMessage(value), /invalid_json_value/);
  assert.equal(executed, false);
  const accessor = [1];
  Object.defineProperty(accessor, '0', { get() { executed = true; return 2; } });
  assert.throws(() => copyMessage(accessor), /invalid_json_value/);
  assert.equal(executed, false);
});
