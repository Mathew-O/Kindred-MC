# e40a31-0f30534 and e40a31r-0f30534: runner notes

Date: 2026-11-28
Agent / branch: Claude RUNNER 2 / `claude/e40a31-0f30534-1128`
Status: two evaluation batches. No code change, no release gate.

**The read:** two identical batches of one jar, on iteration 25's protocol, read against iteration 29's and 30's
pairs. "Outside" a pair's band means both of 31's batches fall outside the range that pair's two batches span. Each
band is one pair. The lead asked for five things: the four exit criteria per batch, the "walk up to the lava" count in
stuck windows, 303-style reconcile blocks, torch place-and-take-back laps, and #515's diagnostic lines grouped by
reason.

## Setup, both batches
- **Build:** main 0f30534c (#515 merged), 2026-11-28, one jar passed by `--jar` (sha256 49110e4b…c727004).
- **What it carries over iteration 30 (63b97ac9):** 24 merges, among them #515 (F-25c4do's WAITING diagnostic line),
  #520 (the cast walk-up: its F-25c4dr marker line and "open the rock over its lava"), #523 (the torch and bed
  clear), #525, #524, #511, #519, #502, #510, #512, #513, #506, #505, #503 and the evidence filings #521 and #504.
- **Harness and settings:** as for iterations 25 to 30. Scenario goal=nether, ticks=144000; ten seeds; parallel 3. No
  `--keep-world`.
- **Order (UTC):** batch 1 04:09 to 06:38, batch 2 06:38 to 09:16, as four calls of five seeds, run detached from the
  runner session.
- **Completion:** 20 of 20 exit 0, 144000 ticks each. No re-runs.
- **Consoles:** on `claude/corpus-data-e40a31-consoles-1128` (cf6158b3), gzipped, with each seed's eval-result. Each
  part was pushed and checked by sha256 before its local consoles were removed. For the counts below they were
  unpacked back into place.

## Exit figures

| Exit criterion | Threshold | Batch 1 | Batch 2 | 30's band | 29's band | Read |
| --- | --- | --- | --- | --- | --- | --- |
| Stuck under a tenth (under 12 game minutes) | 7 of 10 | 7 of 10 (not 31102026, 404, 8675309): yes | 6 of 10 (not 202, 505, 606, 8675309): no | 3 to 6 | 4 to 6 | overlaps both; met in batch 1 only |
| Deaths a game hour, median | 0.5 or less | 0.5 [0 to 2.0]: yes | 0.25 [0 to 2.0]: yes | 0.25 to 0.5 | 0.5 | inside both; met |
| First iron pickaxe, median | under 30 min | 18.35 (10 of 10): yes | 19.35 (10 of 10): yes | 18.4 to 18.7 | 19.5 to 23.05 | overlaps both; met |
| Portal lit | 7 of 10 | 8 of 10 (not 404, 707): yes | 9 of 10 (not 31102026): yes | 8 to 9 | 7 to 10 | inside both; met |

Batch 1 meets all four criteria. Batch 2 misses stuck under a tenth by one seed.

| | Batch 1 | Batch 2 | 30's band | 29's band | Read |
| --- | --- | --- | --- | --- | --- |
| Stuck game minutes, all seeds | 108 | 111 | 111 to 258 | 180 to 195 | outside 29's, fewer; overlaps 30's (batch 2 at its edge) |
| Stuck spells | 36 | 37 | 37 to 86 | 60 to 65 | outside 29's, fewer; overlaps 30's |
| Hemmed in, spells (min) | 13 (39) | 12 (36) | 4 to 21 | 21 to 22 | outside 29's, fewer; inside 30's |
| Deaths, all seeds | 15 | 9 | 14 to 15 | 8 to 15 | overlaps both |
| F-25c4at1's hold: taken again in the hold (tightened) | 0 | 2 | 1 to 19 | 9 to 24 | outside 29's, fewer; overlaps 30's |
| "Climb to find another way" starts | 369 | 244 | 208 | 158 to 241 | outside both, more |
| "Cast: walk up to the lava" lines, all | 80 | 81 | 2581 to 3883 | 83 to 406 | outside both, fewer |

- **The same-commit spread is narrower than 30's.** Per seed, batch 2 minus batch 1 stuck minutes: median +1.5, range
  −36 to +36, median |Δ| 7.5 (30: 12). 404 went 45 then 9; 202 went 0 then 36.
- **Per-seed stuck minutes:**
  - Batch 1: 101 0, 202 0, 20261030 3, 303 9, 31102026 18, 404 45, 505 6, 606 6, 707 6, 8675309 15.
  - Batch 2: 101 0, 202 36, 20261030 6, 303 0, 31102026 9, 404 9, 505 12, 606 15, 707 3, 8675309 21.
- **Pickaxe slowest:** 707 at 41.2 minutes in batch 1 and 31102026 at 31.5 in batch 2. The two seeds with no portal
  in batch 1 are 404 and 707; in batch 2, 31102026.
- **The busy-work read** (score.py's stuck_kind families, by seed, against 30) is CI GREEN's for this iteration, at
  the lead's word, and was sent to the lead part by part. The kinds per spell are in each summary.

## The walk-up loop, in stuck windows
Counted with `iter30-cast.py`, as for 30: a goal or outcome line naming "Cast: walk up to the lava" or "Cast: step
closer", inside a stuck spell's window (the N ticks before its "Goal made no progress" line, matched to the player by
tick).

| | Batch 1 | Batch 2 | 30 |
| --- | --- | --- | --- |
| "Cast: walk up to the lava" lines in stuck windows | 2 | 2 | 3659 and 330 |
| "Cast: walk up to the lava" lines, all | 80 | 81 | 3883 and 2581 |
| "Cast: step closer" lines in stuck windows | 31 | 18 | |
| "Cast: step closer" lines, all | 716 | 475 | |

The loop 30 found, the walk to the lava completing at once and re-issued, is gone at this count. What remains in the
cast's stuck windows is "Cast: step closer" refused "No walkable route" from far above the frame site. That was
404's Sol in batch 1 and 202's Kindred in batch 2. Both were passed to LOOPS with their replays for F-25c4bx4:
- `claude/corpus-data-e40a31-404-sol-replays-1128` (f224e980).
- `claude/corpus-data-e40a31r-202-replays-1128` (360840fc).

## Reconcile blocks (303-style)
None in either batch. Every seed's summary shows 0 blocks for reconciliation and 0 blocked minutes, and no console line
reads "cannot be verified". The only reconcile-worded refusal is Kindred's "native_eat BLOCKED: Food changed during
use; reconcile before another request", 3 times, with no blocked time after it. 30's block was Echo's "Native
placement effect cannot be verified" on 303 batch 1, 37.2 minutes.

## Torch place-and-take-back laps (#523)
#523 adds no log phrase of its own, so a lap is counted from existing lines (`iter31-counts.py`): a player's
"native_place COMPLETED [Place a torch at P]", then that player's "native_break COMPLETED [... P]" on the same cell,
before it places a torch at P again.

| | Batch 1 | Batch 2 |
| --- | --- | --- |
| Laps | 264 | 3 |
| Seeds with a lap | 20261030 (263), 303 (1) | 202, 303, 606 (1 each) |

- **20261030 batch 1:** all 263 laps are Kindred's, 181 of them at 24, 63, 88 in 27 seconds of wall clock (04:25:02 to
  04:25:29).
  - Each lap is "Iron: light the way - Place a torch at 24, 63, 88", then "Iron: Cast the portal frame from lava and
    water - Cast: clear 24, 63, 88", then "picked up 1 minecraft:torch".
  - The light-the-way step and the cast's clear take the same cell in turn.
  - It cost no stuck spell: Kindred's one spell on that seed was at tick 30820, elsewhere. The portal was lit at tick
    71970.
- **The three laps in batch 2** are single laps by Echo: on a branch-mine clear, a bed's room and a cast clear.
- Iteration 30's consoles were not counted this way.

## #515's WAITING lines, by reason
`iter31-counts.py`: each "WAITING <player> at …; steps: …" line, grouped by its steps and their refusals (numbers
dropped), and each tunnel heading's reason counted once per line.

| | Batch 1 | Batch 2 |
| --- | --- | --- |
| WAITING lines | 34 | 37 |
| Seeds | 404 (13), 31102026 (8), 8675309 (7), 303 (3), 101, 202, 20261030 (1 each) | 202 (13), 8675309 (6), 31102026, 505, 606 (4 each), 101, 707 (2 each), 303, 404 (1 each) |

Both batches together:

| Steps and refusals | Lines |
| --- | --- |
| HUNT (Stock some food while animals are around): no task; CAST_PORTAL: no task | 20 |
| CAST_PORTAL (Cast the portal frame from lava and water): no task | 13 |
| HUNT (Stock some food): no task; MINE_IRON: no task | 7 |
| GATHER_LOGS (Gather logs for sticks): no task | 6 |
| MINE_IRON: no task | 4 |
| HUNT (Out of food; hunt an animal in sight): no task; CAST_PORTAL: no task | 4 |
| HUNT (Stock some food): no task; GATHER_LOGS (for sticks): no task | 3 |
| HUNT (Out of food): no task; MINE_IRON: no task | 3 |
| HUNT (Stock some food): no task; PLACE_TABLE: no task | 2 |
| HUNT (Stock some food): night, food in hand; MINE_IRON: no task | 2 |
| HUNT (Stock some food): night, food in hand; CAST_PORTAL: no task | 2 |
| HUNT (Stock some food): no task; IGNITE_PORTAL: set aside until N [Back to its portal frame to light it] | 2 |
| HUNT (Stock some food): no task; GATHER_LOGS (for a table and a wooden pickaxe): set aside until N [Head back up toward home] | 1 |
| MAKE_ROOM (Make room for flint and steel): no task | 1 |
| HUNT (Out of food): no task; MAKE_ROOM (Make room for stone pickaxe): no task | 1 |

| Tunnel heading reason (once per line) | Lines |
| --- | --- |
| a drop ahead | 19 |
| liquid by N, N, N | 13 |
| no step N, N, N | 10 |
| dug corridor, no fresh face | 9 |
| set aside N, N, N | 8 |
| liquid at or by N, N, N | 2 |

- **Most waits are a cast with no task to offer:** 39 of 71 lines name CAST_PORTAL "no task", and 16 name MINE_IRON
  "no task".
- **Where a tunnel is offered**, its headings are mostly "a drop ahead" or liquid.

## #520's marker lines
"F-25c4dr-marker: <player> at …, its fill of the lava at … refused there, no walk up that moves it: <why>": 45 lines in
batch 1 and 44 in batch 2. Reasons, both batches: "a dig toward it" 80, "the fill once more" 6, "the rock over it
opened" 3.

## Holds, climbs and deaths (as for 28 to 30)
- **Tightened hold count:**
  - Batch 1: 79 walk-back tasks (58 completed), 9 ended lower, 0 taken again in the hold.
  - Batch 2: 140 (124), 15 ended lower, 2 taken again.
  - 31102026 batch 2 carries 55 of batch 2's walk-backs.
- **"Climb to find another way" starts:**
  - Batch 1: 369 (404 191, 202 55, 8675309 45).
  - Batch 2: 244 (505 66, 202 64, 20261030 43).
- **Deaths:** 15 and 9. Of these, 4 and 1 fell in a fight by #471's fight lines (`iter29-deaths.py`).

## Scripts
- `iter31-counts.py` (here): WAITING lines, marker lines and torch laps.
- From 30's folder: `iter30-cast.py`, `iter28-counts.py`, `iter29-deaths.py` and `iter27-counts.py` (the console
  clock).
- Exit figures from each `summary.json`.

No cause is named. Batch 1 meeting all four criteria is one batch; batch 2 misses the stuck criterion by one seed.
