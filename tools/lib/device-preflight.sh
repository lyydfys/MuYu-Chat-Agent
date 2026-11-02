#!/usr/bin/env bash
# Shared preflight for the one-shot full-VAE device runners (480p run, 128 smoke).
# The caller sets ADB, SERIAL and APK, and may set MIN_BATTERY / MIN_FREE_GB before calling
# vae_preflight. Returns 0 only when every check passes.
#
# A locked phone has no CE storage: run-as fails and the app data dir looks wiped, which is
# easy to misread as data loss. A long run that starts below the battery floor dies mid-decode
# (the 04:15 480p run did, at 1%). Both are checked here instead of 30 minutes in.

vae_preflight() {
    local fail=0 level free_kb free_gb ce lora boot
    local min_battery=${MIN_BATTERY:-40}
    local min_free_gb=${MIN_FREE_GB:-3}
    if ! "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; then
        echo "FAIL device      not online"; return 1
    fi
    echo "OK   device      online"
    boot=$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n')
    if [ "$boot" = "1" ]; then echo "OK   boot        completed"; else echo "FAIL boot        sys.boot_completed='$boot'"; fail=1; fi
    ce=$("$ADB" -s "$SERIAL" shell 'run-as com.muyuchat.mca ls -d files >/dev/null 2>&1 && echo CE_OK || echo CE_LOCKED' | tr -d '\r\n')
    if [ "$ce" = "CE_OK" ]; then
        echo "OK   storage     app CE dir reachable (unlocked)"
    else
        echo "FAIL storage     app CE dir not reachable — phone is locked; unlock it (adb cannot pass a secure keyguard)"; fail=1
    fi
    level=$("$ADB" -s "$SERIAL" shell dumpsys battery 2>/dev/null | tr -d '\r' | awk -F': ' '/^  level:/{print $2; exit}')
    if [ -n "${level:-}" ] && [ "$level" -ge "$min_battery" ] 2>/dev/null; then
        echo "OK   battery     ${level}% (need ${min_battery}%)"
    else
        echo "FAIL battery     ${level:-unknown}% (need ${min_battery}%)"; fail=1
    fi
    free_kb=$("$ADB" -s "$SERIAL" shell 'df -k /data 2>/dev/null | tail -1' | tr -d '\r' | awk '{print $4}')
    case "$free_kb" in ''|*[!0-9]*) free_kb=0 ;; esac
    free_gb=$(( free_kb / 1024 / 1024 ))
    if [ "$free_gb" -ge "$min_free_gb" ]; then
        echo "OK   free space  ${free_gb} GB on /data (need ${min_free_gb} GB)"
    else
        echo "FAIL free space  ${free_gb} GB on /data (need ${min_free_gb} GB)"; fail=1
    fi
    if [ ! -f "$APK" ]; then
        echo "FAIL apk         missing $APK"; fail=1
    else
        # grep -c (not -q): with `set -o pipefail`, an early-exiting grep makes unzip die of
        # SIGPIPE and the pipeline would report failure even though the sentinel was found.
        local hits
        hits=$(unzip -p "$APK" "classes*.dex" 2>/dev/null | grep -a -c "fullVae=" || true)
        if [ "${hits:-0}" -gt 0 ] 2>/dev/null; then
            echo "OK   apk         $APK (carries the fullVae sentinel)"
        else
            echo "FAIL apk         $APK has no fullVae sentinel — stale dex; rebuild before running"; fail=1
        fi
    fi
    if [ "$ce" = "CE_OK" ]; then
        lora=$("$ADB" -s "$SERIAL" shell 'run-as com.muyuchat.mca ls files/video/runs/lora/turbo.safetensors 2>/dev/null' | tr -d '\r\n')
        if [ -n "$lora" ]; then echo "OK   lora        $lora"; else echo "FAIL lora        files/video/runs/lora/turbo.safetensors missing (stage the Turbo LoRA)"; fail=1; fi
    fi
    return $fail
}

# Writes full_vae_enabled without the UI. A fresh install has no XML (or no key yet), so patch a
# local copy and copy it into the CE dir; the caller must force-stop the app first.
vae_set_pref() {
    local value=$1 tmp="build/.mca_video_settings.$$.xml"
    mkdir -p build
    if ! "$ADB" -s "$SERIAL" exec-out run-as com.muyuchat.mca cat shared_prefs/mca_video_settings.xml > "$tmp" 2>/dev/null \
        || ! grep -q '<map>' "$tmp" 2>/dev/null; then
        printf "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n</map>\n" > "$tmp"
    fi
    if grep -q 'name="full_vae_enabled"' "$tmp"; then
        sed -i -E "s#(name=\"full_vae_enabled\" value=\")[a-z]*(\")#\1$value\2#" "$tmp"
    else
        sed -i "s#</map>#    <boolean name=\"full_vae_enabled\" value=\"$value\" />\n</map>#" "$tmp"
    fi
    "$ADB" -s "$SERIAL" push "$tmp" /data/local/tmp/mca_video_settings.xml >/dev/null || { rm -f "$tmp"; return 1; }
    rm -f "$tmp"
    "$ADB" -s "$SERIAL" shell 'run-as com.muyuchat.mca mkdir -p shared_prefs' >/dev/null 2>&1
    "$ADB" -s "$SERIAL" shell 'run-as com.muyuchat.mca cp /data/local/tmp/mca_video_settings.xml shared_prefs/mca_video_settings.xml' >/dev/null
}
