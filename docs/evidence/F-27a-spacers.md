# F-27a — spacer tests keep each corpus scene out of its neighbours' reach (#235)

Date: 2026-11-12
Agent / branch: claude/corpus-spacers-1112
Build: before runs on main d8fd87f; after runs on 5158191 (this branch before this evidence commit)
Status: in progress (part of F-27a; linked from F-27a.md once merged)

A harness change: the scenes themselves are unchanged. **The pass rates below are a harness shift, not a change in how the players play.** The baseline is not rewritten here. The 422-scene re-baseline from empty worlds on the isolated harness comes after this PR.

## What changes
- **Before:** 1.21.1's `TestStructurePlacer`, as Fabric's `TestServer` builds it, sets 8 tests a row, 5 blocks apart in x. It starts each new row 6 blocks past the z extent of everything placed so far, and never clears a batch. Corpus scenes are 33 × 17 × 33. So every replay's player started 21–22 blocks from a neighbour's box, within `WorldKnowledge`'s 32-block sweep (#235). Logs, ores and fence-top walk targets from the next scene could steer it.
- **After:** `CorpusGameTests` puts an empty spacer test (`kindred_corpus:spacer`, 90 × 1 × 130 of air, completing at once) after every scene. Two scenes in a row now stand 5 + 90 + 5 = 100 blocks apart wall to wall. The next row starts 130 + 6 − 33 = 103 blocks past a scene's far wall.
- **Logging:** each replay now logs `Corpus scene <id> placed at x, y, z`, so the real layout can be read back from a run.
- **Tools:** `tools/corpus/layout.py` simulates the placement from the scene files, or reads it back from a log. `tools/corpus/test_layout.py` holds the corpus to it in CI, alongside `test_gate.py`.
- **The report:** `report.py` takes its scene list from the scene files, so the `corpus.spacer.*` tests are not counted as scenes.

## The runs
- **Code:**
  - The before run used main **d8fd87fe3f7b6090a006a70625e3a4e24f744128**.
  - The after runs used the spacer branch **5158191a823f83cddd444065cb3e2be1b3de59c2**, which is spacer commit 668fc0c merged with that same main.
- **Scenes:** all **422** scenes on both sides, the same set: main d8fd87f's corpus, which the branch does not change.
- **How:** each run was a full `runCorpus --max-workers=2`, alone on a 4-core container. Each started from an empty corpus world, emptied by `runCorpus` itself (#228).
- **Time** is the run's wall clock. **Peak** is the corpus JVM's `VmHWM`, sampled through the run.
- **Fence-top** counts the `No route from … on Block{minecraft:barrier}` lines in the run's log. These are walk targets taken on a scene's barrier fence.
- **Results:** the per-run results are on the side branch `claude/spacer-runs-1112` (`summary.txt` and each run's xml). It has no PR and no CI. The logs are kept locally.

| Run | Code | Time | Peak memory | Passed of 422 | Pass rate | Fence-top |
| --- | --- | --- | --- | --- | --- | --- |
| before-1 | d8fd87f (main) | 40 min | 2623 MB | 273 | 64.7% | 59 |
| after-1 | 5158191 (spacers) | 43 min | 2958 MB | 298 | 70.6% | 16 |
| after-2 | 5158191 (spacers) | 40 min | 3643 MB | 316 | 74.9% | 26 |
| after-3 | 5158191 (spacers) | 41 min | 2856 MB | 312 | 73.9% | 18 |

- **Cost:** run time is unchanged within run-to-run spread, at 40 min before and 40–43 min after. Peak memory went from 2623 MB to 2856–3643 MB. The spacers add 422 empty tests. A row of 8 scenes now runs 1059 blocks in x instead of 299, and rows lie 136 blocks apart in z instead of 39. Every run had 14–15 GB of disk free at its end.
- **The three after runs together** class 268 scenes passing, 74 failing and 80 intermittent: 63.5% passing by `report.py`'s rule, where an intermittent scene never counts as a pass.

## The four checks
1. **The layout, simulated and as run.**
   - `layout.py` simulates the placement. Without spacers, all 420 replays with a recorded start are within 32 of another scene's box (min 21.0, median 21.4, max 22.0). With them, **0 of 420 are within 96** (min 116.0, median 116.4, max 117.0).
   - `layout.py --log` on each after run's log reads back the same layout: 0 within 32, 0 within 96, min 116.0, median 116.4 and max 117.0 in all three runs.
   - The before run has no `placed at` lines, because main does not log them, so its layout is the simulated one.
2. **`diag-factions303-079bdc2-303-sol-102920`**, which #235 found seeing 6 of a neighbour's logs against 2 of its own, passed in the before run and in all three after runs.
3. **Fence-top walk targets** fell from 59 in the before run to 16, 26 and 18 in the after runs.
   - CI GREEN's runs on #235's gate counted 33, 39 and 37 a run with #235's fix, and 85 before it. #235 does not touch where scenes are placed.
   - No other scene's box is within 96 of any start, so the remaining targets are most likely on each scene's own fence. The log lines were not traced to a fence.
4. **Pass rates over three full runs** are 70.6%, 74.9% and 73.9%, against 64.7% on main. This is a harness shift.

## What moved: the before run against the three after runs
These lists compare one before run against three after runs. One before run cannot class a scene as intermittent on main, so a scene listed here may already have varied there.
- **Failed before, passed 3 of 3 after:** 40 scenes, listed below.
- **Passed before, failed 0 of 3 after:** 7 scenes.
  - Four are not passing in the committed baseline: `e40a13-fe2ba3b-101-echo-5287`, `e40a14-80b7150-707-echo-51759`, `f29e-on-4h-8675309-sol-185291` and `factions-110-d5057a4-110-kindred-190720` are intermittent there.
  - Three fail there: `e40a14-80b7150-606-echo-71006`, `e40a20-79a9aad-505-echo-29000` and `f29e-on-4h-8675309-sol-222846`.
- **Passed before, intermittent after:** 38. **Failed before, intermittent after:** 42.

## The gate against the committed baseline
`report.py` was run on the three after runs against `tools/corpus/baseline.json`: 811b0f7's grown-world runs for #215's 332 scenes, and #233's for the other 90.
- **Newly passing:** 75 scenes.
- **Flagged:** 3 scenes passing in the baseline and failing in 2 or more of the 3 after runs.

| Scene | before-1 | after-1 | after-2 | after-3 | How it failed |
| --- | --- | --- | --- | --- | --- |
| `e40a20-79a9aad-101-sol-107500` | fail | fail | fail | fail | still in its stuck spell after 3600 ticks |
| `e40a20-79a9aad-31102026-echo-106260` | pass | pass | fail | fail | still in its stuck spell after 3600 ticks |
| `f29e-on-891567637-kindred-123297` | fail | fail | pass | fail | died again, slain by a zombie, as it did |

- Two of the three failed on main's before run too.
- **Confirmation:** as `gate.sh` does it by hand, each of the three ran alone (`KINDRED_CORPUS_ONLY`) three times on 5158191 and three times on main d8fd87f, each run from an empty world.
  - **Result:** `report.py --confirm-branch … --confirm-main …` confirms 0 and clears all 3. **Gate: passes.**
  - `e40a20-79a9aad-101-sol-107500`: the branch failed 3 of 3 and main passed 0 of 3. It is main's own failure.
  - `e40a20-79a9aad-31102026-echo-106260`: the branch failed 0 of 3 and main passed 3 of 3.
  - `f29e-on-891567637-kindred-123297`: the branch failed 1 of 3 and main passed 1 of 3.
- **Not checked:** the confirm runs are short and alone, unlike the full runs. Whether `-106260`'s two full-run failures come from playing among 421 others was not looked into.

## Failed in the before run, passed 3 of 3 after (40)
- `diag-factions303-079bdc2-303-echo-36120`
- `diag-factions303-079bdc2-303-echo-6460`
- `diag-factions303-079bdc2-303-kindred-107560`
- `diag-factions303-079bdc2-303-sol-99140`
- `diag-holepit-079bdc2-202-sol-91124`
- `e40a13-fe2ba3b-101-kindred-21933`
- `e40a13-fe2ba3b-505-sol-110664`
- `e40a13-fe2ba3b-505-sol-43879`
- `e40a13-fe2ba3b-707-sol-64680`
- `e40a14-80b7150-101-echo-75842`
- `e40a14-80b7150-101-sol-84338`
- `e40a14-80b7150-8675309-kindred-120887`
- `e40a15-dbf4e5c-505-kindred-5689`
- `e40a15-dbf4e5c-707-sol-10569`
- `e40a15-dbf4e5c-8675309-kindred-37265`
- `e40a16-922f4fd-202-echo-55002`
- `e40a16-922f4fd-505-kindred-77615`
- `e40a17-7bc5b42-303-kindred-121775`
- `e40a17-7bc5b42-8675309-kindred-16523`
- `e40a20-79a9aad-31102026-echo-102580`
- `e40a20-79a9aad-8675309-kindred-105140`
- `e40a20-79a9aad-8675309-kindred-134460`
- `e40c1-eed2dc5-8675309-echo-87200`
- `f29e-on-4h-202-sol-244548`
- `f29e-on-4h-31102026-kindred-216171`
- `f29e-on-4h-31102026-sol-216356`
- `f29e-on-707-echo-28809`
- `f29e-on-779795571-echo-78472`
- `f29e-on-779795571-kindred-47186`
- `factions-110-d5057a4-110-echo-240272`
- `factions-110-d5057a4-110-kindred-118253`
- `factions-110-d5057a4-110-kindred-250818`
- `factions-110-d5057a4-110-kindred-254029`
- `factions-110-d5057a4-110-sol-187246`
- `factions-110-d5057a4-110-sol-21363`
- `factions-e40a16seeds-b7b4bec-303-sol-90379`
- `factions-e40a16seeds-b7b4bec-404-kindred-143470`
- `factions-e40a16seeds-b7b4bec-707-kindred-21883`
- `factions-e40a16seeds-b7b4bec-707-kindred-90496`
- `factions-e40a16seeds-b7b4bec-707-sol-72346`
