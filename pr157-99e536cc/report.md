# Failure corpus on 99e536cc (main a60f9193): 113 of 275 scenes pass (41.1%) over 3 runs

Passing 113, failing 101, intermittent 61 (an intermittent scene passed some runs and failed others; it is not counted as a pass).

| Class | Pass | Intermittent | Fail | Scenes |
| --- | --- | --- | --- | --- |
| death: zombie | 42 | 25 | 31 | 98 |
| death: skeleton | 25 | 21 | 21 | 67 |
| death: drowned | 5 | 4 | 21 | 30 |
| death: spider | 9 | 4 | 4 | 17 |
| death: lava | 1 | 0 | 11 | 12 |
| death: fell | 9 | 1 | 0 | 10 |
| death: creeper | 6 | 1 | 2 | 9 |
| death: suffocation | 4 | 0 | 2 | 6 |
| death: husk | 3 | 1 | 2 | 6 |
| death: other | 1 | 1 | 2 | 4 |
| death: cave spider | 2 | 0 | 1 | 3 |
| death: phantom | 0 | 0 | 3 | 3 |
| death: polar bear | 2 | 0 | 0 | 2 |
| death: enderman | 2 | 0 | 0 | 2 |
| death: witch | 0 | 2 | 0 | 2 |
| death: slime | 1 | 0 | 0 | 1 |
| death: warden | 0 | 0 | 1 | 1 |
| death: badly hurt, the hungry climb dug on with a zombie in reach | 1 | 0 | 0 | 1 |
| death: stray | 0 | 1 | 0 | 1 |

| Kind | Pass | Intermittent | Fail | Scenes |
| --- | --- | --- | --- | --- |
| death | 112 | 61 | 101 | 274 |
| fixture | 1 | 0 | 0 | 1 |

| Mode | Pass | Intermittent | Fail | Scenes | Pass rate |
| --- | --- | --- | --- | --- | --- |
| factions | 13 | 16 | 21 | 50 | 26.0% |
| survival | 100 | 45 | 80 | 225 | 44.4% |

Replay players still acting after their own batch, by run: 0, 0, 0 (should be 0)

Staged scenes (contract fixtures run as scenes):
- f-25c4dd-cornered-starving: pass (NativePortalCampaignGameTests.badlyHurtAndStarvingWithAZombieOnItItStopsItsClimbAndFights)

Baseline: 084dde5 over 6 runs.

Flagged: passing on the baseline, now failing in 2 or more of 3 run(s): 1 — e40a17-7bc5b42-707-sol-48263
Confirmed (blocks): 0
Cleared: 1 — e40a17-7bc5b42-707-sol-48263 (branch failed 0 of 3, main passed 2 of 3)
Gate: passes
Newly passing against the baseline: 19 — e40a13-fe2ba3b-707-sol-48414, e40a14-80b7150-101-echo-5557, e40a14-80b7150-505-echo-8422, e40a14-80b7150-8675309-kindred-128400, e40a15-dbf4e5c-8675309-echo-87839, e40a15-dbf4e5c-8675309-kindred-52281, e40a16-922f4fd-505-kindred-77615, e40a16-922f4fd-505-sol-8079, e40a17-7bc5b42-404-echo-42702, e40a17-7bc5b42-505-sol-120198, e40a17-7bc5b42-505-sol-66039, f29e-on-1166122638-sol-63903, f29e-on-1530142077-echo-83228, f29e-on-4h-31102026-sol-216356, f29e-on-4h-8675309-echo-88079, f29e-on-779795571-kindred-47186, f29e-on-891567637-kindred-23384, f29e-on-891567637-kindred-93075, factions-110-d5057a4-110-echo-227579
