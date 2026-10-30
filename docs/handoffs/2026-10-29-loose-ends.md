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
5. My re-run of the branch: see the end of this page.
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

### Validation

- `fb02bc3`: `./gradlew build` 792, none failed, six skipped; testkit and personality demo pass; the full native suite 402 of 405.
- The working tree after it, as committed in `10a60d5`: `:companion-core:test` 615 of 615; the full native suite 401 of 406. Its five failures, alone after, are all intermittents seen before; one, the librarian off the line, fails 2 of 3 on the code before too.
- The live table, against B7's: validation's section for this block.

### Next dependency

- Live runs on `10a60d5` on both seeds, for F-25c4d, f and i's in-play rows.
- Then, from these runs' logs, what costs most now:
  - deaths holing up or backing off in the caves under spawn (6 of 8 on the first seed);
  - drops never picked up after a mob kills the player (`died()` skips them: every death there started over from logs);
  - water fetched from the surface by a fresh staircase, which wore out Sol's last pickaxe;
  - falls into caves while digging stone near spawn (Echo, in B7's runs and these; not reproduced in a fixture).
- The cattle: lead a pair so that one comes first through an opening one wide.
- `modest-faraday`'s merge, as before.
