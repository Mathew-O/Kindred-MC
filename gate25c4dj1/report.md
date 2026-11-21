# Failure corpus on 69241ca3: 286 of 471 scenes pass (60.7%) over 3 runs

Passing 286, failing 89, intermittent 96 (an intermittent scene passed some runs and failed others; it is not counted as a pass).

| Class | Pass | Intermittent | Fail | Scenes |
| --- | --- | --- | --- | --- |
| death: zombie | 66 | 36 | 25 | 127 |
| death: skeleton | 52 | 18 | 7 | 77 |
| death: drowned | 28 | 0 | 9 | 37 |
| stuck: hemmed in | 16 | 8 | 8 | 32 |
| stuck: busy-work loop | 18 | 4 | 6 | 28 |
| death: spider | 15 | 5 | 3 | 23 |
| stuck: mining for a find | 9 | 6 | 3 | 18 |
| death: lava | 8 | 2 | 8 | 18 |
| stuck: hole or pit | 9 | 2 | 4 | 15 |
| stuck: no walkable route | 9 | 1 | 3 | 13 |
| death: creeper | 7 | 4 | 2 | 13 |
| stuck: water loop | 0 | 4 | 6 | 10 |
| death: fell | 10 | 0 | 0 | 10 |
| death: suffocation | 10 | 0 | 0 | 10 |
| death: husk | 4 | 2 | 0 | 6 |
| death: other | 3 | 1 | 0 | 4 |
| death: slime | 1 | 1 | 1 | 3 |
| death: cave spider | 2 | 0 | 1 | 3 |
| death: phantom | 2 | 1 | 0 | 3 |
| death: enderman | 3 | 0 | 0 | 3 |
| death: witch | 2 | 0 | 1 | 3 |
| stuck: refused target repeated | 1 | 1 | 0 | 2 |
| stuck: waiting for daylight or a teammate | 1 | 0 | 1 | 2 |
| death: polar bear | 2 | 0 | 0 | 2 |
| waking: its death in the box | 2 | 0 | 0 | 2 |
| death: warden | 0 | 0 | 1 | 1 |
| death: zombie villager | 1 | 0 | 0 | 1 |
| death: fire | 1 | 0 | 0 | 1 |
| stuck: ledge, aquifer side | 1 | 0 | 0 | 1 |
| death: badly hurt, the hungry climb dug on with a zombie in reach | 1 | 0 | 0 | 1 |
| death: stray | 1 | 0 | 0 | 1 |
| waking: its death outside the box | 1 | 0 | 0 | 1 |

| Kind | Pass | Intermittent | Fail | Scenes |
| --- | --- | --- | --- | --- |
| death | 218 | 70 | 58 | 346 |
| fixture | 2 | 0 | 0 | 2 |
| respawn | 3 | 0 | 0 | 3 |
| stuck | 63 | 26 | 31 | 120 |

| Mode | Pass | Intermittent | Fail | Scenes | Pass rate |
| --- | --- | --- | --- | --- | --- |
| factions | 47 | 19 | 7 | 73 | 64.4% |
| survival | 239 | 77 | 82 | 398 | 60.1% |

Waking scenes, its armour back on at the budget's end (reported, not the pass), by run: 0 of 3, 0 of 3, 0 of 3

Replay players still acting after their own batch, by run: 0, 0, 0 (should be 0)

Staged scenes (contract fixtures run as scenes):
- f-25c4cp-ledge-505: pass (NativeCavernLedgeGameTests.onACavernsLedgeWithAnAquiferBehindItItGetsOffTheLedge)
- f-25c4dd-cornered-starving: pass (NativePortalCampaignGameTests.badlyHurtAndStarvingWithAZombieOnItItStopsItsClimbAndFights)

Baseline: 16cb7a2 over 6 runs.

Flagged: passing on the baseline, now failing in 2 or more of 3 run(s): 3 — e40a15-dbf4e5c-303-kindred-66804, e40a15-dbf4e5c-404-kindred-36022, factions-e40a16seeds-b7b4bec-8675309-echo-106722
Confirmed (blocks): 1 — e40a15-dbf4e5c-303-kindred-66804 (branch failed 2 of 3, main passed 2 of 3)
Cleared: 2 — e40a15-dbf4e5c-404-kindred-36022 (branch failed 0 of 3, main passed 2 of 3); factions-e40a16seeds-b7b4bec-8675309-echo-106722 (branch failed 3 of 3, main passed 1 of 3)
Gate: fails
Newly passing against the baseline: 58 — diag-factions303-079bdc2-303-echo-27140, diag-factions303-079bdc2-303-sol-32660, diag-holepit-079bdc2-404-echo-49188, diag-holepit-079bdc2-404-echo-72860, e40a13-fe2ba3b-404-echo-73717, e40a13-fe2ba3b-505-sol-10846, e40a13-fe2ba3b-707-echo-128942, e40a13-fe2ba3b-707-sol-48414, e40a14-80b7150-101-echo-5557, e40a14-80b7150-31102026-echo-120452, e40a14-80b7150-505-echo-8422, e40a14-80b7150-8675309-echo-127264, e40a14-80b7150-8675309-kindred-124229, e40a15-dbf4e5c-505-kindred-38374, e40a16-922f4fd-101-sol-104427, e40a16-922f4fd-202-echo-55002, e40a16-922f4fd-505-echo-6085, e40a16-922f4fd-505-kindred-77615, e40a16-922f4fd-505-sol-8079, e40a17-7bc5b42-101-sol-53904, e40a17-7bc5b42-505-sol-66039, e40a17-7bc5b42-707-echo-36256, e40a17-7bc5b42-8675309-kindred-16523, e40a20-79a9aad-101-sol-107500, e40a20-79a9aad-202-echo-14987, e40a20-79a9aad-202-sol-29720, e40a20-79a9aad-202-sol-33840, e40a20-79a9aad-31102026-echo-82240, e40a20-79a9aad-8675309-kindred-123600, e40a20-79a9aad-8675309-kindred-34442, e40a20-79a9aad-8675309-kindred-83380, e40a20-79a9aad-8675309-kindred-94280, e40a22-d2389c9-101-kindred-47820, e40a22-d2389c9-101-kindred-51620, e40a22-d2389c9-202-kindred-54486, e40a22-d2389c9-31102026-echo-30737, e40a22-d2389c9-404-sol-50932, e40a22-d2389c9-707-echo-2597, e40a22-d2389c9-707-kindred-51330, e40a23-3115b1c-101-sol-132003, e40c1-eed2dc5-101-echo-23946, e40c1-eed2dc5-101-sol-13531, e40c1-eed2dc5-101-sol-29848, e40c1-eed2dc5-505-echo-104879, e40c1-eed2dc5-8675309-echo-87200, e40c1-eed2dc5-8675309-sol-26517, f29e-on-1166122638-sol-63903, f29e-on-4h-8675309-sol-130048, f29e-on-779795571-echo-78472, f29e-on-891567637-kindred-8108, f29e-on-891567637-kindred-93075, factions-110-d5057a4-110-kindred-254029, factions-e40a16seeds-b7b4bec-101-kindred-71684, factions-e40a16seeds-b7b4bec-20261030-sol-120608, factions-e40a16seeds-b7b4bec-31102026-kindred-17463, factions-e40a16seeds-b7b4bec-404-sol-24492, factions-e40a16seeds-b7b4bec-505-echo-66907, factions-e40a16seeds-b7b4bec-707-kindred-90496
