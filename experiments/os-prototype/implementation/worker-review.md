# Worker function source review

Fixed base: `acb22c3dc5f1a09b92c35a67749b70f1182bf55d`. The review covered the worker service, inference-only HTTP transport, immutable definitions, scoped evidence, supervised loop integration, reference definitions and their tests/API documentation. Preserved prototype and compatibility edits were excluded. Specs: issue #59's callable workers and budgets, the related lifecycle/library contracts in issues #60 and #63, and the [worker API](worker-api.md).

## Standards

Initial review found one P1: a trace failure while dispatching queued inference could escape a background callback after reserving an active slot, leaving an unsent job and failing to supervise its callers. Fallible trace setup now precedes provider-slot and budget reservation. Polling, draining and asynchronous completion contain unexpected faults, and trace failure stops the service through ordinary runner/broker supervision.

The reviewer reran the synchronous trace-capacity reproducer: two actual provider dispatches were charged, all three callers failed through supervision, and late settlement left no active, queued or retained requests. The regression suite also exercises an asynchronous real decision-trace writer failure. Correction report: no remaining documented violations or actionable smell findings.

The later batching and deadline test corrections were reviewed separately. Both use observable request admission rather than assuming independent VMs finish in installation order; their existing behavioral assertions remain intact. Final Standards findings: 0.

## Spec

Initial review found two P2s. First, cancellation detached the final subscriber before preserving its progress basis, leaving an unchanged failure unable to become eligible for reconsideration. The service now retains the last verified basis; the regression permits reconsideration after progress advances from 100 to 1,300 eligible ticks.

Second, the original backoff covered failed inference but did not prevent successful or cached advice from repeatedly reconsidering the same physical failure after input rewording. An immutable optional fingerprint path now gates new and cached advice using revision, profile, epoch and verified material evidence. The reference strategy worker declares this rule. Identical pending subscribers can still share one computation.

Correction report: both requirements are satisfied, with no remaining Spec findings. The final test-only corrections preserve queue/inference deadlines, retained cancellation charges, late-result isolation and shared-supply semantics. Final Spec findings: 0.

## Verification and limits

The corrected focused set passes 69 tests through the real supervised QuickJS runner and a real loopback HTTP server. The complete JavaScript suite passes all 306 tests (`full-suite-passed-fixtures.log`). The forced out-of-order deadline reproducer failed with the old test and passes after deriving active callers from actual transport dispatch. Provider schemas, streaming byte limits, token accounting, cancellation, stale evidence, shared subscribers, fallback containment, cooldown and trace-fault supervision are covered. No Java source was changed in this milestone.

Earlier full-run failures remain recorded. One memory probe reported unavailable RSS; an offer test later timed out without enough evidence to establish its cause. Their isolated diagnostic runs passed without relaxing runtime limits. Two additional failures had reproducible test assumptions: shared-supply batching requires both demands to exist before admission, and inference arrival order need not equal installation order. The tests now establish the former precondition and observe the latter order. These corrections do not alter production scheduling.

The saved reference library qualification at `run/os-implementation/worker-qualification/library-1789540300189/result.json` validated `comment_structure`, `interpret_strategy`, their two pure fallbacks and the reusable `choose_strategy` behavior. Its 28-event trace is complete; all invocations and retained children were reclaimed. The reviewed production source hashes match this artifact. Subsequent changes were test fixtures and reporting only.

No real model profile, paid inference or Minecraft client was used. Model quality, live worker latency, currency limits, relevance selection for mixed-duty observations and the complete homestead experiment remain unqualified. The HTTP protocol's output-token cap is not a total-cost ceiling.

Ignored review evidence is under `run/os-implementation/worker-review-20260916/`: issue snapshots, original/corrected manifests and patches, regression logs, failure diagnostics and reference qualification script. Remaining findings: Standards 0; Spec 0.
