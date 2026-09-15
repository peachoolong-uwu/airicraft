/** Copy closed JSON data after bounding its traversal and encoded size. */
export function copyMessage(value, maximumBytes = 16_384) {
  let nodes = 0, textBytes = 0;
  function visit(item, depth) {
    if (++nodes > 2048 || depth > 16) throw Error('message_limit');
    if (item === null || typeof item === 'boolean') return item;
    if (typeof item === 'number' && Number.isFinite(item)) return item;
    if (typeof item === 'string') {
      textBytes += Buffer.byteLength(item);
      if (textBytes > maximumBytes) throw Error('message_limit');
      return item;
    }
    if (typeof item !== 'object') throw Error('invalid_json_value');
    if (Array.isArray(item)) {
      if (item.length > 2048) throw Error('message_limit');
      if (Object.getPrototypeOf(item) !== Array.prototype || Reflect.ownKeys(item).some(key =>
        key !== 'length' && (typeof key !== 'string' || !/^(0|[1-9]\d*)$/.test(key) || Number(key) >= item.length))) throw Error('invalid_json_value');
      const copied = [];
      for (let index = 0; index < item.length; index++) {
        const descriptor = Object.getOwnPropertyDescriptor(item, String(index));
        if (!descriptor || !Object.hasOwn(descriptor, 'value')) throw Error('invalid_json_value');
        copied.push(visit(descriptor.value, depth + 1));
      }
      return copied;
    }
    if (![Object.prototype, null].includes(Object.getPrototypeOf(item))) throw Error('invalid_json_value');
    const keys = Reflect.ownKeys(item);
    if (keys.length > 2048) throw Error('message_limit');
    const copied = Object.create(null);
    for (const key of keys) {
      if (typeof key !== 'string') throw Error('invalid_json_value');
      visit(key, depth + 1);
      const descriptor = Object.getOwnPropertyDescriptor(item, key);
      if (!descriptor.enumerable || !Object.hasOwn(descriptor, 'value')) throw Error('invalid_json_value');
      copied[key] = visit(descriptor.value, depth + 1);
    }
    return copied;
  }
  const encoded = JSON.stringify(visit(value, 0));
  if (Buffer.byteLength(encoded) > maximumBytes) throw Error('message_limit');
  return JSON.parse(encoded);
}
