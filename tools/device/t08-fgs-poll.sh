#!/system/bin/sh
# FGS observation: poll `dumpsys activity services` while a generation is in flight and record every
# MCA ServiceRecord's foregroundId. `dumpsys notification` does NOT list these FGS notifications on
# this device, so the ServiceRecord fields are the only reliable enumeration path.
#
# Usage: sh t08-fgs-poll.sh <seconds> <tag>
DR=/data/local/tmp/mnn-harness
SECS=${1:-40}
TAG=${2:-fgs}
OUT="$DR/out6"
mkdir -p "$OUT"
LOG="$OUT/$TAG-fgs.log"
: > "$LOG"
i=0
while [ "$i" -lt "$SECS" ]; do
  dumpsys activity services com.muyuchat.mca 2>/dev/null \
    | grep -E 'ServiceRecord\{.*com\.muyuchat\.mca|isForeground=|foregroundId=' \
    | tr -d '\r' >> "$LOG"
  echo "--- tick=$i ---" >> "$LOG"
  i=$((i + 1))
  sleep 1
done
echo "FGS_POLL_DONE ticks=$i" >> "$LOG"
