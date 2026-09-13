# System 2 controller and delegated planner

Implemented as an opt-in `thinkingPlanner` profile for the OpenAI-compatible backend. The controller uses `reasoning_effort: none`; the delegated planner defaults to `medium` and may use a separately configured model.

The main controller uses a fixed non-thinking model profile. Ordinary gathering, crafting, furnace operation, inventory inspection and navigation to known locations stay with it. Delegate substantial spatial reasoning or planning across interacting constraints: shelter design, farm siting/layout, and inventory/storage organisation. Several tool calls or a single failed action do not by themselves justify thinking. Explicit operator delegation requests remain supported for diagnostics. It can delegate a bounded complex task to a separate thinking planner, which takes over the same gameplay tools until it returns control. Each role owns a separate model instance, history, compaction state, fixed system prompt/tool schema and cache identity. Effort is not switched within one history.

`delegate_task` stays pending in the controller while the thinking planner owns decisions. Incoming world/task observations and user guidance go to the current owner. System 1, safety gates and the action runtime remain shared. There must never be two concurrent gameplay decision owners. Thinking pauses or plain replies do not finish the delegation; an idle delegated task must continue without human prompting.

On entry, append the delegated task, completion conditions, relevant controller context and fresh world/task facts to the thinking history. On return, complete the controller's pending tool with the planner-reported outcome plus an independently captured record of executed tool calls, results, failures, observed events and final state. Preserve provenance: a planner success claim is not a verified action result. Bounded excerpts must report truncation. Old reasoning text need not be copied between roles.

The thinking planner explicitly returns with success or give_up. It cannot recursively delegate. Returning normally requires no running gameplay work; provider failures return a failure report to the controller for recovery. Safety/world/reset/disabled transitions must clean up both sessions and reject stale transfers. Switching roles preserves both histories; switching world or resetting runtime clears both.

Stable prefixes also require moving changing goal data out of system instructions and preventing tool discovery from rebuilding the role's prefix. Goal/world updates are appended as context. Context compaction deliberately starts a new prefix when necessary; cache retention/hits still depend on the provider.

Verification gates:

- Controller request effort is always none; thinking request effort stays at its own configured value.
- Tool ownership transfers controller → thinking → controller, with no speculative/duplicate actions.
- Second delegation extends the thinking history and resumes the unchanged controller prefix.
- Return context includes actual tool evidence and final facts, not only a self-written summary.
- Task updates, plaintext yields, hazards, failures, resets and late completions obey ownership.
- Real configured Qwen delegates, performs a bounded world task, returns, and controller uses the reported result.
- Recorder and debug views identify the active role and preserve both sessions' evidence.

## Configuration

```yaml
thinkingPlanner:
  enabled: true
  model: "" # empty reuses the main model
  reasoningEffort: "medium"
```

Reloading config rebuilds both sessions. Normal handoffs preserve them. Each role sends a stable, distinct `prompt_cache_key`; its tool schemas and system prompt remain fixed until reload. All tools available when the role is created are advertised; discovery remains available for descriptions. Dynamic provider availability may still reject execution. Compaction starts a new prefix deliberately.

The copied controller context contains bounded recent visible messages and tool exchanges, with image-presence markers rather than image bytes or private reasoning. The return report distinguishes `plannerReportedOutcome`, `observedEvidence`, `omittedEvidenceEntries`, and `finalFacts`. Evidence is capped at 96,000 characters; long tool results and copied context explicitly mark truncation. User guidance during delegation is included in the return report.

Automated verification: full `./gradlew build` passed, including two real orchestrators using deterministic model responses for repeated delegation, plaintext continuation, tool ownership, incoming task context, preserved prefixes, safety-epoch changes, idle-only return, stale return rejection and reset. Local HTTP tests verify independent fixed effort/cache-key pairs. Shared recording sequences and globally unique live generation IDs avoid collisions between roles.

Live validation is recorded in `docs/autonomous-playtest-log.md`. The initial structural HotSwap attempt failed because a generated enum-switch class changed identity; the old recorder then lacked its newly typed sequence field. The incident had already been exported. This particular migration required restarting the client; ordinary config changes and compatible method changes remain reloadable.
