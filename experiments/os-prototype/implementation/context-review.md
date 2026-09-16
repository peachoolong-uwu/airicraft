# Native retained-context source review

Baseline: `20bb42a6b7de1ae68db6864a62f4f8860ef2a77b`. The review covers the native runtime, container adapter, driver schema, standalone broker compatibility, their focused tests and the native protocol. Preserved prototype/JourneyMap edits are excluded. The initial and corrected eight-file patches and SHA-256 manifests are in ignored `run/os-implementation/context-review-20260916/`.

This is a dependency slice of the [implementation handoff](../design/implementation-handoff.md), implementing the native context boundary needed by the contracts settled in issues #58 and #62. It adopts an already-open window. Automatic host visits, navigation/opening, native pen operations and the mixed scenario are still pending.

## Standards

The independent reviewer found no documented violation. One possible duplication in window admission was corrected: retention and transfer now share exact identity, sync ID and empty-cursor validation while preserving one current capture for affected-slot checks. The corrected review has no remaining findings.

## Spec

The independent reviewer found two defects, each reproduced by a failing public-boundary test before correction:

- A failed child settling after context cancellation could leave the context classified as cancelled. Failure now accumulates independently of the preserved first stop reason.
- Context watchdog reporting paused while a reflex held the player. Entry/exit deadline reporting now continues during observation-only handback waits without permitting physical effects or claiming release.

The corrected review cleared both findings and found no new spec defect or scope creep. Automatic visits and live qualification were assessed as separate gates.

Remaining findings: Standards 0; Spec 0.

## Validation

The corrected focused native set passes 51 tests: 28 container-boundary tests, 20 native-runtime tests and 3 availability-clock tests. Context cases cover distinct transfers sharing one window, exact identity and setup gates, receipt pressure, host expiry during a partial transfer, delayed closure, external closure, reflex revocation, cleanup exceptions and failure ordering. The Minecraft boundary is a controlled fake in these tests.

The first complete JavaScript run passed 305 of 307 tests. Its two failures were an existing child-process commit timeout and an unavailable runner RSS reading. Both passed unchanged in an isolated recheck. Their cause is not established; no deadline or memory limit was relaxed. The initial failure and recheck logs are preserved.

The unchanged JavaScript rerun passes all 307 tests. Two complete Gradle builds each ran 1,375 root tests and hit the same one-second `productionPlanningPlansDiamondFromEmptyInventoryWithFullRecipeNoise` timeout; 1,372 passed and 2 were skipped. The unchanged 51-test planning class passed alone between those runs. The full root suite is therefore not recorded as passing. Neither the planning implementation nor its timeout was changed by this milestone.

The remaining packaging and module checks passed with `build -x :test`, which explicitly excludes only the already-recorded root test task. Wrapper and advisor tests were unchanged/up-to-date, and compatibility artifacts built successfully. This does not convert the failed full root suite into a pass.

## Live qualification and limits

The reviewed source passed a trusted-native probe on `OS-Contexts-20260916-152907-1b0c6cf6`, a fresh copy of the archived survival fixture, with JourneyMap and REI enabled. Two separately identified requests transferred two and three strings through one retained window. Each operation confirmed an empty cursor and its release back to the context; the context remained owned. Duplicate submissions returned the unchanged settled receipts. Context cancellation then verified server/client closure and free controls before the lease was released.

Independent reopening confirmed chest/player stock changed from 122/0 to 117/5. Native history through sequence 31 had no gap. The structured recorder export contains 403 observations through server tick 3,019, an `export_complete` footer, no truncation and no reported drops. Initial read requests timed out while the world was loading, before native admission; those failures are preserved. The driver was confirmed responsive and idle before opening the chest.

Evidence is under ignored `run/os-implementation/context-qualification/`: fixture archive identity, build/source manifests, `contexts-1789545171724-result.json`, request/receipt log, before/after verification and `qualified-incident.jsonl`. All 785 recorded source hashes match the built/live source, including preserved unrelated edits identified in the manifest. The original save was preserved and the owned client stopped after export.

This qualifies the retained native window and distinct operation boundaries. It does not qualify automatic host visit scheduling, context budget enforcement, navigation/opening, shared pen work, a mixed run or efficiency. Interruption/timeout orderings are deterministic tests, not newly exercised live failures.
