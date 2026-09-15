/** Deterministic rehearsal; deliberately not evidence of Minecraft gameplay. */
export class SimulationAdapter {
  constructor(emit = () => {}) {
    this.emit = emit;
    this.time = 0;
    this.growsAt = null;
    this.planted = false;
    this.harvests = 0;
    this.fish = 0;
    this.pending = null;
    this.maximumOwners = 0;
  }
  observe() {
    const mature = this.planted && this.time >= this.growsAt;
    return { wheat: { known: true, ready: !this.planted || mature, mature: Number(mature),
      emptyFarmland: Number(!this.planted), growing: Number(this.planted && !mature), seeds: 16, worldTick: this.time } };
  }
  perform(effect) {
    if (this.pending) throw Error('overlapping_simulated_activities');
    this.maximumOwners = Math.max(this.maximumOwners, 1);
    return new Promise(resolve => {
      this.pending = { effect, due: this.time + (effect.name === 'tend_crops' ? 4 : 6), resolve };
    });
  }
  step() {
    this.time++;
    const pending = this.pending;
    if (!pending || this.time < pending.due) return;
    if (pending.effect.name === 'tend_crops') {
      if (this.planted) this.harvests++;
      this.planted = true;
      this.growsAt = this.time + 12;
    } else this.fish++;
    this.pending = null;
    pending.resolve({ ok: true, simulated: true, harvests: this.harvests, fish: this.fish });
  }
  async stop() {
    this.pending?.resolve({ ok: false, reason: 'stopped' });
    this.pending = null;
  }
}
