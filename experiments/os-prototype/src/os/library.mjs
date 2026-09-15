import { mkdir, open, readdir, rename, rm } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { randomUUID } from 'node:crypto';
import { copyMessage } from './value.mjs';
import { canonicalJson, contentDigest } from './content.mjs';
import { definitionValue, DEFINITION_BYTES, isDigest, resolveClosure } from './definition.mjs';

export { resolveClosure } from './definition.mjs';
const RECORD_BYTES = 32 * 1024, MAX_REVISIONS = 256, MAX_RECORDS = 128;
const RECORD_TRAVERSAL = Object.freeze({ maximumDepth: 20, maximumNodes: 4096 });
const identity = value => typeof value === 'string' && value.length > 0 && value.length <= 256;

async function readJson(path, maximum, traversal) {
  const file = await open(path, 'r'), buffer = Buffer.alloc(maximum + 1);
  try {
    let offset = 0;
    while (offset < buffer.length) {
      const { bytesRead } = await file.read(buffer, offset, buffer.length - offset, offset);
      if (!bytesRead) break;
      offset += bytesRead;
    }
    if (offset > maximum) throw Error('library_file_limit');
    const bytes = buffer.subarray(0, offset), text = bytes.toString('utf8');
    if (!Buffer.from(text).equals(bytes)) throw Error('library_invalid_utf8');
    return copyMessage(JSON.parse(text), maximum, traversal);
  } finally { await file.close(); }
}
async function writeJson(path, value, maximum, traversal) {
  const text = canonicalJson(value, maximum, traversal), file = await open(path, 'wx', 0o600);
  try { await file.writeFile(text); await file.sync(); }
  finally { await file.close(); }
}

/** A bounded Git-friendly content store. Lifecycle records are host evidence, never guest authority. */
export class DefinitionLibrary {
  #directory;
  #queue = Promise.resolve();
  static async open(directory) {
    directory = resolve(directory);
    await mkdir(join(directory, 'revisions'), { recursive: true, mode: 0o700 });
    return new DefinitionLibrary(directory);
  }
  constructor(directory) { this.#directory = directory; }
  candidate(value, proposal) {
    return this.#exclusive(async () => {
      const definition = definitionValue(value), digest = contentDigest(definition, DEFINITION_BYTES);
      proposal = copyMessage(proposal);
      if (!proposal || !identity(proposal.reason) || typeof proposal.hypothesis !== 'string' || proposal.hypothesis.length > 4096 ||
          (proposal.oldDigest !== undefined && !isDigest(proposal.oldDigest))) throw Error('invalid_revision_proposal');
      try { return await this.get(digest); } catch (error) { if (error.code !== 'ENOENT') throw error; }
      if ((await this.#digests()).length >= MAX_REVISIONS) throw Error('library_revision_capacity');
      const temporary = join(this.#directory, `.candidate-${randomUUID()}`);
      await mkdir(join(temporary, 'records'), { recursive: true, mode: 0o700 });
      try {
        await writeJson(join(temporary, 'definition.json'), definition, DEFINITION_BYTES);
        await this.#writeRecord(temporary, digest, 1, null, { type: 'candidate', proposal });
        try { await rename(temporary, this.#path(digest)); }
        catch (error) { if (!['EEXIST', 'ENOTEMPTY'].includes(error.code)) throw error; }
      } finally { await rm(temporary, { recursive: true, force: true }); }
      return this.get(digest);
    });
  }
  async get(digest) {
    const directory = this.#path(digest), definition = definitionValue(await readJson(join(directory, 'definition.json'), DEFINITION_BYTES));
    if (contentDigest(definition, DEFINITION_BYTES) !== digest) throw Error('definition_integrity');
    return { digest, definition, state: await this.#state(directory, digest) };
  }
  resolve(digest, options) { return resolveClosure(digest, { ...options, load: id => this.get(id) }); }
  async list({ name, tags = [], capabilities = [], environment, inputType, outputType, cursor = 0, limit = 32 } = {}) {
    if (!Number.isSafeInteger(cursor) || cursor < 0 || !Number.isSafeInteger(limit) || limit < 1 || limit > 32 ||
        !Array.isArray(tags) || tags.length > 32 || tags.some(tag => !identity(tag)) ||
        !Array.isArray(capabilities) || capabilities.length > 64 || capabilities.some(capability => !identity(capability)) ||
        (name !== undefined && (typeof name !== 'string' || name.length > 128)) ||
        [inputType, outputType].some(value => value !== undefined && !['null', 'boolean', 'number', 'integer', 'string', 'array', 'object'].includes(value)) ||
        (environment !== undefined && (!environment || typeof environment !== 'object' || Array.isArray(environment) ||
          Object.keys(environment).length > 32 || Object.values(environment).some(value => !identity(value))))) throw Error('invalid_library_query');
    if (environment !== undefined) environment = copyMessage(environment);
    const digests = await this.#digests(), items = [];
    let index = cursor;
    while (index < digests.length && items.length < limit) {
      const record = await this.get(digests[index++]), value = record.definition;
      if (name !== undefined && !value.name.includes(name) || tags.some(tag => !value.tags.includes(tag)) ||
          capabilities.some(capability => !value.capabilities.includes(capability)) ||
          environment !== undefined && Object.entries(value.environment).some(([key, expected]) => environment[key] !== expected) ||
          inputType !== undefined && value.inputContract?.type !== inputType || outputType !== undefined && value.outputContract?.type !== outputType) continue;
      items.push({ digest: record.digest, name: value.name, description: value.description, kind: value.kind, tags: value.tags,
        capabilities: value.capabilities, environment: value.environment, inputContract: value.inputContract, outputContract: value.outputContract, phase: record.state.phase });
    }
    return { items, cursor: index < digests.length ? index : null };
  }
  recordValidation(digest, evidence) {
    return this.#append(digest, state => {
      evidence = copyMessage(evidence);
      if (typeof evidence?.passed !== 'boolean' || !Array.isArray(evidence.checks) || evidence.checks.length > 32 || !Array.isArray(evidence.artifacts) || evidence.artifacts.length > 32) throw Error('invalid_validation_evidence');
      if (state.installations.length) throw Error('definition_installed');
      return { type: 'validation', evidence };
    });
  }
  recordInstall(digest, installationId, binding) {
    return this.#append(digest, state => {
      if (!state.validated) throw Error('definition_unvalidated');
      binding = copyMessage(binding, RECORD_BYTES, RECORD_TRAVERSAL);
      if (!identity(installationId) || !identity(binding?.runId) || !identity(binding?.rootId) || !isDigest(binding?.inputDigest) ||
          !Array.isArray(binding.grants) || binding.grants.length > 64 || binding.grants.some(grant => !identity(grant))) throw Error('invalid_installation_record');
      const previous = state.installations.find(item => item.id === installationId);
      if (previous) {
        if (canonicalJson(previous.binding, RECORD_BYTES, RECORD_TRAVERSAL) !== canonicalJson(binding, RECORD_BYTES, RECORD_TRAVERSAL)) throw Error('installation_conflict');
        return null;
      }
      if (state.retired.includes(installationId)) throw Error('installation_reused');
      // Every active binding must retain space for its eventual retirement evidence.
      if (state.records + state.installations.length + 2 > MAX_RECORDS) throw Error('library_record_capacity');
      return { type: 'installed', installationId, binding };
    });
  }
  recordRetired(digest, installationId, outcome) {
    return this.#append(digest, state => {
      outcome = copyMessage(outcome, RECORD_BYTES, RECORD_TRAVERSAL);
      if (!identity(installationId) || !['success', 'failure', 'cancelled'].includes(outcome?.status) || outcome.released !== true) throw Error('invalid_retirement_evidence');
      const previous = state.retirementDigests[installationId];
      if (previous) {
        if (previous !== contentDigest(outcome, RECORD_BYTES, RECORD_TRAVERSAL)) throw Error('retirement_conflict');
        return null;
      }
      if (!state.installations.some(item => item.id === installationId)) throw Error('installation_unknown');
      return { type: 'retired', installationId, outcome };
    });
  }
  #path(digest) {
    if (!isDigest(digest)) throw Error('invalid_definition_digest');
    return join(this.#directory, 'revisions', digest.slice(7));
  }
  async #digests() {
    const entries = await readdir(join(this.#directory, 'revisions'), { withFileTypes: true });
    const ids = entries.filter(entry => entry.isDirectory() && /^[0-9a-f]{64}$/.test(entry.name)).map(entry => `sha256:${entry.name}`).sort();
    if (ids.length > MAX_REVISIONS) throw Error('library_revision_capacity');
    return ids;
  }
  #append(digest, makeEvent) {
    return this.#exclusive(async () => {
      const record = await this.get(digest), event = makeEvent(record.state);
      if (!event) return record;
      if (record.state.records >= MAX_RECORDS) throw Error('library_record_capacity');
      await this.#writeRecord(this.#path(digest), digest, record.state.records + 1, record.state.lastRecord, event);
      return this.get(digest);
    });
  }
  async #writeRecord(directory, digest, sequence, previous, event) {
    const record = { schemaVersion: 1, definition: digest, sequence, previous, atMillis: Date.now(), event };
    const recordDigest = contentDigest(record, RECORD_BYTES, RECORD_TRAVERSAL);
    try { await writeJson(join(directory, 'records', `${String(sequence).padStart(6, '0')}.json`), { ...record, recordDigest }, RECORD_BYTES, RECORD_TRAVERSAL); }
    catch (error) { if (error.code === 'EEXIST') throw Error('library_state_conflict'); throw error; }
  }
  async #state(directory, digest) {
    const names = (await readdir(join(directory, 'records'))).filter(name => /^\d{6}\.json$/.test(name)).sort();
    if (!names.length || names.length > MAX_RECORDS) throw Error('library_record_invalid');
    const state = { phase: 'candidate', validated: false, installations: [], retired: [], retirementDigests: Object.create(null), records: 0, lastRecord: null };
    try {
      for (let index = 0; index < names.length; index++) {
        const full = await readJson(join(directory, 'records', names[index]), RECORD_BYTES, RECORD_TRAVERSAL);
        const { recordDigest, ...record } = full;
        if (names[index] !== `${String(index + 1).padStart(6, '0')}.json` || record.schemaVersion !== 1 || record.definition !== digest ||
            record.sequence !== index + 1 || record.previous !== state.lastRecord || recordDigest !== contentDigest(record, RECORD_BYTES, RECORD_TRAVERSAL)) throw Error('library_record_invalid');
        const event = record.event;
        if (index === 0 && event.type !== 'candidate') throw Error('library_record_invalid');
        if (event.type === 'validation') { state.validated = event.evidence.passed === true; state.phase = state.validated ? 'validated' : 'retired'; }
        else if (event.type === 'installed') { state.installations.push({ id: event.installationId, binding: event.binding }); state.phase = 'installed'; }
        else if (event.type === 'retired') {
          state.installations = state.installations.filter(item => item.id !== event.installationId); state.retired.push(event.installationId);
          state.retirementDigests[event.installationId] = contentDigest(event.outcome, RECORD_BYTES, RECORD_TRAVERSAL);
          if (!state.installations.length) state.phase = 'retired';
        } else if (event.type !== 'candidate' || index !== 0) throw Error('library_record_invalid');
        state.records++; state.lastRecord = recordDigest;
      }
    } catch (error) { throw Object.assign(Error('library_record_invalid'), { cause: error.message }); }
    return state;
  }
  #exclusive(operation) {
    const result = this.#queue.then(operation);
    this.#queue = result.catch(() => {});
    return result;
  }
}
