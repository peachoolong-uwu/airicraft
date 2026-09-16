export const workerPolicy = Object.freeze({ version: 'os-workers-v1', active: 2, queued: 16, calls: 12,
  runMillis: 30 * 60 * 1000, queueMillis: 5000, inferenceMillis: 20000, reconsiderTicks: 1200,
  inputBytes: 16 * 1024, outputBytes: 4 * 1024, responseBytes: 8 * 1024, outputTokens: 512, cache: 32 });
