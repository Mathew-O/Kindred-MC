#!/usr/bin/env python3
"""Two builds' evaluation summaries side by side, on the seeds both ran (F-26).

A difference is marked worth noting only when the two ranges across seeds do not overlap; anything else is reported as
it is and called no clear difference. Python 3 standard library only. Usage:
  tools/eval/compare.py run/eval/<build A>/summary.json run/eval/<build B>/summary.json [--out compare.md]
"""
import argparse
import json
import statistics
from pathlib import Path

MEASURES = [("teamFurthestIndex", "The team's furthest stage (index)", "more"), ("teamDeathsPerHour", "Deaths a game hour, the team", "fewer"),
            ("stuckSpells", "Stuck spells, the team", "fewer"), ("stuckMinutes", "Stuck game minutes, the team", "fewer"),
            ("rescues", "Rescue plans, the team", "-"), ("tokens", "Model tokens, the team", "fewer")]


def on_seeds(summary, seeds, key):
    values = []
    for seed in seeds:
        s = summary["perSeed"][seed]
        if key in ("stuckSpells", "stuckMinutes", "rescues"):
            values.append(round(sum(p.get(key, 0) for p in s["players"].values()), 1))
        elif key == "tokens":
            values.append(sum(p["tokens"] for p in s["players"].values()))
        else:
            values.append(s[key])
    return values


def first_to(summary, seeds, stage):
    return [min((p["minutesTo"][stage] for p in summary["perSeed"][seed]["players"].values() if stage in p["minutesTo"]), default=None)
            for seed in seeds]


def spread(values):
    values = [v for v in values if v is not None]
    if not values:
        return None
    return {"median": round(statistics.median(values), 2), "min": min(values), "max": max(values), "n": len(values)}


def verdict(a, b):
    if a is None or b is None:
        return "not comparable"
    if a["max"] < b["min"] or b["max"] < a["min"]:
        return "worth noting: the ranges do not overlap"
    return "no clear difference: the ranges overlap"


def fmt(s):
    return "-" if s is None else "%s [%s to %s] (n=%d)" % (s["median"], s["min"], s["max"], s["n"])


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("a")
    parser.add_argument("b")
    parser.add_argument("--out")
    args = parser.parse_args()
    a, b = (json.loads(Path(p).read_text(encoding="utf-8")) for p in (args.a, args.b))
    seeds = sorted(set(a["seeds"]) & set(b["seeds"]))
    lines = ["# %s against %s" % (b["build"], a["build"]), "",
             "On the %d seeds both ran (%s). %s: %s; %s: %s." % (len(seeds), ", ".join(seeds), a["build"], " and ".join(a["kinds"]),
                                                               b["build"], " and ".join(b["kinds"]))]
    if len(seeds) < 10:
        lines.append("Fewer than ten seeds in common: nothing here is worth noting, whatever the ranges.")
    if a["scenarios"] != b["scenarios"]:
        lines.append("The scenarios differ: %s against %s." % (a["scenarios"], b["scenarios"]))
    lines += ["", "| Measure | %s | %s | Verdict |" % (a["build"], b["build"]), "| --- | --- | --- | --- |"]
    for key, label, _better in MEASURES:
        sa, sb = spread(on_seeds(a, seeds, key)), spread(on_seeds(b, seeds, key))
        lines.append("| %s | %s | %s | %s |" % (label, fmt(sa), fmt(sb), verdict(sa, sb) if len(seeds) >= 10 else "too few seeds"))
    for stage in ("stone_tools", "iron_ingot", "iron_pickaxe", "obsidian", "portal_lit", "nether_entered"):
        fa, fb = first_to(a, seeds, stage), first_to(b, seeds, stage)
        if not any(v is not None for v in fa + fb):
            continue
        sa, sb = spread(fa), spread(fb)
        reached = "reached on %d and %d seeds" % (sum(v is not None for v in fa), sum(v is not None for v in fb))
        lines.append("| First to %s, game minutes (%s) | %s | %s | %s |" % (stage, reached, fmt(sa), fmt(sb),
                                                                         verdict(sa, sb) if len(seeds) >= 10 else "too few seeds"))
    lines.append("")
    text = "\n".join(lines)
    if args.out:
        Path(args.out).write_text(text, encoding="utf-8")
    print(text)


if __name__ == "__main__":
    main()
