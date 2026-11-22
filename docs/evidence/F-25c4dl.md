# F-25c4dl — Its own shelter, sealed tonight, is not opened by its own mine before daylight

Date: 2026-11-22
Agent / branch: REFACTOR, claude/f25c4dl-seal-mine-1122, at the lead's word (stuck, "mining for a find", family A)
Build: 4ef11c27 (the change and its fixture), on main 47dbfbc5
Status: complete (2026-11-22). The stuck exit figure stays open: this alone flips no seed (below).

## What this row claims
- At night, a planned `native_break` of one of the player's own seals placed in the last 12,000 ticks is not run. The night shelter's own step (`daylightBelow`) runs instead: seal what is still open, else "Wait for daylight". Progress reads "its shelter sealed for the night - …".
- **The seal is kept.** What is held back is the mine's break of it, never the seal.
- **It does not claim:**
  - (B), the branch mine that advances and is turned by the watchdog. That comes later, as a proposal with data first.
  - That a bed which may not go down now goes down. That is E-70a and F-25c4di1c/d's.
  - A seed under the 12-minute bar.

## The loop (E-40 step 8, `e40a25s-0d3432b`, consoles on `claude/corpus-data-e40a25s-1121` at `fe0be8cd`)
- **From LOOPS's census of "mining for a find":**
  - **303-on:** Kindred's spell at 79760; Sol's at 16740, 41240 and 79240. 12 minutes.
  - **101-off:** Echo's at 77560. 3 minutes.
- **Each spell shows one cell going back and forth:** "Branch mine for iron: clear C" then "Seal the shelter at C", with no advance.
- **The marker count:** a seal of C following a clear of C, or the reverse, at cells with more than 6 (the fixture's bar).
  - **303-on:** 1,332 in all. Kindred 351 at −1, 20, −78. Sol 331 at 176, 20, 10; 309 at −93, −54, 158; 231 at 175, 20, 11; 92 at −94, −53, 156; and 18 at −65, −53, 173.
  - **101-off:** 581 in all, all Echo: 343 at −396, −53, −376; 145 at −396, −53, −397; and 93 at −464, −53, −356.
  - The script is `flips.py` in the session's scratch; the count only reads the two consoles' goal lines.
- **No world data was read,** in diagnosis or staging. The diagnosis is from the two consoles and the code. The fixture's ground is staged from scratch in a GameTest plot. 303's kept world was not opened.

## Why (`PortalRunCampaign`)
- **The seal comes first:** `carriedBedNight` (E-70a) calls `seal(body, room)` before its bed checks.
- **Then the bed may not go down now:**
  - in `BED_DOWN_BACKOFF`'s 200-tick wait after a refusal;
  - after `BED_DOWN_REFUSALS`, 3, in a night ("given up tonight");
  - or with a way into the pocket left (`waysIntoBedPocket`).
- **It then hands the night back** (`Optional.empty()`). Below ground the stage's own plan goes on: core `Digger.tunnel`'s branch mine for iron. The mine's next cell is the seal it has just placed, so it clears the seal. The next tick the night's seal places it again.
- **Sol's 176, 20, 10 spell** came after three "Owned inventory changed before the bed was set down" refusals. The other three 303-on spells show no bed line that night: the bed's own events don't reach the console, so their path can't be named. Every path above ends at the same hand-back.

## The change (`PortalRunCampaign`)
- **`breaksTonightsSeal(player, task, tick)`:** true for a `native_break` at night whose cell is in `ownSeals` (the seals it placed and completed) with a tick within `SEAL_TONIGHT`, 12,000. It is false for "open its shelter at", the way out by its own seal (`wayUp`'s escape).
- **In `plan()`, beside F-25c4cd's dug-in rule:** a planned step for which it is true, and which is not `passive` and not `surfacing`, is replaced by `daylightBelow(body)`.
- **What it reads:** only its own seals and the clock, nothing of the world it hasn't placed itself. The atlas, perception exposure and line-of-sight rules are untouched.

## How score.py classes "Wait for daylight"
- **`tools/eval/score.py`, line 62,** in `STUCK_QUIET_RULES`:
  > `("waiting for daylight or a teammate", re.compile(r"Wait for daylight|Wait for the team's|nothing to do before daylight")),`
- **The quiet rules only name an opened spell.** They are read only for a spell the watchdog has already given up ("Goal made no progress for N ticks"), and only where no rule above reaches 3 lines in its last 60. They don't open a spell.
- **The hold opens none.** `PortalRunCampaign.passive` (line 1406) begins:
  > `return label.equals("Wait for daylight") || … || label.startsWith("Seal the shelter") || …`
- **The watchdog's clock** (`stuckClock`, line 1278) advances only while `lastActive`. `lastActive` is false for a passive plan. Both steps the hold can give are passive, so the time it holds is not counted toward a spell.
- **A spell can still read as "waiting for daylight"** if it was opened by time already counted before the hold, and wait lines then fill its last 60 lines. The class ×3 and the corpus gate below show no such spell. The E-40 re-run is the measure.

## Pre-stated gate read (sent to the lead before the gate, 2026-11-22 02:41)
- **It acts only at night,** on a planned break of the player's own seal from tonight.
- **Expected:**
  - the flip spells go: 303-on's 4 and 101-off's 1;
  - 303-on 24 → 12 minutes, at the bar, not under;
  - 101-off 12 → 9.
- **(A) alone flips no seed:** 303-on lands at the bar, not under it, and 101-off was already under.
- **The cost:** a night waiting in place of mining through its own seal, so fewer iron finds at night. I said I'd count "its shelter sealed for the night" lines per run.

## Fixture (staged): `NativeBedRetryGameTests.aSealedPocketWhoseBedWillNotGoDownIsNotOpenedByItsOwnMineAndSealedAgain`
- **Setup:**
  - batch `bedSealMine`, `FixtureGround.WIDE`;
  - a stone slab 20 each way round the pocket, the pocket and its bed's room open;
  - a sheep with no AI in the bed's foot cell, so vanilla refuses the bed (F-25c4di1c's staging);
  - the iron stage's kit with a white bed; night held at 14,000; `OPTIMAL_SURVIVAL`; the portal goal.
- **What it checks:** each tick it reads the goal's progress for "Seal the shelter at" and ": clear" and counts, per cell, a seal after a clear or the reverse. It fails at more than 6 at one cell, with the failure sticky across the runner's retries. It passes after 2,400 ticks with at least one seal.
- **Before** (main 47dbfbc5): 3 of 3 fail, each at the pocket's cell 0, 1, −3. A probe without the bar counted 186 and 187 alternations in 2,400 ticks.
- **After:** 3 of 3 pass. Each run made one clear before its first seal, then held: "Iron: its shelter sealed for the night - Wait for daylight". There was 1 alternation in each run (4, 4 and 2 seals; 1 clear).
- **The classes it could touch,** 3 times: BedRetry, BedPocket, BranchMine, Nights and WakeAtBed. All 18 required tests passed in each run, 54 of 54, with no crash.
- **Again after merging main** (ce74e567, with F-25c4dk's #439 in the same file), the same five classes 3 times: 54 of 54.
  - The fixture read 1, 2 and 2 alternations.
  - **The 2s are one break, not two.** In run 2 the plan named the cell's clear once more while its seal was still being placed: `ownSeals` records a seal only once it completes. No `native_break` of the cell followed; the next steps were the hold's ("its shelter sealed for the night - Seal the shelter at …", then "Wait for daylight").

## Corpus gate (F-27a, 472 scenes, on 4ef11c27; main 47dbfbc5, the merge-base)
- **Over the three runs:** 312 of 472 scenes pass all three (66.1%); 80 are newly passing against the 16cb7a2 baseline. Replay players acting after their own batch, as the report counts them: 38, 34 and 36. In run 2 all 34 are the periodic "Kindred cost: day N" summary line, whose "webs by player" list names each player: a line about players, not a player acting. That line is main's, not this change's; `report.py`'s `leftover` doesn't skip it yet.
- **Flagged:** 3. Each was confirmed alone, 3 times here and 3 times on 47dbfbc5. **0 confirmed; all 3 cleared:**

| Scene | Branch failed (alone) | Main failed (alone) |
| --- | --- | --- |
| e40a14-80b7150-101-echo-75842 | 3 of 3 | 3 of 3 |
| f29e-on-4h-707-kindred-222518 (known intermittent) | 1 of 3 | 2 of 3 |
| factions-e40a16seeds-b7b4bec-8675309-echo-106722 | 2 of 3 | 2 of 3 |

- **No changed line acts in the flagged scenes:** no "its shelter sealed for the night" line in any of the 18 confirm runs.
- **Where it acted:** 16, 24 and 12 hold lines a run. By what followed: 11, 12 and 7 sealed something still open; 5, 12 and 5 waited for daylight. They came from 11 scenes in all, and those scenes failed 0, 0 and 3 times a run.

## The cost, as a number
- **The corpus has no time of day on a line,** so night-time iron is measured as iron in the scenes where the hold acted (it acts only at night).
- **In the gate's full runs:** 93, 83 and 87 raw iron pickups a run in all scenes. In the hold scenes, 0, 1 and 1.
- **Against main,** each of the 11 hold scenes was run alone, once on 47dbfbc5 and once on 4ef11c27:

| Scene | Main: result, holds, raw iron, seals | Branch: result, holds, raw iron, seals |
| --- | --- | --- |
| e40a15-dbf4e5c-303-kindred-66804 | pass, 0, 0, 0 | pass, 0, 0, 0 |
| e40a13-fe2ba3b-707-sol-11919 | pass, 0, 0, 0 | pass, 0, 0, 0 |
| f29e-on-4h-8675309-echo-108154 | pass, 0, 0, 17 | pass, 4, 0, 9 |
| e40a20-79a9aad-31102026-echo-82240 | pass, 0, 0, 0 | pass, 0, 1, 0 |
| e40c1-eed2dc5-101-sol-236961 | pass, 0, 0, 10 | pass, 0, 0, 12 |
| e40a20-79a9aad-8675309-kindred-83380 | pass, 0, 0, 0 | fail, 0, 0, 0 |
| factions-e40a16seeds-b7b4bec-707-kindred-21883 | pass, 0, 0, 0 | pass, 0, 0, 0 |
| e40a20-79a9aad-202-echo-110820 | pass, 0, 0, 0 | fail, 0, 0, 5 |
| e40a22-d2389c9-8675309-kindred-42831 | pass, 0, 0, 5 | pass, 0, 0, 5 |
| e40a17-7bc5b42-707-echo-36256 | fail, 0, 0, 0 | pass, 0, 0, 0 |
| e40a16-922f4fd-707-kindred-43985 | pass, 0, 0, 5 | pass, 0, 0, 0 |

- **Raw iron picked up, all 11 together:** 0 on main, 1 on the branch.
- **The hold acted:** 0 times on main (it isn't there) and 4 times on the branch, all in f29e-on-4h-8675309-echo-108154, which passed on both.
- **Why the counts are so low:** run alone, most scenes don't reach their night. A scene's batch sets its clock, as `gate.sh`'s header explains.
- **Outcomes:** main failed 1 of the 11 (e40a17-7bc5b42-707-echo-36256) and the branch failed 2 (e40a20-79a9aad-8675309-kindred-83380 and e40a20-79a9aad-202-echo-110820). None of the three had a hold line in its run, and each passed in all three of the gate's full runs.
- **The cost, then:** no night-time iron lost against main that the corpus can measure. 16, 24 and 12 hold lines a run in the full runs, 4 in the scenes run alone. The E-40 re-run measures it on a whole night's play.

## Remaining
- The E-40 re-run decides the stuck figure. That's the lead's call, not this row's.
- (B), the advancing branch mine the watchdog turns, as a proposal with data first: blocks advanced per minute, and whether ore was found later.
