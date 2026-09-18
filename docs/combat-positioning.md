# Swarm positioning

The survival reflex keeps nearby threat awareness separately from its engagement
gate. Visible hostile monsters and known aggressors are remembered throughout the
configured `maxThreatDistance` (default 16 blocks). Known threats remain relevant
to route scoring behind a corner. Neutral angerable mobs require actual aggression.
The existing melee engagement distance and line-of-sight policy still control when
combat begins; awareness alone does not authorize chasing a distant mob.
An active swarm encounter uses a wider ten-block release distance (still capped
by the configured radius), retaining briefly occluded pursuers. This prevents
repeated release/reacquisition while circling along the six-block engagement edge.

Combat selects the nearest visible opponent and retains it until it disappears,
loses line of sight, or moves beyond six blocks. Movement, camera facing, and attacks
share this focus. It targets a 2.3–2.7-block center-distance band through the whole
attack cooldown; both the next step and route endpoint are penalized for leaving
three-block attack reach. This also applies to a lone melee opponent. Other mobs
still contribute exposure, body collision, pincer, and dead-end costs, encouraging
isolation of the focus without backing away from the entire pack. Ranged enemies
do not become safer by retreating; navigation closes to melee range.

A bounded beam search projects short pursuit trajectories using each mob's movement
attribute, velocity, and observed displacement. At adequate spacing, a consistent
tangent around the selected opponent is preferred. The original encounter anchor
limits movement to six blocks; knockback outside it permits inward steps. Shield
defense remains active. This spacing is a preference, not a guarantee against hits.

The client adapter builds at most 128 connected feet cells within six horizontal
blocks and two vertical blocks, using Baritone's actual cardinal traverse/ascend/
descend costs. It rejects unloaded terrain, edits, hazards, fluids, drops greater
than one block, and movements outside travel bounds. Search considers at most eight
steps over 24 game ticks with a beam width of 24. It replans every six ticks or upon
arrival and freshly checks the next movement before steering toward an exact adjacent waypoint. The player faces the selected
opponent and backpedals or strafes along that route; an active shield keeps its incoming
attack heading. Travel-time estimates exclude sprinting.
No pathfinding settings are reset or loosened. An unavailable route means hold and
defend, not blind backward movement.

This is a local heuristic, not a globally optimal combat plan. Mob pursuit is a
horizontal estimate, not a simulation of every mob's AI or terrain capabilities.
It conservatively includes threats across elevation; special ranged attacks and
teleportation are not predicted. Terrain can change after observation. Clustering
and survival improvement require live evidence, separate from the deterministic tests.

`reflex.combat_reposition` events record the route, nearby count, selected risk and
standing risk. Reflex decision evidence includes the selected target UUID, search size, and planning time.
