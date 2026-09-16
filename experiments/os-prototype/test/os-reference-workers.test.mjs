import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { setTimeout as delay } from 'node:timers/promises';
import { DefinitionLibrary } from '../src/os/library.mjs';
import { LibraryValidator } from '../src/os/validation.mjs';
import { InvocationBroker } from '../src/os/broker.mjs';
import { RunnerPool } from '../src/os/runners.mjs';
import { InstallationHost } from '../src/os/installations.mjs';
import { ConditionWaits } from '../src/os/waits.mjs';
import { BehaviorLoop } from '../src/os/loop.mjs';
import { WorkerService } from '../src/os/workers.mjs';
import { HttpWorkers } from '../src/os/worker-http.mjs';
import { createReferenceWorkers } from '../examples/workers.mjs';

test('a validated reusable strategy behavior rejects an invented target from actual HTTP inference', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'airicraft-worker-reference-'));
  const requests = [];
  const server = createServer(async (req, res) => {
    let body = ''; for await (const part of req) body += part;
    requests.push(JSON.parse(body));
    res.end(JSON.stringify({ choices: [{ finish_reason: 'stop', message: { role: 'assistant', content: JSON.stringify({ value: {
      action: 'choose_alternative', targetId: 'not-an-eligible-target', explanation: 'Invented by the fixture.'
    } }) } }] }));
  });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  const library = await DefinitionLibrary.open(join(directory, 'library')), invocations = new InvocationBroker(), runners = new RunnerPool({ invocations });
  const validator = new LibraryValidator({ library, invocations, runners, environment: {}, grants: [] });
  const host = new InstallationHost({ library, invocations, runners, environment: {}, grants: [], runId: 'worker-http-reference', refresh: async () => null });
  const workers = new WorkerService({ installations: host, invocations, runners, observations: {
    workerEvidence: () => ({ epoch: 'world', signature: 'eligible-one', captures: ['frame-1'], progress: null })
  }, transport: new HttpWorkers({ reference: { endpoint: `http://127.0.0.1:${server.address().port}/v1/chat/completions`, model: 'fixture', apiKey: '' } }) });
  const loop = new BehaviorLoop({ installations: host, invocations, runners, workers, waits: new ConditionWaits({ invocations, epoch: 'world', scopes: {} }) });
  try {
    const definitions = await createReferenceWorkers({ library, validator, profile: 'reference' });
    const input = { failure: { fingerprint: 'blocked-route', reason: 'Route was obstructed.' }, progress: 'No progress.',
      alternatives: [{ id: 'eligible-sheep', description: 'A different sheep within the pen.' }] };
    const root = (await host.install(definitions.choose_strategy, { input })).rootId; loop.attach(root);
    const deadline = performance.now() + 5000;
    while (!invocations.inspect(root).outcome) { loop.tick(); assert.ok(performance.now() < deadline); await delay(5); }
    const result = invocations.inspect(root).outcome.value;
    assert.equal(result.status, 'fallback'); assert.equal(result.reason, 'worker_invalid_alternative');
    assert.equal(result.value.action, 'defer'); assert.equal(result.value.targetId, '');
    assert.equal(result.evidence.interpretation, true);
    assert.equal(requests.length, 1); assert.deepEqual(requests[0].tools, []);
    assert.equal(invocations.activity(), null); assert.equal(invocations.capacity().retainedChildren, 0);
    assert.equal((await library.get(definitions.comment_structure)).state.validated, true);
    assert.equal((await library.get(definitions.choose_strategy)).state.validated, true);
  } finally {
    loop.close(); workers.close(); await host.close(); await runners.close();
    server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); await rm(directory, { recursive: true, force: true });
  }
});
