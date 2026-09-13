# System 2 refactor validation — 2026-09-14

Implementation and automated validation are complete. Live acceptance is partial: the configured controller collected wood, the thinker repaired the immediate shelter approach and returned control, then the provider rejected the controller with HTTP403 (Vercel Security Checkpoint). No model or thinking-effort setting was changed. The game remains paused; no further provider requests were initiated after diagnosing that block.

## Automated verification

The final `./gradlew build hotswapMain` passed (`/tmp/system2-work-context-final-build.log`). Root suite:1254tests, zero failures/errors,2skipped. Wrapper:94tests, zero failures/errors/skips. Earlier integrated subsystem builds and live corrections are recorded in D130–D140 of [the playtest log](autonomous-playtest-log.md).

| Contract | Evidence |
| --- | --- |
| Fresh outcomes between consecutive tool calls | PlannerOrchestratorTest inserts an identified failure while a model call is running; the next request contains it once and preserves tool pairing. |
| Frozen retry, fresh next decision | Timeout retry preserves the request and leaves the later failure unincorporated; the next gameplay request incorporates it. |
| Compaction, overflow, world/owner changes | Successful compaction preserves the role cursor and reprojects unresolved work/hold identity. Overflow reports its missing range. Changed world or decision owner rejects late actuation. |
| Work lifecycle and identity | WorkHistory/WorkProjection/runtime tests cover bounded terminal retention, graph children, exact held identities, unknown furnace outputs, cancellation semantics and waiting. Requested parameters remain inspectable without conversation history. |
| Immediate outcomes | A live-reproduced craft/placement receipt mismatch failed the new regression before correction. Terminal executor observations now enter the existing job reducer and evidence before completing the tool future. |
| Objective ownership | v1 migration preserves terminal status; controller-only mutation, blocked persistence/reassessment, bounded decisions and replacement reasons are covered. Tool budgets preserve active goals/delegations. |
| Delegation and prefixes | Real orchestrator fixtures transfer tools twice, preserve separate histories and valid pairing, retain effects across stale ownership, and preserve all bounded objective decisions. Fixed role schemas remain unchanged by discovery. |
| Reflex supervision | Active and inactive unchanged holds do not loop after plaintext. Blocked objectives still permit identified safety supervision without resuming the objective. Competing work requires observed release and exact work/hold resumption. |
| Search, travel and geometry | Search detours, occupied movement/head/jump envelopes, user/strategy intersection, partial support, unsupported feet, head obstructions, unloaded observations and door geometry have focused tests. Travel changes enter semantic evidence; route failures identify the failed path predicate and bounds while leaving the cause unknown. |

These tests establish code behavior; they do not replace the remaining actual-planner world trials.

## Actual planner and world evidence

World: `planner playtest`, Easy. Normal configured controller/thinker client, without Codex-driver gameplay tool execution. The old GIVEN_UP objective6713b725 was explicitly migrated to v2 without reactivation. The controller created objectiveb8230738-0152-4146-be67-df874622b2c6, which remains ACTIVE despite the provider failure.

- Collection JOB:job-0efc7ae9-5333-4377-b8fc-fe364ee7523b produced3spruce logs. Pickup events12–14, terminal event15, fresh inventory and the paused player snapshot agree.
- The controller delegated a bounded repair. The thinker navigated, waited through work handles, inspected geometry, crafted3logs into12planks, recovered from a support_not_found placement by changing support face, and placed2planks. Inventory ended with10planks.
- Independent frozen-world queries at snapshot `ccf43b39-ca43-4968-86dd-33f904a2dd7f:tick:24515` confirm spruce_planks at(1,133,3) and(1,133,5), and the open west-facing lower spruce door at(0,134,4). The model-visible standing query at feet(1.5,134,4.5) reports supported, clear feet and clear head.
- Navigation farther east to(2,134,4) failed CALC_FAILED. The thinker retained that limitation in its claimed return rather than claiming a complete route. The observed work failure remains under its original identity.
- The controller's return request at22412 contains shared events21–77, including the failed attempt and completed work, plus the thinker's separately labelled claimed outcome and final facts. HTTP403 prevented a model response to that request.
- Local class corrections were HotSwapped while paused. The structural initial refactor required a clean restart; a stale HotSwap baseline failure was discarded with another clean restart, then subsequent reloads succeeded.

The operator explicitly permitted unilluminated **surface wood only** after existing illumination-policy rejections. This did not grant underground mining permission. A concise delegation instruction reduced repeated restatement of context already supplied by the handoff.

## Measurements and limits

Agent ticks below use nominal20TPS and exclude tick-debug pause intervals. Model transport latency is separately recorded in milliseconds. These are incident measurements, not a controlled before/after benchmark.

| Measurement | Observed result |
| --- | --- |
| Successful collection executor time | ticks7286–7586:300ticks, approximately15seconds. |
| Collection terminal to next decision context | event15 at7586 → controller context8124:538ticks, approximately26.9seconds. The preceding model call was still in flight. The next request contained the outcome, without a plaintext prerequisite. |
| Placement failure to next decision context, before the immediate-outcome fix | event50 at16857 → thinker context16981:124ticks, approximately6.2seconds. Detailed failure text arrived sooner, but the common receipt incorrectly said RUNNING; fixed and regression-tested. |
| Failed farther-east navigation to next decision context | event74 at21360 → thinker context21376:16ticks, approximately0.8seconds. |
| Time from first post-restart request to successful collection admission | ticks548→7286:6738ticks, approximately337seconds, including rejected approaches and illumination investigation. |
| Short delegation to first thinker actuation | controller dispatch14833 → navigation admission15133:300ticks, approximately15seconds. |
| Retained completed read requests | Controller:2inspect_world and1image; thinker:3inspect_world. Earlier reads are missing from the bounded exports, so these are lower bounds. The daylight image and torch lookup did not unlock collection; the explicit surface-wood permission did. |
| Repeated failures | Existing illumination rejection repeated; long delegation output timed out twice in the retained dispatch records. Repair attempted one unsupported placement, then used a different support face successfully. Farther-east navigation failed once. |
| Final provider block | Three actual controller HTTP403 dispatches; additional recorder failure records with dispatchTick=-1 are synthetic reports and are excluded from request counts. Runtime entered degraded scheduling; gameplay objective remained ACTIVE. |

The exported union retains27actual dispatch records, with gaps at calls4,5,23. Completed call snapshots were supplemented from contemporaneous bounded CLI queries where available. This is insufficient to claim an exact full-run redundant-read rate or reliable p95 latency. Per-dispatch metadata is saved in `run/playtest/2026-09-14/system2-metrics.json`; local diagnostic exports are ignored by Git and may contain private gameplay/chat context.

Relevant preserved exports: `system2-after-restart.jsonl`, `system2-logs-collected.jsonl`, `system2-immediate-outcome.jsonl`, `system2-delegation-return.jsonl`, and `system2-provider-blocked.jsonl` under `run/playtest/2026-09-14/`. Later exports explicitly report retention truncation; no complete10minute retention claim is made under the64MiB cap. The delegation-return export was collected while running; the incident exports before code changes and final provider-blocked export were taken while paused.

## Remaining live acceptance

- Controller creates/places a chest, deposits and retrieves an item, then loads the existing furnace and collects charcoal across consecutive tasks. Unified furnace visibility/cancellation is automated-only in this refactor run.
- Reproduce a reflex interruption with the configured roles, observe supervision/policy change and ownership release, then explicitly resume its held work. Automated coverage passes; actual-planner post-refactor proof remains pending.
- Deliberately demonstrate a resource detour outside a narrow search region and an enforced explicit travel boundary, including intermediate movement. Surface gathering succeeded, but that does not establish this controlled detour/restriction trial.
- Recheck the immediate terminal receipt correction live on the next craft/placement, and extend metrics after provider access is restored. No attempt was made to bypass the security checkpoint.

Resume from the current paused world and preserved active objective once the provider's supported API access is restored. The latest pause is session`ccf43b39-ca43-4968-86dd-33f904a2dd7f`, epoch1, client24515. Preserve the existing histories and inventory; do not restart the finished historical objective.
