#!/usr/bin/env python3
"""The month run's restart soak (F-31g): one faction world stopped and started many times, checked at every start.

One world, one folder, kept across every segment. The first segment is an evaluation run (tools/eval) that makes the
faction world as tools/eval/README.md describes, with eight players; the operator's own choice of the mode is then
made at the console (/aiplayer mode world factions), so that it is saved as on the owner's server. Every later segment
starts the same world as an operator would, with no KINDRED_EVAL: each player is connected at the console as the
operator guide says (aiplayer native connect, the Factions pack's step 5, then
aiplayer native resume, which the guide leaves out), they play a game day (/tick sprint) and are stopped at the console (/stop), except one segment, which is killed (SIGKILL) part way.

After every stop or kill, the world's companion database (with its WAL) and the players' memory files are copied aside
as they lie on disk, and checked:
- the database opens and passes SQLite's integrity check;
- every World Record event the last snapshot held is still there, unchanged (action events excepted: F-31b folds
  settled actions into one event each);
- no event from before the restart was written again after it (a starting grant, a sale, a delivery, a claim);
- the faction world's locked rules over the whole Record, across every restart (companion-testkit's RecordAudit);
- each player's memory files: what each held before and after, and what the later one no longer has;
- each player's inventory read at the console just before the stop and just after the start (not before the first
  segment's stop: the evaluation run stops itself); coins are checked from the Record, by the audit.
While a segment runs, every 30 s: the database's and WAL's size, the server's heap (jcmd), and at the sprint's end its
tick rate and milliseconds per tick (vanilla's own report).

Python 3 standard library only. Usage:
  tools/soak/soak.py --jar companion-server-adapter/build/libs/kindred-mc-0.1.0-SNAPSHOT.jar --seed 101 --out run/soak/a
Then tools/soak/report.py run/soak/a.
"""
import argparse
import json
import os
import re
import shutil
import signal
import sqlite3
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(ROOT / "tools" / "eval"))
import run as evalrun  # noqa: E402  (the evaluation engine's own server setup)

PLAYERS = ["Kindred", "Sol", "Echo", "Ada", "Bram", "Cleo", "Dara", "Eli"]


class Server:
    """One start of the world: the process, its console file, and its stdin for the operator's commands."""

    def __init__(self, folder, memory, env, label):
        self.folder, self.label = folder, label
        self.console_path = folder / ("console-%s.txt" % label)
        self.console = open(self.console_path, "w", encoding="utf-8")
        start = sorted((folder.parent / "cache").glob("fabric-server-*.jar"))[-1]
        self.process = subprocess.Popen(["java", "-Xmx" + memory, "-jar", str(start), "nogui"], cwd=folder, env=env,
                                        stdin=subprocess.PIPE, stdout=self.console, stderr=subprocess.STDOUT, text=True)
        self.started = time.time()
        self.read_at = 0

    def text(self):
        try:
            return self.console_path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            return ""

    def wait_for(self, pattern, timeout, since=0):
        """Waits for a console line matching pattern after offset since; the match, or None at the timeout or exit."""
        deadline = time.time() + timeout
        rx = re.compile(pattern)
        while time.time() < deadline:
            text = self.text()
            found = rx.search(text, since)
            if found:
                return found
            if self.process.poll() is not None:
                return None
            time.sleep(2)
        return None

    def command(self, line):
        if self.process.poll() is None:
            self.process.stdin.write(line + "\n")
            self.process.stdin.flush()

    def ask(self, line, pattern, timeout=20):
        """Sends a console command and returns the first matching line written after it."""
        since = len(self.text())
        self.command(line)
        found = self.wait_for(pattern, timeout, since)
        return found.group(0) if found else None

    def heap(self):
        try:
            out = subprocess.run(["jcmd", str(self.process.pid), "GC.heap_info"], capture_output=True, text=True, timeout=20).stdout
        except (OSError, subprocess.TimeoutExpired):
            return None
        used = re.search(r"total (\d+)K, used (\d+)K", out)
        return {"committedMB": int(used.group(1)) // 1024, "usedMB": int(used.group(2)) // 1024} if used else None


def sizes(world):
    kindred = world / "kindred"
    def size(name):
        path = kindred / name
        return path.stat().st_size if path.is_file() else 0
    memory = sum(p.stat().st_size for p in (kindred / "memory").glob("*")) if (kindred / "memory").is_dir() else 0
    region = sum(p.stat().st_size for p in world.rglob("*.mca")) if world.is_dir() else 0
    return {"db": size("companions.sqlite"), "wal": size("companions.sqlite-wal"), "memoryFiles": memory, "regions": region}


def sample(server, folder, samples, phase):
    entry = {"t": round(time.time() - server.started), "segment": server.label, "phase": phase}
    entry.update(sizes(folder / "world"))
    heap = server.heap()
    if heap:
        entry.update(heap)
    samples.append(entry)
    with open(folder / "samples.jsonl", "a", encoding="utf-8") as out:
        out.write(json.dumps(entry) + "\n")


def snapshot(folder, label):
    """The companion database with its WAL and the memory files, copied as they lie on disk."""
    target = folder / "snapshots" / label
    if target.exists():
        shutil.rmtree(target)
    target.mkdir(parents=True)
    kindred = folder / "world" / "kindred"
    for path in sorted(kindred.glob("companions.sqlite*")):
        shutil.copy2(path, target / path.name)
    (target / "memory").mkdir()
    for path in sorted((kindred / "memory").glob("*")) if (kindred / "memory").is_dir() else []:
        shutil.copy2(path, target / "memory" / path.name)
    for name in ("mode.properties", "edits.properties"):
        if (kindred / name).is_file():
            shutil.copy2(kindred / name, target / name)
    # A working copy for reading: the database opened on its own copy, so the copy kept is the bytes as they lay.
    work = target / "read"
    work.mkdir()
    for path in sorted(target.glob("companions.sqlite*")):
        shutil.copy2(path, work / path.name)
    return target


def players_state(server):
    """Each AI player's inventory and coins as the console reports them: vanilla's data command and the coins line."""
    state = {}
    for name in PLAYERS:
        inventory = server.ask("data get entity %s Inventory" % name, r"(%s has the following entity data: .*|No entity was found.*)" % name, 15)
        state[name] = {"inventory": inventory}
    return state


def segment(args, folder, label, env, kill_after=None, eval_run=False):
    """One start: waits for the players, a game day, then a stop at the console or a kill part way."""
    server = Server(folder, args.memory, env, label)
    record = {"segment": label, "eval": eval_run, "killed": False}
    samples = []
    ready = server.wait_for(r"Native players allowed on this server", 600)
    record["ready"] = bool(ready)
    record["readySeconds"] = round(time.time() - server.started)
    if not ready:
        server.process.kill()
        record["note"] = "never ready"
        return record
    if eval_run:
        # The operator's own choice of the mode, saved as on the owner's server; the eval's own is for its run alone.
        found = server.wait_for(r"Evaluation run: a faction world", 1800)
        record["factionWorld"] = bool(found)
        server.ask("aiplayer mode world factions", r"(This world now plays Factions.*|World mode: Factions)", 30)
    else:
        # The players come back from their saves as the operator guide says (the Factions pack, step 5): a restart
        # leaves each offline, and the operator connects each. Then a few seconds, and what each holds is read.
        record["list"] = server.ask("aiplayer native list", r"Ada \(.*", 20)
        since = len(server.text())
        for name in PLAYERS:
            server.command("aiplayer native connect %s" % name)
            time.sleep(2)
        time.sleep(5)
        # Connected, each is still stopped ("Resume explicitly"): the operator resumes each, a step the guide leaves out.
        for name in PLAYERS:
            server.command("aiplayer native resume %s" % name)
            time.sleep(1)
        time.sleep(10)
        joined = re.findall(r"(\w+) joined the game", server.text()[since:])
        record["connected"] = sorted(set(joined))
        record["afterStartSeconds"] = round(time.time() - server.started)
        record["mode"] = server.ask("aiplayer mode world", r"\w+ \(this world.s mode\)", 20)
        record["afterStart"] = players_state(server)
        server.command("tick sprint %d" % args.ticks)
    sample(server, folder, samples, "start")
    done_at = None
    kill_at = server.started + kill_after if kill_after else None
    while server.process.poll() is None:
        time.sleep(30)
        sample(server, folder, samples, "run")
        sprinted = re.search(r"Sprint completed with ([\d.,]+) ticks per second, or ([\d.,]+) ms per tick", server.text())
        # The killed start is killed part way, or at its sprint's end if that comes first: never stopped.
        if kill_at and (time.time() >= kill_at or sprinted):
            record["beforeKill"] = players_state(server)
            sample(server, folder, samples, "kill")
            if server.process.poll() is None:
                os.kill(server.process.pid, signal.SIGKILL)
            server.process.wait()
            record["killed"] = True
            break
        if eval_run:
            continue
        report = sprinted
        if report and not done_at:
            done_at = time.time()
            record["sprint"] = {"ticksPerSecond": report.group(1), "msPerTick": report.group(2)}
            record["tickQuery"] = server.ask("tick query", r"Average time per tick: .*", 20)
            record["beforeStop"] = players_state(server)
            sample(server, folder, samples, "stop")
            server.command("stop")
        if time.time() - server.started > args.timeout:
            record["note"] = "timed out; stopped"
            server.command("stop")
            try:
                server.process.wait(300)
            except subprocess.TimeoutExpired:
                server.process.kill()
    server.process.wait()
    server.console.close()
    record["exit"] = server.process.returncode
    record["seconds"] = round(time.time() - server.started)
    if eval_run and (folder / "logs" / "eval-result.json").is_file():
        shutil.copy2(folder / "logs" / "eval-result.json", folder / "eval-result.json")
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--jar", required=True)
    parser.add_argument("--seed", default="101")
    parser.add_argument("--out", required=True)
    parser.add_argument("--ticks", type=int, default=24000, help="each segment's game time (a game day is 24,000)")
    parser.add_argument("--segments", type=int, default=4, help="plain starts after the first (evaluation) one")
    parser.add_argument("--kill", type=int, default=2, help="the plain start (1-based) killed part way; 0 for none")
    parser.add_argument("--kill-after", type=int, default=600, help="seconds into the killed start")
    parser.add_argument("--memory", default="6G")
    parser.add_argument("--timeout", type=int, default=3 * 3600)
    args = parser.parse_args()

    folder = Path(args.out).resolve() / "world-folder"
    cache = Path(args.out).resolve() / "cache"
    minecraft, loader, fabric = evalrun.pinned()
    evalrun.launcher(cache, minecraft, loader)
    api = evalrun.fabric_api_jar(fabric)
    vanilla = evalrun.vanilla_server(cache, minecraft)
    jar = Path(args.jar).resolve()
    evalrun.prepare(folder, args.seed, jar, api, vanilla, policy=["maxNativePlayers=8"])
    scenario = "starters=Kindred,Sol,Echo; players=%s; founders=3; goal=none; mode=factions; ticks=%d" % (",".join(PLAYERS[3:]), args.ticks)
    plain = {k: v for k, v in evalrun.environment(scenario).items() if k != "KINDRED_EVAL"}
    plan = {"seed": args.seed, "jar": jar.name, "scenario": scenario, "ticks": args.ticks, "segments": args.segments,
            "kill": args.kill, "killAfter": args.kill_after, "memory": args.memory, "minecraft": minecraft, "loader": loader, "fabricApi": fabric}
    (folder.parent / "plan.json").write_text(json.dumps(plan, indent=1) + "\n", encoding="utf-8")
    records = []
    def keep(record):
        records.append(record)
        (folder.parent / "segments.json").write_text(json.dumps(records, indent=1) + "\n", encoding="utf-8")
        print("segment %s: exit %s, %s s%s" % (record["segment"], record.get("exit"), record.get("seconds"), ", killed" if record.get("killed") else ""), flush=True)

    keep(segment(args, folder, "s0", evalrun.environment(scenario), eval_run=True))
    snapshot(folder, "s0")
    for k in range(1, args.segments + 1):
        label = "s%d" % k
        keep(segment(args, folder, label, plain, kill_after=args.kill_after if k == args.kill else None))
        snapshot(folder, label)
    print("done: %s" % folder.parent, flush=True)


if __name__ == "__main__":
    main()
