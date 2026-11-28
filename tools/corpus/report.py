#!/usr/bin/env python3
"""The failure corpus's report and merge gate (F-27a).

usage: report.py [RESULTS_XML ...] [--baseline FILE] [--write-baseline] [--sha SHA] [--out FILE]
                 [--confirm-branch XML ...] [--confirm-main XML ...]

Each RESULTS_XML is one `runCorpus` run's report (companion-server-adapter/build/corpus-results.xml by default); give
three for the daily number. A scene that passes every run passes; one that fails every run fails; one that does both is
intermittent, and an intermittent scene never counts as a pass. A scene with no result in a run counts as failed there.
Staged scenes (a contract fixture run as a scene, `"fixture"` in its file) are listed apart, and when scenes
come from more than one game mode (`corpus.mode`, survival when unset) each mode's pass rate is given apart.

The gate: a scene that passed in the baseline (tools/corpus/baseline.json, the last runs on main, with their sha) now
fails in two or more of the runs given, or in the one run when only one is given, is flagged. With confirming runs
given (each flagged scene run alone on the branch and on main's tip, three times each), a flagged scene blocks (exit 1)
only when the branch fails it two or more times of three and main passes it two or more times of three: it is then
confirmed. Otherwise it is cleared (noise, or main's own failure). Without confirming runs every flagged scene blocks.
The confirm files may hold every flagged scene a round (gate.sh's) or one scene's run each: a scene counts only the
files that hold it, and a scene found in fewer than three a side is not confirmed, and blocks.
A flagged scene listed in tools/corpus/known-intermittent.txt is marked expected; the rule is the same for it.
"""
import argparse
import json
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
# Each flagged scene is confirmed alone this many times a side.
CONFIRM_RUNS = 3
SCENES = ROOT / "companion-server-adapter/src/corpus/resources/data/kindred_corpus/gametest/corpus"


def known_intermittent(path):
    """The scenes known intermittent on main though the baseline counts them passing: {id: why}."""
    known = {}
    if path.is_file():
        for line in path.read_text(encoding="utf-8").splitlines():
            sid, _, why = line.partition("#")
            if sid.strip():
                known[sid.strip()] = why.strip() or "known intermittent"
    return known


KNOWN = known_intermittent(ROOT / "tools/corpus/known-intermittent.txt")


def outcomes(results):
    out = {}
    for case in ET.parse(results).getroot().iter("testcase"):
        name = case.get("name", "").split(" ")[0]
        if name.startswith("corpus."):
            out[name[len("corpus."):]] = not any(child.tag in ("failure", "error") for child in case)
    return out


def armour_back(log):
    """A run's waking scenes that reached their budget's end (F-27a4): how many with their armour back on, of how many."""
    import re
    on = total = 0
    with open(log, encoding="utf-8", errors="replace") as lines:
        for line in lines:
            m = re.search(r"Replay \d+ of \S+: its armour back on: (yes|no)", line)
            if m:
                total += 1
                on += m.group(1) == "yes"
    return on, total


def leftover(log):
    """Replay players (R<hex>_<run>) whose lines go on after their own batch ended: a test that ended without its stop."""
    import re
    batch, first, late = None, {}, set()
    with open(log, encoding="utf-8", errors="replace") as lines:
        for line in lines:
            m = re.search(r"Running test batch '(corpus_\d+)", line)
            if m:
                batch = m.group(1)
                continue
            # The runtime's own stop, logged as the next batch starts, is not acting: its last action dropped unrun, a break
            # in progress whose collection it ended, or a fight in progress it ended (F-27a3). Nor is the periodic cost line,
            # whose "webs by player" names every player the Record holds a web for (F-27a3a).
            if batch is None or ("dropped before it ran" in line and "stop=true" in line) \
                    or "its collection ended (authority ended)" in line or "The fight was interrupted" in line \
                    or "Kindred cost: day " in line or "Kindred cost: world day " in line:
                continue
            for name in set(re.findall(r"\bR[0-9a-f]{5,8}_\d\b", line)):
                first.setdefault(name, batch)
                if first[name] != batch:
                    late.add(name)
    return len(late)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("results", nargs="*", type=Path)
    parser.add_argument("--baseline", type=Path, default=ROOT / "tools/corpus/baseline.json")
    parser.add_argument("--write-baseline", action="store_true")
    parser.add_argument("--sha", default="", help="the build the runs were on, for the report's heading")
    parser.add_argument("--out", type=Path)
    parser.add_argument("--logs", nargs="*", type=Path, default=[], help="the runs' logs, to count replay players left behind by their batch")
    parser.add_argument("--flagged-out", type=Path, help="write the flagged scenes' ids here, one a line")
    parser.add_argument("--confirm-branch", nargs="*", type=Path, default=[], help="the flagged scenes run alone on the branch")
    parser.add_argument("--confirm-main", nargs="*", type=Path, default=[], help="the flagged scenes run alone on main's tip")
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
    by_kind = {}
    for sid, s in status.items():
        kind = scenes[sid].get("kind", "fixture" if "fixture" in scenes[sid] else "death")
        by_kind.setdefault("stuck" if kind in ("stuck", "spell") else kind, Counter())[s] += 1
    if len(by_kind) > 1:
        lines += ["", "| Kind | Pass | Intermittent | Fail | Scenes |", "| --- | --- | --- | --- | --- |"]
        for k_name, k in sorted(by_kind.items()):
            lines.append(f"| {k_name} | {k['pass']} | {k['intermittent']} | {k['fail']} | {sum(k.values())} |")
    by_mode = {}
    for sid, s in status.items():
        by_mode.setdefault(scenes[sid].get("corpus", {}).get("mode", "survival"), Counter())[s] += 1
    if len(by_mode) > 1:
        lines += ["", "| Mode | Pass | Intermittent | Fail | Scenes | Pass rate |", "| --- | --- | --- | --- | --- | --- |"]
        for m, k in sorted(by_mode.items()):
            n = sum(k.values())
            lines.append(f"| {m} | {k['pass']} | {k['intermittent']} | {k['fail']} | {n} | {100.0 * k['pass'] / max(1, n):.1f}% |")
    if args.logs:
        armour = [armour_back(log) for log in args.logs if log.is_file()]
        if any(total for _, total in armour):
            lines += ["", "Waking scenes, its armour back on at the budget's end (reported, not the pass), by run: "
                      + ", ".join(f"{on} of {total}" for on, total in armour)]
        left = [leftover(log) for log in args.logs if log.is_file()]
        lines += ["", f"Replay players still acting after their own batch, by run: {', '.join(str(n) for n in left)} (should be 0)"]
    if staged:
        lines += ["", "Staged scenes (contract fixtures run as scenes):"]
        lines += [f"- {sid}: {status[sid]} ({scenes[sid]['fixture']})" for sid in staged]
    stored = json.loads(args.baseline.read_text(encoding="utf-8")) if args.baseline.is_file() else {}
    # The baseline: {"sha", "runs", "scenes": {id: status}}; an older file is the bare {id: status}.
    base = stored.get("scenes", stored) if isinstance(stored.get("scenes"), dict) else stored
    limit = 1 if len(runs) == 1 else 2
    gate = sorted(sid for sid in status if base.get(sid) == "pass" and fails[sid] >= limit)
    newly_passing = sorted(sid for sid in status if base.get(sid) in ("fail", "intermittent") and status[sid] == "pass")
    if "sha" in stored:
        lines += ["", f"Baseline: {stored['sha']} over {stored.get('runs', '?')} runs."]
    lines += ["", f"Flagged: passing on the baseline, now failing in {limit} or more of {len(runs)} run(s): {len(gate)}"
              + (" — " + ", ".join(gate) if gate else "")]
    expected = [f"{sid} ({KNOWN[sid]})" for sid in gate if sid in KNOWN]
    if expected:
        lines.append(f"Known intermittent among the flagged (expected; the rule is unchanged): {len(expected)} — " + "; ".join(expected))
    if args.flagged_out:
        args.flagged_out.write_text("".join(sid + "\n" for sid in gate), encoding="utf-8")
    blocking = gate
    if gate and (args.confirm_branch or args.confirm_main):
        branch = [outcomes(r) for r in args.confirm_branch if r.is_file()]
        main_runs = [outcomes(r) for r in args.confirm_main if r.is_file()]
        confirmed, cleared, unconfirmed = [], [], []
        for sid in gate:
            # Only the confirm files that hold the scene count for it: one file a round holding every flagged scene
            # (gate.sh's) and one file a scene's run both read the same. A scene with fewer than three runs a side
            # was not confirmed, and blocks: a missing run never reads as a failure on one side or a pass on the other.
            b_runs = [run[sid] for run in branch if sid in run]
            m_runs = [run[sid] for run in main_runs if sid in run]
            if len(b_runs) < CONFIRM_RUNS or len(m_runs) < CONFIRM_RUNS:
                unconfirmed.append(f"{sid} ({len(b_runs)} run(s) on the branch, {len(m_runs)} on main; {CONFIRM_RUNS} each wanted)")
                continue
            b_fail = sum(1 for ok in b_runs if not ok)
            m_pass = sum(1 for ok in m_runs if ok)
            (confirmed if b_fail >= 2 and m_pass >= 2 else cleared).append(f"{sid} (branch failed {b_fail} of {len(b_runs)}, main passed {m_pass} of {len(m_runs)})")
        lines.append(f"Confirmed (blocks): {len(confirmed)}" + (" — " + "; ".join(confirmed) if confirmed else ""))
        lines.append(f"Cleared: {len(cleared)}" + (" — " + "; ".join(cleared) if cleared else ""))
        if unconfirmed:
            lines.append(f"Not confirmed, too few confirm runs (blocks): {len(unconfirmed)} — " + "; ".join(unconfirmed))
        blocking = confirmed + unconfirmed
    lines.append(f"Gate: {'fails' if blocking else 'passes'}")
    lines.append(f"Newly passing against the baseline: {len(newly_passing)}" + (" — " + ", ".join(newly_passing) if newly_passing else ""))
    if not base:
        lines.append("No baseline yet: this run can be recorded as one with --write-baseline.")
    text = "\n".join(lines) + "\n"
    print(text)
    if args.out:
        args.out.write_text(text, encoding="utf-8")
    if args.write_baseline:
        record = {"sha": args.sha, "runs": len(runs), "scenes": status}
        args.baseline.write_text(json.dumps(record, indent=1, sort_keys=True) + "\n", encoding="utf-8")
    sys.exit(1 if blocking else 0)


if __name__ == "__main__":
    main()
