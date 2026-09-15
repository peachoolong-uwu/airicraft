import { compileContract } from './contracts.mjs';
import { canonicalJson } from './content.mjs';
import { copyMessage } from './value.mjs';
import { executionPolicy } from './execution-policy.mjs';

/** Offline declarations are replayed in the same supervised pool, without any world-effect dispatcher. */
export class LibraryValidator {
  #library;
  #invocations;
  #runners;
  #environment;
  #grants;
  #busy = false;

  constructor({ library, invocations, runners, environment, grants }) {
    this.#library = library; this.#invocations = invocations; this.#runners = runners;
    this.#environment = copyMessage(environment); this.#grants = copyMessage(grants);
  }
  async validate(digest, { artifacts = [] } = {}) {
    if (this.#busy) throw Error('validation_busy');
    artifacts = copyMessage(artifacts);
    if (!Array.isArray(artifacts) || artifacts.length > 32 || artifacts.some(item => typeof item !== 'string' || item.length > 256)) throw Error('invalid_validation_artifacts');
    this.#busy = true;
    const evidence = { passed: false, scope: 'deterministic', liveQualified: false, inferenceQualified: false,
      policy: executionPolicy.version, nodeVersion: process.version, checks: [], cases: [], artifacts };
    try {
      const existing = await this.#library.get(digest);
      if (existing.state.installations.length) throw Error('definition_installed');
      try {
        const { root } = await this.#library.resolve(digest, { grants: this.#grants, environment: this.#environment, allowCandidate: true });
        const value = root.definition;
        evidence.checks.push('schema', 'locked_dependencies', 'capabilities', 'environment');
        if (!value.examples.length) throw Error('examples_required');
        if (value.kind === 'behavior') await this.#behavior(digest, value, evidence);
        else {
          const input = compileContract(value.inputContract), output = compileContract(value.outputContract);
          for (const example of value.examples) { input(example.input); output(example.result); }
          evidence.checks.push('worker_example_contracts', 'fallback_contract');
        }
        evidence.passed = true;
      } catch (error) { evidence.reason = String(error.message).slice(0, 256); }
      await this.#library.recordValidation(digest, evidence);
      return copyMessage(evidence);
    } finally { this.#busy = false; }
  }
  async #behavior(digest, definition, evidence) {
    const validateInput = compileContract(definition.inputContract);
    const rootId = this.#invocations.install({ definition: digest, grants: [], failurePolicy: 'collect_all' });
    try {
      await this.#runners.open(rootId);
      for (let index = 0; index < definition.examples.length; index++) {
        const example = definition.examples[index], input = validateInput(example.input);
        const child = this.#invocations.spawn(rootId, { definition: digest, grants: [], outputContract: definition.outputContract });
        try {
          await this.#runners.create(child.id, { definition: digest, source: definition.source, mode: definition.mode, input });
          const responses = definition.mode === 'generator' ? [null, ...example.responses] : example.responses;
          for (let step = 0; step < responses.length; step++) {
            const result = definition.mode === 'generator' ? await this.#runners.resume(child.id, responses[step]) : await this.#runners.offers(child.id, responses[step]);
            const outcome = this.#invocations.inspect(child.id).outcome;
            if (outcome && outcome.status !== 'success') throw Error(outcome.cause.reason);
            if (canonicalJson(result.result) !== canonicalJson(example.expected[step])) throw Object.assign(Error('example_mismatch'), { caseIndex: index, step });
          }
          evidence.cases.push({ index, passed: true, steps: responses.length });
        } finally {
          // Failed roots can already have consumed their children's mandatory joins.
          if (this.#invocations.inspect(rootId).phase === 'running') {
            this.#runners.cancel(child.id, 'example_finished');
            const result = this.#invocations.join(rootId, child);
            if (result.status === 'pending') throw Error('validation_cleanup_unresolved');
          }
        }
      }
      evidence.checks.push('sandbox_initialization', 'deterministic_examples', 'output_contract');
    } finally {
      this.#invocations.cancel(rootId, 'validation_finished');
      try { await this.#runners.retire(rootId); }
      catch (error) { if (error.message !== 'runner_root_unknown') throw error; }
      this.#invocations.uninstall(rootId);
    }
  }
}
