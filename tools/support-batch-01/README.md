# Offline support tools (F-22)

Reconstructed on 24 October 2026 from the recorded support-batch scope. Python 3.10+ and its standard library are sufficient. These commands never call a model, use credentials, launch Minecraft, run Gradle or change tracker checkboxes. Results apply only to the inputs selected.

Run from the repository root:

```bash
python3 tools/support-batch-01/support.py summary tools/support-batch-05/evidence/support.xml
python3 tools/support-batch-01/support.py compare tools/support-batch-05/evidence/regression-before.xml tools/support-batch-05/evidence/regression-after.xml
python3 tools/support-batch-01/support.py repeat first-run/ second-run/
python3 tools/support-batch-01/support.py links tools/support-batch-01/README.md tools/support-batch-05/HANDOFF.md
python3 tools/support-batch-01/support.py tracker
python3 tools/support-batch-01/support.py manifest
python3 tools/support-batch-01/support.py jar companion-server-adapter/build/libs/kindred-mc-0.1.0-SNAPSHOT.jar
python3 tools/support-batch-01/support.py preflight
python3 tools/support-batch-01/support.py bundle build/support-evidence.zip tools/support-batch-05/HANDOFF.md tools/support-batch-05/evidence/support.xml tools/support-batch-05/evidence/original.xml
python3 -m unittest discover -s tools/support-batch-01 -v
```

`--root PATH` precedes the subcommand when checking another checkout. Exit codes: 0 = selected checks pass; 1 = failures, missing/skipped regressions or audit findings; 2 = invalid/unreadable input. A nonzero result is not an instruction to edit production code or mark a gameplay gate complete.

## Report analysis

`summary` counts individual JUnit test cases, distinguishes errors/failures/skips, and audits declared suite counts. It rejects empty input, invalid durations, XML entities and duplicate test identities. Supply repeated runs separately rather than combining them as a single run. `compare` reports fixed/regressed/new/missing/newly-skipped cases. `repeat` distinguishes pass/fail flakes, consistently failing cases and incomplete coverage. Durations are the sum of testcase durations, not wall time or a benchmark.

## Evidence bundles

`bundle` accepts explicit repository-relative files, never directories. It excludes hidden/credential names, databases, raw logs and unapproved file types. It rejects absolute/traversing paths and file/directory symlinks, limits input size, removes XML console output/properties/failure details, and rejects recognizable credentials in the remaining text. ZIP members have deterministic names/timestamps and a SHA-256 checksum manifest. Do not modify inputs concurrently during packaging. Credential detection is a limited heuristic; review selected evidence before sharing it.

## Static audits

Markdown checking covers inline relative links and Markdown heading anchors (including repeated headings), not reference-style links or remote sites. Tracker checking requires unique checklist IDs and dates/evidence links for completed rows; it cannot determine whether the evidence satisfies the feature. Manifest checking compares pinned properties, Java major and wrapper version/checksum. JAR checking inspects archive names, size, Fabric metadata and declared nested JARs; it does not load classes or test Minecraft compatibility. Preflight reports missing project files and unfinished edit markers and includes manifest consistency.

The retained [handoff](../support-batch-05/HANDOFF.md) describes validation scope and unresolved behavior.

## CI evidence

The [build workflow](../../.github/workflows/build.yml) runs these tools' tests, manifest pin checks and tracker syntax checks in a separate Python-only job. Java/Minecraft validation continues in its existing build job. Reports are summarized and uploaded even when a command fails; an unsuccessful command is never converted into success by a passing report.

`ci_report.py` reuses the report parser above. It expects nonempty JUnit reports from core, scheduler, persistence, model and testkit, plus the adapter's native GameTest report. Missing/invalid reports, no passed cases in a required group, test failures/errors or unsuccessful command outcomes produce exit code 1. Optional skipped tests are counted separately and do not count as passes. Add a module to `JUNIT_MODULES` when a new ordinary test suite becomes required.

Every run writes `build/ci-evidence/summary.json` and `summary.md`, including the checked-out commit, tracked-dirty flag, workflow run/attempt when available, command outcomes and separate suite counts. In Actions it also appends to the job summary. The `kindred-validation-<run>-<attempt>` artifact retains these files and the XML reports for 14 days. It excludes generated worlds, player databases and raw server logs. The development JAR is uploaded only after the build job's checks succeed.

For a local run, start with clean build outputs, execute the checks, then pass their **actual** outcomes:

```bash
python3 tools/support-batch-01/ci_report.py \
  --build-outcome success --testkit-outcome success --native-outcome failure
```

The example represents a failed native run and exits 1. Unspecified outcomes default to `not_run`. This command does not run tests or prove report freshness: outside the fresh CI checkout and `clean build`, old files must be removed before testing. It intentionally ignores historical XML under `tools/`. Test reports are the source of counts, not `release-manifest.json`; the script never updates the manifest or marks gameplay features complete. These are single-run automated results, not two consecutive passes, client evidence or an earned playthrough.

Validation and integration notes: [F-22 CI evidence](../../docs/evidence/F-22-ci.md).
