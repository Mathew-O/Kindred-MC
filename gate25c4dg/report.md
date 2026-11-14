# Failure corpus on 3c67e62 (main fbc5f0f, the merge-base): 260 of 422 scenes pass (61.6%) over 3 runs

Passing 260, failing 85, intermittent 77 (an intermittent scene passed some runs and failed others; it is not counted as a pass).

| Class | Pass | Intermittent | Fail | Scenes |
| --- | --- | --- | --- | --- |
| death: zombie | 66 | 25 | 17 | 108 |
| death: skeleton | 50 | 16 | 6 | 72 |
| death: drowned | 26 | 0 | 9 | 35 |
| stuck: hemmed in | 18 | 4 | 10 | 32 |
| stuck: busy-work loop | 17 | 2 | 9 | 28 |
| death: spider | 10 | 7 | 4 | 21 |
| stuck: mining for a find | 8 | 6 | 4 | 18 |
| death: lava | 6 | 1 | 9 | 16 |
| stuck: no walkable route | 6 | 3 | 3 | 12 |
| stuck: hole or pit | 10 | 1 | 1 | 12 |
| stuck: water loop | 1 | 3 | 6 | 10 |
| death: creeper | 7 | 3 | 0 | 10 |
| death: fell | 8 | 2 | 0 | 10 |
| death: suffocation | 3 | 1 | 2 | 6 |
| death: husk | 5 | 0 | 1 | 6 |
| death: other | 3 | 1 | 0 | 4 |
| death: cave spider | 2 | 0 | 1 | 3 |
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
| death | 196 | 58 | 51 | 305 |
| fixture | 2 | 0 | 0 | 2 |
| stuck | 62 | 19 | 34 | 115 |

| Mode | Pass | Intermittent | Fail | Scenes | Pass rate |
| --- | --- | --- | --- | --- | --- |
| factions | 47 | 16 | 10 | 73 | 64.4% |
| survival | 213 | 61 | 75 | 349 | 61.0% |

Replay players still acting after their own batch, by run: 0, 0, 0 (should be 0)

Staged scenes (contract fixtures run as scenes):
- f-25c4cp-ledge-505: pass (NativeCavernLedgeGameTests.onACavernsLedgeWithAnAquiferBehindItItGetsOffTheLedge)
- f-25c4dd-cornered-starving: pass (NativePortalCampaignGameTests.badlyHurtAndStarvingWithAZombieOnItItStopsItsClimbAndFights)

Baseline: 16cb7a2 over 6 runs.

Flagged: passing on the baseline, now failing in 2 or more of 3 run(s): 2 — e40a15-dbf4e5c-707-sol-10569, e40a16-922f4fd-101-echo-18586
Confirmed (blocks): 1 — e40a15-dbf4e5c-707-sol-10569 (branch failed 2 of 3, main passed 2 of 3)
Cleared: 1 — e40a16-922f4fd-101-echo-18586 (branch failed 0 of 3, main passed 2 of 3)
Gate: fails
Newly passing against the baseline: 42 — diag-holepit-079bdc2-31102026-echo-11660, diag-holepit-079bdc2-404-echo-49188, e40a13-fe2ba3b-404-echo-73717, e40a13-fe2ba3b-505-sol-10846, e40a13-fe2ba3b-707-echo-128942, e40a13-fe2ba3b-707-sol-48414, e40a14-80b7150-101-echo-5557, e40a14-80b7150-101-kindred-21957, e40a14-80b7150-101-sol-80066, e40a14-80b7150-505-echo-8422, e40a14-80b7150-8675309-echo-127264, e40a15-dbf4e5c-8675309-kindred-52281, e40a16-922f4fd-101-sol-104427, e40a16-922f4fd-202-echo-55002, e40a16-922f4fd-505-echo-6085, e40a16-922f4fd-505-sol-8079, e40a16-922f4fd-707-kindred-14534, e40a17-7bc5b42-505-sol-120198, e40a17-7bc5b42-707-echo-36256, e40a17-7bc5b42-8675309-kindred-16523, e40a20-79a9aad-101-sol-107500, e40a20-79a9aad-202-echo-110820, e40a20-79a9aad-202-sol-33840, e40a20-79a9aad-8675309-kindred-123600, e40a20-79a9aad-8675309-kindred-134460, e40a20-79a9aad-8675309-kindred-83380, e40a20-79a9aad-8675309-kindred-94280, e40c1-eed2dc5-101-sol-13531, e40c1-eed2dc5-505-echo-104879, e40c1-eed2dc5-505-echo-42820, e40c1-eed2dc5-8675309-echo-87200, e40c1-eed2dc5-8675309-sol-26517, f29e-on-1166122638-sol-63903, f29e-on-4h-202-kindred-98508, f29e-on-505-echo-137153, f29e-on-891567637-kindred-123297, f29e-on-891567637-kindred-8108, factions-110-d5057a4-110-echo-215951, factions-110-d5057a4-110-kindred-254029, factions-e40a16seeds-b7b4bec-101-kindred-71684, factions-e40a16seeds-b7b4bec-20261030-echo-41484, factions-e40a16seeds-b7b4bec-31102026-kindred-20226
