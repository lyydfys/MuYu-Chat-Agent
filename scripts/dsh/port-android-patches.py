#!/usr/bin/env python3
"""Port the reference Android patches onto the official 0.2.2-rc.1 payload.

The reference payload (woaiys3/deepseek-harness-android-app, kernel 0.2.1-rc.2) carried ~17 small
patches that make the harness work on Android: no hardlink support on f2fs/SELinux, no sandbox
service, a plugin log the kernel otherwise swallows, and so on. Replacing the kernel with the
official closure silently dropped every one of them.

This script finds them mechanically instead of by memory:

  ours   = the file in the new payload (official 0.2.2-rc.1)
  base   = the same file from official 0.2.1-rc.2 (what the reference patched)
  theirs = the reference payload's patched file

`git merge-file` then performs a real three-way merge. A clean merge is applied; a conflict is
reported and left untouched so it can be resolved by hand — never by dropping the patch.

Usage:
  python scripts/dsh/port-android-patches.py [--apply]
"""
import os
import re
import subprocess
import sys
import zipfile

REFERENCE_ZIP = r"E:\MAC\artifacts\dsh-verify\20261020-001623\identity\payload.zip"
OFFICIAL_021 = r"E:\tmp\dsh-upgrade\probe021\node_modules\@deepseek-ai"
PAYLOAD_022 = r"E:\tmp\dsh-upgrade\payload-v2\dshroot\lib\node_modules\@deepseek-ai"

# Packages the reference patched, discovered by hashing every vendored @deepseek-ai package
# against the official 0.2.1-rc.2 tarballs. Files that differ are the patches.
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


def reference_entry(pkg, rel):
    return "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/%s/%s" % (pkg, rel)


def main():
    apply = "--apply" in sys.argv
    zip_handle = zipfile.ZipFile(REFERENCE_ZIP)
    clean, conflicted, missing = [], [], []

    for pkg, files in sorted(PATCHED.items()):
        for rel in files:
            ours_path = os.path.join(PAYLOAD_022, pkg, rel.replace("/", os.sep))
            base_path = os.path.join(OFFICIAL_021, pkg, rel.replace("/", os.sep))
            if not os.path.isfile(ours_path) or not os.path.isfile(base_path):
                missing.append("%s/%s" % (pkg, rel))
                continue
            try:
                theirs = zip_handle.read(reference_entry(pkg, rel))
            except KeyError:
                missing.append("%s/%s (reference)" % (pkg, rel))
                continue

            merged = subprocess.run(
                ["git", "merge-file", "-p", "-L", "ours-0.2.2", "-L", "base-0.2.1",
                 "-L", "reference-patched", ours_path, base_path, "-"],
                input=theirs, capture_output=True,
            )
            label = "%s/%s" % (pkg, rel)
            if merged.returncode == 0:
                clean.append(label)
                if apply:
                    with open(ours_path, "wb") as handle:
                        handle.write(merged.stdout)
            else:
                conflicted.append(label)
                conflict_path = ours_path + ".merge-conflict"
                with open(conflict_path, "wb") as handle:
                    handle.write(merged.stdout)

    print("clean merges: %d" % len(clean))
    for item in clean:
        print("  +", item)
    print("conflicts: %d" % len(conflicted))
    for item in conflicted:
        print("  !", item)
    print("missing: %d" % len(missing))
    for item in missing:
        print("  ?", item)
    if apply:
        print("APPLIED clean merges into", PAYLOAD_022)
    else:
        print("dry run; re-run with --apply to write")


if __name__ == "__main__":
    main()
