#!/usr/bin/env python3
"""Port every reference Android patch onto the official 0.2.2-rc.1 payload.

The reference payload (kernel 0.2.1-rc.2) carried 17 patched packages plus three new files. The
official closure does not contain any of that work, so replacing the kernel silently dropped it.

Strategy: the packaged `lib/index.js` files are effectively single-line bundles, so a line-based
three-way merge conflicts on every hunk. Instead this script works at the *character* level:

  base = official 0.2.1-rc.2 file   (what the reference patched)
  ref  = reference payload file     (the patched result)
  ours = official 0.2.2-rc.1 file   (what we are upgrading to)

For every change block base->ref it takes the surrounding context as an anchor and looks for that
anchor in `ours`. The block is applied only when both anchors exist exactly once and sit at the
same distance, which makes a misplaced insertion practically impossible. Anything that cannot be
matched is reported and left alone — a skipped patch is loud, a wrong patch is silent.
"""
import os
import re
import sys
import zipfile
from difflib import SequenceMatcher

REFERENCE_ZIP = r"E:\MAC\artifacts\dsh-verify\20261020-001623\identity\payload.zip"
OFFICIAL_021 = r"E:\tmp\dsh-upgrade\probe021\node_modules\@deepseek-ai"
PAYLOAD_022 = r"E:\tmp\dsh-upgrade\payload-v2\dshroot\lib\node_modules\@deepseek-ai"

PATCHED = {
    "dsh-api-session-controller": ["lib/index.js"],
    "dsh-api-terminal-controller": ["lib/index.js"],
    "dsh-app-boot": ["lib/index.js"],
    "dsh-attachment-local": ["lib/index.js"],
    "dsh-bash-local": ["lib/index.js"],
    "dsh-client-ui-layout": ["lib/client.js"],
    "dsh-client-ui-model-selection": ["lib/client.js"],
    "dsh-fs-local": ["lib/index.js"],
    "dsh-llm-deepseek": ["lib/index.js"],
    "dsh-ptc-runtime-node": ["lib/index.js"],
    "dsh-session-persistence-jsonl": ["lib/index.js"],
    "dsh-storage-json": ["lib/index.js"],
    "dsh-subprocess-local": ["lib/index.js", "lib/runner-launch-B2zsQ1Dz.js"],
    "dsh-tool-fs-search": ["lib/index.js"],
    "dsh-web-frontend": ["dist/index.html"],
    "node-addon-system": ["lib/flock.js"],
}

ADDED_FILES = {
    "dsh-attachment-local": ["lib/sharp-shim.js"],
    "dsh-web-frontend": ["dist/mobile.css", "dist/mobile.js"],
}

CONTEXT = 48


def ref_entry(pkg, rel):
    return "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/%s/%s" % (pkg, rel)


def apply_blocks(base, ref, ours, label, report):
    applied = 0
    for tag, i1, i2, j1, j2 in SequenceMatcher(None, base, ref).get_opcodes():
        if tag == "equal":
            continue
        old = base[i1:i2]
        new = ref[j1:j2]
        head = base[max(0, i1 - CONTEXT):i1]
        tail = base[i2:i2 + CONTEXT]
        head_at = ours.rfind(head) if head else -1
        if head and ours.count(head) != 1:
            report.append("%s: anchor head not unique (%d)" % (label, ours.count(head)))
            continue
        expect = head_at + len(head) if head else 0
        if tail:
            if ours.count(tail) != 1:
                report.append("%s: anchor tail not unique (%d)" % (label, ours.count(tail)))
                continue
            tail_at = ours.find(tail)
            if tail_at != expect:
                report.append("%s: anchors moved apart (expect %d got %d)" % (label, expect, tail_at))
                continue
        if ours[expect:expect + len(old)] != old:
            # The upstream delta touched the patched region; do not guess.
            report.append("%s: region differs upstream, skipped" % label)
            continue
        ours = ours[:expect] + new + ours[expect + len(old):]
        applied += 1
    return ours, applied


def main():
    dry = "--apply" not in sys.argv
    archive = zipfile.ZipFile(REFERENCE_ZIP)
    problems = []
    total = 0
    for pkg, files in sorted(PATCHED.items()):
        for rel in files:
            ours_path = os.path.join(PAYLOAD_022, pkg, rel.replace("/", os.sep))
            base_path = os.path.join(OFFICIAL_021, pkg, rel.replace("/", os.sep))
            if not (os.path.isfile(ours_path) and os.path.isfile(base_path)):
                problems.append("%s/%s missing on disk" % (pkg, rel))
                continue
            ref = archive.read(ref_entry(pkg, rel)).decode("utf-8", "ignore")
            base = open(base_path, encoding="utf-8", errors="ignore").read()
            ours = open(ours_path, encoding="utf-8", errors="ignore").read()
            if "Android" in ours and ref.count("Android") and ours.count("Android") >= ref.count("Android") - 1:
                problems.append("%s/%s already carries Android notes" % (pkg, rel))
            print("... %s/%s (%d bytes base, %d bytes ours)" % (pkg, rel, len(base), len(ours)),
                  flush=True)
            merged, count = apply_blocks(base, ref, ours, "%s/%s" % (pkg, rel), problems)
            total += count
            print("%-44s %d block(s) applied" % ("%s/%s" % (pkg, rel), count))
            if not dry and merged != ours:
                open(ours_path, "w", encoding="utf-8", newline="").write(merged)

    for pkg, files in ADDED_FILES.items():
        for rel in files:
            dest = os.path.join(PAYLOAD_022, pkg, rel.replace("/", os.sep))
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            data = archive.read(ref_entry(pkg, rel))
            if not dry:
                open(dest, "wb").write(data)
            print("%-44s new file (%d bytes)" % ("%s/%s" % (pkg, rel), len(data)))

    print("\nbocks applied:", total)
    print("problems:", len(problems))
    for item in problems:
        print("  !", item)
    print("dry run" if dry else "WRITTEN")


if __name__ == "__main__":
    main()
