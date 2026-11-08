# F-25c4ca — Stranded on a spire over a mob, it gets off with its fall broken with water

Date: 2026-11-07
Agent / branch: Claude / claude/e40-ladder-15-1107 (E-40's ladder)
Build: on top of `42d682c` (batches 13 and 14); recorded in [validation](../validation.md)
Status: complete (native). Its effect on the ten seeds is in E-40's next evaluation.

## Scope and implementation

User-visible behaviour: a player stranded where every way off is a long drop, its water bucket at hand, takes the jump
with its fall broken with water once it has been stranded there through two spells of no progress, fit (14 health or
more) and armed with a sword or an axe, though mobs are about the landing. Before, any hostile mob within twelve blocks
of the landing (and no shield) refused the jump, and it waited where it stood for good.

The jump down is the last way on once every walk, step and climb is refused. It is not taken among mobs without a shield,
as a player listens before dropping into a cave. In a large dark cave a mob is nearly always within twelve blocks of a
landing, and the refusal was for ever. Now, stranded for two of the watchdog's spells in a row, fit and armed, the player
drops in all the same; a creeper within eight blocks of the landing still refuses it.

Found: E-40's iteration 11 (`e40a11-744c636`), Sol on 505, the largest stuck block of the evaluation. Its stair down for
lava came out in a cave from Y=-2 down to Y=-48, read from the run's own kept world for diagnosis: its last steps had
dug the cave's thin wall out round it, and left it on a single block over a spire, every way off it a drop of 16 to 40
blocks. It had filled a water bucket before going down. It waited there "for a feasible step" from tick 70,800 to the end
of the run, 61 game minutes, 21 of its 25 stuck spells.

Changed modules/files:

- `companion-server-adapter`: `PortalRunCampaign.jumpDown` (stranded through two spells, fit and armed, a landing among
  mobs is taken, never with a creeper within eight blocks of it).
- gametest: `NativeSpireGameTests` (new class, one fixture).

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| Stranded on a spire in a great cave, a mob below, its water bucket at hand: it gets off once stranded | `NativeSpireGameTests.strandedOnASpireOverAMobItGetsOffOnceStranded`. Staged: a cave 27 high and 21 square in a block of stone, lit from its ceiling, a lava pool in its floor and a zombie on the floor below, its AI off; the player on top of a spire one block wide, twenty blocks over the floor, with the cast route's kit, its sword and water bucket at hand, no shield; its watchdog giving a spot up after 400 ticks | On `42d682c` (with F-25c4by and F-25c4bz, which do not reach the jump): fails 3 of 3, "waiting for a feasible step" on the spire through nine spells until the test's time ran out. With the fix: passes 3 of 3, off the spire after 840 to 843 ticks, at its second spell, its fall broken with water. |
| The rest | `bash ./gradlew build`; the full native suite | See [validation](../validation.md) |

The same scene with no mob below did not reproduce: lit and empty, the jump was taken at once, on the code before the fix
too (3 of 3 off the spire after 117 to 120 ticks). That form of the fixture is not kept. A first form with the zombie
spawned it far off the field (a conversion of its position), and passed for that reason; the runs above are on the
fixture as committed, the zombie four blocks from the spire's foot.

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions. Staged, as the fixture says.

## Remaining limitations and handoff

- Its stair down still digs a cave's thin wall out round it; this gets it off what is left, it does not keep it from
  getting there.
- Without a water bucket it still waits where every drop costs more than it can afford.
