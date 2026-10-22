# Kindred world-design foundation

An isolated Java 21 planning project. The [full proposed specification](SPECIFICATION.md)
describes the target system; this folder implements the first pure planning slice.
It has no Minecraft, provider, SQLite, or third-party runtime dependencies.

Existing files and the root Gradle project are unchanged. This folder is not
included in the root build or bundled in the server JAR. It uses the repository's
existing Gradle 8.14.3 wrapper with a separate `settings.gradle`.

## Run

From the repository root in PowerShell, with JDK 21 installed:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
.\gradlew.bat -p kindred-world-design --offline build demo
```

The path above is an operator placeholder, not a repository setting. If the
pinned wrapper distribution has not been cached, run once without `--offline`.
No external library download is needed. The source/target/toolchain is Java 21;
using another Gradle launch JVM does not change that requirement.

`build` runs `foundationTest` through `check`. This dependency-free executable
scenario suite throws on failure and exits nonzero. Gradle's ordinary JUnit test
task is disabled; the evidence is the named foundation suite,
not a claimed JUnit or native-test count.

Outputs (ignored by Git):

- `build/reports/foundation-tests.txt`: scenario results.
- `build/reports/workshop-preview.txt`: synthetic planning demo.
- `build/libs/kindred-world-design-0.1.0-foundation.jar`: standalone Java artifact.

`demo` always writes its report. `run` prints the same synthetic report and can
take an optional output path with `--args`. Neither command contacts a server.

## Implemented behavior

| Component | Foundation scope |
| --- | --- |
| Immutable records | Bounded positions/site, observation provenance, brief, geometry, ports, semantic revisions |
| Workshop generator | Seeded 5x5 shell, two-block entrance, interior clearance, three-block path, four rotations |
| Static validator | Unknown/occupied/protected targets, scope, ground support, required air, flat two-cell-high port connectivity, stale revision/world rejection |
| Material planner | Exact direct block-item counts minus existing reservations; deficits only |
| Artifact digest | Deterministic SHA-256 binds geometry, phases, ports, clearance, brief, revisions, generator |
| Support compiler | Preliminary support-adjacency dependencies, deterministic phase ordering, floating-component rejection |
| Semantic revisions | Stable node identities, explicit tombstones, bounded typed relationships, immutable accepted revisions |
| Layout planner | At most three deterministic, known-safe flat-site candidates with protected features and corridors excluded |
| Reference importer | Bounded data-only reviewed components; rejects executable/block-entity payloads and marks missing annotations |
| Lifecycle/inspection | Explicit acceptance/execution transitions and observation-based drift classification that never assigns provenance |
| Construction plan | Phase-scoped actions with exact direct item needs, support dependencies, expected receipts, and explicit missing capabilities |
| Preview/proposals | Bounded diagnostic overhead/diff previews and strictly bound optional suggestion envelopes |
| Novelty policy | Deterministic semantic fingerprints with bounded exclusive in-memory reservations; durable persistence remains external |
| Recovery ledger | In-memory action admission/receipt state machine that fences retries until uncertain outcomes are reconciled |
| Demo/tests | Synthetic observations and stock; no earned gameplay evidence |

The pilot has 99 proposed blocks: 74 cobblestone and 25 oak planks for the even-seed
variant. Its roof palette changes with seed parity. This is small deterministic
variation, not semantic originality. The shell has no placed workstation, chest,
door block, lighting, peaked roof, or functionally qualified workshop interior.

The compiler always returns `executionQualified() == false`. It does not prove
worker stance, reach, interaction face, access during construction, scaffolding,
native block behavior, policies, activity, or conservation. Static validity is
not construction feasibility. Insufficient stock is reported, not manufactured.

## Code map

```text
src/main/java/dev/kindred/design/
  model/DesignModel.java           immutable pure values
  planning/WorkshopGenerator.java  deterministic pilot grammar
  planning/MaterialPlanner.java    direct item deficits
  planning/DesignDigest.java       exact artifact binding
  planning/SupportCompiler.java    preliminary dependency graph
  validation/StaticValidator.java conservative supplied-fact checks
  demo/DesignDemo.java             synthetic executable example
src/test/java/dev/kindred/design/
  FoundationTests.java            executable failure/boundary scenarios
```

## Integration boundary

Future orchestration belongs in `PlayerRuntime`; core remains free of Minecraft
objects. A server-thread adapter must supply trusted observed facts. Native
skills retain the existing arbiter/runner/admission/checkpoint/cancellation path.
Metadata must use the existing SQLite writer and physical items native saves.
No alternate body controller, inventory, accounting ledger, or persistence writer
is introduced here.

Future work includes native stance/placement compilation, registry-derived
recipes/fuel/tool costs, semantic design graph/style persistence, uniqueness
reservations, authorized reference import, team integration, previews, optional
model tools, and native/restart/ordinary-client/earned construction qualification.

See [active scope and handoff](ACTIVE-WORK.md), [local manifest](project-manifest.json),
and [validation evidence](evidence/foundation.md). The root
[canonical tracker](../docs/feature-spec.md) remains the only completion checklist.
Relevant open IDs: E-08/E-29/E-30/E-31; integration dependencies include
F-25b/F-05/F-13/E-11/F-19. No parent feature is completed by this foundation.
