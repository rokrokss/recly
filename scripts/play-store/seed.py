#!/usr/bin/env python3
"""Builds a demo data set for the Play Store screenshots from a template DB the app itself wrote.

Usage: seed.py <en|ko> <template rec.db> <out dir>  (README.md: where the template comes from)

The template is a fresh emulator's rec.db after two throwaway recordings (one with on-device
transcription on), so the workflow JSON, settings and device id are the app's own. This replaces
every recording in it with the demo ones: TTS audio encoded like the app's recorder (AAC-LC m4a,
16 kHz mono, 32 kbps), meta.json, a transcript.json whose segment times are the real offsets in
that audio, and job rows that say the upload, transcription and publish all succeeded.
"""
import hashlib, json, os, random, shutil, sqlite3, subprocess, sys, tempfile, wave
from datetime import datetime, timedelta, timezone

sys.dont_write_bytecode = True
sys.path.insert(0, os.path.dirname(__file__))
from demo_data import SETS

TZ_NAME, TZ = "Asia/Dubai", timezone(timedelta(hours=4))
CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
RATE, GAP = 16000, 0.35
QWEN_REVISION = "qwen3-asr-0.6b-int8-2026-03-25+sherpa-onnx-1.13.8"


def ulid(at: datetime) -> str:
    ms = int(at.timestamp() * 1000)
    head = "".join(CROCKFORD[(ms >> (5 * i)) & 31] for i in reversed(range(10)))
    return head + "".join(random.choice(CROCKFORD) for _ in range(16))


def iso(at: datetime) -> str:
    return at.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.") + f"{at.microsecond // 1000:03d}Z"


def tts(text: str, voice: str, path: str) -> bytes:
    subprocess.run(["say", "-v", voice, "-r", "150", "--file-format=WAVE", f"--data-format=LEI16@{RATE}", "-o", path, text], check=True)
    with wave.open(path) as w:
        assert w.getframerate() == RATE and w.getnchannels() == 1
        return w.readframes(w.getnframes())


def main(lang: str, template: str, out: str) -> None:
    recordings, voice, language = SETS[lang]
    random.seed(f"recly-play-{lang}")
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(os.path.join(out, "recordings"))
    db_path = os.path.join(out, "rec.db")
    shutil.copy(template, db_path)
    db = sqlite3.connect(db_path)

    workflow = json.loads(db.execute(
        "SELECT workflow_json FROM job WHERE workflow_json LIKE '%local.transcribe%' LIMIT 1").fetchone()[0])
    for step in workflow["steps"]:
        if step["type"] == "local.transcribe":
            step["language"] = language
    template_meta = json.loads(db.execute("SELECT meta_json FROM recording LIMIT 1").fetchone()[0])
    settings = json.loads(db.execute("SELECT value FROM sync_state WHERE key = 'processing/settings'").fetchone()[0])
    settings["settings"]["transcription"]["mode"] = "local"
    settings["settings"]["transcription"]["language"] = language
    db.execute("UPDATE sync_state SET value = ? WHERE key = 'processing/settings'", (json.dumps(settings, separators=(",", ":")),))
    for table in ("step_run", "job", "part", "recording"):
        db.execute(f"DELETE FROM {table}")
    db.execute("DELETE FROM sync_state WHERE key LIKE 'processing/recording/%'")

    tmp = tempfile.mkdtemp()
    for local, title, lines in recordings:
        started = datetime.strptime(local, "%Y-%m-%d %H:%M").replace(tzinfo=TZ) + timedelta(seconds=random.randint(0, 50))
        rid = ulid(started)
        base = started.astimezone(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + f"_phone_{rid[:8]}"
        folder = os.path.join(out, "recordings", base)
        os.makedirs(folder)

        pcm, segments, cursor = bytearray(), [], 0.0
        silence = b"\x00\x00" * int(RATE * GAP)
        for i, text in enumerate(lines):
            frames = tts(text, voice, os.path.join(tmp, f"{i}.wav"))
            length = len(frames) / 2 / RATE
            segments.append({"start": round(cursor, 2), "end": round(cursor + length, 2), "speaker": "", "text": text})
            pcm += frames + silence
            cursor += length + GAP
        duration = round(len(pcm) / 2 / RATE, 3)
        wav = os.path.join(tmp, "all.wav")
        with wave.open(wav, "wb") as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE); w.writeframes(bytes(pcm))
        part_file = f"{base}_p001_mono.m4a"
        part_path = os.path.join(folder, part_file)
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", wav, "-c:a", "aac", "-b:a", "32k", "-ar", str(RATE),
                        "-ac", "1", "-movflags", "+faststart", part_path], check=True)
        audio = open(part_path, "rb").read()
        sha, md5 = hashlib.sha256(audio).hexdigest(), hashlib.md5(audio).hexdigest()
        ended = started + timedelta(seconds=duration + 1.1)

        meta = {
            "schema": 1, "recordingId": rid, "source": "phone", "platform": "android",
            "deviceId": template_meta["deviceId"], "deviceName": template_meta["deviceName"],
            "title": title, "startedAt": iso(started), "endedAt": iso(ended), "durationSec": duration,
            "timezone": TZ_NAME, "audio": template_meta["audio"], "tracks": ["mono"],
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
            "provider": {"name": "qwen3-asr", "model": QWEN_REVISION}, "createdAt": iso(done),
            "durationSec": duration, "speakers": [], "segments": segments,
            "speakerIdentification": "unavailable", "timing": "segment",
        }
        with open(os.path.join(folder, f"{base}.transcript.json"), "w") as f:
            json.dump(transcript, f, ensure_ascii=False)

        drive_folder = "1" + "".join(random.choice("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_") for _ in range(32))
        db.execute("INSERT INTO recording(id, source, platform, workflow_id, title, started_at, ended_at, duration_sec, timezone, dir, meta_json, status, drive_folder_id, remote, remote_pending, drive_synced) "
                   "VALUES (?, 'phone', 'android', NULL, ?, ?, ?, ?, ?, ?, ?, 'finalized', ?, 0, NULL, 0)",
                   (rid, title, iso(started), iso(ended), duration, TZ_NAME, f"recordings/{base}", meta_json, drive_folder))
        db.execute("INSERT INTO part(recording_id, part, track, file, bytes, sha256, md5, deleted, drive_file_id) VALUES (?, 1, 'mono', ?, ?, ?, ?, 0, NULL)",
                   (rid, part_file, len(audio), sha, md5))
        created = ended + timedelta(seconds=1)
        job_id = ulid(created)
        db.execute("INSERT INTO job(id, recording_id, workflow_id, workflow_json, status, created_at, updated_at, next_run_at) VALUES (?, ?, ?, ?, 'DONE', ?, ?, NULL)",
                   (job_id, rid, workflow["id"], json.dumps(workflow, separators=(",", ":")), iso(created), iso(done)))
        for ordinal, step in enumerate(workflow["steps"]):
            db.execute("INSERT INTO step_run(id, job_id, step_id, ordinal, status, attempts, next_attempt_at, last_error, state_json, output_json) VALUES (?, ?, ?, ?, 'SUCCEEDED', 1, NULL, NULL, NULL, NULL)",
                       (ulid(created), job_id, step["id"], ordinal))
        snapshot = dict(settings, updatedAt=iso(started))
        db.execute("INSERT INTO sync_state(key, value) VALUES (?, ?)", (f"processing/recording/{rid}", json.dumps(snapshot, separators=(",", ":"))))
        print(f"{lang} {title!r}: {duration:.1f}s, {len(segments)} segments, {base}")
    db.commit()
    db.close()
    shutil.rmtree(tmp)


if __name__ == "__main__":
    main(*sys.argv[1:4])
