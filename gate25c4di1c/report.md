# Failure corpus on 822b33db (its parent 4fc62f53, F-25c4di1b's head): 285 of 458 scenes pass (62.2%) over 3 runs

Passing 285, failing 74, intermittent 99 (an intermittent scene passed some runs and failed others; it is not counted as a pass).

| Class | Pass | Intermittent | Fail | Scenes |
| --- | --- | --- | --- | --- |
| death: zombie | 73 | 30 | 19 | 122 |
| death: skeleton | 46 | 22 | 8 | 76 |
| death: drowned | 27 | 1 | 9 | 37 |
| stuck: hemmed in | 19 | 6 | 7 | 32 |
| stuck: busy-work loop | 17 | 5 | 6 | 28 |
| death: spider | 15 | 8 | 0 | 23 |
| stuck: mining for a find | 8 | 8 | 2 | 18 |
| death: lava | 7 | 4 | 7 | 18 |
| stuck: hole or pit | 11 | 1 | 2 | 14 |
| stuck: no walkable route | 8 | 3 | 2 | 13 |
| death: creeper | 8 | 1 | 3 | 12 |
| stuck: water loop | 2 | 2 | 6 | 10 |
| death: fell | 8 | 2 | 0 | 10 |
| death: suffocation | 10 | 0 | 0 | 10 |
| death: husk | 4 | 2 | 0 | 6 |
| death: other | 3 | 0 | 1 | 4 |
| death: cave spider | 2 | 1 | 0 | 3 |
| death: phantom | 2 | 0 | 1 | 3 |
| death: enderman | 3 | 0 | 0 | 3 |
| stuck: refused target repeated | 1 | 1 | 0 | 2 |
| death: slime | 2 | 0 | 0 | 2 |
| stuck: waiting for daylight or a teammate | 2 | 0 | 0 | 2 |
| death: polar bear | 2 | 0 | 0 | 2 |
| death: witch | 0 | 2 | 0 | 2 |
| death: warden | 0 | 0 | 1 | 1 |
| death: zombie villager | 1 | 0 | 0 | 1 |
| death: fire | 1 | 0 | 0 | 1 |
| stuck: ledge, aquifer side | 1 | 0 | 0 | 1 |
| death: badly hurt, the hungry climb dug on with a zombie in reach | 1 | 0 | 0 | 1 |
| death: stray | 1 | 0 | 0 | 1 |

| Kind | Pass | Intermittent | Fail | Scenes |
| --- | --- | --- | --- | --- |
| death | 215 | 73 | 49 | 337 |
| fixture | 2 | 0 | 0 | 2 |
| stuck | 68 | 26 | 25 | 119 |

| Mode | Pass | Intermittent | Fail | Scenes | Pass rate |
| --- | --- | --- | --- | --- | --- |
| factions | 50 | 17 | 6 | 73 | 68.5% |
| survival | 235 | 82 | 68 | 385 | 61.0% |

Replay players still acting after their own batch, by run: 0, 0, 0 (should be 0)

Staged scenes (contract fixtures run as scenes):
- f-25c4cp-ledge-505: pass (NativeCavernLedgeGameTests.onACavernsLedgeWithAnAquiferBehindItItGetsOffTheLedge)
- f-25c4dd-cornered-starving: pass (NativePortalCampaignGameTests.badlyHurtAndStarvingWithAZombieOnItItStopsItsClimbAndFights)

Baseline: 16cb7a2 over 6 runs.

Flagged: passing on the baseline, now failing in 2 or more of 3 run(s): 6 — e40a13-fe2ba3b-707-echo-80901, e40a14-80b7150-101-echo-75842, e40a15-dbf4e5c-303-kindred-66804, e40a15-dbf4e5c-707-sol-10569, e40c1-eed2dc5-8675309-echo-173225, f29e-on-4h-707-kindred-222518
Confirmed (blocks): 1 — f29e-on-4h-707-kindred-222518 (branch failed 2 of 3, main passed 2 of 3)
Cleared: 5 — e40a13-fe2ba3b-707-echo-80901 (branch failed 1 of 3, main passed 2 of 3); e40a14-80b7150-101-echo-75842 (branch failed 2 of 3, main passed 1 of 3); e40a15-dbf4e5c-303-kindred-66804 (branch failed 1 of 3, main passed 2 of 3); e40a15-dbf4e5c-707-sol-10569 (branch failed 3 of 3, main passed 0 of 3); e40c1-eed2dc5-8675309-echo-173225 (branch failed 0 of 3, main passed 3 of 3)
Gate: fails
Newly passing against the baseline: 64 — diag-factions303-079bdc2-303-sol-32660, diag-factions303-079bdc2-303-sol-58760, diag-holepit-079bdc2-202-echo-80627, diag-holepit-079bdc2-202-sol-91124, diag-holepit-079bdc2-31102026-echo-11660, diag-holepit-079bdc2-404-echo-49188, diag-holepit-079bdc2-404-echo-72860, e40a13-fe2ba3b-404-echo-73717, e40a13-fe2ba3b-505-sol-10846, e40a13-fe2ba3b-505-sol-43879, e40a13-fe2ba3b-707-echo-128942, e40a13-fe2ba3b-707-sol-48414, e40a14-80b7150-101-sol-80066, e40a14-80b7150-101-sol-84338, e40a14-80b7150-31102026-echo-120452, e40a14-80b7150-31102026-sol-78892, e40a14-80b7150-505-echo-8422, e40a14-80b7150-8675309-kindred-128400, e40a15-dbf4e5c-505-kindred-5689, e40a15-dbf4e5c-8675309-kindred-52281, e40a15-dbf4e5c-8675309-kindred-73164, e40a16-922f4fd-101-sol-104427, e40a16-922f4fd-505-echo-6085, e40a16-922f4fd-505-kindred-77615, e40a16-922f4fd-505-sol-8079, e40a17-7bc5b42-505-sol-66039, e40a17-7bc5b42-707-kindred-7797, e40a17-7bc5b42-8675309-sol-7023, e40a20-79a9aad-101-sol-107500, e40a20-79a9aad-202-sol-29720, e40a20-79a9aad-20261030-kindred-131420, e40a20-79a9aad-606-echo-46420, e40a20-79a9aad-8675309-kindred-112700, e40a20-79a9aad-8675309-kindred-123600, e40a20-79a9aad-8675309-kindred-83380, e40a20-79a9aad-8675309-kindred-87020, e40a20-79a9aad-8675309-kindred-90640, e40a20-79a9aad-8675309-kindred-94280, e40a22-d2389c9-101-echo-17476, e40a22-d2389c9-101-kindred-47820, e40a22-d2389c9-101-kindred-51620, e40a22-d2389c9-31102026-echo-30737, e40a22-d2389c9-404-sol-50932, e40a22-d2389c9-707-echo-2597, e40a22-d2389c9-707-kindred-136143, e40a22-d2389c9-707-kindred-51330, e40c1-eed2dc5-101-echo-23946, e40c1-eed2dc5-101-sol-13531, e40c1-eed2dc5-505-echo-104879, e40c1-eed2dc5-8675309-sol-26517, f29e-on-1166122638-sol-63903, f29e-on-4h-202-sol-244548, f29e-on-4h-8675309-sol-130048, f29e-on-779795571-echo-78472, f29e-on-891567637-kindred-123297, factions-110-d5057a4-110-echo-154325, factions-110-d5057a4-110-echo-215951, factions-110-d5057a4-110-kindred-254029, factions-e40a16seeds-b7b4bec-101-kindred-71684, factions-e40a16seeds-b7b4bec-20261030-echo-41484, factions-e40a16seeds-b7b4bec-20261030-echo-43234, factions-e40a16seeds-b7b4bec-20261030-sol-120608, factions-e40a16seeds-b7b4bec-31102026-kindred-17463, factions-e40a16seeds-b7b4bec-505-echo-66907
