"""The gate's confirm counts each flagged scene over the confirm files that hold it (F-27a; the lead, 2026-11-23).

F-25c6e's gate was confirmed by hand with one file a scene's run, not gate.sh's one file a round. The report read every
file as a whole round, so a scene was "failed" in each file that did not hold it: "branch failed 16 of 18, main passed
2 of 18", and two scenes confirmed that were not. And a confirm loop that let Gradle read its scene list from standard
input ran only the first scene; the others were missing from every file, and read as failed on the branch and not
passed on main, so cleared.

Now a scene counts only the files that hold it, so both layouts read the same, and a scene with fewer than three runs
a side is not confirmed and blocks.
"""
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPORT = Path(__file__).resolve().with_name("report.py")
# Two real scenes: the report counts only the corpus's own.
A = "diag-factions303-079bdc2-303-echo-10360"
B = "diag-factions303-079bdc2-303-echo-118680"


def results(path, outcomes):
    """A corpus-results.xml with each scene id passed (True) or failed (False)."""
    cases = "".join(
        f'<testcase classname="kindred_corpus:corpus/{sid}" name="corpus.{sid}" time="1.0">'
        + ("" if ok else '<failure message="died again"/>') + "</testcase>"
        for sid, ok in outcomes.items())
    path.write_text(f'<?xml version="1.0" encoding="UTF-8"?><testsuite><testsuite>{cases}</testsuite></testsuite>',
                    encoding="utf-8")
    return str(path)


class Confirm(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.baseline = self.dir / "baseline.json"
        self.baseline.write_text(json.dumps({"sha": "base", "runs": 3, "scenes": {A: "pass", B: "pass"}}))
        # Both scenes passed on the baseline and fail every run here: both flagged.
        self.runs = [results(self.dir / f"r{i}.xml", {A: False, B: False}) for i in (1, 2, 3)]

    def tearDown(self):
        for p in self.dir.iterdir():
            p.unlink()
        self.dir.rmdir()

    def report(self, branch, main):
        out = subprocess.run([sys.executable, str(REPORT), *self.runs, "--baseline", str(self.baseline),
                              "--confirm-branch", *branch, "--confirm-main", *main],
                             capture_output=True, text=True, cwd=REPORT.parent)
        return out.returncode, out.stdout

    # a-1: the branch fails it 3 of 3, main passes it 3 of 3: confirmed. b-2: the branch fails it once: cleared.
    A_BRANCH, A_MAIN = [False, False, False], [True, True, True]
    B_BRANCH, B_MAIN = [True, True, False], [True, True, False]

    def per_scene(self, side, a, b):
        files = []
        for i in range(3):
            files.append(results(self.dir / f"{side}-{i}.1.xml", {A: a[i]}))
            files.append(results(self.dir / f"{side}-{i}.2.xml", {B: b[i]}))
        return files

    def per_round(self, side, a, b):
        return [results(self.dir / f"{side}-{i}.xml", {A: a[i], B: b[i]}) for i in range(3)]

    def test_one_file_a_scenes_run_counts_each_scene_over_its_own_three(self):
        code, text = self.report(self.per_scene("branch", self.A_BRANCH, self.B_BRANCH),
                                 self.per_scene("main", self.A_MAIN, self.B_MAIN))
        self.assertIn("Confirmed (blocks): 1 — " + A + " (branch failed 3 of 3, main passed 3 of 3)", text)
        self.assertIn("Cleared: 1 — " + B + " (branch failed 1 of 3, main passed 2 of 3)", text)
        self.assertEqual(code, 1)

    def test_one_file_a_round_reads_the_same(self):
        code, text = self.report(self.per_round("branch", self.A_BRANCH, self.B_BRANCH),
                                 self.per_round("main", self.A_MAIN, self.B_MAIN))
        self.assertIn("Confirmed (blocks): 1 — " + A + " (branch failed 3 of 3, main passed 3 of 3)", text)
        self.assertIn("Cleared: 1 — " + B + " (branch failed 1 of 3, main passed 2 of 3)", text)
        self.assertEqual(code, 1)

    def test_a_scene_missing_from_the_confirms_is_not_confirmed_and_blocks(self):
        # The stdin loop: only a-1 ran, and it clears; b-2 has no confirm run at all.
        branch = [results(self.dir / f"branch-{i}.1.xml", {A: ok}) for i, ok in enumerate([True, True, False])]
        main = [results(self.dir / f"main-{i}.1.xml", {A: ok}) for i, ok in enumerate([True, False, True])]
        code, text = self.report(branch, main)
        self.assertIn("Cleared: 1 — " + A + " (branch failed 1 of 3, main passed 2 of 3)", text)
        self.assertIn("Not confirmed, too few confirm runs (blocks): 1 — " + B + " (0 run(s) on the branch, 0 on main; 3 each wanted)", text)
        self.assertIn("Gate: fails", text)
        self.assertEqual(code, 1)

    def test_all_cleared_passes(self):
        code, text = self.report(self.per_round("branch", [True, True, False], self.B_BRANCH),
                                 self.per_round("main", self.A_MAIN, self.B_MAIN))
        self.assertIn("Confirmed (blocks): 0", text)
        self.assertIn("Gate: passes", text)
        self.assertEqual(code, 0)


if __name__ == "__main__":
    unittest.main()
