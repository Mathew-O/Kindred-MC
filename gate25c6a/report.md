# Failure corpus on 42e2a2a: 262 of 422 scenes pass (62.1%) over 3 runs

Passing 262, failing 77, intermittent 83 (an intermittent scene passed some runs and failed others; it is not counted as a pass).

| Class | Pass | Intermittent | Fail | Scenes |
| --- | --- | --- | --- | --- |
| death: zombie | 65 | 23 | 20 | 108 |
| death: skeleton | 51 | 17 | 4 | 72 |
| death: drowned | 26 | 0 | 9 | 35 |
| stuck: hemmed in | 19 | 6 | 7 | 32 |
| stuck: busy-work loop | 17 | 6 | 5 | 28 |
| death: spider | 15 | 6 | 0 | 21 |
| stuck: mining for a find | 9 | 6 | 3 | 18 |
| death: lava | 5 | 1 | 10 | 16 |
| stuck: no walkable route | 6 | 3 | 3 | 12 |
| stuck: hole or pit | 9 | 1 | 2 | 12 |
| stuck: water loop | 2 | 1 | 7 | 10 |
| death: creeper | 7 | 1 | 2 | 10 |
| death: fell | 8 | 2 | 0 | 10 |
| death: suffocation | 3 | 1 | 2 | 6 |
| death: husk | 4 | 2 | 0 | 6 |
| death: other | 3 | 1 | 0 | 4 |
| death: cave spider | 1 | 2 | 0 | 3 |
| death: phantom | 2 | 0 | 1 | 3 |
| death: slime | 1 | 0 | 1 | 2 |
| stuck: waiting for daylight or a teammate | 1 | 1 | 0 | 2 |
| death: polar bear | 2 | 0 | 0 | 2 |
| death: enderman | 2 | 0 | 0 | 2 |
| death: witch | 0 | 2 | 0 | 2 |
| stuck: refused target repeated | 0 | 1 | 0 | 1 |
| death: warden | 0 | 0 | 1 | 1 |
| death: zombie villager | 1 | 0 | 0 | 1 |
| stuck: ledge, aquifer side | 1 | 0 | 0 | 1 |
| death: badly hurt, the hungry climb dug on with a zombie in reach | 1 | 0 | 0 | 1 |
| death: stray | 1 | 0 | 0 | 1 |

| Kind | Pass | Intermittent | Fail | Scenes |
| --- | --- | --- | --- | --- |
| death | 197 | 58 | 50 | 305 |
| fixture | 2 | 0 | 0 | 2 |
| stuck | 63 | 25 | 27 | 115 |

| Mode | Pass | Intermittent | Fail | Scenes | Pass rate |
| --- | --- | --- | --- | --- | --- |
| factions | 48 | 16 | 9 | 73 | 65.8% |
| survival | 214 | 67 | 68 | 349 | 61.3% |

Replay players still acting after their own batch, by run: 0, 0, 0 (should be 0)

Staged scenes (contract fixtures run as scenes):
- f-25c4cp-ledge-505: pass (NativeCavernLedgeGameTests.onACavernsLedgeWithAnAquiferBehindItItGetsOffTheLedge)
- f-25c4dd-cornered-starving: pass (NativePortalCampaignGameTests.badlyHurtAndStarvingWithAZombieOnItItStopsItsClimbAndFights)

Baseline: 06f6cb4 over 6 runs.

Flagged: passing on the baseline, now failing in 2 or more of 3 run(s): 5 — diag-factions303-079bdc2-303-echo-36120, diag-factions303-079bdc2-303-echo-6460, e40a14-80b7150-101-sol-80066, e40a20-79a9aad-20261030-kindred-131420, e40a20-79a9aad-31102026-echo-102580
Confirmed (blocks): 0
Cleared: 5 — diag-factions303-079bdc2-303-echo-36120 (branch failed 1 of 3, main passed 1 of 3); diag-factions303-079bdc2-303-echo-6460 (branch failed 0 of 3, main passed 3 of 3); e40a14-80b7150-101-sol-80066 (branch failed 1 of 3, main passed 2 of 3); e40a20-79a9aad-20261030-kindred-131420 (branch failed 0 of 3, main passed 2 of 3); e40a20-79a9aad-31102026-echo-102580 (branch failed 0 of 3, main passed 3 of 3)
Gate: passes
Newly passing against the baseline: 38 — diag-factions303-079bdc2-303-echo-27140, diag-factions303-079bdc2-303-echo-75760, diag-factions303-079bdc2-303-kindred-107560, diag-holepit-079bdc2-31102026-echo-11660, diag-holepit-079bdc2-404-echo-49188, diag-holepit-079bdc2-8675309-kindred-111140, e40a13-fe2ba3b-101-kindred-21933, e40a13-fe2ba3b-20261030-sol-106709, e40a13-fe2ba3b-404-echo-73717, e40a14-80b7150-101-kindred-21957, e40a14-80b7150-8675309-kindred-128400, e40a15-dbf4e5c-404-kindred-36022, e40a15-dbf4e5c-8675309-kindred-52281, e40a16-922f4fd-101-echo-18586, e40a16-922f4fd-505-kindred-77615, e40a16-922f4fd-505-sol-8079, e40a17-7bc5b42-303-echo-67647, e40a17-7bc5b42-707-sol-48263, e40a17-7bc5b42-8675309-kindred-16523, e40a20-79a9aad-202-echo-110820, e40a20-79a9aad-505-echo-52752, e40a20-79a9aad-8675309-kindred-123600, e40a20-79a9aad-8675309-kindred-141720, e40a20-79a9aad-8675309-kindred-83380, e40c1-eed2dc5-101-echo-23946, e40c1-eed2dc5-101-sol-29848, e40c1-eed2dc5-8675309-echo-173225, f29e-on-1166122638-sol-63903, f29e-on-1530142077-echo-83228, f29e-on-4h-707-kindred-222518, f29e-on-4h-8675309-kindred-31280, f29e-on-891567637-kindred-123297, factions-110-d5057a4-110-echo-125906, factions-110-d5057a4-110-kindred-118253, factions-e40a16seeds-b7b4bec-101-kindred-71684, factions-e40a16seeds-b7b4bec-20261030-sol-120608, factions-e40a16seeds-b7b4bec-303-sol-76362, factions-e40a16seeds-b7b4bec-606-kindred-127222
