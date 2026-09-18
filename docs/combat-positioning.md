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

With two or more nearby threats, movement considers the whole pack. A bounded
beam search projects short pursuit trajectories using each mob's movement attribute,
velocity, and observed displacement. It scores exposure along the route, close contact,
opposing attack directions, and dead ends. At adequate spacing, a consistent tangent
around the pack is preferred, encouraging pursuers to converge on one side. Emergency
separation takes precedence over circling. Attacks still select an in-range target;
movement is no longer determined by that target alone. Shield defense remains active.

The client adapter builds at most 128 connected feet cells within six horizontal
blocks and two vertical blocks, using Baritone's actual cardinal traverse/ascend/
descend costs. It rejects unloaded terrain, edits, hazards, fluids, drops greater
than one block, and movements outside travel bounds. Search considers at most eight
steps over 24 game ticks with a beam width of 24. It replans every six ticks or upon
arrival and freshly checks the next movement before steering toward an exact adjacent waypoint. The player faces the visible threat
group and backpedals or strafes along that route; an active shield keeps its incoming
attack heading. Travel-time estimates exclude sprinting.
No pathfinding settings are reset or loosened. An unavailable route means hold and
defend, not blind backward movement.

This is a local heuristic, not a globally optimal combat plan. Mob pursuit is a
horizontal estimate, not a simulation of every mob's AI or terrain capabilities.
It conservatively includes threats across elevation; special ranged attacks and
teleportation are not predicted. Terrain can change after observation. Clustering
and survival improvement require live evidence, separate from the deterministic tests.

`reflex.combat_reposition` events record the route, nearby count, selected risk and
standing risk. Reflex decision evidence includes the search size and planning time.
