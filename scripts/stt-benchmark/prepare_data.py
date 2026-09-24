"""Prepare a fixed, output-independent sample of two public evaluation datasets."""
import argparse
import hashlib
import io
import json
from collections import Counter
from pathlib import Path

import numpy as np
import pyarrow.parquet as pq
import soundfile as sf
from scipy.signal import resample_poly

SEED = "recly-stt-20260923-v1"


def key(value):
    return hashlib.sha256((SEED + value).encode()).hexdigest()


def prepare(root, count):
    assets = json.loads((root / "assets.json").read_text())
    out = root / "audio"
    out.mkdir(exist_ok=True)
    manifest = []
    for dataset, info in assets["datasets"].items():
        rows = pq.read_table(info["path"]).to_pylist()
        if dataset == "google/fleurs":
            selected = sorted(rows, key=lambda r: key(r["audio"]["path"]))[:count]
            prefix = "fleurs"
        else:
            selected = []
            levels = sorted(set(r["cs_level"] for r in rows))
            for level in levels:
                group = sorted((r for r in rows if r["cs_level"] == level), key=lambda r: key(r["sample_id"]))
                selected.extend(group[:count // len(levels)])
            selected.sort(key=lambda r: key(r["sample_id"]))
            prefix = "hike"
            print("HiKE population", dict(Counter(r["cs_level"] for r in rows)))
        for row in selected:
            source_id = row.get("sample_id") or Path(row["audio"]["path"]).stem
            sample_id = f"{prefix}_{source_id}"
            audio, sample_rate = sf.read(io.BytesIO(row["audio"]["bytes"]), dtype="float32")
            if audio.ndim == 2:
                audio = audio.mean(axis=1)
            if sample_rate != 16000:
                import math
                divisor = math.gcd(sample_rate, 16000)
                audio = resample_poly(audio, 16000 // divisor, sample_rate // divisor)
            path = out / f"{sample_id}.wav"
            sf.write(path, audio, 16000, subtype="PCM_16")
            entry = {
                "id": sample_id, "dataset": prefix, "source_id": source_id,
                "audio": str(path), "duration": len(audio) / 16000,
                "reference": row.get("raw_transcription", row.get("text")),
                "source_revision": info["revision"],
                "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            }
            for field in ["cs_level", "category", "loanwords", "text_pier_labeled", "gender"]:
                if field in row:
                    entry[field] = row[field]
            manifest.append(entry)
    # Interleave datasets. All engines receive the same order, without using model outputs.
    manifest.sort(key=lambda r: key(r["id"]))
    for name, audio in [
        ("silence", np.zeros(160000, dtype=np.float32)),
        ("noise", np.random.default_rng(1729).normal(0, 0.0001, 160000).astype(np.float32)),
    ]:
        path = out / f"control_{name}.wav"
        sf.write(path, audio, 16000, subtype="PCM_16")
        manifest.append({"id": f"control_{name}", "dataset": "control", "audio": str(path),
                         "duration": 10.0, "reference": "",
                         "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
    (root / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
    # Diagnostic inputs are not included in scored cold/warm runs.
    (root / "smoke.json").write_text(json.dumps([next(r for r in manifest if r["dataset"] == d)
                                                 for d in ["fleurs", "hike", "control"]], ensure_ascii=False))
    summary = {"seed": SEED, "samples": len(manifest), "manifest_sha256":
               hashlib.sha256((root / "manifest.json").read_bytes()).hexdigest(), "datasets": {}}
    for dataset in ["fleurs", "hike", "control"]:
        rows = [r for r in manifest if r["dataset"] == dataset]
        summary["datasets"][dataset] = {"samples": len(rows), "duration_seconds": sum(r["duration"] for r in rows)}
    (root / "dataset-summary.json").write_text(json.dumps(summary, indent=2))
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    parser.add_argument("--count", type=int, default=150)
    args = parser.parse_args()
    prepare(args.root, args.count)
