# Resource service review

Baseline: `8184b3ed58f1523590621b5388ac9a49673a670f` on `os-exp`. The frozen ten-file snapshot covers the resource service, ledger allocation views, generator-loop integration, their tests and documentation. Preserved prototype, gameplay and JourneyMap edits are excluded. Source hashes and the patch are retained in `run/os-implementation/resource-review-20260916/`.

## Standards

Independent source review reported zero documented violations and zero actionable Fowler heuristics. It verified all ten snapshot hashes and found invocation ownership, root stock floors and unresolved delivery accounting preserved. Allocation calculations and the simulated native fixture are shared; native admission remains with the coordinator.

## Spec

Independent source review reported zero findings and no scope creep. Root/helper floor identity, owner/sequence retry fencing, cumulative credits, cancellation retention, epoch checks at consumption, allocation metadata and generator delivery gating match the declared contract. Automatic supply selection, global scheduling and complete runtime epoch-stop integration remain subsequent work.

## Validation

The focused service/ledger/activity/loop regression passes 47 tests. The complete JavaScript run passes 197 tests with `--test-concurrency=2`, without skips. Thirteen new tests include actual supervised QuickJS generators sharing one journalled transfer, one subscriber withdrawing while another continues, delivery completion waiting for physical release, and stock shortages becoming unknown after effects until refreshed.

The directory above retains the initial failing and subsequent passing regressions, the frozen source, `focused.log` and `host-regression.log`. Reviewers performed no tests or live operations. Native transport in the integration cases is simulated, and the test host selects the shared activity. No automatic supply-selection, new Minecraft qualification or efficiency claim follows from this slice.

Review totals: Standards zero; Spec zero.
