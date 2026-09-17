"""Automatic-playtest artifact handoff, using the real evaluation Play validator."""
import importlib.util
import json
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
        path, code = playtest.finalize_incident(self.pending, self.destination, self.world, {"minecraftExited": True}, None)
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
        path, code = playtest.finalize_incident(self.pending, self.destination, self.world, {"minecraftExited": True}, None)
        self.assertEqual(1, code)
        self.assertEqual(self.pending, path)
        self.assertFalse(self.destination.exists())
        self.assertTrue((path / "bug-report.json").is_file())
        self.assertEqual("CAPTURE_ERROR", json.loads((path / "summary.json").read_text())["harnessStatus"])

    def test_never_moves_an_active_writer(self):
        self.write_play()
        path, code = playtest.finalize_incident(self.pending, self.destination, self.world, {"minecraftExited": False}, None)
        self.assertEqual(1, code)
        self.assertFalse(self.destination.exists())
        self.assertFalse((path / "world-save").exists())

    def test_timeout_preserves_evidence_without_fabricating_a_bug_report_outcome(self):
        self.write_play()
        path, code = playtest.finalize_incident(self.pending, self.destination, self.world, {"minecraftExited": True}, "No report within budget")
        self.assertEqual(1, code)
        self.assertEqual("INTERRUPTED", json.loads((path / "summary.json").read_text())["status"])
        self.assertFalse(self.destination.exists())

    def test_completed_play_cannot_substitute_for_a_missing_paused_checkpoint(self):
        self.write_play()
        path, code = playtest.finalize_incident(self.pending, self.destination, self.world, {"minecraftExited": True}, None)
        self.assertEqual(1, code)
        self.assertIn("checkpoint", json.loads((path / "summary.json").read_text())["message"])
        self.assertFalse(self.destination.exists())

    def test_checkpoint_must_match_the_verified_report_tick(self):
        self.write_play()
        self.write_checkpoint()
        (self.pending / "pause-verification.json").write_text(json.dumps({"serverTickId": 21}))
        path, code = playtest.finalize_incident(self.pending, self.destination, self.world, {"minecraftExited": True}, None)
        self.assertEqual(1, code)
        self.assertIn("does not match", json.loads((path / "summary.json").read_text())["message"])


if __name__ == "__main__":
    unittest.main()
