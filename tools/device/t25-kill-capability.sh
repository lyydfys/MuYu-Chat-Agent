#!/system/bin/sh
# T25 probe: can the shell user kill an app process, and can run-as do it?
# This determines HOW worker-death can be injected at all. Without a real kill the "interrupted"
# request completes normally and a naive harness records a false PASS (my first worker-death run
# did exactly that: interrupted_image_http=200).
OUT=/data/local/tmp/t25/evidence
mkdir -p "$OUT"
LOG="$OUT/kill-capability.log"
: > "$LOG"

pid_of() { ps -A -o PID,ARGS | grep "$1" | grep -v grep | grep -v 'ps -A' | tr -s ' ' | cut -d' ' -f1 | head -1; }

CW=$(pid_of 'com.muyuchat.mca:local_chat')
echo "chat_worker_pid=$CW" >> "$LOG"

echo "--- attempt 1: shell kill -9 ---" >> "$LOG"
kill -9 "$CW" 2>>"$LOG"
echo "shell_kill_exit=$?" >> "$LOG"
sleep 2
echo "after_shell_kill=$(pid_of 'com.muyuchat.mca:local_chat')" >> "$LOG"

echo "--- attempt 2: run-as kill -9 (app UID signals its own process) ---" >> "$LOG"
run-as com.muyuchat.mca kill -9 "$CW" 2>>"$LOG"
echo "runas_kill_exit=$?" >> "$LOG"
sleep 2
echo "after_runas_kill=$(pid_of 'com.muyuchat.mca:local_chat')" >> "$LOG"

echo "--- attempt 3: am force-stop kills the whole package (not a worker death) ---" >> "$LOG"
echo "note=force-stop is package-level; recorded only for completeness" >> "$LOG"
echo "DONE" >> "$LOG"
cat "$LOG"
