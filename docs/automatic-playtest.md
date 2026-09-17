# Automatic playtest recordings

Start an isolated local client with continuous flight recording, live RGB capture, the required external recording profile, and the planner's `something_wrong` tool:

```sh
scripts/automatic-playtest \
  --world 'run/saves/My World' \
  --recorder-jar /absolute/path/to/recorder-profile.jar \
  --objective 'Obtain an iron pickaxe in survival. Report suspected interface bugs.'
```

The launcher copies the world, joins it, and sends the objective. Omit `--objective` to use in-game chat. `AIRICRAFT_RECORDER_JAR` can supply the profile instead of `--recorder-jar`. Airicraft consumes the same prebuilt profile as evaluation runs; it does not build or fetch recorder dependencies. Both planner roles can report a suspected Airicraft/tool/harness defect with `something_wrong({"description":"What I tried, expected, and observed; relevant work IDs..."})`. The description is free text, up to 8192 characters. Certainty or a root cause is not required. Ordinary survival difficulty or missing ingredients alone are not interface defects.

Recording begins when the world loads. On a report, Airicraft commits the tool receipt, ends that planner turn, pauses both client and integrated-server ticks, and captures final evidence. The launcher asks the client to flush and copy the world while paused, then close normally so the external Recorder Play can finalize. It checks the Play's metadata/events/replay archive with the evaluation validator, publishes the planner extension into the Play, and moves the complete directory to `automatic_playtest/<run-id>/`. No posthoc RGB rendering runs. Duplicate reports return the original incident ID. An active tick-debug trace is stopped so it cannot block the pause.

Every run is recorded from world entry, even if no bug is reported. Normal world exit, client quit, a time limit, or interrupting the launcher (Ctrl-C or SIGTERM) finalizes the flight records and live RGB stream. The launcher waits for Recorder Play shutdown, copies the saved world, and archives the run under `automatic_playtest/<run-id>/`. No run is discarded because it lacked a bug report.

`airicraft status` exposes `automaticPlaytest.state`, `outputDir`, and `error` while the client is alive; use the worker's `bridge-state.json` through `AIRICRAFT_BRIDGE_STATE_FILE`. `CAPTURE_READY` means a reported incident is paused and Airicraft evidence is ready, but the Play still needs finalization. `FINISHED` means a normal exit closed the flight recording. The launcher archives stopped runs with these summary outcomes:

| `status` | Meaning |
| --- | --- |
| `COMPLETED` | Complete dataset recording, with no bug report |
| `REPORTED` | Complete dataset recording with a planner bug report and verified paused checkpoint |
| `INCOMPLETE` | Retained crash, capture failure, or partial recording; inspect `missingArtifacts`, `message`, and `harness-summary.json` |

Select `recordingComplete: true` when a dataset requires fully finalized evidence. `bugReported` and `terminationReason` distinguish why a run stopped from recording completeness. A top-level directory alone does not imply a complete run. An ordinary exit's `world-save.json` identifies a last-saved-world copy, not a paused incident checkpoint.

A JVM crash preserves streams already written and any unfinished Recorder Play/replay scratch files. It cannot guarantee the final in-flight LLM response, last buffered frame/event, final snapshots, a clean replay ZIP, or the latest unsaved world changes. The launcher archives that evidence as `INCOMPLETE`. If the launcher itself is killed or the machine loses power, files remain under `.in-progress`; once all writers have stopped, archive them with:

```sh
scripts/automatic-playtest --recover automatic_playtest/.in-progress/<run-id>
```

Recovery refuses live launcher/client processes and locked worlds. It preserves partial files rather than fabricating missing records or a finalized replay. It also supports older runs that only wrote an `INTERRUPTED` summary; missing final flight records keep those archives `INCOMPLETE`. It never restarts or stops an active run. Start another launcher invocation for another run.

Override the output root with `--output /absolute/path`. Each invocation uses a unique run ID, copied game directory and bridge state file, and disables JDWP, allowing independent invocations to share the output root. Workers remain under `run/automatic-playtest-workers/<run-id>/`; configs stay there rather than in the published bundle. The mode is opt-in and requires a local integrated server. `--max-seconds` bounds wall time after join (default 1800), so hung planners do not leave clients running forever. Use `--max-seconds 0` for an open-ended run with no time limit. It keeps running until a bug report, capture failure, client exit, or explicit interruption; choose an ongoing objective if you also want no goal completion condition.

## Evidence for offline analysis

The evaluator and automatic playtests share `RuntimeFlightRecorder`; this is the same runtime evidence writer, not a separate recorder implementation.

| File | Contents |
| --- | --- |
| `bug-report.json` | Present only on a report: original natural-language description, report ID, wall time and client tick; classified as a suspected interface bug |
| `recording-start.json`, `summary.json` | Run identity, world/dimension, outcome and stream truncation flags |
| `events.jsonl`, `debug-timeline.jsonl`, `llm-calls.jsonl` | Incremental events, correlated tool/decision history and full LLM flight records from session start; a stream file appears when its first record arrives |
| `status-samples.jsonl` | Periodic session, active work and execution state |
| `planner-calls.jsonl` | Finalized planner call journal, matching evaluation output |
| `agent-*-final.json`, `world-evidence-final.json` | The evaluator's standard terminal runtime snapshots |
| `pause.json`, `paused.png` | Report-only debug session/epoch, frozen world snapshot and screenshot when capture succeeds |
| `pause-verification.json` | Report-only launcher verification that both client and server ticks were paused before shutdown |
| `live-recording.jsonl` | Full-run structured/visual observations streamed from the live client, with manifest and final `export_complete` record |
| `recorder/` | Required finalized Recorder Play, including structured events, replay archive and Airicraft planner extension; locate it through the relative `recorderPlayPath` in the summary |
| `world-save/`, `world-save.json` | Report: paused world checkpoint with exact server tick/time. Ordinary exit or crash: last saved world copied after process exit; unsaved crash changes are unavailable |

Review top-level run directories in bulk, starting with the summary and optional bug report. Open `live-recording.jsonl` through the dashboard's **Open session** for visual inspection. Automatic playtests enable direct framebuffer sampling at up to one frame per server second (640×360 RGB; identical frames are skipped). Observations are streamed to disk each tick, so older frames survive rolling-history eviction. The completion record and summary flag any detected sequence gaps. Reported incidents also include a final full screenshot.

Recorder Play supplies the structured recorded world/packet state for replay and inspection; it covers client-visible capture, not an authoritative server checkpoint at every historical tick. `world-save/` is one saved checkpoint, not a sequence of historical saves. Normal disconnect can add a short tail to the Play after a report tick; use `pause-verification.json` and `world-save.json` to locate the incident. Opening a copied checkpoint lets an investigator resume from there.

LLM calls can appear first as pending and later as completed or failed under the same `record.sequenceId`. When analyzing `llm-calls.jsonl`, keep the last record per sequence. Intermediate streaming previews remain in dashboard history; the disk flight stream retains the initial observation and terminal response.

Reports are candidates for review, not confirmed defects. Self-reporting complements harness timeout/failure detection: a planner or process that hangs cannot call `something_wrong`. Partial recordings remain available for that investigation. Disk recording has no automatic retention cleanup. An already-running client keeps its loaded code; these exit hooks apply to new launches, while existing runs continue streaming their evidence without interruption.

## Verification

The 2026-09-17 controlled live smoke used the embedded planner and supplied recording profile. `something_wrong` paused both clocks at server tick 218; `world-save.json` recorded that same tick, and the saved `level.dat` time matched its world time of 246329. Shutdown completed through the bridge with exit code 0. The published Play had completed metadata, events, a CRC-valid replay ZIP with Flashback data and chunk caches, and the planner extension. All three LLM calls retained completed responses, the report receipt reached the debug timeline, and the live RGB stream completed without sequence gaps. No posthoc RGB rendering was used.

Additional isolated live runs verified a time limit and SIGTERM to the launcher both produced `COMPLETED` datasets with finalized Recorder Plays and no bug report. SIGKILL to a disposable Minecraft process produced an `INCOMPLETE` archive retaining every previously measured stream byte, external recorder events, and unfinished replay files. The existing open-ended client was left running throughout. Three-second samples of that client's live RGB and external Recorder Play event files showed both growing continuously; status samples advanced every five seconds. The supplied recorder uses a 256 KiB event buffer, not a fixed periodic fsync, so this observation is not a power-loss durability guarantee. Crash recovery preserves raw bytes, including a possible partial final JSONL line; consumers should read complete records and ignore an invalid trailing record.
