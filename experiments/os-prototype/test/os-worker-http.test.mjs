import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { HttpWorkers } from '../src/os/worker-http.mjs';

async function server(run, handler) {
  const requests = [];
  const http = createServer(async (req, res) => {
    let body = ''; for await (const part of req) body += part;
    requests.push({ url: req.url, headers: req.headers, body: JSON.parse(body) });
    if (handler) return handler(req, res);
    res.setHeader('content-type', 'application/json');
    res.end(JSON.stringify({ model: 'test-model', choices: [{ finish_reason: 'stop', message: { role: 'assistant', content: '{"value":"a small pen"}' } }],
      usage: { prompt_tokens: 17, completion_tokens: 8, total_tokens: 25 } }));
  });
  http.listen(0, '127.0.0.1'); await once(http, 'listening');
  try { await run({ endpoint: `http://127.0.0.1:${http.address().port}/v1/chat/completions`, requests }); }
  finally { http.closeAllConnections(); await new Promise(resolve => http.close(resolve)); }
}
const request = { prompt: 'Describe the provided structure.', input: { blocks: 4 }, outputContract: { type: 'string', maxLength: 200 } };
const reply = value => ({ choices: [{ finish_reason: 'stop', message: { role: 'assistant', content: JSON.stringify({ value }) } }] });

test('HTTP workers send fresh text-only inference and parse bounded JSON values and usage', async () => server(async ({ endpoint, requests }) => {
  const workers = new HttpWorkers({ describe: { endpoint, model: 'test-model', apiKey: 'test-secret' } });
  const result = await workers.complete('describe', request, new AbortController().signal);
  assert.equal(result.value, 'a small pen');
  assert.deepEqual(result.usage, { inputTokens: 17, outputTokens: 8, totalTokens: 25 });
  assert.ok(result.latency.providerMillis >= 0); assert.ok(result.latency.validationMillis >= 0);
  await workers.complete('describe', { ...request, input: { blocks: 9 } }, new AbortController().signal);
  assert.equal(requests.length, 2);
  for (const { body, headers, url } of requests) {
    assert.equal(url, '/v1/chat/completions'); assert.equal(headers.authorization, 'Bearer test-secret');
    assert.deepEqual(body.tools, []); assert.equal(body.tool_choice, 'none');
    assert.equal(body.max_completion_tokens, 512); assert.equal(body.stream, false);
    assert.deepEqual(body.messages.map(message => message.role), ['system', 'user']);
    assert.equal(typeof body.messages[0].content, 'string');
  }
  assert.equal(JSON.parse(requests[1].body.messages[1].content).blocks, 9);
  assert.ok(!JSON.stringify(workers.profile('describe')).includes('test-secret'));
}));

test('malformed envelopes and invalid UTF-8 cannot become worker interpretations', async () => {
  const tool = reply('valid-looking'); tool.choices[0].message.tool_calls = {};
  const length = reply('valid-looking'); length.choices[0].finish_reason = 'length';
  const invalidUtf8 = Buffer.concat([Buffer.from('{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"{\\"value\\":\\"'),
    Buffer.from([0xc3, 0x28]), Buffer.from('\\"}"}}]}')]);
  for (const body of ['null', JSON.stringify(tool), JSON.stringify(length), invalidUtf8]) await server(async ({ endpoint }) => {
    const workers = new HttpWorkers({ test: { endpoint, model: 'test', apiKey: '' } });
    await assert.rejects(workers.complete('test', request), /worker_malformed/);
  }, (_, res) => res.end(body));
});

test('response limits are enforced during streaming and cancellation and redirects never produce retries', async () => {
  await server(async ({ endpoint }) => {
    const workers = new HttpWorkers({ test: { endpoint, model: 'test', apiKey: '' } });
    await assert.rejects(workers.complete('test', request), /worker_response_limit/);
  }, (_, res) => { res.writeHead(200); res.write(' '.repeat(8193)); });
  await server(async ({ endpoint, requests }) => {
    const workers = new HttpWorkers({ test: { endpoint, model: 'test', apiKey: '' } });
    const controller = new AbortController(), call = workers.complete('test', request, controller.signal);
    while (!requests.length) await new Promise(resolve => setTimeout(resolve, 1));
    controller.abort(); await assert.rejects(call, /worker_cancelled/);
    assert.equal(requests.length, 1);
  }, () => {});
  await server(async ({ endpoint, requests }) => {
    const workers = new HttpWorkers({ test: { endpoint, model: 'test', apiKey: 'private' } });
    await assert.rejects(workers.complete('test', request), /worker_transport_failure/);
    assert.equal(requests.length, 1);
  }, (req, res) => { res.writeHead(302, { location: `http://${req.headers.host}/redirected` }); res.end(); });
});

test('output schemas, byte limits and reported token ceilings survive provider noncompliance', async () => {
  const wrong = reply(42), oversized = reply('x'.repeat(4097)), tooManyTokens = reply('fine');
  tooManyTokens.usage = { prompt_tokens: 1, completion_tokens: 513, total_tokens: 514 };
  for (const [body, code] of [[wrong, 'worker_invalid_output'], [oversized, 'worker_invalid_output'], [tooManyTokens, 'worker_token_limit']]) {
    await server(async ({ endpoint }) => {
      const workers = new HttpWorkers({ test: { endpoint, model: 'test', apiKey: '' } });
      await assert.rejects(workers.complete('test', { ...request, outputContract: { type: 'string' } }), { message: code });
    }, (_, res) => res.end(JSON.stringify(body)));
  }
});

test('inconsistent accounting cannot hide a reported token overrun or refund unknown usage', async () => {
  const excessive = reply('fine'); excessive.usage = { prompt_tokens: 1, completion_tokens: 900, total_tokens: 1 };
  await server(async ({ endpoint }) => {
    const workers = new HttpWorkers({ test: { endpoint, model: 'test', apiKey: '' } });
    await assert.rejects(workers.complete('test', request), /worker_token_limit/);
  }, (_, res) => res.end(JSON.stringify(excessive)));
  const unknown = reply('fine'); unknown.usage = { prompt_tokens: 1, completion_tokens: 3, total_tokens: 1 };
  await server(async ({ endpoint }) => {
    const workers = new HttpWorkers({ test: { endpoint, model: 'test', apiKey: '', tokenLimitField: 'max_tokens' } });
    assert.equal((await workers.complete('test', request)).usage, null);
    await assert.rejects(workers.complete('missing', request), /worker_unconfigured/);
  }, (_, res) => res.end(JSON.stringify(unknown)));
});
