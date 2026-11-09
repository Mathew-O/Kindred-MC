# Evaluation summary: f29e-on

Runs: 20 seeds (101, 1166122638, 121616477, 126202224, 1394473968, 1530142077, 1908023170, 202, 20261030, 292680596, 303, 31102026, 404, 505, 606, 707, 779795571, 848677377, 8675309, 891567637), EARNED. Scenario: starters=Kindred,Sol,Echo; goal=nether; edits=on; ticks=144000; sprint=on; strategist=on; voice=off; keep=none; lessons=on; atlas=on; rescue=on; advice=off.

Medians, with the range across seeds in brackets.

| Measure | Median [range] (seeds) |
| --- | --- |
| The team's furthest stage (index in the list below) | 5.5 [3 to 6] (n=20) |
| Deaths a game hour, the team | 1.0 [0.0 to 3.5] (n=20) |
| Stuck spells, the team | 1.5 [0 to 11] (n=20) |
| Stuck game minutes, the team | 4.5 [0 to 33] (n=20) |
| Rescue plans, the team (of them, out) | 22.0 [8 to 31] (n=20) (3.5 [0 to 9] (n=20)) |
| Rescues asked (rounds), the team | 21.0 [8 to 28] (n=20) |
| Rescue spells ended, the team (of them, out; rounds in them) | 7.0 [0 to 12] (n=20) (4.0 [0 to 7] (n=20); 16.5 [0 to 27] (n=20)) |
| Rescue recipes tried first, the team (spells out by a recipe) | 0.0 [0 to 5] (n=20) (0.0 [0 to 2] (n=20)) |
| Game seconds from a spell's last progress to its ask: each seed's median / every ask | 60.2 [60.1 to 61.2] (n=20) / 60.2 [60.0 to 1148.4] (n=170) |
| Model tokens, the team | 56441.5 [20894 to 75499] (n=20) |
| Lines spoken a game minute (between AI players, and said first to people) | 0.0 [0.0 to 0.0] (n=20) |
| Lines between AI players said / said first to people / meetings noted with nobody to hear | 0.0 [0 to 0] (n=20) / 0.0 [0 to 0] (n=20) / 0.0 [0 to 1] (n=20) |
| First to wooden_tool, game minutes (a seed not reaching it counted as later than all) | 0.75 [0.3 to 1.5] (reached on 20 of 20) |
| First to stone_tools, game minutes (a seed not reaching it counted as later than all) | 1.95 [1.2 to 4.4] (reached on 20 of 20) |
| First to iron_ingot, game minutes (a seed not reaching it counted as later than all) | 5.55 [4.1 to 9.3] (reached on 20 of 20) |
| First to iron_pickaxe, game minutes (a seed not reaching it counted as later than all) | 22.75 [10.7 to 34.8] (reached on 20 of 20) |
| First to diamond, game minutes (a seed not reaching it counted as later than all) | not reached [44.4 to not reached] (reached on 5 of 20) |
| First to obsidian, game minutes (a seed not reaching it counted as later than all) | 54.9 [23.6 to not reached] (reached on 17 of 20) |
| First to portal_lit, game minutes (a seed not reaching it counted as later than all) | not reached [24.6 to not reached] (reached on 10 of 20) |

Stages: 0 wooden_tool, 1 stone_tools, 2 iron_ingot, 3 iron_pickaxe, 4 diamond, 5 obsidian, 6 portal_lit, 7 nether_entered, 8 blaze_rods, 9 eyes, 10 stronghold, 11 end, 12 dragon.

Deaths by cause, all seeds: skeleton 15, zombie 10, drowning 8, suffocation 4, spider 3, fall 3, lava 3, drowned 2, creeper 1, cave_spider 1, witch 1.

| Seed | Ticks run | Team furthest | Deaths/h | Per player: furthest, deaths, stuck spells, RunMetrics (done/blocked, worst repeat, recovered %, walks in vain) |
| --- | --- | --- | --- | --- |
| 101 | 144000 | obsidian | 0.5 | Kindred: obsidian, 0, 1, 5007/58, 1, 100%, 39; Sol: iron_pickaxe, 1, 2, 5120/158, 5, 100%, 112; Echo: iron_pickaxe, 0, 1, 3122/67, 5, 100%, 47 |
| 1166122638 | 144000 | portal_lit | 0.5 | Kindred: portal_lit, 0, 0, 5212/317, 7, 100%, 263; Sol: obsidian, 1, 0, 5252/428, 5, 100%, 398; Echo: portal_lit, 0, 1, 3452/85, 2, 100%, 66 |
| 121616477 | 144000 | portal_lit | 0.5 | Kindred: iron_pickaxe, 1, 0, 4453/172, 3, 100%, 141; Sol: iron_pickaxe, 0, 0, 2198/86, 1, 100%, 65; Echo: portal_lit, 0, 1, 2309/104, 1, 100%, 87 |
| 126202224 | 144000 | iron_pickaxe | 0.5 | Kindred: iron_pickaxe, 0, 0, 1405/258, 5, 100%, 249; Sol: iron_pickaxe, 1, 0, 5396/243, 7, 100%, 188; Echo: iron_ingot, 0, 0, 1057/306, 5, 100%, 294 |
| 1394473968 | 144000 | portal_lit | 1.0 | Kindred: portal_lit, 0, 0, 4342/142, 5, 100%, 104; Sol: iron_pickaxe, 0, 10, 7286/56, 1, 96%, 45; Echo: portal_lit, 2, 1, 5651/230, 3, 100%, 196 |
| 1530142077 | 144000 | portal_lit | 1.5 | Kindred: iron_ingot, 1, 0, 1494/494, 5, 100%, 485; Sol: iron_pickaxe, 1, 1, 4524/332, 9, 100%, 316; Echo: portal_lit, 1, 4, 7303/698, 17, 100%, 647 |
| 1908023170 | 144000 | portal_lit | 0.5 | Kindred: iron_pickaxe, 1, 0, 6562/414, 5, 100%, 379; Sol: portal_lit, 0, 0, 4313/129, 5, 100%, 110; Echo: iron_pickaxe, 0, 0, 3746/121, 5, 100%, 96 |
| 202 | 144000 | diamond | 2.0 | Kindred: diamond, 0, 4, 7091/135, 5, 100%, 101; Sol: iron_pickaxe, 1, 1, 5521/132, 4, 100%, 107; Echo: iron_pickaxe, 3, 0, 3509/371, 193, 100%, 159 |
| 20261030 | 144000 | portal_lit | 0.0 | Kindred: portal_lit, 0, 0, 1326/34, 1, 100%, 23; Sol: iron_pickaxe, 0, 0, 5089/110, 2, 100%, 82; Echo: iron_pickaxe, 0, 0, 4675/654, 521, 100%, 638 |
| 292680596 | 144000 | iron_pickaxe | 0.5 | Kindred: iron_pickaxe, 0, 2, 5797/386, 5, 100%, 339; Sol: iron_pickaxe, 0, 5, 4471/443, 8, 100%, 414; Echo: iron_pickaxe, 1, 0, 5919/198, 4, 100%, 132 |
| 303 | 144000 | obsidian | 2.0 | Kindred: obsidian, 3, 0, 6168/317, 2, 100%, 284; Sol: iron_pickaxe, 1, 0, 5053/113, 3, 100%, 85; Echo: iron_pickaxe, 0, 0, 4382/43, 1, 100%, 25 |
| 31102026 | 144000 | obsidian | 1.5 | Kindred: iron_pickaxe, 1, 3, 3848/149, 29, 100%, 93; Sol: iron_pickaxe, 1, 0, 5429/63, 7, 100%, 42; Echo: obsidian, 1, 0, 4677/143, 17, 100%, 92 |
| 404 | 144000 | obsidian | 1.0 | Kindred: obsidian, 1, 0, 6577/398, 8, 100%, 364; Sol: obsidian, 0, 3, 4994/123, 5, 100%, 105; Echo: iron_pickaxe, 1, 0, 6421/132, 7, 100%, 87 |
| 505 | 144000 | portal_lit | 3.0 | Kindred: iron_pickaxe, 1, 0, 5109/376, 9, 100%, 338; Sol: portal_lit, 1, 1, 2901/185, 2, 100%, 175; Echo: iron_ingot, 4, 1, 2897/380, 3, 100%, 362 |
| 606 | 144000 | obsidian | 1.0 | Kindred: iron_pickaxe, 0, 0, 1935/67, 10, 100%, 53; Sol: diamond, 1, 2, 5311/84, 4, 100%, 46; Echo: obsidian, 1, 2, 5990/214, 3, 100%, 187 |
| 707 | 144000 | portal_lit | 2.0 | Kindred: portal_lit, 0, 1, 4309/427, 349, 100%, 401; Sol: portal_lit, 2, 0, 2578/54, 2, 100%, 42; Echo: portal_lit, 2, 0, 3840/190, 3, 100%, 160 |
| 779795571 | 144000 | obsidian | 3.0 | Kindred: iron_pickaxe, 2, 0, 4664/338, 9, 100%, 282; Sol: obsidian, 2, 0, 5634/436, 11, 100%, 382; Echo: iron_pickaxe, 2, 1, 5848/438, 5, 100%, 382 |
| 848677377 | 144000 | obsidian | 0.0 | Kindred: obsidian, 0, 0, 4635/189, 2, 100%, 167; Sol: iron_pickaxe, 0, 0, 4721/86, 5, 100%, 59; Echo: iron_pickaxe, 0, 0, 5063/137, 2, 100%, 86 |
| 8675309 | 144000 | portal_lit | 1.0 | Kindred: portal_lit, 0, 0, 3120/99, 4, 100%, 82; Sol: obsidian, 0, 0, 5477/116, 3, 100%, 74; Echo: iron_pickaxe, 2, 1, 5677/484, 5, 100%, 450 |
| 891567637 | 144000 | portal_lit | 3.5 | Kindred: iron_ingot, 6, 2, 4383/370, 4, 100%, 343; Sol: portal_lit, 0, 0, 3600/44, 3, 100%, 34; Echo: obsidian, 1, 0, 5519/203, 3, 100%, 177 |
