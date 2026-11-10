# factions2d-4ba244e: the Factions scenario with the standing goal, ten seeds, no goal set

Run in the F-29 session's container for Session A (M-06i) on 9 November 2026, from about 06:42 to 12:21 UTC, in a
4-core cloud container. Build `4ba244e` (claude/m06i-play-1108, #72: a player with no goal of its own plays the survival
progression in a faction world). `tools/eval/run.py --parallel 2 --policy bases=true`, scenario `goal=none;
mode=factions; ticks=288000`, seeds 101 to 110. Earned, strategist off.

[summary.md](summary.md) is that build's `score.py` output. `summary.json`, `build.json` and each seed's
`eval-result.json` and `run.json` are beside it. No logs or worlds.

## What it shows

- **Against `factions2b-ffd02cb`, the same seeds before the fix:**
  - a wooden tool on 9 of 10 (median 3.7 game minutes), against 0 of 10;
  - an iron pickaxe on 9 of 10 (median 45.1);
  - a lit portal on 5 of 10;
  - the Nether entered on 3 (102, 103 and 107).
- **Deaths:** median 1.5 a game hour [0.0 to 17.25], against 8.0.
- **Seed 110 is the outlier.** No stage reached, and 17.25 deaths a game hour: 61 of the run's 115 deaths were fire
  ("went up in flames" 48, "burned to death" 13), with 5 by Drowned and 3 drowned. It reads as a fire loop. The
  consoles are kept in the container.
- **The audit:** all ten audited, three factions each. Claims 10 or 11 a seed (none on 110), sales 168 to 509 (none
  on 110), no captures or kills, no rule broken.
- **Load:**
  - mean tick 7.36 to 21.04 ms;
  - worst tick 731 to 2,838 ms, none of them on a multiple of 6,000 (the autosave);
  - ticks over 50 ms, 32 to 2,936; over 200 ms, 4 to 15;
  - heap at most 925 to 1,818 MB.
- Blocked tasks stay high on seeds 105, 109 and 110 (up to about 1,900 a player), mostly "No walkable route". That is
  LOOPS' set-aside, next.

## What else shared the cores

Nothing but this run's two servers, until seed 110 ran alone at the end. No GameTest or build ran beside it.
