# F-25c4dk — A block readied for the seal and a torch readied to light the way do not swap each other out

Date: 2026-11-22
Agent / branch: Claude, LOOPS / `claude/hotbar-swap-1121`, from main `59f62762`
Build: the fixture `4bdb963d`; the fix `18f63e4e` (gated there, with a temporary marker); the marker removed `b35bebb8`;
main `2c51fcb1` merged in as `afcb129e`
Design parent: E-71h (the hotbar's fixed layout)
Status: complete (2026-11-22).

## Scope and implementation

**Found:** in the busy-work census of step 8's consoles ([e40a25s-busywork](eval/e40a25s-busywork.md),
`claude/corpus-data-e40a25s-1121` at `fe0be8cd`), 505 on Echo's hemmed-in spell alternated between two goal steps:
- "Seal the shelter: ready a block";
- "Ready a torch".

Neither the block nor the torch was placed.

**The cause:** `swapToHotbar` put a readied item in the first empty hotbar slot, else slot 8. With a full hotbar, the
seal's block and `light()`'s torch both went to slot 8, each evicting the other.

**The conditions:**
- dark at its feet, underground;
- no cell where a torch can stand;
- an unsealed opening, sealed by the hold's or the wait's `daylightBelow`;
- `light()` planned before the hold.

**Alternations in step 8's consoles, before the fix:**

| Seed, arm, player | Alternations |
| --- | --- |
| 303 off Sol | 4,560 |
| 505 on Echo | 1,412 |
| 202 on Kindred | 78 |
| 303 on Echo | 34 |

**User-visible behavior:** with a full hotbar, a block readied to seal the night shelter stays on the hotbar while a
torch is readied, and the seal is placed.

**Changed files:**
- `PortalRunCampaign.swapToHotbar`:
  - the target slot is chosen as `handSlot` chooses: an empty slot; else one holding nothing kept to hand; else a block,
    while another block stays;
  - with every slot holding something kept to hand, slot 8, but not over a torch or a block, which another step has
    just readied: then the highest slot below it holding neither;
  - the F-25c4cv rule is unchanged: never over the only sword or axe on the hotbar (then slot 7).
- `readied(stack)`: new, a torch, soul torch or block item.
- **Gametest:** `NativeHotbarSwapGameTests` (new, registered in `fabric.mod.json`).

Nothing is given or moved by the code: it changes only which hotbar slot an item the player holds is swapped into. It
reads only the player's own inventory.

**Schema/configuration changes:** none.

## Acceptance evidence

**Fixture (staged):** `NativeHotbarSwapGameTests.withAFullHotbarItSealsItsShelterAndTheTorchDoesNotUndoIt`, batch
`hotbarSwap`.
- **Staging:**
  - night held at 14000, as `NativeNightMeanwhileGameTests` holds it;
  - stone under dirt, no wood;
  - three iron ore on the surface in sight, so its route's next step is on the surface and waits for daylight;
  - a full hotbar of things kept to hand: stone pickaxe, sword, axe, shovel and hoe, bread, a water bucket, shears,
    flint and steel;
  - cobblestone and torches only in the backpack.
- **The run:** it digs in and seals itself. Then, staged:
  - a one-cell gap is opened in its shelter's side at its feet, over a one-block drop;
  - the torches near it are removed and its cell is walled in, so it is dark there, with nowhere to set a torch;
  - its blocks and torches are put back in the backpack, with the hotbar full as before.
- **Fails if:**
  - the goal changes between readying a block for the seal and readying a torch more than 4 times; or
  - the gap is not sealed within 400 ticks.

| Check | Build | Result |
| --- | --- | --- |
| Fixture before the fix | `4bdb963d` (main `59f62762` plus the fixture) | 3 of 3 fail: the block and the torch swap each other out (294 alternations by the time limit; the gap open) |
| Fixture after | `18f63e4e` | 3 of 3 pass: sealed 93 to 97 ticks after the gap opened, 1 alternation |
| 29 related classes ×1 | `18f63e4e` | 215 of 215 pass |
| Fixture and 4 close classes ×3 | `afcb129e` (main `2c51fcb1` merged in) | 3 of 3 pass, 6 of 6 tests each: sealed 96 to 97 ticks after the gap opened, 1 alternation |

**The 29 related classes:** Native… AttackWear, AuctionScreen, BackOffFlip, BedPocket, Bridge, Combat, FactionPlay,
FactionShelter, GatherTarget, Horse, HotbarSwap, Lessons, Manhunt, MarketScreen, MobRhythm, Navigation, NightShelter,
PlayerBody, PortalCampaign, Rescue, RescueRounds, Survival, WeaponToHand, NightMeanwhile, NightWalk, NightPortal,
ShelterWay, Light, KitBeforeNight.

**The 4 close classes:** NativeLight, NativeNightShelter, NativeShelterWay, NativeWeaponToHand.

## Corpus gate (`18f63e4e`; base main `59f62762`)

**The marker, stated before the gate:** a temporary line logged each time `swapToHotbar`'s target differed from the old
rule's (the first empty slot, else 8, then the F-25c4cv rule). That is every swap the change acts on. It was removed
after the gate (`b35bebb8`).

**The three full runs:** each run took 57 to 60 minutes, with a peak game-JVM VmHWM of 3,109 to 3,256 MB.

| Run | Required tests failed | Marker lines |
| --- | --- | --- |
| 1 | 120 | 2,614 |
| 2 | 123 | 2,809 |
| 3 | 120 | 2,793 |

- **Against the baseline** (`16cb7a2`, six runs): 73 scenes newly pass, and 3 are flagged.
- **Run 2 was restarted:** a first attempt was stopped at the background job's two-hour limit before it finished. Its
  partial log was discarded, and the run was made again from the start.

**Deaths across the full runs, which the lead asked for:**
- A death is a scene failure that says "died again, as it did": the harness stops a replay at its first death, so there
  is at most one per scene per run.
- The baseline's own six runs are on `claude/corpus-data-rebaseline-16cb7a2-1121` at `29162b73`, from RUNNER 2. My
  count of them matches RUNNER 2's.

| | Per run | Mean |
| --- | --- | --- |
| Baseline `16cb7a2`, its 422 scenes | 88, 81, 90, 89, 87, 86 | 86.8 |
| Branch, the same 422 scenes | 74, 77, 71 | 74.0 |
| Branch, all 472 scenes | 89, 90, 91 | 90.0 |

The 50 scenes added since the baseline have no baseline runs, so only the first two rows compare.

**Flagged:** 3. The marker is logged 0 times for any of their bodies, in each full run and in every solo run.

**The reading, stated before the results:** each scene runs alone, sides alternating, ×3 on the branch and ×3 on the
base, taken to ×9 where part 1 is not met at ×3. A scene clears only if:
1. the base fails it alone at least once;
2. the branch is not worse at one-sided Fisher p < 0.10;
3. no changed line acts in it.

| Scene | Full runs | Branch alone | Base alone | p | Reading |
| --- | --- | --- | --- | --- | --- |
| e40a15-dbf4e5c-303-kindred-66804 | F F F | F P P | F P F | 0.95 | clears |
| f29e-on-1530142077-echo-83228 | P F F | F P P | P F P | 0.80 | clears |
| f29e-on-4h-707-kindred-222518 (known intermittent) | F F F | F F F | F F P | 0.50 | clears |

- **Every failure is a zombie death**, on both sides, in the full runs and alone.
- **Part 3:** the marker shows the changed rule picked no different slot in any of them.

**Result: 0 confirmed. The gate passes.**

**Kept:** the runs and the confirms are on `claude/spacer-runs-1112`, under `gate25c4dk/`.

## The night-wait measurement gap

`score.py`'s night-wait exemption can hide a loop like this one. 303 off Sol alternated 4,560 times and scored only 3
stuck minutes. So this fix's effect on the stuck bar may not show in stuck minutes.

`score.py` is not changed. The alternation counts after the fix will be re-scored from the next E-40 iteration's
consoles on main, which carries the fix; there is no dedicated batch.

## Remaining limitations and handoff

- **A hotbar full of readied items:** with every slot holding a torch or a block, slot 8 is still taken.
- **Not covered:** 505's hemmed-in spell also re-promised a hand-over to a far teammate after each give-up. That is the
  next slice, with its own fixture and gate.
