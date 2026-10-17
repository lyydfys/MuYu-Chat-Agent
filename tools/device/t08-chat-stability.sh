#!/system/bin/sh
# Stability: N sequential authenticated chat completions through the production Local API.
# Sequential on purpose (one in-flight generation at a time) to match the device's memory budget.
DR=/data/local/tmp/mnn-harness
N=${1:-30}
OUT="$DR/out6"
mkdir -p "$OUT"
cd "$OUT" || exit 9
KEY=$(run-as com.muyuchat.mca cat /data/data/com.muyuchat.mca/shared_prefs/mca_api.xml 2>/dev/null | grep -o 'name="api_key">[^<]*' | sed 's/name="api_key">//')
if [ -z "$KEY" ]; then echo "KEY_UNAVAILABLE"; exit 8; fi
i=1
ok=0
fail=0
while [ "$i" -le "$N" ]; do
  BODY=$(printf '{"model":"Huihui-Qwen3.5-2B-abliterated.Q4_K_M","messages":[{"role":"user","content":"Reply with just the number %s."}],"max_tokens":8,"stream":false,"temperature":0.6}' "$i")
  RESP=$(curl -s -m 300 -X POST -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
       -d "$BODY" -w "|HTTP=%{http_code}|T=%{time_total}" \
       http://127.0.0.1:11435/v1/chat/completions)
  CODE=$(echo "$RESP" | grep -o 'HTTP=[0-9]*' | tail -1 | sed 's/HTTP=//')
  TOK=$(echo "$RESP" | grep -o '"completion_tokens":[0-9]*' | tail -1 | sed 's/.*://')
  T=$(echo "$RESP" | grep -o 'T=[0-9.]*$' | sed 's/T=//')
  if [ "$CODE" = "200" ]; then ok=$((ok+1)); else fail=$((fail+1)); fi
  echo "REQ=$i http=$CODE completion_tokens=$TOK seconds=$T"
  i=$((i+1))
done
echo "CHAT_STABILITY_DONE total=$N ok=$ok fail=$fail"
