#!/system/bin/sh
# NPUG/AOT acceptance through the authenticated Local API.
#
# Usage: t08-npug-acceptance.sh <image-model-id> [runs] [out-dir]
#   image-model-id: bare UUID or image:<uuid> of the installed v4 bundle
#   MCA_API_KEY    : optional; overrides the run-as key read (use for non-debuggable builds)
#
# Runs N sequential generations, decodes data[0].b64_json, checks the PNG signature,
# then submits one generation with X-MCA-Request-Id and stops it in flight.
# It never installs, builds, or modifies the app.
set -u
MODEL=${1:-}
N=${2:-2}
OUT=${3:-/data/local/tmp/mca-npug-accept}
if [ -z "$MODEL" ]; then
  echo "USAGE: $0 <image-model-id> [runs] [out-dir]"
  exit 2
fi
case "$MODEL" in
  image:*) MODEL_ID=$MODEL ;;
  *) MODEL_ID="image:$MODEL" ;;
esac
mkdir -p "$OUT" || exit 9
cd "$OUT" || exit 9

KEY=${MCA_API_KEY:-}
if [ -z "$KEY" ]; then
  KEY=$(run-as com.muyuchat.mca cat /data/data/com.muyuchat.mca/shared_prefs/mca_api.xml 2>/dev/null \
    | grep -o 'name="api_key">[^<]*' | sed 's/name="api_key">//')
fi
if [ -z "$KEY" ]; then
  echo "KEY_UNAVAILABLE"
  exit 8
fi

# A fixed-ACTX v4 bundle accepts a prompt but must declare that it was not applied.
# Set EXPECT_PROMPT_SUPPRESSED=no only when the bundle implements dynamic text.
EXPECT_SUPPRESSED=${EXPECT_PROMPT_SUPPRESSED:-yes}
BASE=http://127.0.0.1:11435
PASS=0
FAIL=0
echo "MODEL=$MODEL_ID RUNS=$N OUT=$OUT KEY_LEN=${#KEY}"

i=1
while [ "$i" -le "$N" ]; do
  BODY=$(printf '{"model":"%s","prompt":"npug acceptance %s","steps":6,"cfg_scale":1.0,"seed":%s,"size":"512x512","n":1,"response_format":"b64_json"}' \
    "$MODEL_ID" "$i" "$i")
  START=$(date +%s)
  CODE=$(curl -s -m 1800 -o "gen-$i.json" -w "%{http_code}" -X POST \
    -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" -d "$BODY" \
    "$BASE/v1/images/generations")
  END=$(date +%s)
  B64=$(grep -o '"b64_json":"[^"]*"' "gen-$i.json" | head -1 | cut -d'"' -f4)
  : > "img-$i.png"
  if [ -n "$B64" ]; then
    printf '%s' "$B64" | base64 -d > "img-$i.png" 2>/dev/null
  fi
  SIZE=$(wc -c < "img-$i.png" | tr -d ' ')
  SHA=$(sha256sum "img-$i.png" | cut -d' ' -f1)
  MAGIC=$(dd if="img-$i.png" bs=1 count=8 2>/dev/null | od -An -tx1 | tr -d ' \n')
  PNG=no
  [ "$MAGIC" = "89504e470d0a1a0a" ] && PNG=yes
  SUPPRESSED=not_required
  if [ "$EXPECT_SUPPRESSED" = "yes" ]; then
    SUPPRESSED=no
    grep -q '"promptSuppressed":true' "gen-$i.json" && SUPPRESSED=yes
  fi
  if [ "$CODE" = "200" ] && [ "$PNG" = "yes" ] && [ "$SIZE" -gt 1024 ] && [ "$SUPPRESSED" != "no" ]; then
    PASS=$((PASS + 1))
    echo "RUN=$i RESULT=PASS HTTP=$CODE PNG=$PNG BYTES=$SIZE SHA=$SHA PROMPT_SUPPRESSED=$SUPPRESSED SECONDS=$((END - START))"
  else
    FAIL=$((FAIL + 1))
    echo "RUN=$i RESULT=FAIL HTTP=$CODE PNG=$PNG BYTES=$SIZE ERROR=$(head -c 200 "gen-$i.json" | tr '\n' ' ')"
  fi
  i=$((i + 1))
done

RID="npug-cancel-$$"
BODY=$(printf '{"model":"%s","prompt":"npug cancellation probe","steps":6,"cfg_scale":1.0,"seed":99,"size":"512x512","n":1}' "$MODEL_ID")
curl -s -m 1800 -X POST -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
  -H "X-MCA-Request-Id: $RID" -d "$BODY" "$BASE/v1/images/generations" -o cancel-gen.json &
GEN_PID=$!
sleep 3
STOP=$(curl -s -m 60 -X POST -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
  -d "{\"request_id\":\"$RID\"}" "$BASE/v1/generate/stop")
wait $GEN_PID
RETURNED_IMAGE=no
grep -q '"b64_json"' cancel-gen.json && RETURNED_IMAGE=yes
if [ "$RETURNED_IMAGE" = "no" ]; then
  CANCELLED=PASS
  PASS=$((PASS + 1))
else
  CANCELLED=FAIL
  FAIL=$((FAIL + 1))
fi
echo "CANCEL REQUEST_ID=$RID STOP=$(printf '%s' "$STOP" | head -c 200) RETURNED_IMAGE=$RETURNED_IMAGE RESULT=$CANCELLED"
echo "SUMMARY PASS=$PASS FAIL=$FAIL"
if [ "$FAIL" = "0" ]; then
  echo "NPUG_ACCEPTANCE_PASS"
  exit 0
fi
echo "NPUG_ACCEPTANCE_FAIL"
exit 1
