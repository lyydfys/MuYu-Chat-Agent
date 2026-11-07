#!/usr/bin/env python3
"""Port the remaining Android patches block by block, keeping only what still parses.

Two files (session-persistence-jsonl, attachment-local) applied five of six blocks and then failed
`node --check`: an ambiguous tail anchor let one insertion land inside a statement. Rather than
reason about each by hand, this script applies candidate blocks one at a time and keeps each one
only when the file still parses. A rejected block is reported, never silently dropped.

Usage: python scripts/dsh/port-android-patches-verified.py [--apply]
"""
import os
import subprocess
import sys
import zipfile
from difflib import SequenceMatcher

REFERENCE_ZIP = r"E:\MAC\artifacts\dsh-verify\20261020-001623\identity\payload.zip"
OFFICIAL_021 = r"E:\tmp\dsh-upgrade\probe021\node_modules\@deepseek-ai"
PRISTINE_022 = r"E:\tmp\dsh-upgrade\probe2\node_modules\@deepseek-ai"
PAYLOAD_022 = r"E:\tmp\dsh-upgrade\payload-v2\dshroot\lib\node_modules\@deepseek-ai"

TARGETS = [
    ("dsh-session-persistence-jsonl", "lib/index.js"),
    ("dsh-attachment-local", "lib/index.js"),
]


def ref_text(pkg, rel):
    with zipfile.ZipFile(REFERENCE_ZIP) as archive:
        return archive.read(
            "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/%s/%s" % (pkg, rel)
        ).decode("utf-8", "ignore")


def parses(text):
    probe = os.path.join(os.environ.get("TEMP", "."), "mca-patch-verify.js")
    with open(probe, "w", encoding="utf-8", newline="") as handle:
        handle.write(text)
    result = subprocess.run(["node", "--check", probe], capture_output=True)
    return result.returncode == 0


def main():
    apply = "--apply" in sys.argv
    for pkg, rel in TARGETS:
        ref = ref_text(pkg, rel)
        base = open(os.path.join(OFFICIAL_021, pkg, rel), encoding="utf-8", errors="ignore").read()
        ours = open(os.path.join(PRISTINE_022, pkg, rel), encoding="utf-8", errors="ignore").read()
        if not parses(ours):
            print("%s/%s: pristine file does not parse; skipping" % (pkg, rel))
            continue

        blocks = []
        for tag, i1, i2, j1, j2 in SequenceMatcher(None, base, ref).get_opcodes():
            if tag != "equal":
                blocks.append((i1, i2, base[i1:i2], ref[j1:j2]))

        kept, rejected = 0, []
        for index, (i1, i2, old, new) in enumerate(blocks):
            head = base[max(0, i1 - 48):i1]
            tail = base[i2:i2 + 48]
            if head and ours.count(head) != 1:
                rejected.append("block %d: head anchor not unique" % index)
                continue
            at = ours.rfind(head) + len(head) if head else 0
            if tail:
                if ours.count(tail) != 1 or ours.find(tail) != at:
                    rejected.append("block %d: tail anchor mismatch" % index)
                    continue
            if ours[at:at + len(old)] != old:
                rejected.append("block %d: region changed upstream" % index)
                continue
            candidate = ours[:at] + new + ours[at + len(old):]
            if not parses(candidate):
                rejected.append("block %d: applying it breaks the parser" % index)
                continue
            ours = candidate
            kept += 1

        print("%s/%s: kept %d/%d blocks, final parse %s"
              % (pkg, rel, kept, len(blocks), parses(ours)))
        for item in rejected:
            print("   !", item)
        if apply and kept:
            with open(os.path.join(PAYLOAD_022, pkg, rel), "w", encoding="utf-8", newline="") as handle:
                handle.write(ours)
    print("dry run" if not apply else "WRITTEN")


if __name__ == "__main__":
    main()
