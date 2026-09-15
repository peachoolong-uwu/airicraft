# Recurring offers review

Baseline: `38bbb7200467f41a9f700be40c47f391f5d8cf98` on `os-exp`. This slice connects installed offer definitions to granted native item views, atomic recurring declarations and the existing work/admission path. It preserves unrelated prototype and JourneyMap edits. Frozen source/test/document trees, hashes, patches and test logs are retained under `run/os-implementation/offers-review-20260916/`.

## Standards

The initial review found no hard documented violations and one actionable naming heuristic. `#retained` excluded an admitted record withdrawn from current declarations even though physical cleanup still retained it. The helper is now named `#budgetedRequests`, making the capacity distinction explicit. The reviewer separately reported the trace correctness issue below.

The corrected review verified all fifteen snapshot hashes and reported zero hard documented violations and zero actionable heuristics. Review was source-only; reviewers performed no edits, tests, builds or live probes.

## Spec

The initial review found one P2: a guest-valid batch close to the 2,048-node wire limit gained enough trace metadata to exceed the trace copier's same node limit. That could stop the shared trace and revoke unrelated authority. A public boundary regression reproduced `message_limit` with one valid work effect carrying 2,022 padding values. Trusted trace payloads now allow 4,096 nodes within the unchanged 64 KiB event limit; the 16 KiB/2,048-node guest wire remains unchanged.

A separate performance advisory identified repeated native preparation of unchanged requests on every host pulse. A regression measured twenty preparations across twenty pulses using one capture. The feed now assesses each pending ID once per capture/generation, includes newly registered IDs, and prunes the cache to current requests. Actual admission retains its independent fresh native read.

The corrected review verified all fifteen snapshot hashes and reported zero remaining scoped Spec findings or scope creep. Native growth eligibility, domain operations, retained contexts, workers and mixed evaluation remain explicit follow-on integrations.

## Validation

The initial focused set passed 65 cases. The trace-envelope and repeated-preparation regressions first failed, then the targeted work/native-feed/trace set passed all 32 cases. The final complete JavaScript suite passed all 272 tests without skips in 23.28 seconds. Earlier red and intermediate logs are retained. No Java source changed in this slice.

A prior red test also demonstrated that withdrawing/reintroducing an offer could reset its retry delay. Deferrals now belong to the consumer root and operation, with bounded metadata, so changed arguments, identities or children cannot reset them. Other tests cover atomic rejection, duplicate identity, stale authoring/results, grant-filtered and oversized views, retained active withdrawal, cancellation of a recurring child after its parent returns, independent generator progress, and repetition only after new observation.

The corrected host source passed a live microcase using two installed QuickJS offer duties and an independent waiting generator. One native six-string transfer met the common observed target; both duties withdrew work, the observer returned, and journal/lease/trace evidence settled cleanly. Independent reopening confirmed 122/0 became 116/6. The copied fixture, pinned revisions, source/build manifest and recorder export are under `run/os-implementation/offers-qualification/`. The owned client was stopped. This is one functional stock case, not repeated live production, mixed duties or efficiency evidence.

Review totals: Standards zero remaining after one resolved heuristic; Spec zero remaining after one corrected P2. The separate repeated-preparation advisory is also corrected and regression-tested.
