#!/system/bin/sh
# Soak v2 — corrected for the product's documented model-eviction behaviour.
#
# WHY v1 WAS RESTARTED: v1 alternated chat and SDCPP image in one loop. Image generation
# UNLOADS the chat model (device shows engineLifecycle=unloaded after an image round), so every
# chat after the first image returned 409 model_mismatch. That is correct product behaviour, but
# it made the alternation meaningless. The soak script now logs the response BODY (not just the
# status) so this class of cause is never invisible again.
#
# DESIGN: two single-modality phases back to back, because they cannot be interleaved without a
# production-UI model reload between every pair.
#   phase 1 (text):  sustained authenticated chat against the loaded model
#   phase 2 (image): sustained SDCPP generations (each also reloads nothing; image path is
#                    independent of the chat model)
# Sequential within a phase (one in-flight generation at a time): a previous concurrent-image run
# was killed by lmkd on this 16 GB device.
#
# Usage: sh t08-soak2.sh <totalMinutes> <outTag>
DR=/data/local/tmp/mnn-harness
MINUTES=${1:-120}
TAG=${2:-soak8}
OUT="$DR/out6"
mkdir -p "$OUT"
LOG="$OUT/$TAG.log"
: > "$LOG"
KEY=$(run-as com.muyuchat.mca cat /data/data/com.muyuchat.mca/shared_prefs/mca_api.xml 2>/dev/null | grep -o 'name="api_key">[^<]*' | sed 's/name="api_key">//')
if [ -z "$KEY" ]; then echo "KEY_UNAVAILABLE" >> "$LOG"; exit 8; fi
PID=$(pidof com.muyuchat.mca)
START=$(date +%s)
HALF=$(( MINUTES * 60 / 2 ))

echo "SOAK2_START utc=$(date -u +%Y-%m-%dT%H:%M:%SZ) minutes=$MINUTES pid=$PID phases=text,image" >> "$LOG"

sample() {
  mi=$(dumpsys meminfo com.muyuchat.mca 2>/dev/null)
  total=$(echo "$mi" | grep -m1 'TOTAL PSS' | awk '{print $3}')
  native=$(echo "$mi" | grep -m1 'Native Heap' | awk '{print $3}')
  fd=$(ls /proc/$PID/fd 2>/dev/null | wc -l)
  th=$(ls /proc/$PID/task 2>/dev/null | wc -l)
  temp=$(cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null)
  avail=$(grep MemAvailable /proc/meminfo | awk '{print $2}')
  echo "SAMPLE utc=$(date -u +%Y-%m-%dT%H:%M:%SZ) pssKb=$total nativeKb=$native fds=$fd threads=$th tempRaw=$temp memAvailKb=$avail" >> "$LOG"
}

# ---------------- phase 1: text ----------------
chatOk=0; chatFail=0; r=0
phaseEnd=$(( START + HALF ))
while [ "$(date +%s)" -lt "$phaseEnd" ]; do
  r=$((r + 1))
  BODY=$(printf '{"model":"Huihui-Qwen3.5-2B-abliterated.Q4_K_M","messages":[{"role":"user","content":"Reply with OK and the round number %s."}],"max_tokens":12,"stream":false,"temperature":0.6}' "$r")
  RESP=$(curl -s -m 300 -X POST -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" -d "$BODY" \
       -w "|HTTP=%{http_code}" http://127.0.0.1:11435/v1/chat/completions)
  C=$(echo "$RESP" | grep -o 'HTTP=[0-9]*' | tail -1 | sed 's/HTTP=//')
  CODE=$(echo "$RESP" | grep -o '"code":"[a-z_]*"' | head -1)
  TOK=$(echo "$RESP" | grep -o '"completion_tokens":[0-9]*' | head -1 | sed 's/.*://')
  if [ "$C" = "200" ]; then chatOk=$((chatOk+1)); else chatFail=$((chatFail+1)); fi
  echo "TEXT round=$r http=$C tokens=$TOK err=$CODE" >> "$LOG"
  [ $((r % 5)) -eq 0 ] && sample
done
echo "TEXT_PHASE_DONE rounds=$r ok=$chatOk fail=$chatFail utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$LOG"
sample

# ---------------- phase 2: image ----------------
imgOk=0; imgFail=0; g=0
end=$(( START + MINUTES * 60 ))
while [ "$(date +%s)" -lt "$end" ]; do
  g=$((g + 1))
  IBODY=$(printf '{"model":"image:0fc35bfa-9cf3-4b18-955f-e05e8aee49b5","prompt":"a yellow star on a blue background","sampler":"euler_a","steps":4,"cfg_scale":1.0,"seed":%s,"size":"512x512","n":1,"response_format":"b64_json"}' "$g")
  IR=$(curl -s -m 1200 -X POST -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" -d "$IBODY" \
       -w "|HTTP=%{http_code}" -o "$OUT/$TAG-img-$g.json" http://127.0.0.1:11435/v1/images/generations)
  IC=$(echo "$IR" | grep -o 'HTTP=[0-9]*' | tail -1 | sed 's/HTTP=//')
  IB=$(wc -c < "$OUT/$TAG-img-$g.json")
  if [ "$IC" = "200" ] && [ "$IB" -gt 1000 ]; then imgOk=$((imgOk+1)); else imgFail=$((imgFail+1)); fi
  echo "IMAGE round=$g http=$IC bytes=$IB" >> "$LOG"
  sample
  rm -f "$OUT/$TAG-img-$g.json"
done
sample
echo "SOAK2_DONE utc=$(date -u +%Y-%m-%dT%H:%M:%SZ) textRounds=$r textOk=$chatOk textFail=$chatFail imageRounds=$g imageOk=$imgOk imageFail=$imgFail" >> "$LOG"
