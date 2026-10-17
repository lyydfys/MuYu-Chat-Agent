#!/system/bin/sh
# T25 workload driver for multi-process sampling.
#
# Purpose: keep the real worker processes alive and busy so the sampler observes the ACTUAL process
# set (main + :local_chat + :local_image) rather than only the main process. Each phase is
# single-modality because image generation unloads the chat model by design; the cross-modal case
# with the production-UI reload step is handled separately.
#
# Usage: sh t25-workload.sh <rounds> <tag>
DR=/data/local/tmp/t25
OUT="$DR/evidence"
mkdir -p "$OUT"
ROUNDS=${1:-4}
TAG=${2:-workload}
LOG="$OUT/$TAG.log"
: > "$LOG"
say() { echo "$1" >> "$LOG"; }
KEY=$(run-as com.muyuchat.mca cat /data/data/com.muyuchat.mca/shared_prefs/mca_api.xml 2>/dev/null | grep -o 'name="api_key">[^<]*' | sed 's/name="api_key">//')
[ -z "$KEY" ] && { say "KEY_UNAVAILABLE"; exit 8; }
API=http://127.0.0.1:11435
say "WORKLOAD_START utc=$(date -u +%Y-%m-%dT%H:%M:%SZ) rounds=$ROUNDS"
r=1
while [ "$r" -le "$ROUNDS" ]; do
  # chat phase: spawns/uses the chat worker
  CB=$(printf '{"model":"Huihui-Qwen3.5-2B-abliterated.Q4_K_M","messages":[{"role":"user","content":"Reply with the word round%s."}],"max_tokens":8,"stream":false,"temperature":0.6}' "$r")
  CC=$(curl -s -m 300 -X POST -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
       -d "$CB" -o "$OUT/$TAG-chat-$r.json" -w "%{http_code}" "$API/v1/chat/completions")
  say "ROUND=$r chat_http=$CC"
  # image phase: spawns/uses the image worker (2 steps keeps each round short)
  IB=$(printf '{"model":"image:0fc35bfa-9cf3-4b18-955f-e05e8aee49b5","prompt":"a dot number %s","sampler":"euler_a","steps":2,"cfg_scale":1.0,"seed":%s,"size":"512x512","n":1,"response_format":"b64_json"}' "$r" "$r")
  IC=$(curl -s -m 900 -X POST -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
       -d "$IB" -o "$OUT/$TAG-img-$r.json" -w "%{http_code}" "$API/v1/images/generations")
  IBY=$(wc -c < "$OUT/$TAG-img-$r.json")
  say "ROUND=$r image_http=$IC bytes=$IBY"
  rm -f "$OUT/$TAG-img-$r.json" "$OUT/$TAG-chat-$r.json"
  r=$((r+1))
done
say "WORKLOAD_END utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
