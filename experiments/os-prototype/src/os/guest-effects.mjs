import { executionPolicy as policy } from './execution-policy.mjs';
import { copyMessage } from './value.mjs';

const fields = {
  observe: ['query'], wait: ['condition', 'options'], spawn: ['definition', 'input', 'options'], join: ['handle'],
  target: ['resource', 'quantity'], demand: ['resource', 'quantity', 'methods'],
  work: ['operation', 'arguments', 'context'], worker: ['definition', 'input']
};
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const name = value => typeof value === 'string' && value.length > 0 && value.length <= 256;

/** Closed data declarations only. Ownership, grants and current feasibility stay with the host. */
export function guestEffect(value) {
  const effect = copyMessage(value), expected = fields[effect?.kind];
  if (!Array.isArray(expected)) throw Error('effect_unknown');
  if (Object.keys(effect).length !== expected.length + 1 || expected.some(key => !Object.hasOwn(effect, key))) throw Error('invalid_effect');
  if (['observe', 'wait', 'spawn', 'join', 'work'].includes(effect.kind)) {
    for (const key of ['query', 'condition', 'options', 'handle', 'arguments'])
      if (Object.hasOwn(effect, key) && !object(effect[key])) throw Error('invalid_effect');
  }
  for (const key of ['definition', 'resource', 'operation']) if (Object.hasOwn(effect, key) && !name(effect[key])) throw Error('invalid_effect');
  if (Object.hasOwn(effect, 'quantity') && (!Number.isSafeInteger(effect.quantity) || effect.quantity < (effect.kind === 'target' ? 0 : 1))) throw Error('invalid_effect');
  if (effect.kind === 'demand' && (!Array.isArray(effect.methods) || effect.methods.length > 32 || effect.methods.some(method => !name(method)))) throw Error('invalid_effect');
  if (effect.kind === 'work' && effect.context !== null && !object(effect.context)) throw Error('invalid_effect');
  return effect;
}

export function guestResult(value, mode) {
  value = copyMessage(value);
  if (mode === 'offers') {
    if (!Array.isArray(value) || value.length > policy.offers) throw Error('work_offer_limit');
    return value.map(offer => {
      const effect = guestEffect(offer);
      if (effect.kind !== 'work') throw Error('invalid_work_offer');
      return effect;
    });
  }
  if (!object(value) || typeof value.done !== 'boolean' || !Object.hasOwn(value, 'value') || Object.keys(value).length !== 2) throw Error('invalid_generator_result');
  return { done: value.done, value: value.done ? value.value : guestEffect(value.value) };
}
