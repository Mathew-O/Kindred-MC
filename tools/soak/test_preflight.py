"""Tests of the month run's pre-flight report (M-06k7): every figure is read as the console and the Record give it."""
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import preflight  # noqa: E402

DAY1 = ("[01:00:00] [ForkJoinPool.commonPool-worker-1/INFO] (Kindred) Kindred cost: day 1: the Record through event 1000, 1000 written "
        "since start (90000 characters of data); by kind events/characters {claim=4/200, journal=900/80000}; the database 2000000 bytes "
        "on disk; webs 8 holding 40 relations, the world's 12; webs by player, held/kept for good {Ada=100/60, Bram=50/50}; Wire inboxes 30 messages; Wire 3 refused since start (BUDGET on team 1, "
        "DUPLICATE on faction 2); territory refusals 5 written, 40 the same minute's and 0 over a faction's daily cap, only counted; "
        "teammates' updates (team.state) 10 told since start, kept only in the peer ledgers; Chronicle 1 pages kept; heap in use 3000 MB, "
        "high water 4100 MB since the last line; server ticks 24000: mean 24.0 ms, p99 48 ms, 300 over 50 ms, longest 900.0 ms (tick 18000) "
        "since the last line; far looks 5 since start, 50 sight lines, 3 ms; native.disconnects 0 since start")
DAY3 = DAY1.replace("day 1:", "day 3:").replace("through event 1000", "through event 3000").replace("2000000 bytes", "5000000 bytes") \
    .replace("p99 48 ms, 300 over 50 ms", "p99 70 ms, 2000 over 50 ms").replace("high water 4100 MB", "high water 11500 MB") \
    .replace("native.disconnects 0", "native.disconnects 2").replace("{Ada=100/60, Bram=50/50}", "{Ada=30000/21000, Bram=90/70}").replace("0 over a faction's daily cap", "7 over a faction's daily cap")
CONSOLE = "\n".join([
    DAY1,
    "[01:10:00] [Server thread/INFO] (Kindred) Kindred action fold: 200 settled actions, 600 events, folded in 20 ms on the writer",
    "[01:20:00] [Server thread/WARN] (Kindred) Kindred native body Bram disconnected from outside after 1200 ticks",
    "[01:21:00] [Server thread/WARN] (Kindred) Kindred native body Ada disconnected from outside after 300 ticks",
    "[01:30:00] [Server thread/ERROR] (Kindred) Bram's native_break 72cbb812-0000-0000-0000-000000000000 is not plain: tick 41000",
    DAY3,
])


class PreflightTest(unittest.TestCase):
    def record(self, directory, differs=0):
        path = Path(directory) / "companions.sqlite"
        db = sqlite3.connect(path)
        db.execute("CREATE TABLE world_events (id INTEGER PRIMARY KEY, kind TEXT, subject TEXT)")
        db.execute("CREATE TABLE action_ledger (action_id TEXT, state TEXT)")
        db.executemany("INSERT INTO world_events (kind, subject) VALUES (?, ?)",
                       [("ledger_check", "agrees")] * 3 + [("ledger_check", "differs")] * differs + [("claim", "red")])
        db.execute("INSERT INTO action_ledger VALUES ('a', 'VERIFIED')")
        db.execute("PRAGMA user_version = 15")
        db.commit()
        db.close()
        return path

    def test_each_figure_is_read_as_the_console_gives_it_and_a_month_only_projected(self):
        out = preflight.report(CONSOLE)
        day1 = out["costLines"][0]
        self.assertEqual(2000000, day1["databaseBytes"])
        self.assertEqual({"BUDGET on team": 1, "DUPLICATE on faction": 2}, day1["wireRefusedBy"])
        self.assertEqual({"written": 5, "sameMinute": 40, "overCap": 0}, day1["territoryRefusals"])
        self.assertEqual({"count": 24000, "meanMs": 24.0, "p99Ms": 48, "p99Over": False, "over50": 300, "longestMs": 900.0,
                          "longestAtTick": 18000}, day1["ticks"])
        self.assertEqual(4100, day1["heapHighWaterMb"])
        self.assertEqual({"Ada": {"held": 100, "keptForGood": 60}, "Bram": {"held": 50, "keptForGood": 50}}, day1["websByPlayer"])
        self.assertEqual([{"fromDay": 1, "toDay": 3, "bytesPerGameDay": 1500000.0, "eventsPerGameDay": 1000.0,
                           "webKeptPerGameDay": {"Ada": 10470.0, "Bram": 10.0}}], out["growth"])
        self.assertIn("projected, not measured", out["projectedMonth"]["note"])
        self.assertEqual(5000000 + 1500000 * 2160, out["projectedMonth"]["databaseBytes"])
        self.assertEqual({"passes": 1, "removed": 200, "secondFigure": 600}, out["prunes"]["fold"])
        self.assertEqual(1, len(out["warnsByKind"]), "two disconnects, one kind: %s" % out["warnsByKind"])
        self.assertEqual(2, list(out["warnsByKind"].values())[0])
        self.assertEqual([("Bram", 41000)], [(e["player"], e["tick"]) for e in out["errors"]])
        # Watch, not stop: the day-3 ticks, the over-cap refusals, the disconnects, the ERROR to read.
        self.assertEqual([], out["stop"])
        joined = " | ".join(out["watch"])
        for expected in ("day 3: p99 tick 70 ms", "day 3: 8.3% of ticks over 50 ms", "7 refused attempts over", "native.disconnects rose", "1 ERROR line",
                         "day 3: Ada's web keeps 21000 facts for good, past its bound of 20000"):
            self.assertIn(expected, joined)
        self.assertNotIn("day 1: p99", joined)
        self.assertNotIn("Bram's web", joined)
        self.assertIn("webs, held/kept for good: Ada 100/60, Bram 50/50", preflight.text(out))

    def test_each_players_body_maps_are_read_and_a_full_chests_used_is_a_watch(self):
        maps = "; body maps by player, place refusals/set aside/chests used/seen at stores/untrusted stores/recently taken/raids "
        console = "\n".join([DAY1 + maps + "{Ada=1/0/5/2/0/3/1, Bram=0/0/0/0/0/0/0}",
                              DAY3 + maps + "{Ada=3/2/32/1/1/4/9, Bram=0/0/1/0/0/0/0}"])
        out = preflight.report(console)
        self.assertEqual({"placeRefusals": 1, "placeSetAside": 0, "chestsUsed": 5, "seenAtStores": 2, "untrustedStores": 0,
                          "recentlyTaken": 3, "raids": 1}, out["costLines"][0]["bodyMapsByPlayer"]["Ada"])
        self.assertEqual({"placeRefusals": 1.0, "placeSetAside": 1.0, "chestsUsed": 13.5, "seenAtStores": -0.5, "untrustedStores": 0.5,
                          "recentlyTaken": 0.5, "raids": 4.0}, out["growth"][0]["bodyMapsPerGameDay"]["Ada"])
        self.assertTrue(any("Ada's chests seen used at its cap of 32" in w for w in out["watch"]), out["watch"])
        self.assertFalse(any("Bram's chests seen used" in w for w in out["watch"]), out["watch"])

    def test_a_differing_check_or_a_heap_near_its_limit_is_a_stop(self):
        with tempfile.TemporaryDirectory() as directory:
            quiet = preflight.report(DAY1, self.record(directory), heap_max_mb=12288)
            self.assertEqual([], quiet["stop"])
            self.assertEqual({"agrees": 3}, quiet["record"]["ledgerChecks"])
            self.assertEqual(("ok", 15, 0), (quiet["record"]["integrity"], quiet["record"]["schema"], quiet["record"]["unresolvedActions"]))
        with tempfile.TemporaryDirectory() as directory:
            out = preflight.report(CONSOLE, self.record(directory, differs=1), heap_max_mb=12288)
            joined = " | ".join(out["stop"])
            self.assertIn("1 ledger check(s) differ", joined)
            self.assertIn("day 3: heap high water 11500 MB", joined)
            self.assertNotIn("day 1: heap", joined)
            self.assertIn("STOP: ", preflight.text(out))


if __name__ == "__main__":
    unittest.main()
