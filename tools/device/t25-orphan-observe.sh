#!/system/bin/sh
# T25 orphan-state observation sampler (controlled reproduction support).
# Samples the five signals that distinguish a real generation from an orphaned state, for N minutes.
# Usage: sh t25-orphan-observe.sh <minutes> <tag>
DR=/data/local/tmp/t25
OUT="$DR/evidence"
mkdir -p "$OUT"
MIN=${1:-3}
TAG=${2:-orphan}
LOG="$OUT/$TAG.log"
: > "$LOG"
say() { echo "$1" >> "$LOG"; }
KEY=$(run-as com.muyuchat.mca cat /data/data/com.muyuchat.mca/shared_prefs/mca_api.xml 2>/dev/null | grep -o 'name="api_key">[^<]*' | sed 's/name="api_key">//')
CB='{"model":"Huihui-Qwen3.5-2B-abliterated.Q4_K_M","messages":[{"role":"user","content":"ping"}],"max_tokens":4,"stream":false}'
pid_of() { ps -A -o PID,ARGS | grep "$1" | grep -v grep | grep -v 'ps -A' | tr -s ' ' | cut -d' ' -f1 | head -1; }

say "ORPHAN_OBSERVE_START tag=$TAG utc=$(date -u +%Y-%m-%dT%H:%M:%SZ) minutes=$MIN"
CW=$(pid_of 'com.muyuchat.mca:local_chat')
MP=$(pid_of 'com.muyuchat.mca$')
say "roles main=$MP chat_worker=$CW"
i=1
end=$(( $(date +%s) + MIN * 60 ))
while [ "$(date +%s)" -lt "$end" ]; do
  CODE=$(curl -s -m 20 -X POST -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" -d "$CB" \
         -o "$OUT/_o.json" -w "%{http_code}" http://127.0.0.1:11435/v1/chat/completions)
  CODEK=$(grep -o '"code":"[a-z_]*"' "$OUT/_o.json" 2>/dev/null | head -1 | sed 's/.*:"//;s/"//')
  DSTATE=$(grep -o '"state":"[a-z_]*"' "$OUT/_o.json" 2>/dev/null | head -1 | sed 's/.*:"//;s/"//')
  LC=$(curl -s -m 20 -H "Authorization: Bearer $KEY" http://127.0.0.1:11435/v1/models | tr ',' '\n' | grep -m1 engineLifecycle | sed 's/.*:"//;s/"//')
  FG=$(dumpsys activity services com.muyuchat.mca 2>/dev/null | grep -A2 'McaGenerationForegroundService' | grep -o 'isForeground=[a-z]*' | head -1 | sed 's/.*=//')
  ID=$(dumpsys activity services com.muyuchat.mca 2>/dev/null | grep -A2 'McaGenerationForegroundService' | grep -o 'foregroundId=[0-9]*' | head -1 | sed 's/.*=//')
  CWT=$(awk '{print $14+$15}' /proc/$CW/stat 2>/dev/null)
  MPT=$(awk '{print $14+$15}' /proc/$MP/stat 2>/dev/null)
  say "SAMPLE t=$((i*20))s http=$CODE code=$CODEK detailsState=$DSTATE lifecycle=$LC fgs11438=$FG/$ID chat_cpu=$CWT main_cpu=$MPT"
  i=$((i+1))
  sleep 20
done
say "ORPHAN_OBSERVE_END tag=$TAG utc=$(date -u +%Y-%m-%dT%H:%M:%SZ) samples=$((i-1))"
cat "$LOG"
