# Failure corpus on 39a06fa (main d257164, the merge-base): 292 of 444 scenes pass (65.8%) over 3 runs

Passing 292, failing 68, intermittent 84 (an intermittent scene passed some runs and failed others; it is not counted as a pass).

| Class | Pass | Intermittent | Fail | Scenes |
| --- | --- | --- | --- | --- |
| death: zombie | 75 | 27 | 14 | 116 |
| death: skeleton | 52 | 18 | 4 | 74 |
| death: drowned | 27 | 1 | 9 | 37 |
| stuck: hemmed in | 18 | 7 | 7 | 32 |
| stuck: busy-work loop | 18 | 3 | 7 | 28 |
| death: spider | 14 | 6 | 1 | 21 |
| stuck: mining for a find | 9 | 7 | 2 | 18 |
| death: lava | 7 | 2 | 9 | 18 |
| stuck: no walkable route | 8 | 2 | 3 | 13 |
| stuck: hole or pit | 11 | 1 | 1 | 13 |
| stuck: water loop | 3 | 1 | 6 | 10 |
| death: creeper | 7 | 2 | 1 | 10 |
| death: fell | 8 | 1 | 1 | 10 |
| death: suffocation | 10 | 0 | 0 | 10 |
| death: husk | 5 | 0 | 1 | 6 |
| death: other | 3 | 1 | 0 | 4 |
| death: cave spider | 2 | 1 | 0 | 3 |
| death: phantom | 1 | 2 | 0 | 3 |
| death: enderman | 3 | 0 | 0 | 3 |
| death: slime | 1 | 0 | 1 | 2 |
| stuck: waiting for daylight or a teammate | 2 | 0 | 0 | 2 |
| death: polar bear | 2 | 0 | 0 | 2 |
| death: witch | 0 | 2 | 0 | 2 |
| stuck: refused target repeated | 1 | 0 | 0 | 1 |
| death: warden | 0 | 0 | 1 | 1 |
| death: zombie villager | 1 | 0 | 0 | 1 |
| death: fire | 1 | 0 | 0 | 1 |
| stuck: ledge, aquifer side | 1 | 0 | 0 | 1 |
| death: badly hurt, the hungry climb dug on with a zombie in reach | 1 | 0 | 0 | 1 |
| death: stray | 1 | 0 | 0 | 1 |

| Kind | Pass | Intermittent | Fail | Scenes |
| --- | --- | --- | --- | --- |
| death | 220 | 63 | 42 | 325 |
| fixture | 2 | 0 | 0 | 2 |
| stuck | 70 | 21 | 26 | 117 |

| Mode | Pass | Intermittent | Fail | Scenes | Pass rate |
| --- | --- | --- | --- | --- | --- |
| factions | 55 | 12 | 6 | 73 | 75.3% |
| survival | 237 | 72 | 62 | 371 | 63.9% |

Replay players still acting after their own batch, by run: 0, 0, 0 (should be 0)

Staged scenes (contract fixtures run as scenes):
- f-25c4cp-ledge-505: pass (NativeCavernLedgeGameTests.onACavernsLedgeWithAnAquiferBehindItItGetsOffTheLedge)
- f-25c4dd-cornered-starving: pass (NativePortalCampaignGameTests.badlyHurtAndStarvingWithAZombieOnItItStopsItsClimbAndFights)

Baseline: 16cb7a2 over 6 runs.

Flagged: passing on the baseline, now failing in 2 or more of 3 run(s): 6 — e40a13-fe2ba3b-20261030-sol-106709, e40a15-dbf4e5c-404-kindred-36022, e40a22-d2389c9-202-kindred-48843, f29e-on-4h-20261030-echo-272968, f29e-on-4h-707-kindred-222518, factions-110-d5057a4-110-kindred-190720
Confirmed (blocks): 1 — e40a15-dbf4e5c-404-kindred-36022 (branch failed 2 of 3, main passed 2 of 3)
Cleared: 5 — e40a13-fe2ba3b-20261030-sol-106709 (branch failed 1 of 3, main passed 3 of 3); e40a22-d2389c9-202-kindred-48843 (branch failed 2 of 3, main passed 0 of 3); f29e-on-4h-20261030-echo-272968 (branch failed 0 of 3, main passed 2 of 3); f29e-on-4h-707-kindred-222518 (branch failed 2 of 3, main passed 1 of 3); factions-110-d5057a4-110-kindred-190720 (branch failed 2 of 3, main passed 1 of 3)
Gate: fails
Newly passing against the baseline: 71 — diag-factions303-079bdc2-303-echo-27140, diag-factions303-079bdc2-303-sol-58760, diag-holepit-079bdc2-202-sol-91124, diag-holepit-079bdc2-31102026-echo-11660, diag-holepit-079bdc2-404-echo-49188, diag-holepit-079bdc2-404-echo-72860, diag-holepit-079bdc2-8675309-echo-45700, e40a13-fe2ba3b-404-echo-73717, e40a13-fe2ba3b-505-sol-10846, e40a13-fe2ba3b-505-sol-43879, e40a13-fe2ba3b-707-echo-128942, e40a14-80b7150-101-sol-80066, e40a14-80b7150-31102026-echo-120452, e40a14-80b7150-505-echo-8422, e40a14-80b7150-8675309-echo-127264, e40a14-80b7150-8675309-kindred-128400, e40a15-dbf4e5c-8675309-kindred-37265, e40a15-dbf4e5c-8675309-kindred-52281, e40a16-922f4fd-101-sol-104427, e40a16-922f4fd-202-echo-55002, e40a16-922f4fd-505-echo-6085, e40a16-922f4fd-707-kindred-14534, e40a17-7bc5b42-101-sol-53904, e40a17-7bc5b42-303-kindred-121775, e40a17-7bc5b42-505-sol-120198, e40a17-7bc5b42-8675309-kindred-16523, e40a17-7bc5b42-8675309-sol-38092, e40a17-7bc5b42-8675309-sol-7023, e40a20-79a9aad-101-sol-107500, e40a20-79a9aad-202-echo-110820, e40a20-79a9aad-202-echo-14987, e40a20-79a9aad-202-sol-29720, e40a20-79a9aad-202-sol-33840, e40a20-79a9aad-20261030-kindred-131420, e40a20-79a9aad-606-echo-46420, e40a20-79a9aad-8675309-kindred-119960, e40a20-79a9aad-8675309-kindred-123600, e40a20-79a9aad-8675309-kindred-83380, e40a20-79a9aad-8675309-kindred-87020, e40a22-d2389c9-101-echo-17476, e40a22-d2389c9-101-kindred-47820, e40a22-d2389c9-101-kindred-51620, e40a22-d2389c9-31102026-echo-30737, e40a22-d2389c9-404-sol-50932, e40a22-d2389c9-707-echo-2597, e40a22-d2389c9-707-kindred-136143, e40a22-d2389c9-707-kindred-51330, e40c1-eed2dc5-101-sol-41494, e40c1-eed2dc5-505-echo-104879, e40c1-eed2dc5-8675309-sol-26517, f29e-on-1166122638-sol-63903, f29e-on-4h-202-sol-244548, f29e-on-4h-8675309-kindred-31280, f29e-on-4h-8675309-sol-130048, f29e-on-505-echo-137153, f29e-on-707-echo-28809, f29e-on-779795571-echo-78472, f29e-on-891567637-kindred-8108, factions-110-d5057a4-110-echo-215951, factions-110-d5057a4-110-kindred-114087, factions-110-d5057a4-110-kindred-231984, factions-110-d5057a4-110-kindred-254029, factions-e40a16seeds-b7b4bec-101-kindred-71684, factions-e40a16seeds-b7b4bec-20261030-sol-120608, factions-e40a16seeds-b7b4bec-31102026-kindred-17463, factions-e40a16seeds-b7b4bec-31102026-kindred-20226, factions-e40a16seeds-b7b4bec-404-kindred-143470, factions-e40a16seeds-b7b4bec-505-echo-66907, factions-e40a16seeds-b7b4bec-606-kindred-127222, factions-e40a16seeds-b7b4bec-707-kindred-88286, factions-e40a16seeds-b7b4bec-707-kindred-90496
