# Evaluation summary: e40a18-53cd517

Runs: 10 seeds (101, 202, 20261030, 303, 31102026, 404, 505, 606, 707, 8675309), EARNED. Scenario: starters=Kindred,Sol,Echo; goal=nether; edits=on; ticks=144000; sprint=on; strategist=off; voice=off; keep=none; lessons=on; atlas=on; rescue=on; advice=on.

Medians, with the range across seeds in brackets.

| Measure | Median [range] (seeds) |
| --- | --- |
| The team's furthest stage (index in the list below) | 6.0 [6 to 6] (n=10) |
| Deaths a game hour, the team | 0.5 [0.0 to 2.5] (n=10) |
| Stuck spells, the team | 3.0 [0 to 17] (n=10) |
| Stuck game minutes, the team | 9.0 [0 to 51] (n=10) |
| Rescue plans, the team (of them, out) | 0.0 [0 to 0] (n=10) (0.0 [0 to 0] (n=10)) |
| Rescues asked (rounds), the team | 0.0 [0 to 0] (n=10) |
| Rescue spells ended, the team (of them, out; rounds in them) | 0.0 [0 to 0] (n=10) (0.0 [0 to 0] (n=10); 0.0 [0 to 0] (n=10)) |
| Rescue recipes tried first, the team (spells out by a recipe) | 0.0 [0 to 0] (n=10) (0.0 [0 to 0] (n=10)) |
| Game seconds from a spell's last progress to its ask: each seed's median / every ask | - / - |
| Model tokens, the team | 0.0 [0 to 0] (n=10) |
| Lines spoken a game minute (between AI players, and said first to people) | 0.0 [0.0 to 0.0] (n=10) |
| Lines between AI players said / said first to people / meetings noted with nobody to hear | 0.0 [0 to 0] (n=10) / 0.0 [0 to 0] (n=10) / 0.0 [0 to 1] (n=10) |
| First to wooden_tool, game minutes (a seed not reaching it counted as later than all) | 0.7 [0.3 to 3.6] (reached on 10 of 10) |
| First to stone_tools, game minutes (a seed not reaching it counted as later than all) | 1.7 [1.1 to 4.1] (reached on 10 of 10) |
| First to iron_ingot, game minutes (a seed not reaching it counted as later than all) | 5.25 [3.4 to 9.1] (reached on 10 of 10) |
| First to iron_pickaxe, game minutes (a seed not reaching it counted as later than all) | 15.9 [13.4 to 28.8] (reached on 10 of 10) |
| First to diamond, game minutes (a seed not reaching it counted as later than all) | not reached [26.1 to not reached] (reached on 4 of 10) |
| First to obsidian, game minutes (a seed not reaching it counted as later than all) | 51.35 [25.2 to 86.9] (reached on 10 of 10) |
| First to portal_lit, game minutes (a seed not reaching it counted as later than all) | 53.3 [26.9 to 102.8] (reached on 10 of 10) |

Stages: 0 wooden_tool, 1 stone_tools, 2 iron_ingot, 3 iron_pickaxe, 4 diamond, 5 obsidian, 6 portal_lit, 7 nether_entered, 8 blaze_rods, 9 eyes, 10 stronghold, 11 end, 12 dragon.

Deaths by cause, all seeds: zombie 9, fall 2, falling_stalactite 1, lava 1, skeleton 1, creeper 1.

Stuck spells by kind, all seeds (F-26c): water loop 2 (4%), no walkable route 1 (2%), hole or pit 21 (46%), hemmed in 2 (4%), waiting for daylight or a teammate 1 (2%), mining for a find 8 (17%), busy-work loop 11 (24%).

Deaths by kind, all seeds (F-26c): lava 1 (7%), fall 2 (13%), starving with a mob near 2 (13%), cornered 6 (40%), dark melee 2 (13%), other 2 (13%).

| Seed | Ticks run | Team furthest | Deaths/h | Per player: furthest, deaths, stuck spells, RunMetrics (done/blocked, worst repeat, recovered %, walks in vain) |
| --- | --- | --- | --- | --- |
| 101 | 144000 | portal_lit | 1.0 | Kindred: iron_pickaxe, 0, 1; Sol: diamond, 2, 1; Echo: portal_lit, 0, 0 |
| 202 | 144000 | portal_lit | 2.0 | Kindred: portal_lit, 0, 0; Sol: portal_lit, 1, 3; Echo: obsidian, 3, 1 |
| 20261030 | 144000 | portal_lit | 1.0 | Kindred: iron_pickaxe, 0, 1; Sol: portal_lit, 1, 2; Echo: diamond, 1, 2 |
| 303 | 144000 | portal_lit | 0.5 | Kindred: portal_lit, 0, 0; Sol: iron_pickaxe, 1, 1; Echo: portal_lit, 0, 0 |
| 31102026 | 144000 | portal_lit | 0.5 | Kindred: iron_pickaxe, 0, 2; Sol: portal_lit, 1, 0; Echo: portal_lit, 0, 1 |
| 404 | 144000 | portal_lit | 2.5 | Kindred: iron_pickaxe, 4, 3; Sol: portal_lit, 0, 0; Echo: obsidian, 1, 5 |
| 505 | 144000 | portal_lit | 0.0 | Kindred: portal_lit, 0, 0; Sol: portal_lit, 0, 0; Echo: iron_pickaxe, 0, 3 |
| 606 | 144000 | portal_lit | 0.0 | Kindred: iron_pickaxe, 0, 13; Sol: portal_lit, 0, 4; Echo: portal_lit, 0, 0 |
| 707 | 144000 | portal_lit | 0.0 | Kindred: iron_pickaxe, 0, 0; Sol: portal_lit, 0, 0; Echo: portal_lit, 0, 0 |
| 8675309 | 144000 | portal_lit | 0.0 | Kindred: portal_lit, 0, 2; Sol: portal_lit, 0, 0; Echo: portal_lit, 0, 1 |

## The server's load

Each seed's mean tick (over the run, from the server's own last-hundred mean once a game second), its worst tick and when it fell (ticks into the run), the ticks that took over 50 and over 200 ms, and its heap at most. A run from before these were kept shows a dash for them.

| Seed | Mean tick, ms | Worst tick, ms | Worst at tick | Over 50 ms | Over 200 ms | Heap at most, MB |
| --- | --- | --- | --- | --- | --- | --- |
| 101 | 12.47 | 1049.64 | 5838 | 561 | 20 | 1167 |
| 202 | 15.10 | 2167.06 | 92391 | 1269 | 10 | 1114 |
| 20261030 | 14.15 | 1961.49 | 1 | 916 | 13 | 995 |
| 303 | 12.76 | 2322.90 | 1 | 344 | 7 | 1224 |
| 31102026 | 12.61 | 2410.14 | 1 | 379 | 10 | 1214 |
| 404 | 13.45 | 2650.57 | 94674 | 390 | 13 | 1356 |
| 505 | 12.51 | 2042.18 | 73564 | 370 | 7 | 1178 |
| 606 | 9.72 | 2188.65 | 75972 | 98 | 9 | 1180 |
| 707 | 13.16 | 1626.69 | 58131 | 321 | 9 | 1337 |
| 8675309 | 13.14 | 1150.36 | 5838 | 254 | 5 | 1078 |
