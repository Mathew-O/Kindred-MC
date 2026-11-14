#!/usr/bin/env python3
"""After a soak (F-31g): one more start of a copy of its world, to check what each player comes back holding, and the
operator's way back for a player blocked by an action a kill left unsettled.

For each AI player: its inventory as its native save holds it on disk (world/playerdata), then, started, connected and
not yet resumed (so it has not acted), its inventory as the console reads it: the two must be the same. A player
blocked for reconciliation is tried as an operator would: connect (refused while blocked), then `aiplayer recover`,
then connect, read, and resume; whether it then plays, and whether its unsettled action was replayed, are recorded.

  tools/soak/restart_check.py run/soak/a
"""
import collections
import gzip
import json
import re
import shutil
import sqlite3
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parents[1] / "tools" / "eval"))
import items  # noqa: E402
import report  # noqa: E402
import run as evalrun  # noqa: E402
import soak  # noqa: E402


def disk(world):
    cache = json.loads((world.parent / "usercache.json").read_text())
    names = {e["uuid"]: e["name"] for e in cache}
    out = {}
    for path in sorted((world / "playerdata").glob("*.dat")):
        if path.stem in names:
            save = items.nbt(gzip.decompress(path.read_bytes()))
            out[names[path.stem]] = collections.Counter({k: v for k, v in items.held({"Inventory": save.get("Inventory", [])}).items()})
    return out


def unsettled(world):
    db = sqlite3.connect("file:%s?mode=ro" % (world / "kindred" / "companions.sqlite"), uri=True)
    return dict(db.execute("SELECT state, COUNT(*) FROM action_ledger WHERE state NOT IN ('VERIFIED', 'FAILED', 'CANCELLED') GROUP BY state").fetchall())


def main():
    soak_dir = Path(sys.argv[1]).resolve()
    source = soak_dir / "world-folder"
    folder = soak_dir / "check-folder"
    if folder.exists():
        shutil.rmtree(folder)
    shutil.copytree(source, folder, ignore=shutil.ignore_patterns("snapshots", "console-*.txt", "samples.jsonl"))
    (soak_dir / "cache").exists() or sys.exit("no cache")
    before = disk(folder / "world")
    states_before = unsettled(folder / "world")
    env = {k: v for k, v in evalrun.environment("x=y").items() if k != "KINDRED_EVAL"}
    server = soak.Server(folder, "6G", env, "check")
    result = {"unsettledBefore": states_before, "players": {}}
    if not server.wait_for(r"Native players allowed on this server", 600):
        sys.exit("never ready")
    time.sleep(5)
    for name in soak.PLAYERS:
        entry = {}
        status = server.ask("aiplayer native status %s" % name, r"%s \(.*" % name, 15) or ""
        entry["blocked"] = "reconcil" in status.lower()
        entry["connect"] = server.ask("aiplayer native connect %s" % name, r"(Native body connected.*|.*[Rr]econcil.*|.*refus.*)", 15)
        if entry["blocked"]:
            entry["recover"] = server.ask("aiplayer recover %s" % name, r"(.*recovered.*|.*[Rr]econcil.*|.*cannot.*|Use native lifecycle controls.*)", 15)
            entry["connectAfterRecover"] = server.ask("aiplayer native connect %s" % name, r"(Native body connected.*|.*[Rr]econcil.*|.*refus.*)", 15)
        time.sleep(2)
        read = server.ask("data get entity %s Inventory" % name, r"(%s has the following entity data: .*|No entity was found.*)" % name, 15)
        got = soak_items(read)
        want = before.get(name, collections.Counter())
        entry["inWorld"] = read is not None and not read.startswith("No entity")
        entry["onDisk"] = sum(want.values())
        entry["read"] = sum(got.values()) if entry["inWorld"] else None
        entry["same"] = entry["inWorld"] and got == want
        if entry["inWorld"] and got != want:
            entry["delta"] = {k: got[k] - want[k] for k in set(got) | set(want) if got[k] != want[k]}
        result["players"][name] = entry
    since = len(server.text())
    for name in soak.PLAYERS:
        server.command("aiplayer native resume %s" % name)
        time.sleep(1)
    time.sleep(60)
    text = server.text()[since:]
    for name, entry in result["players"].items():
        entry["resumed"] = bool(re.search(r"Resumed; autonomous progression active", text)) and entry["inWorld"]
        entry["actedAfterResume"] = len(re.findall(r"\b%s (?:goal at|native_\w+ (?:COMPLETED|FAILED))" % re.escape(name), text))
    server.command("stop")
    server.process.wait(300)
    server.console.close()
    result["unsettledAfter"] = unsettled(folder / "world")
    (soak_dir / "restart-check.json").write_text(json.dumps(result, indent=1) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=1))


def soak_items(text):
    counts = collections.Counter()
    for item_id, count in report.stacks(text or ""):
        counts[item_id] += int(count)
    return counts


if __name__ == "__main__":
    main()
