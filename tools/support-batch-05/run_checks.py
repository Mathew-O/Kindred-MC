#!/usr/bin/env python3
"""Reproduce the bounded support suite when a full Fabric/SDK Gradle build is unavailable.

No network access or provider calls. Supply JDK 21 and the four pinned jars listed below.
This deliberately compiles only ModelGateway/ReasoningPolicy from companion-model; it
is not validation of provider transports, the adapter, all core tests, or live gameplay.
"""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys

DEPENDENCIES = {
    'junit.jar': ('org.junit.platform:junit-platform-console-standalone:1.11.4', 'b016ef6b1c3454d6d7c2c88ce081dabf289699686af6622d6e4e2e1b54b4a2fc'),
    'sqlite.jar': ('org.xerial:sqlite-jdbc:3.50.3.0', 'a3f53a2aa15ae9425a9e793bbe9c8e5288febeb4b65ef5c1a4e80d4c2045cf08'),
    'slf4j-api.jar': ('org.slf4j:slf4j-api:2.0.17', '7b751d952061954d5abfed7181c1f645d336091b679891591d63329c622eb832'),
    'slf4j-nop.jar': ('org.slf4j:slf4j-nop:2.0.17', '3716f83649ec66161a2edefd4f49df34d1dd1c51cdcf941996c6987260f0a829'),
}
EXISTING = {
    'ModelGatewayTest', 'ReasoningPolicyTest', 'ScheduleClockTest', 'BudgetStateTest',
    'ActivityPermitTest', 'PaidActivitySessionTest', 'ActionAdmissionStoreTest', 'NativePlayerStoreTest',
    'SqliteCompanionStoreTest', 'ActionArbiterTest', 'SkillRunnerTest', 'ClaimRegistryTest',
    'PlayerAccessTest', 'WorkSetupTest', 'ActionAdmissionTest', 'ActionRecordTest',
    'SkinFilesTest', 'BlockPerceptionTest', 'InventorySummaryTest', 'CanonicalRosterSkinsTest',
}
MODULES = ('companion-core', 'companion-scheduler', 'companion-persistence', 'companion-model')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--dependencies', type=Path, required=True)
    parser.add_argument('--baseline-ref', help='Compile the original gateway from this local Git ref and require both reproducers to fail')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    jdk, jars = args.jdk.resolve(), args.dependencies.resolve()
    suffix = '.exe' if os.name == 'nt' else ''
    java, javac = jdk / 'bin' / ('java' + suffix), jdk / 'bin' / ('javac' + suffix)
    version = subprocess.run([str(javac), '-version'], capture_output=True, text=True, check=True)
    if not version.stdout.strip().startswith('javac 21'):
        raise ValueError('JDK 21 is required')
    for name, (coordinate, checksum) in DEPENDENCIES.items():
        if hashlib.sha256((jars / name).read_bytes()).hexdigest() != checksum:
            raise ValueError(f'Missing or mismatched dependency: {coordinate} as {name}')
    out = root / 'build/support-recovery' / ('baseline' if args.baseline_ref else 'verified')
    # Only this runner-owned output subtree is recreated, so stale class files cannot be counted.
    if out.exists():
        shutil.rmtree(out)
    classes = out / 'classes'; classes.mkdir(parents=True)
    sources = [p for module in MODULES[:3] for p in (root / module / 'src/main/java').rglob('*.java')]
    gateway_path = 'companion-model/src/main/java/dev/kindred/model/ModelGateway.java'
    if args.baseline_ref:
        original = subprocess.run(['git', 'show', f'{args.baseline_ref}:{gateway_path}'], cwd=root, capture_output=True, check=True).stdout
        gateway = out / 'original/dev/kindred/model/ModelGateway.java'; gateway.parent.mkdir(parents=True); gateway.write_bytes(original)
        sources.append(gateway)
        tests = [root / 'companion-model/src/test/java/dev/kindred/model/SupportBatch03GatewayRegressionTest.java']
    else:
        sources += [root / gateway_path, root / 'companion-model/src/main/java/dev/kindred/model/ReasoningPolicy.java']
        tests = [p for module in MODULES for p in (root / module / 'src/test/java').rglob('*.java') if p.stem.startswith('SupportBatch') or p.stem in EXISTING]
        if len([p for p in tests if p.stem.startswith('SupportBatch')]) != 22:
            raise ValueError('Expected 22 reconstructed support test classes; review runner selection')
        if {p.stem for p in tests if not p.stem.startswith('SupportBatch')} != EXISTING:
            raise ValueError('An expected original regression class is missing')
    sources += tests
    arguments = out / 'sources.txt'
    arguments.write_text('\n'.join('"' + str(p).replace('\\', '/') + '"' for p in sorted(sources)), encoding='utf-8')
    classpath = os.pathsep.join(str(jars / name) for name in DEPENDENCIES)
    subprocess.run([str(javac), '--release', '21', '-encoding', 'UTF-8', '-cp', classpath, '-d', str(classes), '@' + str(arguments)], cwd=root, check=True)
    for module in MODULES:
        resources = root / module / 'src/main/resources'
        if resources.is_dir(): shutil.copytree(resources, classes, dirs_exist_ok=True)
    groups = ['reproducers'] if args.baseline_ref else ['support', 'original']
    for group in groups:
        command = [str(java), '-Djava.awt.headless=true', '-jar', str(jars / 'junit.jar'), 'execute', '--class-path', str(classes) + os.pathsep + classpath, '--reports-dir', str(out / group), '--details=summary', '--disable-banner']
        if args.baseline_ref:
            command += ['--select-class', 'dev.kindred.model.SupportBatch03GatewayRegressionTest']
        else:
            command += ['--scan-class-path', '--include-classname', r'.*\.SupportBatch.*Test' if group == 'support' else r'.*Test', '--exclude-classname', r'.*\.SupportBatch.*Test' if group == 'original' else r'(?!)']
        result = subprocess.run(command, cwd=root, timeout=120)
        if args.baseline_ref:
            # Exit alone is insufficient: require exactly the two expected assertion failures.
            import xml.etree.ElementTree as ET
            cases = [case for path in (out / group).glob('*.xml') for case in ET.parse(path).iter('testcase')]
            if result.returncode != 1 or len(cases) != 2 or any(case.find('failure') is None for case in cases):
                raise ValueError('Baseline did not reproduce both expected assertion failures')
        elif result.returncode:
            return result.returncode
    return 0


if __name__ == '__main__':
    try: sys.exit(main())
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        print(f'Support validation failed: {error}', file=sys.stderr); sys.exit(2)
