> Both arms here ran with the strategist **off**. `f29e-on-4h` is the run name of the lead's arm, filed as [f29e-off-4h-lead](f29e-off-4h-lead/README.md). It asked for `strategist=on`, but the strategist was not configured (its results' `build` field says so), so it made 0 plans and spent 0 tokens. The "scenarios differ" lines below are that request, not a difference in play. Same build (`cd56501`), same seeds, two containers: this is how far one arm moves between containers.

# f29e-on-4h against f29e-off-4h

On the 10 seeds both ran (101, 202, 20261030, 303, 31102026, 404, 505, 606, 707, 8675309). f29e-off-4h: earned; f29e-on-4h: earned.
The scenarios differ: ['starters=Kindred,Sol,Echo; goal=nether; edits=on; ticks=288000; sprint=on; strategist=off; voice=off; keep=none; lessons=on; atlas=on; rescue=on; advice=on'] against ['starters=Kindred,Sol,Echo; goal=nether; edits=on; ticks=288000; sprint=on; strategist=on; voice=off; keep=none; lessons=on; atlas=on; rescue=on; advice=off'].

| Measure | f29e-off-4h | f29e-on-4h | Verdict |
| --- | --- | --- | --- |
| The team's furthest stage (index) | 6.0 [3 to 6] (n=10) | 6.0 [5 to 6] (n=10) | no clear difference: the ranges overlap |
| Deaths a game hour, the team | 0.25 [0.0 to 4.5] (n=10) | 0.88 [0.0 to 2.5] (n=10) | no clear difference: the ranges overlap |
| Stuck spells, the team | 16.5 [1 to 31] (n=10) | 21.0 [10 to 55] (n=10) | no clear difference: the ranges overlap |
| Stuck game minutes, the team | 49.5 [3 to 93] (n=10) | 63.0 [30 to 165] (n=10) | no clear difference: the ranges overlap |
| Rescue plans, the team | 0.0 [0 to 0] (n=10) | 0.0 [0 to 0] (n=10) | no clear difference: the ranges overlap |
| Rescue plans that got it out, the team | 0.0 [0 to 0] (n=10) | 0.0 [0 to 0] (n=10) | no clear difference: the ranges overlap |
| Rescues asked (rounds), the team | 0.0 [0 to 0] (n=10) | 0.0 [0 to 0] (n=10) | no clear difference: the ranges overlap |
| Rescue spells ended, the team | 0.0 [0 to 0] (n=10) | 0.0 [0 to 0] (n=10) | no clear difference: the ranges overlap |
| Rescue spells that got it out, the team | 0.0 [0 to 0] (n=10) | 0.0 [0 to 0] (n=10) | no clear difference: the ranges overlap |
| Rescue rounds in ended spells, the team | 0.0 [0 to 0] (n=10) | 0.0 [0 to 0] (n=10) | no clear difference: the ranges overlap |
| Rescue recipes tried first, the team | 0.0 [0 to 0] (n=10) | 0.0 [0 to 0] (n=10) | no clear difference: the ranges overlap |
| Rescue spells out by a recipe, the team | 0.0 [0 to 0] (n=10) | 0.0 [0 to 0] (n=10) | no clear difference: the ranges overlap |
| Game seconds from a spell's last progress to its ask, the seed's median | - | - | not comparable |
| Model tokens, the team | 0.0 [0 to 0] (n=10) | 0.0 [0 to 0] (n=10) | no clear difference: the ranges overlap |
| First to stone_tools, game minutes, every seed (one not reaching it later than all) | 1.45 [1.0 to 4.6] (reached on 10 of 10) | 1.8 [1.2 to 2.1] (reached on 10 of 10) | no clear difference: the ranges overlap |
| First to iron_ingot, game minutes, every seed (one not reaching it later than all) | 5.5 [3.6 to 8.0] (reached on 10 of 10) | 4.8 [2.8 to 6.5] (reached on 10 of 10) | no clear difference: the ranges overlap |
| First to iron_pickaxe, game minutes, every seed (one not reaching it later than all) | 17.7 [12.5 to 30.4] (reached on 10 of 10) | 19.4 [12.7 to 34.4] (reached on 10 of 10) | no clear difference: the ranges overlap |
| First to diamond, game minutes, every seed (one not reaching it later than all) | not reached [34.6 to not reached] (reached on 4 of 10) | not reached [30.9 to not reached] (reached on 4 of 10) | no clear difference: the ranges overlap |
| First to obsidian, game minutes, every seed (one not reaching it later than all) | 48.05 [22.1 to not reached] (reached on 9 of 10) | 74.0 [24.4 to 226.2] (reached on 10 of 10) | no clear difference: the ranges overlap |
| First to portal_lit, game minutes, every seed (one not reaching it later than all) | 53.75 [23.2 to not reached] (reached on 8 of 10) | 154.15 [43.2 to not reached] (reached on 7 of 10) | no clear difference: the ranges overlap |
