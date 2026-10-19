# E-15 — Serialized JDK 21 regression stabilization

Date: 2026-10-19 EDT
Agent / branch: Codex / main (uncommitted; repository has no committed baseline)
Build: uncommitted source tree inherited from Cursor; tested artifact and report hashes below.
Status: two consecutive serialized 182/182 suites passed on the current E-15 source after E-14 outward-expedition and night-return checks were changed to wait for native activity to settle. The portal fixture now also observes a nearby Blaze after actual arrival and verifies a durable native-attack kill. E-15 acceptance remains incomplete.

## Scope and implementation

This continues the interrupted 179-test gate, preserving the existing craft receipt,
step-down and waypoint changes. No product permissions or native gameplay are enabled.

Changed files: `NativePlayerSkills.java`, `NativeMovementGameTests.java`,
`NativeExpeditionGameTests.java`, `NativeFarmGameTests.java`, `NativePaidSkillsGameTests.java`,
`GameTestPacing.java`, the GameTest-only `fabric.mod.json`, and the tracker,
validation, manifest and E-15 evidence documentation.

- The farm fixture places the crop beside the player's collision box and waits for
  harvest completion before stopping. Its seed counts, crop age, verified ledger
  and output checks remain enforced; failure messages include activity state.
- The outward-expedition fixture selects the existing idle preference after its
  explicit traversal dispatch so another autonomous leg cannot replace the transient
  completion status before assertion. It still checks the generated routine
  candidate, paid traversal completion, expedition state and breadcrumb creation.
- Startup padding uses `expectMinDuration(48)`. Empty runnable tasks execute in the
  same tick and did not provide the intended delay.
- Bounded traversal now steers toward the actual destination, slows before arrival
  and releases forward input while awaiting landing. Previously a fixed heading
  could carry a jumping player past the destination and keep driving away.
  Vertical distance is included in progress so native ascent/descent counts as
  movement. Step-down retains forward input until the player clears the upper
  ledge; braking on horizontal proximity alone can strand its collision box there.
  Corridor, activity, cancellation and arrival bounds remain enforced.
- The GameTest-only initializer gives `TestServer` ticks a minimum 5 ms duration.
  Vanilla's test server otherwise fast-forwards hundreds of ticks before asynchronous
  SQLite startup/identity work gets CPU time. This is 10 times faster than a normal
  50 ms tick and keeps the original assertion deadlines. It does not change production
  tick scheduling, block on an IO result, or ship in the distributable mod.

Dependencies: manifest-pinned Minecraft 1.21.1, Fabric Loader 0.19.5,
Fabric API 0.116.17+1.21.1, Yarn 1.21.1+build.3, OpenJDK 21.0.2, Gradle 8.14.3.
No dependency, schema, saved-key or gameplay policy changes.

## Acceptance evidence

| Criterion / check | Scenario | Result | Evidence |
| --- | --- | --- | --- |
| Baseline full native regression | Serialized JDK 21 suite | 175/179; startup x2, planting and step-up failed | Local `codex-gametest-1.log` |
| Fixture corrections, before pacing | Serialized JDK 21 suite | 177/179; scoped-log runtime startup and night-return identity establishment failed | Local `codex-gametest-2.log` |
| Pacing, before traverse steering correction | Serialized JDK 21 suite | 177/179; step-up stalled and waypoint arrival failed | Local `codex-gametest-3.log` |
| Steering correction, before ledge-clearance correction | Serialized JDK 21 suite | 178/179; step-down failed | Local `codex-gametest-4.log` |
| Ledge correction, before isolating the explicit outward leg | Serialized JDK 21 suite | 178/179; subsequent autonomous outward work replaced the expected completion status | Local `codex-gametest-5.log` |
| Two consecutive full native passes | Same sources, same world directory, no intervening daemon stops or cleanup | **179/179 twice**, 34.19 s then 29.88 s | [Pass 1](artifacts/E-15-2026-10-19/pass-1.xml), [pass 2](artifacts/E-15-2026-10-19/pass-2.xml); local `codex-gametest-6.log` / `codex-gametest-7.log` |
| Current-source full native gate | Same E-15 source after observed-Blaze candidate and E-14 activity-settling changes; serialized JDK 21 runs | **182/182 twice**, zero failures or errors | [Pass 7](artifacts/E-15-2026-10-19/pass-7-blaze-candidate-and-e14-stabilization.xml) SHA-256 `739FFC1CB56FCCEEDA3EC893797A1765A8C4E07A4A414AD4E83848B74A871295`; [Pass 8](artifacts/E-15-2026-10-19/pass-8-blaze-candidate-and-e14-stabilization.xml) SHA-256 `8AEA252D6766BEA069C639A1A26E4F3BF8917D28348C75697D0CBAED6B1ACC90` |
| Full build and console demos | Wrapper, JDK 21 | Build successful; contract demo 5/5 and personality demo 4/4 | 2026-10-19 current-source run: 39 tasks, 9 executed / 30 up-to-date; Gradle exit 0 |
| JUnit report aggregate | Existing reports consumed by the successful build/check | 329 tests, zero failures/errors; up-to-date results reused | Module `build/test-results/test/TEST-*.xml` |
| Distributable isolation | `jar tf` on final remapped JAR | No GameTest classes, descriptor or pacing initializer | Artifact hash below |

Environment: Windows x64. `JAVA_HOME` explicitly set to
`C:\Users\matmu\.jdks\openjdk-21.0.2`; `org.gradle.java.home` was already pinned.
The default PATH points at Java 24, so relying on the Gradle property alone does
not select the wrapper JVM. One runner only; no `gradlew --stop`, world deletion,
daemon contention or session-lock cleanup during these runs.

```powershell
$env:JAVA_HOME='C:\Users\matmu\.jdks\openjdk-21.0.2'
.\gradlew.bat :companion-server-adapter:runGametest --no-daemon --no-parallel --console=plain
.\gradlew.bat build :companion-testkit:run :companion-testkit:runPersonalityDemo --no-daemon --no-parallel --console=plain
```

Tested distributable: `companion-server-adapter/build/libs/kindred-mc-0.1.0-SNAPSHOT.jar`.
SHA-256: `F190AF5EC33041C1F175907F9F55C1102104E9700F43514384E6BA8660A636E2`.
Final `NativePlayerSkills.java` SHA-256:
`6b8b52852f96280cdfe43d326c53dd009bc9c192f6499ae4356d8560bd9d6a0c`.

Report SHA-256:
- Pass 1: `4e97770ec204728928ce5d4822795d908733a8723a0635c1d148b3c7b9a27a3e`.
- Pass 2: `62e12d920ac1c616ac8a32a51d5a9b5835e42a110666022e5022547b0daec2ed`.

Preceding failure reports are retained in the same artifact directory as
`baseline.xml`, `run2.xml`, `run3.xml`, `run4.xml` and `run5.xml`.

These are native fixtures with granted inventory, prepared terrain and repositioning.
They are not earned playthroughs, ordinary-client sessions, or performance qualification.
Existing synchronous admission decorators remain fixture-only; the pacing change
does not qualify production IO latency.

## Remaining limitations and handoff

After the E-15 source updates, the vanilla portal-entry/return restart and Nether hazard observation fixture, the observed-Blaze native-attack fixture, owned-frame progression assembly, and assigned-patch wart gather fixture passed. The frame fixture consumes exactly 14 owned obsidian across 14 admitted placements. The wart fixture verifies a scoped native gather receipt, while explicitly using prepared terrain and inventory. The pure progression test rejects inventory-only Nether access. The observed Blaze is a controlled fixture target and the test verifies a real durable native attack; it does not synthesize or claim a rod. Two current-source full suites passed 182/182 after E-14 activity-settling changes. The earlier two 179/179 passes describe a prior source version.

E-15 remains unchecked. Remaining acceptance: earn obsidian, traverse a real portal, complete hazard-aware Nether resource trips including verified Blaze Rod acquisition and naturally sourced Nether Wart, qualify native player mechanics under F-25b, and pass ordinary-client and earned-playthrough gates. Production portal travel stays disabled.

The manifest records the current-source 182/182 consecutive passes and the later E-14 timing failure. The E-15 checkbox stays open.

