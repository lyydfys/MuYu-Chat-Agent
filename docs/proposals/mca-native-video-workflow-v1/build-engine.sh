#!/usr/bin/env bash
# Reproducible build of the MCA Wan engine from the pinned HappyYzy sources
# and the production patch queue (0001..0008; the 0009 debug-latent dump is
# intentionally excluded).
#
# Usage:
#   bash docs/proposals/mca-native-video-workflow-v1/build-engine.sh [OUTPUT_DIR]
#
# OUTPUT_DIR defaults to <repo>/build/repro-engine-repro. The script deletes and
# recreates OUTPUT_DIR, then extracts both tarballs into OUTPUT_DIR/work, applies
# the patch queue, configures with the local Android NDK, builds
# `stable-diffusion` (bin/libstable-diffusion.so) and, when the host Hexagon
# toolchain is executable, the `htp-v79` DSP skeleton, and prints SHA-256 and
# byte sizes for every produced artifact.
#
# Overridable environment:
#   ANDROID_NDK_ROOT   Android NDK (default E:/model/android-sdk/ndk/29.0.14206865)
#   HEXAGON_SDK_ROOT   Hexagon SDK  (default E:/tools/hexagon-sdk-extract/6.7.0.0)
#   SDCPP_TARBALL      pinned sdcpp archive
#   GGML_TARBALL       pinned ggml archive
#   CMAKE, NINJA       explicit tool binaries
#   JOBS               build parallelism (default: nproc)
#   REQUIRE_SKELETON=1 fail instead of NOT_RUN when the HTP skeleton cannot be
#                      built on this host (e.g. Linux-only Hexagon toolchain)
#
# No Gradle, no WSL, no writes outside OUTPUT_DIR.

set -euo pipefail

die() {
    printf 'ERROR: %s\n' "$*" >&2
    exit 1
}

log() {
    printf '\n== %s\n' "$*"
}

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/../../.." && pwd)

OUT_DIR=${1:-"$REPO_ROOT/build/repro-engine-repro"}
NDK=${ANDROID_NDK_ROOT:-${NDK:-E:/model/android-sdk/ndk/29.0.14206865}}
HEXAGON_SDK_ROOT=${HEXAGON_SDK_ROOT:-E:/tools/hexagon-sdk-extract/6.7.0.0}
SDCPP_TARBALL=${SDCPP_TARBALL:-"$REPO_ROOT/.dsh-tmp/happyyzy/sdcpp-a63954d.tar.gz"}
GGML_TARBALL=${GGML_TARBALL:-"$REPO_ROOT/.dsh-tmp/happyyzy/ggml-f8758628.tar.gz"}
PATCH_DIR="$SCRIPT_DIR/patches"
# qaic output for the pinned htp_iface.idl, generated once with the pinned
# Hexagon SDK. Only used on hosts that cannot execute the SDK's Linux qaic.
HTP_IFACE_DIR=${HTP_IFACE_DIR:-"$SCRIPT_DIR/prebuilt/htp-iface"}
REQUIRE_SKELETON=${REQUIRE_SKELETON:-0}

# Refuse to delete something that is obviously not a scratch build directory.
case "$OUT_DIR" in
    ""|"/"|"$REPO_ROOT"|"$REPO_ROOT/")
        die "refusing to use '$OUT_DIR' as the output directory"
        ;;
esac

[ -d "$NDK" ] || die "Android NDK not found: $NDK (set ANDROID_NDK_ROOT)"
[ -d "$HEXAGON_SDK_ROOT" ] || die "Hexagon SDK not found: $HEXAGON_SDK_ROOT (set HEXAGON_SDK_ROOT)"
[ -f "$SDCPP_TARBALL" ] || die "sdcpp tarball not found: $SDCPP_TARBALL (set SDCPP_TARBALL)"
[ -f "$GGML_TARBALL" ] || die "ggml tarball not found: $GGML_TARBALL (set GGML_TARBALL)"

# ---------------------------------------------------------------- toolchain --
CMAKE_BIN=${CMAKE:-}
if [ -z "$CMAKE_BIN" ]; then
    CMAKE_BIN=$(command -v cmake || true)
fi
if [ -z "$CMAKE_BIN" ]; then
    CMAKE_BIN=$(find "$NDK/../../cmake" -maxdepth 3 \( -name cmake.exe -o -name cmake \) 2>/dev/null | sort | head -1)
fi
[ -n "$CMAKE_BIN" ] && [ -x "$CMAKE_BIN" ] || die "cmake not found (set CMAKE)"

NINJA_BIN=${NINJA:-}
if [ -z "$NINJA_BIN" ]; then
    NINJA_BIN=$(command -v ninja || true)
fi
if [ -z "$NINJA_BIN" ]; then
    # The Android SDK ships ninja next to its CMake packages.
    NINJA_BIN=$(find "$NDK/../../cmake" -maxdepth 3 \( -name ninja.exe -o -name ninja \) 2>/dev/null | sort | head -1)
fi
[ -n "$NINJA_BIN" ] && [ -x "$NINJA_BIN" ] || die "ninja not found (set NINJA)"

# ExternalProject sub-builds invoke `-GNinja` and locate ninja through PATH.
NINJA_DIR=$(dirname "$NINJA_BIN")
export PATH="$NINJA_DIR:$PATH"

JOBS=${JOBS:-4}
# Release keeps the shipped flags; low-memory hosts can request Debug for an H3/CPU baseline
# build (the generated tokenizer vocab translation unit OOMs clang at -O3 with <2 GB free).
BUILD_TYPE=${BUILD_TYPE:-Release}

# Hexagon tools: use an explicit root if provided, otherwise the SDK's default.
if [ -n "${HEXAGON_TOOLS_ROOT:-}" ]; then
    HEXAGON_TOOLS_ROOT=$HEXAGON_TOOLS_ROOT
else
    HEXAGON_TOOLS_ROOT=$(find "$HEXAGON_SDK_ROOT/tools/HEXAGON_Tools" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort | tail -1)
fi
[ -n "$HEXAGON_TOOLS_ROOT" ] || HEXAGON_TOOLS_ROOT="$HEXAGON_SDK_ROOT/tools/HEXAGON_Tools/21.0.04"

# The HTP skeleton needs a compiler that the *host* can execute. The common
# Qualcomm Linux SDK extract contains ELF x86-64 tool binaries; Git Bash on
# Windows cannot run those, and the NDK's clang has no Hexagon target.
host_is_windows=0
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) host_is_windows=1 ;;
esac

hexagon_toolchain_runnable() {
    local hc=$1
    [ -f "$hc" ] || return 1
    if [ "$host_is_windows" -eq 1 ]; then
        # A Windows Hexagon SDK ships hexagon-clang.exe (PE/MZ), not an ELF file.
        [ "$(head -c 2 "$hc" 2>/dev/null)" = "MZ" ]
    else
        [ -x "$hc" ]
    fi
}

host_can_execute() {
    local f=$1
    [ -f "$f" ] || return 1
    if [ "$host_is_windows" -eq 1 ]; then
        [ "$(head -c 2 "$f" 2>/dev/null)" = "MZ" ]
    else
        [ -x "$f" ]
    fi
}

HEXAGON_CC="$HEXAGON_TOOLS_ROOT/Tools/bin/hexagon-clang.exe"
[ -f "$HEXAGON_CC" ] || HEXAGON_CC="$HEXAGON_TOOLS_ROOT/Tools/bin/hexagon-clang"
QAIC_BIN="$HEXAGON_SDK_ROOT/ipc/fastrpc/qaic/bin/qaic"

# SHA-256 of the pinned qaic output for htp_iface.idl
# (id 4784157a24826409f3feac97e870aed32d9105fe7ccc56e8732d8ac14a989411),
# generated with Hexagon SDK 6.7.0.1.  Recorded here so the files cannot drift.
verify_htp_iface() {
    local dir=$1 f actual
    [ -d "$dir" ] || die "pre-generated HTP interface not found: $dir (set HTP_IFACE_DIR)"
    for f in htp_iface.h htp_iface_skel.c htp_iface_stub.c; do
        [ -f "$dir/$f" ] || die "pre-generated HTP interface missing file: $dir/$f"
    done
    check_hash "$dir/htp_iface.h"        0b637338de2a7e51217fb15a0295e6b39a0a2366eb22ec1fad8d57e9b4da2466
    check_hash "$dir/htp_iface_skel.c"   d8b12d2a8b7c1c30e824c17b0628c236bf1e6cd5d82a337169fe666ad1d6623d
    check_hash "$dir/htp_iface_stub.c"   8ac842936f06f1812355d158af234a2dd91987fd6bebe93873fae4974ffd589f
}

check_hash() {
    local actual
    actual=$(sha256sum "$1" | cut -d' ' -f1)
    [ "$actual" = "$2" ] || die "SHA-256 mismatch for $1: expected $2, got $actual"
}

link_dir() {
    local target=$1 link=$2
    if [ "$host_is_windows" -eq 1 ]; then
        cmd //c mklink //J "$(cygpath -w "$link")" "$(cygpath -w "$target")" >/dev/null \
            || die "failed to create junction $link -> $target"
    else
        ln -s "$target" "$link"
    fi
}

# Build a shadow Hexagon SDK root whose build/cmake/hexagon_fun.cmake replaces
# qaic with the pinned pre-generated interface.  Everything else is linked to
# the real SDK; no file inside the SDK installation is modified.
create_sdk_shadow() {
    local real=$1 shadow=$2 entry
    rm -rf "$shadow"
    mkdir -p "$shadow"
    for entry in incs ipc rtos utils libs tools addons hexagon_sdk.json readme.txt setup_sdk_env.source; do
        [ -e "$real/$entry" ] || continue
        if [ -d "$real/$entry" ]; then
            link_dir "$real/$entry" "$shadow/$entry"
        else
            cp -a "$real/$entry" "$shadow/$entry"
        fi
    done
    mkdir -p "$shadow/build"
    cp -a "$real/build/." "$shadow/build/"
    cat > "$shadow/build/cmake/hexagon_fun.cmake" <<EOF
# Generated by build-engine.sh; never written into the repository or the SDK.
# The SDK's qaic is a Linux x86-64 ELF, so on this host the pinned qaic output
# for the pinned htp_iface.idl is copied instead of regenerating it.
include("$real/build/cmake/hexagon_fun.cmake")

function(build_idl idlFile currentTarget)
    if(NOT MCA_HTP_IFACE_DIR)
        message(FATAL_ERROR "build_idl shim: MCA_HTP_IFACE_DIR is not set")
    endif()
    message(STATUS "build_idl shim: using pre-generated interface from \${MCA_HTP_IFACE_DIR}")
    foreach(f htp_iface.h htp_iface_skel.c htp_iface_stub.c)
        if(NOT EXISTS "\${MCA_HTP_IFACE_DIR}/\${f}")
            message(FATAL_ERROR "build_idl shim: missing \${MCA_HTP_IFACE_DIR}/\${f}")
        endif()
        configure_file("\${MCA_HTP_IFACE_DIR}/\${f}" "\${CMAKE_CURRENT_BINARY_DIR}/\${f}" COPYONLY)
        set_source_files_properties("\${CMAKE_CURRENT_BINARY_DIR}/\${f}" PROPERTIES GENERATED TRUE)
    endforeach()
endfunction()
EOF
}

# ------------------------------------------------------------------ scratch --
log "output: $OUT_DIR"
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR/work" "$OUT_DIR/artifacts"
# The script cds into the extracted tree, so every path stored below must be absolute.
OUT_DIR=$(cd "$OUT_DIR" && pwd)
WORK="$OUT_DIR/work"
SRC="$WORK/src"
BUILD="$OUT_DIR/build"

log "tools"
{
    "$CMAKE_BIN" --version | head -1
    grep -E '^Pkg\.Revision' "$NDK/source.properties" || true
    "$NINJA_BIN" --version
    echo "ndk: $NDK"
    echo "hexagon_sdk: $HEXAGON_SDK_ROOT"
    echo "hexagon_tools: $HEXAGON_TOOLS_ROOT"
} | tee "$OUT_DIR/toolchain.txt"

# ---------------------------------------------------------------- extraction --
log "extracting pinned sources"
tar -xzf "$SDCPP_TARBALL" -C "$WORK"
mapfile -t sd_dirs < <(find "$WORK" -mindepth 1 -maxdepth 1 -type d -name 'stable-diffusion.cpp-*')
[ "${#sd_dirs[@]}" -eq 1 ] || die "expected exactly one stable-diffusion.cpp-* directory in $SDCPP_TARBALL"
mv "${sd_dirs[0]}" "$SRC"

mkdir -p "$WORK/ggml-extract"
tar -xzf "$GGML_TARBALL" -C "$WORK/ggml-extract"
mapfile -t gg_dirs < <(find "$WORK/ggml-extract" -mindepth 1 -maxdepth 1 -type d -name 'ggml-*')
[ "${#gg_dirs[@]}" -eq 1 ] || die "expected exactly one ggml-* directory in $GGML_TARBALL"
mkdir -p "$SRC/ggml"
cp -a "${gg_dirs[0]}/." "$SRC/ggml/"
[ -f "$SRC/ggml/CMakeLists.txt" ] || die "ggml was not wired into $SRC/ggml"
echo "sdcpp: $(basename "${sd_dirs[0]}")"
echo "ggml:  $(basename "${gg_dirs[0]}")"

# ------------------------------------------------------------------- patches --
# 0009 (latent dump) and 0010 (stage probe) are debug-only and excluded.
log "applying production patch queue 0001..0008 + 0011 + 0015 + 0016 (0009/0010 debug excluded)"
shopt -s nullglob
patches=("$PATCH_DIR"/000[1-8]-*.patch "$PATCH_DIR"/0011-*.patch "$PATCH_DIR"/0015-*.patch "$PATCH_DIR"/0016-*.patch)
expected_patches=11
# Diagnostic-only patches (e.g. the 0012 tile-memory probe) are never part of the production
# queue: they must be requested explicitly through a colon-separated EXTRA_PATCHES list.
if [ -n "${EXTRA_PATCHES:-}" ]; then
    IFS=':' read -r -a extra_patches <<< "$EXTRA_PATCHES"
    patches+=("${extra_patches[@]}")
    expected_patches=$((expected_patches + ${#extra_patches[@]}))
fi
shopt -u nullglob
[ "${#patches[@]}" -eq "$expected_patches" ] || die "expected $expected_patches patches in $PATCH_DIR, found ${#patches[@]}"

export GIT_CEILING_DIRECTORIES="$OUT_DIR"
cd "$SRC"
for p in "${patches[@]}"; do
    echo "-- $(basename "$p")  $(sha256sum "$p" | cut -d' ' -f1)"
    if command -v patch >/dev/null 2>&1; then
        # GNU patch is the primary path: inside a parent git worktree `git apply` can resolve
        # paths against the parent repo and silently "skip" every file.
        patch -p1 --forward --batch --dry-run -i "$p" \
            || die "patch does not apply: $(basename "$p")"
        patch -p1 --forward --batch -i "$p" \
            || die "patch failed to apply: $(basename "$p")"
    elif command -v git >/dev/null 2>&1; then
        git -c core.autocrlf=false apply -p1 --check "$p" \
            || die "patch does not apply: $(basename "$p")"
        git -c core.autocrlf=false apply -p1 --verbose "$p" \
            || die "patch failed to apply: $(basename "$p")"
    else
        die "neither 'patch' nor 'git' is available to apply the patch queue"
    fi
done

# Post-conditions that prove the production feature set (not 0009) is present.
grep -q 'generate_video_streaming' include/stable-diffusion.h \
    || die "post-patch check failed: generate_video_streaming missing from public header"
grep -q 'generate_video_streaming' src/stable-diffusion.cpp \
    || die "post-patch check failed: generate_video_streaming missing from implementation"
grep -q 'MCA_HTP_SKEL_NAME' ggml/src/ggml-hexagon/ggml-hexagon.cpp \
    || die "post-patch check failed: patch 0002 not present"
grep -q 'MCA_PER_FRAME_DECODE' src/pipeline/video.cpp \
    || die "post-patch check failed: patch 0006 not present"
grep -q 'Partial broadcasts' ggml/src/ggml-hexagon/ggml-hexagon.cpp \
    || die "post-patch check failed: patch 0011 not present"
[ -f src/pipeline/video_stream_result.h ] \
    || die "post-patch check failed: video_stream_result.h missing"
if grep -rq 'MCA_DUMP_FINAL_LATENT' .; then
    die "post-patch check failed: 0009 debug-latent code leaked into the production tree"
fi

# ------------------------------------------------------------------ configure --
# qaic and hexagon-clang are Linux ELF binaries in the installed SDK extract.
# When the host cannot execute qaic, configure against a shadow SDK root whose
# build_idl() copies the pinned pre-generated interface instead.
CONFIGURE_SDK_ROOT=$HEXAGON_SDK_ROOT
if ! host_can_execute "$QAIC_BIN"; then
    log "qaic is not host-executable; using pinned pre-generated HTP interface"
    verify_htp_iface "$HTP_IFACE_DIR"
    create_sdk_shadow "$HEXAGON_SDK_ROOT" "$OUT_DIR/toolchain/hexagon-sdk"
    CONFIGURE_SDK_ROOT="$OUT_DIR/toolchain/hexagon-sdk"
    export MCA_HTP_IFACE_DIR="$HTP_IFACE_DIR"
    echo "htp_iface source: $HTP_IFACE_DIR (pinned)"
else
    echo "htp_iface source: qaic ($QAIC_BIN)"
fi

log "configuring (NDK $(grep -E '^Pkg\.Revision' "$NDK/source.properties" | awk '{print $3}'))"
"$CMAKE_BIN" -S "$SRC" -B "$BUILD" -G Ninja \
    -DCMAKE_MAKE_PROGRAM="$NINJA_BIN" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-28 \
    -DCMAKE_BUILD_TYPE=$BUILD_TYPE \
    -DBUILD_SHARED_LIBS=ON \
    -DGGML_HEXAGON=ON \
    -DHEXAGON_SDK_ROOT="$CONFIGURE_SDK_ROOT" \
    -DPREBUILT_LIB_DIR="$CONFIGURE_SDK_ROOT/libs" \
    -DMCA_HTP_IFACE_DIR="$HTP_IFACE_DIR" \
    -DGGML_BUILD_TESTS=OFF \
    -DGGML_OPENMP=ON \
    -DSD_BUILD_EXAMPLES=OFF \
    -DSD_BUILD_SHARED_LIBS=ON 2>&1 | tee "$OUT_DIR/configure.log"

# ---------------------------------------------------------------------- build --
log "building target stable-diffusion (jobs=$JOBS)"
"$CMAKE_BIN" --build "$BUILD" --target stable-diffusion -- -j "$JOBS" 2>&1 | tee "$OUT_DIR/build-engine.log"

SO="$BUILD/bin/libstable-diffusion.so"
[ -f "$SO" ] || die "engine artifact missing: $SO"
cp "$SO" "$OUT_DIR/artifacts/libstable-diffusion.so"

SKEL_STATUS=NOT_RUN
SKEL=""
if hexagon_toolchain_runnable "$HEXAGON_CC"; then
    log "building target htp-v79 (Hexagon skeleton)"
    "$CMAKE_BIN" --build "$BUILD" --target htp-v79 -- -j "$JOBS" 2>&1 | tee "$OUT_DIR/build-skeleton.log"
    SKEL="$BUILD/ggml/src/ggml-hexagon/libggml-htp-v79.so"
    [ -f "$SKEL" ] || die "Hexagon skeleton artifact missing: $SKEL"
    cp "$SKEL" "$OUT_DIR/artifacts/libggml-htp-v79.so"
    SKEL_STATUS=BUILT
else
    reason="$HEXAGON_CC is not executable on this host (Windows requires a PE hexagon-clang.exe; the installed SDK ships Linux ELF tools and the NDK clang has no Hexagon target)"
    printf 'SKELETON=NOT_RUN: %s\n' "$reason" | tee "$OUT_DIR/build-skeleton.log"
    if [ "$REQUIRE_SKELETON" = "1" ]; then
        die "Hexagon skeleton requested but not buildable: $reason"
    fi
fi

# ------------------------------------------------------------------- verify --
hash_size() {
    local f=$1
    printf '  sha256 %s\n  bytes  %s\n  path   %s\n' \
        "$(sha256sum "$f" | cut -d' ' -f1)" \
        "$(wc -c < "$f" | tr -d ' ')" \
        "$f"
}

SHIPPED_SO="$REPO_ROOT/core/sd-native/src/main/jniLibs/arm64-v8a/libstable-diffusion.so"
SHIPPED_SKEL="$REPO_ROOT/core/sd-native/src/main/jniLibs/arm64-v8a/libggml-htp-mca-v79.so"

log "produced artifacts"
hash_size "$OUT_DIR/artifacts/libstable-diffusion.so"
[ "$SKEL_STATUS" = "BUILT" ] && hash_size "$OUT_DIR/artifacts/libggml-htp-v79.so"

log "comparison with shipped artifacts"
if [ -f "$SHIPPED_SO" ]; then
    echo "shipped libstable-diffusion.so:"
    hash_size "$SHIPPED_SO"
    if cmp -s "$OUT_DIR/artifacts/libstable-diffusion.so" "$SHIPPED_SO"; then
        echo "  RESULT: byte-identical"
    else
        echo "  RESULT: DIFFER (produced size $(wc -c < "$OUT_DIR/artifacts/libstable-diffusion.so" | tr -d ' ') vs shipped size $(wc -c < "$SHIPPED_SO" | tr -d ' '))"
    fi
else
    echo "shipped engine not found: $SHIPPED_SO (comparison NOT_RUN)"
fi
if [ "$SKEL_STATUS" = "BUILT" ] && [ -f "$SHIPPED_SKEL" ]; then
    echo "shipped Hexagon skeleton:"
    hash_size "$SHIPPED_SKEL"
    if cmp -s "$OUT_DIR/artifacts/libggml-htp-v79.so" "$SHIPPED_SKEL"; then
        echo "  RESULT: byte-identical"
    else
        echo "  RESULT: DIFFER"
    fi
elif [ "$SKEL_STATUS" != "BUILT" ]; then
    echo "produced Hexagon skeleton: NOT_RUN (see above)"
    if [ -f "$SHIPPED_SKEL" ]; then
        echo "shipped Hexagon skeleton (reference only):"
        hash_size "$SHIPPED_SKEL"
    fi
fi

log "ELF verification"
PREBUILT_DIR=$(find "$NDK/toolchains/llvm/prebuilt" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort | head -1)
READELF="$PREBUILT_DIR/bin/llvm-readelf"
[ -x "$READELF" ] || READELF="$READELF.exe"
if [ -x "$READELF" ]; then
    "$READELF" -h "$OUT_DIR/artifacts/libstable-diffusion.so" \
        | grep -E 'Class:|Data:|Type:|Machine:'
    echo "DT_NEEDED:"
    "$READELF" -d "$OUT_DIR/artifacts/libstable-diffusion.so" | grep -E 'NEEDED' || true
else
    echo "llvm-readelf not found under $PREBUILT_DIR (ELF verification NOT_RUN)"
fi

( cd "$OUT_DIR/artifacts" && sha256sum ./* > SHA256SUMS )
log "summary"
echo "engine:   BUILT  $(sha256sum "$OUT_DIR/artifacts/libstable-diffusion.so" | cut -d' ' -f1)"
echo "skeleton: $SKEL_STATUS"
echo "output:   $OUT_DIR"
