import { readFile, mkdir, writeFile, appendFile } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import { SkillRuntime } from './runtime.mjs';
import { DriverAdapter } from './driver.mjs';
import { SimulationAdapter } from './simulation.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));
const args = process.argv.slice(2);
const simulate = args.includes('--simulate');
const argument = (name, fallback) => args.includes(name) ? args[args.indexOf(name) + 1] : fallback;
const configPath = resolve(argument('--config', resolve(root, 'world.local.json')));
const minutes = Number(argument('--minutes', '5'));
if (!Number.isFinite(minutes) || minutes <= 0 || minutes > 30) throw Error('minutes_must_be_between_0_and_30');
const artifacts = resolve(argument('--output', resolve(root, 'artifacts', new Date().toISOString().replaceAll(':', '-'))));
await mkdir(artifacts, { recursive: true });
const events = [];
let eventCount = 0;
const emit = (type, data) => {
  if (++eventCount > 20000) throw Error('trace_event_limit');
  events.push(JSON.stringify({ time: new Date().toISOString(), simulated: simulate, type, ...data }));
  if (['player_granted', 'player_released', 'native_state', 'actuation_uncertain'].includes(type)) console.log(type, JSON.stringify(data));
};
const flush = async runtime => {
  if (events.length) await appendFile(resolve(artifacts, 'trace.jsonl'), events.splice(0).join('\n') + '\n');
  await writeFile(resolve(artifacts, 'status.json'), JSON.stringify(runtime.snapshot(), null, 2) + '\n');
};
const config = simulate ? { plots: [{ id: 'wheat' }] } : JSON.parse(await readFile(configPath, 'utf8'));
const adapter = simulate ? new SimulationAdapter(emit) : new DriverAdapter(config, emit);
const runtime = new SkillRuntime(adapter, { emit, ...(simulate ? { clock: () => adapter.time * 1000 } : {}) });
const manifest = JSON.parse(await readFile(resolve(root, 'library/manifest.json'), 'utf8'));
if (manifest.interfaceVersion !== 'generator-effects/v1') throw Error('unsupported_interface_version');
const source = async definition => {
  const path = resolve(root, 'library', definition.source);
  if (dirname(path) !== resolve(root, 'library')) throw Error('source_outside_library');
  return readFile(path, 'utf8');
};
let stopping = false;
process.on('SIGINT', () => { stopping = true; adapter.stopping = true; });
process.on('SIGTERM', () => { stopping = true; adapter.stopping = true; });
let reason = 'trial_duration_complete';
let firstTick, lastTick, activeTicks = 0;
const started = performance.now();
try {
  for (const plot of config.plots) {
    const definition = manifest.definitions.farm;
    await runtime.install({ ...definition, id: `farm:${plot.id}`, source: await source(definition),
      config: { plot: plot.id }, plots: [plot.id] });
  }
  const definition = manifest.definitions.fishing;
  await runtime.install({ ...definition, id: 'fishing', source: await source(definition), config: { site: 'beach' }, sites: ['beach'] });
  runtime.update(simulate ? adapter.observe() : await adapter.preflight());
  await writeFile(resolve(artifacts, 'run.json'), JSON.stringify({ simulated: simulate, config, minutes, manifest,
    instances: runtime.snapshot().instances, startedAt: new Date().toISOString() }, null, 2) + '\n');
  console.log(`${simulate ? 'SIMULATED rehearsal' : 'LIVE prototype'}; evidence: ${artifacts}`);
  for (let step = 0; !stopping; step++) {
    if (simulate) adapter.step();
    const observations = await adapter.observe();
    runtime.update(observations);
    const world = Object.values(observations)[0];
    firstTick ??= world.worldTick;
    if (lastTick !== undefined && world.worldTick < lastTick) throw Error('world_time_rewound');
    if (lastTick !== undefined && !world.paused) activeTicks += world.worldTick - lastTick;
    lastTick = world.worldTick;
    if (!world.paused) runtime.tick();
    if (runtime.fatal) throw Error(runtime.fatal);
    if (runtime.instances.every(instance => ['FAILED', 'DONE'].includes(instance.phase))) throw Error('no_live_behaviors');
    if (step % 10 === 0) emit('observation', { observations });
    await flush(runtime);
    if (simulate ? step >= 100 : activeTicks >= minutes * 60 * 20) break;
    if (performance.now() - started > (minutes + 5) * 60000) throw Error('wall_clock_watchdog');
    await delay(simulate ? 0 : 1000);
  }
  if (stopping) reason = 'interrupted';
} catch (error) {
  reason = stopping ? 'interrupted' : error.message;
  emit(stopping ? 'interrupted' : 'run_failed', { error: error.message });
  if (!stopping) { console.error(reason); process.exitCode = 1; }
} finally {
  try { await runtime.stop(reason); }
  catch (error) { emit('shutdown_uncertain', { error: error.message, ownedWork: adapter.ownedWork }); console.error(error.message); process.exitCode = 1; }
  emit('run_finished', { reason, activeTicks, firstTick, lastTick, harvests: adapter.harvests, fish: adapter.fish });
  await flush(runtime);
  console.log(`Stopped: ${reason}. Evidence: ${artifacts}`);
}
