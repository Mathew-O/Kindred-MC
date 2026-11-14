#!/usr/bin/env bash
# The failure corpus's merge gate (F-27a): run the corpus three times on this checkout, flag any scene that passed in
# tools/corpus/baseline.json (the last runs on main, with their sha) and now fails in two or more of the three, then
# confirm each flagged scene: run alone three times here and three times on main as this branch has it (its merge-base
# with origin/main, so merges that land on main while the gate runs are not counted against it). A flagged scene blocks only when
# it fails two or more of three here and passes two or more of three on main; otherwise it is cleared, and the report
# says which. This keeps a stale baseline, or a scene that varies from run to run, from blocking a change.
#
# usage: tools/corpus/gate.sh [--main-ref REF] [RUNS]      (RUNS defaults to 3; the report and results go to build/corpus-gate/)
#   --main-ref REF       confirm on REF instead of the merge-base (origin/main, say, for main's tip)
#   --print-confirm-ref  print the commit the confirm would run on, and stop (for tools/corpus/test_gate.py)
#   --empty-world-only   empty this checkout's corpus world as before every run, say what it held, and stop (for the test)
# Every run starts from an empty corpus world (build/corpus/world and build/corpus/kindred): one grown over many runs
# played timing-sensitive scenes differently, and the branch's confirm ran in its grown world while main's ran fresh.
# Each flagged scene is confirmed alone: a run of its own (KINDRED_CORPUS_ONLY set to its id only), never in a batch with
# the other flagged scenes. Scenes in one batch share one world clock, weather and difficulty, set by the last of them to
# start, so a scene confirmed beside another could pass or fail by that one's clock: e40a20-79a9aad-31102026-echo-102580
# passed 3 of 3 beside a dusk scene (12119) and 0 of 3 alone on the same build (claude/corpus-data-dc5ad4d-1114).
# Exit 0 when the gate passes, 1 when a confirmed scene blocks, 2 when a run could not be made.
# About 40 minutes a full run on a 4-core container (measured 40 to 41 over six runs on 16cb7a2, at most ten scenes to a
# batch, each batch kept to its scenes' own day and night), so about 2 hours for three. The confirm is N flagged scenes
# x 3 x 2 (here and main) server starts of one scene each, 34 to 40 seconds apiece, so about 4 minutes a flagged scene.
# Run it with nothing else on the cores: the scenes are timing-sensitive.
set -uo pipefail
cd "$(dirname "$0")/../.."
here=$(pwd)
main_ref="" print_ref=0 empty_only=0 runs=3
while [ $# -gt 0 ]; do
  case "$1" in
    --main-ref) main_ref="${2:?--main-ref needs a ref}"; shift 2 ;;
    --print-confirm-ref) print_ref=1; shift ;;
    --empty-world-only) empty_only=1; shift ;;
    *) runs="$1"; shift ;;
  esac
done

# The commit the confirm runs main's side on: by default the merge-base of this checkout with origin/main, which stays
# put however far main moves during the gate's runs; with --main-ref, that ref. Its label goes into the report.
confirm_ref() {
  if [ -n "$main_ref" ]; then git rev-parse --verify -q "$main_ref^{commit}"; else git merge-base HEAD origin/main; fi
}
confirm_label() { if [ -n "$main_ref" ]; then echo "$main_ref"; else echo "merge-base with origin/main"; fi; }
if [ "$print_ref" = 1 ]; then git fetch -q origin main || exit 2; confirm_ref || exit 2; exit 0; fi
out="$here/build/corpus-gate"
mkdir -p "$out"

# The corpus world in the checkout $1 emptied before a run; what it held (in MB) goes to stdout and to $out/worlds.txt,
# labelled $2, and into the report.
empty_world() {
  local run="$1/companion-server-adapter/build/corpus" label=$2 mb
  mb=$(du -sb "$run/world" "$run/kindred" 2>/dev/null | awk '{s += $1} END {print int(s / 1048576)}')
  rm -rf "$run/world" "$run/kindred"
  echo "$label: corpus world held ${mb} MB; emptied" | tee -a "$out/worlds.txt"
}
if [ "$empty_only" = 1 ]; then empty_world "$here" "empty-world-only"; exit 0; fi
: > "$out/worlds.txt"

# One runCorpus in the checkout $1, its results copied to $2; downloads refused by a registry are tried again.
corpus() {
  local tree=$1 dest=$2 log=${2%.xml}.log
  for attempt in 1 2 3; do
    empty_world "$tree" "$(basename "$dest" .xml)"
    rm -f "$tree/companion-server-adapter/build/corpus-results.xml"
    (cd "$tree" && ./gradlew --max-workers=2 :companion-server-adapter:runCorpus) > "$log" 2>&1
    if [ -f "$tree/companion-server-adapter/build/corpus-results.xml" ] && grep -q "GAME TESTS COMPLETE" "$log"; then
      cp "$tree/companion-server-adapter/build/corpus-results.xml" "$dest"; return 0
    fi
    grep -q -E "429|Could not (GET|download|resolve)" "$log" && { sleep $((attempt * 60)); continue; }
    break
  done
  echo "A run could not be made; see $log" >&2
  return 1
}

results=()
for i in $(seq 1 "$runs"); do
  corpus "$here" "$out/results-$i.xml" || exit 2
  results+=("$out/results-$i.xml")
done
sha=$(git rev-parse --short HEAD)
logs=("${results[@]/%.xml/.log}")
rm -f "$out/report.md" "$out/flagged.txt"
python3 tools/corpus/report.py "${results[@]}" --sha "$sha" --logs "${logs[@]}" --flagged-out "$out/flagged.txt" --out "$out/report.md" > /dev/null
# A report that could not be made is no pass: with no report there is no list of flagged scenes either.
[ -f "$out/report.md" ] || { echo "The report could not be made; see the runs in $out" >&2; exit 2; }
if [ ! -s "$out/flagged.txt" ]; then
  { echo; echo "Corpus world before each run (emptied every time):"; sed 's/^/- /' "$out/worlds.txt"; } >> "$out/report.md"
  cat "$out/report.md"; exit 0
fi

# Confirm: each flagged scene alone, in a run of its own, three times here and three times on main as this branch has it
# (confirm_ref). Round i's runs of every flagged scene are put together in confirm-<side>-i.xml for the report; a scene's
# own run is confirm-<side>-i.<n>.xml, n its line in flagged.txt.
mapfile -t flagged < "$out/flagged.txt"
confirm() {
  local tree=$1 side=$2 i n parts
  for i in 1 2 3; do
    parts=()
    for n in "${!flagged[@]}"; do
      KINDRED_CORPUS_ONLY=${flagged[$n]} corpus "$tree" "$out/confirm-$side-$i.$((n + 1)).xml" || return 1
      parts+=("$out/confirm-$side-$i.$((n + 1)).xml")
    done
    python3 - "$out/confirm-$side-$i.xml" "${parts[@]}" <<'PY' || return 1
import sys
import xml.etree.ElementTree as ET
suite = ET.Element("testsuite")
for part in sys.argv[2:]:
    suite.extend(ET.parse(part).getroot().iter("testcase"))
ET.ElementTree(suite).write(sys.argv[1], encoding="utf-8")
PY
  done
}
branch=() main=()
confirm "$here" branch || exit 2
for i in 1 2 3; do branch+=("$out/confirm-branch-$i.xml"); done
git fetch -q origin main || exit 2
tree="$here/build/corpus-gate-main"
rm -rf "$tree"; git worktree prune
ref=$(confirm_ref) || { echo "No commit to confirm on (${main_ref:-merge-base with origin/main})" >&2; exit 2; }
git worktree add -q --detach "$tree" "$ref" || exit 2
confirm "$tree" main || exit 2
for i in 1 2 3; do main+=("$out/confirm-main-$i.xml"); done
mainsha=$(git -C "$tree" rev-parse --short HEAD)
git worktree remove --force "$tree"
python3 tools/corpus/report.py "${results[@]}" --sha "$sha (main $mainsha, the $(confirm_label))" --logs "${logs[@]}" --confirm-branch "${branch[@]}" --confirm-main "${main[@]}" --out "$out/report.md"
status=$?
{ echo; echo "Corpus world before each run (emptied every time):"; sed 's/^/- /' "$out/worlds.txt"; } >> "$out/report.md"
exit $status
