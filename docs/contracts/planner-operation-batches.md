# Planner operation batches

A planner action remains one semantic operation. The runtime may execute its bounded
mechanical steps without returning to the planner between each step. Arbitrary action
chains are still rejected. Navigation and a subsequent world query remain separate
calls; this change does not introduce deferred observation requests.

## Container transfers

`transfer_container` accepts one direction and 1–36 item entries for the currently
open chest-style container:

```json
{
  "syncId": 1,
  "direction": "deposit",
  "items": [
    {"itemId": "minecraft:dirt", "quantity": 7},
    {"itemId": "minecraft:cobblestone", "quantity": 11}
  ]
}
```

Each quantity is an integer from 1 to 2304. The previous top-level `itemId` and
`quantity` form still works, but cannot be combined with `items`. Equipped armor and
offhand are excluded, as before.

The controller plans every entry against predicted remaining source counts and
shared destination space before sending any clicks. An invalid later entry prevents
all clicks. Repeated item entries consume only remaining stock and capacity. Item
components remain distinct. This preflight is not a server transaction or rollback:
after submission, inspect the container to verify settled counts, particularly when
other players may also change the container.

## Kill and collect

`attack_entity` with `mode: "kill"` continues through nearby item-drop collection.
The planner still chooses the entity; the executor owns movement to its drops.
`hit_once` finishes after a hit and does not collect drops.

Collection begins only after observed depleted health or a killed removal reason.
A lost/unloaded target alone is not a confirmed kill. The collection area is a box
extending four blocks in each direction from the observed death position; this is a
local pickup operation, not exact attribution of items to a particular mob.

The executor waits for 20 consecutive empty observations before completing and
allows up to 200 active collection ticks while drops remain. Normal session gates,
cancellation and navigation ownership still apply. Full inventory, unreachable
items, or timeout produce a failure that explicitly says the target died but drops
remain uncollected. Both successful and failed collection results include `collectedItems`, a sorted map
of positive inventory gains since the first attack. Existing stacks are subtracted;
unchanged inventory reports `{}`. These are observed gains, not expected loot or
exact attribution to the killed mob. The terminal task event and retained work
summary carry this result into the planner follow-up, even after executor cleanup.
Disappearance alone is not proof that items entered inventory.

## Validation, 2026-09-18

`./gradlew build` passed: root and wrapper suites had 1,403 passing tests and two
skipped tests. Focused coverage includes shared destination reservations, repeated
item entries, malformed batches, existing tool-call restrictions, and distinguishing
a confirmed death from an unloaded/discarded entity, inventory gain reporting, and
retaining terminal collection messages after executor cleanup.

An isolated external-driver client used a copy of the `farm_easy` world with a
small local datapack providing a chest, test inventory and a stationary cow. All
behavior under test used the standard wrapper planner-tool path:

- Deposited 7 dirt and 11 cobblestone in one call; settled counts and empty cursor
  matched the request.
- Withdrew 3 dirt and 5 cobblestone in one call; settled counts matched.
- Requested 2 dirt followed by one unavailable diamond; the request failed before
  moving dirt, and subsequent container inspection showed unchanged counts.
- One kill call completed with work ID
  `JOB:job-73a6ed9d-d7d8-46ba-8b05-3287740e8c43`. The player moved from the attack
  position, acquired 2 beef and 1 leather, and no nearby entities remained.
  No additional movement or pickup action was submitted during that work.
- A second hunt started with 2 beef and 1 leather already carried. Its terminal
  task event and retained work result reported
  `collectedItems={minecraft:beef=3, minecraft:leather=1}`; final inventory was
  5 beef and 2 leather. Existing stock was not reported as newly collected loot.
- Final-build hunt `JOB:job-403d1080-177b-419b-b657-1a0625bf29f0` reported
  `collectedItems={minecraft:beef=3}` in both the terminal event and retained work
  result, with no misleading resource counter. Inventory increased from 5 to 8
  beef; the existing two leather were correctly omitted from the gains.

Local wrapper outputs and the disposable fixture are retained under
`build/planner-batch-smoke/`. This proves the isolated singleplayer cases above;
multiplayer contention and obstructed/full-inventory hunting were not live-tested.
