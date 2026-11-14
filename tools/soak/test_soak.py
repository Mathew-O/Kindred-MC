"""The restart soak's pure checks (F-31g): reading a console's inventory, a native save, and comparing two Records."""
import struct
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import items  # noqa: E402
import report  # noqa: E402


def tag_string(text):
    raw = text.encode("utf-8")
    return struct.pack(">H", len(raw)) + raw


def compound(fields):
    """A compound's body from (tag, name, payload bytes) triples, ended."""
    return b"".join(bytes([tag]) + tag_string(name) + payload for tag, name, payload in fields) + b"\x00"


def stack(item, count):
    return compound([(8, "id", tag_string(item)), (3, "count", struct.pack(">i", count))])


class ConsoleInventoryTest(unittest.TestCase):
    def test_a_stack_with_components_is_read_whole(self):
        text = ('Eli has the following entity data: [{count: 3, Slot: 0b, id: "minecraft:dirt"}, '
                '{count: 1, Slot: 1b, components: {"minecraft:damage": 3, "minecraft:enchantments": {levels: {"minecraft:unbreaking": 1}}}, '
                'id: "minecraft:iron_pickaxe"}, {count: 1, Slot: 2b, id: "minecraft:shield"}]')
        self.assertEqual([("minecraft:dirt", 3), ("minecraft:iron_pickaxe", 1), ("minecraft:shield", 1)], report.stacks(text))

    def test_nothing_read_from_no_entity(self):
        self.assertEqual([], report.stacks("No entity was found"))
        self.assertEqual({}, dict(report.items(None)))


class NativeSaveTest(unittest.TestCase):
    def test_inventory_and_ender_chest_counted_and_a_box_inside_too(self):
        box = compound([(8, "id", tag_string("minecraft:shulker_box")), (3, "count", struct.pack(">i", 1)),
                        (10, "components", compound([(9, "minecraft:container", bytes([10]) + struct.pack(">i", 1)
                                                      + compound([(10, "item", stack("minecraft:diamond", 5))]))]))])
        inventory = bytes([10]) + struct.pack(">i", 2) + stack("minecraft:cobblestone", 64) + box
        ender = bytes([10]) + struct.pack(">i", 1) + stack("minecraft:cobblestone", 3)
        document = bytes([10]) + tag_string("") + compound([(9, "Inventory", inventory), (9, "EnderItems", ender)])
        held = items.held(items.nbt(document))
        self.assertEqual(67, held["minecraft:cobblestone"])
        self.assertEqual(1, held["minecraft:shulker_box"])
        self.assertEqual(5, held["minecraft:diamond"])


class RecordCompareTest(unittest.TestCase):
    @staticmethod
    def row(eid, kind, data, place="", actor="a", subject="s"):
        return (eid, kind, actor, subject, place, data, eid, eid)

    def test_a_lost_event_a_changed_one_and_one_written_again_are_each_found(self):
        before = {1: self.row(1, "coins", "amount=50000&for=start"), 2: self.row(2, "claim", "", place="1,2"),
                  3: self.row(3, "action", "state=VERIFIED"), 4: self.row(4, "market.sale", "count=1")}
        after = {1: before[1], 2: self.row(2, "claim", "", place="9,9"),
                 5: self.row(5, "coins", "amount=50000&for=start"), 6: self.row(6, "claim", "", place="3,4")}
        lost, changed, again = report.compare(before, after)
        self.assertEqual([4], [r[0] for r in lost])  # the folded action is not counted lost
        self.assertEqual([2], [r[0][0] for r in changed])
        self.assertEqual({"coins": 1}, dict(again))  # a claim of another chunk is not the same claim

    def test_a_days_tallies_are_summed_and_only_those_after_the_last_snapshot_counted_since(self):
        # F-31b1: one game day pruned over two passes has two tallies, and its figures are their sum.
        rows = {1: self.row(1, "action.pruned", "day_start_ms=0&total=1&verified=1&first=a&last=a", subject="0"),
                2: self.row(2, "action.pruned", "day_start_ms=0&total=2&failed=1&cancelled=1&first=b&last=c", subject="0"),
                3: self.row(3, "claim", "", place="1,2"),
                4: self.row(4, "action.pruned", "day_start_ms=1200000&total=4&verified=4", subject="1")}
        self.assertEqual({"total": 7, "verified": 5, "failed": 1, "cancelled": 1}, dict(report.tallied(rows)))
        self.assertEqual({"total": 4, "verified": 4}, dict(report.tallied(rows, after=3)))
        before = {5: self.row(5, "action.folded", "state=VERIFIED"), 6: self.row(6, "claim", "", place="1,2")}
        lost, _, _ = report.compare(before, {6: before[6]})
        self.assertEqual([], lost)  # a pruned summary is counted against the tallies, not as lost

    def test_a_memory_entry_seen_again_since_is_the_same_entry(self):
        earlier = report.entries({"peers": [{"id": "p1", "lastSeen": 10}, {"id": "p2", "lastSeen": 11}]})
        later = report.entries({"peers": [{"id": "p1", "lastSeen": 99}]})
        self.assertEqual({report.identity({"id": "p2"})}, set(earlier["/peers"]) - set(later["/peers"]))


if __name__ == "__main__":
    unittest.main()
