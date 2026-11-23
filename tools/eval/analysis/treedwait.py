#!/usr/bin/env python3
"""F-25c6f: per Nether body, how its time over there went, and its treed waits above hoglins, from an eval batch's consoles.

usage: treedwait.py BATCH_DIR   (BATCH_DIR holds <seed>/console.txt or console.txt.gz)

For each player with "<name> has made the advancement [We Need to Go Deeper]", from that line to the console's end:
- the share of its own lines' wall time under each goal step (the step's trailing detail, such as "walk on" or a block
  to clear, folded in), the six largest; wall time between consecutive lines of its own, so it is a rough share;
- "Out of the hoglin's reach" steps: pillars, waits ("wait it out", 2 seconds, 40 ticks each) and strikes from above;
- the runs of five or more waits in a row (no other line of its own between), the longest first, and the run the
  console ends in, if any;
- hazard spells (the F-25c6d line) with their trigger and what they saw, urgent-retreat cancels and retreats run.

Consoles only; nothing here reads a world or infers what the console does not say (the wait line names no hoglin, its
distance or its target).
"""
import gzip
import re
import sys
from pathlib import Path

DEEPER = re.compile(r"INFO\]: (\S+) has made the advancement \[We Need to Go Deeper\]")
STAMP = re.compile(r"^\[(\d\d):(\d\d):(\d\d)\]")
WAIT = "native_wait COMPLETED [Out of the hoglin's reach: wait it out]"
DETAIL = re.compile(r": (walk on|advance|clear .*|step down|step up|bridge \d+|stairs up \d+|pillar up|wait it out)$")


def lines(seed_dir: Path):
    for name in ("console.txt.gz", "console.txt"):
        p = seed_dir / name
        if p.exists():
            opener = gzip.open if name.endswith(".gz") else open
            with opener(p, "rt", errors="replace") as f:
                return f.read().split("\n")
    return []


def secs(line):
    m = STAMP.match(line)
    return int(m.group(1)) * 3600 + int(m.group(2)) * 60 + int(m.group(3)) if m else None


def report(seed, name, own):
    spent, cur, last = {}, None, None
    for l in own:
        t = secs(l)
        if t is None:
            continue
        if last is not None and cur:
            spent[cur] = spent.get(cur, 0) + (t - last)
        m = re.search(r"goal at [-0-9]+ [-0-9]+ [-0-9]+: (.*)", l)
        if m:
            cur = DETAIL.sub("", m.group(1))
        last = t
    total = sum(spent.values()) or 1
    print(f"{seed} {name}: {total} wall seconds of its own lines after the crossing")
    for k, v in sorted(spent.items(), key=lambda x: -x[1])[:6]:
        print(f"  {round(100 * v / total)}% {k}")
    waits = [l for l in own if WAIT in l]
    pillars = sum(1 for l in own if "native_pillar COMPLETED [Out of the hoglin's reach" in l)
    strikes = sum(1 for l in own if "strike it from above" in l and "goal at" in l)  # goal lines: one a strike decided
    print(f"  hoglin: {pillars} pillars, {len(waits)} waits (about {len(waits) * 40} ticks, "
          f"{round(len(waits) * 40 / 1200, 1)} game minutes), {strikes} strikes from above")
    runs, run = [], []
    for l in own:
        if " chronicle: " in l:
            continue  # a page written, not a step: it does not break a run
        if WAIT in l or ("goal at" in l and "Out of the hoglin's reach: wait it out" in l):
            if WAIT in l:
                run.append(l[1:9])
        else:
            if len(run) >= 5:
                runs.append((run[0], run[-1], len(run)))
            run = []
    ends_in = run[:] if len(run) >= 5 else None
    if ends_in:
        runs.append((run[0], run[-1], len(run)))
    runs.sort(key=lambda r: -r[2])
    print(f"  runs of 5+ waits: {len(runs)}; longest {runs[:5]}")
    print(f"  the console ends in a run of waits: {'yes, ' + str(len(ends_in)) + ' from ' + ends_in[0] if ends_in else 'no'}")
    seen = {}
    for l in own:
        m = re.search(r"hazard spell at ([-0-9]+, [-0-9]+, [-0-9]+) in \S+: (trigger [^;]*); saw ([^;]*); (feet [^;]*)", l)
        if m:
            key = f"at {m.group(1)}: {m.group(2)}; saw {m.group(3)}; {m.group(4)}"
            seen.setdefault(key, []).append(l[1:9])
    lengths = [int(x.group(1)) for x in (re.search(r"hazard spell over at .* after (\d+) ticks", l) for l in own) if x]
    print(f"  hazard spells: {sum(len(v) for v in seen.values())}; over after (ticks): longest {sorted(lengths)[-3:]}, "
          f"{sum(1 for n in lengths if n <= 10)} of {len(lengths)} within 10")
    for k, v in sorted(seen.items(), key=lambda x: -len(x[1]))[:5]:
        print(f"    {len(v)}x {k} (first {v[0]}, last {v[-1]})")
    urgent = sum(1 for l in own if "urgent retreat required" in l)
    retreats = sum(1 for l in own if "native_retreat" in l)
    print(f"  urgent-retreat cancels {urgent}; retreat lines {retreats}")


def main(batch: Path):
    for seed_dir in sorted(p for p in batch.iterdir() if p.is_dir()):
        console = lines(seed_dir)
        for i0, l in enumerate(console):
            m = DEEPER.search(l)
            if m:
                name = m.group(1)
                report(seed_dir.name, name, [x for x in console[i0:] if f"INFO]: {name} " in x])


if __name__ == "__main__":
    main(Path(sys.argv[1]))
