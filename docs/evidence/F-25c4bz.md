# F-25c4bz — A frame cast on snowy ground: the snow in its cells is cleared by hand, and the site is kept

Date: 2026-11-07
Agent / branch: Claude / claude/e40-ladder-15-1107 (E-40's ladder)
Build: on top of `42d682c` (batches 13 and 14); recorded in [validation](../validation.md)
Status: complete (native). Its effect on the ten seeds is in E-40's next evaluation.

## Scope and implementation

User-visible behaviour: on ground under a layer of snow, a player casting its portal frame clears the snow from the
frame's cells with a bare hand, as a player does, and casts the frame there. Before, it gave the site up for the snow,
chose the same site again at once and gave it up again, and never cast its frame.

A cast needs open air in each frame cell and the cell over it. A cell that is not air is cleared if the player could
break it (`breakableNow`), and if not, the site is given up. A layer of snow drops nothing without a shovel, so no tool
the player carries harvests it, and `breakableNow` refused it. Now a cell is cleared, keeping nothing, when it could be
broken or when a bare hand brushes it away at once: a replaceable block with no fluid in it and a hardness of 0.2 or
less (snow lying in layers, a plant). The same check clears the site before the cast (`clearOrApproach`). The break is the
same recorded `native_break` that collects nothing, which a bare hand may already make.

Found: E-40's iteration 11 (`e40a11-744c636`), Sol on 707, a snowy seed. Its lava in hand and two corners set, it gave
its frame site up for a layer of snow at 168, 64, 43, hundreds of times in a second, until the night came; it never
cast a frame in the run. 707 was one of the three seeds without a lit portal.

Changed modules/files:

- `companion-server-adapter`: `PortalRunCampaign` (`clearableNow`; the cast's cell opening and `clearOrApproach` use it).
- gametest: `NativeSnowSiteGameTests` (new class, one fixture).

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| On snowy ground, the cast clears the snow by hand and casts its frame | `NativeSnowSiteGameTests.onSnowyGroundTheCastClearsTheSnowByHandAndCastsItsFrame`. Staged: a field of stone 21 square, walled round, open to the sky, daylight held, a layer of snow over its floor, a lava pool at its north end; the player by the pool with the cast route's kit and flint and steel, and no shovel | See [validation](../validation.md) for the runs before and after the fix. |
| The rest | `bash ./gradlew build`; the full native suite | See [validation](../validation.md) |

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions. Staged, as the fixture says.

## Remaining limitations and handoff

- Only blocks a bare hand breaks at once are cleared without a tool that harvests them; a whole snow block, which is
  not replaceable, is broken by a shovel or not at all, as before.
