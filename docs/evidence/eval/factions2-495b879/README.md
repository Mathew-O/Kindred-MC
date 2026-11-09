# factions2-495b879 — the Factions scenario on Session A's walk fix, ten seeds

Run by the lead session for Session A (M-06i), 8 to 9 November 2026, in a 4-core cloud container. Build `495b879`
(claude/m06i-factions-1108), `tools/eval/run.py --parallel 2`, scenario `goal=none; mode=factions; ticks=288000`, seeds
101 to 110. Earned: empty inventories, edits on under the roaming policy, `keep=none`. Strategist off; no model calls.

[summary.md](summary.md) is `score.py`'s output; `summary.json`, `build.json` and each seed's `eval-result.json` and
`run.json` are beside it. No logs or worlds.

## What it shows

- The audit: all ten seeds audited, three factions each, no rule broken. Claims 0 to 10 a seed (none on 110), no
  captures, no sales, no kills.
- The players did not progress: the team's furthest stage is "none" on every seed, not even a wooden tool in four game
  hours. Deaths a game hour: median 10.25, 3.5 to 26.5, all to mobs (zombies 234 of 446).
- Load for three players: mean tick 4.49 to 12.19 ms, worst tick 512 to 1,903 ms, heap at most 833 to 1,602 MB.

## What else shared the cores (for the load table)

- From 23:07 to 23:32 an orphaned server from a stopped run (`factions1-99a710a`, seed 102) ran beside seeds 101 and 102.
  It used over a core. Seed 102's mean tick, 12.19 ms, is the run's highest.
- From 23:33 to 01:13 the lead's GameTest benches ran at the lowest priority (`nice 19`), one test server at a time:
  batch 24's fixture, class run and build, the pit-climb fixture's class runs, and an obsidian repro. They overlapped
  the end of seeds 101 and 102, seeds 103 to 106, and the first minutes of 107 and 108.
- Seeds 109 and 110 ran with nothing else on the cores.
