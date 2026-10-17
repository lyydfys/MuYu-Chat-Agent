#!/system/bin/sh
# T25 worker-death injection (v2, corrected).
#
# v1 DEFECT (self-caught): it ran `kill -9 $WPID` as the shell user, which returns
# "Operation not permitted" (exit 1) because the app runs under a different UID. v1 did not check
# that exit code, so the generation was never interrupted, the request completed normally with
# HTTP 200, and v1 recorded a PASS. That is precisely the audit section 8.5 failure mode
# (asserting on an outcome that never hit the intended branch).
#
# v2 fixes:
#   1. Inject via `run-as com.muyuchat.mca kill -9 <pid>` (app UID signals its own process).
#   2. VERIFY the kill took effect (process gone) before treating the case as injected; if the
#      injection did not happen the case is recorded INVALID, not PASS.
#   3. Require the in-flight request to differ from a clean 200 (i.e. it must actually be cut off).
#   4. Recover with an IMAGE-modality control (image generation unloads the chat model by design,
#      so a chat control would fail for an unrelated reason).
#
# Usage: sh t25-worker-death.sh [outTag]
DR=/data/local/tmp/t25
OUT="$DR/evidence"
mkdir -p "$OUT"
TAG="${1:-worker-death}"
LOG="$OUT/$TAG.log"
: > "$LOG"
say() { echo "$1" >> "$LOG"; }

KEY=$(run-as com.muyuchat.mca cat /data/data/com.muyuchat.mca/shared_prefs/mca_api.xml 2>/dev/null | grep -o 'name="api_key">[^<]*' | sed 's/name="api_key">//')
[ -z "$KEY" ] && { say "RESULT|KEY_UNAVAILABLE"; exit 8; }
API=http://127.0.0.1:11435
IMG='image:0fc35bfa-9cf3-4b18-955f-e05e8aee49b5'

pid_of() { ps -A -o PID,ARGS | grep "$1" | grep -v grep | grep -v 'ps -A' | tr -s ' ' | cut -d' ' -f1 | head -1; }

say "CASE_START tag=$TAG utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"

IB=$(printf '{"model":"%s","prompt":"a green triangle","sampler":"euler_a","steps":4,"cfg_scale":1.0,"seed":11,"size":"512x512","n":1,"response_format":"b64_json"}' "$IMG")
curl -s -m 900 -X POST -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
     -d "$IB" -o "$OUT/$TAG-img.json" -w "%{http_code}" "$API/v1/images/generations" > "$OUT/$TAG-img.code" 2>&1 &
CP=$!

# wait for the image worker to actually spawn (it appears a few seconds into the request)
WPID=""; i=0
while [ $i -lt 20 ]; do
  WPID=$(pid_of 'com.muyuchat.mca:local_image')
  [ -n "$WPID" ] && break
  i=$((i+1)); sleep 1
done
say "image_worker_pid=$WPID (found after ${i}s)"

if [ -z "$WPID" ]; then
  say "INJECTION_NOT_POSSIBLE: no :local_image process observed"
  say "VERDICT|$TAG|INVALID|target=worker_death|no_worker_process_to_kill"
  exit 3
fi

# inject as the APP uid (shell cannot signal another uid's process)
run-as com.muyuchat.mca kill -9 "$WPID" 2>>"$LOG"
KEXIT=$?
say "runas_kill_pid=$WPID exit=$KEXIT"
sleep 2
AFTER=$(pid_of 'com.muyuchat.mca:local_image')
say "worker_pid_after_kill='$AFTER'"

if [ -n "$AFTER" ]; then
  say "INJECTION_FAILED: worker still present after run-as kill"
  run-as com.muyuchat.mca kill -9 "$CP" 2>/dev/null
  say "VERDICT|$TAG|INVALID|target=worker_death|kill_did_not_take_effect"
  exit 3
fi

wait $CP 2>/dev/null
IC=$(cat "$OUT/$TAG-img.code" 2>/dev/null)
IBY=$(wc -c < "$OUT/$TAG-img.json" 2>/dev/null)
IBODY=$(head -c 300 "$OUT/$TAG-img.json")
say "interrupted_request_http='$IC' bytes=$IBY body=$IBODY"

# The interrupted request must NOT look like a clean success.
INTERRUPTED_CLEAN=no
[ "$IC" = "200" ] && [ "$IBY" -gt 1000 ] && INTERRUPTED_CLEAN=yes

# recovery control, same modality
IB2=$(printf '{"model":"%s","prompt":"a small red dot","sampler":"euler_a","steps":2,"cfg_scale":1.0,"seed":99,"size":"512x512","n":1,"response_format":"b64_json"}' "$IMG")
C3=$(curl -s -m 900 -X POST -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
     -d "$IB2" -o "$OUT/$TAG-control.json" -w "%{http_code}" "$API/v1/images/generations")
IB3=$(wc -c < "$OUT/$TAG-control.json")
NEWPID=$(pid_of 'com.muyuchat.mca:local_image')
say "recovery_http=$C3 bytes=$IB3 new_worker_pid='$NEWPID'"
say "worker_restarted=$([ "$NEWPID" != "$WPID" ] && echo yes || echo unknown)"

if [ "$INTERRUPTED_CLEAN" = "no" ] && [ "$C3" = "200" ] && [ "$IB3" -gt 1000 ]; then
  say "VERDICT|$TAG|PASS|target=worker_death_terminated_and_recovered;interrupted_http=$IC;killed_pid=$WPID;kill_exit=$KEXIT;recovered=$C3"
elif [ "$INTERRUPTED_CLEAN" = "yes" ]; then
  say "VERDICT|$TAG|FAIL|target=worker_death|the_kill_did_not_interrupt_the_request(it_still_returned_a_full_200)"
else
  say "VERDICT|$TAG|FAIL|target=worker_death|interrupted_http=$IC;recovery=$C3;bytes=$IB3"
fi
say "CASE_END tag=$TAG utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
