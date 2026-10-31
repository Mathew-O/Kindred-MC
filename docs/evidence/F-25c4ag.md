# F-25c4ag — Hurt and out of food, once up its search for animals holds where the sky opens and closes over its head

Date: 2026-10-31
Agent / branch: Claude / claude/loose-ends-1029
Build: on top of `0c48f53`; recorded in [validation](../validation.md)
Status: in progress (native passed; live runs pending); F-25c4 stays open

## Scope and implementation

User-visible behavior: a player hurt with nothing to eat climbs toward the surface by day and, once up out of the ground, looks for animals (F-25c4ab). Once it has had open sky over its head, ground no more than three blocks below that spot and within 32 blocks of it still counts as up, so its search goes on under an overhang or through a hill's side rather than turning back to the climb. Before, every cell without open sky over it sent it back to the climb: on a hillside, where the sky opens and closes over its head from one cell to the next, the search and the climb took turns.

Found in the live pair on `41f9de9` (seed 8675309, strategist off): Echo climbed for food from Y=16 to Y=61, then switched between "Explore west for animals" (and "Cut through west for animals") and "Climb toward the surface for food" several times a second from 01:05:18 to 01:06:04, until its watchdog moved it on, into the night.

Changed modules/files:
- `PortalRunCampaign` (the goal's step hurt and out of food): `hungryUpAt`, the last spot of the spell with open sky over its head.
- `NativePortalCampaignGameTests`: one fixture.

## Acceptance evidence

| Criterion | Check or scenario | Result | Evidence |
| --- | --- | --- | --- |
| Health 8, hunger 10, nothing to eat, by day on stone ground under a lit roof of stone four thick and forty-one blocks across, walled round, the sky showing only through a gap over its head: for twenty seconds it looks for animals under the roof and never turns back to the climb | `hurtAndOutOfFoodUpUnderAGapInTheRockItGoesOnLookingForAnimals` | Before, the change set aside, it fails 3 of 3: "Back to the climb: Iron: hurt and out of food - Climb toward the surface for food: back the way it came". After, 3 of 3 alone | native |
| The way up for food, the hunt and fishing unchanged | alone on this code: the search once up (F-25c4ab), the climb with gravel overhead, food first as advised, fishing at a pond | 4 of 4 | native |
| In play | live runs on this code | Pending | live |

Environment: Linux x64 cloud container (4 cores), OpenJDK 21, Minecraft 1.21.1, Fabric Loader 0.19.5, Fabric API 0.116.17.
Fixtures: the ground, its roof, walls and lights, and the four cows, kept from wandering, are staged, and any animal another fixture left on the ground is taken away first; so are the player's health and hunger. The kit is given as though made.

## Remaining limitations and handoff

- More than three blocks down, or 32 blocks across, from where it last had open sky it climbs again, as before.
- Where few animals are about, a hungry player still goes back down with little food.
