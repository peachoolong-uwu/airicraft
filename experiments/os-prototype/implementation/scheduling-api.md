# Deterministic scheduling policy

`SchedulingPolicy` implements the arbitration seam from decision #58. It chooses among trusted, already assessed offers. It does not admit an activity, own a second player lease, measure native readiness, or certify release. The complete work/supply service and native context/checkpoint adapters remain subsequent integration work.

## Offers and root fairness

`update({ roots, offers })` atomically replaces the host view of at most twelve roots and 1,024 offers (32 invocations times 32 offers). Each offer has a stable ID, one or more consumer roots, land/fishing kind, ready/blocked/unknown readiness, host-configured priority, nullable context identity and optional travel estimate. Priority and feasibility must come from trusted operation rules and current evidence, not directly from guest fields. Multiple consumers permit shared supply work to count as one operation serving distinct roots.

Refreshing an existing ID preserves its original order. Its roots, kind and context cannot change under that ID; changed identity requires a new ID. Withdrawing an offer removes it. Root fairness persists across offer refreshes until the host removes the root. Input and returned data are copied.

Ready land work excludes fishing. Blocked and unknown offers cannot win. Scores are configured base priority, one point per 300 eligible completed server ticks, three points for reusing the ready context, and a travel bonus of at most three points. The selected result exposes these terms, travel provenance and uncertainty. Unknown travel contributes no bonus. The later run configuration supplies the agreed duty priorities; this module does not accept guest-controlled priority.

## Eligible game time

`advance({ epoch, fromTick, toTick, eligibleRoots, covered, ordinaryAllowed })` consumes an explicitly covered interval of completed server ticks. The observation/eligibility producer must establish continuous feasibility throughout that interval; two endpoint polls are insufficient evidence. The policy also rejects credited roots lacking a currently ready candidate in the applicable land/fishing class.

Unknown coverage and survival/reflex exclusion accrue no age. Gaps between reported intervals accrue no age. Equal endpoints add zero; overlapping/replayed intervals, reversed ticks, foreign epochs and invalid roots reject atomically. Wall time never becomes game time. A new epoch requires a new policy after the host's native reconciliation; the policy itself cannot approve that transition.

At 2,400 eligible ticks a root enters the overdue FIFO. The crossing tick is calculated within the covered interval; simultaneous crossings use original request order. Stored age saturates at the overdue threshold because FIFO then takes precedence over numeric age. Only currently eligible overdue roots participate, and later arrivals cannot overtake them. `served(selection)` accepts the copied `select` decision after successful fresh coordinator admission and resets its participating roots; retired roots are not recreated. The queue can refresh while admission is pending, so unrelated readiness and eligibility need not freeze. For immediate admission, `served(offerId)` remains available against the current ready offer. A failed proposal is not service. Duplicate offers share their root's age and reset.

## Contexts and active work

`decide({ authority, context, activity })` returns a bounded decision. Unavailable or unknown ordinary authority returns `wait`. The host supplies the native-backed context phase, completed operation count and advancing elapsed ticks. Entering, exiting and unresolved contexts cannot serve ordinary work. The module never changes that physical phase.

A ready visit expires after eight operations or 1,200 advancing ticks. Expiry returns `close_context` before any further selection, including another overdue operation in the same context. If useful outside work is ready, an outside-context turn remains owed. After verified closure, eligible overdue roots come first, then that outside turn, then scores. Serving an overdue same-context root preserves the outside turn. The debt clears on outside service or when no outside candidate remains eligible. Incompatible winners and visits without feasible work also request closure before replacement work.

An active ordinary operation returns `wait`; a later adapter must handle its safe checkpoints. Active fishing with ready land work returns `yield_fishing`: yield navigation before casting, retrieve an available bite, or allow at most 100 further advancing ticks before reeling in. The deadline is sticky even if the original land offer disappears. Paused ticks do not spend it. Without a known tick clock the policy requests immediate retrieval. A releasing activity still returns `wait`; no yield decision claims the hook or inputs are released.

The host must honor context entry/exit watchdogs, native action/checkpoint bounds, fresh resource admission and affirmative handback. This module supplies decisions rather than physical timers or cancellation. Its decision/score and root-state results are available for the later joined scheduler trace; it does not yet persist an observation-to-admission audit by itself.

## Qualification boundary

Deterministic public-interface tests cover filler selection, root age and FIFO, duplicate offers, covered clocks and epoch rejection, score terms, visit budgets and outside-turn precedence, fishing deadlines, atomic identity refreshes, and service to all twelve overdue roots with 1,024 offers. They use explicit synthetic eligibility intervals and native-state inputs. These are policy results, not native integration, automatic supply selection, live fairness, or measured game efficiency.
