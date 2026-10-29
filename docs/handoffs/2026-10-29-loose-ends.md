# Loose ends, 29 October 2026

A snapshot of everything unfinished across the branches, for one long working session. The
[tracker](../feature-spec.md) stays the only checklist: this page points at it and at what is not in it. Check each
item against the code before acting; several branches have moved since this was written.

## Where things are

| Branch | Holds | State |
| --- | --- | --- |
| `main` (`91f408a`) | Everything merged through PR #3 | Full suite last 365 of 367 on the closest build; see [validation](../validation.md) |
| `claude/blissful-fermi-y4tt90` (`385a6c2`+) | Vesp renamed Echo (E-10c), the logo banner, the "make your own" README, the plain-language comments, the voice proposal, this page, `tools/plain-comments` | Clean on `main` |
| `claude/player-voice-e20` (`147cb0f`) | All of the above plus the voice (E-20a) and asks in chat (E-20b) | Reviewed; see section A |
| `claude/modest-faraday-r3spro` (`9f354b4`) | The End fight, combat, villagers, bases, matches, chores, F-18 and much else, all in progress | Its last full suite 380 of 381; conflicts with the voice branch only in `docs/feature-spec.md` |

Merge order that works: the voice branch to `main` first (it carries `blissful-fermi`); then `modest-faraday` merges
`main`, resolves the tracker, and goes in. Then delete the merged branches.

## A. The voice branch, before or right after it merges

1. **Talking to your own player needs public chat on.** A native player's record is its own "owner", so
   `CompanionSocials.canTalk` never matches the person who made it: even they are heard only after
   `/aiplayer social public true <name>`. Either count whoever `mayControl` admits as the owner in the lane (two lines,
   part of F-18, with a fixture) or say so in the README's "Make your own" section and in the operator guide (which
   still says a native player talks "to its owner").
   **Done in `249f476` (F-18a), the lane fix.** `canTalk` alone was not enough: a native player's remarks and
   greeting decide its owner in `SocialPerception` too, so the lane now has one owner predicate for all three.
   README and operator guide updated in `d9b1b91`.
2. **FOLLOW as an ask** needs a native follow task first (none exists for the native body; the legacy
   `FollowBehavior` is the mob body's).
3. **Coalescing drops a speaker.** A message waiting behind one in flight is replaced by any newer message, from anyone,
   so the first speaker can get no reply at all. Keep one waiting message per speaker, or answer the displaced one
   with the fixed reply.
4. **Innocent refusals.** The clean-word list and the "three numbers in a row" place check can refuse an ordinary
   line; the cost is the fixed reply. Watch the `voice: no line (...)` log lines in play and loosen only what they show.
5. My re-run of the branch: see the end of this page.

## B. The optional model (F-19, E-20)

6. **The Anthropic SDK is not bundled in the release jar**, so Claude (the default) only works in development runs;
   the other providers work in the jar. Bundling it (`include`) and checking the jar's size and licence is F-19b's
   last step.
7. **Never compared with and without it.** F-19b/c ask for paired runs on one build (deaths an hour, stage times); none
   run. Until then the strategist's value is a story, not a number.
8. **No live call through a non-Anthropic provider** (F-19d): one consultation with an OpenAI key, checking the log
   line and the reply.
9. **Follow-ups never seen in a live run** (F-19e).
10. **Habits only come from the model.** A player without a key never learns "carry more food" or "shelter early".
    A local rule for the plain lessons (two deaths hungry; two night deaths on the surface) would give every player
    the same memory.
11. **Speech is clean for everyone** until E-18's presets and E-19's recipient caps exist.
12. **`ModelGateway` and `ReasoningPolicy` are unwired** (F-19a's foundation). Decide: wire them as the gate the bridge
    now is, or retire them and say so in F-19.

## C. Roster, identity and wording

13. **The starters' personalities are never applied.** `CanonicalRoster` carries a personality for Kindred, Sol and
    Echo, but `spawnCanonicalRoster` → `createNative` gives them the defaults, so the three differ only in name and
    skin. Apply the preset to the new player's profile on creation (with a fixture), or drop the presets and the
    titles.
14. **Old worlds still show "Vesp".** E-10c keeps a saved player's name; renaming a saved display name needs a tested
    migration (the name is in the companions table and the native save).
15. **"AI" is still in the command and in-game text** (`/aiplayer`, "AI player panel", the panel's messages) after the
    front page dropped it. Decide whether to add an alias (`/player`?) and reword the panel; renaming the command
    breaks habits and the docs.
16. **`docs/optimization-and-qol-guide.md` still says Vesp** (a note at its top says so); the E-10a evidence keeps the
    old name on purpose.

## D. Docs and comments

17. **283 Java files still have no "In plain terms" summary**: the ones the other session was changing
    (`PlayerRuntime`, `NativePlayerBody`, `PortalRunCampaign`, `BuildRunner`, the End-fight and match classes, the
    guide package, their fixtures). `tools/plain-comments/` has the scripts and the method used so far; comments only,
    each module compiled after, and the README's "the rest are getting one" updated when done.
18. **README: "friends with plain vanilla Minecraft can join too (still being tested)".** The ordinary-client gate
    (F-25b) has never been run; keep the wording honest until it has.
19. **No download.** The build workflow keeps a jar as a run artifact only; there is no GitHub release (F-23). The
    README says "build it yourself"; a tagged pre-release would be the first download.
20. **GitHub-side, owner only:** the repository's About description and topics are unset; four stale `codex/*`
    branches remain.

## E. The test suite

21. **Intermittent fixtures, all outside the voice work:**
    - `NativeLifeFirstGameTests.atNightItLeavesItsBuildForItsOwnBedThenBuildsOnInTheMorning` ("Its body"): fails about
      one run in two alone on `385a6c2`, before the voice.
    - `NativePortalCampaignGameTests.goalClimbsOutOfAPitBelowItsWayBackWithNoPickaxe` (still in the pit at the end).
    - The pasture's cattle (two cows left by the pen's doorway), open since suite runs 33 to 46.
    - A base's roof stairs (the review's item #82: a stair set from on top of its own scaffold), open since run 40;
      `28395df` has a fix "not yet run natively".
    - The Nether portal-restart fixture, `modest-faraday`'s one failure on `9f354b4`, with a fix in that commit to confirm.
    Each wants a root cause and a fixture that fails for the right reason, not a retry.
22. **The suite takes about an hour on four cores** and did not finish inside 70 minutes when it shared them (run 51).
    Check that the GitHub workflow, which runs the whole suite on every push, is green on `main` and how long it takes;
    consider splitting the gametest job or running the long fixtures nightly.
23. **Five fixtures use `createMockCreativeServerPlayerInWorld`, deprecated for removal** (`NativeGazeGameTests`,
    `NativeVoiceGameTests`, `CompanionGameTests`, `NativeCombatGameTests`); `NativePlayerBody` compiles with unchecked
    warnings. Clear them before the next Fabric API bump forces it.

## F. Structure (needs the owner's decision)

24. **`PlayerRuntime` is about 13,000 lines** and every feature touches it, so two sessions collide there on every
    merge. The rules say new orchestration belongs in it; carving settled areas (the panel, the roster, the social
    wiring, the match commands) into collaborators it delegates to would keep that rule and shrink the conflicts.
25. **The legacy mob body** (`CompanionEntity`, `ChoreSkills`, `MovementSkills`, `FollowBehavior`, the retired duel and
    survival prototypes in the fixtures) is kept for old saves. A retirement path (load, warn, convert or refuse) is
    undecided.

## G. The open features (the tracker's Active work, in one line each)

The remaining work as each row states it on 29 October. Pull from here after A to E, finishable fixtures first.

| ID | Remaining |
| --- | --- |
| E-39a–d, End fight | Fixtures for the cage and the perch fight; death and recovery in the End (a death loses the kit); an earned run from the stronghold; then E-39's team expedition |
| E-39e–i | A perch-fight fixture, a staged fight on the current build, enchantments beyond Sharpness; the stranded pearl, pearls in the Nether and Overworld; a staged death in the End; the dragon circling a tower; a creeper fixture by a wall, the crystals and the bed shield, the full suite |
| E-07d, E-07e | A real knock off a tower; a bucket kept in the hotbar where heights are; a Nether trip live; Overworld gold smelted; hoglin repellents |
| E-08b, E-08c | A build kept across a restart (blueprint, origin, turns); a build ended short in play; smelted blocks; a team's stock |
| F-13 | A ranch and a moat kept across a restart; suspension, death and uncertain-action recovery together; a live restart mid-work |
| M-05 | Litematica `.litematic`; old saves through vanilla's data fixer |
| F-25c4, c6, c7, c8 | The hole-up fix tried live; a player suffocated digging out (open); an earned stronghold run; the stronghold gathering in real terrain, spiders at night, a live run; each playbook way wired as played, fixtures, live runs at each pace |
| E-33a | Seen by a person in an ordinary client; looks with nothing to do; looks while walking, mining, placing; a remark on looking back |
| E-40a | Team runs with one, two and three players dividing the run, handing over, meeting for the End |
| E-41a, b, c, e, f, g, h | Last-seen positions; sprint knockback and sweep parity; arenas; the full suite with E-41b; an opponent whose habits change; real demonstrations, self-play, a benchmark; a live duel with the strategist; a shield raised on the way, a manhunt fixture, a human's hit; odds weighed before and during a fight |
| E-42, E-43, E-44 | Looting outside the goal, giving up the least for room, a live run; counts across a restart, fetching from a base's chests; a duel, a human-joined match, Factions, a live match |
| E-01, E-02, E-03, E-05, E-06 | Delivery into a chest, chest contention, an errand across a restart; a replanted tree grown and cut again; ore behind blocks, deepslate, Fortune; an approved fishing spot, treasure and junk; lighting below ground, operator-assigned animals; each then the full suite |
| E-16a–d | The table and levels earned in play, own bookshelves; ingredients got in play, drinking a potion when wanted; debris mined and a template found; the naming stage reached in play; live runs |
| E-45b–e | A world-generated village, levels and restocks; a reroll ending in a keeper; the hall's beds in a full suite; the strolling fixture in a full suite; live runs |
| M-00a, M-00b, M-03a | A human-joined duel; scoreboard, action bar, particles, onlookers; mode selection, teams, more than two; races on random seeds, AI against AI and a human |
| M-02a, b, c | A human-joined hunt; a whole hunt from a fresh start; across worlds; blocking pursuit; gearing beyond iron; threats other than a hunter; a night away from home; a hunter that keeps up |
| M-04a | A human builder in play; an arena with lobby, spectator and voting paths; visiting each build; designs from the model or the schematic library |
| M-01a–d, M-06a, M-06b | The ranch chosen in a playthrough paced to the humans; more animals, horses; the house and manor in play, base chests as storage, other woods; the player using the smelter, redstone; the field harvested and resown, towers, moat and bridge, the keep chosen in a Factions match; the bunker's later stages, a hatch |
| E-30, E-31a, E-29a | Reservations in the ordered store, two players' bases in play; repairs of builds other than its base, a repair in play; stone seeds, plans grown rather than chosen |
| E-36, E-37, E-38 | A name registry beyond memory notes, correction; cats and parrots, refusals, restart and death; pet care, a durable record of loss |
| F-10e–h | A live route run; the last plan followed rather than replanned; leaves, ladders, towers; deep-water launch, ocean strongholds, guardians; waiting or sneaking where no berth; a climb in play, stairs for the builder and the End fight |
| F-18 | Live multiplayer speech; glances for the native body; team speech from real stock |
| F-25c9 | Routine work-area chores under the same weights; food from stores, crops and pens, cooked; a bed away from base |
| E-11 | Stock totals beyond reservations saved; a held task released natively after a restart; a team's build in play |
| E-15 | An earned obsidian-to-portal journey, blaze rods and wart from the world, F-25b and ordinary-client gates |
| F-25b, F-25c | Renewable admission, ordinary clients, restart while dead, combat and portal qualification; full body, accounting and recovery, teams, automatic progression |
| F-11, F-07 | Unattended admission and schedules; restart while dead, the remaining transfers |
| F-03/04/05/10, F-01/02/15, F-22, F-23 | Native cancellation and reconciliation; no live release gate has ever been completed; operator diagnostics and installation evidence; the release package |

## My re-run of the voice branch

A build and a focused run of `NativeVoiceGameTests` and `NativeAskGameTests` on `147cb0f`, in a scratch worktree,
was still running when this page was committed; its result follows in the next commit to this page.
