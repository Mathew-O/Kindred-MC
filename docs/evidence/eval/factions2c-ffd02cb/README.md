# factions2c-ffd02cb: the Factions scenario with the Nether goal, three seeds

Run in the F-29 session's container for Session A (M-06i) on 9 November 2026, after `factions2b-ffd02cb`. Build
`ffd02cb` (claude/m06i-play-1108, #72). `tools/eval/run.py --parallel 2 --policy bases=true`, scenario
`goal=nether; mode=factions; ticks=288000`, seeds 101 to 103 (103 alone after 101 ended). Earned, strategist off.

[summary.md](summary.md) is that build's `score.py` output. The other files are as in `factions2b-ffd02cb`.

## What it shows

- **With a goal set, the same build plays the survival line:**
  - a wooden tool on 3 of 3 seeds, at 2.8 game minutes;
  - an iron pickaxe on 3 of 3, at 54.7 game minutes;
  - a lit portal on 1 of 3, at 179.5 game minutes.

  The team's furthest stage was obsidian, iron pickaxe and a lit portal. Deaths a game hour: 1.25 [0.75 to 2.5],
  against `factions2b`'s 8.0 with no goal.
- **The audit:** all three seeds audited, three factions each. Claims 10, 11 and 6; sales 245, 330 and 314; no captures
  or kills; no rule broken.
- **Load:** mean tick 7.59 to 10.87 ms, worst tick 1,221 to 1,818 ms, heap at most 1,285 to 1,530 MB.

## The worst ticks

The lead asked what each seed's five worst ticks coincide with (autosave, chunk generation, the World Record's writer, a
death replay capture, an atlas or region scan). This build cannot say. Its `eval-result.json` keeps only the worst
tick's length (`tickMsWorst`), not when it fell or the next four. Run sprinted, the server logs no "Can't keep up"
lines. The console's silences are not tick lengths: the longest few follow the run's game-day "thinking at tick N"
summary on some seeds (4 of the 15 checked in `factions2c`), and other lines on the rest. No cause can be named from
them. `37f80f2` and later record the worst tick's time and the ticks over 50 and 200 ms (`tickMsWorstAt`,
`ticksOver50ms`, `ticksOver200ms`). The timing arm on `4ba244e` reads one tick a seed that way, and a worst tick on a
multiple of 6,000 is the vanilla autosave. Five a seed would need new logging, which was not added.

## What else shared the cores

Nothing but this run's servers.
