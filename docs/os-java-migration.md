# Java OS migration

The user selected an in-mod Java OS with embedded GraalJS on 2026-09-16. [ADR-0003](adr/0003-embed-airicraft-os-in-java.md) replaces the standalone Node/QuickJS host. The previous implementation remains available at Git baseline `0af7f5282ce2097bf62580510630a7752e533aa9`; its additional working-tree prototype source is preserved under the recovery path recorded in ignored `run/os-java-migration/recovery-path.txt`.

## Migration gates

- [x] Embed GraalJS using Gradle and Java 21; exercise actual JavaScript generators and declarative offers through the Java skill interface.
- [x] Move invocation ownership, skill library/lifecycle, observations/waits, resource accounting, scheduling/contexts, workers and durable effect reconciliation into Java.
- [x] Attach the runtime to mod lifecycle and expose installation/status/stop through the wrapper's existing control surface. No per-action wrapper subprocess belongs in execution.
- [x] Carry reusable JavaScript skill definitions and reference examples into mod resources.
- [x] Remove the active npm project, Node launchers and QuickJS host after their replacement is available; retain historical design/trial evidence with explicit supersession.
- [x] Run focused Java integration checks, the full Gradle checks, independent review and an in-mod live qualification. The final full-suite benchmark timeout is recorded below.

Tests continue at the previously delegated public seams: skill execution with the real engine, Java runtime/library interfaces with real storage, and native admission/release with the existing fake Minecraft adapter. Tests do not mock the Java OS internals. The original mixed-duty gameplay and performance qualifications remain separate from this migration.

GraalJS dependencies come from Maven Central, using the [official embedding contract](https://www.graalvm.org/latest/reference-manual/embed-languages/). The ordinary Java 21 JVM can use the interpreter fallback; a GraalVM JDK is not required merely to run embedded skills. Compatibility and packaged-mod behavior must be verified rather than inferred from successful dependency resolution.

## Verification record

The [operating guide](embedded-os.md) describes the current commands, configuration and limits. The packaged mod includes twelve separate GraalJS dependency jars, preserving provider service descriptors. The thin guest kernel is the only JavaScript runtime support; OS policies are Java classes under `src/main/java/ai/moeru/airicraft/os`.

Focused checks run the real GraalJS engine and filesystem. Minecraft inventory/network state is simulated through the existing native action boundary. The latest focused run passed 62 tests: 61 OS/native checks plus the isolated planning benchmark. Coverage includes independent generators, declarative offers, worker/fallback execution, shared native visits and procurement, cleanup ownership, lost admission replies, context failure, persisted validation, unknown observations and execution limits.

The full multi-project Gradle build passed before the final context-loss correction. The final full run after that correction completed 1,408 root tests with one failure and two skips: `AiricraftProviderPlanningTest.productionPlanningPlansDiamondFromEmptyInventoryWithFullRecipeNoise` exceeded its existing one-second preemptive timeout. The same benchmark passed in the focused run; its timeout was not changed. Wrapper checks (97) and JourneyMap compatibility checks (20) passed in the earlier full build. The final root run had no OS test failures. This is a recorded full-suite limitation, not a claim that every final build check is green.

Logs and the recovery manifest are in ignored `run/os-java-migration/`. The original reference world is preserved; packaged-client qualification uses a copied save named in `live-world.txt`.

The packaged Fabric client loaded the embedded engine on ordinary Java 21.0.9 with JourneyMap and REI enabled. All eight bundled definitions passed actual-engine validation inside the client. An inventory observer completed, and the structure-description skill completed through its deterministic `worker_unconfigured` fallback; no external provider was contacted.

Two independently installed JavaScript skills then withdrew one wheat and returned one wheat during the same native container context. Both server-confirmed receipts report one transferred item, complete accounting and released controls. The shared context reports two completed operations and verified closure of both the client and server windows with an empty cursor. All three journal records settled. After stopping the OS, a fresh native observation confirmed no lease, active action or retained context, a closed window, and the original two wheat in player inventory. The evidence is captured in `live-qualification.json`, `live-effects.json` and the corresponding `live-*.txt` files in the migration log directory.

This qualifies the embedded runtime, skill composition and container adapter in a live client. Farming, sheep care, birch, fishing and compost still need native OS adapters and renewed mixed-duty qualification; the historical Node gameplay trials do not establish that those behaviors run through the Java OS.

## Standards review

No remaining documented-standard violations. The reviewer suggested moving observation pagination, grant checks and wait operations behind NativeViews; that change was made and the follow-up cleared it. Review excluded the preexisting mixed-worktree changes.

## Spec review

The independent review identified overwritten trace evidence, fatal handling of expected rejections, incomplete context reconciliation and a valid request being dropped when a context budget expired between selection and admission. All were corrected. Follow-up also verified that every context polling path preserves terminal failure before scheduling can continue. No findings remained in that follow-up; live qualification is separate from source review.
