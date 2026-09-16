# Native item observations and readiness

`NativeObservationFeed` connects the public wrapper's `os_observe` capture to the resource ledger, scoped condition waits and work scheduling. This slice covers carried items and an explicitly bound, already-open container. It does not open containers, navigate, inspect distant sites or synthesize crop/animal/tree facts.

The native adapter now captures all 36 main-inventory/hotbar slots independently of the current screen. Armor, offhand and cursor contents are excluded from these spendable stocks. Unavailable player/world state is explicit. Item identity includes the native opaque component fingerprint; unlike components never become one stock entry. A complete inventory proves configured missing item keys have quantity zero. A closed or different container supplies no current stock evidence for the bound container.

## Host connection

Construct the feed with the same native transport, effect broker, work service, invocation broker, ledger, resource service, condition waits and operation catalog used for execution. Bind the expected world ID and epoch. Configure up to 32 named views:

```js
views: {
  carried: { location: 'player', resources: { wheat: playerWheatKey } },
  home: { location: 'container:home', resources: { wheat: chestWheatKey } }
}
```

Each view has at most 32 resource aliases; their union with the resource service's declared keys is at most 128. The existing condition-wait scope-to-grant mapping controls guest access. Each projected fact is `['stock', alias]`, with explicit known/unknown status. Every projection retains the native capture identity, session, clock domain, source, tick counters and conservative transport-age bound. View metadata does not imply full world coverage.

Call `await feed.refresh()` once before starting behaviors, so finite demands have an observed epoch. Then call `feed.tick()` regularly alongside `BehaviorLoop.tick()` and `work.tick({ authority: feed.availability() })`. The feed starts at most one read at a time, normally one second after the preceding completion. `refresh()` coalesces concurrent callers. Reads do not block guest execution or the independent lease heartbeat. The effect broker remains the only native mutation authority.

While physical work or admission is pending, passive frames can update waits but cannot replace the ledger's admitted basis. During a free interval, the feed merges operation projections into one ledger observation, derives current supply proposals, checks every queued operation's preparation and read-only ledger admission, and publishes readiness. It makes no reservations. Protected stock, unavailable capacity and component ambiguity block readiness; an unobserved container leaves readiness unknown. Invalid finite transfer arguments return a typed rejection. Invalid automatic proposals use the existing bounded retry policy, allowing other work to proceed.

Supply readiness also requires the prepared output identity and quantity to match the proposal. Different components block that proposal. If the source changes between readiness and the coordinator's fresh admission, the coordinator retains its output guard and the work service backs off the affected rule without failing unrelated roots.

`offerView(owner)` returns the current capture/generation basis and only the feed-configured scopes that owner may observe, or null when there is no fresh basis. It uses `ConditionWaits.grantedView`; the entire copied view must fit 12 KiB and 1,900 traversal nodes, leaving runner-envelope headroom. It never silently truncates or includes ungranted scopes. `isCurrentOfferBasis(basis)` gates asynchronous evaluation results. A new capture makes previous authored offers ineligible until refreshed. Pulses assess newly returned declarations against the cached capture and its ledger basis while quiescent, so evaluation does not need to chase an endlessly newer capture. Each pending ID is assessed once per basis; the cache is pruned to current requests. Physical admission still reads again.

`ResourceLedger.assess` shares the atomic reservation checks without creating a claim. Trusted ledger observations have a 512 KiB ceiling and retain the existing 256-entry bounds on each map and target list. Guest values remain limited to 16 KiB; scoped observations remain limited to 12 KiB. The larger internal ceiling accommodates distinct component-aware inventory/container stock and capacity keys without truncation.

## Freshness, exclusion and failure

Freshness remains less than two wall seconds. Native transport age plus host elapsed time is checked; replaying a capture cannot renew it. Current lease identity, absence of native activity and the broker's local health must agree before the feed reports available authority. The coordinator still obtains a new observation and checks its full bundle immediately before admission.

The work service exposes a monotonic observation generation, advanced when admission begins or old assessments are invalidated. A read delayed across either boundary is discarded, including one delivered after an effect completes. `invalidate()` clears readiness without claiming physical release. `close()` disables publication and invalidates wait/stock/readiness evidence; a late response cannot reopen the feed.

A stale read creates an observation gap and can recover on a newer capture. Invalid native data, session/epoch/world changes and infrastructure failures latch `state().fault`, invalidate evidence and request native lease revocation. The containing host must then stop its generators and continue polling/draining work and the effect broker until physical cleanup is proved. A fault never acknowledges a free player merely because revocation was requested.

Sampled server ticks do not prove continuous process eligibility. Item projections deliberately publish `eligibleTicks: null`. The optional [native progress scopes](progress-api.md) connect explicit chunk/passive-entity counters to wait deadlines. They use distinct configured scopes and grants. This feed never calls `work.advance`; continuous eligible-root intervals require resource/readiness/authority evidence beyond simulation progress. Eligible-root production and retained contexts remain separate integrations.

## Evidence

Focused native tests cover the passive main inventory while the chest is closed. Host integration tests cover shared supply, stock floors, missing coverage, generation races, freshness/replay, session revocation, single-flight close and native ownership. Real supervised QuickJS generators use the feed to receive shared supplies and wait on newly observed stock, against a simulated native transport. These tests do not qualify Minecraft duties or scheduling efficiency. Live qualification, if performed, is recorded separately in the implementation status.
