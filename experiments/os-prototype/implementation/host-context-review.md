# Host context review and qualification

Scope: durable host ownership and automatic shared-visit scheduling on the native retained-window boundary. Baseline `1800c757f5d1d2257448595c5177fbf63c870733`; unrelated prototype and JourneyMap work is excluded. The implementation follows the handoff, coordination walkthrough, scheduling decision #58 and native/broker contracts. Review snapshots and execution logs are retained under `run/os-implementation/host-context-review-20260916/`.

## Standards

The independent review found no documented-standard breach or actionable Fowler smell. It found one correctness issue: recording a budget rejection from inside a promise rejection handler could itself throw after trace failure, causing an unhandled rejection. Cleanup tracing now contains that failure, the work service retains its fault, and native revocation/cleanup continue. A real trace with a failing external writer reproduced the failure before correction and passed afterwards. The corrected source and final test completion-barrier change were cleared.

## Spec

The independent review found one defect: if context cancellation was lost before reaching native, inspection alone left a ready context held indefinitely under renewed heartbeats. Uncertain cancellation now propagates to the coordinator, which revokes the lease and reconciles both physical obligations. Tests cover loss before delivery and after acceptance. The direct broker contract explicitly requires fatal handling and stop/drain. The corrected review has no remaining findings.

Further focused corrections check the visit budget again at fresh admission, preserve recurring authorship time across boundaries, reconcile externally revoked ready contexts, ignore older passive captures without accepting clock regression, and count a completed child once across different polling orders. The guest validator preserves existing typed malformed-work rejections. The new sandbox test waits for both context and activity/accounting completion before inspecting the journal.

## Validation

The first focused set passed 100 of 101 tests; its old malformed-context case exposed the typed-rejection regression corrected above. The initial full JavaScript run passed 315 of 322 tests. Six failures reported `runner_rss_unavailable`; the new shared-visit guest test also inspected the journal before both cleanup paths had drained. The corrected installations/behavior-loop recheck passed all 43 tests with production limits unchanged. The final full run after stopping the live client passed all 322 tests, including 14 host-context cases. OS JavaScript syntax and whitespace checks pass. Both original failures and reruns remain in the evidence directory; no root Java suite rerun is implied.

No native Java or Gradle source changed in this slice. Their hashes match the preceding native qualification. That slice passed 51 focused native tests but retained one unrelated planning-benchmark timeout in the full root suite; this host change does not claim to resolve it.

## Live scope

The prepared copied-world probe installs two independently owned sandboxed generators requesting two and three strings, plus a passive stock observer. It checks one retained context, two separately accounted child operations, automatic closure, settled journal and complete native history. Independent reopening verifies the final stock totals. This is a functional composition check, not a throughput comparison or native opening/pen qualification.

The initial attempt failed during runner installation with unavailable RSS samples before any `os_submit`. Its trace includes a missing reading and a timed-out `/bin/ps` request; all native obligations and the lease were released. The incident export contains 342 observations, a complete footer and no truncation. Twenty subsequent direct samples using the same 200 ms timeout passed, with a maximum measured duration of about 22.1 ms. That diagnostic does not establish the cause of the earlier missing reading, and no watchdog or memory limit was relaxed. Final live results are recorded in `STATUS.md`.

Remaining work includes context-aware native eligibility, navigation/opening, pen and other homestead operations, then mixed-duty evaluation. No general fairness or efficiency claim follows from this slice.

Review totals after correction: Standards 0 remaining findings; Spec 0 remaining findings.
