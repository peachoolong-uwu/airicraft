# Behavior composition: evidence and walkthrough

Companion to the accepted execution contract in [Define behavior instances and compositional execution](https://github.com/shinohara-rin/airicraft/issues/53). The user selected owned children and cancellation of siblings on parallel failure on 2026-09-15. The ticket's resolution is the canonical decision; this note preserves its evidence and concrete examples. Implementation remains separate from the design decision.

## Design problem

A farming behavior can remain understandable as “obtain supplies, plant, wait, harvest, repeat” while other duties act during its waits. Independent duties also need to reuse physical setup: shearing and breeding within one pen visit, or output storage and restocking within one chest visit. That requires distinct ownership for child invocations and shared physical service, so the author does not have to schedule every visit by hand.

Sheep escape recovery remains a separate gameplay scope. It is not a prerequisite for resolving this execution contract.

## Evidence and the missing layer

Source references below describe the `os-exp` working copy reviewed on 2026-09-15. The homestead extensions and their tests were present locally and remained uncommitted when this decision was recorded. This note publishes the evidence summary; its source paths identify that working copy.

The current prototype (`experiments/os-prototype/README.md`) already supports independent definitions contributing work to an OS-owned visit. The chest trial served five requests from three definitions with one open and close: three verified transfers and two unavailable-stock deferrals. A later pen visit served shearing, feeding, and wool collection. This demonstrates shared setup and handoffs; it does not establish sustained production efficiency.

The current runtime installs a flat list of instances. It has no child invocation, join, per-subtree cancellation, or retained behavior return value. Generator completion discards the returned value; `lastResult` is an activity result. Its twelve-instance limit counts all installations until host shutdown. These are gaps to address when implementing the execution contract, not reasons to prescribe the final language or sandbox. See installation and lifecycle (`experiments/os-prototype/src/runtime.mjs`), guest effects (`experiments/os-prototype/src/sandbox.mjs`), and work identity (`experiments/os-prototype/src/work.mjs`).

Native graph/job parent IDs already describe physical execution relationships. They are not behavior invocation parents. The new relationship must not overload that existing meaning. See native work projection (`src/client/java/ai/moeru/airicraft/agent/work/WorkProjection.java`).

### Coverage checked against the execution contract

This table comes from reading the current test assertions and retained live audits, not from assuming that a green suite covers the agreed contract. No new test run or gameplay run was needed for this source review.

| Requirement | Existing evidence | Remaining gap |
| --- | --- | --- |
| Growth waits leave useful work schedulable | Runtime test (`experiments/os-prototype/test/runtime.test.mjs`), `crop waits release the player`: repeated harvests and catches, one maximum owner, and crops selected at contested cast boundaries. | No parent awaiting a child; no worker/resource wait composition. |
| Independent definitions reuse setup | Context tests (`experiments/os-prototype/test/contexts.test.mjs`), `independent shearing and breeding share one visit`, and chest tests (`experiments/os-prototype/test/chest.test.mjs`), `three independent definitions restock farms and sheep and deposit output in one chest visit`: one entry/exit and work from multiple subscribers. | No nested invocation ownership. Shared context is proven separately from that missing layer. |
| Duplicate requests share one physical attempt | Context test `duplicate requests execute once and deliver an outcome to both subscribers`: one shear, two activity results. Chest test `consumer floors add; duplicate goals share one withdrawal`: one seven-item withdrawal for two configured reserves and two duplicate goals. | Subscriber cancellation, joining an active attempt, and distinct child return values are untested and unsupported. |
| A fault in an independent duty does not stop healthy duties | Runtime test `a failed guest cannot acquire another instance capabilities or block a healthy instance`: an invalid plot capability fails one root while fishing dispatches. | This proves root isolation, not child failure handling inside one concurrent group. |
| Unavailable stock can defer without monopolizing access | Chest test `an empty source defers without ending the visit or preventing another restock`: three item deferrals, one useful transfer, and no repeated reopen during the observed backoff. | No general resource wait handle or parent-owned demand lifetime. |
| Shutdown reconciles a submitted transfer | Chest test `stop waits for submitted transfer verification and closes the retained screen`: driver stop waits on a controlled verification barrier, then closes once. | This is whole-driver shutdown with a fake native transport. It does not exercise cancelling one subscriber or one child subtree. |
| Failed cleanup cannot authorize another physical owner | Context test `cleanup failure retains ownership and forbids further dispatch`: failed leave retains the owner and prevents fishing; stop rejects. | Per-instance terminal reporting needs revision: runtime shutdown currently marks nonterminal instances `STOPPED` in `finally`, even when cleanup rejects. That phase therefore cannot establish physical release. |
| Context sharing and interruption also work in the real fixture | Retained `chest-live-01` audit: five chest operations, three verified transfers, two deferrals, one open/close, no unresolved recorded ownership. `chest-stop-01`: one open/close and no submitted transfer. See trial details (`experiments/os-prototype/TRIAL.md`). | Live evidence does not cover child composition, shared-subscriber cancellation, cancellation after transfer submission, or sustained production efficiency. |

The lifecycle contract must therefore distinguish a guest that can no longer run from physical obligations that remain unresolved. Killing or disposing a sandbox may stop computation immediately; it must not manufacture an invocation outcome claiming that its owned effects have settled. The current shutdown path is an implementation gap to carry into the eventual lifecycle work, not a reason to resume unrelated gameplay repairs.

## Walkthrough cases

These examples apply the agreed defaults. They are paper walkthroughs, not newly executed tests.

| Case | Expected distinction |
| --- | --- |
| Wheat and potato duties each call one reusable stock-then-plant skill | Different invocation inputs, state, and outcomes; both can wait without excluding sheep, birch, or fishing. |
| Shearing and breeding become eligible together | Separate behaviors request separate operations. The OS can retain one pen visit without either behavior calling the other. |
| Two parents need the same carried wheat reserve; one is cancelled | Separate invocation ownership; shared supply service remains while the second parent needs it. Duplicate interests do not automatically double material demand. |
| A third supply request arrives during the transfer | It cannot inherit success from argument equality alone. Current demand and the attempt's applicability must be checked. |
| A finite parent's body returns while a child waits for growth | The parent stays closing and its join remains pending until the child settles or is explicitly cancelled. |
| One parallel child fails while another is fishing | The default group cancels the fishing child and waits for hook cleanup. An explicit collect-all group lets that child finish. Unrelated installed duties remain live. |
| The last owner stops during chest entry, transfer verification, or exit | Stopping remains distinct from stopped; no competing actuation is admitted based only on receipt of the stop request. |
| More than twelve sequential calls complete | Live capacity is reclaimed after each child; retained join outcomes do not keep every old sandbox alive. |
| A cancelled invocation receives a late worker result | The result may be recorded as late evidence but cannot resume code or authorize new work for that invocation. |

## Downstream decisions

These decisions retain their existing scope and are not resolved by the composition contract.

- [Define authoritative observations, events, and waits](https://github.com/shinohara-rin/airicraft/issues/56).
- [Define resource claims and shared demands](https://github.com/shinohara-rin/airicraft/issues/57).
- [Define player scheduling, activity size, and interruption](https://github.com/shinohara-rin/airicraft/issues/58).
- [Define callable LLM workers and their execution budgets](https://github.com/shinohara-rin/airicraft/issues/59).
- [Define restart, recovery, and partial-effect semantics](https://github.com/shinohara-rin/airicraft/issues/60).
- [Choose the language, sandbox, and execution host](https://github.com/shinohara-rin/airicraft/issues/61).
- [Define the native action interface and executor integration](https://github.com/shinohara-rin/airicraft/issues/62).
- [Define skill and worker library versions, installation, and replacement](https://github.com/shinohara-rin/airicraft/issues/63).
