# Failure corpus on ab0dc8e1: 297 of 461 scenes pass (64.4%) over 3 runs

Passing 297, failing 82, intermittent 82 (an intermittent scene passed some runs and failed others; it is not counted as a pass).

| Class | Pass | Intermittent | Fail | Scenes |
| --- | --- | --- | --- | --- |
| death: zombie | 71 | 34 | 17 | 122 |
| death: skeleton | 50 | 16 | 10 | 76 |
| death: drowned | 28 | 0 | 9 | 37 |
| stuck: hemmed in | 18 | 4 | 10 | 32 |
| stuck: busy-work loop | 18 | 3 | 7 | 28 |
| death: spider | 14 | 6 | 3 | 23 |
| stuck: mining for a find | 12 | 5 | 1 | 18 |
| death: lava | 7 | 2 | 9 | 18 |
| stuck: hole or pit | 10 | 2 | 2 | 14 |
| stuck: no walkable route | 8 | 1 | 4 | 13 |
| death: creeper | 7 | 3 | 2 | 12 |
| stuck: water loop | 3 | 1 | 6 | 10 |
| death: fell | 9 | 1 | 0 | 10 |
| death: suffocation | 10 | 0 | 0 | 10 |
| death: husk | 4 | 2 | 0 | 6 |
| death: other | 4 | 0 | 0 | 4 |
| death: cave spider | 2 | 1 | 0 | 3 |
| death: phantom | 3 | 0 | 0 | 3 |
| death: enderman | 3 | 0 | 0 | 3 |
| stuck: refused target repeated | 2 | 0 | 0 | 2 |
| death: slime | 2 | 0 | 0 | 2 |
| stuck: waiting for daylight or a teammate | 1 | 1 | 0 | 2 |
| death: polar bear | 2 | 0 | 0 | 2 |
| waking: its death in the box | 2 | 0 | 0 | 2 |
| death: witch | 1 | 0 | 1 | 2 |
| death: warden | 0 | 0 | 1 | 1 |
| death: zombie villager | 1 | 0 | 0 | 1 |
| death: fire | 1 | 0 | 0 | 1 |
| stuck: ledge, aquifer side | 1 | 0 | 0 | 1 |
| death: badly hurt, the hungry climb dug on with a zombie in reach | 1 | 0 | 0 | 1 |
| death: stray | 1 | 0 | 0 | 1 |
| waking: its death outside the box | 1 | 0 | 0 | 1 |

| Kind | Pass | Intermittent | Fail | Scenes |
| --- | --- | --- | --- | --- |
| death | 220 | 65 | 52 | 337 |
| fixture | 2 | 0 | 0 | 2 |
| respawn | 3 | 0 | 0 | 3 |
| stuck | 72 | 17 | 30 | 119 |

| Mode | Pass | Intermittent | Fail | Scenes | Pass rate |
| --- | --- | --- | --- | --- | --- |
| factions | 51 | 14 | 8 | 73 | 69.9% |
| survival | 246 | 68 | 74 | 388 | 63.4% |

Waking scenes, its armour back on at the budget's end (reported, not the pass), by run: 0 of 3, 0 of 3, 0 of 3

Replay players still acting after their own batch, by run: 0, 0, 0 (should be 0)

Staged scenes (contract fixtures run as scenes):
- f-25c4cp-ledge-505: pass (NativeCavernLedgeGameTests.onACavernsLedgeWithAnAquiferBehindItItGetsOffTheLedge)
- f-25c4dd-cornered-starving: pass (NativePortalCampaignGameTests.badlyHurtAndStarvingWithAZombieOnItItStopsItsClimbAndFights)

Baseline: 16cb7a2 over 6 runs.

Flagged: passing on the baseline, now failing in 2 or more of 3 run(s): 7 — e40a13-fe2ba3b-707-echo-80901, e40a15-dbf4e5c-404-kindred-36022, e40a15-dbf4e5c-707-sol-10569, e40a20-79a9aad-202-sol-100125, e40a22-d2389c9-202-kindred-48843, e40c4-b23acc0-303-kindred-235741, f29e-on-4h-707-kindred-222518
Known intermittent among the flagged (expected; the rule is unchanged): 1 — f29e-on-4h-707-kindred-222518 (zombie death; noted 2026-11-15 (LOOPS, from 88 kept runs of it on claude/spacer-runs-1112): intermittent on 11-12 Nov (the 422 re-baseline failed it 3 of 6, g219 1 of 3, the spacer runs 2 of 4, #157's 3 of 3), passed every run of about 30 on the 13-14 Nov gates, and intermittent again from 15 Nov (13 of 18 failed on that day's gates; alone, branches 6 of 15 and bases 8 of 15, rounds 1-3 alike); its scene file unchanged since F-27a (c266a7e3); one failure shape throughout, an early death to a zombie, a fall or a creeper before its pack fills; passing runs live to "inventory full - Drop surplus" (3 to 111 times), a sign of a long life, not a cause; no bisect (the lead, 2026-11-15); its early zombie deaths are SURVIVAL's to count; the three-part rule applies to it unchanged)
Confirmed (blocks): 1 — e40a15-dbf4e5c-404-kindred-36022 (branch failed 2 of 3, main passed 3 of 3)
Cleared: 6 — e40a13-fe2ba3b-707-echo-80901 (branch failed 2 of 3, main passed 1 of 3); e40a15-dbf4e5c-707-sol-10569 (branch failed 3 of 3, main passed 1 of 3); e40a20-79a9aad-202-sol-100125 (branch failed 0 of 3, main passed 3 of 3); e40a22-d2389c9-202-kindred-48843 (branch failed 3 of 3, main passed 1 of 3); e40c4-b23acc0-303-kindred-235741 (branch failed 1 of 3, main passed 1 of 3); f29e-on-4h-707-kindred-222518 (branch failed 2 of 3, main passed 1 of 3)
Gate: fails
Newly passing against the baseline: 69 — diag-factions303-079bdc2-303-echo-36120, diag-factions303-079bdc2-303-sol-32660, diag-factions303-079bdc2-303-sol-58760, diag-holepit-079bdc2-202-echo-80627, diag-holepit-079bdc2-31102026-echo-108000, diag-holepit-079bdc2-404-echo-49188, diag-holepit-079bdc2-404-echo-72860, diag-holepit-079bdc2-8675309-kindred-111140, e40a13-fe2ba3b-404-echo-73717, e40a13-fe2ba3b-505-sol-10846, e40a13-fe2ba3b-707-echo-128942, e40a13-fe2ba3b-707-sol-48414, e40a14-80b7150-101-sol-80066, e40a14-80b7150-31102026-echo-120452, e40a14-80b7150-31102026-sol-78892, e40a14-80b7150-505-echo-8422, e40a15-dbf4e5c-505-kindred-5689, e40a15-dbf4e5c-8675309-kindred-52281, e40a15-dbf4e5c-8675309-kindred-73164, e40a16-922f4fd-101-sol-104427, e40a16-922f4fd-202-echo-55002, e40a16-922f4fd-505-echo-6085, e40a16-922f4fd-505-kindred-77615, e40a16-922f4fd-505-sol-8079, e40a16-922f4fd-707-kindred-14534, e40a17-7bc5b42-505-sol-66039, e40a17-7bc5b42-8675309-kindred-16523, e40a17-7bc5b42-8675309-sol-7023, e40a20-79a9aad-101-sol-107500, e40a20-79a9aad-202-echo-14987, e40a20-79a9aad-202-sol-29720, e40a20-79a9aad-202-sol-33840, e40a20-79a9aad-20261030-kindred-131420, e40a20-79a9aad-404-kindred-59140, e40a20-79a9aad-8675309-kindred-119960, e40a20-79a9aad-8675309-kindred-123600, e40a20-79a9aad-8675309-kindred-130860, e40a20-79a9aad-8675309-kindred-28514, e40a20-79a9aad-8675309-kindred-87020, e40a22-d2389c9-101-kindred-47820, e40a22-d2389c9-101-kindred-51620, e40a22-d2389c9-202-kindred-54486, e40a22-d2389c9-31102026-echo-30737, e40a22-d2389c9-404-sol-50932, e40a22-d2389c9-707-echo-2597, e40a22-d2389c9-707-kindred-51330, e40c1-eed2dc5-101-echo-23946, e40c1-eed2dc5-101-sol-13531, e40c1-eed2dc5-505-echo-104879, e40c1-eed2dc5-505-echo-42820, e40c1-eed2dc5-8675309-echo-87200, e40c1-eed2dc5-8675309-sol-26517, e40c4-b23acc0-303-kindred-194201, e40c4-b23acc0-303-kindred-200600, f29e-on-1166122638-sol-63903, f29e-on-202-echo-124223, f29e-on-4h-202-sol-244548, f29e-on-4h-8675309-echo-88079, f29e-on-4h-8675309-kindred-31280, f29e-on-4h-8675309-sol-130048, f29e-on-779795571-echo-78472, factions-110-d5057a4-110-echo-215951, factions-110-d5057a4-110-kindred-254029, factions-110-d5057a4-110-sol-115671, factions-e40a16seeds-b7b4bec-101-kindred-71684, factions-e40a16seeds-b7b4bec-20261030-sol-120608, factions-e40a16seeds-b7b4bec-31102026-kindred-17463, factions-e40a16seeds-b7b4bec-505-echo-66907, factions-e40a16seeds-b7b4bec-707-kindred-90496
