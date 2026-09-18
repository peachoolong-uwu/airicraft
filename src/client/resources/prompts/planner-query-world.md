Prefer query_world for custom block/entity filtering, counts or projections that would otherwise take several inspection calls or return unnecessary fields. It runs function query(world, input) over one fresh, bounded client snapshot and returns your JSON plus host-owned coverage metadata. No actions, yield, Java access or further reads. Use inspect_world for specialized placement/interaction checks and inspect_nearby_entities for richer entity facts absent from this snapshot. Continue using survey_cave to find visible ore; a loaded-block snapshot does not prove visibility or reachability.

Snapshot registry IDs retain their namespace: compare blockId to "minecraft:torch", not "torch". This also applies to entity type and fluid IDs; compact observation summaries use a different format. Before interpreting an unexpected zero count, check the filter against a few actual IDs.

Set radius (horizontal, 0..8, default 4), verticalRadius (0..4, default 2), and optionally center={x,y,z}. Set includeBlocks=false for entity-only queries or includeEntities=false for block-only queries. Examples of source (all use input={}):

Find nearby doors and return only their coordinates and open state:
```js
function query(world) {
  return world.blocks.filter(b => b.blockId.endsWith('_door'))
    .map(b => ({position: b.position, open: b.properties.open, half: b.properties.half}));
}
```

Summarize hostile mobs in the box (includeBlocks=false):
```js
function query(world) {
  const mobs = world.entities.filter(e => e.hostile && e.alive);
  return {count: mobs.length, nearest: mobs.slice(0, 3).map(e => ({uuid: e.uuid, type: e.type, distance: e.distance}))};
}
```

Find dark, empty cells at the player's feet level (includeEntities=false); these are lighting observations, not validated standing/placement sites:
```js
function query(world) {
  const y = Math.floor(world.player.position.y);
  return world.blocks.filter(b => b.position.y === y && b.air && b.light < 8).map(b => b.position);
}
```

Check metadata bounds, unloaded/outside-world cells and entity truncation before interpreting absence. Results may become stale while the world advances. Keep returns small (16384 JSON characters). Read read_policy_docs for exact fields and limits; correct failures from their error, rather than guessing APIs.
