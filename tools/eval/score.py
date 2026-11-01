#!/usr/bin/env python3
"""Score a build's evaluation runs (F-26): summary.md and summary.json beside them.

Reads run/eval/<build>/<seed>/eval-result.json (and the server's console.txt, else latest.log, for RunMetrics) for
every seed folder, and reports per seed and per player the stage reached, the time to each stage, deaths an hour by
cause, stuck spells, RunMetrics' measures and tokens; then, across the seeds, medians and ranges, never a single number.
Whether the runs were earned or staged is said at the top. A run some of whose starters never came into the world is
named and left out. Python 3 standard library only. Usage:
  tools/eval/score.py run/eval/<build> [--no-metrics]
"""
import argparse
import json
import re
import statistics
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
STAGES = ["wooden_tool", "stone_tools", "iron_ingot", "iron_pickaxe", "diamond", "obsidian", "portal_lit", "nether_entered",
          "blaze_rods", "eyes", "stronghold", "end", "dragon"]
TICKS_PER_HOUR = 72000  # a game hour at twenty ticks a second
METRICS = re.compile(r"^\s+(\S+): (\d+) tasks done, (\d+) blocked, (\d+) cut short; failure repeats within 10 min: worst (\d+).*?;"
                     r" interruptions (\d+), work resumed (\d+) \((\d+)%\); work changes ([\d.]+) a minute; stuck (\d+), recovered (\d+) \((\d+)%\);"
                     r" walks in vain (\d+) \(([\d.]+) a minute\), at most (\d+) running")


def cause_of(message):
    """A death's cause as a kind: the mob that did it, or what killed it."""
    text = message.lower()
    found = re.search(r" by (?:an? )?([a-z ]+?)(?: using .*)?$", text)
    if found and "was" in text:
        return found.group(1).strip().replace(" ", "_")
    for words, kind in ((("drown",), "drowning"), (("fell", "hit the ground", "fall"), "fall"), (("lava",), "lava"),
                        (("burn", "flames", "fire"), "fire"), (("starve",), "starvation"), (("suffocat", "wall"), "suffocation"),
                        (("blew up", "blown up"), "explosion")):
        if any(w in text for w in words):
            return kind
    return "other"


def spread(values):
    """The median and the range of values; None where there are none."""
    values = [v for v in values if v is not None]
    if not values:
        return None
    return {"median": round(statistics.median(values), 2), "min": round(min(values), 2), "max": round(max(values), 2), "n": len(values)}


def metrics_for(logs):
    """RunMetrics' line per player for each log, by log path; nothing if it cannot be run."""
    if not logs:
        return {}
    command = [str(ROOT / "gradlew"), "--no-daemon", "-q", ":companion-testkit:runMetrics", "--args=" + " ".join(str(l) for l in logs)]
    try:
        out = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=1800).stdout
    except (OSError, subprocess.TimeoutExpired) as failure:
        print("RunMetrics not run: %s" % failure, file=sys.stderr)
        return {}
    found, current = {}, None
    for line in out.splitlines():
        if line.endswith(":") and not line.startswith(" "):
            current = line[:-1]
            found[current] = {}
            continue
        m = METRICS.match(line)
        if m and current is not None:
            found[current][m.group(1)] = {"done": int(m.group(2)), "blocked": int(m.group(3)), "cutShort": int(m.group(4)),
                                          "worstRepeat": int(m.group(5)), "resumedPct": int(m.group(8)), "changesPerMin": float(m.group(9)),
                                          "stuck": int(m.group(10)), "recoveredPct": int(m.group(12)), "walksInVain": int(m.group(13))}
    return found


SHORT = "not every starter came into the world"


def log_of(folder):
    """A run's whole log: the server's console, which run.py keeps from its start (latest.log begins again at midnight,
    and a run across midnight kept only its end); latest.log where there is no console."""
    for name in ("console.txt", "latest.log"):
        if (folder / name).is_file():
            return folder / name
    return None


def score(build_dir, use_metrics=True):
    seeds = sorted(p for p in build_dir.iterdir() if p.is_dir() and (p / "eval-result.json").is_file())
    missing = sorted(p.name for p in build_dir.iterdir() if p.is_dir() and p.name not in ("src", "cache") and not (p / "eval-result.json").is_file())
    metrics = metrics_for([log_of(p) for p in seeds if log_of(p)]) if use_metrics else {}
    per_seed, kinds, scenarios, short, other_jars = {}, set(), set(), {}, {}
    build_info = build_dir / "build.json"
    build_jar = json.loads(build_info.read_text(encoding="utf-8")).get("jar") if build_info.is_file() else None
    for folder in seeds:
        run_info = folder / "run.json"
        jar = json.loads(run_info.read_text(encoding="utf-8")).get("jar") if run_info.is_file() else None
        if jar and build_jar and jar != build_jar:
            other_jars[folder.name] = jar
        result = json.loads((folder / "eval-result.json").read_text(encoding="utf-8"))
        # A run some of whose starters never came into the world is not the scenario asked for: named, not scored.
        if SHORT in result.get("build", ""):
            note = result["build"][result["build"].index(SHORT) + len(SHORT):].split(";")[0].rstrip(")")
            short[folder.name] = note.lstrip(": ")
            continue
        kinds.add(result["kind"])
        scenarios.add(result["scenario"])
        hours = max(result["ticksRun"], 1) / TICKS_PER_HOUR
        players, best = {}, -1
        for name, p in result["players"].items():
            reached = [s for s in STAGES if s in p["stages"]]
            furthest = max((STAGES.index(s) for s in reached), default=-1)
            best = max(best, furthest)
            causes = {}
            for d in p["deaths"]:
                causes[cause_of(d["cause"])] = causes.get(cause_of(d["cause"]), 0) + 1
            deaths = len(p["deaths"]) + p.get("deathsNotListed", 0)
            rescues = p.get("rescues", [])
            players[name] = {"furthest": STAGES[furthest] if furthest >= 0 else "none", "furthestIndex": furthest,
                             "minutesTo": {s: round(p["stages"][s] / 1200, 1) for s in reached},
                             "deaths": deaths, "deathsPerHour": round(deaths / hours, 2), "causes": causes,
                             "stuckSpells": len(p["stuck"]) + p.get("stuckNotListed", 0), "stuckMinutes": stuck_minutes(len(p["stuck"]) + p.get("stuckNotListed", 0)),
                             "rescues": len(rescues), "rescuesUnstuck": sum(1 for r in rescues if r["line"].endswith("-> unstuck")),
                             "tokens": p.get("tokens", 0),
                             "metrics": metrics.get(str(log_of(folder)), {}).get(name)}
        per_seed[folder.name] = {"complete": result["complete"], "ticksRun": result["ticksRun"], "hours": round(hours, 2),
                                 "teamFurthest": STAGES[best] if best >= 0 else "none", "teamFurthestIndex": best,
                                 "teamDeathsPerHour": round(sum(p["deaths"] for p in players.values()) / hours, 2), "players": players}
    team = {
        "teamFurthestIndex": spread([s["teamFurthestIndex"] for s in per_seed.values()]),
        "teamDeathsPerHour": spread([s["teamDeathsPerHour"] for s in per_seed.values()]),
        "stuckSpells": spread([sum(p["stuckSpells"] for p in s["players"].values()) for s in per_seed.values()]),
        "stuckMinutes": spread([round(sum(p["stuckMinutes"] for p in s["players"].values()), 1) for s in per_seed.values()]),
        "rescues": spread([sum(p["rescues"] for p in s["players"].values()) for s in per_seed.values()]),
        "rescuesUnstuck": spread([sum(p["rescuesUnstuck"] for p in s["players"].values()) for s in per_seed.values()]),
        "tokens": spread([sum(p["tokens"] for p in s["players"].values()) for s in per_seed.values()]),
    }
    for stage in STAGES:
        firsts = [min((p["minutesTo"][stage] for p in s["players"].values() if stage in p["minutesTo"]), default=None) for s in per_seed.values()]
        team["minutesTo_" + stage] = spread(firsts)
        team["reached_" + stage] = sum(1 for f in firsts if f is not None)
    causes = {}
    for s in per_seed.values():
        for p in s["players"].values():
            for cause, n in p["causes"].items():
                causes[cause] = causes.get(cause, 0) + n
    return {"build": build_dir.name, "jar": build_jar, "otherJars": other_jars, "seeds": sorted(per_seed), "missing": missing, "short": short,
            "kinds": sorted(kinds), "scenarios": sorted(scenarios),
            "team": team, "deathCauses": dict(sorted(causes.items(), key=lambda kv: -kv[1])), "perSeed": per_seed}


def stuck_minutes(spells):
    """Game minutes the watchdog counted as stuck: three for each spell, the minutes of no progress after which it gives a
    spot up. Time spent on a move elsewhere that got nowhere, short of another spell, is not counted."""
    return 3 * spells


def fmt(s, unit=""):
    if s is None:
        return "-"
    return "%s%s [%s to %s] (n=%d)" % (s["median"], unit, s["min"], s["max"], s["n"])


def markdown(summary):
    lines = ["# Evaluation summary: %s" % summary["build"], ""]
    kind = " and ".join(summary["kinds"]) or "none"
    lines.append("Runs: %d seeds (%s), %s. Scenario: %s." % (len(summary["seeds"]), ", ".join(summary["seeds"]), kind.upper(),
                                                           "; ".join(summary["scenarios"]) or "none"))
    if "staged" in summary["kinds"]:
        lines.append("These runs are staged (something was kept or edits were off): they are not earned progress.")
    if summary["missing"]:
        lines.append("No result from: %s." % ", ".join(summary["missing"]))
    for seed, came in summary.get("short", {}).items():
        lines.append("Not scored: seed %s, where not every starter came into the world (only %s did)." % (seed, came))
    for seed, jar in summary.get("otherJars", {}).items():
        lines.append("Seed %s ran on %s, the others on %s." % (seed, jar, summary["jar"]))
    if len(summary["seeds"]) < 10:
        lines.append("Fewer than ten seeds: too few to compare with another build.")
    lines += ["", "Medians, with the range across seeds in brackets.", "", "| Measure | Median [range] (seeds) |", "| --- | --- |"]
    t = summary["team"]
    lines.append("| The team's furthest stage (index in the list below) | %s |" % fmt(t["teamFurthestIndex"]))
    lines.append("| Deaths a game hour, the team | %s |" % fmt(t["teamDeathsPerHour"]))
    lines.append("| Stuck spells, the team | %s |" % fmt(t["stuckSpells"]))
    lines.append("| Stuck game minutes, the team | %s |" % fmt(t["stuckMinutes"]))
    lines.append("| Rescue plans, the team (of them, out) | %s (%s) |" % (fmt(t["rescues"]), fmt(t["rescuesUnstuck"])))
    lines.append("| Model tokens, the team | %s |" % fmt(t["tokens"]))
    for stage in STAGES:
        if t["reached_" + stage]:
            lines.append("| First to %s, game minutes (seeds reaching it) | %s |" % (stage, fmt(t["minutesTo_" + stage])))
    lines += ["", "Stages: " + ", ".join("%d %s" % (i, s) for i, s in enumerate(STAGES)) + ".", "",
              "Deaths by cause, all seeds: " + (", ".join("%s %d" % kv for kv in summary["deathCauses"].items()) or "none") + ".", "",
              "| Seed | Ticks run | Team furthest | Deaths/h | Per player: furthest, deaths, stuck spells, RunMetrics (done/blocked, worst repeat, recovered %, walks in vain) |",
              "| --- | --- | --- | --- | --- |"]
    for seed, s in summary["perSeed"].items():
        cells = []
        for name, p in s["players"].items():
            m = p["metrics"]
            extra = "" if not m else ", %d/%d, %d, %d%%, %d" % (m["done"], m["blocked"], m["worstRepeat"], m["recoveredPct"], m["walksInVain"])
            cells.append("%s: %s, %d, %d%s" % (name, p["furthest"], p["deaths"], p["stuckSpells"], extra))
        lines.append("| %s | %d%s | %s | %s | %s |" % (seed, s["ticksRun"], "" if s["complete"] else " (cut short)", s["teamFurthest"],
                                                     s["teamDeathsPerHour"], "; ".join(cells)))
    lines.append("")
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("build_dir")
    parser.add_argument("--no-metrics", action="store_true", help="skip RunMetrics (it builds the testkit through Gradle)")
    args = parser.parse_args()
    folder = Path(args.build_dir).resolve()
    summary = score(folder, not args.no_metrics)
    (folder / "summary.json").write_text(json.dumps(summary, indent=1) + "\n", encoding="utf-8")
    (folder / "summary.md").write_text(markdown(summary), encoding="utf-8")
    print(markdown(summary))


if __name__ == "__main__":
    main()
