// Guest-side effect constructors and value copying; OS policy is implemented in Java.
(function guestKernel(limits) {
  'use strict';
  const parse = JSON.parse, stringify = JSON.stringify, apply = Reflect.apply, keys = Reflect.ownKeys;
  const descriptor = Object.getOwnPropertyDescriptor, prototype = Object.getPrototypeOf, create = Object.create;
  const setPrototype = Object.setPrototypeOf, freeze = Object.freeze, isArray = Array.isArray, finite = Number.isFinite;
  const objectPrototype = Object.prototype, arrayPrototype = Array.prototype, hasOwn = Object.hasOwn;
  const charCode = Function.prototype.call.bind(String.prototype.charCodeAt), slice = String.prototype.slice;
  const numeric = Number, textValue = String, integer = Number.isSafeInteger;
  const generatorPrototype = prototype(function* () {}.prototype), next = generatorPrototype.next;
  const isPrototypeOf = Object.prototype.isPrototypeOf;
  const os = freeze({
    observe: query => ({ kind: 'observe', query }),
    wait: (condition, options = {}) => ({ kind: 'wait', condition, options }),
    spawn: (definition, input = null, options = {}) => ({ kind: 'spawn', definition, input, options }),
    join: handle => ({ kind: 'join', handle }),
    target: (resource, quantity) => ({ kind: 'target', resource, quantity }),
    demand: (resource, quantity, methods = []) => ({ kind: 'demand', resource, quantity, methods }),
    work: (operation, args = {}, context = null) => ({ kind: 'work', operation, arguments: args, context }),
    worker: (definition, input) => ({ kind: 'worker', definition, input })
  });
  let program, evaluateOffers, inputs;

  function encode(value) {
    let nodes = 0;
    function copy(item, depth) {
      if (++nodes > 2048 || depth > 16) throw Error('guest_message_limit');
      if (item === null || typeof item === 'boolean' || typeof item === 'number' && finite(item)) return item;
      if (typeof item === 'string') {
        if (item.length > limits.messageBytes) throw Error('guest_message_limit');
        return item;
      }
      if (typeof item !== 'object') throw Error('guest_invalid_json');
      const array = isArray(item), own = keys(item);
      if (own.length > 2048 || (array && item.length > 2048)) throw Error('guest_message_limit');
      const inherited = prototype(item);
      if (array ? inherited !== arrayPrototype : inherited !== objectPrototype && inherited !== null) throw Error('guest_invalid_json');
      const out = array ? setPrototype([], null) : create(null);
      for (let index = 0; index < own.length; index++) {
        const key = own[index];
        if (array && key === 'length') continue;
        if (typeof key !== 'string') throw Error('guest_invalid_json');
        if (array && (!integer(numeric(key)) || numeric(key) < 0 || textValue(numeric(key)) !== key || numeric(key) >= item.length)) throw Error('guest_invalid_json');
        const property = descriptor(item, key);
        if (!property || !property.enumerable || !hasOwn(property, 'value')) throw Error('guest_invalid_json');
        out[key] = copy(property.value, depth + 1);
      }
      if (array && own.length !== item.length + 1) throw Error('guest_invalid_json');
      return out;
    }
    const text = stringify(copy(value, 0));
    if (text.length > limits.messageBytes) throw Error('guest_message_limit');
    let bytes = 0;
    for (let index = 0; index < text.length; index++) {
      const code = charCode(text, index);
      bytes += code < 128 ? 1 : code < 2048 ? 2 : code >= 0xd800 && code <= 0xdbff ? (++index, 4) : 3;
      if (bytes > limits.messageBytes) throw Error('guest_message_limit');
    }
    return text;
  }
  function deepFreeze(value) {
    if (value !== null && typeof value === 'object') {
      const own = keys(value);
      for (let index = 0; index < own.length; index++) deepFreeze(value[own[index]]);
      freeze(value);
    }
    return value;
  }
  function explain(error) {
    const detail = create(null);
    detail.message = 'guest_execution_failed';
    if (typeof error === 'string') detail.message = apply(slice, error, [0, 512]);
    else if (error && (typeof error === 'object' || typeof error === 'function')) {
      const names = ['name', 'message', 'stack'];
      for (let index = 0; index < names.length; index++) {
        const property = descriptor(error, names[index]);
        if (property && hasOwn(property, 'value') && typeof property.value === 'string')
          detail[names[index]] = apply(slice, property.value, [0, names[index] === 'stack' ? 2048 : 512]);
      }
    }
    return encode(detail);
  }
  return {
    initialize(factory, input, mode) {
      inputs = deepFreeze(parse(input));
      const exports = factory(os, inputs);
      if (mode === 'offers') {
        if (typeof exports.offers !== 'function') throw Error('offers_function_required');
        evaluateOffers = exports.offers;
      } else {
        if (typeof exports.main !== 'function') throw Error('main_function_required');
        program = exports.main(os, inputs);
        if (!program || !apply(isPrototypeOf, generatorPrototype, [program])) throw Error('main_must_be_synchronous_generator');
      }
      return encode(null);
    },
    resume(input) {
      const result = apply(next, program, [deepFreeze(parse(input))]);
      return encode({ done: result.done, value: result.value === undefined && result.done ? null : result.value });
    },
    offers(input) { return encode(evaluateOffers(os, inputs, deepFreeze(parse(input)))); },
    explain
  };
})({messageBytes: 16384})
