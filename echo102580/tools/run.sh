#!/usr/bin/env bash
# Scene e40a20-79a9aad-31102026-echo-102580 on dc5ad4d: alone x3, then with its full-run batch-mates x3.
S=/tmp/claude-0/-home-user-Kindred-MC/18aa727d-695b-578d-a25a-ebede19d41be/scratchpad
W=$S/wt-corpus-dc5; OUT=$S/echo102580; cd $W
ALONE=e40a20-79a9aad-31102026-echo-102580
BATCH=f29e-on-1394473968-echo-54530,diag-holepit-079bdc2-31102026-sol-126580,e40a20-79a9aad-31102026-echo-102580,f29e-on-707-echo-54683,f29e-on-4h-8675309-sol-222846,e40a20-79a9aad-20261030-kindred-126860,e40a14-80b7150-31102026-sol-78892,diag-factions303-079bdc2-303-sol-102920,diag-holepit-079bdc2-31102026-sol-6920,e40c1-eed2dc5-20261030-kindred-102990
one() { # $1 label, $2 only-list
  for attempt in 1 2 3; do
    rm -rf companion-server-adapter/build/corpus/{world,kindred} companion-server-adapter/build/corpus-results.xml
    KINDRED_CORPUS_ONLY=$2 ./gradlew --max-workers=2 :companion-server-adapter:runCorpus > $OUT/$1.log 2>&1
    if [ -f companion-server-adapter/build/corpus-results.xml ] && grep -q "GAME TESTS COMPLETE" $OUT/$1.log; then
      cp companion-server-adapter/build/corpus-results.xml $OUT/$1.xml; echo "$(date -u +%T) $1 done" >> $OUT/progress.log; return; fi
    echo "$(date -u +%T) $1 attempt $attempt failed" >> $OUT/progress.log; sleep 60
  done
}
for i in 1 2 3; do one alone-$i $ALONE; done
for i in 1 2 3; do one batch-$i $BATCH; done
echo "$(date -u +%T) ALL DONE" >> $OUT/progress.log
