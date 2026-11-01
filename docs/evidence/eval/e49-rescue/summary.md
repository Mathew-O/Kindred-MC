# Evaluation summary: e49-rescue

Runs: 10 seeds (101, 202, 20261030, 303, 31102026, 404, 505, 606, 707, 8675309), EARNED. Scenario: starters=Kindred,Sol,Echo; goal=nether; edits=on; ticks=144000; sprint=on; strategist=on; voice=off; keep=none; lessons=on; atlas=on; rescue=on; advice=off.

Medians, with the range across seeds in brackets.

| Measure | Median [range] (seeds) |
| --- | --- |
| The team's furthest stage (index in the list below) | 4.0 [2 to 6] (n=10) |
| Deaths a game hour, the team | 1.75 [0.0 to 6.0] (n=10) |
| Stuck spells, the team | 12.5 [5 to 28] (n=10) |
| Stuck game minutes, the team | 37.5 [15 to 84] (n=10) |
| Rescue plans, the team (of them, out) | 4.5 [0 to 10] (n=10) (0.0 [0 to 2] (n=10)) |
| Model tokens, the team | 8525.0 [0 to 17255] (n=10) |
| First to wooden_tool, game minutes (a seed not reaching it counted as later than all) | 1.4 [0.3 to 1.8] (reached on 10 of 10) |
| First to stone_tools, game minutes (a seed not reaching it counted as later than all) | 2.75 [1.9 to 6.2] (reached on 10 of 10) |
| First to iron_ingot, game minutes (a seed not reaching it counted as later than all) | 11.3 [5.9 to 16.7] (reached on 10 of 10) |
| First to iron_pickaxe, game minutes (a seed not reaching it counted as later than all) | 37.9 [20.9 to not reached] (reached on 9 of 10) |
| First to obsidian, game minutes (a seed not reaching it counted as later than all) | not reached [65.2 to not reached] (reached on 5 of 10) |
| First to portal_lit, game minutes (a seed not reaching it counted as later than all) | not reached [66.5 to not reached] (reached on 2 of 10) |

Stages: 0 wooden_tool, 1 stone_tools, 2 iron_ingot, 3 iron_pickaxe, 4 diamond, 5 obsidian, 6 portal_lit, 7 nether_entered, 8 blaze_rods, 9 eyes, 10 stronghold, 11 end, 12 dragon.

Deaths by cause, all seeds: zombie 17, skeleton 8, creeper 5, lava 2, drowned 2, drowning 2, suffocation 1, zombie_villager 1, polar_bear 1.

| Seed | Ticks run | Team furthest | Deaths/h | Per player: furthest, deaths, stuck spells, RunMetrics (done/blocked, worst repeat, recovered %, walks in vain) |
| --- | --- | --- | --- | --- |
| 101 | 144000 | iron_pickaxe | 3.5 | Kindred: iron_pickaxe, 1, 14, 4888/20, 2, 100%, 16; Sol: stone_tools, 2, 10, 1595/54, 15, 100%, 29; Echo: stone_tools, 4, 4, 1969/57, 5, 100%, 32 |
| 202 | 144000 | obsidian | 1.5 | Kindred: iron_pickaxe, 2, 5, 2854/245, 5, 100%, 230; Sol: obsidian, 0, 1, 3363/67, 5, 100%, 57; Echo: iron_ingot, 1, 5, 3266/124, 15, 100%, 112 |
| 20261030 | 144000 | iron_pickaxe | 2.0 | Kindred: iron_pickaxe, 1, 3, 3574/78, 13, 100%, 75; Sol: iron_pickaxe, 0, 1, 3344/10, 0, 100%, 10; Echo: iron_pickaxe, 3, 1, 2600/101, 8, 100%, 91 |
| 303 | 144000 | iron_pickaxe | 0.5 | Kindred: iron_pickaxe, 1, 3, 3055/147, 9, 100%, 130; Sol: iron_pickaxe, 0, 4, 3634/15, 2, 91%, 10; Echo: iron_pickaxe, 0, 5, 3287/226, 12, 100%, 213 |
| 31102026 | 144000 | iron_pickaxe | 0.5 | Kindred: iron_pickaxe, 0, 5, 2723/22, 3, 100%, 16; Sol: iron_ingot, 1, 1, 2351/48, 4, 100%, 38; Echo: iron_pickaxe, 0, 12, 2029/235, 98, 67%, 234 |
| 404 | 144000 | portal_lit | 0.5 | Kindred: iron_pickaxe, 1, 3, 2597/46, 1, 100%, 43; Sol: portal_lit, 0, 4, 2596/54, 3, 100%, 52; Echo: obsidian, 0, 6, 3171/58, 2, 100%, 54 |
| 505 | 144000 | obsidian | 2.0 | Kindred: stone_tools, 2, 3, 2657/365, 10, 100%, 342; Sol: obsidian, 1, 8, 3268/289, 135, 100%, 279; Echo: obsidian, 1, 1, 2922/222, 9, 100%, 208 |
| 606 | 144000 | portal_lit | 0.0 | Kindred: iron_ingot, 0, 10, 2828/1144, 1047, 100%, 1123; Sol: portal_lit, 0, 2, 1990/24, 1, 100%, 14; Echo: portal_lit, 0, 4, 2977/59, 2, 100%, 49 |
| 707 | 144000 | iron_ingot | 3.0 | Kindred: iron_ingot, 4, 1, 2444/220, 25, 100%, 216; Sol: iron_ingot, 2, 3, 3198/138, 14, 100%, 130; Echo: iron_ingot, 0, 23, 1159/146, 79, 89%, 142 |
| 8675309 | 144000 | obsidian | 6.0 | Kindred: obsidian, 1, 7, 3805/41, 3, 100%, 24; Sol: iron_ingot, 4, 4, 2667/111, 12, 100%, 105; Echo: stone_tools, 7, 1, 1403/80, 5, 100%, 55 |
