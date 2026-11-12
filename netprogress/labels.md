# Net-progress watchdog: 20 hand-labelled R-only spans (N=8)

The sample was drawn with seed 20261112 from the 91 spans that R(8) flags and the watchdog does not count. That count is after the watchdog's own exemptions, with every action line weighed, sleep included.
- **Source:** the consoles on claude/corpus-data-e40a21-1112 @ c0404af.
- **Each span's raw lines:** `window.py <run> <seed> <player> <from> <to>`.

| # | Span | Label | What it shows |
| --- | --- | --- | --- |
| 1 | e40a21 31102026 Echo 67565-71791 | ambiguous | climbs toward the surface for water through falling sand, with 45 daylight waits mixed in |
| 2 | p157-before 303 Kindred 37352-41206 | false: the watchdog's own move | "make a way elsewhere" |
| 3 | e40a21 505 Sol 117778-122027 | true loop | walk to water blocked ×22, then climbs for logs |
| 4 | e40a21 31102026 Echo 115699-119912 | ambiguous | a sand climb for water, with night waits |
| 5 | p157-before 31102026 Echo 116777-121132 | false: a passive wait | "wait for daylight before going up for logs" |
| 6 | e40a21 31102026 Kindred 19756-23882 | ambiguous | a climb for water, with night waits |
| 7 | e40a21 606 Sol 108294-111973 | false: the goal done | "Done:", a hand-over failing |
| 8 | e40a21 505 Sol 115605-119823 | true loop | walk to water blocked ×22 |
| 9 | e40a21 31102026 Sol 91678-95819 | ambiguous | a sand climb for water |
| 10 | e40a21 707 Kindred 139617-143608 | false: the watchdog's own move | "dig toward elsewhere" |
| 11 | p157-before 31102026 Kindred 19822-24979 | true loop | climb for logs ↔ back the way it came, with night waits mixed in |
| 12 | e40a21 31102026 Echo 92640-96320 | ambiguous | a sand climb for water |
| 13 | e40a21 8675309 Kindred 82778-86486 | false: the goal done | "Done:", a fight |
| 14 | e40a21 202 Sol 21965-27503 | true loop | climb for logs: step up ↔ back the way it came |
| 15 | p157-before 303 Echo 19776-26764 | true loop | the table loop, 32 places, not counted by the watchdog |
| 16 | e40a21 505 Kindred 53120-56720 | true loop | out from under the water ↔ climb toward water |
| 17 | e40a21 303 Kindred 67752-71411 | false: the watchdog's own move | "dig toward elsewhere: let what falls settle" ×163 |
| 18 | p157-before 303 Echo 68400-74737 | true loop | the table loop, 30 places |
| 19 | p157-after 31102026 Echo 68646-72493 | ambiguous | a sand climb for water |
| 20 | p157-after 31102026 Kindred 100640-104800 | ambiguous | a slow climb by hand for logs |

**Tally:** 7 true loops, 6 false and 7 ambiguous.
- **The six false (2, 5, 7, 10, 13, 17)** are all states the game already exempts: its own move elsewhere, the goal done, or a passive wait. In game they would not be flagged. My offline filter only missed them.
- **Six of the seven ambiguous (1, 4, 6, 9, 12, 19)** are one pattern: climbing for water through sand that falls back, with daylight waits mixed in. The seventh (20) is a slow climb by hand.
