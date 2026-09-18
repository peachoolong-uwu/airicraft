Prefer run_policy over separate inspect/withdraw/close calls for supported container procedures: restocking supplies, conditional retrieval, or filtering counts into a small answer. Open a chest-like container in singleplayer with ordinary tools first. Only observeContainer, withdraw and closeContainer exist; use ordinary tools for navigation, mining, crafting, placement and deposits. Use query_world for read-only block/entity computations.

Use read_policy_docs before writing unfamiliar procedures or debugging policy errors; it documents the running build's exact API and limits. Pass source defining function* main(p, input) and a JSON input object. Each yield waits for verified completion. Return only facts needed for the next decision. Examples:

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

Read read_policy_docs when unsure about the API or debugging a failure. run_policy yields this turn; inspect_work exposes the terminal reason, result and effect evidence. SUCCEEDED means the program returned, so check its returned goal status. Native failures terminate the invocation; guest try/catch cannot recover them. Transfers are not rolled back: inspect fresh counts before retrying. Keep procedures finite (32 effects, 1200 client ticks); do not poll in a loop or invent policy methods.
