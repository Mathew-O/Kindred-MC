"""Every corpus scene's replay player has a name of its own (F-27a).

ReplayGameTests.replayName names a scene's player "R" + the low 28 bits of its id's Java hashCode, in hex, + "_" + the
run. Named from the recorded player and tick alone, two scenes of one player caught at the same tick in two worlds were
one name (e40a20-79a9aad-404-kindred-99080 and diag-factions303-079bdc2-303-kindred-99080). These tests replay the rule
over the corpus's scene files, so a scene imported later whose name another already has fails here.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCENES = ROOT / "companion-server-adapter/src/corpus/resources/data/kindred_corpus/gametest/corpus"
SOURCE = ROOT / "companion-server-adapter/src/gametest/java/dev/kindred/fabric/ReplayGameTests.java"


def java_hash(text):
    """Java's String.hashCode, as an unsigned 32-bit value (scene ids are ASCII, one UTF-16 unit a character)."""
    h = 0
    for c in text:
        h = (31 * h + ord(c)) & 0xFFFFFFFF
    return h


def name(scene, run=1):
    return "R%x_%d" % (java_hash(scene) & 0xFFFFFFF, run)


class Names(unittest.TestCase):
    def test_the_rule_here_is_replay_game_tests_own(self):
        text = SOURCE.read_text(encoding="utf-8")
        self.assertIn("scene != null ? scene.hashCode()", text, "ReplayGameTests no longer names a replay from its scene's id")
        self.assertIn('"R" + Integer.toHexString(key & 0xfffffff) + "_" + run', text)

    def test_no_two_corpus_scenes_share_a_name(self):
        ids = sorted(p.stem for p in SCENES.glob("*.json"))
        self.assertTrue(ids)
        by = {}
        for sid in ids:
            by.setdefault(name(sid), []).append(sid)
        shared = {n: s for n, s in by.items() if len(s) > 1}
        self.assertEqual(shared, {}, f"{len(shared)} names shared by scenes")

    def test_every_name_is_a_profile_name(self):
        for p in SCENES.glob("*.json"):
            self.assertRegex(name(p.stem), r"^[A-Za-z0-9_]{1,16}$")

    def test_the_two_99080_scenes_now_differ(self):
        self.assertNotEqual(name("e40a20-79a9aad-404-kindred-99080"), name("diag-factions303-079bdc2-303-kindred-99080"))


if __name__ == "__main__":
    unittest.main()
