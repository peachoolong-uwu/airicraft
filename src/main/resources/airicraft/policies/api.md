# Policy API for this build

Use run_policy for supported action procedures and query_world for read-only world computations. These tools share the bounded GraalJS engine but have different inputs and capabilities. Tool availability in your role's advertised schema is authoritative.

## query_world

Arguments: {source, input, radius?, verticalRadius?, center?, includeBlocks?, includeEntities?}. Define a normal synchronous function query(world, input), not a generator or async function. Return JSON. input is your JSON object. No policy methods or further reads are available.

The snapshot is detached from the live world. Local JavaScript edits cannot change Minecraft. Capture happens once on the client thread; guest computation runs off-thread. This tool does not create foreground work, end the planner turn, require an open container or cancel an action in progress.

Bounds: horizontal radius 0..8 (default 4), vertical radius 0..4 (default 2); center defaults to the player's integer block position or specify {x,y,z}. Every corner must be within 64 blocks of the player. Max 2601 block positions. includeBlocks and includeEntities default true. No chunks are loaded for the query. Blocks outside loaded chunks or world height are omitted and counted in metadata. Entities intersect the box, exclude the controlled player, and are limited to the nearest 64, ordered by distance then UUID. Their positions can lie just outside the box when their bounding boxes intersect it.

Snapshot fields:
- world.player: {position:{x,y,z}, health, food}.
- world.blocks: [{position:{x,y,z}, blockId, properties, air, replaceable, fluid, collisionEmpty, light}]. Includes loaded air cells. properties maps property names to strings (e.g. open:"true", half:"lower"); light is the client's combined light level 0..15. These facts are not a path, placement or spawn-safety proof.
- world.entities: [{uuid, type, position:{x,y,z}, distance, hostile, alive, health?}]. health exists for living entities. hostile means Minecraft's MONSTER spawn group, not current aggression or a complete threat assessment (e.g. an angry neutral mob may be dangerous). No inventory, equipment or unopened-container contents are exposed.
- world.metadata: {source:"client_loaded_snapshot", serverTick, worldTime, dimension, bounds:{min,max}, blocks:{included,requested,returned,unloaded,outsideWorld,truncated}, entities:{included,matched,returned,truncated,order}}. serverTick=-1 means unavailable; client snapshot values need not match authoritative server state at that tick.

Tool output is {metadata, result}. Metadata is retained by the host even if your program edits its local copy or returns only a scalar. Empty results apply only to captured coverage. Guest-filtered or sliced results are your own additional subset; report your own truncation if slicing. Successful reads register the loaded block positions with normal action read-freshness tracking; specialized native action checks still apply. Capture is rejected if the world changes before the result is delivered.

Example: source="function query(w, input) { return w.blocks.filter(b => b.blockId === input.id).map(b => b.position); }", input={"id":"minecraft:oak_door"}. Use ordinary inspect_world for specialized standing, interaction and placement-site checks. Loaded blocks can include hidden terrain: use survey_cave and visible-only native gathering for cave ore selection.

Source and input limits are 32768 and 16384 characters. Host snapshot limit is 2097152 JSON characters. Returned JSON is limited to 16384 characters including the guest result envelope. The same execution deadlines, statement limit and host-access restrictions as run_policy apply. An oversized output fails; reduce fields, aggregate or narrow bounds. read_policy_docs is a separate no-argument read tool available without a world.

## run_policy

Call run_policy with {source, input}. source defines a synchronous JavaScript generator: function* main(policy, input). input is a JSON object. Use yield, not async/await. Variables survive between yields within one invocation; nothing persists between invocations. Plain JavaScript filtering, arithmetic and branching require no yield. Return JSON, with an explicit goal result such as {restocked: true}.

Named JavaScript methods reuse native gameplay tools and their argument objects. No open container is needed to start. World/player, travel bounds, read freshness, inventory, and safety requirements still apply. Use yield p.describe('craftRecipe') to obtain the running native schema, including required arguments. Optional integrations can be unavailable. describe accepts a JavaScript method name or native tool name.

## Named gameplay methods

- Movement: navigateTo, returnToSurface, followPlayer.
- Gathering/building: mineBlocks, ensureBlocksInInventory, collectResource, craftRecipe, placeBlock, useBlock, breakBlocks, tendCrops, startActionGoal.
- Inventory/entities: equipItem, eatFood, dropItems, givePlayer, attackEntity, useEntity, lureEntities, transferContainer, closeContainer.
- Cooking: smeltItems, collectSmeltedItems, checkSmeltables, inspectSmelting.
- Observations: queryWorld, inspectWorld, inspectInventory, inspectNearbyEntities, inspectContainer, checkCraftables, searchRecipes, findWorldFeatures, surveyCave, listActionCapabilities, readPolicyDocs.
- Configuration: configurePathfind, configureLighting, configureReflex.

Every named method accepts the corresponding native tool's argument object, for example yield p.navigateTo({x: 10, y: 64, z: 2, exactY: true}). They are ordinary named JavaScript functions returning effects; yield submits each effect and suspends the generator while Minecraft continues ticking. Calling without yield does not execute an action. There is no generic p.call API.

Named methods return {ok, tool, result, work?}. result preserves native JSON, or a string for text tools; it can include the original admission receipt. work, when present, is the final identified work summary. Admission alone does not resume the generator. ok reflects rejection or the child's terminal outcome. Check ok after each step, and branch/return explicitly on failure. SUCCEEDED for the policy only means the JavaScript returned normally. Native schema errors are also returned as ok=false. Legacy helpers below keep their original failure behavior.

Example: function* main(p,input) { const logs = yield p.mineBlocks({blockIds:input.blockIds,quantity:input.quantity}); if (!logs.ok) return logs; return yield p.craftRecipe({recipeId:input.recipeId,times:input.times}); }

Use exact recipe IDs and confirmation tokens from observations. Mining quantity, crafting times, smelting readiness, and collection retain native semantics. smeltItems completes insertion/startup, not cooking/collection; use inspectSmelting and collectSmeltedItems for those stages. followPlayer is continuous, so it only ends on failure/cancellation/deadline. Use bounded navigateTo for a sequence.

Policy children remain inspectable by work ID but are controlled through the root policy. Cancellation, timeout, death, world changes and reflex takeover stop active child execution. Completed effects and physical furnace cooking are not rolled back. No nested run_policy, planner-goal/delegation control, free-form chat, image/LLM calls or arbitrary host access.

## Verified container helpers

The following helpers require an already-open chest-like GenericContainerScreenHandler in singleplayer with an empty cursor. Use p.useBlock first to open it. They bind to that same window until an ordinary named tool is called; a later helper binds afresh. They read both sides on the server and verify transfers. These stronger confirmations are distinct from the ordinary transferContainer tool's native result.

## Container helper methods

- yield policy.observeContainer(): fresh server-observed {syncId, container, inventory}. Both count maps use full item registry IDs. Absent IDs mean zero. inventory covers the 36 storage/hotbar slots, excluding armor and offhand. Counts do not distinguish item components.
- yield policy.withdraw(syncId, items): items is a nonempty array of up to 36 {itemId, quantity} entries; quantities are integers from 1 to 2304. Copy syncId and IDs from observations. Aggregate each item ID into one entry. Check stock before withdrawing. Ordinary inventory capacity and transfer validation still apply. Returns the updated {syncId, container, inventory} only after both server-observed sides confirm the transfer. A subsequent observe is unnecessary unless something else changed.
- yield policy.closeContainer(syncId): returns {closed: true, syncId} after server-confirmed closure. Make this the final effect; further container effects cannot use a closed window.

Return a compact projection rather than entire snapshots when possible. Returning does not automatically close the window. In the restock example, insufficient stock returns without transfers and leaves it open. Even an observation-only run_policy occupies foreground work and yields the planner turn; it is not a general read-only world query tool.

## Debugging

Use inspect_work with the exact work ID. Details retain source, input, result, reason and yielded effects, including confirmed results. SUCCEEDED means normal JavaScript return, not necessarily goal achievement. An effect without a confirmed result may already have changed the world.

- SyntaxError, ReferenceError or TypeError: compare source against the generator signature and the complete method list above; fix the named expression. Do not invent helpers or access Java, Minecraft objects, require, fetch or the filesystem.
- policy_requires_singleplayer / policy_requires_open_container / cursor_not_empty: restore the required environment with ordinary tools before retrying.
- container/window changed, insufficient stock, inventory capacity or confirmation timeout: inspect current world/container state and fresh counts; recompute remaining work. Do not replay a fixed withdrawal blindly.
- policy_effect_limit / policy_tick_limit / guest execution limit: shorten the procedure; replace repeated reads with local computation. Maximum 128 yielded effects and 12000 client ticks; each container effect has 100 client ticks to confirm.
- Failed legacy container-helper effects stop the invocation; named methods instead return ok=false. They are not thrown into the generator, so try/catch around yield cannot recover them.
- CANCELLED: safety, death, world leave, reset or explicit cancel_work stopped the invocation. Transfers already committed remain; no rollback or automatic resume. Respect safety ownership before starting new work.

Other limits: 32768 source characters, 16384 characters per JSON value, 200000 guest statements per evaluation, 10-second initialization and 1-second resume deadlines. No host, network, process or filesystem access. This helper reads documentation bundled with the running build, not a local checkout or arbitrary file. If documented behavior still fails after inspecting evidence, report the source, input, work ID and terminal reason to the developer instead of repeatedly guessing.
