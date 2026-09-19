# Camera control

`ClientRuntimeController` owns the production `CameraController` and ticks it once
at the end of each client tick. Task executors, social looking, survival reflexes,
vision capture, the player bridge, and Baritone submit targets to that instance.
Only the controller writes player yaw and pitch. Minecraft still owns manual
mouse input, server corrections, and interpolation of previous/current angles.

`RotationSpring` integrates a critically damped angular spring analytically at
50 ms per client tick. Yaw follows the shortest arc; pitch stays within ±90°.
Retargeting retains angular velocity. A motion finishes only when both angle
error and angular velocity are small. Previous rotation fields are not rewritten,
so Minecraft can interpolate between ticks when rendering.

An explicit Airicraft target takes precedence over Baritone until settled.
Baritone can continuously retarget its own motion. The LookBehavior mixin consumes
`updateTarget` before Baritone stores a target, leaving its independent player,
movement-event, and elytra rotation writers inactive. Baritone input waits for
horizontal alignment before grounded travel and for aim before clicking. Sneak
remains available; airborne and swimming navigation do not wait for horizontal
alignment. Combat and item delivery submit direct aim only when they own steering.
Combat waypoint steering converts world directions to keys using the current
player yaw, rather than assuming that the requested aim has already been reached.

Block interactions and breaking wait for alignment before interacting. Entity
interactions wait until the current viewing ray intersects the target's bounds.
Targeted vision waits for the spring to settle before scheduling a screenshot;
competing targeted captures fail as busy. World leave, reload, player replacement,
and death discard spring state and cancel pending alignment waits.

The existing `cameraLerpDefaultTicks` and `--duration-ticks` fields are retained.
They now select an approximate spring response time, not a linear interpolation
deadline. Zero selects the default spring (18 radians per second natural
frequency), rather than snapping. `player look-at` always reports `scheduled`.

## Validation

Unit coverage includes convergence, shortest yaw arc, integration across time
steps, retargeting momentum, repeated targets, ownership, and cancellation.

A real client loaded both mixins and completed a short eastward Baritone route
and its near-180° return. The return trace showed successive yaw values of
−82.846, −43.568, 9.887, 46.862, 68.091, 79.241, and 84.799 degrees; forward input
remained off until the next tick. A targeted eastward screenshot completed.
Evidence from that copied-world smoke is in `/tmp/camera-spring-turn.jsonl` and
`/tmp/camera-spring-return-events.txt` on the development machine.

This is limited navigation/capture proof. The copied survival world later died
to a spider while idle; combat, parkour, elytra, water navigation, and all
interaction types are not established by that smoke. Final ownership and aim
bounds changes require their own live coverage in those conditions.
