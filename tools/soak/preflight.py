#!/usr/bin/env python3
"""The month run's pre-flight report (M-06k7): what a run's console and World Record say about whether a month will hold.

Reads the server's console log (its `Kindred cost:` lines, once a game day and at a stop, and its WARN and ERROR
lines) and, if given, a copy of the World Record (`companions.sqlite`, taken with the server stopped). Prints, per game
day and for the run:
- the Record: events written, database size on disk and its growth a game day; the bounds' prunes seen (the action
  fold and bound, F-31b and F-31b1; the refusals' bound, F-34c2a) and the ledger checks, agreeing or not;
- the Wire's refusals since start, by reason and channel (F-34e);
- refused attempts on factions' land, written, the same minute's and over a faction's cap (F-34c2), if logged;
- the server's ticks: how many, the mean, the 99th, how many over 50 ms, the longest (M-06k7's cost-line window);
- the heap: in use at each line and its high water since the line before;
- native connections closed from outside (`native.disconnects`, #331) and the WARN lines, by kind;
- every ERROR line, with its time and the player it names when it names one.

Every number is measured from the files. A month's figure is shown only as a projection, so labelled, from the last
measured game day's growth: a real month is about 2,160 game days at 20 real minutes each.

The verdict lists each threshold crossed: STOP (call someone before playing on) or WATCH (look again tomorrow). The
thresholds are those of the operator guide's "Before the month run".

Python 3 standard library only. Usage:
  tools/soak/preflight.py --console logs/latest.log [--db check/companions.sqlite] [--heap-max-mb 12288] [--json out.json]
"""
import argparse
import collections
import json
import re
import sqlite3
import sys
from pathlib import Path

GAME_DAYS_A_MONTH = 2160  # 30 real days of 24 hours at 20 real minutes a game day.
COST = re.compile(r"Kindred cost: day (\d+): (.*)$")
STAMP = re.compile(r"^\[(\d\d:\d\d:\d\d)\] \[([^/\]]+)/(WARN|ERROR)\]")
PRUNES = {
    "fold": re.compile(r"Kindred action fold: (\d+) settled actions, (\d+) events"),
    "action_bound": re.compile(r"Kindred action bound: (\d+) settled actions older than 30 game days pruned, (\d+) day tallies"),
    "refusal_bound": re.compile(r"Kindred refusals' bound: (\d+) refused attempts older than 30 game days pruned, (\d+) day tallies"),
}
# What a STOP or WATCH means here is the operator guide's ("Before the month run").
P99_WATCH_MS = 50
OVER50_WATCH_SHARE = 0.05
HEAP_STOP_SHARE = 0.90
# KnowledgeWeb.MAX_RELATIONS: a web's bound, which never drops a told fact still open (F-32e). Past it, the bound no longer
# bounds that web.
WEB_BOUND = 20000


def number(pattern, text, cast=int, default=None):
    m = re.search(pattern, text)
    return cast(m.group(1)) if m else default


def cost_line(day, body):
    """One cost line's figures, each None where the line does not give it."""
    out = {"day": day}
    out["recordThrough"] = number(r"the Record through event (\d+)", body)
    out["writtenSinceStart"] = number(r", (\d+) written since start", body)
    out["databaseBytes"] = number(r"the database (-?\d+) bytes on disk", body)
    out["heapInUseMb"] = number(r"heap in use (\d+) MB", body)
    out["heapHighWaterMb"] = number(r"high water (\d+) MB since the last line", body)
    out["nativeDisconnects"] = number(r"native\.disconnects (\d+) since start", body)
    webs = re.search(r"webs by player, held/kept for good \{([^}]*)\}", body)
    if webs:
        out["websByPlayer"] = {}
        for part in filter(None, webs.group(1).split(", ")):
            name, _, counts = part.rpartition("=")
            held, _, kept = counts.partition("/")
            out["websByPlayer"][name] = {"held": int(held), "keptForGood": int(kept)}
    refused = re.search(r"Wire (none refused since start|(\d+) refused since start \(([^)]*)\))", body)
    if refused:
        out["wireRefused"] = int(refused.group(2) or 0)
        out["wireRefusedBy"] = {}
        for part in filter(None, (refused.group(3) or "").split(", ")):
            key, _, n = part.rpartition(" ")
            out["wireRefusedBy"][key] = int(n)
    territory = re.search(r"territory refusals (\d+) written, (\d+) the same minute's and (\d+) over a faction's daily cap", body)
    if territory:
        out["territoryRefusals"] = {"written": int(territory.group(1)), "sameMinute": int(territory.group(2)), "overCap": int(territory.group(3))}
    ticks = re.search(r"server ticks (\d+): mean ([\d.]+) ms, p99 (over \d+|\d+) ms, (\d+) over 50 ms, longest ([\d.]+) ms \(tick (-?\d+)\)", body)
    if ticks:
        p99 = ticks.group(3)
        out["ticks"] = {"count": int(ticks.group(1)), "meanMs": float(ticks.group(2)),
                        "p99Ms": None if p99.startswith("over") else int(p99), "p99Over": p99.startswith("over"),
                        "over50": int(ticks.group(4)), "longestMs": float(ticks.group(5)), "longestAtTick": int(ticks.group(6))}
    elif re.search(r"server ticks 0 since the last line", body):
        out["ticks"] = {"count": 0}
    return out


def read_console(text):
    lines, warns, errors, prunes = [], collections.Counter(), [], collections.defaultdict(lambda: [0, 0, 0])
    for raw in text.splitlines():
        m = COST.search(raw)
        if m:
            lines.append(cost_line(int(m.group(1)), m.group(2)))
            continue
        for name, pattern in PRUNES.items():
            p = pattern.search(raw)
            if p:
                prunes[name][0] += 1
                prunes[name][1] += int(p.group(1))
                prunes[name][2] += int(p.group(2))
        s = STAMP.search(raw)
        if not s:
            continue
        message = raw[s.end():].strip()
        if s.group(3) == "WARN":
            # By kind: the message with its numbers and ids blanked, so one kind counts once however often it comes.
            kind = re.sub(r"[0-9a-f]{8}-[0-9a-f-]{27}", "<id>", message)
            kind = re.sub(r"-?\d+(\.\d+)?", "N", kind)
            # A player's name (a capitalised word after the first, but the product's own words) blanked too.
            kind = re.sub(r"(?<=\s)(?!(?:Kindred|World|Record|Wire|Chronicle|Minecraft)\b)[A-Z][A-Za-z0-9_]*", "<name>", kind)[:140]
            warns[kind] += 1
        else:
            who = re.search(r"\(Kindred\) ([A-Z][A-Za-z0-9_]{1,15})[ ':]", message)
            tick = number(r"tick (\d+)", message)
            errors.append({"time": s.group(1), "thread": s.group(2), "player": who.group(1) if who else None, "tick": tick, "line": message[:300]})
    return lines, warns, errors, {k: {"passes": v[0], "removed": v[1], "secondFigure": v[2]} for k, v in prunes.items()}


def read_record(path):
    db = sqlite3.connect("file:%s?mode=ro" % path, uri=True)
    try:
        out = {"integrity": db.execute("PRAGMA integrity_check").fetchone()[0]}
        out["events"] = db.execute("SELECT COUNT(*) FROM world_events").fetchone()[0]
        out["byKind"] = dict(db.execute("SELECT kind, COUNT(*) FROM world_events GROUP BY kind ORDER BY 2 DESC").fetchall())
        checks = db.execute("SELECT subject, COUNT(*) FROM world_events WHERE kind = 'ledger_check' GROUP BY subject").fetchall()
        out["ledgerChecks"] = dict(checks)
        out["unresolvedActions"] = db.execute(
            "SELECT COUNT(*) FROM action_ledger WHERE state IN ('PREPARED','DISPATCHED','UNCERTAIN')").fetchone()[0]
        out["schema"] = db.execute("PRAGMA user_version").fetchone()[0]
        return out
    finally:
        db.close()


def growth(lines):
    """Per game day, measured between successive day lines (a stop's line for the same day replaces its day's)."""
    by_day = {}
    for line in lines:
        by_day[line["day"]] = line
    days = sorted(by_day)
    out = []
    for before, after in zip(days, days[1:]):
        a, b = by_day[before], by_day[after]
        span = after - before
        if a.get("databaseBytes") is None or b.get("databaseBytes") is None or span <= 0:
            continue
        out.append({"fromDay": before, "toDay": after,
                    "bytesPerGameDay": (b["databaseBytes"] - a["databaseBytes"]) / span,
                    "eventsPerGameDay": ((b.get("recordThrough") or 0) - (a.get("recordThrough") or 0)) / span,
                    # Each web's facts kept for good, a game day: only for a player in both lines (a web is rebuilt at start).
                    "webKeptPerGameDay": {name: (web["keptForGood"] - (a.get("websByPlayer") or {})[name]["keptForGood"]) / span
                                          for name, web in sorted((b.get("websByPlayer") or {}).items())
                                          if name in (a.get("websByPlayer") or {})}})
    return out


def verdict(lines, errors, record, heap_max_mb):
    stop, watch = [], []
    if record is not None:
        if record["integrity"] != "ok":
            stop.append("The Record's integrity check is not ok: %s" % record["integrity"])
        if record["ledgerChecks"].get("differs"):
            stop.append("%d ledger check(s) differ from the World Record" % record["ledgerChecks"]["differs"])
        if record["unresolvedActions"]:
            watch.append("%d action(s) unresolved in the copy (settle them at the next start)" % record["unresolvedActions"])
    for e in errors:
        if "differs from the World Record" in e["line"] or "not checked against the World Record" in e["line"]:
            stop.append("ERROR at %s: %s" % (e["time"], e["line"][:160]))
    if errors and not stop:
        watch.append("%d ERROR line(s): read each" % len(errors))
    for line in lines:
        t = line.get("ticks") or {}
        if t.get("count"):
            if t.get("p99Over") or (t.get("p99Ms") or 0) > P99_WATCH_MS:
                watch.append("day %d: p99 tick %s ms, over the 50 ms a tick has" % (line["day"], "over 2000" if t.get("p99Over") else t["p99Ms"]))
            if t["over50"] / t["count"] > OVER50_WATCH_SHARE:
                watch.append("day %d: %.1f%% of ticks over 50 ms" % (line["day"], 100.0 * t["over50"] / t["count"]))
        if heap_max_mb and line.get("heapHighWaterMb") and line["heapHighWaterMb"] > HEAP_STOP_SHARE * heap_max_mb:
            stop.append("day %d: heap high water %d MB, over %d%% of %d MB" % (line["day"], line["heapHighWaterMb"], HEAP_STOP_SHARE * 100, heap_max_mb))
        for name, web in sorted((line.get("websByPlayer") or {}).items()):
            if web["keptForGood"] > WEB_BOUND:
                watch.append("day %d: %s's web keeps %d facts for good, past its bound of %d (F-32e)" % (line["day"], name, web["keptForGood"], WEB_BOUND))
        tr = line.get("territoryRefusals")
        if tr and tr["overCap"]:
            watch.append("day %d: %d refused attempts over a faction's daily cap (a griefer?)" % (line["day"], tr["overCap"]))
    if lines:
        first, last = lines[0], lines[-1]
        if (last.get("nativeDisconnects") or 0) > (first.get("nativeDisconnects") or 0):
            watch.append("native.disconnects rose from %s to %s" % (first.get("nativeDisconnects"), last.get("nativeDisconnects")))
    return stop, watch


def report(console_text, db_path=None, heap_max_mb=None):
    lines, warns, errors, prunes = read_console(console_text)
    record = read_record(db_path) if db_path else None
    grown = growth(lines)
    out = {"costLines": lines, "growth": grown, "prunes": prunes, "warnsByKind": dict(warns.most_common()), "errors": errors,
           "record": record}
    if grown:
        last = grown[-1]
        out["projectedMonth"] = {"note": "projected, not measured: the last measured game day's growth times %d game days" % GAME_DAYS_A_MONTH,
                                 "databaseBytes": (lines[-1].get("databaseBytes") or 0) + last["bytesPerGameDay"] * GAME_DAYS_A_MONTH}
    out["stop"], out["watch"] = verdict(lines, errors, record, heap_max_mb)
    return out


def text(out):
    w = []
    w.append("Kindred month-run pre-flight (M-06k7). Measured from the files unless marked projected.")
    w.append("")
    w.append("Per cost line:")
    for line in out["costLines"]:
        t = line.get("ticks") or {}
        ticks = ("%d ticks, mean %.1f ms, p99 %s ms, %d over 50 ms, longest %.0f ms" % (t["count"], t["meanMs"], "over 2000" if t.get("p99Over") else t["p99Ms"], t["over50"], t["longestMs"])
                 if t.get("count") else "no ticks measured")
        tr = line.get("territoryRefusals")
        w.append("  day %s: db %s bytes, Record through %s; heap %s MB (high water %s MB); %s; Wire refused %s %s%s; disconnects %s" % (
            line["day"], line.get("databaseBytes"), line.get("recordThrough"), line.get("heapInUseMb"), line.get("heapHighWaterMb"), ticks,
            line.get("wireRefused"), line.get("wireRefusedBy") or "",
            "; territory %(written)d written, %(sameMinute)d same minute, %(overCap)d over cap" % tr if tr else "", line.get("nativeDisconnects")))
        if line.get("websByPlayer"):
            w.append("    webs, held/kept for good: " + ", ".join("%s %d/%d" % (n, v["held"], v["keptForGood"]) for n, v in sorted(line["websByPlayer"].items())))
    w.append("")
    w.append("Growth a game day, measured between day lines:")
    for g in out["growth"] or [{"fromDay": "-", "toDay": "-", "bytesPerGameDay": 0, "eventsPerGameDay": 0}]:
        w.append("  days %s to %s: %.0f bytes, %.0f events a game day" % (g["fromDay"], g["toDay"], g["bytesPerGameDay"], g["eventsPerGameDay"]))
        if g.get("webKeptPerGameDay"):
            w.append("    webs' facts kept for good a game day: " + ", ".join("%s %.1f" % kv for kv in g["webKeptPerGameDay"].items()))
    if "projectedMonth" in out:
        w.append("  A month (%s): %.0f MB on disk" % (out["projectedMonth"]["note"], out["projectedMonth"]["databaseBytes"] / 1e6))
    w.append("")
    w.append("Bounds' prunes seen: %s" % (json.dumps(out["prunes"]) if out["prunes"] else "none"))
    if out["record"]:
        r = out["record"]
        w.append("The Record copy: integrity %s, schema %s, %d events, ledger checks %s, unresolved actions %d" % (
            r["integrity"], r["schema"], r["events"], r["ledgerChecks"] or "none", r["unresolvedActions"]))
    w.append("")
    w.append("WARN lines by kind (%d kinds):" % len(out["warnsByKind"]))
    for kind, n in list(out["warnsByKind"].items())[:20]:
        w.append("  %6d  %s" % (n, kind))
    w.append("ERROR lines: %d" % len(out["errors"]))
    for e in out["errors"][:20]:
        w.append("  %s %s%s: %s" % (e["time"], e["player"] or "-", " (tick %d)" % e["tick"] if e["tick"] is not None else "", e["line"][:200]))
    w.append("")
    w.append("STOP: " + ("; ".join(out["stop"]) if out["stop"] else "none"))
    w.append("WATCH: " + ("; ".join(out["watch"]) if out["watch"] else "none"))
    return "\n".join(w)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--console", required=True, type=Path)
    parser.add_argument("--db", type=Path)
    parser.add_argument("--heap-max-mb", type=int, help="the server's -Xmx, in MB, for the heap threshold")
    parser.add_argument("--json", type=Path)
    args = parser.parse_args(argv)
    out = report(args.console.read_text(errors="replace"), args.db, args.heap_max_mb)
    if args.json:
        args.json.write_text(json.dumps(out, indent=2))
    print(text(out))
    return 2 if out["stop"] else 0


if __name__ == "__main__":
    sys.exit(main())
