# Evaluation summary: e40a19-6957e28

Runs: 10 seeds (101, 202, 20261030, 303, 31102026, 404, 505, 606, 707, 8675309), EARNED. Scenario: starters=Kindred,Sol,Echo; goal=nether; edits=on; ticks=144000; sprint=on; strategist=off; voice=off; keep=none; lessons=on; atlas=on; rescue=on; advice=on.

Medians, with the range across seeds in brackets.

| Measure | Median [range] (seeds) |
| --- | --- |
| The team's furthest stage (index in the list below) | 6.0 [5 to 6] (n=10) |
| Deaths a game hour, the team | 0.5 [0.0 to 2.0] (n=10) |
| Stuck spells, the team | 7.5 [1 to 16] (n=10) |
| Stuck game minutes, the team | 22.5 [3 to 48] (n=10) |
| Rescue plans, the team (of them, out) | 0.0 [0 to 0] (n=10) (0.0 [0 to 0] (n=10)) |
| Rescues asked (rounds), the team | 0.0 [0 to 0] (n=10) |
| Rescue spells ended, the team (of them, out; rounds in them) | 0.0 [0 to 0] (n=10) (0.0 [0 to 0] (n=10); 0.0 [0 to 0] (n=10)) |
| Rescue recipes tried first, the team (spells out by a recipe) | 0.0 [0 to 0] (n=10) (0.0 [0 to 0] (n=10)) |
| Game seconds from a spell's last progress to its ask: each seed's median / every ask | - / - |
| Model tokens, the team | 0.0 [0 to 0] (n=10) |
| Lines spoken a game minute (between AI players, and said first to people) | 0.0 [0.0 to 0.0] (n=10) |
| Lines between AI players said / said first to people / meetings noted with nobody to hear | 0.0 [0 to 0] (n=10) / 0.0 [0 to 0] (n=10) / 0.0 [0 to 0] (n=10) |
| First to wooden_tool, game minutes (a seed not reaching it counted as later than all) | 0.75 [0.3 to 3.7] (reached on 10 of 10) |
| First to stone_tools, game minutes (a seed not reaching it counted as later than all) | 1.85 [1.1 to 5.5] (reached on 10 of 10) |
| First to iron_ingot, game minutes (a seed not reaching it counted as later than all) | 4.4 [3.1 to 9.4] (reached on 10 of 10) |
| First to iron_pickaxe, game minutes (a seed not reaching it counted as later than all) | 17.85 [12.7 to 26.4] (reached on 10 of 10) |
| First to diamond, game minutes (a seed not reaching it counted as later than all) | not reached [24.5 to not reached] (reached on 2 of 10) |
| First to obsidian, game minutes (a seed not reaching it counted as later than all) | 50.15 [29.5 to 90.5] (reached on 10 of 10) |
| First to portal_lit, game minutes (a seed not reaching it counted as later than all) | 76.45 [36.4 to not reached] (reached on 9 of 10) |

Stages: 0 wooden_tool, 1 stone_tools, 2 iron_ingot, 3 iron_pickaxe, 4 diamond, 5 obsidian, 6 portal_lit, 7 nether_entered, 8 blaze_rods, 9 eyes, 10 stronghold, 11 end, 12 dragon.

Deaths by cause, all seeds: zombie 6, skeleton 3, drowned 2, spider 1, enderman 1.

Stuck spells by kind, all seeds (F-26c): water loop 5 (6%), no walkable route 11 (13%), refused target repeated 1 (1%), hole or pit 21 (25%), hemmed in 16 (19%), mining for a find 8 (9%), busy-work loop 23 (27%).

Deaths by kind, all seeds (F-26c): starving with a mob near 2 (15%), cornered 5 (38%), dark melee 3 (23%), other 3 (23%).

| Seed | Ticks run | Team furthest | Deaths/h | Per player: furthest, deaths, stuck spells, RunMetrics (done/blocked, worst repeat, recovered %, walks in vain) |
| --- | --- | --- | --- | --- |
| 101 | 144000 | obsidian | 0.5 | Kindred: iron_pickaxe, 0, 0; Sol: iron_pickaxe, 1, 1; Echo: obsidian, 0, 3 |
| 202 | 144000 | portal_lit | 0.0 | Kindred: portal_lit, 0, 3; Sol: iron_pickaxe, 0, 2; Echo: portal_lit, 0, 1 |
| 20261030 | 144000 | portal_lit | 0.0 | Kindred: obsidian, 0, 5; Sol: obsidian, 0, 5; Echo: portal_lit, 0, 3 |
| 303 | 144000 | portal_lit | 0.0 | Kindred: portal_lit, 0, 0; Sol: obsidian, 0, 12; Echo: obsidian, 0, 1 |
| 31102026 | 144000 | portal_lit | 0.0 | Kindred: iron_pickaxe, 0, 1; Sol: iron_pickaxe, 0, 1; Echo: portal_lit, 0, 2 |
| 404 | 144000 | portal_lit | 0.5 | Kindred: obsidian, 1, 5; Sol: obsidian, 0, 11; Echo: portal_lit, 0, 0 |
| 505 | 144000 | portal_lit | 2.0 | Kindred: stone_tools, 2, 6; Sol: portal_lit, 1, 0; Echo: portal_lit, 1, 3 |
| 606 | 144000 | portal_lit | 0.5 | Kindred: portal_lit, 1, 0; Sol: portal_lit, 0, 1; Echo: portal_lit, 0, 0 |
| 707 | 144000 | portal_lit | 1.0 | Kindred: iron_pickaxe, 1, 1; Sol: obsidian, 0, 1; Echo: portal_lit, 1, 3 |
| 8675309 | 144000 | portal_lit | 2.0 | Kindred: iron_pickaxe, 3, 0; Sol: obsidian, 1, 12; Echo: portal_lit, 0, 2 |

## The server's load

Each seed's mean tick (over the run, from the server's own last-hundred mean once a game second), its worst tick and when it fell (ticks into the run), the ticks that took over 50 and over 200 ms, and its heap at most. A run from before these were kept shows a dash for them.

| Seed | Mean tick, ms | Worst tick, ms | Worst at tick | Over 50 ms | Over 200 ms | Heap at most, MB |
| --- | --- | --- | --- | --- | --- | --- |
| 101 | 9.80 | 821.72 | 5838 | 400 | 8 | 959 |
| 202 | 13.43 | 1695.10 | 85379 | 600 | 6 | 1034 |
| 20261030 | 13.61 | 2347.26 | 111805 | 675 | 13 | 1077 |
| 303 | 14.31 | 1530.67 | 5838 | 836 | 8 | 1102 |
| 31102026 | 12.51 | 1352.92 | 5838 | 320 | 10 | 1479 |
| 404 | 10.65 | 2028.76 | 1 | 628 | 8 | 1177 |
| 505 | 10.90 | 1850.99 | 43663 | 317 | 6 | 902 |
| 606 | 10.05 | 2110.52 | 61663 | 186 | 6 | 935 |
| 707 | 14.48 | 976.21 | 35575 | 740 | 17 | 1672 |
| 8675309 | 10.47 | 1155.91 | 98223 | 143 | 4 | 1237 |
