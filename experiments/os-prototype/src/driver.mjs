import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import { WrapperStream } from './stream.mjs';

const execute = promisify(execFile);
const cli = fileURLToPath(new URL('../../../wrapper/build/install/airicraft/bin/airicraft', import.meta.url));
const terminal = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED']);

export function parseToolResult(output) {
  const match = /^result: (.*)$/m.exec(output);
  if (!match) throw Error('missing_tool_result');
  const result = match[1].replace(/^Tool result for [a-z_]+: /, '');
  return result.startsWith('{') ? JSON.parse(result) : result;
}

export function validateConfig(config) {
  const coordinate = n => Number.isSafeInteger(n) && Math.abs(n) < 30000000;
  const point = p => p && ['x', 'y', 'z'].every(key => coordinate(p[key]));
  if (!config || !Array.isArray(config.plots) || config.plots.length < 1 || config.plots.length > 3) throw Error('config_requires_1_to_3_plots');
  const ids = new Set();
  for (const plot of config.plots) {
    if (!/^[a-z][a-z0-9_-]{0,31}$/.test(plot.id) || ids.has(plot.id)) throw Error('invalid_plot_id');
    ids.add(plot.id);
    if (!['minecraft:wheat_seeds', 'minecraft:potato', 'minecraft:carrot'].includes(plot.seedItemId)) throw Error('unsupported_crop');
    if (!['x1', 'y', 'z1', 'x2', 'z2'].every(key => coordinate(plot[key])) ||
        plot.x2 < plot.x1 || plot.x2 - plot.x1 >= 16 || plot.z2 < plot.z1 || plot.z2 - plot.z1 >= 16) throw Error('invalid_plot_bounds');
  }
  const site = config.fishing;
  for (const [i, a] of config.plots.entries()) for (const b of config.plots.slice(i + 1)) {
    if (a.y === b.y && a.x1 <= b.x2 && b.x1 <= a.x2 && a.z1 <= b.z2 && b.z1 <= a.z2) throw Error('overlapping_crop_plots');
  }
  if (!site || !point(site.stand) || !point(site.water)) throw Error('config_requires_fishing_stand_and_water');
  if (!Number.isInteger(site.maxWaitTicks) || site.maxWaitTicks < 100 || site.maxWaitTicks > 1200) throw Error('invalid_fishing_wait_budget');
  return config;
}

export function plotArgs(plot) {
  return Object.fromEntries(['seedItemId', 'x1', 'y', 'z1', 'x2', 'z2'].map(key => [key, plot[key]]));
}

/** Trusted adapter. Guest programs can only name configured plots and sites. */
export class DriverAdapter {
  constructor(config, emit = () => {}, run = execute) {
    this.config = validateConfig(config);
    this.emit = emit;
    this.run = run;
    this.ownedWork = null;
    this.stopping = false;
    this.running = null;
    this.worldIdentity = null;
    this.atFishingSite = false;
  }

  async command(args) {
    try {
      const { stdout } = await this.run(cli, args, { timeout: 20000, maxBuffer: 512 * 1024 });
      if (!/^status: ok$/m.test(stdout)) throw Error(stdout.slice(0, 2000));
      return stdout;
    } catch (error) {
      throw Error(`driver_command_failed: ${String(error.stdout || error.message).slice(0, 2000)}`);
    }
  }

  async tool(name, args = {}) {
    if (this.stream && !this.stream.failed) {
      const payload = await this.stream.request({ op: 'call', name, arguments: args });
      if (typeof payload.result !== 'string') throw Error('missing_tool_result');
      return parseToolResult(`result: ${payload.result}`);
    }
    return parseToolResult(await this.command(['agent', 'tools', 'call', '--name', name, '--arguments', JSON.stringify(args), '--verbose']));
  }

  async idle() {
    const current = await this.tool('inspect_work');
    if (typeof current === 'object' && !terminal.has(current.state)) throw Error(`foreign_or_busy_work: ${current.workId}`);
    if (typeof current === 'string' && !current.startsWith('no foreground work')) throw Error(`unknown_work_state: ${current}`);
  }

  async preflight() {
    this.stream = new WrapperStream(cli);
    const status = await this.stream.request({ op: 'status' });
    if (status.codexDriverActive !== true) throw Error('codex_driver_mode_required');
    await this.idle();
    const observations = await this.observe();
    await this.assertReleased();
    return observations;
  }

  checkWorld(observation) {
    const identity = `${observation.worldIdentity}:${observation.dimension}`;
    if (this.worldIdentity && identity !== this.worldIdentity) throw Error('world_changed');
    this.worldIdentity = identity;
    if (!observation.playerAlive) throw Error('player_not_alive');
    if (observation.reflexHold) throw Error(`reflex_hold: ${observation.reflexHold}`);
  }

  async inspectPlot(plot) {
    const observation = await this.tool('inspect_crop_plot', plotArgs(plot));
    if (typeof observation !== 'object' || typeof observation.ready !== 'boolean') throw Error('invalid_plot_observation');
    this.checkWorld(observation);
    return observation;
  }

  async observe() {
    const observations = {};
    // Sequential CLI reads keep the prototype's observation load bounded.
    for (const plot of this.config.plots) observations[plot.id] = await this.inspectPlot(plot);
    return observations;
  }

  async assertReleased() {
    const deadline = performance.now() + 15000;
    do {
      const observation = await this.inspectPlot(this.config.plots[0]);
      if (!observation.hookActive && observation.actuatorReleased) return;
      await delay(300);
    } while (performance.now() < deadline);
    throw Error('native_release_unconfirmed');
  }

  async cancelOwned(reason) {
    if (!this.ownedWork) return;
    const workId = this.ownedWork;
    this.emit('native_cancel_requested', { workId, reason });
    await this.tool('cancel_work', { workId, reason });
    const deadline = performance.now() + 20000;
    do {
      const state = await this.tool('inspect_work', { workId });
      if (terminal.has(state?.state)) {
        await this.assertReleased();
        this.ownedWork = null;
        this.emit('native_cancel_confirmed', { workId, state: state.state });
        return;
      }
      await delay(300);
    } while (performance.now() < deadline);
    throw Error(`cancellation_unconfirmed: ${workId}`);
  }

  async activity(name, args, timeoutMs) {
    if (this.stopping) return { ok: false, reason: 'stopped' };
    await this.idle();
    await this.assertReleased();
    if (this.stopping) return { ok: false, reason: 'stopped' };
    const receipt = await this.tool(name, args);
    if (!receipt?.accepted || typeof receipt.workId !== 'string' || !receipt.workId.startsWith('JOB:')) throw Error(`admission_unconfirmed: ${JSON.stringify(receipt)}`);
    this.ownedWork = receipt.workId;
    this.emit('native_admitted', { tool: name, workId: receipt.workId });
    const deadline = performance.now() + timeoutMs;
    let previous;
    while (performance.now() < deadline && !this.stopping) {
      const state = await this.tool('inspect_work', { workId: receipt.workId });
      if (typeof state !== 'object' || state.workId !== receipt.workId) throw Error('owned_work_unobservable');
      const transition = `${state.state}:${state.phase}`;
      if (transition !== previous) {
        this.emit('native_state', { ...state, workId: receipt.workId });
        previous = transition;
      }
      if (terminal.has(state.state)) {
        await this.assertReleased();
        this.ownedWork = null;
        return { ok: state.state === 'SUCCEEDED', workId: receipt.workId, state: state.state,
          message: state.details?.message ?? state.message, failure: state.details?.failure ?? state.failure };
      }
      await delay(500);
    }
    await this.cancelOwned(this.stopping ? 'os_prototype_stopped' : 'os_prototype_activity_timeout');
    return { ok: false, reason: this.stopping ? 'stopped' : 'activity_timeout' };
  }

  perform(effect, instanceId) {
    if (this.running) return Promise.reject(Error('overlapping_adapter_activity'));
    this.running = this.executeEffect(effect, instanceId).finally(() => { this.running = null; });
    return this.running;
  }

  async executeEffect(effect, instanceId) {
    try {
      this.emit('activity_started', { instanceId, effect });
      if (effect.name === 'tend_crops') {
        this.atFishingSite = false;
        const plot = this.config.plots.find(plot => plot.id === effect.args.plot);
        if (!plot) throw Error('unknown_plot');
        return await this.activity('start_crop_pass', plotArgs(plot), 150000);
      }
      if (effect.name !== 'fish_once' || effect.args.site !== 'beach') throw Error('unknown_activity');
      const site = this.config.fishing;
      if (!this.atFishingSite) {
        const navigation = await this.activity('navigate_to', { ...site.stand, exactY: true }, 90000);
        if (!navigation.ok || this.stopping) return navigation;
        this.atFishingSite = true;
      }
      await this.idle();
      await this.tool('equip_item', { itemId: 'minecraft:fishing_rod' });
      if (this.stopping) return { ok: false, reason: 'stopped' };
      return await this.activity('fish_once', { ...site.water, maxWaitTicks: site.maxWaitTicks }, 90000);
    } catch (error) {
      // Only our exact admitted work may be cancelled. Lost receipts remain fatal.
      if (this.ownedWork) await this.cancelOwned('os_prototype_activity_error');
      throw error;
    }
  }

  async stop() {
    this.stopping = true;
    try {
      await this.running;
      if (this.ownedWork) await this.cancelOwned('os_prototype_shutdown');
      if (this.worldIdentity) await this.assertReleased();
    } finally { await this.stream?.close(); }
  }
}
