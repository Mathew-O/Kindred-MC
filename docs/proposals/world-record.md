# The World Record and the Wire: information and communication on purpose

Status: proposal, 2026-11-08. F-31 to F-34 are reserved in the [tracker](../feature-spec.md); claim one in Active
work before implementing it. Companion to [Factions as Kindred's civilization mode](factions-mode.md) and to the
memory tiers sketched for a 50-player civilization.

## What exists

Durable state is spread across eighteen SQLite tables (players, the action ledger, the journal, memories, goals,
limits, projects, stock), about a dozen JSON files per player or world (atlas, bases, peers, rapport, work, customs,
model context, replays, encounters, rescue recipes), and in-memory registries rebuilt at restart (matches, claims).
Communication is several things: the social lane for speech, `PeerUpdate` records between AI players, team talk, the
strategist bridge, the panel payloads, the watch page's stream, the log. It all works; nothing says what a fact is,
who may know it, how it ages, or which channel it belongs on. Each new feature adds a file.

## The design, in five parts

### F-31 The World Record and the Ledgers

**The World Record** is one append-only table of events in the existing SQLite store, written only by the ordered
writer: `event(id, tick, real_time, world, kind, actor, subject, place, data, how_known)`. Kinds cover everything that
happens that anything later cares about: death, hit, trade, sale, bid, gift, toss, promise, claim, capture, power
change, build stage, match start and result, meeting, said-to, rescue, lesson. `how_known` is `recorded` (the game's
own record), `told` (a structured message), or `claimed` (words). Events are never updated or deleted; a correction is
an event.

**The Ledgers** are the exact facts derived from events and nothing else: inventory and ownership, faction power and
claims, coins, market stock and prices, promises and debts, friendship and wariness, settlement stock. Each ledger is
a table with a replay function that rebuilds it from the Record, and a nightly check that the two agree. Rules read
ledgers; no model writes one.

Migration: the action ledger and the journal become kinds of event (their tables stay for compatibility, written
through the same call); matches and claims become ledgers so a restart loses nothing; the JSON stores move one at a
time (atlas, peers, rapport, bases, work, customs) with their legacy files read once and kept.

Acceptance: JUnit for each ledger's replay from a recorded event stream, and for the agreement check; a GameTest that
plays a sale, a capture and a death, restarts the server, and finds every ledger rebuilt to the same state from the
Record alone; the schema migration (10) covered by the downgrade test.

### F-32 The Knowledge Web

The graph each player, faction and the world reason over: **entities** (players, people, factions, settlements,
places, notable items, claims, matches, promises) and **relations** between them (`owns`, `lives_at`, `claimed`,
`saw_at`, `needs`, `has_spare`, `promised`, `owes`, `allied`, `enemy`, `fears`, `friends_with`), each relation with:

- *provenance*: the event ids it rests on, and whether it is recorded, told or claimed;
- *validity*: from when, until when (a `saw_at` lapses in a game day; a `claimed` fact in a game day unless confirmed);
- *visibility*: who may know it: `self`, `faction`, `team`, `side`, `settlement`, `public`, `operator`;
- *weight*: how sure, from the provenance and how often confirmed.

Visibility follows relation, never proximity: a faction's claims and power are `public` (the spec's notifications), its
stock and members' positions `faction`; a manhunt runner's position is `side`; a person's position is `self` unless
they share it. The peer ledger (E-50) and the atlas (F-30) become views over the web rather than their own files; the
voice's situation, the panel and the watch page read the web through the viewer's visibility and never see more.

Rules: a thing someone said is `claimed` until an event confirms it, and is never stated as a fact; a relation whose
provenance is corrected ("forget that", F-20) is closed with the correction event as its end, never deleted; every
query names the viewer, and the web answers only what that viewer may know.

Acceptance: JUnit for provenance, validity, visibility and correction; a GameTest where a hunter's query never returns
a runner's position from any channel while a teammate's does; the voice's prompt for an opponent built from the web
carrying nothing beyond `public` and what it saw.

### F-33 The Chronicle

Compaction so months of play stay readable and cheap: per player per game day, per faction and settlement per week,
per world per month, each a short summary (150 to 300 tokens) written in a nightly batch by the strategist's model
(Haiku-class, the Batch API where the provider has one), reading only the tier below it, citing the event ids it
rests on, and checked by a test that every cited event exists and that no summary contradicts a ledger (a death count,
an owner, a price). Exact facts are never summarised away: the Chronicle is narrative; the Ledgers are truth. Without a
key, the Chronicle is a plain list of the day's events by kind, so nothing depends on a model.

Used by: the voice ("what happened last week?"), the watch page's history, the strategist's report (a player's recent
chronicle instead of a growing event list, which is what made F-19's prompts five times their design size), and the
month run's weekly reports.

Acceptance: JUnit for the tiers, the citation check and the contradiction check against staged ledgers; a run of the
evaluation engine for two game days with the Chronicle on, its summaries citing real events and the strategist's
report shorter than before by the measured amount.

### F-34 The Wire

Communication as typed messages on named channels, with words last:

| Channel | Audience | Examples |
| --- | --- | --- |
| `private` | one person or player | a reply, an offer, an ask's answer |
| `team` | a co-op team | a find shared out, a need, a hand-over |
| `faction` | a faction's members, people included | a capture announced, a raid planned, a sale made |
| `settlement` | everyone living at a place | the day's work, stock |
| `side` | one side of a match | a manhunt's hunters |
| `public` | everyone who may hear in earshot, or the server for mode announcements | a match proposed, a faction vulnerable (the spec's notification), a market shortage |
| `operator` | operators | refusals, anomalies, audits |

A message is a record (`Message(kind, from, channel, data, event ids)`) carried through the runtime; the Wire decides
who receives it from the channel's audience rule and each receiver's visibility; receivers that are AI players update
their web from it (`told`); only when a person is in the audience is it phrased, by the voice, for that person, within
the social lane's budgets and the recipient's preferences. Every channel has a budget (lines a minute) and a dedupe
window; `public` mode announcements bypass the budget but are few and fixed. Nothing an AI player says ever re-enters
the Wire as a message (the echo rule). The existing paths (team talk, `PeerUpdate`, the match presenter's titles, the
panel, the watch page) become Wire channels or readers of it.

Per mode: Co-op uses `team` and `private`; Manhunt splits everything by `side`; Speedrun posts splits on `public` and
nothing else; Builder is `public` plots and `private` designs until the reveal; Factions uses all of them, with
`faction` the busiest and `public` carrying claims, power and prices.

Acceptance: JUnit for audiences, budgets, dedupe, the echo rule; GameTests: a faction announcement reaching a mock
member and not an outsider; a manhunt hunter's `side` message never reaching a runner; a public shortage notice
phrased once per person in earshot; the budget holding under three players for five minutes.

## Rules, in one place

1. Every fact has a source and a time; said is claimed until recorded.
2. Visibility follows relation, not proximity; the viewer is named on every read.
3. Exact facts are never summarised away; summaries cite.
4. One writer, the ordered SQLite writer, for everything durable; nothing durable lives only in memory.
5. The audience is decided before a word is phrased; a model never sees what the listener may not.
6. Nothing an AI player says re-enters as input.

## Order

F-31 first (Factions mode writes its events there from the start), F-34 second (the faction channel is needed by
M-06i), F-32 third (the web replaces the peer ledger and the atlas files as views), F-33 last.
