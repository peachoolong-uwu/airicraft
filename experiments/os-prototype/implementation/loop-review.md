# Generator service loop review

The fixed baseline is `afef8729e01245cbf77ac742966c63217999f136`. This slice connects installed generator VMs to passive observations, condition waits, child spawning and joins. It includes pinned-definition metadata and supervisor failure hooks. Resource/work/worker services, recurring offers, native projections and the default driver entrypoint remain separate integrations. Preserved prototype/JourneyMap changes are excluded.

## Standards

Independent review found zero documented violations and two heuristics: duplicated dependency resolution, and new execution limits outside the existing policy. Metadata inspection and spawning now share one private pinned resolver; generator/job limits use `executionPolicy.invocations`. Final review verified all seven corrected snapshot hashes and reports zero remaining violations or actionable heuristics. Review was source-only.

## Spec

Independent review found one P2 covering service argument validation. Missing observation scopes and malformed spawn policies/grant counts could reach lower layers as unexpected errors and terminate the caller. These inputs now return bounded typed rejections before child allocation. Regression cases failed before the corresponding corrections and passed afterward. Unexpected host faults retain the ordinary invocation failure path. Final review reports zero remaining Spec findings or scope creep. Review was source-only.

## Verification

The final complete JavaScript run passes 184 tests with `--test-concurrency=2`, without skips. Thirteen new integration cases use actual root processes and QuickJS VMs. They cover independent waits, concurrent and distinct child outcomes, parent return, late initialization/cancellation, both sibling failure policies, retained physical cleanup, invalid service arguments, bounded result envelopes and durable-retirement retry. Earlier focused installation/runner/loop runs also passed; the last full run includes every review regression.

An initial implementation attempt failed two typed-rejection assertions; effect dispatch now happens on the next host pulse so synchronous service rejections use the declared path. A further regression reproduced shutdown after the process had retired but its metadata acknowledgement was lost. Shutdown now skips terminal computation while retaining the host's metadata reconciliation obligation. Original failures and corrected results remain under `run/os-implementation/loop-review-20260916/`, alongside original/corrected manifest/patch/tree snapshots and `host-regression-corrected.log`.

Physical cleanup in these tests is injected broker evidence. The test library's validated status is trusted fixture setup; source validation has its own suite. No Minecraft action or model inference ran. These results establish service composition and ownership behavior, not live mixed-production efficiency.
