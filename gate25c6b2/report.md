# Failure corpus on 571dc62 (main fec0ec4, the merge-base): 266 of 422 scenes pass (63.0%) over 3 runs

Passing 266, failing 75, intermittent 81 (an intermittent scene passed some runs and failed others; it is not counted as a pass).

| Class | Pass | Intermittent | Fail | Scenes |
| --- | --- | --- | --- | --- |
| death: zombie | 66 | 28 | 14 | 108 |
| death: skeleton | 51 | 15 | 6 | 72 |
| death: drowned | 24 | 2 | 9 | 35 |
| stuck: hemmed in | 19 | 6 | 7 | 32 |
| stuck: busy-work loop | 17 | 4 | 7 | 28 |
| death: spider | 12 | 6 | 3 | 21 |
| stuck: mining for a find | 9 | 6 | 3 | 18 |
| death: lava | 6 | 1 | 9 | 16 |
| stuck: no walkable route | 7 | 2 | 3 | 12 |
| stuck: hole or pit | 10 | 1 | 1 | 12 |
| stuck: water loop | 2 | 2 | 6 | 10 |
| death: creeper | 7 | 2 | 1 | 10 |
| death: fell | 9 | 1 | 0 | 10 |
| death: suffocation | 4 | 0 | 2 | 6 |
| death: husk | 4 | 1 | 1 | 6 |
| death: other | 3 | 1 | 0 | 4 |
| death: cave spider | 2 | 1 | 0 | 3 |
| death: phantom | 2 | 1 | 0 | 3 |
| death: slime | 1 | 0 | 1 | 2 |
| stuck: waiting for daylight or a teammate | 1 | 0 | 1 | 2 |
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
| death | 198 | 60 | 47 | 305 |
| fixture | 2 | 0 | 0 | 2 |
| stuck | 66 | 21 | 28 | 115 |

| Mode | Pass | Intermittent | Fail | Scenes | Pass rate |
| --- | --- | --- | --- | --- | --- |
| factions | 47 | 17 | 9 | 73 | 64.4% |
| survival | 219 | 64 | 66 | 349 | 62.8% |

Replay players still acting after their own batch, by run: 0, 0, 0 (should be 0)

Staged scenes (contract fixtures run as scenes):
- f-25c4cp-ledge-505: pass (NativeCavernLedgeGameTests.onACavernsLedgeWithAnAquiferBehindItItGetsOffTheLedge)
- f-25c4dd-cornered-starving: pass (NativePortalCampaignGameTests.badlyHurtAndStarvingWithAZombieOnItItStopsItsClimbAndFights)

Baseline: 16cb7a2 over 6 runs.

Flagged: passing on the baseline, now failing in 2 or more of 3 run(s): 2 — diag-factions303-079bdc2-303-echo-75760, e40a20-79a9aad-8675309-kindred-97900
Confirmed (blocks): 0
Cleared: 2 — diag-factions303-079bdc2-303-echo-75760 (branch failed 1 of 3, main passed 3 of 3); e40a20-79a9aad-8675309-kindred-97900 (branch failed 0 of 3, main passed 3 of 3)
Gate: passes
Newly passing against the baseline: 50 — diag-factions303-079bdc2-303-echo-27140, diag-factions303-079bdc2-303-sol-27680, diag-factions303-079bdc2-303-sol-32660, diag-factions303-079bdc2-303-sol-58760, diag-holepit-079bdc2-31102026-echo-11660, diag-holepit-079bdc2-404-echo-72860, diag-holepit-079bdc2-8675309-echo-45700, e40a13-fe2ba3b-404-echo-73717, e40a13-fe2ba3b-505-sol-10846, e40a13-fe2ba3b-707-echo-128942, e40a13-fe2ba3b-707-sol-48414, e40a14-80b7150-101-kindred-21957, e40a14-80b7150-101-sol-80066, e40a14-80b7150-505-echo-8422, e40a14-80b7150-8675309-echo-127264, e40a14-80b7150-8675309-kindred-128400, e40a15-dbf4e5c-8675309-kindred-37265, e40a15-dbf4e5c-8675309-kindred-52281, e40a16-922f4fd-202-echo-55002, e40a16-922f4fd-505-echo-6085, e40a16-922f4fd-505-kindred-77615, e40a16-922f4fd-505-sol-8079, e40a17-7bc5b42-101-sol-53904, e40a17-7bc5b42-707-echo-36256, e40a17-7bc5b42-8675309-kindred-16523, e40a17-7bc5b42-8675309-sol-7023, e40a20-79a9aad-101-sol-107500, e40a20-79a9aad-202-echo-110820, e40a20-79a9aad-202-echo-14987, e40a20-79a9aad-202-sol-29720, e40a20-79a9aad-8675309-kindred-112700, e40a20-79a9aad-8675309-kindred-127220, e40a20-79a9aad-8675309-kindred-134460, e40a20-79a9aad-8675309-kindred-83380, e40a20-79a9aad-8675309-kindred-87020, e40c1-eed2dc5-101-echo-23946, e40c1-eed2dc5-101-sol-13531, e40c1-eed2dc5-505-echo-104879, e40c1-eed2dc5-505-echo-42820, e40c1-eed2dc5-8675309-sol-26517, f29e-on-101-sol-88809, f29e-on-1166122638-sol-63903, f29e-on-4h-202-sol-244548, f29e-on-4h-8675309-sol-130048, f29e-on-779795571-echo-78472, factions-110-d5057a4-110-echo-125906, factions-110-d5057a4-110-echo-215951, factions-e40a16seeds-b7b4bec-101-kindred-71684, factions-e40a16seeds-b7b4bec-20261030-sol-120608, factions-e40a16seeds-b7b4bec-606-kindred-127222
