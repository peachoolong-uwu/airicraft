/** Version this policy before calibrating or freezing an experiment. All sizes are bytes. */
export const executionPolicy = Object.freeze({
  version: 'os-execution-v1', nodeVersion: 'v26.7.0', quickjsVersion: '0.32.0',
  roots: 12, invocations: 32, offers: 32,
  guestHeapBytes: 4 * 1024 * 1024, guestStackBytes: 256 * 1024,
  sourceBytes: 64 * 1024, messageBytes: 16 * 1024, sourceChunkBytes: 8192,
  initializationCpuMicros: 250_000, resumeCpuMicros: 25_000,
  rootCpuMicrosPerSecond: 100_000, rootCpuBurstMicros: 250_000,
  rootEffectsPerSecond: 64, rootEffectBurst: 64, queuedControls: 64,
  responseMillis: 1000, rssSampleMillis: 250, rssReadMillis: 200,
  rootRssBytes: 256 * 1024 * 1024, groupRssBytes: 768 * 1024 * 1024,
  stderrBytes: 4096
});
