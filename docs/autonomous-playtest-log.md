# Autonomous capability playtest

Started 2026-09-11 (Asia/Taipei). User-authorized goal: continue the playtest/fix loop autonomously for general survival capabilities; treat inefficiency and missing interfaces as valid findings, and revert changes that fail their intended purpose. Related historical evidence: [survival playthrough](codex-driver-survival-playthrough.md).

## Working agreement

- Drive the active client through exposed agent tools. Pause suspected failures and export the flight recorder before changes. Hot-load fixes when supported; resume playtesting automatically.
- Keep changes focused on observed requirements. Separate live evidence, automated checks and unresolved hypotheses. Commit logical changes; preserve unrelated work.
- Use structured observations first and screenshots for visual ambiguity. Track repeated queries and avoid building an observation or memory subsystem from speculation.
- Keep decisions and outcomes here. Raw local evidence lives under `run/playtest/2026-09-11/` (ignored); never commit bridge/dashboard credentials or full unsanitized client logs.
- World memory is game data. Existing named places are the initial capability; expand it only when repeated gameplay exposes a concrete missing fact, ownership rule or retrieval need.

## Acceptance checkpoints

1. Resource supply: repeat useful wood, stone, fuel and iron acquisition from multiple positions; craft and replenish the required tools.
2. Food: obtain an immediate food source and establish a renewable crop source; harvest, replant and produce edible output without manually specifying every routine action.
3. Shelter: choose a usable site, build an enclosed roofed shelter with an entrance, lighting and workstations; return to it using world-persistent memory.
4. Efficiency: use compact structured queries to identify terrain, resource and building/farming opportunities; record and address repeated observation or action overhead that materially slows the loop.
5. Survival: once the peaceful checkpoints are demonstrated, switch to easy and test at least two full day/night cycles while maintaining food, tools and a return route. Escalate difficulty only after the preceding level is stable.

These checkpoints guide testing; they are not claims of completion.

## Current checkpoint

- Code at start: `e166303a`, live via HotSwap. World: `another world`, peaceful survival, driver mode active.
- Player `(209,92,447)`, health 20. Inventory: iron/stone/wooden pickaxes, nine coal, three raw iron, eight torches, four dark-oak logs, 57 cobblestone, 16 dirt, one plank.
- World place `iron workshop`: `(207,89,460)`, furnace `(206,90,459)`. Exact return failed twice; downward-digging override did not resolve it and was reverted. Other approaches are untested.
- Pause at start: session `ffff6172-e072-4759-8ae1-bcb6058d881c`, epoch 1, server tick 25509. Event cursor 154. These handles must be re-read after resuming/reloading.
- Known recorder size defect still shortens retained history under task load. Preserve immediate exports and bounded event logs until repaired.

## Decisions and experiments

### D001 — Establish the surface loop before extending architecture

Use existing structured feature/block queries to locate a suitable surface camp with soil and water, then exercise food and shelter. The existing mining proof already covers buried coal and iron. Do not require returning to the old underground furnace before making progress: its location is recorded, and a new furnace is cheap from the carried cobblestone. Revisit route failure if it blocks useful travel or can be minimized during the run.

Next: inspect available feature-query contracts, find surface opportunities, and select a camp using explicit coordinates and named-place memory.

### D002 — First food loop and attack-hand ownership

- The forest feature's explicit stand coordinate `(203,98,446)` was reachable. A high-level inventory goal crafted a stone axe, including ingredients and workstation use, in one request (`action-graph-2dd5d0f0-0747-4450-8fd9-618a77f7d9da`). It used two logs; optimization of recipe yield/replanning is not yet justified by this single run.
- Equipped the axe, then killed pig `a1643344` through `attack_entity`. The recorder shows `minecraft:cobblestone` held during repeated `attack_cooldown` snapshots and the landed hit at tick 13010: chase navigation selected a building block and the attack executor never restored the chosen hand. This is a confirmed execution inefficiency, not a lethal mob-reflex result.
- Narrow fix: capture the selected hotbar slot when an attack begins and restore it before cooldown checks and hits. Existing focused entity-interaction tests pass; live second-hunt validation pending. HotSwap applied without restarting the client.
- Pork pickup required a separate explicit navigation to the observed drop at `(218,104,453)`; inventory confirmed three porkchops. This matches the attack tool's kill contract, but repeated hunt/pickup overhead may justify a food-acquisition primitive after more evidence.
- Started a high-level cooked-porkchop inventory goal. Raw evidence: `run/playtest/2026-09-11/{axe-graph,hunt-pig,pork-pickup}-status.txt`, `hunt-weapon.jsonl`, `events-first-food.txt`.

D002 outcome: the cooked-porkchop goal succeeded and inventory confirmed one cooked porkchop (two raw retained). The second hunt (`b9933ea1`) succeeded after HotSwap; recorder snapshots at ticks 13756/13761 show the stone axe during attack cooldown, and the target died at 13788 with the axe selected. This validates hand restoration live, not a controlled speed benchmark across identical terrain. Evidence: `hunt-hand-fixed.jsonl`. No production logging or new test-only abstractions were added; existing entity-interaction regressions passed.

### D003 — Surface-filtered feature queries

The current water feature search has no surface constraint. Its nearest results from the initial position offered water around Y=42–62 while our useful terrain was near Y=98–110, requiring extra inspection to distinguish caves from river/lake shores. Add optional `surfaceOnly` to the existing query, filtering target and standing positions while preserving the connected-water evidence. Use the same ground classification as acquisition (ignore logs/leaves as roofs). This is a focused observation enhancement driven by camp/farm selection, not a general map or memory rewrite.

D003 outcome: two regressions first failed (underground pool taking the result limit; underground standing position offered for surface water), then passed. The live `surfaceOnly=true` query is available through the normal provider after HotSwap. In the same-position comparison, an unfiltered result offered target `(266,62,457)` / stand `(266,62,456)` on a connected water component; the surface query selected `(272,62,461)` / `(272,63,462)` instead and reported two qualifying surface sources. The first larger lake retained its shore target, while its returned center moved from Y=58 to Y=62. This is filtered coordinate evidence, not proof of route reachability. `water-any.txt` and `water-surface.txt` preserve the comparison. Full build passed. Surface classification was extracted unchanged from acquisition and shared with feature search.

### D004 — Exact breaking must remove plants

The bucket inventory goal succeeded (`action-graph-150bd65e-9d14-4faa-8707-2764d7ddb178`), including smelting three carried raw iron. Inventory confirmed one bucket. Evidence: `bucket-graph-2-status.txt`.

Attempting to get wheat seeds reproduced false success: `break_blocks` on observed short grass `(215,107,459)` returned `brokenTargets=0 skippedTargets=1`; the following world read still found short grass. Paused and exported `grass-skipped.jsonl` before rebuilding. The completion predicate classified every replaceable block, and every waterlogged block, as already removed.

Narrow fix: only air and standalone fluid blocks count as cleared. After HotSwap and approaching the same target, the repeated request returned `brokenTargets=1 skippedTargets=0`, and the next nearby scan no longer found grass there. No seeds dropped from this single random trial. Focused block-break tests passed; live proof is in `grass-break-fixed-result.txt`. Continue testing seed acquisition without assuming one broken grass guarantees a drop.

### D005 — Reuse the generic inventory planner for seeds and tools

The high-level goal for four wheat seeds resolved a short-grass mining route with probabilistic yield (`estimatedBreakCount=32`) and completed with four seeds. No seed-specific acquisition tool was necessary. The route also picked up the earlier pork drop in transit: inventory now holds five raw porkchops. Surface shore navigation to `(260,63,478)` completed, with the player settling in adjacent shallow water `(260,62,479)`. A bucket interaction filled the bucket, and a high-level stone-hoe goal completed.

Planted four wheat crops using two batch `use_block` calls: soil `(259,62,479..481)` plus `(260,62,478)`, crops one block above. Structured reads confirmed wheat age 0 and hydration beginning (moisture 7 on the first tile). Evidence: `seed-goal-2-status.txt`, `fill-bucket-result.txt`, `hoe-goal-status.txt`, `till-farm-result.txt`, `plant-farm-result.txt`, `shelter-ground.txt`.

Choose a small excavated shelter in the hillside immediately west of the farm. Initial room bounds `(255..257,63..64,479..481)`, entrance `(258,63,480)`, floor Y=62 and roof Y=65. Preserve the farm and add roof where the hillside is open. Existing exact-break batches suffice for the first room; no speculative building DSL. Seven entry/first-row blocks were successfully excavated through normal tools.

Recorder diagnosis: a captured runtime snapshot repeated about 1 MB of mission evidence twice, mostly the entire known recipe catalog (`knownCrafts` ~958 KB, `knownSmelts` ~40 KB). Static recipe knowledge must be retained once with explicit references, while current decision/world evidence remains independently queryable. This is measured from `grass-skipped.jsonl`, not just an assumed byte-budget problem. Implementation pending.

### D006 — Area inspection must include air cells

Roof placement was blocked as `target_not_inspected` for `(258,65,480)`. The automatic inspection and an explicit one-cell `inspect_area` both returned zero records. Paused/exported `roof-uninspected.jsonl`. Source filtered out air unless a nonreplaceable block sat directly below, making ceilings and the upper half of doors impossible to inspect through this path.

Removed that hidden filter: `inspect_area` reports the requested cells, including air, subject to its existing result cap. `find_blocks` remains the selective search. Only returned cells grant mutation freshness; limits remain unchanged. Focused world-query tests passed. After HotSwap, the same two-cell roof query returned both air cells and both cobblestone roof placements completed (`roof-query-fixed.txt`, `roof-fixed-result.txt`). No client restart.

### D007 — Shelter furnished; relocate crops away from foot traffic

Door `(258,63,480)` is closed and faces east. Interior furnace `(255,63,479)`, crafting table `(255,63,481)`, wall torch `(255,64,480)` and outside farm torch `(259,64,479)` were placed successfully. `shelter-interior.png` visually confirms the lit room and workstations. Saved `shore shelter` and `shore wheat farm` in world memory with exact geometry and crop positions.

A later check found `(259,62,479)` reverted to grass and the crop above missing; three other wheat plants remain. The old recorder window cannot identify the moment. Trampling during entry/navigation is plausible, not proven. The original farm overlaps the door approach, so move/expand cultivation away from this traffic instead of treating every loss as an executor bug. Preserve mature crops until harvest where practical. Evidence: `shelter-farm-check.txt`; paused boundary and export `recorder-before-catalog.jsonl`.

### D008 — Deduplicate recorder context, retain current state at one-second intervals

Initial live compaction reduced a runtime payload from about 2 MB to 151,536 JSON bytes, but 120,427 bytes were repeated action-graph details and 18,205 were dialogue history. The final projection stores recipe catalogs, dialogue history, and each action-graph execution as reusable versioned context; a snapshot keeps exact sequence references plus current evidence/summaries. Unchanged context extends its validity; budget eviction considers the end of validity so recently reused context is not discarded just because it was first inserted long ago. Detailed execution payloads retain the existing bounded trace contract.

Runtime snapshots now sample every 20 completed server ticks; per-client-boundary task/reflex decisions and five-tick LLM polling remain. Duplicate mission/task/reflex copies inside `agent` are removed. CLI interval export includes overlapping context; live and file seek load the referenced versions. Full build and context/version/retention/seek tests passed. HotSwap required no restart. Live snapshots measured 15,627 JSON bytes; the first sample held nine distinct execution contexts rather than copying all nine into every snapshot. This proves payload reduction, not yet a full ten-minute workload retention run. Earlier loss counters remain historical and are not reset to hide drops.

Browser validation loaded both structured and framed exports, decoded a 640x360 incident frame, and exercised saved playback without console errors. Evidence: `recorder-context-first.jsonl`, `seed-farm-conflict.jsonl`, `recorder-build.log`.

### D009 — Repeated pause must preserve both tick gates

The watcher raced an explicit incident pause. A second pause reused the client capture, but the server controller rejected the same session; the runtime's error handler reset the client gate. Confirmed mismatch: client ticks advanced from 40,402 to 41,627 while server tick stayed 38,494 and server phase remained PAUSED. This is a debugger bug, not game progression behavior.

Make server pause idempotent for the already-paused session; another session is still rejected. Controller regression passed. HotSwap and recovery through the existing server pause handle restored both gates. Repeated normal CLI pause then returned the same session `c606c35d-20e4-4a3c-ab20-201601bc7319`, epoch 1, client tick 42,786 and server tick 38,514. A later read still held those ticks. Evidence: `duplicate-pause-state.txt`, `pause-restored.txt`, `pause-repeat-fixed.txt`, `pause-repeat-stable.txt`, `duplicate-pause-tests.log`.

The eight-seed goal selected immature wheat from the planted farm, collected one seed and ultimately failed for lack of reachable sources. It was no longer active when cancellation was requested. Remaining crop `(259,63,481)` reached age 1, disproving a complete growth stall; another crop at `(260,63,478)` remains age 0. Normal saved-world randomTickSpeed is 3 and measured light was 12. Crop/source lifecycle handling is the next confirmed capability issue; do not resume this destructive seed goal unchanged.
