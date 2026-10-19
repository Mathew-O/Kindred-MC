# AI-player development operator guide
[Contract](../minecraft_player_spec.md) · [Tracker](feature-spec.md) · [Manifest](../release-manifest.json)

Use a disposable world and back up native saves together with Kindred SQLite metadata. This build is not qualified for unattended production survival.

Use the pinned Java 21/Fabric stack and server JAR. Ordinary clients remain the target; live compatibility requires evidence. No model key or external bot account is needed for current controls.

Use /aiplayer for command help; /companion redirects to the same tree. Creation, status, follow/stay/stop/resume, setup, movement, gather/deposit fixtures, personality and journal retain their existing restrictions. Commands do not imply planned skills are enabled.

Explicit development native bodies are available:
```text
/aiplayer native create Ada
/aiplayer native create Ben
/aiplayer native create Cy
/aiplayer native list
/aiplayer native status|stop|resume|connect|disconnect <UUID>
/aiplayer native wait <1..4 seconds> <UUID>
/aiplayer native eat <0..8 hotbar slot> <UUID>
/aiplayer native walk <north|south|east|west> <1..3 blocks> <UUID>
/aiplayer native equip <0..35 inventory slot> <UUID>
/aiplayer native patch <from x y z> <to x y z> <resource block ID> <UUID>
/aiplayer native mine <target x y z> <0..8 hotbar slot> <UUID>
```
Only operators create them, on solid ground in an already loaded controlled area. Controllers/operators may use lifecycle controls. The cap is three, including pending/incomplete records. New bodies are established after verifying a native player-file receipt. Disconnect/shutdown preserve native items and stop. Restart leaves them offline/stopped; connect explicitly while the saved area is loaded, then resume explicitly. Resume enables no autonomous work yet.

Wait/eat are explicit bounded development tasks. A request reserves at most five seconds through the existing activity writer before acquiring a lease. Eating uses one actual owned hotbar food with native timing, requires hunger, rejects chorus fruit and never retries an uncertain outcome. Stop clears use immediately. A failed/expired allowance starts no work; status distinguishes charged and observed milliseconds. Settlement must finish before another request; shutdown settles accounting without additional game ticks. No automatic renewal or unattended work is admitted. Walking is limited to one short flat loaded corridor, checked again during movement; unsafe or changed routes block. No jumping, digging or general pathfinding.

Equip moves one owned armor/shield into an empty native equipment slot using ordinary inventory-handler rules. Slots 0..8 are the hotbar; 9..35 are main inventory. Occupied gear/cursor, invalid or changed stacks reject. Durable intent acknowledgements precede the transfer; exact items/components/counts and durable outcome verification precede reported completion. Pending action IO is flushed before store shutdown. Unknown outcomes block the native record without replay; preserve its actual native save for reconciliation. No borrowing, gear replacement or automatic equipment selection.

A resource-work patch is explicit bounded task scope: at most nine loaded chunks and 4096 blocks, in the native player dimension and world bounds. No home or owner-presence requirement. Changing it stops current/pending work immediately; after the save acknowledgement, resume explicitly. Status shows its bounds/resource. Configuration does not install mining or grant server edit permission.

Mine executes a single scoped log break using owned hotbar contents, native survival timing, and tool wear. It requires explicit server edit policy opt-in, active paid activity, an unexpired resource claim, and a matching work patch. A matching native break receipt completes the action; drop collection is not claimed. Stop cancels digging immediately; conflicting world changes block for reconciliation without replay.

Missing, foreign, dead, wrong-version or unverified state blocks for reconciliation; it is never replaced with an empty inventory. Blocked/incomplete records keep their slot until recovery/removal is implemented. Autonomous survival and portals remain disabled. Default /aiplayer create still uses the legacy body; no saves are converted. Full client/death/recovery qualification remains open.

Legacy owner/mode/scope fields preserve access and saves; they are not the new product interface. Native creation has its development cap; team management and legacy migration remain unfinished. Combat/duels reject until qualified native skills use the arbiter. Timezone changes reject until safe accounting transitions exist; spent activity is not reset.

Inference is optional and currently disconnected. Planned Claude/OpenAI keys/budgets belong in private operator configuration, never memory/logs/source. No Jev.

Report exact build, seed/difficulty, IDs, commands and native before/after evidence. Stop and snapshot ambiguous actions; do not recreate missing items.
