> Historical Node prototype documentation. Superseded by the [Java OS](../../../docs/embedded-os.md). Commands and source paths below refer to the archived implementation.

# Airicraft OS: mixed homestead prototype

This experiment runs persistent JavaScript behavior definitions in separate QuickJS/WASM runtimes. A local Node host schedules their effects and owns the single player lease. Minecraft actions still run through Airicraft's existing executors and wrapper CLI. Codex authors and revises the behavior files between trials.

The live host supports **crops, sheep, managed birch, composting, shared chest visits, and fishing as filler**. Three farm instances, independent shearing/breeding/sheep-care rules, birch/compost behaviors, and separate output-storage/farm-supply/sheep-supply rules share the player with fishing: twelve instances in the complete reference configuration. Child composition, supervised runner processes, worker calls, restart reconciliation, safe replacement, and the full reference benchmark remain future work. The [design map](design/README.md) now selects JavaScript/QuickJS with a trusted broker and native fencing; this prototype implements only part of that target and does not persist arbitrary continuations.

## Run

From this directory, install dependencies with `npm ci`. Node 22 or later is required.

```sh
npm test
npm run demo
```

The demo rehearses crop/fishing in a deterministic **simulation**. The test suite also runs the mixed behavior definitions together against a fake worksite. Both use the real sandbox and scheduler; neither is Minecraft yield evidence.

For a live trial:

1. Launch `scripts/codex-driver` from the repository root. Join the prepared world and verify its ticks advance while the host runs. The control bridge uses localhost and does not require a LAN game session. Verify `codexDriverActive: true` using the wrapper CLI.
2. Carry birch saplings, an iron axe, shears, and a fishing rod. Planting stock and sheep wheat can start in the inventory or configured home chest; close any open container before starting. Copy `world.example.json` to `world.local.json` here and replace all coordinates with verified locations. The farm's `y` is the crop block, one above the soil. Use separate, non-overlapping rectangles for wheat, potatoes, and carrots. The fishing stand must be dry, with open water nearby. Configure the pen interior, gate and adjacent entrance/exit cells; one to four birch roots at least seven blocks apart; the composter; and optionally the home chest for shared restocking/deposits. The worksite region must contain the full tree canopies (three blocks horizontally, ten above each root) and fit 32 × 16 × 32 blocks. The example coordinates are illustrative, not discovered automatically. Omit `homestead` to run only crops and fishing.
3. Run `npm start -- --config world.local.json --minutes 5`. `--minutes` accepts up to 30 minutes of advancing world ticks. A wall-clock watchdog bounds pauses. Use Ctrl-C to stop and reconcile the current owned action.

The CLI needs localhost access. In Codex's macOS shell sandbox, launch the host outside the sandbox: a blocked wrapper connection can delete bridge discovery as stale. Do not start another controller while the prototype is running.

Evidence is written under `artifacts/<timestamp>/`: `run.json` contains configuration and source revisions, `trace.jsonl` contains scheduling decisions, observations and exact native work IDs, and `status.json` shows the current owner and each instance's wait reason. `--output <directory>` selects another location. World-specific configuration, dependencies, and raw runs are ignored by Git.

The host starts one persistent `airicraft agent tools stream` child. It accepts bounded JSONL requests (`id`, `op: "status"`, or `op: "call"` with `name` and `arguments`). Output starts with the normal status/command header and a protocol identifier, followed by `response: <JSON>` lines carrying the request ID, success flag, and payload/error. It exposes the same tools and policies as individual CLI calls, with serial requests and no arbitrary HTTP route selection. EOF ends the transport, not admitted Minecraft work. The host owns cancellation before closing it.

## Behavior contract

The manifest uses `work-contexts/v3`. Sheep and chest behaviors define `function work(os, config, world)` and return a bounded set of ready work requests. Each evaluation receives only its granted observations. For example, shearing publishes one request per eligible sheep:

```js
return herd.shearable.map(uuid =>
  os.work('shear_sheep', { scope: config.scope, uuid }, config.context));
```

Breeding independently publishes eligible pairs. Sheep care publishes recovery, culling, and pickup work. Farm supplies and sheep supplies independently offer `ensure_stock` requests; storage offers `store_output` requests. These definitions contain no entry/exit navigation or calls to one another. The host reevaluates their offers from fresh observations, bounds them to 32 per behavior, merges identical operation/target requests, and reports an outcome to every subscribing behavior. Operation arguments have a closed schema so irrelevant fields cannot create duplicate identities. Failed operations receive a shared 15-second retry delay; runtime and capability failures disable the offending definition.

The remaining sources define `function* main(os, config)`. Ordinary loops and `yield*` compose smaller generator functions. Yielding one of these descriptors suspends only that behavior:

| Effect | Host behavior |
| --- | --- |
| `yield os.observe(plot)` | Return the latest bounded plot or granted worksite observation. After a completed action, require a newer observation. |
| `yield os.wait(plot)` | Suspend until the latest observation says the worksite is known and ready. Hold no player lease. |
| `yield os.sleep(ms)` | Suspend on a host timer, without the player. |
| `yield os.action('tend_crops', {plot})` | Queue one native crop pass. Resume with its terminal result. |
| `yield os.action('fish_once', {site})` | Queue navigation, rod equip, and one bounded native cast. Resume after hook release. |
| `yield os.action(name, {scope, ...selection})` | Queue an allowed operation; acquire its declared access context when required. Recheck its targets and stock at dispatch. |

The trusted library manifest sets priority and permitted actions. Installation grants specific plots, sites, worksite scopes, context names, and restock item IDs. The trusted operation catalog requires `pen:sheep` for shearing, breeding, culling, and wool collection, and `chest:home` for stock transfers; guest code cannot omit or forge those requirements. Source cannot raise its priority, choose arbitrary coordinates or transfer quantities, call arbitrary tools, or receive host objects. Base priorities are storage 14, sheep care 13, shearing/breeding/supplies 12, farms 10, birch 8, compost 6, and fishing 0. Waiting work gains one priority point per 15 seconds; reusing the active context adds three points. Ties retain request order. These provisional heuristics are observable in `work_selected` events, not an optimal scheduling claim.

Dispatch is cooperative at activity boundaries. A crop becoming ready does **not** interrupt a cast in this slice; it wins the next grant when that cast retrieves a bite or exhausts its wait budget (maximum 60 seconds of active native ticks, plus preparation and cleanup). This makes wasted casts and responsiveness measurable before introducing finer preemption. Fishing's bite response itself runs every client tick, not through remote polling.

The host polls observations roughly once per second plus bridge latency. These are level-triggered conditions, not callbacks that can be lost while a behavior is busy. A growing crop consumes no action slot. Unknown cells do not count as ready. Admission is not completion: the host retains an exact `JOB:` ID, polls terminal state, and verifies native release before granting the player again. Cancellation targets only that ID. Unconfirmed receipt, release, or transport failure stops scheduling; it never authorizes a second owner. Guest execution/capability faults disable that instance while healthy instances continue. Fishing skips repeat navigation until a land activity has moved the player; native cast validation still checks the standing position.

## Shared access contexts and stock

The runtime owns a pen visit across separately scheduled operations. It acquires the context once, serves ready work from any compatible behavior, then releases it before unrelated work. The context provider enters without wheat in hand and closes all pen gates; each operation rechecks its targets and verifies gate closure. A new observation is required after each operation. Dropped wool is a separate care request, so a successful shear no longer owns collection and an entire exit/entry cycle.

A visit ends when no compatible work is ready, another request wins scheduling, an operation fails, or its budget reaches eight operations or 60 seconds. Budget checks occur between operations; an in-flight native action keeps its own timeout. After ending a visit, waiting work outside that context gets a turn before reentry. Context entry and exit exclusively own the player just like operations. Exit cancels owned work if needed, gathers sheep away from the entrance when possible, puts wheat away, crosses from immediately beside the gate, and verifies closure. Cleanup failure stops dispatch and retains uncertain ownership. SIGINT/SIGTERM cleanup retains its authority while ordinary work is stopped. The visit budget excludes entry and cleanup; it is not a hard wall deadline.

The same scheduler owns `chest:home` through a second provider. Output storage, farm restocking, and sheep restocking can each contribute work to one open chest window. Every transfer re-inspects the owned window, calculates a current deficit or surplus, moves at most 64 items, and verifies both carried and stored counts in two later observations. One transfer is one operation boundary. A submitted click receipt alone never completes the operation. Window changes, a nonempty cursor, or unconfirmed transfer counts stop dispatch. Cleanup closes only the expected window ID and verifies return to the player inventory handler, including on interruption and after a failed transfer. Already-closed windows are reconciled; a replacement window is left untouched.

`ensure_stock` names a granted item, not a withdrawal amount. The host sums configured consumer floors for that item; duplicate requests share one work identity and do not add demand. At dispatch, it withdraws only the remaining deficit. Thus two consumers reserving eight of the same planting item require sixteen total, while two goals requesting that same stock do not require thirty-two. `store_output` uses the same ledger, preserving all consumer floors and temporary claims. It normally offers surplus batches of at least sixteen; a full inventory permits smaller deposits to free space before restocking. These are static configured reserves in this slice, not dynamic resource production plans or durable allocations.

Closed-chest contents are explicitly last-seen hints. The first deficit may open the chest to discover availability. An empty source or full destination produces a confirmed deferred result, allowing another compatible operation to continue in the same visit. That item waits for a relevant observed change or 1,200 advancing world ticks before another probe; it holds no player while waiting. Unrelated inventory changes do not wake empty-source requests. Missing supplies can therefore coexist with productive chores and fishing. No transfer is authorized from cached counts. The provider uses ordinary native transfers and their capacity/component preflight; it does not inspect unopened inventories.

Shearing, breeding, and culling remain independently eligible; culling requires an already sheared surplus adult. Breeding consumes two wheat and records a conservative five-minute cooldown per fed adult; client observations explicitly mark the real breeding timer unknown. Refused feeding backs off as well. The population target is eight: surplus sheared adults can be culled only while more than two adults remain. Babies are protected. The herd may temporarily exceed eight while those babies grow. Each visit enters without wheat in hand, closes the gate before interacting, approaches the closed gate before exiting, and verifies it closed after crossing, including interrupted visits. Opening requires the player immediately beside it. Recovery remains a separate bounded visit in this slice. A recovery action uses wheat and the native `lure_entities` executor to gather known escapees together with the other managed sheep; completion checks containment after the player exits. Sheep inside the pen are enrolled as managed. Optional `sheepIds` in the fixture preserves known identities across host restarts; unrelated wild sheep are not recovery targets. Client observations include sheep in a sixteen-block margin around the worksite, plus explicitly tracked sheep among loaded entities within 128 blocks. Recovery approaches distant followers before calling the native lure action, which has a 32-block admission limit. After luring, the behavior checks every fence gate on the pen boundary, since pathfinding may open an unconfigured entrance. It gathers animals away from the entrance when wheat is available, puts the food away, then approaches the closed exit.

Birch harvesting is restricted to the configured trunk columns and a small native acquisition region around each root. It collects nearby logs, saplings, and sticks, then releases the player while leaves decay. Replanting requires a clear root, grass/dirt support, and no remaining birch leaves in that canopy. Sapling growth does not hold the player.

Stock floors belong to consumers: eight planting items per farm, two wheat for sheep, and one birch sapling per configured root. Other consumers can spend only the surplus; every consumptive homestead action rechecks inventory and holds a temporary claim. Farm supplies replenish the configured planting items and sheep supplies replenish wheat from the home chest. Birch saplings remain protected but have no separate supply behavior yet. Compost uses surplus wheat seeds one at a time, waits for the bin to finish, collects bone meal, and applies it to growing crops with wheat preferred. Wheat seeds and saplings stay available to composting and replanting instead of being deposited as output.

Eligibility uses bounded, loaded client observations. Actions verify material outcomes where native acceptance alone is insufficient: feeding, shearing, planting, compost consumption, fertilization, and storage transfers. A missing pickup backs off instead of repeatedly monopolizing the player. The runtime can change behaviors inside a visit and switch contexts at operation boundaries. It does not interrupt the middle of an otherwise healthy native action for priority alone.

## Boundaries and limitations

The `quickjs-emscripten` package is pinned at 0.32.0. Each behavior receives a 25 ms CPU interrupt budget per resume, a one-second wall watchdog, a configured 4 MiB guest allocation limit, 256 KiB stack limit, 64 KiB source limit, and 16 KiB messages. Cold source initialization gets 250 ms CPU. CPU accounting covers the Node process during a synchronous guest resume; it is not an exact per-guest hardware meter. The host allows at most twelve instances, validates every effect and work offer, bounds immediate yields, and caps trace events. Tests exercise computation, allocation, and output failures. The allocation setting is **not a cap on the Node process or total WASM memory**, and this prototype is not an OS-level security audit. See the [embedding API](https://github.com/justjake/quickjs-emscripten) and the broader [sandbox research](../../docs/research/airicraft-os-sandbox-options.md).

Only definition source persists; generator frames, pending offers, context ownership, supply deferrals, and waits do not survive host restart. Restart requires idle native work, a closed container, and fresh world observations. SIGINT/SIGTERM requests cleanup, but a host crash or SIGKILL cannot run cleanup: an already admitted native activity may continue to its own timeout and an open container may remain open. There is no native expiring lease yet. Other limitations include no automatic tool crafting/restocking, route optimization across arbitrary locations, worker calls, a controlled baseline comparison, or a throughput claim. Storage capacity and tool durability remain finite. Native safety reflex holds, death, and world changes stop the trial.

The mod adds `inspect_crop_plot`, bounded client-visible `inspect_worksite` (including current screen identity/cursor state), asynchronous `start_crop_pass`, and `fish_once`. `close_container` accepts an optional exact `syncId` guard; existing calls without it retain their behavior. Driver-mode queued action receipts now include exact work IDs; normal embedded-planner wording is preserved. Block modification tools return asynchronous exact-job receipts in driver mode; embedded calls retain their completion-waiting interface. The native dispatcher keeps a fishing executor attached during hook cleanup before accepting a different executor.

## Validation

Host tests cover shared visits across independent rules, duplicate work, stale targets after entry, urgent work, visit budgets, aging, failed cleanup, stop during a retained context, sandbox bounds, capability isolation, repeated mixed handoffs, stale observations, unconfirmed native release, stock claims, herd protection, managed tree scope, compost reserves, and gate cleanup after partial entry failure. The deterministic demo completes several harvest/fishing cycles. Focused native tests cover bite handling, timeout, delayed-hook cancellation, foreign hooks, executor handoff, crop passes, active jobs, and tool schemas.

Run `node src/audit.mjs artifacts/<run>` after a trial to check the recorded lease/native-work ordering and summarize handoffs, admission delays, pen entries/exits, chest windows, verified stock transfers, deferrals, and the operations/subscribers served by each context visit. It writes `audit.json` and fails on recorded overlaps or unresolved owners/windows. These checks cover the recorded protocol; they do not measure every physical input or classify all idle time.

Live trial evidence and findings are recorded separately in `TRIAL.md` when available. Simulation or unit tests alone do not prove live fishing or crop efficiency.

## Roadmap position

[Define behavior instances and compositional execution](https://github.com/shinohara-rin/airicraft/issues/53) is resolved: child instances have owned lifetimes, parents settle their children before stopping, and parallel child failure cancels siblings by default with an explicit collect-all option. Invocation ownership remains separate from OS-owned shared activities and access contexts. The [evidence and walkthrough](design/behavior-composition.md) records what the prototype demonstrates and which lifecycle capabilities still need implementation.

The remaining architecture contracts are recorded in [Chart the Airicraft OS driver experiment](https://github.com/shinohara-rin/airicraft/issues/51). The [coordination walkthrough](design/coordination-walkthrough.md) checks their interactions; the [implementation and evaluation handoff](design/implementation-handoff.md) starts with a guarded native transfer and explicit admission, cancellation, and release evidence. These are design decisions and qualification requirements, not claims that the full contract already runs. Gameplay-specific sheep recovery is a separate scope.
