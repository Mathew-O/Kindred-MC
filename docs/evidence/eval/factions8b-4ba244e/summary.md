# Evaluation summary: factions8b-4ba244e

Runs: 3 seeds (101, 102, 103), EARNED. Scenario: starters=Kindred,Sol,Echo; goal=none; edits=on; ticks=72000; sprint=on; strategist=off; voice=off; keep=none; lessons=on; atlas=on; rescue=on; advice=on; mode=factions; players=Ada,Bram,Cleo,Dara,Eli; founders=3.
Fewer than ten seeds: too few to compare with another build.

Medians, with the range across seeds in brackets.

| Measure | Median [range] (seeds) |
| --- | --- |
| The team's furthest stage (index in the list below) | 3 [3 to 3] (n=3) |
| Deaths a game hour, the team | 3.99 [2.99 to 7.94] (n=3) |
| Stuck spells, the team | 9 [4 to 23] (n=3) |
| Stuck game minutes, the team | 27 [12 to 69] (n=3) |
| Rescue plans, the team (of them, out) | 0 [0 to 0] (n=3) (0 [0 to 0] (n=3)) |
| Rescues asked (rounds), the team | 0 [0 to 0] (n=3) |
| Rescue spells ended, the team (of them, out; rounds in them) | 0 [0 to 0] (n=3) (0 [0 to 0] (n=3); 0 [0 to 0] (n=3)) |
| Rescue recipes tried first, the team (spells out by a recipe) | 0 [0 to 0] (n=3) (0 [0 to 0] (n=3)) |
| Game seconds from a spell's last progress to its ask: each seed's median / every ask | - / - |
| Model tokens, the team | 0 [0 to 0] (n=3) |
| Lines spoken a game minute (between AI players, and said first to people) | 0.0 [0.0 to 0.0] (n=3) |
| Lines between AI players said / said first to people / meetings noted with nobody to hear | 0 [0 to 0] (n=3) / 0 [0 to 0] (n=3) / 2 [0 to 3] (n=3) |
| First to wooden_tool, game minutes (a seed not reaching it counted as later than all) | 0.8 [0.3 to 1.2] (reached on 3 of 3) |
| First to stone_tools, game minutes (a seed not reaching it counted as later than all) | 3.6 [1.2 to 5.3] (reached on 3 of 3) |
| First to iron_ingot, game minutes (a seed not reaching it counted as later than all) | 8.6 [6.8 to 9.3] (reached on 3 of 3) |
| First to iron_pickaxe, game minutes (a seed not reaching it counted as later than all) | 22.5 [15.0 to 26.8] (reached on 3 of 3) |

Stages: 0 wooden_tool, 1 stone_tools, 2 iron_ingot, 3 iron_pickaxe, 4 diamond, 5 obsidian, 6 portal_lit, 7 nether_entered, 8 blaze_rods, 9 eyes, 10 stronghold, 11 end, 12 dragon.

Deaths by cause, all seeds: skeleton 4, zombie 3, spider 2, suffocation 2, drowned 1, creeper 1, fire 1, drowning 1.

| Seed | Ticks run | Team furthest | Deaths/h | Per player: furthest, deaths, stuck spells, RunMetrics (done/blocked, worst repeat, recovered %, walks in vain) |
| --- | --- | --- | --- | --- |
| 101 | 72216 | iron_pickaxe | 3.99 | Kindred: iron_ingot, 0, 1, 2410/313, 14, 100%, 271; Sol: iron_pickaxe, 0, 1, 2713/75, 2, 100%, 61; Echo: iron_ingot, 0, 0, 1908/44, 2, 100%, 33; Ada: iron_ingot, 0, 1, 2021/18, 1, 100%, 7; Bram: iron_ingot, 1, 0, 1782/65, 6, 100%, 43; Cleo: iron_ingot, 0, 1, 1618/82, 19, 100%, 38; Dara: iron_ingot, 1, 0, 2067/170, 14, 100%, 139; Eli: iron_pickaxe, 2, 0, 2352/65, 2, 100%, 37 |
| 102 | 72531 | iron_pickaxe | 7.94 | Kindred: iron_pickaxe, 0, 0, 3840/129, 11, 100%, 73; Sol: iron_ingot, 3, 0, 2652/247, 5, 100%, 191; Echo: iron_ingot, 2, 0, 2715/126, 6, 92%, 100; Ada: iron_pickaxe, 0, 1, 3720/179, 2, 83%, 164; Bram: iron_ingot, 1, 3, 4974/282, 163, 100%, 103; Cleo: iron_ingot, 2, 1, 3367/229, 15, 100%, 196; Dara: iron_pickaxe, 0, 3, 3228/123, 6, 67%, 102; Eli: iron_pickaxe, 0, 1, 3308/145, 2, 100%, 119 |
| 103 | 72271 | iron_pickaxe | 2.99 | Kindred: iron_pickaxe, 0, 1, 3620/559, 17, 100%, 551; Sol: iron_ingot, 1, 2, 4880/74, 2, 75%, 64; Echo: iron_ingot, 1, 2, 4806/207, 10, 100%, 195; Ada: iron_pickaxe, 0, 2, 3913/48, 2, 100%, 30; Bram: none, 1, 7, 10132/69, 2, 100%, 68; Cleo: iron_pickaxe, 0, 3, 5066/104, 4, 100%, 92; Dara: iron_pickaxe, 0, 1, 2735/82, 11, 100%, 63; Eli: iron_ingot, 0, 5, 5887/87, 5, 100%, 76 |

## The server's load

Each seed's mean tick (over the run, from the server's own last-hundred mean once a game second), its worst tick and when it fell (ticks into the run), the ticks that took over 50 and over 200 ms, and its heap at most. A run from before these were kept shows a dash for them.

| Seed | Mean tick, ms | Worst tick, ms | Worst at tick | Over 50 ms | Over 200 ms | Heap at most, MB |
| --- | --- | --- | --- | --- | --- | --- |
| 101 | 19.25 | 521.37 | 40585 | 271 | 6 | 964 |
| 102 | 26.21 | 773.75 | 21180 | 757 | 6 | 1355 |
| 103 | 26.25 | 621.67 | 11364 | 692 | 7 | 1204 |

## The faction world's audit (M-06i)

Every claim and capture decided again from the World Record by the rules, the safe zone, coins and the auction house's hand-overs.

| Seed | Audited | Factions | Claims | Captures | Sales | Kills | Rules broken |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 101 | yes | 3 | 7 | 0 | 178 | 0 | 0 |
| 102 | yes | 3 | 10 | 0 | 343 | 0 | 0 |
| 103 | yes | 4 | 11 | 0 | 212 | 0 | 0 |

No rule broken on any seed.
