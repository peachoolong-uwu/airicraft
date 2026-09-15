# Automatic supply admission review

Baseline: `f0bef552be9eb964e3af8ceb28e447794f133eaa` on `os-exp`. The original and corrected thirteen-file source/test/document snapshots, manifests and patches are retained under `run/os-implementation/supply-admission-review-20260916/`. This slice connects supply proposals to the existing work scheduler and native-effect coordinator. Unrelated prototype, gameplay and JourneyMap edits are excluded.

## Standards

Independent initial and corrected-source reviews reported zero documented violations and zero actionable Fowler heuristics. The reviewer checked all thirteen corrected hashes, including the bounded metadata calculation, join service credit, regression tests and API documentation. No tests, builds or edits were performed by the reviewer.

## Spec

The initial review identified two P2 findings:

- Target-only expansion required new internal delivery records even when all 256 slots already held finite demands. Every proposal could reject at capacity, preventing any delivery from completing to free space. Procurement now reports remaining metadata slots and the planner limits target expansion accordingly. At full capacity, existing finite deliveries still produce positive batches; target-only anchors without space are omitted. A one-slot boundary also preserves an alternative for every eligible target root.
- A late subscriber could receive shared output without resetting its overdue scheduling age. Work-managed joins now use `WorkService.join`, which delegates grant/output validation to the coordinator and credits each newly participating consumer once per activity. Retried joins and additional subscriptions by the same consumer do not erase subsequent waiting age. Standalone coordinator use retains its lower-level join API.

Corrected-source verification reports zero remaining Spec findings and confirms all thirteen snapshot hashes. The public-interface regressions reproduce both original failures and verify their corrections. There was no scope-creep finding; native readiness, recurring offers, retained contexts and domain operations remain later integrations.

## Validation

The focused integration/resource/scheduling set passes 85 tests. The final complete JavaScript suite passes 243 tests with `--test-concurrency=2`, no skips, in 28.24 seconds. Final logs are `review-focused-corrected.log` and `full-reviewed.log`.

The original full run passed 239 of 240 tests. An older shared-demand test assumed independent VMs would register requests in installation order; it now identifies each request by its owning root. Its focused rerun passed 21 tests and the corrected full run passed 240 before the review regressions were added. Both original and corrected logs remain retained. Early integration assertions also distinguished broker attachment from native submission and allowed a withdrawn subscriber to settle while another subscriber retained physical ownership.

An additional transport regression found that work tracking could lose cleanup after the original proposer withdrew. The scheduler now retains the actual activity identity and all initial supply owners, including a later joined subscriber after the original owners depart. The regression demonstrates failure propagation, continued reconciliation and verified release without another native submission.

Tests use the actual invocation broker, ledger, supply planner, work service, coordinator and SQLite journal with a simulated native container. Real QuickJS generators declare demands and share automatic supply while an unrelated root completes. Readiness and native material progress remain controlled fixtures. No new Minecraft run, throughput result or completed mixed-duty experiment is claimed.

Review totals: Standards zero; Spec zero remaining after two corrected P2 findings.
