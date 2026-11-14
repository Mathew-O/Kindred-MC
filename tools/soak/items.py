#!/usr/bin/env python3
"""The item-source audit (D6): every item an AI player holds, accounted for by what it earned.

Reads a stopped world: each AI player's native save (world/playerdata/<uuid>.dat: its inventory and ender chest), the
game's own statistics for it (world/stats/<uuid>.json: what it picked up, crafted, mined), and the World Record
(world/kindred/companions.sqlite: what the faction market and the auction house delivered to it). For every item it
holds, the sources that could have given it:
- picked up (the game's picked_up statistic: the drops of what it mined or killed, and stacks on the ground);
- crafted (the game's crafted statistic: a crafting table's or its own grid's output, a furnace's output taken, a
  villager's trade taken: vanilla counts each as crafted);
- bought (market.received and auction.delivered events in the Record, item and count);
- smelted (a furnace's output is taken from the block, which the game does not count as crafted: the inputs the player
  loaded, from its verified native_smelt actions in the action ledger, as the output they make; an upper bound);
- looted (the console's "<player> looted the container at ...: took ..." lines);
- filled (a bucket filled at water, lava or a cow: the game's used statistic for the empty bucket; an upper bound);
and flags any item held in greater number than all of them together: an item with no earned source. Taken from a chest
from its own store is not counted (what it stored it had earned); an item held only so is flagged, with how often the
console names it beside the player.

Python 3 standard library only:
  tools/soak/items.py run/soak/a/world-folder [--console run/soak/a/world-folder/console-s4.txt]
"""
import argparse
import collections
import gzip
import json
import re
import sqlite3
import struct
import sys
from pathlib import Path


def nbt(data):
    """A minimal reader of an uncompressed NBT document: the root compound as Python values."""
    pos = 0

    def take(n):
        nonlocal pos
        out = data[pos:pos + n]
        pos += n
        return out

    def string():
        (n,) = struct.unpack(">H", take(2))
        return take(n).decode("utf-8", "replace")

    def payload(tag):
        if tag == 1: return struct.unpack(">b", take(1))[0]
        if tag == 2: return struct.unpack(">h", take(2))[0]
        if tag == 3: return struct.unpack(">i", take(4))[0]
        if tag == 4: return struct.unpack(">q", take(8))[0]
        if tag == 5: return struct.unpack(">f", take(4))[0]
        if tag == 6: return struct.unpack(">d", take(8))[0]
        if tag == 7:
            (n,) = struct.unpack(">i", take(4)); return take(n)
        if tag == 8: return string()
        if tag == 9:
            inner = take(1)[0]
            (n,) = struct.unpack(">i", take(4))
            return [payload(inner) for _ in range(n)]
        if tag == 10:
            out = {}
            while True:
                t = take(1)[0]
                if t == 0: return out
                name = string()
                out[name] = payload(t)
        if tag == 11:
            (n,) = struct.unpack(">i", take(4)); return list(struct.unpack(">%di" % n, take(4 * n)))
        if tag == 12:
            (n,) = struct.unpack(">i", take(4)); return list(struct.unpack(">%dq" % n, take(8 * n)))
        raise ValueError("NBT tag %d" % tag)

    tag = take(1)[0]
    string()
    return payload(tag)


def held(save):
    """Every item in a native save, by id: inventory and ender chest, a shulker box's contents counted inside it."""
    counts = collections.Counter()

    def add(stack):
        counts[stack.get("id", "?")] += int(stack.get("count", stack.get("Count", 1)))
        for inner in ((stack.get("components") or {}).get("minecraft:container") or []):
            add(inner.get("item", {}))

    for stack in save.get("Inventory", []) + save.get("EnderItems", []):
        add(stack)
    return counts


# A furnace's input and what it makes, for what a player smelted.
SMELTS = {"raw_iron": "iron_ingot", "raw_gold": "gold_ingot", "raw_copper": "copper_ingot", "iron_ore": "iron_ingot",
          "gold_ore": "gold_ingot", "beef": "cooked_beef", "porkchop": "cooked_porkchop", "mutton": "cooked_mutton",
          "chicken": "cooked_chicken", "rabbit": "cooked_rabbit", "cod": "cooked_cod", "salmon": "cooked_salmon",
          "potato": "baked_potato", "kelp": "dried_kelp", "sand": "glass", "cobblestone": "stone", "clay_ball": "brick",
          "oak_log": "charcoal", "birch_log": "charcoal", "spruce_log": "charcoal", "jungle_log": "charcoal",
          "acacia_log": "charcoal", "dark_oak_log": "charcoal", "cherry_log": "charcoal", "mangrove_log": "charcoal"}
FILLED = ("minecraft:water_bucket", "minecraft:lava_bucket", "minecraft:milk_bucket", "minecraft:powder_snow_bucket")
LOOT = re.compile(r"\]: (\w+) looted the container at [^:]*: took ([^;\n]*)")


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("folder", help="the server folder (its world/ inside)")
    parser.add_argument("--console", action="append", default=[], help="console files to look in for flagged items")
    args = parser.parse_args()
    world = Path(args.folder) / "world"
    db = sqlite3.connect("file:%s?mode=ro" % (world / "kindred" / "companions.sqlite"), uri=True)
    cache = Path(args.folder) / "usercache.json"
    names = {e["uuid"]: e["name"] for e in json.loads(cache.read_text())} if cache.is_file() else {}
    # Bought: a market purchase (market.buy: its item, its count, its purchase id) whose market.received was written,
    # and an auction delivery (auction.delivered) with its item and count.
    received = {subject for (subject,) in db.execute("SELECT subject FROM world_events WHERE kind = 'market.received'")}
    bought = collections.defaultdict(collections.Counter)
    for kind, actor, subject, data in db.execute("SELECT kind, actor, subject, data FROM world_events WHERE kind IN ('market.buy', 'auction.delivered')"):
        fields = dict(p.split("=", 1) for p in (data or "").split("&") if "=" in p)
        if kind == "market.buy" and fields.get("purchase") not in received:
            continue
        item = subject if kind == "market.buy" else (fields.get("item") or fields.get("id"))
        if item and actor:
            bought[actor][item if ":" in item else "minecraft:" + item] += int(fields.get("count") or 1)
    # Smelted, by the player's name: each verified load of a furnace's input, as the output it makes.
    smelted = collections.defaultdict(collections.Counter)
    companions = {}
    try:
        companions = {str(cid): name for cid, name in db.execute("SELECT id, display_name FROM companions")}
    except sqlite3.Error:
        pass
    for cid, pre, after in db.execute("SELECT companion_id, preconditions, after_evidence FROM action_ledger WHERE kind = 'native_smelt' AND state = 'VERIFIED'"):
        loaded = re.search(r"in=minecraft:([a-z_]+)x\d+", after or "")
        count = re.search(r"inputCount=(\d+)", pre or "")
        if loaded and count and loaded.group(1) in SMELTS:
            smelted[companions.get(str(cid), str(cid))]["minecraft:" + SMELTS[loaded.group(1)]] += int(count.group(1))
    consoles = "\n".join(Path(c).read_text(encoding="utf-8", errors="replace") for c in args.console if Path(c).is_file())
    looted = collections.defaultdict(collections.Counter)
    for name, took in LOOT.findall(consoles):
        for part in took.split(","):
            got = re.match(r"\s*(\d+) (.+?)\s*$", part)
            if got:
                looted[name]["minecraft:" + got.group(2).replace(" ", "_")] += int(got.group(1))
    report = {"players": {}, "flags": []}
    for save_path in sorted((world / "playerdata").glob("*.dat")):
        uid = save_path.stem
        stats_path = world / "stats" / (uid + ".json")
        if not stats_path.is_file():
            continue
        stats = json.loads(stats_path.read_text()).get("stats", {})
        hold = held(nbt(gzip.decompress(save_path.read_bytes())))
        picked, crafted = stats.get("minecraft:picked_up", {}), stats.get("minecraft:crafted", {})
        name = names.get(uid, uid)
        rows = {}
        for item, count in sorted(hold.items()):
            used = stats.get("minecraft:used", {})
            sources = {"pickedUp": picked.get(item, 0), "crafted": crafted.get(item, 0), "bought": bought[uid][item],
                       "smelted": smelted[name][item], "looted": looted[name][item],
                       "filled": used.get("minecraft:bucket", 0) if item in FILLED else 0}
            rows[item] = {"held": count, **sources}
            if count > sum(sources.values()):
                mentions = len(re.findall(r"(?i)\b%s\b.*%s|%s.*\b%s\b" % (re.escape(name), re.escape(item.split(":")[-1]), re.escape(item.split(":")[-1]), re.escape(name)), consoles)) if consoles else None
                report["flags"].append({"player": name, "item": item, "held": count, **sources, "consoleMentions": mentions})
        report["players"][name] = {"items": sum(hold.values()), "kinds": len(hold), "pickedUp": sum(picked.values()),
                                   "crafted": sum(crafted.values()), "bought": sum(bought[uid].values()), "held": rows}
    json.dump(report, sys.stdout, indent=1)
    print()


if __name__ == "__main__":
    main()
