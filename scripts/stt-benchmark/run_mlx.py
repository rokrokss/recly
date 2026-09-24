"""Run one engine per process, writing sample-level timing and hypotheses as JSONL."""
import argparse
import importlib.metadata
import json
import os
import re
import resource
import sys
import time
from pathlib import Path

os.environ.setdefault("HF_HUB_DISABLE_TELEMETRY", "1")
os.environ.setdefault("HF_HUB_OFFLINE", "1")
os.environ.setdefault("HF_HUB_DISABLE_IMPLICIT_TOKEN", "1")


def emit(record):
    print(json.dumps(record, ensure_ascii=False, default=str), flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    parser.add_argument("engine")
    parser.add_argument("--manifest", default="manifest.json")
    parser.add_argument("--limit", type=int)
    parser.add_argument("--language", default="ko", choices=["ko", "auto"])
    args = parser.parse_args()
    import mlx.core as mx
    import soundfile as sf

    assets = json.loads((args.root / "assets.json").read_text())
    model_info = assets["models"][args.engine]
    path = model_info["path"]
    samples = json.loads((args.root / args.manifest).read_text())
    if args.limit:
        samples = samples[:args.limit]
    emit({"event": "engine", "engine": args.engine, "model": model_info,
          "mlx": importlib.metadata.version("mlx"), "mlx_audio": importlib.metadata.version("mlx-audio"),
          "language_mode": args.language, "device": mx.device_info(),
          "max_tokens": 2048, "temperature": 0, "batch_size": 1})
    start = time.perf_counter()
    if args.engine.startswith("whisper"):
        import mlx_whisper
        from mlx_whisper.transcribe import ModelHolder
        model = ModelHolder.get_model(path, mx.float16)
    else:
        from mlx_audio.stt import load
        model = load(path)
    mx.eval(model.parameters())
    mx.synchronize()
    emit({"event": "loaded", "seconds": time.perf_counter() - start,
          "rss_peak_bytes": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,
          "mlx_active_bytes": mx.get_active_memory()})
    for index, sample in enumerate(samples):
        start = time.perf_counter()
        # Decoding the same prepared PCM file is included in each sample's latency.
        audio, sr = sf.read(sample["audio"], dtype="float32")
        assert sr == 16000 and audio.ndim == 1
        try:
            language = None if args.language == "auto" else "ko"
            if args.engine.startswith("whisper"):
                result = mlx_whisper.transcribe(
                    audio, path_or_hf_repo=path, language=language,
                    task="transcribe", temperature=0.0, condition_on_previous_text=False,
                    word_timestamps=False, verbose=None,
                )
                text, segments = result["text"], result.get("segments", [])
            else:
                kwargs = {"max_tokens": 2048, "verbose": False}
                if args.engine.startswith("qwen"):
                    kwargs.update(language="Korean" if language else None, temperature=0.0,
                                  chunk_duration=30.0, batch_size=1)
                elif args.engine == "nemotron":
                    kwargs.update(language="ko-KR" if language else "auto", chunk_duration=30.0)
                result = model.generate(mx.array(audio) if args.engine == "nemotron" else audio, **kwargs)
                text = result.text
                segments = getattr(result, "segments", [])
                if args.engine == "moss" and segments:
                    # Structured speaker/timestamp markers must not count as recognized words.
                    text = " ".join(s.get("text", "") if isinstance(s, dict) else getattr(s, "text", "") for s in segments)
                    text = re.sub(r"\[S\d+\]\s*", "", text)
            mx.synchronize()
            emit({"event": "sample", "index": index, "id": sample["id"], "dataset": sample["dataset"],
                  "duration": sample["duration"], "text": text, "segments": segments,
                  "wall_seconds": time.perf_counter() - start,
                  "rss_peak_bytes": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,
                  "mlx_peak_bytes": mx.get_peak_memory()})
        except Exception as exc:
            import traceback
            traceback.print_exc(file=sys.stderr)
            emit({"event": "sample", "index": index, "id": sample["id"], "dataset": sample["dataset"],
                  "duration": sample["duration"], "error": repr(exc),
                  "wall_seconds": time.perf_counter() - start})
            if index == 0:
                raise


if __name__ == "__main__":
    main()
