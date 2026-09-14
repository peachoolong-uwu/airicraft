# System 2 evidence, work, and decisions

Status: accepted; implemented, automated and assisted live contract validation completed (2026-09-14). See docs/system2-refactor-validation.md for the setup death, controlled-cheat conditions, and remaining planner/navigation limitations.

## Context

Live qwen controller play exposed task outcomes delayed across tool calls, graph-only
inspection used for direct jobs, tool-budget exhaustion mistaken for objective failure,
reflexes starving supervision, and acquisition search bounds acting as travel limits.
Evidence: `run/playtest/2026-09-13/reflex-policy-live-validation.jsonl` is an untruncated
export. The wood job failed at agent tick1995. Five later requests over roughly19seconds
lacked that job's outcome. The observation labelled tick1996 was dispatched before the
failure; recorder collection time must not be mistaken for request dispatch time.

## Decision

Game-owned observations and work outcomes exist independently of requests to wake a
model. Each model role incorporates identified evidence into its own append-only
conversation before a gameplay decision. Transport retries reuse the request. A new
job or human instruction may supersede a wakeup, never the effects of earlier work.

Expose one work lifecycle over existing direct jobs, graph executions and background
processes. Keep their executors and single foreground actuator boundary. System 2 may
choose high-level or precise actions without graph-first fallback requirements.

The controller owns the overall world-persisted objective. The thinker owns a bounded
delegation and may return its outcome, not finish the overall objective. Objectives
have an explicit blocked state distinct from failed attempts, yielded turns and terminal
outcomes. Constraints and named decisions are stored separately from observations.

Decision ownership is separate from actuator ownership. Reflexes gate physical actions
but allow bounded, event-driven inspection and policy changes by the active model role.
Do not add a continuous position controller or change model/effort settings.

Target/search constraints select resources. Explicit travel restrictions govern paths
and edits. Report failed predicates and actual positions; use bounded local geometry
queries for support, clearance and interaction feasibility.

Freeze cleaned-up typed tools independently for each role. Discovery is catalog help,
not dynamic schema activation. Retain separate prefix-stable histories. Preserve bounded
recording and explicit overflow gaps; do not introduce unbounded event sourcing.

## Ownership and limits

| Owner | Authoritative responsibility |
| --- | --- |
| Minecraft runtime and semantic event buffer | Physical observations, executor transitions, bounded event identities and gaps. |
| Work projection/history | Common identity, requested parameters, current state and retained outcomes over existing executors; no scheduling authority. |
| Controller and objective store | Overall objective, constraints, criteria, blockers and named decisions. |
| Thinker and delegation | Bounded assignment, private reasoning/history, claimed return outcome and shared evidence references. |
| Survival reflex | Temporary actuator ownership; does not own or silence System2 decisions. |
| Per-role orchestrator | Fresh decision boundary, incorporated cursor, frozen transport attempts, stale response rejection and fixed schema prefix. |

Work history retains128 terminal entries plus unresolved work; each decision includes unresolved work and the latest8 terminal projections. Shared semantic evidence is bounded512events, with explicit gaps. Goal notes are bounded16names and replaced goals32entries. The recorder remains a separate12000server-tick/64MiB diagnostic window.

## Implementation and acceptance checklist

- [x] Evidence: fresh decision context at initial, follow-up, continuation and handoff boundaries;
  per-role cursors; retained outcomes; retry/compaction/overflow/world-change coverage;
  distinguish dispatch/context/recording clocks.
- [x] Work: stable handles and receipts; inspect/list/cancel/resume/wait; graph children;
  background furnace semantics; legacy CLI adapters; authoritative terminal evidence.
- [x] Objectives: migrate existing persisted goals; blocked/resume; controller-only authority;
  scoped decisions, constraints and criteria; relevant-event reassessment without idle loops.
- [x] Delegation: structured assignment and fresh evidence; identified observed effects and
  final state separate from claimed outcome; preserve both role histories.
- [x] Reflex supervision: read/cancel/policy during reflex; no competing actuation; bounded
  wakes; observed release and explicit resume through the current work/hold identity.
- [x] Constraints and spatial queries: search/travel separation; full movement restrictions;
  forced-displacement reporting; failed predicates; bounded support/clearance/reach/LOS queries.
- [x] Tool surface/prompts: unified lifecycle; free choice of action detail; fixed typed schemas;
  catalog-only discovery; preserve actual capabilities, evidence checks and user constraints.
- [x] Full build after each integrated subsystem; focused regressions; actual configured planner
  resource gathering, shelter repair, chest/furnace interaction and reflex-interruption trials.
  Measure redundant reads, outcome latency, repeated failures and time to productive action.

Keep the existing game paused during implementation. Export before rebuilding; use compatible
HotSwap/config reload where possible, restart cleanly for structural changes. Never silently
reactivate a finished goal. Commit logical stages and record live limitations explicitly.

Verification details, measured evidence latency, provider failures and the remaining live acceptance are in [the validation report](../system2-refactor-validation.md). Checked implementation entries do not imply all live trials passed.
