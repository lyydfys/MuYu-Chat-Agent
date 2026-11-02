#!/usr/bin/env bash
# Low-power integration proof: external engine + full Wan VAE profile + spatial tiling flags
# + non-push (file) encode path, at 128x128x5 (~3-4 min). Proves the App wiring end-to-end
# without the 480p memory/time budget. The 480p recipe itself is proven by the shell probe.
#
# Usage: bash tools/run-full-vae-128.sh [--check] [serial]
#   --check: run only the preflight and exit (battery floor is 15% here; the run is ~4 min).
set -uo pipefail
CHECK=0
if [ "${1:-}" = "--check" ]; then CHECK=1; shift; fi
SERIAL=${1:-device-b}
ADB=${ADB:-/e/model/android-sdk/platform-tools/adb.exe}
# The Local API key is per-install and shown in the app; never hardcode it in the repo.
KEY=${MCA_API_KEY:?export MCA_API_KEY with the key shown in MCA -> Local API}
EVID=build/evidence/v1-27/FULL-VAE-128
MIN_BATTERY=${MCA_MIN_BATTERY:-15}
MIN_FREE_GB=${MCA_MIN_FREE_GB:-3}
APK=app/build/outputs/apk/diagnostic/app-diagnostic.apk
mkdir -p "$EVID"
. "$(dirname "$0")/lib/device-preflight.sh"
. "$(dirname "$0")/lib/video-artifact.sh"
if [ "$CHECK" = "1" ]; then
    vae_preflight; rc=$?
    if [ $rc -eq 0 ]; then echo "PREFLIGHT PASS"; else echo "PREFLIGHT FAIL"; fi
    exit $rc
fi
vae_preflight || { echo "refusing to start: preflight failed"; exit 1; }
"$ADB" -s "$SERIAL" shell am force-stop com.muyuchat.mca >/dev/null 2>&1
"$ADB" -s "$SERIAL" install -r app/build/outputs/apk/diagnostic/app-diagnostic.apk >/dev/null || exit 1
vae_set_pref true || { echo "FAIL: could not write full_vae_enabled=true" >&2; exit 1; }
"$ADB" -s "$SERIAL" shell 'run-as com.muyuchat.mca cat shared_prefs/mca_video_settings.xml' | tee "$EVID/prefs.txt"
if ! grep -q 'full_vae_enabled" value="true"' "$EVID/prefs.txt"; then
    echo "FAIL: full_vae_enabled is not true (launch the app once so it writes mca_video_settings.xml)" >&2
    exit 1
fi
# The Local API is activity-scoped and stops listening when the screen sleeps.
"$ADB" -s "$SERIAL" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
"$ADB" -s "$SERIAL" shell svc power stayon true >/dev/null 2>&1
"$ADB" -s "$SERIAL" shell am start -n com.muyuchat.mca/.MainActivity >/dev/null 2>&1
sleep 8
API_CODE=
for i in $(seq 1 20); do
    API_CODE=$(video_api_probe "$ADB" "$SERIAL" "$KEY")
    [ "$API_CODE" = "200" ] && break
    sleep 3
done
if [ "$API_CODE" != "200" ]; then
    echo "FAIL: local API not ready or key rejected (HTTP ${API_CODE:-none})" >&2
    exit 1
fi
echo "local API ready (HTTP 200)"
"$ADB" -s "$SERIAL" logcat -c
BODY='{"prompt":"a red ball","width":128,"height":128,"frames":5,"fps":24,"steps":4,"seed":42,"engine":"sdcpp_external","backend":"htp","loras":[{"path":"/data/user/0/com.muyuchat.mca/files/video/runs/lora/turbo.safetensors","weight":1.0}]}'
"$ADB" -s "$SERIAL" shell "printf %s '$BODY' > /data/local/tmp/req_fv128.json"
"$ADB" -s "$SERIAL" shell 'rm -f /data/local/tmp/resp_fv128.json; (setsid sh -c "curl -s -X POST http://127.0.0.1:11435/v1/video -H \"Authorization: Bearer '"$KEY"'\" -H \"Content-Type: application/json\" -d @/data/local/tmp/req_fv128.json > /data/local/tmp/resp_fv128.json 2>&1" >/dev/null 2>&1 &); echo submitted'
for i in $(seq 1 40); do
    sleep 15
    "$ADB" -s "$SERIAL" logcat -d > "$EVID/logcat.txt" 2>/dev/null
    if grep -q "external video engine" "$EVID/logcat.txt"; then break; fi
    if video_response_ready "$ADB" "$SERIAL" /data/local/tmp/resp_fv128.json; then
        echo "response file present; stopping watch"
        break
    fi
done
grep -E "fullVae=|VAE Tile size|num tiles|vaeProfile|external video engine" "$EVID/logcat.txt" | tail -6
"$ADB" -s "$SERIAL" shell 'cat /data/local/tmp/resp_fv128.json' | tee "$EVID/response.json"
"$ADB" -s "$SERIAL" shell 'run-as com.muyuchat.mca ls -lt files/video/media | head -2' | tee "$EVID/media.txt"
video_collect_result "$ADB" "$SERIAL" "$EVID" || { echo "run produced no verified artifact"; exit 1; }
