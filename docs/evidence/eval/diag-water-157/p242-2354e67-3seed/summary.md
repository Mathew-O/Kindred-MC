# Evaluation summary: p242-2354e67-3seed

Runs: 3 seeds (303, 31102026, 505), EARNED. Scenario: starters=Kindred,Sol,Echo; goal=nether; edits=on; ticks=144000; sprint=on; strategist=off; voice=off; keep=none; lessons=on; atlas=on; rescue=on; advice=on.
Fewer than ten seeds: too few to compare with another build.

Medians, with the range across seeds in brackets.

| Measure | Median [range] (seeds) |
| --- | --- |
| The team's furthest stage (index in the list below) | 3 [3 to 6] (n=3) |
| Deaths a game hour, the team | 1.5 [1.5 to 2.5] (n=3) |
| The world's days over the run | 6.94 [6.0 to 10.19] (n=3) |
| Deaths a game day, the team | 0.5 [0.29 to 0.72] (n=3) |
| Stuck game minutes a game day, the team | 3.83 [2.5 to 6.48] (n=3) |
| Chunk-loads a game hour, the team | 8356.5 [7378.0 to 11510.0] (n=3) |
| Chunk-minutes a game hour, the team | 52012.8 [52008.0 to 52012.8] (n=3) |
| Stuck spells, the team | 13 [5 to 15] (n=3) |
| Stuck game minutes, the team | 39 [15 to 45] (n=3) |
| Rescue plans, the team (of them, out) | 0 [0 to 0] (n=3) (0 [0 to 0] (n=3)) |
| Rescues asked (rounds), the team | 0 [0 to 0] (n=3) |
| Rescue spells ended, the team (of them, out; rounds in them) | 0 [0 to 0] (n=3) (0 [0 to 0] (n=3); 0 [0 to 0] (n=3)) |
| Rescue recipes tried first, the team (spells out by a recipe) | 0 [0 to 0] (n=3) (0 [0 to 0] (n=3)) |
| Game seconds from a spell's last progress to its ask: each seed's median / every ask | - / - |
| Model tokens, the team | 0 [0 to 0] (n=3) |
| Lines spoken a game minute (between AI players, and said first to people) | 0.0 [0.0 to 0.0] (n=3) |
| Lines between AI players said / said first to people / meetings noted with nobody to hear | 0 [0 to 0] (n=3) / 0 [0 to 0] (n=3) / 0 [0 to 0] (n=3) |
| First to wooden_tool, game minutes (a seed not reaching it counted as later than all) | 0.8 [0.6 to 2.2] (reached on 3 of 3) |
| First to stone_tools, game minutes (a seed not reaching it counted as later than all) | 1.9 [1.5 to 2.6] (reached on 3 of 3) |
| First to iron_ingot, game minutes (a seed not reaching it counted as later than all) | 6.2 [5.6 to 7.4] (reached on 3 of 3) |
| First to iron_pickaxe, game minutes (a seed not reaching it counted as later than all) | 26.7 [11.4 to 31.2] (reached on 3 of 3) |
| First to diamond, game minutes (a seed not reaching it counted as later than all) | not reached [86.2 to not reached] (reached on 1 of 3) |
| First to obsidian, game minutes (a seed not reaching it counted as later than all) | not reached [73.3 to not reached] (reached on 1 of 3) |
| First to portal_lit, game minutes (a seed not reaching it counted as later than all) | not reached [77.3 to not reached] (reached on 1 of 3) |

Stages: 0 wooden_tool, 1 stone_tools, 2 iron_ingot, 3 iron_pickaxe, 4 diamond, 5 obsidian, 6 portal_lit, 7 nether_entered, 8 blaze_rods, 9 eyes, 10 stronghold, 11 end, 12 dragon.

Deaths by cause, all seeds: zombie 3, creeper 2, drowning 2, skeleton 2, fall 1, suffocation 1.

Stuck spells by kind, all seeds (F-26c): no walkable route 2 (6%), refused target repeated 1 (3%), hole or pit 4 (12%), hemmed in 1 (3%), mining for a find 1 (3%), busy-work loop 24 (73%).

Deaths by kind, all seeds (F-26c): drowning 2 (18%), fall 1 (9%), cornered 7 (64%), other 1 (9%).

| Seed | Ticks run | Team furthest | Deaths/h | Per player: furthest, deaths, stuck spells, RunMetrics (done/blocked, worst repeat, recovered %, walks in vain) |
| --- | --- | --- | --- | --- |
| 303 | 144000 | iron_pickaxe | 2.5 | Kindred: iron_pickaxe, 2, 6; Sol: iron_pickaxe, 0, 7; Echo: iron_ingot, 3, 2 |
| 31102026 | 144000 | iron_pickaxe | 1.5 | Kindred: iron_pickaxe, 1, 2; Sol: iron_pickaxe, 2, 3; Echo: iron_pickaxe, 0, 0 |
| 505 | 144000 | portal_lit | 1.5 | Kindred: portal_lit, 2, 3; Sol: iron_pickaxe, 1, 7; Echo: portal_lit, 0, 3 |

## The server's load

Each seed's mean tick (over the run, from the server's own last-hundred mean once a game second), its worst tick and when it fell (ticks into the run), the ticks that took over 50 and over 200 ms, and its heap at most. A run from before these were kept shows a dash for them.

| Seed | Mean tick, ms | Worst tick, ms | Worst at tick | Over 50 ms | Over 200 ms | Heap at most, MB |
| --- | --- | --- | --- | --- | --- | --- |
| 303 | 14.85 | 3873.79 | 1 | 1259 | 23 | 1101 |
| 31102026 | 13.05 | 6582.17 | 1 | 904 | 13 | 1182 |
| 505 | 13.85 | 2130.16 | 92801 | 1684 | 15 | 1156 |
