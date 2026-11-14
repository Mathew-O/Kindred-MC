# Evaluation summary: p157-after-ec581d5

Runs: 3 seeds (303, 31102026, 505), EARNED. Scenario: starters=Kindred,Sol,Echo; goal=nether; edits=on; ticks=144000; sprint=on; strategist=off; voice=off; keep=none; lessons=on; atlas=on; rescue=on; advice=on.
Fewer than ten seeds: too few to compare with another build.

Medians, with the range across seeds in brackets.

| Measure | Median [range] (seeds) |
| --- | --- |
| The team's furthest stage (index in the list below) | 6 [5 to 6] (n=3) |
| Deaths a game hour, the team | 1.0 [0.0 to 2.0] (n=3) |
| The world's days over the run | 8.34 [6.0 to 11.16] (n=3) |
| Deaths a game day, the team | 0.33 [0.0 to 0.48] (n=3) |
| Stuck game minutes a game day, the team | 5.4 [0.81 to 6.5] (n=3) |
| Chunk-loads a game hour, the team | 6849.5 [4875.5 to 11078.5] (n=3) |
| Chunk-minutes a game hour, the team | 52015.2 [52010.4 to 52020.0] (n=3) |
| Stuck spells, the team | 13 [3 to 15] (n=3) |
| Stuck game minutes, the team | 39 [9 to 45] (n=3) |
| Rescue plans, the team (of them, out) | 0 [0 to 0] (n=3) (0 [0 to 0] (n=3)) |
| Rescues asked (rounds), the team | 0 [0 to 0] (n=3) |
| Rescue spells ended, the team (of them, out; rounds in them) | 0 [0 to 0] (n=3) (0 [0 to 0] (n=3); 0 [0 to 0] (n=3)) |
| Rescue recipes tried first, the team (spells out by a recipe) | 0 [0 to 0] (n=3) (0 [0 to 0] (n=3)) |
| Game seconds from a spell's last progress to its ask: each seed's median / every ask | - / - |
| Model tokens, the team | 0 [0 to 0] (n=3) |
| Lines spoken a game minute (between AI players, and said first to people) | 0.0 [0.0 to 0.0] (n=3) |
| Lines between AI players said / said first to people / meetings noted with nobody to hear | 0 [0 to 0] (n=3) / 0 [0 to 0] (n=3) / 0 [0 to 1] (n=3) |
| First to wooden_tool, game minutes (a seed not reaching it counted as later than all) | 0.9 [0.8 to 7.5] (reached on 3 of 3) |
| First to stone_tools, game minutes (a seed not reaching it counted as later than all) | 2.3 [1.6 to 9.7] (reached on 3 of 3) |
| First to iron_ingot, game minutes (a seed not reaching it counted as later than all) | 6.3 [3.9 to 11.6] (reached on 3 of 3) |
| First to iron_pickaxe, game minutes (a seed not reaching it counted as later than all) | 18.0 [13.9 to 20.2] (reached on 3 of 3) |
| First to diamond, game minutes (a seed not reaching it counted as later than all) | not reached [82.1 to not reached] (reached on 1 of 3) |
| First to obsidian, game minutes (a seed not reaching it counted as later than all) | 41.7 [29.2 to 78.5] (reached on 3 of 3) |
| First to portal_lit, game minutes (a seed not reaching it counted as later than all) | 44.7 [32.3 to not reached] (reached on 2 of 3) |

Stages: 0 wooden_tool, 1 stone_tools, 2 iron_ingot, 3 iron_pickaxe, 4 diamond, 5 obsidian, 6 portal_lit, 7 nether_entered, 8 blaze_rods, 9 eyes, 10 stronghold, 11 end, 12 dragon.

Deaths by cause, all seeds: zombie 2, suffocation 1, slime 1, enderman 1, skeleton 1.

Stuck spells by kind, all seeds (F-26c): refused target repeated 1 (3%), hole or pit 11 (35%), hemmed in 3 (10%), mining for a find 2 (6%), busy-work loop 13 (42%), other 1 (3%).

Deaths by kind, all seeds (F-26c): cornered 4 (67%), dark melee 1 (17%), other 1 (17%).

| Seed | Ticks run | Team furthest | Deaths/h | Per player: furthest, deaths, stuck spells, RunMetrics (done/blocked, worst repeat, recovered %, walks in vain) |
| --- | --- | --- | --- | --- |
| 303 | 144000 | portal_lit | 0.0 | Kindred: portal_lit, 0, 1; Sol: iron_pickaxe, 0, 2; Echo: portal_lit, 0, 0 |
| 31102026 | 144000 | obsidian | 1.0 | Kindred: obsidian, 0, 2; Sol: iron_ingot, 1, 4; Echo: obsidian, 1, 7 |
| 505 | 144000 | portal_lit | 2.0 | Kindred: iron_pickaxe, 2, 10; Sol: iron_pickaxe, 2, 5; Echo: portal_lit, 0, 0 |

## The server's load

Each seed's mean tick (over the run, from the server's own last-hundred mean once a game second), its worst tick and when it fell (ticks into the run), the ticks that took over 50 and over 200 ms, and its heap at most. A run from before these were kept shows a dash for them.

| Seed | Mean tick, ms | Worst tick, ms | Worst at tick | Over 50 ms | Over 200 ms | Heap at most, MB |
| --- | --- | --- | --- | --- | --- | --- |
| 303 | 16.85 | 2107.60 | 38806 | 1174 | 20 | 1025 |
| 31102026 | 13.16 | 7104.81 | 1 | 751 | 15 | 1150 |
| 505 | 11.95 | 1291.46 | 839 | 604 | 11 | 1051 |
