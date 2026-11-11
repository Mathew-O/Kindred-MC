# Evaluation summary: e40a20-79a9aad

Runs: 10 seeds (101, 202, 20261030, 303, 31102026, 404, 505, 606, 707, 8675309), EARNED. Scenario: starters=Kindred,Sol,Echo; goal=nether; edits=on; ticks=144000; sprint=on; strategist=off; voice=off; keep=none; lessons=on; atlas=on; rescue=on; advice=on.

Medians, with the range across seeds in brackets.

| Measure | Median [range] (seeds) |
| --- | --- |
| The team's furthest stage (index in the list below) | 6.0 [4 to 6] (n=10) |
| Deaths a game hour, the team | 0.75 [0.0 to 3.0] (n=10) |
| Stuck spells, the team | 4.0 [0 to 21] (n=10) |
| Stuck game minutes, the team | 12.0 [0 to 63] (n=10) |
| Rescue plans, the team (of them, out) | 0.0 [0 to 0] (n=10) (0.0 [0 to 0] (n=10)) |
| Rescues asked (rounds), the team | 0.0 [0 to 0] (n=10) |
| Rescue spells ended, the team (of them, out; rounds in them) | 0.0 [0 to 0] (n=10) (0.0 [0 to 0] (n=10); 0.0 [0 to 0] (n=10)) |
| Rescue recipes tried first, the team (spells out by a recipe) | 0.0 [0 to 0] (n=10) (0.0 [0 to 0] (n=10)) |
| Game seconds from a spell's last progress to its ask: each seed's median / every ask | - / - |
| Model tokens, the team | 0.0 [0 to 0] (n=10) |
| Lines spoken a game minute (between AI players, and said first to people) | 0.0 [0.0 to 0.0] (n=10) |
| Lines between AI players said / said first to people / meetings noted with nobody to hear | 0.0 [0 to 0] (n=10) / 0.0 [0 to 0] (n=10) / 0.0 [0 to 1] (n=10) |
| First to wooden_tool, game minutes (a seed not reaching it counted as later than all) | 0.7 [0.3 to 2.6] (reached on 10 of 10) |
| First to stone_tools, game minutes (a seed not reaching it counted as later than all) | 1.9 [1.1 to 3.2] (reached on 10 of 10) |
| First to iron_ingot, game minutes (a seed not reaching it counted as later than all) | 5.7 [3.4 to 8.5] (reached on 10 of 10) |
| First to iron_pickaxe, game minutes (a seed not reaching it counted as later than all) | 17.25 [10.3 to 49.3] (reached on 10 of 10) |
| First to diamond, game minutes (a seed not reaching it counted as later than all) | not reached [29.5 to not reached] (reached on 3 of 10) |
| First to obsidian, game minutes (a seed not reaching it counted as later than all) | 43.35 [21.2 to not reached] (reached on 9 of 10) |
| First to portal_lit, game minutes (a seed not reaching it counted as later than all) | 46.3 [32.8 to not reached] (reached on 8 of 10) |

Stages: 0 wooden_tool, 1 stone_tools, 2 iron_ingot, 3 iron_pickaxe, 4 diamond, 5 obsidian, 6 portal_lit, 7 nether_entered, 8 blaze_rods, 9 eyes, 10 stronghold, 11 end, 12 dragon.

Deaths by cause, all seeds: zombie 8, skeleton 4, drowning 2, lava 2, spider 1, zombie_villager 1, creeper 1.

Stuck spells by kind, all seeds (F-26c): water loop 8 (11%), no walkable route 7 (10%), hole or pit 2 (3%), hemmed in 29 (41%), waiting for daylight or a teammate 1 (1%), mining for a find 6 (9%), busy-work loop 17 (24%).

Deaths by kind, all seeds (F-26c): lava 2 (11%), drowning 2 (11%), starving with a mob near 1 (5%), cornered 9 (47%), dark melee 4 (21%), other 1 (5%).

| Seed | Ticks run | Team furthest | Deaths/h | Per player: furthest, deaths, stuck spells, RunMetrics (done/blocked, worst repeat, recovered %, walks in vain) |
| --- | --- | --- | --- | --- |
| 101 | 144000 | portal_lit | 1.5 | Kindred: iron_pickaxe, 3, 0; Sol: portal_lit, 0, 2; Echo: iron_pickaxe, 0, 1 |
| 202 | 144000 | diamond | 3.0 | Kindred: diamond, 2, 0; Sol: iron_pickaxe, 1, 4; Echo: iron_pickaxe, 3, 7 |
| 20261030 | 144000 | portal_lit | 1.0 | Kindred: obsidian, 0, 7; Sol: portal_lit, 0, 1; Echo: iron_pickaxe, 2, 1 |
| 303 | 144000 | portal_lit | 0.0 | Kindred: portal_lit, 0, 0; Sol: iron_pickaxe, 0, 0; Echo: iron_pickaxe, 0, 0 |
| 31102026 | 144000 | obsidian | 0.5 | Kindred: iron_pickaxe, 0, 0; Sol: iron_pickaxe, 1, 1; Echo: obsidian, 0, 3 |
| 404 | 144000 | portal_lit | 0.0 | Kindred: obsidian, 0, 12; Sol: obsidian, 0, 0; Echo: portal_lit, 0, 1 |
| 505 | 144000 | portal_lit | 1.5 | Kindred: portal_lit, 2, 0; Sol: portal_lit, 0, 0; Echo: iron_pickaxe, 1, 3 |
| 606 | 144000 | portal_lit | 0.0 | Kindred: portal_lit, 0, 0; Sol: portal_lit, 0, 0; Echo: portal_lit, 0, 2 |
| 707 | 144000 | portal_lit | 0.5 | Kindred: iron_pickaxe, 1, 1; Sol: portal_lit, 0, 2; Echo: iron_pickaxe, 0, 1 |
| 8675309 | 144000 | portal_lit | 1.5 | Kindred: obsidian, 3, 20; Sol: portal_lit, 0, 1; Echo: portal_lit, 0, 0 |

## The server's load

Each seed's mean tick (over the run, from the server's own last-hundred mean once a game second), its worst tick and when it fell (ticks into the run), the ticks that took over 50 and over 200 ms, and its heap at most. A run from before these were kept shows a dash for them.

| Seed | Mean tick, ms | Worst tick, ms | Worst at tick | Over 50 ms | Over 200 ms | Heap at most, MB |
| --- | --- | --- | --- | --- | --- | --- |
| 101 | 11.34 | 3253.07 | 1 | 536 | 16 | 1320 |
| 202 | 17.30 | 932.46 | 5838 | 8815 | 23 | 1196 |
| 20261030 | 13.39 | 3205.19 | 1 | 652 | 14 | 1252 |
| 303 | 13.39 | 1662.12 | 1 | 708 | 9 | 1439 |
| 31102026 | 15.19 | 1818.19 | 1 | 888 | 11 | 1103 |
| 404 | 14.14 | 1258.13 | 74943 | 603 | 11 | 1220 |
| 505 | 11.05 | 1798.72 | 96645 | 228 | 8 | 1239 |
| 606 | 12.73 | 2030.02 | 1 | 402 | 9 | 910 |
| 707 | 12.55 | 2220.91 | 1 | 455 | 8 | 1484 |
| 8675309 | 14.93 | 1443.30 | 56478 | 110 | 4 | 937 |
