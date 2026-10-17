#!/system/bin/sh
# Presence-only inspection of cloud config. NEVER prints values (no keys, no URLs, no prompts).
# NOTE: the file test must run under run-as; a plain shell cannot stat the app's private dir.
echo "=== mca_cloud_api.xml: key names + value lengths only ==="
run-as com.muyuchat.mca cat shared_prefs/mca_cloud_api.xml 2>/dev/null | tr '<' '\n' | grep -E '^(string|boolean|int|long|set)' | while read -r line; do
  NAME=$(echo "$line" | sed -n 's/.*name="\([^"]*\)".*/\1/p')
  VAL=$(echo "$line" | sed -n 's/.*>\([^<]*\)<.*/\1/p')
  [ -z "$NAME" ] && continue
  echo "  key=$NAME  value_len=${#VAL}"
done
echo ""
echo "=== raw entry count ==="
echo "  entries: $(run-as com.muyuchat.mca cat shared_prefs/mca_cloud_api.xml 2>/dev/null | grep -c 'name=')"
echo ""
echo "=== other cloud-ish prefs present? (names only) ==="
run-as com.muyuchat.mca ls shared_prefs/ 2>/dev/null | grep -iE 'cloud|provider|model' | sed 's/^/  /'