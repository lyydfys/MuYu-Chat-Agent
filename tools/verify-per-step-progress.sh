#!/usr/bin/env bash
# Verifies that the engine reports one sampling-progress event per requested step.
#
# Why this exists: a 4-step request used to emit only `0/4` and `4/4` because the Wan sampler
# reports the denoise schedule (sigmas-1) and step-cache hits returned before the report. Patch
# 0015 normalises the denominator to the requested count and reports cached steps too; this script
# is the device check for that patch.
#
# Usage: bash tools/verify-per-step-progress.sh [device-serial] [steps]
# Exit: 0 = every step reported, 1 = fewer events than steps, 2 = invocation/device problem.
set -uo pipefail
export MSYS_NO_PATHCONV=1

ADB=${ADB:-/e/model/android-sdk/platform-tools/adb.exe}
SERIAL=${1:-device-b}
STEPS=${2:-4}
DIR=${DIR:-/data/local/tmp/hy98}
PROBE=${PROBE:-mca_wan_probe}
OUT=${OUT:-/data/local/tmp/hy98/pstep}

[ -n "$SERIAL" ] || { echo "FAIL: no device serial" >&2; exit 2; }
"$ADB" -s "$SERIAL" get-state >/dev/null 2>&1 || { echo "FAIL: device $SERIAL not ready" >&2; exit 2; }

LOG=$(mktemp)
trap 'rm -f "$LOG"' EXIT
"$ADB" -s "$SERIAL" shell \
    "cd $DIR && mkdir -p $OUT && MCA_WAN_DEBUG_PROGRESS=1 LD_LIBRARY_PATH=. ./$PROBE $OUT $STEPS 1 tae 4 1 2 plain 128 128" \
    > "$LOG" 2>&1
rc=$?
echo "probe rc=$rc"
grep -E "probe\] sample|probe\] progress phase=1|raw progress|engine rc=|sampling_calls|status=" "$LOG" | tail -20

# `[probe] sample step=N/M` is printed for every forwarded sampling event (the endpoint-only
# `progress phase=1` line hid the intermediate steps and made an earlier run look like a failure).
mapfile -t forwarded < <(grep -oE "probe\] sample step=[0-9]+/[0-9]+" "$LOG" | sort -u)
count=${#forwarded[@]}
echo "distinct sampling events forwarded: $count (${forwarded[*]:-none})"
grep -oE "sampling_calls=[0-9]+" "$LOG" | tail -1

if [ "$count" -ge "$STEPS" ]; then
    echo "PASS: $count events for a $STEPS-step request"
    exit 0
fi
echo "FAIL: expected >= $STEPS sampling events for a $STEPS-step request, got $count"
exit 1
