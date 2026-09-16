# Native scope progress clocks

Growth/cooldown wait budgets can now use explicit cumulative native counters. They do not derive duration from two sampled game timestamps. This is a simulation-progress source for passive waits; continuously feasible root aging and domain maturity observations remain separate integrations.

## Scope registration

The trusted host optionally supplies `progressScopes` to `os_observe`:

```json
{
  "progressScopes": [
    { "scope": "farmClock", "chunks": [{ "x": -19, "z": -24 }] },
    { "scope": "sheepClock", "entities": ["00000000-0000-0000-0000-000000000001"] }
  ]
}
```

Coordinates are chunk coordinates. Entity identifiers are exact UUIDs; the example UUID is a placeholder. A declaration has exactly one nonempty source list. Sources bind to the current dimension. At most 32 scopes, 16 sources per scope and 128 total source references are retained. Duplicates, malformed sources and excessive declarations reject atomically. The normal 16 KiB native request limit also applies. Registration neither navigates nor loads chunks nor acquires a player lease.

An omitted field retains registrations; an empty array removes them. Unchanged scope/source sets keep their clock identity, including reordered lists. A changed source set or removed-and-readded scope starts a new identity with no inherited progress. A new local world/load binding or driver instance resets the observer. Server shutdown clears it. Remote/unavailable worlds return `available: false`, not a substitute client clock.

## Native evidence

`facts.progress` reports `available`, acquisition `source`, `clockSession`, `throughTick` and bounded scope records. Each record contains `scope`, `kind`, `clockId`, nullable `eligibleTicks` and nullable `lastTickEligible`. Unknown initial coverage remains null. These fields describe clocks, not crop maturity or animal cooldown values.

The Minecraft 1.21.8 hooks record completed `ServerWorld.tickChunk` calls when random tick speed is positive, and completed live server-side `PassiveEntity.tickMovement` calls after the native age/cooldown update. A scope receives one eligible tick only when every declared source was updated within that same completed advancing server tick. Duplicate callbacks cannot multiply credit. No callback means no credit: unloaded/out-of-range chunks and absent/non-ticking animals are not assumed to progress. No extra server lookup reveals their state.

The enclosing tick hook brackets the existing native server tick path. Debug pause skips it; native freeze/pause cannot advance the counter. A scope installed partway through a tick receives no partial credit. Reads during a tick expose only committed counters. An unfinished tick followed by another begin resets the clock session/identities rather than counting its incomplete updates. The observer's `throughTick` is advancing ticks since its own session began; it is distinct from the existing debug tick identifier.

Chunk clocks measure random-tick simulation eligibility, not whether a specific block was selected or met its light/space conditions. Passive-entity clocks measure native age-update eligibility, not wool regrowth, a successful birth or current breeding readiness. Behaviors must still observe their real target condition. Unknown cooldowns stay unknown. Counters do not provide resource feasibility, capability checks or permission to operate the player.

## Host and waits

Pass the same `progressScopes` array to `NativeObservationFeed`. Names must be distinct from its item-view names; their combined scope count is at most 32 and each has an explicit `ConditionWaits` grant. The feed sends the bounded declarations with passive captures, validates native scope kind/identity/counts and projects the counter plus `lastTickEligible`. The native source and frame provenance remain explicit. An unavailable counter gives an unavailable projection, not zero known elapsed time.

The existing wait contract accepts `{ deadline: { clock: 'eligible_ticks', scope: 'farmClock', ticks: 200 } }`. A known monotonic counter advances that budget. A new identity, unknown/stale coverage or a frame gap resets its accounting anchor. A deadline outcome does not assert readiness or acquire the player. Grant-filtered offer views can also see these projections, subject to their existing copied-view limit.

Counter regressions within one identity, contradictory native rows, wrong scope kinds and absent required coverage are rejected or remain explicit unknowns. Progress metadata never changes the ledger's physical ownership or admission path. Item scopes continue to publish `eligibleTicks: null`; they cannot borrow an unrelated farm or sheep counter. The feed still does not call `work.advance`: overdue-root age needs continuous evidence of that root's actual feasibility, including resources and authority.

## Verification status

Focused native tests cover completed versus partial ticks, duplicate updates, missing sources, paused time, dimension mismatches, bounded/atomic registration, unchanged/rebound identities and broken tick continuity. Host tests exercise explicit native progress through a passive wait, unknown coverage and deadline completion, plus malformed projections and configuration bounds. The native boundary set passes 41 tests and the complete JavaScript suite passes 276. The full Gradle build passes; both axes of the [source review](progress-review.md) have no remaining findings.

A live copied-world qualification exercised the actual chunk and passive-entity hooks through the public wrapper. Nearby chunk and sheep counters held at 29 throughout repeated paused reads, then advanced to 30, 31 and 32 across three explicit server steps. A distant unloaded chunk stayed at zero. Two independently installed copies of one validated generator revision completed their three-tick deadlines; the distant-scope copy remained pending, and a separate generator returned while all three waited. No physical action was submitted and the actor remained free. Independent observation confirmed the counters and released lease. This proves the native progress/wait connection in one controlled case; it does not establish maturity, continuously feasible root aging or mixed-duty efficiency.
