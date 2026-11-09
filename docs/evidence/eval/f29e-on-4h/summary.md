# Evaluation summary: f29e-on-4h

Runs: 10 seeds (101, 202, 20261030, 303, 31102026, 404, 505, 606, 707, 8675309), EARNED. Scenario: starters=Kindred,Sol,Echo; goal=nether; edits=on; ticks=288000; sprint=on; strategist=on; voice=off; keep=none; lessons=on; atlas=on; rescue=on; advice=off.

Medians, with the range across seeds in brackets.

| Measure | Median [range] (seeds) |
| --- | --- |
| The team's furthest stage (index in the list below) | 5.5 [2 to 6] (n=10) |
| Deaths a game hour, the team | 0.62 [0.0 to 3.0] (n=10) |
| Stuck spells, the team | 7.0 [2 to 76] (n=10) |
| Stuck game minutes, the team | 21.0 [6 to 228] (n=10) |
| Rescue plans, the team (of them, out) | 33.0 [11 to 45] (n=10) (4.5 [2 to 14] (n=10)) |
| Rescues asked (rounds), the team | 32.5 [10 to 40] (n=10) |
| Rescue spells ended, the team (of them, out; rounds in them) | 11.0 [4 to 19] (n=10) (4.5 [1 to 12] (n=10); 28.5 [9 to 36] (n=10)) |
| Rescue recipes tried first, the team (spells out by a recipe) | 2.0 [0 to 8] (n=10) (1.0 [0 to 3] (n=10)) |
| Game seconds from a spell's last progress to its ask: each seed's median / every ask | 60.3 [60.0 to 60.65] (n=10) / 60.2 [60.0 to 6583.2] (n=124) |
| Model tokens, the team | 87126.0 [26070 to 100418] (n=10) |
| Lines spoken a game minute (between AI players, and said first to people) | 0.0 [0.0 to 0.0] (n=10) |
| Lines between AI players said / said first to people / meetings noted with nobody to hear | 0.0 [0 to 0] (n=10) / 0.0 [0 to 0] (n=10) / 0.0 [0 to 1] (n=10) |
| First to wooden_tool, game minutes (a seed not reaching it counted as later than all) | 0.75 [0.3 to 4.5] (reached on 10 of 10) |
| First to stone_tools, game minutes (a seed not reaching it counted as later than all) | 1.7 [0.8 to 4.9] (reached on 10 of 10) |
| First to iron_ingot, game minutes (a seed not reaching it counted as later than all) | 6.05 [3.3 to 7.5] (reached on 10 of 10) |
| First to iron_pickaxe, game minutes (a seed not reaching it counted as later than all) | 20.8 [11.6 to not reached] (reached on 9 of 10) |
| First to diamond, game minutes (a seed not reaching it counted as later than all) | not reached [28.9 to not reached] (reached on 4 of 10) |
| First to obsidian, game minutes (a seed not reaching it counted as later than all) | 78.7 [33.1 to not reached] (reached on 9 of 10) |
| First to portal_lit, game minutes (a seed not reaching it counted as later than all) | not reached [34.4 to not reached] (reached on 5 of 10) |

Stages: 0 wooden_tool, 1 stone_tools, 2 iron_ingot, 3 iron_pickaxe, 4 diamond, 5 obsidian, 6 portal_lit, 7 nether_entered, 8 blaze_rods, 9 eyes, 10 stronghold, 11 end, 12 dragon.

Deaths by cause, all seeds: zombie 13, skeleton 9, drowning 4, suffocation 2, husk 2, cave_spider 1, spider 1, enderman 1, stray 1, creeper 1, other 1.

| Seed | Ticks run | Team furthest | Deaths/h | Per player: furthest, deaths, stuck spells, RunMetrics (done/blocked, worst repeat, recovered %, walks in vain) |
| --- | --- | --- | --- | --- |
| 101 | 288000 | obsidian | 0.5 | Kindred: iron_pickaxe, 1, 5, 8032/62, 1, 100%, 45; Sol: iron_pickaxe, 1, 1, 8509/164, 3, 100%, 116; Echo: obsidian, 0, 1, 6656/162, 5, 100%, 140 |
| 202 | 288000 | obsidian | 2.0 | Kindred: diamond, 4, 0, 11005/420, 8, 100%, 312; Sol: iron_pickaxe, 4, 3, 11103/504, 121, 100%, 275; Echo: obsidian, 0, 11, 6540/159, 5, 100%, 116 |
| 20261030 | 288000 | portal_lit | 0.75 | Kindred: obsidian, 1, 2, 12526/151, 4, 100%, 92; Sol: portal_lit, 1, 0, 5620/94, 4, 100%, 47; Echo: obsidian, 1, 0, 8796/233, 11, 100%, 194 |
| 303 | 288000 | obsidian | 0.25 | Kindred: obsidian, 1, 0, 3733/444, 15, 100%, 419; Sol: obsidian, 0, 0, 2652/162, 2, 100%, 143; Echo: stone_tools, 0, 76, 1177/266, 22, 100%, 250 |
| 31102026 | 288000 | iron_ingot | 1.0 | Kindred: none, 1, 1, 5376/125, 2, 96%, 104; Sol: iron_ingot, 3, 3, 6752/154, 1, 96%, 125; Echo: iron_ingot, 0, 2, 6790/195, 2, 100%, 118 |
| 404 | 288000 | portal_lit | 0.25 | Kindred: obsidian, 0, 6, 7600/433, 5, 100%, 391; Sol: portal_lit, 0, 0, 3782/292, 82, 100%, 178; Echo: portal_lit, 1, 1, 5803/383, 5, 100%, 354 |
| 505 | 288000 | portal_lit | 0.25 | Kindred: iron_ingot, 0, 1, 2304/39, 5, 100%, 21; Sol: portal_lit, 1, 6, 3342/233, 2, 100%, 216; Echo: portal_lit, 0, 0, 2265/51, 2, 100%, 37 |
| 606 | 288000 | obsidian | 0.0 | Kindred: obsidian, 0, 8, 7250/262, 5, 100%, 241; Sol: iron_pickaxe, 0, 0, 5661/794, 5, 100%, 754; Echo: obsidian, 0, 2, 10924/450, 4, 100%, 395 |
| 707 | 288000 | portal_lit | 1.0 | Kindred: iron_pickaxe, 2, 2, 9112/238, 2, 94%, 193; Sol: portal_lit, 1, 1, 7833/246, 4, 100%, 207; Echo: iron_pickaxe, 1, 0, 3725/528, 5, 100%, 514 |
| 8675309 | 288000 | portal_lit | 3.0 | Kindred: iron_ingot, 2, 0, 1620/873, 816, 86%, 32; Sol: iron_pickaxe, 6, 2, 9571/798, 13, 100%, 728; Echo: portal_lit, 4, 3, 8999/324, 49, 100%, 273 |
