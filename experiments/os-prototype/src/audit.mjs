import { readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';

const directory = resolve(process.argv[2] ?? 'artifacts/live-04');
const events = (await readFile(resolve(directory, 'trace.jsonl'), 'utf8')).trim().split('\n').map(JSON.parse);
const summary = { simulated: events[0]?.simulated, events: events.length, completed: {}, failed: {},
  wakes: [], handoffs: [], cancelledWork: [], ownershipErrors: [], grantToAdmissionMs: [], releaseToGrantMs: [] };
let owner = null, native = null, grant, release;
const pendingWake = new Map();
for (const event of events) {
  const time = Date.parse(event.time);
  if (event.type === 'woken') { pendingWake.set(event.id, time); summary.wakes.push({ id: event.id, time: event.time }); }
  if (event.type === 'player_granted') {
    if (owner) summary.ownershipErrors.push(`grant ${event.id} while ${owner} owns player`);
    owner = event.id; grant = time;
    if (release) summary.releaseToGrantMs.push(time - release);
    if (pendingWake.has(event.id)) {
      summary.handoffs.push({ id: event.id, time: event.time, wakeToGrantMs: time - pendingWake.get(event.id) });
      pendingWake.delete(event.id);
    }
  }
  if (event.type === 'native_admitted') {
    if (native) summary.ownershipErrors.push(`native admission ${event.workId} before ${native} terminated`);
    native = event.workId;
    if (grant) { summary.grantToAdmissionMs.push(time - grant); grant = null; }
  }
  if (event.type === 'native_state' && ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(event.state) && event.workId === native) native = null;
  if (event.type === 'native_cancel_confirmed') { summary.cancelledWork.push(event.workId); if (native === event.workId) native = null; }
  if (event.type === 'player_released') {
    if (owner !== event.id || native) summary.ownershipErrors.push(`unconfirmed release by ${event.id}`);
    owner = null; release = time;
    const counts = event.result.ok ? summary.completed : summary.failed;
    counts[event.id] = (counts[event.id] ?? 0) + 1;
  }
  if (event.type === 'stopped') owner = null;
  if (event.type === 'run_finished') summary.finish = event;
}
summary.unreleasedOwner = owner;
summary.unfinishedNativeWork = native;
for (const key of ['grantToAdmissionMs', 'releaseToGrantMs']) {
  const values = summary[key].sort((a, b) => a - b);
  summary[key] = values.length ? { count: values.length, median: values[Math.floor(values.length / 2)], max: values.at(-1) } : null;
}
await writeFile(resolve(directory, 'audit.json'), JSON.stringify(summary, null, 2) + '\n');
console.log(JSON.stringify(summary, null, 2));
if (summary.ownershipErrors.length || owner || native || !summary.finish) process.exitCode = 1;
