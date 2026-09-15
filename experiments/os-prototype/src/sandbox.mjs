import { createHash } from 'node:crypto';
import { getQuickJS } from 'quickjs-emscripten';

const MAX_SOURCE = 64 * 1024;
const MAX_MESSAGE = 16 * 1024;

/** Guest JS has no host objects, callbacks, module loader, files, network, or credentials. */
export class BehaviorSandbox {
  static async create(source, config, { budgetMs = 25 } = {}) {
    if (typeof source !== 'string' || Buffer.byteLength(source) > MAX_SOURCE) throw Error('behavior_source_limit');
    const engine = await getQuickJS();
    const runtime = engine.newRuntime();
    runtime.setMemoryLimit(4 * 1024 * 1024);
    runtime.setMaxStackSize(256 * 1024);
    const context = runtime.newContext();
    const sandbox = new BehaviorSandbox(runtime, context, budgetMs);
    sandbox.revision = createHash('sha256').update(source).digest('hex');
    try {
      sandbox.evaluate(`
        const os = Object.freeze({
          observe: plot => ({ kind: 'observe', plot }),
          wait: plot => ({ kind: 'wait', plot }),
          sleep: ms => ({ kind: 'sleep', ms }),
          action: (name, args) => ({ kind: 'action', name, args }),
        });
        ${source}
        const __program = main(os, Object.freeze(${JSON.stringify(config)}));
        if (!__program || typeof __program.next !== 'function') throw Error('main_must_be_a_generator');
        true;
      `, 250);
      return sandbox;
    } catch (error) {
      sandbox.dispose();
      throw error;
    }
  }

  constructor(runtime, context, budgetMs) {
    this.runtime = runtime;
    this.context = context;
    this.budgetMs = budgetMs;
    this.deadline = 0;
    this.cpuStart = process.cpuUsage();
    this.cpuBudgetUs = budgetMs * 1000;
    runtime.setInterruptHandler(() => {
      const used = process.cpuUsage(this.cpuStart);
      return used.user + used.system >= this.cpuBudgetUs || performance.now() >= this.deadline;
    });
  }

  evaluate(code, budgetMs = this.budgetMs) {
    // CPU accounting avoids treating time descheduled by Minecraft/builds as guest computation.
    // A separate wall watchdog still bounds elapsed time; cold source initialization gets 250 ms CPU.
    this.cpuStart = process.cpuUsage();
    this.cpuBudgetUs = budgetMs * 1000;
    this.deadline = performance.now() + 1000;
    const result = this.context.evalCode(code, 'behavior.js');
    if (result.error) {
      // Dumping an untrusted exception could itself run guest getters. Keep the interrupt armed.
      let detail = 'guest_execution_failed';
      try { detail = JSON.stringify(this.context.dump(result.error)).slice(0, 1000); } catch { /* retain bounded error */ }
      result.error.dispose();
      throw Error(detail);
    }
    try {
      return this.context.getString(result.value);
    } finally {
      result.value.dispose();
    }
  }

  next(input = null) {
    const message = JSON.stringify(input);
    if (Buffer.byteLength(message) > MAX_MESSAGE) throw Error('behavior_input_limit');
    const encoded = this.evaluate(`JSON.stringify(__program.next(${message}))`);
    if (Buffer.byteLength(encoded) > MAX_MESSAGE) throw Error('behavior_output_limit');
    const result = JSON.parse(encoded);
    if (typeof result.done !== 'boolean') throw Error('invalid_generator_result');
    return result;
  }

  dispose() {
    this.context.dispose();
    this.runtime.dispose();
  }
}
