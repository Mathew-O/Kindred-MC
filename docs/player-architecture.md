# Autonomous AI-player architecture
15 October 2026 · [Product](../minecraft_player_spec.md) · [Tracker](feature-spec.md)

## Domain and modules
An AI player has identity, needs, observations, personal goals and team commitments. Administrative access is separate. Follow/help/wait are tasks; human presence and legacy mode are not core decision inputs.

| Component | Responsibility |
| --- | --- |
| PlayerIdentity | Stable player/world/name identity without creator ownership |
| PlayerDecisionLoop | Local stop/availability/task/selection lifecycle |
| TaskDirector / TaskPreferences | Trusted eligibility, urgency, interests and switching |
| ActionArbiter / SkillRunner | Sole body authority, cancellation and checkpoints |
| PlayerRuntime in adapter | Server-thread observations, admission, dispatch and verification |
| Existing SQLite writer | Metadata, assignments, memories and accounting |
| Native body saves | Physical inventory/equipment and body state |
| companion-model | Optional event-driven inference; never body control |
| SocialBrain | Grounded expression and audience/pacing policy |

Core contains no Minecraft objects/provider SDKs. IO callbacks enter through current server-thread authority checks. The mind is a decision layer, not a second body controller.

F-11a's ActivityPermit bounds already-acknowledged prepaid work by player, reservation, accounting period, sequence, wall deadline and monotonic time. It neither persists nor grants native authority. Runtime activates only after a matching durable acknowledgement, revokes on failed checks and settles through the existing writer.

F-11b's PaidActivitySession supplies the asynchronous reservation/settlement lifecycle through the existing writer API. Owner-dispatched acknowledgements activate permits; stop fences pending callbacks, unknown writes block, and close can finish immutable accounting IO without more game ticks. It owns no body/admission and does not auto-renew. F-04a integrates explicit native wait/eat through the existing admission board, runner and arbiter. New AI identities occupy their own capacity principal. Each request opens the persisted current budget and uses one slice; exact selected food and paid authority are rechecked at native consumption. Tasks do not replay after cancellation/restart. Native saves retain physical items; budget writes retain usage. Renewable schedules and unattended admission remain open.

F-10a adds bounded native flat walking under that same paid dispatch. Each request targets 1..3 cardinal blocks and checks a bounded loaded corridor and full supporting footprint before and during motion. Native forward/yaw inputs provide traversal; route changes stop the task. No teleport, terrain modification, chunk tickets or general navigation is enabled.

F-07a adds native owned armor/shield transfer through the same paid path. Native screen-handler rules perform the move; source/target eligibility and exact stack snapshots fence it. All inventory slots are compared afterward, and an uncertain physical outcome blocks the durable native record. Copies exist only for comparison, never as usable equipment.

F-05a ActionAdmission provides a single-use pre-effect durable boundary over existing ActionRecord writes. Matching PREPARED/DISPATCHED acknowledgements precede a take on the owner; no callback mutates the world. Taken work requires fresh physical evidence to verify/fail; unknown outcomes remain UNCERTAIN. Close flushes immutable records without game ticks. SQLite protects intent fences and before-evidence from rewriting. F-05b integrates that boundary for owned native equipment: durable prepare/dispatch precede a single physical operation, then native inventory evidence and durable verification precede completion. Runtime closes action IO before the writer and blocks uncertain native identities without replay. Timed food and native mining/crafting integration remain open.

F-08a observes actual native block-break completion through Fabric AFTER events. Receipts bind current lease, target/state, identity and a per-body attempt/sequence; external disappearance and duplicate observations cannot claim success. These are ephemeral adapter observations, not authority or a persisted milestone.

F-03a configures bounded native resource-work scope through existing WorkSetup without a home/human-presence prerequisite. Scope changes revoke current and pending work synchronously and require matching owner-delivered persistence acknowledgement before explicit resume. Chunk/volume validation uses overflow-safe bounded arithmetic. Scope does not install skills or grant server-edit policy; native mining still requires its own admission/claims/evidence.

F-08b adds recorded scoped native log mining: explicit requests break one reachable log in the player's acknowledged patch using owned hotbar contents and native survival timing. Explicit server edit policy, active paid activity, resource claims, and durable ActionAdmission fence the action; a matching break receipt plus durable verification completes it without claiming drop collection. Cancel/stop clears digging immediately; unknown outcomes block for reconciliation without replay. Movement to targets, drop pickup, and full F-08 remain open.

## Body and migration
F-25b qualifies native server-player lifecycle/input, vanilla inventory/tools/mining, hunger, crafting, combat, death/respawn and portals. Assess existing open-source implementations against pinned versions before adoption. A skin or mob healing approximation does not pass.

CompanionEntity remains the saved mob compatibility backend. Prototype SurvivalBehavior/DuelBehavior now exist only in GameTest sources and are excluded from the production JAR; their old fixtures do not establish supported combat. Reimplement survival as arbiter-controlled skills.

NativePlayerBody runs native physics, hunger, item use and survival mining with real player slots. One bound ActionArbiter and current per-action mutation gate fence controls; checks include activity, policy, claims, reach, visibility and selected target state. Stop clears voluntary input, digging and item use synchronously. F-25b2 adds vanilla PlayerManager registration, an in-process connection with no socket/packet queue, one physics tick path and validated prepared native saves. NativePlayerSaves reads bounded bytes off-thread; the narrow prepared-state mixin leaves human loading alone. PlayerSaveHandler remains the physical writer. Full ordinary-client presentation, legacy migration, crafting/combat, death/respawn and portals remain open; no automatic progression is enabled.

F-25c1 adds PlayerAccess and schema-7 native identity/controller/body-state records through the existing ordered writer. New AI records self-own the legacy arbiter projection; controller grants are separate. Multiple AI identities may share a controller. Allocated metadata does not prove physical creation. Establishment needs a verified native-save receipt; blocked state requires explicit reconciliation. Existing legacy ownership, settings, stop and allowance records are preserved.

F-25b3 integrates native bodies through PlayerRuntime's existing Entry/arbiter/runner/store. Explicit creation is capped at three. A native-file receipt is verified before establishment; reconnect requires matching existing state in a loaded area. Generation checks fence delayed loads after stop. Shutdown saves/disconnects; restart stays offline. F-04a/F-10a admit only explicit short wait/eat/walk/equip skills; portals/dead-body revival stay blocked. No legacy conversion occurs.

Preserve SQL/NBT/module names, UUIDs, items, stop and spent limits until tested migration. Companion.playerIdentity() separates identity now; persistent controller/team structures remain F-25c. Legacy mode/scope preserves restrictions but is not a new autonomy switch. /aiplayer is primary; /companion is a compatibility alias.

## Autonomous execution
Observe -> update needs/known world -> choose feasible work -> execute -> verify -> revise.
Valid skills continue without inference. Survival interrupts use the existing arbiter/settlement barrier. Registry-derived recipe/resource dependencies and bounded local recovery drive progress. Recheck stale memories and readiness.

F-25c2 enables bounded autonomous candidate generation (hunger/eat, unequipped armor, scoped log mining in assigned patches, rest) under server edit policy, personality-biased selection via PlayerDecisionLoop and TaskDirector with switching hysteresis, and dispatch through PaidActivitySession and ActionArbiter into qualified native skills. Multi-step crafting, recipe resolution, general pathfinding, team goals, and full F-25c automatic progression remain open.

## Teams and inference
E-11 extends existing projects with dependencies, assignee, stock reservations, destinations and verified outcomes. One assignment owner; no double spending. Share permitted discoveries/commitments; preserve individual knowledge and authority. Reassign only after death/stop/restart settlement or reconciliation. Speech describes accepted assignments.

Local gameplay has no API requirement. Optional Claude/OpenAI requests obey F-19: meaningful events, duplicate suppression, compact context, deadlines, token limits, stale rejection and billing. No Jev, hosted orchestration requirement, per-tick requests or generated code.

## Engineering priority
The canonical tracker owns work order. Qualify native player mechanics and actual traversal first, then local first-night/iron progression, team supply chains, Nether trips and multi-seed Dragon completion. Social richness remains a refinement. Measure interventions, recovery, conservation, tick time and API tokens. Historical fixture counts are not playthrough evidence.
