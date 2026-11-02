# F-22 — CI checks and retained validation evidence

Date: 2026-10-26
Agent / branch: Codex / `codex/ci-validation-evidence`
Base: `a1c08653038c8f2790a779f0a5c81bcbf57c0f37` from `claude/modest-faraday-r3spro`.
Status: support-tool slice locally validated; hosted workflow validation pending. Parent F-22 remains open.

## Scope and implementation

The old workflow did not run the existing Python support tests. Its artifact upload followed the build and native suite under the default success condition, so failed runs lost downloadable test evidence.

- Run the support tests, pinned-version audit and tracker syntax audit in an independent Python job.
- Preserve the Gradle build, contract demo and native suite without suppressing failures or adding automatic retries.
- Summarize current output using the existing support XML parser. Require reports from all five ordinary test modules and the adapter GameTests; retain command outcomes separately from test counts.
- Write JSON and Markdown with the checked-out SHA, tracked-dirty flag and workflow identity. Missing, invalid and entirely skipped suites cannot pass; partial reports cannot conceal a failed command.
- Retain summaries and XML on failure using `always()`, with a 14-day artifact lifetime. Upload no worlds, player saves, SQLite databases or raw server logs. The generated summary has no provider prompts or response text.

Only `.github/workflows/build.yml`, `tools/support-batch-01/ci_report.py`, its new tests, the support README and F-22 documentation are changed. No Java source, gameplay tests, provider integration, schemas or release capability flags are changed. The existing release manifest remains historical evidence, not the input to the CI result.

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| Existing support behavior retained | 34 existing Python tests | Pass |
| Actual pass/failure/error/skip counts | New summary tests using JUnit and nested-suite XML | Pass |
| Required evidence cannot silently disappear | Missing module/native reports, empty/malformed XML, duplicate identities and mismatched counts | Pass |
| A failed command cannot appear successful | Failed, cancelled, skipped and unrun outcomes with passing XML | Pass |
| Optional skips stay distinct | Mixed pass/skip allowed; entirely skipped required group rejected | Pass |
| Historical reports cannot fill absent results | XML outside current build report locations ignored | Pass |
| Failed-run evidence survives | CLI integration in a temporary Git checkout: exit 1, JSON/Markdown retained, real checkout SHA and job summary recorded | Pass |
| Configuration/tracker remain consistent | Existing manifest and tracker checks | Pass |

Local environment: Linux, Python standard library and Git; no Minecraft or provider needed. Commands:

```bash
python3 -m unittest discover -s tools/support-batch-01 -v
python3 tools/support-batch-01/support.py manifest
python3 tools/support-batch-01/support.py tracker
python3 tools/support-batch-01/support.py links tools/support-batch-01/README.md docs/evidence/F-22-ci.md
git diff --check
```

Result: 45 Python tests passed (34 existing, 11 new). Workflow YAML was parsed and its command-outcome references and always-run evidence steps checked locally. No full Gradle build, native suite, live provider or client run was performed for this tooling-only change. No GitHub-hosted run is claimed.

## Integration and remaining work

The commit is based on Claude's current development branch and can be cherry-picked onto it. The only shared tracker edit is the F-22 Active work row; preserve Claude's later tracker entries if that line needs reconciliation. Keep this patch separate from ongoing trading, combat, building and runtime changes.

First integration check: inspect both CI jobs on the pushed commit. Check that a failed native suite still has a `kindred-validation-<run>-<attempt>` artifact with the matching SHA and honest counts. A workflow timeout or runner loss may still prevent artifact retention; `always()` cannot recover an unavailable machine. Known native flakes remain Claude's gameplay work, and this workflow does not retry them into a passing result.

For local use, start with clean output directories and supply actual command outcomes; the summarizer does not authenticate report provenance or compare the report inventory with every source annotation. Add new required modules to its explicit expectation list. Full F-22 performance, installation and diagnostics qualification remains unfinished.
