#!/usr/bin/env bash
# Pull the server's live release state back into the repository.
#
# Why this exists: the page's release facts are rewritten IN PLACE on the server by
# sync-release.sh. The local index.html therefore drifts behind, and every subsequent
# `tar -xf` deploy silently reverts the site to the version somebody last built locally.
# That is exactly what happened here: a deploy overwrote v0.4.3 with v0.4.1.
#
# The rule is: the SERVER is authoritative for release facts, the repository is authoritative for
# everything else. `pull-release-state.sh` copies the release-state files back so the two agree
# before the next deploy.
#
# Files pulled:
#   html/index.html          (release markers rewritten in place)
#   html/data/release.json
#   html/data/releases.json
#   html/downloads/manifest.json
set -euo pipefail
SRC=/opt/mca-site/html
DST=${1:?destination repo path required}

cp -a "$SRC/index.html"          "$DST/docs/website/index.html"
cp -a "$SRC/data/release.json"   "$DST/docs/website/data/release.json"
cp -a "$SRC/data/releases.json"  "$DST/docs/website/data/releases.json"
cp -a "$SRC/downloads/manifest.json" "$DST/docs/website/data/manifest.json"

echo "pulled release state from server:"
for f in "$DST/docs/website/index.html" \
         "$DST/docs/website/data/release.json" \
         "$DST/docs/website/data/releases.json" \
         "$DST/docs/website/data/manifest.json"; do
  printf '  %-52s %s\n' "$(basename "$(dirname "$f")")/$(basename "$f")" "$(stat -c %s "$f")"
done
