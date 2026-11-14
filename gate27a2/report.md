# Failure corpus on 7d5968c (main 17f2053, the merge-base): 259 of 422 scenes pass (61.4%) over 3 runs

Passing 259, failing 79, intermittent 84 (an intermittent scene passed some runs and failed others; it is not counted as a pass).

| Class | Pass | Intermittent | Fail | Scenes |
| --- | --- | --- | --- | --- |
| death: zombie | 71 | 20 | 17 | 108 |
| death: skeleton | 51 | 13 | 8 | 72 |
| death: drowned | 25 | 1 | 9 | 35 |
| stuck: hemmed in | 16 | 9 | 7 | 32 |
| stuck: busy-work loop | 18 | 3 | 7 | 28 |
| death: spider | 11 | 8 | 2 | 21 |
| stuck: mining for a find | 8 | 9 | 1 | 18 |
| death: lava | 2 | 0 | 14 | 16 |
| stuck: no walkable route | 6 | 5 | 1 | 12 |
| stuck: hole or pit | 8 | 2 | 2 | 12 |
| stuck: water loop | 2 | 2 | 6 | 10 |
| death: creeper | 8 | 2 | 0 | 10 |
| death: fell | 8 | 2 | 0 | 10 |
| death: suffocation | 3 | 1 | 2 | 6 |
| death: husk | 4 | 1 | 1 | 6 |
| death: other | 3 | 1 | 0 | 4 |
| death: cave spider | 2 | 1 | 0 | 3 |
| death: phantom | 2 | 1 | 0 | 3 |
| death: slime | 0 | 1 | 1 | 2 |
| stuck: waiting for daylight or a teammate | 1 | 1 | 0 | 2 |
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
| death | 197 | 53 | 55 | 305 |
| fixture | 2 | 0 | 0 | 2 |
| stuck | 60 | 31 | 24 | 115 |

| Mode | Pass | Intermittent | Fail | Scenes | Pass rate |
| --- | --- | --- | --- | --- | --- |
| factions | 48 | 16 | 9 | 73 | 65.8% |
| survival | 211 | 68 | 70 | 349 | 60.5% |

Replay players still acting after their own batch, by run: 0, 0, 0 (should be 0)

Staged scenes (contract fixtures run as scenes):
- f-25c4cp-ledge-505: pass (NativeCavernLedgeGameTests.onACavernsLedgeWithAnAquiferBehindItItGetsOffTheLedge)
- f-25c4dd-cornered-starving: pass (NativePortalCampaignGameTests.badlyHurtAndStarvingWithAZombieOnItItStopsItsClimbAndFights)

Baseline: 16cb7a2 over 6 runs.

Flagged: passing on the baseline, now failing in 2 or more of 3 run(s): 3 — e40a15-dbf4e5c-707-sol-10569, e40a16-922f4fd-202-echo-86114, e40a20-79a9aad-8675309-kindred-97900
Confirmed (blocks): 1 — e40a15-dbf4e5c-707-sol-10569 (branch failed 3 of 3, main passed 2 of 3)
Cleared: 2 — e40a16-922f4fd-202-echo-86114 (branch failed 0 of 3, main passed 3 of 3); e40a20-79a9aad-8675309-kindred-97900 (branch failed 1 of 3, main passed 3 of 3)
Gate: fails
Newly passing against the baseline: 41 — diag-factions303-079bdc2-303-sol-32660, diag-factions303-079bdc2-303-sol-58760, diag-holepit-079bdc2-31102026-echo-11660, diag-holepit-079bdc2-404-echo-72860, e40a13-fe2ba3b-505-sol-10846, e40a13-fe2ba3b-707-echo-128942, e40a13-fe2ba3b-707-sol-48414, e40a14-80b7150-101-echo-5557, e40a14-80b7150-101-kindred-21957, e40a14-80b7150-101-sol-80066, e40a14-80b7150-505-echo-8422, e40a15-dbf4e5c-8675309-kindred-52281, e40a16-922f4fd-101-sol-104427, e40a16-922f4fd-202-echo-55002, e40a16-922f4fd-505-echo-6085, e40a17-7bc5b42-8675309-kindred-16523, e40a17-7bc5b42-8675309-sol-38092, e40a17-7bc5b42-8675309-sol-7023, e40a20-79a9aad-202-echo-110820, e40a20-79a9aad-202-echo-14987, e40a20-79a9aad-202-sol-29720, e40a20-79a9aad-202-sol-33840, e40a20-79a9aad-31102026-sol-97112, e40a20-79a9aad-8675309-kindred-83380, e40a20-79a9aad-8675309-kindred-87020, e40c1-eed2dc5-101-echo-23946, e40c1-eed2dc5-101-sol-41494, e40c1-eed2dc5-505-echo-104879, e40c1-eed2dc5-8675309-echo-87200, e40c1-eed2dc5-8675309-sol-26517, f29e-on-1166122638-sol-63903, f29e-on-4h-202-sol-244548, f29e-on-4h-8675309-kindred-31280, f29e-on-505-echo-137153, f29e-on-891567637-kindred-123297, f29e-on-891567637-kindred-8108, factions-110-d5057a4-110-echo-125906, factions-110-d5057a4-110-echo-215951, factions-e40a16seeds-b7b4bec-20261030-sol-120608, factions-e40a16seeds-b7b4bec-606-kindred-127222, factions-e40a16seeds-b7b4bec-707-kindred-88286
