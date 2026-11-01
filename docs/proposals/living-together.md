# Living together: conversation, shared knowledge and play by asking

Status: proposal, 2026-10-31. IDs E-50 to E-53 and the children E-18a and E-19a are reserved in the
[tracker](../feature-spec.md); claim one in Active work before implementing it. This is the social counterpart of
[the next levers](next-levers.md) and builds on [the player's voice](player-voice.md) (E-20a..c, F-18a).

## What is wanted

- Chat between players that feels alive and playful, between the AI players themselves and with people, without
  spamming anyone.
- Each player keeps what it knows of the others: what they are doing, what they need, what they have spare, what they
  promised, what happened between them; across restarts.
- Playing with them needs no commands after a player is made: goals, modes, teams, matches, builds and the rest are
  asked for in chat, and the players propose things themselves.

## What exists

| Piece | Where | What it does today |
| --- | --- | --- |
| The social lane | `CompanionSocials`, `SocialBrain`, `SocialPerception` | Who may hear (owner or controller in private, others with public chat on), visibility and range, one line at a time, 30 s per listener, 10 s between unasked lines, 3 s per speaker; topics: threat, hurt, greeting, rain, night, a notice, work done or blocked, team. Exchanges end; nothing said re-enters as input. |
| The voice | `PlayerVoice`, `ConversationPrompt`, `Strategist.converse` (E-20a..c, F-18a) | A reply in its own words to whoever speaks to it, told only what that listener may know; asks COME, WAIT, STOP, TOSS from a controller (E-20b); the fixed replies on any failure. |
| Peer chats | `PeerProgressDialogue`, `maybePeerConference` | Two AI players in sight confer about progress every two minutes at most, a line whispered to people within six blocks, a mutual fact in each memory. Fixed lines. |
| Team talk | `TeamTalk`, `ShareOut`, `HandOvers`, `RunDivision`, `TeamCoordinator` (E-40a, E-11) | A find shared out by need, said to the team and handed over as tasks; a run divided with a line each. |
| Matches | `MatchRegistry`, `MatchPresentation`, the `match` commands (M-00..M-04) | Duels, races, manhunts, build battles; an AI player is entered by an operator, a person joins by choice; only joined opponents may be struck (D7). |
| Settings by command | `PlayerCommands` → `PlayerRuntime` (`startGoal`, `togglePlaystyleForBot`, `mode`, `proposeDuel`, `proposeManhunt`, `proposeSpeedrun`, `proposeBuildBattle`, `joinMatch`, `startBuild`, `startRanch`, `startField`, skins, limits) | Everything a person does with a player, by `/aiplayer`. |
| Memory | `MemoryNote` (preference, fact, episode, with provenance), `PlayerMemoryStore`, F-20 | What a player remembers and where it learned it; chat alone never creates a memory. Correction and provenance for people remain F-20's open work. |
| Manner and feeling | `Personality`, `ToneDrift`, rapport (E-24..E-26), expression (E-18..E-20), quirks (E-32) | Tone drifts and settles; friendship is durable and separate; presets and recipient caps are designed, not built. |

## Five rules the design keeps

1. **Talk is for an audience; knowledge flows regardless.** AI players exchange what they know as records, silently,
   whenever they may; they put it into words only when someone can hear (a person within earshot, or viewers of the
   watch page, E-48). Nothing is said to the whole server.
2. **Something to say, or nothing.** A line needs news the listener has not heard, relevance to them, and room in a
   budget: per player, per listener, and across the server. Silence is the default; sociability raises the rate,
   `quiet` lowers it.
3. **Grounded and attributed.** What the game recorded is a fact; what someone said is a claim, kept with who said it
   and trusted only once seen. Nothing in chat grants authority or creates a memory on its own (AUTH-01, SOC-02).
4. **Exchanges end.** An AI-to-AI conversation is at most three turns; an unasked thread with a person pauses after
   six lines until they answer. No line of AI speech ever re-enters as input to any player.
5. **Asking is the command.** An ask runs the method the command runs, under the same authority, policy and arbiter
   checks; a person's consent (to a match, to a toss) is theirs alone to give, in their own words.

---

## E-18a and E-19a: a playful voice, bounded

"Playful" needs the expression work the contract already describes (E-18, E-19); this track builds the smallest
useful slice of each first, since everything below speaks through it.

- **E-18a, presets.** A per-player expression preset, set by its controller: `plain`, `playful` (jokes, callbacks,
  light teasing *between AI players only*), `dry`. A server cap set by an operator bounds every preset. Stored in the
  social profile; `clean` wording for everyone regardless (profanity, flirting and dark humour stay out until the rest
  of E-18 is built and the owner says so).
- **E-19a, recipient preferences.** A person sets, for themselves, how AI players speak to them: `fewer` or `more`
  unasked lines, `no teasing`, `no jokes`; the strictest setting among everyone who can hear a line applies to it.
  Stored per person, deletable, read by the budgets below and by the voice's prompt (a line about the listener's
  preferences).

Acceptance: JUnit for the cap and the strictest-wins rule; GameTests: a `playful` player's unasked line to a person
with `no jokes` is the plain one; a line heard by two people takes the stricter preference; presets survive a restart.

---

## E-50 Peer knowledge: what each player knows of the others

**Design.** `PeerLedger` in `companion-core`, pure, one per player per world, one entry per other player it has met
(AI or person):

| Field | Source | Notes |
| --- | --- | --- |
| who, relation | the roster, the team, the match registry, `mayControl` | owner, administrator, teammate, player, opponent |
| last seen: where, when | the game (visible) | a person's place only while they would be visible to it; a teammate's whenever the team shares it |
| doing | a teammate's own update; a person's visible activity; a claim | "mining below the base", "building", "racing" |
| needs | a teammate's needs summary (`NeedsAssessment`, `Want`); a person's claim ("I need iron") | what, how many, since when |
| spare | a teammate's stock summary (`StockLedger`, `Worth`); gifts offered | what it could give |
| promises | `HandOvers` (its own and theirs), asks said yes to | what, to whom, by when, kept or not |
| gifts and debts | tosses, trades, gift chests (E-21/E-22) | both directions, counted from the game |
| feeling | `ToneDrift` band, friendship (E-24), wariness (E-49) | never a reason to strike |
| episodes | the game's events both were in | "we fought a skeleton at the river", "you pulled me out of the pit", "you raced me and won"; at most twenty, oldest dropped |
| claims | things they said | attributed, low trust until seen; expire in a game day |

Sources, in order of trust: the game's own record (harm, trades, tosses, deaths in sight, match results); structured
updates between AI players (`PeerUpdate`: doing, needs, spare, promises, place if shared), sent through the runtime,
never through chat; spoken claims. A person's "forget that" or "that's wrong" marks the note corrected with its
provenance kept (F-20's correction, done here for the ledger), and an operator's `memory forget` removes it.

Persisted as JSON in `PlayerMemoryStore` (`peers`), bounded to 64 peers. Read by: the voice (a "what you know of
them" section in the situation, tiered as everything else is), conversations (topics come from news in the ledger),
team work (needs feed `ShareOut` and `HandOvers`), initiative (an offer when a peer needs what it has spare), and the
watch page.

**Acceptance.** JUnit: each source, the trust order, the bounds, correction, expiry of claims. GameTests: Sol's
needs reach Echo's ledger with nothing spoken; asked "what does Sol need?", Echo answers from the ledger; a person's
"I'm off to the Nether" is a claim until seen; "forget that" corrects it and the voice no longer uses it; the ledger
survives a restart.

---

## E-51 Conversations between AI players

**Design.** `Conversation` in `companion-core` replaces the fixed peer conference:

- *Occasions*: meeting after time apart (ten minutes), a find, a need met or unmet, a death witnessed, a milestone, a
  danger seen, a match proposed or finished, idle together at base at night, a promise kept or missed.
- *Topic*: the occasion's news from both ledgers, ranked by novelty to the other and by sociability; nothing with a
  dedupe key spoken in the last thirty minutes.
- *Turns*: two or three, each either local (templates with personality variation, the current style) or phrased by
  the voice when a person can hear (the same prompt with the peer as listener, costed like any reply); the other's
  turn is answered from the *record* of what was said, never by parsing the words (rule 4).
- *Delivery*: through the lane as now (the `[Nearby]` whisper to people within six blocks, or the full lines when
  they are within earshot), with speech timing, never to the whole server.
- *Silence*: with nobody to hear, the occasion still updates both ledgers (rule 1) and nothing is said.
- *Budget*: server-wide AI-to-AI lines at most four a minute by default (`KINDRED_CHATTER`, and `quiet`), a pair at
  most once in two minutes, none while either is fighting, fleeing, sheltering or in a match's countdown, and the
  recipient preferences of everyone in earshot (E-19a).
- *Playful*: callbacks to shared episodes ("after the pit last night I'm not following you anywhere"), personality
  humour, light teasing only under the `playful` preset and only between AI players.

**Acceptance.** JUnit: occasions, topic ranking, dedupe, the budget. GameTests: two AI players meeting after ten
minutes apart exchange two or three lines heard by a mock player beside them and not by one forty blocks off; with
nobody near, both ledgers update and nothing is spoken; three players over five minutes say at most twenty lines; a
fight stops a conversation; no AI line is ever delivered to an AI player as chat (the echo rule, asserted).

---

## E-52 Play by asking: command-free after creation

**Design.** The ask vocabulary of E-20b grows into `Intents` in `companion-core`: every ask a person makes of a
player in play, each mapped to the runtime method its command calls.

| Intent | Example | Runs | Who may |
| --- | --- | --- | --- |
| GOAL nether / end / dragon / none | "Echo, go for the Nether" | `startGoal` | controller |
| MODE relaxed / survival / speedrun | "Sol, play it safe" | the playstyle setter | controller |
| TEAM together / solo | "You two, work together" | `mode` (collaboration) | controller |
| MATCH duel / race / manhunt / build, with names | "Race me, Echo"; "Manhunt: me against Sol and Echo" | `propose*` | anyone; the match runs only when each person named says "I'm in" and each AI named accepts by its own rule |
| JOIN / LEAVE | "I'm in" / "count me out" | `joinMatch` / leave | the person, for themselves |
| BUILD theme here / RANCH animal here / FARM here | "Build a cabin here" | `startBuild`, `startRanch`, `startField` at the speaker's spot | controller |
| COME / WAIT / STOP / TOSS / FOLLOW (when a native follow exists) / GO HOME / SLEEP | as E-20b | as E-20b | controller |
| SKIN name | "Wear the knight skin" | the skin setter | controller |
| QUIET / TALK | "Keep it down" | the chatter setting | the person, for what they hear; a controller, for the player |
| FORGET that / THAT'S WRONG | "Forget what I said about the Nether" | the ledger's correction (E-50, F-20) | the person, for what they said |
| STATUS, WHERE, WHAT DO YOU NEED | speech only | the voice | anyone the tiers allow |

What stays a command: making and removing players, operator switches (`edits`, `dragonfight`, `bases`), limits,
areas and storage, and anything with coordinates in it. An operator may still do those by chat if they say so
("allow the dragon fight") with the same operator check; the design does not require it.

- *Parsing*: the model returns the intent and its typed arguments (as E-20b's `ask` and `item`), validated in core;
  without a model, a small local grammar reads the plain forms. An ambiguous ask is asked back once ("Race whom?")
  and then dropped. Every ask is answered aloud: done, or refused in the command's own words made its own.
- *Consent*: a match with people in it needs each person's own "I'm in" (the registry's rule is unchanged); an AI
  player named in a match answers by `MatchChoice` (pure): its personality, its state (not while hurt, hungry or on
  a goal stage it will not leave), fairness (no duel against a far better kit unless it chooses to), and says so.
- *Weighty asks*: the dragon needs the operator's switch; the player says what is needed rather than doing nothing.
- *AI to AI*: a player asks another through the same intents as records (a teammate asked for iron → `HandOvers`),
  never through chat.
- *The player proposes*: when idle with people or peers near, a sociable player may propose a match or an outing
  (E-27), at most once in thirty minutes, under the attention budget (E-53).

**Acceptance.** JUnit: the grammar's plain forms, each intent's argument bounds, `MatchChoice`. GameTests, one per
intent class: a goal by chat starts and is answered; a mode and a team change by chat; a race proposed by chat that
runs only after the mock person's "I'm in" and the AI's acceptance; a build by chat at the speaker's spot; a
non-controller's goal ask refused aloud; an ambiguous match ask asked back once and then dropped; an operator-only ask
from a non-operator refused in character; nothing moves on any ask that the command would refuse.

---

## E-53 Speaking first, within an attention budget

**Design.** `Initiative` in `companion-core` decides when a player speaks unasked, and to whom:

| Occasion | Line | Limit |
| --- | --- | --- |
| Meeting someone | a greeting (exists) | as now |
| A person near it is low on health or food and it has spare | "Need food? I've got bread." (a toss if they say yes) | once an hour per person |
| It needs something a person or peer might have | "Anyone got flint?" | once an hour per need |
| Its goal or stage changes | told to those it plays for (exists as `say`) | as now |
| A person's build seen for the first time | one remark | once per build |
| A threat near a person who may not see it | a warning | at once, then 30 s |
| Idle together | banter under the preset | the budget |
| Idle with people or peers near | a match or outing proposed (E-52, E-27) | once in thirty minutes |

The attention budget, per person: one unasked line in three minutes by default, fewer under `quiet` or their `fewer`
preference, more when they answer (a person who replies earns a little more; one who never does gets less, decaying
back over a day), none while they are in a fight, mid-build (placing fast), in a match's countdown, or away (no
movement for five minutes), and none at all beyond earshot. The budget is read from E-19a's preferences and the
ledger's record of who answers.

**Acceptance.** JUnit: the budget, its earning and decay, the occasions. GameTests: an offer to a hungry mock player
holding spare bread, and the toss on "yes please"; a request for flint spoken once in an hour; a warning about a
creeper behind a person; a person fighting gets no banter; a mock player who never answers gets fewer lines over
ten minutes than one who does, counted.

---

## Order and dependencies

1. E-18a and E-19a (small; everything speaks through them).
2. E-50 (the ledger; the voice and the team read it at once).
3. E-52 (asking is the command; the biggest change people notice).
4. E-53 (speaking first; needs the ledger and the preferences).
5. E-51 (AI conversations; needs the ledger, the budget and E-18a).

What this track does not do: expression beyond clean (the rest of E-18), gifts as items (E-21/E-22), outings
(E-27), hobbies (E-28), durable friendship records (E-24); each stays its own card and reads the ledger when built.

## Working beside other tracks

This track owns the social lane (`CompanionSocials`, `SocialBrain`, `SocialPerception`, `PeerProgressDialogue`,
`PlayerVoice`, `ConversationPrompt`, `Strategist.converse`), the team talk classes, `MatchRegistry`'s join rules and
the `peers` record in `PlayerMemoryStore`. In `PlayerRuntime` it adds hook lines only; its logic goes in new
collaborators (`ChatIntents`, `PeerRadio`). The next-levers track owns the eval writer, the snapshot, rescue, the atlas
and `Strategist.rescue`; the two touch `PlayerRuntime`, `Strategist` and `PlayerMemoryStore` in different places and
merge if each keeps to its own classes. `claude/modest-faraday-r3spro`'s F-18, E-40a, E-44 and M-00..M-04 work is in
the same files as this track; this track starts after that branch merges.
