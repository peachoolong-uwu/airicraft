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

### D010 — Generic acquisition preserves immature crops

Use Minecraft's `CropBlock.isMature` for generic resource acquisition. Filter both candidate selection and the final target-presence check, and supply the resolver with a separately cached count of harvestable blocks. Keep ordinary nearby-world evidence unchanged: a growing crop still exists and remains observable. Exact `break_blocks` remains available for deliberate terrain/crop edits. This covers CropBlock crops; other plant families and automatic replanting are not claimed.

After HotSwap, forcing acquisition from the two immature wheat plants failed with `itemCount=1 rejectedTargets=0`, collected count 0. A subsequent world query retained both plants at ages 0 and 1 (`immature-crops-preserved.txt`). Repeating the eight-seed inventory goal selected `minecraft:short_grass:hand` instead of wheat, and began approaching grass beyond the shore. This validates planner ranking and executor preservation; it does not yet prove an eight-seed collection here. Full build passed (`crop-maturity-build.log`).

### D011 — Preserve the intent attached to built places

Confirmed damage beyond crop harvesting: the earlier seed acquisition undermined the shelter entrance. The paused exact read found air at `(258,62..64,480)` where the door support and both door halves had been. Door inventory increased from two to three. The recorder first shows three doors at server tick 37,472, with the player at `(258.4,62,480.7)` and acquisition approaching wheat `(259,63,481)` via excavated work position `(258,62,481)`. Evidence: `seed-farm-conflict.jsonl`, `shelter-door-after-acquisition.txt`.

Decision: extend named world places with an explicit optional preserved region. It must constrain automatic resource target selection and Baritone route excavation; walking through a preserved place and deliberate coordinate-based construction remain possible. Keep this world/dimension scoped and persistent. This is ownership information learned from actual play, not an invented general-purpose memory database. Repair the entrance, then retry a constrained resource request and a return trip against the protected site. A Baritone calculation hook may require one client restart to install; save/export first and prefer HotSwap for subsequent changes.

The improved seed run did select grass, collected one additional seed (two total), but sparse steep terrain consumed time and seven cobblestone. Cancelled it to address site preservation. Current player `(288,91,463)`; six cooked pork, six coal, 28 cobblestone, 36 dirt, two wheat seeds, iron/stone/wooden picks, axe, hoe, six torches and a filled water bucket. Two wheat plants remain near the shore. Current code `578a7d92`; game paused, no seed goal should be resumed. Re-read tick handles before acting.

### D012 — Preserved world regions validated live

`remember_place` now accepts optional inclusive `preserveArea` bounds in the place's dimension; recall/list returns them. Replacing a place without bounds removes preservation. The world file remains compatible with old bookmarks. An immutable client-world policy feeds automatic target selection, harvestable source counts, and Baritone's existing protected-block cost check. A final input guard also stops Baritone breaking a newly preserved block on a previously calculated path. Exact coordinate edits remain available. Recorder snapshots include active preservation bounds and load availability.

Focused persistence, scope, malformed-input and publication tests and the full build passed. One client restart installed the new Baritone Mixin; Minecraft's normal shutdown hook saved the world, and the same inventory and bookmarks loaded. Subsequent policy-loop cleanup used HotSwap. Live return from `(288,91,463)` to `(256,63,480)` completed with the protected areas active. Repaired entrance support, adjacent floor/wall and door through ordinary exact tools. A one-block cobblestone acquisition confined to the protected shell failed with zero collected and left the shell intact. Navigation aimed inside its solid wall failed without excavating it. An explicit `break_blocks` then broke that same wall block and `place_block` restored it, confirming deliberate edits still work.

Evidence: `protected-return-1-*`, `protected-door-repair.txt`, `protected-acquisition-*`, `protected-solid-navigation-*`, `protected-navigation-after.txt`, `protected-explicit-{break,replace}.txt`, `place-preservation-full-build.log`. This preserves terrain from gathering/path work; it does not prevent crop trampling or claim protection from mobs. Farm layout still needs to avoid foot traffic. The pre-restart recorder export is `pre-preservation-restart.jsonl` (marked truncated due to earlier budget losses); the restart begins a new retention measurement.


### D013 — Bound the remaining visual recorder cost

After the preservation restart, server tick 18,765 retained 594 runtime snapshots across the 12,000-tick window, with no structured-record budget drops. Total storage was 50,584,522 bytes. Animated water kept every sampled RGB frame distinct: 497 retained frames reached the separate 32 MiB visual cap, with 438 historical visual drops. Evidence: `recorder-after-ten-minutes.txt`.

Change the default RGB interval from 20 to 40 server ticks (0.5 fps); explicit configuration can still request 1 fps. The snapshot/decision rates remain the same. This follows the measured visual cost and the user's preference for sparse agent-readable history. Verify the fresh client for a complete ten-minute window after installing crop tending. Existing exports keep their loss flags; `pre-tending-restart.jsonl` includes the available frames and is marked truncated.

### D014 — One-pass crop tending instead of coordinate micromanagement

Bread request `action-graph-d6ccb873-6b56-4e33-9e15-545c783ee090` failed `no_route` while four wheat plants were immature. Paused and exported `bread-no-route.jsonl`. The current Java providers do not implement a usable growing-crop route; historical bread-bootstrap docs and leftover runtime YAML are not evidence of an active capability. Two new crops at `(261,63,484)` and `(263,63,484)` are planted away from the entrance, with a torch at `(262,63,484)` and all four crops still present (`crop-growth-during-implementation.txt`).

Implement `tend_crops`: a single bounded pass over an existing flat plot, identifying mature crops, approaching, harvesting, collecting drops, and replanting; plant empty farmland from available seeds and report growing/missing-seed counts. It uses the normal job/executor ownership and cancellation path. The planting item selects the CropBlock type; no hardcoded wheat-only maturity rule. Immature crops and unrelated blocks stay intact. Plot tending is a deliberate crop operation inside preserved farm bounds. It does not till soil or wait for growth. Keep site preparation separate until further play requires a reusable higher-level operation.

State-machine and job-lifecycle tests passed, including delayed planting completion, crop changes during approach, no-seed reporting, stalled paths, and session/reflex pauses. Full build passed. Installing the new task enum/executor requires a restart; later implementation fixes can use HotSwap. Live harvest/replant proof is pending, so do not count this as a completed farming checkpoint yet.

Growth context: the new crop had light 14, and other wheat advanced to age 1/2, so a complete growth stall is disproven. Minecraft's current `CropBlock.getAvailableMoisture` rewards surrounding hydrated farmland and penalizes crowded same-crop layouts. Expand tilled soil around spaced rows while waiting instead of modifying game growth rules.

Initial live tending pass completed with `harvested=0 planted=0 growing=4 missingSeedPlots=12`; exact world reads retained all four plants. This also exposed loss of primitive completion detail in semantic TASK UPDATE messages. Tending now uses the normal bounded block-operation result path, and semantic terminal updates include the matching primitive's detail. Focused runtime/executor/job tests passed; HotSwap applied the feedback fix without restarting. Evidence: `tend-growing-feedback.txt`. The expanded plot has 12 additional tilled cells around the spaced crop row; fetch seeds from the remembered forest and validate automatic planting next. The remembered protected bounds survived the crop-tending restart (`preserved-places-after-restart.txt`).

### D015 — Resource supply and farmland coordinate consistency

The forest seed goal `action-graph-7334b9c1-2866-43b0-ae4d-fa795c87b660` succeeded: zero to eight seeds through 28 short-grass breaks. A surface-constrained wood request then gathered eight pale-oak logs with the stone axe and no rejected targets. Returned from the forest to the farm. A row-only tending pass planted two empty cells and preserved two growing crops: `harvested=0 planted=2 growing=2 missingSeedPlots=0`, seeds eight to six. Evidence: `forest-seeds-3-*`, `forest-wood-eight-*`, `seed-supply-return-1-*`, `tend-row-first-plant.txt`.

A current-position bookmark captured on farmland recorded `(261,62,483)`, although Baritone walks at navigation Y=63 over the 15/16-height soil. Returning from `(264,62,483)` failed. Separately, requesting the correct `(264,63,483)` physically arrived with estimated ticks zero but reported CANCELLED because Airicraft compared raw block Y=62. Both incidents were paused and exported before edits (`farmland-bookmark-failed.jsonl`, `farmland-navigation-cancelled.jsonl`; neither truncated).

Current-position memory and navigation completion now use Baritone's own `playerFeet()` coordinate convention. Explicit user coordinates remain exact. Focused facade, task and memory tests passed; HotSwap applied four classes. Live current capture returned `(264,63,483)`, then moving to `(261,63,483)` and returning to the new bookmark both completed. Evidence: `farmland-fixed-{away,return}-*`. Updated the local watcher to pause unexpected CANCELLED outcomes as well as failures.

The row crop at `(263,63,484)` reached age 5; live mature harvest/replant is still pending. A fresh query found five crops total, not six: the old entrance-side `(259,63,481)` plant is absent. Keep productive rows away from routine access and do not count remembered crop totals as current evidence.

### D016 — Aim inside short crop outlines

The generic crop tending code aimed at the full block center. A deterministic voxel raycast reproduced a miss when that endpoint lies on the top boundary of the half-height mature beetroot outline; aiming at the outline's center hits. Use the actual crop outline center for both visibility and camera aim. The geometry regression and focused navigation/memory/task tests passed; HotSwap applied the environment change. This is geometry proof, not a live beetroot harvest claim. Live wheat harvest remains the next farming checkpoint.

### D017 — Full recorder window and shelter/supply recheck

At server tick 12,551, the new half-frame-per-second default retained the full 12,000-tick window: 597 runtime snapshots, 300 visual frames, 49,453,744 bytes total and 20,784,272 visual bytes. No dropped record types were reported; expired entries belong to normal rolling retention. Evidence: `recorder-half-fps-window.txt`. This is a complete live window under the current workload, not a guarantee for every scene or event rate.

Returned to the shelter and inspected both halves of its shell. All 50 required floor, roof and wall/door cells were observed with no air gaps; furnace, table and wall torch remain present. The high-level 21-torch goal succeeded, stock five to 21, consuming four coal and four sticks. Picked up the displaced entrance crop's seed on the return trip (seven total). Evidence: `shelter-{current,west}-shell.txt`, `shelter-return-after-farm-*`, `replenish-torches-*`. Full build passed after the navigation and crop-aim fixes (`post-navigation-build.log`).

### D018 — Detours are navigation progress

Local eight-coal acquisition selected `(249,63,489)` from the shelter. The first approach was rejected after 81 ticks, although recorded positions moved from `(256,63,480)` through the eastern entrance to `(260,63,483)` and `(260,62,485)`. The progress metric only accepted a new shortest straight-line distance to the target, so leaving the room initially in the opposite direction consumed the entire no-progress allowance. Paused both gates and exported `shore-coal-stall.jsonl` before editing (674 observations, not truncated). The next side had already excavated 22 stone blocks; that productive continuation was not evidence of a new stall.

A regression using the recorded detour failed first, then passed when the no-progress check tracked movement between positions. Stationary approaches still time out, and the 2,400-active-tick attempt budget bounds looping routes. HotSwap applied the change and resumed the same coal job. Live completion is pending.

The same coal job completed after HotSwap, inventory two to ten coal, with no replacement acquisition request (`shore-coal-eight-2-*`). This proves continuation and resource completion; the original detour had already been abandoned, so its prevention is currently covered by the recorded-position regression. Several short drop approaches emitted CANCELED and were reselected while pickup still progressed; log this as a bypassable inefficiency, not a new progression blocker.

### D019 — Renewable crop harvest and parallel smelting demonstrated

The eight-iron acquisition completed from ore at Y=68 down to Y=54, with no detour stall and eight raw iron collected. Returned to the remembered shelter. Enabled existing automatic spawn-proof lighting (`maxLightLevel=1`, spacing six); four torches were consumed during excavation. Started smelting all eight raw iron using one coal at the shelter furnace, then tended the farm while smelting continued.

The first mature row pass completed at server tick 18,637: `harvested=1 planted=1 growing=3 missingSeedPlots=0`. Inventory confirmed one wheat and ten seeds (seven before the pass); exact read confirmed the mature `(263,63,484)` plant was replanted at age 0 and the other three row crops remained. This proves real harvest, pickup and replant through one tool call. A bread output still requires three wheat, so the full edible-crop checkpoint remains open. Evidence: `shore-iron-eight-{1,2}-*`, `iron-return-home-*`, `tend-first-mature.jsonl` (93 records, not truncated), `tend-first-mature-*`.

### D020 — Increase farm throughput as seed supply improves

Collected the eight smelted ingots; the chestplate inventory goal succeeded and `equip_item` accepted it for the chest slot. The crafting run also converted one log to four planks that remained afterward; record this as a small possible prerequisite inefficiency, not a proven crafting blocker. Its exact route history has not been diagnosed.

With ten seeds available, planted eight additional cells in the already-prepared soil via one bounded tending pass, preserving two seeds. The main plot now has twelve crops. This revises the earlier sparse-row choice: adjacent wheat slows individual growth, but more planted cells should improve total harvest from existing soil without another excavation project. Measure output rather than assuming the improvement. Updated the world-persistent farm note with the new layout and successful tending workflow. Evidence: `expanded-farm-crops.txt`, `expanded-farm-inventory.txt`, `chestplate-inventory.txt`.
