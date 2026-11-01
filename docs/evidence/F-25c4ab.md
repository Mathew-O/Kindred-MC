# F-25c4ab — Hurt and out of food, once up out of the ground it looks for animals rather than go back down

Date: 2026-10-31
Agent / branch: Claude / claude/loose-ends-1029
Build: on top of `033fd71`; recorded in [validation](../validation.md)
Status: in progress (native passed; live runs pending); F-25c4 stays open

## Scope and implementation

User-visible behavior: a player that is hurt (below ten health) with nothing to eat climbs toward the surface by day, as before. Once it is up out of the ground it hunts an animal in sight, fishes where it can, or walks out across the ground to look for animals, rather than going back to its work. Before, the climb ended where the ground opened over its head, two blocks short of the surface, and the goal's next step dug straight back down: the player went up and down there, and the climb began again.

Found in the live pair on `a4df9b4` (strategist off):
- Four of the six players spent 12 to 16% of their time climbing for food: Echo and Kindred on the first seed, Echo and Sol on the second.
- On the second seed Sol climbed for food from Y=-2 to 107, from 38 to 116 and from 43 to 77, and each time went straight back to its work below. At Y=76 and 77 it switched between the climb and "Dig down toward lava" about forty times in 45 seconds, and at Y=89 to 91 about fifteen times with "Dig down toward iron".
- On the first seed Echo, hurt and out of food from 22:11, switched between the climb and "Dig down toward iron" at Y=60 to 62, by the surface, and never ate; at 22:31 a zombie killed it with hunger 2.
- On the second seed Echo's way down toward a lava lake at Y=-24 was broken off at Y=13 for the climb.

Changed modules/files:
- `PortalRunCampaign` (the goal's step before its plan): hurt, out of food and not underground by day, it hunts an animal in sight, fishes, or explores for animals (not over the void). In the Overworld only: the Nether and the End have no sky, so no night either, and a hurt player there must not wander off looking for animals.
- `NativePortalCampaignGameTests`: one fixture.

## Acceptance evidence

| Criterion | Check or scenario | Result | Evidence |
| --- | --- | --- | --- |
| On open stone by day, health 8, hunger 10, nothing to eat, a cow a little over thirty blocks out to each side: it walks out, kills one and has its beef | `hurtAndOutOfFoodOnceUpItLooksForAnimalsRatherThanGoBackDown` | Fails on the old campaign, 3 of 3: "It found a cow and has its beef: now 8, -2, -14, Iron: Mine iron ore (0/18) - Branch mine for iron: advance"; in one run it had wandered up to a cow at the time limit. Passes after, 3 of 3 alone, twice: "hurt and out of food - Explore west for animals", then the cow approached, killed and its beef picked up | native |
| The climb out for food, the hunt in sight, fishing and the fights begun hurt unchanged | alone on this code: the climb with gravel overhead, food first as advised, both fishing fixtures, the fight broken off once badly hurt, the creeper faced behind the shield | 6 of 6 | native |
| In play | the live pair on `41f9de9`, both seeds, strategist off | In part: 18 of 30 spells hurt and out of food came up to the search for animals, and players hunted and ate from it on both seeds; on `a4df9b4` none did. But at the top of one of Echo's climbs it switched between the search and the climb, and its walk back the way it came took it down to Y=46; Echo spent 29% of the first seed so. Remaining: the search held once it is up, and a live run | live |

Environment: Linux x64 cloud container (4 cores), OpenJDK 21, Minecraft 1.21.1, Fabric Loader 0.19.5, Fabric API 0.116.17.
Fixtures: the plain and the four cows, kept from wandering, are staged, and any animal another fixture left on the ground is taken away first; so are the player's health and hunger. The kit is given as though made. With the cows 33 blocks out, the old code passed once: its branch mine had carried it under the plain toward a cow, and its climb for food came up in sight of it. At 45 blocks it passed once more, on a cow a failed run before it had left there, its last step never reached.

## Remaining limitations and handoff

- At night it does not climb or look: the goal goes on below as before, hurt.
- Where no animal is within its explore range, it walks out a leg at a time until the watchdog sends it elsewhere.
- The climb itself still switches between digging a staircase up and walking back the way it came, a step each way, some 34 to 138 times a run per player.
