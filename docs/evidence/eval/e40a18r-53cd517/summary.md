# Evaluation summary: e40a18r-53cd517

Runs: 10 seeds (101, 202, 20261030, 303, 31102026, 404, 505, 606, 707, 8675309), EARNED. Scenario: starters=Kindred,Sol,Echo; goal=nether; edits=on; ticks=144000; sprint=on; strategist=off; voice=off; keep=none; lessons=on; atlas=on; rescue=on; advice=on.

Medians, with the range across seeds in brackets.

| Measure | Median [range] (seeds) |
| --- | --- |
| The team's furthest stage (index in the list below) | 6.0 [5 to 6] (n=10) |
| Deaths a game hour, the team | 0.5 [0.0 to 4.0] (n=10) |
| Stuck spells, the team | 4.0 [2 to 26] (n=10) |
| Stuck game minutes, the team | 12.0 [6 to 78] (n=10) |
| Rescue plans, the team (of them, out) | 0.0 [0 to 0] (n=10) (0.0 [0 to 0] (n=10)) |
| Rescues asked (rounds), the team | 0.0 [0 to 0] (n=10) |
| Rescue spells ended, the team (of them, out; rounds in them) | 0.0 [0 to 0] (n=10) (0.0 [0 to 0] (n=10); 0.0 [0 to 0] (n=10)) |
| Rescue recipes tried first, the team (spells out by a recipe) | 0.0 [0 to 0] (n=10) (0.0 [0 to 0] (n=10)) |
| Game seconds from a spell's last progress to its ask: each seed's median / every ask | - / - |
| Model tokens, the team | 0.0 [0 to 0] (n=10) |
| Lines spoken a game minute (between AI players, and said first to people) | 0.0 [0.0 to 0.0] (n=10) |
| Lines between AI players said / said first to people / meetings noted with nobody to hear | 0.0 [0 to 0] (n=10) / 0.0 [0 to 0] (n=10) / 0.0 [0 to 0] (n=10) |
| First to wooden_tool, game minutes (a seed not reaching it counted as later than all) | 0.65 [0.2 to 3.5] (reached on 10 of 10) |
| First to stone_tools, game minutes (a seed not reaching it counted as later than all) | 1.7 [1.1 to 5.1] (reached on 10 of 10) |
| First to iron_ingot, game minutes (a seed not reaching it counted as later than all) | 6.05 [3.2 to 9.1] (reached on 10 of 10) |
| First to iron_pickaxe, game minutes (a seed not reaching it counted as later than all) | 14.4 [10.4 to 25.7] (reached on 10 of 10) |
| First to diamond, game minutes (a seed not reaching it counted as later than all) | not reached [39.7 to not reached] (reached on 1 of 10) |
| First to obsidian, game minutes (a seed not reaching it counted as later than all) | 42.8 [30.0 to 86.6] (reached on 10 of 10) |
| First to portal_lit, game minutes (a seed not reaching it counted as later than all) | 58.45 [31.1 to not reached] (reached on 8 of 10) |

Stages: 0 wooden_tool, 1 stone_tools, 2 iron_ingot, 3 iron_pickaxe, 4 diamond, 5 obsidian, 6 portal_lit, 7 nether_entered, 8 blaze_rods, 9 eyes, 10 stronghold, 11 end, 12 dragon.

Deaths by cause, all seeds: zombie 9, skeleton 4, creeper 3, drowning 2, lava 2, other 1, spider 1.

Stuck spells by kind, all seeds (F-26c): water loop 11 (14%), no walkable route 4 (5%), hole or pit 25 (33%), hemmed in 17 (22%), waiting for daylight or a teammate 3 (4%), mining for a find 5 (7%), busy-work loop 11 (14%).

Deaths by kind, all seeds (F-26c): lava 2 (9%), drowning 2 (9%), starving with a mob near 1 (5%), cornered 13 (59%), dark melee 1 (5%), other 3 (14%).

| Seed | Ticks run | Team furthest | Deaths/h | Per player: furthest, deaths, stuck spells, RunMetrics (done/blocked, worst repeat, recovered %, walks in vain) |
| --- | --- | --- | --- | --- |
| 101 | 144000 | portal_lit | 1.0 | Kindred: iron_pickaxe, 0, 2; Sol: iron_pickaxe, 2, 2; Echo: portal_lit, 0, 0 |
| 202 | 144000 | portal_lit | 0.5 | Kindred: portal_lit, 1, 1; Sol: portal_lit, 0, 1; Echo: portal_lit, 0, 1 |
| 20261030 | 144000 | portal_lit | 0.5 | Kindred: portal_lit, 1, 1; Sol: portal_lit, 0, 1; Echo: portal_lit, 0, 1 |
| 303 | 144000 | portal_lit | 0.5 | Kindred: portal_lit, 0, 0; Sol: obsidian, 1, 1; Echo: portal_lit, 0, 1 |
| 31102026 | 144000 | obsidian | 0.0 | Kindred: iron_pickaxe, 0, 0; Sol: iron_ingot, 0, 3; Echo: obsidian, 0, 4 |
| 404 | 144000 | portal_lit | 1.5 | Kindred: iron_pickaxe, 3, 2; Sol: portal_lit, 0, 1; Echo: portal_lit, 0, 0 |
| 505 | 144000 | portal_lit | 2.5 | Kindred: portal_lit, 0, 0; Sol: obsidian, 3, 0; Echo: iron_ingot, 2, 6 |
| 606 | 144000 | portal_lit | 0.0 | Kindred: obsidian, 0, 9; Sol: portal_lit, 0, 1; Echo: iron_pickaxe, 0, 16 |
| 707 | 144000 | portal_lit | 0.5 | Kindred: portal_lit, 0, 2; Sol: obsidian, 1, 2; Echo: portal_lit, 0, 0 |
| 8675309 | 144000 | obsidian | 4.0 | Kindred: obsidian, 0, 10; Sol: iron_pickaxe, 3, 6; Echo: iron_pickaxe, 5, 2 |

## The server's load

Each seed's mean tick (over the run, from the server's own last-hundred mean once a game second), its worst tick and when it fell (ticks into the run), the ticks that took over 50 and over 200 ms, and its heap at most. A run from before these were kept shows a dash for them.

| Seed | Mean tick, ms | Worst tick, ms | Worst at tick | Over 50 ms | Over 200 ms | Heap at most, MB |
| --- | --- | --- | --- | --- | --- | --- |
| 101 | 10.81 | 3075.31 | 1 | 406 | 13 | 1084 |
| 202 | 15.97 | 1947.76 | 77693 | 815 | 10 | 1470 |
| 20261030 | 15.83 | 2760.63 | 1 | 760 | 12 | 969 |
| 303 | 16.61 | 1607.21 | 74431 | 946 | 9 | 1140 |
| 31102026 | 14.39 | 3200.84 | 1 | 952 | 12 | 1064 |
| 404 | 11.53 | 2103.85 | 66989 | 387 | 9 | 926 |
| 505 | 11.80 | 1834.29 | 77083 | 554 | 11 | 1291 |
| 606 | 10.72 | 941.84 | 361 | 329 | 7 | 955 |
| 707 | 13.37 | 1187.40 | 60257 | 374 | 11 | 1124 |
| 8675309 | 9.16 | 791.73 | 5838 | 140 | 4 | 1191 |
