# The Factions pack (M-06j, draft)

Status: draft, 2026-11-08. Every version below was read from Modrinth's API for Minecraft 1.21.1 and Fabric on that
date. Proven on a fresh server the same day: hashes, loading, start, stop, backup, and a pre-generation sample (see
`docs/evidence/M-06j.md`). The admin checklist's console steps were walked on a fresh server on 2026-11-11
(`docs/evidence/M-06j1.md`); the items a real client or a long run is needed for are listed there.

Kindred's Factions rules live in the Kindred jar itself; this pack is the server's `mods` folder and configuration
around it, kept as a manifest. Nothing here is bundled: each mod is downloaded from its own release by the index's
link and checked against its hashes. None of these mods owns a rule (claims, power, coins and the market are
Kindred's alone).

## Mods

`modrinth.index.json` lists them in Modrinth's `.mrpack` index format (hashes, sizes, downloads; server only).

| Mod | Version | Licence | Purpose | Download | Source |
| --- | --- | --- | --- | --- | --- |
| Fabric API | `0.116.17+1.21.1` (2026-09-01) | Apache-2.0 | Required by Kindred and the other mods | [fabric-api-0.116.17+1.21.1.jar](https://cdn.modrinth.com/data/P7dR8mSH/versions/Mys3P7lK/fabric-api-0.116.17%2B1.21.1.jar) | https://github.com/FabricMC/fabric-api |
| Chunky | `1.4.23` (2025-04-04) | GPL-3.0-only | Pre-generates the 8,000-block world | [Chunky-Fabric-1.4.23.jar](https://cdn.modrinth.com/data/fALzjamp/versions/RVFHfo1D/Chunky-Fabric-1.4.23.jar) | https://github.com/pop4959/Chunky |
| spark | `1.10.109-fabric` (2024-09-26) | GPL-3.0-only | Profiles tick time and memory for the month run | [spark-1.10.109-fabric.jar](https://cdn.modrinth.com/data/l6YH9Als/versions/cALUj9l1/spark-1.10.109-fabric.jar) | https://github.com/lucko/spark |
| Lithium | `mc1.21.1-0.15.4-fabric` (2026-06-27) | LGPL-3.0-only | Server performance (game logic) | [lithium-fabric-0.15.4+mc1.21.1.jar](https://cdn.modrinth.com/data/gvQqBUqZ/versions/N08Z8wog/lithium-fabric-0.15.4%2Bmc1.21.1.jar) | https://github.com/caffeinemc/lithium-fabric |
| FerriteCore | `7.0.3-fabric` (2026-01-25) | MIT | Memory use | [ferritecore-7.0.3-fabric.jar](https://cdn.modrinth.com/data/uXXizFIs/versions/sOzRw3CG/ferritecore-7.0.3-fabric.jar) | https://github.com/malte0811/FerriteCore |
| Textile Backup | `3.1.3-1.21` (2024-06-26) | GPL-3.0-only | Scheduled world backups | [textile_backup-3.1.3-1.21.jar](https://cdn.modrinth.com/data/wwcspvkr/versions/C73KkDD6/textile_backup-3.1.3-1.21.jar) | https://github.com/Szum123321/textile_backup |
| Cloth Config API | `15.0.140+fabric` (2024-09-16) | LGPL-3.0-only | Textile Backup's configuration library | [cloth-config-15.0.140-fabric.jar](https://cdn.modrinth.com/data/9s6osm5g/versions/HpMb5wGb/cloth-config-15.0.140-fabric.jar) | https://github.com/shedaniel/ClothConfig/ |

Kindred itself: the `companion-server-adapter` jar built from this repository at the release commit
(`bash ./gradlew build`; `companion-server-adapter/build/libs/`), on Fabric Loader 0.19.5 and Fabric API
0.116.17+1.21.1 as `release-manifest.json` pins them. Java 21.

## Files

- `server.properties`: vanilla settings for the run (PvP on so Kindred's faction rules decide, vanilla spawn
  protection off as Kindred's hub is the safe zone, whitelist on).
- `config/kindred/factions.properties`: the mode's settings, the rulebook's defaults with the 8,000-block border.
- `config/textile_backup.json5`: backups every 3 hours and at each stop, the last 16 kept.

## Install (a fresh server)

1. Java 21; the Fabric server launcher for Minecraft 1.21.1 with Fabric Loader 0.19.5.
2. Each mod in the index into `mods/`, its SHA-512 checked; the Kindred jar beside them.
3. `server.properties`, `config/kindred/factions.properties` and `config/textile_backup.json5` from here. Memory on a 12 GB host: `-Xms8G -Xmx10G`.
4. Start; choose the mode at the console: `aiplayer mode world factions` (kept in `<world>/kindred/mode.properties`
   across restarts).
5. As an operator: `/faction admin hub` (the safe zone round spawn, the border set in every dimension, the
   hub built).
6. Pre-generate with Chunky (below), then back up (below), then open the whitelist.

## Pre-generation

The border is 8,000 across, so the overworld is 4,000 out from spawn each way and the Nether 500:

```
/chunky world minecraft:overworld
/chunky center 0 0
/chunky shape square
/chunky radius 4000
/chunky start
/chunky world minecraft:the_nether
/chunky radius 500
/chunky start
```

That is about 250,000 overworld chunks (500 × 500) and 4,000 in the Nether. `spark profiler` while it runs, and
`spark tps` after, should show the server back at 20 ticks a second. On the owner's server use
`spark heapsummary --save-to-file`, never the default, which uploads the summary to spark's public paste site.

## Backups

Textile Backup: `config/textile_backup.json5` from here (its own file as it writes it on a first start, with
`backupInterval: 10800` for every 3 hours, `backupsToKeep: 16`, `shutdownBackup: true`, and `doBackupsOnEmptyServer: true`
since AI players play with no person online). Kindred's own records are inside the world folder (`<world>/kindred/companions.sqlite`, the World
Record), so a world backup carries them; take backups while the server runs (Textile Backup flushes saves first), never
by copying the folder under a running server.

## What the AI players do

In a faction world an AI player plays the mode on its own: it founds or joins a faction, claims, sells, scouts and
defends by the same rules and commands as people (M-06i). Without a goal set for it, it also plays the survival
progression, earned from an empty inventory: toward the Ender Dragon where the server allows the fight
(`/aiplayer dragonfight on`), the End portal otherwise. A goal an operator sets wins, `none` included; the default is
not saved and applies only while the world is in Factions mode. What keeps it alive (its bed at night, food when it is
hungry) comes first, then the faction world, then its goal. AI players need world edits on (`autonomousEdits`) to
gather and build; set `bases=true` in `edits.properties` for them to build bases of their own.

## The admin checklist (the rulebook's section 10, for Kindred)

Prelaunch:

- [ ] Fabric 1.21.1 with Fabric Loader 0.19.5 and Fabric API 0.116.17+1.21.1; each mod above at its listed version and
      hash; the Kindred jar from the release commit.
- [ ] A backup taken before the first start with Kindred.
- [ ] `/faction admin hub`: the border 8,000 across round (0, 0) with a 20-block warning, the Nether 1,000, the End
      8,000; the hub's chunks a safe zone; the hub built.
- [ ] Portals: a Nether portal leading beyond the border refuses and says so (Kindred, M-06f).
- [ ] Pre-generation done in the overworld and the Nether.
- [ ] `config/kindred/factions.properties`: 10 members, 80 chunks, power 10, −2 a PvP death, +1 an online hour;
      restart once and check `/faction power` keeps a player's progress.
- [ ] Capture: border-first only, the 48-hour new-faction shield and the 30-minute shield after a loss (Kindred's
      GameTests; a manual capture between two test factions on the live server before opening).
- [ ] One economy: Kindred's coins only, no economy mod. `/shop price` on a few items; `/sellall` on a test account.
- [ ] `/ah`: the 25-coin fee, 3% on a sale, five listings, 48 hours.
- [ ] No chest shops, custom enchantments or resource world (the owner's decisions).
- [ ] Backups scheduled; spark installed; the World Record's agreement check (once a game day) logging no error.

Not used from the rulebook's list, and why: Paper, FactionsUUID, EssentialsX, Vault, DynamicShop, EzAuction,
QuickShop, AxTrade, ExcellentEnchants, LuckPerms, WorldGuard, CoreProtect (Kindred implements each rule itself on
Fabric, the owner's decision; the World Record is the audit trail).

## Remaining for M-06j

A fresh Fabric 1.21.1 server built from this manifest, started, pre-generated to the 8,000-block border and through
this checklist, with the run's notes, timings and memory filed as evidence; the operator guide's section on it.
