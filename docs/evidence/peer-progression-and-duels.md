> Historical evidence from the retired companion prototype. Its directions, completion labels and pending-work lists are not current requirements. Use [the AI-player contract](../../minecraft_player_spec.md) and [canonical tracker](../feature-spec.md); retain this record only for provenance.

> Retired from production 2026-10-15: SurvivalBehavior and DuelBehavior now exist only in GameTest sources. Their direct healing, free/copied equipment and independent control are not qualified AI-player skills.

# User Directives — Duel Behaviors & Bot-to-Bot Peer Progression Dialogue

Date: 2026-10-14
Agent / branch: Antigravity agent, uncommitted working tree
Build: `.\gradlew.bat test` (BUILD SUCCESSFUL), `.\gradlew.bat :companion-server-adapter:runGametest` (37/37 GameTests passed)
Status: Completed foundation & native integration.

## Scope and implementation

### 1. Duels & Hit-back Retaliation
- Added `GENERIC_ATTACK_DAMAGE` attribute registration to `CompanionEntity` in `KindredMod.java`.
- `DuelBehavior`:
  - Out-of-duel unarmed hit-back when struck by player (preserves weapon in inventory, delivers 1 unarmed strike back, gives personality-grounded voice line).
  - Challenge request via `/companion duel` or companion-initiated prompt when idle.
  - Acceptance / decline weighted by personality traits (bravery, sociability, patience).
  - When accepted, draws best melee weapon from inventory, awaits player approach to within 2–3 blocks, and begins combat.
  - Terminates safely on surrender (companion or player at $\le 4.0$ HP) without player death or equipment loss.
  - 4 Native GameTests in `DuelGameTests.java`: hit-back bare-handed, duel acceptance drawing weapon, initiation on proximity, decline when busy.

### 2. Private Peer-to-Peer Progression Dialogue (Player Simulation)
- Implemented `PeerProgressDialogue.java` in `companion-core`:
  - 5 progression topics: `GATHERING_COORDINATION`, `MINING_PROGRESSION`, `FARMING_SUPPLIES`, `INDEPENDENT_MILESTONES`, `COMBAT_AWARENESS`.
  - Companions observe nearby peers (visibility and line of sight required).
  - Generates personality-grounded progression dialogue without broadcasting or spamming global chat.
  - Records mutual verified facts into SQLite memory via `MemoryNote` (`Kind.FACT`, provenance `peer_dialogue:<uuid>`).
  - Appends `PEER_CONFERENCE` journal events to the action ledger for both companions.
  - Players within 6 blocks receive a local ambient whisper: `§7[Nearby] Alice confers with Bob: "..."`.
  - 120-second pair cooldown and staggered tick checks to ensure 10 concurrent companions run smoothly on 8GB–16GB servers.
- 4 Unit tests in `PeerProgressDialogueTest.java` (all passing).
- Wired in `CompanionSocials.java` and `CompanionRuntime.java`.

## Acceptance evidence

| Test Suite | Result |
| --- | --- |
| `PeerProgressDialogueTest` (4 tests) | Pass |
| `DuelGameTests` (4 GameTests) | Pass |
| Full GameTest suite (37 tests across 9 test classes) | Pass (37/37) |
| Multi-module unit test suite (`.\gradlew.bat test`) | Pass (18 tasks) |
