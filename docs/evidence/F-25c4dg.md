# F-25c4dg — Sealed under the sea with no pickaxe, it opens the roof by hand and swims up

Date: 2026-11-14
Agent / branch: LOOPS, claude/sealed-under-sea-1114
Status: complete (2026-11-14)

## The stall: E-40 iteration 22 (`e40a22-d2389c9`, #310), seed 101, Kindred
- **The cost:** 99 of the batch's 240 stuck minutes, and 22 of its 24 "no walkable route" spells, from about tick 45,440 to the run's end.
- **Source:** RUNNER 2's `claude/corpus-data-e40a22-1114` @ 7eba62e:
  - `extract/101-kindred-40000-52000.txt` (Kindred's console lines);
  - spell 1's replay, `Kindred-47820`, and what it held.
- **What it did (wall clock):**
  - From 16:45:14 to 16:46:09: "Climb toward the surface for logs: out from under the water: clear … by hand", westward along z −79 at y 44 and 45, then "back the way it came".
  - At 16:46:13 and 16:46:14: two `native_pillar` COMPLETED, "pillar up into the hole over it".
  - From then on: "Head back up toward home" was `native_goto BLOCKED: No walkable route toward -271, 71, -112`, "Wood: waiting for a feasible step", and the runner BLOCKED, 48 times to the end. "Climb toward the surface for logs" never appears again, as a step or as a refusal.
- **Where it stood** (spell 1's replay): feet at −244, 46, −79, in a shaft one block across and two high.
  - Its two dirt blocks were under it at 44 and 45.
  - Stone was round its feet, and gravel round the cell over its head.
  - At 48, over its head, was a sunken ship's dark oak plank, with water over that to the top of the replay's box at 54.
- **What it held:** no pickaxe of any kind; a stone sword, a shield, an iron chestplate, 1 stick, 1 oak plank, a crafting table, a furnace, 4 coal, 3 cooked porkchop, 14 dirt, 40 gravel, and stone blocks. Health 20, food 20, air full.

## Why nothing was offered
- **The logs step:** in a hole, `GATHER_LOGS` asks `wayUp` for a way out first, then falls back to "Head back up toward home" when it gives none.
- **With no pickaxe, `wayUpBy` has two branches:**
  - shortly after being out from under the water: its own night seal, the climb out from under the water by hand, the way back;
  - otherwise: a pillar out, the way back, a stair by hand.
- **None of them goes up into water,** by design; earlier drownings came of it (F-25c4bg, F-25c4bu). Here every way up led into water, so each gave nothing, and the walk home had no route.
- **The way out a player takes:** open the plank by hand and swim up. The water code already swims a player up and out once it is in the water (`surface`: "Get out of the water: up for air", "to dry ground").

## The change (`PortalRunCampaign`)
- **`swimOut`**, last in both no-pickaxe branches of `wayUpBy`. It opens the block over its head by hand (or with a tool that breaks it), labelled "open the roof at … and swim up". It acts only when all of these hold:
  - it is on its feet, dry, with its head's cell open;
  - the block over its head is one it may break: not set aside, not sand or gravel (which would come down on it), not beside lava;
  - water is straight over that block and reaches air within `SWIM_UP`, 20 blocks over its feet;
  - its breath is full, and its health is half or more.
- **`surface`** looks for the air over it as far as `SWIM_UP`, from 16. Out of this shaft, the sea's top is 17 over its feet.
- **With a pickaxe, nothing changes.** F-35g's rule stands: it never opens its ceiling into the sea, and the goal goes on elsewhere (`NativeSeabedWaterGameTests`).
- **Nothing is given and nothing is moved;** the plank it breaks drops as usual.

## Fixture (staged): `NativeSealedUnderSeaGameTests.sealedUnderTheSeaWithNoPickaxeItOpensTheRoofAndSwimsUp`
- **Setup:**
  - the shaft as the replay shows it: its two cells open, its two dirt blocks under it, stone round its feet and a layer of gravel over that, the dark oak plank over its head;
  - the sea over the plank, 16 deep to its top. **The replay's box shows only the first eight; the full depth is assumed from sea level.**
  - walls round it 25 square, of stone to the sea's top, so that dry ground is within 13;
  - Kindred's kit at the spell, slot for slot (no pickaxe); daylight held; the portal goal.
- **Passes once** it stands on dry ground under the open sky, alive, within 2,400 ticks, with no action left to reconcile.
- **Before** (main fbc5f0f's `PortalRunCampaign`): 3 of 3 fail. Each stays in its shaft on "Wood: waiting for a feasible step", "has started nothing for 1201 ticks", for 2,400 ticks: the run's stall.
- **The first version passed 2 of 3.** It put `swimOut` only in the second branch.
  - In the failing run, the roof's first offer came before its decision loop was taking tasks ("Player decision: Stopped or unavailable" at establishment).
  - By the next pass, the climb had noted water over its head, so `wayUpBy` took the under-water branch, which had no `swimOut`. It stood there for good.
  - Kindred was in that branch in the run, just out from under the water. Temporary log lines (not committed) showed it.
- **After** (`swimOut` in both branches): 3 of 3 pass. It opens the roof, gets "out of the water: to dry ground", and stands on the wall's top in 285 to 298 ticks. Its health stays 20, and its least air is 196 to 197 of 300.
- **The other fixtures it could touch:** 30 classes in one run (water, climbs, holes, shelters, falls, rescue and replays): all 187 required tests pass. The classes:
  - Atlas, Boat, CaveLedge, CavernLedge, CoverClimb, Fall, FallingColumn, GravelTunnel, LakeBedClimb, LavaProbe, NightShelter, ObsidianPickup, PillarInterrupt, PortalCampaign;
  - Rescue, RescueRecipes, RescueRounds, SealedUnderSea, SeabedWater, ShelterSeal, Stair, Survival, SwimUp, TripDown;
  - WaterBox, WaterCut, WaterEdge, WaterExit, WaterFight and `ReplayGameTests`.

## The run's own scenes (RUNNER 2, #311)
- **On main:** `e40a22-d2389c9-101-kindred-47820` (spell 1) and `-51620` (spell 2, the first "no walkable route") each failed 6 of 6.
- **Copied into this tree for these runs only, each alone, 3 times on the fix:**
  - `-47820`: 3 of 3 pass;
  - `-51620`: 3 of 3 pass.
- **Every run took the new way:** "open the roof at … and swim up", then "Get out of the water", and its goal gained something 77 to 146 ticks in; one run of `-51620` took 963.
- The scenes join the corpus with #311, not with this change.

## Corpus gate (F-27a, 422 scenes, on 3c67e62; main fbc5f0f, the merge-base)
- **Full runs:** three, failing 124, 116 and 124 required tests. The 16cb7a2 baseline's six runs failed 119 to 129.
- **Against the 16cb7a2 baseline (6 runs):**
  - 260 of 422 scenes pass all three runs (61.6%), and 42 are newly passing.
  - Replay players acting after their own batch: 0, 0, 0.
  - `swimOut` fired in none of the three runs; the corpus has no such shaft yet.
- **Flagged:** 2 scenes. Each was confirmed alone, 3 times on the branch and 3 times on main fbc5f0f, as `gate.sh` does.

| Scene | Branch failed (alone) | Main failed (alone) |
| --- | --- | --- |
| e40a15-dbf4e5c-707-sol-10569 | 2 of 3 | 1 of 3 |
| e40a16-922f4fd-101-echo-18586 | 0 of 3 | 1 of 3 |

- **The report:** echo-18586 cleared; sol-10569 confirmed.
- **echo-18586** is a zombie death. In none of the three full runs does its player reach the water code: no "Get out of the water" lines, no "up for air".

### sol-10569: the lead's three-part rule, as on F-27a2
The lead cleared this scene under this rule on F-27a2 (#304). The three parts, from this gate's own runs:
1. **Main fails it alone at least once:** main fbc5f0f failed it 1 of 3 here. On F-27a2, main 17f2053's own tree failed it 6 of 6 alone.
2. **The branch is not worse at a one-sided Fisher p < 0.10:** passed 1 of 3 against main's 2 of 3, p = 0.5.
3. **No changed line acts in it:** in all six of the branch's runs of it (2 passing, 4 failing):
   - the player's line "made in minecraft:overworld" appears once;
   - `swimOut` never fires;
   - the water code, the only place `SWIM_UP`'s change lives, never runs (no "Get out of the water", no "up for air").
- **Its baseline "pass" predates F-22c72 and F-22c73;** it is already flagged for the next re-baseline (F-27a2's evidence).
- **Where it is kept:** the summaries, the XML, the report and the confirms are on `claude/spacer-runs-1112` under `gate25c4dg/`.

## Remaining
- None.
