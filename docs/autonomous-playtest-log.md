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
