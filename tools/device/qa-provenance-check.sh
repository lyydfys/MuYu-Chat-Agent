#!/system/bin/sh
# Verify the provenance of the 'mnn-qwen35-2b' catalog entry (suspected EARLY TEST RESIDUE from my own
# build-verification work, where I copied an MNN chat bundle into files/models/). Also check whether the
# e2e53c85 bundle is registered as chat or image by the app's own prefs.
BASE=/sdcard/Android/data/com.muyuchat.mca/files
echo "=== 1) does mnn-qwen35-2b have a bundle dir anywhere? ==="
ls -d $BASE/models/mnn-qwen35-2b 2>/dev/null || echo "  NOT in files/models/mnn-qwen35-2b"
ls -d $BASE/image_models/mnn-qwen35-2b 2>/dev/null || echo "  NOT in image_models/mnn-qwen35-2b"
echo "  find (maxdepth 4, name *qwen35*):"
find $BASE -maxdepth 4 -name '*qwen35*' 2>/dev/null | head -5 | sed 's/^/    /'
[ -z "$(find $BASE -maxdepth 4 -name '*qwen35*' 2>/dev/null)" ] && echo "    (none)"
echo ""
echo "=== 2) files/models contents ==="
ls -la $BASE/models/ | sed 's/^/  /'
echo ""
echo "=== 3) app's image-model pref: which ids does it know about? ==="
run-as com.muyuchat.mca cat shared_prefs/mca_local_image_models.xml 2>/dev/null | tr '<' '\n' | grep -oE 'name="[^"]*"' | sed 's/^/  /' | head -14
echo ""
echo "=== 4) is e2e53c85 known to the image pref, and single-1d2d9be8 to any chat pref? ==="
echo -n "  e2e53c85 in image prefs: "
run-as com.muyuchat.mca cat shared_prefs/mca_local_image_models.xml 2>/dev/null | grep -c 'e2e53c85'
echo -n "  single-1d2d9be8 in image prefs: "
run-as com.muyuchat.mca cat shared_prefs/mca_local_image_models.xml 2>/dev/null | grep -c 'single-1d2d9be8'
echo "  chat/model pref files available:"
run-as com.muyuchat.mca ls shared_prefs/ 2>/dev/null | grep -iE 'chat|model' | sed 's/^/    /'
