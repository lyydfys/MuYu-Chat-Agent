#!/usr/bin/env python3
"""Apply reference patch blocks one at a time to the real payload file, verifying after each.

The previous attempt verified a *copy* in TEMP and disagreed with the real file, so a broken
insertion slipped through. This version writes the candidate to its final path, runs `node --check`
on that exact path, and restores the previous content when the check fails.

Usage: python scripts/dsh/port-blocks-verified.py <pkg> <rel> [--apply]
"""
import os
import shutil
import subprocess
import sys
import zipfile
from difflib import SequenceMatcher

REFERENCE_ZIP = r"E:\MAC\artifacts\dsh-verify\20261020-001623\identity\payload.zip"
OFFICIAL_021 = r"E:\tmp\dsh-upgrade\probe021\node_modules\@deepseek-ai"
PRISTINE_022 = r"E:\tmp\dsh-upgrade\probe2\node_modules\@deepseek-ai"
PAYLOAD_022 = r"E:\tmp\dsh-upgrade\payload-v2\dshroot\lib\node_modules\@deepseek-ai"


def ref_text(pkg, rel):
    with zipfile.ZipFile(REFERENCE_ZIP) as archive:
        return archive.read(
            "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/%s/%s" % (pkg, rel)
        ).decode("utf-8", "ignore")


def parses(path):
    result = subprocess.run(["node", "--check", path], capture_output=True)
    return result.returncode == 0, result.stderr.decode("utf-8", "ignore").splitlines()[:3]


def main():
    pkg, rel = sys.argv[1], sys.argv[2]
    apply = "--apply" in sys.argv
    target = os.path.join(PAYLOAD_022, pkg, rel.replace("/", os.sep))
    pristine = os.path.join(PRISTINE_022, pkg, rel.replace("/", os.sep))
    shutil.copyfile(pristine, target)

    ref = ref_text(pkg, rel)
    base = open(os.path.join(OFFICIAL_021, pkg, rel), encoding="utf-8", errors="ignore").read()
    ours = open(target, encoding="utf-8", errors="ignore").read()

    ok, why = parses(target)
    if not ok:
        print("pristine does not parse:", why)
        return

    # Positional alignment: map every base offset that survives unchanged into `ours`, then place
    # each patch block at its mapped offset. This disambiguates anchors that repeat in a bundle.
    mapping = []
    for tag, i1, i2, j1, j2 in SequenceMatcher(None, base, ours).get_opcodes():
        if tag == "equal":
            mapping.append((i1, i2, j1))

    def map_pos(pos):
        for b1, b2, j1 in mapping:
            if b1 <= pos <= b2:
                return j1 + (pos - b1)
        return None

    kept, rejected = 0, []
    raw = [(tag, i1, i2, j1, j2) for tag, i1, i2, j1, j2
           in SequenceMatcher(None, base, ref).get_opcodes() if tag != "equal"]
    # A coherent patch is often split into several adjacent blocks; applying one fragment alone
    # breaks the parser even though the whole group is valid. Merge blocks that are close together
    # so each candidate edit is a complete thought.
    GROUP_GAP = 200
    blocks = []
    for tag, i1, i2, j1, j2 in raw:
        if blocks and i1 - blocks[-1][2] <= GROUP_GAP:
            prev = blocks[-1]
            blocks[-1] = ("group", prev[1], i2, prev[3], j2)
        else:
            blocks.append((tag, i1, i2, j1, j2))
    for index, (tag, i1, i2, j1, j2) in enumerate(blocks):
        old, new = base[i1:i2], ref[j1:j2]
        at = map_pos(i1)
        at_end = map_pos(i2)
        if at is None or at_end is None:
            rejected.append("block %d: position falls in an upstream-changed region" % index)
            continue
        if ours[at:at_end] != old:
            rejected.append("block %d: mapped region text differs" % index)
            continue
        candidate = ours[:at] + new + ours[at_end:]
        previous = ours
        if apply:
            with open(target, "w", encoding="utf-8", newline="") as handle:
                handle.write(candidate)
            ok, why = parses(target)
            if not ok:
                rejected.append("block %d: breaks parser (%s)" % (index, why[-1] if why else "?"))
                with open(target, "w", encoding="utf-8", newline="") as handle:
                    handle.write(previous)
                continue
            ours = candidate
            # Recompute the alignment after a successful insertion.
            mapping = []
            for tg, a1, a2, b1, b2 in SequenceMatcher(None, base, ours).get_opcodes():
                if tg == "equal":
                    mapping.append((a1, a2, b1))
            kept += 1
            continue
        ours = candidate
        kept += 1
    # Blocks are applied in ascending order; rebuild the mapping each time so offsets stay valid.
    if not apply:
        pass

    if apply:
        with open(target, "w", encoding="utf-8", newline="") as handle:
            handle.write(ours)
    final_ok, _ = parses(target)
    print("%s/%s: kept %d, rejected %d, parses=%s" % (pkg, rel, kept, len(rejected), final_ok))
    for item in rejected:
        print("   !", item)


if __name__ == "__main__":
    main()
