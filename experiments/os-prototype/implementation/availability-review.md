# Availability evidence source review

Fixed base: `434d88c70c0fb45536886565c473b29643c610bc`. The review covered the 19 owned source/test/API files recorded in the frozen manifest, excluding preserved prototype and compatibility edits. Specs: issue #56's observation contract, issue #58's eligible-root/FIFO rules, and the [availability API](availability-api.md).

## Standards

Initial review found no hard documented violations and two judgment calls: duplicated client/server window projection and an authority-notification data clump. The correction shares window projection through a caller-owned slot reader, preserving server thread ownership and stack caching. One immutable `AvailabilityGate` now carries authority notifications.

Correction report: “All 19 corrected hashes match. Both findings are resolved: window projection is shared, and `AvailabilityGate` carries the authority notification as one immutable record. The accounting delta restores the authored selection view in `finally`. No remaining hard violations or actionable Fowler heuristics found.”

## Spec

Initial review found one P2: a recurring declaration awaiting reevaluation could prevent an unrelated finite root from receiving its covered interval, so that root could miss entering overdue FIFO. Retained intent now earns age from fresh native feasibility independently of reevaluation. The fresh-authorship gate still applies before execution selection.

Correction report: “P2 resolved. Eligibility now credits continuously retained requests independently of recurring reevaluation, while `finally` restores the fresh-authorship gate for selection. The regression covers overdue entry and prevents stale recurring execution. All 19 corrected hashes match. The accompanying native refactors preserve the reviewed behavior. Zero remaining Spec findings in these corrections.”

Both reviews were independent and source-only; neither reviewer ran tests or live probes. Remaining findings: Standards 0; Spec 0.

## Verification

The corrected source passes all 288 JavaScript tests and the full Gradle build: 1,362 root tests passed, two skipped; 97 wrapper and 20 JourneyMap tests passed. The focused native availability/action/container set passes 40 tests. The JavaScript integration cases cover duplicate offers, shared consumers, recurring refresh and withdrawal, native/host gaps, allocation changes that return to the same state, paused counters, unsupported adapters, malformed evidence and lease revocation. The review regression failed at 2,390 versus the required 2,400 ticks before the correction and passes afterwards.

The initial full runs also passed (287 JavaScript tests before the review regression; full Gradle build). Earlier failing checks are retained: missing APIs during red/green development, an initial missing test import, and a shared-supply fixture that requested resources before its first observation. The corrected fixture observes before requesting; production behavior was not relaxed.

Ignored evidence: `run/os-implementation/eligibility-review-20260916/` contains spec snapshots, original/corrected source hashes and patches, red/green logs and full build outputs. Live evidence is recorded separately in `STATUS.md` after qualification.
