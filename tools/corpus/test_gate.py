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


if __name__ == "__main__":
    unittest.main()
