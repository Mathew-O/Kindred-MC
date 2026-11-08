# F-25c4cf — Out of a flowing channel at a cave's edge, it stays out and goes on

Date: 2026-11-07
Agent / branch: Claude / claude/e40-ladder-16-1107 (E-40's ladder)
Build: on top of `8c2814a` (batch 16 with batch 15's F-25c4ce); recorded in [validation](../validation.md)
Status: complete (native). Its effect on the ten seeds is in E-40's next evaluation.

## Scope and implementation

User-visible behaviour: a player that comes up in a channel of moving water at a cave's edge climbs out onto dry ground
clear of the water, and gets on with its work from there (here digging and branch mining for lava). It is not carried
back in, and it is not sent back in by its own walks. Before, it was sent out of the water hundreds of times in a few
minutes, and its dig was stopped "In deep water; up for air first" each time.

Three things kept it going round:
- The walk out of the water went to the nearest dry ground, which was the stand at the channel's edge. A body standing
  there can still overlap the flowing water beside it, and the current carried it back in.
- Standing on the channel's bottom in water up to its head, with its eyes over the current's low surface, it was not
  counted as in deep water. So it was given work there, then bobbed up and was sent ashore again.
- Its walks on into the cave toward lava led back through the channel. Each walk was bound for another cave floor, so
  the set-aside for walks that keep ending in the water, which counts by where a walk is bound, never added up.

Now:
- The walk out goes first to dry ground with no water in the eight cells round it at its feet or head. When there is no
  such ground in reach, it goes to the nearest dry ground as before.
- Standing in water two deep counts as being in the water for getting out of it.
- Two walks on into the caves that end in deep water from about one place within a minute leave the caves round there
  alone for two minutes. The goal digs for what it wants instead.

Found: E-40's iteration 11 (`e40a11-744c636`), Kindred on 606 at 365, 52, -79. It was given "Get out of the water: to dry
ground" 3,789 times, and its dig toward lava was stopped "In deep water; up for air first" 1,782 times. Iteration 12
(`e40a12-66a7c0a`) on 8675309 has the same in-and-out between "Explore the cave toward lava" and "to dry ground", Echo at
a flooded passage with a drowned about, where it later died to one.

Changed modules/files:

- `companion-server-adapter`: `PortalRunCampaign.surface` (dry ground clear of the water first; wading two deep counts),
  `waterRound`, `wadingTwoDeep`, `wetDip` (walks on into the caves that end in deep water, counted by where they ended),
  `cave` (the caves round such a place left alone for two minutes).
- gametest: `NativeWaterEdgeGameTests` (new class, one fixture) and its structure `water-edge-606.snbt`: the block of
  the run's kept world round the place, 25 by 23 by 27, read offline from the evaluation's own world for diagnosis.

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| In a channel of flowing water two deep at a cave's edge, wanting lava for its cast: it gets out and goes on | `NativeWaterEdgeGameTests.atAFloodedCavesEdgeItGetsOutAndGoesOn`. Staged: the block of 606's kept world round 365, 52, -79, the water on its faces made stone so that it does not spill; daylight held; the player in the channel where Kindred was, with the cast route's kit. Passes if in 2,400 ticks it is sent out of the water at most 6 times and stays alive | On `8c2814a`: fails 3 of 3, sent out of the water a seventh time after 235 to 286 ticks (39 to 192 times by the time limit), its dig stopped "In deep water" 18 to 172 times. With the fix: passes 3 of 3, sent out of the water 3 times, no dig stopped, then digging down and branch mining for lava. |
| Each part needed | The same fixture with parts of the fix | With only the dry ground clear of the water, in one run: fails, sent out 23 times, from the explore walks into the channel and its bottom. Without the caves left alone (the other two parts): passes 3 of 3 at its limit, sent out 6 times in each run, against 3 with it. |
| The rest | `bash ./gradlew build`; the goal's fixtures with water | See [validation](../validation.md) |

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions. Staged, as the fixture says.

## Remaining limitations and handoff

- Where all dry ground in reach touches the water, the walk out still goes to the nearest. The current can carry the
  player back in from there.
- Dips are not counted for walks on into the caves that end in shallow water, only those that end in deep water or two
  deep.
