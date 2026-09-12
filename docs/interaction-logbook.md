# Storage and interaction history

Open an observed chest or barrel with `use_block`, then call `inspect_container`.
Copy its `syncId` into `transfer_container` with `direction` (`deposit` or `withdraw`), exact `itemId` and `quantity`. The whole request is checked for supply and space before any clicks. Equipped armor and offhand are excluded. Double chests use the same interface. Read the container again to verify settled counts, then `close_container` before other work. Transfers report submission, not server-confirmed completion.

The world automatically keeps significant server-observed interactions in `airicraft/interactions.jsonl`: crafting output, actual drops, chest/barrel/furnace transfers, and observed container contents. Ordinary player clicks and agent actions use the same observation hooks. Same-tick changes are combined and files are written off the game thread. No planner tool can author, edit or delete entries. This persistent logbook is separate from the rolling flight recorder.

`read_logbook` returns the latest matching entries in chronological order:

```json
{"place":"shore shelter","itemId":"minecraft:iron_ingot","limit":20}
```

Optional filters: exact `place` bookmark (with `radius`, default 16), exact `itemId`, `action` (`crafted`, `dropped`, `container_put`, `container_take`, `container_observed`), and `limit` 1–100. Entries carry world time, actor, dimension and interaction coordinates. A container observation describes its contents at that time; other actors, hoppers, smelting and later unobserved changes can invalidate it. Reopen a container to confirm current stock. No pre-installation history is fabricated. Persistence/querying currently requires a locally hosted world save.
