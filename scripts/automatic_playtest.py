"""One isolated planner playtest using the evaluation runner's recording profile and Play handoff."""
from __future__ import annotations

import argparse
import fcntl
import importlib.machinery
import importlib.util
import json
import math
import os
from pathlib import Path
import shlex
import shutil
import signal
import subprocess
import sys
import time
import uuid


REPO = Path(__file__).resolve().parent.parent
loader = importlib.machinery.SourceFileLoader("airicraft_playtest_evaluation_support", str(REPO / "scripts/run-evaluation-scenarios"))
spec = importlib.util.spec_from_loader(loader.name, loader)
evaluation = importlib.util.module_from_spec(spec)
sys.modules[loader.name] = evaluation
loader.exec_module(evaluation)
artifact_spec = importlib.util.spec_from_file_location("playtest_artifact", REPO / "scripts/playtest_artifact.py")
artifact = importlib.util.module_from_spec(artifact_spec)
artifact_spec.loader.exec_module(artifact)


def prepare_game(world: Path, worker: Path, pending: Path) -> Path:
    game = worker / "game"
    game.mkdir(parents=True)
    for name in ("config", "journeymap/config"):
        source = REPO / "run" / name
        if source.is_dir():
            shutil.copytree(source, game / name)
    if (REPO / "run/options.txt").is_file():
        shutil.copy2(REPO / "run/options.txt", game / "options.txt")
    shutil.copytree(world, game / "saves/playtest", ignore=shutil.ignore_patterns("session.lock"))
    evaluation.prepare_recorder_game_directory(game, pending / "recorder")
    return game


def finalize_recording(pending: Path, destination: Path, world: Path, client_exit: dict,
                       failure: str | None, termination_reason: str) -> tuple[Path, int]:
    # Never validate, copy or move a directory while any recording writer is alive.
    if not client_exit.get("minecraftExited", False):
        evaluation.write_json(pending / "harness-summary.json", {
            "harnessStatus": "CAPTURE_ERROR", "clientExit": client_exit,
            "message": "Minecraft did not exit; recording writers may still be active"})
        return pending, 1
    # A crashed JVM closes the encoder pipe; wait for the final MP4 fragment before publication.
    encoder = artifact.read_json(pending / "screen-encoder.json", {})
    encoder_deadline = time.monotonic() + 30
    while evaluation.process_alive(encoder.get("pid", -1)):
        if time.monotonic() >= encoder_deadline:
            evaluation.write_json(pending / "harness-summary.json", {
                "harnessStatus": "CAPTURE_ERROR", "message": "Screen encoder is still writing; recover after it exits"})
            return pending, 1
        time.sleep(0.2)
    summary_file = pending / "summary.json"
    try:
        summary = json.loads(summary_file.read_text()) if summary_file.exists() else {}
    except json.JSONDecodeError:
        shutil.copy2(summary_file, pending / "summary-incomplete.json")
        summary = {}
        failure = failure or "Interrupted summary write"
    if summary.get("artifactPlayPath") and artifact.published_play(pending, destination.parent) is not None:
        artifact.discard_published_sources(pending)
        pending.rename(destination)
        return destination, 0 if summary.get("recordingComplete") else 1
    flight_finished = summary.get("status") in ("CAPTURE_READY", "FINISHED")
    reported = (pending / "bug-report.json").is_file()
    harness = {"harnessStatus": "OK", "message": failure, "clientExit": client_exit}
    evaluation.finalize_recorder_capture(harness, pending, pending / "recorder")
    if world.is_dir() and not (pending / "world-save").exists():
        checkpoint = pending / (".world-save-recovery-" + str(uuid.uuid4()))
        shutil.copytree(world, checkpoint, ignore=shutil.ignore_patterns("session.lock"))
        checkpoint.rename(pending / "world-save")
        evaluation.write_json(pending / "world-save.json", {
            "capturedWhilePaused": False, "source": "last_saved_world", "copiedAt": evaluation.utc_now_iso()})
    if reported:
        try:
            checkpoint = json.loads((pending / "world-save.json").read_text())
            pause = json.loads((pending / "pause-verification.json").read_text())
            if not checkpoint.get("capturedWhilePaused") or checkpoint.get("serverTickId") != pause.get("serverTickId"):
                raise ValueError("World checkpoint does not match the report pause")
        except (OSError, ValueError) as error:
            harness.update(harnessStatus="CAPTURE_ERROR", message=f"Paused world checkpoint is missing or invalid: {error}")
    if (pending / "world-save/level.dat").is_file():
        summary["worldSavePath"] = "world-save"
    required = ("recording-start.json", "planner-calls.jsonl", "agent-status-final.json", "agent-events-final.json",
                "agent-debug-timeline-final.json", "agent-debug-llm-calls-final.json", "world-evidence-final.json",
                "live-recording.jsonl", "world-save/level.dat", "world-save.json")
    missing = [name for name in required if not (pending / name).is_file()]
    if not flight_finished or missing:
        harness.update(harnessStatus="CAPTURE_ERROR", message=harness.get("message") or "Flight recording did not finish")
    if any(summary.get(key) for key in ("eventsTruncated", "debugTimelineTruncated", "llmCallsTruncated", "visualHistoryTruncated")):
        harness.update(harnessStatus="CAPTURE_ERROR", message=harness.get("message") or "Recording contains stream gaps")
    if failure:
        harness["harnessStatus"] = "CAPTURE_ERROR"
    complete = harness["harnessStatus"] == "OK"
    summary.update(status=("REPORTED" if reported else "COMPLETED") if complete else "INCOMPLETE",
                   recordingComplete=complete, bugReported=reported, terminationReason=termination_reason,
                   missingArtifacts=missing,
                   recorderPlayPath=harness.get("recorderPlayPath"), harnessStatus=harness["harnessStatus"],
                   message=harness.get("message"), finishedAt=evaluation.utc_now_iso(),
                   outputDir=str(destination))
    evaluation.write_json(pending / "harness-summary.json", harness)
    evaluation.write_json(summary_file, summary)
    try:
        play = artifact.publish(pending, destination.parent)
        if play is not None:
            summary = json.loads(summary_file.read_text())
            complete = summary["recordingComplete"]
            harness["recorderPlayPath"] = summary["recorderPlayPath"]
            harness["harnessStatus"] = summary["harnessStatus"]
            harness["message"] = summary.get("message")
            evaluation.write_json(pending / "harness-summary.json", harness)
            artifact.discard_published_sources(pending)
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        complete = False
        summary.update(status="INCOMPLETE", recordingComplete=False, harnessStatus="CAPTURE_ERROR",
                       message=f"Playtest extension publication failed: {error}")
        evaluation.write_json(summary_file, summary)
    # Retain partial/crashed runs too; dataset consumers select recordingComplete explicitly.
    pending.rename(destination)
    return destination, 0 if complete else 1


def recover_recording(pending: Path) -> int:
    pending = pending.resolve()
    if pending.parent.name != ".in-progress":
        raise ValueError("--recover requires a run directory under .in-progress")
    if artifact.published_play(pending, pending.parent.parent) is not None:
        # The canonical move proves writers stopped. Cleanup may already have removed launch.json.
        result, code = finalize_recording(pending, pending.parent.parent / pending.name, pending / "world-save",
            {"method": "OFFLINE_RECOVERY", "minecraftExited": True}, None, "recovered")
        print(json.dumps({"outputDir": str(result), "status": json.loads((result / "summary.json").read_text())["status"]}))
        return code
    launch = json.loads((pending / "launch.json").read_text())
    worker = Path(launch["workerDirectory"])
    processes_file = pending / "processes.json"
    processes = json.loads(processes_file.read_text()) if processes_file.exists() else {}
    bridge = evaluation.read_bridge_state(worker / "bridge-state.json")
    pids = [launch.get("launcherPid", -1), processes.get("launcherChildPid", -1),
            processes.get("minecraftPid", -1), bridge.process_id if bridge else -1]
    if any(evaluation.process_alive(pid) for pid in pids):
        raise ValueError("Run is still active; recovery will not interrupt it")
    world = worker / "game/saves/playtest"
    # Also protect old runs without process metadata and saves opened by another client.
    with (world / "session.lock").open("a+b") as lock:
        try:
            fcntl.lockf(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as error:
            raise ValueError("World is still in use; recovery will not interrupt it") from error
        result, code = finalize_recording(pending, pending.parent.parent / pending.name, world,
            {"method": "OFFLINE_RECOVERY", "minecraftExited": True}, None, "recovered")
    print(json.dumps({"outputDir": str(result), "status": json.loads((result / "summary.json").read_text())["status"]}))
    return code


def interrupt_run(signum, frame):
    raise KeyboardInterrupt


def run(args: argparse.Namespace) -> int:
    if not shutil.which("ffmpeg") or not shutil.which("ffprobe"):
        raise ValueError("Automatic playtests require ffmpeg and ffprobe on PATH for MP4 screen capture")
    world = args.world.resolve()
    if not (world / "level.dat").is_file():
        raise ValueError(f"Not a Minecraft world directory: {world}")
    recorder = evaluation.resolve_recorder_options(args.recorder_jar, False, os.environ)
    profile = Path(recorder.profile).expanduser().resolve()
    if not profile.is_file():
        raise ValueError(f"Recording profile does not exist: {profile}")
    recorder = evaluation.RecorderOptions(True, str(profile))
    run_id = evaluation.local_timestamp() + "-" + str(uuid.uuid4())
    output = args.output.resolve()
    pending = output / ".in-progress" / run_id
    destination = output / run_id
    worker = REPO / "run/automatic-playtest-workers" / run_id
    pending.mkdir(parents=True)
    game = prepare_game(world, worker, pending)
    bridge_file = worker / "bridge-state.json"
    evaluation.write_json(pending / "launch.json", {"id": run_id, "sourceWorld": str(world), "objective": args.objective,
                                                   "workerDirectory": str(worker), "recordingProfile": str(profile),
                                                   "maxSeconds": args.max_seconds, "launcherPid": os.getpid()})
    command = shlex.join([
        "./gradlew", "--no-daemon", "-Pairicraft.includeEvaluator=true",
        "-Pairicraft.automaticPlaytest=true", f"-Pairicraft.automaticPlaytestId={run_id}",
        f"-Pairicraft.automaticPlaytestDir={output}", f"-Pairicraft.evaluator.runDir={game}",
        "-Pairicraft.evaluator.jdwp.enabled=false", f"-Pairicraft.evaluator.recorderJar={profile}",
        "wrapper:installDist", ":runClientEvaluator",
    ])
    client = None
    bridge = None
    failure = None
    termination_reason = "startup_failed"
    client_exit = {"method": "NOT_STARTED", "minecraftExited": True}
    print(f"Recording: {pending}", flush=True)
    try:
        launch_ms = int(time.time() * 1000)
        client = evaluation.start_client(REPO, command, pending / "client.log", bridge_file, game, run_id, False, recorder)
        processes = {"launcherChildPid": client.process.pid}
        evaluation.write_json(pending / "processes.json", processes)
        bridge, _ = evaluation.wait_for_title_bridge(client, bridge_file, launch_ms, args.startup_timeout)
        processes["minecraftPid"] = bridge.process_id
        evaluation.write_json(pending / "processes.json", processes)
        worlds = evaluation.bridge_json(bridge, "GET", "/v1/worlds")["worlds"]
        if len(worlds) != 1:
            raise RuntimeError("Expected exactly one copied playtest world")
        evaluation.bridge_json(bridge, "POST", "/v1/worlds/join", {"worldId": worlds[0]["worldId"]})
        deadline = time.monotonic() + args.max_seconds if args.max_seconds else math.inf
        objective_sent = not bool(args.objective)
        termination_reason = "capture_error"
        while time.monotonic() < deadline:
            if client.process.poll() is not None:
                termination_reason = "client_exit" if client.process.returncode == 0 else "crash"
                if client.process.returncode != 0:
                    failure = f"Client exited with code {client.process.returncode}"
                break
            try:
                status = evaluation.bridge_json(bridge, "GET", "/v1/status")
            except evaluation.BridgeHttpError as error:
                if error.status != 500:
                    raise
                # World loading can hold the render thread past the bridge's short timeout.
                # A configured wall-clock run budget still bounds an actually hung client.
                time.sleep(0.5)
                continue
            except OSError:
                time.sleep(0.5)
                continue
            recording = status.get("automaticPlaytest", {})
            state = recording.get("state")
            if state == "FAILED":
                raise RuntimeError(recording.get("error") or "Automatic playtest capture failed")
            if state == "FINISHED":
                termination_reason = "world_left"
                failure = recording.get("error") or None
                break
            if state == "CAPTURE_READY":
                termination_reason = "bug_report"
                pause = evaluation.bridge_json(bridge, "GET", "/v1/agent/debug/ticks/state")
                if not (pause.get("paused") and pause.get("serverPaused")):
                    raise RuntimeError("Report finished without both client and server paused")
                evaluation.write_json(pending / "pause-verification.json", pause)
                break
            if status.get("worldLoaded") and state == "RECORDING" and not objective_sent:
                evaluation.bridge_json(bridge, "POST", "/v1/agent/debug/chat", {"message": args.objective})
                objective_sent = True
            # Drain the evaluation log queue without echoing model output or accumulating it in memory.
            while not client.line_queue.empty():
                client.line_queue.get_nowait()
            time.sleep(0.5)
        else:
            termination_reason = "time_limit"
    except KeyboardInterrupt:
        termination_reason = "manual_interrupt"
    except Exception as error:
        failure = f"{type(error).__name__}: {error}"
    finally:
        # A second interrupt must not kill finalization midway through recorder shutdown.
        signal.signal(signal.SIGINT, signal.SIG_IGN)
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        if client is not None:
            bridge = bridge or evaluation.read_bridge_state(bridge_file)
            client_exit = evaluation.stop_client(client, 45, bridge.process_id if bridge else -1,
                (lambda: evaluation.bridge_json(bridge, "POST", "/v1/evaluation/client-stop")) if bridge else None)
        bridge_file.unlink(missing_ok=True)
    result, code = finalize_recording(pending, destination, game / "saves/playtest", client_exit, failure, termination_reason)
    outcome = json.loads((result / "summary.json").read_text()) if (result / "summary.json").exists() else {}
    print(json.dumps({"status": outcome.get("status", "INCOMPLETE"), "outputDir": str(result), "error": outcome.get("message")}), flush=True)
    return code


def main() -> int:
    parser = argparse.ArgumentParser(description="Run an isolated automatic playtest with flight records, live RGB and a required Recorder Play.")
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--world", type=Path, help="Saved world directory to copy; the source is not modified.")
    source.add_argument("--recover", type=Path, help="Archive a stopped .in-progress run; refuses active clients or launchers.")
    parser.add_argument("--objective", help="Send this instruction to the planner after joining; omit to use in-game chat.")
    parser.add_argument("--recorder-jar", help="Prebuilt recording profile; defaults to AIRICRAFT_RECORDER_JAR.")
    parser.add_argument("--output", type=Path, default=REPO / "automatic_playtest")
    parser.add_argument("--max-seconds", type=float, default=1800, help="Wall-clock budget after joining, including hangs; 0 means no time limit (default 1800).")
    parser.add_argument("--startup-timeout", type=float, default=240)
    args = parser.parse_args()
    if not math.isfinite(args.max_seconds) or args.max_seconds < 0:
        parser.error("--max-seconds must be finite and nonnegative; 0 means no time limit")
    if not math.isfinite(args.startup_timeout) or args.startup_timeout <= 0:
        parser.error("--startup-timeout must be finite and positive")
    try:
        if args.recover:
            return recover_recording(args.recover)
        signal.signal(signal.SIGTERM, interrupt_run)
        return run(args)
    except (ValueError, OSError, evaluation.RunnerError) as error:
        print(str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
