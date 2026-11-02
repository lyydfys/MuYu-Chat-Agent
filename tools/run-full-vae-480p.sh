#!/usr/bin/env bash
# One-shot device run of the App path with the full Wan VAE (spatial tiling, gap 4).
# Prereqs: device online and unlocked, APK built, keys already exported in the app.
#
# Usage: bash tools/run-full-vae-480p.sh [--check|--print-request] [serial]
#   --check: run only the preflight (CE storage, battery, free space, APK, LoRA) and exit.
#   --print-request: print the /v1/video body that would be submitted and exit (no device needed).
# Env: MCA_FRAMES (default 5; use 1 for a ~4-minute 480p full-VAE smoke),
#      MCA_MIN_BATTERY (default 40), MCA_MIN_FREE_GB (default 3; set ~10 if the models must be re-imported).
set -uo pipefail
CHECK=0
PRINT_REQ=0
case "${1:-}" in
    --check) CHECK=1; shift ;;
    --print-request) PRINT_REQ=1; shift ;;
esac
SERIAL=${1:-98a37aa7}
ADB=${ADB:-/e/model/android-sdk/platform-tools/adb.exe}
# The Local API key is per-install and shown in the app; never hardcode it in the repo.
KEY=${MCA_API_KEY:?export MCA_API_KEY with the key shown in MCA -> Local API}
APK=app/build/outputs/apk/diagnostic/app-diagnostic.apk
EVID=${MCA_EVID:-build/evidence/v1-27/FULL-VAE-APP}
MIN_BATTERY=${MCA_MIN_BATTERY:-40}
MIN_FREE_GB=${MCA_MIN_FREE_GB:-3}
FRAMES=${MCA_FRAMES:-5}
FULL_VAE=${MCA_FULL_VAE:-1}   # 0 = same request with the TAE decoder (A/B control)
GUIDANCE=${MCA_GUIDANCE:-}    # optional request-scoped sampler override, e.g. 1.0
POLLS=${MCA_POLLS:-$(( FRAMES >= 5 ? 120 : 30 ))}

mkdir -p "$EVID"

# The /v1/video body is the contract this runner exercises; --print-request makes it checkable
# without a device (a 1-frame body is the ~4-minute 480p full-VAE smoke).
build_body() {
    local extra=""
    [ -n "$GUIDANCE" ] && extra=",\"guidance\":$GUIDANCE"
    printf '{"prompt":"a red ball","width":480,"height":832,"frames":%s,"fps":24,"steps":4,"seed":42%s,"engine":"sdcpp_external","backend":"htp","loras":[{"path":"/data/user/0/com.muyuchat.mca/files/video/runs/lora/turbo.safetensors","weight":1.0}]}' "$FRAMES" "$extra"
}

if [ "$PRINT_REQ" = "1" ]; then
    build_body; echo; exit 0
fi

. "$(dirname "$0")/lib/device-preflight.sh"
. "$(dirname "$0")/lib/video-artifact.sh"
preflight() { vae_preflight; }

if [ "$CHECK" = "1" ]; then
    preflight; rc=$?
    if [ $rc -eq 0 ]; then echo "PREFLIGHT PASS"; else echo "PREFLIGHT FAIL"; fi
    exit $rc
fi
preflight || { echo "refusing to start: preflight failed"; exit 1; }
"$ADB" -s "$SERIAL" shell am force-stop com.muyuchat.mca >/dev/null 2>&1
"$ADB" -s "$SERIAL" install -r "$APK" || exit 1
# The decode-quality flag is a device-local preference; set it without the UI.
if [ "$FULL_VAE" = "1" ]; then VAE_PREF=true; else VAE_PREF=false; fi
echo "decoder: full-VAE=$FULL_VAE (pref full_vae_enabled=$VAE_PREF)"
vae_set_pref "$VAE_PREF" || { echo "FAIL: could not write full_vae_enabled=$VAE_PREF" >&2; exit 1; }
"$ADB" -s "$SERIAL" shell 'run-as com.muyuchat.mca cat shared_prefs/mca_video_settings.xml' | tee "$EVID/prefs.txt"
if ! grep -q "full_vae_enabled\" value=\"$VAE_PREF\"" "$EVID/prefs.txt"; then
    echo "FAIL: full_vae_enabled is not $VAE_PREF (launch the app once so it writes mca_video_settings.xml)" >&2
    exit 1
fi
# The Local API is activity-scoped: with the screen off the activity stops and 127.0.0.1:11435
# stops listening (measured 2026-11-02 07:05). Wake it and keep it awake while charging.
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
BODY=$(build_body)
"$ADB" -s "$SERIAL" shell "printf %s '$BODY' > /data/local/tmp/req_fullvae.json"
"$ADB" -s "$SERIAL" shell 'rm -f /data/local/tmp/resp_fullvae.json; (setsid sh -c "curl -s -X POST http://127.0.0.1:11435/v1/video -H \"Authorization: Bearer '"$KEY"'\" -H \"Content-Type: application/json\" -d @/data/local/tmp/req_fullvae.json > /data/local/tmp/resp_fullvae.json 2>&1" >/dev/null 2>&1 &); echo submitted'
echo "watching for tiling + result (frames=$FRAMES, up to $(( POLLS * 30 / 60 )) min)..."
for i in $(seq 1 "$POLLS"); do
    sleep 30
    "$ADB" -s "$SERIAL" logcat -d > "$EVID/logcat.txt" 2>/dev/null
    if grep -q "VAE Tile size" "$EVID/logcat.txt" && ! grep -q "tiling-ok" "$EVID/status.txt" 2>/dev/null; then
        echo "tiling-ok" >> "$EVID/status.txt"
        grep -m2 -E "VAE Tile size|num tiles" "$EVID/logcat.txt"
    fi
    if grep -q "external video engine" "$EVID/logcat.txt"; then
        grep -E "external video engine" "$EVID/logcat.txt" | tail -1
        break
    fi
    if video_response_ready "$ADB" "$SERIAL" /data/local/tmp/resp_fullvae.json; then
        echo "response file present; stopping watch"
        break
    fi
done
"$ADB" -s "$SERIAL" shell 'cat /data/local/tmp/resp_fullvae.json' | tee "$EVID/response.json"
"$ADB" -s "$SERIAL" shell 'run-as com.muyuchat.mca ls -lt files/video/media | head -3' | tee "$EVID/media.txt"
video_collect_result "$ADB" "$SERIAL" "$EVID" || { echo "run produced no verified artifact"; exit 1; }
