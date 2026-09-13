# Planner goals

A planner goal records the purpose of multi-stage work. It is separate from an action-graph goal or navigation job: collecting logs can complete while the larger objective of preparing a shelter remains active.

The initial tool surface includes four tools:

| Tool | Effect |
| --- | --- |
| `set_planner_goal(objective)` | Starts an objective when none is active. |
| `change_planner_goal(goalId, objective, reason)` | Replaces the active objective and returns a new identity. |
| `finish_planner_goal(goalId, status, outcome)` | Ends with `success` or `give_up`, recording evidence or a reason. |
| `inspect_planner_goal()` | Reads the current objective, identity, status and outcome. |

Changing or finishing requires the exact current identity. A delayed completion for an earlier objective cannot finish its replacement. Goal mutations use the client executor and participate in the planner's side-effect boundary.

One current record is saved atomically in the local world at `airicraft/planner-goal.json`; completed records remain readable until another goal replaces them. No unbounded goal history accumulates. Reloading the planner restores the record; switching worlds loads that world's record. Remote worlds without a local save currently return an explicit persistence-unavailable error. Invalid saved data is reported and is not silently overwritten.

The current record is included in the planner's system context, so conversation compaction does not erase the objective. A saved objective is intent, not evidence: after reload the planner must inspect current inventory and surroundings.

When an active objective remains and the planner and action executors are idle, the existing client tick path submits a `GOAL CONTINUATION` trigger after a one-second quiet interval. It does not need a chat message, ambient idle timer, or unrelated event. This uses the existing generation/coalescing mechanism. Queued task updates and active planner/tool calls take priority. Active jobs, action graphs, navigation/follow goals, safety reflexes, disabled/degraded planners, external-driver mode and unavailable world actuation suppress continuation. Tick-debug pause freezes the client tick path and therefore continuation.

A plaintext reply yields the current turn. It does not complete an active planner goal. While an action runs, the planner should yield and wait for the terminal task update rather than polling the same status repeatedly. It must explicitly finish a goal once all completion conditions are observed, or give up with a concrete explanation when it cannot progress. Goal tools do not cancel action jobs; cancellation remains explicit. `clear_goal` retains its existing action/navigation meaning.

The runtime does not certify the model's claimed success or choose a replacement objective. Those are planner decisions whose evidence must be checked in playtests. It also does not detect all no-progress loops: repeated calls within one model/tool chain still need observation and diagnosis.

Tests cover the previous reply-to-idle behavior without a planner goal, continued work after a plain reply with an active goal, action-busy and disabled/external-driver guards, termination, persistence, world isolation, stale completion rejection and malformed saves. Live evidence is recorded in `docs/autonomous-playtest-log.md`.
