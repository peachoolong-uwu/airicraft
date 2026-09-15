# Airicraft OS: crop and fishing prototype

This experiment runs persistent JavaScript behavior definitions in separate QuickJS/WASM runtimes. A local Node host schedules their effects and owns the single player lease. Minecraft actions still run through Airicraft's existing executors and wrapper CLI. Codex authors and revises the behavior files between trials.

The first slice is **plant/harvest/replant crops, then fish while they grow**. Each configured plot has an independent farm instance. Fishing is filler. Sheep, trees, compost, worker calls, durable continuations, hot replacement, and the full reference benchmark remain outside this slice. This is a provisional runtime choice for an experiment; it does not settle the language/hosting decision in the design map.

## Run

From this directory, install dependencies with `npm ci`. Node 22 or later is required.

```sh
npm test
npm run demo
```

The demo is a deterministic **simulation**, using the same behavior files, sandbox, and scheduler as the live host. Its counters are not Minecraft yield evidence.

For a live trial:

1. Launch `scripts/codex-driver` from the repository root. Join the prepared world and open its LAN session. Verify `codexDriverActive: true` using the wrapper CLI.
2. Put planting stock and a fishing rod in the player's inventory; close the chest. Copy `world.example.json` to `world.local.json` here and replace all coordinates with verified locations. The farm's `y` is the crop block, one above the soil. Use separate, non-overlapping rectangles for wheat, potatoes, and carrots. The fishing stand must be dry, with open water nearby.
3. Run `npm start -- --config world.local.json --minutes 5`. `--minutes` accepts up to 30 minutes of advancing world ticks. A wall-clock watchdog bounds pauses. Use Ctrl-C to stop and reconcile the current owned action.

The CLI needs localhost access. In Codex's macOS shell sandbox, launch the host outside the sandbox: a blocked wrapper connection can delete bridge discovery as stale. Do not start another controller while the prototype is running.

Evidence is written under `artifacts/<timestamp>/`: `run.json` contains configuration and source revisions, `trace.jsonl` contains scheduling decisions, observations and exact native work IDs, and `status.json` shows the current owner and each instance's wait reason. `--output <directory>` selects another location. World-specific configuration, dependencies, and raw runs are ignored by Git.

The host starts one persistent `airicraft agent tools stream` child. It accepts bounded JSONL requests (`id`, `op: "status"`, or `op: "call"` with `name` and `arguments`). Output starts with the normal status/command header and a protocol identifier, followed by `response: <JSON>` lines carrying the request ID, success flag, and payload/error. It exposes the same tools and policies as individual CLI calls, with serial requests and no arbitrary HTTP route selection. EOF ends the transport, not admitted Minecraft work. The host owns cancellation before closing it.

## Behavior contract

Each source defines `function* main(os, config)`. Ordinary loops and `yield*` compose smaller generator functions. Yielding one of these descriptors suspends only that behavior:

| Effect | Host behavior |
| --- | --- |
| `yield os.observe(plot)` | Return the latest bounded plot observation. After a completed action, require a newer observation. |
| `yield os.wait(plot)` | Suspend until the latest observation says the plot is known and ready. Hold no player lease. |
| `yield os.sleep(ms)` | Suspend on a host timer, without the player. |
| `yield os.action('tend_crops', {plot})` | Queue one native crop pass. Resume with its terminal result. |
| `yield os.action('fish_once', {site})` | Queue navigation, rod equip, and one bounded native cast. Resume after hook release. |

The trusted library manifest sets priority and permitted actions. Installation grants specific plots/sites. Source cannot raise its priority, choose arbitrary coordinates, call arbitrary tools, or receive host objects. Farm priority is 10; fishing is 0. Equal priorities rotate in request order.

Dispatch is cooperative at activity boundaries. A crop becoming ready does **not** interrupt a cast in this slice; it wins the next grant when that cast retrieves a bite or exhausts its wait budget (maximum 60 seconds of active native ticks, plus preparation and cleanup). This makes wasted casts and responsiveness measurable before introducing finer preemption. Fishing's bite response itself runs every client tick, not through remote polling.

The host polls observations roughly once per second plus bridge latency. These are level-triggered conditions, not callbacks that can be lost while a behavior is busy. A growing crop consumes no action slot. Unknown cells do not count as ready. Admission is not completion: the host retains an exact `JOB:` ID, polls terminal state, and verifies native release before granting the player again. Cancellation targets only that ID. Unconfirmed receipt, release, or transport failure stops scheduling; it never authorizes a second owner. Guest execution/capability faults disable that instance while healthy instances continue. Fishing skips repeat navigation until a land activity has moved the player; native cast validation still checks the standing position.

## Boundaries and limitations

The `quickjs-emscripten` package is pinned at 0.32.0. Each behavior receives a 25 ms CPU interrupt budget per resume, a one-second wall watchdog, a configured 4 MiB guest allocation limit, 256 KiB stack limit, 64 KiB source limit, and 16 KiB messages. Cold source initialization gets 250 ms CPU. CPU accounting covers the Node process during a synchronous guest resume; it is not an exact per-guest hardware meter. The host allows at most eight instances, validates every yielded capability, bounds immediate yields, and caps trace events. Tests exercise computation, allocation, and output failures. The allocation setting is **not a cap on the Node process or total WASM memory**, and this prototype is not an OS-level security audit. See the [embedding API](https://github.com/justjake/quickjs-emscripten) and the broader [sandbox research](../../docs/research/airicraft-os-sandbox-options.md).

Only definition source persists; generator frames and waits do not survive host restart. Restart requires idle native work and fresh world observations. SIGINT/SIGTERM requests cleanup, but a host crash or SIGKILL cannot run cleanup: an already admitted native activity may continue to its own timeout. There is no native expiring lease yet. Other limitations include no inventory reservations, route batching, aging beyond FIFO ties, worker calls, baseline comparison, or throughput claim. Native safety reflex holds, death, and world changes stop the trial.

The mod adds `inspect_crop_plot`, asynchronous `start_crop_pass`, and `fish_once`. Driver-mode queued action receipts now include exact work IDs; normal embedded-planner wording is preserved. `tend_crops` retains its existing completion-waiting interface. The native dispatcher keeps a fishing executor attached during hook cleanup before accepting a different executor.

## Validation

Seven host tests cover sandbox bounds, capability isolation, repeated handoffs, stale observations, and unconfirmed native release. The deterministic demo completes several harvest/fishing cycles. Focused native tests cover bite handling, timeout, delayed-hook cancellation, foreign hooks, executor handoff, crop passes, active jobs, and tool schemas.

Run `node src/audit.mjs artifacts/<run>` after a trial to check the recorded lease/native-work ordering and summarize handoffs and admission delays. It writes `audit.json` and fails on recorded overlaps or unresolved owners. These checks cover the recorded protocol; they do not measure every physical input or classify all idle time.

Live trial evidence and findings are recorded separately in `TRIAL.md` when available. Simulation or unit tests alone do not prove live fishing or crop efficiency.

## Roadmap position

[Define behavior instances and compositional execution](https://github.com/shinohara-rin/airicraft/issues/53) is resolved: child instances have owned lifetimes, parents settle their children before stopping, and parallel child failure cancels siblings by default with an explicit collect-all option. Invocation ownership remains separate from OS-owned shared activities and access contexts. The [evidence and walkthrough](design/behavior-composition.md) records what the prototype demonstrates and which lifecycle capabilities still need implementation.

The remaining architecture contracts are recorded in [Chart the Airicraft OS driver experiment](https://github.com/shinohara-rin/airicraft/issues/51). The [coordination walkthrough](design/coordination-walkthrough.md) checks their interactions; the [implementation and evaluation handoff](design/implementation-handoff.md) starts with a guarded native transfer and explicit admission, cancellation, and release evidence. These are design decisions and qualification requirements, not claims that the full contract already runs. Gameplay-specific sheep recovery is a separate scope.
