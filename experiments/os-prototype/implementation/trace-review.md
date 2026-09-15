# Host trace and supply declaration review

Baseline: `b6c5d273d164ff4f593e3e0721516e3923735772` on `os-exp`. The slice includes `DecisionTrace`, supply dependency validation, their ownership/admission/native hooks and focused tests. It does not implement the complete opportunity/timing observer, native event-gap reader, author query API, archive reservation or mixed benchmark. Frozen source and review manifests are retained locally under `run/os-implementation/trace-review-20260916/`.

## Standards

The independent source review reported zero documented-standard violations and zero actionable Fowler heuristics in both the initial snapshot and the follow-up covering rejection evidence and charging ordinary progress to the ordinary trace budget.

## Spec

The independent source review found one P2: rejected admissions disappeared from the trace, so a later admitted candidate could not be explained against the rejected request's stock basis. The correction records the bounded request, reason and available owner/capture/resource evidence before returning the original error. Trace-recording failure still begins safe drain. The follow-up confirmed the finding resolved and no remaining Spec findings within this slice.

The review also checked that ordinary ACCEPTED/RUNNING progress cannot spend the cleanup reserve. Terminal, reconciliation and drain records may use it. Reviewers inspected source only and did not run tests.

## Validation

Focused activity/effect/trace/supply tests pass 29 cases. They exercise real file output, refusal to overwrite an existing run, copied ordered records, mandatory child outcomes, activity/native/claim correlation, rejected decisions, quota/queue bounds, a stalled writer, broken-disk revocation, and original-error preservation when rejection recording exceeds its limit. The complete JavaScript regression passes 108 cases, recorded in the local review directory. These are host contract tests; the new trace has not been qualified in a live Minecraft run.
