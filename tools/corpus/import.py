#!/usr/bin/env python3
"""Turn an evaluation run's replays into failure-corpus scenes (F-27a).

usage: import.py RUN_FOLDER [RUN_FOLDER ...] [--out DIR] [--only ID ...]

A run folder is `tools/eval/run.py`'s output (or its filing under docs/evidence/eval): seed folders, each with
`replays/<world>/<player>-<tick>/replay.json` and `replay.snbt`, and the run's `build.json`. Each replay becomes
`<out>/corpus/<id>.json`: the replay's state with a `corpus` object saying where it came from (`replay`, the folder's
path in the repository) and how it is staged. The structure is not copied: the build takes it from that folder's
`replay.snbt` (the adapter's `processCorpusResources`), so a replay filed in the evidence is not kept twice. The id is
`<run>-<seed>-<player>-<tick>`, lower case.

Staged, as every replay is: the ground is the structure captured round the player when it died (or was stuck), the
player is made from its recorded state (vitals, effects, inventory, position, time of day, weather). Nothing is given
beyond what the recorded player held.
"""
import argparse
import gzip
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUT = ROOT / "companion-server-adapter/src/corpus/resources/data/kindred_corpus/gametest"


def classify(state):
    """A coarse class of the failure, for the report: what killed it, or what it was stuck at."""
    kind = state.get("kind", "death")
    cause = (state.get("cause") or "").lower()
    task = (state.get("task") or "") + " " + (state.get("stage") or "")
    if kind == "stuck":
        if "water" in task.lower():
            return "stuck: water"
        return "stuck: " + (task.split(" - ")[-1].split(":")[0].strip().lower() or "other")
    for word in ("drowned", "lava", "fell", "fire", "flames", "burn", "suffocat", "starv"):
        if word in cause:
            return "death: " + {"flames": "fire", "burn": "fire", "suffocat": "suffocation", "starv": "starvation"}.get(word, word)
    m = re.search(r"by ([A-Z][a-z]+(?: [A-Z][a-z]+)?)", state.get("cause") or "")
    return "death: " + (m.group(1).lower() if m else "other")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("runs", nargs="+", type=Path)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--only", nargs="*", default=None, help="scene ids to import, the rest left out")
    parser.add_argument("--keep-structures", action="store_true",
                        help="keep each structure gzipped in companion-server-adapter/src/corpus/structures (for replays not "
                             "filed on main, such as the iterations' data branch)")
    parser.add_argument("--source", default="", help="where the run folders came from, recorded in each scene (a branch and commit)")
    args = parser.parse_args()
    (args.out / "corpus").mkdir(parents=True, exist_ok=True)
    count = 0
    for run in args.runs:
        build = {}
        if (run / "build.json").is_file():
            build = json.loads((run / "build.json").read_text(encoding="utf-8"))
        for replay in sorted(run.glob("*/replays/*/*/replay.json")):
            folder = replay.parent
            seed = folder.parents[2].name
            snbt = folder / "replay.snbt"
            if not snbt.is_file():
                print("no structure beside", replay, file=sys.stderr)
                continue
            state = json.loads(replay.read_text(encoding="utf-8"))
            sid = re.sub(r"[^a-z0-9_.-]", "-", f"{run.name}-{seed}-{folder.name}".lower())
            if args.only is not None and sid not in args.only:
                continue
            state["corpus"] = {
                "run": run.name,
                "seed": seed,
                "build": build.get("ref") or build.get("commit") or build.get("sha") or run.name.rsplit("-", 1)[-1],
                "replay": str(folder.resolve().relative_to(ROOT)) if not args.keep_structures else "",
                "source": (args.source + ":" + "/".join(folder.parts[-5:])) if args.source else str(folder),
                "class": classify(state),
                "staged": "the ground captured round the player at the time (the structure); the player made from its "
                          "recorded state; nothing given beyond what it held",
            }
            (args.out / "corpus" / f"{sid}.json").write_text(json.dumps(state, indent=1) + "\n", encoding="utf-8")
            if args.keep_structures:
                kept = ROOT / "companion-server-adapter/src/corpus/structures"
                kept.mkdir(parents=True, exist_ok=True)
                with gzip.open(kept / f"{sid}.snbt.gz", "wb", compresslevel=9) as out:
                    out.write(snbt.read_bytes())
            count += 1
    print(f"{count} scenes written to {args.out}")


if __name__ == "__main__":
    main()
