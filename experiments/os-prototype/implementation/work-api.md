# Owned work requests and admission

`WorkService` connects finite `os.work(...)` generator effects, recurring offer declarations and optional automatic supply proposals to the scheduling policy and the existing `ActivityCoordinator`. It owns request/result lifetime, not another physical lease. A trusted rule names an installed operation and supplies priority. Operation grants come from the same catalog used by the coordinator; behavior arguments cannot raise authority or priority.

This connection accepts land operations with no retained cross-operation context. The bound, already-open container transfer is the integrated adapter. Fishing and retained-context rules reject at construction until their native lifecycle adapters exist. This does not yet run the mixed duties or retain pen/chest visits.

## Request lifetime

`request(owner, sequence, { operation, arguments, context: null })` checks the closed request and current grant before registration. The generator loop supplies owner and effect sequence. Repeating an active sequence with identical input returns its existing ID; changed input conflicts, and a consumed sequence cannot recreate work. Distinct sequences are distinct finite requests. At most 32 retained requests belong to an invocation. Without automatic supply, the service retains at most 1,024 finite requests; completed unconsumed results and unresolved work still occupy slots.

`take(owner, id)` returns pending until the coordinator reports both verified release and complete accounting. Final results distinguish success, failure, cancellation and pre-admission rejection, retaining the coordinator activity evidence. Consumption removes the result. Cancelling an owner removes queued work; an admitting or active request remains until its native obligation settles. The regular `poll()` sweeps subscriptions and retired owner cursors. Late transport replies cannot resume a cancelled generator.

## Recurring declarations

Recurring definitions use `replaceOffers(owner, sequence, declarations, basis)` instead of finite request/result slots. Full-batch validation, stable identities, retry and withdrawal semantics are specified in the [offer contract](offers-api.md). They share the 32-per-invocation and remaining global work budget with finite requests. As with automatic supply, at most one retired admitting/active record can remain outside the current declaration budget for physical cleanup; all paths share one actor.

## Automatic supply

Pass the same optional `SupplyPlanner` as `supplies` to the work service and coordinator. Every configured supply operation must have a supported work rule. The service derives current alternatives when updating the schedule or reading `pending()`, giving them the same unknown initial feasibility and single admission path as finite work. Their score priority is the maximum of the operation priority and participating consumers' configured priority. Shared service credits the participating roots through the existing policy.

The service reserves `supplies.maximumOffers` slots from the 1,024-offer budget for automatic alternatives. A single supply rule reserves twelve slots, leaving 1,012 retained finite requests. At most one additional admitting/active supply record remains outside the queued alternatives until cleanup completes. Recomputing needs cannot discard that record when its original requester stops. Automatic results are delivered through resource accounting, not `work.take`; finished supply metadata is swept automatically.

Trusted scheduling code uses `join({ activityId, owner, deliveryId, quantity })` for an in-flight join to this service's active activity. The coordinator verifies grants, identity and spare output before the work service resets the newly participating consumer's eligible age. Each consumer receives that credit once per activity, so idempotent joins or additional same-consumer subscriptions cannot repeatedly erase accumulated age. Use this entry point instead of calling the lower-level coordinator directly when work is managed by this scheduler.

`pending()` includes copied supply provenance and any deferral. A failed or partially productive supply attempt backs off for five wall seconds per rule and anchor consumer, without failing a healthy requesting behavior or blocking other rules. Changed demand/ownership requires fresh selection without that retry timer. A changed proposal ID cannot evade its rule/consumer's timer. Every supply rejection invalidates the old feasibility capture; a new attempt needs newer evidence as well as an expired timer. The [native item feed](native-feed-api.md) now provides source readiness for an already-open bound container. Opening/inspection work remains unconnected.

## Feasibility and clocks

All new requests start with unknown feasibility. A trusted host observer reads `pending()` and calls `publish(id, assessment)`, giving epoch, capture identity, monotonic capture sequence, ready/blocked/unknown status and a conservative age upper bound. This first producer contract uses one common capture sequence within the epoch. It must supply real capture age and operation-specific eligibility; it is not exposed to guests.

Assessment freshness is less than two wall seconds, including its reported age. Duplicate or old capture sequences cannot restart the freshness timer, even for a newly registered request using that same capture. Conflicting current capture identities reject. A completed effect invalidates all queued assessments through the highest observed capture sequence; another request needs newer evidence. Selection remains a hint: the coordinator independently obtains a fresh native frame, checks grants and atomic resource claims, journals intent, and submits the identified operation.

The native feed is one producer of this contract. `observationGeneration` changes when admission begins or assessments are invalidated, fencing out reads delayed across physical work. Trusted `invalidate()` clears readiness; trusted `reject(id, reason)` returns invalid transfer arguments or unsupported items without leaving the caller suspended forever. Neither API grants or releases the player.

`advance(interval)` passes covered eligible game ticks to the same `SchedulingPolicy`. It refreshes unrelated readiness and root membership even during transport; the pending admission retains only its copied selection for eventual service credit. Its continuous-eligibility contract still applies; the service does not synthesize age from wall time, endpoint polls or a pending request. The authoritative native eligibility producer remains to be connected.

## Dispatch and failure

`tick({ authority })` is synchronous and never waits for native transport. Unknown/unavailable authority cannot start work. One asynchronous admission or reconciliation runs at a time; the code-execution loop and independent lease heartbeat keep progressing. While any activity remains owned, no replacement is admitted. Selection is recorded with score terms and its assessment basis; the work ID also follows the activity into durable intent provenance. The root receives service credit only after native admission, not after a failed proposal.

The active path continues polling cleanup even when new ordinary work is unavailable. Expected argument/resource admission failures return a typed rejection for finite work or defer automatic supply. An infrastructure fault stops new dispatch, reports `state().fault`, fails affected subscribers and preserves unresolved ownership. Cleanup follows the activity identity and all supply owners, so a remaining or later subscriber can outlive the original requester. The containing runtime must stop/drain the rest of the run on that fault; this service does not secretly restart it. Native epoch transitions and run shutdown still require the global host lifecycle policy.

The generator loop optionally accepts this service and suspends a work caller without occupying a computation job. Missing service configuration still returns `service_unavailable`. Unsupported arguments/operations/grants and native preflight rejections retain typed rejection reasons; native rejection evidence remains attached. Unexpected host faults use invocation failure propagation. Its sweep also synchronizes runner ownership when a native service stops an owner, using retained root identity even if mandatory join consumed the child's handle. It does not acknowledge physical release or cancel the children of a normally returning parent. Oversized final envelopes retain the existing explicit response-limit rejection.

## Evidence

Integration tests use the actual invocation broker, ledger, coordinator and SQLite effect journal with a simulated native container transport. Cases cover priority/overdue selection, unknown/stale assessment gating, capture replay, protected stock, delayed admission cancellation, shared targets, backoff, partial cleanup, transport failure and retained capacity. Real supervised QuickJS generators complete finite work and automatically supplied demands while another root finishes independently; delivery waits for physical handback. Native-state evidence and readiness in these tests are controlled fixtures. No new Minecraft or efficiency qualification is claimed.
