#!/system/bin/sh
# T25 cross-modal loop WITH the production-UI reload step preserved.
#
# The audit explicitly forbids "covering" cross-modal behaviour by deleting the interleaving: image
# generation unloads the chat model by design, so the reload step is PART of the product behaviour
# and must be kept. This loop therefore does, per round:
#     image generation  ->  (expect chat to be unloaded)  ->  reload via production UI  ->  chat
# and records per round: image http, chat-after-image http + code, whether a reload was needed,
# chat-after-reload http, and engineLifecycle. The reload itself is driven from the host (UI taps);
# this script only performs the API parts and writes a machine-readable line per round.
#
# Usage: sh t25-crossmodal.sh <rounds> <tag>
DR=/data/local/tmp/t25
OUT="$DR/evidence"
mkdir -p "$OUT"
ROUNDS=${1:-3}
TAG=${2:-crossmodal}
LOG="$OUT/$TAG.log"
: > "$LOG"
say() { echo "$1" >> "$LOG"; }
KEY=$(run-as com.muyuchat.mca cat /data/data/com.muyuchat.mca/shared_prefs/mca_api.xml 2>/dev/null | grep -o 'name="api_key">[^<]*' | sed 's/name="api_key">//')
[ -z "$KEY" ] && { say "KEY_UNAVAILABLE"; exit 8; }
API=http://127.0.0.1:11435
IMG='image:0fc35bfa-9cf3-4b18-955f-e05e8aee49b5'
CHAT='Huihui-Qwen3.5-2B-abliterated.Q4_K_M'
say "CROSSMODAL_START utc=$(date -u +%Y-%m-%dT%H:%M:%SZ) rounds=$ROUNDS"

r=1
while [ "$r" -le "$ROUNDS" ]; do
  # 1) chat BEFORE image (should work if the model is loaded)
  CB=$(printf '{"model":"%s","messages":[{"role":"user","content":"say pre%s"}],"max_tokens":6,"stream":false}' "$CHAT" "$r")
  C0=$(curl -s -m 300 -X POST -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" -d "$CB" \
       -o "$OUT/$TAG-pre.json" -w "%{http_code}" "$API/v1/chat/completions")
  K0=$(grep -o '"code":"[a-z_]*"' "$OUT/$TAG-pre.json" 2>/dev/null | head -1 | sed 's/.*:"//;s/"//')

  # 2) image generation (this UNLOADS the chat model by design)
  IB=$(printf '{"model":"%s","prompt":"a magenta dot %s","sampler":"euler_a","steps":2,"cfg_scale":1.0,"seed":%s,"size":"512x512","n":1,"response_format":"b64_json"}' "$IMG" "$r" "$r")
  CI=$(curl -s -m 900 -X POST -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" -d "$IB" \
       -o "$OUT/$TAG-img.json" -w "%{http_code}" "$API/v1/images/generations")
  IBY=$(wc -c < "$OUT/$TAG-img.json")
  rm -f "$OUT/$TAG-img.json"

  # 3) chat AFTER image but BEFORE reload -> expected to be rejected (model unloaded)
  C1=$(curl -s -m 120 -X POST -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" -d "$CB" \
       -o "$OUT/$TAG-postimg.json" -w "%{http_code}" "$API/v1/chat/completions")
  K1=$(grep -o '"code":"[a-z_]*"' "$OUT/$TAG-postimg.json" 2>/dev/null | head -1 | sed 's/.*:"//;s/"//')
  LC1=$(curl -s -m 30 -H "Authorization: Bearer $KEY" "$API/v1/models" | tr ',' '\n' | grep -m1 engineLifecycle | sed 's/.*:"//;s/"//')

  # reload marker: the host performs the production-UI reload between this and the next line
  say "ROUND=$r BEFORE_RELOAD image_http=$CI image_bytes=$IBY chat_after_image_http=$C1 code=$K1 lifecycle=$LC1"

  # signal the host that a reload is expected now
  echo "AWAIT_RELOAD r=$r" >> "$LOG"
  # wait for the host to write the reload-done marker (the reload itself is UI-driven)
  w=0
  while [ $w -lt 150 ]; do
    if [ -f "$OUT/$TAG-reload-$r.done" ]; then break; fi
    w=$((w+1)); sleep 2
  done
  if [ -f "$OUT/$TAG-reload-$r.done" ]; then
    RELOADED=$(cat "$OUT/$TAG-reload-$r.done")
    rm -f "$OUT/$TAG-reload-$r.done"
  else
    RELOADED="TIMEOUT"
  fi

  # 4) chat AFTER reload -> must succeed if the reload worked
  C2=$(curl -s -m 300 -X POST -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" -d "$CB" \
       -o "$OUT/$TAG-postreload.json" -w "%{http_code}" "$API/v1/chat/completions")
  K2=$(grep -o '"code":"[a-z_]*"' "$OUT/$TAG-postreload.json" 2>/dev/null | head -1 | sed 's/.*:"//;s/"//')
  LC2=$(curl -s -m 30 -H "Authorization: Bearer $KEY" "$API/v1/models" | tr ',' '\n' | grep -m1 engineLifecycle | sed 's/.*:"//;s/"//')
  say "ROUND=$r AFTER_RELOAD reload=$RELOADED chat_after_reload_http=$C2 code=$K2 lifecycle=$LC2"

  # if an orphaned generating state appeared, clear it (force-stop) and record that
  if [ "$C2" = "409" ] && [ "$K2" = "generation_in_progress" ]; then
    say "ROUND=$r ORPHAN_STATE_DETECTED (409 generation_in_progress after reload) -> force-stop to recover"
    am force-stop com.muyuchat.mca
    sleep 4
    am start -n com.muyuchat.mca/.MainActivity >/dev/null 2>&1
    sleep 8
    say "ROUND=$r recovered_by_force_stop"
  fi
  r=$((r+1))
done
say "CROSSMODAL_END utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
