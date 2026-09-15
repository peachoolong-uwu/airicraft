# Supply proposal review

Baseline: `0d785ed5c52d8c83984d429749535c70e5576f38` on `os-exp`. The frozen seven-file source/test/document snapshots, manifests and complete patches are retained under `run/os-implementation/supply-planning-review-20260916/`. This change derives bounded alternative supply proposals and consumer demand estimates; it does not add another admission or player controller. Unrelated prototype, gameplay and JourneyMap changes are excluded.

## Standards

Independent review reported zero documented violations and zero actionable Fowler heuristics. A separate correctness advisory identified insufficient traversal headroom: a valid large argument template could pass construction but exceed the message node limit after proposal metadata was added. A diagnostic against the original frozen implementation reproduced acceptance followed by `message_limit` from `offers()`.

Template admission now reserves headroom with 1,024-node/depth-twelve limits inside its existing 4 KiB bound. The regression rejects oversized traversal at construction and builds full proposals containing 32 owners, 32 delivery IDs and twelve target shares with bounded arguments. Corrected-source verification reports zero remaining findings.

## Spec

The initial review found one P2: an outstanding delivery restricted to a method without an installed rule could suppress independently permitted floor restocking. The planner now supplies its installed resource-to-method routes to the union calculation. Only applicable outstanding deliveries suppress extra floor production; actual allocated output remains counted even if its rule is removed. The restricted delivery is never credited by a different method. Both the missing-route and multiple-installed-method cases have regressions.

Corrected-source verification reports zero remaining Spec findings. Both reviewers verified all seven snapshot hashes and performed no tests, builds, edits or live operations.

## Validation

The focused resource, supply-planning and scheduling suite passes 28 tests. The complete JavaScript suite passes 228 tests with `--test-concurrency=2`, without skips; the corrected full run completed in 27.24 seconds. Final logs are `corrected-focused.log` and `host-regression-corrected.log`. Initial failing regressions, the original-envelope diagnostic and the earlier 226-test full run remain retained.

Tests use real broker, ledger, resource service and scheduling policy components. They establish copied bounded proposals, consumer/quantity accounting, in-flight allocation handling, stale-resolution rejection, grants/method restrictions, closing-child behavior, and eligible-root visibility at the declared limits. Native availability, physical admission, shared context handling and efficiency have not been qualified by this slice.

Review totals: Standards zero; Spec zero remaining after one corrected P2. The separate envelope correctness advisory is also resolved.
