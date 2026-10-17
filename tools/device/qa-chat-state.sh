#!/system/bin/sh
# Authoritative chat-engine state, read from the app's OWN Local API (not a guess, not a UI heuristic).
# Also reports the UI's transient status line separately so the two can be compared.
# Usage: sh qa-chat-state.sh [tag]
DR=/data/local/tmp/t25
OUT="$DR/evidence"
mkdir -p "$OUT"
KEY=$(run-as com.muyuchat.mca cat /data/data/com.muyuchat.mca/shared_prefs/mca_api.xml 2>/dev/null | grep -o 'name="api_key">[^<]*' | sed 's/name="api_key">//')
[ -z "$KEY" ] && { echo "KEY_UNAVAILABLE"; exit 8; }
echo "=== /v1/models: chat models and their load state ==="
curl -s -m 30 -H "Authorization: Bearer $KEY" http://127.0.0.1:11435/v1/models -o "$OUT/_m.json"
tr '{' '\n' < "$OUT/_m.json" | grep -E '"type":"chat"|engineLifecycle|"loaded"|"id"' | head -30 | sed 's/^/  /'
echo ""
echo "=== engine lifecycle (top-level) ==="
tr ',' '\n' < "$OUT/_m.json" | grep -E 'engineLifecycle|loaded|activeModel' | head -8 | sed 's/^/  /'
echo ""
echo "=== chat worker process(es) ==="
ps -A -o PID,ARGS | grep -E 'com.muyuchat.mca' | grep -v grep | tr -s ' ' | cut -d' ' -f1,2-3 | sed 's/^/  /'
