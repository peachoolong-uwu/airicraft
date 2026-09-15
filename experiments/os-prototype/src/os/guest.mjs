import { executionPolicy as policy } from './execution-policy.mjs';
import { getQuickJS } from 'quickjs-emscripten';
import { copyMessage } from './value.mjs';
import { guestKernel } from './guest-kernel.mjs';
import { guestResult } from './guest-effects.mjs';

/** One bounded VM, used only inside a supervised runner in the installed execution path. */
export class GuestInvocation {
  #runtime;
  #context;
  #functions = new Map();
  #mode;
  #start;
  #budget = 0;
  #deadline = 0;
  #interrupted = null;
  #failed = false;
  #finished = false;
  #disposed = false;
  #usage = 0;

  static async create({ source, input = null, definition, mode = 'generator' }) {
    if (typeof source !== 'string' || Buffer.byteLength(source) > policy.sourceBytes) throw Error('guest_source_limit');
    if (!['generator', 'offers'].includes(mode) || typeof definition !== 'string' || !definition.length || definition.length > 256) throw Error('invalid_guest_definition');
    input = JSON.stringify(copyMessage(input));
    const engine = await getQuickJS(), runtime = engine.newRuntime();
    runtime.setMemoryLimit(policy.guestHeapBytes); runtime.setMaxStackSize(policy.guestStackBytes);
    let context;
    try { context = runtime.newContext(); } catch (error) { runtime.dispose(); throw error; }
    const guest = new GuestInvocation(runtime, context, mode);
    try {
      guest.#measure(policy.initializationCpuMicros, () => {
        const controls = guest.#evaluate(`(${guestKernel.toString()})(${JSON.stringify({ messageBytes: policy.messageBytes })})`, 'os-kernel.js');
        try { for (const name of ['initialize', 'resume', 'offers', 'explain']) guest.#functions.set(name, guest.#context.getProp(controls, name)); }
        finally { controls.dispose(); }
        const handles = [];
        try {
          handles.push(guest.#evaluate(`(function(os, input) { "use strict";\n${source}\n;return { main: typeof main === 'function' ? main : null, offers: typeof offers === 'function' ? offers : null }; })`, `${definition}.js`));
          handles.push(guest.#context.newString(input));
          handles.push(guest.#context.newString(mode));
          guest.#invoke('initialize', handles);
        } finally { for (const handle of handles.reverse()) handle.dispose(); }
      });
      return guest;
    } catch (error) { guest.dispose(); throw error; }
  }
  constructor(runtime, context, mode) {
    this.#runtime = runtime; this.#context = context; this.#mode = mode;
    runtime.setInterruptHandler(() => {
      const cpu = process.cpuUsage(this.#start);
      if (cpu.user + cpu.system >= this.#budget) this.#interrupted = 'guest_cpu_limit';
      else if (performance.now() >= this.#deadline) this.#interrupted = 'guest_wall_limit';
      return this.#interrupted !== null;
    });
  }
  get cpuMicros() { return this.#usage; }
  resume(input = null) { return this.#run('generator', 'resume', input); }
  offers(input) { return this.#run('offers', 'offers', input); }
  dispose() {
    if (this.#disposed) return;
    this.#disposed = true;
    for (const handle of this.#functions.values()) handle.dispose();
    this.#functions.clear(); this.#context.dispose(); this.#runtime.dispose();
  }
  #run(mode, operation, input) {
    if (this.#failed) throw Error('guest_failed');
    if (this.#finished) throw Error('guest_finished');
    if (this.#disposed) throw Error('guest_disposed');
    if (mode !== this.#mode) throw Error('guest_mode_mismatch');
    input = JSON.stringify(copyMessage(input));
    try {
      const result = this.#measure(policy.resumeCpuMicros, () => {
        const handle = this.#context.newString(input);
        try { return guestResult(this.#invoke(operation, [handle]), mode); }
        finally { handle.dispose(); }
      });
      if (mode === 'generator' && result.done) this.#finished = true;
      return { result, cpuMicros: this.#usage };
    } catch (error) { this.#failed = true; throw error; }
  }
  #measure(budget, operation) {
    this.#start = process.cpuUsage(); this.#budget = budget;
    this.#deadline = performance.now() + policy.responseMillis; this.#interrupted = null;
    try {
      const value = operation();
      const cpu = process.cpuUsage(this.#start);
      if (this.#interrupted || cpu.user + cpu.system > budget) throw Error(this.#interrupted ?? 'guest_cpu_limit');
      return value;
    } catch (error) {
      const cpu = process.cpuUsage(this.#start);
      error.cpuMicros = cpu.user + cpu.system;
      throw error;
    } finally { const cpu = process.cpuUsage(this.#start); this.#usage = cpu.user + cpu.system; }
  }
  #evaluate(source, filename) {
    const result = this.#context.evalCode(source, filename);
    if (!result.error) return result.value;
    try { throw this.#error(result.error); }
    finally { result.error.dispose(); }
  }
  #invoke(name, args) {
    const result = this.#context.callFunction(this.#functions.get(name), this.#context.undefined, args);
    if (result.error) {
      try { throw this.#error(result.error); }
      finally { result.error.dispose(); }
    }
    try {
      // Only the closed kernel produces this handle, after checking UTF-8 size inside the VM.
      if (this.#context.typeof(result.value) !== 'string') throw Error('invalid_guest_envelope');
      return copyMessage(JSON.parse(this.#context.getString(result.value)));
    } finally { result.value.dispose(); }
  }
  #error(handle) {
    if (this.#interrupted) return Error(this.#interrupted);
    let detail = { message: 'guest_execution_failed' };
    if (this.#functions.has('explain')) {
      const result = this.#context.callFunction(this.#functions.get('explain'), this.#context.undefined, [handle]);
      if (result.error) result.error.dispose();
      else {
        try { detail = copyMessage(JSON.parse(this.#context.getString(result.value))); }
        finally { result.value.dispose(); }
      }
    }
    return Object.assign(Error(this.#interrupted ?? detail.message), { detail });
  }
}
