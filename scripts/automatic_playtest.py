"""One isolated planner playtest using the evaluation runner's recording profile and Play handoff."""
from __future__ import annotations

import argparse
import importlib.machinery
import importlib.util
import json
import math
import os
from pathlib import Path
import shlex
import shutil
import sys
import time
import uuid


REPO = Path(__file__).resolve().parent.parent
loader = importlib.machinery.SourceFileLoader("airicraft_playtest_evaluation_support", str(REPO / "scripts/run-evaluation-scenarios"))
spec = importlib.util.spec_from_loader(loader.name, loader)
evaluation = importlib.util.module_from_spec(spec)
sys.modules[loader.name] = evaluation
loader.exec_module(evaluation)


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


def finalize_incident(pending: Path, destination: Path, world: Path, client_exit: dict, failure: str | None) -> tuple[Path, int]:
    summary_file = pending / "summary.json"
    summary = json.loads(summary_file.read_text()) if summary_file.exists() else {}
    ready = summary.get("status") == "CAPTURE_READY" and failure is None
    harness = {"harnessStatus": "OK" if ready else "INTERRUPTED", "message": failure, "clientExit": client_exit}
    evaluation.finalize_recorder_capture(harness, pending, pending / "recorder")
    if not client_exit.get("minecraftExited", False):
        harness.update(harnessStatus="CAPTURE_ERROR", message="Minecraft did not exit; recording writers may still be active")
    elif not ready and world.is_dir() and not (pending / "world-save").exists():
        shutil.copytree(world, pending / "world-save", ignore=shutil.ignore_patterns("session.lock"))
    if ready and not ((pending / "world-save/level.dat").is_file() and (pending / "world-save.json").is_file()):
        harness.update(harnessStatus="CAPTURE_ERROR", message="Paused world checkpoint is missing")
    elif ready:
        checkpoint = json.loads((pending / "world-save.json").read_text())
        pause = json.loads((pending / "pause-verification.json").read_text())
        if not checkpoint.get("capturedWhilePaused") or checkpoint.get("serverTickId") != pause.get("serverTickId"):
            harness.update(harnessStatus="CAPTURE_ERROR", message="World checkpoint does not match the report pause")
    if (pending / "world-save/level.dat").is_file():
        summary["worldSavePath"] = "world-save"
    complete = ready and harness["harnessStatus"] == "OK"
    summary.update(status="REPORTED" if complete else "FAILED" if ready else "INTERRUPTED",
                   recorderPlayPath=harness.get("recorderPlayPath"), harnessStatus=harness["harnessStatus"],
                   message=harness.get("message"), finishedAt=evaluation.utc_now_iso(),
                   outputDir=str(destination if complete else pending))
    evaluation.write_json(pending / "harness-summary.json", harness)
    evaluation.write_json(summary_file, summary)
    if complete:
        pending.rename(destination)
        return destination, 0
    return pending, 1


def run(args: argparse.Namespace) -> int:
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
                                                   "maxSeconds": args.max_seconds})
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
    client_exit = {"method": "NOT_STARTED", "minecraftExited": True}
    print(f"Recording: {pending}", flush=True)
    try:
        launch_ms = int(time.time() * 1000)
        client = evaluation.start_client(REPO, command, pending / "client.log", bridge_file, game, run_id, False, recorder)
        bridge, _ = evaluation.wait_for_title_bridge(client, bridge_file, launch_ms, args.startup_timeout)
        worlds = evaluation.bridge_json(bridge, "GET", "/v1/worlds")["worlds"]
        if len(worlds) != 1:
            raise RuntimeError("Expected exactly one copied playtest world")
        evaluation.bridge_json(bridge, "POST", "/v1/worlds/join", {"worldId": worlds[0]["worldId"]})
        deadline = time.monotonic() + args.max_seconds if args.max_seconds else math.inf
        objective_sent = not bool(args.objective)
        while time.monotonic() < deadline:
            if client.process.poll() is not None:
                raise RuntimeError(f"Client exited before a report, code={client.process.returncode}")
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
            if state == "CAPTURE_READY":
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
            raise RuntimeError(f"No report within {args.max_seconds:g} seconds")
    except KeyboardInterrupt:
        failure = "Interrupted by user"
    except Exception as error:
        failure = f"{type(error).__name__}: {error}"
    finally:
        if client is not None:
            bridge = bridge or evaluation.read_bridge_state(bridge_file)
            client_exit = evaluation.stop_client(client, 45, bridge.process_id if bridge else -1,
                (lambda: evaluation.bridge_json(bridge, "POST", "/v1/evaluation/client-stop")) if bridge else None)
        bridge_file.unlink(missing_ok=True)
    result, code = finalize_incident(pending, destination, game / "saves/playtest", client_exit, failure)
    outcome = json.loads((result / "summary.json").read_text())
    print(json.dumps({"status": "REPORTED" if code == 0 else "INCOMPLETE", "outputDir": str(result), "error": outcome.get("message")}), flush=True)
    return code


def main() -> int:
    parser = argparse.ArgumentParser(description="Run an isolated automatic playtest with flight records, live RGB and a required Recorder Play.")
    parser.add_argument("--world", type=Path, required=True, help="Saved world directory to copy; the source is not modified.")
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
        return run(args)
    except (ValueError, OSError, evaluation.RunnerError) as error:
        print(str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
