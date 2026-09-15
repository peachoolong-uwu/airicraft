import { executionPolicy as policy } from './execution-policy.mjs';
import { createHash } from 'node:crypto';
import { getQuickJS } from 'quickjs-emscripten';
import quickjsPackage from 'quickjs-emscripten/package.json' with { type: 'json' };
import { GuestInvocation } from './guest.mjs';
import { FrameDecoder, writeFrame } from './runner-wire.mjs';

const guests = new Map(), loading = new Map(), queue = [];
let sequence = 0, processing = false;
const shapes = {
  begin: ['definition', 'mode', 'input', 'sourceBytes', 'sourceHash'], chunk: ['offset', 'bytes'],
  create: [], resume: ['input'], offers: ['input'], dispose: []
};
function validate(message) {
  const extra = shapes[message?.command];
  if (!Array.isArray(extra) || Object.keys(message).length !== extra.length + 3 ||
      !Number.isSafeInteger(message.requestId) || message.requestId !== sequence + 1 ||
      typeof message.invocation !== 'string' || !/^invocation:[1-9]\d{0,14}$/.test(message.invocation) ||
      extra.some(key => !Object.hasOwn(message, key))) throw Error('runner_protocol_error');
  sequence = message.requestId;
}
async function perform(message) {
  const id = message.invocation;
  if (message.command === 'begin') {
    // The broker owns invocation lifetime; wire order need not match child creation order.
    if (guests.has(id) || loading.has(id)) throw Error('runner_invocation_exists');
    if (guests.size + loading.size >= policy.invocations) throw Error('runner_invocation_capacity');
    if (!Number.isSafeInteger(message.sourceBytes) || message.sourceBytes < 0 || message.sourceBytes > policy.sourceBytes ||
        typeof message.sourceHash !== 'string' || !/^[0-9a-f]{64}$/.test(message.sourceHash) ||
        typeof message.definition !== 'string' || !message.definition.length || message.definition.length > 256 ||
        !['generator', 'offers'].includes(message.mode)) throw Error('runner_invalid_source');
    loading.set(id, { ...message, source: Buffer.alloc(message.sourceBytes), offset: 0 });
    return { result: null, cpuMicros: 0 };
  }
  if (message.command === 'chunk') {
    const pending = loading.get(id);
    if (!pending || message.offset !== pending.offset || typeof message.bytes !== 'string' || message.bytes.length > 4 * Math.ceil(policy.sourceChunkBytes / 3) ||
        !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(message.bytes)) throw Error('runner_invalid_source_chunk');
    const bytes = Buffer.from(message.bytes, 'base64');
    if (!bytes.length || pending.offset + bytes.length > pending.source.length) throw Error('runner_invalid_source_chunk');
    bytes.copy(pending.source, pending.offset); pending.offset += bytes.length;
    return { result: null, cpuMicros: 0 };
  }
  if (message.command === 'create') {
    const pending = loading.get(id);
    if (!pending || pending.offset !== pending.source.length || createHash('sha256').update(pending.source).digest('hex') !== pending.sourceHash)
      throw Error('runner_source_mismatch');
    const source = pending.source.toString('utf8');
    if (!Buffer.from(source).equals(pending.source)) throw Error('runner_invalid_utf8');
    loading.delete(id);
    const guest = await GuestInvocation.create({ ...pending, source });
    guests.set(id, guest);
    return { result: null, cpuMicros: guest.cpuMicros };
  }
  if (message.command === 'dispose') {
    guests.get(id)?.dispose(); guests.delete(id); loading.delete(id);
    return { result: null, cpuMicros: 0 };
  }
  const guest = guests.get(id);
  if (!guest) throw Error('runner_invocation_unknown');
  const result = message.command === 'resume' ? guest.resume(message.input) : guest.offers(message.input);
  if (message.command === 'resume' && result.result.done) { guest.dispose(); guests.delete(id); }
  return result;
}
async function drain() {
  if (processing) return;
  processing = true;
  try {
    while (queue.length) {
      const message = queue.shift();
      let performed;
      try { performed = await perform(message); await writeFrame(process.stdout, { requestId: message.requestId, ok: true, ...performed }); }
      catch (error) {
        guests.get(message.invocation)?.dispose(); guests.delete(message.invocation); loading.delete(message.invocation);
        const detail = error.detail ?? { message: String(error.message ?? 'runner_failed').slice(0, 256) };
        await writeFrame(process.stdout, { requestId: message.requestId, ok: false, error: detail,
          cpuMicros: Number.isSafeInteger(error.cpuMicros) && error.cpuMicros >= 0 ? error.cpuMicros : performed?.cpuMicros ?? 0 });
      }
    }
  } finally { processing = false; }
}
function fatal() { process.exit(1); }
const decoder = new FrameDecoder(message => {
  validate(message);
  if (queue.length >= policy.queuedControls) throw Error('runner_queue_capacity');
  queue.push(message); drain().catch(fatal);
});
process.stdin.on('data', chunk => { try { decoder.push(chunk); } catch { fatal(); } });
process.stdin.on('end', () => process.exit(0));
process.stdin.on('error', fatal); process.stdout.on('error', fatal);
await getQuickJS();
await writeFrame(process.stdout, { hello: 1, pid: process.pid, node: process.version, quickjs: quickjsPackage.version });
