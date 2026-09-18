"""Automatic-playtest artifact handoff, using the real evaluation Play validator."""
import importlib.util
import json
import os
import subprocess
import sys
from pathlib import Path
import tempfile
import unittest
import zipfile
from types import SimpleNamespace
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location("automatic_playtest", Path(__file__).resolve().parents[1] / "automatic_playtest.py")
playtest = importlib.util.module_from_spec(spec)
spec.loader.exec_module(playtest)


class PlaytestPlannerDegradationTest(unittest.TestCase):
    def test_open_ended_run_stops_gracefully_after_planner_degrades(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            world = root / "world"
            world.mkdir()
            (world / "level.dat").touch()
            profile = root / "recorder.jar"
            profile.touch()
            args = SimpleNamespace(world=world, output=root / "output", recorder_jar=str(profile),
                                   objective="Survive", max_seconds=0, startup_timeout=10)
            bridge = SimpleNamespace(process_id=123)
            client = Mock()
            client.process.pid = 122
            client.process.poll.return_value = None
            client.line_queue.empty.return_value = True
            agent_states = iter([{"degraded": False}, {"degraded": True}])
            requests = []

            def bridge_json(connection, method, path, body=None):
                requests.append((method, path, body))
                if path == "/v1/worlds":
                    return {"worlds": [{"worldId": "playtest"}]}
                if path == "/v1/status":
                    return {"worldLoaded": True, "automaticPlaytest": {"state": "RECORDING"}}
                if path == "/v1/agent/status":
                    return next(agent_states)
                return {}

            client_exit = {"minecraftExited": True, "method": "BRIDGE"}

            def stop_client(actual_client, timeout, pid, graceful_stop):
                self.assertIs(client, actual_client)
                self.assertEqual(123, pid)
                graceful_stop()
                return client_exit

            with (patch.object(playtest, "REPO", root),
                  patch.object(playtest.shutil, "which", return_value="available"),
                  patch.object(playtest, "prepare_game", return_value=root / "game"),
                  patch.object(playtest.evaluation, "start_client", return_value=client),
                  patch.object(playtest.evaluation, "wait_for_title_bridge", return_value=(bridge, {})),
                  patch.object(playtest.evaluation, "bridge_json", side_effect=bridge_json),
                  patch.object(playtest.evaluation, "stop_client", side_effect=stop_client) as stop,
                  patch.object(playtest, "finalize_recording", return_value=(root / "final", 0)) as finalize,
                  patch.object(playtest.signal, "signal"),
                  patch.object(playtest.time, "sleep") as sleep):
                self.assertEqual(0, playtest.run(args))
            stop.assert_called_once()
            finalize.assert_called_once()
            self.assertEqual((client_exit, None, "planner_degraded"), finalize.call_args.args[3:])
            self.assertEqual(1, sleep.call_count)
            self.assertEqual(1, sum(path == "/v1/agent/debug/chat" for _, path, _ in requests))
            self.assertEqual(("POST", "/v1/evaluation/client-stop", None), requests[-1])


class PlaytestPublicationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.pending = root / ".in-progress/run-1"
        self.pending.mkdir(parents=True)
        self.destination = root / "run-1"
        self.world = root / "worker/world"
        self.world.mkdir(parents=True)
        (self.world / "level.dat").write_bytes(b"stopped world")
        (self.pending / "summary.json").write_text(json.dumps({"status": "CAPTURE_READY"}))
        (self.pending / "bug-report.json").write_text(json.dumps({"description": "suspected interface bug"}))
        (self.pending / "pause-verification.json").write_text(json.dumps({"serverTickId": 20}))
        for name in ("recording-start.json", "agent-status-final.json", "agent-events-final.json",
                     "agent-debug-timeline-final.json", "agent-debug-llm-calls-final.json", "world-evidence-final.json"):
            (self.pending / name).write_text("{}")
        (self.pending / "planner-calls.jsonl").touch()
        (self.pending / "live-recording.jsonl").write_text('{"recordType":"export_complete"}\n')

    def evidence(self, run):
        summary = json.loads((run / "summary.json").read_text())
        return run.parent / summary["artifactPlayPath"] / "extensions/airicraft.playtest"

    def assert_world(self, run, expected):
        with zipfile.ZipFile(self.evidence(run) / "world-save.zip") as archive:
            self.assertEqual(expected, archive.read("level.dat"))

    def write_checkpoint(self):
        (self.pending / "world-save").mkdir()
        (self.pending / "world-save/level.dat").write_bytes(b"paused world")
        (self.pending / "world-save.json").write_text(json.dumps({"capturedWhilePaused": True, "serverTickId": 20}))

    def write_play(self, complete=True):
        play = self.pending / "recorder/v1/server/players/player/plays/connection"
        (play / "capture").mkdir(parents=True)
        (play / "metadata.json").write_text(json.dumps({
            "server": {"instanceId": "00000000-0000-4000-8000-000000000001"},
            "player": {"uuid": "00000000-0000-4000-8000-000000000002"},
            "connection": {"id": "00000000-0000-4000-8000-000000000003", "startServerTick": 1, "endedAt": "2026-09-17T00:00:00Z" if complete else None, "endServerTick": 20 if complete else None},
            "capture": {"events": "capture/events.jsonl", "replay": "capture/replay.zip", "replayFormat": "flashback"},
        }))
        (play / "capture/events.jsonl").write_text('{"serverTick":1}\n')
        with zipfile.ZipFile(play / "capture/replay.zip", "w") as replay:
            replay.writestr("capture.flashback", "replay fixture")
        return play

    def test_publishes_only_after_the_play_and_world_are_complete(self):
        play = self.write_play()
        self.write_checkpoint()
        relative = play.relative_to(self.pending).as_posix()
        path, code = playtest.finalize_recording(self.pending, self.destination, self.world, {"minecraftExited": True}, None, "bug_report")
        self.assertEqual(0, code)
        self.assertEqual(self.destination, path)
        self.assertFalse(self.pending.exists())
        summary = json.loads((path / "summary.json").read_text())
        self.assertEqual("REPORTED", summary["status"])
        self.assertEqual("../" + relative.removeprefix("recorder/"), summary["recorderPlayPath"])
        self.assert_world(path, b"paused world")

    def test_missing_or_unfinished_replay_never_publishes_a_success(self):
        self.write_play(complete=False)
        self.write_checkpoint()
        path, code = playtest.finalize_recording(self.pending, self.destination, self.world, {"minecraftExited": True}, None, "bug_report")
        self.assertEqual(1, code)
        self.assertEqual(self.destination, path)
        self.assertEqual("suspected interface bug", json.loads((self.evidence(path) / "playtest.json").read_text())["bugReport"]["description"])
        self.assertEqual("CAPTURE_ERROR", json.loads((path / "summary.json").read_text())["harnessStatus"])

    def test_never_moves_an_active_writer(self):
        self.write_play()
        path, code = playtest.finalize_recording(self.pending, self.destination, self.world, {"minecraftExited": False}, None, "bug_report")
        self.assertEqual(1, code)
        self.assertFalse(self.destination.exists())
        self.assertFalse((path / "world-save").exists())

    def test_extension_keeps_primitive_capture_bytes_and_binds_exact_identity(self):
        import hashlib
        play = self.write_play()
        self.write_checkpoint()
        hashes = {name: hashlib.sha256((play / name).read_bytes()).hexdigest()
                  for name in ("metadata.json", "capture/events.jsonl", "capture/replay.zip")}
        path, code = playtest.finalize_recording(self.pending, self.destination, self.world,
            {"minecraftExited": True}, None, "bug_report")
        self.assertEqual(0, code)
        extension = self.evidence(path)
        published = extension.parent.parent
        for name, digest in hashes.items():
            self.assertEqual(digest, hashlib.sha256((published / name).read_bytes()).hexdigest())
        manifest = json.loads((extension / "manifest.json").read_text())
        self.assertEqual("airicraft.playtest", manifest["extensionType"])
        self.assertEqual("00000000-0000-4000-8000-000000000003", manifest["play"]["connectionId"])
        self.assertTrue(all((extension / asset["path"]).is_file() for asset in manifest["assets"]))
        self.assertFalse((path / "live-recording.jsonl").exists())

    def test_native_fpv_and_consolidated_metadata_have_single_homes(self):
        self.write_play()
        self.write_checkpoint()
        (self.pending / "screen.mp4").write_bytes(b"video")
        (self.pending / "screen-frames.jsonl").write_text('{"videoSeconds":0,"serverTickId":20}\n')
        (self.pending / "recording-start.json").write_text(json.dumps({
            "startedAt": "2026-09-17T00:00:00Z", "context": {"clock": {"serverTick": 10, "debugServerTick": 20}, "dimension": "minecraft:overworld"}}))
        (self.pending / "launch.json").write_text(json.dumps({"objective": "Build and survive", "maxSeconds": 0, "launcherPid": 123}))
        probe = subprocess.CompletedProcess([], 0, json.dumps({"packets": [{"pts_time": "0"}],
            "streams": [{"width": 640, "height": 360, "r_frame_rate": "1/1"}]}), "")
        with patch.object(playtest.artifact.subprocess, "run", return_value=probe):
            path, code = playtest.finalize_recording(self.pending, self.destination, self.world,
                {"minecraftExited": True}, None, "bug_report")
        self.assertEqual(0, code)
        extension = self.evidence(path)
        renders = extension.parent.parent / "renders"
        self.assertEqual(b"video", (renders / "fpv.mp4").read_bytes())
        fpv = json.loads((renders / "fpv.json").read_text())
        self.assertEqual([{"serverTick": "10", "videoSeconds": 0}], fpv["frames"])
        self.assertEqual("5", fpv["sizeBytes"])
        metadata = json.loads((extension / "playtest.json").read_text())
        self.assertEqual("Build and survive", metadata["run"]["objective"])
        self.assertEqual("10", metadata["checkpoint"]["serverTick"])
        self.assertEqual("10", metadata["bugReport"]["serverTick"])
        self.assertTrue((extension / "flight-final.json.gz").exists())
        self.assertEqual({"manifest.json", "playtest.json"}, {file.name for file in extension.glob("*.json")})
        self.assertFalse((extension / "screen.mp4").exists())
        self.assertFalse((extension / "screen-frames.jsonl.gz").exists())
        self.assertNotIn("launcherPid", json.dumps(metadata))
        self.assertEqual(["summary.json"], [file.name for file in path.iterdir()])

    def test_partial_video_index_retains_its_original_tail(self):
        (self.pending / "screen.mp4").write_bytes(b"retained video")
        (self.pending / "screen-frames.jsonl").write_bytes(b'{"videoSeconds":0,"serverTickId":20}\n{"videoSeconds":')
        probe = subprocess.CompletedProcess([], 0, json.dumps({"packets": [{"pts_time": "0"}],
            "streams": [{"width": 640, "height": 360, "r_frame_rate": "1/1"}]}), "")
        with patch.object(playtest.artifact.subprocess, "run", return_value=probe):
            result = playtest.artifact.video_index(self.pending, 0)
        self.assertFalse(result["complete"])
        self.assertEqual([{"serverTick": "20", "videoSeconds": 0}], result["frames"])

    def test_recovery_after_atomic_publication_does_not_duplicate_or_lose_evidence(self):
        self.write_play()
        self.write_checkpoint()
        with patch.object(playtest.artifact, "discard_published_sources", side_effect=KeyboardInterrupt):
            with self.assertRaises(KeyboardInterrupt):
                playtest.finalize_recording(self.pending, self.destination, self.world,
                    {"minecraftExited": True}, None, "bug_report")
        self.assertTrue(self.pending.exists())
        self.assertFalse(self.destination.exists())
        path, code = playtest.finalize_recording(self.pending, self.destination, self.world,
            {"minecraftExited": True}, None, "recovered")
        self.assertEqual(0, code)
        self.assert_world(path, b"paused world")
        self.assertEqual(1, len(list(path.parent.glob("v1/*/players/*/plays/*/metadata.json"))))

    def test_publication_failure_preserves_original_sources(self):
        self.write_play()
        self.write_checkpoint()
        with patch.object(playtest.artifact, "_copy_evidence", side_effect=OSError("disk full")):
            path, code = playtest.finalize_recording(self.pending, self.destination, self.world,
                {"minecraftExited": True}, None, "bug_report")
        self.assertEqual(1, code)
        self.assertTrue((path / "live-recording.jsonl").is_file())
        self.assertTrue((path / "world-save/level.dat").is_file())
        self.assertTrue(list((path / "recorder").rglob("metadata.json")))

    def test_prejoin_failure_remains_a_harness_archive_without_fabricated_play(self):
        path, code = playtest.finalize_recording(self.pending, self.destination, self.world,
            {"minecraftExited": True}, "Could not join", "startup_failed")
        self.assertEqual(1, code)
        self.assertNotIn("artifactPlayPath", json.loads((path / "summary.json").read_text()))
        self.assertTrue((path / "live-recording.jsonl").is_file())

    def test_interrupted_report_keeps_other_evidence_viewable(self):
        self.write_play()
        self.write_checkpoint()
        (self.pending / "bug-report.json").write_bytes(b'{"description":')
        path, code = playtest.finalize_recording(self.pending, self.destination, self.world,
            {"minecraftExited": True}, "Client crashed", "crash")
        self.assertEqual(1, code)
        extension = self.evidence(path)
        self.assertEqual(b'{"description":', (extension / "bug-report.json").read_bytes())
        descriptor = json.loads((extension / "playtest.json").read_text())
        self.assertFalse(descriptor["run"]["recordingComplete"])
        self.assertIsNone(descriptor["bugReport"])

    def test_normal_exits_and_time_limits_archive_the_complete_dataset_without_a_bug(self):
        for reason in ("manual_interrupt", "client_exit", "world_left", "time_limit", "planner_degraded"):
            with self.subTest(reason=reason):
                if self.destination.exists():
                    self.setUp()
                (self.pending / "bug-report.json").unlink(missing_ok=True)
                (self.pending / "summary.json").write_text(json.dumps({"status": "FINISHED"}))
                if not (self.pending / "recorder").exists():
                    self.write_play()
                path, code = playtest.finalize_recording(self.pending, self.destination, self.world,
                    {"minecraftExited": True}, None, reason)
                self.assertEqual(0, code)
                summary = json.loads((path / "summary.json").read_text())
                self.assertEqual("COMPLETED", summary["status"])
                self.assertTrue(summary["recordingComplete"])
                self.assertFalse(summary["bugReported"])
                self.assertEqual(reason, summary["terminationReason"])
                self.assert_world(path, b"stopped world")
                self.assertFalse(json.loads((self.evidence(path) / "playtest.json").read_text())["checkpoint"]["capturedWhilePaused"])

    def test_crash_archives_partial_evidence_without_claiming_it_is_complete(self):
        (self.pending / "bug-report.json").unlink()
        (self.pending / "summary.json").unlink()
        (self.pending / "agent-status-final.json").unlink()
        (self.pending / "events.jsonl").write_bytes(b'{"event":"last flush"}\n{"partial":')
        play = self.write_play(complete=False)
        (play / "capture/replay.zip").unlink()
        scratch = self.pending / "recorder/server-replay/unfinished"
        scratch.mkdir(parents=True)
        (scratch / "chunks").write_bytes(b"unfinished replay data")
        path, code = playtest.finalize_recording(self.pending, self.destination, self.world,
            {"minecraftExited": True}, "Client exited with code 137", "crash")
        self.assertEqual(1, code)
        summary = json.loads((path / "summary.json").read_text())
        self.assertEqual("INCOMPLETE", summary["status"])
        self.assertFalse(summary["recordingComplete"])
        self.assertFalse(summary["bugReported"])
        self.assertIn("agent-status-final.json", summary["missingArtifacts"])
        self.assertEqual(b'{"event":"last flush"}\n{"partial":', __import__("gzip").decompress((self.evidence(path) / "events.jsonl.gz").read_bytes()))
        with zipfile.ZipFile(self.evidence(path) / "recorder-server-replay.zip") as archive:
            self.assertEqual(b"unfinished replay data", archive.read("unfinished/chunks"))

    def test_recovery_refuses_a_live_launcher_without_touching_recording(self):
        (self.pending / "launch.json").write_text(json.dumps({"launcherPid": os.getpid(), "workerDirectory": str(self.world.parent)}))
        before = (self.pending / "summary.json").read_bytes()
        with self.assertRaisesRegex(ValueError, "still active"):
            playtest.recover_recording(self.pending)
        self.assertEqual(before, (self.pending / "summary.json").read_bytes())
        self.assertFalse(self.destination.exists())

    def test_recovery_archives_legacy_interrupted_runs(self):
        worker = self.world.parent
        world = worker / "game/saves/playtest"
        world.mkdir(parents=True)
        (world / "level.dat").write_bytes(b"legacy saved world")
        (self.pending / "launch.json").write_text(json.dumps({"workerDirectory": str(worker)}))
        (self.pending / "summary.json").write_text(json.dumps({"status": "INTERRUPTED"}))
        (self.pending / "bug-report.json").unlink()
        self.write_play()
        self.assertEqual(1, playtest.recover_recording(self.pending))
        self.assertFalse(self.pending.exists())
        self.assert_world(self.destination, b"legacy saved world")
        self.assertEqual("INCOMPLETE", json.loads((self.destination / "summary.json").read_text())["status"])

    def test_recovery_refuses_an_old_live_world_without_process_metadata(self):
        worker = self.world.parent
        world = worker / "game/saves/playtest"
        world.mkdir(parents=True)
        (self.pending / "launch.json").write_text(json.dumps({"workerDirectory": str(worker)}))
        child = subprocess.Popen([sys.executable, "-c",
            "import fcntl,sys; f=open(sys.argv[1],'a+b'); fcntl.lockf(f,fcntl.LOCK_EX); print('locked',flush=True); sys.stdin.read()",
            str(world / "session.lock")], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
        try:
            self.assertEqual("locked", child.stdout.readline().strip())
            with self.assertRaisesRegex(ValueError, "still in use"):
                playtest.recover_recording(self.pending)
            self.assertTrue(self.pending.exists())
            self.assertFalse(self.destination.exists())
        finally:
            child.communicate(timeout=5)

    def test_stream_gaps_are_archived_but_excluded_from_complete_datasets(self):
        self.write_play()
        self.write_checkpoint()
        (self.pending / "summary.json").write_text(json.dumps({"status": "CAPTURE_READY", "visualHistoryTruncated": True}))
        path, code = playtest.finalize_recording(self.pending, self.destination, self.world,
            {"minecraftExited": True}, None, "bug_report")
        self.assertEqual(1, code)
        self.assertFalse(json.loads((path / "summary.json").read_text())["recordingComplete"])
        self.assertTrue((self.evidence(path) / "live-recording.jsonl.gz").exists())

    def test_a_torn_summary_write_keeps_its_bytes_and_the_rest_of_the_capture(self):
        self.write_play()
        (self.pending / "summary.json").write_bytes(b'{"status":')
        path, code = playtest.finalize_recording(self.pending, self.destination, self.world,
            {"minecraftExited": True}, "Client crashed", "crash")
        self.assertEqual(1, code)
        self.assertEqual(b'{"status":', (self.evidence(path) / "summary-incomplete.json").read_bytes())
        self.assertEqual("INCOMPLETE", json.loads((path / "summary.json").read_text())["status"])

    def test_completed_play_cannot_substitute_for_a_missing_paused_checkpoint(self):
        self.write_play()
        path, code = playtest.finalize_recording(self.pending, self.destination, self.world, {"minecraftExited": True}, None, "bug_report")
        self.assertEqual(1, code)
        self.assertIn("checkpoint", json.loads((path / "summary.json").read_text())["message"])
        self.assertTrue(self.destination.exists())

    def test_checkpoint_must_match_the_verified_report_tick(self):
        self.write_play()
        self.write_checkpoint()
        (self.pending / "pause-verification.json").write_text(json.dumps({"serverTickId": 21}))
        path, code = playtest.finalize_recording(self.pending, self.destination, self.world, {"minecraftExited": True}, None, "bug_report")
        self.assertEqual(1, code)
        self.assertIn("does not match", json.loads((path / "summary.json").read_text())["message"])


if __name__ == "__main__":
    unittest.main()
