# F-25c4dq — A branch mine begun lays its legs where it has stood least

Date: 2026-11-23 to 2026-11-28
Agent / branch: Claude, LOOPS / `claude/holepit-ironless-1123`, from main `853b686c`, at the lead's word
Build: the claim `5ec51c6b`; the fixture `a1f87f06`; the first change `41a38c4c`, re-keyed as `bf6c3f88`, gated there; main
(`e99ee812`) merged in as `af87a89a`
Design parent: F-25c4 (the portal run's iron hunt)
Status: in progress (2026-11-28), at the lead's word. The staged shape is cured; the corpus shows no gain; iron was just outside
main's spread. It stays In progress until E-40 iteration 32's live read shows whether working branch mines still count as
stuck.

## Scope and implementation

**Found:** main's own holepit stuck scene, `diag-holepit-079bdc2-8675309-kindred-111140`, fails 13 of 61 runs (21%) on main
`120e35ac`, "still in its stuck spell after 3600 ticks" (REFACTOR's runs while gating F-25c4dm3).
- **REFACTOR's 98 runs, read with `boxread.py`:** main's 13 failures were 9 runs of a *working* branch mine and 4 of a second
  shape (a mine begun 2 to 4 off that barely digs). In the 9, the iron hunt's explore walks round where the spell began came
  first. Then a branch mine for iron laid its legs back across that ground: legs twenty long, three apart, alternating, so leg
  after leg swept through the eight blocks round where the spell began. Every step was fresh rock, no iron was found, and none
  of it was new ground to its watchdog.
- **Near starts against far:** 7 of 16 runs failed from a near start, 10 of 62 from a far one.
- **E-40 iteration 29, read with `minespells.py`:** 8 of 112 stuck spells were such a working mine, 24 of 375 stuck minutes. No
  seed crossed the bar on these alone.

**The cause:** `Digger`'s branch mine takes its first leg along the heading the dig already had, with no regard to where the
player has stood. Begun beside ground it has walked, its legs can lay straight back across it.

**User-visible behavior:** an AI player that starts a branch mine for iron right beside ground it has already walked over now
mines into ground it has not been in. Where all round it is new ground, it mines as before.

**Changed files:**
- `Digger` (core):
  - `branchBegins`, when a branch mine begins (none gone on with for 600 ticks). If the first four legs of its heading would
    run through 3 or more eight-block cells it has stood in (`BRANCH_SEEN_TURN`), it takes, of the four headings and the two
    sides its steps between legs may go, the one through the fewest. Ties keep the heading and side it has.
  - `seenAhead` counts those cells over the first four legs: 21 along and 10 over.
  - Leg length, step and alternation are unchanged.
- `PortalRunCampaign` (adapter): `descendOrTunnel` hands the digger the player's own record of the eight-block cells it has
  stood in, `seenCells`, as a snapshot taken when the mine begins. That is the same record its watchdog counts new ground by.
- **It reads only where the player itself has stood.** No unseen block is read.
- **The marker:** "F-25c4dq-marker: <name> branch mine for <what> begun at <pos>: its legs laid <way>, not <way>, toward
  ground it has not stood in". It is said when a mine is begun with its legs laid elsewhere than its heading.

Nothing is given or moved. No timeout or stuck threshold is changed, and the scene's pass criterion is unchanged.

**Schema/configuration changes:** none. No saved keys.

**Gametest:** `NativeBranchAwayGameTests` (new, registered after `NativeBranchMineGameTests`).

## Acceptance evidence

**Fixture, `aBranchMineBegunBesideGroundItHasStoodInLaysItsLegsIntoGroundItHasNot`. STAGED:**
- `NativeBranchMineGameTests`' slab: stone round it with no ore, a pocket two high, the morning held, the sprint;
- its kit as though made by the iron stage: stone pickaxes, a stone sword, bread, torches, coal, sticks, cobblestone, a table
  and a furnace;
- through the campaign's fixture setter, the eight-block cells within twelve of a place 3 east and 9 north of the pocket
  recorded as stood in, as its watchdog records them. That is the holepit scene's explore walks round where its spell began;
- its digs heading north, across that place, as the scene's failing runs began;
- its watchdog's own count held off.

It fails if, once its branch mine for iron has begun, its feet come within eight blocks across of that place before it has dug
30 blocks of the mine.

| Check | Build | Result |
| --- | --- | --- |
| Fixture before, first keying (the place the spell was caught) | main's source with the fixture (`a1f87f06`) | fails 3 of 3: "Its branch mine came within 8 of where the spell was caught, at 0, 0, −1 from its pocket, after 2 blocks" |
| Fixture after, first keying | `41a38c4c` | 3 of 3 pass; but keyed to the place the spell was caught, it could not act in the scene nor in 6 of iteration 29's 8 live spells, so it was re-keyed to ground stood in |
| Fixture before, re-keyed (ground stood in) | main's source with the final fixture | fails 3 of 3: "Its branch mine came within 8 of the place it had walked round, at 0, 0, −1 from its pocket, after 2 blocks" |
| Fixture after, re-keyed, no threshold | — | 3 of 3 pass, but it turned mines on eight-block alignment noise alone; hence `BRANCH_SEEN_TURN` = 3 |
| Fixture after | `bf6c3f88` | 3 of 3 pass: 30 blocks of mine, never nearer than 9 to the place it had walked round |
| Related classes ×1 (17) | `bf6c3f88` | 164 of 164 |
| Core unit tests | `bf6c3f88` | 1,085 of 1,085 |
| Core unit tests | `af87a89a` (main `e99ee812` merged in, with #510's `Digger` change) | 1,094 of 1,094 |
| The fixture and 7 close classes ×1 (BranchMine, HandPillarAside, CastWalkUp, RetraceLower, Stair, CoverClimb, CastPool) | `af87a89a` | 16 of 16 |

**The related classes:** Atlas, BranchAway, BranchMine, CastPool, CavernLedge, CoverClimb, Lessons, Navigation,
PortalCampaign, Rescue, RetraceLower, Spire, Stair, StrandedReturn, Survival, ToldLava, TripDown.

## Corpus gate (`bf6c3f88`; baseline `3354cb33`)

**The marker,** stated before the gate, is the line above. It is said whenever the change lays a mine's legs elsewhere than its
heading, and that is the only decision it alters.

| Run | Minutes | Peak game-JVM VmHWM | Required tests failed | Marker lines |
| --- | --- | --- | --- | --- |
| 1 | 32 | 3,843 MB | 114 | 15 |
| 2 | 32 | 3,233 MB | 126 | 18 |
| 3 | 32 | 3,172 MB | 115 | 11 |

**Against the baseline** (`report.py`, its exact `corpus.<scene>` matching): 25 scenes newly pass, and 4 are flagged, all 4
known intermittent (KI).

| Scene | Body | Full runs | Failure | Marker |
| --- | --- | --- | --- | --- |
| e40c1-eed2dc5-101-sol-13531 (KI) | R55d4c76_1 | P F F | died again (skeleton) | 0 in every run |
| e40c1-eed2dc5-101-sol-29848 (KI) | R56e299a_1 | F F F | died again (spider) | 0 in every run |
| f29e-on-505-echo-43893 (KI) | Rfe89b77_1 | F F P | died again (zombie) | 0 in every run |
| factions-e40a16seeds-b7b4bec-707-kindred-88286 (KI) | R3c6bbb7_1 | P F F | died again (zombie) | 0 in every run |

**Result: 0 confirmed. The gate passes.**

**The target scene,** `diag-holepit-079bdc2-8675309-kindred-111140` (`R48788b7_1`): F P P.
- Run 1 failed "still in its stuck spell", with no marker line.
- Run 2 passed, with its marker said once.
- Run 3 passed, with no marker line.

Main's rate there is 21%, and three runs cannot tell it from main.

**The iron hunt's counts** (`gatecount.py`: bodies reaching iron and an iron pickaxe after their first goal line, in place of
times; scenes failed for dying again or for their stuck spell; branch mines begun), against main `853b686c` ×3:

| Count | Main runs 1 / 2 / 3 | This change, runs 1 / 2 / 3 |
| --- | --- | --- |
| Iron | 181 / 179 / 180 | 182 / 178 / 178 |
| Iron pickaxe | 104 / 102 / 102 | 102 / 103 / 103 |
| Died again | 88 / 88 / 89 | 82 / 91 / 84 |
| Stuck (Iron stage) | 33 (22) / 34 (21) / 36 (22) | 32 (20) / 35 (22) / 31 (20) |
| Branch mines begun | 423 / 404 / 456 | 478 / 436 / 390 |
| Required tests failed | 121 / 122 / 125 | 114 / 126 / 115 |

- Every count is inside main's spread but iron, at 178 twice against main's lowest 179.
- Main's holepit scene went P P F against this change's F P P.
- **Read (the lead's, 2026-11-28):** no worse. The staged shape is cured; the corpus shows no gain; iron, at 178 twice, was
  just outside main's spread, within the noise at three runs.

**Kept:** the runs, this change's and main's, are on `claude/spacer-runs-1112`, under `gate25c4dq/`.

## Remaining limitations and handoff

- **Remaining, the live read, E-40 iteration 32:** whether working branch mines still count as stuck (8 of iteration 29's 112
  spells were). The marker counts how often a mine is laid away from its heading. This row stays In progress until then.
- **The second shape** of main's 13 holepit failures, a mine begun 2 to 4 off that barely digs (4 of 13), is untouched. It is
  its own row if the live read keeps it.
- **No corpus gain:** the shape is cured in the staged scene but did not show as a gain in three corpus runs.
