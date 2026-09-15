# Airicraft OS implementation status

This follows the settled [implementation handoff](../design/implementation-handoff.md). Work is on `os-exp`. The existing prototype and unrelated JourneyMap edits were preserved in `run/os-implementation/baseline-20260915-233217` before implementation began.

## Current slice: native authority

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

## Remaining implementation

1. Broker ownership, child joins and shared subscribers; resource ledger and durable unfinished-effect journal.
2. Supervised per-root QuickJS processes, bounded IPC and versioned library installation.
3. Bounded inference-only worker functions and fallbacks. A real model profile is not configured.
4. Scheduling and native crop, sheep, birch, compost, supplies, storage and fishing operations.
5. Complete trace/opportunity/time accounting and staged evaluator integration.
6. Prepared live qualification, mixed paired runs, context ablation, and revision/reuse evidence.

Escaped-sheep repair, mining, embedded authorship and production confinement remain outside this experiment. The architecture map is closed; this document tracks implementation, not a claim that the experiment has passed.
