Prefer run_policy for dependent gameplay sequences instead of spending a planner turn on each action: navigate, gather, craft, place, interact, equip, eat, and manage containers. Yield named JavaScript functions such as p.navigateTo(args), p.mineBlocks(args), p.craftRecipe(args), p.placeBlock(args), and p.queryWorld(args). Their argument objects match the corresponding native tools. Use standalone query_world for a single custom read. No open container is required unless using a container helper.

Use read_policy_docs before writing unfamiliar procedures or debugging policy errors; it documents the running build's exact API and limits. Pass source defining function* main(p, input) and a JSON input object. Each yield waits for verified completion. Return only facts needed for the next decision. Named tool methods return {ok, tool, result, work?}. Each waits for its own work to finish; check ok and branch or return on failure. result preserves the native JSON or text; work is the final work summary when available. p.describe('craftRecipe') yields the exact native schema without executing it. Examples:

Restock several carried items to target counts; input={"stock":{"minecraft:bread":8,"minecraft:torch":16}}:
```js
function* main(p, input) {
  let s = yield p.observeContainer();
  const items = Object.entries(input.stock).map(([itemId, target]) =>
    ({itemId, quantity: Math.max(0, target - (s.inventory[itemId] || 0))})).filter(i => i.quantity > 0);
  const missing = items.filter(i => i.quantity > (s.container[i.itemId] || 0));
  if (missing.length) return {restocked: false, missing};
  if (items.length) s = yield p.withdraw(s.syncId, items);
  yield p.closeContainer(s.syncId);
  return {restocked: true, transferred: items};
}
```

Take up to four spare torches while leaving eight in storage; input={}:
```js
function* main(p) {
  const s = yield p.observeContainer();
  const quantity = Math.min(4, Math.max(0, (s.container['minecraft:torch'] || 0) - 8));
  if (quantity) yield p.withdraw(s.syncId, [{itemId: 'minecraft:torch', quantity}]);
  yield p.closeContainer(s.syncId);
  return {taken: quantity};
}
```

Inspect selected carried/stored counts, leaving the window open; input={"ids":["minecraft:coal","minecraft:iron_ingot"]}:
```js
function* main(p, input) {
  const s = yield p.observeContainer();
  return input.ids.map(itemId => ({itemId, carried: s.inventory[itemId] || 0, stored: s.container[itemId] || 0}));
}
```

Read read_policy_docs when unsure about the API or debugging a failure. run_policy yields this turn; inspect_work exposes the terminal reason, result and effect evidence. SUCCEEDED means the program returned, so check its returned goal status. Named tool failures return ok=false; legacy container-helper failures terminate the invocation. Transfers are not rolled back: inspect fresh counts before retrying. Keep procedures finite (128 effects, 12000 client ticks); do not poll in a loop or invent policy methods.

Gather then craft from an observed recipe; input contains blockIds, quantity, recipeId and times:
```js
function* main(p, input) {
  const gathered = yield p.mineBlocks({blockIds: input.blockIds, quantity: input.quantity});
  if (!gathered.ok) return {stage: 'gather', failure: gathered};
  const crafted = yield p.craftRecipe({recipeId: input.recipeId, times: input.times});
  return {crafted: crafted.ok, outcome: crafted};
}
```

Observe a chosen placement cell, then place with ordinary freshness and support checks; input={position:{x,y,z},itemId}:
```js
function* main(p, input) {
  const observed = yield p.queryWorld({center: input.position, radius: 1, verticalRadius: 1,
    includeEntities: false, source: 'function query(w) { return w.blocks.filter(b => b.air).map(b => b.position); }', input: {}});
  if (!observed.ok) return observed;
  return yield p.placeBlock({...input.position, itemId: input.itemId, facePreference: 'auto'});
}
```

Safety cancels the procedure and its active child; inspect fresh state before retrying. Native methods preserve existing semantics: smeltItems starts cooking, so inspect and collect output separately. followPlayer is continuous and runs until cancellation or the policy deadline. These are bounded procedures, not persistent background programs.

While a run_policy executes, the harness may prepare one guarded next policy in an isolated planner request. The current policy keeps acting while that request thinks. Write complete bounded sequences for known dependent steps, and return a small deterministic JSON success object (for example {gathered:true}) after verifying all required effects. On failure, return a distinct object such as {gathered:false,stage:'mine'}. Avoid embedding unpredictable native receipts in successful return values when a small verified outcome suffices; effect evidence remains available through inspect_work. This lets the next request predict a verifiable handoff. Do not split a short known sequence just to trigger speculation.

Prepared work starts only after the parent's actual result and the proposed inventory/block prerequisites match. Changed user guidance, goal, world, safety, health loss, failure, or mismatched prerequisites discard it; late proposals never delay normal planning. A policy.continuation.accepted event records the actual admitted source/input and receipt. Treat discarded/ready proposals as unexecuted. If planning resumes after a miss, inspect current work before repeating actions. Do not keep the player busy with unrelated actions solely to hide model latency.
