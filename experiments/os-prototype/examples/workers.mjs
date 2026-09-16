/** Offline reference definitions. Creating this pack validates code; it never configures or calls a model. */
export async function createReferenceWorkers({ library, validator, profile }) {
  const text = maximum => ({ type: 'string', maxLength: maximum });
  const object = properties => ({ type: 'object', properties, required: Object.keys(properties), additionalProperties: false });
  const common = { schemaVersion: 1, tags: ['reference', 'worker'], capabilities: [], environment: {}, dependencies: {} };
  const stage = async value => {
    const revision = await library.candidate({ ...common, ...value }, { reason: 'OS callable-worker reference',
      hypothesis: 'Pinned pure fallbacks and candidate validation keep interpretation separate from action admission.' });
    const evidence = await validator.validate(revision.digest);
    if (!evidence.passed) throw Error(`reference_worker_validation_failed:${evidence.reason}`);
    return revision.digest;
  };
  const descriptionInput = object({ name: text(128), description: text(2048) });
  const structure = { name: 'sheep pen', description: 'A fenced grass area with two sheep.' };
  const descriptionFallback = 'Structure description unavailable.';
  const describeFallback = await stage({ kind: 'behavior', name: 'describe-fallback', description: 'Pure unavailable-description result.',
    inputContract: descriptionInput, outputContract: text(1024), mode: 'generator', source: `function* main() { return ${JSON.stringify(descriptionFallback)}; }`,
    examples: [{ input: structure, responses: [], expected: [{ done: true, value: descriptionFallback }] }] });
  const describe = await stage({ kind: 'worker', name: 'comment_structure', description: 'Interpret only the supplied structure description.',
    inputContract: descriptionInput, outputContract: text(1024), dependencies: { fallback: describeFallback }, fallback: 'fallback', profile,
    prompt: 'Briefly describe the supplied structure. Do not invent blocks, occupants or functions that the evidence does not support. Mark uncertainty.',
    examples: [{ input: structure, result: 'A small fenced sheep pen.' }] });
  const strategyInput = object({ failure: object({ fingerprint: text(256), reason: text(512) }), progress: text(512),
    alternatives: { type: 'array', maxItems: 16, items: object({ id: { type: 'string', minLength: 1, maxLength: 128 }, description: text(256) }) } });
  const strategyOutput = object({ action: { type: 'string', enum: ['retry', 'choose_alternative', 'defer'] }, targetId: text(128), explanation: text(512) });
  const input = { failure: { fingerprint: 'blocked-route', reason: 'Route obstructed.' }, progress: 'No progress.', alternatives: [{ id: 'sheep-1', description: 'An eligible sheep.' }] };
  const deferred = { action: 'defer', targetId: '', explanation: 'No fresh permitted worker recommendation.' };
  const defer = await stage({ kind: 'behavior', name: 'strategy-fallback', description: 'Pure defer result; no action is implied.',
    inputContract: strategyInput, outputContract: strategyOutput, mode: 'generator', source: `function* main() { return ${JSON.stringify(deferred)}; }`,
    examples: [{ input, responses: [], expected: [{ done: true, value: deferred }] }] });
  const strategy = await stage({ kind: 'worker', name: 'interpret_strategy', description: 'Interpret a failure and bounded candidate set.',
    inputContract: strategyInput, outputContract: strategyOutput, dependencies: { fallback: defer }, fallback: 'fallback', profile,
    reconsideration: { fingerprint: ['failure', 'fingerprint'] },
    prompt: 'Choose retry, choose_alternative or defer from the supplied failure and progress evidence. For choose_alternative, targetId must exactly match a supplied alternative id. Otherwise targetId must be empty. Explain briefly. This is advice only; the OS must recheck eligibility before any action.',
    examples: [{ input, result: deferred }] });
  const accepted = { status: 'success', value: { action: 'choose_alternative', targetId: 'sheep-1', explanation: 'Try the other eligible sheep.' } };
  const invented = { ...accepted, value: { ...accepted.value, targetId: 'invented' } };
  const rejected = { ...invented, status: 'fallback', reason: 'worker_invalid_alternative', value: deferred };
  const selection = await stage({ kind: 'behavior', name: 'choose_strategy', description: 'Return a worker interpretation only when its chosen target belongs to the supplied candidate set.',
    inputContract: strategyInput, outputContract: true, mode: 'generator', dependencies: { interpret: strategy },
    source: `function* main(os,input) {
      const result = yield os.worker("interpret",input);
      if (result.status !== "success" && result.status !== "fallback") return result;
      const advice = result.value;
      const permitted = advice.action === "choose_alternative"
        ? input.alternatives.some(candidate => candidate.id === advice.targetId) : advice.targetId === "";
      return permitted ? result : { ...result, status: "fallback", reason: "worker_invalid_alternative", value: ${JSON.stringify(deferred)} };
    }`, examples: [accepted, invented].map(result => ({ input, responses: [result], expected: [
      { done: false, value: { kind: 'worker', definition: 'interpret', input } }, { done: true, value: result === accepted ? accepted : rejected }
    ] })) });
  return { comment_structure: describe, interpret_strategy: strategy, choose_strategy: selection };
}
