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

spec = importlib.util.spec_from_file_location("automatic_playtest", Path(__file__).resolve().parents[1] / "automatic_playtest.py")
playtest = importlib.util.module_from_spec(spec)
spec.loader.exec_module(playtest)


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

    def write_checkpoint(self):
        (self.pending / "world-save").mkdir()
        (self.pending / "world-save/level.dat").write_bytes(b"paused world")
        (self.pending / "world-save.json").write_text(json.dumps({"capturedWhilePaused": True, "serverTickId": 20}))

    def write_play(self, complete=True):
        play = self.pending / "recorder/v1/server/players/player/plays/connection"
        (play / "capture").mkdir(parents=True)
        (play / "metadata.json").write_text(json.dumps({
            "connection": {"endedAt": "2026-09-17T00:00:00Z" if complete else None, "endServerTick": 20 if complete else None},
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
        self.assertEqual(relative, summary["recorderPlayPath"])
        self.assertEqual(b"paused world", (path / "world-save/level.dat").read_bytes())

    def test_missing_or_unfinished_replay_never_publishes_a_success(self):
        self.write_play(complete=False)
        self.write_checkpoint()
        path, code = playtest.finalize_recording(self.pending, self.destination, self.world, {"minecraftExited": True}, None, "bug_report")
        self.assertEqual(1, code)
        self.assertEqual(self.destination, path)
        self.assertTrue((path / "bug-report.json").is_file())
        self.assertEqual("CAPTURE_ERROR", json.loads((path / "summary.json").read_text())["harnessStatus"])

    def test_never_moves_an_active_writer(self):
        self.write_play()
        path, code = playtest.finalize_recording(self.pending, self.destination, self.world, {"minecraftExited": False}, None, "bug_report")
        self.assertEqual(1, code)
        self.assertFalse(self.destination.exists())
        self.assertFalse((path / "world-save").exists())

    def test_normal_exits_and_time_limits_archive_the_complete_dataset_without_a_bug(self):
        for reason in ("manual_interrupt", "client_exit", "world_left", "time_limit"):
            with self.subTest(reason=reason):
                if self.destination.exists():
                    self.destination.rename(self.pending)
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
                self.assertEqual(b"stopped world", (path / "world-save/level.dat").read_bytes())
                self.assertFalse(json.loads((path / "world-save.json").read_text())["capturedWhilePaused"])

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
        self.assertEqual(b'{"event":"last flush"}\n{"partial":', (path / "events.jsonl").read_bytes())
        self.assertEqual(b"unfinished replay data", (path / "recorder/server-replay/unfinished/chunks").read_bytes())

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
        self.assertEqual(b"legacy saved world", (self.destination / "world-save/level.dat").read_bytes())
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
        self.assertTrue((path / "live-recording.jsonl").exists())

    def test_a_torn_summary_write_keeps_its_bytes_and_the_rest_of_the_capture(self):
        self.write_play()
        (self.pending / "summary.json").write_bytes(b'{"status":')
        path, code = playtest.finalize_recording(self.pending, self.destination, self.world,
            {"minecraftExited": True}, "Client crashed", "crash")
        self.assertEqual(1, code)
        self.assertEqual(b'{"status":', (path / "summary-incomplete.json").read_bytes())
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
