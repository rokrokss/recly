"""Notes extraction bench: synthetic meetings, the app's own requests, a codex runner and a scorer.

  python3 -I scripts/notes-eval/notes_eval.py render [--answers DIR]
  python3 -I scripts/notes-eval/notes_eval.py run [--model MODEL] [--effort LEVEL] [--run-id ID] [--send]
  python3 -I scripts/notes-eval/notes_eval.py score --answers DIR [--tolerance SEC] [--verbose]

`render` builds each case's request with the app's code (Gradle, :core:notesEvalRender). `run` is a dry run unless
--send: only then are the requests sent, one `codex exec` call per case, on the signed-in ChatGPT plan. `score`
reads the answers as the app does and grades them against the ground truth. See README.md.
"""
import argparse
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tempfile
import time

HERE = Path(__file__).resolve().parent
sys.dont_write_bytecode = True  # no __pycache__ beside the scripts
sys.path.insert(0, str(HERE))
import scoring  # noqa: E402

ROOT = HERE.parents[1]
CASES = HERE / "cases"
OUT = ROOT / "build" / "notes-eval"
REQUESTS = OUT / "requests"
RUNS = OUT / "runs"
SCORES = OUT / "scores"
CALL_TIMEOUT_SEC = 900
# A codex agent with nothing but the app's request: its own instructions replaced by the app's, no tools, no web,
# no user config, rules or project docs, run from an empty folder outside the repository.
CODEX_FLAGS = [
    "--ignore-user-config", "--ignore-rules", "--ephemeral", "--skip-git-repo-check", "-s", "read-only",
    "--color", "never",
    *[x for feature in ("shell_tool", "unified_exec", "apps", "plugins", "multi_agent", "browser_use",
                        "computer_use", "image_generation", "hooks") for x in ("--disable", feature)],
    *[x for setting in ("include_permissions_instructions=false", "include_apps_instructions=false",
                        "include_environment_context=false", "include_collaboration_mode_instructions=false",
                        "project_doc_max_bytes=0", 'web_search="disabled"') for x in ("-c", setting)],
]


def case_names():
    names = sorted(p.name for p in CASES.iterdir() if (p / "transcript.json").is_file())
    if not names:
        sys.exit(f"no case in {CASES}")
    return names


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def memo(name):
    path = CASES / name / "memo.txt"
    return path.read_text().strip() if path.is_file() else ""


def request(name):
    """The rendered request of a case, refusing one rendered from an older transcript."""
    folder = REQUESTS / name
    if not (folder / "request.json").is_file():
        sys.exit(f"{name}: no request in {folder}. Render first: python3 -I scripts/notes-eval/notes_eval.py render")
    meta = json.loads((folder / "request.json").read_text())
    truth = json.loads((CASES / name / "truth.json").read_text())
    if (meta["transcriptSha256"] != sha256(CASES / name / "transcript.json") or meta["appLocale"] != truth["appLocale"]
            or meta["memo"] != memo(name)):
        sys.exit(f"{name}: the case changed since its request was rendered. Render again.")
    return {"dir": folder, "meta": meta, "instructions": (folder / "instructions.txt").read_text(),
            "input": (folder / "input.txt").read_text()}


# ---------------------------------------------------------------- render

def render(args):
    env = dict(os.environ)
    env.setdefault("JAVA_HOME", "/opt/homebrew/opt/openjdk@21")
    env.setdefault("ANDROID_HOME", "/opt/homebrew/share/android-commandlinetools")
    paths = [CASES, REQUESTS] + ([Path(args.answers).resolve()] if args.answers else [])
    gradlew = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    command = [str(gradlew), "-q", ":core:notesEvalRender", "--args=" + " ".join(f'"{p}"' for p in paths)]
    print("$", shlex.join(command), flush=True)
    subprocess.run(command, cwd=ROOT, env=env, check=True)
    for name in case_names():
        r = request(name)
        print(f"{name}: {len(r['instructions']) + len(r['input'])} characters, notes in {r['meta']['language']}, "
              f"{len(r['meta']['placeholders'])} value(s) hidden")
    print("requests in", REQUESTS)


# ---------------------------------------------------------------- run

def codex_command(folder, answer, model, effort, workdir):
    command = ["codex", "exec", *CODEX_FLAGS,
               "-c", "model_instructions_file=" + json.dumps(str(folder / "instructions.txt")),
               "-C", str(workdir), "-m", model, "-o", str(answer)]
    if effort:
        command += ["-c", f'model_reasoning_effort="{effort}"']
    return command + ["-"]


def run(args):
    names = case_names()
    if args.case:
        if args.case not in names:
            sys.exit(f"no case {args.case}: {', '.join(names)}")
        names = [args.case]
    requests = {name: request(name) for name in names}
    run_dir = RUNS / (args.run_id or datetime.now().strftime("%Y%m%dT%H%M%S"))
    model = args.model or "MODEL"
    print(f"{len(names)} case(s) ({', '.join(names)}): a real run makes {len(names)} call(s), one per case, "
          f"to {model} through `codex exec` on the signed-in ChatGPT plan.")
    print(f"Answers go to {run_dir}/<case>.txt, codex's output to <case>.log, timings to run.json.")
    example = codex_command(REQUESTS / names[0], run_dir / f"{names[0]}.txt", model, args.effort, "<empty temp dir>")
    print(f"Each call, e.g. {names[0]}:\n  {shlex.join(example)} < {REQUESTS / names[0] / 'input.txt'}")
    if not args.send:
        print("Dry run: nothing sent. To send: python3 -I scripts/notes-eval/notes_eval.py run --send --model MODEL")
        return
    if not args.model:
        sys.exit("--send needs --model: the model the app would use, e.g. the one Settings shows")
    if shutil.which("codex") is None:
        sys.exit("codex is not on PATH")
    if run_dir.exists():
        sys.exit(f"{run_dir} exists; pass another --run-id")
    run_dir.mkdir(parents=True)
    log = {"model": args.model, "effort": args.effort, "codex": subprocess.run(["codex", "--version"], text=True,
           capture_output=True).stdout.strip(), "calls": []}
    for name in names:
        answer = run_dir / f"{name}.txt"
        with tempfile.TemporaryDirectory(prefix="notes-eval-") as workdir:
            started = time.time()
            try:
                done = subprocess.run(codex_command(requests[name]["dir"], answer, args.model, args.effort, workdir),
                                      input=requests[name]["input"], text=True, capture_output=True,
                                      timeout=CALL_TIMEOUT_SEC)
                error = None if done.returncode == 0 and answer.is_file() else f"codex exit {done.returncode}"
                output = done.stderr[-20000:] + "\n--- stdout ---\n" + done.stdout[-20000:]
            except subprocess.TimeoutExpired:
                error, output = f"timed out after {CALL_TIMEOUT_SEC} s", ""
        (run_dir / f"{name}.log").write_text(output)
        log["calls"].append({"case": name, "seconds": round(time.time() - started, 1), "error": error})
        print(name, error or "answered", flush=True)
        (run_dir / "run.json").write_text(json.dumps(log, indent=1) + "\n")
    print(f"Score it: python3 -I scripts/notes-eval/notes_eval.py score --answers {run_dir}")


# ---------------------------------------------------------------- score

def score(args):
    answers = Path(args.answers)
    results, drift = [], False
    for name in case_names():
        path = answers / f"{name}.txt"
        if not path.is_file():
            print(f"{name}: no answer at {path}")
            continue
        case = scoring.load_case(CASES / name)
        meta = request(name)["meta"]
        reading = scoring.read_answer(path.read_bytes().decode("utf-8", "replace"), meta["placeholders"],
                                      case["segments"], case["speakers"])
        app_file = REQUESTS / name / f"app-items-{sha256(path)[:12]}.json"
        result = scoring.grade(case, reading, args.tolerance)
        if app_file.is_file():
            differences = scoring.same_reading(reading, json.loads(app_file.read_text()))
            result["parser"] = "differs from the app" if differences else "same as the app"
            result["parserDifferences"] = differences
            drift = drift or bool(differences)
        else:
            result["parser"] = "not checked against the app (render --answers DIR)"
        results.append(result)
    if not results:
        sys.exit(f"no answer in {answers}")
    report(results, args.verbose, args.tolerance)
    SCORES.mkdir(parents=True, exist_ok=True)
    out = SCORES / f"{answers.resolve().name}.json"
    out.write_text(json.dumps({"answers": str(answers), "tolerance": args.tolerance, "cases": results},
                              indent=1, ensure_ascii=False) + "\n")
    print("written to", out)
    if drift:
        sys.exit("The scorer reads an answer differently from the app: fix scoring.py (see above).")


def report(results, verbose, tolerance):
    columns = ("planted",) + scoring.CATEGORIES + ("dropped", "points", "no time")
    rows = [(r["case"], {"planted": r["planted"], **r["counts"], "dropped": r["dropped"], "points": r["points"],
                         "no time": r["withoutTime"], "time": (r["time"]["within"], r["time"]["checked"])}) for r in results]
    total = {c: sum(row[c] for _, row in rows) for c in columns}
    total["time"] = tuple(sum(row["time"][i] for _, row in rows) for i in (0, 1))
    print(f"{'case':<17}" + "".join(f"{c:>{len(c) + 2}}" for c in columns) + f"{'time ok':>9}")
    for name, row in rows + [("total", total)]:
        print(f"{name:<17}" + "".join(f"{row[c]:>{len(c) + 2}}" for c in columns) + f"{'%d/%d' % row['time']:>9}")
    print(f"(time ok: evidence within {tolerance:g} s of a ground-truth line; dropped: items the app would not keep "
          "— malformed, or a quote not found near its time; points: key points, not scored; no time: items kept "
          "without a time or a quote, always listed: nothing checks them)")
    for r in results:
        print(f"\n{r['case']}: parser {r['parser']}")
        for difference in r.get("parserDifferences", []):
            print("  PARSER", difference)
        if not r["readable"]:
            print("  unreadable answer: no JSON object")
        for line in r["items"]:
            timed = line["atSec"] is not None
            quiet = timed and (line["category"] == "point" or (line["category"] == "correct" and line["timeOff"] <= tolerance))
            if quiet and not verbose:
                continue
            clock = (f"{int(line['atSec']) // 3600:02d}:{int(line['atSec']) % 3600 // 60:02d}:{int(line['atSec']) % 60:02d}"
                     if timed else "no time ")
            detail = ", ".join(line["reasons"])
            if timed and line.get("timeOff", 0) > tolerance:
                detail = ", ".join(filter(None, [detail, f"evidence {line['timeOff']:g} s off"]))
            print(f"  {line['category']:<10} {line['kind']:<8} {clock}  {line['text']}"
                  + (f"  [{line['entry']}]" if line["entry"] else "") + (f"  ({detail})" if detail else ""))
        for missing in r["missing"]:
            print(f"  {'missing':<10} {missing}")
        for fact, places in r["memo"].items():
            print(f"  {'memo only':<10} {fact}: {', '.join(places) or 'left out'}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="step", required=True)
    p = sub.add_parser("render", help="build the requests with the app's code (Gradle)")
    p.add_argument("--answers", help="also have the app read <DIR>/<case>.txt, for score to compare with")
    p = sub.add_parser("run", help="send the requests through codex exec (a dry run without --send)")
    p.add_argument("--model", help="the ChatGPT model, e.g. the one the app's Settings shows; needed with --send")
    p.add_argument("--effort", choices=["low", "medium", "high"],
                   help="reasoning effort (the app sends none for notes; default: codex's default for the model)")
    p.add_argument("--run-id", help="the run folder's name under build/notes-eval/runs (default: the time)")
    p.add_argument("--case", help="only this case (default: every case)")
    p.add_argument("--send", action="store_true", help="really send the transcripts, on your ChatGPT plan")
    p = sub.add_parser("score", help="grade answers against the ground truth")
    p.add_argument("--answers", required=True, help="a folder with <case>.txt answers")
    p.add_argument("--tolerance", type=float, default=5.0, help="seconds an evidence time may be off (default 5)")
    p.add_argument("--verbose", action="store_true", help="list the correct items too")
    args = parser.parse_args()
    {"render": render, "run": run, "score": score}[args.step](args)


if __name__ == "__main__":
    main()
