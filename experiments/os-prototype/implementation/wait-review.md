# Observation and wait review

The fixed baseline is `001ac22c07dd761f58291bf730d70d5a955e4d86`. The review covers scoped observation storage, declarative conditions, passive owned waits, their focused tests and API documentation. Native domain projections and the guest effect loop remain separate integration work. Preserved prototype and JourneyMap edits are excluded.

## Standards

The initial review found no documented violations and one possible Duplicated Code heuristic: observation paths had identical validators in two modules. Both now use one shared validator. Final independent review verified all eight corrected snapshot hashes and reports zero hard violations and zero actionable heuristics. This was a source-only review.

## Spec

The initial review found one P2: a frame accepted at the full message limit could exceed byte, depth or node limits after its export envelope was added. Projection admission now reserves that overhead within the unchanged guest boundary. Observation responses page through one independently captured scope at a time. Tests first reproduced oversized admission and failed export, then passed with the correction. Final independent review reports zero remaining findings and no scope creep. This was a source-only review.

## Verification

Sixteen focused tests pass. The complete JavaScript regression passes 171 tests with `--test-concurrency=2`, without skips. Evidence is retained under `run/os-implementation/wait-review-20260916/`: original and corrected manifest/patch/tree snapshots, `envelope-red.log`, `envelope-green.log`, and `host-regression-corrected.log`.

The final synthetic probe, `run/os-implementation/wait-qualification/probe-1789503091478.json`, records source hashes and 256 retained waits reading an 1,800-element observation. Its three updates took approximately 23, 53 and 20 ms. The original approximately 12-second repeated-copying case is retained with its original source hashes. These bounded offline measurements are not a sustained latency guarantee or Minecraft efficiency result. No Minecraft client or model inference ran for this slice.
