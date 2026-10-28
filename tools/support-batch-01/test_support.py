import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('support', Path(__file__).with_name('support.py'))
support = importlib.util.module_from_spec(spec)
spec.loader.exec_module(support)


class SupportToolsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def put(self, name, content):
        p = self.root / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content)
        return p

    def report(self, name='run.xml', cases='<testcase classname="C" name="a" time="0.25"/>'):
        return self.put(name, '<testsuite>' + cases + '</testsuite>')

    def test_01_counts_nested_suites_once(self):
        p = self.put('run.xml', '<testsuites tests="2"><testsuite tests="2"><testcase name="a"/><testcase name="b"><failure/></testcase></testsuite></testsuites>')
        totals = support.reports([p])['totals']
        self.assertEqual((2, 1, 1), (totals['tests'], totals['passed'], totals['failure']))

    def test_02_distinguishes_error_skip_and_failure(self):
        p = self.report(cases='<testcase name="a"><skipped/></testcase><testcase name="b"><error/></testcase><testcase name="c"><failure/></testcase>')
        self.assertEqual({'tests': 3, 'passed': 0, 'failure': 1, 'error': 1, 'skipped': 1, 'seconds': 0}, support.reports([p])['totals'])

    def test_03_rejects_empty_directory(self):
        with self.assertRaises(ValueError): support.reports([self.root])

    def test_04_rejects_empty_report(self):
        with self.assertRaises(ValueError): support.reports([self.report(cases='')])

    def test_05_rejects_malformed_xml(self):
        with self.assertRaises(support.ET.ParseError): support.reports([self.put('bad.xml', '<testsuite>')])

    def test_06_rejects_duplicate_test_identities(self):
        p = self.report(cases='<testcase name="a"/><testcase name="a"/>')
        with self.assertRaises(ValueError): support.reports([p])

    def test_07_rejects_entity_declarations(self):
        p = self.put('entity.xml', '<!DOCTYPE x [<!ENTITY y "x">]><testsuite><testcase name="a"/></testsuite>')
        with self.assertRaises(ValueError): support.reports([p])

    def test_08_rejects_nonfinite_or_negative_duration(self):
        for value in ('NaN', 'Infinity', '-1'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                support.reports([self.report(cases=f'<testcase name="a" time="{value}"/>')])

    def test_09_audits_declared_test_count(self):
        p = self.put('count.xml', '<testsuite tests="2"><testcase name="a"/></testsuite>')
        with self.assertRaises(ValueError): support.reports([p])
        p = self.put('count.xml', '<testsuite tests="1" failures="0"><testcase name="a"><failure/></testcase></testsuite>')
        with self.assertRaises(ValueError): support.reports([p])
        p = self.put('count.xml', '<testsuites><testsuite errors="1"/><testsuite><testcase name="a"/></testsuite></testsuites>')
        with self.assertRaises(ValueError): support.reports([p])

    def test_10_regression_reports_missing_added_fixed_and_skipped(self):
        a = {'cases': {'C::a': {'status': 'passed'}, 'C::b': {'status': 'failure'}, 'C::c': {'status': 'passed'}, 'C::d': {'status': 'passed'}}}
        b = {'cases': {'C::a': {'status': 'error'}, 'C::b': {'status': 'passed'}, 'C::d': {'status': 'skipped'}, 'C::e': {'status': 'passed'}}}
        r = support.regression(a, b)
        self.assertEqual(['C::a'], r['regressed']); self.assertEqual(['C::b'], r['fixed'])
        self.assertEqual(['C::c'], r['missing']); self.assertEqual(['C::d'], r['newly_skipped']); self.assertEqual(['C::e'], r['added'])

    def test_11_repeated_runs_distinguish_flakes_and_missing(self):
        runs = [{'cases': {'a': {'status': 'passed'}, 'b': {'status': 'failure'}}}, {'cases': {'a': {'status': 'failure'}, 'c': {'status': 'passed'}}}]
        r = support.repeated(runs)
        self.assertEqual(['a'], list(r['flaky'])); self.assertEqual(['b', 'c'], list(r['incomplete']))

    def test_12_repeated_requires_two_runs(self):
        with self.assertRaises(ValueError): support.repeated([{'cases': {}}])

    def test_13_bundle_is_deterministic_and_hashes_members(self):
        self.put('evidence/a.md', '# Evidence\n')
        a, b = self.root / 'a.zip', self.root / 'b.zip'
        support.bundle(self.root, ['evidence/a.md'], a); support.bundle(self.root, ['evidence/a.md'], b)
        self.assertEqual(a.read_bytes(), b.read_bytes())
        with zipfile.ZipFile(a) as z:
            self.assertEqual(json.loads(z.read('SHA256SUMS.json'))['evidence/a.md'], support.hashlib.sha256(z.read('evidence/a.md')).hexdigest())

    def test_14_bundle_rejects_traversal(self):
        with self.assertRaises(ValueError): support.bundle(self.root, ['../outside.md'], self.root / 'out.zip')

    def test_15_bundle_rejects_absolute_paths(self):
        with self.assertRaises(ValueError): support.bundle(self.root, ['/tmp/data.md'], self.root / 'out.zip')

    def test_16_bundle_rejects_backslashes_and_drive_names(self):
        for name in ('..\\data.md', 'C:/data.md'):
            with self.subTest(name=name), self.assertRaises(ValueError): support.bundle(self.root, [name], self.root / 'out.zip')

    def test_17_bundle_rejects_real_symlink_file(self):
        actual = self.put('actual.md', 'evidence')
        (self.root / 'link.md').symlink_to(actual)
        with self.assertRaises(ValueError): support.bundle(self.root, ['link.md'], self.root / 'out.zip')

    def test_18_bundle_rejects_real_symlink_directory(self):
        self.put('actual/a.md', 'evidence'); (self.root / 'linked').symlink_to(self.root / 'actual', target_is_directory=True)
        with self.assertRaises(ValueError): support.bundle(self.root, ['linked/a.md'], self.root / 'out.zip')

    def test_19_bundle_rejects_secret_names_and_database_types(self):
        for name in ('.env', 'secret.json', 'state.sqlite', 'events.log', 'auth/token.txt'):
            self.put(name, 'x')
            with self.subTest(name=name), self.assertRaises(ValueError): support.bundle(self.root, [name], self.root / 'out.zip')

    def test_20_bundle_rejects_credentials_in_text(self):
        self.put('notes.md', 'api_key=sk-' + 'a' * 24)
        with self.assertRaises(ValueError): support.bundle(self.root, ['notes.md'], self.root / 'out.zip')

    def test_21_bundle_strips_xml_logs_properties_and_failure_details(self):
        self.put('test.xml', '<testsuite><properties><property name="password" value="private"/></properties><testcase name="a"><failure message="private">private</failure></testcase><system-out>private</system-out></testsuite>')
        out = self.root / 'out.zip'; support.bundle(self.root, ['test.xml'], out)
        with zipfile.ZipFile(out) as z:
            data = z.read('test.xml'); self.assertNotIn(b'private', data); self.assertIn(b'<failure', data)

    def test_22_bundle_rejects_duplicate_and_reserved_names(self):
        self.put('a.md', 'x'); self.put('SHA256SUMS.json', '{}')
        for names in (['a.md', 'a.md'], ['SHA256SUMS.json']):
            with self.subTest(names=names), self.assertRaises(ValueError): support.bundle(self.root, names, self.root / 'out.zip')

    def test_23_bundle_refuses_empty_selection(self):
        with self.assertRaises(ValueError): support.bundle(self.root, [], self.root / 'out.zip')

    def test_24_read_enforces_size_limit(self):
        p = self.put('big.md', 'x' * 11)
        with self.assertRaises(ValueError): support.read_bytes(p, 10)

    def test_25_markdown_checks_relative_target_and_anchor(self):
        self.put('docs/a.md', '[ok](../target.md#hello-world)\n[missing](gone.md)\n[remote](https://example.invalid)')
        self.put('target.md', '# Hello World\n')
        r = support.links(self.root, ['docs/a.md']); self.assertEqual(2, r['checked']); self.assertEqual(1, len(r['problems']))

    def test_26_markdown_reports_missing_anchor_and_ignores_code_fences(self):
        self.put('a.md', '# Present\n[bad](#absent)\n```md\n[example](no.md)\n```')
        r = support.links(self.root, ['a.md']); self.assertEqual(1, r['checked']); self.assertEqual(1, len(r['problems']))

    def test_27_duplicate_heading_anchors_are_numbered(self):
        self.assertEqual({'same', 'same-1', 'same-2'}, support.anchors('# Same\n## Same\n### Same\n'))

    def test_28_tracker_requires_date_and_evidence_for_completed_ids(self):
        self.put('docs/feature-spec.md', '- [x] F-01 — Complete without evidence\n- [x] F-02 — Complete 2026-10-24 [evidence](a.md)\n- [ ] F-02 — Duplicate\n')
        r = support.tracker(self.root); self.assertEqual(['F-01'], r['missing_dated_evidence']); self.assertEqual(['F-02'], r['duplicate_ids'])

    def manifest_fixture(self):
        self.put('release-manifest.json', json.dumps({'minecraft': '1.21.1', 'fabricLoader': '0.19.5', 'fabricApi': '0.116.17+1.21.1', 'version': '0.1.0', 'gradle': '8.14.3', 'javaMajor': 21, 'liveAcceptanceGatesPassed': []}))
        self.put('gradle.properties', 'minecraft_version=1.21.1\nloader_version=0.19.5\nfabric_version=0.116.17+1.21.1\nmod_version=0.1.0\n')
        self.put('gradle/wrapper/gradle-wrapper.properties', 'distributionUrl=https://example.invalid/gradle-8.14.3-bin.zip\ndistributionSha256Sum=' + 'a' * 64 + '\n')

    def test_29_manifest_matches_pinned_properties_and_checksum(self):
        self.manifest_fixture(); self.assertEqual([], support.manifest(self.root)['problems'])

    def test_30_manifest_reports_drift_without_changing_files(self):
        self.manifest_fixture(); p = self.root / 'gradle.properties'; p.write_text(p.read_text().replace('1.21.1', '1.22'))
        before = p.read_bytes(); self.assertTrue(support.manifest(self.root)['problems']); self.assertEqual(before, p.read_bytes())

    def test_31_jar_checks_nested_entries(self):
        p = self.root / 'test.jar'
        with zipfile.ZipFile(p, 'w') as z: z.writestr('fabric.mod.json', json.dumps({'id': 'kindred', 'jars': [{'file': 'META-INF/jars/missing.jar'}]}))
        self.assertIn('Declared nested JAR missing', support.jar(p)['problems'])

    def test_32_jar_rejects_traversal_and_symlinks(self):
        p = self.root / 'test.jar'
        with zipfile.ZipFile(p, 'w') as z:
            z.writestr('../escape', 'x'); info = zipfile.ZipInfo('link'); info.external_attr = (0o120777 << 16); z.writestr(info, '/private')
        r = support.jar(p); self.assertIn('Unsafe ZIP entry name', r['problems']); self.assertIn('Symlink ZIP entry', r['problems'])

    def test_33_preflight_is_static_and_reports_missing_wrapper(self):
        self.manifest_fixture(); self.put('docs/validation.md', 'evidence')
        self.put('docs/feature-spec.md', '<!-- LIVE EDIT: active -->\n')
        r = support.preflight(self.root); self.assertIn('gradlew', r['missing']); self.assertEqual(['docs/feature-spec.md'], r['live_edit_markers'])

    def test_34_cli_exit_codes_signal_test_failure_and_bad_input(self):
        p = self.report(cases='<testcase name="a"><failure/></testcase>')
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(1, support.main(['summary', str(p)])); self.assertEqual(2, support.main(['summary', str(self.root / 'missing.xml')]))


if __name__ == '__main__':
    unittest.main()
