#!/system/bin/sh
# Soak: alternate real chat and real image generation on the production Local API for a fixed
# duration, sampling device health each round. Sequential by design: the device has ~8 GB
# available and a previous concurrent-image run was killed by lmkd.
#
# Usage: sh t08-soak.sh <minutes> [outTag]
DR=/data/local/tmp/mnn-harness
MINUTES=${1:-120}
TAG=${2:-soak}
OUT="$DR/out6"
mkdir -p "$OUT"
LOG="$OUT/$TAG.log"
: > "$LOG"
KEY=$(run-as com.muyuchat.mca cat /data/data/com.muyuchat.mca/shared_prefs/mca_api.xml 2>/dev/null | grep -o 'name="api_key">[^<]*' | sed 's/name="api_key">//')
if [ -z "$KEY" ]; then echo "KEY_UNAVAILABLE" >> "$LOG"; exit 8; fi

pid=$(pidof com.muyuchat.mca)
echo "SOAK_START utc=$(date -u +%Y-%m-%dT%H:%M:%SZ) minutes=$MINUTES pid=$pid" >> "$LOG"

sample() {
  # PSS via dumpsys meminfo (KB), plus native/FD/threads and thermals
  mi=$(dumpsys meminfo com.muyuchat.mca 2>/dev/null)
  total=$(echo "$mi" | grep -m1 'TOTAL PSS' | awk '{print $3}')
  native=$(echo "$mi" | grep -m1 'Native Heap' | awk '{print $3}')
  fd=$(ls /proc/$pid/fd 2>/dev/null | wc -l)
  th=$(ls /proc/$pid/task 2>/dev/null | wc -l)
  oom=$(cat /proc/$pid/oom_score_adj 2>/dev/null)
  temp=$(cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null)
  avail=$(grep MemAvailable /proc/meminfo | awk '{print $2}')
  echo "SAMPLE utc=$(date -u +%Y-%m-%dT%H:%M:%SZ) pssKb=$total nativeKb=$native fds=$fd threads=$th oomAdj=$oom tempRaw=$temp memAvailKb=$avail" >> "$LOG"
}

round=0
end=$(( $(date +%s) + MINUTES * 60 ))
chatOk=0; chatFail=0; imgOk=0; imgFail=0
while [ "$(date +%s)" -lt "$end" ]; do
  round=$((round + 1))
  # --- chat ---
  BODY=$(printf '{"model":"Huihui-Qwen3.5-2B-abliterated.Q4_K_M","messages":[{"role":"user","content":"Say OK and the round number %s."}],"max_tokens":12,"stream":false,"temperature":0.6}' "$round")
  R=$(curl -s -m 300 -X POST -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" -d "$BODY" \
      -w "|HTTP=%{http_code}" http://127.0.0.1:11435/v1/chat/completions)
  C=$(echo "$R" | grep -o 'HTTP=[0-9]*' | tail -1 | sed 's/HTTP=//')
  if [ "$C" = "200" ]; then chatOk=$((chatOk+1)); else chatFail=$((chatFail+1)); fi
  echo "ROUND=$round chat=$C" >> "$LOG"
  sample
  # --- image (SDCPP, 4 steps: ~4.5 min each on this device) ---
  IBODY=$(printf '{"model":"image:0fc35bfa-9cf3-4b18-955f-e05e8aee49b5","prompt":"a red cube on a white table","sampler":"euler_a","steps":4,"cfg_scale":1.0,"seed":%s,"size":"512x512","n":1,"response_format":"b64_json"}' "$round")
  IR=$(curl -s -m 1200 -X POST -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" -d "$IBODY" \
       -w "|HTTP=%{http_code}" -o "$OUT/$TAG-img-$round.json" http://127.0.0.1:11435/v1/images/generations)
  IC=$(echo "$IR" | grep -o 'HTTP=[0-9]*' | tail -1 | sed 's/HTTP=//')
  IBYTES=$(wc -c < "$OUT/$TAG-img-$round.json")
  if [ "$IC" = "200" ] && [ "$IBYTES" -gt 1000 ]; then imgOk=$((imgOk+1)); else imgFail=$((imgFail+1)); fi
  echo "ROUND=$round image=$IC bytes=$IBYTES" >> "$LOG"
  sample
  # keep the b64 payload out of the log, drop the file to save space
  rm -f "$OUT/$TAG-img-$round.json"
done
echo "SOAK_DONE utc=$(date -u +%Y-%m-%dT%H:%M:%SZ) rounds=$round chatOk=$chatOk chatFail=$chatFail imgOk=$imgOk imgFail=$imgFail" >> "$LOG"
