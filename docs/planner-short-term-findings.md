# Experimental short-term query findings

Enable in `agent.yml` and reload the agent:

```yaml
plannerSummarizeToolResults: true
```

Default: `true` for `openai-compatible`; set `false` to opt out. Requires the `openai-compatible` backend, whose conversation Airicraft owns. The Codex app-server backend owns its history, defaults this switch off, and rejects an explicit opt-in. The switch is preserved for both planner roles.

The experiment applies to `inspect_world`, `query_world`, `inspect_nearby_entities`, `find_world_features`, and `custom_` world queries. Other results, including images and action receipts, retain their existing behavior. Tool calls become sequential while enabled.

After an eligible result, the only permitted next response is one `record_finding` call:

```json
{
  "sourceToolCallId": "the-original-query-call-id",
  "result": null,
  "memory": "Searched x=-6..0, y=65..68, z=-4 for missing wall blocks. None found; all stone bricks. Search the eastern half next."
}
```

`result` is a task-specific answer or explicit `null` for no target found. `memory` must retain relevant exact positions/materials, searched coverage, failures and uncertainty. The planner is instructed to summarize the question motivating its query, rather than its general surroundings. Memory text is required even when the result is null.

The orchestrator validates the source ID and argument shape, rejects other tools or final replies while an observation is pending, and replaces that observation's tool message only after accepting the finding. The original query/result pairing stays valid. The finding call and receipt are removed from retained context to avoid keeping duplicate summaries. Invalid findings preserve the raw result for correction. Reset clears these findings with normal conversation history; normal context compaction still applies.

There is no new persistent memory store, retrieval system or cross-session recall. Existing debug/flight recordings retain raw results for diagnosis. Delegation reports replace matching raw observations when their findings arrive, so returning control does not reintroduce the discarded output.

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

`source .envrc && ./gradlew build`: 1,456 root tests, 2 skipped; 95 wrapper tests; no failures. Tests cover positive/null replacement across subsequent turns, source validation, required negative-evidence text, sequential gating, failed receipts preserving raw evidence, opt-in config, role propagation, unsupported backend rejection, and delegation evidence replacement.
