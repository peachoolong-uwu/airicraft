# Guest resource declarations

`ResourceService` connects generator stock targets and finite delivery waits to the shared `ResourceLedger`. It does not own a second inventory, reserve speculative output, or bypass `ActivityCoordinator`. A trusted run configuration binds at most 64 resource aliases to exact item/location/component keys, a resource grant, permitted supply methods and an essential-need priority. Methods resolve against the bounded acyclic operation catalog and require their own operation grants.

## Targets and deliveries

`os.target(alias, quantity)` updates the installed root consumer's maintained floor and returns `registered`. Repeating the declaration does not add stock requirements; an authorized helper updates that same consumer's target. Different roots add separate floors. Zero removes that target. The target survives a helper's return and is removed when the root stops or becomes terminal. A closing root retains its floors for still-owned children, but receives no new target-only supply work. Existing admitted claims remain protected after target removal until accounted release.

The priority comes from host configuration, not behavior code. A target's original declaration order is retained across updates for deterministic shortage allocation. The ledger continues to protect the maximum of a consumer's floor and admitted unconsumed claims. Declaring a target neither reserves the actor nor proves the target is currently satisfied.

`os.demand(alias, quantity, methods)` suspends the caller for a finite delivery. Separate requests add. The execution loop assigns the owner and effect sequence; guests cannot choose those identities. Repeating an active owner/sequence with identical input returns its existing ID, including when later requests are also pending. Changed input is a conflict, and a consumed sequence cannot create a new delivery. IDs come from the ledger's monotonic allocator, so service construction cannot restart a conflicting counter.

An empty method list selects the configured methods granted to that invocation. Explicit methods must be configured and individually granted. Missing methods or authorization produce typed rejections, not hidden procurement. There are at most 32 retained deliveries per invocation and 256 in the ledger globally. Completed but unconsumed results and unresolved cancellation still occupy capacity. Cursor history is bounded by current owners and retained deliveries.

## Shared output and cleanup

Compatible finite requests can be admitted together through the existing coordinator. Verified cumulative native output is credited once to explicit delivery IDs. `ResourceLedger.delivery()` now separately reports outstanding, allocated-but-uncredited, and unallocated quantities. The scheduler view excludes quantities already assigned to a live/reconciling supply attempt; expected output never becomes observed stock. Newly credited output invalidates the previous inventory view until a fresh observation, even before the physical activity releases.

A delivery reply contains its ID, resource alias, epoch, requested quantity, credited quantity and status. `fulfilled` is available only after applicable supply cleanup permits consuming the ledger result. Partial progress or an accepted native request is insufficient. Cancellation withdraws only that invocation's subscription; its credits and accounting remain until the corresponding supply settles. The broker/coordinator decide whether the shared actor must stop, and unconfirmed physical release remains an obligation.

The service rechecks epochs at consumption, including when no intervening regular poll ran. Old requests return `epoch_changed` after their accounting can retire; stock in a new world cannot fulfill them. The complete runtime still must enforce its wrong-world/epoch stop policy and native reconciliation.

## Scheduler boundary

`pending()` exposes up to 256 unallocated finite requests with their owners, exact resource keys, allowed methods and configured priority. `shortages()` exposes maintained-floor deficits, with null for missing stock or inventory invalidated by a completed effect. Both are bounded trusted host views, not guest response envelopes. Stock allocation is calculated once per resource for each shortage view.

`procurement(routes)` combines the two views without adding the same consumer's floor to finite deliveries that can cover it through installed routes. The optional resource-to-method map limits which outstanding deliveries suppress extra floor production; already allocated units still count even if their route is removed. Only a positive remainder needs target-only production. Without a route map, the diagnostic estimate includes all declared methods. Unknown floor deficits remain null. Different consumers and distinct delivery IDs retain separate needs. The [supply proposal API](supply-planning-api.md) passes its installed routes to this calculation, so a delivery naming an absent rule cannot hide independent restocking.

The calculation includes the coordinator's internal target-share deliveries from the same ledger. An admitted target share therefore covers its own remaining floor estimate without becoming stock or generating a duplicate supply proposal. These bounded internal records share the global delivery capacity, are retired by the coordinator after release or pre-admission rollback, and never appear as guest delivery results. `procurement().targetSlots` reports remaining ledger slots so the planner can bound target expansion without blocking service of finite deliveries when all 256 slots are already occupied.

These views are scheduling inputs. Even a known shortage remains a hint until the coordinator obtains a fresh native frame and admits a valid atomic bundle. The [work service](work-api.md) now combines automatic supply proposals with finite requests under one bounded scheduler, including admission and retry backoff. Native readiness and inspection/context entry remain unconnected. This resource service itself does not poll or reopen an empty chest just because a demand exists.

`BehaviorLoop` accepts the configured service and handles generator target/demand effects alongside observation/wait/spawn/join. Its regular pulses and stop/failure paths sweep resource subscriptions. Native heartbeat, acquisition, progress and cleanup remain independently serviced; no resource wait awaits a transport call on the code-execution pulse.

## Evidence

Focused tests cover root/helper floor identity, grant checks, active and retired retries, distinct demands, shared output, cancellation, epoch changes, capacity, unknown stock and post-effect refresh. Two integration cases use real QuickJS/root processes, the actual journal/effect broker/coordinator and a simulated native container transport: separate VM requests share one transfer, and one cancelled VM does not stop the remaining subscriber. These are deterministic integration results, not new Minecraft qualification.

Diagnostic bounds probes under `run/os-implementation/resource-qualification/` retain source hashes and ten samples with twelve roots, 768 targets, 256 deliveries and 32 ledger supplies. They motivated sharing allocation calculations within one shortage view. These short host-only probes have uncalibrated host load and are not matched performance comparisons, sustained latency guarantees or game efficiency evidence.
