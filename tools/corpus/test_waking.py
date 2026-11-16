"""Waking scenes in the corpus tools (F-27a4): their class on import, and the report's count of armour back on."""
import importlib.util
import os
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import report  # noqa: E402

spec = importlib.util.spec_from_file_location("corpus_import", os.path.join(HERE, "import.py"))
corpus_import = importlib.util.module_from_spec(spec)
spec.loader.exec_module(corpus_import)


class Waking(unittest.TestCase):
    def test_a_waking_has_a_class_of_its_own(self):
        self.assertEqual("waking: its death in the box", corpus_import.classify({"kind": "respawn", "deathInside": True,
                                                                                 "cause": "respawned after its death at 1, 2, 3 (Sol was slain by Zombie)"}))
        self.assertEqual("waking: its death outside the box", corpus_import.classify({"kind": "respawn", "deathInside": False}))

    def test_a_death_is_still_classed_by_what_killed_it(self):
        self.assertEqual("death: zombie", corpus_import.classify({"kind": "death", "cause": "Sol was slain by Zombie"}))

    def test_armour_back_on_is_counted_from_the_runs_lines(self):
        lines = ["[12:00:00] [Server thread/INFO] (Kindred) Replay 1 of R1234567_1: its armour back on: yes (1 of 4 armour slots; reported, not the pass)",
                 "[12:00:01] [Server thread/INFO] (Kindred) Replay 1 of R7654321_1: its armour back on: no (0 of 4 armour slots; reported, not the pass)",
                 "[12:00:02] [Server thread/INFO] (Kindred) Replay 1 of R7654321_1: died again, as it did"]
        with tempfile.NamedTemporaryFile("w", suffix=".log", delete=False, encoding="utf-8") as log:
            log.write("\n".join(lines) + "\n")
        try:
            self.assertEqual((1, 2), report.armour_back(log.name))
        finally:
            os.unlink(log.name)


if __name__ == "__main__":
    unittest.main()
