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

Combat recalculates attack focus each tick. Visible witches rank first, then baby
zombie variants, armed skeleton variants when melee pressure is low, spiders, and
ordinary melee mobs. Skeletons fall below melee targets when melee mobs are within
four blocks or projected to contact within ten ticks from at most six blocks.
Visibility takes precedence; proximity, closing speed and attack preparation break
ties within a rank. There is no persistent target lock.

The focus normally controls camera facing and a 2.3–2.7-block fighting distance.
Other mobs contribute route exposure, body collision, pincer and dead-end costs.
Both the immediate route step and sub-block waypoint also score clearance from
all melee threats: reach +0.3 blocks, plus up to 0.8 blocks for three ticks of
observed approach. Mob observations refresh each tick; a half-block displacement,
0.1-block/tick velocity change, or crowd-size change invalidates route reuse.
Ranged enemies do not gain fictional contact damage; navigation closes to melee
range. Incoming thrown potions add predicted splash-impact avoidance costs.

An igniting creeper within seven blocks temporarily overrides movement focus.
Cooldown or fuse progress >=20% requests five-block separation; ready approaches
request sprint when hunger permits. Attacks stop at >=20% fuse. The shield is a
late fallback at >=70% fuse, not an intentional explosion strategy. Bow guarding
starts at draw tick 14 of 20, or for incoming collision-course arrows, with a
six-tick warning hold. These thresholds are heuristics, not guaranteed escapes.

For a melee target already approaching within 3.5 blocks, next-step spacing uses
its measured horizontal velocity to anticipate up to four ticks of motion, capped
at half a block. This favors a sidestep, short backstep, or hold over walking into
a charge. Route endpoints still use current target distance and the same attack
reach penalty and encounter anchor. Stationary, departing, and ranged opponents
receive no interception offset. A meaningful velocity change invalidates the
cached movement decision, including after knockback.

A bounded beam search projects short pursuit trajectories using each mob's movement
attribute, velocity, and observed displacement. At adequate spacing, a consistent
tangent around the selected opponent is preferred. The original encounter anchor
limits melee movement to six blocks; knockback outside it permits inward steps.
Ranged approaches and creeper retreats use a fresh local anchor. Shield
defense remains active. This spacing is a preference, not a guarantee against hits.

The client adapter builds at most 128 connected feet cells within six horizontal
blocks and three vertical blocks, using Baritone's actual cardinal traverse/ascend/
descend costs. It rejects unloaded terrain, edits, hazards, fluids, drops greater
than three blocks, and movements outside travel bounds. Search considers at most eight
steps over 24 game ticks with a beam width of 24. It replans every six ticks or upon
arrival and freshly checks the next movement before steering toward a collision-checked sub-block waypoint. Baritone movement states execute
ascents and descents while local steering retains combat aim. Committed terrain
moves finish before replanning and time out after 60 ticks; no block placement or
towering is implemented. The player faces the selected
opponent and backpedals or strafes along that route; an active shield keeps its incoming
attack heading. Travel-time estimates exclude sprinting.
No pathfinding settings are reset or loosened. An unavailable route means hold and
defend, not blind backward movement.

This is a local heuristic, not a globally optimal combat plan. Mob pursuit is a
horizontal estimate, not a simulation of every mob's AI or terrain capabilities.
It conservatively includes threats across elevation; potion impacts are approximate and
teleportation is not predicted. Terrain can change after observation. Clustering
and survival improvement require live evidence, separate from the deterministic tests.

`reflex.combat_reposition` events record the route, nearby count, selected risk and
standing risk. Reflex decision evidence includes the selected target UUID, search size, and planning time.

The policy was integrated from `codex/natural-combat-experiments`; the endurance
runner, automatic healing, cheat bridge and replay tooling remain on that branch.
Live mixed-horde windows informed tuning, but were not matched trials. Complete
creeper sprint-hit cycles and normal survival without experimental healing remain
unvalidated. Focused regressions cover ranking, shielding, terrain state startup,
route constraints and flanker clearance.
