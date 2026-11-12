# Failure corpus on 5158191a: 268 of 422 scenes pass (63.5%) over 3 runs

Passing 268, failing 74, intermittent 80 (an intermittent scene passed some runs and failed others; it is not counted as a pass).

| Class | Pass | Intermittent | Fail | Scenes |
| --- | --- | --- | --- | --- |
| death: zombie | 63 | 30 | 15 | 108 |
| death: skeleton | 51 | 15 | 6 | 72 |
| death: drowned | 26 | 0 | 9 | 35 |
| stuck: hemmed in | 21 | 5 | 6 | 32 |
| stuck: busy-work loop | 17 | 5 | 6 | 28 |
| death: spider | 13 | 8 | 0 | 21 |
| stuck: mining for a find | 12 | 5 | 1 | 18 |
| death: lava | 3 | 0 | 13 | 16 |
| stuck: no walkable route | 9 | 0 | 3 | 12 |
| stuck: hole or pit | 9 | 1 | 2 | 12 |
| stuck: water loop | 3 | 1 | 6 | 10 |
| death: creeper | 6 | 2 | 2 | 10 |
| death: fell | 9 | 1 | 0 | 10 |
| death: suffocation | 3 | 1 | 2 | 6 |
| death: husk | 5 | 1 | 0 | 6 |
| death: other | 3 | 0 | 1 | 4 |
| death: cave spider | 2 | 1 | 0 | 3 |
| death: phantom | 2 | 1 | 0 | 3 |
| death: slime | 1 | 0 | 1 | 2 |
| stuck: waiting for daylight or a teammate | 1 | 1 | 0 | 2 |
| death: polar bear | 2 | 0 | 0 | 2 |
| death: enderman | 2 | 0 | 0 | 2 |
| death: witch | 1 | 1 | 0 | 2 |
| stuck: refused target repeated | 0 | 1 | 0 | 1 |
| death: warden | 0 | 0 | 1 | 1 |
| death: zombie villager | 1 | 0 | 0 | 1 |
| stuck: ledge, aquifer side | 1 | 0 | 0 | 1 |
| death: badly hurt, the hungry climb dug on with a zombie in reach | 1 | 0 | 0 | 1 |
| death: stray | 1 | 0 | 0 | 1 |

| Kind | Pass | Intermittent | Fail | Scenes |
| --- | --- | --- | --- | --- |
| death | 194 | 61 | 50 | 305 |
| fixture | 2 | 0 | 0 | 2 |
| stuck | 72 | 19 | 24 | 115 |

| Mode | Pass | Intermittent | Fail | Scenes | Pass rate |
| --- | --- | --- | --- | --- | --- |
| factions | 53 | 12 | 8 | 73 | 72.6% |
| survival | 215 | 68 | 66 | 349 | 61.6% |

Staged scenes (contract fixtures run as scenes):
- f-25c4cp-ledge-505: pass (NativeCavernLedgeGameTests.onACavernsLedgeWithAnAquiferBehindItItGetsOffTheLedge)
- f-25c4dd-cornered-starving: pass (NativePortalCampaignGameTests.badlyHurtAndStarvingWithAZombieOnItItStopsItsClimbAndFights)

Baseline: 811b0f7 over 6 runs.

Flagged: passing on the baseline, now failing in 2 or more of 3 run(s): 3 — e40a20-79a9aad-101-sol-107500, e40a20-79a9aad-31102026-echo-106260, f29e-on-891567637-kindred-123297
Confirmed (blocks): 0
Cleared: 3 — e40a20-79a9aad-101-sol-107500 (branch failed 3 of 3, main passed 0 of 3); e40a20-79a9aad-31102026-echo-106260 (branch failed 0 of 3, main passed 3 of 3); f29e-on-891567637-kindred-123297 (branch failed 1 of 3, main passed 1 of 3)
Gate: passes
Newly passing against the baseline: 75 — diag-factions303-079bdc2-303-echo-10360, diag-factions303-079bdc2-303-echo-27140, diag-factions303-079bdc2-303-echo-36120, diag-factions303-079bdc2-303-echo-6460, diag-factions303-079bdc2-303-echo-75760, diag-factions303-079bdc2-303-kindred-107560, diag-factions303-079bdc2-303-sol-99140, diag-holepit-079bdc2-202-sol-91124, diag-holepit-079bdc2-31102026-echo-11660, diag-holepit-079bdc2-8675309-echo-34560, diag-holepit-079bdc2-8675309-kindred-111140, diag-holepit-079bdc2-8675309-sol-16249, e40a13-fe2ba3b-101-kindred-21933, e40a13-fe2ba3b-505-sol-110664, e40a13-fe2ba3b-505-sol-43879, e40a13-fe2ba3b-707-echo-128942, e40a14-80b7150-101-echo-75842, e40a14-80b7150-101-sol-84338, e40a14-80b7150-8675309-kindred-120887, e40a15-dbf4e5c-303-kindred-17203, e40a15-dbf4e5c-404-kindred-36022, e40a15-dbf4e5c-505-kindred-5689, e40a15-dbf4e5c-707-kindred-140905, e40a15-dbf4e5c-707-sol-10569, e40a15-dbf4e5c-8675309-kindred-37265, e40a16-922f4fd-202-echo-86114, e40a16-922f4fd-202-echo-90372, e40a16-922f4fd-202-kindred-136005, e40a16-922f4fd-505-echo-6085, e40a17-7bc5b42-303-kindred-121775, e40a17-7bc5b42-8675309-kindred-16523, e40a20-79a9aad-202-echo-14987, e40a20-79a9aad-202-sol-100125, e40a20-79a9aad-31102026-echo-102580, e40a20-79a9aad-8675309-kindred-105140, e40a20-79a9aad-8675309-kindred-112700, e40a20-79a9aad-8675309-kindred-116320, e40a20-79a9aad-8675309-kindred-130860, e40a20-79a9aad-8675309-kindred-134460, e40c1-eed2dc5-101-echo-23946, e40c1-eed2dc5-101-sol-13531, e40c1-eed2dc5-8675309-echo-173225, e40c1-eed2dc5-8675309-echo-87200, e40c1-eed2dc5-8675309-sol-114191, f29e-on-1166122638-sol-63903, f29e-on-303-kindred-91294, f29e-on-4h-202-kindred-112942, f29e-on-4h-202-sol-244548, f29e-on-4h-31102026-kindred-216171, f29e-on-4h-31102026-sol-216356, f29e-on-4h-707-sol-165088, f29e-on-4h-8675309-echo-266819, f29e-on-4h-8675309-sol-130048, f29e-on-707-echo-28809, f29e-on-779795571-echo-78472, f29e-on-779795571-kindred-47186, f29e-on-779795571-kindred-80926, f29e-on-891567637-kindred-36249, factions-110-d5057a4-110-echo-125906, factions-110-d5057a4-110-echo-240272, factions-110-d5057a4-110-kindred-118253, factions-110-d5057a4-110-kindred-231984, factions-110-d5057a4-110-kindred-250818, factions-110-d5057a4-110-kindred-254029, factions-110-d5057a4-110-sol-187246, factions-110-d5057a4-110-sol-21363, factions-e40a16seeds-b7b4bec-303-sol-76362, factions-e40a16seeds-b7b4bec-303-sol-90379, factions-e40a16seeds-b7b4bec-31102026-kindred-22521, factions-e40a16seeds-b7b4bec-404-kindred-143470, factions-e40a16seeds-b7b4bec-606-kindred-127222, factions-e40a16seeds-b7b4bec-707-kindred-21883, factions-e40a16seeds-b7b4bec-707-kindred-90496, factions-e40a16seeds-b7b4bec-707-sol-72346, factions-e40a16seeds-b7b4bec-8675309-kindred-72781
