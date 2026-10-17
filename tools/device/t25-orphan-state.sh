#!/system/bin/sh
# T25 observation probe: is the app holding a "generating" state (and the 11438 foreground lease)
# while no work is actually happening?
#
# Context: after tapping the production UI's "regenerate" control, the busy gate began returning
# generation_in_progress while /v1/models reported engineLifecycle=ready. This probe separates the
# two possibilities:
#   (a) a real generation is still running  -> worker CPU ticks must CLIMB
#   (b) an orphaned state / pinned lease    -> worker CPU ticks stay FLAT and the gate stays busy
# A flat CPU counter plus a busy gate plus a foreground 11438 is evidence of an orphaned state.
OUT=/data/local/tmp/t25/evidence
mkdir -p "$OUT"
LOG="$OUT/orphan-state.log"
: > "$LOG"
say() { echo "$1" >> "$LOG"; }
KEY=$(run-as com.muyuchat.mca cat /data/data/com.muyuchat.mca/shared_prefs/mca_api.xml 2>/dev/null | grep -o 'name="api_key">[^<]*' | sed 's/name="api_key">//')

CW=$(ps -A -o PID,ARGS | grep 'com.muyuchat.mca:local_chat' | grep -v grep | tr -s ' ' | cut -d' ' -f1 | head -1)
MP=$(ps -A -o PID,ARGS | grep 'com.muyuchat.mca$' | grep -v grep | tr -s ' ' | cut -d' ' -f1 | head -1)
say "ORPHAN_PROBE_START utc=$(date -u +%Y-%m-%dT%H:%M:%SZ) main=$MP chat_worker=$CW"

i=1
while [ $i -le 10 ]; do
  CB='{"model":"Huihui-Qwen3.5-2B-abliterated.Q4_K_M","messages":[{"role":"user","content":"ping"}],"max_tokens":4,"stream":false}'
  CODE=$(curl -s -m 20 -X POST -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" -d "$CB" \
         -o "$OUT/_gate.json" -w "%{http_code}" http://127.0.0.1:11435/v1/chat/completions)
  GATE="$CODE:$(grep -o '\"code\":\"[a-z_]*\"' "$OUT/_gate.json" 2>/dev/null | head -1)"
  LC=$(curl -s -m 20 -H "Authorization: Bearer $KEY" http://127.0.0.1:11435/v1/models | tr ',' '\n' | grep -m1 engineLifecycle | sed 's/.*://')
  CWT=$(awk '{print $14+$15}' /proc/$CW/stat 2>/dev/null)
  MPT=$(awk '{print $14+$15}' /proc/$MP/stat 2>/dev/null)
  FG=$(dumpsys activity services com.muyuchat.mca 2>/dev/null | grep -A2 'McaGenerationForegroundService' | grep -o 'isForeground=[a-z]*' | head -1)
  ID=$(dumpsys activity services com.muyuchat.mca 2>/dev/null | grep -A2 'McaGenerationForegroundService' | grep -o 'foregroundId=[0-9]*' | head -1)
  say "SAMPLE t=$((i*15))s lifecycle=$LC chat_cpu=$CWT main_cpu=$MPT $FG $ID gate=$GATE"
  i=$((i+1)); sleep 15
done
say "ORPHAN_PROBE_END utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
cat "$LOG"
