#!/system/bin/sh
# Run T02's real-model harness against a given library dir, one mode per invocation.
# Logs go to $DR/out6/<mode>.log so the host can pull compact greps instead of streaming the
# multi-megabyte statsAfterGeneration JSON that each mode prints.
# Usage: sh t08-mnn-run.sh <libDirTag> <mode> [extra args...] [modelRoot]
#   The model root is only used for chat modes; image mode receives its bundle as an extra arg.
DR=/data/local/tmp/mnn-harness
TAG="$1"
MODE="$2"
shift 2
OUT="$DR/out6"
mkdir -p "$OUT"
cd "$DR" || exit 9
LIBS="$DR/$TAG/lib/arm64-v8a"
case "$MODE" in
    chat|chat-multiturn|chat-cancel|chat-vision)
        MODEL="$DR/models/qwen35-2b-mnn"
        if [ "$#" -gt 0 ]; then MODEL="$1"; fi
        LD_LIBRARY_PATH="$LIBS" dalvikvm -cp "$DR/mnn-realmodel-harness.jar" mca.MnnRealModelHarness \
            "$LIBS" "$MODEL" "$OUT" "$MODE" > "$OUT/$MODE.log" 2>&1
        ;;
    *)
        # image mode: <paramsJson> <outPng> <bundleDir>
        LD_LIBRARY_PATH="$LIBS" dalvikvm -cp "$DR/mnn-realmodel-harness.jar" mca.MnnRealModelHarness \
            "$LIBS" "$DR/models" "$OUT" "$MODE" "$@" > "$OUT/$MODE.log" 2>&1
        ;;
esac
echo "EXIT=$?"
