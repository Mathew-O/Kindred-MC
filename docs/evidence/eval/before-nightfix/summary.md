# Evaluation summary: before-nightfix

Runs: 10 seeds (101, 202, 20261030, 303, 31102026, 404, 505, 606, 707, 8675309), EARNED. Scenario: starters=Kindred,Sol,Echo; goal=nether; edits=on; ticks=144000; sprint=on; strategist=off; voice=off; keep=none.
Seed 404 ran on kindred-nonight-sf.jar, the others on kindred-nonight.jar.

Medians, with the range across seeds in brackets.

| Measure | Median [range] (seeds) |
| --- | --- |
| The team's furthest stage (index in the list below) | 5.0 [3 to 6] (n=10) |
| Deaths a game hour, the team | 0.5 [0.0 to 2.5] (n=10) |
| Stuck spells, the team | 13.0 [4 to 25] (n=10) |
| Stuck game minutes, the team | 39.0 [12 to 75] (n=10) |
| Rescue plans, the team (of them, out) | 0.0 [0 to 0] (n=10) (0.0 [0 to 0] (n=10)) |
| Model tokens, the team | 0.0 [0 to 0] (n=10) |
| First to wooden_tool, game minutes (a seed not reaching it counted as later than all) | 1.6 [0.6 to 2.0] (reached on 10 of 10) |
| First to stone_tools, game minutes (a seed not reaching it counted as later than all) | 3.0 [1.9 to 3.7] (reached on 10 of 10) |
| First to iron_ingot, game minutes (a seed not reaching it counted as later than all) | 8.4 [6.0 to 15.2] (reached on 10 of 10) |
| First to iron_pickaxe, game minutes (a seed not reaching it counted as later than all) | 25.15 [18.4 to 57.5] (reached on 10 of 10) |
| First to diamond, game minutes (a seed not reaching it counted as later than all) | not reached [51.1 to not reached] (reached on 3 of 10) |
| First to obsidian, game minutes (a seed not reaching it counted as later than all) | 93.9 [51.0 to not reached] (reached on 6 of 10) |
| First to portal_lit, game minutes (a seed not reaching it counted as later than all) | not reached [53.6 to not reached] (reached on 4 of 10) |

Stages: 0 wooden_tool, 1 stone_tools, 2 iron_ingot, 3 iron_pickaxe, 4 diamond, 5 obsidian, 6 portal_lit, 7 nether_entered, 8 blaze_rods, 9 eyes, 10 stronghold, 11 end, 12 dragon.

Deaths by cause, all seeds: zombie 4, skeleton 4, creeper 4, drowning 2, drowned 2, spider 1, slime 1, cave_spider 1.

| Seed | Ticks run | Team furthest | Deaths/h | Per player: furthest, deaths, stuck spells, RunMetrics (done/blocked, worst repeat, recovered %, walks in vain) |
| --- | --- | --- | --- | --- |
| 101 | 144000 | obsidian | 0.0 | Kindred: iron_pickaxe, 0, 10, 3767/152, 4, 100%, 137; Sol: obsidian, 0, 1, 2292/68, 2, 75%, 62; Echo: iron_ingot, 0, 14, 1562/154, 127, 67%, 148 |
| 202 | 144000 | obsidian | 0.5 | Kindred: iron_pickaxe, 1, 2, 3455/43, 12, 100%, 34; Sol: obsidian, 0, 5, 3328/127, 8, 100%, 106; Echo: iron_pickaxe, 0, 5, 2451/179, 35, 80%, 173 |
| 20261030 | 144000 | iron_pickaxe | 0.5 | Kindred: iron_pickaxe, 1, 6, 3416/149, 66, 88%, 143; Sol: iron_pickaxe, 0, 0, 2958/27, 2, 100%, 22; Echo: iron_pickaxe, 0, 3, 3384/14, 4, 100%, 14 |
| 303 | 144000 | portal_lit | 0.5 | Kindred: iron_ingot, 0, 10, 2383/186, 3, 100%, 183; Sol: portal_lit, 0, 3, 2235/84, 5, 100%, 73; Echo: iron_pickaxe, 1, 1, 2815/152, 4, 100%, 145 |
| 31102026 | 144000 | iron_pickaxe | 0.5 | Kindred: iron_ingot, 0, 11, 1675/130, 98, 86%, 128; Sol: iron_pickaxe, 1, 0, 2832/35, 9, 100%, 20; Echo: none, 0, 4, 1842/48, 10, 100%, 32 |
| 404 | 144000 | portal_lit | 0.5 | Kindred: diamond, 1, 4, 3537/209, 5, 100%, 208; Sol: iron_pickaxe, 0, 2, 1606/39, 5, 67%, 37; Echo: portal_lit, 0, 2, 1486/80, 2, 100%, 80 |
| 505 | 144000 | portal_lit | 1.0 | Kindred: iron_pickaxe, 1, 3, 2846/133, 6, 100%, 122; Sol: portal_lit, 0, 2, 1887/70, 5, 100%, 63; Echo: iron_pickaxe, 1, 10, 2984/813, 581, 50%, 800 |
| 606 | 144000 | iron_pickaxe | 2.5 | Kindred: iron_pickaxe, 2, 2, 3109/235, 3, 100%, 227; Sol: iron_pickaxe, 1, 8, 3590/201, 90, 100%, 192; Echo: iron_pickaxe, 2, 1, 2957/73, 5, 100%, 69 |
| 707 | 144000 | portal_lit | 1.5 | Kindred: portal_lit, 0, 1, 1918/25, 1, 100%, 18; Sol: iron_pickaxe, 1, 1, 3425/56, 3, 100%, 51; Echo: iron_pickaxe, 2, 2, 3029/57, 2, 100%, 50 |
| 8675309 | 144000 | diamond | 2.0 | Kindred: diamond, 0, 4, 3662/143, 13, 100%, 126; Sol: iron_pickaxe, 1, 6, 3207/62, 2, 100%, 56; Echo: iron_ingot, 3, 6, 2786/147, 2, 100%, 138 |
