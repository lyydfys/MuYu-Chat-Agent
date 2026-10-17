#!/system/bin/sh
# READ-ONLY inventory: does the device hold any MNN / GenieX / QAIRT chat asset, and does the
# frontend list hide any engine? No writes, no UI, no state change.
echo "=== 1) files/models/ : actual directories and their file types ==="
for d in /sdcard/Android/data/com.muyuchat.mca/files/models/*/; do
  echo "  DIR $(basename $d)"
  ls -l "$d" 2>/dev/null | awk 'NR>1 {printf "      %10d  %s\n", $5, $NF}'
done
echo ""
echo "=== 2) any .gguf / .litertlm / llm.mnn / .dlc / .bin anywhere under files/ ==="
find /sdcard/Android/data/com.muyuchat.mca/files -maxdepth 4 \( -name '*.gguf' -o -name '*.litertlm' -o -name 'llm.mnn' -o -name '*.dlc' -o -name '*.bin' -o -name 'llm.mnn.weight' \) 2>/dev/null | sed 's/^/  /'
echo ""
echo "=== 3) app prefs that mention engine/runtime keys (names only, no values) ==="
run-as com.muyuchat.mca ls shared_prefs/ 2>/dev/null | sed 's/^/  /'
echo ""
echo "=== 4) does any pref file mention mnn/geniex/qairt runtime ids? (count only) ==="
for f in $(run-as com.muyuchat.mca ls shared_prefs/ 2>/dev/null); do
  C=$(run-as com.muyuchat.mca cat shared_prefs/$f 2>/dev/null | grep -oE 'mnn_llm|geniex_llama_cpp|geniex_qairt|mnn_cpu' | wc -l)
  [ "$C" -gt 0 ] && echo "  $f : $C hit(s)"
done
echo "  (no lines above = no pref references those runtime ids)"
echo ""
echo "=== 5) download receipts: which model ids were ever downloaded? ==="
run-as com.muyuchat.mca cat shared_prefs/mca_download_receipts.xml 2>/dev/null | tr '<' '\n' | grep -oE 'name="[^"]*"' | sed 's/^/  /' | head -20
echo ""
echo "=== 6) profileInstalled/ and stage_export/ (Lead-mentioned) ==="
ls -la /sdcard/Android/data/com.muyuchat.mca/files/profileInstalled/ 2>/dev/null | head -8 | sed 's/^/  /'
ls -la /sdcard/Android/data/com.muyuchat.mca/files/stage_export/ 2>/dev/null | head -8 | sed 's/^/  /'
