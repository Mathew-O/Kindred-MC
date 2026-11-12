#!/bin/bash
# Determinism experiment: main 5cfe873, seed 505, goal=nether, ticks=24000, earned.
set -u
cd /home/user/Kindred-MC
OUT=/home/user/Kindred-MC/run/eval
SC="goal=nether; ticks=24000"
log(){ echo "$(date -u +%H:%M:%S) $*"; }
log "arm1 r1 (builds 5cfe873)"
python3 tools/eval/run.py --ref 5cfe873f --name det-a1-r1 --seeds 505 --scenario "$SC" --parallel 1 --out $OUT; log "arm1 r1 exit $?"
SHA=$(git rev-parse --short=10 5cfe873f)
JAR=$(ls $OUT/src/$SHA/companion-server-adapter/build/libs/kindred-mc-*.jar | grep -v -e -sources -e -dev | tail -1)
log "jar $JAR $(sha256sum $JAR | cut -c1-16)"
for r in 2 3; do python3 tools/eval/run.py --jar $JAR --name det-a1-r$r --seeds 505 --scenario "$SC" --parallel 1 --out $OUT; log "arm1 r$r exit $?"; done
log "arm2 start (3 at once)"
for r in 1 2 3; do python3 tools/eval/run.py --jar $JAR --name det-a2-r$r --seeds 505 --scenario "$SC" --parallel 1 --out $OUT > /tmp/claude-0/-home-user-Kindred-MC/18aa727d-695b-578d-a25a-ebede19d41be/scratchpad/det-a2-r$r.out 2>&1 & done
wait; log "arm2 done"
log "ALL DONE"
