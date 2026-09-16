# Native progress source review

Base: `7ef8ac2ee16e54989606cca0735a69cce0791df7`. The reviewed slice adds bounded native scope registrations, completed-tick counters, passive observation projection and eligible-tick wait deadlines. It does not claim continuously feasible root aging, domain maturity, mixed-duty execution or efficiency.

## Independent source review

Both reviewers verified the 21-file frozen manifest in `run/os-implementation/progress-review-20260916/`.

- **Spec:** zero actionable findings. The reviewer checked atomic bounds, complete-tick accounting, continuity and identity changes, unknown projections and the absence of physical authority or root-aging effects. Saved mapped Minecraft 1.21.8 bytecode supports the chunk and passive-entity callback locations.
- **Standards:** no hard documented violations. One shared-policy duplication was found between parsed and directly configured scope collection validation. Both now use `validateScopes`; the reviewer verified the separately hashed correction and reported no remaining findings.

These were source-only reviews. They did not run tests or control the client.

## Automated checks

The initial red tests rejected the absent native clock implementation, unsupported observation query and missing host clock projection. Their logs remain in the evidence directory.

The complete JavaScript suite passes 276 tests. Native boundary checks pass 41 tests: six scope-clock tests, sixteen container/observation tests and nineteen native action runtime tests. The focused run also passes all 51 provider-planning tests.

The first full build exhausted the existing 1 GiB Gradle daemon heap during remapping and was stopped through its owned wrapper process. A retry with a temporary 3 GiB heap and two workers completed the native tests except for one existing provider-planning timeout. That test's entire class passed in isolation. The corrected-source full build then passed with a temporary 3 GiB heap, one worker and parallel project execution disabled: 1,357 root tests passed, two skipped, 97 wrapper tests passed and 20 JourneyMap tests passed. No repository heap setting or test deadline was changed. Failure logs and XML results are retained alongside the successful results.

The invocation-only build command was:

```sh
./gradlew --no-daemon '-Dorg.gradle.jvmargs=-Xmx3G' --max-workers=1 --no-parallel build
```

## Live qualification

The corrected build passed a live qualification on `OS-Progress-20260916-002536`, a fresh copy of the archived survival fixture. JourneyMap and REI were enabled. The driver acquired full sheep identity through a captured world query; no terrain, inventory or animal action was performed. Original saves were preserved.

The nearby chunk and sheep counters remained at 29 during repeated paused reads, including a 2.5-second hold after all three guest waits were installed. Three explicit server steps advanced both counters to 30, 31 and 32; a distant unloaded chunk remained at zero. Two installations of the same validated generator revision returned `deadline` after their three eligible ticks. Its distant-scope installation remained pending and was cancelled during cleanup. A separate generator returned successfully while the waits remained pending. The actor stayed free, native submissions remained zero, the journal had no unfinished effects, and lease release was independently confirmed. The decision trace contained 148 written records with no gap.

Artifacts are in `run/os-implementation/progress-qualification/`: fixture/build/source manifests, scoped registrations, captured full entity identity, `progress-1789518733707/result.json`, immutable definitions and validation, native request/response log, decision trace, SQLite journal and independent final observation. All 749 recorded build source hashes matched the exercised tree. The recorder export contains 234 observations through server tick 1375, an `export_complete` footer, no truncation and no reported drops. The owned launcher was stopped after export.

This is one controlled connection test of native scope progress, copied observations and supervised passive waits. The independent generator performed no physical work. Crop maturity, actual breeding cooldown state, root feasibility aging, retained worksite contexts and mixed-duty throughput remain separate qualifications.
