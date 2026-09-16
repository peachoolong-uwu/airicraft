# Airicraft OS in the mod

Airicraft OS runs in Java inside the Fabric mod. GraalJS evaluates JavaScript skill definitions in separate contexts; Java owns scheduling, resources, persistence, inference workers and native actions. Gradle supplies GraalJS 25.0.4. Node, npm and a separate GraalVM installation are unnecessary. An ordinary Java 21 JVM uses the engine's interpreter fallback.

Codex remains the behavior author: inspect outcomes, propose a definition, validate it, then install or replace it. The runtime executes installed behaviors without an LLM choosing each action. This experimental control surface is currently available in **Codex driver mode**.

## Run

Launch the normal driver client with its supported integrations:

```sh
scripts/codex-driver
```

Use the existing wrapper in another terminal. Join a world before starting the OS; verify `codexDriverActive: true` with `agent status --verbose`.

```sh
wrapper/build/install/airicraft/bin/airicraft agent tools list --verbose
wrapper/build/install/airicraft/bin/airicraft agent tools call --name os_runtime --arguments '{"action":"start"}'
wrapper/build/install/airicraft/bin/airicraft agent tools call --name os_library --arguments '{"action":"bundled"}'
```

The second call imports and validates eight reference definitions, returning names and immutable digests. It does not install them. Use the returned `observe_inventory` digest for a passive check, or `describe_structure` for a worker/fallback check:

```sh
wrapper/build/install/airicraft/bin/airicraft agent tools call --name os_skills --arguments '{"action":"install","digest":"<observe_inventory digest>","input":null}'
wrapper/build/install/airicraft/bin/airicraft agent tools call --name os_runtime --arguments '{"action":"status"}'
wrapper/build/install/airicraft/bin/airicraft agent tools call --name os_skills --arguments '{"action":"inspect","installationId":"<returned installation ID>"}'
```

Installation acceptance is not completion. Inspect until `phase: retired` and check the outcome. Shared native work may continue for other installations after one subscriber finishes. For shutdown, call `os_runtime` with `{"action":"stop"}`, then inspect until `started: false` and `native.unresolved: false`.

## Author and run skills

A definition contains a name, description, tags, capabilities, environment constraints, input/output contracts, dependency digests and executable examples. Behavior definitions add `mode` and JavaScript `source`. See the complete [reference definitions](../src/main/resources/airicraft/os/skills/).

The lifecycle is `os_library propose` → `os_library validate` → `os_skills install`. Validation runs the examples through the real engine and verifies contracts and dependency closure. It is deterministic evidence; it does not certify live gameplay or an inference provider. Installation also checks capabilities against the current operator configuration. Capability declarations are sufficient for offline example validation, but do not grant execution authority.

`install_batch` accepts an `installations` array of `{digest,input}` records. It lets independent skills become eligible together. `replace` takes an installation ID, new digest and optional input; the old invocation and owned native work drain before a fresh instance starts. `stop` on one installation withdraws its interest without cancelling work still needed by another subscriber.

Generator bodies use ordinary JavaScript and yield effect descriptions:

```js
function* main(os, quantity) {
  const received = yield os.demand('wheat', quantity, ['chest']);
  return received;
}
```

An `offers` definition instead exports `function offers(os, input, view)`, returning an array of `os.work(...)` declarations. Java periodically supplies the granted observation view, deduplicates offers and selects feasible work. An empty array means no current work. Offers own no player controls and must remain bounded computations.

| JavaScript API | Meaning |
| --- | --- |
| `os.observe({scopes, offset?})` | One page of granted facts; unknown and stale values remain explicit. |
| `os.wait(condition, options?)` | Suspend this invocation until a level condition, deadline, observation gap or epoch change. |
| `os.spawn(alias, input?, options?)` / `os.join(handle)` | Owned child invocation using a pinned dependency. Parents cannot abandon children. |
| `os.target(resource, quantity)` | Maintain a protected stock floor for the root consumer. |
| `os.demand(resource, quantity, methods?)` | Request an additional finite delivery; expected production is never observed stock. |
| `os.work(operation, arguments?, context?)` | Request a trusted native operation with Java-owned priority, constraints and accounting. |
| `os.worker(alias, input)` | Call a pure interpretation worker with a pinned deterministic fallback. |

Conditions use `{scope,path,equals}`, `{scope,path,atLeast}`, `{scope,path,known:true}`, or bounded `all`, `any` and `not` combinations. Wait options can include a returned `{epoch,sequence}` cursor and a deadline: `{clock:"wall",milliseconds}` or `{clock:"eligible_ticks",scope,ticks}`. Eligible time comes from explicit native counters for configured chunks/entities; unloaded, paused or unobserved time is not inferred from wall time.

## Configure trusted operations

Start accepts a `configuration` object with `operations`, `resources`, `views`, `supplies`, `progressScopes` and `workerProfiles`. Skills cannot change it. The quick container binding is:

```json
{"action":"start","configuration":{"bindOpenContainer":true}}
```

Open the desired chest before this call. It binds the current window identity as operation `chest`, capability/context `container:home`. The bundled `withdraw_wheat` behavior accepts an integer from 1 to 64. Several withdrawals can share one visit; a visit closes after eight operations, 1,200 confirmed server ticks, incompatible work, or no feasible work. The binding currently cannot navigate to or reopen a closed chest. Reconfigure a new run after opening it again.

Explicit operation entries are `{scope,windowId,priority?,context?}`. Resource entries are `{key,grant,methods,priority?}`; keys identify location, item and exact component variant. A view is `{location,resources:{alias:resourceKey}}`. The default `inventory` view has no selected resource facts: configure aliases to read quantities. Supply entries are `{resource,operation,arguments,maximum}`; arguments omit `quantity`, which Java fits to demand, protected floors and destination capacity, up to 64. Compatible demands share a batch and credits derive only from verified native results.

`progressScopes` uses the native `{scope,chunks:[{x,z}]}` or `{scope,entities:[uuid]}` subscriptions. They observe eligible completed ticks without loading chunks. At most 32 total view/progress scopes are configured.

Worker profiles map a name to `{endpoint,model,apiKey,tokenLimitField?}`. The endpoint is an OpenAI-compatible chat-completion URL; HTTPS is required except for loopback testing. `tokenLimitField` is `max_completion_tokens` by default or `max_tokens`. Profiles are supplied at runtime, not stored in skill definitions or trace configuration. No profile is configured automatically: the bundled workers use their pinned fallback until one is supplied. Workers have no tools; identical calls share a computation, and changed granted evidence invalidates results before delivery.

## Ownership, persistence and limits

The Java actor handles policy away from the Minecraft thread. Each Graal context has its own execution thread. Native requests enter the Minecraft client thread directly through the existing native driver; execution does not spawn wrapper processes or send per-action HTTP calls. Lease renewal has an independent thread. Intent records are durably written before admission, and a lost response is reconciled by identity without replaying the action.

Storage lives under the game directory's `config/airicraft/os/`: content-addressed definitions and lifecycle history in `library/`, desired installations in `installations.json`, unresolved/settled native intents in `effects.json`, and per-run traces in `trace/`. A file lock prevents two runtimes from opening the same store. Restart reconciles unfinished effects before creating fresh instances; JavaScript stacks are not serialized. Explicit stop, world leave and clean shutdown retire installations. Desired definitions survive an interrupted process, including a replacement that was draining.

The current limits include 12 roots, 32 live invocations/guest contexts, depth 8, 16 KiB effect messages, 64 KiB sources, 200,000 statements per evaluation, a 10-second initialization deadline and a 1-second resume deadline. Library storage is bounded to 256 revisions and 128 lifecycle entries per revision. Traces preserve up to eight runs of 64 MiB each; reaching capacity stops admission rather than overwriting evidence, with 1 MiB per run reserved for cleanup. Export and remove reviewed trace runs before the store fills.

Worker limits are two concurrent provider calls, 16 queued computations, five seconds in queue, 20 seconds for a provider response, 12 calls per runtime session within 30 minutes, and at most 512 output tokens per call. Reconsideration uses 1,200 eligible ticks when exactly one granted progress clock is available; unrelated clocks are not merged into a timing guarantee.

Host classes, filesystem, network, process/thread creation and Minecraft objects are unavailable to guest code. These restrictions and execution deadlines do **not** provide a hard per-skill heap limit or strong isolation from hostile code in the shared Minecraft JVM. This is a trusted behavior-authoring experiment.

## Current qualification boundary

The Java OS connects to the native **container** action surface. Farming, sheep care, birch, composting and fishing still need native operation/observation adapters on this runtime. Their older prototype definitions are retained as [historical skill sources](../experiments/os-prototype/library/); their earlier gameplay results do not qualify the new Java execution path. Java scheduling contains the land-before-fishing, bounded fishing yield, context reuse and overdue fairness rules, but fishing policy tests are not live fishing support.

Use focused checks with `./gradlew :test --tests 'ai.moeru.airicraft.os.*'`. The [migration record](os-java-migration.md) records current focused, full-build and packaged-client evidence. [ADR-0003](adr/0003-embed-airicraft-os-in-java.md) is the hosting decision; the old Node host and tests are archived, not another runnable implementation.
