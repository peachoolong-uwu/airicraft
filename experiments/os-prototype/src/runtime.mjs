import { BehaviorSandbox } from './sandbox.mjs';

const terminal = new Set(['DONE', 'FAILED', 'STOPPED']);

/** Scheduling is host-owned. Guest programs only suspend with validated effects. */
export class SkillRuntime {
  constructor(adapter, { emit = () => {}, clock = () => performance.now() } = {}) {
    this.adapter = adapter;
    this.emit = emit;
    this.clock = clock;
    this.instances = [];
    this.observations = {};
    this.version = 0;
    this.dispatchAfterVersion = 0;
    this.owner = null;
    this.sequence = 0;
    this.stopping = false;
    this.fatal = null;
  }

  async install({ id, source, config, priority, actions, plots = [], sites = [] }) {
    if (this.instances.length >= 8 || this.instances.some(item => item.id === id)) throw Error('instance_limit_or_duplicate');
    if (!Number.isFinite(priority) || priority < 0 || priority > 100) throw Error('invalid_priority');
    const sandbox = await BehaviorSandbox.create(source, config);
    const instance = { id, sandbox, priority, actions, plots, sites, phase: 'NEW', effect: null,
      reply: null, freshAfter: 0, order: ++this.sequence, completedActivities: 0 };
    this.instances.push(instance);
    this.emit('installed', { id, revision: sandbox.revision, priority });
    return instance;
  }

  update(observations) {
    this.observations = structuredClone(observations);
    this.version++;
  }

  state(instance, phase, details = {}) {
    if (instance.phase !== phase) this.emit('state', { id: instance.id, phase, ...details });
    instance.phase = phase;
  }

  validate(instance, effect) {
    if (!effect || typeof effect !== 'object' || Array.isArray(effect)) throw Error('invalid_effect');
    if (effect.kind === 'observe' || effect.kind === 'wait') {
      if (!instance.plots.includes(effect.plot)) throw Error('plot_capability_denied');
    } else if (effect.kind === 'sleep') {
      if (!Number.isInteger(effect.ms) || effect.ms < 100 || effect.ms > 60000) throw Error('invalid_sleep');
    } else if (effect.kind === 'action') {
      if (!instance.actions.includes(effect.name)) throw Error('action_capability_denied');
      if (effect.name === 'tend_crops' && !instance.plots.includes(effect.args?.plot)) throw Error('plot_capability_denied');
      if (effect.name === 'fish_once' && !instance.sites.includes(effect.args?.site)) throw Error('site_capability_denied');
      if (!['tend_crops', 'fish_once'].includes(effect.name)) throw Error('unknown_action');
    } else throw Error('unknown_effect');
  }

  advance(instance) {
    // Bound host work even if a program yields endlessly without doing computation in a resume.
    for (let step = 0; step < 16; step++) {
      if (terminal.has(instance.phase) || instance.phase === 'ACTING' || instance.phase === 'READY') return;
      const effect = instance.effect;
      if (effect) {
        if (effect.kind === 'observe') {
          if (this.version < instance.freshAfter || !this.observations[effect.plot]) {
            this.state(instance, 'WAITING_OBSERVATION', { plot: effect.plot });
            return;
          }
          instance.reply = this.observations[effect.plot];
        } else if (effect.kind === 'wait') {
          const observation = this.observations[effect.plot];
          if (this.version < instance.freshAfter || !observation?.known || !observation.ready) {
            this.state(instance, 'WAITING_WORLD', { plot: effect.plot });
            return;
          }
          instance.reply = observation;
          this.emit('woken', { id: instance.id, plot: effect.plot, observationVersion: this.version });
        } else if (effect.kind === 'sleep') {
          if (this.clock() < instance.wakeAt) return;
          instance.reply = null;
        }
      }
      const next = instance.sandbox.next(instance.reply);
      instance.reply = null;
      if (next.done) { this.state(instance, 'DONE'); return; }
      this.validate(instance, next.value);
      instance.effect = next.value;
      if (next.value.kind === 'action') {
        instance.order = ++this.sequence;
        this.state(instance, 'READY', { action: next.value.name });
        return;
      }
      if (next.value.kind === 'sleep') {
        instance.wakeAt = this.clock() + next.value.ms;
        this.state(instance, 'WAITING_TIMER', { ms: next.value.ms });
        return;
      }
    }
    this.state(instance, 'COMPUTE_YIELD');
  }

  tick() {
    if (this.stopping || this.fatal) return;
    if (this.owner?.settled) {
      const instance = this.owner;
      if (instance.result.fatal) {
        this.fatal = instance.result.error;
        this.emit('actuation_uncertain', { id: instance.id, error: this.fatal });
        return; // Retain ownership. An uncertain release is never permission to dispatch.
      }
      this.emit('player_released', { id: instance.id, result: instance.result });
      instance.completedActivities += Number(instance.result.ok);
      instance.reply = instance.result;
      instance.effect = null;
      instance.freshAfter = this.version + 1;
      this.dispatchAfterVersion = this.version + 1;
      this.state(instance, 'RESUMING');
      this.owner = null;
    }
    for (const instance of this.instances) {
      try { this.advance(instance); }
      catch (error) {
        this.state(instance, 'FAILED', { error: String(error.message).slice(0, 1500) });
      }
    }
    if (this.owner || this.version < this.dispatchAfterVersion) return;
    const candidates = this.instances.filter(instance => instance.phase === 'READY')
      .filter(instance => instance.effect.name !== 'tend_crops' || this.observations[instance.effect.args.plot]?.ready)
      .sort((a, b) => b.priority - a.priority || a.order - b.order);
    const instance = candidates[0];
    if (!instance) return;
    this.owner = instance;
    instance.settled = false;
    this.state(instance, 'ACTING', { action: instance.effect.name });
    this.emit('player_granted', { id: instance.id, action: instance.effect.name,
      eligible: candidates.map(candidate => candidate.id), observationVersion: this.version });
    instance.promise = Promise.resolve().then(() => this.adapter.perform(instance.effect, instance.id))
      .then(result => { instance.result = { ok: true, ...result }; })
      .catch(error => { instance.result = { ok: false, fatal: true, error: String(error.message).slice(0, 1500) }; })
      .finally(() => { instance.settled = true; });
  }

  snapshot() {
    return { owner: this.owner?.id ?? null, fatal: this.fatal, observationVersion: this.version,
      instances: this.instances.map(instance => ({ id: instance.id, phase: instance.phase,
        revision: instance.sandbox.revision, effect: instance.effect, completedActivities: instance.completedActivities })) };
  }

  async stop(reason = 'stopped') {
    this.stopping = true;
    try {
      await this.adapter.stop?.(reason);
      if (this.owner?.promise) await this.owner.promise;
      this.owner = null;
      this.emit('stopped', { reason });
    } finally {
      for (const instance of this.instances) {
        if (!terminal.has(instance.phase)) this.state(instance, 'STOPPED', { reason });
        instance.sandbox.dispose();
      }
    }
  }
}
