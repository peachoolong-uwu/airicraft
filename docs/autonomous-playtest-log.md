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
