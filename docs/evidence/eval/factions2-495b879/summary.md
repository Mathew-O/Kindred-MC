# Evaluation summary: factions2-495b879

Runs: 10 seeds (101, 102, 103, 104, 105, 106, 107, 108, 109, 110), EARNED. Scenario: starters=Kindred,Sol,Echo; goal=none; edits=on; ticks=288000; sprint=on; strategist=off; voice=off; keep=none; lessons=on; atlas=on; rescue=on; advice=on; mode=factions.

Medians, with the range across seeds in brackets.

| Measure | Median [range] (seeds) |
| --- | --- |
| The team's furthest stage (index in the list below) | -1.0 [-1 to -1] (n=10) |
| Deaths a game hour, the team | 10.25 [3.5 to 26.5] (n=10) |
| Stuck spells, the team | 0.0 [0 to 0] (n=10) |
| Stuck game minutes, the team | 0.0 [0 to 0] (n=10) |
| Rescue plans, the team (of them, out) | 0.0 [0 to 0] (n=10) (0.0 [0 to 0] (n=10)) |
| Rescues asked (rounds), the team | 0.0 [0 to 0] (n=10) |
| Rescue spells ended, the team (of them, out; rounds in them) | 0.0 [0 to 0] (n=10) (0.0 [0 to 0] (n=10); 0.0 [0 to 0] (n=10)) |
| Rescue recipes tried first, the team (spells out by a recipe) | 0.0 [0 to 0] (n=10) (0.0 [0 to 0] (n=10)) |
| Game seconds from a spell's last progress to its ask: each seed's median / every ask | - / - |
| Model tokens, the team | 0.0 [0 to 0] (n=10) |
| Lines spoken a game minute (between AI players, and said first to people) | 0.0 [0.0 to 0.0] (n=10) |
| Lines between AI players said / said first to people / meetings noted with nobody to hear | 0.0 [0 to 0] (n=10) / 0.0 [0 to 0] (n=10) / 1.0 [0 to 3] (n=10) |

Stages: 0 wooden_tool, 1 stone_tools, 2 iron_ingot, 3 iron_pickaxe, 4 diamond, 5 obsidian, 6 portal_lit, 7 nether_entered, 8 blaze_rods, 9 eyes, 10 stronghold, 11 end, 12 dragon.

Deaths by cause, all seeds: zombie 234, skeleton 70, spider 48, drowned 26, phantom 21, drowning 19, creeper 17, zombie_villager 5, polar_bear 4, witch 1, pillager 1.

| Seed | Ticks run | Team furthest | Deaths/h | Per player: furthest, deaths, stuck spells, RunMetrics (done/blocked, worst repeat, recovered %, walks in vain) |
| --- | --- | --- | --- | --- |
| 101 | 288001 | none | 14.0 | Kindred: none, 18, 0, 141/21, 3, 0%, 21; Sol: none, 16, 0, 238/24, 3, 0%, 18; Echo: none, 22, 0, 227/33, 5, 0%, 27 |
| 102 | 288001 | none | 6.5 | Kindred: none, 13, 0, 101/382, 47, 100%, 381; Sol: none, 0, 0, 21/21, 3, 100%, 21; Echo: none, 13, 0, 93/26, 3, 0%, 26 |
| 103 | 288001 | none | 16.5 | Kindred: none, 0, 0, 9/559, 41, 100%, 559; Sol: none, 34, 0, 353/526, 21, 0%, 525; Echo: none, 32, 0, 301/646, 23, 0%, 644 |
| 104 | 288001 | none | 12.5 | Kindred: none, 7, 0, 112/8, 2, 0%, 7; Sol: none, 21, 0, 212/5, 2, 0%, 4; Echo: none, 22, 0, 288/34, 7, 0%, 32 |
| 105 | 288001 | none | 3.5 | Kindred: none, 3, 0, 41/738, 21, 100%, 738; Sol: none, 1, 0, 32/12, 2, 100%, 11; Echo: none, 10, 0, 81/75, 3, 0%, 69 |
| 106 | 288001 | none | 10.0 | Kindred: none, 28, 0, 343/194, 23, 0%, 191; Sol: none, 5, 0, 74/10, 2, 0%, 10; Echo: none, 7, 0, 79/34, 4, 0%, 34 |
| 107 | 288001 | none | 5.5 | Kindred: none, 9, 0, 98/3, 2, 0%, 3; Sol: none, 9, 0, 92/0, 0, 0%, 0; Echo: none, 4, 0, 42/13, 2, 0%, 13 |
| 108 | 288001 | none | 10.5 | Kindred: none, 13, 0, 178/95, 7, 0%, 93; Sol: none, 12, 0, 101/572, 21, 0%, 572; Echo: none, 17, 0, 176/390, 16, 0%, 390 |
| 109 | 288001 | none | 26.5 | Kindred: none, 35, 0, 513/543, 47, 0%, 541; Sol: none, 51, 0, 1122/165, 17, 0%, 161; Echo: none, 20, 0, 339/186, 13, 0%, 184 |
| 110 | 288001 | none | 6.0 | Kindred: none, 4, 0, 30/730, 30, 0%, 730; Sol: none, 10, 0, 68/971, 36, 0%, 959; Echo: none, 10, 0, 79/1138, 59, 100%, 1138 |

## The server's load

Each seed's mean tick (over the run, from the server's own last-hundred mean once a game second), its worst tick, and its heap at most.

| Seed | Mean tick, ms | Worst tick, ms | Heap at most, MB |
| --- | --- | --- | --- |
| 101 | 5.35 | 1326.95 | 1211 |
| 102 | 12.19 | 1903.15 | 1323 |
| 103 | 9.28 | 1045.01 | 1363 |
| 104 | 5.04 | 765.91 | 937 |
| 105 | 10.59 | 746.21 | 1200 |
| 106 | 8.47 | 1189.49 | 1347 |
| 107 | 4.49 | 889.64 | 1139 |
| 108 | 6.25 | 858.34 | 1048 |
| 109 | 7.06 | 1226.24 | 1602 |
| 110 | 5.85 | 511.88 | 833 |

## The faction world's audit (M-06i)

Every claim and capture decided again from the World Record by the rules, the safe zone, coins and the auction house's hand-overs.

| Seed | Audited | Factions | Claims | Captures | Sales | Kills | Rules broken |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 101 | yes | 3 | 10 | 0 | 0 | 0 | 0 |
| 102 | yes | 3 | 10 | 0 | 0 | 0 | 0 |
| 103 | yes | 3 | 7 | 0 | 0 | 0 | 0 |
| 104 | yes | 3 | 10 | 0 | 0 | 0 | 0 |
| 105 | yes | 3 | 7 | 0 | 0 | 0 | 0 |
| 106 | yes | 3 | 10 | 0 | 0 | 0 | 0 |
| 107 | yes | 3 | 10 | 0 | 0 | 0 | 0 |
| 108 | yes | 3 | 5 | 0 | 0 | 0 | 0 |
| 109 | yes | 3 | 9 | 0 | 0 | 0 | 0 |
| 110 | yes | 3 | 0 | 0 | 0 | 0 | 0 |

No rule broken on any seed.
