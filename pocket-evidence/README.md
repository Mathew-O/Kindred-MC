# 8675309 pocket (e40a20-79a9aad): a route and standability item for MOVEMENT

Scenes: the e40a20-79a9aad-8675309-kindred scenes 109060, 112700, 116320, 119960, 123600, 127220, 130860, 134460, 138100 and 141720.
- **Source:** branch claude/corpus-e40a20-1111 at 81f0d9b, worktree /home/user/kmc-a20.
- **Baseline:** each fails 0 of 6 at 79a9aad, "still in its stuck spell after 3600 ticks".
- **Where:** Y -50 to -52. Goal "Iron: Cast the portal frame from lava and water".

## Loop
"Tunnel past liquid toward lava: step down/advance", then "no way on from here – Climb to find another way: step up/aside", then "waiting for a feasible step". Every try-elsewhere fails with:

    No route from X,-50,Z ... toward X+24,-50,Z (expanded 3, standing true; the goal standable false, on Block{minecraft:air}, at Block{minecraft:stone}; ...)

- **What the line says:** the try-elsewhere target is 24 blocks off at the same Y, inside stone. The search expands 3 nodes: the pocket is closed, and no walking route leaves it.
- **Then:** "climb away from here: step up/aside" goes back into the same cells.
- **Counts,** in each run of 10 scenes: 10 "No route", all "goal standable false"; 20–22 try-elsewheres; 33–37 "waiting for a feasible step".

## What was tried (PortalRunCampaign, uncommitted, all reverted; 10 scenes ×3 each, all 30 runs failed each time)
1. **Rung 1, dead-end floor.** Once it climbed "to find another way", the cell and its open neighbours were blocked from tunnel()'s step-down for 2,400 ticks. Counter: 4–12 dead ends after a tunnel past liquid per logged spell (2 once).
2. **Rung 2, K=3.** "Tunnel past liquid toward" was set aside within 16 blocks for the rest of the spell. It fired in 29 of 30. The loop moved to "Dig down toward lava: step down".
3. **(a), the dead-end floor at digDown's landing checks as well.** Steps after the set-aside: climb 61–90, dig down 16–35, tunnel 17–29.
4. **(b), all of descendOrTunnel toward lava set aside within 16 for the rest of the spell.** Steps afterwards: climb 28–46 (one run: 161 "Climb toward the surface for food"), tunnel 14–85 (some from other players whose set-aside hadn't fired), dig down 11.

## Reading
- **Not the cause:** the step the goal plan picks.
- **The cause:** try-elsewhere and climb-away pick walking targets in solid stone, beyond a closed pocket, and dig nothing toward them.
- **Diagnosis copies:** stuckLevel reset and no memory, and each still failed 3 of 3. So the trap is the ground and the state, not the escalation level.

Patch of the last attempt (rung 1 + rung 2 + b), for reference: tunnel-rungs-b.patch (here; applies to main 0476ee5)
Logs: tun1-1.log (rung 1 alone) and tunB-1.log (rung 1 + rung 2 + b), one run of all 10 scenes each. Not for merging: a branch to read from.
