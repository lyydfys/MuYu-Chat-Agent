#!/system/bin/sh
# Confirm WHY the alternating soak saw chat=409 after an image round, and whether a chat-only
# load recovers. Reads the error body instead of discarding it (the previous soak logged only the
# status code, which is why the cause was invisible).
DR=/data/local/tmp/mnn-harness
OUT="$DR/out6"
KEY=$(run-as com.muyuchat.mca cat /data/data/com.muyuchat.mca/shared_prefs/mca_api.xml 2>/dev/null | grep -o 'name="api_key">[^<]*' | sed 's/name="api_key">//')
if [ -z "$KEY" ]; then echo "KEY_UNAVAILABLE"; exit 8; fi
BODY='{"model":"Huihui-Qwen3.5-2B-abliterated.Q4_K_M","messages":[{"role":"user","content":"hi"}],"max_tokens":4,"stream":false}'
echo "--- chat right after the image round (expect the eviction reason) ---"
curl -s -m 120 -X POST -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
     -d "$BODY" -w "|HTTP=%{http_code}" http://127.0.0.1:11435/v1/chat/completions
echo ""
echo "--- engine lifecycle via /v1/models ---"
curl -s -m 60 -H "Authorization: Bearer $KEY" http://127.0.0.1:11435/v1/models \
  | tr ',' '\n' | grep -E '"engineLifecycle"|"loaded"|"reloadRequired"' | head -6
