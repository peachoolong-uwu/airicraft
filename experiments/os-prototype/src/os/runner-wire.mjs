import { executionPolicy as policy } from './execution-policy.mjs';
import { copyMessage } from './value.mjs';

export const FRAME_BYTES = policy.messageBytes;

export function frame(message) {
  const body = Buffer.from(JSON.stringify(copyMessage(message, FRAME_BYTES)));
  const bytes = Buffer.allocUnsafe(4 + body.length);
  bytes.writeUInt32BE(body.length); body.copy(bytes, 4);
  return bytes;
}

/** Never allocate a body until its four-byte declared length has passed the bound. */
export class FrameDecoder {
  #onMessage;
  #header = Buffer.alloc(4);
  #headerOffset = 0;
  #body = null;
  #offset = 0;
  #failed = false;
  constructor(onMessage) { this.#onMessage = onMessage; }
  get bufferedBytes() { return this.#body?.length ?? 0; }
  push(chunk) {
    if (this.#failed) throw Error('runner_decoder_failed');
    try {
      let cursor = 0;
      while (cursor < chunk.length) {
        if (!this.#body) {
          const count = Math.min(4 - this.#headerOffset, chunk.length - cursor);
          chunk.copy(this.#header, this.#headerOffset, cursor, cursor + count);
          cursor += count; this.#headerOffset += count;
          if (this.#headerOffset < 4) continue;
          const length = this.#header.readUInt32BE();
          if (length < 2 || length > FRAME_BYTES) throw Error('runner_frame_limit');
          this.#body = Buffer.allocUnsafe(length); this.#offset = 0;
        }
        const count = Math.min(this.#body.length - this.#offset, chunk.length - cursor);
        chunk.copy(this.#body, this.#offset, cursor, cursor + count);
        cursor += count; this.#offset += count;
        if (this.#offset === this.#body.length) {
          const text = this.#body.toString('utf8');
          if (!Buffer.from(text).equals(this.#body)) throw Error('runner_invalid_utf8');
          const message = copyMessage(JSON.parse(text), FRAME_BYTES);
          this.#body = null; this.#headerOffset = 0;
          this.#onMessage(message);
        }
      }
    } catch (error) { this.#body = null; this.#failed = true; throw error; }
  }
}

export function writeFrame(stream, message) {
  const bytes = frame(message);
  return new Promise((resolve, reject) => stream.write(bytes, error => error ? reject(error) : resolve()));
}
