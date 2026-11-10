# Evaluation summary: factions-e40a16seeds-b7b4bec

Runs: 10 seeds (101, 202, 20261030, 303, 31102026, 404, 505, 606, 707, 8675309), EARNED. Scenario: starters=Kindred,Sol,Echo; goal=nether; edits=on; ticks=144000; sprint=on; strategist=off; voice=off; keep=none; lessons=on; atlas=on; rescue=on; advice=on; mode=factions.

Medians, with the range across seeds in brackets.

| Measure | Median [range] (seeds) |
| --- | --- |
| The team's furthest stage (index in the list below) | 4.0 [2 to 6] (n=10) |
| Deaths a game hour, the team | 1.25 [0.5 to 3.5] (n=10) |
| Stuck spells, the team | 9.0 [4 to 18] (n=10) |
| Stuck game minutes, the team | 27.0 [12 to 54] (n=10) |
| Rescue plans, the team (of them, out) | 0.0 [0 to 0] (n=10) (0.0 [0 to 0] (n=10)) |
| Rescues asked (rounds), the team | 0.0 [0 to 0] (n=10) |
| Rescue spells ended, the team (of them, out; rounds in them) | 0.0 [0 to 0] (n=10) (0.0 [0 to 0] (n=10); 0.0 [0 to 0] (n=10)) |
| Rescue recipes tried first, the team (spells out by a recipe) | 0.0 [0 to 0] (n=10) (0.0 [0 to 0] (n=10)) |
| Game seconds from a spell's last progress to its ask: each seed's median / every ask | - / - |
| Model tokens, the team | 0.0 [0 to 0] (n=10) |
| Lines spoken a game minute (between AI players, and said first to people) | 0.0 [0.0 to 0.0] (n=10) |
| Lines between AI players said / said first to people / meetings noted with nobody to hear | 0.0 [0 to 0] (n=10) / 0.0 [0 to 0] (n=10) / 0.0 [0 to 1] (n=10) |
| First to wooden_tool, game minutes (a seed not reaching it counted as later than all) | 1.5 [0.3 to 64.5] (reached on 10 of 10) |
| First to stone_tools, game minutes (a seed not reaching it counted as later than all) | 4.5 [2.0 to 64.9] (reached on 10 of 10) |
| First to iron_ingot, game minutes (a seed not reaching it counted as later than all) | 14.4 [5.0 to 67.2] (reached on 10 of 10) |
| First to iron_pickaxe, game minutes (a seed not reaching it counted as later than all) | 43.0 [21.4 to not reached] (reached on 9 of 10) |
| First to diamond, game minutes (a seed not reaching it counted as later than all) | not reached [69.5 to not reached] (reached on 1 of 10) |
| First to obsidian, game minutes (a seed not reaching it counted as later than all) | not reached [41.8 to not reached] (reached on 5 of 10) |
| First to portal_lit, game minutes (a seed not reaching it counted as later than all) | not reached [44.9 to not reached] (reached on 4 of 10) |

Stages: 0 wooden_tool, 1 stone_tools, 2 iron_ingot, 3 iron_pickaxe, 4 diamond, 5 obsidian, 6 portal_lit, 7 nether_entered, 8 blaze_rods, 9 eyes, 10 stronghold, 11 end, 12 dragon.

Deaths by cause, all seeds: zombie 10, drowning 5, skeleton 5, spider 2, creeper 2, husk 2, lava 1, witch 1, suffocation 1, polar_bear 1, drowned 1.

Stuck spells by kind, all seeds (F-26c): water loop 8 (8%), no walkable route 8 (8%), hole or pit 60 (61%), hemmed in 7 (7%), mining for a find 8 (8%), busy-work loop 8 (8%).

Deaths by kind, all seeds (F-26c): lava 1 (3%), drowning 5 (16%), starving with a mob near 4 (13%), cornered 13 (42%), dark melee 3 (10%), other 5 (16%).

| Seed | Ticks run | Team furthest | Deaths/h | Per player: furthest, deaths, stuck spells, RunMetrics (done/blocked, worst repeat, recovered %, walks in vain) |
| --- | --- | --- | --- | --- |
| 101 | 144164 | portal_lit | 1.0 | Kindred: obsidian, 2, 2; Sol: portal_lit, 0, 1; Echo: portal_lit, 0, 1 |
| 202 | 144190 | iron_pickaxe | 0.5 | Kindred: iron_pickaxe, 1, 5; Sol: iron_pickaxe, 0, 4; Echo: iron_pickaxe, 0, 4 |
| 20261030 | 144001 | iron_ingot | 2.0 | Kindred: iron_ingot, 0, 7; Sol: iron_ingot, 1, 3; Echo: none, 3, 5 |
| 303 | 144106 | portal_lit | 1.0 | Kindred: portal_lit, 0, 1; Sol: iron_pickaxe, 2, 3; Echo: iron_pickaxe, 0, 3 |
| 31102026 | 144119 | iron_pickaxe | 2.0 | Kindred: iron_ingot, 4, 1; Sol: iron_pickaxe, 0, 2; Echo: iron_pickaxe, 0, 6 |
| 404 | 144137 | iron_pickaxe | 2.5 | Kindred: iron_pickaxe, 2, 5; Sol: stone_tools, 3, 6; Echo: iron_pickaxe, 0, 7 |
| 505 | 144209 | portal_lit | 1.5 | Kindred: portal_lit, 0, 2; Sol: iron_pickaxe, 1, 1; Echo: iron_ingot, 2, 3 |
| 606 | 144156 | portal_lit | 0.5 | Kindred: portal_lit, 1, 0; Sol: iron_pickaxe, 0, 6; Echo: portal_lit, 0, 3 |
| 707 | 144120 | iron_pickaxe | 3.5 | Kindred: iron_pickaxe, 3, 4; Sol: iron_pickaxe, 4, 6; Echo: iron_pickaxe, 0, 1 |
| 8675309 | 144141 | obsidian | 1.0 | Kindred: iron_ingot, 1, 0; Sol: obsidian, 0, 2; Echo: iron_pickaxe, 1, 5 |

## The server's load

Each seed's mean tick (over the run, from the server's own last-hundred mean once a game second), its worst tick and when it fell (ticks into the run), the ticks that took over 50 and over 200 ms, and its heap at most. A run from before these were kept shows a dash for them.

| Seed | Mean tick, ms | Worst tick, ms | Worst at tick | Over 50 ms | Over 200 ms | Heap at most, MB |
| --- | --- | --- | --- | --- | --- | --- |
| 101 | 13.86 | 2021.18 | 7 | 569 | 18 | 1319 |
| 202 | 19.57 | 2207.01 | 5838 | 3531 | 15 | 1406 |
| 20261030 | 17.04 | 3872.59 | 7 | 2204 | 43 | 1695 |
| 303 | 17.16 | 1635.26 | 5838 | 2349 | 10 | 1172 |
| 31102026 | 13.30 | 1585.66 | 5838 | 645 | 14 | 1369 |
| 404 | 13.95 | 2214.54 | 5838 | 1186 | 10 | 1302 |
| 505 | 11.11 | 2059.67 | 97227 | 835 | 10 | 1133 |
| 606 | 16.76 | 1945.07 | 106166 | 3075 | 13 | 1142 |
| 707 | 15.42 | 1781.60 | 5838 | 1702 | 18 | 1134 |
| 8675309 | 12.28 | 1496.21 | 5838 | 666 | 4 | 1362 |

## The faction world's audit (M-06i)

Every claim and capture decided again from the World Record by the rules, the safe zone, coins and the auction house's hand-overs.

| Seed | Audited | Factions | Claims | Captures | Sales | Kills | Rules broken |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 101 | yes | 3 | 10 | 0 | 34 | 0 | 0 |
| 202 | yes | 3 | 10 | 0 | 40 | 0 | 0 |
| 20261030 | yes | 3 | 10 | 0 | 9 | 0 | 0 |
| 303 | yes | 3 | 10 | 0 | 32 | 0 | 0 |
| 31102026 | yes | 3 | 10 | 0 | 33 | 0 | 0 |
| 404 | yes | 3 | 10 | 0 | 22 | 0 | 0 |
| 505 | yes | 3 | 4 | 0 | 17 | 0 | 0 |
| 606 | yes | 3 | 11 | 0 | 37 | 0 | 0 |
| 707 | yes | 3 | 10 | 0 | 17 | 0 | 0 |
| 8675309 | yes | 3 | 10 | 0 | 36 | 0 | 0 |

No rule broken on any seed.
