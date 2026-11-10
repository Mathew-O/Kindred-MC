#!/usr/bin/env python3
"""The failure corpus's report and merge gate (F-27a).

usage: report.py [RESULTS_XML ...] [--baseline FILE] [--write-baseline] [--sha SHA] [--out FILE]

Each RESULTS_XML is one `runCorpus` run's report (companion-server-adapter/build/corpus-results.xml by default); give
three for the daily number. A scene that passes every run passes; one that fails every run fails; one that does both is
intermittent, and an intermittent scene never counts as a pass. A scene with no result in a run counts as failed there.
Staged scenes (a contract fixture run as a scene, `"fixture"` in its file) are listed apart.

The gate (exit 1): a scene that passed in the baseline (tools/corpus/baseline.json, the last run on main) now fails in
two or more of the runs given, or in the one run when only one is given.
"""
import argparse
import json
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCENES = ROOT / "companion-server-adapter/src/corpus/resources/data/kindred_corpus/gametest/corpus"


def outcomes(results):
    out = {}
    for case in ET.parse(results).getroot().iter("testcase"):
        name = case.get("name", "").split(" ")[0]
        if name.startswith("corpus."):
            out[name[len("corpus."):]] = not any(child.tag in ("failure", "error") for child in case)
    return out


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("results", nargs="*", type=Path)
    parser.add_argument("--baseline", type=Path, default=ROOT / "tools/corpus/baseline.json")
    parser.add_argument("--write-baseline", action="store_true")
    parser.add_argument("--sha", default="", help="the build the runs were on, for the report's heading")
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    runs = [outcomes(r) for r in (args.results or [ROOT / "companion-server-adapter/build/corpus-results.xml"]) if r.is_file()]
    if not runs:
        sys.exit("no results to report")
    scenes = {p.stem: json.loads(p.read_text(encoding="utf-8")) for p in sorted(SCENES.glob("*.json"))}
    fails = {sid: sum(1 for run in runs if not run.get(sid, False)) for sid in scenes}
    status = {sid: "pass" if n == 0 else "fail" if n == len(runs) else "intermittent" for sid, n in fails.items()}
    staged = sorted(sid for sid, scene in scenes.items() if "fixture" in scene)
    counts = Counter(status.values())
    total = len(status)
    lines = [f"# Failure corpus{' on ' + args.sha if args.sha else ''}: {counts['pass']} of {total} scenes pass "
             f"({100.0 * counts['pass'] / max(1, total):.1f}%) over {len(runs)} run{'s' if len(runs) > 1 else ''}", "",
             f"Passing {counts['pass']}, failing {counts['fail']}, intermittent {counts['intermittent']} (an intermittent "
             f"scene passed some runs and failed others; it is not counted as a pass).", "",
             "| Class | Pass | Intermittent | Fail | Scenes |", "| --- | --- | --- | --- | --- |"]
    by_class = {}
    for sid, s in status.items():
        c = scenes[sid].get("corpus", {}).get("class", "other")
        by_class.setdefault(c, Counter())[s] += 1
    for c, k in sorted(by_class.items(), key=lambda kv: -sum(kv[1].values())):
        lines.append(f"| {c} | {k['pass']} | {k['intermittent']} | {k['fail']} | {sum(k.values())} |")
    if staged:
        lines += ["", "Staged scenes (contract fixtures run as scenes):"]
        lines += [f"- {sid}: {status[sid]} ({scenes[sid]['fixture']})" for sid in staged]
    base = json.loads(args.baseline.read_text(encoding="utf-8")) if args.baseline.is_file() else {}
    limit = 1 if len(runs) == 1 else 2
    gate = sorted(sid for sid in status if base.get(sid) == "pass" and fails[sid] >= limit)
    newly_passing = sorted(sid for sid in status if base.get(sid) in ("fail", "intermittent") and status[sid] == "pass")
    lines += ["", f"Gate: passing on the baseline, now failing in {limit} or more of {len(runs)} run(s): {len(gate)}"
              + (" — " + ", ".join(gate) if gate else "")]
    lines.append(f"Newly passing against the baseline: {len(newly_passing)}" + (" — " + ", ".join(newly_passing) if newly_passing else ""))
    if not base:
        lines.append("No baseline yet: this run can be recorded as one with --write-baseline.")
    text = "\n".join(lines) + "\n"
    print(text)
    if args.out:
        args.out.write_text(text, encoding="utf-8")
    if args.write_baseline:
        args.baseline.write_text(json.dumps(status, indent=1, sort_keys=True) + "\n", encoding="utf-8")
    sys.exit(1 if gate else 0)


if __name__ == "__main__":
    main()
