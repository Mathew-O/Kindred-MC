"""The corpus's scenes stand out of each other's sight (F-27a, #235).

layout.py replays 1.21.1's TestStructurePlacer as CorpusGameTests feeds it: 8 tests a row, 5 blocks apart in x, a
row 6 blocks past everything placed so far in z, a spacer test after each scene. These tests hold the corpus's own
scenes to it, and check the simulation against the case #235 found: without spacers, every scene within 22 blocks of
another.
"""
import unittest

import layout


class Layout(unittest.TestCase):
    def setUp(self):
        self.scenes = layout.load()
        self.spacer = layout.spacer_size()

    def test_corpus_game_tests_sets_a_spacer_after_each_scene(self):
        self.assertIsNotNone(self.spacer, "CorpusGameTests sets no spacer after each scene")
        width, depth = self.spacer
        self.assertGreaterEqual(width, 86, "a scene's wall within 96 of the next in its row: 5 + width + 5 < 96")

    def test_no_scene_starts_within_96_of_another_scenes_box(self):
        near = layout.nearest(self.scenes, layout.simulate(self.scenes, self.spacer))
        self.assertTrue(near)
        close = sorted(sid for sid, d in near.items() if d <= 96)
        self.assertEqual(close, [], f"{len(close)} scenes start within 96 of another scene's box")

    def test_without_spacers_every_scene_sees_a_neighbour(self):
        near = layout.nearest(self.scenes, layout.simulate(self.scenes, None))
        self.assertTrue(all(d <= 32 for d in near.values()))

    def test_a_row_holds_scenes_and_spacers_alternately(self):
        boxes = layout.simulate({"a": {"size": [33, 17, 33], "at": [16, 8, 16]}, "b": {"size": [33, 17, 33], "at": [16, 8, 16]}},
                                (90, 130))
        self.assertEqual(boxes["a"][:2], (0, 0))
        self.assertEqual(boxes["b"][:2], (33 + 5 + 90 + 5, 0))


if __name__ == "__main__":
    unittest.main()
