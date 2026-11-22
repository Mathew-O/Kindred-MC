#!/usr/bin/env bash
# F-22c112's native comparison, as stated to the lead: NativeBaseGameTests, NativeBunkerGameTests,
# NativeTradingHallGameTests and NativeCompoundGameTests; base (merge-base main's BuildRunner) and branch (the fix)
# alternating, base first, $1 pairs from pair $2 (so a run cut short can go on). The GameTest list and BuildRunner are put
# back as they were at the end.
set -uo pipefail
cd /home/user/Kindred-MC
S=${SCRATCH:?set SCRATCH to a folder holding BuildRunner.fixed.java and fmj3.bak}
B=companion-server-adapter/src/main/java/dev/kindred/fabric/BuildRunner.java
L=companion-server-adapter/src/gametest/resources/fabric.mod.json
pairs=${1:-9} from=${2:-1}
base=$(git merge-base HEAD origin/main)
cp "$S/BuildRunner.fixed.java" "$S/cmp-branch.java"
git show "$base:$B" > "$S/cmp-base.java"
cp "$S/fmj3.bak" "$L"
python3 - "$L" <<'PY'
import json, sys
keep = {"dev.kindred.fabric." + k for k in ("NativeBaseGameTests", "NativeBunkerGameTests", "NativeTradingHallGameTests", "NativeCompoundGameTests")}
d = json.load(open(sys.argv[1]))
for k, v in d["entrypoints"].items():
    if isinstance(v, list) and any(isinstance(x, str) and x.endswith("GameTests") for x in v):
        d["entrypoints"][k] = [x for x in v if not (isinstance(x, str) and x.endswith("GameTests")) or x in keep]
json.dump(d, open(sys.argv[1], "w"), indent=2)
PY
for p in $(seq "$from" "$pairs"); do
  for side in base branch; do
    cp "$S/cmp-$side.java" "$B"
    timeout 3000 ./gradlew -q :companion-server-adapter:runGametest > "$S/cmp-$side-$p.log" 2>&1
    echo "pair $p $side exit $? $(grep -o 'All [0-9]* required tests passed\|[0-9]* required tests failed' "$S/cmp-$side-$p.log" | head -1)"
  done
done
cp "$S/cmp-branch.java" "$B"
cp "$S/fmj3.bak" "$L"
git status --short
