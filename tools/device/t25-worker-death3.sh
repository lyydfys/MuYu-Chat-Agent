#!/system/bin/sh
# T25 worker-death injection (v3, rigorous).
#
# History of this case (kept deliberately, because each fix came from a self-caught defect):
#   v1: used `kill -9` as the shell user -> "Operation not permitted" (exit 1) -> the generation was
#       never interrupted -> the request returned a full HTTP 200 -> v1 recorded a FALSE PASS.
#       This is the audit section 8.5 failure mode, in my own harness.
#   v2: injected via `run-as com.muyuchat.mca kill -9` and verified the kill. But a leftover
#       generation from an earlier probe was still running, so the "interrupted" request was
#       actually rejected as image_generation_busy and the PASS rested on the wrong branch.
#   v3 (this file): wait for QUIESCENCE first, then prove the target request is genuinely IN FLIGHT
#       (its curl is alive AND the worker exists) before killing, and require that the in-flight
#       request is cut off rather than rejected-before-start.
#
# Usage: sh t25-worker-death3.sh [outTag]
DR=/data/local/tmp/t25
OUT="$DR/evidence"
mkdir -p "$OUT"
TAG="${1:-worker-death-v3}"
LOG="$OUT/$TAG.log"
: > "$LOG"
say() { echo "$1" >> "$LOG"; }

KEY=$(run-as com.muyuchat.mca cat /data/data/com.muyuchat.mca/shared_prefs/mca_api.xml 2>/dev/null | grep -o 'name="api_key">[^<]*' | sed 's/name="api_key">//')
[ -z "$KEY" ] && { say "RESULT|KEY_UNAVAILABLE"; exit 8; }
API=http://127.0.0.1:11435
IMG='image:0fc35bfa-9cf3-4b18-955f-e05e8aee49b5'
pid_of() { ps -A -o PID,ARGS | grep "$1" | grep -v grep | grep -v 'ps -A' | tr -s ' ' | cut -d' ' -f1 | head -1; }

say "CASE_START tag=$TAG utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"

# ---- step 0: quiescence. No in-flight image worker, and a probe request must be accepted.
q=0
while [ $q -lt 90 ]; do
  STALE=$(pid_of 'com.muyuchat.mca:local_image')
  [ -z "$STALE" ] && break
  q=$((q+1)); sleep 2
done
say "quiescence_wait_s=$((q*2)) stale_worker='$STALE'"
if [ -n "$STALE" ]; then
  say "VERDICT|$TAG|INVALID|target=worker_death|could_not_reach_quiescence"
  exit 3
fi

# ---- step 1: start the target request and prove it is IN FLIGHT
IB=$(printf '{"model":"%s","prompt":"a green triangle","sampler":"euler_a","steps":4,"cfg_scale":1.0,"seed":11,"size":"512x512","n":1,"response_format":"b64_json"}' "$IMG")
curl -s -m 900 -X POST -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
     -d "$IB" -o "$OUT/$TAG-img.json" -w "%{http_code}" "$API/v1/images/generations" > "$OUT/$TAG-img.code" 2>&1 &
CP=$!
say "target_curl_pid=$CP"

WPID=""; i=0
while [ $i -lt 30 ]; do
  WPID=$(pid_of 'com.muyuchat.mca:local_image')
  if [ -n "$WPID" ]; then
    # worker exists AND the request has not returned yet => genuinely in flight
    if kill -0 "$CP" 2>/dev/null; then break; fi
  fi
  i=$((i+1)); sleep 1
done
if [ -z "$WPID" ] || ! kill -0 "$CP" 2>/dev/null; then
  say "IN_FLIGHT_UNPROVEN worker='$WPID' curl_alive=$(kill -0 $CP 2>/dev/null && echo yes || echo no)"
  say "VERDICT|$TAG|INVALID|target=worker_death|target_request_was_not_in_flight"
  exit 3
fi
say "in_flight_confirmed worker_pid=$WPID after_s=$i"

# ---- step 2: inject (must be the app UID) and VERIFY it took effect
run-as com.muyuchat.mca kill -9 "$WPID" 2>>"$LOG"
KEXIT=$?
sleep 2
AFTER=$(pid_of 'com.muyuchat.mca:local_image')
say "runas_kill pid=$WPID exit=$KEXIT worker_after='$AFTER'"
if [ -n "$AFTER" ]; then
  say "VERDICT|$TAG|INVALID|target=worker_death|kill_did_not_take_effect"
  exit 3
fi

# ---- step 3: the in-flight request must terminate, and NOT as a clean image
wait $CP 2>/dev/null
IC=$(cat "$OUT/$TAG-img.code" 2>/dev/null)
IBY=$(wc -c < "$OUT/$TAG-img.json" 2>/dev/null)
IBODY=$(head -c 260 "$OUT/$TAG-img.json")
say "cut_request_http='$IC' bytes=$IBY body=$IBODY"
CLEAN=no
[ "$IC" = "200" ] && [ "$IBY" -gt 1000 ] && CLEAN=yes

# ---- step 4: recovery control, same modality
sleep 1
IB2=$(printf '{"model":"%s","prompt":"a small red dot","sampler":"euler_a","steps":2,"cfg_scale":1.0,"seed":99,"size":"512x512","n":1,"response_format":"b64_json"}' "$IMG")
C3=$(curl -s -m 900 -X POST -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
     -d "$IB2" -o "$OUT/$TAG-control.json" -w "%{http_code}" "$API/v1/images/generations")
IB3=$(wc -c < "$OUT/$TAG-control.json")
NEWPID=$(pid_of 'com.muyuchat.mca:local_image')
say "recovery_http=$C3 bytes=$IB3 new_worker_pid='$NEWPID' (killed was $WPID)"

if [ "$CLEAN" = "no" ] && [ "$C3" = "200" ] && [ "$IB3" -gt 1000 ]; then
  say "VERDICT|$TAG|PASS|target=worker_death_cut_in_flight_and_recovered;cut_http=$IC;killed_pid=$WPID;kill_exit=$KEXIT;recovered=$C3"
elif [ "$CLEAN" = "yes" ]; then
  say "VERDICT|$TAG|FAIL|target=worker_death|kill_did_not_cut_the_in_flight_request(full_200_returned)"
else
  say "VERDICT|$TAG|FAIL|target=worker_death|cut_http=$IC;recovery=$C3;bytes=$IB3"
fi
say "CASE_END tag=$TAG utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
