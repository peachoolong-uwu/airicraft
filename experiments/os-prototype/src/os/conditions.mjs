import { copyMessage } from './value.mjs';
import { contentDigest } from './content.mjs';
import { isObservationPath } from './observation-path.mjs';

const keyCounts = new WeakMap();
function keyCount(value) {
  if (keyCounts.has(value)) return keyCounts.get(value);
  const count = Reflect.ownKeys(value).length;
  if (Object.isFrozen(value)) keyCounts.set(value, count);
  return count;
}
function equal(actual, expected) {
  if (actual === expected) return true;
  if (actual === null || expected === null || typeof actual !== typeof expected || typeof actual !== 'object') return false;
  if (Array.isArray(actual) !== Array.isArray(expected)) return false;
  if (![Object.prototype, Array.prototype, null].includes(Object.getPrototypeOf(actual))) throw Error('invalid_observation_cell');
  const keys = Object.keys(expected);
  if (Array.isArray(expected) && actual.length !== expected.length) return false;
  if (keyCount(actual) !== keys.length + (Array.isArray(expected) ? 1 : 0)) return false;
  for (const key of keys) {
    const descriptor = Object.getOwnPropertyDescriptor(actual, key);
    if (!descriptor) return false;
    if (!descriptor.enumerable || !Object.hasOwn(descriptor, 'value')) throw Error('invalid_observation_cell');
    if (!equal(descriptor.value, expected[key])) return false;
  }
  return true;
}

/** Pure bounded conditions. The observer supplies explicit known/value cells, never guessed absence. */
export function compileCondition(value) {
  value = copyMessage(value);
  let nodes = 0;
  const scopes = new Set();
  function compile(condition, depth) {
    if (++nodes > 64 || depth > 8) throw Error('condition_limit');
    if (!condition || typeof condition !== 'object' || Array.isArray(condition)) throw Error('invalid_condition');
    const keys = Object.keys(condition);
    if (keys.length === 1 && ['all', 'any'].includes(keys[0])) {
      const kind = keys[0], children = condition[kind];
      if (!Array.isArray(children) || !children.length) throw Error('invalid_condition');
      if (children.length > 64) throw Error('condition_limit');
      const parts = children.map(child => compile(child, depth + 1));
      return read => {
        let unknown = false;
        for (const part of parts) {
          const result = part(read);
          if (kind === 'all' && result === 'unmet' || kind === 'any' && result === 'met') return result;
          unknown ||= result === 'unknown';
        }
        return unknown ? 'unknown' : kind === 'all' ? 'met' : 'unmet';
      };
    }
    if (keys.length === 1 && keys[0] === 'not') {
      const part = compile(condition.not, depth + 1);
      return read => { const result = part(read); return result === 'unknown' ? result : result === 'met' ? 'unmet' : 'met'; };
    }
    const operation = ['equals', 'atLeast', 'known'].find(key => Object.hasOwn(condition, key));
    if (keys.length !== 3 || !operation || !Object.hasOwn(condition, 'scope') || !Object.hasOwn(condition, 'path') ||
        typeof condition.scope !== 'string' || !condition.scope.length || condition.scope.length > 128 ||
        !isObservationPath(condition.path) ||
        operation === 'atLeast' && typeof condition.atLeast !== 'number' || operation === 'known' && condition.known !== true) throw Error('invalid_condition');
    scopes.add(condition.scope);
    if (scopes.size > 32) throw Error('condition_limit');
    return read => {
      const cell = read(condition.scope, [...condition.path]);
      const known = cell && Object.getOwnPropertyDescriptor(cell, 'known');
      if (!known || !Object.hasOwn(known, 'value') || typeof known.value !== 'boolean') throw Error('invalid_observation_cell');
      if (operation === 'known') return known.value ? 'met' : 'unmet';
      if (!known.value) return 'unknown';
      const value = Object.getOwnPropertyDescriptor(cell, 'value');
      if (!value || !Object.hasOwn(value, 'value')) throw Error('invalid_observation_cell');
      const matches = operation === 'equals' ? equal(value.value, condition.equals)
        : typeof value.value === 'number' && value.value >= condition.atLeast;
      return matches ? 'met' : 'unmet';
    };
  }
  const evaluate = compile(value, 0);
  return Object.freeze({ digest: contentDigest(value), scopes: Object.freeze([...scopes].sort()), evaluate });
}
