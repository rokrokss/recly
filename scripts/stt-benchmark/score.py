"""Score saved hypotheses with explicit, identical normalization for every engine.

This is not the official HiKE scorer: loanword equivalence is intentionally not
applied. English spelling preservation is reported separately.
"""
import argparse
from collections import defaultdict
import json
from pathlib import Path
import re
import unicodedata

import numpy as np
from rapidfuzz.distance import Levenshtein


def normalize(text):
    text = unicodedata.normalize("NFKC", text).lower()
    text = "".join(c if c.isalnum() or c.isspace() else " " for c in text)
    return " ".join(text.split())


def tokens(text, metric):
    text = normalize(text)
    if metric == "cer":
        return list(text.replace(" ", ""))
    if metric == "wer":
        return text.split()
    if metric == "mixed":
        return re.findall(r"[a-z]+|\d+|[^\W_]", text, flags=re.UNICODE)
    if metric == "english":
        return re.findall(r"[a-z]+", text)
    raise ValueError(metric)


def counts(reference, hypothesis, metric):
    ref, hyp = tokens(reference, metric), tokens(hypothesis, metric)
    return Levenshtein.distance(ref, hyp), len(ref)


def read_records(path):
    records = []
    for line in path.read_text().splitlines():
        try:
            record = json.loads(line)
        except ValueError:
            continue
        if record.get("event") == "sample":
            records.append(record)
    if len({r["id"] for r in records}) != len(records):
        raise ValueError(f"Duplicate sample ID in {path}")
    return {r["id"]: r for r in records}


def bootstrap(values, draws=2000):
    values = np.asarray(values, dtype=float)
    if values[:, 1].sum() == 0:
        return None
    rng = np.random.default_rng(2319)
    sums = values[rng.integers(0, len(values), (draws, len(values)))].sum(axis=1)
    ratios = sums[:, 0] / np.maximum(sums[:, 1], 1)
    return (np.quantile(ratios, [0.025, 0.975]) * 100).tolist()


def evaluate(root, label):
    manifest = json.loads((root / "manifest.json").read_text())
    output = {"normalizer": "NFKC, lowercase, punctuation to spaces; CER excludes spaces",
              "mixed_tokens": "Hangul characters, English words, number groups; no loanword aliases",
              "confidence_interval": "2000 utterance-bootstrap draws, descriptive; correlated sources not controlled",
              "engines": {}}
    errors = []
    for path in sorted((root / "results").glob(f"{label}-*.jsonl")):
        engine = path.stem[len(label) + 1:]
        records = read_records(path)
        entry = {"received": len(records), "expected": len(manifest), "datasets": {}}
        for dataset in ["fleurs", "hike", "control"]:
            samples = [r for r in manifest if r["dataset"] == dataset]
            missing = [r["id"] for r in samples if r["id"] not in records]
            present = [r for r in samples if r["id"] in records]
            result = {"samples": len(present), "missing": len(missing),
                      "failures": sum("error" in records[r["id"]] for r in present)}
            if not present:
                entry["datasets"][dataset] = result
                continue
            measured = [records[r["id"]] for r in present]
            duration = sum(r["duration"] for r in present)
            seconds = sum(r["wall_seconds"] for r in measured)
            result.update(audio_seconds=duration, inference_seconds=seconds, rtf=seconds / duration,
                          latency_p50=float(np.median([r["wall_seconds"] for r in measured])),
                          latency_p95=float(np.quantile([r["wall_seconds"] for r in measured], .95)))
            if dataset == "control":
                result["outputs"] = {r["id"]: records[r["id"]].get("text", "") for r in present}
            else:
                for metric in ["cer", "wer", "mixed", "english"]:
                    values = [counts(r["reference"], records[r["id"]].get("text", ""), metric) for r in present]
                    edits, units = map(sum, zip(*values))
                    result[metric] = {"edits": edits, "reference_units": units,
                                      "percent": 100 * edits / units if units else None,
                                      "ci95_percent": bootstrap(values) if units else None}
                if dataset == "hike":
                    result["by_cs_level"] = {}
                    for level in ["word", "phrase", "sentence"]:
                        group = [r for r in present if r.get("cs_level") == level]
                        values = [counts(r["reference"], records[r["id"]].get("text", ""), "mixed") for r in group]
                        if values:
                            edits, units = map(sum, zip(*values))
                            result["by_cs_level"][level] = {"samples": len(group), "mixed_percent": 100 * edits / units}
                for sample in present:
                    record = records[sample["id"]]
                    edits, units = counts(sample["reference"], record.get("text", ""), "cer")
                    if edits:
                        errors.append({"engine": engine, "id": sample["id"], "dataset": dataset,
                                       "reference": sample["reference"], "hypothesis": record.get("text", ""),
                                       "char_edits": edits, "reference_characters": units})
            entry["datasets"][dataset] = result
        entry["reported_peak_rss_bytes"] = max((r.get("rss_peak_bytes", 0) for r in records.values()), default=0) or None
        entry["reported_peak_mlx_bytes"] = max((r.get("mlx_peak_bytes", 0) for r in records.values()), default=0) or None
        output["engines"][engine] = entry
    (root / "results" / f"{label}-scores.json").write_text(json.dumps(output, indent=2, ensure_ascii=False))
    (root / "results" / f"{label}-errors.json").write_text(json.dumps(errors, indent=2, ensure_ascii=False))
    for engine, entry in output["engines"].items():
        datasets = entry["datasets"]
        def score(dataset, metric):
            return round(datasets[dataset].get(metric, {}).get("percent") or 0, 2)
        print(engine, f"{entry['received']}/{entry['expected']}",
              "KO_CER", score("fleurs", "cer"), "CS_MIXED", score("hike", "mixed"),
              "CS_EN_WER", score("hike", "english"))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    parser.add_argument("--label", default="main")
    args = parser.parse_args()
    evaluate(args.root, args.label)
