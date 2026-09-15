/** Shared address contract for published facts and the predicates that read them. */
export function isObservationPath(value) {
  return Array.isArray(value) && value.length <= 8 && value.every(key =>
    typeof key === 'string' && key.length <= 128 || Number.isSafeInteger(key) && key >= 0 && key < 2048);
}
