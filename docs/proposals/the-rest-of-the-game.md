# The rest of the game: what a pass over the wiki added

Status: proposal, 2026-11-10. IDs E-64 to E-69 are reserved in the [tracker](../feature-spec.md); several lines were
also added to [the survival world](survival-world.md) and [the match modes](match-modes.md). Checked against the
Minecraft Wiki's Java Edition 1.21 page (Tricky Trials, 13 June 2024; we pin 1.21.1) and a walk through the game's
systems by category. Everything here keeps the contract: earned, nothing hidden, every block and item through the
ledger; boss encounters only when qualified and allowed.

## What was missing

The survival-world pass covered the big systems. A category walk (blocks, items, mobs, structures, mechanics,
dimensions, advancements) found these left out: archaeology and the sniffer; amethyst and the spyglass; copper's
oxidation, lightning rods and 1.21's copper bulbs, doors and grates; the 1.21 **crafter**; item sorters, bubble
elevators, piston doors and flying machines; dripstone lava farms; potions and food *used in play* (fire resistance,
water breathing, golden apples, milk, honey, suspicious stew) and the 1.21 potions and ominous events; powder snow
and freezing; villagers beyond trading (breeding, curing, golems, the wandering trader and llamas); signs and hanging
signs, paintings, mob heads and trophies, the ominous banner, the ender chest, lodestone compasses, chiseled
bookshelves; striders and respawn anchors in the Nether; ice-boat roads; chest boats; sponges; wolf armour and
armadillos; and advancements as goals.

---

## E-64 Archaeology, amethyst and copper

| Slice | What the player does |
| --- | --- |
| **E-64a Archaeology** | A brush from feathers, copper and a stick; suspicious sand and gravel at desert temples, desert wells, ocean ruins and trail ruins brushed slowly (the brush animation is the item use; the ledger records each find); sherds kept and decorated pots made with four; a sniffer egg hatched and the sniffer kept (E-61), its torchflower and pitcher seeds planted. The atlas remembers dig sites. |
| **E-64b Amethyst** | Geodes found by their budding sound and the atlas's sightings; shards mined when fully grown (never the budding block); a spyglass (scouting, and manhunt's M-02e), tinted glass for a dark room, amethyst blocks in its palette. |
| **E-64c Copper** | Copper mined and smelted; oxidation stages used on purpose in builds (a weathered roof) and waxed with honeycomb (E-61c) where it should stay bright; a lightning rod on each tall build (fire safety, and charged creepers for heads, E-68c); 1.21's copper bulbs as redstone-toggled lights, copper doors and trapdoors, grates. |

Acceptance: a fixture per slice (a brushed find recorded and conserved; a pot made; a sniffer hatched; a spyglass
made and used in a glance; a rod placed by a build; a bulb toggled); the palettes by tag (F-38).

## E-65 Automation: the crafter, sorters, elevators, machines

| Slice | What the player does |
| --- | --- |
| **E-65a The crafter** | 1.21's crafter fed by hoppers for the recipes it repeats (planks to sticks, iron to blocks, sugar cane to paper, wheat to bread) at its storage room and farms; a recorded build with a bill of materials; the ledger watches what goes in and out. |
| **E-65b Item sorters** | A hopper and comparator sorter in the storage room (E-62g) by item group, overflow to a bulk chest; built when the chests outgrow sorting by hand. |
| **E-65c Elevators and doors** | A soul-sand and magma bubble elevator at the mine shaft (water from its bucket, soul sand and magma from the Nether); a piston door at the base; daylight-sensor lamps on the roads and in the yard. |
| **E-65d Flying machines and farms** | Slime and honey block flying machines for the tree and sugar-cane farms (the game's own observer and piston rules); a dripstone lava farm (pointed dripstone, a lava source, a cauldron) for cobblestone, obsidian and fuel. |
| **E-65e Blast mining** | TNT from creeper gunpowder and sand for mining a vein or clearing a room, under the edits policy and never in a claim it does not own; forbidden in Factions by the mode's rule (no TNT raids). |

Acceptance: a fixture per machine working as the game runs it (items counted in and out); E-65e refused in another
faction's claim.

## E-66 Potions, food and effects in play

The brewing (E-16b) and the eating (E-07a) exist; this is *using* them well.

| Slice | Rule |
| --- | --- |
| **E-66a The staples** | A golden apple kept for the worst moment (health under a third in a fight, a fall it cannot clutch), an enchanted one from a structure held for the dragon or the Wither; milk from its cow for poison, wither and mining fatigue (E-60a); a honey bottle for poison when no milk; suspicious stew from a flower it knows (saturation from the dandelion, night vision from the poppy, never the fire one by mistake); glow berries as food and light in a cave. |
| **E-66b Potions by situation** | Fire resistance before a Nether trip and in a bastion; water breathing for the monument; night vision for a dark cave or the sea floor; swiftness on a long road or a race; strength and regeneration for the dragon and the Wither; slow falling for a tower or the End's gateways; invisibility as a manhunt ruse (M-02e). Each a `Want` the needs queue can fill (F-25c7) from the brewing it has, with a count; splash potions for teammates in a fight. |
| **E-66c 1.21's bottles and arrows** | Ominous bottles kept from raid captains and used on purpose for an ominous trial (E-60e) when it wants the vault; the four 1.21 arrows and potions (wind charging, weaving, oozing, infestation) read as the game gives them and used where they help (weaving against a chase; wind charging to escape). |
| **E-66d Cold, heat and air** | Powder snow walked round or crossed in leather boots; freezing read as damage and left; the Nether's heat as fire resistance; air underwater as the breath meter it already watches, with a door or a torch for an air pocket. |

Acceptance: JUnit for the rules (when which potion, from staged state); fixtures: a golden apple eaten at the
threshold and not before; milk after a poison; fire resistance drunk before the portal; invisibility used only in a
manhunt; powder snow crossed in boots.

## E-67 Villagers beyond trading

Beside E-45 (trading, the hall, rerolls, bringing a villager home):

| Slice | What the player does |
| --- | --- |
| **E-67a Breeding and growth** | Beds and food (bread, carrots, potatoes, beetroot) for a village to grow; a bell at its square (E-63); job blocks for the professions it wants, placed and claimed; a village it founded counted as one by the game (beds and workstations). |
| **E-67b Curing** | A zombie villager caught in the open or found in an igloo, a splash potion of weakness and a golden apple, the cured villager's discounts taken: the cheapest way to a mending book, as players do it; the villager kept safe in a cell while it turns. |
| **E-67c Iron golems** | A golem built from its iron blocks and a pumpkin to guard the village, and the iron farm (E-62f) run from villagers it housed: the game's rules, not a trick. |
| **E-67d The wandering trader** | Met and traded with for saplings, dyes, coral and the rare thing; its llamas left alone, or a llama of its own tamed and a caravan carpeted for a hauling trip (E-61a). |
| **E-67e Hero and pariah** | Hero of the Village after a raid (E-60d) and its gifts; the reverse: a villager it hit by accident is not hit again, and the golems' anger read as a reason to leave. |

Acceptance: fixtures: a village growing by one villager from beds and food; a zombie villager cured and the
discount read from the trade; a golem built and the iron farm's first ingot; a trader trade.

## E-68 Expression, the rest

Beside E-59:

| Slice | What the player does |
| --- | --- |
| **E-68a Signs** | Signs and hanging signs written in its own words (the voice, or a fixed form without a key): its name on its door, a shop sign at the hall, a warning at the mine, a joke on a bench; never a person's name without their leave; dyed and glow-ink where it has the squid ink. |
| **E-68b Paintings** | Paintings placed on its walls by size and, among 1.21's twenty, by what it likes (a cautious player hangs calm ones); never over another's. |
| **E-68c Trophies** | Mob heads from charged creepers it set up with a lightning rod or a trident with Channelling in a storm; the dragon head from the End ship; the ominous banner from a captain; each on a stand or a wall in its hall with a frame; the voice tells the story when asked. |
| **E-68d Keepsakes of place** | An ender chest for what it carries everywhere (its own blaze powder and obsidian); a lodestone at its base with a compass bound to it, given to a friend; a chiseled-bookshelf library of the enchanted books it does not need yet; a celebration of fireworks it made at a milestone (the portal lit, the dragon) and at a match's end. |

Acceptance: fixtures: a sign written and read back; a painting placed within the wall; a head obtained and framed;
a lodestone compass pointing home; fireworks launched once at the milestone.

## E-69 Advancements as goals

The game's own long tail, and a standing goal engine for a stable server: a completionist player works toward the
advancements it has not earned, in the order its state allows, with the hard ones as projects: *Adventuring Time*
(every biome, by the atlas's frontier), *A Balanced Diet* (every food, with E-66 and the farms), *How Did We Get
Here?* (every effect at once, a feat players plan for days), *Two by Two* (every animal bred), *Monster Hunter*
and *Monsters Hunted*, *Cover Me in Debris*, *Serious Dedication*, *Is It a Plane?*, the 1.21 set (*Under Lock and
Key*, *Revaulting*, *Who Needs Rockets?*, *Blowback*, *Over-Overkill*, *Crafters Crafting Crafters*, *Lighten Up*).
Each advancement maps to needs the queue can fill and places the atlas can find; a scoreboard per player in the
World Record and on the watch page; the voice brags, in character, once per advancement.

Acceptance: the mapping in JUnit for every advancement in the game's list (one need chain each, or "blocked, and
why": the Warden's and the modded ones); a GameTest earning three advancements end to end from a standing goal; the
engine's ladder gains an "advancements earned" measure.

## Lines added to the other specs

- Survival world: **E-62i** Nether travel (striders ridden across lava with a warped fungus on a stick; a respawn
  anchor at the Nether base from glowstone it mined); **E-62a** ice roads (packed and blue ice from the peaks or
  with Silk Touch, a boat road for the long haul); **E-61a** llamas and chest boats for hauling; **E-61b** wolf
  armour from armadillo scutes, dyed, repaired; **E-60a** sponges used to drain an underwater build site; **E-60e**
  the ominous trial on purpose, vaults with both keys, wind charges as a jump and an escape.
- Match modes: **M-02e** invisibility, a spyglass, an ice-boat run and chorus fruit as ruses; **M-04b** concrete,
  terracotta and glazed terracotta palettes (concrete powder set with water on the plot), paintings and copper
  weathering in builds.

## When, on the current track

Only E-66a is urgent: golden apples, milk, honey and fire resistance used right are a direct cut in the deaths the
mechanics-first SURVIVAL session is chasing, and the brewing already exists. The rest lands in the survival-world
order after Factions and the World Record, as the lead's schedule says.
