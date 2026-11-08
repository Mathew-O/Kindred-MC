# F-25c4cn — A frame half cast is not given up for a block out of sight from its stand

Date: 2026-11-08
Agent / branch: Claude / claude/e40-ladder-20-1108 (E-40's ladder, batch 20)
Build: on top of `06e32e6` (batch 19); recorded in [validation](../validation.md)
Status: complete (native). Its effect on the ten seeds is in E-40's next evaluation.

## Scope and implementation

User-visible behaviour: a player casting its portal frame sometimes finds the next cell needs a block cleared first. If it
cannot see that block from its stand, it now goes along the row in front of the frame to a spot where it can, clears the
block there and casts on. Before, it gave the frame up from its stand, with the obsidian it had cast left behind. The
cast's clearing now goes as the site's own clearing does: the stand, then the open cells of the row in front, and only
then the site given up (F-25c4aj).

The case found: casting a side cell needs the cell over it open, and over the topmost side cell is the frame's top corner.
A natural block there counts as the corner, so the site's clearing leaves it. Casting the side cell under it then needs it
dug.

Found: E-40's iteration 14 (`e40a14-80b7150`), Echo on 31102026. It had cast six of its frame's ten obsidian when it gave
the frame up: "gives up the frame site at 179, -54, 263: cannot open 179, -50, 263 (deepslate)". Its next side cell's
cast needed that top corner open, and the corner's deepslate was out of sight from the stand. Then:
- It chose frame sites overlapping its own obsidian, and gave those up too: it cannot dig obsidian without a diamond
  pickaxe.
- It walked "Cast: step closer" 2,144 times in four stretches, until the watchdog moved it on.
- No portal was lit on the seed. It is one of the three seeds without a portal in iteration 14; rung b asks for one on 8
  of 10.

Changed modules/files:

- `companion-server-adapter`: `PortalRunCampaign` casting. A cell to open that it cannot clear from where it stands goes
  through `clearOrApproach` (the stand, then the row in front, then the site given up), not straight from the stand to
  giving the site up.
- gametest: `NativeHalfCastFrameGameTests` (new class, one fixture).

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| Six of its frame's ten cast, with stone in a top corner out of sight from its stand, it opens the corner from the row in front and casts the side cell under it within 1,200 ticks | `NativeHalfCastFrameGameTests.aFrameHalfCastIsNotGivenUpForACornerOutOfSightFromItsStand`. Staged as described below | On `06e32e6`: fails 3 of 3. Each time it "gives up the frame site ...: cannot open" the corner's stone, as Echo did. With the fix: passes 3 of 3. It goes "Cast: over to" the corner, clears it, and the side cell is cast after 40 to 41 ticks. |
| The rest | the classes that cast, light and build frames; `bash ./gradlew build` | See [validation](../validation.md) |

The fixture's staging:
- A block of stone. The frame runs north and south with its opening facing east.
- Its bottom corners are cobblestone. Its two bottom cells and four lower side cells are obsidian, as if the player had
  cast them. Its top corners are left the stone they were.
- The row in front of the frame is open five high and lit. The stand is at the end of a tunnel two high, with the stone
  left over it. The frame is taken as the player's own.
- The player stands on its stand with the cast route's kit, a bucket each of lava and water, and the iron and flint for
  its flint and steel. Daylight is held.

A first form of the fixture gave no iron or flint. It did not reproduce: the goal went to mine an iron for its flint and
steel before the cast, and never reached the corner. The runs above are on the fixture as committed.

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions. Staged, as the fixture says.

## Remaining limitations and handoff

- The other "Cast: step closer" loops on 31102026 came after the frame was given up. It is not yet shown that the
  frame's other steps, a placement out of reach of a stand that "step closer" counts in reach, cannot loop the same way.
- Frame sites overlapping its own obsidian are still chosen and given up; with the frame kept, this case no longer
  reaches them.
