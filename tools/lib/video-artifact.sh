#!/usr/bin/env bash
# Pulls the media file named by the Local API response and checks it against the response fields.
# The response carries media_id / sha256 / size_bytes / frame_count / duration_us, so a completed
# run can be verified end to end without a human reading logcat.
#
# Usage (after the response is on disk):
#   video_collect_result <adb> <serial> <evidence-dir> [package]
# Returns 0 when the file was pulled and every checked field matches, 1 otherwise.

# True when the device-side response file exists and is non-empty, i.e. the HTTP call has finished
# (success or error). An immediate 4xx leaves the generation log silent, so polling this beats
# waiting out the whole watch window.
video_response_ready() { # adb serial device-path
    "$1" -s "$2" shell "test -s '$3' && echo READY" 2>/dev/null | tr -d '\r' | grep -q READY
}

# HTTP status of GET /v1/models on the device (200 = server up and the bearer key accepted).
video_api_probe() { # adb serial key
    "$1" -s "$2" shell "curl -s -o /dev/null -w '%{http_code}' -H 'Authorization: Bearer $3' http://127.0.0.1:11435/v1/models" 2>/dev/null | tr -d '\r\n'
}

video_collect_result() {
    local adb=$1 serial=$2 evid=$3 pkg=${4:-com.muyuchat.mca}
    local resp="$evid/response.json" out="$evid/result.mp4" name media
    local expect_sha expect_size expect_frames actual_sha actual_size

    if [ ! -s "$resp" ]; then echo "FAIL artifact   $resp missing or empty"; return 1; fi
    media=$(python - "$resp" <<'PY' | tr -d '\r\n'
import json, sys
try:
    d = json.load(open(sys.argv[1], encoding='utf-8'))
    data = d.get('data') or []
    print(data[0].get('media_id', '') if data else '')
except Exception:
    print('')
PY
)
    if [ -z "$media" ]; then echo "FAIL artifact   response has no data[0].media_id"; return 1; fi
    name=$("$adb" -s "$serial" shell "run-as $pkg ls files/video/media 2>/dev/null" | tr -d '\r' | grep -F "$media" | head -1)
    if [ -z "$name" ]; then echo "FAIL artifact   $media not found under files/video/media"; return 1; fi
    "$adb" -s "$serial" exec-out run-as "$pkg" cat "files/video/media/$name" > "$out" || {
        echo "FAIL artifact   pull failed for $name"; return 1; }
    actual_sha=$(sha256sum "$out" | awk '{print $1}')
    actual_size=$(wc -c < "$out" | tr -d ' ')
    read -r expect_sha expect_size expect_frames <<EOF
$(python - "$resp" <<'PY' | tr -d '\r'
import json, sys
try:
    d = json.load(open(sys.argv[1], encoding='utf-8'))
    item = (d.get('data') or [{}])[0]
    print(item.get('sha256', ''), item.get('size_bytes', ''), item.get('frame_count', ''))
except Exception:
    print('')
PY
)
EOF
    echo "OK   artifact   $name  $actual_size B  sha256=$actual_sha"
    ffprobe -v error -show_entries stream=codec_name,width,height,nb_frames,r_frame_rate \
        -show_entries format=duration -of default=nw=1 "$out" 2>&1 | tee "$evid/ffprobe.txt"
    if [ -n "$expect_size" ] && [ "$actual_size" != "$expect_size" ]; then
        echo "FAIL artifact   size $actual_size != response $expect_size"; return 1
    fi
    if [ -n "$expect_sha" ] && [ "$actual_sha" != "$expect_sha" ]; then
        echo "FAIL artifact   sha256 != response $expect_sha"; return 1
    fi
    local probe_frames
    probe_frames=$(sed -n 's/^nb_frames=//p' "$evid/ffprobe.txt" 2>/dev/null | head -1)
    if [ -n "$expect_frames" ] && [ -n "$probe_frames" ] && [ "$expect_frames" != "$probe_frames" ]; then
        echo "FAIL artifact   ffprobe nb_frames=$probe_frames != response frame_count=$expect_frames"; return 1
    fi
    if [ -n "$expect_frames" ]; then
        echo "OK   artifact   response frame_count=$expect_frames, size/sha match"
    else
        echo "OK   artifact   size/sha match"
    fi
    return 0
}
