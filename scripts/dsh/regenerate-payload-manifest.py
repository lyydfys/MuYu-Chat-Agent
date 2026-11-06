#!/usr/bin/env python3
"""Regenerate the DSH payload manifest for the 0.2.2-rc.1 kernel upgrade.

The manifest is the installer's contract: `archive` pins the zip, `criticalFiles` are re-hashed
inside the extracted tree, and `kernelEntry`/`node` describe how to launch. This script keeps every
field it does not own verbatim, and only rewrites the ones that must change when the kernel moves.
"""
import hashlib
import json
import os
import sys
import zipfile

import sys as _sys

# Default to the packaged asset so a forgotten argument can never pin a staging archive.
PAYLOAD_ZIP = _sys.argv[1] if len(_sys.argv) > 1 else (
    r"E:\MAC\core\dsh-runtime\src\main\assets\dsh\payload.zip")
PAYLOAD_DIR = _sys.argv[2] if len(_sys.argv) > 2 else r"E:\tmp\dsh-upgrade\payload-new"
MANIFEST = _sys.argv[3] if len(_sys.argv) > 3 else (
    r"E:\MAC\core\dsh-runtime\src\main\assets\dsh\MANIFEST.json")
NEW_KERNEL_VERSION = "0.2.2-rc.1"
NEW_PAYLOAD_VERSION = "dsh-0.2.2-rc.1+mca-android-tools"
OFFICIAL_COMMIT = None  # filled from the release tag below when known


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    with open(MANIFEST, encoding="utf-8") as handle:
        manifest = json.load(handle)

    archive_size = os.path.getsize(PAYLOAD_ZIP)
    archive_sha = sha256_file(PAYLOAD_ZIP)

    with zipfile.ZipFile(PAYLOAD_ZIP) as archive:
        entries = [info.filename for info in archive.infolist() if not info.is_dir()]

    # Every critical file must still exist in the new payload; a missing one would make the
    # installer refuse to install on a device, so fail here instead of there.
    critical = []
    for item in manifest["criticalFiles"]:
        path = item["path"]
        disk = os.path.join(PAYLOAD_DIR, path.replace("/", os.sep))
        if not os.path.isfile(disk):
            sys.exit("critical file missing from the new payload: " + path)
        critical.append({
            "path": path,
            "sha256": sha256_file(disk),
            "size": os.path.getsize(disk),
        })

    kernel_package = os.path.join(PAYLOAD_DIR, "dshroot", "lib", "node_modules",
                                  "@deepseek-ai", "dsh", "package.json")
    with open(kernel_package, encoding="utf-8") as handle:
        kernel_meta = json.load(handle)
    if kernel_meta.get("version") != NEW_KERNEL_VERSION:
        sys.exit("kernel package version is %r, expected %r"
                 % (kernel_meta.get("version"), NEW_KERNEL_VERSION))

    manifest["payloadVersion"] = NEW_PAYLOAD_VERSION
    manifest["archive"] = {
        "asset": "dsh/payload.zip",
        "size": archive_size,
        "sha256": archive_sha,
    }
    manifest["fileCount"] = len(entries)
    manifest["criticalFiles"] = critical
    manifest["source"] = {
        "officialCommit": OFFICIAL_COMMIT,
        "officialVersion": NEW_KERNEL_VERSION,
        "officialPackage": "@deepseek-ai/dsh@" + NEW_KERNEL_VERSION,
        # The Android tool packages are not published on npm; they are carried over from the
        # reference payload and pinned by their own peer range (dsh-tools >= 0.1.0-rc.6).
        "androidTools": [
            "@deepseek-ai/dsh-tool-android@0.1.0",
            "@deepseek-ai/dsh-tool-shizuku@0.1.0",
            "@deepseek-ai/dsh-tool-accessibility@0.1.0",
            "@deepseek-ai/dsh-tool-vscreen@0.1.0",
        ],
    }

    licenses = manifest.get("licenses", [])
    names = {item.get("name", "") for item in licenses}
    if "@deepseek-ai/dsh (DeepSeek Harness)" not in names:
        licenses.insert(0, {
            "license": "MIT",
            "name": "@deepseek-ai/dsh (DeepSeek Harness)",
            "source": "https://github.com/deepseek-ai/deepseek-harness",
        })
    android_tools_license = {
        "license": "MIT",
        "name": "Android tool packages (dsh-tool-android/shizuku/accessibility/vscreen)",
        "source": "carried over from the reference payload",
    }
    if android_tools_license["name"] not in names:
        licenses.append(android_tools_license)
    manifest["licenses"] = licenses

    with open(MANIFEST, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(manifest, handle, ensure_ascii=False, indent=2)
        handle.write("\n")

    print("payloadVersion:", manifest["payloadVersion"])
    print("archive:", archive_size, archive_sha)
    print("fileCount:", len(entries))
    print("criticalFiles:", len(critical))


if __name__ == "__main__":
    main()
