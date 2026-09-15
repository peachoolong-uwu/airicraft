/** Native identity is a value tuple; JSON object field order is not significant. */
export function validNativeId(id) {
  return !!id && typeof id === 'object' && !Array.isArray(id) &&
    typeof id.epoch === 'string' && id.epoch.length > 0 && id.epoch.length <= 256 &&
    Number.isSafeInteger(id.generation) && id.generation > 0 &&
    Number.isSafeInteger(id.sequence) && id.sequence > 0 &&
    Object.keys(id).every(key => ['epoch', 'generation', 'sequence'].includes(key));
}

export function nativeIdKey(id) {
  if (!validNativeId(id)) throw Error('invalid_native_id');
  return JSON.stringify([id.epoch, id.generation, id.sequence]);
}

export function sameNativeId(a, b) {
  return validNativeId(a) && validNativeId(b) && nativeIdKey(a) === nativeIdKey(b);
}
