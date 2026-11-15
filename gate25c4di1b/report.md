# Failure corpus on cc67d1d1 (its parent f80148fe, F-25c4di1a's head): 288 of 458 scenes pass (62.9%) over 3 runs

Passing 288, failing 68, intermittent 102 (an intermittent scene passed some runs and failed others; it is not counted as a pass).

| Class | Pass | Intermittent | Fail | Scenes |
| --- | --- | --- | --- | --- |
| death: zombie | 75 | 36 | 11 | 122 |
| death: skeleton | 52 | 18 | 6 | 76 |
| death: drowned | 26 | 3 | 8 | 37 |
| stuck: hemmed in | 17 | 5 | 10 | 32 |
| stuck: busy-work loop | 19 | 2 | 7 | 28 |
| death: spider | 12 | 9 | 2 | 23 |
| stuck: mining for a find | 10 | 5 | 3 | 18 |
| death: lava | 7 | 4 | 7 | 18 |
| stuck: hole or pit | 10 | 1 | 3 | 14 |
| stuck: no walkable route | 7 | 4 | 2 | 13 |
| death: creeper | 7 | 4 | 1 | 12 |
| stuck: water loop | 1 | 3 | 6 | 10 |
| death: fell | 9 | 1 | 0 | 10 |
| death: suffocation | 10 | 0 | 0 | 10 |
| death: husk | 5 | 0 | 1 | 6 |
| death: other | 3 | 1 | 0 | 4 |
| death: cave spider | 2 | 1 | 0 | 3 |
| death: phantom | 2 | 1 | 0 | 3 |
| death: enderman | 3 | 0 | 0 | 3 |
| stuck: refused target repeated | 1 | 1 | 0 | 2 |
| death: slime | 2 | 0 | 0 | 2 |
| stuck: waiting for daylight or a teammate | 1 | 1 | 0 | 2 |
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
| death | 220 | 80 | 37 | 337 |
| fixture | 2 | 0 | 0 | 2 |
| stuck | 66 | 22 | 31 | 119 |

| Mode | Pass | Intermittent | Fail | Scenes | Pass rate |
| --- | --- | --- | --- | --- | --- |
| factions | 53 | 14 | 6 | 73 | 72.6% |
| survival | 235 | 88 | 62 | 385 | 61.0% |

Replay players still acting after their own batch, by run: 0, 0, 0 (should be 0)

Staged scenes (contract fixtures run as scenes):
- f-25c4cp-ledge-505: pass (NativeCavernLedgeGameTests.onACavernsLedgeWithAnAquiferBehindItItGetsOffTheLedge)
- f-25c4dd-cornered-starving: pass (NativePortalCampaignGameTests.badlyHurtAndStarvingWithAZombieOnItItStopsItsClimbAndFights)

Baseline: 16cb7a2 over 6 runs.

Flagged: passing on the baseline, now failing in 2 or more of 3 run(s): 3 — e40a13-fe2ba3b-606-echo-81682, e40a15-dbf4e5c-404-kindred-36022, f29e-on-4h-707-kindred-222518
Confirmed (blocks): 0
Cleared: 3 — e40a13-fe2ba3b-606-echo-81682 (branch failed 1 of 3, main passed 1 of 3); e40a15-dbf4e5c-404-kindred-36022 (branch failed 1 of 3, main passed 3 of 3); f29e-on-4h-707-kindred-222518 (branch failed 1 of 3, main passed 2 of 3)
Gate: passes
Newly passing against the baseline: 63 — diag-factions303-079bdc2-303-sol-32660, diag-holepit-079bdc2-202-echo-80627, diag-holepit-079bdc2-202-sol-91124, diag-holepit-079bdc2-31102026-echo-108000, diag-holepit-079bdc2-404-echo-49188, diag-holepit-079bdc2-404-echo-72860, diag-holepit-079bdc2-8675309-echo-45700, e40a13-fe2ba3b-404-echo-73717, e40a13-fe2ba3b-505-sol-10846, e40a13-fe2ba3b-505-sol-43879, e40a13-fe2ba3b-707-echo-128942, e40a13-fe2ba3b-707-sol-48414, e40a14-80b7150-101-sol-80066, e40a14-80b7150-101-sol-84338, e40a14-80b7150-31102026-echo-120452, e40a14-80b7150-8675309-kindred-124229, e40a15-dbf4e5c-505-kindred-5689, e40a15-dbf4e5c-8675309-kindred-52281, e40a15-dbf4e5c-8675309-kindred-73164, e40a16-922f4fd-101-sol-104427, e40a16-922f4fd-202-echo-55002, e40a16-922f4fd-505-echo-6085, e40a16-922f4fd-505-kindred-77615, e40a16-922f4fd-505-sol-8079, e40a16-922f4fd-707-kindred-14534, e40a17-7bc5b42-8675309-kindred-16523, e40a20-79a9aad-101-sol-107500, e40a20-79a9aad-202-echo-110820, e40a20-79a9aad-202-sol-29720, e40a20-79a9aad-202-sol-33840, e40a20-79a9aad-404-echo-13600, e40a20-79a9aad-8675309-kindred-123600, e40a20-79a9aad-8675309-kindred-83380, e40a20-79a9aad-8675309-kindred-87020, e40a20-79a9aad-8675309-kindred-94280, e40a22-d2389c9-101-echo-17476, e40a22-d2389c9-101-kindred-47820, e40a22-d2389c9-101-kindred-51620, e40a22-d2389c9-31102026-echo-30737, e40a22-d2389c9-404-sol-50932, e40a22-d2389c9-707-echo-2597, e40a22-d2389c9-707-kindred-136143, e40a22-d2389c9-707-kindred-51330, e40c1-eed2dc5-101-echo-23946, e40c1-eed2dc5-20261030-kindred-113934, e40c1-eed2dc5-505-echo-104879, e40c1-eed2dc5-8675309-sol-26517, f29e-on-4h-8675309-echo-88079, f29e-on-505-echo-137153, f29e-on-779795571-echo-78472, f29e-on-891567637-kindred-123297, f29e-on-891567637-kindred-93075, factions-110-d5057a4-110-echo-125906, factions-110-d5057a4-110-echo-154325, factions-110-d5057a4-110-echo-215951, factions-110-d5057a4-110-kindred-114087, factions-e40a16seeds-b7b4bec-101-kindred-71684, factions-e40a16seeds-b7b4bec-20261030-echo-41484, factions-e40a16seeds-b7b4bec-31102026-kindred-17463, factions-e40a16seeds-b7b4bec-31102026-kindred-22521, factions-e40a16seeds-b7b4bec-505-echo-66907, factions-e40a16seeds-b7b4bec-606-kindred-127222, factions-e40a16seeds-b7b4bec-707-kindred-88286
