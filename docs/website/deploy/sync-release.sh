#!/usr/bin/env bash
# Sync the MCA release mirror from GitHub without any credentials on the GitHub side.
#
# Design (chosen over GitHub Actions / webhook):
#   * GitHub never holds an SSH key to this server
#   * no公网 write endpoint is exposed
#   * polling every 6h is enough for a marketing site
#   * GitHub being down cannot take the site down: on failure we keep serving the old release
#
# What it does, in order, and only if a NEWER release exists:
#   1. query the GitHub API for the latest published, non-draft release
#   2. compare with the version recorded in downloads/manifest.json
#   3. download the APK to a .part file
#   4. verify byte size AND sha256 against the digest GitHub publishes
#   5. atomically promote the .part to the real filename
#   6. rewrite index.html release facts between <!--rel:field--> markers
#   7. rewrite the four /downloads/MCA-v<ver>-arm64-v8a.apk URLs
#   8. regenerate data/release.json + data/releases.json (changelog source)
#   9. keep the previous APK as a fallback (one generation), then reload nothing (static files)
#
# Every step is idempotent. A failure at any step leaves the previous version serving.
#
# Run:      sync-release.sh            (normal)
#           FORCE=1 sync-release.sh    (re-pin even if the tag matches)
#           DRY=1 sync-release.sh      (report what would change, touch nothing)
set -uo pipefail

SITE=/opt/mca-site/html
DL="$SITE/downloads"
MANIFEST="$DL/manifest.json"
INDEX="$SITE/index.html"
LOG=/var/log/mca-sync.log
LOCK=/var/lock/mca-sync.lock
API="https://api.github.com/repos/lyydfys/MuYu-Chat-Agent/releases"

FORCE="${FORCE:-0}"
DRY="${DRY:-0}"

log() { printf '%s  %s\n' "$(date -Iseconds)" "$*" | tee -a "$LOG"; }
die() { log "FAIL: $*"; exit 1; }

# ---------------------------------------------------------------- single instance
exec 9>"$LOCK" || die "cannot open lock"
flock -n 9 || { log "another sync is running; exiting"; exit 0; }

# ---------------------------------------------------------------- fetch metadata
log "checking GitHub releases"
META=$(curl -fsSL --retry 2 --max-time 40 \
        -H 'Accept: application/vnd.github+json' \
        -H 'User-Agent: mca-site-sync' \
        "$API?per_page=100") || die "GitHub API unreachable"

# Pick the newest published (non-draft) release. jq is present on this host.
LATEST=$(printf '%s' "$META" | jq -r '
  [ .[] | select(.draft == false) ]
  | sort_by(.published_at) | last
  | [ .tag_name,
      (.assets[] | select(.name | endswith(".apk")) | .name),
      (.assets[] | select(.name | endswith(".apk")) | (.size|tostring)),
      (.assets[] | select(.name | endswith(".apk")) | (.digest // "" | sub("sha256:";"") )),
      (.published_at | split("T")[0])
    ] | @tsv' 2>/dev/null) || die "jq parse failed"

IFS=$'\t' read -r TAG ASSET SIZE SHA PUBLISHED <<<"$LATEST"
[ -n "${TAG:-}" ] || die "could not determine latest tag"
[ -n "${ASSET:-}" ] || die "latest release has no .apk asset"
[ -n "${SHA:-}" ] || log "WARN: release publishes no sha256 digest; size check only"

log "latest = $TAG  asset=$ASSET  size=$SIZE  published=$PUBLISHED"

CURRENT=""
[ -f "$MANIFEST" ] && CURRENT=$(jq -r '.version // empty' "$MANIFEST" 2>/dev/null || true)
log "current = ${CURRENT:-(none)}"

if [ "$TAG" = "v$CURRENT" ] && [ "$FORCE" != "1" ]; then
  log "already up to date; nothing to do"
  exit 0
fi

if [ "$DRY" = "1" ]; then
  log "DRY: would switch ${CURRENT:-(none)} -> $TAG"
  exit 0
fi

PLATFORM="arm64-v8a"
VER="${TAG#v}"
FINAL="$DL/$ASSET"
PART="$FINAL.part"
URL="https://github.com/lyydfys/MuYu-Chat-Agent/releases/download/$TAG/$ASSET"

# ---------------------------------------------------------------- download
log "downloading $URL"
rm -f "$PART"
curl -fL --retry 3 --retry-delay 3 --max-time 900 -o "$PART" "$URL" \
  || die "download failed (previous release still serving)"

ACT_SIZE=$(stat -c %s "$PART")
[ "$ACT_SIZE" = "$SIZE" ] || die "size mismatch: got $ACT_SIZE want $SIZE"

if [ -n "${SHA:-}" ]; then
  ACT_SHA=$(sha256sum "$PART" | cut -d' ' -f1)
  [ "$ACT_SHA" = "$SHA" ] || die "sha256 mismatch: got $ACT_SHA want $SHA"
  log "sha256 verified: $ACT_SHA"
else
  log "size verified: $ACT_SIZE bytes (no digest available)"
fi

# ---------------------------------------------------------------- promote
# Keep one previous generation as a fallback for anyone mid-download.
PREV=$(jq -r '.asset // empty' "$MANIFEST" 2>/dev/null || true)
if [ -n "$PREV" ] && [ -f "$DL/$PREV" ] && [ "$PREV" != "$ASSET" ]; then
  mv -f "$DL/$PREV" "$DL/$PREV.prev" 2>/dev/null || true
  log "kept previous as $PREV.prev"
fi

mv -f "$PART" "$FINAL" || die "promote failed"
log "promoted $ASSET"

# ---------------------------------------------------------------- page rewrite
SIZE_LABEL=$(awk -v b="$SIZE" 'BEGIN{printf "%.1f MB", b/1024/1024}')

python3 - "$INDEX" "$VER" "$ASSET" "$SIZE_LABEL" "$PUBLISHED" "$SHA" <<'PY'
import re, sys, pathlib

index, ver, asset, size_label, published, sha = sys.argv[1:7]
p = pathlib.Path(index)
s = p.read_text(encoding="utf-8")

def setfield(text, field, value):
    return re.sub(
        r"<!--rel:%s-->.*?<!--/rel:%s-->" % (field, field),
        lambda m: "<!--rel:%s-->%s<!--/rel:%s-->" % (field, value, field),
        text, flags=re.S)

for field, val in (("version", "v" + ver),
                   ("size", size_label),
                   ("published", published)):
    s = setfield(s, field, val)
if sha:
    s = setfield(s, "sha256", sha)

# URLs are replaced by pattern, not by marker: the /downloads/MCA- prefix is unambiguous.
s = re.sub(r"downloads/MCA-v[\d.]+-arm64-v8a\.apk", "downloads/" + asset, s)

p.write_text(s, encoding="utf-8")
print("index.html updated")
PY
[ $? -eq 0 ] || die "index.html rewrite failed"

# ---------------------------------------------------------------- data files
python3 - "$TAG" "$ASSET" "$SIZE" "$SIZE_LABEL" "$SHA" "$PUBLISHED" <<'PY' >/dev/null
import json, sys, pathlib, datetime
tag, asset, size, size_label, sha, published = sys.argv[1:7]
dl = pathlib.Path("/opt/mca-site/html/downloads")
data = pathlib.Path("/opt/mca-site/html/data")
data.mkdir(parents=True, exist_ok=True)

ver = tag.lstrip("v")
manifest = {
    "version": ver, "asset": asset, "size_bytes": int(size), "size_label": size_label,
    "abi": "arm64-v8a", "min_android": "Android 8.0", "sha256": sha,
    "fetched_at": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%MZ"),
    "source": f"https://github.com/lyydfys/MuYu-Chat-Agent/releases/download/{tag}/{asset}",
    "sha256_verified": bool(sha),
}
(dl / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
                                  encoding="utf-8")

release = {
    "schema": "mca.website.release.v1",
    "generated_at": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "tag": tag, "published_date": published,
    "asset_name": asset, "size_bytes": int(size), "size_label": size_label,
    "sha256": sha, "abi": "arm64-v8a", "min_android": "Android 8.0", "license": "Apache-2.0",
    "download_url": f"https://mca.mac-api.xyz/downloads/{asset}",
    "releases_url": "https://github.com/lyydfys/MuYu-Chat-Agent/releases",
}
(data / "release.json").write_text(json.dumps(release, ensure_ascii=False, indent=2) + "\n",
                                  encoding="utf-8")
PY
[ $? -eq 0 ] || die "data rewrite failed"
# Changelog data: every release, so the history page never goes stale.
python3 - "$API" <<'PY2' >/dev/null
import json, sys, pathlib, urllib.request
api = sys.argv[1]
req = urllib.request.Request(api + "?per_page=100",
        headers={"User-Agent": "mca-site-sync",
                 "Accept": "application/vnd.github+json"})
raw = json.loads(urllib.request.urlopen(req, timeout=40).read())
rels = []
for r in raw:
    if r.get("draft"):
        continue
    apk = next((a for a in (r.get("assets") or []) if a["name"].lower().endswith(".apk")), None)
    sha = ""
    if apk and (apk.get("digest") or "").startswith("sha256:"):
        sha = apk["digest"].split(":", 1)[1]
    rels.append({"tag": r["tag_name"], "name": r.get("name") or r["tag_name"],
                 "published": r["published_at"][:10],
                 "prerelease": bool(r.get("prerelease")),
                 "url": r["html_url"], "body": (r.get("body") or "").strip(),
                 "asset": apk["name"] if apk else None,
                 "size_bytes": apk["size"] if apk else None,
                 "size_label": ("%.1f MB" % (apk["size"]/1024/1024)) if apk else None,
                 "sha256": sha})
rels.sort(key=lambda x: x["published"], reverse=True)
out = pathlib.Path("/opt/mca-site/html/data/releases.json")
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps({"schema": "mca.website.releases.v1",
                           "source": api, "count": len(rels),
                           "releases": rels}, ensure_ascii=False, indent=2) + "\n",
               encoding="utf-8")
PY2
[ $? -eq 0 ] || log "WARN: changelog refresh failed (page still valid)"

log "data/release.json, data/releases.json and downloads/manifest.json updated"

log "OK: now serving $TAG ($SIZE_LABEL)"
