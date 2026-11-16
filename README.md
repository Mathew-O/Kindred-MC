# Data: e40a24r-83321ac seed 707, Sol's reconciliation block (for LOOPS)

The run: iteration 24's jar (main 83321ac) by `--jar`, seed 707, `goal=nether; ticks=144000`, earned, strategist off,
replays on, `--keep-world` (run.py from #376's branch). This seed was re-run alone after the runner's 2-hour limit
stopped the first try; this is that re-run.

- `e40a24r-83321ac/707/world-kept/`: all six region files (the block's spot, 57, −4, 110, is in `r.0.0.mca`),
  `level.dat`, `data/`, `playerdata/` and `kindred/companions.sqlite`, as the run left them (its end, not the block's
  moment).
- `e40a24r-83321ac/707/console-14700-14900.txt.gz`: console lines 14,700 to 14,900; the block is line 14,862.
  `console.txt.gz` is the whole console. `eval-result.json` is the run's result.

## The block
- **When:** 07:53:31 console time, about tick 58,829 (placed between the tick marks at 48,000 and 60,000).
- **Where:** last goal line "Sol goal at 57 -4 110: Iron: waiting for a feasible step"; before it, "Cast the portal
  frame from lava and water - Dig down toward lava: clear 56, -2, 110", after "native_break COMPLETED [Dig down toward
  lava: clear 57, -4, 111]". "Sol picked up 1 minecraft:gravel" is the line before the block.
- **The line:** "Native break outcome requires reconciliation (The block changed before an attributable break;
  inventory changed without an attributable break)". Sol completed no action after it, to the run's end.

## What the ground was (read offline)
- **Fresh seed-707 terrain** (a throwaway vanilla 1.21.1 server, seed 707, no mods, no players; deleted after): at
  x 56 and 57, z 110 and 111, **gravel from y −5 to −2**, deepslate from −1 up, and air (a cave) at −6.
- **The kept world, at the run's end:** the same columns are air from −6 to −2 and deepslate from −1 up. The gravel
  is gone.
- So the target 56, −2, 110 was gravel standing over a cave.
