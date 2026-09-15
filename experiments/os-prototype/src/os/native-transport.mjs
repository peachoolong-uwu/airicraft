import { fileURLToPath } from 'node:url';
import { WrapperStream } from '../stream.mjs';
import { copyMessage } from './value.mjs';

const cli = fileURLToPath(new URL('../../../../wrapper/build/install/airicraft/bin/airicraft', import.meta.url));
const methods = new Set(['os_observe', 'os_lease', 'os_submit', 'os_inspect', 'os_cancel']);

/** Trusted transport through the public wrapper. Neither the stream nor this object enters a guest. */
export class NativeTransport {
  #stream;
  #clock;
  #closed = false;

  static async open({ stream = new WrapperStream(cli), clock = () => performance.now() } = {}) {
    const native = new NativeTransport(stream, clock);
    try {
      // This discarded handshake may include wrapper JVM startup. It is never an action basis.
      native.#decode(await stream.request({ op: 'call', name: 'os_observe', arguments: {} }));
      return native;
    } catch (error) { await native.close(); throw error; }
  }
  constructor(stream, clock) { this.#stream = stream; this.#clock = clock; }
  async call(name, args = {}) {
    if (this.#closed) throw Error('native_transport_closed');
    if (!methods.has(name)) throw Error('native_method_not_allowed');
    args = copyMessage(args);
    const began = this.#clock();
    const payload = await this.#stream.request({ op: 'call', name, arguments: args });
    const received = this.#clock();
    const result = this.#decode(payload);
    if (name === 'os_observe' && result.status === 'ok') {
      const frame = result.frame;
      if (frame?.schemaVersion !== 1 || frame.epoch !== result.authority.epoch ||
          !Number.isFinite(frame.captureAgeMillis) || frame.captureAgeMillis < 0 || received < began) throw Error('invalid_native_observation');
      frame.captureAgeUpperBoundMillis = frame.captureAgeMillis + received - began;
      frame.receivedAtHostMillis = received;
      if (frame.captureAgeUpperBoundMillis >= 2000) throw Error('observation_stale');
    }
    return result;
  }
  #decode(payload) {
    if (payload?.codexDriverActive !== true) throw Error('driver_mode_required');
    if (typeof payload.result !== 'string' || Buffer.byteLength(payload.result) > 524_288) throw Error('native_response_limit');
    const result = JSON.parse(payload.result);
    if (result?.schemaVersion !== 1) throw Error('unsupported_native_schema');
    if (!['ok', 'rejected'].includes(result.status) || !result.authority || typeof result.authority !== 'object') throw Error('invalid_native_response');
    return result;
  }
  async close() {
    if (this.#closed) return;
    this.#closed = true;
    await this.#stream.close();
  }
}
