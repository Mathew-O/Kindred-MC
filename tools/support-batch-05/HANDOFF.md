# F-19/F-22 — Reconstructed support integration

Date: 24 October 2026 (America/New_York)
Agent / branch: Codex / `codex/rebuild-support-integration`
Base: `fa8c3ac` on `claude/modest-faraday-r3spro`, which contains the earlier recorded `abde165` base.
Status: reconstructed support slice validated; parent F-19/F-22 and gameplay/release gates remain open.

## Scope and provenance

The original unpushed tip `e7415b8a7a60b4e425f8149e8994c521f8f3e7af` was unavailable. This is a fresh implementation of the recovered task scope, not a replay or byte-for-byte restoration of those ten commits. The original 196-Java/34-Python claim is historical and is not reused as current evidence.

Only one production file changes: `companion-model/.../ModelGateway.java`. The rest is new tests, offline tooling, evidence and supporting docs/manifest. Provider transports, gameplay, navigation, team systems, schema and native saves are unchanged. The development branch's later work is preserved by using its tip captured at checkout as the base. During final review the upstream branch advanced to `b04114d` (team hand-overs); its five changed paths do not overlap this support slice. This recovery is published separately from both upstream and `main`. Two pre-existing untracked files in the checkout (`SeedStructureLocator.java` and `docs/active-work-cursor-parallel.md`) are outside this change.

## Fixes

1. Caller cancellation previously cancelled the dependent `handle` stage, suppressing accounting/capacity cleanup even after the provider completed. A separately owned result future now leaves provider-terminal cleanup independent of caller cancellation. Capacity and conservative token reservations remain held while the provider can still run/bill. Terminal success, failure or provider cancellation releases capacity exactly once; a late result cannot replace a cancelled caller result.
2. Extreme positive provider token counts could overflow `int` counters and clamp to zero, reopening budget admission. Intermediate arithmetic now uses `long`; diagnostics saturate at `Integer.MAX_VALUE`, preserving the public `int` API and conservatively blocking further daily admission. Normal reservation/refund arithmetic is unchanged. An extreme provider count is an accounting robustness case, not a measured gameplay failure.

## Rebuilt task coverage

- Batch 01: standard-library offline test summaries, regressions, repeated-run analysis, evidence ZIPs, Markdown links, tracker/evidence audits, pinned manifest checks, JAR metadata checks, preflight and unified CLI/documentation.
- Batch 02: gateway capacity/cancellation/accounting; DST/rollback schedules; budget conservation; owner-dispatched paid callbacks; actual SQLite ledger/budget reopen and native identity/save receipts. No live provider or transport integration is claimed.
- Batch 03: both gateway fixes plus stale arbiter leases, terminal runner behavior, checkpoint validation/ownership, claim expiry/fencing, access immutability, work geometry/policy, admission acknowledgement ordering and action-record transition sequences.
- Batch 04: skin naming, size/header/corruption, arm-mode decisions and input byte/hash ownership; section boundaries/order/clipping; perception budgets/short-circuits/retargeting; observation freshness/nearest lookup; snapshot copies; admission capacity/identity and skill/proposal boundaries.
- Batch 05: integrated checks, baseline reproducers, retained sanitized XML, exact testcase totals, source hashes and this handoff.

## Acceptance evidence

Environment: Linux x64, OpenJDK 21.0.2, JUnit Jupiter 5.11.4 (console platform 1.11.4), SQLite JDBC 3.50.3.0, SLF4J 2.0.17. Native pins remain Minecraft 1.21.1/Fabric as recorded in the manifest; no native run was performed.

| Check | Result | Retained evidence |
| --- | --- | --- |
| Two reproducers on unchanged `fa8c3ac` gateway | Both fail with expected assertions | [Before](evidence/regression-before.xml) |
| Same two reproducers after fixes | 2 pass | [After](evidence/regression-after.xml) |
| 22 reconstructed support classes | 64 pass; 0 skips | [Support XML](evidence/support.xml) |
| 20 selected original regression classes | 145 pass; 0 skips | [Original XML](evidence/original.xml) |
| Python offline tools | 34 pass; 0 skips | [Test source](../support-batch-01/test_support.py) |
| Real file and directory symlink bundle rejection | Pass, no permission skip | Python tests 17/18 |
| Report totals, before/after identity comparison and source hashes | Audited from XML | [Results](evidence/results.json) |
| Manifest consistency, tracker syntax and changed-doc links | Pass | Offline CLI commands below |
| Full Gradle/Fabric/provider/native/live validation | Not completed | No new release qualification claimed |

The wrapper download failed in this container with a Java network error. A separately fetched, checksum-verified Gradle distribution also did not complete a build. The bounded fallback compiles all current core/scheduler/persistence production sources and only ModelGateway/ReasoningPolicy from model, copies real migration resources and runs the selected JUnit classes with pinned dependency hashes. It deliberately excludes provider SDK transports and native adapter code. The initial standalone run omitted migration resources; that runner setup was corrected before the retained passing run.

## Reproduce

Prefer the project's JDK 21 Gradle wrapper in an environment with working dependencies. For the bounded offline fallback, supply the four jars named in [run_checks.py](run_checks.py) with their exact pinned coordinates/hashes; it never downloads anything:

```bash
python3 tools/support-batch-05/run_checks.py --jdk /path/to/jdk21 --dependencies /path/to/pinned-jars --baseline-ref fa8c3ac
python3 tools/support-batch-05/run_checks.py --jdk /path/to/jdk21 --dependencies /path/to/pinned-jars
python3 -m unittest discover -s tools/support-batch-01 -v
python3 tools/support-batch-01/support.py summary tools/support-batch-05/evidence/support.xml tools/support-batch-05/evidence/original.xml
python3 tools/support-batch-01/support.py compare tools/support-batch-05/evidence/regression-before.xml tools/support-batch-05/evidence/regression-after.xml
python3 tools/support-batch-01/support.py manifest
python3 tools/support-batch-01/support.py tracker
```

The fallback runner recreates only its owned `build/support-recovery/verified` or `baseline` subtree, ensuring stale classes are not counted. Baseline mode requires exactly the two expected assertion failures and returns success only when both reproduce. All callbacks, times, IDs and fake providers are local fixtures. Persistence tests use temporary SQLite files and close/reopen the actual ordered store. No Minecraft saves, player databases, keys or provider data are retained in the evidence.

## Test class audit

| Class | Passing tests |
| --- | --- |
| `SupportBatch03AccessTest` | 2 |
| `SupportBatch03ActionRecordTest` | 3 |
| `SupportBatch03AdmissionTest` | 2 |
| `SupportBatch03ArbiterTest` | 2 |
| `SupportBatch03CheckpointTest` | 2 |
| `SupportBatch03ClaimsTest` | 3 |
| `SupportBatch03RunnerTest` | 3 |
| `SupportBatch03WorkSetupTest` | 3 |
| `SupportBatch04AdmissionBoardTest` | 2 |
| `SupportBatch04ObservationsTest` | 3 |
| `SupportBatch04PerceptionTest` | 4 |
| `SupportBatch04SectionTest` | 3 |
| `SupportBatch04SkillProposalTest` | 2 |
| `SupportBatch04SkinTest` | 5 |
| `SupportBatch04SnapshotTest` | 2 |
| `SupportBatch02ModelGatewayTest` | 6 |
| `SupportBatch03GatewayRegressionTest` | 2 |
| `SupportBatch02LedgerTest` | 2 |
| `SupportBatch02NativeIdentityTest` | 2 |
| `SupportBatch02BudgetTest` | 4 |
| `SupportBatch02PaidSessionTest` | 3 |
| `SupportBatch02ScheduleTest` | 4 |

## Remaining limitations and next dependency

- `resetDailyBudgets()` with in-flight calls has unresolved period ownership: late reconciliation can land in the newly reset counters. This recovery does not change reset semantics; specify period attribution before fixing it.
- `SkinFiles.Skin.png()` exposes its stored array, and the public record constructor can pair arbitrary bytes/hash. The accepted-input clone is tested; accessor hardening is a separate production change. Explicit `_slim` suffixes also win before the legacy-height rule, so a legacy `_slim` filename contradicts the “always wide” comment. No skin production changes were authorized by the recovered narrow scope.
- Extreme `SectionKey` and `BlockObservation` distance tests characterize integer subtraction overflow at full integer extrema. These are intentionally outside Minecraft coordinates and do not establish a real gameplay-distance defect. They stay qualified rather than triggering unrelated production edits.
- AdmissionBoard's behavior for an existing player with a changed principal/chunk request remains unchanged; tests repeat identical identities and verify capacity release. New semantics need a separate contract.
- Python credential detection and Markdown syntax coverage are bounded as documented. The JAR checker was tested on synthetic archives; no new production mod JAR was built or qualified.
- Selected ordinary tests do not establish a full-core, provider runtime, native GameTest, ordinary-client or earned-playthrough pass. No parent checkbox or release gate is advanced.

Next dependency: review/merge this support branch into the intended development branch, then run the normal full Gradle and native/client qualification appropriate to the receiving branch.
