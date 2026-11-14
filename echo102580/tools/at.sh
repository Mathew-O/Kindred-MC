#!/usr/bin/env bash
# at.sh SHA... : the scene alone x3 on each sha, in its own worktree
S=/tmp/claude-0/-home-user-Kindred-MC/18aa727d-695b-578d-a25a-ebede19d41be/scratchpad; OUT=$S/echo102580; ID=e40a20-79a9aad-31102026-echo-102580
for sha in "$@"; do
  t=${sha:0:7}; [ -d $S/wt-$t ] || git -C /home/user/Kindred-MC worktree add -q --detach $S/wt-$t $sha
  cd $S/wt-$t
  for i in 1 2 3; do for attempt in 1 2 3; do
    rm -rf companion-server-adapter/build/corpus/{world,kindred} companion-server-adapter/build/corpus-results.xml
    KINDRED_CORPUS_ONLY=$ID ./gradlew --max-workers=2 :companion-server-adapter:runCorpus > $OUT/alone-$t-$i.log 2>&1
    if [ -f companion-server-adapter/build/corpus-results.xml ] && grep -q "GAME TESTS COMPLETE" $OUT/alone-$t-$i.log; then
      cp companion-server-adapter/build/corpus-results.xml $OUT/alone-$t-$i.xml; echo "$(date -u +%T) alone-$t-$i done" >> $OUT/progress.log; break; fi
    echo "$(date -u +%T) alone-$t-$i attempt $attempt failed" >> $OUT/progress.log; sleep 60
  done; done
done
echo "$(date -u +%T) AT DONE $*" >> $OUT/progress.log
