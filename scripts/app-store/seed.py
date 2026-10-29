#!/usr/bin/env python3
"""Builds the iPhone demo data set for the README promo images from the iPhone app's own data directory.

Usage: seed.py <en|ko> <app data dir> <out dir>  (README.md: where the data directory is)

The data directory is a fresh simulator install's `Library/Application Support/app.recly` after one launch, so
the processing settings and the device id are the app's own. The recordings are the Play set's
(`scripts/play-store/demo_data.py`) and are built the same way (`scripts/play-store/seed.py`): TTS audio encoded
like the app's recorder (AAC-LC m4a, 16 kHz mono, 32 kbps), meta.json, a transcript.json whose segment times
are the real offsets in that audio, and job rows that say the upload, transcription and publish all succeeded.
The job's workflow is the one `ProcessingPlan.compile` makes from those settings.
"""
import hashlib, importlib.util, json, os, random, shutil, sqlite3, subprocess, sys, tempfile, wave
from datetime import timedelta, timezone, datetime

sys.dont_write_bytecode = True
PLAY = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "play-store")
sys.path.insert(0, PLAY)
spec = importlib.util.spec_from_file_location("play_seed", os.path.join(PLAY, "seed.py"))
play = importlib.util.module_from_spec(spec)
spec.loader.exec_module(play)
from demo_data import SETS

# What `AppleSpeechTranscriber` writes as the transcript's provider on iOS 26.5.
PROVIDER = {"name": "apple-speech", "model": "speech-Version 26.5 (Build 23F77)"}
AUDIO = {"codec": "aac-lc", "container": "m4a", "sampleRateHz": 16000, "channels": 1, "bitrateKbps": 32, "segmentSec": 900}
RETRY = {"maxAttempts": 8, "initialDelaySec": 30, "maxDelaySec": 3600}


def workflow(settings_doc: dict) -> dict:
    """`ProcessingPlan.compile` for on-device transcription into Drive."""
    s = settings_doc["settings"]
    return {"id": "00000000000000000000REC100", "name": "Recording", "updatedAt": settings_doc["updatedAt"],
            "minDurationSec": s["storage"]["minDurationSec"], "steps": [
                {"type": "drive.upload", "id": "upload", "onError": "abort", "retry": RETRY,
                 "folder": "recly/memo/{{yyyy}}-{{MM}}", "includeMeta": True, "store": "drive"},
                {"type": "local.transcribe", "id": "transcribe", "onError": "abort", "retry": RETRY,
                 "language": s["transcription"]["language"], "diarize": True, "vocabulary": []},
                {"type": "transcript.publish", "id": "publish", "onError": "abort", "retry": RETRY}]}


def main(lang: str, data_dir: str, out: str) -> None:
    recordings, voice, language = SETS[lang]
    random.seed(f"recly-app-store-{lang}")
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(os.path.join(out, "recordings"))
    db_path = os.path.join(out, "rec.db")
    for suffix in ("", "-wal", "-shm"):
        if os.path.exists(os.path.join(data_dir, "rec.db" + suffix)):
            shutil.copy(os.path.join(data_dir, "rec.db" + suffix), db_path + suffix)
    db = sqlite3.connect(db_path)
    db.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    device_id = open(os.path.join(data_dir, "device.id")).read().strip()

    settings = json.loads(db.execute("SELECT value FROM sync_state WHERE key = 'processing/settings'").fetchone()[0])
    settings["settings"]["transcription"]["mode"] = "local"
    settings["settings"]["transcription"]["language"] = language
    db.execute("UPDATE sync_state SET value = ? WHERE key = 'processing/settings'", (json.dumps(settings, separators=(",", ":")),))
    plan = workflow(settings)
    for table in ("step_run", "job", "part", "recording"):
        db.execute(f"DELETE FROM {table}")
    db.execute("DELETE FROM sync_state WHERE key LIKE 'processing/recording/%'")

    tmp = tempfile.mkdtemp()
    for local, title, lines in recordings:
        started = datetime.strptime(local, "%Y-%m-%d %H:%M").replace(tzinfo=play.TZ) + timedelta(seconds=random.randint(0, 50))
        rid = play.ulid(started)
        base = started.astimezone(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + f"_phone_{rid[:8]}"
        folder = os.path.join(out, "recordings", base)
        os.makedirs(folder)

        pcm, segments, cursor = bytearray(), [], 0.0
        silence = b"\x00\x00" * int(play.RATE * play.GAP)
        for i, text in enumerate(lines):
            frames = play.tts(text, voice, os.path.join(tmp, f"{i}.wav"))
            length = len(frames) / 2 / play.RATE
            segments.append({"start": round(cursor, 2), "end": round(cursor + length, 2), "speaker": "", "text": text})
            pcm += frames + silence
            cursor += length + play.GAP
        duration = round(len(pcm) / 2 / play.RATE, 3)
        wav = os.path.join(tmp, "all.wav")
        with wave.open(wav, "wb") as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(play.RATE); w.writeframes(bytes(pcm))
        part_file = f"{base}_p001_mono.m4a"
        part_path = os.path.join(folder, part_file)
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", wav, "-c:a", "aac", "-b:a", "32k", "-ar", str(play.RATE),
                        "-ac", "1", "-movflags", "+faststart", part_path], check=True)
        audio = open(part_path, "rb").read()
        sha, md5 = hashlib.sha256(audio).hexdigest(), hashlib.md5(audio).hexdigest()
        ended = started + timedelta(seconds=duration + 1.1)

        meta = {
            "schema": 1, "recordingId": rid, "source": "phone", "platform": "ios",
            "deviceId": device_id, "deviceName": "iPhone",
            "title": title, "startedAt": play.iso(started), "endedAt": play.iso(ended), "durationSec": duration,
            "timezone": play.TZ_NAME, "audio": AUDIO, "tracks": ["mono"],
            "parts": [{"part": 1, "track": "mono", "file": part_file, "bytes": len(audio), "sha256": sha,
                       "startOffsetSec": 0.0, "durationSec": duration}],
            "gaps": [], "silenced": [], "status": "finalized",
        }
        meta_json = json.dumps(meta, ensure_ascii=False, separators=(",", ":"))
        with open(os.path.join(folder, f"{base}.meta.json"), "w") as f:
            f.write(meta_json)
        done = ended + timedelta(seconds=40 + len(lines) * 3)
        transcript = {
            "schema": 2, "recordingId": rid, "track": "mono", "language": language,
            "provider": PROVIDER, "createdAt": play.iso(done),
            "durationSec": duration, "speakers": [], "segments": segments,
            "speakerIdentification": "unavailable", "timing": "segment",
        }
        with open(os.path.join(folder, f"{base}.transcript.json"), "w") as f:
            json.dump(transcript, f, ensure_ascii=False)
        play.write_notes(folder, base, play.notes(lang, title, rid, segments, done + timedelta(seconds=20)))

        drive_folder = "1" + "".join(random.choice("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_") for _ in range(32))
        db.execute("INSERT INTO recording(id, source, platform, workflow_id, title, started_at, ended_at, duration_sec, timezone, dir, meta_json, status, drive_folder_id, remote, remote_pending, drive_synced) "
                   "VALUES (?, 'phone', 'ios', NULL, ?, ?, ?, ?, ?, ?, ?, 'finalized', ?, 0, NULL, 0)",
                   (rid, title, play.iso(started), play.iso(ended), duration, play.TZ_NAME, f"recordings/{base}", meta_json, drive_folder))
        db.execute("INSERT INTO part(recording_id, part, track, file, bytes, sha256, md5, deleted, drive_file_id) VALUES (?, 1, 'mono', ?, ?, ?, ?, 0, NULL)",
                   (rid, part_file, len(audio), sha, md5))
        created = ended + timedelta(seconds=1)
        job_id = play.ulid(created)
        db.execute("INSERT INTO job(id, recording_id, workflow_id, workflow_json, status, created_at, updated_at, next_run_at) VALUES (?, ?, ?, ?, 'DONE', ?, ?, NULL)",
                   (job_id, rid, plan["id"], json.dumps(plan, separators=(",", ":")), play.iso(created), play.iso(done)))
        for ordinal, step in enumerate(plan["steps"]):
            db.execute("INSERT INTO step_run(id, job_id, step_id, ordinal, status, attempts, next_attempt_at, last_error, state_json, output_json) VALUES (?, ?, ?, ?, 'SUCCEEDED', 1, NULL, NULL, NULL, NULL)",
                       (play.ulid(created), job_id, step["id"], ordinal))
        snapshot = dict(settings, updatedAt=play.iso(started))
        db.execute("INSERT INTO sync_state(key, value) VALUES (?, ?)", (f"processing/recording/{rid}", json.dumps(snapshot, separators=(",", ":"))))
        print(f"{lang} {title!r}: {duration:.1f}s, {len(segments)} segments, {base}")
    db.commit()
    db.close()
    for suffix in ("-wal", "-shm"):
        if os.path.exists(db_path + suffix):
            os.remove(db_path + suffix)
    shutil.rmtree(tmp)


if __name__ == "__main__":
    main(*sys.argv[1:4])
