#!/usr/bin/env python3
"""Offline support checks. Python standard library only; never starts builds/providers/servers."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path, PurePosixPath
import re
import stat
import sys
import tempfile
from urllib.parse import unquote, urlsplit
import xml.etree.ElementTree as ET
import zipfile

MAX_FILE = 8 * 1024 * 1024
MAX_BUNDLE = 32 * 1024 * 1024
ALLOWED_EVIDENCE = {'.md', '.txt', '.xml', '.json'}
SECRET_NAME = re.compile(r'(^\.|credential|secret|token|password|private.?key|\.pem$|\.key$)', re.I)
SECRET_CONTENT = re.compile(r'(?:sk-(?:ant-)?[A-Za-z0-9_-]{16,}|-----BEGIN [A-Z ]*PRIVATE KEY-----|(?:api[_-]?key|authorization|password)\s*[=:]\s*["\']?(?!null\b|none\b|false\b|<|\$\{|\*\*\*|redacted\b)[A-Za-z0-9/+_.-]{12,})', re.I)


def read_bytes(path: Path, limit: int = MAX_FILE) -> bytes:
    if path.is_symlink() or not path.is_file():
        raise ValueError(f'Expected a regular, non-symlink file: {path.name}')
    with path.open('rb') as stream:
        data = stream.read(limit + 1)
    if len(data) > limit:
        raise ValueError(f'File exceeds size limit: {path.name}')
    return data


def reports(paths: list[Path]) -> dict:
    """Count test cases, never aggregate suite attributes twice; reject duplicate identities."""
    files = sorted({p for path in paths for p in (path.rglob('*.xml') if path.is_dir() else [path])})
    if not files:
        raise ValueError('No XML reports found')
    cases = {}
    for path in files:
        data = read_bytes(path)
        if b'<!DOCTYPE' in data.upper() or b'<!ENTITY' in data.upper():
            raise ValueError('DTD/entity declarations are not accepted')
        root = ET.fromstring(data)
        if root.tag not in {'testsuite', 'testsuites'}:
            raise ValueError(f'Not a JUnit report: {path.name}')
        for case in root.iter('testcase'):
            name = case.get('name')
            if not name:
                raise ValueError('Unnamed test case')
            key = case.get('classname', '') + '::' + name
            if key in cases:
                raise ValueError(f'Duplicate test identity; analyze repeated runs separately: {key}')
            seconds = float(case.get('time', '0'))
            if not math.isfinite(seconds) or seconds < 0:
                raise ValueError('Test duration must be finite and nonnegative')
            status = next((tag for tag in ('error', 'failure', 'skipped') if case.find(tag) is not None), 'passed')
            cases[key] = {'status': status, 'seconds': seconds}
        for suite in root.iter('testsuite'):
            direct = suite.findall('testcase')
            if direct and suite.get('tests') is not None and int(suite.get('tests')) != len(direct):
                raise ValueError(f'Suite count disagrees with test cases: {path.name}')
            if direct:
                for attribute, tag in (('failures', 'failure'), ('errors', 'error'), ('skipped', 'skipped')):
                    actual = sum(case.find(tag) is not None for case in direct)
                    if suite.get(attribute) is not None and int(suite.get(attribute)) != actual:
                        raise ValueError(f'Suite {attribute} disagree with test cases: {path.name}')
            elif not suite.findall('testsuite') and any(int(suite.get(key, '0')) for key in ('errors', 'failures')):
                raise ValueError('Suite-level failure has no represented test cases')
    if not cases:
        raise ValueError('Reports contain no test cases')
    totals = {key: 0 for key in ('tests', 'passed', 'failure', 'error', 'skipped')}
    for case in cases.values():
        totals['tests'] += 1
        totals[case['status']] += 1
    totals['seconds'] = round(sum(c['seconds'] for c in cases.values()), 6)
    return {'files': len(files), 'totals': totals, 'cases': dict(sorted(cases.items())),
            'scope': 'Only the supplied reports; no native or live qualification inferred.'}


def regression(before: dict, after: dict) -> dict:
    a, b = before['cases'], after['cases']
    return {'regressed': sorted(k for k in a.keys() & b.keys() if a[k]['status'] == 'passed' and b[k]['status'] in {'failure', 'error'}),
            'fixed': sorted(k for k in a.keys() & b.keys() if a[k]['status'] in {'failure', 'error'} and b[k]['status'] == 'passed'),
            'missing': sorted(a.keys() - b.keys()), 'added': sorted(b.keys() - a.keys()),
            'newly_skipped': sorted(k for k in a.keys() & b.keys() if a[k]['status'] != 'skipped' and b[k]['status'] == 'skipped')}


def repeated(runs: list[dict]) -> dict:
    if len(runs) < 2:
        raise ValueError('Repeated-run analysis needs at least two runs')
    keys = set().union(*(run['cases'] for run in runs))
    states = {key: [run['cases'].get(key, {}).get('status', 'missing') for run in runs] for key in sorted(keys)}
    return {'runs': len(runs), 'flaky': {k: v for k, v in states.items() if 'passed' in v and any(s in {'failure', 'error'} for s in v)},
            'incomplete': {k: v for k, v in states.items() if 'missing' in v},
            'always_failed': [k for k, v in states.items() if all(s in {'failure', 'error'} for s in v)]}


def safe_path(root: Path, relative: str) -> Path:
    root = root.resolve()
    if '\\' in relative:
        raise ValueError('Use repository-relative POSIX paths')
    key = PurePosixPath(relative)
    if key.is_absolute() or '..' in key.parts or not key.parts or ':' in relative:
        raise ValueError('Path must stay inside the repository')
    path = root
    for part in key.parts:
        path = path / part
        if path.is_symlink():
            raise ValueError('Symlinks are not accepted')
    if not path.resolve().is_relative_to(root):
        raise ValueError('Path escapes repository')
    return path


def bundle(root: Path, inputs: list[str], destination: Path) -> dict:
    """Explicit evidence files only, no directories, symlinks, credentials, databases or raw logs."""
    payload = {}
    total = 0
    for relative in inputs:
        path = safe_path(root, relative)
        if path.suffix.lower() not in ALLOWED_EVIDENCE or any(SECRET_NAME.search(part) for part in PurePosixPath(relative).parts):
            raise ValueError('Evidence input has an excluded name or type')
        data = read_bytes(path)
        text = data.decode('utf-8')
        if path.suffix.lower() == '.xml':
            if '<!DOCTYPE' in text.upper() or '<!ENTITY' in text.upper():
                raise ValueError('DTD/entity declarations are not accepted')
            xml = ET.fromstring(text)
            # Console output and assertion messages can contain application data. Keep outcomes only.
            for parent in xml.iter():
                for child in list(parent):
                    if child.tag in {'system-out', 'system-err', 'properties'}:
                        parent.remove(child)
                    elif child.tag in {'failure', 'error'}:
                        child.clear()
            data = ET.tostring(xml, encoding='utf-8', xml_declaration=True)
            text = data.decode('utf-8')
        if SECRET_CONTENT.search(text):
            raise ValueError('Possible credentials in evidence; sanitize before bundling')
        key = PurePosixPath(relative).as_posix()
        if key == 'SHA256SUMS.json' or key in payload:
            raise ValueError('Duplicate or reserved archive entry')
        payload[key] = data
        total += len(data)
        if total > MAX_BUNDLE:
            raise ValueError('Evidence exceeds bundle size limit')
    if not payload:
        raise ValueError('Select at least one evidence file')
    checksums = {key: hashlib.sha256(data).hexdigest() for key, data in sorted(payload.items())}
    payload['SHA256SUMS.json'] = (json.dumps(checksums, indent=2, sort_keys=True) + '\n').encode()
    if destination.is_symlink():
        raise ValueError('Output cannot be a symlink')
    destination = destination.resolve()
    if any(destination == safe_path(root, name).resolve() for name in inputs):
        raise ValueError('Output cannot replace an evidence input')
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=destination.parent, delete=False) as temporary:
        temporary_path = Path(temporary.name)
    try:
        with zipfile.ZipFile(temporary_path, 'w', zipfile.ZIP_DEFLATED) as archive:
            for name, data in sorted(payload.items()):
                info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = (stat.S_IFREG | 0o644) << 16
                archive.writestr(info, data)
        os.replace(temporary_path, destination)
    finally:
        temporary_path.unlink(missing_ok=True)
    return {'files': checksums, 'bytes': total, 'sha256': hashlib.sha256(destination.read_bytes()).hexdigest()}


def anchors(markdown: str) -> set[str]:
    result = set(re.findall(r'(?:id|name)=["\']([^"\']+)["\']', markdown))
    used = {}
    for heading in re.findall(r'^#{1,6}\s+(.+?)\s*#*$', markdown, re.M):
        slug = re.sub(r'[^\w\- ]', '', heading.lower()).replace(' ', '-')
        n = used.get(slug, 0)
        used[slug] = n + 1
        result.add(slug if n == 0 else f'{slug}-{n}')
    return result


def links(root: Path, inputs: list[str]) -> dict:
    problems = []
    checked = 0
    for relative in inputs:
        path = safe_path(root, relative)
        source = read_bytes(path).decode('utf-8')
        source = re.sub(r'```.*?```', '', source, flags=re.S)
        for target in re.findall(r'!?\[[^\]]*\]\(([^)]+)\)', source):
            target = target.strip().split(' "', 1)[0].strip('<>')
            url = urlsplit(target)
            if url.scheme or url.netloc:
                continue  # Offline checks never contact a remote URL.
            checked += 1
            resolved = path.parent / unquote(url.path) if url.path else path
            try:
                # Normalize ../ links while ensuring final target stays inside root; then check symlinks.
                resolved = Path(os.path.abspath(resolved))
                relative_target = resolved.relative_to(root.resolve()).as_posix()
                checked_path = safe_path(root, relative_target)
                if not checked_path.exists():
                    raise ValueError('missing target')
                if url.fragment and checked_path.suffix.lower() == '.md' and unquote(url.fragment) not in anchors(read_bytes(checked_path).decode('utf-8')):
                    raise ValueError('missing heading anchor')
            except (ValueError, OSError) as error:
                problems.append({'file': relative, 'target': target, 'reason': str(error)})
    return {'checked': checked, 'problems': problems,
            'scope': 'Inline relative Markdown links/headings only; reference links and external URLs are not checked.'}


def tracker(root: Path) -> dict:
    text = read_bytes(root / 'docs/feature-spec.md').decode()
    complete = re.findall(r'^- \[x\] ([A-Z][A-Z0-9-]*[a-z0-9]*)\s+[—-]\s+(.+)$', text, re.M)
    ids = re.findall(r'^- \[[ x]\] ([A-Z][A-Z0-9-]*[a-z0-9]*)\s+[—-]', text, re.M)
    duplicates = sorted({key for key in ids if ids.count(key) > 1})
    undocumented = [key for key, line in complete if not re.search(r'\d{4}-\d{2}-\d{2}', line) or '](' not in line]
    return {'completed': len(complete), 'duplicate_ids': duplicates, 'missing_dated_evidence': undocumented,
            'scope': 'Checks tracker syntax and dated links; does not certify evidence or acceptance criteria.'}


def manifest(root: Path) -> dict:
    data = json.loads(read_bytes(root / 'release-manifest.json'))
    props = dict(re.findall(r'^([\w.]+)=(.+)$', read_bytes(root / 'gradle.properties').decode(), re.M))
    problems = []
    for key, prop in {'minecraft': 'minecraft_version', 'fabricLoader': 'loader_version', 'fabricApi': 'fabric_version', 'version': 'mod_version'}.items():
        if data.get(key) != props.get(prop):
            problems.append(f'{key} disagrees with {prop}')
    wrapper = read_bytes(root / 'gradle/wrapper/gradle-wrapper.properties').decode()
    if f'gradle-{data.get("gradle")}-bin.zip' not in wrapper:
        problems.append('Gradle wrapper version mismatch')
    if not re.search(r'^distributionSha256Sum=[0-9a-f]{64}$', wrapper, re.M):
        problems.append('Wrapper checksum missing or malformed')
    if data.get('javaMajor') != 21:
        problems.append('Java major must remain 21')
    if not isinstance(data.get('liveAcceptanceGatesPassed'), list):
        problems.append('Live gate list missing')
    return {'problems': problems, 'versions': {k: data.get(k) for k in ('minecraft', 'javaMajor', 'gradle', 'version')},
            'scope': 'Version/configuration consistency only; not a build or release qualification.'}


def jar(path: Path) -> dict:
    problems = []
    with zipfile.ZipFile(path) as archive:
        infos = archive.infolist()
        names = [i.filename for i in infos]
        if len(names) != len(set(names)):
            problems.append('Duplicate ZIP entries')
        for info in infos:
            key = PurePosixPath(info.filename)
            if key.is_absolute() or '..' in key.parts or '\\' in info.filename or ':' in info.filename:
                problems.append('Unsafe ZIP entry name')
            if stat.S_ISLNK(info.external_attr >> 16):
                problems.append('Symlink ZIP entry')
            if info.file_size > MAX_BUNDLE:
                problems.append('Oversized ZIP member')
        if sum(i.file_size for i in infos) > 256 * 1024 * 1024:
            problems.append('Oversized expanded JAR')
        if 'fabric.mod.json' not in names:
            problems.append('fabric.mod.json missing')
        elif not problems:
            mod = json.loads(archive.read('fabric.mod.json'))
            if not isinstance(mod, dict) or not isinstance(mod.get('jars', []), list):
                raise ValueError('Fabric metadata must be an object with a nested-JAR list')
            if mod.get('id') != 'kindred':
                problems.append('Unexpected Fabric mod ID')
            for entry in mod.get('jars', []):
                if not isinstance(entry, dict) or not isinstance(entry.get('file'), str) or entry['file'] not in names:
                    problems.append('Declared nested JAR missing')
    return {'problems': problems, 'scope': 'Archive metadata only; no Minecraft execution or runtime linkage proof.'}


def preflight(root: Path) -> dict:
    required = ['AGENTS.md', 'gradlew', 'gradle/wrapper/gradle-wrapper.jar', 'docs/feature-spec.md', 'docs/validation.md', 'release-manifest.json']
    return {'missing': [name for name in required if not (root / name).is_file()],
            'live_edit_markers': [str(p.relative_to(root)) for p in sorted((root / 'docs').rglob('*.md')) if read_bytes(p).decode().startswith('<!-- LIVE EDIT:')],
            'manifest': manifest(root), 'scope': 'Read-only static preflight; commands/tests are never executed.'}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
    sub = parser.add_subparsers(dest='command', required=True)
    for command in ('summary', 'repeat'):
        p = sub.add_parser(command); p.add_argument('reports', type=Path, nargs='+')
    p = sub.add_parser('compare'); p.add_argument('before', type=Path); p.add_argument('after', type=Path)
    p = sub.add_parser('bundle'); p.add_argument('output', type=Path); p.add_argument('inputs', nargs='+')
    p = sub.add_parser('links'); p.add_argument('inputs', nargs='+')
    for command in ('tracker', 'manifest', 'preflight'):
        sub.add_parser(command)
    p = sub.add_parser('jar'); p.add_argument('file', type=Path)
    args = parser.parse_args(argv); root = args.root.resolve()
    try:
        if args.command == 'summary': result = reports(args.reports)
        elif args.command == 'compare': result = regression(reports([args.before]), reports([args.after]))
        elif args.command == 'repeat': result = repeated([reports([p]) for p in args.reports])
        elif args.command == 'bundle': result = bundle(root, args.inputs, args.output)
        elif args.command == 'links': result = links(root, args.inputs)
        elif args.command == 'tracker': result = tracker(root)
        elif args.command == 'manifest': result = manifest(root)
        elif args.command == 'jar': result = jar(args.file)
        else: result = preflight(root)
        print(json.dumps(result, indent=2, sort_keys=True))
        failed = any(result.get(key) for key in ('problems', 'regressed', 'missing', 'newly_skipped', 'flaky', 'incomplete', 'always_failed', 'duplicate_ids', 'missing_dated_evidence', 'live_edit_markers'))
        if args.command == 'summary': failed |= bool(result['totals']['failure'] + result['totals']['error'])
        if args.command == 'preflight': failed |= bool(result['manifest']['problems'])
        return 1 if failed else 0
    except (ValueError, OSError, ET.ParseError, zipfile.BadZipFile) as error:
        print(f'Check failed: {error}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
