# F-25c4bu — Its pickaxe gone under a lake, out from under it by hand

Date: 2026-11-06
Agent / branch: Claude / claude/e40-ladder-12-1105 (E-40's ladder)
Build: on top of `ec27caa` (batch 11, merged); recorded in [validation](../validation.md)
Status: complete (native). Its effect on the ten seeds is in E-40's next evaluation.

## Scope and implementation

User-visible behaviour: a player with no pickaxe, climbing for the surface from under a lake, digs its way along under
the lake by hand until the ground over its head is dry, then climbs, as it already climbed by hand where there was no
water over it.

Before, three rules took turns. The way along under the water (F-25c4ah) was dug only with a tool, so with no pickaxe it
could only walk the cells already dug, stepping down into the corridor's lower part; the climb then stepped up again.
And with no pickaxe, the way up tried the way back before the climb, so the few cells it did get dug out from under the
water were walked back under it at the next step.

Now, with no pickaxe:
- the way along under the water is dug by hand, as the climb is;
- it keeps to its own level, never a step down, while it is out from under the water;
- out from under the water comes first, before the way back, as it already did with a pickaxe.

Found: E-40's iteration 9 (`e40a9-dd9b037`), seed 8675309, Kindred, its pickaxe just broken ("Stranded without a
pickaxe"), climbing for logs at -26, 24, 55, one block of stone between its corridor and a lake. It stepped up and back
down for 10 stuck spells, some 2,400 plans in all.

Changed modules/files:

- `companion-server-adapter`: `PortalRunCampaign`:
  - `tunnel`: `tunnelByHand`, set only by the climb's way out from under the water: a cell no tool can dig is dug by
    hand when no pickaxe is held (`handClearable`).
  - the climb's under-water branch: `tunnelFloor` held at the player's level during that tunnel, so no step down.
  - `wayUp`: with no pickaxe, the under-water rule (F-25c4ah) before the way back; with one, its order unchanged.
- gametest: `NativeUnderLakeKeptGameTests` (new class, one fixture) and its structure `under-lake-8675309.snbt`,
  staged from the run's own kept ground (`run.py --keep-world`).

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| No pickaxe, under 8675309's lake where Kindred stood: three or more above where it stood, on dry ground, no water over its head | `NativeUnderLakeKeptGameTests.itsPickaxeGoneUnderALakeItDigsOutByHandAndClimbs`. Staged: the block of the run's kept world round it (read offline, diagnosis only), 29 by 17 by 21, the corridor Kindred had dug in it, the lake's water on the region's sides and floor made stone; daylight held; cobblestone and bread, no pickaxe, no wood | On `ec27caa`: fails 3 of 3, each time stepping up and back down until the test ended (up to 396 steps up), never out from under the lake. With the fix: passes 3 of 3, out about 7,040 ticks on with 3 steps up. |
| The other climbs under water: F-25c4ah's corridor under a lake, 303's aquifer ledge, 101's lake bed | Those fixtures, run alone with the fix | All pass with the fix, run alone once: F-25c4ah's fixture and the seven of `NativeTripDownGameTests` |

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions. Staged, as the fixture says.

## Remaining limitations and handoff

- By hand a block of stone takes seven and a half seconds: out from under a wide lake is slow, but it gets out.
- With a pickaxe the way along under the water was already dug; there only the step down is new.
