"""Supervise sequential benchmark processes without concurrent inference."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time

import psutil


def run(root, engines, manifest, label, timeout):
    source = Path(__file__).parent.resolve()
    folder = root / "results"
    folder.mkdir(exist_ok=True)
    records = []
    for engine in engines:
        if engine == "apple":
            command = [str(root / "apple-speech"), str(root / manifest)]
        elif engine.startswith("whisperkit"):
            info = json.loads((root / "whisperkit-assets.json").read_text())
            command = [str(source / "native-whisper/.build/release/WhisperKitBenchmark"),
                       str(root / manifest), info["model_folder"], info["tokenizer_folder"]]
            if engine.endswith("gpu"):
                command.append("cpuAndGPU")
        else:
            command = [str(root / "venv/bin/python"), str(source / "run_mlx.py"), str(root),
                       engine, "--manifest", manifest]
        start = time.monotonic()
        record = {"engine": engine, "command": command, "started_unix": time.time(),
                  "loadavg_start": os.getloadavg(), "peak_process_rss": 0,
                  "min_available_memory": psutil.virtual_memory().available}
        path = folder / f"{label}-{engine}"
        print("START", label, engine, flush=True)
        environment = dict(os.environ, HF_HUB_OFFLINE="1", HF_HUB_DISABLE_TELEMETRY="1",
                           HF_HUB_DISABLE_IMPLICIT_TOKEN="1", TOKENIZERS_PARALLELISM="false")
        with path.with_suffix(".jsonl").open("w") as output, path.with_suffix(".log").open("w") as error:
            process = subprocess.Popen(command, stdout=output, stderr=error, env=environment)
            observed = psutil.Process(process.pid)
            while process.poll() is None:
                try:
                    record["peak_process_rss"] = max(record["peak_process_rss"], observed.memory_info().rss)
                    record["min_available_memory"] = min(record["min_available_memory"], psutil.virtual_memory().available)
                except psutil.Error:
                    pass
                if time.monotonic() - start > timeout:
                    process.terminate()
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        process.kill()
                    record["timeout"] = True
                    break
                time.sleep(0.5)
            record["returncode"] = process.wait()
        record.update(process_seconds=time.monotonic() - start, loadavg_end=os.getloadavg())
        records.append(record)
        (folder / f"{label}-processes.json").write_text(json.dumps(records, indent=2))
        print("END", label, engine, record["returncode"], round(record["process_seconds"], 2), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    parser.add_argument("--engines", nargs="+", required=True)
    parser.add_argument("--manifest", default="manifest.json")
    parser.add_argument("--label", default="main")
    parser.add_argument("--timeout", type=float, default=1800)
    args = parser.parse_args()
    run(args.root, args.engines, args.manifest, args.label, args.timeout)
