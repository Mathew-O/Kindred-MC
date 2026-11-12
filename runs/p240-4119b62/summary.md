# Evaluation summary: p240-4119b62

Runs: 3 seeds (303, 31102026, 505), EARNED. Scenario: starters=Kindred,Sol,Echo; goal=nether; edits=on; ticks=144000; sprint=on; strategist=off; voice=off; keep=none; lessons=on; atlas=on; rescue=on; advice=on.
Fewer than ten seeds: too few to compare with another build.

Medians, with the range across seeds in brackets.

| Measure | Median [range] (seeds) |
| --- | --- |
| The team's furthest stage (index in the list below) | 5 [5 to 6] (n=3) |
| Deaths a game hour, the team | 1.5 [1.5 to 2.5] (n=3) |
| The world's days over the run | 6.0 [6.0 to 7.54] (n=3) |
| Deaths a game day, the team | 0.5 [0.5 to 0.66] (n=3) |
| Stuck game minutes a game day, the team | 4.5 [1.99 to 6.0] (n=3) |
| Chunk-loads a game hour, the team | 6591.0 [6035.5 to 10736.0] (n=3) |
| Chunk-minutes a game hour, the team | 52012.8 [41630.4 to 52012.8] (n=3) |
| Stuck spells, the team | 9 [5 to 12] (n=3) |
| Stuck game minutes, the team | 27 [15 to 36] (n=3) |
| Rescue plans, the team (of them, out) | 0 [0 to 0] (n=3) (0 [0 to 0] (n=3)) |
| Rescues asked (rounds), the team | 0 [0 to 0] (n=3) |
| Rescue spells ended, the team (of them, out; rounds in them) | 0 [0 to 0] (n=3) (0 [0 to 0] (n=3); 0 [0 to 0] (n=3)) |
| Rescue recipes tried first, the team (spells out by a recipe) | 0 [0 to 0] (n=3) (0 [0 to 0] (n=3)) |
| Game seconds from a spell's last progress to its ask: each seed's median / every ask | - / - |
| Model tokens, the team | 0 [0 to 0] (n=3) |
| Lines spoken a game minute (between AI players, and said first to people) | 0.0 [0.0 to 0.0] (n=3) |
| Lines between AI players said / said first to people / meetings noted with nobody to hear | 0 [0 to 0] (n=3) / 0 [0 to 0] (n=3) / 0 [0 to 0] (n=3) |
| First to wooden_tool, game minutes (a seed not reaching it counted as later than all) | 1.3 [0.8 to 4.0] (reached on 3 of 3) |
| First to stone_tools, game minutes (a seed not reaching it counted as later than all) | 2.2 [2.0 to 4.4] (reached on 3 of 3) |
| First to iron_ingot, game minutes (a seed not reaching it counted as later than all) | 5.8 [4.9 to 6.0] (reached on 3 of 3) |
| First to iron_pickaxe, game minutes (a seed not reaching it counted as later than all) | 19.2 [13.0 to 32.0] (reached on 3 of 3) |
| First to obsidian, game minutes (a seed not reaching it counted as later than all) | 48.1 [29.1 to 119.0] (reached on 3 of 3) |
| First to portal_lit, game minutes (a seed not reaching it counted as later than all) | not reached [30.3 to not reached] (reached on 1 of 3) |

Stages: 0 wooden_tool, 1 stone_tools, 2 iron_ingot, 3 iron_pickaxe, 4 diamond, 5 obsidian, 6 portal_lit, 7 nether_entered, 8 blaze_rods, 9 eyes, 10 stronghold, 11 end, 12 dragon.

Deaths by cause, all seeds: zombie 4, skeleton 4, lava 2, suffocation 1.

Stuck spells by kind, all seeds (F-26c): water loop 2 (8%), no walkable route 1 (4%), refused target repeated 1 (4%), hole or pit 8 (31%), mining for a find 3 (12%), busy-work loop 11 (42%).

Deaths by kind, all seeds (F-26c): lava 2 (18%), cornered 5 (45%), other 4 (36%).

| Seed | Ticks run | Team furthest | Deaths/h | Per player: furthest, deaths, stuck spells, RunMetrics (done/blocked, worst repeat, recovered %, walks in vain) |
| --- | --- | --- | --- | --- |
| 303 | 144000 | obsidian | 2.5 | Kindred: iron_pickaxe, 0, 3; Sol: iron_pickaxe, 5, 1; Echo: obsidian, 0, 1 |
| 31102026 | 144000 | obsidian | 1.5 | Kindred: obsidian, 0, 3; Sol: iron_ingot, 1, 6; Echo: iron_pickaxe, 2, 3 |
| 505 | 144000 | portal_lit | 1.5 | Kindred: portal_lit, 0, 0; Sol: portal_lit, 1, 1; Echo: iron_ingot, 2, 8 |

## The server's load

Each seed's mean tick (over the run, from the server's own last-hundred mean once a game second), its worst tick and when it fell (ticks into the run), the ticks that took over 50 and over 200 ms, and its heap at most. A run from before these were kept shows a dash for them.

| Seed | Mean tick, ms | Worst tick, ms | Worst at tick | Over 50 ms | Over 200 ms | Heap at most, MB |
| --- | --- | --- | --- | --- | --- | --- |
| 303 | 12.72 | 1512.07 | 104 | 1020 | 18 | 997 |
| 31102026 | 13.16 | 7427.48 | 1 | 905 | 15 | 1220 |
| 505 | 10.64 | 2054.50 | 55115 | 708 | 8 | 1164 |
