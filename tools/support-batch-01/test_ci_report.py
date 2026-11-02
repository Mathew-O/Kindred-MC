import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import ci_report


class CiReportTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.source = {'commit': 'a' * 40, 'trackedChanges': False}
        self.steps = dict.fromkeys(('build', 'testkit', 'native'), 'success')
        self.native = 'companion-server-adapter/build/gametest-results.xml'
        self.seed_reports()

    def put(self, relative, contents):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(contents, encoding='utf-8')
        return path

    def xml(self, cases='<testcase classname="Example" name="works"/>'):
        return '<testsuite>' + cases + '</testsuite>'

    def seed_reports(self):
        for module in ci_report.JUNIT_MODULES:
            self.put(f'{module}/build/test-results/test/TEST-Example.xml', self.xml())
        self.put(self.native, self.xml())

    def result(self):
        return ci_report.summarize(self.root, self.source, self.steps)

    def test_complete_reports_and_successful_commands_pass(self):
        result = self.result()
        self.assertEqual('passed', result['status'])
        self.assertEqual(6, len(result['groups']))
        self.assertEqual(self.source, result['source'])

    def test_command_failure_or_skip_cannot_be_hidden_by_passing_reports(self):
        for outcome in ('failure', 'cancelled', 'skipped', 'not_run'):
            with self.subTest(outcome=outcome):
                self.steps['build'] = outcome
                self.assertEqual('incomplete_or_failed', self.result()['status'])

    def test_missing_module_is_not_silently_omitted(self):
        (self.root / 'companion-core/build/test-results/test/TEST-Example.xml').unlink()
        result = self.result()
        self.assertEqual('missing', result['groups']['companion-core']['status'])
        self.assertIsNone(result['groups']['companion-core']['totals'])
        self.assertEqual('incomplete_or_failed', result['status'])

    def test_native_missing_empty_and_malformed_reports_never_pass(self):
        for xml in (None, '<testsuite tests="0"/>', '<testsuite>'):
            with self.subTest(xml=xml):
                if xml is None:
                    (self.root / self.native).unlink()
                else:
                    self.put(self.native, xml)
                result = self.result()
                self.assertIn(result['groups']['native']['status'], ('missing', 'invalid'))
                self.assertEqual('incomplete_or_failed', result['status'])

    def test_failure_error_and_skip_counts_remain_separate(self):
        self.put(self.native, self.xml(
            '<testcase name="pass"/>'
            '<testcase name="fail"><failure/></testcase>'
            '<testcase name="error"><error/></testcase>'
            '<testcase name="skip"><skipped/></testcase>'
        ))
        result = self.result()
        native = result['groups']['native']
        self.assertEqual('failed', native['status'])
        self.assertEqual([4, 1, 1, 1, 1], [native['totals'][key] for key in ci_report.COUNTS])
        self.assertIn('| native | failed | 4 | 1 | 1 | 1 | 1 |', ci_report.markdown(result))

    def test_opt_in_skips_are_reported_but_all_skipped_is_incomplete(self):
        model = 'companion-model/build/test-results/test/TEST-Example.xml'
        skipped = '<testcase name="liveProvider"><skipped/></testcase>'
        self.put(model, self.xml('<testcase name="localPolicy"/>' + skipped))
        result = self.result()
        self.assertEqual('passed', result['status'])
        self.assertEqual(1, result['groups']['companion-model']['totals']['skipped'])
        self.put(model, self.xml(skipped))
        result = self.result()
        self.assertEqual('incomplete', result['groups']['companion-model']['status'])
        self.assertEqual('incomplete_or_failed', result['status'])

    def test_duplicate_or_miscounted_reports_are_invalid(self):
        for xml in (
            self.xml('<testcase name="same"/><testcase name="same"/>'),
            '<testsuite tests="2"><testcase name="one"/></testsuite>',
        ):
            with self.subTest(xml=xml):
                self.put(self.native, xml)
                self.assertEqual('invalid', self.result()['groups']['native']['status'])

    def test_historical_xml_outside_build_outputs_does_not_fill_a_missing_run(self):
        (self.root / self.native).unlink()
        self.put('tools/support-batch-05/evidence/native.xml', self.xml())
        self.put('companion-server-adapter/build/old/gametest-results.xml', self.xml())
        self.assertEqual('missing', self.result()['groups']['native']['status'])

    def test_nested_native_suites_are_counted_once(self):
        self.put(self.native, '<testsuites tests="1">' + self.xml() + '</testsuites>')
        self.assertEqual(1, self.result()['groups']['native']['totals']['tests'])

    def test_explicit_command_outcomes_are_required(self):
        with self.assertRaises(ValueError):
            ci_report.summarize(self.root, self.source, {})
        self.steps['build'] = 'unknown'
        with self.assertRaises(ValueError):
            self.result()

    def test_cli_keeps_evidence_after_failure_and_uses_checkout_revision(self):
        subprocess.run(['git', 'init', '-q', str(self.root)], check=True)
        subprocess.run(['git', '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                        'commit', '--allow-empty', '-qm', 'Fixture'], cwd=self.root, check=True)
        sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=self.root, text=True).strip()
        summary = self.root / 'github-summary.md'
        self.put(self.native, self.xml('<testcase name="fails"><failure/></testcase>'))
        env = dict(os.environ, GITHUB_STEP_SUMMARY=str(summary))
        result = subprocess.run([
            sys.executable, str(Path(ci_report.__file__).resolve()), '--root', str(self.root),
            '--build-outcome', 'success', '--testkit-outcome', 'success', '--native-outcome', 'failure',
        ], capture_output=True, text=True, env=env)
        self.assertEqual(1, result.returncode, result.stderr)
        saved = json.loads((self.root / 'build/ci-evidence/summary.json').read_text())
        self.assertEqual(sha, saved['source']['commit'])
        self.assertEqual('failure', saved['steps']['native'])
        self.assertEqual(1, saved['groups']['native']['totals']['failure'])
        self.assertIn('incomplete_or_failed', summary.read_text())
        self.assertTrue((self.root / 'build/ci-evidence/summary.md').is_file())


if __name__ == '__main__':
    unittest.main()
