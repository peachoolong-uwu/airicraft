# Physical observations for the planner

System 1 samples the active player's physical state and emits `player.physical` episodes. System 2 decides whether to recover, replace a goal, or continue. The observer neither moves the player nor retries navigation.

| Kind | Reporting rule |
| --- | --- |
| `fall` | Unsupported descent of at least 1.5 blocks; start, updates at most every 40 ticks, and landing/support transition. Ordinary jumps and one-block steps stay quiet. |
| `displacement` | At least 2 blocks of movement without directional input. Updates require another 2 blocks and 40 ticks; 10 quiet ticks end the episode. Water contact is a fact, not a claimed cause. |
| `burning` | First observed active condition, significant health deterioration, and clearing. |
| `low_air` | Submerged air at/below the existing reflex threshold, significant air/health deterioration, and clearing. |

Every episode carries its identity, start/observation ticks, origin/current position, velocity, contact flags, health/air, and start/current task context. Active tasks and tasks ended within 100 ticks include identity, status and a known target. The episode keeps its original context even after a task changes. World changes, respawn and shutdown reset observations instead of reporting cross-world movement. Tick-debug pause freezes sampling with the agent tick.

These are observations, not a complete involuntary-motion classifier. A planned drop can produce a fall; displacement during active directional input is not separately detected. Riding, gliding and creative flight are excluded from movement episodes. The stream does not claim knockback, teleportation or a water cause without evidence. Existing `combat.damage_taken` events retain their damage-source information and now include position/task context; existing survival reflex events remain in use.

Episodes enter the existing planner semantic feed and rolling flight recorder. They can wake System 2 with a coalesced `SYSTEM` trigger per kind. While the survival reflex owns actuation, observations remain semantic and the existing reflex-resolution trigger provides the planner handoff. This adds no durable physical-event archive. Inspect with `agent events recent --verbose`; pause and export through the [flight recorder](live-playtest-recording.md).

Navigation terminal notices also carry actual position, velocity and support state. Block equality while airborne cannot establish arrival. After `AT_GOAL` or a cancellation, the executor observes up to 10 ticks for a supported arrival, allowing a normal step to land. It issues no movement during this window. An unconfirmed goal event fails; an unconfirmed cancellation remains cancelled. Water/climbing count as support, so a subsequent current can still move the player: the physical stream reports that change for the planner to interpret.

The routing profile table belongs to each runtime, so config/runtime reload after HotSwap rebuilds it. Reload resets active planner/task state and releases tick pause; export first and pause again afterward.
