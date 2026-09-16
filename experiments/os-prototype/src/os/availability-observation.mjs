import { contentDigest } from './content.mjs';

export const materialAvailabilitySource = 'native_stable_material_ticks';
const tick = value => Number.isSafeInteger(value) && value >= 0;

/** This proof covers the bound container and carried inventory, not arbitrary world operations. */
export function nativeAvailability(frame, authority) {
  const proof = frame.facts?.availability;
  if (!proof || proof.available === false && Object.keys(proof).length === 1) return null;
  if (proof.source !== materialAvailabilitySource || typeof proof.available !== 'boolean' ||
      typeof proof.clockId !== 'string' || !proof.clockId.length || proof.clockId.length > 256 ||
      !tick(proof.throughTick) || !tick(proof.gateRevision) || proof.gateRevision < 1 ||
      Object.keys(proof).some(key => !['source', 'available', 'clockId', 'gateRevision', 'fromTick', 'throughTick', 'stamp'].includes(key)))
    throw Error('invalid_native_availability');
  if (!proof.available) {
    if (proof.fromTick !== null || proof.stamp !== null) throw Error('invalid_native_availability');
    return proof;
  }
  if (!tick(proof.fromTick) || proof.fromTick > proof.throughTick || !authority?.lease || authority.active !== null ||
      typeof proof.stamp !== 'string' || !/^[a-f0-9]{64}$/.test(proof.stamp) || frame.facts.inventory?.available !== true)
    throw Error('invalid_native_availability');
  const stamp = contentDigest({ captureId: 'eligibility-v1', operation: 'material', arguments: {
    world: frame.world, lease: authority.lease, gateRevision: proof.gateRevision,
    window: frame.facts.window, inventory: frame.facts.inventory
  } }, 524_288, { maximumNodes: 8192 }).slice(7);
  if (stamp !== proof.stamp) throw Error('availability_material_mismatch');
  return proof;
}
