import { randomUUID } from 'node:crypto';
import { copyMessage } from './value.mjs';
import { compileContract } from './contracts.mjs';
import { contentDigest } from './content.mjs';
import { executionPolicy } from './execution-policy.mjs';
import { DEFINITION_BYTES } from './definition.mjs';

/** Pins source and input identity; the invocation broker remains the authority for physical retirement. */
export class InstallationHost {
  #library;
  #invocations;
  #runners;
  #environment;
  #grants;
  #runId;
  #refresh;
  #trace;
  #records = new Map();
  #roots = new Map();
  #starts = new Set();
  #advancing = null;
  #closed = false;

  constructor({ library, invocations, runners, environment, grants, runId, refresh, trace }) {
    if (typeof runId !== 'string' || !runId.length || runId.length > 256 || typeof refresh !== 'function') throw Error('invalid_installation_host');
    this.#library = library; this.#invocations = invocations; this.#runners = runners; this.#trace = trace;
    this.#environment = copyMessage(environment); this.#grants = copyMessage(grants); this.#runId = runId; this.#refresh = refresh;
  }
  install(digest, options = {}) {
    if (this.#closed) return Promise.reject(Error('installations_closed'));
    if (this.#records.size >= executionPolicy.roots) return Promise.reject(Error('root_capacity'));
    const record = { id: randomUUID(), digest, rootId: null, phase: 'preparing', installAttempted: false, replacement: null, replacing: false, starting: true };
    this.#records.set(record.id, record);
    const started = this.#start(record, options);
    this.#starts.add(started);
    started.finally(() => this.#starts.delete(started)).catch(() => {});
    return started;
  }
  async replace(installationId, digest, options = {}) {
    const record = this.#get(installationId);
    if (record.phase !== 'installed' || record.replacing) throw Error('installation_stopping');
    record.replacing = true;
    try {
      const prepared = await this.#prepare(digest, { input: record.input, grants: record.grants, ...options });
      this.#check(record, 'installed');
      record.replacement = { digest, options: { input: prepared.input, grants: prepared.grants } };
      this.#stop(record, 'definition_replaced');
      return this.inspect(installationId);
    } finally { record.replacing = false; }
  }
  stop(installationId, reason = 'installation_stopped') {
    const record = this.#get(installationId);
    record.replacement = null;
    this.#stop(record, reason);
  }
  #stop(record, reason) {
    record.phase = 'draining';
    if (record.rootId) {
      this.#invocations.cancel(record.rootId, reason);
      try { this.#runners.cancel(record.rootId, reason); }
      catch (error) { if (error.message !== 'runner_root_unknown') throw error; }
    }
  }
  inspect(installationId) {
    const record = this.#get(installationId);
    return { installationId: record.id, digest: record.digest, rootId: record.rootId, inputDigest: record.inputDigest ?? null,
      phase: record.phase, invocation: record.rootId ? this.#invocations.inspect(record.rootId) : null };
  }
  describe(id, alias = null) {
    const { digest, definition } = alias === null ? this.#lookup(id) : this.#dependency(id, alias);
    return { digest, kind: definition.kind, mode: definition.mode ?? null };
  }
  workerDefinition(id, alias) {
    const worker = this.#dependency(id, alias);
    if (worker.definition.kind !== 'worker') throw Error('worker_definition_required');
    const digest = worker.definition.dependencies[worker.definition.fallback];
    const fallback = this.#lookup(id).record.closure.get(digest);
    if (worker.definition.capabilities.length || fallback.definition.capabilities.length || fallback.definition.mode !== 'generator')
      throw Error('worker_effects_forbidden');
    return copyMessage({ ...worker, fallback: { digest, definition: fallback.definition } }, DEFINITION_BYTES * 2);
  }
  async spawn(parentId, alias, input, options = {}) {
    const { digest, definition } = this.#dependency(parentId, alias);
    if (definition.kind !== 'behavior') throw Error('dependency_requires_worker_service');
    options = copyMessage(options);
    if (!options || typeof options !== 'object' || Array.isArray(options) || Object.keys(options).some(key => !['grants', 'failurePolicy'].includes(key)) ||
        Object.hasOwn(options, 'failurePolicy') && !['cancel_siblings', 'collect_all'].includes(options.failurePolicy)) throw Error('invalid_spawn_options');
    input = compileContract(definition.inputContract)(input);
    const grants = copyMessage(options.grants ?? definition.capabilities);
    if (!Array.isArray(grants) || grants.length > 64) throw Error('invalid_spawn_options');
    if (grants.some(grant => !definition.capabilities.includes(grant))) throw Error('capability_escalation');
    for (const grant of definition.capabilities) if (!grants.includes(grant)) throw Error('capability_missing');
    for (const grant of grants) this.#invocations.authorize(parentId, grant);
    const handle = this.#invocations.spawn(parentId, { definition: digest, grants, outputContract: definition.outputContract,
      ...(options.failurePolicy ? { failurePolicy: options.failurePolicy } : {}) });
    try { await this.#runners.create(handle.id, { definition: digest, source: definition.source, mode: definition.mode, input }); }
    catch (error) {
      try { this.#invocations.failed(handle.id, String(error.message).slice(0, 256)); }
      catch (failure) { if (failure.message !== 'invocation_unknown') throw failure; }
      throw error;
    }
    return handle;
  }
  join(parentId, handle) {
    this.#lookup(parentId);
    return this.#invocations.join(parentId, handle);
  }
  async resume(id, input = null) {
    const { definition } = this.#lookup(id);
    if (definition.mode !== 'generator') throw Error('definition_mode_mismatch');
    return this.#runners.resume(id, input);
  }
  async offers(id, view) {
    const { definition } = this.#lookup(id);
    if (definition.mode !== 'offers') throw Error('definition_mode_mismatch');
    return this.#runners.offers(id, view);
  }
  advance() {
    if (!this.#advancing) this.#advancing = this.#advance().finally(() => { this.#advancing = null; });
    return this.#advancing;
  }
  async close() {
    this.#closed = true;
    for (const record of this.#records.values()) { record.replacement = null; this.stop(record.id, 'host_closed'); }
    await Promise.allSettled([...this.#starts]);
    return this.advance();
  }
  async #prepare(digest, options) {
    options = copyMessage(options);
    if (Object.keys(options).some(key => !['input', 'grants'].includes(key))) throw Error('invalid_installation');
    const requested = options.grants ?? this.#grants;
    if (!Array.isArray(requested) || requested.length > 64 || requested.some(grant => !this.#grants.includes(grant))) throw Error('capability_escalation');
    const resolved = await this.#library.resolve(digest, { grants: requested, environment: this.#environment });
    const definition = resolved.root.definition;
    if (definition.kind !== 'behavior') throw Error('installed_root_requires_behavior');
    const input = compileContract(definition.inputContract)(options.input ?? null);
    return { definition, input, grants: definition.capabilities, closure: new Map(resolved.revisions.map(item => [item.digest, item])) };
  }
  async #start(record, options) {
    try {
      Object.assign(record, await this.#prepare(record.digest, options));
      this.#check(record, 'preparing');
      record.inputDigest = contentDigest(record.input);
      record.rootId = this.#invocations.install({ definition: record.digest, grants: record.grants, outputContract: record.definition.outputContract });
      this.#roots.set(record.rootId, record.id);
      await this.#runners.open(record.rootId);
      this.#check(record, 'preparing');
      await this.#runners.create(record.rootId, { definition: record.digest, source: record.definition.source, mode: record.definition.mode, input: record.input });
      this.#check(record, 'preparing');
      record.installAttempted = true;
      await this.#library.recordInstall(record.digest, record.id, { runId: this.#runId, rootId: record.rootId, input: record.input,
        inputDigest: record.inputDigest, grants: record.grants, closure: [...record.closure.keys()], environment: this.#environment,
        executionPolicy: contentDigest(executionPolicy) });
      this.#check(record, 'preparing');
      let timer;
      try {
        record.initialObservation = copyMessage(await Promise.race([
          Promise.resolve().then(() => this.#refresh(copyMessage(record.input))),
          new Promise((_, reject) => { timer = setTimeout(() => reject(Error('installation_observation_timeout')), 2000); })
        ]));
      } finally { clearTimeout(timer); }
      this.#check(record, 'preparing');
      this.#trace?.record('library.installed', { installationId: record.id, rootId: record.rootId, digest: record.digest,
        inputDigest: record.inputDigest, grants: record.grants, observation: record.initialObservation });
      record.phase = 'installed';
      return this.inspect(record.id);
    } catch (error) {
      record.phase = 'draining';
      if (record.rootId) {
        this.#invocations.failed(record.rootId, String(error.message).slice(0, 256));
        try { await this.#retire(record); }
        catch (cleanup) { error.cleanup = String(cleanup.message).slice(0, 256); }
      } else this.#records.delete(record.id);
      throw error;
    } finally { record.starting = false; }
  }
  async #advance() {
    const transitions = [];
    for (const record of [...this.#records.values()]) {
      if (record.starting || !record.rootId || record.replacing && record.phase === 'installed') continue;
      if (!this.#invocations.inspect(record.rootId).outcome) continue;
      record.phase = 'draining';
      const outcome = await this.#retire(record);
      const transition = { status: 'retired', installationId: record.id, digest: record.digest, rootId: record.rootId, outcome };
      if (record.replacement && !this.#closed) {
        try {
          transition.replacement = await this.install(record.replacement.digest, record.replacement.options);
          transition.status = 'replaced';
        } catch (error) { transition.status = 'replacement_failed'; transition.reason = String(error.message).slice(0, 256); }
      }
      transitions.push(transition);
    }
    return transitions;
  }
  async #retire(record) {
    const outcome = this.#invocations.inspect(record.rootId).outcome;
    if (!outcome) throw Error('invocation_unsettled');
    try { await this.#runners.retire(record.rootId); }
    catch (error) { if (error.message !== 'runner_root_unknown') throw error; }
    if (record.installAttempted) {
      // A failed metadata reply can follow a successful durable write. Resolve it before forgetting the root.
      const { state } = await this.#library.get(record.digest);
      if (state.installations.some(item => item.id === record.id) || state.retired.includes(record.id))
        await this.#library.recordRetired(record.digest, record.id, { ...outcome, released: true });
    }
    this.#trace?.record('library.retired', { installationId: record.id, rootId: record.rootId, digest: record.digest, outcome }, { cleanup: true });
    this.#invocations.uninstall(record.rootId);
    this.#roots.delete(record.rootId); this.#records.delete(record.id);
    return outcome;
  }
  #get(id) {
    const record = this.#records.get(id);
    if (!record) throw Error('installation_unknown');
    return record;
  }
  #check(record, phase) {
    if (this.#closed || record.phase !== phase || phase === 'preparing' && record.rootId && this.#invocations.inspect(record.rootId).phase !== 'running') throw Error('installation_stopping');
  }
  #lookup(id) {
    const owner = this.#invocations.execution(id), record = this.#get(this.#roots.get(owner.rootId));
    this.#check(record, 'installed');
    if (owner.phase !== 'running') throw Error('invocation_closing');
    return { record, digest: owner.definition, definition: record.closure.get(owner.definition).definition };
  }
  #dependency(id, alias) {
    const { record, definition } = this.#lookup(id);
    if (typeof alias !== 'string' || !Object.hasOwn(definition.dependencies, alias)) throw Error('dependency_not_declared');
    const digest = definition.dependencies[alias];
    return { digest, definition: record.closure.get(digest).definition };
  }
}
