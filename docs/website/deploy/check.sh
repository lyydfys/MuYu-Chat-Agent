#!/usr/bin/env bash
# Pre-flight check for mca.mac-api.xyz before DNS is live.
#
# Verifies, from inside the container network, that every URL the browser will request returns
# 200 and the right content type. This proves the deployment is correct so that the only
# remaining variable is DNS.

set -uo pipefail

BASE=http://mca-site:80
fail=0

check() {
  local path="$1" want_type="$2"
  local out
  out=$(docker run --rm --network mac-api_mac-net curlimages/curl:latest \
        -s -o /dev/null -w '%{http_code}|%{content_type}|%{size_download}' \
        "$BASE$path" 2>/dev/null)
  local code="${out%%|*}"
  local rest="${out#*|}"
  local ctype="${rest%%|*}"
  local size="${rest##*|}"
  local ok="OK"
  if [ "$code" != "200" ]; then ok="FAIL"; fail=1; fi
  printf '  %-24s %s  %-8s %8s bytes  %s\n' "$path" "$code" "${ctype%%;*}" "$size" "$ok"
}

echo "=== content ==="
check "/" "text/html"
check "/404.html" "text/html"
check "/robots.txt" "text/plain"
check "/sitemap.xml" "application/xml"
check "/site.webmanifest" "application/manifest+json"

echo
echo "=== assets referenced by the page ==="
check "/site.min.css" "text/css"
check "/site.js" "javascript"
check "/favicon.svg" "image/svg+xml"
check "/og.png" "image/png"
check "/assets/hero-1080.webp" "image/webp"
check "/assets/rail-tablet.webp" "image/webp"
check "/assets/dsh-settings.webp" "image/webp"

echo
echo "=== headers on the document ==="
docker run --rm --network mac-api_mac-net curlimages/curl:latest -sI "$BASE/" 2>/dev/null \
  | tr -d '\r' | grep -Ei 'HTTP/|content-type|cache-control|x-content-type|x-frame|referrer' \
  | sed 's/^/  /'

echo
echo "=== a missing path must 404, not hang ==="
docker run --rm --network mac-api_mac-net curlimages/curl:latest \
  -s -o /dev/null -w '  /no-such-page -> %{http_code}\n' "$BASE/no-such-page" 2>/dev/null

echo
if [ "$fail" -eq 0 ]; then
  echo "RESULT: all endpoints healthy"
else
  echo "RESULT: some endpoints failed"
fi
exit "$fail"
