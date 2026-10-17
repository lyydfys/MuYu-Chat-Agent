#!/system/bin/sh
# T25 multi-process sampler.
#
# WHY: the previous soak fixed on the main app PID only, so "main process stays flat" was wrongly
# generalised to "the native system does not leak". This sampler enumerates EVERY process of the
# package (main + actual worker processes), samples each INDEPENDENTLY, and records PID changes so
# restarts are visible.
#
# FD: a plain `shell` user gets 0 entries from another UID's /proc/<pid>/fd (that was the invalid
# sampling recorded earlier). `run-as` switches to the app UID and CAN read its own processes' fd
# directories. This sampler marks FD INVALID (rather than 0) when it cannot read them.
#
# Usage: sh t25-multiproc-sample.sh <minutes> <tag>
DR=/data/local/tmp/t25
OUT="$DR/evidence"
mkdir -p "$OUT"
MIN=${1:-10}
TAG="${2:-multiproc}"
LOG="$OUT/$TAG.log"
: > "$LOG"
PKG=com.muyuchat.mca

# NOTE: mksh does not expand a '|' pattern inside ${var%%|*}, which silently produced empty
# role/pid fields in the first version of this sampler. Enumerate PIDs and look up each name with
# ps instead of building a custom separator string.
pids() { ps -A -o PID,ARGS | grep "$PKG" | grep -v grep | grep -v 'ps -A' | tr -s ' ' | cut -d' ' -f1; }
name_of() { ps -p "$1" -o ARGS 2>/dev/null | tail -n +2 | tr -d ' '; }

# read a value from /proc/<pid>/status for a given process
stat_of() { grep -E "^$2:" /proc/$1/status 2>/dev/null | tr -s ' \t' ' ' | cut -d' ' -f2; }

# FD count via run-as (app UID can read its own processes); echoes "INVALID" if unavailable
fd_count() {
  n=$(run-as $PKG ls /proc/$1/fd 2>/dev/null | wc -l)
  if [ "$n" -gt 0 ] 2>/dev/null; then echo "$n"; else echo "INVALID"; fi
}

echo "MULTIPROC_START utc=$(date -u +%Y-%m-%dT%H:%M:%SZ) minutes=$MIN pkg=$PKG" >> "$LOG"

# PID history to detect restarts: remembers the PID seen for each role
PREV_ROLE_PID=""
end=$(( $(date +%s) + MIN * 60 ))
tick=0
while [ "$(date +%s)" -lt "$end" ]; do
  tick=$((tick+1))
  ROLES=""
  for P in $(pids); do
    N=$(name_of "$P")
    VM=$(stat_of "$P" VmRSS)
    FD=$(fd_count "$P")
    TH=$(ls /proc/$P/task 2>/dev/null | wc -l)
    ROLES="$ROLES $N=$P"
    echo "SAMPLE utc=$(date -u +%Y-%m-%dT%H:%M:%SZ) tick=$tick role=$N pid=$P vmrssKb=${VM:-NA} threads=$TH fds=$FD" >> "$LOG"
  done
  echo "TICK $tick roles:$ROLES" >> "$LOG"
  # restart detection: compare this tick's role->pid map with the previous tick's
  if [ -n "$PREV_ROLE_PID" ] && [ "$PREV_ROLE_PID" != "$ROLES" ]; then
    echo "PROCESS_SET_CHANGE utc=$(date -u +%Y-%m-%dT%H:%M:%SZ) prev:$PREV_ROLE_PID now:$ROLES" >> "$LOG"
  fi
  PREV_ROLE_PID="$ROLES"
  # host-level totals
  echo "HOST utc=$(date -u +%Y-%m-%dT%H:%M:%SZ) memAvailKb=$(grep MemAvailable /proc/meminfo | tr -s ' ' | cut -d' ' -f2) swapFreeKb=$(grep SwapFree /proc/meminfo | tr -s ' ' | cut -d' ' -f2)" >> "$LOG"
  sleep 15
done
echo "MULTIPROC_END utc=$(date -u +%Y-%m-%dT%H:%M:%SZ) ticks=$tick" >> "$LOG"
