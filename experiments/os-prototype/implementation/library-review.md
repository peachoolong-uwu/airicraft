# Library source review and verification

The fixed baseline is `60bb2d7443993d9050a83fe64c27cc53208cc530`. This review covers the coherent library/contract/validation/installation slice, including the broker and runner result-validation hooks. It excludes the preserved prototype/JourneyMap work and the subsequent condition-wait work.

## Standards

Independent Standards review found zero documented violations and zero actionable Fowler heuristics in the original and corrected snapshots. Final review verified all fifteen source/document snapshot hashes. Reviews were read-only and did not run tests.

A separate correctness advisory concerned a hypothetical exception after recording retirement. The actual `DecisionTrace` cleanup contract returns null on failure, so that stated path cannot throw. Checking the analogous metadata path exposed a real durable-write/lost-readback ambiguity; identical retirement retries now use an outcome digest and changing the proof is a conflict.

## Spec

The initial review found three P2 defects:

1. Closing while replacement dependency resolution was pending could retain an already-terminal old root. Draining roots now retire even while invalidated preparation is pending; the late preparation cannot publish a replacement.
2. Offer arrays bypassed the declared output contract. The broker's result validator now applies to every runner offer result, as well as terminal generator returns, before callers receive it.
3. A full valid outcome could exceed the retirement envelope after adding release metadata. Lifecycle envelopes now provide bounded byte, depth and node overhead without changing guest limits.

Follow-up review extended the third case to deep/numerous values and found that a lost installation-record reply could leave an orphan binding. Installation now marks its metadata attempt before writing, queries the resulting lifecycle state during cleanup, and retains the root if metadata is unavailable. A discovered active binding is retired before the root is forgotten.

All reported findings have regression tests that failed before their corresponding correction and passed afterward. Final Spec review reports zero remaining findings; no scope creep was identified. These were source reviews, not gameplay qualification.

## Verification

The final focused run passes 46 tests covering the library, validator, installations, contracts and existing broker/runner behavior. The complete JavaScript regression for this slice passes 155 tests with `--test-concurrency=2`; no skips. This includes 31 new library tests and the existing 124 tests. The adjacent three condition-compiler tests are excluded from this commit and regression count.

Evidence is retained locally under `run/os-implementation/library-review-20260916/`: original, corrected and `envelope-corrected` manifest/patch/tree snapshots; failing and passing regression logs; `final-focused.log`; and `host-regression-final.log`. Earlier full runs passed 147 and 152 tests before the additional review regressions were added.

One early focused attempt failed at a validation-success assertion before the assertion included the validator's reason. Its cause was not established. The assertion now captures the bounded evidence; subsequent focused and complete runs passed. No runtime guard was relaxed to obtain a pass.

The same immutable definition was exercised with two distinct input digests and separate invocation outcomes in real supervised VMs. Physical cleanup in these tests is controlled broker evidence. No Minecraft client or model inference was started for this slice; it establishes deterministic lifecycle behavior, not mixed-production efficiency, live worker value, or safe replacement on every native operation.
