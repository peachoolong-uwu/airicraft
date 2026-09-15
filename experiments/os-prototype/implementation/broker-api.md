# Broker foundation

The host modules in `src/os/` implement the ownership, stock and native-effect seams from the [handoff](../design/implementation-handoff.md). They are not yet the prototype's default `src/main.mjs` entrypoint. The [implementation status](STATUS.md) tracks the connected runner, resource and scheduling layers and the remaining native duties and evaluation work.

## Ownership and stock

`InvocationBroker.install` creates a root with an immutable definition identity and operation grants. `spawn` creates an owned child with attenuated grants. `returned` closes the body; an outcome appears only after children and physical obligations settle. `join` consumes a child handle once. Normal close consumes remaining child results; `collect_all` is explicit, while the default child-failure policy cancels siblings. Cancellation can withdraw one shared subscriber without cancelling useful work for another. The last subscriber retains the cleanup obligation.

`ResourceLedger.observe` replaces a scoped, versioned stock/asset/capacity/target view; omitted or null stock is unknown. `target` declares a consumer's maintained floor. `reserve` admits a complete activity bundle atomically against an exact revision. A consumer can spend its own protected stock and surplus; floor and admitted claims overlap by maximum rather than adding twice. `settle` requires affirmative release and accounting before freeing claims and requires a fresh view before further admission.

Finite deliveries use host-issued increasing IDs. `requestDelivery` is idempotent for the same ID/specification; another ID creates additive demand. `beginSupply` and `joinSupply` allocate bounded expected output to those demands. `creditSupply` accepts cumulative verified output from one native identity and credits it once; new output invalidates the previous stock view until refreshed. Expected output never becomes stock. `pendingDeliveries` exposes copied outstanding records to trusted procurement code. `cancelDelivery` withdraws remaining interest without deleting earlier credit. `closeDelivery`/`closeSupply` consume terminal metadata and reclaim their bounded slots. Retired IDs cannot recreate old requests.

## Native effects

`NativeTransport` uses only the public wrapper stream. A discarded `os_observe` handshake verifies driver mode and protocol without loading planner status. Action observations include native age plus the whole host round trip and expire at two seconds.

`EffectJournal` stores native intent and reconciliation receipts in SQLite on a dedicated writer thread. It bounds unfinished and retained settled entries separately. `EffectBroker.start` reconciles unfinished identities before acquiring a fresh native lease; no continuation stack is restored. `execute` records the exact intent before submission. A lost reply is queried by that identity, never replayed as a new request. `poll`, `cancel`, and `stop` preserve unresolved release and accounting obligations. A failed startup cannot report released merely because it acquired no new lease.

`ActivityCoordinator.admit` joins these boundaries using one fresh observation: check grants, prepare a trusted domain operation, reserve stock, attach owned subscribers, persist native intent, and submit. `join` can attach another granted consumer to applicable unallocated output while the attempt is running; it does not submit a second effect. `poll` reconciles current subscriber withdrawals before assigning new credit. Native-origin stopping also closes admission to new subscribers. Infrastructure failure latches the coordinator and starts revocation before notifying owners, since one child's failure can retire sibling handles.

With a configured `SupplyPlanner`, `admit({ supplyOfferId, workId? })` derives the operation and subscribers from that proposal. It resolves the same ID again after obtaining fresh inventory and rejects changed needs, epochs, output keys or quantities before any effect. Callers cannot override the proposal's owner, operation or arguments. Target-only shares receive internal delivery records for that attempt so their output is committed and their owners participate in withdrawal/cleanup. They are not guest delivery replies. Admission rollback and verified release retire those internal records; expected output cannot be borrowed by a later subscriber.

One shared activity accepts up to 32 explicit deliveries plus twelve target shares, within the ledger's 256 total retained delivery slots. Proposals limit extra target records to `availableDeliverySlots`; a full ledger can still serve already-registered finite deliveries without allocating target records. A failed admission rolls back the entire pre-admission bundle, including any internal records already created. Durable intent retains the proposal ID and internal delivery IDs alongside ordinary allocation provenance. Their accounting survives uncertain release; a host restart still reconciles the journal and creates fresh invocations rather than restoring these ephemeral records.

When the coordinator is owned by a `WorkService`, route in-flight joins through `WorkService.join` so the scheduling policy credits each newly participating consumer exactly once. The coordinator's lower-level `join` remains available for standalone broker use without that scheduling policy.

The first domain adapter is `ContainerTransfer`, bound to one currently open window. It projects component-aware stock, destination capacity and exclusive targets into the ledger, and gives native work a quantity allowance. Mixed source variants are rejected because this native operation currently selects by item ID. Each transfer closes its owned window; shared physical visits and opening contexts are later work.

These objects are trusted host interfaces. Passing them directly into a guest would bypass the intended capability and ownership boundary. The runner layer must translate bounded typed guest effects through these interfaces.

## Supply declarations and lifecycle evidence

An operation may declare `supplyDependencies`, an array of other registered operation names. Coordinator construction validates the complete bounded graph and rejects missing dependencies and cycles with their path. The [supply planner](supply-planning-api.md) derives current demand proposals for configured catalog operations; recursive prerequisite acquisition and native source readiness remain later integrations.

Pass the same `DecisionTrace` as `trace` to the invocation broker, effect broker and activity coordinator. It records linked host lifecycle, admission and reconciliation events. In particular, mandatory parent close records each child outcome and join before consuming the handle. Native intent also remains in the independent durable journal.

Rejected admissions retain their copied request, reason, and available owner/capture/resource basis. A rejection does not alter another admission's busy ownership. If recording that rejection fails, the original domain error survives while trace failure starts the same safe drain. Ordinary progress uses ordinary trace capacity; terminal and reconciliation evidence can use the cleanup reserve.

`DecisionTrace.open` creates a new run directory and refuses to overwrite existing evidence. `record` copies and bounds an event before enqueueing it; `flush` waits for queued writes, and `close` appends a terminal stream record and closes files. Quota and queue failures latch an incomplete stream and notify subscribers asynchronously. The effect broker revokes authority and the coordinator stops ordinary admission. Cleanup recording uses reserved capacity and cannot prevent physical cancellation if the writer is broken. Drain physical obligations before closing the trace.

The `trace.closed` completeness field describes this host event stream only. It does not establish full native history, clock classification, opportunity coverage or benchmark success. Native event-gap handling, bounded author queries, archive quota and evaluation verdicts remain part of the subsequent observation/evaluation layers.

## Validation

Run `npm test --prefix experiments/os-prototype` for the old prototype plus new host seams. Native checks use `./gradlew :test --tests ai.moeru.airicraft.os.NativeActionRuntimeTest --tests ai.moeru.airicraft.agent.NativeContainerActionsTest`. The public-wrapper live transfer, cancellation and host-crash results are recorded in [STATUS.md](STATUS.md), separately from simulated cases and still-unimplemented roadmap work.
