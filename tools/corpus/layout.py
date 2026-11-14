"""Where a corpus run sets its scenes, and how near each scene's player starts to another scene (F-27a, #235).

1.21.1's TestStructurePlacer (Fabric's TestServer makes it with 8 a row, never clearing a batch) sets each test 5 blocks
past the last in x, and after 8 starts a row 6 blocks past the z extent of everything placed so far. CorpusGameTests
orders scenes by world key, then id, and puts a spacer test, SPACER_WIDTH wide and SPACER_DEPTH deep, after each one. This module
replays that placement from the scene files, or reads the places a run logged ("Corpus scene <id> placed at x, y, z"),
and gives each scene the distance from its player's start to the nearest other scene's box.

    python3 tools/corpus/layout.py                 # simulated, with the spacers CorpusGameTests sets
    python3 tools/corpus/layout.py --no-spacers    # simulated, as before the spacers
    python3 tools/corpus/layout.py --log run.log   # as a run placed them
"""
import argparse
import json
import re
import statistics
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCENES = ROOT / "companion-server-adapter/src/corpus/resources/data/kindred_corpus/gametest/corpus"
SOURCE = ROOT / "companion-server-adapter/src/corpus/java/dev/kindred/fabric/CorpusGameTests.java"
PER_ROW, GAP_X, GAP_Z = 8, 5, 6
STAGED = (80, 80)  # a staged fixture's ground, at least FixtureGround.FIELD


def spacer_size(source=SOURCE):
    """The spacer's (width, depth) as CorpusGameTests sets them, or None when it sets none after each scene."""
    text = source.read_text(encoding="utf-8")
    width = re.search(r"SPACER_WIDTH\s*=\s*(\d+)", text)
    depth = re.search(r"SPACER_DEPTH\s*=\s*(\d+)", text)
    return (int(width.group(1)), int(depth.group(1))) if width and depth and "tests.add(spacer(" in text else None


def world_key(scene):
    """CorpusGameTests.worldKey: difficulty, thunder, rain, time of day (day or night is batches.py's clock rule)."""
    if "timeOfDay" not in scene:
        return "~"
    time = scene["timeOfDay"] % 24000
    flag = lambda b: "true" if b else "false"
    return "%s/%s/%s/%05d" % (scene.get("difficulty", ""), flag(scene.get("thundering")), flag(scene.get("raining")), time)


def load(scenes=SCENES):
    return {p.stem: json.loads(p.read_text(encoding="utf-8")) for p in sorted(scenes.glob("*.json"))}


def simulate(scenes, spacer):
    """Each scene's box as (x, z, width, depth), placed in CorpusGameTests' order with a spacer after each."""
    order = sorted(scenes, key=lambda i: (world_key(scenes[i]), i))
    x = z = in_row = 0
    union = [0, 0, 0, 0]
    boxes = {}

    def place(width, depth):
        nonlocal x, z, in_row
        at = (x, z, width, depth)
        union[:] = [min(union[0], x), min(union[1], z), max(union[2], x + width), max(union[3], z + depth)]
        x += width + GAP_X
        in_row += 1
        if in_row >= PER_ROW:
            in_row, x = 0, 0
            z += (union[3] - union[1]) + GAP_Z
        return at

    for sid in order:
        size = scenes[sid].get("size")
        boxes[sid] = place(size[0], size[2]) if size else place(*STAGED)
        if spacer:
            place(*spacer)
    return boxes


def from_log(log, scenes):
    """Each replay's box as a run logged it."""
    pattern = re.compile(r"Corpus scene (\S+) placed at (-?\d+), (-?\d+), (-?\d+)")
    boxes = {}
    for line in log.read_text(encoding="utf-8", errors="replace").splitlines():
        m = pattern.search(line)
        if m and m.group(1) in scenes and "size" in scenes[m.group(1)]:
            size = scenes[m.group(1)]["size"]
            boxes[m.group(1)] = (int(m.group(2)), int(m.group(4)), size[0], size[2])
    return boxes


def nearest(scenes, boxes):
    """For each replay with a recorded start: the distance across the ground to the nearest other scene's box."""
    out = {}
    for sid, (bx, bz, _, _) in boxes.items():
        at = scenes[sid].get("at")
        if not at:
            continue
        px, pz = bx + at[0], bz + at[2]
        best = float("inf")
        for other, (ox, oz, w, d) in boxes.items():
            if other == sid:
                continue
            dx = max(ox - px, 0, px - (ox + w))
            dz = max(oz - pz, 0, pz - (oz + d))
            best = min(best, (dx * dx + dz * dz) ** 0.5)
        out[sid] = best
    return out


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--no-spacers", action="store_true")
    parser.add_argument("--log", type=Path, help="a corpus run's log, for the places the run set")
    args = parser.parse_args()
    scenes = load()
    boxes = from_log(args.log, scenes) if args.log else simulate(scenes, None if args.no_spacers else spacer_size())
    near = nearest(scenes, boxes)
    if not near:
        sys.exit("no scene starts found")
    values = sorted(near.values())
    print(f"{len(values)} scenes; nearest other box from the start: min {values[0]:.1f}, median {statistics.median(values):.1f}, "
          f"max {values[-1]:.1f}")
    for limit in (32, 96):
        print(f"  with another scene's box within {limit}: {sum(1 for v in values if v <= limit)}")


if __name__ == "__main__":
    main()
