import { copyMessage } from './value.mjs';

/** Trusted invocation ownership. Runners report body completion; only the host settles effects. */
export class InvocationBroker {
  #instances = new Map();
  #sequence = 0;
  #activity = null;
  #trace;

  constructor({ trace } = {}) { this.#trace = trace; }

  install(spec) {
    if (this.capacity().roots >= 12) throw Error('root_capacity');
    return this.#create(null, spec).id;
  }
  uninstall(id) {
    const instance = this.#get(id);
    if (instance.parentId) throw Error('not_installed_root');
    if (!instance.outcome) throw Error('invocation_unsettled');
    this.#instances.delete(id);
  }
  spawn(parentId, spec) {
    const parent = this.#get(parentId);
    if (parent.phase !== 'running') throw Error('invocation_closing');
    if (parent.children.size >= 64 || this.capacity().retainedChildren >= 256) throw Error('child_result_capacity');
    const child = this.#create(parent, spec);
    const sequence = ++parent.childSequence;
    parent.children.set(sequence, child.id);
    return { id: child.id, parentId, sequence };
  }
  returned(id, value) {
    const instance = this.#get(id);
    if (instance.phase !== 'running') return;
    try { instance.value = copyMessage(value === undefined ? null : value); }
    catch { this.failed(id, 'invalid_result'); return; }
    this.#trace?.record('invocation.body_returned', { id, value: instance.value }, { cleanup: true });
    instance.phase = 'closing';
    this.#settle(instance);
  }
  failed(id, reason) { this.#stop(this.#get(id), { status: 'failure', cause: { reason: this.#reason(reason) } }); }
  cancel(id, reason = 'cancelled') { this.#stop(this.#get(id), { status: 'cancelled', cause: { reason: this.#reason(reason) } }); }
  join(parentId, handle) {
    const parent = this.#get(parentId);
    if (handle.parentId !== parentId || !Number.isSafeInteger(handle.sequence) || handle.sequence < 1 || handle.sequence > parent.childSequence)
      throw Error('invalid_child_handle');
    const id = parent.children.get(handle.sequence);
    if (!id) throw Error('already_joined');
    if (id !== handle.id) throw Error('invalid_child_handle');
    const child = this.#get(id);
    if (!child.outcome) return { status: 'pending' };
    const outcome = structuredClone(child.outcome);
    this.#trace?.record('invocation.joined', { parentId, childId: id, sequence: handle.sequence, mandatory: false }, { cleanup: true });
    parent.children.delete(handle.sequence);
    this.#instances.delete(id);
    this.#settle(parent);
    return outcome;
  }
  trackActivity(id, subscribers) {
    if (this.#activity) throw Error('player_owned');
    if (!subscribers.length) throw Error('activity_requires_subscriber');
    for (const subscriber of subscribers) if (this.#get(subscriber).phase !== 'running') throw Error('invocation_closing');
    this.#trace?.record('activity.attached', { id, subscribers });
    this.#activity = { id, subscribers: new Set(subscribers), cleanupOwners: new Set(), stopRequested: false };
  }
  activity() {
    if (!this.#activity) return null;
    const { id, subscribers, cleanupOwners, stopRequested } = this.#activity;
    return { id, subscribers: [...subscribers], cleanupOwners: [...cleanupOwners], stopRequested };
  }
  subscribeActivity(id, owner) {
    if (!this.#activity || this.#activity.id !== id) throw Error('activity_unknown');
    if (this.#activity.stopRequested) throw Error('activity_stopping');
    if (this.#get(owner).phase !== 'running') throw Error('invocation_closing');
    this.#trace?.assertHealthy();
    this.#activity.subscribers.add(owner);
    this.#trace?.record('activity.subscribed', { id, owner }, { cleanup: true });
  }
  settleActivity(id, evidence) {
    const activity = this.#activity;
    if (!activity || activity.id !== id) throw Error('activity_unknown');
    evidence = copyMessage(evidence);
    if (evidence.released !== true || evidence.accountingComplete !== true) return;
    this.#trace?.record('activity.released', { ...this.activity(), evidence }, { cleanup: true });
    this.#activity = null;
    for (const owner of [...activity.subscribers, ...activity.cleanupOwners]) {
      const instance = this.#instances.get(owner);
      if (instance) {
        instance.lastActivity = { id, evidence: structuredClone(evidence) };
        this.#settle(instance);
      }
    }
  }
  inspect(id) {
    const instance = this.#get(id);
    return structuredClone({ id, phase: instance.phase, outcome: instance.outcome });
  }
  execution(id) {
    const instance = this.#get(id);
    let root = instance;
    while (root.parentId) root = this.#get(root.parentId);
    return { id, parentId: instance.parentId, rootId: root.id, definition: instance.spec.definition, phase: instance.phase };
  }
  authorize(id, grant) {
    const instance = this.#get(id);
    if (instance.phase !== 'running') throw Error('invocation_closing');
    if (!instance.spec.grants.includes(grant)) throw Error('operation_not_granted');
    return { definition: instance.spec.definition, consumer: this.execution(id).rootId };
  }
  capacity() {
    const all = [...this.#instances.values()];
    return { roots: all.filter(instance => !instance.parentId).length,
      live: all.filter(instance => !instance.outcome).length,
      retainedChildren: all.reduce((count, instance) => count + instance.children.size, 0) };
  }
  #create(parent, spec) {
    spec = copyMessage(spec);
    if (!spec || typeof spec.definition !== 'string' || !spec.definition.length || spec.definition.length > 256 ||
        !Array.isArray(spec.grants) || spec.grants.length > 64 || spec.grants.some(grant => typeof grant !== 'string' || !grant.length || grant.length > 256) ||
        (spec.failurePolicy !== undefined && !['cancel_siblings', 'collect_all'].includes(spec.failurePolicy))) throw Error('invalid_invocation');
    if (parent && spec.grants.some(grant => !parent.spec.grants.includes(grant))) throw Error('capability_escalation');
    const depth = parent ? parent.depth + 1 : 1;
    if (depth > 8) throw Error('invocation_depth');
    if (this.capacity().live >= 32) throw Error('live_invocation_capacity');
    const instance = { id: `invocation:${++this.#sequence}`, parentId: parent?.id ?? null,
      depth, spec: structuredClone(spec), children: new Map(), childSequence: 0, phase: 'running', outcome: null };
    this.#trace?.record('invocation.created', { id: instance.id, parentId: instance.parentId, depth, spec });
    this.#instances.set(instance.id, instance);
    return instance;
  }
  #get(id) {
    const instance = this.#instances.get(id);
    if (!instance) throw Error('invocation_unknown');
    return instance;
  }
  #reason(reason) { return typeof reason === 'string' && reason.length > 0 && reason.length <= 256 ? reason : 'invalid_failure'; }
  #stop(instance, cause) {
    if (instance.outcome || instance.cause) return;
    this.#trace?.record('invocation.stop_requested', { id: instance.id, cause }, { cleanup: true });
    instance.cause = cause;
    instance.phase = 'stopping';
    const activity = this.#activity;
    if (activity?.subscribers.delete(instance.id)) {
      if (activity.subscribers.size === 0) {
        activity.cleanupOwners.add(instance.id);
        activity.stopRequested = true;
      }
      this.#trace?.record('activity.withdrawn', { id: activity.id, owner: instance.id, stopRequested: activity.stopRequested }, { cleanup: true });
    }
    if (cause.status === 'failure' && instance.parentId) {
      const parent = this.#get(instance.parentId);
      if (parent.spec.failurePolicy !== 'collect_all') this.#stop(parent, { status: 'failure',
        cause: { childId: instance.id, reason: cause.cause.reason } });
    }
    for (const childId of [...instance.children.values()]) {
      const child = this.#instances.get(childId);
      if (child) this.#stop(child, { status: 'cancelled', cause: { reason: 'parent_stopping' } });
    }
    this.#settle(instance);
  }
  #settle(instance) {
    if (!['closing', 'stopping'].includes(instance.phase)) return;
    if (this.#activity?.subscribers.has(instance.id) || this.#activity?.cleanupOwners.has(instance.id)) return;
    for (const id of instance.children.values()) if (!this.#get(id).outcome) return;
    for (const [sequence, id] of instance.children) {
      this.#trace?.record('invocation.joined', { parentId: instance.id, childId: id, sequence, mandatory: true }, { cleanup: true });
      this.#instances.delete(id);
    }
    instance.children.clear();
    try {
      instance.outcome = copyMessage({ ...(instance.cause ?? { status: 'success', value: instance.value }),
        ...(instance.lastActivity ? { lastActivity: instance.lastActivity } : {}) });
    } catch {
      instance.outcome = { ...(instance.cause ?? { status: 'failure', cause: { reason: 'outcome_limit' } }),
        ...(instance.lastActivity ? { lastActivityId: instance.lastActivity.id } : {}) };
    }
    instance.phase = 'terminal';
    this.#trace?.record('invocation.terminal', { id: instance.id, parentId: instance.parentId, outcome: instance.outcome }, { cleanup: true });
    if (instance.parentId) {
      const parent = this.#instances.get(instance.parentId);
      if (parent) this.#settle(parent);
    }
  }
}
