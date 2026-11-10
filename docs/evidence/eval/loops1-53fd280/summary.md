# Evaluation summary: loops1-53fd280

Runs: 10 seeds (101, 202, 20261030, 303, 31102026, 404, 505, 606, 707, 8675309), EARNED. Scenario: starters=Kindred,Sol,Echo; goal=nether; edits=on; ticks=144000; sprint=on; strategist=off; voice=off; keep=none; lessons=on; atlas=on; rescue=on; advice=on.

Medians, with the range across seeds in brackets.

| Measure | Median [range] (seeds) |
| --- | --- |
| The team's furthest stage (index in the list below) | 5.5 [3 to 6] (n=10) |
| Deaths a game hour, the team | 0.75 [0.0 to 5.5] (n=10) |
| Stuck spells, the team | 6.0 [0 to 18] (n=10) |
| Stuck game minutes, the team | 18.0 [0 to 54] (n=10) |
| Rescue plans, the team (of them, out) | 0.0 [0 to 0] (n=10) (0.0 [0 to 0] (n=10)) |
| Rescues asked (rounds), the team | 0.0 [0 to 0] (n=10) |
| Rescue spells ended, the team (of them, out; rounds in them) | 0.0 [0 to 0] (n=10) (0.0 [0 to 0] (n=10); 0.0 [0 to 0] (n=10)) |
| Rescue recipes tried first, the team (spells out by a recipe) | 0.0 [0 to 0] (n=10) (0.0 [0 to 0] (n=10)) |
| Game seconds from a spell's last progress to its ask: each seed's median / every ask | - / - |
| Model tokens, the team | 0.0 [0 to 0] (n=10) |
| Lines spoken a game minute (between AI players, and said first to people) | 0.0 [0.0 to 0.0] (n=10) |
| Lines between AI players said / said first to people / meetings noted with nobody to hear | 0.0 [0 to 0] (n=10) / 0.0 [0 to 0] (n=10) / 0.0 [0 to 1] (n=10) |
| First to wooden_tool, game minutes (a seed not reaching it counted as later than all) | 0.65 [0.3 to 3.3] (reached on 10 of 10) |
| First to stone_tools, game minutes (a seed not reaching it counted as later than all) | 1.75 [1.2 to 4.6] (reached on 10 of 10) |
| First to iron_ingot, game minutes (a seed not reaching it counted as later than all) | 5.6 [3.7 to 9.1] (reached on 10 of 10) |
| First to iron_pickaxe, game minutes (a seed not reaching it counted as later than all) | 17.9 [11.1 to 22.9] (reached on 10 of 10) |
| First to diamond, game minutes (a seed not reaching it counted as later than all) | not reached [39.3 to not reached] (reached on 3 of 10) |
| First to obsidian, game minutes (a seed not reaching it counted as later than all) | 49.95 [29.6 to not reached] (reached on 8 of 10) |
| First to portal_lit, game minutes (a seed not reaching it counted as later than all) | not reached [49.0 to not reached] (reached on 5 of 10) |

Stages: 0 wooden_tool, 1 stone_tools, 2 iron_ingot, 3 iron_pickaxe, 4 diamond, 5 obsidian, 6 portal_lit, 7 nether_entered, 8 blaze_rods, 9 eyes, 10 stronghold, 11 end, 12 dragon.

Deaths by cause, all seeds: zombie 14, skeleton 4, fall 2, creeper 2, lava 1, spider 1, polar_bear 1, enderman 1.

Stuck spells by kind, all seeds (F-26c): no walkable route 10 (12%), hole or pit 18 (22%), hemmed in 31 (39%), waiting for daylight or a teammate 1 (1%), mining for a find 6 (8%), busy-work loop 14 (18%).

Deaths by kind, all seeds (F-26c): lava 1 (4%), fall 2 (8%), starving with a mob near 4 (15%), cornered 14 (54%), dark melee 3 (12%), other 2 (8%).

| Seed | Ticks run | Team furthest | Deaths/h | Per player: furthest, deaths, stuck spells, RunMetrics (done/blocked, worst repeat, recovered %, walks in vain) |
| --- | --- | --- | --- | --- |
| 101 | 144000 | iron_pickaxe | 0.5 | Kindred: iron_pickaxe, 0, 0; Sol: iron_pickaxe, 1, 1; Echo: iron_pickaxe, 0, 5 |
| 202 | 144000 | portal_lit | 1.0 | Kindred: portal_lit, 0, 1; Sol: stone_tools, 0, 3; Echo: obsidian, 2, 0 |
| 20261030 | 144000 | obsidian | 1.0 | Kindred: iron_pickaxe, 1, 1; Sol: obsidian, 0, 5; Echo: iron_pickaxe, 1, 5 |
| 303 | 144000 | obsidian | 0.0 | Kindred: iron_pickaxe, 0, 0; Sol: obsidian, 0, 0; Echo: obsidian, 0, 2 |
| 31102026 | 144000 | portal_lit | 0.5 | Kindred: iron_pickaxe, 0, 0; Sol: obsidian, 1, 2; Echo: portal_lit, 0, 1 |
| 404 | 144000 | portal_lit | 0.5 | Kindred: obsidian, 0, 11; Sol: obsidian, 1, 4; Echo: portal_lit, 0, 3 |
| 505 | 144000 | portal_lit | 2.5 | Kindred: portal_lit, 0, 0; Sol: iron_pickaxe, 4, 8; Echo: obsidian, 1, 8 |
| 606 | 144000 | portal_lit | 0.0 | Kindred: iron_pickaxe, 0, 0; Sol: iron_pickaxe, 0, 0; Echo: portal_lit, 0, 0 |
| 707 | 144000 | iron_pickaxe | 5.5 | Kindred: iron_pickaxe, 6, 1; Sol: iron_pickaxe, 3, 3; Echo: iron_pickaxe, 2, 2 |
| 8675309 | 144000 | obsidian | 1.5 | Kindred: obsidian, 0, 12; Sol: obsidian, 1, 1; Echo: iron_pickaxe, 2, 1 |

## The server's load

Each seed's mean tick (over the run, from the server's own last-hundred mean once a game second), its worst tick and when it fell (ticks into the run), the ticks that took over 50 and over 200 ms, and its heap at most. A run from before these were kept shows a dash for them.

| Seed | Mean tick, ms | Worst tick, ms | Worst at tick | Over 50 ms | Over 200 ms | Heap at most, MB |
| --- | --- | --- | --- | --- | --- | --- |
| 101 | 11.99 | 2026.29 | 248 | 682 | 15 | 1133 |
| 202 | 13.16 | 1297.72 | 5838 | 802 | 12 | 1129 |
| 20261030 | 13.64 | 4574.01 | 1 | 1197 | 16 | 1385 |
| 303 | 12.59 | 1070.09 | 5838 | 640 | 11 | 1218 |
| 31102026 | 13.67 | 2575.78 | 1 | 588 | 12 | 1152 |
| 404 | 12.21 | 2089.44 | 1 | 307 | 8 | 1175 |
| 505 | 10.34 | 712.85 | 5838 | 245 | 7 | 1078 |
| 606 | 9.73 | 896.65 | 5838 | 94 | 7 | 1451 |
| 707 | 11.56 | 1423.46 | 1 | 658 | 13 | 1053 |
| 8675309 | 10.93 | 874.11 | 5838 | 125 | 4 | 1343 |
