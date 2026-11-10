# Evaluation summary: main-7828527

Runs: 10 seeds (101, 202, 20261030, 303, 31102026, 404, 505, 606, 707, 8675309), EARNED. Scenario: starters=Kindred,Sol,Echo; goal=nether; edits=on; ticks=144000; sprint=on; strategist=off; voice=off; keep=none; lessons=on; atlas=on; rescue=on; advice=on.

Medians, with the range across seeds in brackets.

| Measure | Median [range] (seeds) |
| --- | --- |
| The team's furthest stage (index in the list below) | 6.0 [3 to 6] (n=10) |
| Deaths a game hour, the team | 0.75 [0.0 to 2.0] (n=10) |
| Stuck spells, the team | 5.0 [0 to 15] (n=10) |
| Stuck game minutes, the team | 15.0 [0 to 45] (n=10) |
| Rescue plans, the team (of them, out) | 0.0 [0 to 0] (n=10) (0.0 [0 to 0] (n=10)) |
| Rescues asked (rounds), the team | 0.0 [0 to 0] (n=10) |
| Rescue spells ended, the team (of them, out; rounds in them) | 0.0 [0 to 0] (n=10) (0.0 [0 to 0] (n=10); 0.0 [0 to 0] (n=10)) |
| Rescue recipes tried first, the team (spells out by a recipe) | 0.0 [0 to 0] (n=10) (0.0 [0 to 0] (n=10)) |
| Game seconds from a spell's last progress to its ask: each seed's median / every ask | - / - |
| Model tokens, the team | 0.0 [0 to 0] (n=10) |
| Lines spoken a game minute (between AI players, and said first to people) | 0.0 [0.0 to 0.0] (n=10) |
| Lines between AI players said / said first to people / meetings noted with nobody to hear | 0.0 [0 to 0] (n=10) / 0.0 [0 to 0] (n=10) / 0.0 [0 to 0] (n=10) |
| First to wooden_tool, game minutes (a seed not reaching it counted as later than all) | 0.85 [0.3 to 2.2] (reached on 10 of 10) |
| First to stone_tools, game minutes (a seed not reaching it counted as later than all) | 1.95 [1.2 to 2.7] (reached on 10 of 10) |
| First to iron_ingot, game minutes (a seed not reaching it counted as later than all) | 5.8 [4.0 to 7.8] (reached on 10 of 10) |
| First to iron_pickaxe, game minutes (a seed not reaching it counted as later than all) | 16.15 [12.1 to 109.2] (reached on 10 of 10) |
| First to diamond, game minutes (a seed not reaching it counted as later than all) | not reached [28.0 to not reached] (reached on 3 of 10) |
| First to obsidian, game minutes (a seed not reaching it counted as later than all) | 42.95 [24.9 to not reached] (reached on 9 of 10) |
| First to portal_lit, game minutes (a seed not reaching it counted as later than all) | 53.6 [25.5 to not reached] (reached on 9 of 10) |

Stages: 0 wooden_tool, 1 stone_tools, 2 iron_ingot, 3 iron_pickaxe, 4 diamond, 5 obsidian, 6 portal_lit, 7 nether_entered, 8 blaze_rods, 9 eyes, 10 stronghold, 11 end, 12 dragon.

Deaths by cause, all seeds: zombie 8, skeleton 5, drowning 1, fall 1, creeper 1.

Stuck spells by kind, all seeds (F-26c): water loop 4 (7%), hole or pit 20 (34%), hemmed in 12 (21%), waiting for daylight or a teammate 1 (2%), mining for a find 6 (10%), busy-work loop 15 (26%).

Deaths by kind, all seeds (F-26c): drowning 1 (6%), fall 1 (6%), starving with a mob near 2 (12%), cornered 7 (44%), dark melee 2 (12%), other 3 (19%).

| Seed | Ticks run | Team furthest | Deaths/h | Per player: furthest, deaths, stuck spells, RunMetrics (done/blocked, worst repeat, recovered %, walks in vain) |
| --- | --- | --- | --- | --- |
| 101 | 144000 | portal_lit | 0.5 | Kindred: portal_lit, 1, 0; Sol: iron_pickaxe, 0, 1; Echo: portal_lit, 0, 0 |
| 202 | 144000 | portal_lit | 1.0 | Kindred: diamond, 2, 2; Sol: portal_lit, 0, 0; Echo: portal_lit, 0, 3 |
| 20261030 | 144000 | portal_lit | 1.0 | Kindred: obsidian, 0, 5; Sol: portal_lit, 2, 1; Echo: iron_pickaxe, 0, 1 |
| 303 | 144000 | portal_lit | 0.0 | Kindred: portal_lit, 0, 0; Sol: portal_lit, 0, 0; Echo: portal_lit, 0, 0 |
| 31102026 | 144000 | iron_pickaxe | 2.0 | Kindred: iron_ingot, 4, 0; Sol: iron_pickaxe, 0, 1; Echo: iron_ingot, 0, 0 |
| 404 | 144000 | portal_lit | 0.0 | Kindred: iron_pickaxe, 0, 0; Sol: portal_lit, 0, 0; Echo: obsidian, 0, 10 |
| 505 | 144000 | portal_lit | 1.0 | Kindred: obsidian, 0, 6; Sol: obsidian, 1, 6; Echo: portal_lit, 1, 3 |
| 606 | 144000 | portal_lit | 0.5 | Kindred: portal_lit, 1, 0; Sol: iron_pickaxe, 0, 12; Echo: portal_lit, 0, 1 |
| 707 | 144000 | portal_lit | 1.5 | Kindred: portal_lit, 1, 0; Sol: iron_pickaxe, 0, 2; Echo: iron_pickaxe, 2, 3 |
| 8675309 | 144000 | portal_lit | 0.5 | Kindred: portal_lit, 0, 0; Sol: portal_lit, 0, 0; Echo: portal_lit, 1, 1 |

## The server's load

Each seed's mean tick (over the run, from the server's own last-hundred mean once a game second), its worst tick and when it fell (ticks into the run), the ticks that took over 50 and over 200 ms, and its heap at most. A run from before these were kept shows a dash for them.

| Seed | Mean tick, ms | Worst tick, ms | Worst at tick | Over 50 ms | Over 200 ms | Heap at most, MB |
| --- | --- | --- | --- | --- | --- | --- |
| 101 | 12.14 | 2346.57 | 1 | 530 | 14 | 1094 |
| 202 | 14.94 | 2414.26 | 42922 | 715 | 12 | 1190 |
| 20261030 | 14.01 | 2031.35 | 64783 | 835 | 12 | 1759 |
| 303 | 14.08 | 1428.41 | 5838 | 525 | 9 | 1188 |
| 31102026 | 11.73 | 1452.04 | 5838 | 746 | 8 | 1140 |
| 404 | 10.36 | 2423.02 | 47689 | 135 | 8 | 1221 |
| 505 | 14.09 | 2252.70 | 132897 | 713 | 11 | 1060 |
| 606 | 10.82 | 2014.95 | 88195 | 179 | 11 | 1319 |
| 707 | 12.29 | 2137.19 | 1 | 461 | 11 | 1245 |
| 8675309 | 14.45 | 1592.37 | 65053 | 137 | 8 | 1000 |
