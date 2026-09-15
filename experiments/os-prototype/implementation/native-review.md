# Native authority review

Baseline: `643578b860350d7ffc98d63096ae08a44f507188`. Two independent reviewers inspected the native-only staged slice. Unrelated prototype and JourneyMap changes were excluded. This review covers the first guarded-transfer boundary; later broker and domain implementation remains pending.

## Standards

- **P2, documented rule:** the physical fence also blocked `configure_reflex`. ADR-0002 permits inspection and policy changes during a reflex. Correction: retain policy/cancellation access while denying competing actuation, covered through the native driver boundary.
- **P2, correctness risk:** deleting container contexts at a lifecycle boundary discarded the only remaining reconciliation source. Corrected: pin the active context across lifecycle changes, recognize affirmative detached/closed handler evidence, and report incomplete accounting after an external closure. An unreachable old server still leaves release unresolved.
- **Judgment call, possible duplicated code:** the transfer and envelope validators both decode bounded strings and exact integers. This is a small duplication across different schemas and error codes; no general decoder is introduced in this slice.

## Spec

- **P1:** arbitrary vanilla right-clicks are not simple stack arithmetic. A filled bundle can extract contents into the destination and violate the requested item/quantity allowance. Corrected: constrain the initial adapter's transferable items to advertised reference resources/tools and reject unsupported items before admission.
- **P2:** full-window equality invalidated cleanup when unrelated slots changed. Decision 56 requires relevant predicates. Corrected: guard the selected source/destination slots, cursor and window identity, while allowing unrelated changes.

No additional scope creep or missing first-slice requirements were reported. Reviewer conclusions are based on source and local mapped Minecraft bytecode, not a live gameplay trial. Initial totals: Standards 2 actionable findings plus 1 heuristic; Spec 2 actionable findings. Regression and live results are tracked separately in [STATUS.md](STATUS.md).

Both reviewers checked the corrections and reported no residual defect in those findings. The corrected native tests (30) and existing EmbodiedAgentRuntime tests (115) pass. The earlier full root run had one one-second planning timeout among 1,344 tests; its complete 51-test class passed when rerun alone. Wrapper (97), advisor (10), and prototype (50) checks passed. The timeout is retained in the regression record; the full run is not relabelled an uninterrupted pass.
