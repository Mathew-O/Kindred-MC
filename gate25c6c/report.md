# Failure corpus on 52fbcc6: 265 of 422 scenes pass (62.8%) over 3 runs

Passing 265, failing 77, intermittent 80 (an intermittent scene passed some runs and failed others; it is not counted as a pass).

| Class | Pass | Intermittent | Fail | Scenes |
| --- | --- | --- | --- | --- |
| death: zombie | 63 | 31 | 14 | 108 |
| death: skeleton | 49 | 17 | 6 | 72 |
| death: drowned | 26 | 0 | 9 | 35 |
| stuck: hemmed in | 19 | 5 | 8 | 32 |
| stuck: busy-work loop | 18 | 2 | 8 | 28 |
| death: spider | 13 | 7 | 1 | 21 |
| stuck: mining for a find | 10 | 5 | 3 | 18 |
| death: lava | 3 | 0 | 13 | 16 |
| stuck: no walkable route | 9 | 2 | 1 | 12 |
| stuck: hole or pit | 10 | 0 | 2 | 12 |
| stuck: water loop | 1 | 1 | 8 | 10 |
| death: creeper | 7 | 3 | 0 | 10 |
| death: fell | 9 | 1 | 0 | 10 |
| death: suffocation | 3 | 1 | 2 | 6 |
| death: husk | 5 | 1 | 0 | 6 |
| death: other | 3 | 1 | 0 | 4 |
| death: cave spider | 2 | 1 | 0 | 3 |
| death: phantom | 2 | 1 | 0 | 3 |
| death: slime | 1 | 0 | 1 | 2 |
| stuck: waiting for daylight or a teammate | 2 | 0 | 0 | 2 |
| death: polar bear | 2 | 0 | 0 | 2 |
| death: enderman | 2 | 0 | 0 | 2 |
| death: witch | 1 | 1 | 0 | 2 |
| stuck: refused target repeated | 1 | 0 | 0 | 1 |
| death: warden | 0 | 0 | 1 | 1 |
| death: zombie villager | 1 | 0 | 0 | 1 |
| stuck: ledge, aquifer side | 1 | 0 | 0 | 1 |
| death: badly hurt, the hungry climb dug on with a zombie in reach | 1 | 0 | 0 | 1 |
| death: stray | 1 | 0 | 0 | 1 |

| Kind | Pass | Intermittent | Fail | Scenes |
| --- | --- | --- | --- | --- |
| death | 193 | 65 | 47 | 305 |
| fixture | 2 | 0 | 0 | 2 |
| stuck | 70 | 15 | 30 | 115 |

| Mode | Pass | Intermittent | Fail | Scenes | Pass rate |
| --- | --- | --- | --- | --- | --- |
| factions | 52 | 12 | 9 | 73 | 71.2% |
| survival | 213 | 68 | 68 | 349 | 61.0% |

Replay players still acting after their own batch, by run: 0, 0, 0 (should be 0)

Staged scenes (contract fixtures run as scenes):
- f-25c4cp-ledge-505: pass (NativeCavernLedgeGameTests.onACavernsLedgeWithAnAquiferBehindItItGetsOffTheLedge)
- f-25c4dd-cornered-starving: pass (NativePortalCampaignGameTests.badlyHurtAndStarvingWithAZombieOnItItStopsItsClimbAndFights)

Baseline: 06f6cb4 over 6 runs.

Flagged: passing on the baseline, now failing in 2 or more of 3 run(s): 5 — diag-factions303-079bdc2-303-echo-36120, diag-factions303-079bdc2-303-echo-6460, e40a20-79a9aad-20261030-kindred-131420, e40a20-79a9aad-31102026-echo-102580, f29e-on-4h-8675309-kindred-20101
Confirmed (blocks): 0
Cleared: 5 — diag-factions303-079bdc2-303-echo-36120 (branch failed 2 of 3, main passed 0 of 3); diag-factions303-079bdc2-303-echo-6460 (branch failed 0 of 3, main passed 3 of 3); e40a20-79a9aad-20261030-kindred-131420 (branch failed 1 of 3, main passed 1 of 3); e40a20-79a9aad-31102026-echo-102580 (branch failed 0 of 3, main passed 3 of 3); f29e-on-4h-8675309-kindred-20101 (branch failed 1 of 3, main passed 3 of 3)
Gate: passes
Newly passing against the baseline: 38 — diag-factions303-079bdc2-303-echo-27140, diag-factions303-079bdc2-303-kindred-107560, diag-factions303-079bdc2-303-kindred-84180, diag-factions303-079bdc2-303-sol-27680, diag-factions303-079bdc2-303-sol-32660, diag-holepit-079bdc2-31102026-echo-11660, e40a13-fe2ba3b-101-kindred-21933, e40a13-fe2ba3b-707-sol-48414, e40a13-fe2ba3b-707-sol-64680, e40a14-80b7150-8675309-kindred-128400, e40a15-dbf4e5c-8675309-kindred-37265, e40a16-922f4fd-101-echo-18586, e40a16-922f4fd-101-sol-104427, e40a16-922f4fd-202-echo-55002, e40a16-922f4fd-505-kindred-77615, e40a16-922f4fd-707-kindred-14534, e40a17-7bc5b42-303-echo-67647, e40a17-7bc5b42-8675309-sol-38092, e40a17-7bc5b42-8675309-sol-7023, e40a20-79a9aad-606-echo-46420, e40a20-79a9aad-8675309-kindred-105140, e40a20-79a9aad-8675309-kindred-119960, e40a20-79a9aad-8675309-kindred-123600, e40a20-79a9aad-8675309-kindred-94280, e40c1-eed2dc5-101-echo-23946, f29e-on-1166122638-sol-63903, f29e-on-4h-707-kindred-222518, f29e-on-4h-8675309-kindred-31280, f29e-on-4h-8675309-sol-130048, f29e-on-779795571-echo-78472, f29e-on-779795571-kindred-47186, factions-110-d5057a4-110-echo-125906, factions-110-d5057a4-110-kindred-118253, factions-110-d5057a4-110-kindred-263604, factions-e40a16seeds-b7b4bec-20261030-sol-120608, factions-e40a16seeds-b7b4bec-303-sol-76362, factions-e40a16seeds-b7b4bec-31102026-kindred-22521, factions-e40a16seeds-b7b4bec-606-kindred-127222
