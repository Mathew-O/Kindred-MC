"""A corpus batch keeps each scene's own day and night (F-27a).

Every replay sets the world clock to its record as it starts, and the last of a batch to start sets it for all.
Batched by a day-or-night flag at 13000 with the time beside it, 20 of 422 scenes played under another day or night
than their own: 11004 to 11286 beside 11529, which runs into dusk (corpus_21); 3599 to 9027 in rain beside 11419
(corpus_40); 12851 and 12912 beside 23470, at dawn (corpus_24). batches.py replays CorpusGameTests' batching with
the constants it sets; these tests hold the corpus to it.
"""
import unittest

import batches
import layout


class Batches(unittest.TestCase):
    def setUp(self):
        self.scenes = layout.load()
        self.made = batches.batches(self.scenes)

    def test_corpus_game_tests_batches_by_the_clock(self):
        c = batches.constants()
        self.assertTrue(c["clockFits"], "CorpusGameTests does not check a batch's clock (clockFits)")
        # vanilla isNight, worked out over every tick with 1.21.1's MathHelper (docs/evidence/F-27a-clock-batches.md)
        self.assertEqual((c["NIGHT_CLEAR_FROM"], c["NIGHT_CLEAR_TO"]), (12542, 23460))
        self.assertEqual((c["NIGHT_RAIN_FROM"], c["NIGHT_RAIN_TO"]), (12010, 23992))
        self.assertIsNotNone(c["CLOCK_TOLERANCE"])

    def test_every_batch_keeps_its_scenes_day_and_night(self):
        tolerance = batches.constants()["CLOCK_TOLERANCE"] or 0
        bad = {f"corpus_{n}": batches.worst(self.scenes, b) for n, b in enumerate(self.made)
               if batches.worst(self.scenes, b) > tolerance}
        self.assertEqual(bad, {}, "batches whose scenes are night at other ticks under a batch-mate's time")

    def test_the_three_cases_found_are_split(self):
        where = {sid: n for n, b in enumerate(self.made) for sid in b}
        for scene, setter in (("e40c1-eed2dc5-101-echo-59004", "f29e-on-707-sol-11529"),
                              ("e40a20-79a9aad-505-kindred-60851", "factions-e40a16seeds-b7b4bec-404-kindred-143470"),
                              ("e40a20-79a9aad-20261030-kindred-99600", "e40a20-79a9aad-20261030-kindred-131420")):
            self.assertNotEqual(where[scene], where[setter], f"{scene} batched with {setter}")

    def test_a_batch_is_one_group_in_time_order_never_round_the_wrap(self):
        for n, b in enumerate(self.made):
            self.assertLessEqual(len(b), batches.constants()["BATCH"])
            groups = {batches.world_key(self.scenes[i]).rsplit("/", 1)[0] for i in b}
            self.assertEqual(len(groups), 1, f"corpus_{n} mixes {groups}")
            times = [self.scenes[i]["timeOfDay"] % 24000 for i in b if "timeOfDay" in self.scenes[i]]
            self.assertEqual(times, sorted(times), f"corpus_{n} runs round from 23999 to 0")

    def test_clock_difference(self):
        clear = {"timeOfDay": 11004, "ticks": 3600}
        self.assertEqual(batches.clock_difference(clear, 11004), 0)
        self.assertEqual(batches.clock_difference(clear, 11529), 525)  # night from 12542: at 12029 of its run, not 1538
        self.assertEqual(batches.clock_difference({"timeOfDay": 12851, "ticks": 3600}, 23470), 3600)  # all night, or all day
        self.assertEqual(batches.clock_difference({"timeOfDay": 3599, "ticks": 3600, "raining": True}, 11419), 3009)  # rain: night from 12010, so 15019 - 12010
        self.assertEqual(batches.clock_difference({"timeOfDay": 100, "ticks": 600, "raining": True, "thundering": True}, 6000), 0)


if __name__ == "__main__":
    unittest.main()
