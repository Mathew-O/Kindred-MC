# Evaluation summary: e40c5-b6f2d4b

Runs: 5 seeds (101, 202, 20261030, 31102026, 404), EARNED. Scenario: starters=Kindred,Sol,Echo; goal=dragon; edits=on; ticks=288000; sprint=on; strategist=off; voice=off; keep=none; lessons=on; atlas=on; rescue=on; advice=on.
No result from: 303.
Fewer than ten seeds: too few to compare with another build.

Medians, with the range across seeds in brackets.

| Measure | Median [range] (seeds) |
| --- | --- |
| The team's furthest stage (index in the list below) | 6 [6 to 7] (n=5) |
| Deaths a game hour, the team | 0.75 [0.25 to 1.5] (n=5) |
| The world's days over the run | 12.0 [12.0 to 18.06] (n=5) |
| Deaths a game day, the team | 0.25 [0.06 to 0.5] (n=5) |
| Stuck game minutes a game day, the team | 2.77 [0.0 to 8.75] (n=5) |
| Chunk-loads a game hour, the team | 5092.0 [1937.2 to 12044.2] (n=5) |
| Chunk-minutes a game hour, the team | 52016.4 [52012.8 to 52018.8] (n=5) |
| Bridge blocks a second, the team | 1.44 [0.88 to 1.86] (n=4) |
| Stuck spells, the team | 14 [0 to 35] (n=5) |
| Stuck game minutes, the team | 42 [0 to 105] (n=5) |
| Blocked for reconciliation, game minutes, the team (apart from stuck) | 0 [0 to 0] (n=5) |
| Blocks for reconciliation, the team | 0 [0 to 0] (n=5) |
| Rescue plans, the team (of them, out) | 0 [0 to 0] (n=5) (0 [0 to 0] (n=5)) |
| Rescues asked (rounds), the team | 0 [0 to 0] (n=5) |
| Rescue spells ended, the team (of them, out; rounds in them) | 0 [0 to 0] (n=5) (0 [0 to 0] (n=5); 0 [0 to 0] (n=5)) |
| Rescue recipes tried first, the team (spells out by a recipe) | 0 [0 to 0] (n=5) (0 [0 to 0] (n=5)) |
| Game seconds from a spell's last progress to its ask: each seed's median / every ask | - / - |
| Model tokens, the team | 0 [0 to 0] (n=5) |
| Lines spoken a game minute (between AI players, and said first to people) | 0.0 [0.0 to 0.0] (n=5) |
| Lines between AI players said / said first to people / meetings noted with nobody to hear | 0 [0 to 0] (n=5) / 0 [0 to 0] (n=5) / 0 [0 to 1] (n=5) |
| First to wooden_tool, game minutes (a seed not reaching it counted as later than all) | 0.9 [0.3 to 5.6] (reached on 5 of 5) |
| First to stone_tools, game minutes (a seed not reaching it counted as later than all) | 1.9 [1.2 to 6.2] (reached on 5 of 5) |
| First to iron_ingot, game minutes (a seed not reaching it counted as later than all) | 6.9 [4.7 to 10.4] (reached on 5 of 5) |
| First to iron_pickaxe, game minutes (a seed not reaching it counted as later than all) | 20.2 [15.5 to 64.0] (reached on 5 of 5) |
| First to diamond, game minutes (a seed not reaching it counted as later than all) | 74.6 [38.0 to not reached] (reached on 4 of 5) |
| First to obsidian, game minutes (a seed not reaching it counted as later than all) | 40.6 [33.6 to 77.7] (reached on 5 of 5) |
| First to portal_lit, game minutes (a seed not reaching it counted as later than all) | 74.8 [43.0 to 141.7] (reached on 5 of 5) |
| First to nether_entered, game minutes (a seed not reaching it counted as later than all) | not reached [64.6 to not reached] (reached on 2 of 5) |

Stages: 0 wooden_tool, 1 stone_tools, 2 iron_ingot, 3 iron_pickaxe, 4 diamond, 5 obsidian, 6 portal_lit, 7 nether_entered, 8 blaze_rods, 9 eyes, 10 stronghold, 11 end, 12 dragon.

Deaths by cause, all seeds: zombie 5, skeleton 4, fall 2, creeper 2, husk 1, suffocation 1.

Stuck spells by kind, all seeds (F-26c): water loop 5 (6%), no walkable route 10 (12%), hole or pit 11 (13%), hemmed in 24 (28%), mining for a find 3 (4%), busy-work loop 31 (36%), other 1 (1%).

Deaths by kind, all seeds (F-26c): fall 2 (13%), starving with a mob near 3 (20%), cornered 3 (20%), dark melee 2 (13%), other 5 (33%).

| Seed | Ticks run | Team furthest | Deaths/h | Per player: furthest, deaths, stuck spells, RunMetrics (done/blocked, worst repeat, recovered %, walks in vain) |
| --- | --- | --- | --- | --- |
| 101 | 288000 | portal_lit | 0.5 | Kindred: portal_lit, 1, 3; Sol: obsidian, 1, 11; Echo: portal_lit, 0, 0 |
| 202 | 288000 | nether_entered | 0.25 | Kindred: obsidian, 1, 17; Sol: obsidian, 0, 15; Echo: nether_entered, 0, 1 |
| 20261030 | 288000 | portal_lit | 0.75 | Kindred: iron_pickaxe, 0, 0; Sol: iron_ingot, 2, 0; Echo: portal_lit, 1, 0 |
| 31102026 | 288000 | nether_entered | 1.5 | Kindred: iron_ingot, 5, 3; Sol: portal_lit, 1, 0; Echo: nether_entered, 0, 0 |
| 404 | 288000 | portal_lit | 0.75 | Kindred: iron_pickaxe, 1, 2; Sol: diamond, 1, 21; Echo: portal_lit, 1, 12 |

## The server's load

Each seed's mean tick (over the run, from the server's own last-hundred mean once a game second), its worst tick and when it fell (ticks into the run), the ticks that took over 50 and over 200 ms, and its heap at most. A run from before these were kept shows a dash for them.

| Seed | Mean tick, ms | Worst tick, ms | Worst at tick | Over 50 ms | Over 200 ms | Heap at most, MB |
| --- | --- | --- | --- | --- | --- | --- |
| 101 | 17.49 | 1800.69 | 25 | 1104 | 19 | 1165 |
| 202 | 24.14 | 1792.23 | 70207 | 10566 | 13 | 1364 |
| 20261030 | 14.34 | 2002.70 | 51657 | 624 | 15 | 913 |
| 31102026 | 20.80 | 3962.05 | 1 | 2204 | 27 | 1095 |
| 404 | 15.40 | 2552.85 | 170158 | 694 | 28 | 965 |
