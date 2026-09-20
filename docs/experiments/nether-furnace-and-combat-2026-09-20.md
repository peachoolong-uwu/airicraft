# Nether continuation: furnace placement and combat disconnect

Run `20260920-212417-432374-94781-990c3cbe-03a1-42f9-a088-d48a1b04e6b7`
used commit `90c3f7d3`. Its recording finalized with `world_left`; the client log
shows this was a network-packet exception, not an intentional departure. The
published recording and CRC-valid world checkpoint remain under the run index's
`artifactPlayPath` in `automatic_playtest/<run-id>/summary.json`.

## Combat disconnect

At agent tick 7609, a creeper explosion reduced health from 20 to 17.094664.
The health-update handler entered `SurvivalReflexRuntime.resolve`, which threw
`NullPointerException: Cannot invoke "java.lang.Long.longValue()"` while computing
the optional `noProgressTicks` event field. The nested conditional combined a
primitive long with a nullable boxed result and unboxed null when neither stall
condition applied. Minecraft disconnected with `Network Protocol Error` and saved
the world; no fatal player damage is recorded.

The fix uses explicit branches returning nullable `Long`. Regression coverage
reproduced the exception before the fix and covers absent/non-stalled monitors,
both stalled monitors, and progress-monitor precedence. This is source and unit
proof, not a subsequent live explosion replay.

## Underground furnace investigation

Timeline entries 2114–2152 contain the relevant calls. `find_placement_sites`
returned exactly two targets: `(-226,48,-1)` with stand `(-226,48,0)`, and
`(-226,48,0)` with stand `(-226,48,-1)`. Both targets had coal-ore support below.
The player was near `(-227,46,-1)`.

Three `place_block` calls failed (terminal ticks 4897, 4968, 5504), targeting the
first cell, the second cell, then the first cell again. Each requested
`facePreference=down`, selecting the support below and its upward face. Each
reported `target_not_visible ... safe_stand_position_not_found
attemptedStandPositions=0`. The furnace remained in inventory.

`CurrentWorldQueryService.standableAdjacentPosition` checks immediate horizontal
neighbors at target height. `BlockInteractionTaskExecutor.placementStandCandidates`
deliberately omits those neighbors, preferring a full-cell gap except for lower
stances. The existing `placementStandCandidatesKeepOneBlockClearOfTarget` test
asserts this exclusion. Thus the executor never considers either advertised
standing cell. Query range is geometric proximity, not support-face visibility
or a navigable route. At feet Y=46, a standing eye is below the support's top face
at Y=48, consistent with the observed raycast failure.

The planner also contributed to the unsuccessful retries. It guessed a stance at
`(-228,47,-1)` from a truncated inspection, navigated with `exactY=false`, and
remained at Y=46. Its first custom query called nonexistent `world.getBlock`;
after reading the API it correctly indexed `world.blocks`. Its findings then
treated nearby coordinates as sufficient reach evidence despite the earlier
visibility failure. Finally it returned to the surface instead of placing the
furnace underground. None of the calls consumed the furnace.

Recommended follow-up: admit physically non-overlapping adjacent stances through
the same standability, reach, and support-face visibility checks as other
candidates; align query output with those checks and explicitly distinguish a
geometric site from a route-proven placement. Keep collision and visibility
checks. A copied-world test is still needed to establish whether either ledge
stance was reachable without terrain edits. No furnace behavior was changed in
this investigation.
