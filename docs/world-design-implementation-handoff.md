# World design: Kindred integration review and implementation handoff

Date: 22 October 2026. Status: implementation paused at the user's request; source review and implementation plan only.

## Start here

The user abandoned the current implementation attempt and requested a design review, improvements, and a repository handoff for another agent. Preserve the useful foundation, but do not treat the accumulated prototype as a qualified implementation. The next agent should fix its contracts and evidence model before enabling world mutations. No native design dispatcher exists today.

Read [AGENTS.md](../AGENTS.md), the [player contract](../minecraft_player_spec.md), [runtime architecture](player-architecture.md), [building contract](architectural-design.md), and [world-design specification v0.2](../kindred-world-design/SPECIFICATION.md). The [canonical tracker](feature-spec.md) remains the only completion checklist. E-08, E-29, E-30, and E-31 stay open. The stages below are an implementation sequence, not completion claims or another checkbox tracker.

Repository baselines:

- Main inspected at `4cb2b330e4b06c66ff5cde6b03008793bc91f3c2`.
- Original isolated foundation: `codex/world-design-foundation`, `9f84eae13514857861639b8260da1c21a0cad10c`.
- Accumulated unfinished prototype preserved at `98ce3b8` on `codex/world-design-handoff`. This includes the imported folder, subsequent pure-planning additions, root build wiring, and proposed migrations 9–11. It is a review snapshot, not a release.
- This handoff is a later documentation commit on the same branch. No prototype changes have been merged into main by this task. Inspect current branch state before implementing; remote main may advance.

## Recommended product slice

Implement one useful workshop and connected path, built from owned/acquired resources by the existing native players. First prove one simple cube placement with an accepted plan, exact inventory conservation, cancellation during asynchronous admission, and restart reconciliation. Then grow to a useful enclosed phase with a reachable crafting table and storage. Finally demonstrate three players acquiring and delivering materials and finishing the workshop without grants.

Do not use the current 99-block shell as evidence of a usable workshop: it has no workstation, storage, door block, or lighting. A village-scale generator, optional critique, and aesthetic scoring should follow the reliable construction loop. The full specification remains the target; the staged approach supplies measurable prerequisites.

## Current state and gaps found in source

These findings are from static inspection, not newly executed failing tests.

| Area / source | Observed behavior | Required correction before relying on it |
| --- | --- | --- |
| [DesignModel](../kindred-world-design/src/main/java/dev/kindred/design/model/DesignModel.java), [WorkshopGenerator](../kindred-world-design/src/main/java/dev/kindred/design/planning/WorkshopGenerator.java) | Two materials, AIR/SOLID/HAZARD observations, simple ports, and a fixed shell; no exact native states/components or functional program. | Add versioned exact state specifications, trustworthy observation provenance/freshness, functional volumes, typed entrances, and capability requirements. |
| [SemanticDesign](../kindred-world-design/src/main/java/dev/kindred/design/model/SemanticDesign.java) | `withStatus` permits a draft to become accepted directly. Revision copying checks node kind, but allows function changes under the same ID and tombstone revival. Edges have no containment/dependency cycle checks. | Centralize validated acceptance, stable semantic identity, tombstone rules, acyclic containment/dependencies, and hard style inheritance constraints. |
| [LayoutPlanner](../kindred-world-design/src/main/java/dev/kindred/design/planning/LayoutPlanner.java) | Truncates output to three after enumerating sites and running a route search for each. Request lacks world/dimension; preservation/corridor sets constrain footprints but are not passed into route search. It expects an already-solid floor where the shell validator expects an AIR placement target. | Bound total search expansions and deadline, bind world/dimension, share route/footprint constraints, reconcile coordinate/floor semantics, and integrate candidates with geometry generation. Audit ordinary integer arithmetic at extreme coordinates. |
| [ConstructionPlanner](../kindred-world-design/src/main/java/dev/kindred/design/planning/ConstructionPlanner.java), [SupportCompiler](../kindred-world-design/src/main/java/dev/kindred/design/planning/SupportCompiler.java) | Adjacency order and direct item counts only; stance is null, face is absent, and `executionQualified()` is always false. A later phase can be supply-feasible while an earlier phase is short. | Compile supported placement contexts and approaches; validate action DAG and useful stopping boundaries. Distinguish phase supply feasibility from dependency readiness and executable admission. Include temporary access costs. |
| [StoredDesignRevision](../companion-persistence/src/main/java/dev/kindred/persistence/StoredDesignRevision.java), [store design APIs](../companion-persistence/src/main/java/dev/kindred/persistence/SqliteCompanionStore.java) | Hashes are checked for shape, not recomputed from artifact/validation text. JSON is opaque. Parent continuity does not enforce same world/dimension. Immutable row status overlaps mutable binding state. | Use bounded versioned codecs, canonical byte hashes, strict validation reports, consistent lineage identity, and one lifecycle authority. Verify artifacts before storing and loading; reject corruption. |
| [DesignBinding](../companion-persistence/src/main/java/dev/kindred/persistence/DesignBinding.java), store `createDesignBinding` | A binding can be created directly in ACCEPTED at any nonnegative version. Its project need not exist; no validator/policy evidence is required. Several revisions may be accepted independently. | Initial state/version restrictions, real project linkage, evidence-bound acceptance transaction, and a defined supersession/active-revision rule. Acceptance is intention approval, never world authority. |
| [DesignPlacementAction](../companion-persistence/src/main/java/dev/kindred/persistence/DesignPlacementAction.java), store placement APIs | Actions can be appended after acceptance without proving membership in frozen geometry. `side` accepts any identifier; dependencies are only a digest with no traversable graph; no task/receipt binding exists. | Freeze the complete action set and real dependency edges with the artifact before acceptance. Validate face/state/scope/limits, idempotent inserts, and semantic action-to-attempt-to-receipt links. |
| [ProjectLifecycle](../kindred-world-design/src/main/java/dev/kindred/design/planning/ProjectLifecycle.java) | Generic transitions let terminal execution move to PAUSED/BLOCKED/RECONCILING, then READY. Guard uses the old revision status, allowing supersession alongside continued execution. | Test transitions as combined design/execution state; preserve historical completion and revoke execution on cancellation/supersession. New repair work needs a new authorized task. |
| [ExecutionLedger](../kindred-world-design/src/main/java/dev/kindred/design/planning/ExecutionLedger.java) | Independent in-memory ledger; boolean reconciliation produces RECONCILED and permits re-admission even when a mutation may already have happened. | Do not connect this ledger to production. Reuse core `ActionRecord`/`ActionAdmission` and the ordered writer. Explicitly distinguish effect verified, no effect proven, and unresolved; never replay an already-effective action. |
| [InspectionPlanner](../kindred-world-design/src/main/java/dev/kindred/design/planning/InspectionPlanner.java) | Any SOLID cell is PRESENT regardless of material/state; known AIR can allow a repair proposal without ownership/change provenance. No freshness or inspection cooldown. | Match exact state and functional usability; keep geometric satisfaction separate from attribution/conservation. Preserve human edits and unknown provenance; gate repair proposals by policy, budget, cooldown, and resolved prior attempts. |
| [NoveltyRegistry](../kindred-world-design/src/main/java/dev/kindred/design/planning/NoveltyRegistry.java) | Hash includes arbitrary node/style IDs, omits structural geometry/proportions, and stores reservations only in memory. Renaming can evade equality; rotation/recoloring semantics are not evaluated. | Canonical semantic features independent of arbitrary IDs and palette, plus a measured near-copy policy and durable scoped uniqueness reservations. Retain exact artifact hashes separately. |
| [ReferenceImporter](../kindred-world-design/src/main/java/dev/kindred/design/planning/ReferenceImporter.java) | Accepts already-decoded Java records and trusts payload/annotation booleans. Annotated records can report `executionEligible()` without native qualification. | Separate reviewed-reference ingestion from execution qualification. Implement actual bounded parsing/decompression and registry/state checks; preserve attribution/license data. Annotation alone never qualifies mechanics. |
| [ProposalEnvelope](../kindred-world-design/src/main/java/dev/kindred/design/proposal/ProposalEnvelope.java) | Checks lengths/expiry/echoed IDs, but arguments remain arbitrary strings. No per-tool schemas, trusted session ownership, one-use request tracking, inference budgets, or privacy enforcement. | Host in the existing model boundary with strict typed schemas, server-issued correlation context, replay/staleness rejection, and contract budgets. Keep useful local planning independent of providers. |
| [TeamCoordinator](../companion-core/src/main/java/dev/kindred/core/team/TeamCoordinator.java), store `recover` | Stop/death/revoke paths release reservations; startup releases ACTIVE reservations and reopens in-flight tasks. That is insufficient for an uncertain placement that consumed an item. | Hold unresolved materials/claims in a reconciliation state; reopen only after attempt outcome is settled. Audit project states, assignment revisions, membership, and durable acknowledgements before reusing E-11 for construction. |
| Build/schema/documentation | Root build includes design and server adapter bundles it; the original README says isolated. Store now migrates to 11 while release manifest says 8. Local manifest previously described persistence more strongly than its tests support. | Treat this branch as an unfinished integration. Resolve packaging/migration compatibility and regenerate truthful manifests/evidence before release. No production gate follows from compilation. |

## Where Kindred should own each responsibility

| Responsibility | Existing integration point | Design boundary |
| --- | --- | --- |
| Need selection and plan progress | `PlayerRuntime`, `PlayerDecisionLoop`, `TaskDirector`, `TaskPreferences` | Select eligible project work alongside survival; construction is never a separate controller. |
| Bounded world observation | Adapter/server thread; existing perception and scan budgets | Publish immutable observed facts, not every loaded chunk. Native legality may deny hidden work without disclosing unseen contents. |
| Generation / search / previews | `kindred-world-design` workers | Pure inputs; cancellation/deadlines, bounded memory, and deterministic configuration-aware cache keys. No Minecraft objects or thread waits. |
| Immutable artifacts and metadata | `SqliteCompanionStore` ordered writer; content-addressed files if necessary | Persist and verify artifact bytes off-thread before committing durable references. Native saves remain item/world authority. |
| Cooperative assignments / supplies | Existing shared project/task types and `TeamCoordinator` / stock ledger | Bind accepted revision, dependencies, assignee, acknowledged reservations, and settlement state. Never manufacture stock from a planned outcome. |
| Activity and dispatch | `PlayerRuntime.dispatchNativeTask`, `PaidActivitySession`, `ActionArbiter`, `SkillRunner` | Revalidate a server-owned design admission context at every asynchronous return and immediately before mutation. |
| Native placement | [NativePlaceAction](../companion-server-adapter/src/main/java/dev/kindred/fabric/NativePlaceAction.java), `NativePlayerBody.placeBlock` | Reuse policy, claim, inventory evidence and durable admission; extend with exact intended state and design binding. |
| Physical effect evidence / recovery | [ActionAdmission](../companion-core/src/main/java/dev/kindred/core/ActionAdmission.java), `ActionRecord`, existing startup ledger scan | One action ledger; link attempts to immutable semantic action IDs. Completion requires settled receipts plus fresh physical inspection. |
| User controls | `PlayerCommands` and existing administrative access checks | Preview/status, accept where policy requires it, pause/cancel/reconcile. Commands never perform unchecked block writes. |
| Optional creative proposals / speech | `companion-model`, F-19 boundaries, existing social routing | Read/propose only; privacy and listener consent apply to briefs, screenshots, and progress speech. |

Avoid making `PlayerRuntime` much larger. Add a focused adapter service owned by it for observation/admission orchestration, and a focused metadata codec/repository helper that runs exclusively on the existing writer. Do not add another database executor, task scheduler, stock ledger, or body controller. Keep the design module free of Minecraft and persistence dependencies; reconsider the current unused persistence-to-design `api` dependency after selecting shared DTO ownership.

## Accepted artifact and admission contract

Acceptance should freeze a versioned bundle containing world/dimension, project and revision lineage, brief/style/generator/config hashes, observation coverage/version, exact block states, functional volumes/ports, validation report and validator/capability versions, complete action DAG, resource plan, and useful phase boundaries. Large files need length/hash verification and atomic off-thread publication before the metadata transaction refers to them.

An action needs target and expected before-state, exact after-state and item/component predicate, support and clicked face/hit context, stance and approach requirements, permanent/temporary dependencies, bounded claim footprint, expected evidence, and native mechanics capability IDs. A dependency digest supplements actual dependency edges; it cannot replace them. Separate stable semantic action identity from per-attempt ledger UUIDs.

One transaction should verify the bundle, persist its frozen action set/dependencies, bind a real project, and change the accepted revision under optimistic concurrency. New geometry creates a new revision. Supersession must revoke in-flight admission tokens and wait for settlement; frozen actions cannot be appended after acceptance. Store-level API callers cannot declare a validated hash or accepted status without validated evidence and policy authorization.

Runtime sequence for the first supported cube action:

1. On the server thread, resolve current player/controller/team identity and project assignment. Capture body generation, runtime generation, task revision, accepted binding version, setup/policy revision, and stop/cancellation state. Resolve a bounded trusted observation; unknown prerequisites defer work.
2. Off-thread, load and verify the accepted bundle and dependency receipts through the existing writer. Reserve resources using acknowledged existing shared-task accounting. Do not wait synchronously on the server thread.
3. On return to the server thread, reject changed identity/generation, cancellation, supersession, assignment loss, unsettled prior attempts, or stale relevant observations. Inspect actual owned inventory; choose the correct hotbar stack or queue a separate qualified swap/haul task. A stock count is not an inventory slot.
4. Resolve reachable stance and interaction context for the target. Use qualified movement first if necessary. For the initial cube case, reject occupied targets, nontrivial block components, attachments, gravity, fluids, and multi-block behavior until those cases are qualified. Never teleport or paste.
5. Submit through `dispatchNativeTask` and the existing paid activity/runner path with an internal design context. Carry that context through budget preparation, activity acknowledgement, and action admission; checking acceptance only at the initial database read leaves a stale-result race.
6. Immediately before effect, `NativePlaceAction` must revalidate that context in addition to existing body/lease, reach, setup/policy, claims, target and inventory checks. Its current target semantics choose the target itself when replaceable or `target.offset(side)` otherwise; explicitly bind clicked support and final destination to prevent placement in an unintended neighbor. Verify exact after-state, not only block type.
7. Persist receipt and design-attempt linkage through the existing writer. Advance the semantic action only after durable VERIFIED effect/conservation evidence. No-effect attempts may become retryable only under fresh authority; ambiguous attempts hold dependent work/resources. A callback saying the skill ended is not enough.
8. After the phase, perform fresh functional inspection: traversable entrance, usable floor, workstation/storage access and escape route. Report partial/blocked/completed status accurately. Complete the project only after required actions and functional outcomes are verified.

A command accepting arbitrary action coordinates plus a database ACCEPTED flag is not this bridge. The immutable artifact, current authority, and durable effect must all be linked.

## Implementation stages and exit evidence

| Stage / feature ownership | Change and likely files | Evidence required before moving on |
| --- | --- | --- |
| 0 — E-29 baseline repair | Restore a real JDK/build; reproduce prototype defects in focused tests. Reconcile docs/manifests. Claim stable child IDs in the canonical tracker before implementing. | Root and isolated build behavior understood; historical reports distinguished from new results. |
| 1 — E-29 validated artifacts | Design model/codec/validator, store APIs and schema; combined lifecycle rules and atomic acceptance. | Forged hashes, malformed/oversized payloads, unknown states, wrong world/parent, direct ACCEPTED creation, late action append and stale binding races all reject. Round-trip and idempotent acceptance tests pass. |
| 2 — E-08 one native action | Focused runtime design service, `PlayerRuntime`, `NativePlaceAction`, `PlayerCommands`; existing team task and ledger links. | A persisted accepted cube is placed with an owned item. Wrong slot/state/support, revoked policy, stopped actor, lost claim, supersession and delayed acknowledgement prevent effects. Exact inventory/target receipts pass native fixtures. |
| 3 — E-31/F-05/F-13 recovery | Existing action ledger/store recovery, task/reservation settlement and design progress projection. | Crash before dispatch, after mutation before acknowledgement, after receipt before checkpoint, disconnect/death/stop, and duplicate delivery do not duplicate effects or release uncertain stock. Restart tests use paired native saves and SQLite evidence. |
| 4 — E-08/E-29 useful workshop | Program/room/port grammar, capability-aware compiler, registry resource planner, deterministic routes and previews. | Workstation/storage interior usable; build order preserves worker access/escape; shortages produce a useful bounded phase or stable blocker. Qualified temporary access is included in costs and cleanup. |
| 5 — E-11 cooperation | Existing assignments, claims and acknowledged stock reservations; gathering/hauling/building through the same decision loop. | Three players contend safely; support/access corridors can conflict even when targets differ. Death, reservation expiry, lost supplies and tools do not double-spend or silently reassign uncertain work. |
| 6 — E-30/E-29 identity/composition | Semantic canonicalization, durable novelty reservation, persisted style/taste, reviewed reference adapters and scene composition. | Rotated/recolored/renamed near-copies are detected; distinct layouts remain distinguishable. Reference attribution retained; parse bombs/payloads reject; basic local shelter works with no library/model. |
| 7 — F-19/E-29 optional direction | Typed proposal tools, bounded critique/revision loop, privacy and inference limits. | Replay, stale/world-mismatched responses, unknown fields and budget exhaustion reject; outage preserves best valid local candidate. |
| 8 — E-31 qualification | Inspection/repair budgets, diagnostics, native suite plus ordinary-client and earned-world scenarios. | All twelve conditions in specification §27 demonstrated; actual resource acquisition and useful construction without fixture grants. Record seeds, interventions, before/after inventories, receipts, restart and client evidence separately. |

Do not renumber migrations blindly. The draft initializes schema 11; first determine whether any databases have actually been opened on 9–11. If they have, preserve compatibility with forward migrations and tests. If never published, consolidate only as an explicit reviewed development decision. Test upgrades from a real schema-8 fixture, repeated open, failed migration rollback, missing/corrupt artifact handling, and older-schema data preservation. Update `release-manifest.json` only with the actual validated schema/capabilities, not planned completion gates.

## Improvements to the design itself

- **One acceptance authority:** use immutable, typed validated artifacts and a single persisted lifecycle projection. Remove competing notions of acceptance from generic records and binding constructors. Keep execution authorization ephemeral and revocable.
- **A useful first build:** choose a small functional floor plan and qualify each native block family independently. A flat accessible workshop is a better first acceptance fixture than unsupported stairs or a dramatic roof. A floor-only phase should be called staging, not shelter.
- **Real construction context:** model access corridors, temporary supports, dependency edges and before/after states. Recompute only affected work when an observation changes; do not regenerate the whole design on every tick.
- **Total computation budgets:** bound input volume, expanded nodes, candidate attempts, conflict reports, reference bytes/palette, and preview pixels. Three returned candidates does not bound a search. Cache by all input hashes/versioned capabilities and block unchanged infeasible requests until relevant facts change.
- **Two distinct comparisons:** exact reproducibility hashes should bind every relevant byte; novelty should compare normalized structure/functions/circulation/massing independent of arbitrary IDs, rotation and palette. Calibrate near-copy thresholds on labeled examples with false-positive/negative reporting.
- **Conservative maintenance:** record condition, authorship evidence and action accounting separately. Matching geometry can satisfy a physical requirement without proving the player spent an item. Preserve human edits; cooldowns and capped repair budgets prevent endless rebuilding.
- **Operator visibility:** show proposed, validated, accepted, resource-ready, building, reconciling and physically verified states with the next useful phase and exact blockers. One status view should explain why a valid preview is not yet executable.
- **Evidence-driven architecture:** add a failing regression for each source defect above. Integrate one vertical slice fully before adding more disconnected helpers; compilation and enum names alone do not demonstrate a system.

## Specification coverage map

| Specification sections | Required implementation / qualification |
| --- | --- |
| 1–4, 19–21 | Unified accepted artifact, runtime admission, existing controller/writer reuse, durable attempt linkage and restart recovery (stages 1–3). |
| 5–10 | Semantic hierarchy/invariants, detailed briefs, trusted observations, bounded priorities and persistent hard-constrained style/taste (stages 1, 4, 6). |
| 11 | Real reviewed-reference adapter, bounded decoding, provenance/license/registry compatibility and separate execution qualification (stage 6). |
| 12–16 | Coupled layout/routes/structures, useful phased supply including recipes/fuel/tools/food/temporary access, supported construction contexts (stages 2, 4–5). |
| 17–18 | Labeled overhead/exterior/approach/interior/phase/diff previews as available, bounded typed critique and existing inference/privacy budgets (stages 4, 7). |
| 22–24 | Changed-world invalidation, human-edit preservation, maintenance budgets/cooldowns, bounded job scheduling, grounded diagnostics and measured tick cost (stages 3–8). |
| 25–28 | Pure tests, native fixtures, real clients, matched-site design comparison and earned playthrough evidence; parent IDs remain open until their actual criteria pass (stage 8). |

## Validation and environment handoff

This review did not rerun runtime tests or make gameplay changes. Earlier turns reported 29 executable pure scenarios with 128 transformed-generation cases and direct compiler-module compilation of core/scheduler/design/persistence sources. The committed original foundation evidence contains 21 scenarios from the original slice; it does not substantiate later integrations. SQLite design tests were added but not run. Root Gradle, migration, server packaging, design GameTests, ordinary-client and earned-world acceptance are unverified.

Environment pitfalls observed earlier:

- Java 21 runtime modules were installed, but `javac` and the normal JDK release-symbol support were missing. A direct compiler-module run with `-source/-target 21` is not proof the wrapper build works. `/workspace/tools/jdk21` is an ad hoc launcher/release-file workaround, not a complete supported JDK. Replace it with a verified complete JDK 21 distribution or package.
- Root `gradle.properties` pins a Windows Java path. Override it locally with `-Dorg.gradle.java.home="$JAVA_HOME"`; do not upgrade the manifest-pinned runtime/dependencies to make a build pass.
- Gradle needed explicit proxy configuration. Some Fabric resolution later succeeded, while JDK downloads returned HTTP 403. Recheck the specific required hosts rather than assume all network traffic is blocked. Use normal TLS/checksum verification.
- Fabric Loom previously reported a lock owned by defunct PID 2075. Reinspect current process/lock state; stop only task-owned live Gradle processes. Remove only a confirmed stale lock or use a separate cache. A stale lock is local troubleshooting, not a reason to ask the user for permission again.

With a real JDK and working repositories, from the repository root run:

```bash
export JAVA_HOME=/absolute/path/to/verified-jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
bash ./gradlew -Dorg.gradle.java.home="$JAVA_HOME" --no-daemon --max-workers=2 build
bash ./gradlew -Dorg.gradle.java.home="$JAVA_HOME" --no-daemon --max-workers=2 :kindred-world-design:foundationTest :kindred-world-design:demo
bash ./gradlew -Dorg.gradle.java.home="$JAVA_HOME" --no-daemon --max-workers=2 :companion-persistence:test --tests dev.kindred.persistence.SharedProjectStoreTest
bash ./gradlew -Dorg.gradle.java.home="$JAVA_HOME" --no-daemon --max-workers=2 :companion-testkit:run :companion-testkit:runPersonalityDemo
bash ./gradlew -Dorg.gradle.java.home="$JAVA_HOME" --no-daemon --max-workers=2 :companion-server-adapter:runGametest
```

These are instructions for the next agent, not checks executed during this review. Add focused tests for new behavior, then run required suites once changes warrant them. Verify report timestamps/targets and actual nonzero test counts; distinguish skipped, failed and unrun suites. Check the produced server JAR contains required design classes and excludes test fixtures.

The next agent's first implementation task is stage 0 followed by stage 1: repair and test artifact/acceptance invariants, then implement one guarded native placement through the existing runtime. Do not start by exposing the present `findAcceptedDesignPlacementAction` API directly as a construction command. It lacks the geometry membership, dependency, authority and receipt guarantees described above.
