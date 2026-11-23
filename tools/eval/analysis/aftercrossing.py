#!/usr/bin/env python3
"""F-25b6: per body, what happened after its first portal crossing, from an eval batch's consoles.

usage: aftercrossing.py BATCH_DIR   (BATCH_DIR holds <seed>/console.txt or console.txt.gz, and eval-result.json where written)

For each player whose console has "<name> has made the advancement [We Need to Go Deeper]" (its first crossing), it
prints the crossing's tick (the eval result's nether_entered stage, or, with none, the tick read between the console's
"thinking at tick" marks either side of it), the ticks and game minutes from it to the run's end, and, from the
player's own lines after it: every "health X" reading, the drops between consecutive readings, the fights started and
how they ended, deaths, and RETURN goal lines ("Back through the portal"). The same readings before the crossing are
given as the control: a body that can be hurt shows drops there.

Consoles only; it reads no world and infers nothing unseen.
"""
import gzip
import json
import re
import sys
from pathlib import Path

DEEPER = re.compile(r"INFO\]: (\S+) has made the advancement \[We Need to Go Deeper\]")
MARK = re.compile(r"^\[(\d\d):(\d\d):(\d\d)\].*thinking at tick (\d+)")
STAMP = re.compile(r"^\[(\d\d):(\d\d):(\d\d)\]")


def lines(seed_dir: Path):
    for name in ("console.txt.gz", "console.txt"):
        p = seed_dir / name
        if p.exists():
            opener = gzip.open if name.endswith(".gz") else open
            with opener(p, "rt", errors="replace") as f:
                return f.read().split("\n")
    return []


def secs(m):
    return int(m.group(1)) * 3600 + int(m.group(2)) * 60 + int(m.group(3))


def tick_between(console, i0):
    """The crossing's tick, read linearly between the nearest "thinking at tick" marks either side of line i0."""
    before = after = None
    for i, l in enumerate(console):
        m = MARK.match(l)
        if not m:
            continue
        if i <= i0:
            before = (secs(m), int(m.group(4)))
        elif after is None:
            after = (secs(m), int(m.group(4)))
    s = STAMP.match(console[i0])
    if not (before and after and s):
        return None
    t = secs(s)
    return round(before[1] + (after[1] - before[1]) * (t - before[0]) / max(1, after[0] - before[0]))


def readings(console, name):
    out = []
    for l in console:
        if f"INFO]: {name} " not in l:
            continue
        m = re.search(r"\bhealth ([0-9.]+)", l)
        if m:
            out.append(float(m.group(1)))
    return out


def drops(hs):
    return sum(1 for a, b in zip(hs, hs[1:]) if b < a - 0.01)


def main(batch: Path):
    for seed_dir in sorted(p for p in batch.iterdir() if p.is_dir()):
        console = lines(seed_dir)
        if not console:
            continue
        result = None
        rp = seed_dir / "eval-result.json"
        if rp.exists():
            result = json.loads(rp.read_text())
        for i0, l in enumerate(console):
            m = DEEPER.search(l)
            if not m:
                continue
            name = m.group(1)
            after, before = console[i0:], console[:i0]
            entered, end, how = None, None, ""
            if result:
                entered = result["players"].get(name, {}).get("stages", {}).get("nether_entered")
                end = result.get("ticksRun")
                how = "eval result"
            if entered is None:
                entered = tick_between(console, i0)
                marks = [int(x.group(4)) for x in map(MARK.match, console) if x]
                end = end or (marks[-1] if marks else None)
                how = "read between the console's tick marks; the run's end is its last mark"
            span = None if entered is None or end is None else end - entered
            ha, hb = readings(after, name), readings(before, name)
            fights = [x.group(1) for x in (re.search(rf"INFO\]: {name} fight starts with ([a-z_ ]+):", y) for y in after) if x]
            ends = [x.group(1) for x in (re.search(rf"INFO\]: {name} fight ends ([A-Z]+)", y) for y in after) if x]
            deaths = sum(1 for y in after if re.search(rf"INFO\]: {name} died at", y))
            ret = sum(1 for y in after if f"{name} goal" in y and "Back through the portal" in y)
            print(f"{seed_dir.name} {name}: crossed at tick {entered} ({how}); {span} ticks"
                  f" ({None if span is None else round(span / 1200, 1)} game minutes) to the end")
            print(f"  after: {len(ha)} health readings, min {min(ha) if ha else None}, {drops(ha)} drops;"
                  f" {len(fights)} fights started {dict(sorted({f: fights.count(f) for f in fights}.items()))},"
                  f" ended {dict(sorted({e: ends.count(e) for e in ends}.items()))}; {deaths} deaths; {ret} RETURN lines")
            print(f"  before: {len(hb)} health readings, min {min(hb) if hb else None}, {drops(hb)} drops")


if __name__ == "__main__":
    main(Path(sys.argv[1]))
