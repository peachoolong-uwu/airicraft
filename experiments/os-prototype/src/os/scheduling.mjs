import { copyMessage } from './value.mjs';
import { executionPolicy } from './execution-policy.mjs';

const name = value => typeof value === 'string' && value.length > 0 && value.length <= 256;
const tick = value => Number.isSafeInteger(value) && value >= 0;
export const schedulingPolicy = Object.freeze({ version: 'os-scheduling-v1', overdueTicks: 2400, agePointTicks: 300,
  contextBonus: 3, maximumTravelBonus: 3, contextOperations: 8, contextTicks: 1200, fishingYieldTicks: 100 });

/** Trusted scheduling declarations only; a choice never admits work or proves physical release. */
export class SchedulingPolicy {
  #epoch;
  #roots = new Map();
  #offers = new Map();
  #sequence = 0;
  #tick = null;
  #outsideContext = null;
  #fishingYield = null;

  constructor({ epoch }) {
    if (!name(epoch)) throw Error('invalid_scheduler_epoch');
    this.#epoch = epoch;
  }
  update({ roots, offers }) {
    if (!Array.isArray(roots) || roots.length > executionPolicy.roots || roots.some(id => !name(id)) || new Set(roots).size !== roots.length ||
        !Array.isArray(offers) || offers.length > executionPolicy.invocations * executionPolicy.offers) throw Error('invalid_schedule');
    const next = new Map();
    let sequence = this.#sequence;
    for (const value of offers) {
      const offer = copyMessage(value);
      if (!offer || Object.keys(offer).some(key => !['id', 'roots', 'kind', 'readiness', 'priority', 'context', 'travel'].includes(key)) ||
          !name(offer.id) || next.has(offer.id) || !Array.isArray(offer.roots) || !offer.roots.length || offer.roots.length > executionPolicy.roots ||
          new Set(offer.roots).size !== offer.roots.length || offer.roots.some(root => !roots.includes(root)) ||
          !['land', 'fishing'].includes(offer.kind) || !['ready', 'blocked', 'unknown'].includes(offer.readiness) ||
          !Number.isInteger(offer.priority) || offer.priority < 0 || offer.priority > 2_147_483_647 ||
          (offer.context !== null && !name(offer.context))) throw Error('invalid_schedule');
      const travel = offer.travel ?? { bonus: 0, source: 'unknown', uncertain: true };
      if (!Number.isFinite(travel.bonus) || travel.bonus < 0 || travel.bonus > schedulingPolicy.maximumTravelBonus ||
          !['observed', 'configured', 'unknown'].includes(travel.source) || typeof travel.uncertain !== 'boolean' ||
          Object.keys(travel).some(key => !['bonus', 'source', 'uncertain'].includes(key)) ||
          (travel.source === 'unknown' && (travel.bonus !== 0 || travel.uncertain !== true))) throw Error('invalid_schedule');
      const previous = this.#offers.get(offer.id);
      if (previous && (previous.kind !== offer.kind || previous.context !== offer.context || previous.roots.length !== offer.roots.length ||
          previous.roots.some(root => !offer.roots.includes(root)))) throw Error('offer_identity_conflict');
      const order = previous?.order ?? ++sequence;
      if (!Number.isSafeInteger(sequence)) throw Error('schedule_sequence_exhausted');
      next.set(offer.id, { ...offer, travel, order });
    }
    this.#roots = new Map(roots.map(id => [id, this.#roots.get(id) ?? { ageTicks: 0, overdue: null }]));
    this.#offers = next; this.#sequence = sequence;
  }
  advance({ epoch, fromTick, toTick, eligibleRoots, covered, ordinaryAllowed }) {
    if (epoch !== this.#epoch) throw Error('stale_scheduler_epoch');
    if (!tick(fromTick) || !tick(toTick) || toTick < fromTick || (this.#tick !== null && fromTick < this.#tick) ||
        typeof covered !== 'boolean' || typeof ordinaryAllowed !== 'boolean' || !Array.isArray(eligibleRoots) ||
        eligibleRoots.length > executionPolicy.roots || new Set(eligibleRoots).size !== eligibleRoots.length ||
        eligibleRoots.some(root => !this.#roots.has(root))) throw Error('invalid_scheduler_progress');
    const candidates = this.#candidates();
    if (covered && ordinaryAllowed && eligibleRoots.some(root => !candidates.some(offer => offer.roots.includes(root))))
      throw Error('ineligible_scheduler_root');
    if (covered && ordinaryAllowed) for (const id of eligibleRoots) {
      const root = this.#roots.get(id), before = root.ageTicks;
      root.ageTicks = before + Math.min(schedulingPolicy.overdueTicks - before, toTick - fromTick);
      if (!root.overdue && root.ageTicks >= schedulingPolicy.overdueTicks) root.overdue = {
        tick: fromTick + schedulingPolicy.overdueTicks - before,
        order: Math.min(...candidates.filter(offer => offer.roots.includes(id)).map(offer => offer.order))
      };
    }
    this.#tick = toTick;
  }
  decide({ authority, context = null, activity = null }) {
    if (authority !== 'available') return { kind: 'wait', reason: 'authority_unavailable' };
    if (context !== null) {
      context = copyMessage(context);
      if (!context || !name(context.id) || !['ready', 'entering', 'exiting', 'unresolved'].includes(context.phase) ||
          !tick(context.operations) || !tick(context.elapsedTicks) ||
          Object.keys(context).some(key => !['id', 'phase', 'operations', 'elapsedTicks'].includes(key))) throw Error('invalid_scheduler_context');
      if (context.phase !== 'ready') return { kind: 'wait', reason: 'context_not_ready' };
    }
    let candidates = this.#candidates();
    if (activity !== null) return this.#activeDecision(activity, candidates);
    this.#fishingYield = null;
    if (this.#outsideContext && !candidates.some(offer => offer.context !== this.#outsideContext)) this.#outsideContext = null;
    if (context && (context.operations >= schedulingPolicy.contextOperations || context.elapsedTicks >= schedulingPolicy.contextTicks)) {
      if (!this.#outsideContext && candidates.some(offer => offer.context !== context.id)) this.#outsideContext = context.id;
      return { kind: 'close_context', context: context.id, reason: 'context_budget' };
    }
    if (!candidates.length) return context ? { kind: 'close_context', context: context.id, reason: 'no_feasible_work' }
      : { kind: 'wait', reason: 'no_feasible_work' };
    const overdue = [...this.#roots].filter(([id, root]) => root.overdue && candidates.some(offer => offer.roots.includes(id)))
      .sort(([, a], [, b]) => a.overdue.tick - b.overdue.tick || a.overdue.order - b.overdue.order)[0]?.[0];
    let reason = 'score';
    if (overdue) { candidates = candidates.filter(offer => offer.roots.includes(overdue)); reason = 'overdue'; }
    else if (this.#outsideContext) { candidates = candidates.filter(offer => offer.context !== this.#outsideContext); reason = 'outside_turn'; }
    candidates.sort((a, b) => this.#score(b, context).total - this.#score(a, context).total || a.order - b.order);
    const winner = candidates[0];
    if (context && winner.context !== context.id) return { kind: 'close_context', context: context.id, reason: 'incompatible_work', nextOfferId: winner.id };
    return { kind: 'select', offerId: winner.id, roots: [...winner.roots], context: winner.context, reason, score: this.#score(winner, context) };
  }
  served(selected) {
    const offer = typeof selected === 'string' ? this.#offers.get(selected) : copyMessage(selected);
    if (typeof selected === 'string') {
      if (!offer || offer.readiness !== 'ready') throw Error('offer_not_ready');
    } else if (!offer || offer.kind !== 'select' || !name(offer.offerId) || !Array.isArray(offer.roots) || !offer.roots.length ||
        offer.roots.length > executionPolicy.roots || offer.roots.some(id => !name(id)) || new Set(offer.roots).size !== offer.roots.length ||
        (offer.context !== null && !name(offer.context))) throw Error('invalid_service_selection');
    // The admitted snapshot can outlive queue refreshes and cancelled/retired consumers.
    for (const rootId of offer.roots) if (this.#roots.has(rootId)) Object.assign(this.#roots.get(rootId), { ageTicks: 0, overdue: null });
    if (this.#outsideContext && offer.context !== this.#outsideContext) this.#outsideContext = null;
  }
  state() {
    return { epoch: this.#epoch, tick: this.#tick, offers: this.#offers.size, outsideContext: this.#outsideContext,
      fishingYield: this.#fishingYield && { ...this.#fishingYield },
      roots: [...this.#roots].map(([id, root]) => ({ id, ageTicks: root.ageTicks, overdue: root.overdue && { ...root.overdue } })) };
  }
  #candidates() {
    const ready = [...this.#offers.values()].filter(offer => offer.readiness === 'ready');
    const land = ready.filter(offer => offer.kind === 'land');
    return land.length ? land : ready;
  }
  #score(offer, context) {
    const base = offer.priority, age = Math.floor(Math.max(...offer.roots.map(id => this.#roots.get(id).ageTicks)) / schedulingPolicy.agePointTicks),
      reuse = context && offer.context === context.id ? schedulingPolicy.contextBonus : 0, travel = offer.travel.bonus;
    return { base, age, context: reuse, travel, total: base + age + reuse + travel,
      travelSource: offer.travel.source, travelUncertain: offer.travel.uncertain };
  }
  #activeDecision(value, candidates) {
    const activity = copyMessage(value);
    if (!activity || !name(activity.id) || !['land', 'fishing'].includes(activity.kind) ||
        !['preparing', 'acting', 'waiting', 'releasing'].includes(activity.phase) ||
        (activity.kind === 'fishing' && typeof activity.biteReady !== 'boolean') ||
        Object.keys(activity).some(key => !['id', 'kind', 'phase', 'biteReady'].includes(key))) throw Error('invalid_scheduler_activity');
    if (this.#fishingYield?.activityId !== activity.id) this.#fishingYield = null;
    if (activity.kind !== 'fishing' || activity.phase === 'releasing') return { kind: 'wait', reason: 'activity_in_progress' };
    if (!this.#fishingYield && !candidates.some(offer => offer.kind === 'land')) return { kind: 'wait', reason: 'activity_in_progress' };
    if (!this.#fishingYield) this.#fishingYield = { activityId: activity.id,
      deadlineTick: this.#tick === null ? null : this.#tick > Number.MAX_SAFE_INTEGER - schedulingPolicy.fishingYieldTicks
        ? this.#tick : this.#tick + schedulingPolicy.fishingYieldTicks };
    const action = activity.phase === 'preparing' ? 'yield_before_cast' : activity.biteReady ? 'retrieve_bite' :
      this.#fishingYield.deadlineTick === null || this.#tick >= this.#fishingYield.deadlineTick ? 'reel_in' : 'wait_for_bite';
    return { kind: 'yield_fishing', ...this.#fishingYield, action };
  }
}
