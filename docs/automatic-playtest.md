# Automatic playtest reports

Start an isolated local client with continuous flight recording, live RGB capture, the required external recording profile, and the planner's `something_wrong` tool:

```sh
scripts/automatic-playtest \
  --world 'run/saves/My World' \
  --recorder-jar /absolute/path/to/recorder-profile.jar \
  --objective 'Obtain an iron pickaxe in survival. Report suspected interface bugs.'
```

The launcher copies the world, joins it, and sends the objective. Omit `--objective` to use in-game chat. `AIRICRAFT_RECORDER_JAR` can supply the profile instead of `--recorder-jar`. Airicraft consumes the same prebuilt profile as evaluation runs; it does not build or fetch recorder dependencies. Both planner roles can report a suspected Airicraft/tool/harness defect with `something_wrong({"description":"What I tried, expected, and observed; relevant work IDs..."})`. The description is free text, up to 8192 characters. Certainty or a root cause is not required. Ordinary survival difficulty or missing ingredients alone are not interface defects.

Recording begins when the world loads. On a report, Airicraft commits the tool receipt, ends that planner turn, pauses both client and integrated-server ticks, and captures final evidence. The launcher asks the client to flush and copy the world while paused, then close normally so the external Recorder Play can finalize. It checks the Play's metadata/events/replay archive with the evaluation validator, publishes the planner extension into the Play, and moves the complete directory to `automatic_playtest/<run-id>/`. No posthoc RGB rendering runs. Duplicate reports return the original incident ID. An active tick-debug trace is stopped so it cannot block the pause.

`airicraft status` exposes `automaticPlaytest.state`, `outputDir`, and `error` while the client is alive; use the worker's `bridge-state.json` through `AIRICRAFT_BRIDGE_STATE_FILE`. `CAPTURE_READY` means the game is paused and Airicraft evidence is ready, but the Play still needs finalization. Only a top-level run directory with `summary.json` status `REPORTED` is complete. Capture errors, missing/unfinished Plays, early exits and timeouts preserve files under `.in-progress`. `harness-summary.json` records shutdown and capture errors. Start another launcher invocation for another run.

Override the output root with `--output /absolute/path`. Each invocation uses a unique run ID, copied game directory and bridge state file, and disables JDWP, allowing independent invocations to share the output root. Workers remain under `run/automatic-playtest-workers/<run-id>/`; configs stay there rather than in the published bundle. The mode is opt-in and requires a local integrated server. `--max-seconds` bounds wall time after join (default 1800), so hung planners do not leave clients running forever. Use `--max-seconds 0` for an open-ended run with no time limit. It keeps running until a bug report, capture failure, client exit, or explicit interruption; choose an ongoing objective if you also want no goal completion condition.

## Evidence for offline analysis

The evaluator and automatic playtests share `RuntimeFlightRecorder`; this is the same runtime evidence writer, not a separate recorder implementation.

| File | Contents |
| --- | --- |
| `bug-report.json` | Original natural-language description, report ID, wall time and client tick; classified as a suspected interface bug |
| `recording-start.json`, `summary.json` | Run identity, world/dimension, outcome and stream truncation flags |
| `events.jsonl`, `debug-timeline.jsonl`, `llm-calls.jsonl` | Incremental events, correlated tool/decision history and full LLM flight records from session start; a stream file appears when its first record arrives |
| `status-samples.jsonl` | Periodic session, active work and execution state |
| `planner-calls.jsonl` | Finalized planner call journal, matching evaluation output |
| `agent-*-final.json`, `world-evidence-final.json` | The evaluator's standard terminal runtime snapshots |
| `pause.json`, `paused.png` | Debug session/epoch, frozen world snapshot and screenshot when capture succeeds |
| `pause-verification.json` | Launcher verification that both client and server ticks were paused before shutdown |
| `live-recording.jsonl` | Full-run structured/visual observations streamed from the live client, with manifest and final `export_complete` record |
| `recorder/` | Required finalized Recorder Play, including structured events, replay archive and Airicraft planner extension; locate it through the relative `recorderPlayPath` in the summary |
| `world-save/`, `world-save.json` | World checkpoint flushed and copied while paused, with its exact server tick and world time |

Review completed top-level run directories in bulk, starting with the report and summary. Open `live-recording.jsonl` through the dashboard's **Open session** for visual inspection. Automatic playtests enable direct framebuffer sampling at up to one frame per server second (640×360 RGB; identical frames are skipped). Observations are streamed to disk each tick, so older frames survive rolling-history eviction. The completion record and summary flag any detected sequence gaps. A final full screenshot is saved separately.

Recorder Play supplies the structured recorded world/packet state for replay and inspection; it covers client-visible capture, not an authoritative server checkpoint at every historical tick. `world-save/` is the paused incident checkpoint, not a sequence of historical saves. Normal disconnect can add a short tail to the Play after the report tick; use `pause-verification.json` and `world-save.json` to locate the incident. Opening a copied checkpoint lets an investigator resume from there.

LLM calls can appear first as pending and later as completed or failed under the same `record.sequenceId`. When analyzing `llm-calls.jsonl`, keep the last record per sequence. Intermediate streaming previews remain in dashboard history; the disk flight stream retains the initial observation and terminal response.

Reports are candidates for review, not confirmed defects. Self-reporting complements harness timeout/failure detection: a planner or process that hangs cannot call `something_wrong`. Interrupted recordings remain available for that investigation. Disk recording has no automatic retention cleanup.

## Verification

The 2026-09-17 controlled live smoke used the embedded planner and supplied recording profile. `something_wrong` paused both clocks at server tick 218; `world-save.json` recorded that same tick, and the saved `level.dat` time matched its world time of 246329. Shutdown completed through the bridge with exit code 0. The published Play had completed metadata, events, a CRC-valid replay ZIP with Flashback data and chunk caches, and the planner extension. All three LLM calls retained completed responses, the report receipt reached the debug timeline, and the live RGB stream completed without sequence gaps. No posthoc RGB rendering was used.
