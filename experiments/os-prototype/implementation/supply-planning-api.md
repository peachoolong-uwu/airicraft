# Bounded supply proposals

`SupplyPlanner` derives alternative procurement batches from `ResourceService`, using a trusted configuration of at most 32 rules. It is deterministic host code. A rule binds an exact resource key to a catalog operation, copied argument template and maximum output of 1–64 units. The operation catalog must have an acyclic supply dependency graph. Templates cannot supply their own `quantity`; each is limited to 4 KiB, 1,024 traversal nodes and depth twelve, reserving room for the proposal envelope and subscribers. The complete rule configuration uses the ordinary 16 KiB message limit.

The planner itself derives quantities and subscribers without observing source availability, reserving stock or calling Minecraft. The optional `WorkService` integration turns its alternatives into queued supply work; native readiness and physical context entry remain unconnected. A missing applicable rule leaves the original resource demand visible and blocked.

## Union of needs

`ResourceService.procurement(routes)` returns unallocated finite deliveries and remaining target needs. The planner supplies its installed resource-to-method routes. For each consumer/resource pair, the extra target quantity is the positive difference between its observed floor deficit and outstanding finite deliveries with an applicable route. An allocated but uncredited delivery still covers that part of the estimate even if its route is no longer installed. Different consumers remain separate; distinct finite deliveries still add. Omitting `routes` gives the broader diagnostic estimate using every declared method.

A blocked delivery cannot erase an independently allowed target alternative merely by naming a missing rule. If a four-unit delivery permits only harvesting, but only chest restocking is installed, a four-unit floor can still yield a chest proposal. That proposal does not credit the harvest-only delivery. The original finite demand remains visible and blocked. Installed routes are not evidence of physical readiness; that still needs the native producer and admission integration.

For example, A's target deficit of six plus two separate two-unit deliveries requires four delivery units and two additional target units. B's target deficit of two adds two more. A shared batch can therefore produce eight units, with four credited to A's explicit delivery IDs and four committed to the maintained floors through internal attempt records. Those records prevent duplicate procurement and preserve subscriber ownership; they do not create speculative stock. Only fresh inventory can show that the maintained floors are satisfied.

Unknown floor deficits remain unknown. Known finite delivery quantities can still produce proposals while the corresponding floor is unknown. A closing root keeps its protected floor but receives no target-only production; a still-running child can retain its finite delivery. Cancelled and foreign-epoch deliveries are removed by the resource service's existing reconciliation rules.

## Candidate batches

`offers()` returns copied alternatives, grouped by resource and allowed method. Every eligible consumer can anchor one batch per matching rule. That consumer's needs come first; other compatible needs follow in configured priority and stable order, with finite deliveries before extra target units. Each batch includes at most 64 output units and 32 delivery IDs. Only owners and consumers receiving a positive share participate, and the batch inherits their maximum configured priority.

Target shares also respect the resource service's current `targetSlots` headroom. With a full 256-slot ledger, proposals can still serve existing finite deliveries without creating internal target records; their consumed results reclaim capacity. With one free slot, each target root may anchor an alternative containing its own share. This prevents extra maintained-floor production from deadlocking all already-registered deliveries. Headroom is recomputed when resolving the proposal at fresh admission.

Anchoring prevents an earlier large demand from hiding another eligible root. With twelve roots and 32 rules there are at most 384 alternatives; these are a trusted host view, not a guest message or 384 admitted activities. `maximumOffers` reports the configured upper bound and `operations` returns copied unique operation names. The work service reserves this capacity within its combined queue limit. Root age and overdue selection still belong to `SchedulingPolicy`.

Each proposal carries its primary owner, all participating owners/consumers, explicit delivery allocations, target-only shares, bounded operation arguments and exact resource/epoch. Its ID hashes this copied content. `resolve(id)` derives the current alternatives again and rejects an ID whose content is no longer applicable. Equal needs can produce the same ID again; a proposal ID is not an execution or delivery ID and cannot replay a previous receipt.

These are alternatives to one another. `ActivityCoordinator.admit({ supplyOfferId })` resolves the selected proposal again after fresh native observation, checks all current grants and output compatibility, and admits one atomic bundle. It creates bounded internal deliveries for target-only shares and includes all subscribers in cleanup ownership. `WorkService` refreshes alternatives, invalidates old feasibility after each effect/rejection, and applies bounded failure backoff. Planning itself never allocates future output. Physical source/context readiness remains unconnected.

## Evidence

Public-interface tests use the actual invocation broker, resource ledger/service and scheduling policy. They cover union arithmetic, in-flight allocations, cancellation, unknown stock, closing roots, incompatible methods, stale proposal resolution, copied configuration/results, batch limits and all twelve roots. An overdue consumer remains selectable beside a large earlier request. No native transport, Minecraft run or efficiency result is claimed by these tests.
