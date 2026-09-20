# Policy continuation implementation and live test — 2026-09-20

Implemented on `codex/graal-policy-prototype` in `3bcbfea6`, `cda94c87`, and `80d731b1`. The pre-existing untracked content inside `vendor/action-plan-advisor` was left untouched.

## Shipped behavior

One root `run_policy` can execute while a separate backend prepares one guarded next policy. The speculative registry exposes only its proposal schema, with no gameplay executor. Hypothetical output is isolated from normal conversation and narration. Client-thread admission checks world/owner, goal, guidance, safety epoch, travel restrictions, health relative to the starting observation, successful parent work/effects, exact parent return value, minimum inventory, loaded block matches, and idle actuator ownership. The normal executor remains the final admission gate. Late results do not delay ordinary planning. An uncancellable request drains without queueing more speculation behind it.

Routine child completion, crafting, pickup, and idle-think wakeups are suppressed while the root policy owns work; safety and direct guidance remain able to interrupt. Both ordinary and speculative planner prompts explain finite procedures, outcome checks, and deterministic return values. See [code-policy.md](../code-policy.md).

## Automated validation

`source .envrc` followed by `./gradlew build` passed after the final code change: 1,441 root tests (two existing skips) and 95 wrapper tests; zero failures/errors. This is 1,534 passed tests. Regression checks cover pending/accepted handoffs, no dispatch before completion, isolation from accepted history, one-time dispatch, changed world/goal/guidance/safety, parent failure, failed child effects, mismatched returned results, inventory/block guards, missing guard rejection, late responses, bounded outstanding requests, the isolated tool surface, and routine progress wakeups without suppressing damage.

The original guard and scheduler tests were observed failing before implementation. The tool-surface regression and routine-event regression likewise failed before their corrections. Existing Java deprecation warnings remain.

## Recorded runs and limits

1. `20260920-174434-781662-41812-a5e9b273-f7a6-4f3f-831f-918f0b13fc12`: manually finalized to narrow the speculative tool registry after source review found inherited default tools. Recording complete.
2. `20260920-174706-393926-42654-6a1d0142-db4d-483a-b914-fde7c0e27bd1`: demonstrated speculative generation during active policies, late-result discard and parent-failure rejection. It also exposed a routine `crafting.item_crafted` trigger launching the ordinary planner during a policy; `80d731b1` fixes that path. Manually finalized before rebuilding; recording complete.
3. `20260920-175133-930952-43919-1a7b0186-1e47-490e-9aae-464e19838fee`: final code, copied checkpoint, normal survival supplies objective. Root policies began at client ticks 833 and 963; speculation began at 835 and 965. Both parents returned early after native action failures, and their speculative requests were discarded at ticks 855 and 1068 as `not_ready`. The live requests advertised only the guarded `run_policy` proposal. The run subsequently spent several minutes in a native surface-return task and paused on a planner report.

There was **no accepted continuation in the live runs**, so reduced idle time or successful gameplay handoff is not established. Acceptance and one-time execution have integration-test coverage. The live evidence establishes overlap and safe rejection/fallback; native recipe/argument/navigation failures constrained coverage.

The third recording finalized as `REPORTED`, `recordingComplete: true`, after validating the report and stopping the helper gracefully. Its report's claim that surface return had not completed was stale by submission time. The distinct tool-selection failure below is supported by the recording. The client is stopped.

Artifact root (relative to the checkout):

`automatic_playtest/v1/airicraft-evaluation--056bbce9-0b26-4a0c-a3c5-b494f5b86980/players/AiricraftTest--d0a06f8c-4222-3e72-988a-8e0924bde20d/plays/20260920T095158.802Z--ce2d9078-b510-4b6a-ae54-d109ae9d5cc3`

Entry point: `extensions/airicraft.playtest/playtest.json`. Evidence: `events.jsonl.gz`, `llm-calls.jsonl.gz`, `live-recording.jsonl.gz`, `flight-final.json.gz`, paused screenshot and `world-save.zip`. The world checkpoint is preserved; primitive capture/replay was not altered. Handoff `bef01e37-0a02-4542-aece-ddf0fb6fc27d` was processed here; duplicate delivery must not restart this run.

## Separate investigation: navigation mines with an unsuitable held item

The user observed the player mining its way out without a pickaxe. The player actually carried two usable pickaxes, but held a furnace.

- Work: `JOB:job-444d8cce-5e65-4c7c-b682-96785eeaf19c`, `RETURN_TO_SURFACE`, accepted at client tick 1447.
- All 314 running-task snapshots from ticks 1450–7801 (317.527 wall seconds) report `equippedItemId: minecraft:furnace`. In 242 of those snapshots the planner was not in flight.
- The player moved from approximately `(-1,108,6)` to `(-9,130,13)`. It was making progress, so a pure no-progress detector would not reliably identify the inefficiency.
- The final paused inventory confirms the furnace in selected hotbar slot 1, wooden pickaxe in inventory slot 33 (damage 4/59), and stone pickaxe in slot 34 (damage 16/131). The pickaxes were outside the hotbar.
- Surface return completed at tick 7807 with `surface_reached`. The report was submitted at 7981, then both clocks paused at client tick 7983 / debug server tick 7900. The report used an older running observation (tick 7322), so it is not proof that the task never completed.

### Cause supported by code and runtime evidence

`BaritoneSettingsProfile.apply` enables `autoTool` but disables `allowInventory`. Inspection of the bundled Baritone 1.15.0 `ToolSet.getBestSlot` bytecode confirms selection loops over slots 0–8. `ReturnToSurfaceTaskExecutor` calls `startNavigateNear` without a mining-tool preflight. `BaritoneTaskExecutor.applyGoal` does preflight for explicit `MINE_BLOCKS`, but not navigation. Consequently a suitable tool in main inventory is not brought into the hotbar for incidental path excavation.

A separate observability gap compounds this: `currentPhysicalState()` exposes position, velocity, grounded/water/climbing flags, while work remains `RUNNING` / `navigating_to_surface`. It does not expose the actual block-breaking action, target block, held tool, or comparison with a suitable carried tool. Earlier inventory text mentioned the held furnace, but there is no fresh execution warning tying that fact to current excavation. A gravel pickup woke the planner at dispatch tick 4184; it chose `wait_for_work` again.

### Proposed next work; not implemented in this change

Keep two concerns distinct:

1. Select an appropriate carried tool at the shared navigation block-breaking seam, covering incidental excavation as well as explicit mining. Do not solve this by requiring a planner round-trip before every block.
2. Publish compact, factual execution check-ins for long-running work: work ID, intended task, actual action, target, held tool, suitable carried tool, elapsed duration and recent progress. Sample cheaply, deduplicate/rate-limit reports, and submit the latest meaningful report only when the planner is free. Preserve meaningful anomalies while it is busy without launching competing requests or narrating speculation.

A useful diagnostic example is: “Returning to surface; breaking stone; holding furnace; stone pickaxe available in slot 34; work has run for several minutes with slow upward progress.” The planner can then reassess strategy using actual behavior. Unknown failure patterns should remain observable, rather than restricting the report to a hard-coded no-progress condition.

No tool-selection or execution-check-in fix was made during this separate investigation. No performance improvement from such a fix is claimed.
