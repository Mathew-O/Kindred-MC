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
# Exit 0 when the gate passes, 1 when a confirmed scene blocks, 2 when a run could not be made.
# About 25 minutes a full run on a 4-core container (ten scenes to a batch), so about 75 minutes for three, and a few
# minutes more for each confirming run. Run it with nothing else on the cores: the scenes are timing-sensitive.
set -uo pipefail
cd "$(dirname "$0")/../.."
here=$(pwd)
main_ref="" print_ref=0 runs=3
while [ $# -gt 0 ]; do
  case "$1" in
    --main-ref) main_ref="${2:?--main-ref needs a ref}"; shift 2 ;;
    --print-confirm-ref) print_ref=1; shift ;;
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

# One runCorpus in the checkout $1, its results copied to $2; downloads refused by a registry are tried again.
corpus() {
  local tree=$1 dest=$2 log=${2%.xml}.log
  for attempt in 1 2 3; do
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
python3 tools/corpus/report.py "${results[@]}" --sha "$sha" --logs "${logs[@]}" --flagged-out "$out/flagged.txt" --out "$out/report.md" > /dev/null
if [ ! -s "$out/flagged.txt" ]; then cat "$out/report.md"; exit 0; fi

# Confirm: the flagged scenes alone, three times here and three times on main as this branch has it (confirm_ref).
export KINDRED_CORPUS_ONLY=$(paste -sd, "$out/flagged.txt")
branch=() main=()
for i in 1 2 3; do corpus "$here" "$out/confirm-branch-$i.xml" || exit 2; branch+=("$out/confirm-branch-$i.xml"); done
git fetch -q origin main || exit 2
tree="$here/build/corpus-gate-main"
rm -rf "$tree"; git worktree prune
ref=$(confirm_ref) || { echo "No commit to confirm on (${main_ref:-merge-base with origin/main})" >&2; exit 2; }
git worktree add -q --detach "$tree" "$ref" || exit 2
for i in 1 2 3; do corpus "$tree" "$out/confirm-main-$i.xml" || exit 2; main+=("$out/confirm-main-$i.xml"); done
mainsha=$(git -C "$tree" rev-parse --short HEAD)
git worktree remove --force "$tree"
python3 tools/corpus/report.py "${results[@]}" --sha "$sha (main $mainsha, the $(confirm_label))" --logs "${logs[@]}" --confirm-branch "${branch[@]}" --confirm-main "${main[@]}" --out "$out/report.md"
