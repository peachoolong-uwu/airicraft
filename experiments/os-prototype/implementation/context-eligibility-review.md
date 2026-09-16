# Retained-context eligibility review

Baseline: `72c5eaf299c2805b02cb90bfbc03013b509fcf32` on `os-exp`. The slice connects the existing covered-material clock to ready boundaries inside an owned visit. It does not implement chest opening, navigation or mixed-duty feasibility.

## Standards

The first review found stale availability descriptions in `host-context-api.md` and `native-protocol.md`. Both now distinguish a covered scheduling boundary from player release. The final review also removed an outdated claim that automatic shared visits were still unconnected, while preserving the restriction to dispatch within the broker's own ready context. The corrected review found no remaining documented-standard violations or actionable Fowler smells.

## Spec

The first review found that a present context with a missing/null identity could borrow a null-bound proof. The validator now requires a non-null identity for every present context, in addition to exact identity, readiness and lease checks. Two native-feed regressions first reproduced the missing rejection, then passed after correction. The corrected review found no remaining Spec findings.

The reviewers also cleared two later test-only corrections: the malformed-proof fixture signs a valid null value while still omitting the field in its emitted envelope; a recurring-duty assertion now exposes the full failure outcome before retaining its existing successful-value check. No runtime limits or success criteria were relaxed.

## Validation

The native ready-boundary test first failed at confirmed entry, then passed after the gate change. The new host integration first rejected the unsupported v2 proof, then passed after native/host context binding was connected. It checks separate and duplicate root waiting age, paused coverage, child service and a new material/allocation basis. All 53 corrected focused native checks and 45 host context/feed checks pass.

The first live attempt caught a Java/JavaScript fingerprint mismatch before any native submission: Gson omitted the explicit null context binding while the host preserved it. The native encoder now preserves null fields. A literal cross-language digest test reproduced the failure before the correction and verifies nested/array nulls and the distinction between a null field and an omitted field. Both reviewers independently checked the digest and cleared the correction. The rejected live attempt and its 250-observation recorder export remain preserved.

The initial full root Gradle run reports 1,376 tests: 1,373 passed, two skipped, and the existing one-second diamond-planning benchmark timed out. Its 51-test class also failed that benchmark alone this time. The final full root run, after the null-encoding correction, reports 1,377 tests: 1,374 passed, two skipped and the same benchmark timeout. An initial isolated command used the wrong package and selected no tests; it is not counted as validation. Corrected-source packaging and the other module checks passed with only root `:test` excluded. The full root suite is not a pass.

The first full JavaScript run passed 326/327; an existing recurring-duty result lacked the expected value and then passed unchanged alone. After adding failure-outcome diagnostics, the next full run passed 325/327 with runner memory-reading failures. A later loop/worker check passed 27/38, reporting memory-reading and watchdog failures. Their logs remain preserved; the cause of the initial value failure is unestablished. These results do not qualify reliable sandboxed execution. Runner budgets and production supervision code are unchanged by this slice.

The corrected-source live attempt accepted both null-bound and retained-context proofs, passed paused-credit checks and one stepped-tick check, then stopped on a stale observation during the next debug step. It submitted a context but no transfer. After incident export and resuming the client, a fresh broker recovered that exact context with zero new action submissions, drained the journal and released ownership. Later fresh stock verified the unchanged 122/0 totals. The rest of the live eligibility probe remains incomplete; this is not a qualification pass.

Review snapshots, original failures and command logs are retained in ignored `run/os-implementation/context-eligibility-review-20260916/`. Detailed live evidence and open gates are recorded in [STATUS.md](STATUS.md).

Final source review: Standards 0 remaining; Spec 0 remaining. Execution reliability and the complete mixed experiment remain open qualifications.
