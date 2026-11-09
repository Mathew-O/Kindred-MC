# F-25c4cp — Stranded on a ledge long enough, whole and fed: a deeper fall onto clear ground

Date: 2026-11-08
Agent / branch: Claude / claude/e40-ladder-22-1108 (E-40's ladder, batch 22)
Build: on top of `66e7f76` (batch 21 with main); recorded in [validation](../validation.md)
Status: complete (native). Its effect on the ten seeds is in E-40's next evaluation.

## Scope and implementation

User-visible behaviour: a player stranded on a ledge, with every way off it a fall deeper than it takes lightly, now
waits through three of the watchdog's spells. Then, if it is at full health and fed and has no water to break the fall,
it takes the shortest fall onto clear ground that leaves it 6 health or more: up to 17 blocks, where before the limit
was 12 and 10 health left. It never does so with a mob within 12 blocks of the landing, shield or no shield. Before, it
waited there for good.

Found: E-40's iteration 15 (`e40a15-dbf4e5c`), Echo on 505. It was the run's most frequent stuck cause, 20 of its 114
stuck spells (filed as "tunnel past liquid toward iron").
- Echo dug its stair down for iron from 12, 57, -44, into the wall between an aquifer and a cavern. It came out on
  ledges at 16 to 18, 45 to 47, -48 to -44, with the cavern's floor fifteen below.
- From there, a step down went onto the ledge below with "no way on from here", and the climb came back up the one
  step. Every step up from the ledge's top was refused, for the aquifer's water by the cells over it. Every walk to the
  cavern's floor was "No walkable route". Every move elsewhere the watchdog chose, 24 along at the same level or toward
  home, was refused the same way.
- There was no walk back the way it came either. It had come in on the edges of blocks, and in the fixture the
  navigator's search from where it stood reaches three cells.
- It went "waiting for a feasible step" through 20 stuck spells, tried elsewhere 29 times, until a zombie's blows put
  it down into the cavern at night, where it died.

Changed modules/files:

- `companion-server-adapter`: `PortalRunCampaign.jumpDown`.
  - Stranded through three spells (`stuckLevel >= 3`) at full health, with food 18 or more and no water bucket ready,
    the deepest fall it takes is 17 instead of 12.
  - A fall deeper than 12 must leave it 6 health or more; a fall of 12 or less still must leave 10.
  - A fall deeper than 12 with nothing to break it is refused with any mob but an enderman within 12 across and 6 up or
    down of the landing, shield or no shield. F-25c4ca's stranded allowance (mobs it can face) does not apply to it.
  - The task for a fall broken with water keeps its label; the deep fall is labelled "Jump down from the ledge", as the
    short ones are, so the place it lands is set aside after it as theirs is.
- gametest: `NativeCavernLedgeGameTests` (new class, one fixture). Its ground, `ledge-505.snbt`, is cut from the run's
  kept world.

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| On Echo's ledge, its watchdog's spell 400 ticks, it stands on ground eight or more from where it stood within 3,000 ticks, alive | `NativeCavernLedgeGameTests.onACavernsLedgeWithAnAquiferBehindItItGetsOffTheLedge`. Staged as described below | On `6edf706`: fails 3 of 3, "Still on the ledge after 3,000 ticks". In each run it went 2 or 3 times "no way on from here" and back up, then waited at Echo's spot "for a feasible step", the watchdog's moves elsewhere refused. With the fix: passes 3 of 3. After three spells it goes "no way on from here - Jump down from the ledge" and lands 16 below, off the ledge after 1,450 to 1,468 ticks, alive. |
| The rest | the classes that climb, tunnel, dig out and jump down; `bash ./gradlew build` | See [validation](../validation.md) |

The fixture's staging:
- The block of the run's kept world round the ledge, 29 by 34 by 27 (4, 27, -56 to 32, 60, -30), read offline for
  diagnosis only. The liquid on the block's faces is made stone. The cells Echo dug are open in it, as the kept world has
  them.
- Daylight is held, and the cavern is lit with light blocks so that nothing spawns in it.
- The player stands where Echo stood, 18, 47, -48. It has the goal's iron stage: a stone pickaxe, sticks, planks and a
  crafting table, with cobblestone, torches and bread. Its health and food are full.
- Its watchdog gives a spot up after 400 ticks, as the spire fixture's does (F-25c4ca), so that three spells pass within
  the test.

The fixture world's Y is not the run's. The ledge stands at Y=-39 in the fixture, below the iron depth the goal digs to,
so the steps down are "Branch mine for iron" where Echo's were "Dig down toward iron" and "Tunnel past liquid toward
iron". The loop is the same: a step down onto the ledge below, "no way on from here", the climb back, then waiting at the
same spot.

Two other forms of the fix were tried first and are not kept:
- Back the way it came (`retrace`) when the climb is refused, its way in set aside behind it. In a form of the fixture
  with Echo's way in as its trail, the walk back was refused: its stair down had drops in it no walk climbs back.
- The same, to the furthest place on the trail that the navigator could route to. None could be reached: the
  navigator's search from the ledge expands three cells.

The form of the fixture with the trail is not kept either; the runs above are on the fixture as committed.

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions. Staged, as the fixture says.

## Remaining limitations and handoff

- It lands hurt, 7 health after the fixture's 16-block fall, in a cavern that may be dark. In the run, nothing like a
  lit cavern is promised: the mob check is at the moment of the jump only.
- A player would more often get down a cliff like this by digging a stair inside the wall where the water allows, or by
  placing blocks. Neither is done here.
- The two-cell to-and-fro between the ledge's top and the ledge below it still happens a few times first: a climb that
  lands on a block's edge, then a step that drops it back.
