# Broker foundation

The host modules in `src/os/` implement the ownership, stock and native-effect seams from the [handoff](../design/implementation-handoff.md). They are not yet the prototype's default `src/main.mjs` entrypoint. Guest runners, installed supply rules, complete lifecycle traces, and domain scheduling are subsequent layers.

## Ownership and stock

`InvocationBroker.install` creates a root with an immutable definition identity and operation grants. `spawn` creates an owned child with attenuated grants. `returned` closes the body; an outcome appears only after children and physical obligations settle. `join` consumes a child handle once. Normal close consumes remaining child results; `collect_all` is explicit, while the default child-failure policy cancels siblings. Cancellation can withdraw one shared subscriber without cancelling useful work for another. The last subscriber retains the cleanup obligation.

`ResourceLedger.observe` replaces a scoped, versioned stock/asset/capacity/target view; omitted or null stock is unknown. `target` declares a consumer's maintained floor. `reserve` admits a complete activity bundle atomically against an exact revision. A consumer can spend its own protected stock and surplus; floor and admitted claims overlap by maximum rather than adding twice. `settle` requires affirmative release and accounting before freeing claims and requires a fresh view before further admission.

Finite deliveries use host-issued increasing IDs. `requestDelivery` is idempotent for the same ID/specification; another ID creates additive demand. `beginSupply` and `joinSupply` allocate bounded expected output to those demands. `creditSupply` accepts cumulative verified output from one native identity and credits it once. Expected output never becomes stock. `cancelDelivery` withdraws remaining interest without deleting earlier credit. `closeDelivery`/`closeSupply` consume terminal metadata and reclaim their bounded slots. Retired IDs cannot recreate old requests.

## Native effects

`NativeTransport` uses only the public wrapper stream. A discarded `os_observe` handshake verifies driver mode and protocol without loading planner status. Action observations include native age plus the whole host round trip and expire at two seconds.

`EffectJournal` stores native intent and reconciliation receipts in SQLite on a dedicated writer thread. It bounds unfinished and retained settled entries separately. `EffectBroker.start` reconciles unfinished identities before acquiring a fresh native lease; no continuation stack is restored. `execute` records the exact intent before submission. A lost reply is queried by that identity, never replayed as a new request. `poll`, `cancel`, and `stop` preserve unresolved release and accounting obligations. A failed startup cannot report released merely because it acquired no new lease.

`ActivityCoordinator.admit` joins these boundaries using one fresh observation: check grants, prepare a trusted domain operation, reserve stock, attach owned subscribers, persist native intent, and submit. `join` can attach another granted consumer to applicable unallocated output while the attempt is running; it does not submit a second effect. `poll` reconciles current subscriber withdrawals before assigning new credit. Native-origin stopping also closes admission to new subscribers. Infrastructure failure latches the coordinator and starts revocation before notifying owners, since one child's failure can retire sibling handles.

The first domain adapter is `ContainerTransfer`, bound to one currently open window. It projects component-aware stock, destination capacity and exclusive targets into the ledger, and gives native work a quantity allowance. Mixed source variants are rejected because this native operation currently selects by item ID. Each transfer closes its owned window; shared physical visits and opening contexts are later work.

These objects are trusted host interfaces. Passing them directly into a guest would bypass the intended capability and ownership boundary. The runner layer must translate bounded typed guest effects through these interfaces.

## Validation

Run `npm test --prefix experiments/os-prototype` for the old prototype plus new host seams. Native checks use `./gradlew :test --tests ai.moeru.airicraft.os.NativeActionRuntimeTest --tests ai.moeru.airicraft.agent.NativeContainerActionsTest`. The public-wrapper live transfer, cancellation and host-crash results are recorded in [STATUS.md](STATUS.md), separately from simulated cases and still-unimplemented roadmap work.
