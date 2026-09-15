import { copyMessage } from './value.mjs';
import { canonicalJson } from './content.mjs';

const keywords = {
  null: [], boolean: [], number: ['minimum', 'maximum'], integer: ['minimum', 'maximum'],
  string: ['minLength', 'maxLength'], array: ['items', 'minItems', 'maxItems'],
  object: ['properties', 'required', 'additionalProperties']
};
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);

/** Closed JSON Schema subset: unknown keywords are errors, never ignored promises. */
export function compileContract(schema) {
  try { schema = copyMessage(schema); }
  catch (error) { throw Error(error.message === 'message_limit' ? 'contract_limit' : 'invalid_contract'); }
  let nodes = 0;
  function compile(rule, depth) {
    if (++nodes > 256 || depth > 8) throw Error('contract_limit');
    if (typeof rule === 'boolean') return (_, path) => { if (!rule) mismatch(path, 'no_value_allowed'); };
    if (!object(rule) || typeof rule.type !== 'string' || !Object.hasOwn(keywords, rule.type)) throw Error('invalid_contract');
    const allowed = ['type', 'enum', ...keywords[rule.type]];
    if (Object.keys(rule).some(key => !allowed.includes(key))) throw Error('invalid_contract');
    if (rule.enum !== undefined && (!Array.isArray(rule.enum) || rule.enum.length < 1 || rule.enum.length > 32)) throw Error('invalid_contract');
    const choices = rule.enum?.map(value => canonicalJson(value));
    const bound = (key, maximum) => {
      const value = rule[key];
      if (value !== undefined && (!Number.isSafeInteger(value) || value < 0 || value > maximum)) throw Error('invalid_contract');
      return value;
    };
    let minimum, maximum, items, properties, required;
    if (['number', 'integer'].includes(rule.type)) {
      minimum = rule.minimum; maximum = rule.maximum;
      if ([minimum, maximum].some(value => value !== undefined && (typeof value !== 'number' || !Number.isFinite(value)))) throw Error('invalid_contract');
    } else if (rule.type === 'string') { minimum = bound('minLength', 16_384); maximum = bound('maxLength', 16_384); }
    else if (rule.type === 'array') {
      if (!Object.hasOwn(rule, 'items')) throw Error('invalid_contract');
      minimum = bound('minItems', 2048); maximum = bound('maxItems', 2048); items = compile(rule.items, depth + 1);
    } else if (rule.type === 'object') {
      if ((rule.properties !== undefined && !object(rule.properties)) || Object.keys(rule.properties ?? {}).length > 64 ||
          (rule.additionalProperties !== undefined && typeof rule.additionalProperties !== 'boolean')) throw Error('invalid_contract');
      properties = new Map(Object.entries(rule.properties ?? {}).map(([key, value]) => {
        if (key.length > 128) throw Error('invalid_contract');
        return [key, compile(value, depth + 1)];
      }));
      required = rule.required ?? [];
      if (!Array.isArray(required) || required.length > 64 || new Set(required).size !== required.length ||
          required.some(key => typeof key !== 'string' || !properties.has(key))) throw Error('invalid_contract');
    }
    if (minimum !== undefined && maximum !== undefined && minimum > maximum) throw Error('invalid_contract');
    return (value, path) => {
      const matches = rule.type === 'null' ? value === null : rule.type === 'object' ? object(value) : rule.type === 'array' ? Array.isArray(value)
        : rule.type === 'integer' ? Number.isSafeInteger(value) : typeof value === rule.type;
      if (!matches) mismatch(path, rule.type);
      if (choices && !choices.includes(canonicalJson(value))) mismatch(path, 'enum');
      const measured = rule.type === 'string' ? Array.from(value).length : rule.type === 'array' ? value.length : value;
      if (minimum !== undefined && measured < minimum || maximum !== undefined && measured > maximum) mismatch(path, 'bounds');
      if (items) value.forEach((item, index) => items(item, `${path}[${index}]`));
      if (properties) {
        for (const key of required) if (!Object.hasOwn(value, key)) mismatch(path, `missing:${key}`);
        for (const [key, item] of Object.entries(value)) {
          const validate = properties.get(key);
          if (validate) validate(item, `${path}.${key}`);
          else if (rule.additionalProperties !== true) mismatch(path, `undeclared:${key}`);
        }
      }
    };
  }
  const validate = compile(schema, 0);
  return value => { const copied = copyMessage(value); validate(copied, '$'); return copied; };
}

function mismatch(path, expected) { throw Object.assign(Error('contract_mismatch'), { path, expected }); }
