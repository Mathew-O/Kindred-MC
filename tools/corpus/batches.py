"""The corpus's batches as CorpusGameTests makes them, and whether each keeps its scenes' own day and night (F-27a).

Every replay sets the world clock, weather and difficulty to its record as it starts, so the last scene of a batch to
start sets them for all. CorpusGameTests batches scenes by difficulty and weather, then by time of day, at most BATCH to
a batch, and only scenes whose nights fall on the same ticks of their runs under any batch-mate's time (clockFits,
within CLOCK_TOLERANCE ticks). This module reads those constants from CorpusGameTests.java and replays the batching from
the scene files, or reads a run's batches from its log, so tools/corpus/test_batches.py can hold the corpus to it.

    python3 tools/corpus/batches.py               # each batch, its times, and the worst clock difference in it
    python3 tools/corpus/batches.py --log run.log # check a run's batches (membership and order) against this
"""
import argparse
import re
import sys
from pathlib import Path

import layout

SOURCE = layout.SOURCE
DAY = 24000


def constants(source=SOURCE):
    """BATCH, the night intervals and CLOCK_TOLERANCE as CorpusGameTests sets them (None for any it does not set)."""
    text = source.read_text(encoding="utf-8")
    out = {}
    for name in ("BATCH", "NIGHT_CLEAR_FROM", "NIGHT_CLEAR_TO", "NIGHT_RAIN_FROM", "NIGHT_RAIN_TO", "CLOCK_TOLERANCE"):
        m = re.search(r"\b" + name + r"\s*=\s*(\d+)", text)
        out[name] = int(m.group(1)) if m else None
    out["clockFits"] = "clockFits(members)" in text
    return out


C = constants()


def weather(scene):
    rain = bool(scene.get("raining"))
    return rain, rain and bool(scene.get("thundering"))


def night_spans(rain, thunder):
    """The night of one day as [from, to) spans, by vanilla's isNight at full rain and thunder."""
    if thunder:
        return [(0, DAY)]
    a, b = (C["NIGHT_RAIN_FROM"], C["NIGHT_RAIN_TO"]) if rain else (C["NIGHT_CLEAR_FROM"], C["NIGHT_CLEAR_TO"])
    return [(a, b)]


def night_ticks(start, ticks, rain, thunder):
    """The offsets k in [0, ticks) at which time start + k is night, as sorted [from, to) spans."""
    out = []
    day0 = start - start % DAY
    for d in range(day0, start + ticks + DAY, DAY):
        for a, b in night_spans(rain, thunder):
            lo, hi = max(d + a - start, 0), min(d + b - start, ticks)
            if lo < hi:
                if out and out[-1][1] == lo:
                    out[-1] = (out[-1][0], hi)
                else:
                    out.append((lo, hi))
    return out


def covered(spans):
    return sum(b - a for a, b in spans)


def overlap(x, y):
    i = j = total = 0
    while i < len(x) and j < len(y):
        lo, hi = max(x[i][0], y[j][0]), min(x[i][1], y[j][1])
        total += max(0, hi - lo)
        if x[i][1] < y[j][1]:
            i += 1
        else:
            j += 1
    return total


def clock_difference(scene, clock):
    """CorpusGameTests.clockDifference: ticks of the scene's run night under clock and day under its own, or the reverse."""
    ticks = scene.get("ticks", 1200)
    rain, thunder = weather(scene)
    own, other = night_ticks(scene["timeOfDay"], ticks, rain, thunder), night_ticks(clock, ticks, rain, thunder)
    return covered(own) + covered(other) - 2 * overlap(own, other)


def clock_fits(members):
    timed = [s for s in members if "timeOfDay" in s]
    return all(clock_difference(s, o["timeOfDay"]) <= C["CLOCK_TOLERANCE"]
               for s in timed for o in timed if o is not s)


def world_key(scene):
    """CorpusGameTests.worldKey: difficulty, thunder, rain, time of day ("~" for a staged fixture)."""
    return layout.world_key(scene)


def batches(scenes):
    """CorpusGameTests' batches: lists of scene ids, in order."""
    order = sorted(scenes, key=lambda i: (world_key(scenes[i]), i))
    out, group, members = [], None, []
    for sid in order:
        key = world_key(scenes[sid])
        its = key[:max(0, key.rfind("/"))]
        members.append(scenes[sid])
        if not out or len(out[-1]) >= C["BATCH"] or its != group or (C["clockFits"] and not clock_fits(members)):
            out.append([])
            group, members = its, [scenes[sid]]
        out[-1].append(sid)
    return out


def worst(scenes, batch):
    """The largest clock difference in a batch, over every scene and every batch-mate's time."""
    timed = [scenes[i] for i in batch if "timeOfDay" in scenes[i]]
    return max([clock_difference(s, o["timeOfDay"]) for s in timed for o in timed if o is not s], default=0)


def from_log(log):
    """A run's batches as it logged them: {batch name: [scene ids in placement order]}."""
    got, cur = {}, None
    for line in log.read_text(encoding="utf-8", errors="replace").splitlines():
        m = re.search(r"Running test batch '(corpus_\d+):\d+'", line)
        if m:
            cur = m.group(1)
            got[cur] = []
            continue
        m = re.search(r"Corpus scene (\S+) placed at", line)
        if m and cur:
            got[cur].append(m.group(1))
    return got


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--log", type=Path, help="a corpus run's log, to check its batches against this")
    args = parser.parse_args()
    scenes = layout.load()
    made = batches(scenes)
    if args.log:
        got = from_log(args.log)
        wrong = [f"corpus_{n}" for n, b in enumerate(made) if all("timeOfDay" in scenes[i] for i in b) and got.get(f"corpus_{n}") != b]
        print(f"{len(made)} batches; the log's differ in {len(wrong)}" + (": " + ", ".join(wrong) if wrong else ""))
        sys.exit(1 if wrong else 0)
    for n, b in enumerate(made):
        times = [scenes[i]["timeOfDay"] % DAY for i in b if "timeOfDay" in scenes[i]]
        print(f"corpus_{n}: {len(b)} scenes, times {min(times, default='-')}-{max(times, default='-')}, worst clock difference {worst(scenes, b)}")
    print(f"{len(made)} batches")


if __name__ == "__main__":
    main()
