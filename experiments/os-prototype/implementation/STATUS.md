# Airicraft OS implementation status

This follows the settled [implementation handoff](../design/implementation-handoff.md). Work is on `os-exp`. The existing prototype and unrelated JourneyMap edits were preserved in `run/os-implementation/baseline-20260915-233217` before implementation began.

## Native authority

The new driver-only boundary has identified submit, inspect and cancel operations; a renewable five-second lease; world/load fencing; two-second observation freshness; a submission high-water mark; and retained receipts. A guarded transfer uses the existing container transfer planner and vanilla container-click mechanics, with server-side validation at the actual effect. One effect is outstanding at a time. Cancellation preserves verified delivered items and returns the remaining cursor stack before closing the owned window.

The server effect permit is checked again after queuing and serializes revocation with the actual effect. A stale Minecraft client snapshot does not authorize transferring a replacement stack. Server reads are limited to the player's currently open handler. This first adapter requires an integrated server.

The public wrapper exposes `os_observe`, `os_lease`, `os_submit`, `os_inspect`, and `os_cancel` in Codex driver mode. Ordinary gameplay dispatch, direct bridge camera control and runtime reload are fenced while native ownership or cleanup remains. Once OS mode has been activated, death does not trigger automatic respawn.

Focused deterministic tests cover duplicate/lost submissions, receipt eviction, wall-clock expiry, load changes, stale observations, cancellation before/after effects, reflex/external control, executor failure, delayed confirmation and resource allowances. The Minecraft adapter compiles. The first live transfer/cancellation qualification passed on 2026-09-16; details and limits are below.

The [native protocol](native-protocol.md) now includes a 30-second confirmation watchdog, a 512-event history with explicit gaps, request/capture provenance, affirmative release evidence, and bounded malformed-request checks. World leave, shutdown, reflex takeover and operator stop revoke already-queued effects synchronously. The two-axis source review, focused regression, and first live transfer/interruption cases are complete. Later native operations and host-crash qualification remain separate work. A timeout continues to report unresolved release until cleanup is proved.

## Native qualification evidence

On a copied survival fixture with JourneyMap and REI, a six-string transfer succeeded. Completion was determined by querying the request ID after discarding the admission result; resubmitting the identical request returned the unchanged settled receipt. Freshly reopening the chest independently verified chest/player totals of 116/6, from an initial 122/0.

A subsequent 32-string request was cancelled after partial progress. Four strings were delivered and 28 remained unconsumed. Both server and client confirmed the owned window closed, cursor empty, and controls released. Reopening verified totals of 112/10. The complete native event history through sequence 58 was retrieved without a gap. This is functional qualification on one fixture, not a throughput comparison or host-process crash test.

The first attempt was rejected before admission after its chest closed between two observations. No items moved and no native ownership remained. The recorder incident was exported before continuing; an isolated handoff check and both subsequent probes did not reproduce the closure. Its cause remains unestablished. The probe now rejects changed-window preparation and distinguishes explicit non-admission from unknown acceptance; native code was not changed to explain the incident.

Ignored local evidence is under `run/os-implementation/native-qualification/`: fixture/build manifests and archive, initial rejected trace/export, `transfer-1789492206879-result.json`, `cancel-1789492276105-result.json`, fresh-container verification traces, and `qualified-transfer-incident.jsonl`. The original save was preserved. The live build included the preserved prototype/JourneyMap edits, recorded in the build manifest; those edits are not included in this native commit.

The corrected native tests (30) and EmbodiedAgentRuntime tests (115) pass. The full root suite had one one-second planning timeout among 1,344 tests (two skipped); its 51-test class passed when rerun alone. Wrapper (97), advisor (10), and existing prototype (50) checks passed. The original full-run failure remains recorded. See [native-review.md](native-review.md) for the independent Standards and Spec findings and corrections.

## Broker foundation

The new `src/os/` modules are separate from the preserved earlier prototype edits. Deterministic checks currently cover owned parent/child completion, cancellation versus physical cleanup, explicit collect-all, shared subscriber withdrawal, reclaimed live capacity and bounded result handles, child grant/depth limits, atomic stock/tool/capacity/target claims, floor/claim overlap, and reconciliation after external stock loss.

The unfinished-effect journal uses Node's built-in SQLite connection on a dedicated writer thread. DELETE journaling with synchronous EXTRA and macOS fullfsync is selected; transactions keep 1,024 unfinished effects and 256 recent settled receipts. An acknowledged entry survived an abrupt test-host SIGKILL. This storage test does not yet qualify crashing the complete broker during live Minecraft execution. The public SQLite API was checked against the installed Node 26.7.0 runtime and [Node's documentation](https://nodejs.org/download/release/v26.5.1/docs/api/sqlite.html); the durability settings follow [SQLite's synchronous documentation](https://www.sqlite.org/pragma.html#pragma_synchronous).

A native effect broker now records intent before submission, reconciles a lost reply by exact ID, checks retained receipt identity and release, and quarantines unknown recovery rather than replaying it. Native authority additionally exposes the generation associated with its high-water mark after lease expiry; this allows a fenced, never-admitted request above that mark to be distinguished from an evicted or ambiguous one. Post-identity preflight rejections retain queryable zero-effect receipts. The protocol corrections pass 33 native tests. The corrected build also completed the live broker qualification below; a live never-admitted recovery case remains unexercised.

The effect broker renews its lease independently and stops admitting work after a failed heartbeat. Focused races cover stop during lease acquisition, a stalled renewal, durable intent append, and native submission; an unconfirmed revocation remains an obligation across repeated stop calls. The public-wrapper adapter checks driver mode, OS method/schema boundaries, response size, and observation freshness including the complete transport round trip. Its startup handshake is a discarded read-only native observation; it does not load the legacy planner status. Every subsequent actionable observation still passes the two-second age check.

Finite delivery IDs now distinguish retries from additive demand. Shared bounded supply attempts assign output to explicit IDs once, preserve previously credited output on withdrawal, and admit new subscribers only against applicable unallocated output. Expected production never becomes observed stock. The ledger retains at most 256 delivery slots and 32 supply slots; explicit result consumption reclaims capacity, while monotonic ID high-water marks reject retired IDs. Cumulative native output must be accounted for before credit, and unfinished commitments survive uncertain release.

The first integrated coordinator uses an exact fresh native frame for invocation grant checks, atomic resource admission, durable submission, and eventual release. Its bound-container adapter supplies native consumption/capacity allowances and distinguishes item components; mixed source variants are rejected while the native transfer chooses by item ID. Simulated cases cover another consumer's protected stock and a shared transfer whose last subscriber must await cancellation cleanup. The journal includes activity, bundle and delivery provenance.

The coordinator also supports joining applicable spare output in flight, including grant/consumer checks, bounded subscriptions and idempotent join retries. Cancellation is rechecked after asynchronous receipt delivery, before assigning any new output credit. A failed journal stops new admission and requests native revocation. Failed startup cannot acknowledge release before old obligations have been discovered and reconciled. Native request identity uses one validated tuple across crediting, recovery and journal lookup, independent of JSON field order.

The two-axis [broker source review](broker-review.md) and live transfer qualification are committed as `b6c5d273`. That foundation passed all 100 JavaScript tests: 50 existing prototype tests and 50 new host tests. The host API is described in [broker-api.md](broker-api.md). These foundations do not yet run installed guest definitions or dispatch the mixed reference duties.

The next host slice adds cycle/missing-dependency validation before registering up to 32 declared supply operations, with an explicit dependency path on rejection. A bounded append-only decision trace now records invocation creation, body return, stop, distinct child outcomes and explicit/mandatory joins; shared activity ownership; successful and rejected admissions with their available stock/capture basis; native intent/receipts; output accounting and claim release. This is host lifecycle evidence, not the later complete native-event/opportunity/timing observer.

The trace uses four segments of at most 16 MiB within a 64 MiB run budget, including a 1 MiB cleanup reserve, and a 4 MiB writer queue. Serialized UTF-8 bytes and segment-boundary waste count before enqueueing. Ordinary records stop on quota/queue failure; a gap marks the stream incomplete and reserved cleanup can continue. Disk failure revokes native authority, blocks new activity, and preserves journal-backed physical reconciliation. Tests exercise real file writes, mandatory child result retention, reduced test quotas, a stalled writer and an injected disk failure. The two-axis [trace source review](trace-review.md) has zero remaining findings; focused tests pass 29 cases and the complete JavaScript regression passes 108. This additional trace has not been live-qualified.

## Broker live qualification

The corrected native build and integrated invocation/ledger/journal path were exercised through the public wrapper on `OS-Broker-20260916-020619`, a fresh copy of the same archived survival fixture. Original saves were preserved. JourneyMap and REI were enabled; no embedded planner ran.

- One shared six-string transfer credited three units to each of two independent roots. Both normal returns waited for the same verified physical release. Reopening independently confirmed chest/player stock changed from 122/0 to 116/6.
- A 32-string shared transfer continued after the first subscriber withdrew and stopped after the second withdrew. Five units actually moved; each demand retained its two units credited before withdrawal, and the final unit remained unassigned stock. Native cancellation, window/cursor/control release and journal settlement were verified. Reopening confirmed 111/11.
- During a 64-string request, the host was killed with SIGKILL after two verified units, while its admission reply was deliberately withheld. The durable journal contained one unfinished intent and no receipt. Lease expiry cancelled native work at fourteen transferred units with fifty remaining and affirmative release/accounting. A fresh broker reconciled that exact ID, settled the journal, and submitted zero new native actions. Reopening confirmed 97/25, matching all three operations.

Native history through sequence 135 had no gap. Local evidence is under `run/os-implementation/broker-qualification/`: fixture/build manifests, `transfer-1789496870325-result.json`, `cancel-1789497083990-result.json`, `crash-1789497136877-trigger.json`, its SQLite journal and `crash-1789497136877-recovery.json`, plus independent fresh-container checks. `qualified-broker-incident.jsonl` contains 742 recorder observations covering server ticks 1700–7673, with `export_complete`, no truncation and no reported drops. The owned client was stopped after export. This qualifies one broker transfer/cancellation/crash path; it is not a mixed run, a scheduler comparison, full restart coverage, or runner-process qualification.

The initial world join timed out while the client continued loading. Some legacy status handshakes also timed out before any native admission; those failed attempts remain recorded. A direct native handshake was then measured and adopted. Client load variability was not calibrated for timing claims, and the functional tests do not establish throughput improvement.

## Supervised execution

The supervised execution foundation is now implemented separately from the earlier prototype entrypoint. Each root owns one process, and each live invocation has a separate QuickJS VM. Copied typed effects, bounded source chunks/framing, a one-second external watchdog, independent root failure, shared CPU/message budgets, round-robin execution and RSS supervision are covered by the [execution API](execution-api.md). Runner failure retains native cleanup obligations in the broker; it does not claim the player is free.

The two-axis [execution source review](execution-review.md) has zero remaining findings. Focused VM/process/pool/broker checks pass 26 tests. The first full JavaScript regression passed 123 of 124: an infinite-loop fixture hit the configured wall guard before its CPU guard, while the test expected only the CPU guard. The corrected expectation accepts either independent limit; production limits were unchanged. The complete rerun passes all 124 tests. Both logs are preserved.

The corrected-source footprint probe reached twelve roots and thirty-two lightweight live VMs. Thirteen samples recorded a peak group RSS of 739.875 MiB and a peak root RSS of 82.46875 MiB, below the unchanged 768/256 MiB stop thresholds. All runners were closed afterwards. Evidence is in `run/os-implementation/runner-qualification/footprint-1789499742315.json`, with runtime/package-lock and reviewed-code identity. An earlier diagnostic probe also passed at 729.89 MiB group peak. The initial attempt stopped on an unavailable memory reading; its cause remains unestablished and its artifact is retained. The reader now retains bounded failure diagnostics. These short lightweight-VM measurements do not establish peak memory or efficiency of the Minecraft reference workload.

## Remaining implementation

1. Versioned source/dependency library, candidate validation and drain-before-replacement; connect guest effects to owned child, condition, demand and work services.
2. Bounded inference-only worker functions and fallbacks. A real model profile is not configured.
3. Scheduling and native crop, sheep, birch, compost, supplies, storage and fishing operations.
4. Complete trace/opportunity/time accounting and staged evaluator integration.
5. Prepared live qualification, mixed paired runs, context ablation, and revision/reuse evidence.

Escaped-sheep repair, mining, embedded authorship and production confinement remain outside this experiment. The architecture map is closed; this document tracks implementation, not a claim that the experiment has passed.
