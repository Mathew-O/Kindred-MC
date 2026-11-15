# F-25c4dh — A back-off that leaves a mob on foot on the player is fought, not flipped

Date: 2026-11-14 to 2026-11-15
Agent / branch: Claude, MODES on MOVEMENT (`claude/movement-1114-backoffflip`)
Build: ebdd8729 (main aa584be6 merged in); main's side at aa584be6
Status: in progress. The fixture, the classes and the corpus gate (it passes; the corpus shows no change in deaths either way) are below; the PR's CI remains.

## Scope and implementation

User-visible behavior: e40a22's A1, five of that run's deaths. A player badly hurt, with a zombie after it, switched
between "Back off" and "Hole up: recover behind the wall" every second or two until the zombie killed it. A zombie walks as
fast as a player, so each back-off ended with it still close. The rest between back-offs was taken in the open, with no
wall, and was cut short by the zombie's hits. It ate with the zombie on it. In seven of the nine back-off deaths it was not
holding its sword: 505-kindred-33951 died with a shield, armour 6 and cooked porkchop in hand.

What changed, in three parts:

1. **A back-off that gained nothing is followed by a fight** (`PortalRunCampaign`).
   - When a "Back off" walk ends, its tick is kept (`taskFinished`).
   - A mob on foot coming into reach within 5 s (`GAINLESS_CATCH` = 100 ticks) makes the back-off gainless.
   - For 10 s after (`GAINLESS_HOLD` = 200 ticks), the player:
     - does not back off from a mob on foot after it within 4 blocks, whether in reach or close behind;
     - does not hole up while such a mob is within 8;
     - is `cornered()`, so "Badly hurt; break off the fight" does not cut the fight short.
   - The fight's target is that mob even below six health, where `threats()` is empty by design. The weapon goes to hand
     through `strike`.
   - What stays as before: the first back-off (`goalBreaksOffAFightOnceBadlyHurt` keeps its assertion), a back-off that
     opens distance, cover from shooters, creepers and hoglins.
2. **No rest in the open with a mob on foot after it** (`PortalRunCampaign.holeUp`). "Hole up: recover behind the wall"
   is not taken when more than four ways are open round the player and a mob on foot after it is within 16. Behind a wall
   (four or fewer open, F-25c4dc's sealed passage among them) it is taken as before.
3. **No meal with a mob on foot that can get at it** (`PlayerRuntime.mobCanReachToEat`). **This is a change for every
   native player, not only the goal's.**
   - The survival eat is not chosen while a hostile on foot after the player can get at it: within 2 blocks, or in sight
     with a path to it (`findPathTo` reaches the player).
   - It looks out to 6 blocks, or only 3 when the bar is at 6 or below, where hunger is about to cost health.
   - Shooters and creepers do not hold a meal back.
   - Walled in, with a zombie outside, the zombie fails the sight and path test, so the player eats as before.
   - Its cost: `findPathTo` runs only when such a mob is within range, and only at the decision cadence, never per tick. In
     one fixture run of the four cases it ran 1, 5 (over ticks 37 to 140), 2 and 0 times; the walled-in case 0, since the
     zombie outside cannot see the player.

Changed modules/files:
- companion-server-adapter: `PortalRunCampaign` (`taskFinished`, the goal's hole-up choice, `defend`, `cornered`, `holeUp`,
  `huntedOnFootWithin`, `nearestHunterOnFoot`); `PlayerRuntime` (`mobCanReachToEat`, the survival eat).
- gametest: `NativeBackOffFlipGameTests` (new, four cases) and its `fabric.mod.json` line.
- docs: this file, `feature-spec.md` (this row, F-25c4dh1 filed, E-08a1 ticked), `validation.md`.

## Acceptance evidence

### The fixture, `NativeBackOffFlipGameTests`

Staged after 505-kindred-33951. Each case runs on a level stone arena 61 across, roofed four blocks up, in the middle of the
test's own WIDE plot, at night:
- the player at health 5 and food 16, cooked porkchop in hand, an iron sword on the hotbar;
- a shield in the off hand, and a leather chestplate and leggings;
- no block to wall itself in with;
- difficulty normal while it runs, put back after.

The zombies and the spider are spawned with `TestContext.spawnEntity`, so they keep their goals and pursue and strike for
real. `spawnMob` clears a mob's goals, and a mob so spawned neither pursues nor strikes: see F-22c80. An earlier staging,
with `spawnMob` and a scripted pursuit, was dropped once that cause was found.

What each case asserts:
- **Second zombie behind** (a second zombie eight blocks behind it, three seconds in). The fail-before, pass-after case:
  never killed; at most 2 gainless back-offs (ended with a mob on foot within 2.5); a sword fight; at most 2 switches
  between backing off and holing up.
- **One zombie.** A guard: never killed, a sword fight, at most 2 switches, at most 2 gainless back-offs.
- **A zombie and a spider.** A guard: never killed, its choice held. Holing up and staying holed up passes. The sword fight
  was asserted here too at first. In 1 of the first 3 runs after the fix it lived without one. That assertion was dropped
  for this case only, as the lead had set its criterion before those runs.
- **Walled in, food 6, a zombie outside the wall.** A guard for the eating rule: it eats within 800 ticks.

| Case | Main aa584be6 / 9f53db31: gainless, switches, killed, sword | After ebdd8729, ×3 |
| --- | --- | --- |
| Second zombie behind | 1, 1, 1; 0; **killed 3 of 3**; yes | 1, 0, 1; 0; **killed 0 of 3**; yes |
| One zombie | 0, 0, 0; 2; killed 0 of 3; yes | 0, 0, 0; 2; killed 0 of 3; yes |
| Zombie and spider | 0, 0, 0; 0; killed 0 of 3 | 0, 0, 0; 0 to 1; killed 0 of 3 |
| Walled in, hungry | — | ate 3 of 3 within the bound |

Deaths of the second-zombie case, over every run on the way, so the record is whole:
- main: killed 3 of 3 alone, and 3 of 3 in an earlier set;
- the first pass (the hold alone): 2 of 3;
- the second pass (catch-up, eating within 3): 3 of 3;
- the third pass (eating within 6, `cornered()` sees the catch-up): 0 of 3 alone, 0 of 3 in class runs (one of them
  alive but with 8 switches, which part 2 answers);
- part 2 added: 0 of 3 alone, 1 of 2 in class runs (killed backing off from a zombie 2.5 to 4 blocks behind);
- ebdd8729, which holds that one too: 0 of 3 alone, 0 of 3 in class runs.

At 2.5 blocks the gainless count does not see main's loop in the one-zombie case: the zombie is 3 to 5 behind when each
back-off ends. That case was kept a guard, as the lead ruled; the threshold was not moved to make main fail.

With the second zombie and part 2, the goal after each skipped rest was a fight, another back-off, or "heal before going
on"; never a stall. A trace of all three runs, repeats folded:

```
Back off from zombie → heal before going on → Fight zombie → Back off from zombie → heal before going on → Fight zombie
→ Back off from zombie → heal before going on → Back off from zombie → heal before going on → Back off from zombie
→ Fight zombie → Back off from zombie → Back off from zombie the way it came → Fight zombie (sword in hand, alive)
```

**An observation, left as it is:** "heal before going on" (a 4 s rest) is taken while a mob is after the player. Its
condition, "hurt with nothing after it", reads `threats()`, which is empty below six health by design, so at low health
"nothing after it" is read wrongly. The "A hostile mob is attacking; fight back first" interrupt cuts the rest short. Filed
as F-25c4dh1.

### Classes ×3, branch 84225419/ebdd8729 against main aa584be6

The 11 classes that fight, retreat or hole up: `PortalCampaign`, `Combat`, `Tactical`, `Encounter`, `MobRhythm`, `Nights`,
`PassageSeal`, `WeaponToHand`, `Initiative`, `CoverClimb`, `LifeFirst`. On the branch they run with `BackOffFlip` as well.
- **On ebdd8729:** 140 of 140, 140 of 140, 140 of 140.
- **On main:** 136 of 136, 136 of 136, 136 of 136.

Earlier sets, on the second pass and on part 2, found the two holes above; each failure was the new fixture's own
second-zombie case.

REFACTOR's PR 3 (F-22c80: ten `PortalCampaign` combat fixtures spawned with `spawnEntity`) has not landed. These runs are on
today's main without it; REFACTOR will run those ten on this branch when it does.

### Corpus gate on ebdd8729

`tools/corpus/gate.sh`, baseline 16cb7a2 (6 runs), main's side the merge-base aa584be6: 279 of 444 scenes pass over 3 runs
(78 fail, 87 intermittent). "Replay players still acting after their own batch": 0, 0, 0.

Flagged (passing on the baseline, failing in 2 or more of 3 runs here): 5. All were cleared by the gate's own confirm
(alone ×3 a side); none was confirmed:

| Scene | Branch failed | Main passed |
| --- | --- | --- |
| `e40a14-80b7150-101-echo-75842` | 2 of 3 | 1 of 3 |
| `e40a15-dbf4e5c-303-kindred-66804` | 1 of 3 | 2 of 3 |
| `e40a15-dbf4e5c-404-kindred-36022` | 0 of 3 | 2 of 3 |
| `e40a15-dbf4e5c-8675309-echo-87839` | 0 of 3 | 2 of 3 |
| `e40a20-79a9aad-202-sol-100125` | 1 of 3 | 3 of 3 |

**The deaths criterion, from the corpus: no change either way.**
- Against E-08a1's gate on the same 444 scenes (6520ee81, main b23acc09), the death classes pass about the same: deaths
  213 → 212 of 325, zombie 74 → 72 of 116, skeleton 51 → 50, spider 15 → 14, drowned 28 → 27. That is within what the
  corpus moves between runs; no gain or loss is claimed.
- The five A1 scenes themselves come out the same on both sides. 101-echo-17476, 202-kindred-48843, 505-kindred-33951 and
  707-kindred-136143 pass every run before and after. 707-sol-24111 fails the same entries on both.
- So as replayed, the A1 scenes do not die before the fix. The corpus does not measure A1's deaths; the fixture is where
  the effect shows (3 of 3 killed to 0 of 3). A live run or the e40a22 seeds again would show it on the deaths criterion.

## Remaining limitations and handoff

- F-25c4dh1, open: a rest to heal taken with a mob on foot after it, from the low-health "nothing after it" read.
- The second-zombie case is one staging. The corpus's A1 scenes do not die as replayed, before or after, so the deaths
  criterion's effect is not yet shown outside the fixture: a live run, or the e40a22 seeds again, would show it.
- Next dependency: the PR's CI and the lead's merge; then E-71b1.
