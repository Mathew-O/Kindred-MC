# <Mod name>

Mod id: `examplemod`. Versions tested: none yet. Status: partial.

## What Kindred does with it

- 

## What it does not do, and why

- 

## Fields in `profile.json`

| Field | What it holds | Derived by the game? |
| --- | --- | --- |
| `mod`, `versions`, `status` | the mod id as declared, a version range, `supported` or `partial` | no |
| `capabilities.edible` | items or tags edible that lack a food component | usually derived; list only exceptions |
| `capabilities.sittable` | blocks a player sits on by right-click | no |
| `capabilities.climbable` | climbable blocks not in `#minecraft:climbable` | usually derived |
| `capabilities.hazard` | blocks or entities that hurt and are not flagged by the game | no |
| `capabilities.logs`, `planks`, `ores` | only where the mod misses the conventional tags | usually derived |
| `stations` | a block, the recipe type it takes, and one of the generic interactions: `open_and_insert`, `place_and_use_tool`, `use_with_item`, `wait_and_take` | no |
| `needs` | data needs for a system core supports (a drink for thirst); empty until that system exists | no |
| `mobs` | an entity, a behaviour (`melee`, `ranged`, `explodes`, `climbs`, `flies`, `passive`, `neutral`) and a flee distance | partly |
| `policy` | `teleport` and `freeItems`: `forbid` (default) or `announced` | no |
| `names` | plain names for the voice and the panel | from the language file where present |
| `notes` | one or two sentences | no |

## Fixtures

`fixtures/` holds GameTests that run on a test server with this mod installed at a version in range. Each passes
three of three before the folder is marked `supported`.
