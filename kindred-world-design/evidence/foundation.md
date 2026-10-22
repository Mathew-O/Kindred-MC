# E-08 / E-29 / E-30 / E-31 — isolated pure foundation evidence

Date: 22 October 2026.
Agent / branch: Codex / `codex/world-design-foundation`.
Build: uncommitted foundation source, based on `4cb2b330e4b06c66ff5cde6b03008793bc91f3c2`.
Status: bounded foundation only; parent IDs remain open.

## Scope and implementation

User-visible behavior: standalone local workshop planning demo, static checks,
material deficits, exact artifact digest, preliminary support graph.
Changed modules/files: only `kindred-world-design/**`; no existing file edited.
Dependencies: existing pinned Gradle wrapper and installed JDK 21. No downloaded
library dependency. No runtime/core/store integration.
Schema/configuration: local manifest and standalone build only; no migration or
root release configuration change.

## Acceptance evidence

| Bounded requirement | Scenario | Result | Retained evidence |
| --- | --- | --- | --- |
| E-29 pure generation foundation | Determinism; 128 rotation/site/seed combinations | Pass | `foundation-tests.txt` |
| E-08 static plan boundaries | Unknown/occupied/protected cells, clearance, access, ground, scope | Pass | `foundation-tests.txt` |
| E-08 material foundation | Exact counts, reserved stock, deficits, invalid/large values | Pass | `foundation-tests.txt` |
| E-30 exact artifact binding only | Reordered input stable; changed geometry/ports/clearance/revisions differ | Pass | `foundation-tests.txt` |
| E-08 preliminary dependency foundation | Complete ordered adjacency DAG; floating component rejected | Pass | `foundation-tests.txt` |
| Authority boundary | Every compiled plan remains execution-unqualified | Pass | `foundation-tests.txt`, `workshop-preview.txt` |
| Documentation consistency | Markdown local links resolve; manifest parses; pins match root manifest | Pass | Commands below / local manifest |
| E-31 native construction/recovery | Native, clients, restart, earned acquisition | Not run | Outside foundation scope |

Environment: Windows, OpenJDK 21.0.2+13-58 (x64), pinned Gradle 8.14.3.
No Minecraft/Fabric process or client launched.

Commands executed from the repository root (JDK 21 set in `JAVA_HOME`):

```powershell
.\gradlew.bat -p kindred-world-design --offline build demo
```

Result: BUILD SUCCESSFUL; 21 named pure scenarios passed, including 128 transformed
site/rotation/seed cases. The synthetic demo produced 99 proposed blocks, 74
cobblestone and 25 oak planks. From 70 cobblestone with 5 already reserved and
20 planks, deficits were 9 cobblestone and 5 planks. Static validation passed;
native qualification remained false. The suite uses a dependency-free executable
harness, not JUnit or Minecraft GameTests.

Fixture: synthetic flat bounded site, origin `(0,64,0)`, seed `734922`, rotations
0..3; additional sites `(−200,80,−300)`, `(127,−20,−129)`, `(30000,100,30000)`;
variation seeds 0..7. Each scenario constructs fresh immutable values; no external
world, player, database, credentials, or private chat is used.

Generated reports: `build/reports/foundation-tests.txt` and
`build/reports/workshop-preview.txt`. Sanitized retained copies are beside this
record. Demo design artifact SHA-256:
`e85f8a2a42b5ca03fc022b911e1254943bca80946f55e0496dbabe09c26c867e`.
This is a pure design hash, not a server JAR or native gameplay receipt.
Standalone planning JAR SHA-256:
`9533741a09f58b633122958bd57d358822cbc672f9f42ea4ee33d2616330e98b`.
The generated JAR remains ignored build output and is not published as a server mod.

Additional checks: local Markdown links resolve; `project-manifest.json` parses
with PowerShell `ConvertFrom-Json`; JDK reports `21.0.2`. Folder-only staged diff
must pass `git diff --cached --check` before commit. Existing root runtime suites
were not rerun because no integrated module changed.

## Remaining limitations and handoff

No Minecraft server or client started. No native fixture, persistence/restart,
death/conservation, zero-human, model-provider, aesthetic, or earned construction
result is claimed. `executionQualified()` is always false. Direct item budgets
exclude recipes, fuel, tools, food, transport, and temporary supports.

Next: compile native worker stance/reach and placement contexts under E-08, with
existing authority and action reconciliation; then qualify native construction
and recovery under E-31/F-25b/F-05/F-13. Semantic comparison/reservations under
E-30 and full architectural grammar/taste under E-29 remain open.

User documentation: `README.md`, `SPECIFICATION.md`, `ACTIVE-WORK.md`.
Manifest: `project-manifest.json`; root manifest unchanged by explicit user scope.
Canonical tracker: unchanged and still authoritative; no feature box checked.
