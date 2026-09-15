# Native item feed review

Baseline: `5c4aba049dfaad352ce674b20f1b907afe988188` on `os-exp`. Exact source/test/document snapshots, hashes and patches are retained under `run/os-implementation/native-feed-review-20260916/`. This change connects passive native item captures to the existing wait, resource and scheduling contracts; it preserves the unrelated prototype, gameplay and JourneyMap edits.

## Standards

The initial independent review found no hard documented violations and one actionable Fowler heuristic: the passive container projection duplicated the destination-capacity formula used by actual preparation. Both paths now use the same small `capacityFor` function, keeping stack/component behavior aligned. The reviewer also identified the hash-envelope issue reported under Spec.

Corrected snapshot verification reports zero hard documented violations and zero remaining actionable heuristics. All twenty frozen hashes match. Reviews are source-only; reviewers run no tests, builds, probes or edits.

## Spec

The initial independent review reported two P2 findings:

- The feed accepted native frames up to 512 KiB/8,192 traversal nodes, then hashed them with the default 16 KiB/2,048-node envelope. A populated 90-slot handler plus 36 main-inventory slots exceeded that smaller bound and stopped the feed. Hashing now uses the same trusted capture envelope. A populated, component-aware regression first failed with `message_limit` and now passes. Guest view limits are unchanged.
- Readiness did not compare the prepared supply output with the proposal's component identity. A variant mismatch could reach the coordinator's guard and be treated as an infrastructure failure. Readiness now blocks incompatible output, and a mismatch arising only at fresh admission uses the expected per-rule rejection/backoff path. Separate regressions cover both races, preserving the exact demand and allowing independent finite work.

Corrected snapshot verification reports zero remaining actionable Spec findings and confirms all twenty frozen hashes. No other scoped omissions or scope creep were reported.

## Validation

The final focused JavaScript set passes 57 tests. The complete `npm test` suite passes all 260 tests without skips in 24.63 seconds. Focused root Gradle tests pass 19 `NativeActionRuntimeTest` cases and 15 `NativeContainerActionsTest` cases; the native adapter compiles. Red, intermediate and final logs remain retained.

An early Java test used an unavailable `JsonArray.getFirst` method; after correcting it, the intended missing-inventory assertion failed before implementation. The first generator integration also exposed a simulated transport returning mutable receipt objects, unlike the real JSON boundary. Its delivered responses now have independent copies. The test and implementation limits were preserved. A misplaced test option was corrected separately; original failures remain in `loop.log` and `loop-corrected.log`, followed by the passing `loop-transport-copy.log` and full suite.

The corrected production host source also passed a live six-string microcase using two installed QuickJS generators, the native observation feed, automatic supply selection, real transfer receipts, passive inventory confirmation and verified release. Fresh reopening confirmed chest/player totals of 116/6 from 122/0. The prepared fixture, immutable source revision and deterministic validation, build/source manifest, native log, decision trace, effect journal and recorder export are retained under `run/os-implementation/native-feed-qualification/`. The original save was preserved and the owned client stopped. This is not a mixed-duty or timed-efficiency qualification.

Review totals: Standards zero remaining after one resolved heuristic; Spec zero remaining after two corrected P2 findings.
