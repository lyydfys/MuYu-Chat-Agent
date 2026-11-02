#!/usr/bin/env bash
# P0 ABI gate: the packaged app must not carry two colliding copies of the
# stable-diffusion C ABI, and every engine symbol a native lib needs must be
# provided by an engine it actually links.
#
# Checks
#   1. duplicate-definitions : sd_* exported GLOBAL by more than one packaged .so
#   2. unresolved            : sd_* UND in <lib>.so but provided by nobody in the APK
#   3. engine-pair           : record which sdcpp tree each packaged engine came from
#
# Exit 0 = clean, 1 = violations, 2 = bad invocation.
set -uo pipefail
export MSYS_NO_PATHCONV=1

REPO=${REPO:-E:/MAC}
NDK=${NDK:-/e/model/android-sdk/ndk/29.0.14206865}
READELF=$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe
MERGED=${MERGED:-$REPO/app/build/intermediates/merged_native_libs/diagnostic/mergeDiagnosticNativeLibs/out/lib/arm64-v8a}

[ -x "$READELF" ] || { echo "FAIL: llvm-readelf not found at $READELF"; exit 2; }
[ -d "$MERGED" ]  || { echo "FAIL: merged lib dir not found: $MERGED"; exit 2; }

tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
viol=0

echo "== packaged libs =="
for so in "$MERGED"/*.so; do printf '  %-34s %s B\n' "$(basename "$so")" "$(stat -c%s "$so")"; done

# ---- collect exported sd_* per lib -------------------------------------------
for so in "$MERGED"/*.so; do
  b=$(basename "$so" .so)
  "$READELF" --dyn-syms -W "$so" 2>/dev/null \
    | awk '$5=="GLOBAL" && $7!="UND" && $8 ~ /^sd_/ {print $8}' | sort -u > "$tmp/exp_$b"
done

echo
echo "== check 1: duplicate GLOBAL sd_* definitions across packaged libs =="
cat "$tmp"/exp_* | sort | uniq -d > "$tmp/dup"
if [ -s "$tmp/dup" ]; then
  viol=1
  echo "  VIOLATION: $(wc -l < "$tmp/dup") sd_* symbol(s) defined by more than one packaged lib:"
  sed 's/^/    /' "$tmp/dup" | head -20
else
  echo "  ok: no duplicate sd_* definitions"
fi

# ---- check 2: unresolved sd_* per lib ---------------------------------------
echo
echo "== check 2: unresolved sd_* per packaged lib =="
for so in "$MERGED"/*.so; do
  b=$(basename "$so" .so)
  "$READELF" --dyn-syms -W "$so" 2>/dev/null \
    | awk '$7=="UND" && $8 ~ /^sd_/ {print $8}' | sort -u > "$tmp/und_$b"
  n=$(wc -l < "$tmp/und_$b")
  if [ "$n" -eq 0 ]; then echo "  $b: 0 unresolved sd_*"; continue; fi
  cat "$tmp"/exp_* | sort -u > "$tmp/all_exp"
  comm -23 "$tmp/und_$b" "$tmp/all_exp" > "$tmp/missing_$b"
  m=$(wc -l < "$tmp/missing_$b")
  if [ "$m" -eq 0 ]; then
    echo "  $b: $n unresolved sd_*, all provided by packaged libs"
  else
    viol=1
    echo "  VIOLATION $b: $m unresolved sd_* provided by NO packaged lib:"
    sed 's/^/    /' "$tmp/missing_$b" | head -20
  fi
done

# ---- check 3: which sdcpp tree each engine came from ------------------------
echo
echo "== check 3: engine provenance markers =="
for so in "$MERGED"/libstable-diffusion.so "$MERGED"/libmca_sd_native.so; do
  [ -f "$so" ] || continue
  b=$(basename "$so")
  stream=$("$READELF" --dyn-syms -W "$so" | grep -c " generate_video_streaming$")
  cancel=$("$READELF" --dyn-syms -W "$so" | grep -c " sd_set_cancel_callback$")
  # --exclude-libs,ALL hides the statically linked sdcpp of libmca_sd_native from
  # .dynsym, so fall back to the full symbol table to keep the provenance readable.
  scope="dynsym"
  if [ "$stream" -eq 0 ] && [ "$cancel" -eq 0 ]; then
    stream=$("$READELF" --syms -W "$so" 2>/dev/null | grep -c " generate_video_streaming$")
    cancel=$("$READELF" --syms -W "$so" 2>/dev/null | grep -c " sd_set_cancel_callback$")
    scope="symtab"
  fi
  echo "  $b: generate_video_streaming=$stream  sd_set_cancel_callback=$cancel  (via $scope)"
done
echo "  legend: a63954d tree -> streaming=1 cancel=0 ; 16b3846 tree -> streaming=0 cancel=1"
echo "  NOTE: both markers present in one process means two sdcpp trees coexist."
echo "  That is allowed only while check 1 is clean (no exported sd_* collisions)."

echo
if [ "$viol" -eq 0 ]; then echo "ABI GATE: PASS"; else echo "ABI GATE: FAIL"; fi
exit "$viol"
