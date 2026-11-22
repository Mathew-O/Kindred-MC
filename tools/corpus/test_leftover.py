"""The leftover count, replay players acting after their own batch, counts actions, not a stop's own lines (F-27a3).

As the next batch starts, the runtime stops the last one's players. Its stop logs what it revokes: an action dropped
before it ran ("dropped before it ran … stop=true"), a break in progress whose collection it ends ("its collection
ended (authority ended)"), and a fight in progress it ends ("The fight was interrupted", from the fight's and the dragon
fight's close). None is the player acting. On F-25c4di1a's gate (#355) run 3, `Rc0794a1_1`'s only line after
its batch was the second, and the count said 1.

Nor is the periodic "Kindred cost: day N" line (F-27a3a): its "webs by player" (F-32d) names every player the Record
holds a web for. On E-40a1a's gate (#448) it was every one of the 35, 34 and 36 counted.
"""
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import report  # noqa: E402

BATCH = "[12:50:01] [Server thread/INFO] (Minecraft) Running test batch 'corpus_{}' (1 tests)"
BREAK = ("[12:50:05] [Server thread/INFO] (Kindred) Rc0794a1_1 native_break COMPLETED [Dig toward the surface]")
DROPPED = ("[12:51:22] [Server thread/INFO] (Kindred) R62d2729_1 native_wait dropped before it ran: paid=false/false "
           "generation=false stop=true cancelling=false hazard=false connected=true ledger=KNOWN_LEDGER_CLEAR blocker=")
REVOKED = ("[12:51:22] [Server thread/INFO] (Kindred) Rc0794a1_1 break at -11418214, -43, -7539024: its collection ended "
           "(authority ended) after 48 ticks, its own drops uncollected: [-11418213.13, -43.00, -7539023.78 age 49 delay 0]")
FIGHT = "[09:30:55] [Server thread/INFO] (Kindred) Ra97511_1 fight: 1 hits, 0 critical - The fight was interrupted"
COST = ("[05:43:51] [ForkJoinPool.commonPool-worker-1/INFO] (Kindred) Kindred cost: day 1: the Record through event 744, "
        "744 written since start (66725 characters of data); webs 1 holding 0 relations, the world's 0; webs by player, "
        "held/kept for good {R15f77d8_1=0/0, Rc0794a1_1=0/0}; Wire inboxes 0 messages")
ACTS = "[12:51:30] [Server thread/INFO] (Kindred) Rc0794a1_1 native_goto COMPLETED [Walk to gravel at 1, 2, 3]"


class Leftover(unittest.TestCase):
    def count(self, *lines):
        with tempfile.NamedTemporaryFile("w", suffix=".log", delete=False, encoding="utf-8") as log:
            log.write("\n".join(lines) + "\n")
        try:
            return report.leftover(log.name)
        finally:
            os.unlink(log.name)

    def test_a_stop_revoking_a_break_in_progress_is_not_acting(self):
        self.assertEqual(0, self.count(BATCH.format(15), BREAK, BATCH.format(0), REVOKED))

    def test_a_stop_dropping_an_action_is_not_acting(self):
        self.assertEqual(0, self.count(BATCH.format(15), BREAK.replace("Rc0794a1_1", "R62d2729_1"), BATCH.format(0), DROPPED))

    def test_a_stop_ending_a_fight_in_progress_is_not_acting(self):
        self.assertEqual(0, self.count(BATCH.format(5), BREAK.replace("Rc0794a1_1", "Ra97511_1"), BATCH.format(41), FIGHT))

    def test_an_action_after_its_batch_still_counts(self):
        self.assertEqual(1, self.count(BATCH.format(15), BREAK, BATCH.format(0), REVOKED, ACTS))

    def test_the_periodic_cost_line_naming_every_player_is_not_acting(self):
        # F-27a3a: its "webs by player" names every player the Record holds a web for (F-32d), long after their batches.
        self.assertEqual(0, self.count(BATCH.format(15), BREAK, BATCH.format(0), COST, BATCH.format(3), COST))

    def test_an_action_after_its_batch_still_counts_beside_the_cost_line(self):
        self.assertEqual(1, self.count(BATCH.format(15), BREAK, BATCH.format(0), COST, ACTS))

    def test_within_its_own_batch_nothing_counts(self):
        self.assertEqual(0, self.count(BATCH.format(15), BREAK, REVOKED, ACTS))


if __name__ == "__main__":
    unittest.main()
