#!/usr/bin/env python3
"""Two builds' evaluation summaries side by side, on the seeds both ran (F-26).

A difference is marked worth noting only when the two ranges across seeds do not overlap; anything else is reported as
it is and called no clear difference. A time to a stage is over every seed, one that never reached the stage counted
as later than any that did. Python 3 standard library only. Usage:
  tools/eval/compare.py run/eval/<build A>/summary.json run/eval/<build B>/summary.json [--out compare.md]
"""
import argparse
import json
import math
import random
import re
import statistics
from pathlib import Path

from score import by_end, fmt_by_end, later

MEASURES = [("teamFurthestIndex", "The team's furthest stage (index)", "more"), ("teamDeathsPerHour", "Deaths a game hour, the team", "fewer"),
            ("stuckSpells", "Stuck spells, the team", "fewer"), ("stuckMinutes", "Stuck game minutes, the team", "fewer"),
            ("rescues", "Rescue plans, the team", "-"), ("rescuesUnstuck", "Rescue plans that got it out, the team", "more"),
            ("rescueAsks", "Rescues asked (rounds), the team", "-"), ("rescueSpells", "Rescue spells ended, the team", "-"),
            ("rescueSpellsOut", "Rescue spells that got it out, the team", "more"), ("rescueRounds", "Rescue rounds in ended spells, the team", "-"),
            ("recipesTried", "Rescue recipes tried first, the team", "-"), ("spellsOutByRecipe", "Rescue spells out by a recipe, the team", "more"), ("askSeconds", "Game seconds from a spell's last progress to its ask, the seed's median", "fewer"),
            ("tokens", "Model tokens, the team", "fewer")]


def on_seeds(summary, seeds, key):
    values = []
    for seed in seeds:
        s = summary["perSeed"][seed]
        if key in ("stuckSpells", "stuckMinutes", "rescues", "rescuesUnstuck", "rescueAsks", "rescueSpells", "rescueSpellsOut", "rescueRounds", "recipesTried", "spellsOutByRecipe"):
            values.append(round(sum(p.get(key, 0) for p in s["players"].values()), 1))
        elif key == "askSeconds":
            asked = [x for p in s["players"].values() for x in p.get("askSeconds", [])]
            values.append(statistics.median(asked) if asked else None)
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


def verdict_by_end(a, b):
    """As verdict, for times to a stage over every seed, "not reached" being later than any time."""
    if a is None or b is None:
        return "not comparable"
    if later(b["min"], a["max"]) or later(a["min"], b["max"]):
        return "worth noting: the ranges do not overlap"
    return "no clear difference: the ranges overlap"


# Paired by seed (F-26c): each measure's per-seed difference B - A, which way is better, and how a seed that never
# reached a stage counts (the run's length in game minutes, so "not reached" is the latest time there can be).
PAIRED = [("portal", "Portal lit (seeds reaching it)", "more"), ("nether", "Nether entered (seeds reaching it)", "more"),
          ("stuckMinutes", "Stuck game minutes, the team", "fewer"), ("teamDeathsPerHour", "Deaths a game hour, the team", "fewer"),
          ("ironPickaxe", "First to iron_pickaxe, game minutes (not reached = the run's length)", "fewer"),
          ("portalMinutes", "First to portal_lit, game minutes (not reached = the run's length)", "fewer")]
STAGE_OF = {"portal": 6, "nether": 7}
BOOTSTRAP = 10000


def per_seed_value(summary, seed, key):
    """One seed's value of a paired measure, or None where the summary does not hold it (an old summary.md)."""
    s = summary["perSeed"][seed]
    if key in STAGE_OF:
        return 1 if s["teamFurthestIndex"] >= STAGE_OF[key] else 0
    if key == "stuckMinutes":
        return round(sum(p.get("stuckMinutes", 0) for p in s["players"].values()), 1)
    if key == "teamDeathsPerHour":
        return s["teamDeathsPerHour"]
    stage = {"ironPickaxe": "iron_pickaxe", "portalMinutes": "portal_lit"}[key]
    if not all("minutesTo" in p for p in s["players"].values()):
        return None
    times = [p["minutesTo"][stage] for p in s["players"].values() if stage in p["minutesTo"]]
    return min(times) if times else round(s["ticksRun"] / 1200, 1)


def sign_test(helped, hurt):
    """Two-sided exact sign test on the seeds that changed: the chance of a split this uneven with no effect."""
    n, k = helped + hurt, min(helped, hurt)
    if n == 0:
        return 1.0
    return min(1.0, 2 * sum(math.comb(n, i) for i in range(k + 1)) / 2 ** n)


def bootstrap(diffs, draws=BOOTSTRAP, seed=0):
    """A bootstrap 95% interval on the median of the per-seed differences, the seeds drawn again with replacement."""
    rng = random.Random(seed)
    medians = sorted(statistics.median(rng.choices(diffs, k=len(diffs))) for _ in range(draws))
    return medians[int(0.025 * draws)], medians[int(0.975 * draws) - 1]


def paired(a, b, seeds, key, better):
    """The per-seed differences B - A and what they say: seeds helped, hurt and unchanged, the median difference with
    its bootstrap interval, and the sign test's p."""
    pairs = [(per_seed_value(a, s, key), per_seed_value(b, s, key)) for s in seeds]
    diffs = [round(vb - va, 2) for va, vb in pairs if va is not None and vb is not None]
    if not diffs:
        return None
    sign = 1 if better == "more" else -1
    helped = sum(1 for d in diffs if d * sign > 0)
    hurt = sum(1 for d in diffs if d * sign < 0)
    lo, hi = bootstrap(diffs)
    return {"n": len(diffs), "helped": helped, "hurt": hurt, "same": len(diffs) - helped - hurt, "diffs": diffs,
            "median": round(statistics.median(diffs), 2), "lo": round(lo, 2), "hi": round(hi, 2), "p": round(sign_test(helped, hurt), 3)}


def fmt_paired(r):
    if r is None:
        return "not in both summaries"
    return "helped on %d of %d seeds, hurt on %d; median delta %s [%s, %s]; sign test p=%s" % (
        r["helped"], r["n"], r["hurt"], r["median"], r["lo"], r["hi"], r["p"])


ROW = re.compile(r"^\| (\d+) \| (\d+)(?: \(cut short\))? \| (\w+) \| ([\d.]+) \| (.*) \|$")


def from_markdown(text, name):
    """An old summary.md's per-seed table as a summary: each seed's furthest stage, deaths an hour and stuck spells
    (three game minutes each), no times to a stage."""
    stages = ["wooden_tool", "stone_tools", "iron_ingot", "iron_pickaxe", "diamond", "obsidian", "portal_lit", "nether_entered",
              "blaze_rods", "eyes", "stronghold", "end", "dragon"]
    per_seed = {}
    for line in text.splitlines():
        m = ROW.match(line)
        if not m:
            continue
        players = {}
        for cell in m.group(5).split("; "):
            pname, rest = cell.split(": ", 1)
            parts = rest.split(", ")
            players[pname] = {"stuckSpells": int(parts[2]), "stuckMinutes": 3 * int(parts[2])}
        per_seed[m.group(1)] = {"ticksRun": int(m.group(2)), "teamFurthest": m.group(3),
                                "teamFurthestIndex": stages.index(m.group(3)) if m.group(3) in stages else -1,
                                "teamDeathsPerHour": float(m.group(4)), "players": players}
    scenario = re.search(r"Scenario: (.*?)\.$", text, re.M)
    return {"build": name, "seeds": sorted(per_seed), "kinds": ["earned"] if "EARNED" in text else ["staged"],
            "scenarios": [scenario.group(1)] if scenario else [], "perSeed": per_seed, "fromMarkdown": True}


def load(path):
    """A summary.json, or failing that its build's summary.md (the older iterations kept only that)."""
    path = Path(path)
    if path.suffix == ".json" and path.is_file():
        return json.loads(path.read_text(encoding="utf-8"))
    md = path if path.suffix == ".md" else path.with_name("summary.md")
    return from_markdown(md.read_text(encoding="utf-8"), md.parent.name)


def paired_lines(a, b, seeds):
    lines = ["## Paired by seed (F-26c)", "",
             "Each seed's difference %s minus %s; helped means better for %s. The interval is a bootstrap 95%% interval on the "
             "median difference; the sign test counts the seeds that changed." % (b["build"], a["build"], b["build"]), "",
             "| Measure | Paired result | Per-seed deltas |", "| --- | --- | --- |"]
    for key, label, better in PAIRED:
        r = paired(a, b, seeds, key, better)
        lines.append("| %s | %s | %s |" % (label, fmt_paired(r), "-" if r is None else " ".join(str(d) for d in r["diffs"])))
    return lines + [""]


def fmt(s):
    return "-" if s is None else "%s [%s to %s] (n=%d)" % (s["median"], s["min"], s["max"], s["n"])


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("a")
    parser.add_argument("b")
    parser.add_argument("--out")
    args = parser.parse_args()
    a, b = load(args.a), load(args.b)
    seeds = sorted(set(a["seeds"]) & set(b["seeds"]))
    lines = ["# %s against %s" % (b["build"], a["build"]), "",
             "On the %d seeds both ran (%s). %s: %s; %s: %s." % (len(seeds), ", ".join(seeds), a["build"], " and ".join(a["kinds"]),
                                                               b["build"], " and ".join(b["kinds"]))]
    if len(seeds) < 10:
        lines.append("Fewer than ten seeds in common: nothing here is worth noting, whatever the ranges.")
    if a["scenarios"] != b["scenarios"]:
        lines.append("The scenarios differ: %s against %s." % (a["scenarios"], b["scenarios"]))
    lines += [""] + paired_lines(a, b, seeds)
    if a.get("fromMarkdown") or b.get("fromMarkdown"):
        lines += ["Read from summary.md, which holds no times to a stage: those rows and the ranges below are left out.", ""]
        text = "\n".join(lines)
        if args.out:
            Path(args.out).write_text(text, encoding="utf-8")
        print(text)
        return
    lines += ["## Ranges, unpaired", "", "| Measure | %s | %s | Verdict |" % (a["build"], b["build"]), "| --- | --- | --- | --- |"]
    for key, label, _better in MEASURES:
        sa, sb = spread(on_seeds(a, seeds, key)), spread(on_seeds(b, seeds, key))
        lines.append("| %s | %s | %s | %s |" % (label, fmt(sa), fmt(sb), verdict(sa, sb) if len(seeds) >= 10 else "too few seeds"))
    for stage in ("stone_tools", "iron_ingot", "iron_pickaxe", "diamond", "obsidian", "portal_lit", "nether_entered"):
        fa, fb = first_to(a, seeds, stage), first_to(b, seeds, stage)
        if not any(v is not None for v in fa + fb):
            continue
        sa, sb = by_end(fa), by_end(fb)
        lines.append("| First to %s, game minutes, every seed (one not reaching it later than all) | %s | %s | %s |"
                     % (stage, fmt_by_end(sa), fmt_by_end(sb), verdict_by_end(sa, sb) if len(seeds) >= 10 else "too few seeds"))
    lines.append("")
    text = "\n".join(lines)
    if args.out:
        Path(args.out).write_text(text, encoding="utf-8")
    print(text)


if __name__ == "__main__":
    main()
