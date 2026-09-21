# Planner inspection micro-compaction

`plannerSummarizeToolResults` remains enabled by default. Completed inspections automatically enter a background micro-compaction pass using the configured planner model. The planner does not call a summary tool: `record_finding` is no longer exposed or required.

Normal planning and FIFO execution continue with raw results while the pass runs. The micro-compactor receives the task context, original inspection arguments, and raw evidence (including images), without gameplay tools. It returns a task-specific result or explicit null plus checked coverage, negative evidence, failures and uncertainty. Multiple completed observations are coalesced into one pass. World, inventory, work, entity, recipe and other inspection families are eligible; gameplay action receipts are not.

Completed findings replace only their matching source result in future context, preserving the original call/result identity. A request already in flight keeps its immutable snapshot. Replacement records also prevent a later response based on that snapshot from restoring old raw results. Summaries are short-term context, not persistent memory; raw debug/flight recordings remain intact. Delegation evidence is updated separately, without pretending a tool executed.

Full compaction waits for pending micro-compaction to finish, so its immutable input contains the completed finding or the original evidence when micro-compaction failed. Successful full compaction starts a new context epoch and discards the replacement cache. If full compaction fails, the original context remains available. Reset and world changes cancel pending micro-work and clear the same state.

Micro-compaction failures retain raw evidence and do not consume planner repair attempts or degrade the planner. The same failed observation is not retried on every tick; ordinary full compaction can still absorb it. The existing configuration restriction to OpenAI-compatible planner history remains.

## Historical experiment

The construction results below measured the earlier, synchronous tool-driven design, not the current parallel implementation.

## Construction experiment — 2026-09-20

Tested with the configured `qwen3-8-27b` planner, single-role mode, in isolated automatic-playtest workers. The normal local config and user worlds were not changed. A disposable datapack constructed a stone-brick wall spanning x=-6..6, y=65..68, z=-4, removed `(3,65,-4)`, `(3,66,-4)`, `(4,65,-4)`, and supplied three stone bricks. The planner was not given the missing coordinates. A separate datapack predicate checked that all three positions became stone bricks.

The first run located and repaired all three positions, and then verified them through `query_world`. Actual subsequent provider requests contained findings in place of raw query results. For its three accepted findings, result-message character counts changed from 1,147 → 504, 761 → 835, and 628 → 492. A short raw result can grow when summarized; each finding also costs another model call. These measurements establish replacement, not a net token or latency win.

A second run retained an unsupported inference that air beyond the wall's eastern edge was additional damage. It was stopped and saved. This illustrates a risk of task-specific summarization: a mistaken interpretation can become the retained premise. The runtime validates structure and sequencing, not the truth of a model-written finding. Both initial runs also described an intact searched section with a non-null result despite the null-result instruction; the negative evidence was retained, but semantic use of null is model-dependent.

The final controlled run supplied the outer wall bounds, while leaving hole coordinates unknown. It found the three-cell L-shaped hole, placed exactly those three blocks, and queried all 52 wall cells to confirm every cell was stone bricks. The independent fixture predicate also passed. Its three raw results changed from 444 → 367, 601 → 400, and 482 → 427 characters (1,527 → 1,194 combined, about 22% smaller). Inspection of every later provider request confirmed each matching tool result remained replaced. This run also used a textual negative rather than literal null.

All three runs were stopped gracefully and their recordings finalized. `COMPLETED` in a recording summary means capture finalized, not that the construction task passed.

| Run | Construction outcome | Local recording summary |
| --- | --- | --- |
| Initial, unknown outer bounds | Repaired and verified the three missing positions | [Summary](../run/planner-findings-experiment/output/20260920-203504-693337-81960-c4a895ea-0a9f-4420-8433-a6212d9e6593/summary.json) |
| Repeat, unknown outer bounds | Stopped after unsupported wall-boundary inference | [Summary](../run/planner-findings-experiment/output/20260920-203849-574674-82968-fa572e77-4390-4e80-8daf-024ac25da346/summary.json) |
| Known outer bounds, unknown hole coordinates | Repaired; all 52 cells verified | [Summary](../run/planner-findings-experiment/output/20260920-204026-054770-83448-3a2b17dd-dfef-4085-9910-b5edf48a14c8/summary.json) |

Each summary locates the finalized Artifact V1 Play. Its `extensions/airicraft.playtest/llm-calls.jsonl.gz` contains actual provider requests and responses. Local `*-analysis.json` files beside the experiment launcher record the replacement measurements and findings.

Local experiment launcher and full recordings are under `run/planner-findings-experiment/` (ignored experimental artifacts).

## Automated validation

Regression coverage includes concurrent planning with raw evidence, positive/null replacement on later requests, immutable in-flight snapshots, source/version matching, invalid summaries retaining raw evidence, multiple inspection families, delegation replacement, and both completion orders with full compaction.
