#!/usr/bin/env bash
# Capture one /v1/video run as reproducible evidence: binary identity, logcat, HTTP response,
# media bytes, container checks. Written because a 480p pass was reported with the response only,
# and because libmca_wan.so is a prebuilt that has silently drifted from source before.
#
# Usage:
#   tools/capture-video-evidence.sh --out <dir> --body-file <json> [--serial <sn>] [--port 11435]
#   tools/capture-video-evidence.sh --out <dir> --prompt "a red ball" --width 480 --height 832 \
#       --frames 5 --fps 24 --steps 4 [--seed 42] [--engine sdcpp_external] [--backend htp] \
#       [--lora <path:weight> ...] [--serial <sn>] [--port 11435]
#
# Exit codes: 0 = every check passed, 1 = a check failed, 2 = bad invocation / no device.
set -uo pipefail
export MSYS_NO_PATHCONV=1

ADB=${ADB:-/e/model/android-sdk/platform-tools/adb.exe}
PKG=${PKG:-com.muyuchat.mca}
OUT=
BODY_FILE=
SERIAL=
PORT=11435
PROMPT=
WIDTH=480
HEIGHT=832
FRAMES=5
FPS=24
STEPS=4
SEED=42
ENGINE=sdcpp_external
BACKEND=htp
LORAS=()

die() { echo "FAIL: $*" >&2; exit 2; }
need() { command -v "$1" >/dev/null 2>&1 || die "missing tool: $1"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT=$2; shift 2 ;;
    --body-file) BODY_FILE=$2; shift 2 ;;
    --serial) SERIAL=$2; shift 2 ;;
    --port) PORT=$2; shift 2 ;;
    --prompt) PROMPT=$2; shift 2 ;;
    --width) WIDTH=$2; shift 2 ;;
    --height) HEIGHT=$2; shift 2 ;;
    --frames) FRAMES=$2; shift 2 ;;
    --fps) FPS=$2; shift 2 ;;
    --steps) STEPS=$2; shift 2 ;;
    --seed) SEED=$2; shift 2 ;;
    --engine) ENGINE=$2; shift 2 ;;
    --backend) BACKEND=$2; shift 2 ;;
    --lora) LORAS+=("$2"); shift 2 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

[ -n "$OUT" ] || die "--out is required"
need "$ADB"; need sha256sum; need unzip
ADB_A=("$ADB")
[ -n "$SERIAL" ] && ADB_A+=(-s "$SERIAL")

mkdir -p "$OUT"
LOG="$OUT/run.log"
: > "$LOG"
say() { echo "$*" | tee -a "$LOG"; }
check() { # check <label> <expected> <actual>
  if [ "$2" = "$3" ]; then say "  PASS $1: $3"; return 0; fi
  say "  FAIL $1: expected=$2 actual=$3"; return 1
}
rc=0

# ---- 0. device ---------------------------------------------------------------
DEV=$("${ADB_A[@]}" get-serialno 2>/dev/null)
[ -n "$DEV" ] && [ "$DEV" != "unknown" ] || die "no device (adb get-serialno failed)"
say "device=$DEV"

# ---- 1. binary identity (the anchor) ----------------------------------------
say "== binary identity"
VERSION=$("${ADB_A[@]}" shell dumpsys package "$PKG" 2>/dev/null | sed -n 's/.*versionName=\([^ ]*\).*/\1/p' | head -1)
VCODE=$("${ADB_A[@]}" shell dumpsys package "$PKG" 2>/dev/null | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1)
APK_PATH=$("${ADB_A[@]}" shell pm path "$PKG" 2>/dev/null | sed -n 's/^package://p' | head -1)
say "  versionName=$VERSION versionCode=$VCODE"
say "  apk=$APK_PATH"
[ -n "$APK_PATH" ] || die "package $PKG is not installed"
"${ADB_A[@]}" pull "$APK_PATH" "$OUT/base.apk" >/dev/null 2>&1 || die "cannot pull the installed APK"
APK_SHA=$(sha256sum "$OUT/base.apk" | cut -d' ' -f1)
say "  apk_sha256=$APK_SHA"
for lib in libmca_wan.so libmca_sd_native.so libstable-diffusion.so; do
  h=$(unzip -p "$OUT/base.apk" "lib/arm64-v8a/$lib" 2>/dev/null | sha256sum | cut -d' ' -f1)
  if [ -n "$h" ] && [ "$h" != "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" ]; then
    say "  $lib=$h"
  else
    say "  $lib=ABSENT"
  fi
done

# ---- 2. preflight: free memory, then clear logcat ---------------------------
say "== preflight"
FREE_KB=$("${ADB_A[@]}" shell 'grep MemAvailable /proc/meminfo' 2>/dev/null | tr -dc '0-9')
say "  MemAvailable=${FREE_KB:-unknown} kB"
if [ -n "${FREE_KB:-}" ] && [ "$FREE_KB" -lt 3000000 ]; then
  say "  WARN available memory below 3 GB; a 480p run may be killed by the LMK"
fi
"${ADB_A[@]}" logcat -c >/dev/null 2>&1
"${ADB_A[@]}" logcat -v time -s McaWanEngine:V McaVideoNative:V ActivityManager:I > "$OUT/logcat.txt" 2>&1 &
LOGCAT_PID=$!
trap 'kill $LOGCAT_PID 2>/dev/null' EXIT

# ---- 3. request --------------------------------------------------------------
say "== request"
"${ADB_A[@]}" forward "tcp:$PORT" "tcp:$PORT" >/dev/null 2>&1 || die "adb forward failed"
KEY=$("${ADB_A[@]}" shell "run-as $PKG cat /data/data/$PKG/shared_prefs/mca_api.xml" 2>/dev/null \
      | sed -n 's/.*name="api_key">\([^<]*\)<.*/\1/p' | head -1)
if [ -z "$KEY" ]; then
  KEY=$("${ADB_A[@]}" shell "run-as $PKG cat /data/data/$PKG/shared_prefs/mca_api.xml" 2>/dev/null \
        | sed -n 's/.*>\([A-Za-z0-9_\-]\{16,\}\)<.*/\1/p' | head -1)
fi
[ -n "$KEY" ] || die "cannot read the Local API key from shared_prefs/mca_api.xml"

if [ -n "$BODY_FILE" ]; then
  BODY=$(cat "$BODY_FILE")
else
  [ -n "$PROMPT" ] || die "--prompt or --body-file is required"
  LORA_JSON=""
  for l in "${LORAS[@]:-}"; do
    [ -n "$l" ] || continue
    path=${l%:*}; weight=${l##*:}
    [ "$path" = "$l" ] && weight=1.0
    LORA_JSON="${LORA_JSON:+$LORA_JSON,}{\"path\":\"$path\",\"weight\":$weight}"
  done
  BODY="{\"prompt\":\"$PROMPT\",\"width\":$WIDTH,\"height\":$HEIGHT,\"frames\":$FRAMES,\"fps\":$FPS,\"steps\":$STEPS,\"seed\":$SEED,\"engine\":\"$ENGINE\",\"backend\":\"$BACKEND\""
  [ -n "$LORA_JSON" ] && BODY="$BODY,\"loras\":[$LORA_JSON]"
  BODY="$BODY}"
fi
printf '%s' "$BODY" > "$OUT/request.json"
say "  body=$(cat "$OUT/request.json")"

START=$(date +%s)
curl -sS -m 1800 -o "$OUT/response.json" -w '%{http_code}' \
  -X POST "http://127.0.0.1:$PORT/v1/video" \
  -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \
  --data-binary "@$OUT/request.json" > "$OUT/http_code.txt" 2>>"$LOG"
HTTP=$(cat "$OUT/http_code.txt" 2>/dev/null)
ELAPSED=$(( $(date +%s) - START ))
say "  http=$HTTP wall=${ELAPSED}s"
check "http 200" 200 "$HTTP" || rc=1

# ---- 4. response fields ------------------------------------------------------
say "== response"
python - "$OUT/response.json" "$OUT" <<'PY' | tee -a "$LOG"
import json, sys, pathlib
resp = json.load(open(sys.argv[1], encoding='utf-8'))
out = pathlib.Path(sys.argv[2])
exe = resp.get("execution", {}) or {}
media = (resp.get("data") or [{}])[0]
rows = {
    "request_id": resp.get("request_id"),
    "engine": resp.get("engine"),
    "external_backend": resp.get("external_backend"),
    "fallback": resp.get("external_backend_fallback"),
    "fallback_reason": resp.get("external_backend_reason"),
    "state": exe.get("state"),
    "stage": exe.get("stage"),
    "elapsed_ms": exe.get("elapsed_ms"),
    "media_id": media.get("media_id"),
    "size_bytes": media.get("size_bytes"),
    "sha256": media.get("sha256"),
    "width": media.get("width"),
    "height": media.get("height"),
    "frame_count": media.get("frame_count"),
    "fps": media.get("fps"),
    "duration_us": media.get("duration_us"),
}
(out / "response-fields.json").write_text(json.dumps(rows, indent=2, ensure_ascii=False), encoding='utf-8')
for k, v in rows.items():
    print(f"  {k}={v}")
if rows["fallback"] or rows["fallback_reason"]:
    print("  NOTE backend fell back; backend evidence is CPU, not NPU")
PY

MEDIA_ID=$(python -c "import json,sys;print((json.load(open(sys.argv[1],encoding='utf-8')).get('data') or [{}])[0].get('media_id') or '')" "$OUT/response.json")
API_SHA=$(python -c "import json,sys;print((json.load(open(sys.argv[1],encoding='utf-8')).get('data') or [{}])[0].get('sha256') or '')" "$OUT/response.json")
API_BYTES=$(python -c "import json,sys;print((json.load(open(sys.argv[1],encoding='utf-8')).get('data') or [{}])[0].get('size_bytes') or '')" "$OUT/response.json")

# ---- 5. media bytes ----------------------------------------------------------
say "== media"
if [ -z "$MEDIA_ID" ]; then
  say "  FAIL no media_id in the response"; rc=1
else
  MP4="$OUT/media-$MEDIA_ID.mp4"
  "${ADB_A[@]}" exec-out "run-as $PKG cat files/video/media/$MEDIA_ID.mp4" > "$MP4" 2>/dev/null
  DISK_BYTES=$(stat -c%s "$MP4" 2>/dev/null || echo 0)
  DISK_SHA=$(sha256sum "$MP4" | cut -d' ' -f1)
  check "size_bytes" "${API_BYTES:-0}" "$DISK_BYTES" || rc=1
  check "media sha256" "$(echo "$API_SHA" | tr 'A-Z' 'a-z')" "$DISK_SHA" || rc=1
  if command -v ffprobe >/dev/null 2>&1; then
    ffprobe -v error -select_streams v:0 \
      -show_entries stream=codec_name,width,height,r_frame_rate,nb_frames,duration \
      -of default=nw=1 "$MP4" | tee -a "$LOG"
  else
    say "  ffprobe unavailable; container UNVERIFIED"
  fi
fi

# ---- 6. runtime phase evidence from logcat ----------------------------------
sleep 3
"${ADB_A[@]}" logcat -d -v time -s McaWanEngine:V McaVideoNative:V ActivityManager:I > "$OUT/logcat.txt" 2>&1
kill $LOGCAT_PID 2>/dev/null
trap - EXIT
say "== runtime (logcat excerpt)"
grep -E "htp canary|requested=\[|LoRA|lowmemorykiller|INTERRUPTED" "$OUT/logcat.txt" 2>/dev/null | tail -12 | tee -a "$LOG" \
  || say "  no HTP/LoRA/LMK lines captured"

say "== result: $([ $rc -eq 0 ] && echo PASS || echo FAIL)"
say "  evidence dir: $OUT"
exit $rc
