#!/usr/bin/env python3
"""The failure corpus's replays as scenes the headless simulator can load (F-35f).

Reads the corpus (kindred_corpus/gametest/corpus/<id>.json, the ground round each in src/corpus/structures/<id>.snbt.gz,
and tools/corpus/baseline.json for the game's outcome) from a checkout or an archive of it, and writes one scene file for
each replay whose ground holds only blocks the simulator knows, in the simulator's own block and item names: the game's
ids stay here, never in the simulator. A scene is loadable when every block of its ground maps (the table below) and it is
a replay, not a staged fixture; the rest are listed with the first block that does not map. The hostile mobs captured with the ground come too, as the
simulator's zombie, skeleton, creeper and spider (a husk or a drowned as a zombie, a stray as a skeleton, a cave spider
as a spider). Python 3 standard library.

  tools/sim/corpus_scenes.py <corpus root> <out folder>
  (<corpus root> holds companion-server-adapter/src/corpus and tools/corpus, e.g. `git archive origin/main ...` unpacked)

The mapping, by what each block is to a walk, a dig and a look:
  STONE   stone, its varieties and deepslate, and the ores the simulator has none of (copper, gold, lapis, redstone,
          emerald), which a player there mines as rock; tuff, calcite, basalt, dripstone, amethyst, sculk and infested stone
  COBBLE  cobblestone, mossy and cobbled deepslate; DIRT dirt, coarse dirt, podzol, rooted dirt, moss, clay, mud;
  GRASS   grass block; SAND sand, red sand; GRAVEL; LOG every log and wood; LEAVES every leaves, and azaleas (a bush the
          height of a block); PLANKS every planks; WATER water, bubble columns and the plants that grow under it; LAVA;
  COAL_ORE, IRON_ORE, DIAMOND_ORE with their deepslate forms; BEDROCK; OBSIDIAN; TABLE crafting table; FURNACE;
  TORCH torch and wall torch; AIR air and every plant or layer with no collision box (grass, ferns, flowers, vines,
          lichen, moss carpet, one layer of snow, rails, mushrooms, sugar cane, dead bushes, sculk veins).
"""
import gzip
import json
import re
import sys
from pathlib import Path

DIRECT = {
    "stone": "STONE", "granite": "STONE", "diorite": "STONE", "andesite": "STONE", "deepslate": "STONE", "tuff": "STONE",
    "calcite": "STONE", "smooth_basalt": "STONE", "dripstone_block": "STONE", "amethyst_block": "STONE", "budding_amethyst": "STONE",
    "sculk": "STONE", "infested_stone": "STONE", "infested_deepslate": "STONE", "polished_granite": "STONE",
    "polished_diorite": "STONE", "polished_andesite": "STONE", "sandstone": "STONE", "raw_iron_block": "STONE", "raw_copper_block": "STONE",
    "copper_ore": "STONE", "deepslate_copper_ore": "STONE", "gold_ore": "STONE", "deepslate_gold_ore": "STONE",
    "lapis_ore": "STONE", "deepslate_lapis_ore": "STONE", "redstone_ore": "STONE", "deepslate_redstone_ore": "STONE",
    "emerald_ore": "STONE", "deepslate_emerald_ore": "STONE",
    "cobblestone": "COBBLE", "mossy_cobblestone": "COBBLE", "cobbled_deepslate": "COBBLE",
    "dirt": "DIRT", "coarse_dirt": "DIRT", "podzol": "DIRT", "rooted_dirt": "DIRT", "moss_block": "DIRT", "clay": "DIRT", "mud": "DIRT",
    "grass_block": "GRASS", "sand": "SAND", "red_sand": "SAND", "gravel": "GRAVEL",
    "water": "WATER", "bubble_column": "WATER", "seagrass": "WATER", "tall_seagrass": "WATER", "kelp": "WATER", "kelp_plant": "WATER",
    "lava": "LAVA", "coal_ore": "COAL_ORE", "deepslate_coal_ore": "COAL_ORE", "iron_ore": "IRON_ORE", "deepslate_iron_ore": "IRON_ORE",
    "diamond_ore": "DIAMOND_ORE", "deepslate_diamond_ore": "DIAMOND_ORE", "bedrock": "BEDROCK", "obsidian": "OBSIDIAN",
    "crafting_table": "TABLE", "furnace": "FURNACE", "torch": "TORCH", "wall_torch": "TORCH",
    "azalea": "LEAVES", "flowering_azalea": "LEAVES",
    "air": "AIR", "cave_air": "AIR", "short_grass": "AIR", "tall_grass": "AIR", "fern": "AIR", "large_fern": "AIR",
    "glow_lichen": "AIR", "vine": "AIR", "cave_vines": "AIR", "cave_vines_plant": "AIR", "moss_carpet": "AIR", "rail": "AIR",
    "brown_mushroom": "AIR", "red_mushroom": "AIR", "sugar_cane": "AIR", "dead_bush": "AIR", "sculk_vein": "AIR",
    "dandelion": "AIR", "poppy": "AIR", "cornflower": "AIR", "oxeye_daisy": "AIR", "lilac": "AIR", "azure_bluet": "AIR",
    "allium": "AIR", "blue_orchid": "AIR", "rose_bush": "AIR", "peony": "AIR", "sunflower": "AIR", "lily_of_the_valley": "AIR",
    "orange_tulip": "AIR", "red_tulip": "AIR", "white_tulip": "AIR", "pink_tulip": "AIR", "sweet_berry_bush": "AIR",
    "hanging_roots": "AIR", "spore_blossom": "AIR", "small_dripleaf": "AIR",
}


def block(state):
    """The simulator's block for a game block state, or None for one it does not know."""
    name = state.split("[")[0].split("{")[0].removeprefix("minecraft:")
    if name.endswith("_log") or name.endswith("_wood") or name.endswith("_stem") and name != "big_dripleaf_stem":
        return "LOG"
    if name.endswith("_leaves"):
        return "LEAVES"
    if name.endswith("_planks"):
        return "PLANKS"
    if name == "snow":
        layers = re.search(r"layers[=:](\d+)", state)
        return "AIR" if layers is None or layers.group(1) == "1" else None
    return DIRECT.get(name)


MOBS = {"zombie": "zombie", "husk": "zombie", "drowned": "zombie", "zombie_villager": "zombie", "skeleton": "skeleton",
        "stray": "skeleton", "creeper": "creeper", "spider": "spider", "cave_spider": "spider"}
ENTITY = re.compile(r'\{blockPos: \[[^\]]*\], pos: \[(-?[\d.E-]+)d, (-?[\d.E-]+)d, (-?[\d.E-]+)d\], nbt: (.*)$')


def mobs(text):
    """The hostile mobs in the ground's entities, as the simulator's four kinds, with where they stood and their health."""
    found = []
    start = text.find("entities: [")
    if start < 0:
        return found
    for line in text[start:].splitlines()[1:]:
        if line.startswith("]"):
            break
        m = ENTITY.match(line)
        if not m:
            continue
        nbt = m.group(4)
        # The entity's own id is its compound's last key, after anything it holds or wears.
        own = re.findall(r'id: "minecraft:([a-z_]+)"', nbt)
        kind = MOBS.get(own[-1]) if own else None
        if kind is None:
            continue
        health = re.search(r"\bHealth: ([\d.]+)f", nbt)
        found.append("mob %s %s %s %s %s" % (kind, m.group(1), m.group(2), m.group(3), health.group(1) if health else "20"))
    return found


FOOD_RAW = {"porkchop", "beef", "chicken", "mutton", "rabbit", "cod", "salmon"}


def item(game_id):
    """The simulator's item for a game item, or None for one it has no use for."""
    name = game_id.removeprefix("minecraft:")
    if name.endswith("_log") or name.endswith("_wood"):
        return "log"
    if name.endswith("_planks"):
        return "planks"
    if name.endswith("_sapling"):
        return "sapling"
    if name in FOOD_RAW:
        return "raw_meat"
    if name.startswith("cooked_"):
        return "cooked_meat"
    if name in ("cobbled_deepslate", "granite", "diorite", "andesite", "tuff", "mossy_cobblestone", "cobblestone"):
        return "cobblestone"
    if name in ("dirt", "coarse_dirt", "sand", "gravel", "stick", "stone_sword", "crafting_table", "shield", "coal", "charcoal",
                "wooden_pickaxe", "stone_pickaxe", "iron_pickaxe", "diamond_pickaxe", "wooden_sword", "iron_sword", "wooden_axe",
                "stone_axe", "iron_chestplate", "flint", "iron_ingot", "bucket", "water_bucket", "lava_bucket", "furnace",
                "raw_iron", "torch", "flint_and_steel", "diamond", "obsidian", "bread"):
        return {"sand": "dirt", "charcoal": "coal"}.get(name, name)
    return None


def main():
    root, out = Path(sys.argv[1]), Path(sys.argv[2])
    corpus = root / "companion-server-adapter/src/corpus"
    baseline = json.loads((root / "tools/corpus/baseline.json").read_text())
    out.mkdir(parents=True, exist_ok=True)
    loaded, skipped = [], []
    for scene in sorted((corpus / "resources/data/kindred_corpus/gametest/corpus").glob("*.json")):
        sid = scene.stem
        data = json.loads(scene.read_text())
        if "fixture" in data:
            skipped.append((sid, "a staged fixture, not a replay"))
            continue
        structure = corpus / "structures" / (sid + ".snbt.gz")
        if not structure.exists():
            skipped.append((sid, "no ground captured"))
            continue
        text = gzip.open(structure, "rt").read()
        cells, unknown = [], None
        for m in re.finditer(r'\{pos: \[(-?\d+), (-?\d+), (-?\d+)\], state: "([^"]+)"', text):
            b = block(m.group(4))
            if b is None:
                unknown = m.group(4)
                break
            if b != "AIR":
                cells.append("block %s %s %s %s" % (m.group(1), m.group(2), m.group(3), b))
        if unknown is not None:
            skipped.append((sid, "holds " + unknown))
            continue
        size = re.search(r"size: \[(\d+), (\d+), (\d+)\]", text)
        lines = ["id " + sid, "name " + data["player"], "outcome " + baseline.get(sid, "unknown"), "goal " + data.get("goal", "NONE"),
                 "size %s %s %s" % size.groups(), "origin %d %d %d" % tuple(data["origin"]),
                 "at %r %r %r" % tuple(data["at"]), "health %r" % data["health"], "food %d" % data["food"],
                 "saturation %r" % data["saturation"], "air %d" % data.get("air", 300), "time %d" % data["timeOfDay"], "ticks %d" % data.get("ticks", 1200)]
        dropped = 0
        for slot in data.get("inventory", []):
            name = item(slot["stack"]["id"])
            if name is None:
                dropped += 1
            else:
                lines.append("item %s %d" % (name, slot["stack"].get("count", 1)))
        lines.append("dropped %d" % dropped)
        lines.extend(mobs(text))
        (out / (sid + ".scene")).write_text("\n".join(lines + cells) + "\n")
        loaded.append(sid)
    (out / "skipped.txt").write_text("".join("%s: %s\n" % s for s in skipped))
    print("%d loadable, %d not (%s)" % (len(loaded), len(skipped), out / "skipped.txt"))


if __name__ == "__main__":
    main()
