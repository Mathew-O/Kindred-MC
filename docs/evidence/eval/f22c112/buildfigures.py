#!/usr/bin/env python3
"""F-22c112's build figures from a GameTest console, as pre-stated to the lead.

Per build (a player's "<name>: building <blueprint>" line, to its end):
- given up: its status ends "built but for", "stopped, ... steps failed one after another", or "the rest are blocks it
  cannot place yet";
- completed: its status reaches "<blueprint>: built, ";
- time per completed build: seconds from the "building" line to the first "built, " line;
- pick-up walks: the player's "native_goto|native_wait <STATE> [Pick up ...]" lines while the build runs;
- stuck on build steps: the rescue lines' "N ticks after its last progress" for the player while the build runs, summed
  (each rescue spell's largest);
- markers: "not counted against it (n of 3) (F-22c112)" lines.
Also: the doorway-plank test's failures ("withadoorwayplank" among the failed tests).
"""
import json
import re
import sys

STAMP = re.compile(r"^\[(\d\d):(\d\d):(\d\d)\]")
START = re.compile(r"\(Minecraft\) (\w+): building (.+?) \(by ")
STATUS = re.compile(r"\(Kindred\) (\w+) build: (.+)$")
PICK = re.compile(r"\(Kindred\) (\w+) native_(?:goto|wait) [A-Z]+ \[Pick up ")
RESCUE = re.compile(r"\(Kindred\) Rescue (\w+): .*?(\d+) ticks after its last progress")
MARKER = re.compile(r"not counted against it \(\d of 3\) \(F-22c112\)")
GIVEN_UP = ("built but for", "steps failed one after another", "cannot place yet")


def seconds(line):
    m = STAMP.match(line)
    return int(m.group(1)) * 3600 + int(m.group(2)) * 60 + int(m.group(3)) if m else None


def read(path):
    builds, open_, markers = [], {}, 0
    failed_doorway = 0
    lines = open(path, errors="replace").read().splitlines()
    failed = False
    for line in lines:
        t = seconds(line)
        if MARKER.search(line):
            markers += 1
        if "required tests failed" in line:
            failed = True
        if failed and "withadoorwayplank" in line:
            failed_doorway += 1
        m = START.search(line)
        if m:
            name, blueprint = m.group(1), m.group(2)
            if name in open_:
                builds.append(open_.pop(name))
            open_[name] = {"player": name, "blueprint": blueprint, "start": t, "end": None, "outcome": "unended", "picks": 0, "stuck": 0}
            continue
        m = STATUS.search(line)
        if m and m.group(1) in open_:
            b, text = open_[m.group(1)], m.group(2)
            if text.startswith(b["blueprint"] + ": built, "):
                b["outcome"], b["end"] = "completed", t
                builds.append(open_.pop(m.group(1)))
            elif any(g in text for g in GIVEN_UP):
                b["outcome"], b["end"] = "given up", t
                builds.append(open_.pop(m.group(1)))
            continue
        m = PICK.search(line)
        if m and m.group(1) in open_:
            open_[m.group(1)]["picks"] += 1
            continue
        m = RESCUE.search(line)
        if m and m.group(1) in open_:
            open_[m.group(1)]["stuck"] = max(open_[m.group(1)]["stuck"], int(m.group(2)))
    builds.extend(open_.values())
    done = [b for b in builds if b["outcome"] == "completed" and b["start"] is not None and b["end"] is not None]
    times = sorted((b["end"] - b["start"]) % 86400 for b in done)
    return {
        "builds": len(builds),
        "completed": len(done),
        "givenUp": sum(b["outcome"] == "given up" for b in builds),
        "unended": sum(b["outcome"] == "unended" for b in builds),
        "pickWalks": sum(b["picks"] for b in builds),
        "markers": markers,
        "stuckTicks": sum(b["stuck"] for b in builds),
        "secondsPerCompleted": round(sum(times) / len(times), 1) if times else None,
        "medianSeconds": times[len(times) // 2] if times else None,
        "doorwayFailed": failed_doorway,
    }


if __name__ == "__main__":
    for p in sys.argv[1:]:
        print(p, json.dumps(read(p)))
