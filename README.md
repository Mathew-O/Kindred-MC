# Are runs of one build and seed deterministic? (RUNNER 2, 2026-11-12)

**Setup:**
- Build: main `5cfe873` (#219's merge). It was built once; runs 2 to 6 used the same jar (sha256 `676611ec39e2deef…`).
- Seed 505, `goal=nether; ticks=24000`, earned. All six runs are complete at 24,000 ticks.
- Arm 1: three runs one after another, each alone (14:50 to 15:04).
- Arm 2: three `run.py` processes at once (15:04 to 15:11).
- Script: `analysis/det.sh`. Timeline: `analysis/det-timeline.txt`.

**Comparison:** `analysis/detcmp.py`. Per player, it finds the first "goal at" or `native_*` line that differs between two runs. The tick is the console clock interpolated from "/tick sprint" (tick 0) to "24000 ticks run", accurate to about ±200 ticks. Every pair's output is in `analysis/det-cmp.txt`.

| Pair | First difference, about tick |
| --- | --- |
| arm 1: r1 / r2 | 105 (Kindred) |
| arm 1: r1 / r3 | 100 (Kindred) |
| arm 1: r2 / r3 | 100 (Kindred) |
| arm 2: r1 / r2 | 81 (Kindred) |
| arm 2: r1 / r3 | 220 (Kindred) |
| arm 2: r2 / r3 | 81 (Kindred) |

**Answer: no run is deterministic, alone or not.** Arm 1 diverges as early as arm 2: in the first few seconds of game time, at the first or second step of every player.

In every pair the players' lines agree through "Stock some food while animals are around - Approach pig". Then the "Hunt pig" step begins at a different spot: Kindred at -7 72 -7, -1 73 -3 or -3 73 -8. The player followed a pig that went a different way.

**The earliest differing subsystem is vanilla mob AI.** In Minecraft 1.21.1 (yarn `1.21.1+build.3`, read from the bytecode of the cached merged jar):
- `Entity`'s constructor sets `random = Random.create()`.
- `Random.create()` seeds from `RandomSeed.getSeed()`, which is a counter `AtomicLong` updated and then XORed with `System.nanoTime()`.

So every pig, zombie and other entity draws from a generator seeded by the wall clock. Their wandering, targeting and spawning choices differ run to run whatever the world seed. This is vanilla behaviour, not Kindred's.

The candidate 1 named in E-40's method note (`AttentionWorkScheduler`'s soft per-tick wall-clock limit) is not needed to explain the divergence. It may still add to it, but these runs cannot separate it: the vanilla source alone makes runs differ from the first animal.

**The outcomes spread within 24,000 ticks.** First iron pickaxe, by player:
- arm 1: Kindred 17340 | none | Kindred 15700
- arm 2: Sol 21800 | Sol 18840 | Kindred 22600 and Echo 23240

The six runs' consoles scan to 0 for keys and model IDs.
