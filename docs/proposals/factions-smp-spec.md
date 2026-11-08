<!-- The owner's rulebook for Factions mode, added to the repository 2026-11-08 as written (a Paper/plugin blueprint). Kindred implements its rules on Fabric 1.21.1; see proposals/factions-mode.md for the mapping and for what is cut for the first run. -->

# Factions SMP — Complete Server Rules & Systems Specification

**Status:** Proposed implementation blueprint (not a verified, ready-to-install configuration)  
**Edition:** Minecraft Java, Paper server  
**Gameplay:** Survival + classic power-based Factions + overclaim-and-loot raids + adaptive server economy  
**World border:** **24,000 blocks across (diameter)**, centered at world coordinates **X=0, Z=0**. The playable X/Z range is approximately **−12,000 to +12,000**.  
**Server philosophy:** Fight for territory, profit from resources, trade valuable gear, and keep dedicated safe commercial space. No jobs plugin.

> **Border interpretation:** Minecraft's `/worldborder set 24000` sets the *full width* to 24,000 blocks (12,000 blocks from the center in each direction).

---

## 1. Core Player Experience

1. Join the server with a **500-coin starting balance** and **10/10 personal power**.
2. Gather resources, establish or join a faction, and claim a base in the wilderness.
3. Sell **all legitimate, obtainable items** to the adaptive `/shop`; buy items where the market allows; trade with players through `/trade`, `/ah`, or physical chest shops.
4. Obtain and trade custom enchantments and rare equipment.
5. Compete through wilderness PvP. **Qualifying PvP deaths cost power**, potentially exposing a faction's overextended claims.
6. **Overclaim vulnerable border chunks** and loot captured land. This is **not** a TNT-cannon raiding server.
7. Build faction territory or use permanently safe market plots to protect a trading business.

### Server Rules (Player-Facing)

- No hacked clients, duplication exploits, combat-logging exploits, claim-bypass exploits, or alternate accounts used to manipulate power or markets.
- PvP is allowed in wilderness. PvP is **disabled** in spawn and designated neutral market areas.
- Claims belonging to factions that remain within their power limit are protected against unauthorized building, breaking, container access, redstone access, and destructive explosions.
- Raiding requires **legitimate overclaiming**. Attackers do **not** get to steal from or destroy a protected claim just because the defender's power is low; they must first capture a valid chunk.
- Captured chunks change ownership. Their containers and contents are accessible to the new owner according to the new faction's permissions. Former owners may fight back and **reclaim** the territory later.
- No TNT-cannon raids or unrestricted explosion-based claim destruction. Do not use pistons, fluids, hoppers, mobs, or block-border exploits to bypass claim protections.
- Spawn and official safe commercial plots are permanently non-raidable and cannot be overclaimed.
- World border is hard and non-expanding unless administrators announce a future season change. No border glitches or illegal building beyond it.
- Server shop is a **price-changing buy/sell service**, not a promise of fixed profits. Market manipulation and item duplication are bannable.
- Custom enchantments must follow server item and combination limits. No pay-to-win exclusive combat enchantments.

---

## 2. Worlds, Border, and Map Layout

### Main survival world

- World border: **24,000 blocks diameter**, center **(0, 0)**.
- Standard admin command (in the appropriate overworld):

  ```mcfunction
  /worldborder center 0 0
  /worldborder set 24000
  /worldborder warning distance 20
  ```

- Border edges are approximately at X = ±12,000 and Z = ±12,000, producing a roughly **576 million block²** horizontal area (24,000 × 24,000).
- Pre-generate terrain within the border using a compatible world-generation tool to reduce exploration lag; leave safety margin as appropriate for border handling.
- Do **not** assume setting the overworld border automatically enforces equivalent playable limits in every Nether/End configuration. Explicitly configure and test each dimension's access, travel, portals, and border behavior.
- Do not allow faction land claims beyond the effective playable area.

### Safe areas

- **Spawn hub:** centered around (0, 0), protected by WorldGuard, with no PvP, griefing, or claiming. Recommended launch claiming exclusion radius: **500 blocks** from spawn (exact shape enforced through region/claim rules).
- **Neutral commercial district:** admin-protected stalls / plots, always safe from faction warfare. Use explicit lease / ownership policy if renting stalls.
- **Wilderness:** claimable faction territory outside protected zones. PvP enabled.
- **Resource world (optional, later phase):** separate from permanent survival claims, with published reset schedule and no persistent player bases. Its economy contribution must be monitored.

### Claimed land and territory shape

- Claims use 16 × 16 block chunks; one chunk occupies 256 horizontal block².
- Implement a **1-chunk neutral separation / anti-trapping rule** between ordinary independent faction settlements when feasible. Any exception for battlefield contact should be explicit; do not prevent legitimate border overclaims through this rule.
- Require adjacent claims for regular expansion to avoid claim-spam and long tendrils. For enemy overclaims, allow the first eligible border capture from accessible territory, then enforce connected capture progression.
- Deny player-created walls or slivers intended solely to trap other players inside claims.

---

## 3. Factions and Power

| Rule | Proposed launch setting |
|---|---|
| Max faction members | **10** |
| Starting personal power | **10** |
| Maximum personal power | **10** |
| Power loss per qualifying PvP death | **−2** |
| Power regeneration | **+1 power per hour of online time** |
| Regeneration when offline | **No** |
| Regeneration progress across logouts | **Preserved** (custom behavior if not supported natively) |
| Environmental death power loss | **Disabled** |
| Claims supported | **1 chunk per current faction power** |
| Absolute faction claim limit | **80 chunks** |
| Protected spawn exclusion | **500-block radius** (recommended) |
| Personal power minimum | **0** for the planned model; verify plugin supports this |

**Faction power = sum of current member power.** Claiming capacity is the lower of (a) faction power in chunks and (b) the absolute **80-chunk cap**. A member at 10 power supports at most 10 chunks; 10 fully powered members generate 100 power, but the faction still cannot hold more than 80 chunks under the hard cap.

**Vulnerability rule:** When `claimed_chunks > current_faction_power`, up to that many chunks are **potentially overclaimable**, subject to border-first capture and other safety rules. Do not assume attackers may capture any internal chunk immediately.

**Example:** Five members × 10 = 50 power. They control 45 chunks. Four qualifying PvP deaths cost 8 power, lowering their faction to 42; **3 chunks become overclaimable**. At +1 power/hour per online member, recovery depends on the number of members online and whether each is below their personal cap.

### Prevent power abuse

- Qualifying PvP kills must have an anti-farming cooldown for repeated kills of the same opponent and for cooperating accounts.
- No power farming via alts, logout/login resets, faction hopping, or rapid faction disband/recreation.
- Power must be accurately preserved and restored across restarts.
- Trigger visible notifications when a faction becomes vulnerable, regains security, or loses a chunk.
- If a faction loses members, recalculate capacity and vulnerability immediately, but enforce any required anti-abuse or short grace policy.

---

## 4. Overclaim-Only Raiding

**Chosen model:** **Permanent capture with later counterclaim possible.** No temporary loot-only raids, no TNT cannon mechanic.

### Attack sequence

1. Enemies fight the defending faction in an allowed PvP area.
2. Qualifying PvP deaths lower defender faction power by 2 each.
3. If a faction's claimed chunks exceed current power, it enters an **overextended / overclaimable** state.
4. An attacking faction with sufficient available power and remaining capacity captures an **eligible exterior/border chunk**. Raids progress inward by capturing contiguous chunks.
5. **Only after a chunk changes ownership** may the attacker open and remove items from that captured chunk, subject to new owner permissions.
6. The defending faction may later overclaim those chunks back if the attacker's power falls sufficiently.

### Raid protection matrix

| Action | Secure faction claim | Eligible but not-yet-captured enemy claim | Successfully captured claim | Spawn / safe market |
|---|---|---|---|---|
| Enemy place/break blocks | No | No | New owner / trusted members only | No |
| Enemy access protected chests | No | No | New owner / trusted members only | No |
| Explosive block damage | No | No | Disabled by raid policy | No |
| Overclaim | No | Yes, when power and border rules allow | Can be recaptured if vulnerable | Never |
| PvP | Per configured faction combat rules | Per configured faction combat rules | Per configured faction combat rules | No |

### Recommended fairness safeguards

- **48-hour new-faction grace period** against overclaims (one-time, not resettable by recreating a faction).
- **30-minute post-capture recovery shield** after a qualifying raid outcome, with strict anti-reset / anti-abuse behavior. Exact trigger must be specified in implementation; recommended: after a faction loses its first chunk in an encounter, subsequent overclaims against it pause for 30 minutes.
- Use a **border-first capture** rule, not arbitrary attacks against deep interior claims.
- No hostile access to protected items or land before ownership transfer.
- No fluid, TNT, piston, hopper, projectile, or other cross-boundary bypass.
- Limit abusive repeated captures or rapid swapping of the same chunk.
- Permit legitimate counterattacks and future recapture once conditions are met.

> **Implementation caveat:** FactionsUUID-style power/overclaim mechanics are an appropriate base, but **border-only captures, grace windows, anti-farming, conditional storage access, and some explosion rules may require a companion Paper plugin**. Verify the selected Factions plugin exposes the necessary events and permissions before developing that integration.

---

## 5. Economy — Sell Every Legitimate Item With Adaptive Prices

### Goal

`/shop` must accept **every legitimate item obtainable on the server**, including vanilla items, tools, enchanted equipment, custom enchant books, and other approved custom items. Illegal/admin-only objects, invalid metadata, unsupported containers, and exploit-generated items must not be cashed out. Selling is universal **after safe valuation and validation**; buying from the server can be selectively restricted.

### Channels

| Channel | Function |
|---|---|
| `/shop` | Server-run market; all valid items sellable; supported items buyable; prices dynamically change |
| `/sellhand`, `/sellall` | Bulk sale convenience, subject to price-impact calculation and permissions |
| `/ah` | Global player listings, timed auctions, bidding, and buy orders |
| `/trade` | Confirmation-based direct item and currency exchange |
| Physical shops | Player-owned chest shops inside claims and safe market plots |

### Price engine principles

- Each item has a stable market identifier. Vanilla `material` alone is insufficient for tools and custom items; key by **material + approved variant / relevant metadata / enchantment category**.
- Maintain a **baseline/fair-reference price**, **current virtual stock**, **recent buying/selling volume**, and **volatility settings** per market item.
- **Selling into `/shop` increases effective supply and tends to reduce the next unit's price**.
- **Buying from `/shop` decreases effective stock and tends to raise the next unit's price**.
- Player-to-player auction signals may inform demand but must be **filtered, low-weight, and resistant to wash trading**. Player-to-player trades must not directly mint currency.
- Recalculate prices on each transaction (or within an atomic batch), not only once per day.
- **Bulk sales move through the price curve:** 2,304 items are not all paid at the first item's price.
- Use configurable floors, ceilings, maximum change per time window, and gradual reversion toward baseline if trading stops.
- Each item should have category-specific sensitivity: cheap, mass-farmable materials move slowly and have low price floors; scarce items can move more but remain capped.
- All sales and payments must be atomic: item removal and currency payout succeed together or both roll back. No double-spend on lag, disconnect, or inventory errors.
- Prices and history persist across restarts, and administrators can inspect / roll back anomalous events.

### Recommended initial transaction settings

| Setting | Starting recommendation |
|---|---|
| Starting money per player | **500 coins** |
| Sell eligibility | **All legitimate, safely valued items** |
| Server buy inventory | **Most ordinary items**; restricted for ultra-rare/custom equipment |
| Server sell payout | Dynamic based on current supply/demand |
| Server buy price | Dynamic with a spread above sale payout |
| Initial buy/sell spread | **About 15% on each side of mid-price** (tune per category) |
| Minimum prices | Positive per-item floors (may require fractional internal coin units) |
| Maximum prices | Per-item price ceilings and rate limits |
| `/shop` currency creation | Currency enters via purchases of player-supplied goods |
| Auction sale fee | **3%** |
| Auction listing fee | **25 coins** |
| Base auction listing cap | **5 active listings/player** |
| Auction duration | **48 hours max** |
| Direct trade fee | **0** |
| Base physical shop cap | **10 shops/player** |
| Claim purchase fee | **100 coins/chunk** initially, in addition to power/capacity limits |
| Recurring land tax | **Disabled** |

The server's buy/sell price must never permit risk-free profit from buying back and reselling, including crafting/uncrafting, smelting, enchanting, item repairs, and container workflows. Individual item baselines should be balanced against actual production rates; there is **no promise of unlimited profit**.

### Dynamic economy user interface

- `/shop`: searchable item browser and categorized browsing.
- Hover details: live sell price, live buy price (if enabled), 24-hour change, current market condition, current item eligibility.
- `/market top`: strongest gains and declines.
- `/market history <item>`: recent price history (if implemented).
- `/sellall`: estimate payout first when feasible, then execute an atomic transaction using the batch price curve.
- Show notice when prices fall due to heavy supply or rise due to shortages.

### Item-specific valuation and exploit prevention

- Blocks, crops, mob drops, ores: dynamic value per defined material/variant.
- Weapons, armor, tools: value conditioned on **durability, permitted enchants, item variant, and validated metadata**.
- Custom enchant books: rarity-sensitive, metadata-validated, capped valuation.
- Shulker boxes and other container items: **never double-count container and contents**. Either refuse unsafe states until supported or atomically unpack and value contents once.
- Filled maps, signed books, renamed items, player heads, suspicious stews, potions, fireworks, banners, trims, and unique metadata: explicit canonicalization and validation; never permit arbitrary NBT data to inflate payouts.
- Non-stackable items and durability variants must use safe rules and cannot be sold as if they were pristine items.
- Block newly introduced items from payout until a safe fallback price/identifier is assigned; publish as a temporary **valuation pending** exception rather than silently minting money.
- Deny `STRUCTURE_BLOCK`, `COMMAND_BLOCK`, barrier, debug and other admin-only materials from cashout even if acquired by exploit.
- Log abnormal market events, self-trading rings, extreme bulk sales, and unusual mint volume; permit staff investigation.

### Economic purpose

`/shop` is a reliable source of *some* currency, but a popular automated farm should saturate its own market and suffer lower marginal payouts. The auction house should often be more profitable for sought-after rare items; server purchases remain a floor/exit option. Money sinks include claim purchases, auction fees, and optional cosmetic services.

---

## 6. Custom Enchantments and Equipment

- Suggested plugin: **ExcellentEnchants** with **NightCore** dependency, contingent on supported build/version.
- Custom enchant books and equipped items should retain their metadata through `/ah`, `/trade`, chest shops, death drops, and any `/shop` sale.
- Start with rarity tiers: **Common, Uncommon, Rare, Epic, Legendary**. These are design labels, not necessarily native plugin rarity tiers.
- Example economy roles: mining utility, farming utility, weapon utility, PvP, and scarce collector enchantments.
- PvP enchant combinations must be capped, with high-impact enchants limited to about **2–3 per item** subject to balancing.
- No unavoidable one-hit kills, permanent invulnerability, or pay-only combat advantages.
- Obtainable via controlled game systems such as enchanting, loot, exploration, or events; rare enchant books can be sold on the market.
- Validate metadata serialization and compatibility before launch. Back up plugin data and items before upgrades.

---

## 7. Claims, Shops, and Ownership Changes

### Protected claims

- Owners and authorized faction members may build, mine, farm, and access assigned storage.
- Outsiders can only use explicitly public shop interactions. **Public shopping must not grant private chest or hopper access.**
- TNT, mob explosions, pistons, redstone, liquids, and external hoppers cannot bypass protected territory.

### Claim capture integration

On a successful overclaim:

1. Complete the faction ownership transfer.
2. Re-evaluate all protection and trust permissions.
3. Reconcile any chest-shop owner, shop stock, active leases, and shop transaction permissions.
4. Preserve item contents without duplicating or deleting them.
5. Notify both factions and record an auditable capture event.
6. Ensure captured assets cannot be liquidated through race conditions during the transfer.

**This is an integration risk.** QuickShop-Hikari has land-management integrations, but compatibility with the *selected Factions plugin and its overclaim events* must be validated. A custom shop-ownership bridge may be necessary.

### Safe market

- A permanent admin-protected marketplace where merchants can lease/staff shops.
- Never raidable or overclaimable.
- Use clearly defined lease limits, expiration handling, abandoned stock return, and anti-monopoly rules if rentals are added.

---

## 8. Suggested Plugin Stack (Verify Exact Builds)

| Need | Preferred implementation | Notes |
|---|---|---|
| Minecraft server | **Paper** | Target a single version supported by all plugins; previously discussed **1.21.10** as a candidate, not a guarantee |
| Faction claims / power / overclaim | **FactionsUUID** | Verify exact release/version and available hooks for custom raid rules |
| Currency | **EssentialsX Economy** | Use **one** authoritative economy provider |
| Economy bridge | **Vault-compatible API bridge** | Check whether each selected plugin requires Vault vs VaultUnlocked; do not blindly install conflicting bridges |
| Adaptive `/shop` | **DynamicShop candidate** | Validate full vanilla item coverage, API, metadata handling, price updates; custom price extension likely |
| Auction house | **EzAuction candidate** | Auction listings, bids, buy orders |
| Chest shops | **QuickShop-Hikari candidate** | Validate claim ownership transfer behavior |
| Secure trading | **AxTrade candidate** | Test enchant and currency exchanges |
| Custom enchants | **ExcellentEnchants + NightCore** | Verify cross-plugin NBT and item behavior |
| Permissions | **LuckPerms** | Restrict all high-impact commands |
| Safe spawn and market regions | **WorldGuard + WorldEdit** | Define non-PvP and non-raidable zones |
| Rollback/auditing | **CoreProtect** | Capture, grief, and loot investigation |
| Profiling | **spark** | Diagnose performance under load |
| Custom integrations | **FactionRaidRules + MarketBridge** (tentative names) | Only if native plugins cannot enforce exact rules |

Do not install two plugins that both own `/shop`, claims, or primary account balances. This table is a **target architecture**; it is not a compatibility certification.

---

## 9. Custom Integration Requirements

### `FactionRaidRules` (if required)

- Enforce power loss only on eligible PvP deaths and +1/hour online regeneration with persisted partial hour.
- Block PvP kill farming and power manipulation by alts/faction cycling.
- Enforce **80-chunk cap**, border-only overclaiming, connected capture progression, safe-zone exceptions, and anti-trapping.
- Apply 48-hour one-time new-faction protection and 30-minute post-raid grace without repeatable exploits.
- Separate **overclaim-eligible** from **authorized to access items**. Actual loot permissions change only upon capture.
- Deny all non-raid bypass mechanics (TNT/fluids/pistons/hoppers/etc.).
- Publish events / logs for claim transfer and market/shop integrations.

### `MarketBridge` (if required)

- Ensure every valid vanilla and approved custom item has a safely identifiable sell entry.
- Provide atomic `/sellall` and exact marginal-price accounting for bulk sales.
- Safely compute prices for durability, enchants, variant metadata and containers.
- Maintain price histories and filtered, anti-manipulation demand signals.
- Monitor duplication, currency creation, and anomalous transaction volume.
- Provide admin controls for baseline updates, price caps, pause, and audit/export.

---

## 10. Proposed Admin Configuration Checklist

### Prelaunch

- [ ] Choose one exact Paper version and verify a compatible build for **each** required plugin.
- [ ] Create/restore world backup before installing economy or protection plugins.
- [ ] Set hard 24,000-diameter overworld border centered at (0, 0).
- [ ] Configure portal travel and dimension-specific borders/safety explicitly.
- [ ] Pre-generate terrain within world bounds.
- [ ] Protect spawn and permanent market region with WorldGuard.
- [ ] Set maximum faction members to 10 and hard claim cap to 80.
- [ ] Set max power 10, PvP loss −2, online regeneration +1/hour; verify persistency.
- [ ] Enable overclaim capture while preventing TNT-cannon / pre-capture griefing.
- [ ] Test overclaim border-only rules, new-faction shield, and anti-farming protections.
- [ ] Install **one** economy provider; test balances and payments through all plugins.
- [ ] Populate dynamic market with all legitimate item classes and safe metadata pricing.
- [ ] Configure `/ah` taxes, listing limits and duration.
- [ ] Test public chest-shop purchases without storage permission leakage.
- [ ] Validate custom enchant data across every transaction type.
- [ ] Add backups, anti-dupe monitors, incident logs, and rollback procedures.

### Acceptance test scenarios

1. **Power accounting:** 10→8 on eligible PvP death; online regen 8→9 after 60 accumulated online minutes, not 15.
2. **Overclaim trigger:** 45 claims at 50 power is safe; after four −2 deaths, 45 claims at 42 power exposes 3 claims.
3. **Capture boundary:** Attackers cannot jump directly to an internal treasure chunk; border/connected sequence enforced.
4. **Pre-capture protection:** Even when a claim becomes vulnerable, attackers cannot open chests or break blocks until they legally capture it.
5. **Protected spaces:** No faction action can capture protected spawn or admin commercial plots.
6. **Shops after capture:** No duplicated money, wrong-owner shop transactions, or vanished stock.
7. **Every-item sell path:** Test vanilla items and edge cases including damaged tools, enchant books, potions, shulkers, renamed items, and custom metadata; deny unsafe/illegal items with an explanation.
8. **Dynamic-price bulk sales:** Selling a stack affects subsequent units' valuation and commits atomically.
9. **Arbitrage:** Buy/sell and crafting loops cannot mint free money.
10. **Border:** Players cannot cross or exploit travel beyond the ±12,000 overworld limit; portal transitions remain predictable.
11. **Persistence:** Restart mid-market activity and mid-power regeneration without losing state or duplicating assets.
12. **Load testing:** Run farms, auctions, and PvP concurrently and monitor ticks, database load, and market transaction latency.

---

## 11. Locked Decisions vs. Implementation Choices

### Locked by server concept

- 24,000-block **hard world border** (full width).
- Classic **power-based Factions** with territory loss from qualifying PvP defeats.
- **+1 power per hour online** and **−2 per PvP death**.
- **Overclaim-and-loot** raids, **not TNT-cannon** raids.
- Fully protected *non-overclaimed* faction territory; safe spawn and market always protected.
- Player shops, auctions, direct trading, custom enchants.
- `/shop` buys **every legitimate, safely valued in-game item**, with **supply/demand adaptive pricing**.
- No jobs-based progression.

### Proposed defaults to tune in testing

- 10-player faction limit, 80-chunk hard cap, 500-coin starting funds.
- 500-block spawn claim exclusion, 48-hour starter grace, 30-minute raid recovery shield.
- +1 supported chunk per power, 100 coins for new claims, no upkeep tax.
- Auction 3% tax, 25-coin fee, 5 listings, 48-hour duration.
- Market spread and price response rates; custom enchant rarity and stacking.

### Open technical checks

- Plugin version compatibility and whether selected plugins provide all needed APIs.
- Persisted partial-hour power regeneration.
- Border-only overclaim enforcement and safe claim transition.
- Shop ownership and inventory consistency after overclaims.
- Safe valuation of all enchantments, metadata variants, and filled containers.
- Dimension boundaries and anti-escape protections.

---

**One-sentence pitch:** A compact, hard-bordered Factions survival world where power determines control of territory, markets determine wealth, and captured land—not TNT explosions—is the prize of war.
