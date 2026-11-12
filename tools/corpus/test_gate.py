"""The gate's confirm runs main's side on the branch's merge-base with origin/main, not on main's tip (F-27a).

A gate takes over an hour, and main moves meanwhile. Confirmed on main's tip, a flagged scene was run against merges the
branch does not carry (#192, #208 and #216 on 11 November). Each test builds a throwaway origin, a branch off it and a
later merge to main, and asks gate.sh which commit it would confirm on.
"""
import os
import shutil
import subprocess
import tempfile
import unittest

GATE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "gate.sh")


def git(cwd, *args):
    return subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True, text=True).stdout.strip()


class ConfirmRef(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        env = {"GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@example.invalid",
               "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@example.invalid"}
        os.environ.update(env)
        self.origin = os.path.join(self.dir, "origin.git")
        git(self.dir, "init", "-q", "--bare", "-b", "main", self.origin)
        self.main = os.path.join(self.dir, "main")
        git(self.dir, "clone", "-q", self.origin, self.main)
        git(self.main, "checkout", "-q", "-b", "main")
        git(self.main, "commit", "-q", "--allow-empty", "-m", "base")
        git(self.main, "push", "-q", "origin", "main")
        self.base = git(self.main, "rev-parse", "HEAD")
        # The branch under the gate: gate.sh at tools/corpus/, as in the repository, with one commit of its own.
        self.branch = os.path.join(self.dir, "branch")
        git(self.dir, "clone", "-q", self.origin, self.branch)
        git(self.branch, "checkout", "-q", "-b", "work")
        os.makedirs(os.path.join(self.branch, "tools", "corpus"))
        shutil.copy(GATE, os.path.join(self.branch, "tools", "corpus", "gate.sh"))
        git(self.branch, "add", "tools")
        git(self.branch, "commit", "-q", "-m", "work")

    def tearDown(self):
        shutil.rmtree(self.dir)

    def confirm_ref(self, *flags):
        gate = os.path.join(self.branch, "tools", "corpus", "gate.sh")
        return subprocess.run(["bash", gate, *flags, "--print-confirm-ref"], cwd=self.branch, check=True,
                              capture_output=True, text=True).stdout.strip()

    def move_main(self):
        git(self.main, "commit", "-q", "--allow-empty", "-m", "merged while the gate ran")
        git(self.main, "push", "-q", "origin", "main")
        return git(self.main, "rev-parse", "HEAD")

    def test_the_merge_base_by_default(self):
        self.assertEqual(self.confirm_ref(), self.base)

    def test_main_moving_during_the_gate_does_not_change_it(self):
        before = self.confirm_ref()
        tip = self.move_main()
        after = self.confirm_ref()  # gate.sh fetches origin/main first, so it sees the new tip
        self.assertEqual(git(self.branch, "rev-parse", "origin/main"), tip)
        self.assertEqual(before, after)
        self.assertEqual(after, self.base)

    def test_a_branch_that_merged_main_confirms_on_what_it_merged(self):
        tip = self.move_main()
        git(self.branch, "fetch", "-q", "origin", "main")
        git(self.branch, "merge", "-q", "--no-edit", "origin/main")
        self.move_main()
        self.assertEqual(self.confirm_ref(), tip)

    def test_main_ref_chooses_another(self):
        tip = self.move_main()
        self.assertEqual(self.confirm_ref("--main-ref", "origin/main"), tip)
        self.assertEqual(self.confirm_ref("--main-ref", self.base), self.base)



# A stand-in for ./gradlew: one "runCorpus" that notes whether a corpus world was there when it started, leaves a world
# behind as a real run does, and reports scene s1 failing in a full run (so the gate flags it) and passing alone.
FAKE_GRADLEW = """#!/usr/bin/env bash
run=companion-server-adapter/build/corpus
if [ -e "$run/world" ] || [ -e "$run/kindred" ]; then echo "$(pwd) grown" >> "$SEEN"; else echo "$(pwd) empty" >> "$SEEN"; fi
mkdir -p "$run/world/region" "$run/kindred/encounters/x"
head -c 2097152 /dev/zero > "$run/world/region/r.0.0.mca"
fail="<failure message='died'/>"; [ -n "${KINDRED_CORPUS_ONLY:-}" ] && fail=''
mkdir -p companion-server-adapter/build
echo "<testsuite><testcase name='corpus.s1'>$fail</testcase></testsuite>" > companion-server-adapter/build/corpus-results.xml
[ -n "${BROKEN:-}" ] && echo "<testsuite>" > companion-server-adapter/build/corpus-results.xml
echo "GAME TESTS COMPLETE"
"""


class EmptyWorld(unittest.TestCase):
    """Every corpus run in the gate, the branch's confirm included, starts from an empty world (F-27a)."""

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        os.environ.update({"GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@example.invalid",
                           "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@example.invalid"})
        self.seen = os.path.join(self.dir, "seen.txt")
        os.environ["SEEN"] = self.seen
        origin = os.path.join(self.dir, "origin.git")
        git(self.dir, "init", "-q", "--bare", "-b", "main", origin)
        self.tree = os.path.join(self.dir, "tree")
        git(self.dir, "clone", "-q", origin, self.tree)
        git(self.tree, "checkout", "-q", "-b", "main")
        corpus = os.path.join(self.tree, "tools", "corpus")
        os.makedirs(corpus)
        here = os.path.dirname(os.path.abspath(__file__))
        for name in ("gate.sh", "report.py", "known-intermittent.txt"):
            shutil.copy(os.path.join(here, name), corpus)
        scenes = os.path.join(self.tree, "companion-server-adapter", "src", "corpus", "resources", "data", "kindred_corpus",
                              "gametest", "corpus")
        os.makedirs(scenes)
        with open(os.path.join(scenes, "s1.json"), "w") as f:
            f.write('{"kind": "death", "corpus": {"class": "death: zombie"}}')
        with open(os.path.join(corpus, "baseline.json"), "w") as f:
            f.write('{"sha": "base", "runs": 6, "scenes": {"s1": "pass"}}')
        with open(os.path.join(self.tree, "gradlew"), "w") as f:
            f.write(FAKE_GRADLEW)
        os.chmod(os.path.join(self.tree, "gradlew"), 0o755)
        with open(os.path.join(self.tree, ".gitignore"), "w") as f:
            f.write("build/\ncompanion-server-adapter/build/\n")
        git(self.tree, "add", ".")
        git(self.tree, "commit", "-q", "-m", "base")
        git(self.tree, "push", "-q", "origin", "main")
        git(self.tree, "checkout", "-q", "-b", "work")
        # What an earlier run in this checkout left: a grown world.
        world = os.path.join(self.tree, "companion-server-adapter", "build", "corpus", "world", "region")
        os.makedirs(world)
        with open(os.path.join(world, "r.0.0.mca"), "wb") as f:
            f.write(b"\0" * (3 * 1024 * 1024))

    def tearDown(self):
        shutil.rmtree(self.dir)

    def gate(self, *args):
        return subprocess.run(["bash", os.path.join(self.tree, "tools", "corpus", "gate.sh"), *args], cwd=self.tree,
                              capture_output=True, text=True)

    def test_empty_world_only_empties_it_and_says_what_it_held(self):
        done = self.gate("--empty-world-only")
        self.assertEqual(done.returncode, 0, done.stderr)
        self.assertIn("corpus world held 3 MB; emptied", done.stdout)
        self.assertFalse(os.path.exists(os.path.join(self.tree, "companion-server-adapter", "build", "corpus", "world")))

    def test_every_run_and_both_confirms_start_from_an_empty_world(self):
        done = self.gate("2")
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        with open(self.seen) as f:
            seen = [line.split()[-1] for line in f]
        # Two full runs, three confirming runs on the branch and three on main: each from an empty world, though the
        # checkout came in with one grown and each run left one behind.
        self.assertEqual(len(seen), 8, seen)
        self.assertEqual(set(seen), {"empty"})
        with open(os.path.join(self.tree, "build", "corpus-gate", "report.md")) as f:
            report = f.read()
        self.assertIn("Corpus world before each run (emptied every time):", report)
        self.assertIn("- results-1: corpus world held 3 MB; emptied", report)
        self.assertIn("- confirm-branch-1: corpus world held 2 MB; emptied", report)
        self.assertIn("- confirm-main-1: corpus world held 0 MB; emptied", report)

    def test_a_report_that_could_not_be_made_is_no_pass(self):
        os.environ["BROKEN"] = "1"
        try:
            done = self.gate("1")
        finally:
            del os.environ["BROKEN"]
        self.assertEqual(done.returncode, 2, done.stdout + done.stderr)
        self.assertIn("The report could not be made", done.stderr)


if __name__ == "__main__":
    unittest.main()
