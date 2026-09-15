import { copyMessage } from './value.mjs';

/** Validate the declared supply graph before making its trusted operations available. */
export function supplyOperations(operations) {
  if (!operations || typeof operations !== 'object' || Array.isArray(operations)) throw Error('invalid_supply_dependencies');
  const catalog = new Map(Object.entries(operations));
  if (catalog.size > 32) throw Error('supply_rule_capacity');
  const dependencies = new Map();
  for (const [name, operation] of catalog) {
    if (!name.length || name.length > 256 || !operation || typeof operation !== 'object') throw Error('invalid_supply_dependencies');
    const required = copyMessage(operation.supplyDependencies ?? []);
    if (!Array.isArray(required) || required.length > 32 || required.some(id => typeof id !== 'string' || !catalog.has(id))) {
      const missing = Array.isArray(required) && required.find(id => typeof id === 'string' && !catalog.has(id));
      if (missing) throw Object.assign(Error('supply_dependency_missing'), { path: [name, missing] });
      throw Error('invalid_supply_dependencies');
    }
    dependencies.set(name, [...new Set(required)]);
  }
  const visited = new Set(), path = [];
  function visit(name) {
    const loop = path.indexOf(name);
    if (loop !== -1) throw Object.assign(Error('supply_dependency_cycle'), { path: [...path.slice(loop), name] });
    if (visited.has(name)) return;
    path.push(name);
    for (const dependency of dependencies.get(name)) visit(dependency);
    path.pop(); visited.add(name);
  }
  for (const name of catalog.keys()) visit(name);
  return catalog;
}
