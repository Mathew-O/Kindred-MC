# Factions as Kindred's civilization mode

Status: proposal, 2026-11-08. Children M-06c to M-06k are reserved in the [tracker](../feature-spec.md) under M-06;
claim one in Active work before implementing it. The owner's decisions (2026-11-08): the rules are implemented in
Kindred on Fabric 1.21.1 (no Paper plugins, no hybrid server); the first month-long run uses an 8,000-block border;
the market is the adaptive shop plus auctions, with no chest shops.

## The rulebook

The owner's *Factions SMP, Complete Server Rules and Systems Specification* (the "spec") is the rulebook. It was
written for a Paper plugin stack; Kindred cannot run on Paper and those plugins cannot run on Fabric, so this
proposal maps its rules onto Kindred's own code. Where the spec names a plugin, read "the rule that plugin enforced".
What the spec locks stays locked: power-based factions, −2 power per qualifying PvP death and +1 per online hour,
overclaim-and-loot raids with no TNT cannons, protected non-overclaimed territory, a safe spawn and market, a shop that
buys every legitimate item at adaptive prices, no jobs progression. Its proposed defaults (10 members, 80 chunks,
500 coins, 500-block spawn exclusion, 48-hour and 30-minute shields, 100 coins a claim, 3% and 25-coin auction fees,
5 listings, 48 hours) are the starting configuration, tunable in `config/kindred/factions.properties`.

What is cut for the first run, and why: custom enchantments (a plugin product, not a rule; vanilla enchanting stands),
chest shops (the owner's decision), the resource world, and a 24,000-block border (8,000 for the run: AI players live
in settlements and the pre-generation of 2.25 million chunks buys nothing).

## How it fits what exists

| Spec | Kindred today | This proposal |
| --- | --- | --- |
| PvP in the wilderness, none in safe zones; factions are enemies | `MatchRegistry` (D7): only players in a match they joined may strike each other; `GameModes.WorldMode` | Factions mode is a standing match every faction member joins by joining a faction; safe zones override it |
| Claims, protection against outsiders | `ClaimRegistry` (block and area claims for work), the edits policy, E-43's chest look | chunk-level faction claims with protection enforced at the block, container and explosion events |
| Power, vulnerability, overclaim, capture | deaths and online time are already recorded (F-11, the journal) | the power ledger, the vulnerability rule, border-first capture and ownership transfer through the recorded-action ledger |
| Shop, prices, sell everything | `Worth` (an item's value in play minutes), `Trades`, E-45, `StockLedger` | a currency ledger, an adaptive market with price curves, item canonicalisation and validation |
| Auction house | tosses, gifts (E-21/E-22), hand-overs | listings, bids, buy orders, timed closes, fees |
| Spawn hub, market district, border | the base and build code, `/worldborder` | a generated spawn and market district under a protection flag; the border set and checked per dimension |
| Audit, rollback | the recorded-action ledger, the journal, death replays | the World Record ([proposal](world-record.md)) carries every capture, sale and power change |
| Permissions, admin commands | Kindred's own access model (`PlayerAccess`, operators) | admin commands under `/aiplayer factions` and `/faction`; no LuckPerms |

The rules live in `companion-core` as pure classes with JUnit tests, exactly like matches and claims today, so the AI
players can reason about power, claims and prices in their planners; the adapter only enforces them at the game's
events and renders them. A third-party claims or economy mod would leave the AI blind to its own state, which is why
porting one is not the plan.

## M-06c Factions and power

`Faction` and `PowerLedger` in core: name, tag, members (at most 10), ranks (leader, officer, member), relations
(enemy by default, ally, truce), home; per member a power of 0 to 10 with regeneration progress kept to the second
and persisted across restarts (the spec's "preserved across logouts"). Events: a qualifying PvP death (the killer in
an enemy faction, in the wilderness or claimed land, not in a safe zone) costs the victim 2; an online hour restores 1,
read from the same online accounting F-11 keeps; environmental deaths cost nothing. Anti-farming: the same killer and
victim pair counts at most once in 20 minutes, and a death to a faction-mate, an ally or an account that joined the
server in the last hour counts nothing (AI players have no alts, but people do). Faction power is the sum of its
members' power; claim capacity is the lesser of faction power and 80.

Persistence: `factions`, `faction_members`, `power` tables through the ordered writer (schema 10). Commands for
people: `/faction create|invite|join|leave|kick|promote|ally|enemy|home|info|list|map`; AI players use the same
methods through asks (E-52).

Acceptance: JUnit for every rule in the spec's table and its two worked examples (10→8 on a PvP death; 8→9 after sixty
accumulated online minutes, not fifteen; 45 claims at 50 power safe, three exposed at 42). A GameTest: a faction of two
AI players and one mock person, a PvP death in the wilderness costing the victim two power and the faction its
capacity, a restart keeping the regeneration progress to the second.

## M-06d Chunk claims and protection

`Territory` in core: chunk claims by faction, adjacency required for ordinary expansion, the one-chunk neutral
separation between unrelated factions, no claims within 500 blocks of spawn or beyond the border, 100 coins a claim,
the 80-chunk cap. Protection in the adapter at the events Fabric gives: block break and place
(`PlayerBlockBreakEvents`, `UseBlockCallback`), container and redstone use, explosions (a mixin on the explosion's
block list: none in protected land), and the bypasses the spec names: fluids and pistons do not cross a claim border
into protected land, hoppers do not pull across it, projectiles do not trigger across it. Outsiders' public use is only
what a claim's flags allow (doors and buttons by flag, nothing else). Enforcement is the same code for people and AI
players; an AI player's recorded action checks the territory at admission and again at dispatch.

Acceptance: JUnit for adjacency, separation, exclusion, cap and fees; GameTests for each bypass (a piston cannot push
a block into protected land; water poured at the border does not flow in; a hopper does not pull from a protected
chest; a creeper's blast leaves protected blocks standing), and an AI player's own action refused inside another
faction's claim with nothing moved.

## M-06e Overclaim and capture

`Capture` in core, the spec's section 4 exactly: a faction is overextended when claims exceed power; the exposed count
is the difference; only an exterior chunk of the defender's territory that touches the attacker's own claim or the
wilderness can be captured first, then contiguous chunks inward; the attacker needs the capacity and 100 coins; the
48-hour one-time shield for a new faction and the 30-minute shield after a faction loses its first chunk in an
encounter; no rapid swapping of the same chunk (one capture a chunk an hour). On capture the ownership transfers, the
chunk's containers become the new owner's under its flags, nothing is moved or duplicated (the recorded-action ledger
observes the transfer as a fact, not a move), both factions are told, and the World Record keeps the event. Before
capture a vulnerable claim is protected exactly as a secure one.

Acceptance: JUnit for the spec's acceptance scenarios 2 to 5 (trigger, boundary, pre-capture protection, protected
spaces); GameTests: an attacker cannot open a chest in a vulnerable but uncaptured chunk; a border chunk captured and
its chest opened by the new owner with its contents unchanged; an interior chunk refused; the 30-minute shield; a
recapture when the attacker's power falls.

## M-06f Safe zones, spawn and the border

A spawn hub and a market district generated at (0, 0) from blueprints (the builder's own generators), flagged
`safe`: no PvP, no claims, no block changes except by operators, never capturable. The border set to 8,000 across
with a 20-block warning and checked in the Nether (1,000 across, the eighth) and the End; portals that would land
outside the border are refused and said so. Claims and captures are refused beyond the playable area.

Acceptance: GameTests for a strike in the hub doing nothing (and the striker told), a claim at 400 blocks refused and
at 600 accepted, a Nether portal that would exit beyond the border refused.

## M-06g Currency and the adaptive market

`Coins` (a balance ledger per player, 500 to start, every change an event) and `Market` in core: for each market item
a baseline price, virtual stock, recent volume, volatility class (mass-farmable, ordinary, scarce), floor, ceiling and
a maximum change per hour; selling raises supply and lowers the next unit's price along the curve, buying does the
reverse, idle items revert toward baseline. Bulk sales walk the curve unit by unit and settle atomically with the item
removal through the recorded-action ledger (both or neither). The spread is 15% each side of mid-price by class.

Items are keyed by material plus the variant that matters: durability band, the enchantments that carry value, potion
type, banner and trim patterns canonicalised; anything with metadata the canonicaliser does not know is *valuation
pending* and not sellable, never silently paid. Containers are refused for sale (shulkers are emptied by the seller
first). Admin-only materials never sell. A newly added item is pending until a baseline is set. No loop of buying,
crafting, smelting, repairing or uncrafting may mint coins: a JUnit test walks every recipe the guide knows against the
baselines and fails on any positive loop.

The market is reached from the hub (a market block and `/shop`) with a searchable browser in the chest-GUI style the
control deck uses, hover prices, 24-hour change, `/market top` and `/market history <item>`; `/sellall` shows the
estimate, then settles. Prices and history persist and are inspectable and reversible by an operator.

Acceptance: JUnit for the curve, the classes, floors and ceilings, reversion, the canonicaliser's edge cases (damaged
tools, enchanted books, potions, renamed items, filled maps, suspicious stew), the arbitrage walk; GameTests for an
atomic `/sellall` of a stack moving the price, a disconnect mid-sale leaving both sides whole, a pending item refused
with its reason.

## M-06h Auctions

`Auctions` in core: listings (5 a player), bids, buy orders, 48-hour closes, a 25-coin listing fee and 3% sale fee,
the item held in escrow by the ledger from listing to close, returned on no sale; closes processed on the server
thread from a persisted queue so a restart loses nothing. `/ah` as a GUI and as commands. Player-to-player sales
never mint coins (the buyer's coins move, minus the fee, which is destroyed). Auction prices feed the market's demand
signal at low weight after a wash-trading filter (the same two accounts trading the same item back and forth count
nothing).

Acceptance: JUnit for fees, caps, escrow, closes, the filter; GameTests for a listing, a bid, a close that pays and
delivers, a restart mid-auction, a buy order filled by a later listing.

## M-06i AI players in a faction world

The reason for all of it: AI players that play the mode. In core, `FactionPlay` decides what an AI player does with
these rules, from its personality, its faction's state and what it knows (E-50's ledger, the World Record's public
facts): found or join a faction (sociability and the roster's relations), choose and claim a base site (M-01c, M-06a,
M-06b) inside the rules, expand claims as power allows, keep power by not dying (the survival rules weigh PvP risk by
`FightOdds`, E-41h), earn coins at the market from what it gathers (`Worth` tells it what to sell and what to keep),
buy what it lacks, bid on what it values, scout enemy territory through "saw at" facts only, raid by the rule
(E-44's raid question becomes the capture decision: fight in the wilderness, capture an exposed border chunk, loot
what is now its own), defend (return home when a capture is announced), and talk about it in its faction's channel
(the Wire) and never outside it. Humans in a faction are faction-mates in every rule and every channel.

Acceptance: fixtures per decision (found, claim, sell, buy, bid, scout, capture, defend) and, through the evaluation
engine, a Factions scenario: eight AI players in three factions on an 8,000-block border for twelve game days, with
claims, captures, sales and deaths counted, nothing captured outside the rule, nothing sold that was pending, no
coin minted.

## M-06l The Factions HUD

What a person sees while playing: a semi-transparent panel down the right side of the screen, drawn by the Kindred
client mod (`HudRenderCallback`, beside the K panel and skins), fed by a server payload that changes only when its
facts change (at most once a second) and carries only what that person may know (F-32's visibility: their own
faction's private numbers, everyone's public ones).

Layout, top to bottom, in the panel's dark translucent card (about 35% opacity, a thin lighter edge, rounded corners,
Minecraft's own font; width about a quarter of the screen, scaled with the GUI scale):

1. **Faction**: tag and name in the faction's colour, members online of total, the leader marked.
2. **Power**: the person's own power as `7 / 10` with a thin bar and a smaller bar for the hour's regeneration
   progress; the faction's power and claims as `power 42 · claims 45 / 80`, turning amber when claims approach power
   and red with **3 exposed** when they exceed it; shield timers (new-faction, post-raid) counting down.
3. **Coins**: the balance, and the last change fading in and out (`+120 sold oak logs`).
4. **Here**: whose land the person stands in (`Wilderness`, `Safe zone`, `Your land`, `Red Hand (enemy)`), with
   the chunk's exposure if it is capturable (`exposed, capturable`), updated as they cross chunk borders; the same
   change also spoken once on the action bar for vanilla clients.
5. **Standings**: the top five factions by power with claims and members, the person's own row always shown, arrows
   for a change since the last hour.
6. **Notices**: the last three faction-channel events (a capture, a vulnerability, a sale), fading after a minute.
7. **Market** (collapsed by default): the five biggest movers of the last 24 hours and the price of the item in hand.

Behaviour: a key (default `H`) cycles full, compact (faction, power, coins, here) and hidden; the opacity, scale and
side (right by default, left as an option) are client settings; the panel hides while a chest or the K panel is open
and during the match countdown titles; nothing in it is clickable (controls stay in commands, chat asks and the K
panel). Colours are the faction's chosen colour plus a fixed palette for states (amber warning, red exposed, green
safe) that stays readable over any sky.

Vanilla clients, which cannot draw a custom HUD, get the fallback the match presenter already uses: a sidebar
scoreboard with faction, power, claims, coins and standing, and the action bar for the land they enter; the server
chooses by whether the client answered the Kindred handshake.

Acceptance: a client-side rendering test of the panel from a staged payload (each section, each state colour,
compact and hidden); a GameTest that a payload to an outsider carries no private faction numbers and that a chunk
crossing produces one action-bar line; the panel seen on a development client over three skies (day, night, Nether)
and the vanilla sidebar seen on an ordinary client, recorded with screenshots in the evidence.

## M-06j The Factions pack

Kindred's Factions rules are inside the Kindred jar. The *pack* is the server's `mods` folder and configuration for
the mode, kept in the repository as a manifest, never as third-party jars: `packs/factions/` with a Modrinth
`.mrpack`-style index (each mod by name, exact version, download, licence and purpose), the `server.properties`,
`config/kindred/*.properties` for the mode, the pre-generation and backup instructions, and the admin checklist from
the spec's section 10 rewritten for Kindred. Candidates for the pack, each to be checked for its 1.21.1 build and its
licence before it is listed: a chunk pre-generator (Chunky), a profiler (spark), performance mods (Lithium,
FerriteCore), a backup mod; none of them owns rules. Only mods whose licences allow listing in a pack are listed, and
nothing is bundled in Kindred's jar.

Acceptance: a fresh Fabric 1.21.1 server built from the manifest starts, pre-generates the 8,000-block world, and
passes the admin checklist; documented in the operator guide.

## M-06k The month run

The gate: a month on the owner's server (Zeus, 12 GB) with the pack, eight or more AI players in factions and whatever
people join, under the evaluation engine's recording (`KINDRED_EVAL` recording only, no scenario) and the watch page.
Recorded: power and claims over time, captures and recaptures, market prices and volumes, auction activity, deaths
by cause, tick time, memory and chunk count, model tokens, and every rule refusal. Passing: no rule broken (no capture
outside the rule, no coin minted, no protected block changed, no PvP in a safe zone), the server at 20 ticks a second
at the 95th percentile, and the players still playing on day 30. A failure of any of those is reported with its event.

Acceptance: the run's record filed under `docs/evidence/eval/factions-month/` with weekly summaries and the final
report.

## Order

M-06c, M-06d, M-06f (the rules with nothing to buy yet), M-06g, M-06h, M-06e (capture needs coins and claims), M-06i,
M-06l, M-06j, then M-06k on the owner's server. F-31 to F-34 ([the World Record and the Wire](world-record.md)) run beside it:
M-06c onward write their events to the World Record from the start rather than to new JSON files.
