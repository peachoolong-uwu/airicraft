import { createHash } from 'node:crypto';
import { copyMessage } from './value.mjs';

export function canonicalJson(value, maximumBytes = 16_384, traversalLimits) {
  function ordered(item) {
    if (Array.isArray(item)) return item.map(ordered);
    if (item !== null && typeof item === 'object') return Object.fromEntries(Object.keys(item).sort().map(key => [key, ordered(item[key])]));
    return item;
  }
  return JSON.stringify(ordered(copyMessage(value, maximumBytes, traversalLimits)));
}

export function contentDigest(value, maximumBytes, traversalLimits) {
  return 'sha256:' + createHash('sha256').update(canonicalJson(value, maximumBytes, traversalLimits)).digest('hex');
}
