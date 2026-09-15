import { copyMessage } from './value.mjs';
import { canonicalJson, contentDigest } from './content.mjs';
import { isObservationPath } from './observation-path.mjs';

const text = value => typeof value === 'string' && value.length > 0 && value.length <= 256;
const tick = value => Number.isSafeInteger(value) && value >= 0;
function freeze(value) {
  if (value !== null && typeof value === 'object') { for (const child of Object.values(value)) freeze(child); Object.freeze(value); }
  return value;
}
const fields = ['schemaVersion', 'sessionId', 'epoch', 'scope', 'captureId', 'captureSequence', 'capturedAtNanos', 'clockDomain',
  'source', 'clientTick', 'serverTick', 'receivedAtHostMillis', 'captureAgeUpperBoundMillis', 'coverage', 'facts', 'progress'];

/** Per-scope native projections. Different captures remain separate and missing facts stay unknown. */
export class ObservationFrames {
  #epoch;
  #sessionId = null;
  #scopes;
  #frames = new Map();
  #now;
  constructor({ epoch, scopes, now }) { this.#scopes = new Set(scopes); this.#now = now; this.setEpoch(epoch); }
  get epoch() { return this.#epoch; }
  get size() { return this.#frames.size; }
  setEpoch(epoch) {
    if (!text(epoch)) throw Error('invalid_epoch');
    if (epoch !== this.#epoch) { this.#epoch = epoch; this.#sessionId = null; this.#frames.clear(); }
  }
  publish(value) {
    // Reserve space for the page, effect response and runner frame without raising guest limits.
    const frame = copyMessage(value, 12_288, { maximumDepth: 10, maximumNodes: 1900 });
    if (frame?.schemaVersion !== 1 || Object.keys(frame).length !== fields.length || fields.some(key => !Object.hasOwn(frame, key)) ||
        !['sessionId', 'epoch', 'captureId', 'clockDomain', 'source'].every(key => text(frame[key])) ||
        !this.#scopes.has(frame.scope) || !tick(frame.captureSequence) || frame.captureSequence === 0 || !tick(frame.clientTick) ||
        frame.serverTick !== null && !tick(frame.serverTick) || typeof frame.capturedAtNanos !== 'string' || !/^-?\d{1,30}$/.test(frame.capturedAtNanos) ||
        !Number.isFinite(frame.receivedAtHostMillis) || frame.receivedAtHostMillis < 0 || frame.receivedAtHostMillis > this.#now() ||
        frame.captureAgeUpperBoundMillis !== null && (!Number.isFinite(frame.captureAgeUpperBoundMillis) || frame.captureAgeUpperBoundMillis < 0)) throw Error('invalid_observation');
    if (frame.epoch !== this.#epoch) throw Error('stale_epoch');
    if (this.#sessionId !== null && frame.sessionId !== this.#sessionId) throw Error('observation_session_changed');
    if (!frame.coverage || Object.keys(frame.coverage).length !== 3 || ['available', 'complete', 'truncated'].some(key => typeof frame.coverage[key] !== 'boolean') ||
        !frame.progress || Object.keys(frame.progress).length !== 2 || !text(frame.progress.clockId) ||
        frame.progress.eligibleTicks !== null && !tick(frame.progress.eligibleTicks) || !Array.isArray(frame.facts) || frame.facts.length > 128) throw Error('invalid_observation');
    const cells = new Map();
    for (const cell of frame.facts) {
      if (!cell || Object.keys(cell).some(key => !['path', 'known', 'value', 'reason'].includes(key)) ||
          !isObservationPath(cell.path) ||
          typeof cell.known !== 'boolean' || cell.known !== Object.hasOwn(cell, 'value') ||
          cell.reason !== undefined && !text(cell.reason)) throw Error('invalid_observation_cell');
      const path = canonicalJson(cell.path);
      if (cells.has(path)) throw Error('duplicate_observation_cell');
      cells.set(path, cell);
    }
    const { receivedAtHostMillis, captureAgeUpperBoundMillis, ...native } = frame;
    const signature = contentDigest(native), previous = this.#frames.get(frame.scope);
    if (previous && frame.captureSequence <= previous.frame.captureSequence) {
      if (frame.captureSequence === previous.frame.captureSequence && signature !== previous.signature) throw Error('capture_conflict');
      return false;
    }
    const highWater = previous?.clockId === frame.progress.clockId ? previous.highWater : null;
    if (highWater !== null && frame.progress.eligibleTicks !== null && frame.progress.eligibleTicks < highWater) throw Error('progress_clock_regressed');
    this.#sessionId = frame.sessionId;
    this.#frames.set(frame.scope, { frame: freeze(frame), signature, cells, invalidated: false, clockId: frame.progress.clockId, highWater: frame.progress.eligibleTicks ?? highWater });
    return true;
  }
  invalidate() { for (const entry of this.#frames.values()) entry.invalidated = true; }
  read(scope, path, now = this.#now()) {
    const entry = this.#frames.get(scope);
    if (!this.#fresh(entry, now)) return { known: false };
    const cell = entry.cells.get(canonicalJson(path));
    return cell?.known ? { known: true, value: cell.value } : { known: false };
  }
  progress(scope, now = this.#now()) {
    const entry = this.#frames.get(scope);
    return this.#fresh(entry, now) ? { ...entry.frame.progress } : { clockId: null, eligibleTicks: null };
  }
  basis(scopes) {
    return scopes.map(scope => {
      const frame = this.#frames.get(scope)?.frame;
      return { scope, captureId: frame?.captureId ?? null, captureSequence: frame?.captureSequence ?? null };
    });
  }
  observe(scopes) {
    const now = this.#now();
    return scopes.map(scope => {
      const entry = this.#frames.get(scope), frame = entry?.frame;
      return { scope, current: this.#fresh(entry, now),
        ageUpperBoundMillis: frame && frame.captureAgeUpperBoundMillis !== null && now >= frame.receivedAtHostMillis
          ? frame.captureAgeUpperBoundMillis + now - frame.receivedAtHostMillis : null,
        frame: frame ? copyMessage(frame) : null };
    });
  }
  #fresh(entry, now) {
    const frame = entry?.frame;
    return entry?.invalidated === false && frame?.coverage.available === true && frame.captureAgeUpperBoundMillis !== null && now >= frame.receivedAtHostMillis &&
      frame.captureAgeUpperBoundMillis + now - frame.receivedAtHostMillis < 2000;
  }
}
