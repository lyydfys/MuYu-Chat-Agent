#!/system/bin/sh
# Inventory what the APP ITSELF offers on this device: installed models with their runtime,
# task type, family and key metadata. Read through the authenticated Local API (the app's own
# catalog), not by guessing from the filesystem.
DR=/data/local/tmp/t25
OUT=/data/local/tmp/t25/evidence
mkdir -p "$OUT"
KEY=$(run-as com.muyuchat.mca cat /data/data/com.muyuchat.mca/shared_prefs/mca_api.xml 2>/dev/null | grep -o 'name="api_key">[^<]*' | sed 's/name="api_key">//')
[ -z "$KEY" ] && { echo "KEY_UNAVAILABLE"; exit 8; }
echo "=== /v1/models (app catalog) ==="
curl -s -m 60 -H "Authorization: Bearer $KEY" http://127.0.0.1:11435/v1/models -o "$OUT/models.json" -w "http=%{http_code} bytes=%{size_download}\n"
echo ""
echo "=== per-model summary (id | type | runtime | family | task | configured | loaded) ==="
tr '{' '\n' < "$OUT/models.json" | sed 's/^"data":\[//' | while read -r line; do
  ID=$(echo "$line" | grep -o '"id":"[^"]*"' | head -1 | sed 's/"id":"//;s/"//')
  [ -z "$ID" ] && continue
  TY=$(echo "$line" | grep -o '"type":"[^"]*"' | head -1 | sed 's/.*:"//;s/"//')
  RT=$(echo "$line" | grep -o '"runtime":"[^"]*"' | head -1 | sed 's/.*:"//;s/"//')
  FM=$(echo "$line" | grep -o '"family":"[^"]*"' | head -1 | sed 's/.*:"//;s/"//')
  TK=$(echo "$line" | grep -o '"task":"[^"]*"' | head -1 | sed 's/.*:"//;s/"//')
  CF=$(echo "$line" | grep -o '"configured":[a-z]*' | head -1 | sed 's/.*://')
  LD=$(echo "$line" | grep -o '"loaded":[a-z]*' | head -1 | sed 's/.*://')
  echo "  id=$ID"
  echo "      type=${TY:-chat} runtime=$RT family=${FM:--} task=${TK:--} configured=${CF:--} loaded=${LD:--}"
done
echo ""
echo "=== engine/runtime values actually present on device ==="
grep -o '"runtime":"[^"]*"' "$OUT/models.json" | sed 's/.*:"//;s/"//' | sort | uniq -c | sed 's/^/  /'
echo ""
echo "=== installed local model artifacts (files) ==="
ls -la /sdcard/Android/data/com.muyuchat.mca/files/models/ 2>/dev/null | head -12
echo "--- image_models ---"
ls -la /sdcard/Android/data/com.muyuchat.mca/files/image_models/ 2>/dev/null | head -12
