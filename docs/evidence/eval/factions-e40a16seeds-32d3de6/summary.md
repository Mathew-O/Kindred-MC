# Evaluation summary: factions-e40a16seeds-32d3de6

Runs: 10 seeds (101, 202, 20261030, 303, 31102026, 404, 505, 606, 707, 8675309), EARNED. Scenario: starters=Kindred,Sol,Echo; goal=nether; edits=on; ticks=144000; sprint=on; strategist=off; voice=off; keep=none; lessons=on; atlas=on; rescue=on; advice=on; mode=factions.

Medians, with the range across seeds in brackets.

| Measure | Median [range] (seeds) |
| --- | --- |
| The team's furthest stage (index in the list below) | 6.0 [2 to 6] (n=10) |
| Deaths a game hour, the team | 1.0 [0.5 to 2.5] (n=10) |
| Stuck spells, the team | 11.0 [1 to 24] (n=10) |
| Stuck game minutes, the team | 33.0 [3 to 72] (n=10) |
| Rescue plans, the team (of them, out) | 0.0 [0 to 0] (n=10) (0.0 [0 to 0] (n=10)) |
| Rescues asked (rounds), the team | 0.0 [0 to 0] (n=10) |
| Rescue spells ended, the team (of them, out; rounds in them) | 0.0 [0 to 0] (n=10) (0.0 [0 to 0] (n=10); 0.0 [0 to 0] (n=10)) |
| Rescue recipes tried first, the team (spells out by a recipe) | 0.0 [0 to 0] (n=10) (0.0 [0 to 0] (n=10)) |
| Game seconds from a spell's last progress to its ask: each seed's median / every ask | - / - |
| Model tokens, the team | 0.0 [0 to 0] (n=10) |
| Lines spoken a game minute (between AI players, and said first to people) | 0.0 [0.0 to 0.0] (n=10) |
| Lines between AI players said / said first to people / meetings noted with nobody to hear | 0.0 [0 to 0] (n=10) / 0.0 [0 to 0] (n=10) / 0.0 [0 to 1] (n=10) |
| First to wooden_tool, game minutes (a seed not reaching it counted as later than all) | 5.05 [0.4 to 64.0] (reached on 10 of 10) |
| First to stone_tools, game minutes (a seed not reaching it counted as later than all) | 7.3 [2.1 to 65.7] (reached on 10 of 10) |
| First to iron_ingot, game minutes (a seed not reaching it counted as later than all) | 11.4 [7.8 to 68.6] (reached on 10 of 10) |
| First to iron_pickaxe, game minutes (a seed not reaching it counted as later than all) | 37.3 [21.8 to not reached] (reached on 9 of 10) |
| First to diamond, game minutes (a seed not reaching it counted as later than all) | not reached [87.6 to not reached] (reached on 1 of 10) |
| First to obsidian, game minutes (a seed not reaching it counted as later than all) | 92.4 [44.0 to not reached] (reached on 7 of 10) |
| First to portal_lit, game minutes (a seed not reaching it counted as later than all) | 100.95 [44.7 to not reached] (reached on 6 of 10) |

Stages: 0 wooden_tool, 1 stone_tools, 2 iron_ingot, 3 iron_pickaxe, 4 diamond, 5 obsidian, 6 portal_lit, 7 nether_entered, 8 blaze_rods, 9 eyes, 10 stronghold, 11 end, 12 dragon.

Deaths by cause, all seeds: zombie 10, skeleton 6, drowning 4, spider 1, drowned 1, suffocation 1, enderman 1, stray 1.

Stuck spells by kind, all seeds (F-26c): water loop 7 (6%), no walkable route 31 (25%), hole or pit 40 (32%), hemmed in 20 (16%), mining for a find 8 (6%), busy-work loop 20 (16%).

Deaths by kind, all seeds (F-26c): drowning 4 (16%), cornered 15 (60%), dark melee 3 (12%), other 3 (12%).

| Seed | Ticks run | Team furthest | Deaths/h | Per player: furthest, deaths, stuck spells, RunMetrics (done/blocked, worst repeat, recovered %, walks in vain) |
| --- | --- | --- | --- | --- |
| 101 | 144358 | portal_lit | 1.0 | Kindred: iron_pickaxe, 2, 1; Sol: iron_ingot, 0, 0; Echo: portal_lit, 0, 0 |
| 202 | 144342 | portal_lit | 0.5 | Kindred: portal_lit, 0, 0; Sol: portal_lit, 1, 1; Echo: obsidian, 0, 3 |
| 20261030 | 144001 | iron_ingot | 0.5 | Kindred: none, 0, 9; Sol: iron_ingot, 1, 7; Echo: iron_ingot, 0, 6 |
| 303 | 144079 | iron_pickaxe | 0.5 | Kindred: iron_pickaxe, 1, 7; Sol: iron_pickaxe, 0, 9; Echo: iron_pickaxe, 0, 8 |
| 31102026 | 144039 | iron_pickaxe | 1.0 | Kindred: iron_pickaxe, 1, 5; Sol: iron_pickaxe, 0, 9; Echo: iron_pickaxe, 1, 3 |
| 404 | 144136 | portal_lit | 2.0 | Kindred: portal_lit, 0, 1; Sol: iron_pickaxe, 3, 4; Echo: iron_pickaxe, 1, 6 |
| 505 | 144243 | portal_lit | 2.5 | Kindred: portal_lit, 2, 0; Sol: iron_pickaxe, 2, 5; Echo: iron_pickaxe, 1, 4 |
| 606 | 144318 | portal_lit | 1.0 | Kindred: portal_lit, 2, 2; Sol: obsidian, 0, 5; Echo: obsidian, 0, 3 |
| 707 | 144127 | portal_lit | 2.0 | Kindred: portal_lit, 1, 1; Sol: iron_pickaxe, 0, 4; Echo: iron_pickaxe, 3, 6 |
| 8675309 | 144245 | obsidian | 1.5 | Kindred: iron_pickaxe, 1, 3; Sol: obsidian, 0, 2; Echo: iron_ingot, 2, 12 |

## The server's load

Each seed's mean tick (over the run, from the server's own last-hundred mean once a game second), its worst tick and when it fell (ticks into the run), the ticks that took over 50 and over 200 ms, and its heap at most. A run from before these were kept shows a dash for them.

| Seed | Mean tick, ms | Worst tick, ms | Worst at tick | Over 50 ms | Over 200 ms | Heap at most, MB |
| --- | --- | --- | --- | --- | --- | --- |
| 101 | 13.33 | 1232.99 | 5838 | 661 | 18 | 1156 |
| 202 | 16.47 | 2614.11 | 73605 | 1602 | 12 | 1205 |
| 20261030 | 14.34 | 3482.92 | 7 | 1712 | 41 | 1429 |
| 303 | 12.70 | 1960.02 | 8 | 1080 | 10 | 1437 |
| 31102026 | 14.47 | 1979.69 | 7 | 1168 | 10 | 1559 |
| 404 | 17.40 | 1445.80 | 5838 | 2299 | 12 | 1183 |
| 505 | 19.48 | 2111.51 | 21012 | 2272 | 13 | 1369 |
| 606 | 12.51 | 1377.57 | 36683 | 421 | 10 | 1316 |
| 707 | 14.68 | 1287.50 | 5838 | 1636 | 11 | 1326 |
| 8675309 | 15.73 | 797.82 | 5838 | 362 | 6 | 972 |

## The faction world's audit (M-06i)

Every claim and capture decided again from the World Record by the rules, the safe zone, coins and the auction house's hand-overs.

| Seed | Audited | Factions | Claims | Captures | Sales | Kills | Rules broken |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 101 | yes | 3 | 11 | 0 | 47 | 0 | 0 |
| 202 | yes | 3 | 10 | 0 | 50 | 0 | 0 |
| 20261030 | yes | 3 | 10 | 0 | 13 | 0 | 0 |
| 303 | yes | 3 | 8 | 0 | 20 | 0 | 0 |
| 31102026 | yes | 3 | 10 | 0 | 28 | 0 | 0 |
| 404 | yes | 3 | 7 | 0 | 17 | 0 | 0 |
| 505 | yes | 3 | 10 | 0 | 35 | 0 | 0 |
| 606 | yes | 3 | 10 | 0 | 47 | 0 | 0 |
| 707 | yes | 3 | 10 | 0 | 21 | 0 | 0 |
| 8675309 | yes | 3 | 10 | 0 | 38 | 0 | 0 |

No rule broken on any seed.
