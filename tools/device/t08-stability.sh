#!/system/bin/sh
# MNN stability loop: repeated loadModel -> generate -> unloadModel cycles in separate JVM
# invocations, so a leak in one cycle cannot be masked by a warm process (the harness runs one
# process per invocation and exits).
DR=/data/local/tmp/mnn-harness
N=${1:-10}
TAG=${2:-libs7}
OUT="$DR/out6"
mkdir -p "$OUT"
LIBS="$DR/$TAG/lib/arm64-v8a"
i=1
fail=0
while [ "$i" -le "$N" ]; do
  cd "$DR" || exit 9
  LD_LIBRARY_PATH="$LIBS" dalvikvm -cp "$DR/mnn-realmodel-harness.jar" mca.MnnRealModelHarness \
      "$LIBS" "$DR/models/qwen35-2b-mnn" "$OUT" chat > "$OUT/stab-$i.log" 2>&1
  rc=$?
  f=$(grep -o 'REAL|summary|failures=[0-9]*' "$OUT/stab-$i.log" | tail -1 | sed 's/.*failures=//')
  ld=$(grep -o 'REAL|loadModel=|[-0-9]*' "$OUT/stab-$i.log" | tail -1 | sed 's/.*=//')
  bc=$(grep -o 'REAL|beginCompletion=|[-0-9]*' "$OUT/stab-$i.log" | tail -1 | sed 's/.*=//')
  ch=$(grep -o 'REAL|chunks=chunks=[0-9]*' "$OUT/stab-$i.log" | tail -1 | sed 's/.*=//')
  echo "CYCLE=$i exit=$rc load=$ld begin=$bc chunks=$ch failures=$f"
  if [ "$rc" != "0" ] || [ "$f" != "0" ]; then fail=$((fail + 1)); fi
  i=$((i + 1))
done
echo "STABILITY_DONE cycles=$N failed_cycles=$fail"
