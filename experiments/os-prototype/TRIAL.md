> Historical Node prototype evidence. The active runtime is now the Java/GraalJS mod implementation; see the archived-directory README and docs/embedded-os.md. Results below do not qualify the new execution path.

# Crop/fishing prototype trials — 2026-09-15

These are engineering trials in the user's `OS-Exp` survival world. They are not the planned 30-minute mixed-duty benchmark or a baseline comparison. The client ran in verified external Codex-driver mode. Codex prepared inventory and configured locations before each run; the host performed gameplay during runs. No growth-speed change, item spawning, teleportation, or manual crop-maturity injection was used.

**Result:** the prototype planted 22 starting crops, used growth waits for fishing, observed a natural readiness handoff, and—after correcting a native approach failure—harvested and replanted potatoes before returning to fishing. The final inventory contains 50 fish. Scheduling and native corrections were made between runs; this is not one uninterrupted successful run.

## Starting world and preparation

The existing farm has 80 farmland cells around one water block: X -309 through -301, Z -393 through -385, soil Y 63. Crop Y is 64. Three disjoint strips share the space: wheat X -309..-307, potato X -306..-304, carrot X -303..-301. The verified fishing stand is (-289, 63, -358), targeting water at (-289, 62, -353).

Live chest inspection found six wheat seeds, eight potatoes, eight carrots, eight birch saplings, 64 sticks, 128 string, 64 oak planks, an iron hoe, an iron axe, a water bucket, and two shears. Preparation withdrew the planting stock and materials for one fishing rod and crafting table, crafted through the normal tool executor, and closed the chest. A starting save copy is retained locally under `artifacts/world-backup/OS-Exp/`.

## Initial trial and correction

`artifacts/live-01/` records about 137 seconds of advancing world time before a manual diagnostic stop. The three native crop passes planted all 22 available crops: six wheat, eight potatoes, eight carrots. Each instance entered `WAITING_WORLD` after exhausting its planting stock. Fishing then received the player without a Codex gameplay command.

Two casts failed at approximately 100 active ticks with `fishing_cast_not_in_water`. A separate reproduction using the same native arguments was paused and exported before changing code. At that boundary, bobber entity 716 was at approximately (-288.482, 62.915, -351.661), floating over a source-water block at (-289, 62, -352), while its generic `touchingWater` flag was false. The old check therefore rejected a valid floating hook. The fix samples fluid just below the float instead. The native bite flag still triggers retrieval on the client tick.

The reproduction's native work ID was `JOB:job-e8c195e3-4df1-4de4-875b-448177b8904f`. `artifacts/live-01/incident.jsonl` contains 1,610 exported observations, including frames, and reports an untruncated export. The first run's manual Ctrl-C was initially recorded as an interrupted CLI error; the host now labels a requested interruption explicitly and keeps its persistent wrapper alive for cleanup.

The initial trace also exposed per-command JVM startup overhead. A persistent wrapper stream replaced repeated CLI process launches. Two subsequent preflight attempts (`live-02`, `live-03`) stopped before any player grant because raw loaded-world status exceeded the bounded response size. The stream now selects compact status fields; a regression test covers the large recipe-catalog case.

## Five-minute corrected trial

`artifacts/live-04/` ran for 6,020 advancing world ticks, approximately five minutes. It reused the planted world and the same behavior definition hashes. The host completed 14 fishing activities while all three crop instances waited for maturity.

The final inventory contained **4 cod, 5 salmon, 2 pufferfish, 10 ink sacs, leather boots, and a potion**, alongside the rod. This is 11 fish plus other fishing loot. Actual inventory is the yield evidence; a successful `fish_once` alone only establishes that a bounded cast completed.

The trace audit found:

- No recorded overlapping player grants or overlapping native work admissions.
- Median grant-to-first-native-admission time **179 ms**, maximum **194 ms**, across 15 grants. This measures receipt latency, not the first physical input.
- Median release-to-next-grant interval **1,115 ms**, maximum **1,128 ms**. This includes the deliberate fresh-observation polling boundary; it is not a complete measure of avoidable idle time.
- The final in-progress cast was cancelled by its exact work ID, cancellation was confirmed, the hook was released, and no owner or native work remained unresolved at shutdown.

The water-check reproduction no longer occurred. Repeated casts produced loot in the live world. Crops had not matured during this five-minute window, so this run alone does not establish a live harvest handoff. A subsequent longer run is recorded separately below.

## Ten-minute continuation

`artifacts/live-05/` ran for 12,008 advancing world ticks and completed 25 fishing activities, with no failed activities or recorded ownership conflicts. The ending inventory contained 24 cod, nine salmon, and three pufferfish: **25 additional fish** since the previous run. Crops remained growing; there was no farm wake in this window.

Median grant-to-admission time was 512 ms (maximum 520 ms), and median release-to-next-grant time was 1,216 ms (maximum 1,322 ms). The run ended between casts with no unresolved owner or native work. Timing differs from the previous run and should not be treated as a stable performance guarantee.

The original rod had 16 durability remaining. Between runs, Codex withdrew six sticks and four string from the same chest and crafted two spare rods through the normal executor. The host can equip another carried rod; automatic crafting and restocking remain outside this slice.

## Final runtime corrections

Cancellation now keeps the native fishing executor attached if a cast was sent but no hook acknowledgement has arrived. A missing acknowledgement cannot become permission to grant the player again merely because a timeout expired. If acknowledgement never arrives, the host reports an unconfirmed release and stops. Another regression test covers a new cast after a session pause, ensuring its first bite is reeled immediately.

A strict 25 ms wall deadline caused healthy cold guest initialization to be interrupted while Minecraft and a Gradle build competed for CPU. The sandbox now meters Node-process CPU time during synchronous guest execution, allows 250 ms CPU for source initialization, and retains a one-second wall watchdog. Resumes retain a 25 ms CPU budget. This is bounded prototype accounting, not exact per-guest hardware metering.

## Natural readiness handoff and crop approach failure

`artifacts/live-06/` used a restarted client with the fishing cancellation corrections. A potato matured naturally while fishing was running. Its behavior woke at 08:08:07.940 UTC and won the next player grant at 08:08:10.104 UTC, ahead of the ready fishing instance: **2,164 ms from observed readiness to grant** in this case.

The native crop pass then failed with `crop_approach_failed` before harvesting. The host released that activity, the farm entered its retry delay, and fishing resumed. Codex stopped the run for diagnosis after 6,227 advancing world ticks (about 5.2 minutes). The audit records 11 completed fishing activities, one failed potato activity, no overlapping ownership, and confirmed cancellation of the final cast. Inventory increased by ten fish and one lily pad.

The paused incident export contains 8,966 observations with frames and no truncation or budget drops. The recorded approach targeted potato (-306, 64, -385) from standing cell (-303, 64, -382). The cell center was within interaction range, but navigation arrived near its edge at (-302.216, 64, -381.292), outside reach. Work-position selection now requires the whole standing cell to fit within reach, with a regression based on these coordinates. This corrects a native executor assumption exposed by the scheduler trial.

## Successful replay after the approach correction

`artifacts/live-07/` ran for 2,418 advancing world ticks on another restarted client. The same behavior sources were reused. Two potatoes were already mature at startup, so the potato instance received the first grant. Native work `JOB:job-0f43dee1-2f97-41a0-8efe-93cfa1d86140` completed with **two harvested and nine planted**. Direct block inspection confirmed young crops at the harvested positions and 15 growing potato plants, up from eight total plants before the pass.

The farm then waited for growth while the host navigated back to the beach and completed four fishing activities. Inventory gained three cod and one pufferfish. Final inventory across the trials: **34 cod, 11 salmon, four pufferfish, and one tropical fish**, plus the other recorded loot: 50 fish in total.

The audit records no failed activities or overlapping ownership in this replay. Median grant-to-admission time was 207 ms (maximum 626 ms); median release-to-next-grant time was 1,097 ms (maximum 1,232 ms). The duration limit cancelled the final in-progress cast, confirmed hook release, and left no unresolved owner or native work. `replay.jsonl` preserves 3,349 native observations with frames and a completed, untruncated export. The test client was stopped after evidence collection.

This replay verifies the corrected harvest/replant/return path. Its potatoes were ready at startup; the natural wake and its measured delay belong to `live-06`, whose crop approach failed. The separate simulation exercises repeated readiness cycles, and no full live repetition or throughput comparison is claimed.

## Limits and next design questions

The protocol now has concrete seams for code-defined behaviors, suspended waits, scoped effects, one player owner, and confirmed release. The persistent wrapper addresses an observed control-transport bottleneck. This does not establish optimal scheduling, exact physical utilization, inventory sustainability, or recovery after a hard host crash.

Before extending the reference scenario, settle resource reserves and tool replenishment, worker request/result contracts, and native lease expiry after host loss. Sheep, birch, compost, LLM workers, and durable running instances are not implemented in this slice. The current cast is allowed to finish before ready land work takes over; finer interruption policy remains a measured design question.

## Automated checks

Seven host tests cover guest computation/allocation/output bounds, capability isolation, repeated farm/fishing handoffs, stale observations, and failure to confirm native release. Sixty-six distinct focused native tests passed for fishing, dispatch, crop passes and geometry, active jobs, and tool schemas, including both cancellation/session-pause regressions and the recorded crop approach case. Seventy-seven distinct wrapper tests passed across the focused CLI and stream runs, including compact loaded-world status. `git diff --check` passes.

Raw trial directories are local ignored artifacts. `node src/audit.mjs artifacts/<run>` writes a reproducible protocol audit beside each trace. These trials do not complete the full Airicraft OS or its reference evaluation.
