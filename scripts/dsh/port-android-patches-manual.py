#!/usr/bin/env python3
"""Manual, per-file ports of the reference Android patches that the anchor pass could not place.

The generic pass (`apply-android-patches.py`) handles 36 blocks cleanly. Four files were left
broken because the patched region and the upstream 0.2.1->0.2.2 delta overlap, so a blind insertion
lands inside a statement. Each entry here names the exact anchor, inserts the reference block at
that anchor, and refuses to write when the anchor is missing or not unique.
"""
import os
import sys
import zipfile

REFERENCE_ZIP = r"E:\MAC\artifacts\dsh-verify\20261020-001623\identity\payload.zip"
PAYLOAD_022 = r"E:\tmp\dsh-upgrade\payload-v2\dshroot\lib\node_modules\@deepseek-ai"


def ref_text(pkg, rel):
    with zipfile.ZipFile(REFERENCE_ZIP) as archive:
        return archive.read(
            "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/%s/%s" % (pkg, rel)
        ).decode("utf-8", "ignore")


def apply_edges(text, edits, label):
    problems = []
    for kind, anchor, payload in edits:
        if text.count(anchor) != 1:
            problems.append("%s: anchor appears %d times: %r" % (label, text.count(anchor), anchor[:60]))
            continue
        index = text.find(anchor)
        if kind == "after":
            text = text[:index + len(anchor)] + payload + text[index + len(anchor):]
        elif kind == "before":
            text = text[:index] + payload + text[index:]
        elif kind == "replace":
            text = text[:index] + payload + text[index + len(anchor):]
        else:
            problems.append("%s: unknown edit kind %r" % (label, kind))
    return text, problems


def port_by_anchors(pkg, rel, write, problems):
    """Apply base->ref blocks to ours from the end backwards, so offsets never shift."""
    from difflib import SequenceMatcher
    ref = ref_text(pkg, rel)
    base_text = open(os.path.join(r"E:\tmp\dsh-upgrade\probe021\node_modules\@deepseek-ai", pkg, rel),
                     encoding="utf-8", errors="ignore").read()
    path = os.path.join(PAYLOAD_022, pkg, rel)
    ours = open(path, encoding="utf-8", errors="ignore").read()
    blocks = []
    for tag, i1, i2, j1, j2 in SequenceMatcher(None, base_text, ref).get_opcodes():
        if tag == "equal":
            continue
        old, new = base_text[i1:i2], ref[j1:j2]
        head = base_text[max(0, i1 - 48):i1]
        tail = base_text[i2:i2 + 48]
        if head and ours.count(head) != 1:
            problems.append("%s: head anchor not unique" % pkg); continue
        index = ours.rfind(head) + len(head) if head else None
        if tail:
            if ours.count(tail) != 1:
                problems.append("%s: tail anchor not unique" % pkg); continue
            if ours.find(tail) != index:
                problems.append("%s: anchors moved apart" % pkg); continue
        if index is None or ours[index:index + len(old)] != old:
            problems.append("%s: region differs upstream" % pkg); continue
        blocks.append((index, index + len(old), new))
    for start, end, new in sorted(blocks, key=lambda b: -b[0]):
        ours = ours[:start] + new + ours[end:]
    print("%s/%s: %d/%d blocks" % (pkg, rel, len(blocks), len(blocks) + 1))
    if write and blocks:
        open(path, "w", encoding="utf-8", newline="").write(ours)


def main():
    write = "--apply" in sys.argv
    problems = []

    # --- dsh-subprocess-local: Android substitute for node-pty -------------------------------
    pkg, rel = "dsh-subprocess-local", "lib/index.js"
    ref = ref_text(pkg, rel)
    path = os.path.join(PAYLOAD_022, pkg, rel)
    ours = open(path, encoding="utf-8", errors="ignore").read()

    marker = "function signalName(number) {"
    start = ref.find(marker)
    assert start != -1, "reference signalName missing"
    # The inserted block starts at the doc comment that follows signalName and ends right before
    # the next top-level declaration that also exists in ours.
    doc_start = ref.find("/**", start)
    tail_probe = None
    for candidate in ("function signalNumberCompat", "var constants$1", "const constants$1",
                      "function createProcessInspector", "class ", "function spawn"):
        pos = ref.find(candidate, doc_start)
        if pos != -1:
            tail_probe = ref[pos:pos + 24]
            block = ref[doc_start:pos]
            break
    old_fs = 'writeFileSync } from "node:fs";'
    new_fs = ('fstatSync, mkdtempSync, openSync, readFileSync, readSync, rmSync, statSync, '
              'writeFileSync, writeSync } from "node:fs";')
    if old_fs in ours:
        edits = [("replace", old_fs, new_fs)]
        if tail_probe and block:
            anchor = "}\n" + tail_probe if ("}\n" + tail_probe) in ours else None
            if anchor:
                edits.append(("before", anchor, block))
            else:
                problems.append("%s: tail anchor for the pty block not found" % pkg)
        else:
            problems.append("%s: could not delimit the reference pty block" % pkg)
        ours, errs = apply_edges(ours, edits, "%s/%s" % (pkg, rel))
        problems.extend(errs)
        print("%s/%s handled (%d edits)" % (pkg, rel, len(edits)))
        if write:
            open(path, "w", encoding="utf-8", newline="").write(ours)
    else:
        problems.append("%s: fs import line not found" % pkg)

    for pkg, rel in [("dsh-session-persistence-jsonl", "lib/index.js"),
                     ("dsh-attachment-local", "lib/index.js"),
                     ("dsh-client-ui-model-selection", "lib/client.js"),
                     ("dsh-ptc-runtime-node", "lib/index.js")]:
        port_by_anchors(pkg, rel, write, problems)

    print("problems:", len(problems))
    for item in problems:
        print("  !", item)
    print("dry run" if not write else "WRITTEN")


if __name__ == "__main__":
    main()
