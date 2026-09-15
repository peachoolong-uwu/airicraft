# Isolated execution review

Baseline: `85364120dff25916a0ddd12a92c7eb0c8176b71f` on `os-exp`. Exact initial and corrected source snapshots are retained under `run/os-implementation/runner-review-20260916/`. Review covers VM isolation, typed framing, root compute/process supervision and broker lifecycle integration. Definition storage/validation/installation, actual effect services and the mixed scheduler remain later work.

## Standards

The independent Standards review found zero documented-standard violations and one actionable Fowler heuristic: execution budgets were repeated in the VM, scheduler and token bucket. One versioned `execution-policy.mjs` now supplies those values. The corrected thirteen-file snapshot has zero remaining hard violations or actionable heuristics.

## Spec

The independent Spec review found one P2: the runner confused increasing invocation IDs with required VM initialization order. Two already-owned siblings could therefore fail if the later ID initialized first. The runner now checks wire sequence and live identity collisions, while the broker remains the invocation-lifetime authority. A regression initializes siblings in reverse order, joins both, and rejects recreating a completed child.

The corrected snapshot has zero remaining Spec findings within this slice. Review also covered memory-read diagnostics/coalescing, unchanged resource limits, the pinned runtime handshake and cleanup of partially allocated initialization handles. Both reviews were source-only.

## Validation

The corrected focused VM/process/pool/broker regression passes 26 tests. Process-fault tests stop or kill owned test runners; no Minecraft process is involved. Separate footprint probes preserve the initial unavailable-reading failure and subsequent full-root measurements. The first full regression found one overly specific expectation: under parallel load the infinite initialization hit its wall guard before its CPU guard. The test now accepts either configured independent bound; production code and limits were unchanged. All 124 JavaScript tests pass in the complete rerun. Final footprint results and evidence paths are recorded in [STATUS.md](STATUS.md).
