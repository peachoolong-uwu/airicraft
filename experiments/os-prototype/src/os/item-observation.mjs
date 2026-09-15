import { createHash } from 'node:crypto';
import { copyMessage } from './value.mjs';

const text = value => typeof value === 'string' && value.length > 0 && value.length <= 256;
const count = value => Number.isSafeInteger(value) && value >= 0 && value <= 64;

/** Resource identity includes its observed location and opaque item components. */
export function itemResource(location, itemId, variant) {
  if (!(location === 'player' || /^container:[^/]{1,64}$/.test(location)) || !text(itemId) ||
      typeof variant !== 'string' || variant.length > 4096) throw Error('invalid_item_identity');
  const key = `${location}/${itemId}/${createHash('sha256').update(variant).digest('hex')}`;
  if (key.length > 256) throw Error('invalid_item_identity');
  return key;
}

export function itemSlots(value, { inventory = false } = {}) {
  const slots = copyMessage(value, 131_072), error = inventory ? 'invalid_inventory_observation' : 'invalid_container_observation';
  if (!Array.isArray(slots) || slots.length > 90 || new Set(slots.map(slot => slot?.id)).size !== slots.length ||
      inventory && (slots.length !== 36 || slots.some(slot => slot?.id > 35 || slot?.container !== false))) throw Error(error);
  for (const slot of slots) {
    if (!slot || !Number.isSafeInteger(slot.id) || slot.id < 0 || typeof slot.container !== 'boolean' ||
        !count(slot.count) || !count(slot.maxCount) || slot.maxCount < 1 || slot.count > slot.maxCount ||
        typeof slot.itemId !== 'string' || slot.count > 0 && !text(slot.itemId) ||
        typeof slot.variant !== 'string' || slot.variant.length > 4096) throw Error(error);
  }
  return slots;
}

export function itemStocks(slots, location, knownKeys = []) {
  const stocks = Object.fromEntries(knownKeys.filter(key => key.startsWith(`${location}/`)).map(key => [key, 0]));
  for (const slot of slots) if (slot.count > 0) {
    const key = itemResource(location, slot.itemId, slot.variant);
    stocks[key] = (stocks[key] ?? 0) + slot.count;
  }
  return stocks;
}

/** Missing or unavailable coverage is unknown; only a complete inventory can establish zero. */
export function observeInventory(frame, knownKeys = []) {
  if (frame.facts?.inventory?.available !== true) return {};
  return itemStocks(itemSlots(frame.facts.inventory.slots, { inventory: true }), 'player', knownKeys);
}
