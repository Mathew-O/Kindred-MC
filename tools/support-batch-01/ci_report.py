#!/usr/bin/env python3
"""Summarize this CI run's reports; never infer live qualification or edit the manifest."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import html
import json
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

from support import reports


# These modules currently contain ordinary JUnit tests. The adapter uses GameTests.
# Keep explicit expectations: discovering only existing reports can hide an omitted module.
JUNIT_MODULES = (
    'companion-core', 'companion-scheduler', 'companion-persistence',
    'companion-model', 'companion-testkit',
)
OUTCOMES = ('success', 'failure', 'cancelled', 'skipped', 'not_run')
COUNTS = ('tests', 'passed', 'failure', 'error', 'skipped')


def report_group(paths: list[Path]) -> dict:
    if not paths or any(not path.exists() for path in paths):
        return {'status': 'missing', 'totals': None, 'detail': 'Expected XML reports were not produced.'}
    try:
        result = reports(paths)
    except (OSError, ValueError, ET.ParseError) as error:
        return {'status': 'invalid', 'totals': None, 'detail': str(error)}
    totals = result['totals']
    if totals['failure'] or totals['error']:
        status = 'failed'
    elif not totals['passed']:
        status = 'incomplete'
    else:
        status = 'passed'
    return {'status': status, 'totals': totals, 'files': result['files']}


def summarize(root: Path, source: dict, steps: dict[str, str]) -> dict:
    if set(steps) != {'build', 'testkit', 'native'} or any(value not in OUTCOMES for value in steps.values()):
        raise ValueError('Explicit build, testkit and native outcomes are required.')
    groups = {
        module: report_group(sorted((root / module / 'build/test-results/test').glob('TEST-*.xml')))
        for module in JUNIT_MODULES
    }
    groups['native'] = report_group([root / 'companion-server-adapter/build/gametest-results.xml'])
    passed = all(outcome == 'success' for outcome in steps.values()) and all(
        group['status'] == 'passed' for group in groups.values()
    )
    return {
        'schemaVersion': 1,
        'source': source,
        'generatedAt': datetime.now(timezone.utc).isoformat(),
        'steps': steps,
        'groups': groups,
        'status': 'passed' if passed else 'incomplete_or_failed',
        'scope': 'One automated run only. Optional skipped tests are not passed tests. '
                 'Native fixtures do not establish ordinary-client or earned-playthrough qualification.',
    }


def markdown(result: dict) -> str:
    def cell(value: object) -> str:
        return html.escape(str(value)).replace('|', '&#124;').replace('\n', ' ')

    source = result['source']
    lines = [
        '## Automated validation evidence', '',
        f"Source: `{cell(source['commit'])}`; tracked changes: **{source['trackedChanges']}**.",
        f"Overall: **{result['status']}**.", '',
        '| Command | Outcome |', '| --- | --- |',
    ]
    lines.extend(f'| {name} | {outcome} |' for name, outcome in result['steps'].items())
    lines += ['', '| Suite | Status | Tests | Passed | Failures | Errors | Skipped |',
              '| --- | --- | ---: | ---: | ---: | ---: | ---: |']
    for name, group in result['groups'].items():
        counts = group['totals']
        numbers = [str(counts[key]) if counts is not None else '—' for key in COUNTS]
        lines.append('| ' + ' | '.join([name, group['status'], *numbers]) + ' |')
    for name, group in result['groups'].items():
        if 'detail' in group:
            lines += ['', f"{name}: {cell(group['detail'])}"]
    lines += ['', result['scope'], '']
    return '\n'.join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--output', type=Path, default=Path('build/ci-evidence'))
    for step in ('build', 'testkit', 'native'):
        parser.add_argument(f'--{step}-outcome', choices=OUTCOMES, default='not_run')
    args = parser.parse_args()
    root = args.root.resolve()

    def git(*arguments: str) -> str:
        return subprocess.run(['git', *arguments], cwd=root, check=True, capture_output=True, text=True).stdout.strip()

    source = {
        'commit': git('rev-parse', '--verify', 'HEAD'),
        'trackedChanges': bool(git('status', '--porcelain', '--untracked-files=no')),
        'workflowRef': os.environ.get('GITHUB_REF'),
        'runId': os.environ.get('GITHUB_RUN_ID'),
        'runAttempt': os.environ.get('GITHUB_RUN_ATTEMPT'),
    }
    steps = {step: getattr(args, f'{step}_outcome') for step in ('build', 'testkit', 'native')}
    result = summarize(root, source, steps)
    output = root / args.output
    output.mkdir(parents=True, exist_ok=True)
    (output / 'summary.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    rendered = markdown(result)
    (output / 'summary.md').write_text(rendered, encoding='utf-8')
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with Path(os.environ['GITHUB_STEP_SUMMARY']).open('a', encoding='utf-8') as stream:
            stream.write(rendered)
    print(rendered)
    return 0 if result['status'] == 'passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
