# Active work (Cursor parallel track — 2026-10-18)

**E-15 (Nether)** — This 2026-10-18 snapshot is superseded by the current tracker and [E-15 evidence](evidence/E-15.md). Portal entry/return and restart conservation are now fixture-tested; parent acceptance remains open.

| ID | Status | Notes |
| --- | --- | --- |
| E-15 (Nether) | In progress | Portal entry/return and restart persistence tested; owned frame assembly and earned Nether resource trips remain open |
| CHEST-01 / QOL-05 / QOL-03 / GUI-01 / SPEED-01 (adapter) | Done (2026-10-18) | See rows below |
| Core slices (IDLE/EXEC/ROSTER/SPEED/WEAP) | Spec + core units | Tracker IDs remain open until wired to production milestones |

| OPT-01 … OPT-04, IDLE-01, GUI-01, SPEED-01, CHEST-01, QOL-05, QOL-03 | Done (2026-10-18) | Adapter QoL for in-game trial |

**Deferred (post playtest):** surplus backpack deposit routing, sleep-sync + broken-tool replenish, `native_organize_chest` / `native_toss` GameTests, DiscoveryAtlas seed provenance logging.

**In-game quick start**

1. Build/install mod JAR: `.\gradlew.bat build` → `companion-server-adapter/build/libs/`.
2. Enable autonomous edits for natives (server config / `edits.properties` or admin flow used in GameTests).
3. `/aiplayer gui` — roster, playstyle, collaboration, idle radius, seed locator. Canonical names (`Kindred`, `Sol`, `Vesp`) pick up bundled NameMC skins on native spawn/connect (legacy mob bodies use the same PNGs when the Kindred client mod is installed).
4. `/aiplayer settings seedlocator on|off [uuid]` — fair-play structure hints when not idle.
5. Create/resume natives: lifecycle `resume` after reconcile; new bodies start idle until resumed.
6. **Nether (E-15):** flint & steel in hotbar, valid portal frame in work patch; transit requires authorized portal travel on the body.
7. **QoL:** COOP team food/torch toss; chest organize via autonomy when chests are in scope; hotbar organization in ROUTINE/RECOVERY.
