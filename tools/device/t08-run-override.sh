#!/system/bin/sh
# Run the T08 patched harness (sampler override capable) against build6's library so the
# temperature==0 greedy+penalty extraction path can be exercised.
# Usage: sh t08-run-override.sh <mode>
DR=/data/local/tmp/mnn-harness
MODE="$1"
LIBS="$DR/libs6/lib/arm64-v8a"
OUT="$DR/out6"
mkdir -p "$OUT"
cd "$DR" || exit 9
LD_LIBRARY_PATH="$LIBS" dalvikvm -cp "$DR/t08-harness.dex" mca.MnnRealModelHarness \
    "$LIBS" "$DR/models/qwen35-2b-mnn" "$OUT" "$MODE" > "$OUT/override-$MODE.log" 2>&1
echo "EXIT=$?"
