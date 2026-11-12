# F-27a — e40a20's replays and ledge-505's staged scene join the corpus

Date: 2026-11-12
Agent / branch: claude/corpus-e40a20-1111
Status: in progress (part of F-27a; linked from F-27a.md once merged)

A scene-only addition: 90 scenes and their baseline entries, with no code change.

- **What is added:**
  - e40a20-79a9aad's 89 memory-carrying replays, 70 stuck spells and 19 deaths, imported from `claude/corpus-data-e40a20-1111`.
  - CI GREEN's staged scene `f-25c4cp-ledge-505`. It is a contract fixture run as a scene: staged, not earned.
  - `e40a20-79a9aad-20261030-echo-74065` came in with #225. It was not in the baseline until now and is counted here.
- **Six runs:** only these 90 ran (`KINDRED_CORPUS_ONLY`), alone on one machine with `runCorpus --max-workers=2`.
  - **Code:** 9aa0c27, which is main a0bb355 with #228's harness at 6143139. #228 merged as 19092ae with that harness unchanged.
  - **Merges since:** main has since merged #226, #206, #229 and #223. The runs predate them, and the baseline's notes say so.
  - **Empty worlds:** every run began from an empty corpus world. `runCorpus` empties it first (#228). Each run left 64–66 MB behind, and the next run deleted it before starting. The first started from a 412 MB tree left by earlier work.
- **Results:** 45 pass, 35 fail and 10 are intermittent.
  - Failures per run: 39, 40, 42, 38, 41, 39.
  - Replay players still acting after their batch: 0 in every run.

| Kind | Pass | Intermittent | Fail | Scenes |
| --- | --- | --- | --- | --- |
| stuck (spell) | 34 | 6 | 30 | 70 |
| death | 10 | 4 | 5 | 19 |
| fixture | 1 | 0 | 0 | 1 |

- **Baseline:** `tools/corpus/baseline.json` keeps #215's 332 scenes on 811b0f7 as they were and gains these 90, 422 in all. A `notes` field records which runs made which entries. `report.py` reads only `sha`, `runs` and `scenes`.
  - **#215's 332 predate the fresh-world harness.** Its six 811b0f7 runs were made in one checkout whose corpus folder was new at 14:49 on 11 November. The first run started empty, and each later one played in the world the earlier runs had grown, 977 MB by the end. The notes say so. Whether to re-baseline those 332 from empty worlds is decided after this PR.
- **The 8675309 pocket:** the ten `e40a20-79a9aad-8675309-kindred-1xxxxx` spells (109060 to 141720) are among the failing 35. Each failed 6 of 6 here.
  - MODES's F-35k (#231) passes them 8, 8 and 7 of 10 over three runs. Once #231 merges, the gate will list them as newly passing.
  - `-138100` lies wholly at night, and the player rightly stays sheltered through it, so it cannot pass by the stuck-spell rule.

## Why the empty world matters for these numbers: A's 20-a-side
Scene `f29e-on-1394473968-echo-28457`, run alone and interleaved, with both sides starting from deleted `build/corpus` folders. The worlds grew to 183 MB and 179 MB over the 20 runs.
- **Branch 89bc1e27:** failed 3 of 20.
- **Main b7027be4:** failed 4 of 20, the same death each time ("Echo was shot by Skeleton").
- **One-sided Fisher test (is the branch worse?):** p = 0.80.
- **The gate's own confirm,** with the branch in a 491 MB grown tree and main in a fresh one: the branch failed 2 of 3 and main 0 of 3.
- **Reading:** from fresh worlds this is a roughly 17% intermittent on both sides. The gate's confirmed flag came from the grown-against-fresh imbalance, which #228 removes. These six runs were made the way every gate run is made now.

## The failing and intermittent scenes
**Failing (35):**
- `e40a20-79a9aad-101-echo-75080` (stuck spell, passed 0 of 6)
- `e40a20-79a9aad-101-sol-26800` (stuck spell, passed 0 of 6)
- `e40a20-79a9aad-202-echo-116900` (stuck spell, passed 0 of 6)
- `e40a20-79a9aad-202-echo-129300` (stuck spell, passed 0 of 6)
- `e40a20-79a9aad-202-echo-134060` (stuck spell, passed 0 of 6)
- `e40a20-79a9aad-202-echo-138700` (stuck spell, passed 0 of 6)
- `e40a20-79a9aad-202-echo-143360` (stuck spell, passed 0 of 6)
- `e40a20-79a9aad-202-kindred-108666` (death, passed 0 of 6)
- `e40a20-79a9aad-202-kindred-57987` (death, passed 0 of 6)
- `e40a20-79a9aad-202-sol-25260` (stuck spell, passed 0 of 6)
- `e40a20-79a9aad-20261030-kindred-126860` (stuck spell, passed 0 of 6)
- `e40a20-79a9aad-20261030-kindred-77460` (stuck spell, passed 0 of 6)
- `e40a20-79a9aad-20261030-kindred-81060` (stuck spell, passed 0 of 6)
- `e40a20-79a9aad-20261030-sol-29680` (stuck spell, passed 0 of 6)
- `e40a20-79a9aad-31102026-echo-82240` (stuck spell, passed 0 of 6)
- `e40a20-79a9aad-404-echo-13600` (stuck spell, passed 0 of 6)
- `e40a20-79a9aad-404-kindred-135240` (stuck spell, passed 0 of 6)
- `e40a20-79a9aad-404-kindred-54260` (stuck spell, passed 0 of 6)
- `e40a20-79a9aad-505-echo-29000` (stuck spell, passed 0 of 6)
- `e40a20-79a9aad-505-kindred-60851` (death, passed 0 of 6)
- `e40a20-79a9aad-707-echo-80020` (stuck spell, passed 0 of 6)
- `e40a20-79a9aad-707-sol-28380` (stuck spell, passed 0 of 6)
- `e40a20-79a9aad-8675309-kindred-109060` (stuck spell, passed 0 of 6)
- `e40a20-79a9aad-8675309-kindred-112700` (stuck spell, passed 0 of 6)
- `e40a20-79a9aad-8675309-kindred-116320` (stuck spell, passed 0 of 6)
- `e40a20-79a9aad-8675309-kindred-119960` (stuck spell, passed 0 of 6)
- `e40a20-79a9aad-8675309-kindred-123600` (stuck spell, passed 0 of 6)
- `e40a20-79a9aad-8675309-kindred-127220` (stuck spell, passed 0 of 6)
- `e40a20-79a9aad-8675309-kindred-130860` (stuck spell, passed 0 of 6)
- `e40a20-79a9aad-8675309-kindred-134460` (stuck spell, passed 0 of 6)
- `e40a20-79a9aad-8675309-kindred-138100` (stuck spell, passed 0 of 6)
- `e40a20-79a9aad-8675309-kindred-141720` (stuck spell, passed 0 of 6)
- `e40a20-79a9aad-8675309-kindred-28514` (death, passed 0 of 6)
- `e40a20-79a9aad-8675309-kindred-34442` (death, passed 0 of 6)
- `e40a20-79a9aad-8675309-kindred-79780` (stuck spell, passed 0 of 6)

**Intermittent (10):**
- `e40a20-79a9aad-202-echo-14987` (death, passed 2 of 6)
- `e40a20-79a9aad-202-sol-100125` (death, passed 5 of 6)
- `e40a20-79a9aad-31102026-echo-102580` (stuck spell, passed 1 of 6)
- `e40a20-79a9aad-31102026-sol-97112` (death, passed 2 of 6)
- `e40a20-79a9aad-404-kindred-59140` (stuck spell, passed 3 of 6)
- `e40a20-79a9aad-505-kindred-8833` (death, passed 4 of 6)
- `e40a20-79a9aad-606-echo-46420` (stuck spell, passed 5 of 6)
- `e40a20-79a9aad-707-kindred-42100` (stuck spell, passed 2 of 6)
- `e40a20-79a9aad-8675309-kindred-105140` (stuck spell, passed 3 of 6)
- `e40a20-79a9aad-8675309-kindred-90640` (stuck spell, passed 4 of 6)

