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

## Verified checkpoint after the autonomous run

- All five acceptance checkpoints above were exercised live. Easy ran from world time 118,078 to 167,363: 49,285 simulation ticks, about 41 minutes and more than two full day/night cycles, with no time commands or sleep skips. No death was observed; the latest saved statistics also have no death entry and equal play-time/time-since-death values.
- Player is paused inside `shore shelter` at `(257,63,480)`, health 20, hunger 20, full iron armor. Inventory retains three cooked porkchops, two bread, an apple, two wheat, fifteen wheat seeds, two iron picks, an iron sword, shears, three coal and five torches. One storage slot remains free.
- Shelter door is verified closed; bed foot `(256,63,480)` faces west toward head `(255,63,480)`, with spawn point set. The east doorway column stays clear. Farm has fourteen wheat and fourteen farmland cells. World bookmarks preserve shelter/farm bounds and updated station/resource notes.
- Live-proven changes include buried resource acquisition and tool-loss replanning, crop collection/replanting, neutral-mob avoidance, usable navigation coordinates, preserved construction areas, filtered entity queries, combat weapon retrieval, drowned weapon classification, and nearby-workbench reuse. See the decision entries for the evidence boundary of each change.
- Full build passed after the final source changes (`post-survival-fixes-build.log`). All task changes are committed; the pre-existing dirty `vendor/action-plan-advisor` submodule was preserved.
- Remaining limits: some recorder exports flag dropped observations; depleted acquisition scopes can repeat eight futile replans; storage/transfer will be the next capacity requirement. Earlier farmland damage during elevated leaf harvesting remains causally unconfirmed. Normal/Hard difficulty and a clean long run without interventions have not been validated.
- Final live pause: debug session `e5ac8a7b-f80e-439e-9315-6f822e55d914`, epoch 1, client tick 28,356. Re-read the handle before resuming. Export `completed-checkpoint.jsonl` has 1,491 observations with frames and a truncated flag; retain the earlier focused, non-truncated incident exports as well.

## Initial checkpoint

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

### D021 — Full leggings supply chain and two open return-path findings

The iron-leggings goal `action-graph-e94ba171-e887-4788-a1f3-f6aabf34ac89` succeeded end to end: craft a portable furnace, acquire iron, start and await smelting, collect output, and craft leggings. Equipped the leggings. Extra ore exposed by the route yielded surplus; two ingots and one raw iron remain. It used a new underground furnace at `(255,52,474)` and table at `(255,52,476)` rather than reusing the surface stations.

Recheck found the newly planted `(260,63,483)` crop missing and its farmland converted to dirt/grass; current light is 12. The return from the expanded farm crossed that area; the later leggings mining route went north, so do not attribute the loss to that mining route. Paused and exported `farm-traffic-loss.jsonl` (1,085 records, not truncated). Trampling is suspected but no per-tick landing reproduction has yet been captured. Repair and a short player/block trace remain pending.

Returning from the underground crafting pocket to valid open `(259,63,481)` repeatedly failed `CALC_FAILED`. Selecting the iron pickaxe and moving one block sideways did not resolve it. An exact one-block upward navigation did succeed, proving local excavation/placement works. The next whole return advanced partially to `(258,52,480)` then failed again. Increasing path-search time from 2 to 10 seconds did not resolve it, so restored the original 500 ms primary / 2,000 ms failure budgets. Do not keep that ineffective override. Evidence: `leggings-return-failed.jsonl`, `leggings-return-pick-selected-*`, `pocket-{side-step,up-one}-*`, `return-after-pillar.jsonl`, `return-extended-search-*`. Next test uses the actual recorded mine entrance near `(263,65,471)` as an intermediate waypoint; home remains the final intended destination.

### D022 — Access construction and placement input restoration

The recorded north entrance was reached, but its final leg to home still failed. This disproved a purely underground-climbing diagnosis. Exact shore inspection found floor gaps at `(259,62,478)`, `(259,62,480)` and `(259,62,482)`, all inside the preserved farm region. The narrow shore access alternated solid islands and gaps; automatic navigation could not bridge protected cells. Deliberately placed a three-block cobblestone walkway through exact construction tools.

The third placement stalled at `waiting_for_sneak_for_placement`, player standing with the actual sneak input cleared. Paused and exported `walkway-sneak-wait.jsonl` (110 records, not truncated). `PlacementSneakController` treated its ownership flag as evidence that the key was still pressed, so it waited indefinitely after input was cleared. A regression failed first, then passed after restoring owned input when the actual key is up, including a stale player-sneaking flag. Focused placement tests passed; HotSwap applied two classes. Resuming the same job completed the third block within 33 client ticks, with no replacement request. Exact inspection confirms a continuous floor along x259, y62, z478..483. Evidence: `walkway-sneak-fixed-*`, `doorstep-after-build.txt`.

The repeated long return failures are not yet classified as an A* defect: access construction was incomplete. Retry home after the walkway, then repeat from the mine entrance to establish whether that construction resolves them. The farm trampling trace remains pending.

### D023 — Passive-mob avoidance caused the repeated return failures

The continuous walkway fixed short access, but the return from the north entrance still failed. A temporary, read-only local path-cost probe showed finite connected movement costs into the shelter, disproving a fully blocked local route. Inspecting Baritone's actual avoidance implementation then found its filter accepts every `MobEntity`. The live probe found 87 avoidance centers including fish, bats, farm animals, a wandering trader and its llamas. The three trader-party entities multiplied the home destination's cost by 64. More heavily overlapping fish also explain why digging away from that area incurred extreme estimates; this latter connection is an inference, not a separate controlled test.

Controlled live test: from the unchanged `(263,65,471)` position with the same home goal `(256,63,480)`, the default coefficient 4 failed repeatedly. Setting only `mobAvoidanceCoefficient=1` made the identical return complete in 105 client ticks, without terrain preparation between those two attempts. Evidence: `home-avoidance-factors.txt`, `home-path-costs.txt`, `home-without-passive-avoidance-*`. This supersedes the earlier incomplete explanation based on pocket geometry and shore floor gaps.

Fix the integration by filtering Baritone's entity stream to Minecraft's `Monster` marker, which covers slimes and ghasts as well as HostileEntity subclasses. Keep its subsequent enderman, zombified-piglin and daylight-spider exceptions and retain spawner avoidance. Remove the temporary path-cost logging. A new Mixin requires one client restart; afterward restore the normal coefficient 4 and repeat the route. Live validation of the durable fix is pending.

D023 live follow-up: after the Mixin restart, the original mob avoidance coefficient remained 4 (radius 16). Shelter to north entrance and the previously failing north entrance to shelter both completed. Evidence: `monster-fix-{north,home}-*`. Corrected the world-persistent mine note to remove the obsolete mandatory-waypoint workaround, and moved the farm bookmark onto the solid access walkway rather than a planted cell. Eleven crops still present and growing. The durable route fix is now live-verified; hostile avoidance will be checked after the peaceful food checkpoint.

### D024 — Crop tending must collect actual scattered drops

The isolated mature wheat at `(260,63,478)` reported `harvested=1 planted=1`, but inventory remained one wheat and spent one seed. Paused and exported `old-crop-drops.jsonl` with frames (103 observations, not truncated). Nearby-item inspection and the rendered capture confirmed wheat and seeds still lying 1.6–2 blocks away. The collection phase accepted squared integer-coordinate distance <=2.25 and waited 20 ticks; proximity to the old crop was not proof of pickup.

Changed collection to observe matching crop-drop entities, navigate to their positions, retarget remaining piles, and replant only after those drops disappear. The existing 240-tick phase limit bounds unreachable/full-inventory cases. A regression failed before the change; scattered-pile, retargeting and timeout tests now pass with the crop suite. Applied five classes via HotSwap. Manually recovered this already-completed task's abandoned harvest by walking onto the crop: wheat one to two, seeds one to four. Next mature harvest remains the live proof for the new automatic collection behavior.

Crafted shears and a composter through ordinary recipes/goals. Placed composter `(260,63,480)` on the spare shore table, clear of the x259 access path. Preparing a leaf-compost loop to improve farm output without altering random-tick rules. The composter recipe goal consumed four logs and left ten planks/five slabs; extra plank conversion is a bypassable planning inefficiency to inspect later.

### D025 — Surface acquisition on fractional ground and giant mushrooms

The 32-leaf request failed immediately on farmland `(260,62,478)` with `acquisition_scope_left`. Acquisition still used raw player block Y, unlike the already-fixed navigation/bookmark boundaries. Changed its player position to Baritone `playerFeet()`. Focused acquisition/excavation tests passed; HotSwap applied one class. The identical request then broke 13 leaves and collected 12, proving the initial farmland rejection fixed.

It subsequently stopped at `(263,64,487)`. Paused/exported `leaves-scope-second.jsonl` (165 observations with frames, not truncated). Exact column inspection found grass at Y63, leaves at Y67/68, and a giant red mushroom cap at Y69..71. SurfaceTerrain ignores logs/leaves but classified the mushroom canopy as terrain, so ordinary ground beneath it became underground. This is a separate false surface-boundary rejection, not evidence that the feet fix failed. Extend the existing vegetation exclusion to giant mushroom blocks and retry.

D025 follow-up: ignoring `MushroomBlock` canopy/stems in the existing surface-ground scan was compiled and HotSwapped. The remaining 20-leaf request completed from the stopped position, in the same anchored surface scope, with no rejected targets. Evidence: `leaves-mushroom-fixed-1-*`. Nearby leaf gathering also converted `(260,62,484)` and `(262,62,485)` farmland to dirt, destroying their crops. Paused/exported `leaves-farm-loss.jsonl` (354 observations with frames, not truncated). Diagnose player landings before resuming farm-adjacent resource work.

### D026 — Narrow the farm-loss claim and continue useful work

A traced shelter-to-ground-level tree edge round trip recorded no farmland changes. The return's only one-block descent landed on ordinary grass before crossing the plot. Evidence: `farm-roundtrip-trace.jsonl`, `farm-descent-trace.jsonl`. These disprove the claim that ordinary walking on the plot inherently destroys crops. The earlier leaf job's elevated work/drop approaches remain suspect, but the canopy was removed during that job and the same elevated path can no longer be replayed without reconstructing terrain. Two additional trace attempts performed no harvesting: one surface-only request started inside the shelter and correctly failed; after moving outside, the cleared local scope contained no reachable matching leaves. Do not label these as new bugs.

Keep the precise cause of the two lost cells unconfirmed. The important observed fact is that farmland became dirt during nearby harvesting. Repair the soil, keep routine harvesting away from the planted plot, and capture another real occurrence if it arises. No speculative navigation patch. Collected leaf stock is now 33; use it for composting. Full build passed after the crop pickup and surface fixes (`post-crop-surface-build.log`).

### D027 — Wait at an already-reached crop pickup goal

The first pass with drop-aware collection collected/replanted one crop, then failed while collecting the next. Paused/exported `crop-pickup-path-failed.jsonl` (143 observations with frames, not truncated). A read-only JDI inspection of the retained executor showed its failed work goal `(261,63,483)` was the player's current navigation cell. The implementation unnecessarily started a zero-length Baritone path while waiting for newly spawned item pickup. The trace also confirms no farmland conversions during this tending pass.

Skip path submission when Baritone already reports the pickup goal reached; keep observing the drops until collected or the phase budget expires. Added work position to decision snapshots. The regression failed before the change and passed with the crop/acquisition suites. HotSwapped one class. The next full tending pass completed; inventory reached five wheat and six seeds, including remaining nearby drops and the newly harvested crop. Evidence: `crop-pickup-current-goal-fixed-*`, `crop-pickup-fixed-pass.jsonl`, `farm-after-pickup-fix.txt`. Automatic harvest/collect/replant is now live-proven with the new logic. Proceed to bread and Easy-mode preparation.

### D028 — Peaceful food checkpoint and difficulty control

The first bread inventory goal succeeded using wheat from the maintained farm (`first-farm-bread-*`); fourteen crop cells were verified replanted, with two wheat, six seeds and one bread retained. The iron-sword supply goal also succeeded, supplementing the existing iron chestplate/leggings and six cooked porkchops. Peaceful resource gathering, hunting/cooking, crop harvest/replant, composting and basic enclosed shelter have all been exercised live.

The host desktop is locked, so the normal settings UI is unavailable. Added `world difficulty [--set peaceful|easy|normal|hard]` to the authenticated wrapper/bridge for the local integrated world. It runs on the server thread, rejects locked/hardcore changes and paused-server requests, and reports actual difficulty and time of day. Wrapper routing/validation/error tests and compilation passed. HotSwap loaded the bridge/service; a live read confirmed peaceful, unlocked, non-hardcore. No difficulty change has been made yet.

The sword-goal return then failed because a newly auto-placed crafting table at `(259,63,480)` obstructed the preserved doorway approach. Paused/exported `sword-return-failed.jsonl` (204 observations with frames, not truncated). This is a separate automatic workstation placement violation of preserved-place intent; fix it and restore entry before switching to Easy.

### D029 — Automatic workstations must respect preserved spaces

Portable crafting-table placement ranked the protected air cell `(259,63,480)` as feasible, then used an explicit-placement child task, bypassing the Baritone-only preservation guard. Added preserved-state filtering to its candidate policy and a live recheck before each child attempt. Applied the same preserved-cell check at both furnace discovery and execution placement boundaries; existing protected workstations can still be reused, and explicit coordinate-based edits remain allowed.

The recorded doorway regression and focused crafting/smelting tests passed. HotSwap applied the affected classes. Deliberately removed the misplaced table and the identical home return completed (`clear-doorway-table-*`, `doorway-cleared-home-*`). This proves the obstruction and restoration live; automatic selection of a new unprotected workstation site is still pending a suitable crafting run. The difficulty control also correctly rejected a request while ticks were paused (`difficulty-paused-rejected.txt`).

### D030 — Begin Easy survival

Cleared and closed the shelter door, equipped the iron sword, and enabled normal spawn-proof lighting. `world difficulty --set easy` returned changed=true at world time 118,078; a separate read returned easy with changed=false. No commands changed time, weather, health, inventory or growth rules. Start state: healthy, iron chestplate/leggings, six cooked porkchops, one bread, one apple, fourteen torches, a maintained wheat plot and the remembered enclosed shelter. Capture `easy-start-ticks.txt` anchors the server clock. Aim for at least two full day/night cycles with actual supply work, not idle shelter time alone. Pause/export unexpected behavior and resume after fixes.

### D031 — Tool breakage must invalidate the active acquisition route

The Easy-mode replacement-pick goal acquired one raw iron, then repeatedly stalled after its old iron pickaxe broke. At pause, the player held a wooden pickaxe against iron ore; a stone pickaxe remained in inventory, while the route required `minecraft:iron_pickaxe`. Export: `easy-pick-acquisition-stall.jsonl` (389 observations with frames, not truncated). Acquisition only checked tool suitability at dispatch/break time and treated later failure as another target rejection.

Added a per-tick presence check for the route's required tool, after checking completed inventory quantity. Tool loss fails with `MISSING_ITEM` and releases movement instead of cycling excavation sides. The regression failed before the change; acquisition/crop tests passed afterward. HotSwapped seven classes and resumed the same action goal. It replanned automatically to the existing stone pickaxe, gathered the remaining ore, and entered smelting (`tool-loss-replan-*`). The goal now needs two fresh pickaxes because the old one broke; full crafting completion is pending.

### D032 — Compact inventory readiness and completed Easy supply recovery

The same replacement-pick action goal completed with two fresh iron pickaxes after tool-loss replanning. Its portable table was placed at `(254,63,486)`, outside both preserved areas, and the final home return completed. This supplies live automatic-workstation placement evidence for D029. Export `easy-pick-completed.jsonl` contains 583 observations, not truncated.

Extended the existing `inspect_inventory` response with free storage slots, equipped armor/offhand, per-stack durability (inventory slot, item ID, remaining/max uses), and health/hunger/saturation. This requirement arose directly from needing a large debug trace just to identify a worn pickaxe before a supply run. Counts and prior output fields remain available; no new query tool or memory schema. Compilation and HotSwap passed. Live response confirmed both iron picks at 250/250, stone pick 71/131, the two worn armor slots, and seven free storage slots (`inventory-readiness-{live,final}.txt`). Starting an iron-boots supply goal while continuing the Easy survival clock.

### D033 — Distinguish slow resource recovery from a new blocker

The boots goal collected three raw iron while rejecting several deep approaches. Paused/exported `easy-boots-ore-stall.jsonl` (605 observations with frames, not truncated); healthy pick durability ruled out D031. Fresh inspection found only one remaining higher ore in the old scope and a dropped raw iron in the cave. The planner had already replanned to that drop. Resumed the bounded attempt: it descended from surface to `(259,54,473)`, recovered the required supply and entered smelting. Keep the expensive search/recovery as an efficiency observation; no new pathfinding patch was justified. Full build passed (`easy-supply-build.log`). Next survival capability is a bed/spawn point, using sheep/wool if available.

D033 evidence correction: the same cave raw-iron entity was still observed after smelting began. Required inventory was obtained, but its exact source may have been fallback ore rather than that drop; this is not proof of underwater item pickup. The boots goal completed and they were equipped. Started the remembered forest trip for sheep/wool with full health.

### D034 — Filtered entity search and first Easy combat

The forest stop had no sheep in the fixed 32-block observation. Extended `inspect_nearby_entities` with optional exact `entityTypeIds`, radius 1–128 (default 32), and maxResults 1–64 (default 32). It searches loaded client entities only and filters before nearest-first limiting; distant results require navigation into the existing interaction radius. This follows an actual wool/bed requirement and avoids repeated screenshots. Query-bound tests, filter-before-limit coverage, and existing orchestrator/inventory tests passed. The static tool catalog requires a normal save/restart to expose the new schema; live validation follows.

During implementation the survival reflex fought in the forest. Paused at world time 139,123, server tick 47,429. The recent recorder shows an aggro skeleton at 12.25 blocks triggering DEFEND, subsequent close-quarter attacks, arrow damage to 18.38 health, an attacking spider joining, string pickup, and threats_clear resolution. Health recovered to 20; saturation reached zero; armor and axe wear increased. No death or stuck reflex observed. `easy-first-night-recent.jsonl` contains 448 observations with frames; `easy-first-night.jsonl` contains 1,796. Both flag truncated because this session's droppedByType counters are nonempty; do not present them as lossless. These establish combat activity, not yet two full Easy cycles.

D034 live validation: after the normal restart, Easy persisted at world time 139,313. A radius-128 sheep-only query returned three sheep at 28.8, 75.7 and 125.6 blocks with exact selectors and positions (`sheep-query-live.txt`). Shearing the nearest completed, and navigation to its observed drop collected one white wool. Travel hunger reached 17; `eat_food` consumed the farm bread and subsequent inspection confirmed hunger 20 and saturation 6 (`second-sheep-travel-inventory.txt`). This is live crop-to-food-to-hunger recovery, not just recipe completion.

### D035 — Drowned ranged capability depends on its weapon

The wool return paused for DEFEND and diverted toward drowned detected at 33.93 blocks, followed by several others at 30–34 blocks. This was not an empty threat set: an initial filtered query omitted drowned. Paused at world time 144,051 and exported `home-return-drowned.jsonl` (3,205 observations with frames, not truncated). The last decision targeted a drowned below the water surface while the original home navigation remained held. Debug entity inspection found both remaining drowned with empty equipment (`drowned-equipment.txt`).

Root cause: `isRangedThreat` treated every `RangedAttackMob` as ranged, but Minecraft's DrownedEntity implements that interface regardless of equipment. Its actual TridentAttackGoal.canStart requires a main-hand trident. Added the same condition to threat classification, preserving the existing six-block melee threshold and ranged classification for trident carriers. Regression failed before the fix; reflex tests passed afterward. HotSwapped one class and resumed the same interrupted home route; live recovery pending.

D035 live result: the first tick after HotSwap resolved the distant drowned at server tick 4,910. Subsequent drowned were admitted at 5.98–6.00 blocks, confirming the melee boundary live. Close encounters and air recovery continued; do not claim the home route completed. Export `drowned-after-classification.jsonl` contains 1,223 observations, not truncated.

### D036 — Combat must retrieve an available weapon

The same encounter exposed another inventory limitation: `equipBestCombatHotbarItem` examined only slots 0–8. Shearing and other work had moved the iron sword to inventory slot 27; the reflex fought with an iron pickaxe despite carrying a fresh sword and stone axe. Pause/inspection (`combat-inventory-weapon*`) confirmed pick durability 129/250, untouched sword 250/250, health 17.025 and hunger 16. The ranged fix is separate from this equipment weakness.

Search all 36 carried inventory slots using the existing weapon ranking; prefer equal-ranked hotbar items, otherwise swap the selected weapon into the current hotbar slot. Resolve the screen slot by its backing inventory/index so an interrupted container task cannot cause an unrelated container slot swap. Added weaponItemId to attack events. The exact slot-27 regression failed before expanding the search; reflex tests passed afterward. HotSwapped one class. Eating pork restored full health; an air recovery hold then explicitly awaited the planner, and Codex resumed that exact hold. Weapon use in a subsequent live encounter remains pending.

### D037 — Bed, spawn point and repeat farming

The resumed wool return completed at client tick 7,681. The white-bed inventory goal succeeded using three wool from two sheep; it unnecessarily converted a log first despite having six planks for a three-plank recipe (`bed-plan-state.txt`, `bed-craft-inventory.txt`). This is confirmed excess crafting, not a missing-material claim; defer deeper recipe planning work until the bed and crop maintenance checkpoint is complete.

Normal coordinate placement from a deliberate stance placed the bed at foot `(257,63,480)`, head `(257,63,479)`, facing north. No new orientation API was needed. Using it in daylight produced the game's `Respawn point set` message (`bed-use-result.txt`, client log 04:38:16). Closed the shelter door and updated its world bookmark while retaining the protected bounds. The farm still had fourteen farmland and fourteen wheat cells, with four mature crops and five at age six (`farm-after-wool-trip.txt`). Starting another harvest/collect/replant pass in Easy.

### D038 — Revert a bed layout that blocks the door

The first repeat farm call failed before harvesting. A read-only inspection of its retained executor identified work goal `(259,63,481)`; an explicit navigation to that exact cell also failed. Removing the bed from `(257,63,480)` made the same route complete (`bed-exit-reproduction-*`, `bed-removed-exit-*`). This was a Codex construction mistake: the bed occupied the inside door approach under a low roof, not evidence for a new pathfinding patch.

A relocation attempt from an edge stance was rejected with player_hitbox_overlaps_target; moving to `(257,63,481)` allowed placement at foot `(256,63,480)`, head `(255,63,480)`, facing west. The revised bed's exit and return to `(257,63,480)` both completed, and using it in daylight reset the spawn point. Updated the world bookmark accordingly, preserving bounds and recording why the east column must stay clear. The second farming pass then completed six harvests/replants: wheat 2→8, seeds 6→15, all fourteen crop/farmland cells intact (`easy-farm-repeat-*`, `repeat-farm-{inventory,plants}.txt`).

### D039 — Reuse an observed workbench in recipe planning

The bed's excess log conversion came from `effectiveRecipeInputCounts`: it reserved four table planks whenever no table was in inventory, ignoring the placed table the executor would use. It also multiplied those setup planks by the number of recipe runs. Preserved `bed-excess-planks.jsonl` (280 observations; truncated flag set, so not lossless) plus the earlier route/inventory artifacts.

Expose the executor's existing usable-workbench search to observation. While resolving, publish a current-tick world-site fact for that table and actor; the planner can omit portable setup only while that observation is fresh. Scale recipe inputs by batch size, then add portable setup once if still required. Nearby-table and batch regressions failed before the fix; a stale-table test retained the setup requirement. Provider, crafting and action-runtime tests passed. HotSwapped three classes and started a two-bread batch beside the shelter table for live verification.

D039 live result: the two-bread goal succeeded with one `wheat_x3_to_bread` route step and no plank conversion (`nearby-table-bread-complete.txt`). Logs stayed at one and planks at seven. An immediate inventory observation briefly showed the earlier ingredient count; a settled read confirmed wheat 8→2 and bread 0→2 (`after-bread-settled-inventory.txt`). Avoid inferring duplication from an intermediate client inventory update.

The helmet goal exhausted the local ore scope with zero targets and zero broken blocks, repeating eight replans before terminal failure. Recorded as a bounded but wasteful depleted-scope retry, not a new excavation failure. A structured query of the remembered old workshop found four iron ore at `(205,95..96,457..459)` (`old-workshop-iron-query.txt`). Starting a trip there to search a fresh acquisition scope. Current recorder exports have a truncated flag from session drop counters; keep that limitation explicit.

D036 live verification: the next workshop trip triggered skeleton combat without any manual sword equip. Attack events at client ticks 19,354, 19,383, 19,395 and 19,415 explicitly recorded `weaponItemId=minecraft:iron_sword`; the threat resolved. Further encounters followed. At the next resolved hold, inventory showed the sword in hotbar slot 5 at 239/250 durability, health 20 and air 300 (`new-combat-events.txt`, `sword-combat-{state,inventory}.txt`). This proves inventory retrieval and actual attacks in the live world. Resumed the exact held workshop navigation.

### D040 — Complete the supply return and Easy checkpoint

The old-workshop scope contained eleven observed ore, and constrained acquisition collected five raw iron with full health (`fresh-iron-inventory.txt`). The supplied helmet goal succeeded, and equip verification showed all four iron armor slots. Its return encountered another hostile, recovered to full health, resumed the held route and arrived at the revised shelter bookmark. The tighter local monitor reads the current hold, waits for food use, and resumes only a resolved reflex; earlier stale-hold/item-use rejections were expected concurrency guards, not successful resumes.

Final inspections confirmed the closed door, intact bed, fourteen planted/hydrated farmland cells, full health/hunger and retained food/tools. Paused at world time 167,363 after 49,285 Easy simulation ticks. This completes the stated two-cycle capability checkpoint across the playtest/fix loop; it is not a claim of uninterrupted autonomous reliability or validation on harder difficulties. Keep Easy for the next session, address inventory capacity before extended gathering, and investigate remaining wasteful scope retries from their recorded evidence.

### D041 — Shield preparation and chest/logbook follow-up

The next requested capability is shield use against skeletons, followed by cave exploration rather than strip mining. Added carried-shield retrieval to offhand, facing/blocking drawn bows and incoming projectile trajectories, release between shots and on reflex cleanup, plus an explicit shield preparation preference in the planner prompt. Focused reflex/item-use/prompt tests passed and two runtime classes were HotSwapped without restarting. Live shield blocking is still pending; do not count shield-use events as proof of blocked damage.

The shield item goal at home failed in the previously depleted ore scope. Travel to the remembered iron workshop completed. Its supply route then left two raw-iron drops within 0.6 blocks while all storage slots were full. Paused and exported `run/playtest/2026-09-13/full-inventory.jsonl`; the resumed route later picked up ore and entered smelting without manual disposal. Drop requests were rejected by the active graph guard; nothing was discarded. A subsequent supply replan was paused/exported as `supply-replan.jsonl`. Both exports report truncation from recorder loss counters. The player remains paused while adding the user's requested chest inspection/transfer tools and an automatic world-persistent interaction logbook. These are prerequisites for the longer cave trip, not a change to the shield objective.

### D042 — Chest storage and automatic interaction history

Added `inspect_container`, `transfer_container` and `close_container` for ordinary open chests/barrels. Transfers preflight the full exact quantity, preserve component variants, fill partial stacks first, refuse stale screen IDs/nonempty cursors/insufficient space, and use normal screen clicks. Transfer submission is explicitly not completion; inspect settled counts. Mutation ownership remains behind the normal active-task, graph and survival gates.

Added server observations for container opening/clicks, crafting output and real item drops. Significant changes coalesce within a completed server tick, then append off-thread to `airicraft/interactions.jsonl` inside the world save. `read_logbook` is read-only, filtered by item, event and remembered place, and returns bounded recent history. Stock snapshots remain dated observations. Item queries retain later empty stock snapshots so old positive stock does not hide an observed depletion. Read/write ordering and world isolation have tests; normal server shutdown drains pending writes.

Full build passed. New mixin observation hooks required a normal saved-client restart. In the same survival world, the chest graph crafted one chest from the existing mixed plank supplies; the server logbook recorded exactly one crafted chest at the workbench. Placed home chest at `(256,63,481)`, leaving the door column clear. Deposited 96 cobblestone and withdrew 7: settled chest count 89 matched the persisted server snapshot. Seventeen further surplus deposits restored 20 free storage slots. Stale syncId and insufficient-source requests were rejected without changing stock. All 18 item counts in the live chest inspection exactly matched the final persisted snapshot. `chest-roundtrip.jsonl` contains 1,284 observations with frames and is not truncated. Close-tool loading, restart persistence, furnace/drop recording and shield combat remain to validate before cave exploration.

### D043 — Confirm persistence and repair open-furnace collection

A saved-client restart retained the chest and logbook. `read_logbook` returned the previous home stock; reopening the chest confirmed it, and `close_container` worked. A one-dirt drop produced one server-recorded `dropped` entry at home. Querying an item retains later container observations that no longer contain it, so historical positive stock is not the only result after depletion.

At the workshop, opening furnace `(202,92,469)` revealed three iron ingots and one coal. Confirmed untracked collection failed with `station_unavailable`: the executor converted an `#open_screen` station key to a world position, unlike smelting's existing open-screen path. Paused/exported `furnace-collect-failure.jsonl` (1,363 observations with frames, not truncated). The logbook correctly showed no withdrawal for the failed request.

Added exact dimension/screen-ID matching for collection from a confirmed open furnace, with stale-window rejection. Collection also refuses a nonempty cursor and reports `inventory_full` if quick-transfer leaves output behind, preserving the tracked process instead of falsely completing. The open-screen regression failed before the fix; smelting executor/planner/process tests passed afterward. HotSwapped one class. Retrying the same furnace recovered all three ingots, and `furnace-recovered-logbook.txt` shows a three-ingot withdrawal followed by a coal-only snapshot. The return navigation reported CALC_FAILED near the requested stance, but ordinary furnace use succeeded within range; record that as a bypassable waypoint issue, not another blocking pathfinding defect.

### D044 — Shield blocks live arrows and allows counterattacks

The shield inventory goal succeeded, and the automatic logbook recorded one crafted shield. Against a naturally encountered skeleton, the reflex raised the offhand shield for drawn bows/incoming arrows, then released it and closed for iron-sword attacks. Minecraft awarded `Not Today, Thank You` at client tick 8,461. Health stayed at 20 through the recorded cycles and final inspection; shield durability changed from 336 to 332. Attack events followed at ticks 8,543, 8,587, 8,600 and 8,612 before `threats_clear` at 8,613. This validates one live skeleton encounter, not perfect blocking under every timing or mixed-mob scenario.

Paused and exported `shield-combat.jsonl`: 3,074 observations with frames, not truncated. Baseline/final inventory and events are alongside it in `run/playtest/2026-09-13/`. The held navigation was reset by the later configuration reload after its incident had been saved.

### D045 — Compact visible cave survey and bounded return

Added read-only `survey_cave`: sparse first-hit rays return visible standing candidates, exposed ore and hazards, with light and explicit route uncertainty. The planner procedure uses remembered entrances/junctions, short movements, lighting and resupply; passage navigation disables Baritone breaking/placement. No hidden ore scan selects targets. The new provider loaded through factory HotSwap and normal configuration reload without restarting the client. Focused tests and the full build passed.

Surface observations returned water hazards and standing candidates. With breaking/placement disabled, navigation entered the existing underground workstation room at `(257,52,476)` but failed its final exact goal `(255,52,475)` with CALC_FAILED. Paused and exported `cave-endpoint.jsonl` (1,103 observations with frames, not truncated); screenshot shows the cramped table/furnace room. Surveys at radius 12 and 24 offered the same visible floor but no additional passage or exposed ore. A visible candidate is not a proven route: do not retry it blindly or label the reachable room a progression blocker.

The return to remembered `(263,65,471)` completed with excavation and placement still disabled. This validates a bounded passage visit and return, not natural-cave discovery or ore acquisition. Deeper natural-cave exploration remains the next live acceptance boundary.

Final checkpoint: return to shore shelter `(257,63,480)` also completed. Restored `allowBreak=true` and `allowPlace=true`, closed and inspected the door, and confirmed health/food 20, shield 332/336, five torches and fifteen free storage slots. `read_logbook` still returned the dated home chest stock (89 cobblestone and the other stored supplies). Paused under debug session `4bb2a732-8469-40f2-a162-1b7b2dbb5ad4`, epoch 1. `final-home.jsonl` contains 1,479 observations with frames, not truncated. The live client remains paused for the next session.
