import { executionPolicy as policy } from './execution-policy.mjs';

/** One budget per installed root; children never receive independent buckets. */
export class RootBudget {
  #now;
  #last;
  #cpu = policy.rootCpuBurstMicros;
  #messages = policy.rootEffectBurst;
  constructor(now = () => performance.now()) { this.#now = now; this.#last = now(); }
  reserve(cpuMicros, ordinary = true) {
    if (![policy.resumeCpuMicros, policy.initializationCpuMicros].includes(cpuMicros)) throw Error('invalid_runner_allowance');
    this.#refill();
    const count = ordinary ? 1 : 0;
    const waitMillis = Math.max(0, (cpuMicros - this.#cpu) / (policy.rootCpuMicrosPerSecond / 1000), (count - this.#messages) / (policy.rootEffectsPerSecond / 1000));
    if (waitMillis > 0) return { admitted: false, waitMillis: Math.ceil(waitMillis) };
    this.#cpu -= cpuMicros; this.#messages -= count;
    return { admitted: true, reservedMicros: cpuMicros };
  }
  charge(reservedMicros, actualMicros) {
    if (!Number.isSafeInteger(actualMicros) || actualMicros < 0) throw Error('invalid_runner_usage');
    this.#cpu = Math.min(policy.rootCpuBurstMicros, this.#cpu + reservedMicros - actualMicros);
  }
  state() { this.#refill(); return { cpuMicros: this.#cpu, messages: this.#messages }; }
  #refill() {
    const now = this.#now(), elapsed = now - this.#last;
    if (!Number.isFinite(elapsed) || elapsed < 0) throw Error('runner_clock_invalid');
    this.#last = now;
    this.#cpu = Math.min(policy.rootCpuBurstMicros, this.#cpu + elapsed * (policy.rootCpuMicrosPerSecond / 1000));
    this.#messages = Math.min(policy.rootEffectBurst, this.#messages + elapsed * (policy.rootEffectsPerSecond / 1000));
  }
}
