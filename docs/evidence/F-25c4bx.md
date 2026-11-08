# F-25c4bx — Dug in for the night with nothing to do before daylight, it stays in and waits

Date: 2026-11-07
Agent / branch: Claude / claude/e40-ladder-13-1106 (E-40's ladder)
Build: on top of `eae8161` (batch 13); recorded in [validation](../validation.md)
Status: complete (native). Its effect on the ten seeds is in E-40's next evaluation.

## Scope and implementation

User-visible behaviour: a player that has dug in for the night a few blocks below the surface stays there until morning,
its way in sealed, when it has nothing it can do from there before daylight. Before, it climbed straight back out "to
find another way", was out under the sky at night, and dug in again, up and down a step a second for the whole night.

The night's dig-in goes three blocks down. Once below, the route's steps are tried in turn; at night the ones that are
daylight work (looking about for animals; going up for logs) are left, and others may have nothing to do from there (a
frame cast from lava out of reach, say). With no step taken, the last fallback for a player below ground was to climb a
step and look again. Near the surface that climb took it out of its shelter, and the night's dig-in came first again.

Now, at night, below ground in its night shelter (within six blocks of the ground's top over it, or by where it dug in
tonight, as under a hillside the ground's top is the hill's), a player with no step to take seals the openings of where it
stands and waits for daylight, as it already did when the step it waited on was logs or water.
Deeper down the climb goes on as before. And the search for animals, which the route's own steps leave for daylight, is
left for daylight at the End goal's gates too: for the Nether trip's food the End leg sent it up out of its shelter at
night to look for animals.

Found:

- E-40's iteration 10 (`e40a10-eed2dc5`): the commonest stuck cause, 15 of 96 spells ("no way on from here - Climb to
  find another way"), Kindred's on 202 (seven, at the cast, at Y=61 to 68) and on 8675309 (six, at Y=60 to 68).
- The first End-goal probe (`e40c1-eed2dc5`): Sol, its portal lit, on 101 through a whole night in the End goal's
  preparation ("Gather 6 food for the Nether trip"), 408 climbs and 196 dig-ins; and on 20261030 the same, over 750 climbs
  and 330 dig-ins.

Changed modules/files:

- `companion-server-adapter`: `PortalRunCampaign`: `inNightShelter`, `nightShelterAt` (where it dug in tonight, cleared
  by day), `NIGHT_SHELTER_DEPTH`; the fallback waits in the night's shelter (`daylightBelow`) before the climb; `realize`'s
  look for animals is daylight work.
- gametest: `NativeNightShelterGameTests` (new class, one fixture).

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| Night on open ground by its lit portal, the End goal's next step food for the Nether trip and none carried: dug in, it stays below until morning, its way in sealed, then goes up | `NativeNightShelterGameTests.dugInForTheNightWithNothingToDoBeforeDaylightItStaysInAndWaits`. Staged: a block of stone with a lit portal on it, the night held for 1,500 ticks, the End goal's kit but for food, open to the sky | On `eae8161`: fails 3 of 3, out of its shelter and dug in again 41 or 42 times in the night, climbing out to look for animals and then "to find another way". With the fix: passes 3 of 3, dug in four down, a torch placed, the shelter sealed and daylight waited for; in the morning out of it and on toward its portal. |
| The rest | `bash ./gradlew build`; the full native suite | See [validation](../validation.md) |

The fixture's first runs were made without sky access: under the barriers the test server puts over a field without it,
the ground's top was the barriers', so the shelter seemed deep, and a first form of the fix that judged the shelter by
depth alone did not see it (the one run made with it fell back to the climb, out of its shelter once). In a world the ground is open to the sky, as the
fixture now is; and the fix also knows where it dug in tonight, for a shelter under a hillside, whose ground's top is the
hill's. The runs above are all on the fixture as committed.

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions. Staged, as the fixture says.

## Remaining limitations and handoff

- Underground and stuck deeper than a night shelter at night, it still climbs to find another way.
- The End goal's food for the Nether trip is still short where no animals are near: Sol on 20261030 went hungry looking.
