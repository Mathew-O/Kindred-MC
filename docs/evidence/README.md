# Feature completion evidence

Create `<feature-id>.md` here when implementing a tracked feature, or link another durable repository evidence document with equivalent detail. Preserve earlier results when adding new runs and identify which build each result applies to. Do not commit private chat, credentials, full player databases or bulky generated worlds; retain sanitized fixtures and give stable artifact locations/checksums for larger evidence.

The canonical checkbox lives in [the feature spec](../feature-spec.md). Completing this template does not itself prove the feature passes. A feature with an unmet acceptance criterion stays unchecked; report the remaining step in Active work. Product AT gates require the entire referenced scenario, not just a unit test with a similar name.

## Copyable record

```markdown
# F-XX — Feature name

Date:
Agent / branch:
Build: commit SHA if available; otherwise state uncommitted and identify the tested artifact/hash
Status: in progress / blocked / complete

## Scope and implementation

User-visible behavior:
Changed modules/files:
Dependencies satisfied:
Schema/configuration changes and migration coverage:
Requirements and AT portions covered:

## Acceptance evidence

| Criterion from feature card | Check or scenario | Result | Evidence / artifact |
| --- | --- | --- | --- |
| Copy each actual criterion | Exact reproduction or test | Pass / fail / not run | Retained reference |

Environment: OS, JDK, server/loader/mod versions, client variants, relevant settings and hardware
Commands executed:
Fixture/reset procedure and seed/layout IDs:
Expected result and observed result:
Report locations and artifact hashes:
For physical work: before/after native inventories, targets, action IDs and reconciled totals
For timing: sample count, measurement boundary, p50/p95/max, tick timing and transport treatment
For restart/crash: injection boundary, paired state evidence and reconciliation result

## Remaining limitations and handoff

Not tested / known failures:
Exact next action, if incomplete:
Updated operator/user documentation:
Manifest changes, if applicable:
Canonical checkbox updated only after all criteria pass:
```

For pure policy features, use deterministic boundary/failure fixtures. For native features, include GameTests and the live scenarios required by the card. For docs-only work, record link/ID/consistency checks and explicitly state that runtime tests were not rerun. Historical evidence is in [validation.md](../validation.md); do not present it as a new run.
