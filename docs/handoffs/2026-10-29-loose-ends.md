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
   **Not done.** Confirmed: no native follow skill in `NativePlayerSkills`. It is a new skill (a goto that re-aims at a
   moving player, its own stop and range), then FOLLOW in `Strategist.Ask`, the prompt and the ask dispatch.
3. **Coalescing drops a speaker.** A message waiting behind one in flight is replaced by any newer message, from anyone,
   so the first speaker can get no reply at all. Keep one waiting message per speaker, or answer the displaced one
   with the fixed reply.
   **Done in `6ea5681` (E-20c):** the displaced speaker is given the fixed reply at once; the model is still asked only
   for the newest.
4. **Innocent refusals.** The clean-word list and the "three numbers in a row" place check can refuse an ordinary
   line; the cost is the fixed reply. Watch the `voice: no line (...)` log lines in play and loosen only what they show.
   **Not done: no play yet.** The only `voice: no line` lines so far are the fixtures' own refusals.
5. My re-run of the branch: see its section below, after G.
   **Answered on the voice branch itself:** the full native suite on `64f4ac1` (E-20b's code) passed 393 of 393, with
   the build's 787 JUnit results, none failed ([validation](../validation.md)).

## B. The optional model (F-19, E-20)

6. **The Anthropic SDK is not bundled in the release jar**, so Claude (the default) only works in development runs;
   the other providers work in the jar. Bundling it (`include`) and checking the jar's size and licence is F-19b's
   last step.
   **Checked, not bundled: the owner's call.**
   - Size: the SDK and the 26 libraries it needs come to 48 MB without SLF4J, which Minecraft supplies. Of that,
     `anthropic-java-core` alone is 40 MB, then Kotlin's reflect and stdlib, Jackson, OkHttp, Okio, OpenTelemetry and
     victools. The release jar would grow from 17.3 MB to about 65 MB.
   - Licences: all MIT (the SDK, standardwebhooks) or Apache-2.0 (the rest), so bundling is allowed.
   - Options: bundle them all; ship an optional add-on jar that carries the SDK; or keep it out of the release and point
     release servers at the other providers, which already work there.
7. **Never compared with and without it.** F-19b/c ask for paired runs on one build (deaths an hour, stage times); none
   run. Until then the strategist's value is a story, not a number.
   **Run, one pair; no gain shown.** Seed 8675309, the three starters on the Nether goal, 144,000 ticks sprinted, on
   `f7f206a`; fixture runs shared the cores.

   | | Off | On |
   | --- | --- | --- |
   | Deaths | 2 | 10 (8 of them Echo's) |
   | Iron pickaxes | 3 | 2 |
   | Furthest | casting the frame | casting the frame |
   | Strategist | none | 54 replies; 381,612 tokens in (327,805 from the cache), 8,634 out |

   One pair on one seed says nothing either way. Next: two pairs on two seeds, nothing else on the cores, with each
   death logged with what it happened in. The key stayed in the server's environment; neither log has it.

   **Run again, two pairs on two seeds (30 October), on `4d0884a`, nothing else on the cores; no gain shown.** The
   starters on the Nether goal, 144,000 ticks each, each death logged with what it happened in. On seed 20261030 the
   harness found no room at spawn for Echo, in both runs, so that pair is two players.

   | | 8675309 off | 8675309 on | 20261030 off | 20261030 on |
   | --- | --- | --- | --- | --- |
   | Deaths | 9 | 5 | 2 | 1 |
   | Most iron toward its need | 14 of 15 | 14 of 15 | 14 of 15 | 14 of 15 |
   | Furthest | iron | iron | obsidian: Kindred found diamonds digging down for lava, so its frame was to be mined | iron |
   | Strategist | none | 52 replies; 366,807 tokens in (315,435 from the cache), 8,365 out | none | 25 replies; 172,919 in (148,440 cached), 3,523 out |

   - With the strategist on there were fewer deaths on both seeds (5 against 9, 1 against 2), and no more progress: no
     portal lit, the same iron. Its advice was mostly to play safe or to surface and recover, food first. Two pairs are
     still too few to say whether it helps.
   - Of the first run's nine deaths, five came while the player held a block for cover from a skeleton or a witch, with
     a zombie or a spider in reach; two backing off; two holing up. Three of the 26 deaths in B7's logs were
     suffocation, climbing up into gravel.
   - Found in these logs and in reading the code they led to: waits below for daylight to go up for logs (up to 19% of a
     player's time), coal hunts (16%), climbs out for food on raw meat that was never cooked (18%), a crafting table
     put down and picked up again 113 times in one run, and the stick reserve burnt as fuel. With the cast fixture's
     frame site past its wall, from the closing suite, each is now a slice of F-25c4 (F-25c4a to F-25c4h, in the
     tracker).
   - The key stayed in the server's environment; no log has it.
8. **No live call through a non-Anthropic provider** (F-19d): one consultation with an OpenAI key, checking the log
   line and the reply.
   **Not run:** no OpenAI (or other non-Anthropic) key in this session's environment.
9. **Follow-ups never seen in a live run** (F-19e).
   **Seen, in B7's strategist run:** seven replies queued follow-ups, each logged as `<name> strategist: queued [...]`:
   - food for the Nether trip ("Only 1 food carried; the Nether trip needs 12 or more");
   - sticks and flint for the frame's flint and steel;
   - a shield for Echo, twice ("three deaths to mobs underground, and no shield yet");
   - wool for a bed.
   **Closed with a control (`e375d25`):**
   - The stronghold fixture already queues a shield as a strategist reply would, and checks it is crafted before going
     down. Its new control is the same with nothing queued: it goes down with no shield, its iron and planks unspent,
     3 of 3. So the shield is the follow-up's, not the leg's own plan's (the End fight's own needs hold none).
   - On the Nether goal a queued shield adds nothing, by design: the route plans its own iron and wood, so the
     follow-up skips a shield there. The plan itself makes one whenever it has six planks and an ingot. Echo's five
     shields in B7 were the plan's ("Craft shield"), not the follow-up's ("on the way, for what comes later").
10. **Habits only come from the model.** A player without a key never learns "carry more food" or "shelter early".
    A local rule for the plain lessons (two deaths hungry; two night deaths on the surface) would give every player
    the same memory.
    **Not done:** the rule is simple, but it has to be wired where a death is recorded and habits are given to the goal.
    That is `PlayerRuntime` and `PortalRunCampaign`, both changed by `modest-faraday`, and today the habit memory
    (`ModelContextStore`) exists only inside the strategist's bridge. After that branch merges.
11. **Speech is clean for everyone** until E-18's presets and E-19's recipient caps exist.
    **No action:** waits on E-18 and E-19.
12. **`ModelGateway` and `ReasoningPolicy` are unwired** (F-19a's foundation). Decide: wire them as the gate the bridge
    now is, or retire them and say so in F-19.
    **The owner's decision; untouched.** Both are still unreferenced outside their own tests.

## C. Roster, identity and wording

13. **The starters' personalities are never applied.** `CanonicalRoster` carries a personality for Kindred, Sol and
    Echo, but `spawnCanonicalRoster` → `createNative` gives them the defaults, so the three differ only in name and
    skin. Apply the preset to the new player's profile on creation (with a fixture), or drop the presets and the
    titles.
    **Done in `21a8a3d` (E-10d):** applied on creation, read back after a restart, never reset on a saved player. The
    titles are still shown nowhere.
14. **Old worlds still show "Vesp".** E-10c keeps a saved player's name; renaming a saved display name needs a tested
    migration (the name is in the companions table and the native save).
    **Not done, by choice.** The migration would run in `PlayerRuntime`'s startup load, which `modest-faraday` changes
    heavily, and would rewrite the companion record, the arbiter's snapshot and the native record's copy with their
    revisions: not a small, local change. E-10c chose to keep saved names, and whether an old world's Vesp should be
    renamed at all is the owner's call. After `modest-faraday` merges, if wanted.
15. **"AI" is still in the command and in-game text** (`/aiplayer`, "AI player panel", the panel's messages) after the
    front page dropped it. Decide whether to add an alias (`/player`?) and reword the panel; renaming the command
    breaks habits and the docs.
    **Owner's decision; not touched.**
16. **`docs/optimization-and-qol-guide.md` still says Vesp** (a note at its top says so); the E-10a evidence keeps the
    old name on purpose.
    **Done in `5fdb7bd`:** its text says Echo; its code excerpts stay as written, and the note says what the code is now.

## D. Docs and comments

17. **283 Java files still have no "In plain terms" summary**: the ones the other session was changing
    (`PlayerRuntime`, `NativePlayerBody`, `PortalRunCampaign`, `BuildRunner`, the End-fight and match classes, the
    guide package, their fixtures). `tools/plain-comments/` has the scripts and the method used so far; comments only,
    each module compiled after, and the README's "the rest are getting one" updated when done.
    **Done in `b4fe930`, but for `modest-faraday`'s twenty.** 289 files lacked one on this branch: the page's 283, and six
    added since by the voice work and this session's tests. 269 now have one, `NativePlayerBody` included, with short comments on public and
    package methods that had none; the twenty files `modest-faraday` changes are left for after it merges. Comments
    only: every file was compared with its comments stripped against the commit before, and every module compiled.
    The docs README now says which files are left.
18. **README: "friends with plain vanilla Minecraft can join too (still being tested)".** The ordinary-client gate
    (F-25b) has never been run; keep the wording honest until it has.
    **Done in the commit that ticks this:** "should be able to join too, but nobody has tried that with a real vanilla
    client yet".
19. **No download.** The build workflow keeps a jar as a run artifact only; there is no GitHub release (F-23). The
    README says "build it yourself"; a tagged pre-release would be the first download.
20. **GitHub-side, owner only:** the repository's About description and topics are unset; four stale `codex/*`
    branches remain.
    **Owner only; untouched.** D19 (a first download, a tagged pre-release) is also the owner's call: publishing a
    release is outward-facing.

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
    **Checked on this branch (29 October):**
    - **The night-and-bed fixture: two causes, both fixed.**
      - [E-08d](../evidence/E-08d.md), `2b1f325`: short of logs, it cut the corner posts out of its own cabin. In the
        failing suite run v1, its first twelve log breaks were its cabin's twelve corner logs.
      - [F-25c9a](../evidence/F-25c9a.md), `bc1690a`: its first night slept in its cabin made the cabin's bed its
        expedition's home, and the next night the urgent walk home stopped at the cabin wall and outranked its bed.
      - A fixture for each fails before its fix: a hut standing nearer than the trees, and the night fixture with its
        first night falling while the cabin goes up.
      - The class alone ten times on both fixes: the night fixture 10 of 10, the new one 10 of 10. The
        class's third fixture, the hungry one, failed once in the ten on a separate cause, fixed as E-08e (below).
      - Where this page is wrong: it gives one failure for this fixture; it had two causes, either of which ended in
        "Its body" (a timeout at the scheduled stop, not a death).
    - **Found in those runs: the hungry fixture stranded by its scaffold (E-08e).**
      - In run 5 its base stood on its field's last row, three blocks above the ground beyond.
      - Taking the scaffold down, the planner chose a spot down off that edge: it could walk there and not back. Stood
        there, the last scaffold was out of reach and so was its bed.
      - Now a spot off the build and its ring must be one it can walk back from: E-08e, with a planner test that fails
        before and passes after. See E-08e's evidence for its commit.
    - **The pit climb: the fix is on `modest-faraday`.**
      - In both failing suite runs (s53, v1) it fails at "Down in the pit": the player never saw the iron ore at the
        staircase's foot. It looked about, explored and branch-mined at the top, broke its last pickaxe there, and
        then climbed out for logs.
      - `modest-faraday`'s `292852f` ("Ore not seen past open stone") fixes exactly that, giving cave floors sight
        lines of their own, and clears the sky over the staircase fixtures.
      - It spans `WorldKnowledge` and the fixture's file, in a commit that also changes `PlayerRuntime`, all files that
        branch changes, so it waits for the merge.
    - **The pasture's cattle: reproduced alone, cause found (2026-10-30); open.**
      - It failed in suite runs 46, 48 and 49 with "the player at none": its body was gone at the scheduled stop, so a
        timeout while still leading cows.
      - It passed in all seven suite runs since (s52 to s56, v1, v2). No commit names a fix, and its cause is not
        established.
      - 2026-10-30: it failed again in the suite on the portal run's slices (F-25c4a to h), and once in three runs
        alone after. Alone, the player went "To the gate", waited for the cow, went "Back out through the doorway for
        the cow" and "Through the doorway, the cow behind", over and over until the fixture ran out. The cow stood a
        block west of the doorway, at the wall's foot outside, and never came through.
      - The cause, from both cows' positions, paths and goals logged every second in five more runs alone (one
        failed): tempted together, the pair came up side by side to the doorway, one block wide, at 0.0 and 1.0 from
        its west edge. Each overhung the wall beside it, so neither could step in past the other. Their paths reached
        the player inside, but they stood there for thousands of ticks; going back out for them led them up to the same
        jam. In the runs that passed they came one after the other. (Back at the doorway the player also never waited
        for them in line: the wait's clock, `Ranch.alignSince`, is reset only on the first walk up to it.)
      - A doorway two wide was tried and taken back. The pair came in together, then jammed the same way at the pen's
        gate, one wide: 2 of 8 runs alone failed, one of them so. A pair brought through any opening one wide needs
        leading so that one comes first. Still open.
    - **A base's roof stairs (#82): `28395df`'s fix has now run natively.**
      - The keep passed in all seven suite runs on this branch since it.
      - CI run 223 on `main` (which has the fix) failed the keep, but CI's log gives only the test's name: the results
        file, with the message, is not kept.
    - **The portal restart: the fix is on `modest-faraday`.**
      - It failed in s54, s55 and s56 with three different messages: a blaze fight's receipt, "No walk back into the
        portal admitted", and the return portal's 8:1 region.
      - `9f354b4` fixes the walk back; it is not on this branch, so it waits for the merge.
    - The CI run 223 failure "the build that fetches its shortfall" is not in this list: it set out with 77 of 112
      blocks placed where the fixture asks for 79. It passed here in all four of its runs this session, alone.
    - Recommended, not done: keep `companion-server-adapter/build/gametest-results.xml` as a CI artifact. Without it
      no CI failure can be root-caused from GitHub.
22. **The suite takes about an hour on four cores** and did not finish inside 70 minutes when it shared them (run 51).
    Check that the GitHub workflow, which runs the whole suite on every push, is green on `main` and how long it takes;
    consider splitting the gametest job or running the long fixtures nightly.
    **Checked.** `main` has been red on every run: the build (17 min) and the testkit pass, then the native suite fails.
    Run 223 on `91f408a` failed 6 of 379 in 41 minutes: the lava-pool portal cast, the build that fetches its
    shortfall, the walled keep, the night-and-bed fixture, the furnished cabin and the off-line librarian bring. All
    six are in validation's open lists, and several have fixes on `modest-faraday`. Done in `fa0466b`: a newer push to a
    branch cancels the run it supersedes (pushes had queued an hour each). Splitting the job, or moving the long
    fixtures to a nightly run, is the owner's call.
23. **Five fixtures use `createMockCreativeServerPlayerInWorld`, deprecated for removal** (`NativeGazeGameTests`,
    `NativeVoiceGameTests`, `CompanionGameTests`, `NativeCombatGameTests`); `NativePlayerBody` compiles with unchecked
    warnings. Clear them before the next Fabric API bump forces it.
    **Done in `a841f6b`:** a `MockPlayers` helper built from the same, non-deprecated parts; the one unchecked cast replaced.
    The six classes involved (crafting, combat, gaze, voice, ask, companion) passed 45 of 45, three times.

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

On `147cb0f` in a clean scratch worktree, Linux x64 cloud container, OpenJDK 21, 2026-10-29:
- `./gradlew build`: 787 JUnit results, none failed, six skipped (the opt-in live model tests).
- `NativeVoiceGameTests` and `NativeAskGameTests` together, the entrypoints narrowed for the run and restored after:
  all 13 fixtures passed in one run. That agrees with the branch's own evidence; the full suite was not rerun here.
- Read in full: `ConversationAudience`, `ConversationPrompt`, `PlayerVoice`, the `Strategist` and `StrategistBridge`
  changes, the `CompanionSocials` hook and the ask dispatch in `PlayerRuntime`. The tiers are enforced in code, the
  reply is checked by the provider and again by the bridge, and an ask runs through the speaker's own command source
  into the method the command calls. Items A1 to A4 above are what the read found.

## Handoff, 30 October (`claude/loose-ends-1029`)

The page's items, A to E and G, worked in that order on `claude/loose-ends-1029`; F not touched. `modest-faraday` not
edited; where an item needed files it changes, the item waits for its merge.

### Each item

| Item | State | Commit |
| --- | --- | --- |
| A1 talking to your own player | Done: whoever may control it is its owner in the lane (F-18a) | `249f476`, docs `d9b1b91` |
| A2 FOLLOW as an ask | Not done: it needs a native follow skill first | |
| A3 coalescing drops a speaker | Done (E-20c) | `6ea5681` |
| A4 innocent refusals | Not done: no play has logged one yet | |
| A5 the voice branch re-run | Answered: 393 of 393 on `64f4ac1`; on this branch the closing suite ran all nine voice and six ask fixtures, all passed | |
| B6 the SDK in the release jar | Checked (about 48 MB more, all MIT or Apache-2.0); not bundled, the owner's call | |
| B7 paired runs | Run: one pair, then two pairs on two seeds. Fewer deaths with the strategist on, no more progress; too few runs to say it helps | `fbdb8ed` (death logging) |
| B8 a non-Anthropic provider live | Not run: no such key here | |
| B9 follow-ups fetched | Closed: a queued shield is crafted from the follow-up, and without it no shield | `e375d25` |
| B10 habits without the model | Not done: after `modest-faraday` merges | |
| B11 clean speech | Waits on E-18 and E-19 | |
| B12 `ModelGateway`, `ReasoningPolicy` | The owner's decision | |
| C13 the starters' personalities | Done (E-10d) | `21a8a3d` |
| C14 old worlds' Vesp | Not done, by choice: the owner's call, after `modest-faraday` | |
| C15 "AI" in the command and panel | The owner's decision | |
| C16 the guide's Vesp | Done | `5fdb7bd` |
| D17 plain comments | Done but for `modest-faraday`'s twenty files | `b4fe930` |
| D18 README's vanilla wording | Done | `0fe4b39` |
| D19, D20 download, GitHub settings | Owner only | |
| E21 intermittent fixtures | The night-and-bed fixture: two causes, both fixed (E-08d, F-25c9a). Found doing it: the hungry fixture stranded by its scaffold (E-08e). The pit climb and the portal restart: fixes on `modest-faraday`. The cattle: reproduced alone, cause found (a pair side by side jams any opening one wide), open. The keep's roof stairs: failed again in the closing suite (8 short), open | `2b1f325`, `bc1690a`, `323d852` |
| E22 the suite's hour on CI | Checked; a newer push now cancels the run it supersedes. Splitting the job is the owner's call | `fa0466b` |
| E23 the deprecated mock player | Done | `a841f6b` |
| G an open feature slice | F-25c9b: hungry, food from its own storage before it hunts | `5ebb9f7` |
| Per-death logging (for B7) | Done | `fbdb8ed` |

### Validation

- On `fbdb8ed`, nothing else running:
  - `./gradlew build`: 789 JUnit results, none failed, six skipped (the opt-in live model tests).
  - `:companion-testkit:run` and `:companion-testkit:runPersonalityDemo`: both pass.
  - The full native suite: 396 of 400 in 58 minutes. Against validation's open list:
    - three failures open before this session: the lava-pool portal cast; the keep's roof stairs (#82); the pit
      climb (`modest-faraday`'s `292852f`);
    - one this session's: F-25c9a's second-night fixture ran out before morning, because other fixtures' players
      were awake and vanilla skips a night only when all sleep.
- `4d0884a`, the last code change, gives both night fixtures a whole night's time. With
  `NativePortalCampaignGameTests` beside them: 56 of 56. No full suite has run on it.
- Each fix's fixture failed on the code before it and passed after: see validation's section for this branch and
  each item's evidence page.
- Live, on `4d0884a`: B7's two pairs (item 7), no portal lit, the furthest at obsidian.

### The owner's decisions

- F24, F25: carving `PlayerRuntime` into collaborators; a retirement path for the mob body.
- A1: I took the lane fix (whoever may control a player is its owner when it talks). If talking to your own player
  should stay public-only, revert `249f476` and say so in the README.
- C14: whether old worlds' Vesp is renamed at all; if so, a tested migration after `modest-faraday`.
- C15: an alias for `/aiplayer`, and the panel's wording.
- Also: B6 (bundle the SDK, an add-on jar, or neither), B12, D19, D20, E22 (split the suite or run it nightly).
- Recommended: keep `companion-server-adapter/build/gametest-results.xml` as a CI artifact. Without it no CI failure
  can be traced from GitHub.

### Next dependency

- Merging `modest-faraday` unblocks the pit climb (`292852f`), the portal restart (`9f354b4`), D17's last twenty
  files, B10 and C14.
- Then the core playthrough: no live run in an ordinary world has yet lit a portal it earned. F-25c4a to F-25c4h,
  claimed in the tracker, are the fixes the second pairs pointed to: deaths under cover, night waits below, fuel, food,
  the table loop, gravel overhead, and the cast site. Each needs its fixture failing before and passing after, then live
  runs on both seeds.

## Handoff, later on 30 October 2026: the portal run cleaned, then checked in play

The owner's next block: back to the core playthrough, clean and complete.

### What was done

| Item | Outcome | Commits |
| --- | --- | --- |
| F-25c4a to h, the slices B7's second pairs pointed to | Each fixture failed before its fix and passed after, 10 of 10 in each of three runs alone; the full suite 402 of 405, its three failures in other classes and older | `fb02bc3` |
| Live runs on `fb02bc3`, both seeds, strategist off | b, c, e, g and h met their in-play rows and are complete; a was complete on its fixtures. d was not met. f is shown natively only, and the iron's load now names its fuel, so the next runs can show it | `10a60d5` |
| F-25c4i, the trip's wood | Found in those runs: three of five players stranded below with no pickaxe and no sticks. The fixture fails before and passes after, 3 of 3; the planner case fails with either half switched off | `10a60d5` |
| The pasture's cattle (E21) | Cause found: a pair side by side jams any opening one wide. A doorway two wide only moved the jam to the pen's gate, so it was put back. Open | `10a60d5` |
| Live runs on `10a60d5`, both seeds, strategist off | d, f and i met their in-play rows: all of F-25c4a to i are complete. No player stranded, no stick burnt, the first seed's coal hunts gone; 6 deaths in the two runs against 8 on `fb02bc3`. No portal lit | run on `10a60d5`; recorded in the commit after `5685f08` |

### Validation

- `fb02bc3`: `./gradlew build` 792, none failed, six skipped; testkit and personality demo pass; the full native suite 402 of 405.
- The working tree after it, as committed in `10a60d5`: `:companion-core:test` 615 of 615; the full native suite 401 of 406. Its five failures, alone after, are all intermittents seen before; one, the librarian off the line, fails 2 of 3 on the code before too.
- The live table, against B7's: validation's section for this block.

### Next dependency

- The lit portal: from the runs on `10a60d5`, what stops it now (validation's section for this block):
  - Kindred's furnace collect that was asked for and never carried out, seven times until the watchdog (28% of its
    time on the first seed): no action and no log line, so it begins with logging the smelt action's admission;
  - lava for the cast looked for down to Y=−55 (25% of Kindred's time on the second seed);
  - a climb for water at night, undone by the night's dig-in (Sol, 18%);
  - deaths holing up or backing off in the caves under spawn, and drops never picked up after a mob kill (`died()`
    skips them, so each such death starts over from logs);
  - falls into caves while digging stone near spawn (Echo; not reproduced in a fixture).
- The cattle: lead a pair so that one comes first through an opening one wide.
- `modest-faraday`'s merge, as before.

## Handoff, evening of 30 October 2026: what stopped the lit portal, worked on

The owner: work through what is left, leaving the decisions only the owner can make (they are as listed above).

### What was done

| Item | Outcome | Commits |
| --- | --- | --- |
| Kindred's stall on the first seed (the furnace collect never carried out) | Not explained. Four places in the code could drop a step without a word. They are the decision gates, the dispatch's asynchronous half, a step that fails its still-executable check, and a team task that cannot be claimed. Each now logs, and a player that starts nothing on its goal for a minute logs its gates once (F-25c4m, open). It did not recur on `ace1d80` | `ace1d80` |
| Lava for the cast | Lava seen beyond one walk's reach is walked to a leg at a time. After both buckets are made, a worn-out iron pickaxe is not made again (F-25c4j, open: no player knew of far lava in the runs). Fixture and planner case fail before and pass after | `ace1d80` |
| Water at night | Below ground at night, water at the surface waits for daylight (F-25c4k, complete). The two players that went up and down 147 and 107 times between the climb and the dig-in now wait below | `ace1d80` |
| Drops after a mob kill | Gone back for once no hostile mob has them in sight (F-25c4l, complete). Two mob kills were gone back for in play, where none ever was, though neither kit came back | `ace1d80` |
| The night waited out with both buckets empty (Sol, 17%) | The cast's lava, known below within a walk, fetched meanwhile (F-25c4n, open: the live runs). Fixture fails before, 3 of 3 after | `a286cc8` |
| The pasture's cattle | The line-up wait begun afresh each time it backs out, and a pair abreast waited for one behind the other: 8 of 10 alone. Neither failure was the pair abreast: one cow never came through, as before. Not committed; open | none |
| The pasture's cattle, again | Logged once a second over ten runs alone on the committed code (4 of 10): every failure was the pair jammed side by side at the doorway's mouth. A pair jammed so at a way in one wide is now let wander off with the food put away, and a wait that puts the food away does so (M-06a1, open). 9 of 10, then 8 of 10; the two left are the doorway's shut walk refused and a pair not ready to breed, once each | the commit after `f4f4aee` |
| Live runs on `ace1d80`, both seeds, strategist off | 3 deaths against 6 on `10a60d5`; no stall; the first lava bucket for a cast (Echo, at Y=-55; the run ended as it cleared the frame's site). No portal lit | |
| A long run on `a286cc8`, seed 8675309, 240,000 ticks, strategist off | No portal lit; Echo got as far as looking for the cast's lava. Four deaths, deep below spawn or at night; two walks back refused, none gone back for to any end. Kindred never left the iron: four iron pickaxes made, 62% of its time mining iron. Sol's stall line twice, with no step left at Y=47 (F-25c4m) | |
| Sol out of the world for that run's last seventeen minutes | Its stone pickaxe, fought with for want of a sword, broke on the blow that killed a zombie. The fight's record took it for an outcome to reconcile, and the body was taken out. A weapon, tool, armour piece or shield worn out in a fight is now lost as wear (F-25c4o, open: the live runs). Fixture fails before, 3 of 3 after | `7a510b2` |
| Echo's death in that run, one iron short of its flint and steel and digging down for the cast's lava | It jumped twenty-four blocks to break the fall with water, and its goal's fights with a zombie and a creeper outranked the water: a goal's steps are ranked with the player's liking for mining, and Echo's is 95. A fall to break with water now comes before anything else (F-25c4p, open: the live runs). Fixture fails before, 3 of 3 after | `011a579` |
| Kindred's four iron pickaxes in that run, and never a bucket | The goal gathers all of a route's iron before the buckets, but made the iron pickaxe from the first three. Each wore out before the rest was found, and the next three went to another. Where the frame is cast it is now made with the rest of the iron (F-25c4q, open: the live runs). The planner case fails before with the same loop; core 617 of 617; the goal's fixtures 64 of 64, two restaged | `03faab9` |
| Kindred's death in that run, to a zombie it fought with an empty hand, an iron pickaxe in its hotbar | A fight given no weapon struck with whatever was left in hand. Now it strikes with what in its hotbar hits hardest (F-25c4r, open: the live runs). Fixture fails before, 3 of 3 after | `03faab9` |
| A live run on `03faab9`, seed 8675309, 240,000 ticks, strategist off (the second seed left until its findings were fixed) | No portal lit; Echo got as far as digging down for the cast's lava. Ten deaths, eight Sol's, most in one cave at Y=36 to 39. No fall broken, no fight ending in a stop. Three new losses, below | |
| Kindred in a flooded pit for 46 minutes, until it drowned | It had mined coal that let the water in. With no dry ground in reach its climb out was cut short each time, some 250 times a minute. Now, swimming with no dry ground to reach, it climbs out: to the pit's side across the water, a step dug from the water's top cell, treading water while it digs (F-25c4t, open: the live runs). Fixture fails before (it drowned), 10 of 10 after | the commit after `03faab9` |
| Sol out of the world for that run's last half hour | A seal of dirt was recorded as an outcome that could not be verified. The same record and stop come when the block clicked against is gone before the placement acts, and vanilla puts the block in its cell. That block must now still be there, and an unverifiable outcome records what changed (F-25c4u, open: the live runs). Fixture fails before, 3 of 3 after | the commit after `03faab9` |
| Sol's three walks back to its drops, each stopped short of the one it went for | A drop a walk back ends short of is now passed over for the next nearest, three at most (F-25c4s, open: the live runs). Fixture fails before, 3 of 3 after | the commit after `03faab9` |
| A live pair on `458d192`, both seeds, 240,000 ticks each, strategist off | No portal lit. Furthest yet: on the second seed Echo cast ten obsidian at Y=-55, in two frames, and died there unlit. No reconciliation stop on either seed. F-25c4q and r complete in play: each iron pickaxe made once a life with the buckets, and none of 40 fights fought with an empty hand. On the second seed Echo was refused room on the spawn point and joined at tick 57,000 | |
| Kindred's eight deaths in one cave on the first seed, each on its way back for what it had dropped before | 11 of that seed's 21 deaths came while a walk back for the last drops was under way. Dead again before it has its things back, it now leaves those drops too (F-25c4v, open: the live runs). Fixture fails before, 3 of 3 after | the commit after `458d192` |
| Echo wading into an underground lake and back out 107 times in twelve minutes, from a pocket it had dug beside it | Water level with its own floor is no longer a jump off a ledge: the swim out took it straight back (F-25c4w, open: the live runs). Fixture fails before, 3 of 3 after. The pocket itself had no way out but by water, Echo having dug out its own staircase's lowest step | the commit after `458d192` |
| 44 of 52 deaths since `fb02bc3` with no armour on | Where there are caves, the chestplate is made from the first eight iron, before the rest is looked for, and its own smelt counts in the packed fuel (F-25c4x, open: the live runs). The planner case fails before; `PortalRunPlannerTest` 39 of 39 | the commit after `458d192` |
| Echo's frame given up with six of its ten cast, and another begun beside it | Back from the lava, a cell to dig before the next cast was out of reach, and the frame was given up. It is now approached first, as the casts are (F-25c4y, open: the live runs). Fixture fails before (split frames, none lit), 3 of 3 after, the other casts 5 of 5 | the commit after `458d192` |
| A live pair on `a4df9b4`, both seeds, 240,000 ticks each, strategist off | No portal lit and no lava bucket filled: on the second seed Kindred branch-mined at Y=-55 for thirteen minutes without coming on lava. 18 deaths against 24 on `458d192`, six of the first seed's in the caves under the spawn point. F-25c4v and x complete in play: three second deaths before the drops were back, none gone back for; every life with eight iron made its chestplate first, and 7 of 18 deaths came with one on. w and y not met. No reconciliation stop | |
| Sol walking toward Echo 7,389 times on the first seed, each walk done at once, for twenty of its forty-four minutes | A hand-over walk that ends short of tossing reach, no nearer than the last, is now a failed step, and six let the hand-over go (E-40a1, open: the live runs). Fixture (Wren walled in stone) fails before, 3 of 3 after | the commit after `a4df9b4` |
| Echo tunnelling 209 blocks of deepslate between lava lakes for room for its frame, on `458d192` | With no site found and lava at or below its feet within six blocks, it climbs up out of the lava's reach first (F-25c4z, open: the live runs). Fixture fails before, 3 of 3 after | the commit after `a4df9b4` |
| Echo setting out for the same spot on a stream's bank 173 times; Sol 375 times on `458d192` | Dry ground a walk out failed to reach is passed over for the next nearest (F-25c4aa, open: the live runs). Fixture fails before, judged by where it first stands out of the water, 3 of 3 after | the commit after `a4df9b4` |
| Four players 12 to 16% of their time climbing for food on `a4df9b4`; Sol up from Y=-2 to 107 and twice more, straight back down each time | The climb ended where the ground opened over its head, and the goal's next step dug down again. Once up out of the ground, hurt with nothing to eat, it now hunts in sight, fishes or walks out to look for animals, in the Overworld only (F-25c4ab, open: the live runs). Fixture fails before, 3 of 3 after | the commit after `033fd71` |
| No lava bucket filled on either seed of `a4df9b4`; Kindred branch-mined thirteen minutes at Y=-55 | Simulated on the pair's world saves, most of the lava its tunnels came on lay beside or under a cell they would dig, refused unseen. The search now digs to Y=-54, a block above the lava level, opens a cell with lava only under it to see the lava and fill its bucket, and runs its legs 48 long and 16 apart (F-25c4ac, open: the live runs). Fixture fails before, 3 of 3 after | `e3aa373` |
| A live pair on `41f9de9`, both seeds, 240,000 ticks each, strategist off | No portal lit and no lava bucket filled. 13 deaths against 18 on `a4df9b4`; no reconciliation stop. F-25c4aa complete in play: no spot set out for more than four times (173 before). F-25c4ab in part: 18 of 30 hungry spells came up to the search for animals, and players hunted and ate from it, but at the top of one of Echo's climbs the search and the climb took turns. Echo saw lava for the first time in two pairs, in a pocket inside a mountain, and with no walk to it set its sources aside | |
| Kindred's hand-over walk to Echo, too far for one route, refused as it was made for five minutes | A teammate beyond the team's range is waited for a minute at a time, six waits letting the hand-over go (E-40a1, open: the live runs). Fixture fails before, 3 of 3 after | `77ddbb4` |
| Echo's only sighting of lava, a pool in a pocket inside a mountain, set aside source by source as no walk led to it | Lava a walk failed to is now dug toward: a staircase to a block above the pool, then a tunnel along it that opens a cell over the lava (F-25c4ad, open: the live runs). Fixture fails before, 3 of 3 after | the commit after `0c48f53` |
| Echo's walk home, more than 160 blocks off, refused as it was made nine times | Home beyond one route's reach is headed for a leg at a time (F-25c4ae, open: the live runs). Fixture fails before, 3 of 3 after | the commit after `0c48f53` |
| Echo drowned at Y=8, its walk up for air stopped to fight a mob, and eating begun and cancelled a dozen times | Short of breath, the way out of the water is not stopped for a fight, nor for eating (F-25c4af, open: the live runs). Fixture fails before, 3 of 3 after | the commit after `0c48f53` |
| Echo at the top of a climb for food switching between the climb and the search several times a second for a minute | Once up, ground near where it last had open sky counts as up, and the search holds (F-25c4ag, open: the live runs). Fixture fails before, 3 of 3 after | the commit after `0c48f53` |
| Kindred under a lake for eighteen minutes, wanting water, going round between a step up with no way on, the wall and the way back | Under water, every step up refused by it, the climb now tunnels on one way until the ground over its head is dry and climbs out at the shore, and a climb for water goes on to the top of its staircase (F-25c4ah, open: the live runs). Fixture fails before, 3 of 3 after | the commit after `f59492d` |
| A live pair on `64c298d`, both seeds, 240,000 ticks each, strategist off | No portal lit. Lava buckets again after two pairs with none: Kindred one at a cell its branch mine opened over lava, Sol sixteen at a pool it dug toward (F-25c4ac complete in play). Sol cast 15 obsidian in two frames at Y=-54, the second eight blocks and both top corners. 10 deaths, all on the first seed; none drowned | |
| Sol's frame stood with its top two blocks uncast for seven minutes, the cast refused from every stand | Under rock, every line from below to the water over a top block ran through the block once it hardened. The cell in front of that water is now opened first (F-25c4ai, open: the live runs). Fixture fails before (8 obsidian, no portal), 3 of 3 after | the commit after `f59492d` |
| Kindred's 17 frame sites given up in two minutes, its bucket filled at Y=-54 | Rebuilt from the save: on its stand, the row in front's first cell was walled in behind the next. Each pass now digs first what it can break, then goes to the stand or beside the cell, and only then gives a site up (F-25c4aj, open: the live runs). Fixture, the spot staged from the save, fails before (111 sites given up), 3 of 3 after | the commit after `238ff1e` |
| A live pair on `c432fba`, both seeds, 240,000 ticks each, strategist off | **The first earned lit portals**: Kindred's on the first seed (about tick 236,000), Sol's on the second (about tick 150,000), each cast from lava at Y=-54 and Y=-53 with its own buckets and lit with its own flint and steel. F-25c4ai complete in play; 8 frame sites given up against 49. 8 deaths | |
| Kindred's 81 blocks of tunnel past the lava it saw across a cave on `64c298d` | Rebuilt from the save: a cave opened south of its tunnel at the lava's level the whole way. At that level it now goes only the two ways that close on the lava, a drop bridged with its own block (F-25c4ak, open: the live runs). Fixture fails before (33 blocks west), 3 of 3 after | the commit after `c432fba` |
| Kindred and Sol digging away from their lit portals on `c432fba` | On the Nether goal a lit portal is the end: nothing more is planned, and the watchdog stays off (F-25c4al, open: the live runs). Fixture fails before, 3 of 3 after | the commit after `c432fba` |
| Echo starting nothing for twenty-seven minutes at a lava pool's edge on `c432fba` | Its step off was refused every tick: a straight block on from off the middle of its cell overlapped the wall beside the safe cell, its work stopped meanwhile as a hazard. The step now goes to the middle of the cell found safe (F-25c4am, open: the live runs). Fixture fails before, 3 of 3 after | the commit after `21046b0` |
| A live run on the dragon goal | Refused on the dev server until an operator turns the dragon fight on (`/aiplayer dragonfight on`); its players ran with no goal and were killed at spawn 67 times over the first two nights, 59 of them by husks. Stopped; the next runs use the End goal | |
| A live run on `91c1290`, seed 31102026 (a desert), the End goal, strategist off | Stopped at about tick 175,000 with no player past wood. Sol cut the two trees found and lost its kit at each death. Four deaths, two of them while waiting below ground for daylight; 34 to 39% of each player's time waiting for daylight below ground. Next fix: logs are seen only 32 blocks round, and walks to look keep within 192 blocks of home (F-25c4ap) | |
| The watchdog's moves elsewhere digging back and forth (Q28; Sol for 8 minutes on `c432fba`, Kindred on `91c1290`) | Only along the ways that close on the new place, never back down a level it climbed to, and no walk to a wall; the plan's turn after each; the cut-through never steps down (F-25c4an, open: the live runs). Fixture fails before, 3 of 3 after | the commit after `91c1290` |
| Kindred under the desert's sand, between the step up and the wall | The block over each step's head dug first, what falls let settle, the climb held while below the rim (F-25c4ao, open: the live runs). Fixture fails before, 3 of 3 after | the commit after `91c1290` |
| No player on the desert seed finding trees | With no wood, on open ground it sees trees on the skyline out to 96 blocks and walks to the nearest, past the bound of its walks to look (F-25c4ap, open: the live runs). Fixture fails before, 3 of 3 after | the commit after `3c184b6` |
| The climbs' holds held before any climb was made | Their "long ago" was the least long, and a tick less it overflowed: on open ground at the foot of a two-high step it climbed instead of looking about (F-25c4ao's since `3c184b6`, F-25c4ah's since `238ff1e`). Now half the least long. Fixture fails before, 3 of 3 after | the commit after `3c184b6` |
| The cattle fixture's other two failures, once in ten each (M-06a1) | A cow left in the gateway as the gate shut is fetched in, not bred without; a player pressed against the pen's fence from inside, its middle in the fence's block, is in the pen, so the ranch is no longer done with it shut in. A unit case and a fixture, each failing before; the cattle ten times with both queued | the commit after `00e98f5` |
| On the desert seed on `00e98f5` (P22): Echo's only tree standing in a lake, and its watchdog's moves home refused as too far for one route; from P21, the night's dig-ins left open with only sand | The night's seal made with sand or gravel where it rests (F-25c4aq): its fixture fails before, 3 of 3 after. The far move a leg at a time, each walk judged once it has ended (F-25c4ar): with the leg alone its fixture still failed, the first walk judged before it had run; with both, 3 of 3. The tree chopped from the water, treading water, the log in reach from the water before the way ashore (F-25c4as): with the first two parts its fixture still failed, the plan sending it ashore before the log; with the third, 3 of 3. Its criterion was narrowed to the log it can see: the stump's lower log, every face under water, is not one it can know of (said so in its evidence) | `3067b0f`, then this block's commit |
| Kindred sealed in for the last fifty minutes of the desert seed on `00e98f5` (65 stall lines) | Sealed into its night shelter beside a lake at Y=45: every walk out refused by its own seal, every step up by the water beside the cell over its head. Now its own seal is the climb's last resort, and a refused walk back gives the climb its turn (F-25c4at). Its fixture fails before, 3 of 3 after | `2bceb1f` |
| Kindred walled in for the last twenty-two minutes of 8675309 on `00e98f5`, beside a mineshaft's cave-spider spawner | Walled in a minute with the danger still about, it digs away from it on its own level, sealing toward it (F-25c4au). Its fixture fails before, 3 of 3 after | this block's commit |
| The cattle's last failure, once in ten on `2bceb1f`: "not ready to breed again yet", one cow pushed up onto the shut gate as the other was fed | In breeding, with fewer than two in the pen it fetches again (M-06a1). A unit case fails before; the cattle 10 of 10 alone after, the ranch's pen 3 of 3 | this block's commit |

### Validation

- `ace1d80`: `:companion-core:test` 616 of 616. The full native suite 404 of 409, with `NativePortalCampaignGameTests` 61 of 61. The five failures were the cattle, the keep's roof stairs, the ranch's pen (2 of 3 alone after), the librarian off the line, and the second-night fixture, out of time with other fixtures' players awake.
- The F-25c4n fixture fails on `ace1d80` and passes 3 of 3 after; the F-25c4j, k and l fixtures pass on the same code.
- The live table: validation's section for this block.
- On top of `a286cc8`: the F-25c4o fixture fails before and passes 3 of 3 after; `NativePortalCampaignGameTests`, `NativeCombatGameTests` and `NativeEndGameTests` alone 77 of 77; `build` successful, with `:companion-core:test` 616 of 616.
- On top of `7a510b2`: the F-25c4p fixture fails before ("Unhurt by a twenty-two block fall: 1.0") and passes 3 of 3 after; the fall, portal, compound, follow-up and team-portal fixture classes alone: 79 of 79.
- On top of `011a579`: the F-25c4q planner case fails before and passes after, `:companion-core:test` 617 of 617, the goal's fixture classes 64 of 64; the F-25c4r fixture fails before and passes 3 of 3 after, the fixture classes with fights alone 89 of 89.
- On top of `03faab9`: the F-25c4s and u fixtures fail before and pass 3 of 3 after, the F-25c4t fixture fails before and passes 10 of 10; the full native suite 414 of 416: the pasture's cattle, known, and `goalClimbsOutOfAPitBelowItsWayBackWithNoPickaxe`, which never saw its ore from the start (10 of 10 alone after, the neighbouring plot its own doc names).
- On top of `458d192`: the F-25c4v, w and y fixtures fail before and pass 3 of 3 after; the F-25c4x planner case fails before and passes, `build` successful with `:companion-core:test` 618 of 618; the full native suite before F-25c4y 416 of 418, the cattle's two; the other cast fixtures with F-25c4y alone, 5 of 5.
- On top of `a4df9b4`: the E-40a1, F-25c4z and F-25c4aa fixtures fail before and pass 3 of 3 after; `build` successful; the full native suite 421 of 422: the one failure, the life-first fixture that hunts before building its base (its cabin's stairs out of reach), passed 3 of 3 alone after, its cause not found; the cattle passed.
- On top of `033fd71`: the F-25c4ab fixture fails before and passes 3 of 3 after; the fixtures that start hurt or climb for food, alone, 6 of 6; `build` successful. The life-first fixture that hunts before building its base failed once alone with it, its cabin's top out of reach, as in the suite before; the step changed does not run in it.
- On top of `41f9de9`: the F-25c4ac and E-40a1 far-teammate fixtures fail before and pass 3 of 3 after; the lava and team fixtures beside them alone, 6 of 6; the full native suite on `77ddbb4`, 419 of 425. The six failures ran while log and world-save analyses ran on the same cores, three back to back as a log report ran; alone on the same code the follow-up without a shield queued, the ruined portal's chest, the long fall broken with water, the route that bridges a gap and digs through a wall, and the librarian off the line pass, 5 of 5. The second-night fixture, 42,000 ticks long, was not run alone: its cabin's repair ended short, its roof stairs out of reach, as the life-first fixture's has before
- On top of `0c48f53`: the F-25c4ad, ae, af and ag fixtures fail before and pass 3 of 3 after (F-25c4ad's 10 of 10); F-25c4af's each half alone fails it; the lava, logs, water and fight, and food fixtures beside them alone: 4 of 4, 1 of 1, 7 of 7 and 4 of 4; the four short fixtures the suite on `77ddbb4` failed pass alone on this code too, 4 of 4. `build` successful: the adapter compiles and remaps, and `:companion-core:test` stands at 618 of 618, the core unchanged.
- On top of `64c298d` and `f59492d`: the F-25c4ah and ai fixtures fail before and pass 3 of 3 after; with F-25c4ah's tunnel alone its fixture failed too, at the top of its stair; the water climbs and the casts beside them alone, 7 of 7 and 4 of 4; the climbs for logs and food 4 of 4 on this code less the water step's hold; `build` successful.
- On top of `238ff1e`: the F-25c4aj fixture, staged from the save, fails before and passes 3 of 3 after; the casts beside it alone 7 of 7; `build` successful.
- On top of `c432fba`: the F-25c4ak and al fixtures fail before and pass 3 of 3 after; the lava and portal fixtures beside them alone, 7 of 7; `build` successful.
- On top of `21046b0`: the F-25c4am fixture fails before and passes 3 of 3 after; the steps off hazards beside it alone, 6 of 6; `build` successful.
- On top of `91c1290`: the F-25c4an and F-25c4ao fixtures fail before and pass 3 of 3 after; the watchdog's, climbs' and tunnels' fixtures beside them alone, 8 of 8; `build` successful.
- On top of `3c184b6`: the F-25c4ap fixture and the long-ago fixture fail before and pass 3 of 3 after; the log, water and food searches and the climbs beside them alone, 12 of 12; `build` successful.
- On top of `00e98f5`: `RanchTest` 14 of 14, its gateway case failing with the change set aside; the fence fixture fails 3 of 3 before and passes 3 of 3 after; `build` successful.
- Live, on `00e98f5` (P22): seeds 31102026 and 8675309, 400,000 ticks each, the End goal, strategist off. No portal lit; on the desert seed all three had wood in the first minute (none got past wood there on `91c1290`); 5 and 11 deaths. The rest: validation's section for this block.
- On `2bceb1f` and the code after it: the F-25c4aq, ar, as, at and au fixtures each fail 3 of 3 before and pass 3 of 3 alone after; their neighbours alone, 10 of 10, 11 of 11, 8 of 8 and 2 of 2. F-25c4ar and as each needed a second part after their first form failed after; F-25c4as's criterion was narrowed to the log it can see. The cattle 9 of 10 with the gateway and fence fixed, then, its third cause fixed (a unit case failing before, `RanchTest` 15 of 15), 10 of 10 alone; M-06a1 ticked. `build` successful, 801 JUnit results, none failed, six skipped.

### Next dependency

- The lit portal, from the runs on `ace1d80` to `41f9de9`:
  - the lit portal is reached (`c432fba`, both seeds); on a third seed, 31102026, a desert, no player got past wood in 175,000 ticks (`91c1290`). Next: that seed again, with trees seen on the skyline (F-25c4ap); then pairs on the End goal (the dragon goal waits on an operator turning the dragon fight on), longer than 240,000 ticks, for the Nether onward (F-25c6, the blaze rods and pearls, the stronghold and the End); F-25c4ak, al, am, an, ao and ap, and F-25c4ah, ad, ae, af and ag in play;
  - open from the pairs: the watchdog's spells now dig only toward the new place and leave the plan its turn (F-25c4an; Echo's 59% of a seed on `c432fba` was mostly the retreat refused at a lava pool's edge, F-25c4am); deaths while waiting below ground for daylight (two on `91c1290`); hurt and out of food 20 to 34% of a seed, once punching up by hand with no pickaxe; waiting for daylight below ground 25 to 29% (34 to 39% on the desert seed); Sol's first frame dropped with seven cast on `64c298d` (the cast's give-up now logged); a hand-over let go after six waits and proposed again at once (E-40a1);
  - food at depth: the search once up now holds near where the player last had open sky (F-25c4ag), to be seen in play; a hungry player still goes down with little food where few animals are about;
  - water: at night in a pocket of flowing water, the wait for daylight and the swim out took turns (Sol, four minutes on `41f9de9`), every way out of the water blocked;
  - the nights: 12 to 28% of a player's time waiting for daylight below ground for its water. A lava search meanwhile would use it, but the frame must then be cast by the lava, not where the water is filled, or each bucket is a trip from the surface; F-25c4n's night fill has the same question;
  - deaths in caves (one drowning, fixed natively by F-25c4af); the watchdog's walks elsewhere; the stall's other causes (F-25c4m).
- Not yet checked alone: the keep and the second-night fixture, each failing once in the suite on `ace1d80`, the second-night fixture again on `77ddbb4`, its cabin's roof stairs out of reach; the ranch's pen failed again in the suite on `458d192`; the life-first fixture that hunts before building its base, its cabin's top out of reach, once in the suite on `033fd71` and once alone on `41f9de9`'s code. The cabin's top out of reach is the likeliest common cause of the three. In a suite, analysis on the same cores also costs fixtures: on `77ddbb4` three failed back to back as a log report ran, and passed alone.
- The cattle (M-06a1, E21): done, 10 of 10 alone; the full native suite's run of it is still to be seen.
- From P22, the live runs of F-25c4aq to au; the climb that went step up, back the way it came, to the wall and up again (Kindred, three minutes on the desert seed), not taken further; deaths in caves, 11 on 8675309.
- `modest-faraday`'s merge, as before.

## Handoff, 1 November 2026: the next levers (`claude/loose-ends-1029`)

The owner: F-26, F-27, E-46, F-30, F-29, E-48, E-49, F-28 and E-47, in that order, each finished before the next, each
"what exists" claim checked against the code; D9 and D10 answered yes. The design is
[next-levers](../proposals/next-levers.md); each feature's evidence says where the code differed from it.

### Each feature

| Feature | State | Commits |
| --- | --- | --- |
| F-26 The evaluation engine | Complete (1 November): `KINDRED_EVAL`, `eval-result.json` and `tools/eval`; the two-game-hour run, the night fix against the build before it (no clear difference), the E-49 build's arms, and the final build's ten seeds, every run complete and earned | `093d188`, `c8b3500`, `af57c2e`, `60cb3a7`, `5dc37b9`, `1987f45`, `3c5ad99`, `4d67c9c`, the summaries in this handoff's commit |
| F-27 Death replay | Complete (31 October): the capture within a tick, the generator, the drowning replay 3 of 3 dead before its fix and 3 of 3 alive after | `597f0c6`, `6af8fe6`, `56d53fe` |
| E-46 Lessons from its own play | Complete (1 November): six local rules over its own deaths and breakages, each a note with its habit; ten seeds with lessons on against off, no clear difference (the team's deaths a game hour 1.5 [0.0 to 4.0] on, 0.75 [0.0 to 3.0] off; 25 lessons learned) | `56d53fe`, `5cd5152`, `4d67c9c` |
| F-30 The atlas | Complete against its acceptance (1 November): ore it saw gone back to, the night cost of walks, the report line; ten seeds with it against without, no clear difference (the first iron ingot 10.75 [4.7 to 31.2] game minutes against 11.75 [5.8 to 27.3]; diamonds on 3 and 2 seeds; 112 leads back to iron). The design's other users are not built | `034e967`, `4d67c9c` |
| F-29 Rescue plans | Complete against its acceptance (1 November). The first arm found plans refused as stale (the fence now leaves out the player's own task starts) and steps misread (the instructions now say what each does). On the final build's ten seeds, rescue on against off: stuck game minutes 39.0 [3 to 63] against 42.0 [12 to 99], no clear difference; 34 asked, none stale, 4 of the 31 plans carried out got the player out | `2318c15`, `213542a`, `2449b28`, `f1a15b6`, `9e5400d`, `e049b0b` |
| E-48 The watch page | Complete (31 October): read-only, token-gated, served from a per-tick snapshot | `3dd6ba3` |
| E-49 Trust and griefing | Complete (31 October): wariness withholds asks and tosses, never strikes | `39af678` |
| F-28 The headless simulator | Complete (31 October): three packs of 100 seeds in 35.1 s; the furnace bug it found fixed; its deaths an hour against the server's corrected on 1 November to the ten seeds, 0.60 a player-hour (a factor of 3.3) | `934d5da`, `5dc37b9`, `4d67c9c` |
| E-47 Learning from the server's people | Complete (1 November): `/aiplayer watch on|off`, each person's own say; the mining depth only | `f6e30f2` |

### Found by CI and by the evaluation runs, fixed on 1 November

Each with a fixture that fails before the fix and passes after, in its evidence:
- `5cd5152` E-46: the memory, which now looks at every player, replaced a goal's follow-ups instead of merging them (the
  end fight's shield never made; CI runs 301, 305, 309).
- `1f16f1e` F-25c4as: the log cut from the water floated off and was never fetched (CI 292, 299, 309).
- `55e3227` F-25c4z: a frame site's lava check looked two blocks below, the climb's three (CI 309).
- `bb620ba` F-25c4ap: a tree known only by its top log was walked to at that log, four up, which no stand reaches
  (CI 298, 299, 313).
- `213542a` F-29: the compatible route did not ask Anthropic's server for no thinking; every rescue reply came back
  empty (the rescue arm's first try).
- `2449b28` F-29: plans came back refused as stale: the goal's own tasks move on the revision the fence compares, and
  the goal worked on while asking (the rescue arm's second try).
- `2e93546` F-25c4aj: the walled-in cell's site was given up on asks never walked: asked while its last break finished,
  the clearing marked the stand and the row in front as tried without a walk (CI 301, 313, 316). And its fixture named
  a site that F-25c4z's lava rule has refused since `55e3227`, so its passes had come from that very give-up; it now
  checks the first site the player chose. Before 0 of 4 alone, after 5 of 5; the portal class alone 94 of 94.
- `f1a15b6` F-29: with the hold, 12 of the rescue arm's first 29 asks still came back stale: every task the player
  starts of its own choosing begins on a direct lease and moved the fence's revision (an eat, a discard, its digging
  when not calm). The fence now leaves those out; a stop, a resume or a command still refuses a plan. Before 0 of 1,
  after the class 8 of 8 three times. And 11 of its 15 plans stopped at a step, 7 at a walk (4 just after a `dig up`
  taken for a climb) and 3 at a dig with no pickaxe: the instructions now say what each step does.

Still failing in CI's full runs, each analysed and open (CI 318 on `2e93546`: 479 of 480, the cabin alone): the keep's
roof stairs and the nine-by-seven cabin (#82's family); the lake librarian (bring, its villager's AI on; failed in full
runs before run 49 too); the ruined portal's chest (its goal's first step, before it had seen the chest, to a "cave"
floor two below the fixture's own: in the GameTest world every floor below sea level counts as a cave; also at
`77ddbb4`); and once each several others (validation). A fixture that fails leaves its player running to the suite's
end (31 minutes and 158 batches in run 313): a listener that stops a failed fixture's runtime would keep one failure
from loading the rest.

### Validation

- `bash ./gradlew build` green on `f1a15b6`, the final code: 846 JUnit tests, 0 failures, 6 skipped.
- Each new GameTest class alone, three runs each on `2e93546` (the gametest entrypoints narrowed for each run and
  restored after), every run passing: `NativeEvalGameTests` 2 of 2, `ReplayGameTests` 4 of 4, `NativeLessonsGameTests`
  4 of 4, `NativeAtlasGameTests` 3 of 3, `NativeRescueGameTests` 7 of 7, `NativeWatchGameTests` 3 of 3,
  `NativeTrustGameTests` 7 of 7, `NativeFurnaceWaitGameTests` 1 of 1, `NativeCustomsGameTests` 1 of 1; each with its
  before-the-fix run in its evidence. `f1a15b6` changed only the rescue's fence and instructions after that, and
  `NativeRescueGameTests` ran three times again on it, 8 of 8 each. `NativePortalCampaignGameTests` alone 94 of 94 with
  the walled-in fix.
- The full native suite on `9e5400d` (the code of `f1a15b6`; only an evaluation summary after it), nothing else on the
  cores: 476 of 481 in 56.2 minutes. Compared with the open list:
  - still failing, open before: the keep's roof stairs (#82's family, five stairs not placed), the lake librarian (bring,
    "I ran out of blocks to bridge the water"), the ruined portal's chest (its first step to a "cave" before its first
    full look; cause found, patch proposed);
  - this branch's own fixtures, each failing before in CI or a class run: the trip's wood (F-25c4i: its first look,
    before its first full sweep had seen the trees, took the first stone; the next look chose the trip's wood with the
    stone already in hand; CI 292 too) and the hungry search for animals (F-25c4ab: beside the cow, its strikes came
    several a second and the cow did not die; a class run on `bb620ba` and 31 October too; passes alone; cause not
    found, and in the twenty evaluation runs of the E-49 build no hunt took more than five strikes in a row);
  - the trip's wood fixture's player went on mining to the suite's end, as failed fixtures' players do.
- CI on the same code: run 319 on `f1a15b6` and run 320 on `e049b0b`, each 480 of 481: the walk back up when its
  pickaxe breaks (passed in the local suite; failed in CI 298 too), and the night and its own bed (open before).
- The testkit demos on `4d67c9c`: `:companion-testkit:run` (the contract demo) and `runPersonalityDemo` passed; their
  modules (core, scheduler, persistence) have not changed since.
- Ten seeds of the final build: `final-f1a15b6`, beside the rescue arm (one server each), 16:53 to 22:19, all complete
  and earned, after the first try was lost when the container restarted while no turn was running: the team's furthest
  stage 4.0 [2 to 6], deaths a game hour 1.75 [0.0 to 4.5], stuck game minutes 42.0 [12 to 99], the first iron pickaxe
  at 39.35 game minutes [22.5 to not reached], a portal lit on 1 seed. [Summary](../evidence/eval/final-f1a15b6/summary.md).

### The evaluation

Every comparison on the same ten seeds, medians and ranges, a difference worth noting only when the ranges do not
overlap. None did:

| Comparison | Build | The measure | Without | With |
| --- | --- | --- | --- | --- |
| The night fix (F-26) | `093d188` | furthest stage | 5.0 [3 to 6] | 3.5 [2 to 6] |
| Lessons (E-46) | E-49 | deaths a game hour | 0.75 [0.0 to 3.0] | 1.5 [0.0 to 4.0] |
| The atlas (F-30) | E-49 | first iron ingot, game minutes | 11.75 [5.8 to 27.3] | 10.75 [4.7 to 31.2] |
| Rescue (F-29), the first arm | E-49 | stuck game minutes | 28.5 [9 to 54] | 37.5 [15 to 84] |
| Rescue (F-29) | final, `f1a15b6` | stuck game minutes | 42.0 [12 to 99] | 39.0 [3 to 63] |

The runs' own findings matter more than these numbers: the hand-over's loop and the plans' geometry (next dependency),
and F-25c4aj's give-up gone in play (7 sites given up on the final build, each after its clearing walked; on the E-49
build 20 of 30 with none). The rescues cost about a cent a ten-seed arm (85,001 and 68,009 tokens).

### What the owner must decide

As before: F24/F25, A1, C14, C15, B6, B12, D19, D20, E22, the CI artifact, `modest-faraday`'s merge (eleven commits of
28 and 29 October, melee, pets, chores and the smelter, not on this branch) and the dragon-fight policy. New:
- **COME's "three blocks short" (E-49).** The design asked a wary player's come-to-them to stop three blocks short.
  E-20b's own fixture has the walk end within 2.6 blocks of its owner, so this would change the ask's meaning for
  everyone. Built: never beside lava, fire or a drop, and as near as it safely can be.
- **E-47's other customs.** Only the mining depth is built. Shelter time, the palette, the hours people play, the heat
  map and where to settle would each need more watched (beds, placements, positions once a minute).
- **The strategist from the release jar.** The release jar does not bundle the Anthropic SDK, so `KINDRED_STRATEGIST`
  with Anthropic's provider finds no client in it. The compatible route works from the release jar
  (`KINDRED_STRATEGIST_PROVIDER=openai-compatible` with Anthropic's `/v1` base URL), at about 6,700 input tokens a
  consultation, none of them cached; since `213542a` it asks for no thinking there, without which every rescue reply
  came back empty. Bundle the SDK, or document the compatible route as the way.

### What changed from the design, and why

- F-26: the bootstrap waits for the runtime's records before it spawns; the voice has no switch of its own; tokens are
  one count for the strategist and the voice together. The night-fix comparison's "before" is the same commit with
  F-25c4aq and F-25c4at taken out, since the commit before them had no bootstrap. Four faults of the engine's own,
  found by its runs and fixed: a starter refused its spot on a tree's crown (seed 404, in every ten-seed run until
  then), the midnight log rollover, a fixture stale since E-46, and times to a stage taken over the seeds that reached
  it only. The arms for E-46 and F-30, and F-29's first, ran on the E-49 build (`39af678`), not on the final build, to fit
  the time; each is compared within that build, and seed 404 was run again with the spawn fix. F-29's measure ran on
  the final build, its two arms side by side.
- F-27: the structure copy reads block states off the thread rather than vanilla's `saveFromWorld` (58 to 71 ms on the
  thread); captures run 575 KB to 1 MB, not under 200 KB; a replay's player is made on the nearest cell the runtime
  takes.
- E-46: deaths were kept only while a strategist ran; the memory now runs without one. A broken pickaxe is an episode
  read from vanilla's statistics, not a death's field. The torch habit raises `light`'s line rather than counting
  blocks.
- F-30: the priors corrected against 1.21.1's generation (diamonds most from −50 down, the two coal bands, lava's
  levels). Not built: the frontier exploration, the fortress and the other users the design listed.
- F-29: walk is `native_goto`; `wait` is 1 to 4 seconds, as the installed skill waits at most 4; the staircase is not
  in the vocabulary. `ModelGateway`'s duplicate accounting is retired in favour of `StrategistBridge.admit`. The design
  said nothing waits for the reply; the game thread still never does, but the player now holds still while a way out
  is asked for, unless in danger, so the plan finds it where it asked from. The fence leaves out the tasks the player
  starts of its own choosing, which begin on direct leases as commands do; the design's "revalidated at dispatch"
  stands for a person's stop, resume or command. The instructions say what each step does as it is carried out. The
  compatible route asks Anthropic's server for no thinking, as the SDK route does.
- E-48: the decision lines are kept by the runtime while the page is on, never read from the log file.
- E-49: a stop is never withheld; COME's distance left as above; the toss cap covers the command's and the chat ask's
  toss, not team hand-overs; wariness is not kept across restarts.
- F-28: `ExpeditionPlanner` is not driven; one player a run; spiders added; no sleeping or pillaring.
- E-47: each person's counts kept apart by UUID so an opt-out can delete them; the mining depth only; the prior blended
  is the campaign's own depth, not the atlas's; a custom stands on 16 ores; who really ran the command is checked.

### Next dependency

- The living-together track (E-18a and E-19a, E-50, E-52, E-53, E-51), as the owner set it out: from `main` once this
  branch is merged, with `claude/blissful-fermi-y4tt90` (`c6a042b`) merged into it.
- For the play-through, in order of what the runs and the suite showed:
  - the hand-over's make step looping on a walk to the table it stands beside (E-40a): in one seed of most arms
    (554 to 7,325 walks), the goal starved for minutes each time, counted stuck by the watchdog. The goal's craft
    helper walks to a table already in reach when it cannot plan the recipe, and the hand-over asks it for sticks with
    no planks; a two-line fix is proposed in `docs/validation.md`. Proposed: the fix and a fixture failing before it,
    as the first change on the living-together branch, since that track owns the hand-overs' team talk;
  - a look round before the goal's first dig and its first cave stride (the trip's wood and the ruined portal's chest,
    both chosen before its first full sweep; patches proposed in the PR's CI comment);
  - F-29's plans: most that run stop at a step whose geometry the model got wrong (a dig of one block, then a walk of
    two; a `dig up`, then a walk); a check of each walk's route before the plan is accepted would turn those into
    refusals with nothing moved, and the report could show the cells each step needs;
  - a listener that stops a failed fixture's player, so one failure does not load every batch after it;
  - the hungry hunt that never lands in the suite's world (cause not found);
  - F-30's frontier exploration, the fortress and stronghold searches, and the sightings shared in a team.

## Handoff, 2 November 2026: living together (`claude/living-together-1101`)

The owner: E-18a and E-19a, then E-50, E-52, E-53 and E-51, on a branch from `main` with the next levers merged
(`04f2859`) and the design (`c6a042b`) merged in (`c6e9814`). The design is
[living together](../proposals/living-together.md); each feature's evidence says where the code differs from it.
`claude/modest-faraday-r3spro` changes two of the files this track touched, `PlayerRuntime` and `PlayerCommands`; its
hunks were checked before each change, and this branch's changes there are hook lines and new commands away from them.

### Each feature

| Feature | State | Commits |
| --- | --- | --- |
| E-18a Expression presets | Complete (2 November): `plain`, `dry`, `playful` per player (`/aiplayer expression`), under `KINDRED_EXPRESSION_CAP` (default `playful`; unknown is `plain`); clean wording for everyone; teasing only between AI players; one line in the voice's prompt; saved with the profile (`V10`) | `12fbb53`, `50baba4` |
| E-19a A person's own preferences | Complete (2 November): `/aiplayer prefs` (fewer, usual or more unasked lines; no teasing; no jokes; forget), the person's own, kept per person and deletable; the strictest of those a line is said to applies | `50baba4` |
| E-50 What each player knows of the others | Complete (2 November): `social.PeerLedger` per player per world, 64 peers and 20 episodes; the game's record, then teammates' updates through the runtime, then first-person claims, which hold a game day, are never told as facts and are never written to disk; "forget that" and "that's wrong" with provenance kept; a person's place only while seen, a teammate's through the team; the voice reads it by the same tiers; `<uuid>.peers.json` | `0db3cc0`, `ae7ddc4` |
| E-52 Play by asking | Complete (2 November): `social.Intent` and the local `IntentGrammar`; `ChatIntents` runs each ask through its command's method as the speaker and says the command's refusal as its own; a race or duel only on each AI player's own yes (`MatchChoice`) and each person's own "I'm in"; an unclear ask asked back once, then dropped; operator switches, creation, limits and coordinates stay commands | `7b9393e`, `046b3b6` |
| E-53 Speaking first | Complete (2 November): `social.Initiative`, a budget per person (none out of earshot, in a countdown or away; nothing but a warning to someone fighting or building fast); a warning behind a person, food offered and tossed through the toss method only on "yes please", a request for a need, a word when idle | `0e82c72`, `811ea58` |
| E-51 Conversations between AI players | Complete (2 November): `social.Conversation` and `PeerTalk`, two or three turns on meeting again after ten minutes apart, each reply from its speaker's own record; said only to people who can hear, never to an AI player; with nobody to hear, noted in both ledgers and nothing said; `KINDRED_CHATTER` (four a minute, `quiet`), a pair's two minutes, a fight, flight, bed or countdown stopping it; the fixed peer conference removed | `19f3e72`, `8537a03` |

Found by the track's own tests while building, and fixed (each in its feature's evidence): `PeerRadio`'s first update
interval overflowed from `Long.MIN_VALUE`, so no update was ever sent (1 of 3, then 3 of 3); `memory forget` refused
every native player; the claims parser missed "I've got spare"; E-52's no-model fallback said "Coming." to a bystander
after the voice had declined (it now runs only with no voice, and never for someone who may not command the player); the
offer's toss re-checked control and refused the player's own offer (it now runs as the player's own task); the
initiative lane spoke for a player whose remarks were off; a talk the chatter budget refused still marked its topic
spoken, so three players said 3 lines in five minutes instead of 16.

### Validation

- `bash ./gradlew build` green on `8537a03`, the final code: 882 JUnit tests, 0 failures, 6 skipped (876 passed); 36 more
  than the 846 of `main`: `ExpressionTest` 6, the store and the prompt 1 each, `PeerLedgerTest` 9, `ClaimsTest` 4,
  `IntentGrammarTest` 7, `InitiativeTest` 4, `ConversationTest` 4.
- Each new GameTest class alone, three runs or more on its final code (the gametest entrypoints narrowed for each run and
  restored after), every run passing: `NativeExpressionGameTests` 3 of 3, `NativePeerLedgerGameTests` 3 of 3 (six runs),
  `NativeChatAskGameTests` 8 of 8 (six runs), `NativeInitiativeGameTests` 5 of 5, `NativeConversationGameTests` 4 of 4.
- The ten social classes together on the final code: 51 of 51 (`SocialGameTests` 6, `NativeVoiceGameTests` 9,
  `NativeAskGameTests` 6, `NativeMatchGameTests` 3, `MannerismGameTests` 4, and the five above).
- The full native suite on `8537a03`, nothing else on the cores: 503 of 504 in 50 minutes. The one failure was open
  before: the base repair, its roof stairs out of reach (#82's family, as in full run 41). The rest of the open list
  passed this time (the keep's roof stairs, the lake librarian, the ruined portal's chest, the trip's wood, the hungry
  search for animals, the cabin with its furniture, the night and its own bed, the walk back up), and every social
  class passed in it.
- Ten seeds of the final build (`social-8537a03`: the jar `./gradlew build` made from `8537a03`), three starters, the
  nether goal, 144,000 ticks, strategist and voice off, `KINDRED_CHATTER` unset (four lines a minute), two servers at a
  time, 04:40 to 07:32, nothing else on the cores but a copy of the players' ledgers once a minute and a few searches of
  finished seeds' logs. All ten complete and earned. [Summary](../evidence/eval/social-8537a03/summary.md).
  - The social lanes: 0 lines a game minute on every seed; 0 lines between AI players, 0 said first to people (an
    evaluation run has none), 1 meeting noted with nobody to hear (seed 606, Kindred and Echo, "what you were doing");
    model tokens 0 on every seed. The players were in sight of each other (eight blocks, a clear line) in their first
    3,800 ticks (about three game minutes) and then, by the ledgers kept, once more on three of the nine seeds copied
    (20261030, 606 and 707; 8675309 had ended before the copying began). Only 606's became a meeting; the other two were
    not looked into.
  - Against the next levers' final build on the same seeds ([comparison](../evidence/eval/compare-social.md)), no measure
    differs clearly: the team's furthest stage 5.5 [2 to 6] against 4.0 [2 to 6], deaths a game hour 1.25 [0.0 to 3.0]
    against 1.75 [0.0 to 4.5], stuck game minutes 42.0 [12 to 84] against 42.0 [12 to 99]; a portal lit on 5 seeds
    against 1. This track changed no goal, route or campaign code.
  - Found, open: on 20261030 Kindred, killed by a warden at Y −49 and back at the world's spawn on a mountain with
    nothing, cut logs, made sticks and a crafting table, placed it, and then walked to it 927 times in the run's last 19
    game minutes: the goal's own step "Craft stone pickaxe" without the stone it needs, given out by the craft helper as
    "Walk to the crafting table", which ends at once (`PortalRunCampaign.craft`, the hand-over loop's cause). So the
    goal does ask the helper for a recipe it has not the makings of, and the two-line fix proposed for the hand-over
    would leave this step with nothing to do: the step wants its stone first, or to be passed over, not a walk.

### What the owner must decide

As before: F24/F25, A1, C14, C15, B6, B12, D19, D20, E22, the CI artifact, `modest-faraday`'s merge, the dragon-fight
policy, COME's distance, E-47's other customs and the strategist from the release jar. New:
- **The rest of E-18's expression beyond clean.** Every line is clean for everyone; profanity, flirting and dark humour
  are not built and are not reachable by any preset. `playful` is a tone (a callback, a tease between AI players, plain
  to anyone who asked for none), not a licence. What a "mature" preset would allow, who may turn it on (the cap is the
  operator's; a person's own `prefs` can only make it stricter) and how a listener who has not opted in is kept out of
  it are the owner's to say before any of it is built.
- **Questions answered from the ledger without the model.** "What is Sol doing?" is answered from the record locally even
  with the voice on: exact, free, but in the template's words. Say if the voice should phrase those (each would then be
  a costed reply).
- **The model's ask vocabulary.** The local grammar reads every ask kind; the model still emits only E-20b's four. Widening
  its schema changes the cached instructions; say if it is wanted.

### What changed from the design, and why

- E-18a/E-19a: the commands are `/aiplayer expression` and `/aiplayer prefs`, since `/aiplayer talk` already exists. The
  cap is read where the runtime is built. The voice's cached instructions still describe the personality's humour; the
  one line in the varying part says how to speak to this listener, and wins. "Strictest in earshot" is everyone the
  line is said to: someone whose own pause has not run out is not sent it and does not count.
- E-50: claims are first-person only and never saved, so nothing said survives a restart; a claim said in the second
  after a player joins, before its ledger is read, is not noted. Teammates are AI players out of any match (the
  share-out's reckoning); people are not sent updates. Not built from the design: promises and asks said yes to as
  entries, feeling (wariness is E-49's, friendship E-24's), the share-out fed from needs, the watch page.
- E-52: the model's vocabulary is unchanged (above). Not built: manhunts and build battles by chat (they stay `match
  manhunt` and `match build`), go home and sleep (a native player has no command for either), "you two" to more than
  one player. Quiet and talk are the person's own `prefs unasked`, not a player-wide setting. A race asked by someone
  who does not control the AI player is proposed on the AI's own yes; the registry's join rule is unchanged.
- E-53: the budget governs the new occasions only; greetings and the remarks from before keep their limits, since
  folding them in would cut today's chatter to a line in three minutes per person. A warning is not held by the budget
  nor kept from someone fighting (it is about the fight), but has its thirty seconds and needs earshot. A player whose
  remarks are off says nothing first, warnings included (the teammate fixture of E-50 now turns Sol's off). Offers are
  of food only; the budget is held in memory.
- E-51: the only occasion built is meeting again after ten minutes apart; the others in the design (a find, a need met,
  a death seen, a milestone, a danger, a match, night at base, a promise) are not. No model phrases a turn: every turn
  is a local template, so the rule that a model phrases only when a person can hear holds without a call. The fixed
  peer conference is removed with its `[Nearby]` whisper to anyone within six blocks. Sheltering is read as a bed; a
  sealed dig-in is not a task the lane can see.

### Next dependency

- E-21 and E-22, gifts as items: surplus and the gift policy, then a chest deposit or a deliberate drop, through the
  recorded actions as E-53's food toss is; E-50's `spare` and needs are their input.
- E-24, durable friendship: attributed rapport events kept per peer, which E-51's callbacks and E-53's budget would read
  in place of the session's counts.
- E-27, outings: a shared trip proposed, accepted by each one's own yes as E-52's matches are, and remembered as an
  episode for E-51's callbacks. In the ten seeds the players were seldom in sight of each other after their first few
  game minutes (one meeting in ten runs); outings, or a shared base, are what would bring them together for E-51.
- Still open from the last handoff, and wider than it said: the craft helper's walk to a table in reach for a recipe it
  cannot plan (`PortalRunCampaign.craft`). The last handoff found it from the hand-over (E-40a) and proposed a two-line
  fix as this branch's first change; this track did not take it, as it is not a social item and no goal or campaign
  code was otherwise touched. This track's seeds show the goal itself reaching it (the stone pickaxe with no stone, 927
  walks on 20261030), so the fix must also give the goal's step its stone first or pass it over; each case wants its own
  fixture, failing before.
- `/core-test.log` at the container's filesystem root was written there by mistake during this track (a JUnit run's
  output); removing it was refused by a safety check, so it was left. It is outside the repository and goes with the
  container.

## Handoff, 2 November 2026 (evening): everything merged, rough talk, gifts, friendship, outings (`claude/expression-1102`, `claude/next-deps-1102`)

The owner's four asks, in order:
- profanity, flirting, dark humour and typing quirks (no punctuation, shorthand such as "ur");
- everything merged and up on GitHub;
- the voice phrases what a player knows of the others ("Yes the voice should phrase");
- on to the next dependencies.

### Merged

- [Mathew-O/Kindred-MC#5](https://github.com/Mathew-O/Kindred-MC/pull/5): living together.
- [Mathew-O/Kindred-MC#6](https://github.com/Mathew-O/Kindred-MC/pull/6): `claude/modest-faraday-r3spro`,
  `codex/ci-validation-evidence` and both world-design branches, through an integration branch; nobody else's branch was
  pushed to.
  - The world-design persistence and adapter draft is held back at `98ce3b8` on `codex/world-design-handoff`, as the note
    at the top of `docs/world-design-implementation-handoff.md` says.
  - Its migrations need new numbers: main's V9, V10 and now V11 (`rough_talk`, below) are taken.
- [Mathew-O/Kindred-MC#7](https://github.com/Mathew-O/Kindred-MC/pull/7): E-18b and E-50a, merged as `61848e6`.
  - It also raises the CI build job's limit from 60 to 90 minutes: the native suite had outgrown 60.
  - Its CI: every JUnit suite and 517 of 518 native tests. The one failure was the tree standing in water (below).
- The pull request for `claude/next-deps-1102` (this branch): gifts, friendship, outings and F-25c4av.

### Done

- **E-18b, rough talk.**
  - Swearing, dark humour, harder teasing (Sol by personality) and flirting (Kindred by personality).
  - Four chat styles: proper, casual, gamer, chaotic. Kindred is casual, Sol gamer, Echo proper.
  - Each kind is heard only by a person who said yes to it: `/aiplayer prefs swearing|dark|flirting on`. The operator's
    `KINDRED_ROUGH_AUDIENCE` decides only for people who said nothing, and never for flirting. The strictest in earshot
    decides.
  - Slurs, sexual words and threats are never said, by any lane or the voice.
  - Saved in schema 11.
- **E-50a.** The voice phrases answers about others from the ledger; the ledger's words when it gives none.
- **E-21a.** Something spare offered to a person who said they need it: half, at most 16, never tools, armour, food or a
  running goal's stock, never to someone it is wary of. Tossed only on their yes.
- **E-22a.** A toss counts as given only once its recipient picks it up, from their own `picked_up` statistic. A toss left
  lying ninety seconds is noted and never tossed again.
- **E-24a.** Friendship from what happened: a gift picked up +3, one received +6, an outing together +5, a blow outside a
  match −12. Each counts once and can be struck out with `/aiplayer rapport`. It is kept in `<uuid>.rapport.json` and
  grants and withholds nothing.
- **E-27a.** Outings to a lake or village it has seen, with whoever says "I'm in" or an idle AI player's own yes. There, a
  minute, and back, its walks its own decision candidates. Who was really there is what it remembers.
- **F-25c4av, open.** At a table in reach without the makings, the craft helper gives nothing to do, not a walk to the
  table. The fixture fails before and passes 3 of 3 after. The goal then passes the step over (it takes the first step
  of its plan that gives a task). Seed 20261030 has not been evaluated again yet.

### Validation

- `./gradlew build` on `e732618`: green, 922 JUnit tests, 916 passed, 6 skipped. On `a875512`, E-18b and E-50a alone:
  916, 910 passed.
- The new GameTest classes together (`NativeCraftHelperGameTests`, `NativeGiftGameTests`, `NativeOutingGameTests`): 5
  of 5 in each of three runs. With the eleven social classes: 60 of 60.
- The full native suite on `e732618`: 519 of 523 in 55 minutes, nothing else on the cores. Each of the four failures was seen before, and none touches the new code: no outing, offer, gift or "cannot make" line appears in the suite outside the new fixtures.
  - The tree standing in water: F-25c4as's fixture, failing in full suites since #6 and passing alone; open.
  - The Nether portal-restart fixture: "No walk back into the portal admitted", as in CI 338.
  - Two builds left with roof stairs unplaced, #82's kind (suite runs 41 and 44): the walled keep (27 blocks) and the base cabin (4 blocks).
  Rechecked alone:
  - `NativeCompoundGameTests` and `NativeBaseGameTests` together with the Nether class: the keep and the base cabin both passed.
  - The Nether portal-restart fixture fails alone too, on this branch (2 of 4 runs) and on main's code without it (`6a31725`, 1 of 3).
    - Its messages: "No walk back into the portal admitted", or "Return portal preserves expected 8:1 linked region".
    - No outing, offer or gift line in any of those runs.
    - It is intermittent on main, as in CI 338, and open.

### Found and fixed on the way

- `Outings` dated its lines with the wall clock. The social lane runs on the runtime's monotonic clock, so the invitation
  was never said.
- The outing's walks, dispatched directly, waited for an idle runner that the player's own decision loop kept busy. Each
  leg is now a candidate in that loop, beside the attention pause. A walk edits nothing, so it does not wait on the edit
  switch.
- A gift fixture spoke before the player's ledger was read at start, so the claim was not noted. The fixture now waits for
  the read. A need said in the first second after a join is still not noted (E-50's known limit).
- Fixtures on this test server: it runs ticks several times faster than a line is typed. A stay or a deadline counted in
  ticks can be over before a line arrives, and a real-time sleep inside a retried step stalls the test. The outing
  fixture holds its stay until it has heard the arrival.

### Decisions taken without asking (each reversible)

- **Rough talk cap and default:** the server's cap for how rough a player may be defaults to rough. People still hear
  clean unless they or the operator's audience setting say otherwise. Flirting is only ever a person's own yes.
- **Friendship's effect:** it changes nothing a player does yet. E-25 is where it may shape tone and discretionary
  choices.
- **Who may go on outings:** a player in the default idle-observing playstyle is free to host and join them. They are not
  work.

### Next dependency

- **E-25:** friendship read for tone and discretionary choices, such as who is offered first or a warmer greeting. Never
  for control, access or budgets. With it, E-51's callbacks can read outings and gifts.
- **E-35:** tone drift, kept apart from friendship.
- **E-21, rest:** surplus noticed unasked; offers to AI players.
- **E-22, rest:** the gift chest; a toss watched across a restart.
- **E-23:** finds and maps shared.
- **E-27, rest:** other destinations, supplies, a participant lost on the way.
- **An evaluation of this build on the ten seeds:** F-25c4av on 20261030, and whether outings bring the players together
  (one meeting in ten runs before).
- **Still open:** the tree standing in water (F-25c4as's fixture) fails only in the full suite since the merge in #6 and
  passes alone; its cause was not found.
- **Also open:** an AI receiver sometimes does not pick up a stack tossed to it. `NativeDeliveryGameTests` fails that way
  on main's code alone (1 of 3 in one run) and failed so in this branch's CI.
- `/core-test.log` at the container's filesystem root, written there by mistake earlier, is still there (its removal was
  refused). It is outside the repository.

## Handoff, 3 November 2026: the three open failures (`claude/fixtures-1103`)

The owner's asks:
- "From now on when done with a block merge to main": each finished block goes to main by pull request and merge, after
  its CI.
- Take care of the three failures the last CI runs left.

The three:
- **The tree standing in water: fixed (F-25c4aw, open until an evaluation).**
  - Cause: the goal's walk out to look for logs ran its full 28 blocks even once the player knew of a log. In a busy test
    world the first look about is slow to reach the stump, so the first step was a walk out. That walk ran off the
    fixture's ground and down a step it could not climb back.
  - The walk now ends as soon as a log the goal would gather is known.
  - Its fixture fails before (25 blocks out, 3 of 3) and passes after (5 of 5).
- **A delivery whose AI receiver did not pick up the toss: fixed (E-22b).**
  - Cause: every toss flew where the thrower faced. The aim at the receiver looked its stack up by a thrower a plain drop
    never has.
  - A toss now lands just past the receiver's feet and clear of the thrower, who otherwise picks it straight back up.
    The team hand-over fixture showed that.
  - Deliveries: 15 of 15 after, against 7 of 9 on main's code.
- **The portal cast from lava: diagnosed, open.**
  - CI's log shows the mined obsidian left in its hole under the poured water: the goal waited, took its water back, and
    went on with 0 of 10.
  - It happens about once in thirty to forty runs alone.
  - A candidate fix (the wait restarted when the water is taken back) changed nothing measurable (30 of 30 without, 29 of
    30 with), so it was not kept.
  - In the one failure with it the drop was planned for and still not picked up. Ten copies side by side also saw each
    other's obsidian.
  - Next:
    - a fixture where the obsidian drop rests in the dry hole with the player beside it, to see the pickup walk;
    - the lava-cast fixture given a batch of its own, so neighbours' obsidian is out of its sight.

Validation: on `373bb90`, `./gradlew build` green: 922 JUnit tests, 916 passed, 6 skipped. The full native suite: 522 of 525 in 57 minutes. The tree standing in water and the lava cast both passed. The three failures:
  - **`NativeDeliveryGameTests.stoppedPartWayAndResumedItHandsOverNoMoreThanAskedFor`, Wren left holding nothing again.**
    - Its coordinates show why: Wren stood on its chunk's last column, and the stack, now aimed, came to rest 0.6 past it, over the line.
    - The test server ticks only the chunks its players and the test's structure stand in, so there the stack's pickup delay never ran out.
    - A server with a simulation distance ticks the chunks round its players. The fixture now keeps its field's chunks simulated (`FixtureGround.simulated`) and says so (`40b4412`).
    - Landing short of the receiver instead was tried: the team hand-over failed 2 of 2, the stack in Mason's reach as it stepped on. So the toss still lands just past the receiver.
    - After: every class that tosses, 36 of 36 in each of two runs; the deliveries alone, 3 of 3 in each of three runs.
  - **`NativeLifeFirstGameTests.atNightItLeavesItsBuildForItsOwnBedThenBuildsOnInTheMorning`** ("The hut under way: 0"): it failed before in a full suite ("Its body"); open.
  - **The pasture's cattle (`NativeCompoundGameTests`, a cow brought back into the pen):** open before.

Next dependency: as before (E-25, then an evaluation of the current build on the ten seeds, which also checks F-25c4av
and F-25c4aw in the open).


## Handoff, 3 November 2026 (afternoon): the roof-reach stops (`claude/roof-reach-1103`)

The owner's ask, after the three failures: carry on. The most common red in CI was a build stopped "8 steps failed one
after another", each a walk with "no walkable route" (#82's family). E-08f ([evidence](../evidence/E-08f.md)).

- **Why, from the navigator's own notes** over the 17 such stops in the saved CI logs:
  - 8 hemmed in by its own scaffolding. Coming down a column, a walk planned while it was still dropping onto the next
    block left the rest of the column standing; stubs so left round a cell beside a wall shut it in.
  - 4 started from the wrong cell at a column's edge, its middle over the open side.
  - 3 below the floor in a hollow beside the build: open.
- **Fixed:** it lands on its scaffold before it plans on down it; its own scaffolding in reach comes down before a block is
  called out of reach; a walk from a block's edge starts on the block it rests on.
- **Shown:** the new planner case and the new edge fixture fail before and pass after. In the build classes, walks off a
  column left standing fell from 84 of 365 to 33 of 419, and walks hemmed in or from the wrong cell from 11 to 0.
- **Not shown yet:** whether CI's roof stops are gone; that shows over the next suite runs. The hollow below the floor is
  the next slice of it.

Next dependency: as before (E-25, then an evaluation of the current build on the ten seeds).

## Handoff, 3 November 2026 (afternoon): friendship shapes tone (`claude/friendship-tone-1103`)

E-25a ([evidence](../evidence/E-25a.md)). The friendship each AI player keeps (E-24a) now shapes how it is with someone:
- a friend gets a warmer hello, is called by name in offers and is turned to first;
- one it is cool with gets no talk started, no small talk and a word for an answer;
- spare is shared in talk with anyone but one it is wary of;
- a friend's outing is joined at sociability 20 (30 for a friendly one); a cool one's, never.

It never changes control, access, budgets, a stop or a strike, nor whether it warns of danger or offers food to someone
hungry. Shown: the new conversation fixture fails 3 of 3 on main's code and passes with this; the social classes 50 of 50.

Next dependency: the ten-seed evaluation of main with E-08f and E-25a (F-25c4av and F-25c4aw in the open too). Open in
E-25: the voice given the band; a friend's ask answered first; help offered to a friend in trouble; E-35's drift.

## Handoff, 3 November 2026 (evening): the ten seeds, and up out of a hollow (`claude/hollow-1103`)

- **Ten seeds of main with E-08f and E-25a** (`e25a-7c8febf`; [validation](../validation.md)). All complete and earned.
  - Against the last build's ten seeds no measure differs clearly. A portal was lit on 1 seed against 5; the comparison
    before went 1 to 5 the other way, so a lit portal swings this much from build to build.
  - F-25c4av seen working: on 20261030, 2 walks to the crafting table against 928. Still open: why the plan asked for a
    stone pickaxe with no stone.
  - F-25c4aw done: 17 walks out for logs ended with a log in view, on 7 seeds.
  - Open, as before: a player under water whose walks out find no route again and again (Kindred on 8675309, 830 walks;
    on 505 35 tries; the last build on 505, 891).
- **E-08g** ([evidence](../evidence/E-08g.md)): down in a hollow beside its build, below the floor, a walk round failed,
  it climbs out first, on blocks the build does not take, and builds on. The fixture fails 3 of 3 on main's code and
  passes 3 of 3 with it; the build classes 22 of 22. That was the last kind of the 17 CI roof stops.
- **CI on #11** saw one new failure once: the hungry life-first fixture began its base at 31 food, not 32. Its hunt
  passed over the cows left after two strikes came up out of reach. It passed in the other two runs. Open.

- **CI on #12** ([validation](../validation.md)):
  - A trading hall was left short of its beds' wool after all nine sheep were shorn. Each drop was given up after six
    tries, and the tries were counted each time the plan was asked for its next step, every 5 ticks, so one second's
    wait used them up. Tries are now counted when a pick-up ends (E-45d). The new fixture fails 3 of 3 before the fix
    and passes 3 of 3 with it.
  - Two fixtures stopped the server when their body was gone at their own stop; their lines are now safe.
  - E-08g's climb is now kept out of the build's own box.

Next dependency: the water livelock (a player under water whose walks out find no route), seen on two of ten seeds in
each of the last two evaluations.

## Handoff, 3 November 2026 (night): out of its own flooded shaft (`claude/flooded-shaft-1103`)

- **F-25c4ax** ([evidence](../evidence/F-25c4ax.md)): the water livelock.
  - Its stair dug up into a lake's floor flooded its pocket. The one way up to air was a block high: rock over the
    pocket, the lake's sand in the shaft.
  - It now breaks that block and swims up. The fixture fails 3 of 3 on main's code (it drowns) and passes 5 of 5 with
    the fix.
- **Open:**
  - A hunt that does not get its cow in time: the hungry-up fixture once locally, and the hungry life-first fixture
    once on #11's CI.
  - Two timing collisions, each once, in the push build of #12's `36b4b49`:
    - the long fall's water clutch was checked while the player ate;
    - the fire fixture's walk command was refused while an activity slice settled.
  - The lake librarian off its line, as before.

Next dependency: ten seeds of main with E-08g, E-45d's wool and F-25c4ax, to see the flooded shaft in the open; then
that hunt.

## Handoff, 4 November 2026: E-40's ladder, rung a (`claude/e40-ladder-1104`, `-2-1104`, `-3-1104`)

**Rung reached:** none in full. Rung a is half met. The iron pickaxe within the first game hour came on 10 of 10 seeds in
both evaluations after the fixes; stuck time under a tenth of the run on 1 of 10 in each. Rungs b to g not started;
the gate not run. The three reserved gate seeds are in [E-40](../evidence/E-40.md), rolled before any run and not used.

**Numbers** (ten development seeds, earned, two game hours, strategist off; medians [ranges]):

| Measure | starting build `f25c4ax-84b331c` | iteration 1 `e40a1-e9d33c0` | iteration 2 `e40a2-143806e` |
| --- | --- | --- | --- |
| Iron pickaxe in the first hour | 9 of 10 | 10 of 10 | 10 of 10 |
| First iron pickaxe, minutes | 37.3 [13.0 to not reached] | 16.75 [11.9 to 36.7] | 16.35 [12.3 to 23.3] |
| Stuck time under a tenth | 1 of 10 | 1 of 10 | 1 of 10 |
| Stuck spells | 15.5 [4 to 22] | 9.5 [1 to 61] | 8.0 [1 to 20] |
| Obsidian / portal lit (rung b's) | 5 / 3 of 10 | 5 / 4 of 10 | 8 / 5 of 10 |
| Deaths | 38 | 39 | 19 |

Every range overlaps, so by the convention no difference is clear. The starting build was measured at the slower pace
F-26a removed. Summaries, comparisons and every death's record are under [eval](../evidence/eval/).

**Fixes, each with its fixture failing before and passing after:**
- **F-25c4az** (`78fdb82`): walks that keep ending in deep water set their place aside. Fixture: fails 2 of 3 on the
  old code, passes 3 of 3. It was moved into a wide structure of its own, because in the full suite its field lay over
  the tests beside it.
- **F-25c4ba** (`f0258b6`): a spare stone pickaxe is not made from the iron pickaxe's sticks. JUnit case: fails
  before, passes after.
- **F-26a** (`e9d33c0`): work handed back to the server thread runs within a tick on a sprinting server.
  - Every player task had waited on two to four of vanilla's three-tick holds, so the evaluation measured the players
    at half their pace. That pace, not the mine, was behind the 45 branch-mine spells.
  - Sprinting branch-mine fixture: fails 3 of 3, passes 3 of 3.
- **F-25c4bb** (`143806e`): on a cavern's rim the way over the drop is not dug into, and it climbs away. Kindred on
  505 waited 97 minutes there. Rim fixture: fails 3 of 3, passes 3 of 3.
- **Merges:** #14 (`79f2a3e`) and #15 (`f82b8a9`, F-25c4bb and iteration 1's evidence) merged. Their CI failures
  were all on the open list or analysed on the PR; the accounting race there has a proposed patch.

**Validation on the final code (`143806e`):**
- `bash ./gradlew build` green: 929 JUnit tests, none failing, 6 skipped.
- Every new fixture passes 3 of 3 alone.
- Full native suite, nothing else on the cores: 532 of 533 in 48 minutes. The one failure is the Nether portal
  restart, on the open list.
- The final ten-seed summary is filed with its comparison against the starting build
  ([compare-e40a2-base](../evidence/eval/compare-e40a2-base.md)).

**What stops rung a now.** 94 stuck spells in iteration 2. A team is stuck for more than a tenth of the run on 9 of
10 seeds.
- **Pickaxes and sticks on the way down:** the longest spells are players out of sticks or a pickaxe below ground.
  "Gather logs for sticks - Head back up toward home" finds no route: 12 spells, Sol on 20261030 and the desert.
  - On 20261030, Sol made 14 stone pickaxes and 10 batches of torches in the run, and lost its last pickaxe at Y=35 on
    its stair down for lava. (Corrected 4 November: this line said two deaths were among them; Sol did not die on
    20261030 in this run.)
  - The wood for the trip down is not kept against the pickaxes and torches the trip will use.
- **One waiting place on 101:** Kindred, 14 spells, not looked at yet.
- **Also seen on iteration 1 and open:**
  - 505 Echo's aquifer swims at Y=2 (25 spells, the stair down for lava breaking into an aquifer);
  - the desert's by-hand climb under sand (Sol, Y=59: every way up refused for the sand over its head).

**Deaths** (each recorded with cause, place, light, time, stance and step):
- 19 in iteration 2, all underground, 11 in the dark (light 0 or 1), 15 while fighting back.
- Zombies are most of them. A player fights in the dark tunnels it digs.

**Decisions for the owner:**
- **The accounting race:** whether to make the patch proposed on #14. A lapse within `RENEW_GAIN_MIN` of a period's
  end counts as at the end. It is open as an intermittent.
- **CI:** main's suite is red on every merge, two to seven timing intermittents a run, different each time. Whether
  to spend a slice on them before the ladder goes on.

**Exact next action:**
1. The trip-down's wood: make the reserve for the way down cover the pickaxes and torches its depth will use, or keep
   a log to make them from. Fixture first: Sol's 20261030 stair.
2. Then 101's waiting place.
3. Then the ten seeds again.
4. **Keep the session busy while an evaluation runs.** The container is reclaimed when the session goes idle. The
   first try of iteration 1 died that way, five minutes after it started.

## Handoff, 4 November 2026 (evening): E-40's ladder, rung a, iterations 3 and 4 (`claude/e40-ladder-4-1104` to `-6-1104`)

**Rung reached:** none in full. Rung a is still half met:
- the iron pickaxe within the first game hour on 10 of 10 seeds in both new evaluations;
- stuck time under a tenth of the run on 1 of 10 in each.

Rungs b to g are not started, and the gate has not been run. The three reserved gate seeds in [E-40](../evidence/E-40.md)
are still unused.

**Numbers** (ten development seeds, earned, two game hours, strategist off; medians [ranges]):

| Measure | starting build `f25c4ax-84b331c` | iteration 2 `e40a2-143806e` | iteration 3 `e40a3-05b58fd` | iteration 4 `e40a4-c237a6e` |
| --- | --- | --- | --- | --- |
| Iron pickaxe in the first hour | 9 of 10 | 10 of 10 | 10 of 10 | 10 of 10 |
| First iron pickaxe, minutes | 37.3 [13.0 to not reached] | 16.35 [12.3 to 23.3] | 20.15 [13.5 to 46.5] | 17.35 [9.8 to 31.7] |
| Stuck time under a tenth | 1 of 10 | 1 of 10 | 1 of 10 | 1 of 10 |
| Stuck spells | 15.5 [4 to 22] | 8.0 [1 to 20] | 22.5 [2 to 48] | 18.0 [3 to 39] |
| Obsidian / portal lit | 5 / 3 of 10 | 8 / 5 of 10 | 5 / 2 of 10 | 6 / 5 of 10 |
| Deaths | 38 | 19 | 28 | 26 |

Every range overlaps, so by the convention no difference is clear. The seeds' worst places move from run to run: each
fix removes the place it was found at, and the next run finds others.

**Fixes, each with its fixture failing before and passing after (all in `NativeTripDownGameTests`):**
- **F-25c4bc** (`0467a5f`): with no pickaxe, cobbled deepslate is punched by hand (the limit 3.5, not 3.0).
  - Sol on 20261030 had sealed itself in for the night with it, inferred, and stood there 41 minutes.
  - Stair-foot fixture: fails 3 of 3, passes 3 of 3.
- **F-25c4bd** (`05b58fd`): when the block over its head may not be dug (sand on it, or water beside it), it gets out
  from the cell beside it.
  - Sol under the desert's sand on 31102026, and Kindred beside a pocket of water on 101, stood so to the end of
    their runs.
  - Sand and water fixtures: each fails 3 of 3, passes 3 of 3.
- **F-25c4be** (`c237a6e`): on ground it cannot climb or walk off, it pillars up into a hole one wide over it, or over a
  cell beside it.
  - Echo on 20261030 stood 38 spells on an island in a lava lake, under the hole it came down through.
  - Lava-island fixture: fails 3 of 3, passes 3 of 3.
- **Merges:** #16 (`914dd97`), #17 (`9df95ac`) and #18 (`04f302d`). Each was red on CI with timing intermittents only,
  no fixture failing in both builds.
- **Not kept:** a staged flooded-stair fixture, which got out on the old code.
- **Tried and taken out, for want of a fixture that fails without them:**
  - passing over a refused "to the wall" target for a minute (F-25c4ay);
  - keeping F-25c4bd's step aside out of water (on 404 it fired 546 times in flood water).
- **Corrected:** this page and E-40 had said Sol died twice on 20261030 in iteration 2. It did not die there.

**Validation on the final code (`c237a6e`):**
- `bash ./gradlew build` green: 929 JUnit tests, none failing, 6 skipped.
- Every new fixture passes 3 of 3 alone.
- Full native suite, nothing else on the cores: 535 of 537 in 52.6 minutes.
  - The two failures (the wolf tamed and named, the librarian brought home) were not on the open list.
  - Alone, their classes passed 8 of 8 in each of three runs.
- The final ten-seed summary is filed with its comparison against the starting build
  ([compare-e40a4-base](../evidence/eval/compare-e40a4-base.md)).

**What stops rung a now: the water loop (F-25c4ay).** It is 70 of iteration 4's 194 spells, on five seeds, and 65 of
iteration 3's 232.
- **How it starts:** a player's stair up toward water breaks into it, or into the sand under it, from below. The water
  comes down the stair.
- **Then:** standing in the flowing water, it walks "to the wall" at its stair's next step, with the water falling
  down it. On 202 in iteration 3 it asked for that walk 9,792 times, each refused, and 5,076 more were cancelled for
  air.
- **The staged form did not reproduce it:** a lake one deep over a stair of one step. The player swam out over the
  lake.
- **The real ones:** stand in a column of flowing water at the stair's foot, with sand come down and the lake's edge
  further off. Build the fixture from a seed's own terrain. Read offline from a fresh world on 202 (diagnosis only), 20, 56, -88
  lies under shallow water at Y=62 over sand from 60 to 62, with diorite under that.

**Deaths** (each recorded with cause, place, light, time, stance and step):
- 28 in iteration 3: 24 below ground, 18 in the dark (light 0 or 1), 19 while fighting back.
- 26 in iteration 4: 23 below ground, 15 in the dark, 19 while fighting back.
- Zombies most of all, then skeletons, spiders and creepers.

**Decisions for the owner** (as in the morning's handoff, still open):
- **The accounting race:** the patch proposed on #14.
- **CI's timing intermittents:** one to nine a build on #16 to #18, a different set each time.

**Exact next action:**
1. The flooded stair (F-25c4ay): a fixture from 202's or 606's own terrain that fails at least 2 of 3. Then the
   pass-over of a refused wall target, and what gets it out of the water once it no longer walks to the wall (up the
   falling water, or a block set to stop the flow).
2. Then the ten seeds again, and on to rung b.
3. **Keep the session busy while an evaluation runs.** The container is reclaimed when the session goes idle.

## Handoff, 11 November 2026: Session A's Factions pack (F-31, M-06c to M-06l)

**Merged** (the lead merges on green; Session A checks each in memory first: merge-tree onto current main and
`support.py tracker`):
- #113, #136 (M-06i: the shelter sealed only where it may), #140 (M-06l: the HUD as sections), #141.
- #139 (`f411a32`, carrying #137): F-31's event kinds a registry; the Record writes only registered kinds.
- #161 (`109fd04`), M-06h: a stop mid-handover writes nothing after it; the stop barrier (`stopping`, `stopEpoch`)
  in `PlayerRuntime`. On the way, a duplication on crash-restart found and fixed: a begun delivery stayed owed and was
  handed over twice; `Auctions` now folds `auction.delivering`.
- #162 (`5f0e115`), M-06i evidence: the shelter fix on ten seeds (`b7b4bec`, then main `32d3de6`), the stuck time by
  cause and seed.
- #176 (`ccda814`), M-06i: Factions an operator's choice (`/aiplayer mode world factions`, kept in `mode.properties`,
  the territory guard on with it and off with any other mode); D7's stricter reading told on joining: PvP only between
  members of different factions, never in safe zones, joining a faction is joining the match.

**Open, green or on CI:**
- #178, M-06i: an AI player's found, join, claim and capture told to its faction citing exactly the Record's events
  (through `recordAndTell`); `recordAndTell` given #161's barrier, which stops the tell, never the events.
- #185, M-06f: the safe zones' chunks gathered once. `SafeZones.Book.chunks()` rebuilt a sorted set of every zone's
  chunks on each territory check, asked for every water cell the goal's search tests: in a faction world the thinking
  cost grew with each day's known water (diag-factions303 and 110). A question 3.8 to 4.1 µs at the 63-chunk hub and
  1.1 ms at 5,171 chunks before, about 100 ns after; on 303 under JFR the call went from 2.22% of the server thread's
  samples to 0.00%, mean tick 16.75 to 9.2 ms. RUNNER 2 re-runs diag-factions110 on its head (`0edc678`).

**Read and handed on, nothing changed:**
- diag-factions303 and 110: the hole-or-pit spells are survival loops (the logs climb, the water tunnel, a bamboo
  canopy at one spot on 110), not faction work; no territory refusal in either run (LOOPS).
- Faction walks are refused far more than they finish: 62 to 521 on 303, 44 to 564 on 110 (MOVEMENT's basin slice,
  `claude/movement-1111-basin`). `FactionsPlayer.destination()` was tested as the cause and ruled out.

**Pack readiness for the month run (M-06k), what stays open, in order:**
1. Native fixtures for the AI's own sell, buy, bid and defend (M-06i).
2. Wants from its plans, so it buys and bids and not only sells (M-06i).
3. The base site from M-01c's chosen ground (M-06i). Sightings across a restart went to Session B (F-30d, merged).
4. The eight-player hitches attributed (M-06k), with #185's numbers as the new baseline.
5. M-06j: the in-world checklist on a fresh server now that Factions can be chosen; the operator guide's section; the
   full pre-generation timed on the owner's machine (the owner's).
6. Mob griefing other than explosions (M-06d), the market's chest-GUI browser (M-06g), the HUD's screenshots (M-06l),
   a real client's pass over `/ah` (M-06h).

**Decisions taken without asking (each reversible):**
- D7: a person in no faction is never a target (the lead's reading; the owner may loosen it).
- No self-merge: Session A's PRs are merged by the lead, as instructed, after Session A's "ready: green and clean".

**Next dependency:** #178 and #185 merged; then item 1 above, fixture first.
