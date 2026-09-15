# Broker foundation review

Baseline: `9b4c153444a0b9b4bd0f15de020c6622a40f9c36` on `os-exp`. Review includes only the new `src/os/` host modules, their tests, native authority corrections, and these implementation notes. Preserved prototype, gameplay and JourneyMap edits are excluded. Frozen source and manifests are retained locally under `run/os-implementation/broker-review-20260916/`.

## Standards

The independent Standards review found no documented-standard violations and one actionable heuristic: native request identity was represented inconsistently across journal lookup, receipt reconciliation and output credit. A shared validated tuple now supplies all three. A regression reorders ID fields between partial and final output without changing identity or assigning duplicate credit.

Re-review of the corrected snapshot and failure-drain delta reported zero remaining hard violations and zero actionable heuristics. This was source review; the reviewer did not run tests.

## Spec

The independent Spec review found four initial correctness issues. Cancellation during an asynchronous native reply could still receive output credit; journal failure during polling did not drain native authority; failed startup could acknowledge release before discovering old journal obligations; and a lost preflight rejection after sequence admission could leave its journal entry permanently unqueryable.

The corrections recheck withdrawals at accounting time, latch infrastructure failure and revoke authority, begin with recovery unproven, and retain zero-effect native rejection receipts. Receipt retention preserves active work and uses settlement order so a newly completed operation is not immediately evicted after many rejected requests.

Re-review found two additional interactions. Failing one child could retire its sibling before the failure loop reached native revocation. Revocation now starts before notification, and the loop rechecks current subscribers. Native-origin cancellation could also leave a supply open to new subscribers; a stopping receipt now marks the supply reconciling while preserving existing output and cleanup accounting.

The final source re-review confirmed all reported Spec findings resolved. Live qualification and later roadmap layers remain separate gates; no full-runtime or efficiency pass follows from source review.

## Validation

The nine integrated coordinator tests cover protected stock, shared cancellation, writer failure, cancellation during admission/inspection replies, rejected admission, tuple identity, in-flight sharing, sibling failure propagation and native-origin cancellation. The complete JavaScript regression passes 100 tests, including 50 existing prototype and 50 new host tests. The 33 focused native tests pass after updating the rejection contract. Real broker transfer/restart evidence is recorded in [STATUS.md](STATUS.md).

Live setup exposed timeouts in the legacy status handshake before native admission. The adapter now verifies driver mode with a discarded read-only native observation; subsequent admission observations retain the same full-round-trip freshness rule. A focused handshake regression passes, and a source-only Spec follow-up found no safety regression in this change.
