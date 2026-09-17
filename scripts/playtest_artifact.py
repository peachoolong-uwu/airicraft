"""Publish Airicraft-owned evidence inside an unmodified Recorder Play (Artifacts V1)."""
from __future__ import annotations

import gzip
import json
from pathlib import Path
import shutil
import subprocess
import uuid
import zipfile

EXTENSION_TYPE = "airicraft.playtest"
SCHEMA = "airicraft.playtest.v1"


def read_json(path: Path, default=None):
    return json.loads(path.read_text()) if path.is_file() else default


def write_json(path: Path, value):
    temporary = path.with_name("." + path.name + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")
    temporary.replace(path)


def published_play(run: Path, artifacts: Path) -> Path | None:
    try:
        summary = read_json(run / "summary.json", {})
    except json.JSONDecodeError:
        return None  # The regular recovery path retains an interrupted summary's original bytes.
    relative = summary.get("artifactPlayPath")
    if not relative:
        return None
    path = artifacts / relative
    path.resolve().relative_to((artifacts / "v1").resolve())
    if not path.exists():
        return None
    descriptor = read_json(path / "extensions" / EXTENSION_TYPE / "playtest.json")
    if not descriptor or descriptor["run"]["id"] != run.name:
        raise ValueError("Published Play does not belong to this run")
    return path


def complete_lines(path: Path):
    """Only an interrupted final line may be discarded; interior corruption is an error."""
    with path.open() as source:
        for line in source:
            if not line.endswith("\n"):
                break
            if line.strip():
                yield json.loads(line)


def video_index(run: Path, offset: int) -> dict:
    video = run / "screen.mp4"
    index = run / "screen-frames.jsonl"
    if not video.is_file() or not index.is_file():
        return {"frames": [], "complete": False}
    result = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0",
        "-show_entries", "packet=pts_time", "-of", "json", str(video)], capture_output=True, text=True, timeout=60)
    # A crash can leave an incomplete final fragment. Expose only the retained packet range.
    packets = json.loads(result.stdout or "{}").get("packets", [])
    end = max((float(packet["pts_time"]) for packet in packets if "pts_time" in packet), default=-1)
    raw = list(complete_lines(index))
    frames = [{"videoSeconds": frame["videoSeconds"],
               "serverTick": str(int(frame["serverTickId"]) - offset)}
              for frame in raw if frame["videoSeconds"] <= end]
    return {"frames": frames, "complete": result.returncode == 0 and bool(frames) and len(frames) == len(raw)}


def _asset(path: str, role: str, media: str, schema: str) -> dict:
    return {"path": path, "role": role, "mediaType": media, "schema": schema}


def _copy_evidence(source: Path, target: Path) -> tuple[str, str]:
    if source.is_symlink() or not source.is_file():
        raise ValueError(f"Evidence must be a regular file: {source}")
    target.parent.mkdir(parents=True, exist_ok=True)
    if source.suffix == ".jsonl" or (source.suffix in (".json", ".log") and source.stat().st_size > 256_000):
        target = target.with_suffix(target.suffix + ".gz")
        with source.open("rb") as incoming, gzip.open(target, "wb", compresslevel=3) as outgoing:
            shutil.copyfileobj(incoming, outgoing)
        return target.name, "application/gzip"
    shutil.copyfile(source, target)
    return target.name, {".json": "application/json", ".png": "image/png", ".mp4": "video/mp4"}.get(source.suffix, "text/plain")


def _archive_directory(source: Path, target: Path):
    with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=3) as archive:
        for path in sorted(source.rglob("*")):
            if path.is_symlink():
                raise ValueError(f"Evidence cannot contain symlinks: {path}")
            if path.is_file():
                archive.write(path, path.relative_to(source).as_posix())


def publish(run: Path, artifacts: Path) -> Path | None:
    """Publish complete or interrupted evidence; never invent a Play for a pre-join failure.

    Keep original evidence until the extension and canonical server directory are published.
    A retry after interruption reuses only the exact same run's already-published Play.
    """
    summary = read_json(run / "summary.json", {})
    existing = published_play(run, artifacts)
    if existing:
        return existing
    metadata_paths = list((run / "recorder/v1").glob("*/players/*/plays/*/metadata.json"))
    if not metadata_paths:
        return None
    if len(metadata_paths) != 1:
        raise ValueError(f"Expected one Recorder Play, found {len(metadata_paths)}")
    play = metadata_paths[0].parent
    metadata = read_json(metadata_paths[0])
    identity = {"serverInstanceId": metadata["server"]["instanceId"],
                "playerUuid": metadata["player"]["uuid"], "connectionId": metadata["connection"]["id"]}
    for value in identity.values():
        if str(uuid.UUID(value)) != value:
            raise ValueError("Recorder Play identity must use canonical UUIDs")
    connection = metadata["connection"]
    start = int(connection["startServerTick"])
    end = connection.get("endServerTick")
    warnings = []

    def optional_evidence(name):
        try:
            return read_json(run / name, {})
        except json.JSONDecodeError:
            warnings.append(f"Interrupted {name}; original bytes retained")
            return {}

    context = optional_evidence("recording-start.json").get("context", {})
    clock = context.get("clock")
    offset = int(clock["debugServerTick"]) - int(clock["serverTick"]) if clock else None
    try:
        video = video_index(run, offset) if offset is not None else {"frames": [], "complete": False}
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        video = {"frames": [], "complete": False, "error": str(error)}
        warnings.append(f"Screen index could not be recovered: {error}")
    relative = Path("v1") / play.relative_to(run / "recorder/v1")
    summary["artifactPlayPath"] = relative.as_posix()
    summary["recorderPlayPath"] = "../" + relative.as_posix() if summary.get("recorderPlayPath") else None
    summary.pop("worldSavePath", None)
    if (run / "world-save").is_dir():
        summary["worldCheckpointAsset"] = "world-save.zip"
    summary.setdefault("id", run.name)
    if summary.get("screenVideoRequired") and not video["complete"]:
        summary.update(recordingComplete=False, status="INCOMPLETE", harnessStatus="CAPTURE_ERROR",
                       message="Screen recording is missing or incomplete; retained fragments remain available")
    # Bounds describe observed evidence on an unfinished Play, never a fabricated completion marker.
    observed_end = max((int(frame["serverTick"]) for frame in video["frames"]), default=start)
    timeline = {"startServerTick": str(start), "endServerTick": str(int(end) if end is not None else observed_end)}
    report = optional_evidence("bug-report.json") or None
    pause = optional_evidence("pause-verification.json")
    if report and offset is not None and "serverTickId" in pause:
        report = {**report, "serverTick": str(int(pause["serverTickId"]) - offset)}
    if warnings:
        summary.update(recordingComplete=False, status="INCOMPLETE", harnessStatus="CAPTURE_ERROR",
                       publicationWarnings=warnings, message=summary.get("message") or warnings[0])
    harness = read_json(run / "harness-summary.json", {})
    harness.update(recorderPlayPath=summary["recorderPlayPath"], harnessStatus=summary["harnessStatus"],
                   message=summary.get("message"))
    parent = play / "extensions"
    extension = parent / EXTENSION_TYPE
    temporary = parent / ("." + EXTENSION_TYPE + "-" + str(uuid.uuid4()))
    temporary.mkdir(parents=True)
    assets = [_asset("playtest.json", "playtest", "application/json", SCHEMA),
              _asset("video-index.json", "video_index", "application/json", "airicraft.screen-index.v1")]
    files = []
    try:
        write_json(temporary / "video-index.json", video)
        for source in sorted(run.iterdir()):
            if source.name == "recorder":
                continue
            if source.is_symlink():
                raise ValueError(f"Evidence cannot contain symlinks: {source}")
            if source.is_dir():
                target = temporary / (source.name + ".zip")
                _archive_directory(source, target)
                media = "application/zip"
            else:
                name, media = _copy_evidence(source, temporary / source.name)
                target = temporary / name
            role = {"screen.mp4": "screen_video", "live-recording.jsonl.gz": "observations",
                    "world-save.zip": "world_checkpoint"}.get(target.name, "evidence")
            assets.append(_asset(target.name, role, media, "airicraft.evidence.v1"))
            files.append({"path": target.name, "bytes": target.stat().st_size})
        # ServerReplay scratch can live outside v1 on failure; retain it with the Play too.
        for source in sorted((run / "recorder").iterdir()):
            if source.name == "v1":
                continue
            target = temporary / ("recorder-" + source.name + ".zip")
            if source.is_dir():
                _archive_directory(source, target)
                media = "application/zip"
            else:
                target = temporary / ("recorder-" + source.name)
                name, media = _copy_evidence(source, target)
                target = temporary / name
            assets.append(_asset(target.name, "recorder_scratch", media, "airicraft.evidence.v1"))
            files.append({"path": target.name, "bytes": target.stat().st_size})
        write_json(temporary / "summary.json", summary)
        write_json(temporary / "harness-summary.json", harness)
        for file in files:
            file["bytes"] = (temporary / file["path"]).stat().st_size
        write_json(temporary / "playtest.json", {"schemaVersion": 1, "run": summary,
            "timeline": timeline, "debugTickOffset": offset, "bugReport": report, "files": files})
        write_json(temporary / "manifest.json", {"manifestVersion": 1, "extensionType": EXTENSION_TYPE,
            "play": identity, "timeDomain": "PLAY_EXTENSION_TIME_DOMAIN_SERVER_TICK", "assets": assets})
        if extension.exists():
            previous = read_json(extension / "playtest.json", {})
            if previous.get("run", {}).get("id") != run.name:
                raise ValueError("Refusing to replace another playtest extension")
            shutil.rmtree(extension)
        temporary.rename(extension)
        # Each isolated recording profile has its own server identity, allowing one atomic publication.
        server = play.relative_to(run / "recorder/v1").parts[0]
        destination = artifacts / "v1" / server
        destination.parent.mkdir(parents=True, exist_ok=True)
        if destination.exists():
            raise ValueError(f"Artifact server directory already exists: {destination}")
        # Journal the target before the atomic move, so recovery can finish publication after either boundary.
        write_json(run / "summary.json", summary)
        (run / "recorder/v1" / server).rename(destination)
        return artifacts / relative
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)


def discard_published_sources(run: Path):
    """The small run index remains; all evidence now belongs to the published extension."""
    for source in run.iterdir():
        if source.name in ("summary.json", "harness-summary.json"):
            continue
        if source.is_dir():
            shutil.rmtree(source)
        else:
            source.unlink()
