#!/system/bin/sh
# Cancel/retry loop: submit an image generation, stop it while in flight, confirm the caller gets
# the cancellation contract, then immediately start the next one. Repeat N times.
# One in-flight image task at a time on purpose: concurrent image runs previously caused lmkd to
# kill a competing run on this 16 GB device.
#
# The API key is read through `run-as` because shared_prefs lives in the app's private data dir;
# a plain shell cannot read it (a shell-level grep yields an empty key and every call returns 401).
DR=/data/local/tmp/mnn-harness
N=${1:-10}
OUT="$DR/out6"
mkdir -p "$OUT"
cd "$OUT" || exit 9
KEY=$(run-as com.muyuchat.mca cat /data/data/com.muyuchat.mca/shared_prefs/mca_api.xml 2>/dev/null | grep -o 'name="api_key">[^<]*' | sed 's/name="api_key">//')
if [ -z "$KEY" ]; then echo "KEY_UNAVAILABLE"; exit 8; fi
echo "KEY_LEN=${#KEY}"
i=1
while [ "$i" -le "$N" ]; do
  RID="t08-cr-$i"
  BODY=$(printf '{"model":"image:0fc35bfa-9cf3-4b18-955f-e05e8aee49b5","prompt":"a simple blue circle on white, minimal","sampler":"euler_a","steps":4,"cfg_scale":1.0,"seed":%s,"size":"512x512","n":1}' "$i")
  STOP=$(printf '{"request_id":"%s"}' "$RID")
  curl -s -S -m 900 -X POST -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
       -H "X-MCA-Request-Id: $RID" -d "$BODY" -w "HTTP=%{http_code}" \
       http://127.0.0.1:11435/v1/images/generations > "cr-$i.gen" 2>&1 &
  PID=$!
  sleep 4
  S1=$(curl -s -m 60 -X POST -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
       -d "$STOP" http://127.0.0.1:11435/v1/generate/stop)
  wait $PID
  GEN=$(head -c 200 "cr-$i.gen")
  echo "ITER=$i STOP=$S1 GEN=$GEN"
  i=$((i + 1))
done
echo "LOOP_DONE"
