# Scoped observations and passive waits

This implements the host-side observation/condition portion of [issue 56](https://github.com/shinohara-rin/airicraft/issues/56). The generator loop and [native item projections](native-feed-api.md) are connected; other native domain scopes remain to be added. These classes do not perform inspection, navigation, or other Minecraft actions.

## Current facts and provenance

`ObservationFrames` accepts bounded projections of native frames for at most 32 configured scopes. Each projection carries schema version, bridge session, explicit world epoch, scope, capture ID/sequence, native clock domain and timestamp, client/server ticks, source, coverage, and the native-age-plus-request-round-trip bound already checked by `NativeTransport`. Missing server time or eligible progress stays null. The host never subtracts a Java timestamp from a Node timestamp.

A projection has up to 128 explicit fact cells. Each cell identifies a path and declares either `known: true` with a copied value or `known: false`; missing cells are unknown even when the surrounding scope is complete. Truncated/unloaded coverage never proves an omitted animal or block is absent. A known cell in partial coverage may describe a specifically observed target; native adapters remain responsible for the truth of aggregate facts.

Published data is copied and deeply frozen. Each projection is limited to 12 KiB, ten nesting levels and 1,900 traversal nodes, reserving space within the unchanged guest wire limit for response envelopes. Internal predicate reads share those immutable values; exported observations receive copies. `observe(owner, scopes, {offset: 0})` returns exactly one requested scope and `nextOffset` (null at the end). Every page reports its own current cursor; pages are independent reads, not an atomic snapshot across scopes. Oversized projections are rejected at publication; adapters must declare narrower scopes instead of silently truncating facts.

A fact is current only when its scope is available, its age upper bound remains below two wall seconds, and no history gap has invalidated it. Read responses separately report `current`, present age bound, and the original last-seen frame. Different scope captures remain visibly separate.

The host explicitly changes epochs. A delayed frame from an old epoch or another bridge session cannot switch the current epoch. Older capture sequences are ignored; reusing the same sequence with changed content is an error. A gap invalidates facts while retaining capture high-water marks: a replayed old capture cannot restore readiness. Only a newer capture restores its scope. The underlying native event history and effect journal retain their separate contracts.

## Declarative predicates and ownership

`compileCondition` accepts bounded `all`, `any`, `not` and scoped leaves:

```js
{ scope: "wheat", path: ["matureCount"], atLeast: 1 }
{ scope: "sheep", path: ["ready"], equals: true }
{ scope: "homeChest", path: ["stock"], known: true }
```

Predicates have at most 64 nodes, eight nesting levels, and 32 dependency scopes, within the normal closed 16 KiB value boundary. Paths have at most eight segments. Results are `met`, `unmet`, or `unknown`; negating unknown remains unknown. Structural comparisons use the bounded literal's shape, do not invoke getters, and do not repeatedly serialize a large observed value. This is a data vocabulary for the `os.wait` effect, not another source-code evaluator.

`ConditionWaits` receives the invocation broker, configured scope-to-grant mapping, epoch, monotonic clock and optional trace. `wait(owner, condition, options)` checks the owner's grants, registers against the current cursor and evaluates current facts synchronously. Registration contains no asynchronous gap. A result already ready is retained immediately. `take` consumes one outcome; repeated delivery cannot consume it again. There are at most 32 retained waits/results per invocation and 256 globally.

`publish` reevaluates only waits depending on that scope. The host loop also calls `poll()` regularly to service wall deadlines, freshness changes and owner cancellation. Cancelled/joined owners lose their subscriptions; late updates cannot resume them. `observe` and `inspect` expose bounded state. `inspect.evaluatedAt` identifies the latest evaluation time rather than implying continuous observation.

The cursor is an epoch-qualified host update sequence with a 512-update continuity window. An expired or explicitly gapped cursor returns `gap` and available current capture references; it does not reconstruct a missed historical edge. An epoch change invalidates pending waits and stale deliveries. Identified native operation outcomes are not stored or coalesced in this level-wait registry; the broker/journal retain those.

## Declared clocks

Wall deadlines use `{ clock: "wall", milliseconds: n }`. Growth deadlines use `{ clock: "eligible_ticks", scope: "wheat", ticks: n }`, with authorization for that explicit clock scope. The native projection must provide an authoritative cumulative eligible-tick counter and clock ID for that scope. The host does not derive it from polling duration, wall time, or maturity assumptions. Unknown/stale clock coverage resets the accounting anchor; a different clock ID starts a new anchor. A counter regression within one clock is rejected. Paused/ineligible time does not advance that counter.

The deadline's named clock scope is intentional even when its predicate combines multiple scopes; the host does not infer simultaneous eligibility by combining unrelated counters. Observed readiness wins when readiness and a deadline are first noticed in the same evaluation. Deadline expiration itself never asserts that a crop or tree matured.

The current native open-container frame does not yet provide all domain projections or these per-scope growth counters. Until those adapters are implemented and qualified, unavailable counters remain unknown. No elapsed-time substitution is permitted.

## Evidence and limits

The initial synthetic boundary probe exposed a 12,245 ms update caused by repeatedly copying one large observation for 256 waits. Immutable internal facts and comparisons bounded by literal shape reduced the same case to about 23 ms in a follow-up probe; large matching-value and mismatched-shape cases took about 54 and 21 ms. Evidence and exact source hashes are under `run/os-implementation/wait-qualification/`. These offline measurements do not establish sustained worst-case latency or Minecraft efficiency.

Current trace hooks record wait identities, condition digests, subscriptions, outcomes, cancellation and capture references. Complete observation artifact retention, independent opportunity accounting and the measured-run stop policy are later integration work. No live duty or source publication follows from these isolated classes.
