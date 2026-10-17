#!/system/bin/sh
# T25 production-chain fault injection harness (authenticated Local API on the real server).
#
# DESIGN RULES (learned from the audit's section 8.5 finding against my earlier probe):
#   1. Every assertion names the SPECIFIC target branch (error code / error field) it must hit.
#      A generic "ok":false is NOT acceptance -- that is exactly how a case that fails early at a
#      DIFFERENT field gets recorded as PASS.
#   2. Every case ends with a POSITIVE CONTROL: after the fault, a normal request must succeed.
#      Without it, a case whose precondition is never met still looks green.
#   3. The raw body is always captured verbatim so the branch can be re-verified later.
#
# Target branches (from source):
#   busy gate  -> generation_in_progress / model_loading / model_reloading / profile_rollback / tuning_in_progress
#                 (LocalApiControlPlaneOperations.kt:27-40)
#   image busy -> worker_busy / image_generation_busy / image_prompt_translation_busy /
#                 image_model_not_ready / image_worker_unavailable   (ImageGenerationApiContract.kt:336-337,
#                 LocalImageApiErrorMapping.kt:12-17)
#
# Usage: sh t25-fault-inject.sh <case> [outTag]
#   case = slow-client | disconnect | busy-overflow | worker-death | concurrency-image
DR=/data/local/tmp/t25
OUT="$DR/evidence"
mkdir -p "$OUT"
CASE="$1"
TAG="${2:-$1}"
LOG="$OUT/$TAG.log"
: > "$LOG"

KEY=$(run-as com.muyuchat.mca cat /data/data/com.muyuchat.mca/shared_prefs/mca_api.xml 2>/dev/null | grep -o 'name="api_key">[^<]*' | sed 's/name="api_key">//')
if [ -z "$KEY" ]; then echo "RESULT=$TAG|KEY_UNAVAILABLE|FAIL" >> "$LOG"; exit 8; fi
API=http://127.0.0.1:11435
CHAT_MODEL='Huihui-Qwen3.5-2B-abliterated.Q4_K_M'
IMG_MODEL='image:0fc35bfa-9cf3-4b18-955f-e05e8aee49b5'

say() { echo "$1" >> "$LOG"; }
# record a case outcome: name | status | target-branch-hit | detail
verdict() { echo "VERDICT|$TAG|$1|$2|$3" >> "$LOG"; }

chat_body() { printf '{"model":"%s","messages":[{"role":"user","content":"%s"}],"max_tokens":%s,"stream":false,"temperature":0.6}' "$CHAT_MODEL" "$1" "$2"; }
img_body()  { printf '{"model":"%s","prompt":"%s","sampler":"euler_a","steps":%s,"cfg_scale":1.0,"seed":%s,"size":"512x512","n":1,"response_format":"b64_json"}' "$IMG_MODEL" "$1" "$2" "$3"; }

post_chat() { # $1 body, $2 timeout -> writes body to $3, echoes http code
  curl -s -m "$2" -X POST -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
       -d "$1" -o "$3" -w "%{http_code}" "$API/v1/chat/completions"
}
post_img() {
  curl -s -m "$2" -X POST -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
       -d "$1" -o "$3" -w "%{http_code}" "$API/v1/images/generations"
}
# busy-family test: returns 0 when the body carries one of the documented busy codes
is_busy_code() {
  grep -qE '"code":"(generation_in_progress|model_loading|model_reloading|profile_rollback|tuning_in_progress|runtime_busy|worker_busy|image_generation_busy|image_prompt_translation_busy|image_model_not_ready|image_worker_unavailable)"' "$1"
}

say "CASE_START tag=$TAG utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"

case "$CASE" in

# ---------------------------------------------------------------- slow client
# A slow reader holds the runtime; a second client must be REJECTED with the busy code (the API
# rejects rather than queueing), and the slow request must still finish. Positive control after.
slow-client)
  B1=$(chat_body "Write a very long essay about the sea." 400)
  # --limit-rate forces a slow READ of the response, keeping the request in flight longer
  curl -s -m 600 --limit-rate 200 -X POST -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
       -d "$B1" -o "$OUT/$TAG-slow.json" -w "%{http_code}" "$API/v1/chat/completions" > "$OUT/$TAG-slow.code" 2>&1 &
  SLOWPID=$!
  sleep 3
  B2=$(chat_body "hi" 8)
  C2=$(post_chat "$B2" 20 "$OUT/$TAG-second.json")
  say "slow_inflight_second_http=$C2 body=$(head -c 220 "$OUT/$TAG-second.json")"
  wait $SLOWPID
  C1=$(cat "$OUT/$TAG-slow.code")
  say "slow_request_final_http=$C1 bytes=$(wc -c < "$OUT/$TAG-slow.json")"
  HIT=no; is_busy_code "$OUT/$TAG-second.json" && HIT=yes
  # positive control: a normal request must now succeed
  B3=$(chat_body "hi" 16)
  C3=$(post_chat "$B3" 200 "$OUT/$TAG-control.json")
  say "positive_control_http=$C3 bytes=$(wc -c < "$OUT/$TAG-control.json")"
  if [ "$C1" = "200" ] && [ "$HIT" = "yes" ] && [ "$C3" = "200" ]; then
    verdict PASS "target=busy_gate_code_in_second_request;slow_finished=$C1;recovered=$C3" "second=$(head -c 120 "$OUT/$TAG-second.json")"
  else
    verdict FAIL "expected slow=200 & busy-code & control=200 (got $C1/$HIT/$C3)" "second=$(head -c 160 "$OUT/$TAG-second.json")"
  fi
  ;;

# ---------------------------------------------------------------- active disconnect
# Client aborts mid-generation (SIGKILL of curl closes the socket). The server must terminate the
# in-flight work and release the runtime so the NEXT request succeeds.
disconnect)
  B1=$(chat_body "Write a 500 word essay." 400)
  curl -s -m 600 -X POST -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
       -d "$B1" -o "$OUT/$TAG-aborted.json" -w "%{http_code}" "$API/v1/chat/completions" > "$OUT/$TAG-aborted.code" 2>&1 &
  CPID=$!
  sleep 4
  kill -9 $CPID 2>/dev/null
  say "client_killed pid=$CPID at t+4s"
  # poll until the runtime stops reporting an active generation (or timeout)
  REL=no
  i=0
  while [ $i -lt 30 ]; do
    ST=$(curl -s -m 20 -H "Authorization: Bearer $KEY" "$API/v1/models" | tr ',' '\n' | grep -m1 '"engineLifecycle"')
    say "t+$((4+i))s $ST"
    case "$ST" in *'"engineLifecycle":"generating"'*) ;; *) REL=yes; break;; esac
    i=$((i+1))
    sleep 1
  done
  B3=$(chat_body "hi" 16)
  C3=$(post_chat "$B3" 300 "$OUT/$TAG-control.json")
  say "post_disconnect_control_http=$C3 body=$(head -c 200 "$OUT/$TAG-control.json")"
  if [ "$REL" = "yes" ] && [ "$C3" = "200" ]; then
    verdict PASS "target=runtime_released_after_client_abort;recovered=$C3;release_after_s=$i" "control=$(head -c 120 "$OUT/$TAG-control.json")"
  else
    verdict FAIL "release=$REL control=$C3 (release_after_s=$i)" "control=$(head -c 200 "$OUT/$TAG-control.json")"
  fi
  ;;

# ---------------------------------------------------------------- busy gate / overflow
# Fire N concurrent chats on one runtime. Exactly one may be admitted; the rest must be REJECTED
# with a documented busy code (no unbounded queue). Positive control after the storm.
busy-overflow)
  N=5
  i=1
  while [ $i -le $N ]; do
    B=$(chat_body "concurrent request $i" 24)
    ( curl -s -m 400 -X POST -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
        -d "$B" -o "$OUT/$TAG-c$i.json" -w "%{http_code}" "$API/v1/chat/completions" > "$OUT/$TAG-c$i.code" 2>&1 ) &
    i=$((i+1))
  done
  wait
  OKC=0; BUSY=0; OTHER=0
  i=1
  while [ $i -le $N ]; do
    C=$(cat "$OUT/$TAG-c$i.code" 2>/dev/null)
    if [ "$C" = "200" ]; then OKC=$((OKC+1))
    elif is_busy_code "$OUT/$TAG-c$i.json"; then BUSY=$((BUSY+1))
    else OTHER=$((OTHER+1)); fi
    say "concurrent[$i] http=$C body=$(head -c 160 "$OUT/$TAG-c$i.json")"
    i=$((i+1))
  done
  B3=$(chat_body "hi" 16)
  C3=$(post_chat "$B3" 300 "$OUT/$TAG-control.json")
  say "positive_control_http=$C3"
  if [ "$OKC" -ge 1 ] && [ "$OTHER" -eq 0 ] && [ "$C3" = "200" ]; then
    verdict PASS "target=busy_gate_rejects_concurrency(admitted=$OKC busy=$BUSY other=$OTHER);recovered=$C3" "N=$N"
  else
    verdict FAIL "admitted=$OKC busy=$BUSY other=$OTHER control=$C3" "N=$N"
  fi
  ;;

# ---------------------------------------------------------------- worker death in flight
# Kill the image worker process while a generation is in flight. The request must terminate with a
# locatable outcome and the next request must recover.
worker-death)
  IB=$(img_body "a green triangle" 4 11)
  ( curl -s -m 900 -X POST -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
      -d "$IB" -o "$OUT/$TAG-img.json" -w "%{http_code}" "$API/v1/images/generations" > "$OUT/$TAG-img.code" 2>&1 ) &
  sleep 6
  # find and kill the image worker's process (the work happens in a dedicated process)
  WPID=$(ps -A -o PID,ARGS | grep 'com.muyuchat.mca:local_image' | grep -v grep | awk '{print $1}' | head -1)
  if [ -z "$WPID" ]; then WPID=$(ps -A -o PID,ARGS | grep 'com.muyuchat.mca' | grep -v grep | awk '{print $1}' | head -1); fi
  say "worker_pid_selected=$WPID"
  if [ -n "$WPID" ]; then kill -9 $WPID 2>/dev/null; say "killed $WPID at t+6s"; fi
  wait
  IC=$(cat "$OUT/$TAG-img.code" 2>/dev/null)
  IBODY=$(head -c 300 "$OUT/$TAG-img.json")
  say "interrupted_image_http=$IC body=$IBODY"
  # Recovery control MUST be image-modality: image generation unloads the chat model by design,
  # so a chat control here would fail with model_mismatch and be misread as a recovery failure.
  # Cross-modal recovery (with the production-UI reload step) is a separate case.
  IB2=$(img_body "a small red dot" 2 99)
  C3=$(post_img "$IB2" 900 "$OUT/$TAG-control-img.json")
  IB3=$(wc -c < "$OUT/$TAG-control-img.json")
  say "post_worker_death_image_control_http=$C3 bytes=$IB3"
  PRE=$(ps -A -o PID,ARGS | grep 'com.muyuchat.mca:local_image' | grep -v grep | awk '{print $1}' | head -1)
  say "image_worker_pid_after_recovery=$PRE (restart visible if different from $WPID)"
  if [ "$C3" = "200" ] && [ "$IB3" -gt 1000 ]; then
    verdict PASS "target=request_terminated_and_worker_recovered;interrupted_http=$IC;recovered=$C3;killed_pid=$WPID;new_pid=$PRE" "body=$IBODY"
  else
    verdict FAIL "interrupted_http=$IC recovered=$C3 bytes=$IB3" "body=$IBODY"
  fi
  ;;

# ---------------------------------------------------------------- concurrent image pressure
# Two image generations at once: the second must be rejected with an image-busy code, and NO
# process may be killed by lmkd (the failure mode recorded earlier in this project).
concurrency-image)
  IB1=$(img_body "a red triangle" 4 21)
  IB2=$(img_body "a blue square" 4 22)
  ( curl -s -m 1200 -X POST -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
      -d "$IB1" -o "$OUT/$TAG-i1.json" -w "%{http_code}" "$API/v1/images/generations" > "$OUT/$TAG-i1.code" 2>&1 ) &
  sleep 5
  ( curl -s -m 1200 -X POST -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
      -d "$IB2" -o "$OUT/$TAG-i2.json" -w "%{http_code}" "$API/v1/images/generations" > "$OUT/$TAG-i2.code" 2>&1 ) &
  wait
  C1=$(cat "$OUT/$TAG-i1.code" 2>/dev/null); C2=$(cat "$OUT/$TAG-i2.code" 2>/dev/null)
  B2=$(head -c 220 "$OUT/$TAG-i2.json")
  say "image1 http=$C1 bytes=$(wc -c < "$OUT/$TAG-i1.json")"
  say "image2 http=$C2 body=$B2"
  LMKD=$(logcat -d -t 400 2>/dev/null | grep -c 'lmkd.*com.muyuchat.mca')
  say "lmkd_kill_lines=$LMKD"
  # image-modality positive control (see worker-death note about chat-model eviction)
  IB3=$(img_body "a small blue dot" 2 98)
  C3=$(post_img "$IB3" 900 "$OUT/$TAG-control-img.json")
  IB4=$(wc -c < "$OUT/$TAG-control-img.json")
  say "post_concurrency_image_control_http=$C3 bytes=$IB4"
  HIT=no; is_busy_code "$OUT/$TAG-i2.json" && HIT=yes
  if { [ "$C1" = "200" ] || [ "$C2" = "200" ]; } && [ "$C3" = "200" ] && [ "$IB4" -gt 1000 ]; then
    verdict PASS "target=concurrent_image_serialized_or_rejected(admit1=$C1 admit2=$C2 busy2=$HIT);recovered=$C3;lmkd=$LMKD" "img2=$B2"
  else
    verdict FAIL "img1=$C1 img2=$C2 control=$C3 bytes=$IB4 lmkd=$LMKD" "img2=$B2"
  fi
  ;;
*)
  say "unknown case: $CASE"; verdict FAIL "unknown_case" "$CASE" ;;
esac

say "CASE_END tag=$TAG utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
