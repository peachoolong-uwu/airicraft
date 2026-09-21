# Nether playtest: furnace inspection report

Run `20260920-183505-182900-54813-3ca256c3-143d-4823-a3d8-1e906d730faf` reported that furnace use briefly opened and then closed the GUI, making `inspect_container` fail. Handoff `1ebd2a40-e9f6-4123-a4f7-ea998acdb0d9` was processed once. Fresh bridge inspection confirmed client tick 5594 and server tick 5507 remained paused.

The claimed GUI closure is disproven by recorded runtime snapshots: ticks 4857, 5059, 5259, 5461 and 5593 all report `class_3873`, mapped to `FurnaceScreen`. Furnace `use_block` work completed successfully at ticks 4942, 5014 and 5391, with matching `interaction.container_observed` events. `closing_open_screen` is the next interaction's preparation step, not proof that the previous successful interaction failed to keep its GUI open.

`ContainerInventoryController.requireContainer` accepts only `GenericContainerScreenHandler`. Its `container_not_open` error also covers unsupported screens such as furnaces. The advertised `inspect_container` tool explicitly lists chest, barrel, chest minecart and chest boat. The planner incorrectly applied this storage interface to a furnace despite dedicated smelting tools. No furnace-placement or furnace-opening fix is warranted by this evidence.

Added general system-prompt guidance distinguishing chest-style storage from furnace screens and directing furnace workflows through `check_smeltables`, `smelt_items`, `inspect_smelting`, and `collect_smelted_items`. No gameplay implementation was changed.

The helper was stopped gracefully before building. Final recording is `REPORTED`, `recordingComplete: true`. Published Play:

`automatic_playtest/v1/airicraft-evaluation--58cdf97d-ed55-4661-98ac-d78545094d46/players/AiricraftTest--d0a06f8c-4222-3e72-988a-8e0924bde20d/plays/20260920T103526.922Z--ecd435c8-7f2f-4098-bbba-325c813b7a98`

The extension manifest confirms pause and checkpoint server tick 5507, checkpoint world time 256528, captured while paused. Its immutable `world-save.zip` was extracted into `run/nether-furnace-resume-20260920`; `level.dat` exists. The resumed run retains the Nether objective, recording profile and 1800-second budget.

Validation: `./gradlew build` passed. Prompt correction committed as `ca4403b2`. Resumed run `20260920-184214-546720-57128-530881f8-b675-4e64-9e0d-5ef8d01335ca` joined the world, started recording, received the Nether objective and the updated system prompt, and began `query_world` / `read_logbook` calls. This verifies prompt delivery and resumed planning, not yet successful smelting or Nether entry. Duplicate delivery of the original handoff must not launch another client.

## Resumed run outcome

Exit handoff `27fb69b6-79de-4974-b904-79018f66354f` processed in analyze-and-wait mode. The 1800-second budget expired normally. Bridge shutdown returned 0, the Minecraft and helper processes exited, and the recording finalized as `COMPLETED`, `recordingComplete: true`, with no bug report or degraded planner. This is recording completion, not objective completion: the player remained in the Overworld.

The corrected furnace workflow has live proof: smelting started at tick 1532 and output collection completed at 3637, followed by bucket crafting at 3805. A second smelting/collection cycle completed at 25062/27094. Automatic lighting placed torches at ticks 18016 and 21106.

Six recorded deaths interrupted progress: skeleton at 6187, spider at 10973, and zombies at 28501, 28849, 29139 and 30477. Each fatal damage event occurred with the survival reflex ACTIVE. Repeated deaths near camp and recovery are a substantial observed setback, but these events alone do not establish the underlying combat defect. Next investigation should correlate reflex decisions, attack/reposition progress, equipment and escape routes around those episodes before selecting a fix.

Final recorded snapshot: tick 35309, Overworld, position approximately (119.6,109,163.4), health 15.33/20, food 12. Inventory includes bucket, flint-and-steel, two iron ingots, stone pickaxe, five coal and two torches. A navigation job toward (214,64,243), non-exact Y, remained RUNNING. Four speculative continuations started; two became ready and all four were discarded, with none accepted. No live reduction in transition idle time is established by this run.

Final Play:

`automatic_playtest/v1/airicraft-evaluation--4bddc3d1-a366-4571-ba8d-6090508dbba6/players/AiricraftTest--d0a06f8c-4222-3e72-988a-8e0924bde20d/plays/20260920T104239.914Z--848fc432-7636-4226-9a6c-c17604e0bb3d`

`extensions/airicraft.playtest/world-save.zip` contains `level.dat`. This checkpoint was copied after graceful exit, not captured while paused. Evidence is retained in `events.jsonl.gz`, `live-recording.jsonl.gz`, `flight-final.json.gz`, and `playtest.json`. No gameplay fix or restart was performed for this exit; awaiting user direction per the playtest-loop skill.
