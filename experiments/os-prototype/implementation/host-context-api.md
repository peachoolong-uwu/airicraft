# Host-owned access contexts

The work service can retain one native access context across separately owned activities. Behavior code declares a bounded string key, for example `os.work("chest", { direction: "withdraw", itemId: "minecraft:string", quantity: 1 }, "container:home")`. The key must match the trusted rule and operation adapter. A guest cannot select a native context ID, change priority, or bypass resource admission. Automatic supply proposals inherit their operation's configured context.

The `ContainerTransfer` adapter binds `container:home` to an exact already-open window. `prepareContext(frame)` verifies that binding and requests native `retain_container`. Opening and navigation are still preparation outside this adapter. The experiment does not yet have a native pen context or mixed-duty run.

## Selection and boundaries

The scheduler selects work from fresh readiness, then enters its context if needed. Entry belongs to the OS, so cancelling the proposer does not discard setup that another root can use. After native confirmation, the host requires a newer observation and rechecks the operation grant, resources and target before admitting a child. Every child has its own journal entry, claim, subscribers and receipt. Its verified release completes its callers while the visit remains available.

The existing scheduling policy chooses each next operation independently, including overdue-root precedence and the reuse score. It requests exit when incompatible work wins, no compatible work remains, or eight operations or 1,200 advancing server ticks have elapsed. The admission capture checks those limits again. A completed native child is counted once even when its context receipt was inspected first. Exit retains the actor until the native context receipt proves closure and accounting. An owed outside-context turn follows the policy after closure; setup for that next work still needs to be feasible.

Recurring duties must reevaluate after entry and operation completion. The host preserves the ready visit while compatible retained declarations await that fresh evaluation; it never dispatches their old declaration. Available incompatible work and the visit budget retain precedence. Withdrawal or root failure removes that interest. Budget time comes from completed server ticks in the same native session and epoch, including entry time conservatively. Paused ticks do not advance it. Missing or regressed clocks fail the run rather than guess elapsed time.

## Durable ownership and failure

`EffectBroker.retainContext(key, prepare)` records the context intent before submission. The context shares the host lease and global native sequence with activities, but has a separate unfinished journal entry. A lost submission reply is inspected by exact ID; it is never replayed. `execute(prepare, { context: key })` verifies ownership against a fresh native authority response and injects the opaque native context ID after trusted preparation. Standalone or mismatched work cannot borrow a held visit.

`context()` returns the policy view: key, phase, completed operation count and elapsed ticks. `state().unresolved` remains true throughout the visit; `operationUnresolved` distinguishes the current child. Passive observations can refresh readiness at a ready context boundary, not during entry, exit or an active child. Fresh native evidence that a previously ready context is no longer ready triggers receipt reconciliation.

`closeContext()` sends identified cancellation. An uncertain cancellation reply propagates as an infrastructure failure; the coordinator revokes the lease and reconciles both obligations. Standalone broker callers must likewise treat this error as fatal and call `stop()`/drain. This covers a request lost before delivery as well as a reply lost after acceptance. Native cleanup may still be outstanding: failure, timeout or lease revocation never means the player is free. Context failure stops new dispatch, and cleanup polling continues. The containing runtime must stop/drain the whole run on a work-service fault, as with other infrastructure failures.

Shutdown revokes authority before waiting for outstanding transport and reconciles the child and context separately. Restart inspects every unfinished entry and acquires no replacement lease until all have settled. It closes/reconciles the previous visit; it does not resume old behavior or replay its effects.

## Qualification limits

The native material-availability source currently reports unavailable while a context is held. Shared work selection still uses fresh resource/readiness checks, but root waiting age does not gain that interval. This implementation does not infer eligible age from the visit clock. Context-aware native eligibility remains required before claiming fairness or efficiency for mixed duties.

Focused tests use real host modules and SQLite with a simulated native transport; supervised QuickJS duties exercise the guest declaration path. Those tests establish composition and ownership behavior, not Minecraft production throughput. Live qualification and review evidence are recorded separately in `host-context-review.md` and `STATUS.md`.
