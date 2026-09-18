# Automatic playtest OOM, 2026-09-18

Run: `20260918-193818-649999-79582-e59f3020-5a8d-41d3-af41-d10f9172864f`.
Times below are Asia/Taipei. Gameplay remains stopped.

## Evidence and cause

At 20:32:03 Minecraft logged `java.lang.OutOfMemoryError: Java heap space` on the render thread. The failed allocation was `StringWriter` growth inside Gson, called by `AutomaticPlaytestRecording.recordVisualHistory` while serializing an observation. Minecraft caught the error, but subsequent bridge operations repeatedly timed out waiting for its client thread. The launcher retried those errors indefinitely because this run had no time limit.

Streaming analysis of the published gzip journals found:

| Measurement | Observed value |
| --- | ---: |
| Distinct LLM sequence IDs | 1,487 |
| Compaction requests | 671 |
| Sum of request characters, counting each call once | 1,285,549,101 |
| Largest individual request | 2,826,430 characters |
| Largest completed live observation line | 7,216,621 characters |

`LlmFlightRecorder` retained full request strings and capped only the number of calls at 2,048. This run never reached that cap, so its repeated image-bearing requests remained reachable. These character totals exclude Minecraft, parsed payloads, other histories, and temporary serialization allocations; they are not an exact heap measurement. There was no crash-time heap dump, so they do not establish the fraction of heap held by every subsystem.

The preceding compaction defect submitted nine images to a provider allowing eight. Hundreds of HTTP 400 retries accumulated requests. Commit `c2af0e21` fixed the image limit and permanent-error retries, and live compaction succeeded at 20:26:14. That hot patch did not discard previously retained recorder history. The later OOM is consistent with the recorder retaining over a billion request characters while additional observations were allocated.

## Fix and validation

The flight recorder now bounds estimated retained payloads to 64 MiB as well as the call-count cap. It accounts for response growth, reports query truncation, and prevents callbacks from restoring evicted payloads. Pending evicted calls retain their identity until completion without retaining request bodies. Automatic observation export and final flight journals write JSON directly to buffered writers.

Four regression tests failed before the fix and pass afterward: byte-driven eviction below the call-count cap, response growth, oversized records, and late streaming/completion after eviction. A separate Java 21 probe used a 128 MiB heap and 671 distinct requests of approximately 1.57 million characters each. The old source failed with OOM after 61 requests; the patched source completed all 671, retaining 21 recent records and reporting truncation. This isolates the retention defect; it is not a Minecraft gameplay soak test.

The launcher now detects fatal JVM diagnostics and permanent compaction failures even when the bridge fails, and bounds continuous bridge unavailability to 60 seconds. It attempts graceful saving, applies bounded shutdown if necessary, and preserves partial recordings. Its five new lifecycle tests cover caught OOM, permanent provider rejection, transient failures, bridge timeout, and timer reset after recovery. The three failing pre-fix behaviors now pass.

Validation: full root Java suite, 1,358 passed and two skipped; automatic-playtest Python suite, 38 passed. No restarted gameplay validation has been performed.

## Saved state

The helper finished shutdown and publication at 20:40:30. Both launcher and client exited. The run index is `automatic_playtest/<run-id>/summary.json`; resolve its `artifactPlayPath` under `automatic_playtest/`. The extension directory `extensions/airicraft.playtest/` contains `world-save.zip`, `client.log.gz`, `llm-calls.jsonl.gz`, and `live-recording.jsonl.gz`.

The published recording is `INCOMPLETE`: no finalized Recorder Play was found, and final planner calls, timeline, LLM and world snapshots were missing. The existing summary records `manual_interrupt` because this pre-fix helper was stopped with SIGTERM after the OOM. The retained world is a last-saved-world checkpoint, not proof all changes at the instant of the crash were saved. Published artifacts were not edited.
