# factions2b-ffd02cb: the Factions scenario with life-first ordering, ten seeds, no goal

Run in the F-29 session's container for Session A (M-06i) on 9 November 2026, from 02:46 to about 05:00 UTC, in a
4-core cloud container. Build `ffd02cb` (claude/m06i-play-1108, #72: "what keeps a player alive comes before the
faction world"). `tools/eval/run.py --parallel 2 --policy bases=true`, scenario `goal=none; mode=factions;
ticks=288000`, seeds 101 to 110. Earned: empty inventories, edits on under the roaming policy, `keep=none`. Strategist
off; no model calls.

[summary.md](summary.md) is that build's `score.py` output. `summary.json`, `build.json` and each seed's
`eval-result.json` and `run.json` are beside it. No logs or worlds.

## What it shows

- **The audit:** all ten seeds audited, three factions each, no rule broken. Claims 0 to 10 a seed (none on 110), with
  no captures, sales or kills.
- **No progress:** the team's furthest stage is "none" on every seed, as in `factions2-495b879`. Deaths a game hour:
  8.0 [4.5 to 26.0], 231 of them zombies.
- **Why, from the consoles of seeds 104 and 110:**
  - Each player's base logs "Stone tools first" in its first seconds and nothing more.
  - No break, craft, place or eat was dispatched in four hours.
  - The players walk, wait, retreat and claim. Most walks end "No walkable route": the route planner gives up at its
    6,000-node limit, and the same targets are tried 30 to 59 times.
  - With no goal of its own, nothing gathered wood. Session A's fix is `4ba244e`.
- **Load for three players:** mean tick 3.58 to 7.78 ms, worst tick 383 to 1,199 ms, heap at most 817 to 1,628 MB.

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

Nothing but this run's two servers. No GameTest or build ran beside it.
