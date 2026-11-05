# F-25c4bq — Treading water beside a source, the bucket is filled there

Date: 2026-11-05
Agent / branch: Claude / claude/e40-ladder-11-1105 (E-40's ladder)
Build: on top of `c9de40f` (batch 10, merged); recorded in [validation](../validation.md)
Status: complete (native). Its effect on the ten seeds is in E-40's next evaluation.

## Scope and implementation

User-visible behaviour: a player that wants a bucket of water, and is swimming beside a water source with breath to
spare, fills its bucket where it swims, as a player does, before it heads for dry ground.

Before, a bucket begun in deep water was cancelled at once ("In deep water; up for air first"), whatever its breath,
and the player in deep water was sent to dry ground before anything the plan wanted. Where the way to the water ended in
deep water, as at a lake whose beach drops straight into it, the player walked in, was sent ashore, and walked back in.

Found: E-40's iteration 8 (`e40a8-aed3e82`), seed 303, Sol, near the run's end. At a lake whose gravel beach dropped
into water three deep, in about four game minutes the fill was chosen 255 times and begun 13, each begun cancelled for
air, with 72 walks ashore; no bucket was filled there.

Changed modules/files:

- `companion-server-adapter`: `PortalRunCampaign`:
  - `fillFromTheWater`: in deep water with two thirds of its breath or more, an empty bucket and no bucket of water, no
    obsidian or generator site of its own, and the plan wanting water, the fill of a source in reach is given before the
    way out of the water. As F-25c4as does for a log.
  - `stopFor`: in deep water, a bucket being filled is not stopped for air while breath lasts, as a climb out, a walk
    and a block gathered are not.
- gametest: `NativeBucketGameTests.treadingWaterBesideASourceItFillsItsBucketThereRatherThanBeSentAshore`.

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| Afloat in a well it cannot climb out of, an empty bucket and breath to spare: a bucket of water within 30 seconds | The fixture, staged: a well of obsidian three sources across and three deep, its walls three above the water; the player put in its middle; daylight; its stock as though gathered, its chestplate worn | On `c9de40f`: fails 3 of 3, its bucket still empty at 30 seconds, full breath, the goal at "Climb out of the water: to the wall". With the fix: passes 3 of 3. |
| The class's other fixtures (F-25c4bl, F-25c4bm) | `NativeBucketGameTests` | Pass in all six runs, before and after |

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions. Staged, as the fixture says.

## Remaining limitations and handoff

- Short of breath, the way out still comes first, as before.
- The walk to water still goes to the water itself, not to a stand on dry ground within reach of it.
