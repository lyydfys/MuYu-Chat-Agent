#!/usr/bin/env bash
# Compares one MCA output clip against a benchmark project's output (gap 2).
#
# Both sides are decoded to PNG at a common size/rate and compared frame by frame with ffmpeg's
# psnr/ssim filters, plus a temporal-stability metric (mean relative size change between
# consecutive frames) so "soft" and "jittery" stay separate findings.
#
# Usage:
#   tools/compare-video-reference.sh --ours out.mp4 --reference ref.mp4 \
#       [--width N] [--height N] [--fps N] [--out DIR]
#   tools/compare-video-reference.sh --ours-frames DIR --reference-frames DIR [--out DIR]
#   tools/compare-video-reference.sh --selftest
#
# Exit: 0 = comparison produced, 1 = inputs unusable, 2 = bad invocation.
#
# NOTE: the ffmpeg/ffprobe on this box are Windows binaries. MSYS-style absolute paths (/tmp/...)
# are invisible to them, so every ffmpeg path below is relative to a directory we cd into.
set -uo pipefail

FFMPEG=${FFMPEG:-ffmpeg}
FFPROBE=${FFPROBE:-ffprobe}
OURS=
REF=
OURS_FRAMES=
REF_FRAMES=
WIDTH=
HEIGHT=
FPS=
OUT=build/evidence/v1-27/compare
SELFTEST=0

die() { echo "FAIL: $*" >&2; exit 2; }

while [ $# -gt 0 ]; do
    case "$1" in
        --ours) OURS=$2; shift 2 ;;
        --reference) REF=$2; shift 2 ;;
        --ours-frames) OURS_FRAMES=$2; shift 2 ;;
        --reference-frames) REF_FRAMES=$2; shift 2 ;;
        --width) WIDTH=$2; shift 2 ;;
        --height) HEIGHT=$2; shift 2 ;;
        --fps) FPS=$2; shift 2 ;;
        --out) OUT=$2; shift 2 ;;
        --selftest) SELFTEST=1; shift ;;
        -h|--help) sed -n '2,18p' "$0"; exit 0 ;;
        *) die "unknown argument: $1" ;;
    esac
done

command -v "$FFMPEG" >/dev/null 2>&1 || die "ffmpeg not found (set FFMPEG)"
command -v "$FFPROBE" >/dev/null 2>&1 || die "ffprobe not found (set FFPROBE)"

# No-reference report: a single clip, no benchmark needed. This is what gap 2 will consume once
# the reference exists -- the same metrics are then printed side by side for both clips.
report_mode() { # report_mode <video> <out>
    local video=$1 out=$2
    local fps=${FPS:-24}
    rm -rf "$out"; mkdir -p "$out" || return 1
    "$FFMPEG" -v error -y -i "$video" -vf "fps=$fps" -pix_fmt rgb24 "$out/f_%05d.png" || return 1
    python - "$out" <<'PY'
import glob, json, sys
from PIL import Image, ImageFilter
import numpy as np

out = sys.argv[1]
frames = sorted(glob.glob(out + "/f_*.png"))
if not frames:
    print("FAIL: no frames decoded"); sys.exit(1)
rows, prev = [], None
for path in frames:
    im = Image.open(path).convert("RGB")
    a = np.asarray(im).astype(np.float32) / 255.0
    mx, mn = a.max(axis=2), a.min(axis=2)
    sat = float(np.where(mx > 0, (mx - mn) / np.maximum(mx, 1e-6), 0).mean())
    gray = np.asarray(im.convert("L"), dtype=np.float32)
    edge = Image.open(path).convert("L").filter(ImageFilter.FIND_EDGES)
    row = {
        "frame": path.rsplit("/", 1)[-1],
        "luma_mean": round(float(a.mean()), 4),
        "luma_std": round(float(a.std()), 4),
        "saturation": round(sat, 4),
        "edge_variance": round(float(np.asarray(edge, dtype=np.float32).var()), 1),
    }
    if prev is not None:
        row["temporal_delta"] = round(float(np.abs(a - prev).mean() * 255.0), 2)
    prev = a
    rows.append(row)

summary = {
    "frames": len(rows),
    "saturation_mean": round(float(np.mean([r["saturation"] for r in rows])), 4),
    "luma_mean": round(float(np.mean([r["luma_mean"] for r in rows])), 4),
    "edge_variance_mean": round(float(np.mean([r["edge_variance"] for r in rows])), 1),
    "temporal_delta_mean": round(float(np.mean([r.get("temporal_delta", 0.0) for r in rows])), 2),
}
with open(out + "/report.json", "w", encoding="utf-8") as fh:
    json.dump({"summary": summary, "frames": rows}, fh, ensure_ascii=False, indent=2)
print(json.dumps(summary, ensure_ascii=False))
print("per-frame:")
for r in rows:
    print("  {frame} sat={saturation} edge={edge_variance} luma={luma_mean}".format(**r))
print("NO_REFERENCE_REPORT PASS")
PY
    local rc=$?
    rm -rf "$out"
    return $rc
}

run_compare() { # run_compare <ours-video|-> <ours-frames|-> <ref-video|-> <ref-frames|-> <out>
    local ours_video=$1 ours_frames=$2 ref_video=$3 ref_frames=$4 out=$5
    rm -rf "$out"
    mkdir -p "$out/ours" "$out/ref" || return 1

    local w=$WIDTH h=$HEIGHT fps=$FPS
    if { [ -z "$w" ] || [ -z "$h" ]; } && [ "$ours_video" != "-" ]; then
        w=$("$FFPROBE" -v error -select_streams v:0 -show_entries stream=width -of default=nw=1:nk=1 "$ours_video" | head -1)
        h=$("$FFPROBE" -v error -select_streams v:0 -show_entries stream=height -of default=nw=1:nk=1 "$ours_video" | head -1)
    fi
    if [ -z "$fps" ] && [ "$ours_video" != "-" ]; then
        fps=$("$FFPROBE" -v error -select_streams v:0 -show_entries stream=r_frame_rate -of default=nw=1:nk=1 "$ours_video" | head -1)
    fi
    w=${w:-480}; h=${h:-832}; fps=${fps:-24}

    if [ "$ours_video" != "-" ]; then
        "$FFMPEG" -v error -y -i "$ours_video" -vf "fps=$fps,scale=$w:$h:flags=bicubic" \
            -pix_fmt rgb24 "$out/ours/f_%05d.png" || return 1
    fi
    if [ "$ref_video" != "-" ]; then
        "$FFMPEG" -v error -y -i "$ref_video" -vf "fps=$fps,scale=$w:$h:flags=bicubic" \
            -pix_fmt rgb24 "$out/ref/f_%05d.png" || return 1
    fi
    [ "$ours_frames" != "-" ] && cp "$ours_frames"/*.png "$out/ours/" 2>/dev/null
    [ "$ref_frames" != "-" ] && cp "$ref_frames"/*.png "$out/ref/" 2>/dev/null

    local ours_list ref_list count i
    ours_list=$(find "$out/ours" -name '*.png' | sort)
    ref_list=$(find "$out/ref" -name '*.png' | sort)
    [ -n "$ours_list" ] || { echo "FAIL: no frames on the ours side" >&2; return 1; }
    [ -n "$ref_list" ] || { echo "FAIL: no frames on the reference side" >&2; return 1; }

    count=$(printf '%s\n' "$ours_list" | wc -l)
    local ref_count
    ref_count=$(printf '%s\n' "$ref_list" | wc -l)
    [ "$ref_count" -lt "$count" ] && count=$ref_count

    mkdir -p "$out/seq_ours" "$out/seq_ref"
    i=0
    while IFS= read -r f; do
        [ "$i" -ge "$count" ] && break
        cp "$f" "$(printf '%s/seq_ours/f_%05d.png' "$out" "$i")"
        i=$((i + 1))
    done <<< "$ours_list"
    i=0
    while IFS= read -r f; do
        [ "$i" -ge "$count" ] && break
        cp "$f" "$(printf '%s/seq_ref/f_%05d.png' "$out" "$i")"
        i=$((i + 1))
    done <<< "$ref_list"

    ( cd "$out" || exit 1
      "$FFMPEG" -v error -y -i seq_ours/f_%05d.png -i seq_ref/f_%05d.png \
          -lavfi "[0:v][1:v]psnr=stats_file=psnr.log" -f null - 2>/dev/null
      "$FFMPEG" -v error -y -i seq_ours/f_%05d.png -i seq_ref/f_%05d.png \
          -lavfi "[0:v][1:v]ssim=stats_file=ssim.log" -f null - 2>/dev/null ) || true

    python - "$out" "$count" "$w" "$h" "$fps" <<'PY'
import json, os, re, sys

out, count, w, h, fps = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4], sys.argv[5]

def read(path, pattern):
    values = []
    if not os.path.isfile(path):
        return values
    for line in open(path, encoding='utf-8', errors='replace'):
        m = re.search(pattern, line)
        if not m:
            continue
        raw = m.group(1)
        values.append(float('inf') if raw in ('inf', 'nan') else float(raw))
    return values

psnr = read(os.path.join(out, 'psnr.log'), r'psnr_avg:(\S+)')
ssim = read(os.path.join(out, 'ssim.log'), r'All:(\S+)')

def finite_mean(values):
    vals = [v for v in values if v != float('inf')]
    if vals:
        return sum(vals) / len(vals)
    return float('inf') if values else None

def temporal(dirname):
    files = sorted(f for f in os.listdir(dirname) if f.endswith('.png'))[:count]
    deltas = []
    for a, b in zip(files, files[1:]):
        sa = os.path.getsize(os.path.join(dirname, a))
        sb = os.path.getsize(os.path.join(dirname, b))
        deltas.append(abs(sa - sb) / max(sa, sb) if max(sa, sb) else 0.0)
    return sum(deltas) / len(deltas) if deltas else None

result = {
    'frames': count, 'width': int(w), 'height': int(h), 'fps': fps,
    'psnr_mean': finite_mean(psnr), 'psnr_min': min(psnr) if psnr else None,
    'ssim_mean': finite_mean(ssim), 'ssim_min': min(ssim) if ssim else None,
    'temporal_delta_ours': temporal(os.path.join(out, 'seq_ours')),
    'temporal_delta_reference': temporal(os.path.join(out, 'seq_ref')),
    'per_frame_psnr': psnr, 'per_frame_ssim': ssim,
}
json.dump(result, open(os.path.join(out, 'comparison.json'), 'w', encoding='utf-8'), indent=2)

def fmt(v, digits=4):
    if v is None:
        return 'n/a'
    return 'inf' if v == float('inf') else f'{v:.{digits}f}'

print(f"frames compared: {count}  ({w}x{h} @ {fps})")
print(f"PSNR  mean {fmt(result['psnr_mean'], 2)} dB   min {fmt(result['psnr_min'], 2)} dB")
print(f"SSIM  mean {fmt(result['ssim_mean'])}      min {fmt(result['ssim_min'])}")
print(f"temporal delta  ours {fmt(result['temporal_delta_ours'])}   reference {fmt(result['temporal_delta_reference'])}")
print(f"written: {os.path.join(out, 'comparison.json')}")
PY
}

# Single-clip, no-reference mode.
if [ -n "$OURS" ] && [ -z "$REF" ] && [ -z "$REF_FRAMES" ] && [ -z "$OURS_FRAMES" ]; then
    report_mode "$OURS" "$OUT/report" || die "no-reference report failed"
    exit 0
fi

if [ "$SELFTEST" = "1" ]; then
    TMP=build/evidence/v1-27/selftest.$$
    rm -rf "$TMP"
    mkdir -p "$TMP"
    trap 'rm -rf "$TMP"' EXIT
    "$FFMPEG" -v error -y -f lavfi -i testsrc2=size=160x96:rate=24:duration=0.5 -pix_fmt yuv420p "$TMP/a.mp4" \
        || die "selftest: cannot create source clip"
    "$FFMPEG" -v error -y -i "$TMP/a.mp4" -vf "boxblur=2:1" -pix_fmt yuv420p "$TMP/b.mp4" \
        || die "selftest: cannot create blurred clip"
    SELF_W=160 SELF_H=96 SELF_FPS=24
    WIDTH=$SELF_W HEIGHT=$SELF_H FPS=$SELF_FPS run_compare "$TMP/a.mp4" - "$TMP/a.mp4" - "$TMP/same" >/dev/null \
        || die "selftest: identical clips did not compare"
    WIDTH=$SELF_W HEIGHT=$SELF_H FPS=$SELF_FPS run_compare "$TMP/b.mp4" - "$TMP/a.mp4" - "$TMP/blur" >/dev/null \
        || die "selftest: blurred comparison failed"
    python - "$TMP/same/comparison.json" "$TMP/blur/comparison.json" <<'PY'
import json, sys
same = json.load(open(sys.argv[1]))
blur = json.load(open(sys.argv[2]))
ok = (same['frames'] > 0 and same['psnr_mean'] == float('inf') and same['ssim_mean'] == 1.0
      and blur['psnr_mean'] is not None and blur['psnr_mean'] != float('inf')
      and blur['psnr_mean'] < 60 and blur['ssim_mean'] < 1.0)
print(f"identical: frames={same['frames']} psnr={same['psnr_mean']} ssim={same['ssim_mean']}")
print(f"blurred:   frames={blur['frames']} psnr={blur['psnr_mean']:.2f} ssim={blur['ssim_mean']:.4f}")
print("SELFTEST PASS" if ok else "SELFTEST FAIL")
sys.exit(0 if ok else 1)
PY
    exit $?
fi

[ -n "$OURS$OURS_FRAMES" ] || die "--ours or --ours-frames is required"
[ -n "$REF$REF_FRAMES" ] || die "--reference or --reference-frames is required"
run_compare "${OURS:--}" "${OURS_FRAMES:--}" "${REF:--}" "${REF_FRAMES:--}" "$OUT"
