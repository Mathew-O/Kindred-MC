# Evaluation summary: factions-e40a16seeds-d5057a4

Runs: 10 seeds (101, 202, 20261030, 303, 31102026, 404, 505, 606, 707, 8675309), EARNED. Scenario: starters=Kindred,Sol,Echo; goal=nether; edits=on; ticks=144000; sprint=on; strategist=off; voice=off; keep=none; lessons=on; atlas=on; rescue=on; advice=on; mode=factions.

Medians, with the range across seeds in brackets.

| Measure | Median [range] (seeds) |
| --- | --- |
| The team's furthest stage (index in the list below) | 5.5 [2 to 6] (n=10) |
| Deaths a game hour, the team | 0.75 [0.0 to 3.0] (n=10) |
| Stuck spells, the team | 14.0 [6 to 27] (n=10) |
| Stuck game minutes, the team | 42.0 [18 to 81] (n=10) |
| Rescue plans, the team (of them, out) | 0.0 [0 to 0] (n=10) (0.0 [0 to 0] (n=10)) |
| Rescues asked (rounds), the team | 0.0 [0 to 0] (n=10) |
| Rescue spells ended, the team (of them, out; rounds in them) | 0.0 [0 to 0] (n=10) (0.0 [0 to 0] (n=10); 0.0 [0 to 0] (n=10)) |
| Rescue recipes tried first, the team (spells out by a recipe) | 0.0 [0 to 0] (n=10) (0.0 [0 to 0] (n=10)) |
| Game seconds from a spell's last progress to its ask: each seed's median / every ask | - / - |
| Model tokens, the team | 0.0 [0 to 0] (n=10) |
| Lines spoken a game minute (between AI players, and said first to people) | 0.0 [0.0 to 0.0] (n=10) |
| Lines between AI players said / said first to people / meetings noted with nobody to hear | 0.0 [0 to 0] (n=10) / 0.0 [0 to 0] (n=10) / 0.0 [0 to 0] (n=10) |
| First to wooden_tool, game minutes (a seed not reaching it counted as later than all) | 6.5 [1.1 to 80.0] (reached on 10 of 10) |
| First to stone_tools, game minutes (a seed not reaching it counted as later than all) | 8.65 [2.5 to 81.2] (reached on 10 of 10) |
| First to iron_ingot, game minutes (a seed not reaching it counted as later than all) | 17.25 [8.1 to 107.4] (reached on 10 of 10) |
| First to iron_pickaxe, game minutes (a seed not reaching it counted as later than all) | 45.7 [17.5 to not reached] (reached on 9 of 10) |
| First to obsidian, game minutes (a seed not reaching it counted as later than all) | 86.85 [34.6 to not reached] (reached on 7 of 10) |
| First to portal_lit, game minutes (a seed not reaching it counted as later than all) | not reached [38.6 to not reached] (reached on 5 of 10) |

Stages: 0 wooden_tool, 1 stone_tools, 2 iron_ingot, 3 iron_pickaxe, 4 diamond, 5 obsidian, 6 portal_lit, 7 nether_entered, 8 blaze_rods, 9 eyes, 10 stronghold, 11 end, 12 dragon.

Deaths by cause, all seeds: zombie 8, skeleton 6, drowning 5, slime 1, creeper 1, spider 1, suffocation 1.

Stuck spells by kind, all seeds (F-26c): water loop 22 (14%), no walkable route 48 (31%), refused target repeated 1 (1%), hole or pit 40 (26%), hemmed in 5 (3%), waiting for daylight or a teammate 2 (1%), mining for a find 11 (7%), busy-work loop 25 (16%).

Deaths by kind, all seeds (F-26c): drowning 5 (22%), starving with a mob near 3 (13%), cornered 9 (39%), dark melee 2 (9%), other 4 (17%).

| Seed | Ticks run | Team furthest | Deaths/h | Per player: furthest, deaths, stuck spells, RunMetrics (done/blocked, worst repeat, recovered %, walks in vain) |
| --- | --- | --- | --- | --- |
| 101 | 144152 | iron_pickaxe | 3.0 | Kindred: iron_pickaxe, 0, 2; Sol: iron_ingot, 4, 3; Echo: iron_ingot, 2, 1 |
| 202 | 144583 | obsidian | 0.5 | Kindred: iron_pickaxe, 0, 4; Sol: obsidian, 0, 5; Echo: iron_pickaxe, 1, 3 |
| 20261030 | 144001 | iron_ingot | 2.0 | Kindred: none, 3, 10; Sol: none, 0, 8; Echo: iron_ingot, 1, 7 |
| 303 | 144094 | obsidian | 0.5 | Kindred: obsidian, 0, 6; Sol: stone_tools, 1, 2; Echo: iron_pickaxe, 0, 7 |
| 31102026 | 144104 | portal_lit | 0.0 | Kindred: iron_pickaxe, 0, 4; Sol: portal_lit, 0, 3; Echo: iron_pickaxe, 0, 4 |
| 404 | 144084 | portal_lit | 2.5 | Kindred: iron_ingot, 0, 12; Sol: portal_lit, 1, 1; Echo: iron_pickaxe, 4, 14 |
| 505 | 144161 | portal_lit | 0.0 | Kindred: portal_lit, 0, 8; Sol: portal_lit, 0, 4; Echo: portal_lit, 0, 1 |
| 606 | 144415 | iron_pickaxe | 1.5 | Kindred: iron_pickaxe, 0, 5; Sol: iron_pickaxe, 2, 6; Echo: iron_pickaxe, 1, 4 |
| 707 | 144065 | portal_lit | 0.5 | Kindred: iron_pickaxe, 0, 13; Sol: portal_lit, 1, 1; Echo: iron_pickaxe, 0, 8 |
| 8675309 | 144124 | portal_lit | 1.0 | Kindred: iron_pickaxe, 2, 1; Sol: portal_lit, 0, 3; Echo: portal_lit, 0, 4 |

## The server's load

Each seed's mean tick (over the run, from the server's own last-hundred mean once a game second), its worst tick and when it fell (ticks into the run), the ticks that took over 50 and over 200 ms, and its heap at most. A run from before these were kept shows a dash for them.

| Seed | Mean tick, ms | Worst tick, ms | Worst at tick | Over 50 ms | Over 200 ms | Heap at most, MB |
| --- | --- | --- | --- | --- | --- | --- |
| 101 | 10.54 | 2973.80 | 15 | 633 | 16 | 1149 |
| 202 | 15.94 | 1563.94 | 5838 | 1542 | 11 | 1647 |
| 20261030 | 15.03 | 3330.43 | 5 | 1999 | 32 | 1536 |
| 303 | 16.25 | 1784.79 | 5838 | 1844 | 13 | 1625 |
| 31102026 | 14.49 | 1826.53 | 106028 | 1342 | 20 | 1115 |
| 404 | 15.67 | 1828.94 | 5838 | 993 | 12 | 1312 |
| 505 | 16.92 | 2482.86 | 46352 | 1179 | 10 | 1622 |
| 606 | 9.93 | 1210.08 | 5838 | 439 | 4 | 1331 |
| 707 | 22.34 | 1402.90 | 1794 | 2983 | 10 | 1330 |
| 8675309 | 14.79 | 1390.37 | 5838 | 431 | 5 | 1374 |

## The faction world's audit (M-06i)

Every claim and capture decided again from the World Record by the rules, the safe zone, coins and the auction house's hand-overs.

| Seed | Audited | Factions | Claims | Captures | Sales | Kills | Rules broken |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 101 | yes | 3 | 11 | 0 | 141 | 0 | 0 |
| 202 | yes | 3 | 10 | 0 | 347 | 0 | 0 |
| 20261030 | yes | 3 | 7 | 0 | 46 | 0 | 0 |
| 303 | yes | 3 | 7 | 0 | 153 | 0 | 0 |
| 31102026 | yes | 3 | 10 | 0 | 161 | 0 | 0 |
| 404 | yes | 3 | 8 | 0 | 144 | 0 | 0 |
| 505 | yes | 3 | 7 | 0 | 121 | 0 | 0 |
| 606 | yes | 3 | 10 | 0 | 238 | 0 | 0 |
| 707 | yes | 3 | 10 | 0 | 135 | 0 | 0 |
| 8675309 | yes | 3 | 10 | 0 | 185 | 0 | 0 |

No rule broken on any seed.
