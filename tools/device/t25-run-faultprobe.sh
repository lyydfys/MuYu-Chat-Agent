#!/system/bin/sh
# T25 runner for T23's fixed fault probe (assertions now target declared branches, with a positive
# control). Runs the SAME dex against two library sets so build7 and the audit-fix APK can be
# compared without reinstalling either APK:
#   libs7 = extracted from the installed build7 APK earlier
#   libs8 = extracted from the installed audit-fix APK
# Usage: sh t25-run-faultprobe.sh <libsTag> <tag>
DR=/data/local/tmp/t25
OUT="$DR/evidence"
mkdir -p "$OUT"
TAG="$2"
LIBS="$DR/$1/lib/arm64-v8a"
cd "$DR" || exit 9
LD_LIBRARY_PATH="$LIBS" dalvikvm -cp "$DR/t25-faultprobe.dex" mca.MnnJniFaultProbe \
    "$LIBS" "$DR/empty-bundle" "$DR/faults" "$OUT" > "$OUT/$TAG.raw" 2>&1
echo "EXIT=$?"
