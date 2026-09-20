# Nether playtest: furnace inspection report

Run `20260920-183505-182900-54813-3ca256c3-143d-4823-a3d8-1e906d730faf` reported that furnace use briefly opened and then closed the GUI, making `inspect_container` fail. Handoff `1ebd2a40-e9f6-4123-a4f7-ea998acdb0d9` was processed once. Fresh bridge inspection confirmed client tick 5594 and server tick 5507 remained paused.

The claimed GUI closure is disproven by recorded runtime snapshots: ticks 4857, 5059, 5259, 5461 and 5593 all report `class_3873`, mapped to `FurnaceScreen`. Furnace `use_block` work completed successfully at ticks 4942, 5014 and 5391, with matching `interaction.container_observed` events. `closing_open_screen` is the next interaction's preparation step, not proof that the previous successful interaction failed to keep its GUI open.

`ContainerInventoryController.requireContainer` accepts only `GenericContainerScreenHandler`. Its `container_not_open` error also covers unsupported screens such as furnaces. The advertised `inspect_container` tool explicitly lists chest, barrel, chest minecart and chest boat. The planner incorrectly applied this storage interface to a furnace despite dedicated smelting tools. No furnace-placement or furnace-opening fix is warranted by this evidence.

Added general system-prompt guidance distinguishing chest-style storage from furnace screens and directing furnace workflows through `check_smeltables`, `smelt_items`, `inspect_smelting`, and `collect_smelted_items`. No gameplay implementation was changed.

The helper was stopped gracefully before building. Final recording is `REPORTED`, `recordingComplete: true`. Published Play:

`automatic_playtest/v1/airicraft-evaluation--58cdf97d-ed55-4661-98ac-d78545094d46/players/AiricraftTest--d0a06f8c-4222-3e72-988a-8e0924bde20d/plays/20260920T103526.922Z--ecd435c8-7f2f-4098-bbba-325c813b7a98`

The extension manifest confirms pause and checkpoint server tick 5507, checkpoint world time 256528, captured while paused. Its immutable `world-save.zip` was extracted into `run/nether-furnace-resume-20260920`; `level.dat` exists. The resumed run retains the Nether objective, recording profile and 1800-second budget.
