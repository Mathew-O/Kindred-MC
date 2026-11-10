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


# F-29b wrote "rescue asked: spell N, T ticks ..."; since F-29c a round's number follows the spell's, and only a spell's
# first round counts toward the time from its start to its ask.
ASKED = re.compile(r"rescue asked: spell \d+(?: round (\d+))?, (\d+) ticks after its last progress")
SPELL = re.compile(r"rescue spell \d+: (out|not out) (?:after (\d+) rounds?|by its recipe)")
RECIPE = re.compile(r"rescue recipe: spell \d+, ")
KEPT = re.compile(r"rescue recipe kept: ")

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


def by_end(values):
    """Times to a stage over every seed, a seed that never reached it (None) counted as later than any that did: so the
    median and the range are over all the seeds, and either end, or the median, can be "not reached"."""
    if not values:
        return None
    ordered = sorted(values, key=lambda v: (v is None, v or 0))
    n, half = len(ordered), len(ordered) // 2
    if n % 2:
        median = ordered[half]
    else:
        low, high = ordered[half - 1], ordered[half]
        median = None if low is None or high is None else round((low + high) / 2, 2)
    return {"median": median, "min": ordered[0], "max": ordered[-1], "n": n, "reached": sum(1 for v in values if v is not None)}


def later(x, y):
    """Whether time x is later than time y, "not reached" (None) being later than any time."""
    if x is None:
        return y is not None
    return y is not None and x > y


def fmt_by_end(s):
    if s is None:
        return "-"
    shown = lambda v: "not reached" if v is None else v
    return "%s [%s to %s] (reached on %d of %d)" % (shown(s["median"]), shown(s["min"]), shown(s["max"]), s["reached"], s["n"])


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


# The social lanes' log lines (E-51, E-53): a line between AI players said to people, a line said first to a person, and
# a meeting noted with nobody to hear.
TALK_LINE = " hearing): "
UNASKED_LINE = " unasked ("
SILENT_MEETING = "with nobody to hear: noted, nothing said"


def chatter_of(log):
    """How many lines between AI players were said, how many said first to people, and how many meetings were noted in
    silence, in a run's whole console."""
    talk = unasked = silent = 0
    if log is None:
        return {"talkLines": 0, "unaskedLines": 0, "silentMeetings": 0}
    with open(log, encoding="utf-8", errors="replace") as f:
        for line in f:
            if TALK_LINE in line:
                talk += 1
            elif UNASKED_LINE in line:
                unasked += 1
            elif SILENT_MEETING in line:
                silent += 1
    return {"talkLines": talk, "unaskedLines": unasked, "silentMeetings": silent}


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
            lines = p.get("rescues", [])
            # Since F-29b an ask is a line of its own ("rescue asked: spell N, T ticks after its last progress"); a plan's
            # line is every other.
            asked = [ASKED.match(r["line"]) for r in lines if ASKED.match(r["line"])]
            asks = [int(m.group(2)) for m in asked if (m.group(1) or "1") == "1"]
            spells = [SPELL.match(r["line"]) for r in lines if SPELL.match(r["line"])]
            recipes = [r for r in lines if RECIPE.match(r["line"])]
            rescues = [r for r in lines if not r["line"].startswith("rescue asked") and not r["line"].startswith("rescue spell")
                       and not r["line"].startswith("rescue recipe")]
            players[name] = {"furthest": STAGES[furthest] if furthest >= 0 else "none", "furthestIndex": furthest,
                             "minutesTo": {s: round(p["stages"][s] / 1200, 1) for s in reached},
                             "deaths": deaths, "deathsPerHour": round(deaths / hours, 2), "causes": causes,
                             "stuckSpells": len(p["stuck"]) + p.get("stuckNotListed", 0), "stuckMinutes": stuck_minutes(len(p["stuck"]) + p.get("stuckNotListed", 0)),
                             "rescues": len(rescues), "rescuesUnstuck": sum(1 for r in rescues if r["line"].endswith("-> unstuck")),
                             "rescueAsks": len(asked), "askSeconds": [round(t / 20, 1) for t in asks],
                             "rescueSpells": len(spells), "rescueSpellsOut": sum(1 for m in spells if m.group(1) == "out"),
                             "rescueRounds": sum(int(m.group(2) or 0) for m in spells),
                             "recipesTried": len(recipes),
                             "spellsOutByRecipe": sum(1 for r in lines if r["line"].endswith(": out by its recipe")),
                             "tokens": p.get("tokens", 0),
                             "metrics": metrics.get(str(log_of(folder)), {}).get(name)}
        chatter = chatter_of(log_of(folder))
        minutes = max(result["ticksRun"], 1) / 1200
        chatter["linesPerMinute"] = round((chatter["talkLines"] + chatter["unaskedLines"]) / minutes, 3)
        # A faction world's audit (M-06i): what the World Record held and every locked rule it shows broken.
        factions = result.get("factions")
        per_seed[folder.name] = {"complete": result["complete"], "ticksRun": result["ticksRun"], "hours": round(hours, 2), "chatter": chatter,
                                 "factions": factions, "server": result.get("server"),
                                 "teamFurthest": STAGES[best] if best >= 0 else "none", "teamFurthestIndex": best,
                                 "teamDeathsPerHour": round(sum(p["deaths"] for p in players.values()) / hours, 2), "players": players}
    team = {
        "teamFurthestIndex": spread([s["teamFurthestIndex"] for s in per_seed.values()]),
        "teamDeathsPerHour": spread([s["teamDeathsPerHour"] for s in per_seed.values()]),
        "stuckSpells": spread([sum(p["stuckSpells"] for p in s["players"].values()) for s in per_seed.values()]),
        "stuckMinutes": spread([round(sum(p["stuckMinutes"] for p in s["players"].values()), 1) for s in per_seed.values()]),
        "rescues": spread([sum(p["rescues"] for p in s["players"].values()) for s in per_seed.values()]),
        "rescuesUnstuck": spread([sum(p["rescuesUnstuck"] for p in s["players"].values()) for s in per_seed.values()]),
        "rescueAsks": spread([sum(p.get("rescueAsks", 0) for p in s["players"].values()) for s in per_seed.values()]),
        "rescueSpells": spread([sum(p.get("rescueSpells", 0) for p in s["players"].values()) for s in per_seed.values()]),
        "rescueSpellsOut": spread([sum(p.get("rescueSpellsOut", 0) for p in s["players"].values()) for s in per_seed.values()]),
        "rescueRounds": spread([sum(p.get("rescueRounds", 0) for p in s["players"].values()) for s in per_seed.values()]),
        "recipesTried": spread([sum(p.get("recipesTried", 0) for p in s["players"].values()) for s in per_seed.values()]),
        "spellsOutByRecipe": spread([sum(p.get("spellsOutByRecipe", 0) for p in s["players"].values()) for s in per_seed.values()]),
        # Each seed's median game seconds from a spell's last progress to its ask, and every ask's, pooled.
        "askSeconds": spread([statistics.median(a) if a else None
                              for a in ([x for p in s["players"].values() for x in p.get("askSeconds", [])] for s in per_seed.values())]),
        "askSecondsPooled": spread([x for s in per_seed.values() for p in s["players"].values() for x in p.get("askSeconds", [])]),
        "tokens": spread([sum(p["tokens"] for p in s["players"].values()) for s in per_seed.values()]),
        "linesPerMinute": spread([s["chatter"]["linesPerMinute"] for s in per_seed.values()]),
        "talkLines": spread([s["chatter"]["talkLines"] for s in per_seed.values()]),
        "unaskedLines": spread([s["chatter"]["unaskedLines"] for s in per_seed.values()]),
        "silentMeetings": spread([s["chatter"]["silentMeetings"] for s in per_seed.values()]),
    }
    for stage in STAGES:
        firsts = [min((p["minutesTo"][stage] for p in s["players"].values() if stage in p["minutesTo"]), default=None) for s in per_seed.values()]
        team["minutesTo_" + stage] = by_end(firsts)
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
    if "rescueAsks" in t:
        lines.append("| Rescues asked (rounds), the team | %s |" % fmt(t["rescueAsks"]))
        if "rescueSpells" in t:
            lines.append("| Rescue spells ended, the team (of them, out; rounds in them) | %s (%s; %s) |"
                         % (fmt(t["rescueSpells"]), fmt(t["rescueSpellsOut"]), fmt(t["rescueRounds"])))
        if "recipesTried" in t:
            lines.append("| Rescue recipes tried first, the team (spells out by a recipe) | %s (%s) |"
                         % (fmt(t["recipesTried"]), fmt(t["spellsOutByRecipe"])))
        lines.append("| Game seconds from a spell's last progress to its ask: each seed's median / every ask | %s / %s |"
                     % (fmt(t["askSeconds"]), fmt(t["askSecondsPooled"])))
    lines.append("| Model tokens, the team | %s |" % fmt(t["tokens"]))
    if "linesPerMinute" in t:
        lines.append("| Lines spoken a game minute (between AI players, and said first to people) | %s |" % fmt(t["linesPerMinute"]))
        lines.append("| Lines between AI players said / said first to people / meetings noted with nobody to hear | %s / %s / %s |"
                     % (fmt(t["talkLines"]), fmt(t["unaskedLines"]), fmt(t["silentMeetings"])))
    for stage in STAGES:
        if t["reached_" + stage]:
            lines.append("| First to %s, game minutes (a seed not reaching it counted as later than all) | %s |"
                         % (stage, fmt_by_end(t["minutesTo_" + stage])))
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
    loads = {seed: s["server"] for seed, s in summary["perSeed"].items() if s.get("server")}
    if loads:
        lines += ["", "## The server's load", "",
                  "Each seed's mean tick (over the run, from the server's own last-hundred mean once a game second), its worst tick "
                  "and when it fell (ticks into the run), the ticks that took over 50 and over 200 ms, and its heap at most. A run "
                  "from before these were kept shows a dash for them.", "",
                  "| Seed | Mean tick, ms | Worst tick, ms | Worst at tick | Over 50 ms | Over 200 ms | Heap at most, MB |",
                  "| --- | --- | --- | --- | --- | --- | --- |"]
        for seed, l in loads.items():
            lines.append("| %s | %.2f | %.2f | %s | %s | %s | %d |" % (seed, l["tickMsMean"], l["tickMsWorst"], l.get("tickMsWorstAt", "-"),
                                                                    l.get("ticksOver50ms", "-"), l.get("ticksOver200ms", "-"), l["heapMbMax"]))
    audited = {seed: s["factions"] for seed, s in summary["perSeed"].items() if s.get("factions")}
    if audited:
        lines += ["", "## The faction world's audit (M-06i)", "",
                  "Every claim and capture decided again from the World Record by the rules, the safe zone, coins and the auction house's hand-overs.", "",
                  "| Seed | Audited | Factions | Claims | Captures | Sales | Kills | Rules broken |", "| --- | --- | --- | --- | --- | --- | --- | --- |"]
        broken = 0
        for seed, a in audited.items():
            c = a.get("counts", {})
            broken += len(a.get("violations", []))
            lines.append("| %s | %s | %s | %s | %s | %s | %s | %s |" % (seed, "yes" if a.get("audited") else "no: " + a.get("note", ""),
                         c.get("factions", "-"), c.get("claims", "-"), c.get("captures", "-"), c.get("sales", "-"), c.get("kills", "-"),
                         len(a.get("violations", []))))
        lines.append("")
        lines.append("No rule broken on any seed." if broken == 0 and all(a.get("audited") for a in audited.values())
                     else "RULES BROKEN or not audited: see each seed's eval-result.json.")
        for seed, a in audited.items():
            for v in a.get("violations", [])[:20]:
                lines.append("- seed %s: %s" % (seed, v))
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
