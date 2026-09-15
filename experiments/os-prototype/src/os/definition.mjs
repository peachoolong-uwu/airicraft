import { copyMessage } from './value.mjs';
import { compileContract } from './contracts.mjs';
import { canonicalJson } from './content.mjs';
import { executionPolicy } from './execution-policy.mjs';
import { guestResult } from './guest-effects.mjs';

export const DEFINITION_BYTES = 512 * 1024;
export const isDigest = value => typeof value === 'string' && /^sha256:[0-9a-f]{64}$/.test(value);
const text = (value, maximum = 256) => typeof value === 'string' && value.length > 0 && value.length <= maximum;
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);

export function definitionValue(value) {
  value = copyMessage(value, DEFINITION_BYTES);
  const common = ['schemaVersion', 'kind', 'name', 'description', 'tags', 'capabilities', 'environment', 'dependencies', 'inputContract', 'outputContract', 'examples'];
  const fields = [...common, ...(value?.kind === 'behavior' ? ['mode', 'source'] : ['prompt', 'profile', 'fallback'])];
  if (!object(value) || value.schemaVersion !== 1 || !['behavior', 'worker'].includes(value.kind) ||
      Object.keys(value).length !== fields.length || fields.some(key => !Object.hasOwn(value, key)) ||
      !text(value.name, 128) || !text(value.description, 2048)) throw Error('invalid_definition');
  for (const [key, limit] of [['tags', 32], ['capabilities', 64]]) {
    if (!Array.isArray(value[key]) || value[key].length > limit || value[key].some(item => !text(item)) || new Set(value[key]).size !== value[key].length) throw Error('invalid_definition');
  }
  for (const key of ['environment', 'dependencies']) {
    if (!object(value[key]) || Object.keys(value[key]).length > 32 || Object.keys(value[key]).some(name => !/^[a-zA-Z][a-zA-Z0-9_.-]{0,63}$/.test(name))) throw Error('invalid_definition');
  }
  if (Object.values(value.environment).some(item => !text(item)) || Object.values(value.dependencies).some(item => !isDigest(item))) throw Error('invalid_definition');
  compileContract(value.inputContract); compileContract(value.outputContract);
  if (!Array.isArray(value.examples) || value.examples.length > 32) throw Error('invalid_definition');
  if (value.kind === 'behavior') {
    if (!['generator', 'offers'].includes(value.mode) || typeof value.source !== 'string' || Buffer.byteLength(value.source) > executionPolicy.sourceBytes) throw Error('invalid_definition');
    for (const example of value.examples) {
      if (!object(example) || Object.keys(example).length !== 3 || !Object.hasOwn(example, 'input') || !Array.isArray(example.responses) ||
          example.responses.length > 32 || !Array.isArray(example.expected) || example.expected.length < 1 || example.expected.length > 32) throw Error('invalid_definition_example');
      if (example.expected.length !== example.responses.length + (value.mode === 'generator' ? 1 : 0)) throw Error('invalid_definition_example');
      copyMessage(example.input);
      for (const item of example.responses) copyMessage(item);
      example.expected.forEach((item, index) => {
        const expected = guestResult(item, value.mode);
        if (value.mode === 'generator' && expected.done && index !== example.expected.length - 1) throw Error('invalid_definition_example');
      });
    }
  } else if (!text(value.prompt, executionPolicy.sourceBytes) || Buffer.byteLength(value.prompt) > executionPolicy.sourceBytes ||
      !text(value.profile) || !text(value.fallback, 64) || !Object.hasOwn(value.dependencies, value.fallback)) throw Error('invalid_worker_definition');
  else for (const example of value.examples) {
    if (!object(example) || Object.keys(example).length !== 2 || !Object.hasOwn(example, 'input') || !Object.hasOwn(example, 'result')) throw Error('invalid_definition_example');
    copyMessage(example.input); copyMessage(example.result);
  }
  return value;
}

/** Resolve an explicit local lock; never search by display name or fetch a dependency. */
export async function resolveClosure(digest, { load, grants, environment, allowCandidate = false }) {
  if (!isDigest(digest)) throw Error('invalid_definition_digest');
  if (!Array.isArray(grants) || grants.length > 64 || grants.some(grant => !text(grant)) || !object(environment)) throw Error('invalid_installation');
  const visited = new Map(), path = [];
  async function visit(id) {
    if (path.includes(id)) throw Object.assign(Error('dependency_cycle'), { path: [...path.slice(path.indexOf(id)), id] });
    if (visited.has(id)) return visited.get(id);
    if (path.length >= 8 || visited.size + path.length >= 32) throw Error('dependency_capacity');
    let record;
    try { record = await load(id); } catch (error) {
      if (error.code !== 'ENOENT') throw error;
    }
    if (!record) throw Object.assign(Error('dependency_unavailable'), { path: [...path, id] });
    if (record.definition.schemaVersion !== 1) throw Error('definition_schema_incompatible');
    if (!record.state.validated && !(allowCandidate && id === digest)) throw Object.assign(Error('definition_unvalidated'), { path: [...path, id] });
    for (const [key, value] of Object.entries(record.definition.environment)) if (environment[key] !== value) throw Object.assign(Error('environment_incompatible'), { definition: id, key });
    for (const capability of record.definition.capabilities) if (!grants.includes(capability)) throw Object.assign(Error('capability_missing'), { definition: id, capability });
    path.push(id);
    const dependencies = new Map();
    for (const [name, dependency] of Object.entries(record.definition.dependencies)) {
      const child = await visit(dependency);
      for (const capability of child.definition.capabilities) if (!record.definition.capabilities.includes(capability))
        throw Object.assign(Error('dependency_capability_undeclared'), { definition: id, dependency, capability });
      dependencies.set(name, child);
    }
    if (record.definition.kind === 'worker') {
      const fallback = dependencies.get(record.definition.fallback)?.definition;
      if (!fallback || fallback.kind !== 'behavior' || canonicalJson(fallback.inputContract) !== canonicalJson(record.definition.inputContract) ||
          canonicalJson(fallback.outputContract) !== canonicalJson(record.definition.outputContract)) throw Error('fallback_contract_mismatch');
    }
    path.pop(); visited.set(id, record);
    return record;
  }
  const root = await visit(digest);
  return { root, revisions: [...visited.values()] };
}
