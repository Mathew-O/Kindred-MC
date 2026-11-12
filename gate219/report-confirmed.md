# Failure corpus on c277e84 (main 69c5d12, local main f009244 + #241 1ca5c7b, tree = main 697f391): 267 of 422 scenes pass (63.3%) over 3 runs

Passing 267, failing 72, intermittent 83 (an intermittent scene passed some runs and failed others; it is not counted as a pass).

| Class | Pass | Intermittent | Fail | Scenes |
| --- | --- | --- | --- | --- |
| death: zombie | 65 | 33 | 10 | 108 |
| death: skeleton | 51 | 14 | 7 | 72 |
| death: drowned | 26 | 0 | 9 | 35 |
| stuck: hemmed in | 17 | 9 | 6 | 32 |
| stuck: busy-work loop | 19 | 3 | 6 | 28 |
| death: spider | 16 | 3 | 2 | 21 |
| stuck: mining for a find | 10 | 6 | 2 | 18 |
| death: lava | 2 | 1 | 13 | 16 |
| stuck: no walkable route | 8 | 1 | 3 | 12 |
| stuck: hole or pit | 9 | 1 | 2 | 12 |
| stuck: water loop | 2 | 2 | 6 | 10 |
| death: creeper | 7 | 3 | 0 | 10 |
| death: fell | 9 | 1 | 0 | 10 |
| death: suffocation | 3 | 1 | 2 | 6 |
| death: husk | 4 | 2 | 0 | 6 |
| death: other | 4 | 0 | 0 | 4 |
| death: cave spider | 2 | 0 | 1 | 3 |
| death: phantom | 2 | 1 | 0 | 3 |
| death: slime | 1 | 0 | 1 | 2 |
| stuck: waiting for daylight or a teammate | 1 | 1 | 0 | 2 |
| death: polar bear | 2 | 0 | 0 | 2 |
| death: enderman | 2 | 0 | 0 | 2 |
| death: witch | 1 | 0 | 1 | 2 |
| stuck: refused target repeated | 0 | 1 | 0 | 1 |
| death: warden | 0 | 0 | 1 | 1 |
| death: zombie villager | 1 | 0 | 0 | 1 |
| stuck: ledge, aquifer side | 1 | 0 | 0 | 1 |
| death: badly hurt, the hungry climb dug on with a zombie in reach | 1 | 0 | 0 | 1 |
| death: stray | 1 | 0 | 0 | 1 |

| Kind | Pass | Intermittent | Fail | Scenes |
| --- | --- | --- | --- | --- |
| death | 199 | 59 | 47 | 305 |
| fixture | 2 | 0 | 0 | 2 |
| stuck | 66 | 24 | 25 | 115 |

| Mode | Pass | Intermittent | Fail | Scenes | Pass rate |
| --- | --- | --- | --- | --- | --- |
| factions | 51 | 14 | 8 | 73 | 69.9% |
| survival | 216 | 69 | 64 | 349 | 61.9% |

Replay players still acting after their own batch, by run: 1, 1, 1 (should be 0)

Staged scenes (contract fixtures run as scenes):
- f-25c4cp-ledge-505: pass (NativeCavernLedgeGameTests.onACavernsLedgeWithAnAquiferBehindItItGetsOffTheLedge)
- f-25c4dd-cornered-starving: pass (NativePortalCampaignGameTests.badlyHurtAndStarvingWithAZombieOnItItStopsItsClimbAndFights)

Baseline: 811b0f7 over 6 runs.

Flagged: passing on the baseline, now failing in 2 or more of 3 run(s): 4 — diag-factions303-079bdc2-303-kindred-84180, e40a20-79a9aad-101-sol-107500, e40a20-79a9aad-20261030-kindred-131420, e40a20-79a9aad-404-kindred-99080
Confirmed (blocks): 0
Cleared: 4 — diag-factions303-079bdc2-303-kindred-84180 (branch failed 0 of 3, main passed 3 of 3); e40a20-79a9aad-101-sol-107500 (branch failed 3 of 3, main passed 0 of 3); e40a20-79a9aad-20261030-kindred-131420 (branch failed 0 of 3, main passed 3 of 3); e40a20-79a9aad-404-kindred-99080 (branch failed 0 of 3, main passed 3 of 3)
Gate: passes
Newly passing against the baseline: 74 — diag-factions303-079bdc2-303-echo-10360, diag-factions303-079bdc2-303-echo-27140, diag-factions303-079bdc2-303-echo-36120, diag-factions303-079bdc2-303-echo-6460, diag-factions303-079bdc2-303-echo-75760, diag-factions303-079bdc2-303-sol-27680, diag-factions303-079bdc2-303-sol-99140, diag-holepit-079bdc2-202-sol-91124, diag-holepit-079bdc2-31102026-echo-11660, diag-holepit-079bdc2-8675309-kindred-111140, diag-holepit-079bdc2-8675309-sol-16249, e40a13-fe2ba3b-101-kindred-21933, e40a13-fe2ba3b-505-sol-110664, e40a13-fe2ba3b-505-sol-43879, e40a13-fe2ba3b-606-echo-81682, e40a13-fe2ba3b-707-echo-128942, e40a13-fe2ba3b-707-echo-80901, e40a14-80b7150-101-echo-75842, e40a14-80b7150-101-kindred-21957, e40a14-80b7150-101-sol-84338, e40a14-80b7150-8675309-echo-127264, e40a14-80b7150-8675309-kindred-120887, e40a15-dbf4e5c-303-kindred-17203, e40a15-dbf4e5c-404-kindred-36022, e40a15-dbf4e5c-707-kindred-140905, e40a15-dbf4e5c-707-sol-10569, e40a16-922f4fd-101-echo-57638, e40a16-922f4fd-101-sol-104427, e40a16-922f4fd-202-echo-90372, e40a16-922f4fd-202-kindred-136005, e40a16-922f4fd-505-echo-6085, e40a17-7bc5b42-101-sol-53904, e40a17-7bc5b42-505-sol-66039, e40a17-7bc5b42-8675309-kindred-16523, e40a20-79a9aad-202-echo-14987, e40a20-79a9aad-202-sol-100125, e40a20-79a9aad-31102026-echo-102580, e40a20-79a9aad-31102026-sol-97112, e40a20-79a9aad-707-kindred-42100, e40a20-79a9aad-8675309-kindred-116320, e40c1-eed2dc5-101-sol-41494, e40c1-eed2dc5-20261030-kindred-113934, e40c1-eed2dc5-8675309-sol-114191, f29e-on-1166122638-sol-63903, f29e-on-202-echo-124223, f29e-on-202-echo-35084, f29e-on-303-kindred-91294, f29e-on-4h-202-kindred-112942, f29e-on-4h-202-sol-244548, f29e-on-4h-20261030-echo-272968, f29e-on-4h-31102026-kindred-216171, f29e-on-4h-31102026-sol-216356, f29e-on-4h-707-sol-165088, f29e-on-4h-8675309-echo-266819, f29e-on-4h-8675309-kindred-31280, f29e-on-4h-8675309-sol-130048, f29e-on-4h-8675309-sol-185291, f29e-on-779795571-kindred-80926, f29e-on-891567637-kindred-36249, factions-110-d5057a4-110-echo-240272, factions-110-d5057a4-110-kindred-118253, factions-110-d5057a4-110-kindred-190720, factions-110-d5057a4-110-kindred-231984, factions-110-d5057a4-110-kindred-254029, factions-110-d5057a4-110-sol-187246, factions-110-d5057a4-110-sol-21363, factions-e40a16seeds-b7b4bec-20261030-sol-120608, factions-e40a16seeds-b7b4bec-303-sol-76362, factions-e40a16seeds-b7b4bec-303-sol-90379, factions-e40a16seeds-b7b4bec-404-kindred-143470, factions-e40a16seeds-b7b4bec-707-kindred-88286, factions-e40a16seeds-b7b4bec-707-kindred-90496, factions-e40a16seeds-b7b4bec-707-sol-72346, factions-e40a16seeds-b7b4bec-8675309-kindred-72781
