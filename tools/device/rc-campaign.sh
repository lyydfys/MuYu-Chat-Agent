#!/system/bin/sh
# MCA RC production campaign device worker.
#
# The host wrapper owns admission, APK binding, pacing, UI-assisted model load/unload and report
# rendering. This script only runs on the Android device. It receives an untrusted campaign plan
# (pipe-delimited), executes the real Local API and records raw NDJSON.
#
# The API key is never written to a file or printed. Debug builds read their own private preference
# as the app UID. Release builds receive the key on stdin from the host process, and curl reads the
# Authorization header from a pipe-backed config (curl -K -), not from argv.
#
# Modes:
#   monitor <root> <pkg> <interval_s> <safety_seconds>
#   workload <root> <pkg> <chat_model> <image_model> <auth_mode> <plan_file>
#   authcheck <root> <pkg> <chat_model> <image_model> <auth_mode>
#
# auth_mode: debug | caller
# Plan fields (pipe separated, one request per line):
#   seq|request_id|kind|stream|scenario|retry_of|counted
# kind: chat | image
# scenario: normal | cancel | retry | disconnect | worker_death |
#           main_process_kill | background_foreground | load_unload
# retry_of: request id or '-' ; counted: 1 | 0

set -u
umask 022

API_HOST=127.0.0.1
API_PORT=11435
API="http://${API_HOST}:${API_PORT}"
SCHEMA_EVENT=mca-rc-request-event/1.0

die() {
  echo "FATAL|$*" >&2
  exit 9
}

iso_now() {
  date -u +%Y-%m-%dT%H:%M:%SZ
}

epoch_now() {
  date +%s
}

epoch_ms() {
  echo $(( $(date +%s) * 1000 ))
}

safe_name() {
  printf '%s' "$1" | tr -cd 'A-Za-z0-9._:-' | cut -c 1-96
}

hash_file() {
  if [ -f "$1" ]; then
    if command -v sha256sum >/dev/null 2>&1; then
      sha256sum "$1" 2>/dev/null | awk '{print $1; exit}'
    elif command -v toybox >/dev/null 2>&1; then
      toybox sha256sum "$1" 2>/dev/null | awk '{print $1; exit}'
    else
      echo ""
    fi
  else
    echo ""
  fi
}

file_bytes() {
  if [ -f "$1" ]; then
    wc -c < "$1" 2>/dev/null | tr -d ' '
  else
    echo 0
  fi
}

trim_num() {
  printf '%s' "$1" | tr -dc '0-9' | head -c 18
}

pid_of_exact() {
  ps -A -o PID,ARGS 2>/dev/null | awk -v n="$1" '$2 == n {print $1; exit}'
}

pid_of_main() {
  pid_of_exact "$PKG"
}

pid_of_chat() {
  pid_of_exact "$PKG:local_chat"
}

pid_of_image() {
  pid_of_exact "$PKG:local_image"
}

init_layout() {
  ROOT=$1
  PKG=$2
  LOGS="$ROOT/logs"
  TMP="$ROOT/tmp"
  RECEIPTS="$ROOT/receipts"
  mkdir -p "$LOGS" "$TMP" "$RECEIPTS" || die "cannot_create_device_layout"
  touch "$LOGS/device-stderr.log"
}

load_key() {
  AUTH_MODE=$1
  MCA_KEY=""
  if [ "$AUTH_MODE" = "debug" ]; then
    MCA_KEY=$(run-as "$PKG" cat "/data/data/$PKG/shared_prefs/mca_api.xml" 2>/dev/null \
      | sed -n 's/.*name="api_key"[^>]*>\([^<]*\)<.*/\1/p' | head -1)
    if [ -z "$MCA_KEY" ]; then
      die "api_key_unavailable_debug_prefs"
    fi
  elif [ "$AUTH_MODE" = "caller" ]; then
    IFS= read -r MCA_KEY || true
    if [ -z "$MCA_KEY" ]; then
      die "api_key_unavailable_caller_stdin"
    fi
  else
    die "invalid_auth_mode"
  fi
  case "$MCA_KEY" in
    *[!A-Za-z0-9._~+/=:-]*)
      unset MCA_KEY
      die "api_key_contains_unsupported_characters"
      ;;
  esac
}

curl_auth() {
  # Header content travels on curl stdin. It is never a process argument or a file.
  printf 'header = "Authorization: Bearer %s"\n' "$MCA_KEY" | curl -K - "$@"
}

curl_public() {
  curl "$@"
}

curl_code_get() {
  URL=$1
  TIMEOUT=$2
  curl_public -sS -m "$TIMEOUT" -o /dev/null -w '%{http_code}' "$URL" 2>/dev/null || true
}

stop_request() {
  RID=$1
  BODY="$TMP/stop-$RID.json"
  RESP="$TMP/stop-$RID.resp"
  printf '{"request_id":"%s"}' "$RID" > "$BODY"
  CODE=$(curl_auth -sS -m 90 -X POST \
    -H 'Content-Type: application/json' \
    -d @"$BODY" \
    -o "$RESP" \
    -w '%{http_code}' \
    "$API/v1/generate/stop" 2>/dev/null || true)
  [ -n "$CODE" ] || CODE=000
  ACCEPTED=0
  if [ "$CODE" = "200" ] && grep -q '"stopped":true' "$RESP" 2>/dev/null; then
    ACCEPTED=1
  fi
  REQ_STOP_HTTP=$CODE
  REQ_STOP_ACCEPTED=$ACCEPTED
}

chat_body() {
  RID=$1
  STREAM=$2
  MAXTOK=$3
  PROMPT=$4
  BODY="$TMP/$RID.request.json"
  if [ "$STREAM" = "1" ]; then
    printf '{"model":"%s","messages":[{"role":"user","content":"%s"}],"max_tokens":%s,"stream":true,"temperature":0.0}' \
      "$CHAT_MODEL" "$PROMPT" "$MAXTOK" > "$BODY"
  else
    printf '{"model":"%s","messages":[{"role":"user","content":"%s"}],"max_tokens":%s,"stream":false,"temperature":0.0}' \
      "$CHAT_MODEL" "$PROMPT" "$MAXTOK" > "$BODY"
  fi
  CHAT_BODY_PATH=$BODY
}

image_body() {
  RID=$1
  STEPS=$2
  SEED=$3
  BODY="$TMP/$RID.request.json"
  printf '{"model":"%s","prompt":"a minimal red square on white background request %s","sampler":"euler_a","steps":%s,"cfg_scale":1.0,"seed":%s,"size":"512x512","n":1,"response_format":"b64_json"}' \
    "$IMAGE_MODEL" "$SEED" "$STEPS" "$SEED" > "$BODY"
  IMAGE_BODY_PATH=$BODY
}

clean_chat_response() {
  RESP=$1
  STREAM=$2
  CODE=$3
  [ "$CODE" = "200" ] || return 1
  [ -f "$RESP" ] || return 1
  if [ "$STREAM" = "1" ]; then
    grep -q '^data:' "$RESP" 2>/dev/null && grep -q '\[DONE\]' "$RESP" 2>/dev/null
  else
    grep -q '"choices"' "$RESP" 2>/dev/null
  fi
}

clean_image_response() {
  RESP=$1
  CODE=$2
  BYTES=$3
  [ "$CODE" = "200" ] || return 1
  [ "$BYTES" -gt 1000 ] 2>/dev/null || return 1
  grep -q '"data"' "$RESP" 2>/dev/null
}

run_chat_once() {
  RID=$1
  STREAM=$2
  TIMEOUT=$3
  MAXTOK=$4
  PROMPT=$5
  chat_body "$RID" "$STREAM" "$MAXTOK" "$PROMPT"
  RESP="$TMP/$RID.response"
  ERR="$TMP/$RID.curl.err"
  if [ "$STREAM" = "1" ]; then
    CODE=$(curl_auth -sS -N -m "$TIMEOUT" -X POST \
      -H 'Content-Type: application/json' \
      -H "X-MCA-Request-Id: $RID" \
      -d @"$CHAT_BODY_PATH" -o "$RESP" -w '%{http_code}' \
      "$API/v1/chat/completions" 2>"$ERR")
    RC=$?
  else
    CODE=$(curl_auth -sS -m "$TIMEOUT" -X POST \
      -H 'Content-Type: application/json' \
      -H "X-MCA-Request-Id: $RID" \
      -d @"$CHAT_BODY_PATH" -o "$RESP" -w '%{http_code}' \
      "$API/v1/chat/completions" 2>"$ERR")
    RC=$?
  fi
  [ -n "$CODE" ] || CODE=000
  REQ_HTTP=$CODE
  REQ_EXIT=$RC
  REQ_BYTES=$(file_bytes "$RESP")
  REQ_SHA=$(hash_file "$RESP")
  REQ_CLEAN=0
  if clean_chat_response "$RESP" "$STREAM" "$CODE"; then
    REQ_CLEAN=1
  fi
  REQ_ERR_CODE=""
  if [ "$REQ_CLEAN" = "0" ] && [ -f "$RESP" ]; then
    REQ_ERR_CODE=$(sed -n 's/.*"code":"\([A-Za-z0-9_]*\)".*/\1/p' "$RESP" | head -1)
  fi
}

run_image_once() {
  RID=$1
  STEPS=$2
  TIMEOUT=$3
  SEED=$(printf '%s' "$RID" | cksum | awk '{print $1}')
  image_body "$RID" "$STEPS" "$SEED"
  RESP="$TMP/$RID.response"
  ERR="$TMP/$RID.curl.err"
  CODE=$(curl_auth -sS -m "$TIMEOUT" -X POST \
    -H 'Content-Type: application/json' \
    -H "X-MCA-Request-Id: $RID" \
    -d @"$IMAGE_BODY_PATH" -o "$RESP" -w '%{http_code}' \
    "$API/v1/images/generations" 2>"$ERR")
  RC=$?
  [ -n "$CODE" ] || CODE=000
  REQ_HTTP=$CODE
  REQ_EXIT=$RC
  REQ_BYTES=$(file_bytes "$RESP")
  REQ_SHA=$(hash_file "$RESP")
  REQ_CLEAN=0
  if clean_image_response "$RESP" "$CODE" "$REQ_BYTES"; then
    REQ_CLEAN=1
  fi
  REQ_ERR_CODE=""
  if [ "$REQ_CLEAN" = "0" ] && [ -f "$RESP" ]; then
    REQ_ERR_CODE=$(sed -n 's/.*"code":"\([A-Za-z0-9_]*\)".*/\1/p' "$RESP" | head -1)
  fi
}

start_chat_bg() {
  RID=$1
  STREAM=$2
  TIMEOUT=$3
  MAXTOK=$4
  PROMPT=$5
  LIMIT_RATE=$6
  chat_body "$RID" "$STREAM" "$MAXTOK" "$PROMPT"
  BG_RESP="$TMP/$RID.response"
  BG_CODE="$TMP/$RID.http"
  BG_ERR="$TMP/$RID.curl.err"
  if [ "$STREAM" = "1" ]; then
    if [ "$LIMIT_RATE" -gt 0 ] 2>/dev/null; then
      ( curl_auth -sS -N -m "$TIMEOUT" --limit-rate "$LIMIT_RATE" -X POST \
          -H 'Content-Type: application/json' \
          -H "X-MCA-Request-Id: $RID" \
          -d @"$CHAT_BODY_PATH" -o "$BG_RESP" -w '%{http_code}' \
          "$API/v1/chat/completions" > "$BG_CODE" 2>"$BG_ERR" ) &
    else
      ( curl_auth -sS -N -m "$TIMEOUT" -X POST \
          -H 'Content-Type: application/json' \
          -H "X-MCA-Request-Id: $RID" \
          -d @"$CHAT_BODY_PATH" -o "$BG_RESP" -w '%{http_code}' \
          "$API/v1/chat/completions" > "$BG_CODE" 2>"$BG_ERR" ) &
    fi
  else
    ( curl_auth -sS -m "$TIMEOUT" -X POST \
        -H 'Content-Type: application/json' \
        -H "X-MCA-Request-Id: $RID" \
        -d @"$CHAT_BODY_PATH" -o "$BG_RESP" -w '%{http_code}' \
        "$API/v1/chat/completions" > "$BG_CODE" 2>"$BG_ERR" ) &
  fi
  BG_PID=$!
}

start_image_bg() {
  RID=$1
  STEPS=$2
  TIMEOUT=$3
  SEED=$(printf '%s' "$RID" | cksum | awk '{print $1}')
  image_body "$RID" "$STEPS" "$SEED"
  BG_RESP="$TMP/$RID.response"
  BG_CODE="$TMP/$RID.http"
  BG_ERR="$TMP/$RID.curl.err"
  ( curl_auth -sS -m "$TIMEOUT" -X POST \
      -H 'Content-Type: application/json' \
      -H "X-MCA-Request-Id: $RID" \
      -d @"$IMAGE_BODY_PATH" -o "$BG_RESP" -w '%{http_code}' \
      "$API/v1/images/generations" > "$BG_CODE" 2>"$BG_ERR" ) &
  BG_PID=$!
}

process_alive() {
  P=$1
  [ -n "$P" ] || return 1
  [ -r "/proc/$P/stat" ] || return 1
  S=$(awk '{print $3}' "/proc/$P/stat" 2>/dev/null)
  [ "$S" != "Z" ]
}

kill_tree() {
  P=$1
  [ -n "$P" ] || return 0
  if [ -r "/proc/$P/task/$P/children" ]; then
    for C in $(cat "/proc/$P/task/$P/children" 2>/dev/null); do
      kill -9 "$C" 2>/dev/null || true
    done
  fi
  kill -9 "$P" 2>/dev/null || true
}

wait_bg() {
  WAIT_SECONDS=$1
  I=0
  BG_KILLED=0
  while [ "$I" -lt "$WAIT_SECONDS" ]; do
    if ! process_alive "$BG_PID"; then
      break
    fi
    sleep 1
    I=$((I + 1))
  done
  if process_alive "$BG_PID"; then
    kill_tree "$BG_PID"
    BG_KILLED=1
  fi
  wait "$BG_PID" 2>/dev/null
  BG_EXIT=$?
}

read_bg_result() {
  STREAM=$1
  BG_HTTP=$(cat "$BG_CODE" 2>/dev/null | tr -d '[:space:]')
  [ -n "$BG_HTTP" ] || BG_HTTP=000
  BG_BYTES=$(file_bytes "$BG_RESP")
  BG_SHA=$(hash_file "$BG_RESP")
  BG_CLEAN=0
  if [ "$KIND_FOR_CLEAN" = "image" ]; then
    if clean_image_response "$BG_RESP" "$BG_HTTP" "$BG_BYTES"; then
      BG_CLEAN=1
    fi
  else
    if clean_chat_response "$BG_RESP" "$STREAM" "$BG_HTTP"; then
      BG_CLEAN=1
    fi
  fi
  BG_ERROR_CODE=""
  if [ "$BG_CLEAN" = "0" ] && [ -f "$BG_RESP" ]; then
    BG_ERROR_CODE=$(sed -n 's/.*"code":"\([A-Za-z0-9_]*\)".*/\1/p' "$BG_RESP" | head -1)
  fi
}

reset_event() {
  EV_RID=""
  EV_SEQ=0
  EV_KIND=chat
  EV_STREAM=0
  EV_SCENARIO=normal
  EV_RETRY_OF=""
  EV_COUNTED=1
  EV_OUTCOME=failure
  EV_HTTP=0
  EV_EXIT=0
  EV_STARTED=""
  EV_ENDED=""
  EV_DURATION_MS=0
  EV_ERROR_CODE=""
  EV_RESP_BYTES=0
  EV_RESP_SHA=""
  EV_SCENARIO_RESULT=FAILED
  EV_INJECTION_VERIFIED=0
  EV_STOP_HTTP=0
  EV_STOP_ACCEPTED=0
  EV_COLD_START_HEALTH=0
  EV_BACKGROUND_OBSERVED=0
  EV_FOREGROUND_RESUMED=0
  EV_RECOVERY_HTTP=0
  EV_RECOVERY_BYTES=0
  EV_WORKER_PID=0
  EV_WORKER_PID_AFTER=0
  EV_TARGET_WORKER_SEEN=0
}

emit_event() {
  FILE=$1
  printf '{"schema":"%s","request_id":"%s","sequence":%s,"kind":"%s","stream":%s,"scenario":"%s","retry_of":"%s","counts_toward_workload":%s,"outcome":"%s","http_status":%s,"curl_exit":%s,"started_at":"%s","ended_at":"%s","duration_ms":%s,"error_code":"%s","response_bytes":%s,"response_sha256":"%s","scenario_result":"%s","injection_verified":%s,"stop_http_status":%s,"stop_accepted":%s,"cold_start_health_status":%s,"background_observed":%s,"foreground_resumed":%s,"recovery_http_status":%s,"recovery_bytes":%s,"worker_pid":%s,"worker_pid_after":%s,"target_worker_seen":%s}\n' \
    "$SCHEMA_EVENT" "$EV_RID" "$EV_SEQ" "$EV_KIND" "$EV_STREAM" "$EV_SCENARIO" \
    "$EV_RETRY_OF" "$EV_COUNTED" "$EV_OUTCOME" "$EV_HTTP" "$EV_EXIT" \
    "$EV_STARTED" "$EV_ENDED" "$EV_DURATION_MS" "$EV_ERROR_CODE" "$EV_RESP_BYTES" \
    "$EV_RESP_SHA" "$EV_SCENARIO_RESULT" "$EV_INJECTION_VERIFIED" "$EV_STOP_HTTP" \
    "$EV_STOP_ACCEPTED" "$EV_COLD_START_HEALTH" "$EV_BACKGROUND_OBSERVED" \
    "$EV_FOREGROUND_RESUMED" "$EV_RECOVERY_HTTP" "$EV_RECOVERY_BYTES" \
    "$EV_WORKER_PID" "$EV_WORKER_PID_AFTER" "$EV_TARGET_WORKER_SEEN" >> "$FILE"
}

finish_event() {
  EV_ENDED=$(iso_now)
  EV_DURATION_MS=$(( $(epoch_ms) - START_MS ))
}

prune_tmp() {
  for F in "$TMP"/*.response; do
    [ -f "$F" ] || continue
    B=$(file_bytes "$F")
    [ "$B" -gt 262144 ] 2>/dev/null && rm -f "$F"
  done
}

handle_normal() {
  if [ "$EV_KIND" = "image" ]; then
    run_image_once "$EV_RID" 4 900
    EV_WORKER_PID=$(pid_of_image)
    [ -n "$EV_WORKER_PID" ] || EV_WORKER_PID=0
    if [ "$REQ_CLEAN" = "1" ]; then
      EV_OUTCOME=success
      EV_SCENARIO_RESULT=PASSED
    else
      EV_OUTCOME=failure
      EV_SCENARIO_RESULT=FAILED
    fi
  else
    run_chat_once "$EV_RID" "$EV_STREAM" 300 16 "Reply with OK for campaign ${EV_SEQ}."
    if [ "$REQ_CLEAN" = "1" ]; then
      EV_OUTCOME=success
      EV_SCENARIO_RESULT=PASSED
    else
      EV_OUTCOME=failure
      EV_SCENARIO_RESULT=FAILED
    fi
  fi
  EV_HTTP=$REQ_HTTP
  EV_EXIT=$REQ_EXIT
  EV_ERROR_CODE=$REQ_ERR_CODE
  EV_RESP_BYTES=$REQ_BYTES
  EV_RESP_SHA=$REQ_SHA
}

handle_retry() {
  sleep 1
  if [ "$EV_KIND" = "image" ]; then
    run_image_once "$EV_RID" 2 600
    EV_WORKER_PID=$(pid_of_image)
    [ -n "$EV_WORKER_PID" ] || EV_WORKER_PID=0
    if [ "$REQ_CLEAN" = "1" ]; then EV_OUTCOME=success; EV_SCENARIO_RESULT=PASSED; else EV_OUTCOME=failure; EV_SCENARIO_RESULT=FAILED; fi
  else
    run_chat_once "$EV_RID" "$EV_STREAM" 300 12 "Recovery retry ${EV_SEQ}."
    if [ "$REQ_CLEAN" = "1" ]; then EV_OUTCOME=success; EV_SCENARIO_RESULT=PASSED; else EV_OUTCOME=failure; EV_SCENARIO_RESULT=FAILED; fi
  fi
  EV_HTTP=$REQ_HTTP
  EV_EXIT=$REQ_EXIT
  EV_ERROR_CODE=$REQ_ERR_CODE
  EV_RESP_BYTES=$REQ_BYTES
  EV_RESP_SHA=$REQ_SHA
}

handle_cancel() {
  start_chat_bg "$EV_RID" "$EV_STREAM" 180 512 "Write a long numbered list for cancellation ${EV_SEQ}." 0
  sleep 2
  if process_alive "$BG_PID"; then
    EV_INJECTION_VERIFIED=1
    stop_request "$EV_RID"
    wait_bg 60
    KIND_FOR_CLEAN=chat
    read_bg_result "$EV_STREAM"
    EV_HTTP=$BG_HTTP
    EV_EXIT=$BG_EXIT
    EV_ERROR_CODE=$BG_ERROR_CODE
    EV_RESP_BYTES=$BG_BYTES
    EV_RESP_SHA=$BG_SHA
    EV_STOP_HTTP=$REQ_STOP_HTTP
    EV_STOP_ACCEPTED=$REQ_STOP_ACCEPTED
    if [ "$REQ_STOP_ACCEPTED" = "1" ] && [ "$BG_KILLED" = "0" ]; then
      EV_OUTCOME=cancel
      EV_SCENARIO_RESULT=PASSED
    else
      EV_OUTCOME=failure
      EV_SCENARIO_RESULT=FAILED
      [ -n "$EV_ERROR_CODE" ] || EV_ERROR_CODE=cancel_contract_not_observed
    fi
  else
    wait_bg 5
    EV_OUTCOME=failure
    EV_ERROR_CODE=request_finished_before_cancel
    EV_SCENARIO_RESULT=FAILED
  fi
}

handle_disconnect() {
  start_chat_bg "$EV_RID" 1 180 512 "Stream for disconnect ${EV_SEQ}." 0
  sleep 2
  if process_alive "$BG_PID"; then
    EV_INJECTION_VERIFIED=1
    kill_tree "$BG_PID"
    wait_bg 10
    EV_EXIT=$BG_EXIT
    EV_HTTP=000
    EV_ERROR_CODE=client_disconnect
    EV_SCENARIO_RESULT=PASSED
  else
    wait_bg 5
    EV_SCENARIO_RESULT=FAILED
    EV_ERROR_CODE=request_finished_before_disconnect
  fi
  EV_OUTCOME=failure
}

inject_worker_death() {
  TARGET_PID=$1
  if [ "$AUTH_MODE" = "debug" ]; then
    run-as "$PKG" kill -9 "$TARGET_PID" 2>/dev/null || true
  else
    am stop-service -n "$PKG/.LocalImageWorkerService" --user 0 >/dev/null 2>&1 || true
    sleep 1
    if [ -n "$(pid_of_image)" ]; then
      cmd activity stop-service -n "$PKG/.LocalImageWorkerService" --user 0 >/dev/null 2>&1 || true
    fi
  fi
  I=0
  while [ "$I" -lt 15 ]; do
    [ -z "$(pid_of_image)" ] && break
    I=$((I + 1))
    sleep 1
  done
}

handle_worker_death() {
  start_image_bg "$EV_RID" 4 900
  I=0
  WPID=""
  while [ "$I" -lt 30 ]; do
    WPID=$(pid_of_image)
    if [ -n "$WPID" ] && process_alive "$BG_PID"; then
      break
    fi
    I=$((I + 1))
    sleep 1
  done
  if [ -z "$WPID" ] || ! process_alive "$BG_PID"; then
    wait_bg 20
    EV_SCENARIO_RESULT=FAILED
    EV_ERROR_CODE=worker_death_target_not_in_flight
    EV_OUTCOME=failure
    return
  fi
  EV_TARGET_WORKER_SEEN=1
  EV_WORKER_PID=$WPID
  inject_worker_death "$WPID"
  AFTER=$(pid_of_image)
  [ -n "$AFTER" ] || AFTER=0
  EV_WORKER_PID_AFTER=$AFTER
  if [ "$AFTER" = "$WPID" ]; then
    kill_tree "$BG_PID"
    wait_bg 10
    EV_SCENARIO_RESULT=FAILED
    EV_ERROR_CODE=worker_death_injection_did_not_take_effect
    EV_OUTCOME=failure
    return
  fi
  EV_INJECTION_VERIFIED=1
  wait_bg 60
  KIND_FOR_CLEAN=image
  read_bg_result 0
  EV_HTTP=$BG_HTTP
  EV_EXIT=$BG_EXIT
  EV_ERROR_CODE=$BG_ERROR_CODE
  EV_RESP_BYTES=$BG_BYTES
  EV_RESP_SHA=$BG_SHA
  if [ "$BG_CLEAN" = "1" ] || [ "$BG_KILLED" = "1" ]; then
    EV_SCENARIO_RESULT=FAILED
    EV_ERROR_CODE=worker_death_request_not_cut_cleanly
    EV_OUTCOME=failure
    return
  fi
  run_image_once "${EV_RID}control" 2 600
  EV_RECOVERY_HTTP=$REQ_HTTP
  EV_RECOVERY_BYTES=$REQ_BYTES
  if [ "$REQ_CLEAN" = "1" ]; then
    EV_SCENARIO_RESULT=PASSED
  else
    EV_SCENARIO_RESULT=FAILED
    EV_ERROR_CODE=worker_death_recovery_failed
  fi
  EV_OUTCOME=failure
}

handle_main_kill() {
  start_chat_bg "$EV_RID" 1 240 512 "Stream that will be interrupted by main process death ${EV_SEQ}." 0
  sleep 2
  if ! process_alive "$BG_PID"; then
    wait_bg 5
    EV_SCENARIO_RESULT=FAILED
    EV_ERROR_CODE=request_finished_before_main_kill
    EV_OUTCOME=failure
    return
  fi
  am force-stop "$PKG" >/dev/null 2>&1 || true
  I=0
  while [ "$I" -lt 12 ]; do
    [ -z "$(pid_of_main)" ] && break
    I=$((I + 1))
    sleep 1
  done
  if [ -n "$(pid_of_main)" ]; then
    EV_SCENARIO_RESULT=FAILED
    EV_ERROR_CODE=main_process_injection_did_not_take_effect
    EV_OUTCOME=failure
    kill_tree "$BG_PID"
    wait_bg 10
    return
  fi
  EV_INJECTION_VERIFIED=1
  wait_bg 45
  KIND_FOR_CLEAN=chat
  read_bg_result 1
  EV_HTTP=$BG_HTTP
  EV_EXIT=$BG_EXIT
  EV_ERROR_CODE=${BG_ERROR_CODE:-main_process_killed}
  EV_RESP_BYTES=$BG_BYTES
  EV_RESP_SHA=$BG_SHA
  am start -W -n "$PKG/.MainActivity" >/dev/null 2>&1 || true
  I=0
  HEALTH=0
  while [ "$I" -lt 120 ]; do
    HEALTH=$(curl_code_get "$API/health" 5)
    [ "$HEALTH" = "200" ] && break
    I=$((I + 1))
    sleep 1
  done
  EV_COLD_START_HEALTH=$HEALTH
  if [ "$BG_CLEAN" = "0" ] && [ "$HEALTH" = "200" ]; then
    EV_SCENARIO_RESULT=PASSED
  else
    EV_SCENARIO_RESULT=FAILED
    EV_ERROR_CODE=main_kill_cold_start_incomplete
  fi
  EV_OUTCOME=failure
}

handle_background_foreground() {
  start_chat_bg "$EV_RID" 1 300 384 "Stream while the app moves to background ${EV_SEQ}." 200
  sleep 2
  if ! process_alive "$BG_PID"; then
    wait_bg 5
    EV_SCENARIO_RESULT=FAILED
    EV_ERROR_CODE=request_finished_before_background
    EV_OUTCOME=failure
    return
  fi
  input keyevent KEYCODE_HOME >/dev/null 2>&1 || true
  sleep 2
  TOP=$(dumpsys activity activities 2>/dev/null | grep -m1 'topResumedActivity' || true)
  case "$TOP" in
    *"$PKG/.MainActivity"*) EV_BACKGROUND_OBSERVED=0 ;;
    *) EV_BACKGROUND_OBSERVED=1 ;;
  esac
  am start -W -n "$PKG/.MainActivity" >/dev/null 2>&1 || true
  sleep 1
  TOP2=$(dumpsys activity activities 2>/dev/null | grep -m1 'topResumedActivity' || true)
  case "$TOP2" in
    *"$PKG/.MainActivity"*) EV_FOREGROUND_RESUMED=1 ;;
    *) EV_FOREGROUND_RESUMED=0 ;;
  esac
  wait_bg 180
  KIND_FOR_CLEAN=chat
  read_bg_result 1
  EV_HTTP=$BG_HTTP
  EV_EXIT=$BG_EXIT
  EV_ERROR_CODE=$BG_ERROR_CODE
  EV_RESP_BYTES=$BG_BYTES
  EV_RESP_SHA=$BG_SHA
  if [ "$BG_CLEAN" = "1" ] && [ "$EV_BACKGROUND_OBSERVED" = "1" ] && [ "$EV_FOREGROUND_RESUMED" = "1" ]; then
    EV_OUTCOME=success
    EV_SCENARIO_RESULT=PASSED
  else
    EV_OUTCOME=failure
    EV_SCENARIO_RESULT=FAILED
    [ -n "$EV_ERROR_CODE" ] || EV_ERROR_CODE=background_recovery_not_observed
  fi
}

run_workload() {
  init_layout "$1" "$2"
  CHAT_MODEL=$3
  IMAGE_MODEL=$4
  AUTH_MODE=$5
  PLAN=$6
  load_key "$AUTH_MODE"
  FILE="$LOGS/requests.ndjson"
  : > "$FILE"
  COUNT=0
  while IFS='|' read -r SEQ RID KIND STREAM SCENARIO RETRY_OF COUNTED; do
    [ -n "${SEQ:-}" ] || continue
    reset_event
    EV_RID=$(safe_name "$RID")
    EV_SEQ=$(trim_num "$SEQ")
    EV_KIND=$KIND
    EV_STREAM=$([ "$STREAM" = "1" ] && echo 1 || echo 0)
    EV_SCENARIO=$SCENARIO
    [ "$RETRY_OF" = "-" ] && EV_RETRY_OF="" || EV_RETRY_OF=$(safe_name "$RETRY_OF")
    EV_COUNTED=$([ "$COUNTED" = "0" ] && echo 0 || echo 1)
    [ -n "$EV_RID" ] || die "invalid_request_id_in_plan"
    EV_STARTED=$(iso_now)
    START_MS=$(epoch_ms)
    case "$SCENARIO" in
      normal|load_unload|warmup) handle_normal ;;
      retry) handle_retry ;;
      cancel) handle_cancel ;;
      disconnect) handle_disconnect ;;
      worker_death) handle_worker_death ;;
      main_process_kill) handle_main_kill ;;
      background_foreground) handle_background_foreground ;;
      *) EV_SCENARIO_RESULT=FAILED; EV_ERROR_CODE=unknown_scenario; EV_OUTCOME=failure ;;
    esac
    finish_event
    emit_event "$FILE"
    prune_tmp
    COUNT=$((COUNT + 1))
    echo "REQUEST_DONE|$EV_RID|$EV_SCENARIO|$EV_OUTCOME|$EV_SCENARIO_RESULT"
  done < "$PLAN"
  echo "WORKLOAD_DONE|count=$COUNT|utc=$(iso_now)"
}

run_authcheck() {
  init_layout "$1" "$2"
  CHAT_MODEL=$3
  IMAGE_MODEL=$4
  AUTH_MODE=$5
  load_key "$AUTH_MODE"
  NOAUTH=$(curl_code_get "$API/v1/mca/runtime" 20)
  BAD=$(curl_public -sS -m 20 -o /dev/null -w '%{http_code}' \
    -H 'Authorization: Bearer mca-invalid-token' "$API/v1/mca/runtime" 2>/dev/null || true)
  [ -n "$BAD" ] || BAD=000
  VALID=$(curl_auth -sS -m 60 -o /dev/null -w '%{http_code}' "$API/v1/mca/runtime" 2>/dev/null || true)
  [ -n "$VALID" ] || VALID=000
  printf '{"schema":"mca-rc-auth-check/1.0","timestamp":"%s","no_auth_http":%s,"invalid_auth_http":%s,"valid_auth_http":%s}\n' \
    "$(iso_now)" "$NOAUTH" "$BAD" "$VALID" >> "$LOGS/auth-check.ndjson"
  echo "AUTHCHECK|noauth=$NOAUTH|bad=$BAD|valid=$VALID"
}

meminfo_kb() {
  LABEL=$1
  printf '%s\n' "$MEMINFO_TEXT" | awk -v label="$LABEL" 'index($0,label)>0 {print $3; exit}'
}

pss_kb() {
  PID=$1
  V=$(meminfo_kb "TOTAL PSS:")
  if [ -z "$V" ] && [ "$CAN_RUN_AS" = "1" ]; then
    V=$(run-as "$PKG" cat "/proc/$PID/smaps_rollup" 2>/dev/null | awk '/^Pss:/ {s+=$2} END {if (s>0) print s}')
  fi
  trim_num "$V"
}

native_kb() {
  PID=$1
  trim_num "$(meminfo_kb "Native Heap")"
}

rss_kb() {
  PID=$1
  trim_num "$(awk '/^VmRSS:/ {print $2; exit}' "/proc/$PID/status" 2>/dev/null)"
}

fd_count_for() {
  PID=$1
  if [ "$CAN_RUN_AS" = "1" ]; then
    trim_num "$(run-as "$PKG" ls "/proc/$PID/fd" 2>/dev/null | wc -l)"
    return
  fi
  V=$(printf '%s\n' "$MEMINFO_TEXT" | awk '/Open FDs:/ {print $3; exit}')
  V=$(trim_num "$V")
  if [ -n "$V" ] && [ "$V" != "0" ]; then
    echo "$V"
    return
  fi
  trim_num "$(dumpsys fdinfo "$PID" 2>/dev/null | grep -c '^ *[0-9]')"
}

thread_count_for() {
  PID=$1
  V=$(trim_num "$(awk '/^Threads:/ {print $2; exit}' "/proc/$PID/status" 2>/dev/null)")
  if [ -z "$V" ] || [ "$V" = "0" ]; then
    V=$(trim_num "$(ps -T -p "$PID" 2>/dev/null | tail -n +2 | wc -l)")
  fi
  echo "${V:-0}"
}

max_temperature_millic() {
  MAX=0
  for F in /sys/class/thermal/thermal_zone*/temp; do
    [ -r "$F" ] || continue
    V=$(cat "$F" 2>/dev/null | tr -dc '0-9' | head -c 12)
    [ -n "$V" ] || continue
    [ "$V" -gt "$MAX" ] 2>/dev/null && MAX=$V
  done
  echo "$MAX"
}

mem_available_kb() {
  trim_num "$(awk '/^MemAvailable:/ {print $2; exit}' /proc/meminfo 2>/dev/null)"
}

sample_once() {
  NOW=$(iso_now)
  ELAPSED=$(( $(epoch_now) - MONITOR_START_EPOCH ))
  PROCS=""
  PCOUNT=0
  EXPECTED=0
  RSS_SUM=0
  PSS_SUM=0
  NATIVE_SUM=0
  FD_SUM=0
  THREAD_SUM=0
  PRESENT_MAIN=0
  PRESENT_CHAT=0
  PRESENT_IMAGE=0
  MAIN_SAMPLED=0
  QUALITY=OK
  for ROW in $(ps -A -o PID,ARGS 2>/dev/null | awk -v m="$PKG" '$2==m || $2==m":local_chat" || $2==m":local_image" {print $1":"$2}'); do
    EXPECTED=$((EXPECTED + 1))
    P=${ROW%%:*}
    N=${ROW#*:}
    ROLE=other
    case "$N" in
      "$PKG") ROLE=main; PRESENT_MAIN=1 ;;
      "$PKG:local_chat") ROLE=local_chat; PRESENT_CHAT=1 ;;
      "$PKG:local_image") ROLE=local_image; PRESENT_IMAGE=1 ;;
    esac
    MEMINFO_TEXT=$(dumpsys meminfo "$P" 2>/dev/null)
    PSS=$(pss_kb "$P")
    RSS=$(rss_kb "$P")
    [ -n "$RSS" ] || RSS=$PSS
    NAT=$(native_kb "$P")
    FD=$(fd_count_for "$P")
    TH=$(thread_count_for "$P")
    if [ -z "$PSS" ] || [ "$PSS" = "0" ] || [ -z "$RSS" ] || [ "$RSS" = "0" ] || [ -z "$NAT" ] || [ -z "$FD" ] || [ "$FD" = "0" ] || [ -z "$TH" ] || [ "$TH" = "0" ]; then
      QUALITY=DEGRADED
      continue
    fi
    RSS_B=$((RSS * 1024))
    PSS_B=$((PSS * 1024))
    NAT_B=$((NAT * 1024))
    RSS_SUM=$((RSS_SUM + RSS_B))
    PSS_SUM=$((PSS_SUM + PSS_B))
    NATIVE_SUM=$((NATIVE_SUM + NAT_B))
    FD_SUM=$((FD_SUM + FD))
    THREAD_SUM=$((THREAD_SUM + TH))
    SEP=""
    [ "$PCOUNT" -gt 0 ] && SEP=","
    PROCS="$PROCS$SEP{\"name\":\"$N\",\"process_name\":\"$N\",\"role\":\"$ROLE\",\"pid\":$P,\"rss_bytes\":$RSS_B,\"pss_bytes\":$PSS_B,\"native_heap_bytes\":$NAT_B,\"fd_count\":$FD,\"thread_count\":$TH}"
    PCOUNT=$((PCOUNT + 1))
    [ "$ROLE" = "main" ] && MAIN_SAMPLED=1
  done
  MEMAVAIL=$(mem_available_kb)
  [ -n "$MEMAVAIL" ] || MEMAVAIL=0
  TEMP=$(max_temperature_millic)
  [ -n "$TEMP" ] || TEMP=0
  PRESENT_MAIN_B=$([ "$PRESENT_MAIN" = "1" ] && echo true || echo false)
  PRESENT_CHAT_B=$([ "$PRESENT_CHAT" = "1" ] && echo true || echo false)
  PRESENT_IMAGE_B=$([ "$PRESENT_IMAGE" = "1" ] && echo true || echo false)
  if [ "$EXPECTED" -eq 0 ]; then
    printf '{"schema":"mca-rc-memory-sample/1.0","type":"sample_unavailable","timestamp":"%s","elapsed_seconds":%s,"quality":"NO_PACKAGE_PROCESS","process_presence":{"main":%s,"local_chat":%s,"local_image":%s},"mem_available_bytes":%s,"temperature_millic":%s}\n' \
      "$NOW" "$ELAPSED" "$PRESENT_MAIN_B" "$PRESENT_CHAT_B" "$PRESENT_IMAGE_B" \
      "$((MEMAVAIL * 1024))" "$TEMP" >> "$LOGS/samples.ndjson"
  elif [ "$PCOUNT" -ne "$EXPECTED" ] || [ "$MAIN_SAMPLED" -ne 1 ]; then
    printf '{"schema":"mca-rc-memory-sample/1.0","type":"sample_invalid","timestamp":"%s","elapsed_seconds":%s,"quality":"%s","expected_processes":%s,"sampled_processes":%s,"process_presence":{"main":%s,"local_chat":%s,"local_image":%s},"mem_available_bytes":%s,"temperature_millic":%s}\n' \
      "$NOW" "$ELAPSED" "$QUALITY" "$EXPECTED" "$PCOUNT" "$PRESENT_MAIN_B" "$PRESENT_CHAT_B" \
      "$PRESENT_IMAGE_B" "$((MEMAVAIL * 1024))" "$TEMP" >> "$LOGS/samples.ndjson"
  else
    printf '{"schema":"mca-rc-memory-sample/1.0","type":"memory_sample","timestamp":"%s","elapsed_seconds":%s,"rss_bytes":%s,"pss_bytes":%s,"native_heap_bytes":%s,"fd_count":%s,"thread_count":%s,"mem_available_bytes":%s,"temperature_millic":%s,"quality":"%s","process_presence":{"main":%s,"local_chat":%s,"local_image":%s},"processes":[%s]}\n' \
      "$NOW" "$ELAPSED" "$RSS_SUM" "$PSS_SUM" "$NATIVE_SUM" "$FD_SUM" "$THREAD_SUM" \
      "$((MEMAVAIL * 1024))" "$TEMP" "$QUALITY" "$PRESENT_MAIN_B" "$PRESENT_CHAT_B" \
      "$PRESENT_IMAGE_B" "$PROCS" >> "$LOGS/samples.ndjson"
  fi
}
run_monitor() {
  init_layout "$1" "$2"
  INTERVAL=$3
  SAFETY=$4
  CAN_RUN_AS=0
  if run-as "$PKG" id >/dev/null 2>&1; then CAN_RUN_AS=1; fi
  MONITOR_START_EPOCH=$(epoch_now)
  MONITOR_END_EPOCH=$((MONITOR_START_EPOCH + SAFETY))
  : > "$LOGS/samples.ndjson"
  : > "$LOGS/crash-buffer.log"
  : > "$LOGS/anr-events.log"
  : > "$LOGS/runtime-errors.log"
  logcat -b crash -v threadtime > "$LOGS/crash-buffer.log" 2>&1 &
  C1=$!
  logcat -b events -v threadtime -s am_anr:I am_crash:I > "$LOGS/anr-events.log" 2>&1 &
  C2=$!
  logcat -b main -v threadtime -s AndroidRuntime:E ActivityManager:E > "$LOGS/runtime-errors.log" 2>&1 &
  C3=$!
  sleep 1
  C1A=$([ "$(process_alive "$C1" && echo yes || echo no)" = "yes" ] && echo true || echo false)
  C2A=$([ "$(process_alive "$C2" && echo yes || echo no)" = "yes" ] && echo true || echo false)
  C3A=$([ "$(process_alive "$C3" && echo yes || echo no)" = "yes" ] && echo true || echo false)
  printf '{"schema":"mca-rc-monitor-meta/1.0","type":"monitor_meta","timestamp":"%s","crash_logcat_alive":%s,"anr_logcat_alive":%s,"runtime_logcat_alive":%s,"pids":[%s,%s,%s]}\n' \
    "$(iso_now)" "$C1A" "$C2A" "$C3A" "$C1" "$C2" "$C3" >> "$LOGS/samples.ndjson"
  COUNT=0
  while [ ! -f "$ROOT/STOP_MONITOR" ]; do
    NOW_EPOCH=$(epoch_now)
    [ "$NOW_EPOCH" -ge "$MONITOR_END_EPOCH" ] && break
    sample_once
    COUNT=$((COUNT + 1))
    sleep "$INTERVAL"
  done
  C1E=$([ "$(process_alive "$C1" && echo yes || echo no)" = "yes" ] && echo true || echo false)
  C2E=$([ "$(process_alive "$C2" && echo yes || echo no)" = "yes" ] && echo true || echo false)
  C3E=$([ "$(process_alive "$C3" && echo yes || echo no)" = "yes" ] && echo true || echo false)
  kill "$C1" "$C2" "$C3" 2>/dev/null || true
  wait "$C1" 2>/dev/null || true
  wait "$C2" 2>/dev/null || true
  wait "$C3" 2>/dev/null || true
  printf '{"schema":"mca-rc-monitor-end/1.0","type":"monitor_end","timestamp":"%s","started_epoch":%s,"ended_epoch":%s,"samples":%s,"interval_seconds":%s,"crash_logcat_alive":%s,"anr_logcat_alive":%s,"runtime_logcat_alive":%s}\n' \
    "$(iso_now)" "$MONITOR_START_EPOCH" "$(epoch_now)" "$COUNT" "$INTERVAL" "$C1E" "$C2E" "$C3E" >> "$LOGS/samples.ndjson"
  echo "MONITOR_DONE|samples=$COUNT|utc=$(iso_now)"
}

MODE=${1:-}
[ -n "$MODE" ] || die "missing_mode"
shift
case "$MODE" in
  monitor)
    [ "$#" -ge 4 ] || die "monitor_arguments"
    run_monitor "$1" "$2" "$3" "$4"
    ;;
  workload)
    [ "$#" -ge 6 ] || die "workload_arguments"
    run_workload "$1" "$2" "$3" "$4" "$5" "$6"
    ;;
  authcheck)
    [ "$#" -ge 5 ] || die "authcheck_arguments"
    run_authcheck "$1" "$2" "$3" "$4" "$5"
    ;;
  *)
    die "unknown_mode"
    ;;
esac
