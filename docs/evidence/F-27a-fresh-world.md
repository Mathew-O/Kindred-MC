# F-27a — Every corpus run from an empty world

Date: 2026-11-12
Agent / branch: claude/corpus-fresh-world-1111
Status: in progress (part of F-27a; linked from F-27a.md once merged)

`runCorpus` ran in `companion-server-adapter/build/corpus` and never cleared it. So each checkout's corpus world, and its `kindred/` folder with the players' kept encounters, grew with every run.

## Why
**REFACTOR's counts,** for scene `e40a13-fe2ba3b-505-sol-18180`. Code is #216's 8941ef0 unless noted.

| Tree | Arm | Passed |
|---|---|---|
| wt-12, long-used: corpus world 1.8 GB, 1,429 encounters folders | branch | 2/5, 4/6, and its gate's confirm 1/3 |
| wt-e70main4: 221 MB, 22 encounters folders | branch | 5/6 |
| wt-e70main4 | main b7027be | 5/5, 5/6, 5/6 |
| wt-e70main4 | nights-off ablation of the branch | 3/6, then 5/6 |

- **Pooled in wt-e70main4:** main 15/17, branch 5/6.
- **Read-back:** REFACTOR found no read-back of the encounters or `kindred-replay-*` folders.
- **Suspected mechanism:** chunk IO and tick timing in a grown world. This is a lead, not proven.

**A's interim finding,** for item 2's gate on `f29e-on-1394473968-echo-28457`:
- The gate's confirm-branch ran in its own tree, which had grown to 491 MB after the 3 full runs.
- Its confirm-main ran in a fresh `build/corpus-gate-main` tree of about 25 MB.
- Interleaved before the clean-up: in the grown tree it failed 2 of 3, and in the fresh tree it passed 3 of 3. That matches the gate's verdict.

**Why this biases every gate:** main's side always ran fresh, and the branch's side always ran in a grown world. A's 20-a-side count from fresh worlds is still pending, and it goes into the next PR's evidence (the e40a20 scene-only addition). The fix stands without it: a fresh world for every run is cleaner whatever the size of the effect.

**#215's baseline:** its six runs (811b0f7) were made in one checkout whose corpus folder was new at 14:49 on 11 November. The first run started from an empty world, and each later one played in the world the earlier runs had grown (977 MB at the end).

## Change
- **`runCorpus`:** a `doFirst` in `companion-server-adapter/build.gradle` deletes `build/corpus/world` and `build/corpus/kindred` before every run, gate or not.
- **`tools/corpus/gate.sh`:**
  - **Before every run:** it empties the checkout's corpus world, covering the full runs, the branch's confirm and main's.
  - **Reporting:** it prints the MB each held, and the report ends with that list.
  - **`--empty-world-only`:** does only that, for the test.
- **`gate.sh` and report.py crashes:** if `report.py` fails before writing its report, the gate now exits 2. Before, the crash also left no `flagged.txt`, and the gate went on to pass with nothing flagged.

## Validation
- **`python3 -m unittest discover -s tools/corpus -v`:** 7 pass.
  - **The 3 new ones** run gate.sh with a stand-in `./gradlew` that records whether a world was there when each run started, and leaves one behind:
    - `--empty-world-only` empties a 3 MB world and says so.
    - In a 2-run gate whose scene is flagged, all 8 runs start empty: 2 full, 3 confirm-branch and 3 confirm-main. This holds even though the checkout came in with a grown world and every run left one. The report lists each size.
    - A results file that report.py cannot read gives exit 2.
  - **Against the old gate.sh,** the 3 new tests fail.
- **The `doFirst` on a real run:** I put marker files in `build/corpus/world` and `kindred/`, then ran `KINDRED_CORPUS_ONLY=e40a13-fe2ba3b-505-sol-18180 ./gradlew :companion-server-adapter:runCorpus`. Both markers were gone, and the world afterwards was 13 MB, from that run alone. The scene failed that one run, which is no count either way.
- **Other checks:** `bash -n tools/corpus/gate.sh` passes, and `support.py tracker` reports out_of_order [].
