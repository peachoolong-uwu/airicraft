import { copyMessage } from './value.mjs';

const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const text = (value, maximum) => typeof value === 'string' && value.trim().length > 0 && value.length <= maximum;
const tick = value => Number.isSafeInteger(value) && value >= 0;
const coordinate = value => Number.isInteger(value) && Math.abs(value) <= 1_875_000;
const uuid = value => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);

/** Trusted scope declarations. These subscribe to native updates; they neither load chunks nor claim an actor. */
export function progressScopes(value) {
  const scopes = copyMessage(value, 12_288, { maximumNodes: 1900 });
  if (!Array.isArray(scopes) || scopes.length > 32) throw Error('invalid_progress_scopes');
  let sources = 0;
  for (const scope of scopes) {
    if (!object(scope) || !text(scope.scope, 128) || Object.keys(scope).length !== 2) throw Error('invalid_progress_scopes');
    const chunks = Object.hasOwn(scope, 'chunks'), targets = chunks ? scope.chunks : scope.entities;
    if (!Array.isArray(targets) || !targets.length || targets.length > 16) throw Error('invalid_progress_scopes');
    if (chunks ? targets.some(point => !object(point) || Object.keys(point).length !== 2 || !coordinate(point.x) || !coordinate(point.z))
      : targets.some(id => !uuid(id))) throw Error('invalid_progress_scopes');
    const identities = targets.map(target => chunks ? `${target.x}:${target.z}` : target.toLowerCase());
    if (new Set(identities).size !== targets.length) throw Error('invalid_progress_scopes');
    sources += targets.length;
  }
  if (sources > 128 || new Set(scopes.map(scope => scope.scope)).size !== scopes.length) throw Error('invalid_progress_scopes');
  return scopes;
}

/** Only explicit cumulative counters can advance a wait; sampled client/server tick differences are ignored. */
export function projectProgress(frame, scopes, provenance) {
  if (!scopes.length) return [];
  const data = frame.facts?.progress;
  if (!object(data) || typeof data.available !== 'boolean' || !Array.isArray(data.scopes)) throw Error('invalid_native_progress');
  if (data.available && (data.source !== 'native_completed_scope_ticks' || !text(data.clockSession, 96) || !tick(data.throughTick) ||
      data.scopes.length !== scopes.length) || !data.available && data.scopes.length !== 0) throw Error('invalid_native_progress');
  const entries = new Map(data.scopes.map(entry => [entry?.scope, entry]));
  if (entries.size !== data.scopes.length) throw Error('invalid_native_progress');
  return scopes.map(scope => {
    const entry = entries.get(scope.scope), kind = scope.chunks ? 'random_tick_chunks' : 'passive_entities';
    if (data.available && (!object(entry) || Object.keys(entry).length !== 5 || entry.kind !== kind || !text(entry.clockId, 96) ||
        entry.eligibleTicks !== null && (!tick(entry.eligibleTicks) || entry.eligibleTicks > data.throughTick) ||
        (entry.eligibleTicks === null ? entry.lastTickEligible !== null : typeof entry.lastTickEligible !== 'boolean'))) throw Error('invalid_native_progress');
    const known = data.available && entry.eligibleTicks !== null;
    return { ...provenance, source: 'native_completed_scope_ticks', scope: scope.scope,
      coverage: { available: known, complete: known, truncated: false },
      facts: [known ? { path: ['lastTickEligible'], known: true, value: entry.lastTickEligible }
        : { path: ['lastTickEligible'], known: false, reason: 'progress_unavailable' }],
      progress: { clockId: data.available ? `native-progress:${data.clockSession}:${entry.clockId}` : `unavailable:${scope.scope}`,
        eligibleTicks: known ? entry.eligibleTicks : null } };
  });
}
