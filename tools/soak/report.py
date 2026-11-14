#!/usr/bin/env python3
"""The restart soak's checks (F-31g): each snapshot against the one before it, and the faction rules over each.

Reads what tools/soak/soak.py kept (plan.json, segments.json, samples.jsonl, the console of each start and the
snapshot after each stop or kill) and writes report.json and report.md beside them. Python 3 standard library only;
the faction audit runs companion-testkit's RecordAudit through the Gradle wrapper.

  tools/soak/report.py run/soak/a
"""
import collections
import json
import re
import sqlite3
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
# Folded by F-31b (settled actions replaced by one event each): the only kind the Record ever deletes.
FOLDED_KIND = "action"
# Console lines about outcomes that could not be settled, or things set aside, after a start.
RECONCILE = re.compile(r"(?i)(reconcil|uncertain|ambiguous|unreadable|set aside|could not be read|not replayed|blocked for|storage inspection)")


def stacks(text):
    """Each item stack in a console inventory read, as (id, count): the list's elements split by brace depth, so a
    stack with components (damage, enchantments) is read whole."""
    out, depth, start = [], 0, None
    body = text[text.find("["):] if text and "[" in text else ""
    for i, ch in enumerate(body):
        if ch == "{":
            if depth == 0:
                start = i
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0 and start is not None:
                element = body[start:i + 1]
                # Its own count leads it; its own id is the last named in it (a component's ids come before).
                found_id = re.findall(r'id: "([a-z0-9_:./-]+)"', element)
                found_count = re.search(r"^\{count: (\d+)", element)
                if found_id and found_count:
                    out.append((found_id[-1], int(found_count.group(1))))
    return out


def connect(snapshot):
    database = snapshot / "read" / "companions.sqlite"
    return sqlite3.connect("file:%s?mode=ro" % database, uri=True) if database.is_file() else None


def events(db):
    rows = db.execute("SELECT id, kind, actor, subject, place, data, tick, real_time_ms FROM world_events ORDER BY id").fetchall()
    return {r[0]: r for r in rows}


def tables(db):
    names = [r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")]
    return {n: db.execute('SELECT COUNT(*) FROM "%s"' % n).fetchone()[0] for n in names}


def keyed(db, table):
    """A table's rows by their primary key (or whole row when it has none), for what a later snapshot lost."""
    columns = db.execute('PRAGMA table_info("%s")' % table).fetchall()
    key = [c[1] for c in sorted(columns, key=lambda c: c[5]) if c[5] > 0] or [c[1] for c in columns]
    return {tuple(r) for r in db.execute('SELECT %s FROM "%s"' % (", ".join('"%s"' % k for k in key), table))}


def memory(snapshot):
    out = {}
    folder = snapshot / "memory"
    for path in sorted(folder.glob("*")) if folder.is_dir() else []:
        if path.suffix == ".unreadable":
            out[path.name] = {"unreadable": True}
            continue
        try:
            value = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as bad:
            out[path.name] = {"error": str(bad)}
            continue
        out[path.name] = {"entries": entries(value)}
    return out


def entries(value, prefix=""):
    """Every list in a memory file, by its path, with its items as canonical text: what a later file can be checked for."""
    found = {}
    if isinstance(value, dict):
        for k, v in value.items():
            found.update(entries(v, prefix + "/" + k))
    elif isinstance(value, list):
        found[prefix or "/"] = sorted(identity(item) for item in value)
    return found


# What names an entry in a memory file, so that an entry updated since (seen again, its age on) is not taken for lost.
IDENTITY = ("id", "villager", "player", "dimension", "x", "y", "z", "pos", "place", "key", "name")


def identity(item):
    if isinstance(item, dict):
        named = [(k, item[k]) for k in IDENTITY if k in item and not isinstance(item[k], (dict, list))]
        if named:
            return json.dumps(named)
    return json.dumps(item, sort_keys=True)


def items(text):
    counts = collections.Counter()
    for item_id, count in stacks(text or ""):
        counts[item_id] += int(count)
    return counts


def audit(snapshot):
    database = snapshot / "read" / "companions.sqlite"
    run = subprocess.run(["./gradlew", "--no-daemon", "-q", ":companion-testkit:runRecordAudit", "--args=%s" % database],
                         cwd=ROOT, capture_output=True, text=True)
    for line in reversed(run.stdout.splitlines()):
        if line.startswith("{"):
            return json.loads(line)
    return {"error": (run.stderr or run.stdout)[-2000:]}


def compare(before, after):
    """What the later snapshot lost or wrote again of the earlier one's Record."""
    lost, changed = [], []
    for eid, row in before.items():
        if row[1] == FOLDED_KIND:
            continue
        if eid not in after:
            lost.append(row)
        elif after[eid] != row:
            changed.append((row, after[eid]))
    last = max(before) if before else 0
    # Same kind, actor, subject, place and data: an event of before the restart written again after it.
    earlier = collections.Counter((r[1], r[2], r[3], r[4], r[5]) for r in before.values())
    again = collections.Counter()
    for eid, row in after.items():
        if eid > last and (row[1], row[2], row[3], row[4], row[5]) in earlier:
            again[row[1]] += 1
    return lost, changed, again


def main():
    folder = Path(sys.argv[1]).resolve()
    plan = json.loads((folder / "plan.json").read_text())
    segments = json.loads((folder / "segments.json").read_text())
    world = folder / "world-folder"
    samples = [json.loads(l) for l in (world / "samples.jsonl").read_text().splitlines() if l.strip()] if (world / "samples.jsonl").is_file() else []
    report = {"plan": plan, "segments": []}
    previous = None
    for record in segments:
        label = record["segment"]
        snapshot = world / "snapshots" / label
        db = connect(snapshot)
        entry = {"segment": label, "eval": record.get("eval"), "killed": record.get("killed"), "exit": record.get("exit"),
                 "seconds": record.get("seconds"), "readySeconds": record.get("readySeconds"), "mode": record.get("mode"),
                 "sprint": record.get("sprint"), "tickQuery": record.get("tickQuery")}
        mine = [s for s in samples if s["segment"] == label]
        if mine:
            entry["heapUsedMaxMB"] = max((s.get("usedMB", 0) for s in mine), default=None)
            entry["heapCommittedMaxMB"] = max((s.get("committedMB", 0) for s in mine), default=None)
            entry["dbBytes"] = [mine[0]["db"], mine[-1]["db"]]
            entry["walBytesMax"] = max(s["wal"] for s in mine)
        console = world / ("console-%s.txt" % label)
        text = console.read_text(encoding="utf-8", errors="replace") if console.is_file() else ""
        entry["reconcileLines"] = [l.strip()[:300] for l in text.splitlines() if RECONCILE.search(l)][:40]
        entry["errors"] = [l.strip()[:300] for l in text.splitlines() if "/ERROR]" in l or "Exception" in l][:40]
        if db is None:
            entry["note"] = "no database in the snapshot"
            report["segments"].append(entry)
            continue
        entry["integrity"] = db.execute("PRAGMA integrity_check").fetchone()[0]
        current = {"events": events(db), "tables": tables(db), "memory": memory(snapshot),
                   "keyed": {t: keyed(db, t) for t in ("native_players", "memories", "social_profiles", "goals", "action_ledger") if t in tables(db)}}
        entry["tables"] = current["tables"]
        entry["eventsByKind"] = dict(collections.Counter(r[1] for r in current["events"].values()).most_common())
        open_actions = db.execute("SELECT state, COUNT(*) FROM action_ledger GROUP BY state").fetchall() if "action_ledger" in current["tables"] else []
        entry["actionStates"] = dict(open_actions)
        entry["audit"] = audit(snapshot)
        if previous:
            lost, changed, again = compare(previous["events"], current["events"])
            entry["eventsLost"] = [list(r[:5]) for r in lost][:50]
            entry["eventsLostCount"] = len(lost)
            entry["eventsChanged"] = len(changed)
            entry["writtenAgainByKind"] = dict(again)
            entry["rowsLost"] = {t: len(previous["keyed"][t] - current["keyed"].get(t, set())) for t in previous["keyed"]}
            mem = {}
            for name, was in previous["memory"].items():
                now = current["memory"].get(name)
                if now is None:
                    mem[name] = "file gone"
                    continue
                if "entries" not in was or "entries" not in now:
                    mem[name] = {"before": was, "after": now}
                    continue
                gone = {p: len(set(v) - set(now["entries"].get(p, []))) for p, v in was["entries"].items()}
                mem[name] = {"before": {p: len(v) for p, v in was["entries"].items()}, "after": {p: len(v) for p, v in now["entries"].items()},
                             "gone": {p: n for p, n in gone.items() if n}}
            entry["memory"] = mem
            stop, start = previous["record"].get("beforeStop") or previous["record"].get("beforeKill"), record.get("afterStart")
            if stop and start:
                diff = {}
                for name in stop:
                    a, b = items(stop[name]["inventory"]), items((start.get(name) or {}).get("inventory"))
                    delta = {k: b[k] - a[k] for k in set(a) | set(b) if b[k] != a[k]}
                    diff[name] = {"before": sum(a.values()), "after": sum(b.values()), "delta": delta}
                entry["inventories"] = diff
        else:
            entry["memory"] = {name: {"entries": {p: len(v) for p, v in m.get("entries", {}).items()}} for name, m in current["memory"].items()}
        current["record"] = record
        previous = current
        report["segments"].append(entry)
    (folder / "report.json").write_text(json.dumps(report, indent=1, default=str) + "\n", encoding="utf-8")
    lines = ["# Restart soak: seed %s, %s" % (plan["seed"], plan["jar"]), "", "| Start | How it ended | Ready (s) | Integrity | Events | Lost | Changed | Written again | Audit | Heap used max (MB) | DB bytes | WAL max | Sprint |",
             "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |"]
    for e in report["segments"]:
        audit_text = e.get("audit", {})
        audited = ("clean" if not audit_text.get("violations") else "%d broken" % len(audit_text["violations"])) if "violations" in audit_text else audit_text.get("error", "-")[:40]
        lines.append("| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |" % (
            e["segment"], "killed" if e.get("killed") else ("eval, stopped itself" if e.get("eval") else "stopped at the console") + " (exit %s)" % e.get("exit"),
            e.get("readySeconds"), e.get("integrity"), sum(e.get("eventsByKind", {}).values()), e.get("eventsLostCount", "-"), e.get("eventsChanged", "-"),
            sum(e.get("writtenAgainByKind", {}).values()) if "writtenAgainByKind" in e else "-", audited, e.get("heapUsedMaxMB"), e.get("dbBytes"),
            e.get("walBytesMax"), (e.get("sprint") or {}).get("msPerTick", "-")))
    (folder / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
