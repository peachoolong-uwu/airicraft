import { contentDigest } from './content.mjs';

export const materialAvailabilitySource = 'native_stable_material_ticks';
const tick = value => Number.isSafeInteger(value) && value >= 0;
const contextId = value => value === null || typeof value === 'string' && value.length > 0 && value.length <= 256;

/** This proof covers the bound container and carried inventory, not arbitrary world operations. */
export function nativeAvailability(frame, authority) {
  const proof = frame.facts?.availability;
  if (!proof || proof.available === false && Object.keys(proof).length === 1) return null;
  if (proof.source !== materialAvailabilitySource || typeof proof.available !== 'boolean' ||
      typeof proof.clockId !== 'string' || !proof.clockId.length || proof.clockId.length > 256 ||
      !tick(proof.throughTick) || !tick(proof.gateRevision) || proof.gateRevision < 1 ||
      !contextId(proof.contextId) ||
      Object.keys(proof).some(key => !['source', 'available', 'clockId', 'gateRevision', 'fromTick', 'throughTick', 'stamp', 'contextId'].includes(key)))
    throw Error('invalid_native_availability');
  if (!proof.available) {
    if (proof.fromTick !== null || proof.stamp !== null) throw Error('invalid_native_availability');
    return proof;
  }
  if (!tick(proof.fromTick) || proof.fromTick > proof.throughTick || !authority?.lease || authority.active !== null ||
      typeof proof.stamp !== 'string' || !/^[a-f0-9]{64}$/.test(proof.stamp) || frame.facts.inventory?.available !== true)
    throw Error('invalid_native_availability');
  const context = authority.context;
  if (proof.contextId !== (context?.effects?.contextId ?? null) || context != null &&
      (proof.contextId === null || context.state !== 'RUNNING' || context.released !== false || context.effects?.contextReady !== true ||
       context.id?.epoch !== authority.lease.epoch || context.id?.generation !== authority.lease.generation))
    throw Error('availability_context_mismatch');
  const stamp = contentDigest({ captureId: 'eligibility-v2', operation: 'material', arguments: {
    world: frame.world, lease: authority.lease, gateRevision: proof.gateRevision, contextId: proof.contextId,
    window: frame.facts.window, inventory: frame.facts.inventory
  } }, 524_288, { maximumNodes: 8192 }).slice(7);
  if (stamp !== proof.stamp) throw Error('availability_material_mismatch');
  return proof;
}
