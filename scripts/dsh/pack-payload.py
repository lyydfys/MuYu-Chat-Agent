#!/usr/bin/env python3
"""Pack the DSH payload directory into `payload.zip` with portable entry names.

Why this exists instead of `Compress-Archive`/`ZipFile.CreateFromDirectory`: the .NET helpers on
Windows write **backslash** separators into the archive, and the installer (Kotlin, JVM) treats a
backslash as an ordinary filename character. The result is a payload whose `dshhome/` and
`runtime/` entries are invisible to every consumer that looks for `/`, which fails on the device
long after the build looked successful. Python's zipfile always writes `/`.
"""
import os
import sys
import time
import zipfile

SOURCE = sys.argv[1] if len(sys.argv) > 1 else r"E:\tmp\dsh-upgrade\payload-new"
TARGET = sys.argv[2] if len(sys.argv) > 2 else r"E:\tmp\dsh-upgrade\payload-new.zip"


def main():
    started = time.time()
    files = []
    for dirpath, _dirnames, filenames in os.walk(SOURCE):
        for name in filenames:
            full = os.path.join(dirpath, name)
            rel = os.path.relpath(full, SOURCE).replace(os.sep, "/")
            files.append((rel, full))
    # Deterministic order: the manifest pins the resulting archive hash.
    files.sort()

    if os.path.exists(TARGET):
        os.remove(TARGET)

    with zipfile.ZipFile(TARGET, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for rel, full in files:
            info = zipfile.ZipInfo(rel, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (0o644 & 0xFFFF) << 16
            with open(full, "rb") as src, archive.open(info, "w") as dst:
                while True:
                    chunk = src.read(1024 * 1024)
                    if not chunk:
                        break
                    dst.write(chunk)

    size = os.path.getsize(TARGET)
    with zipfile.ZipFile(TARGET) as archive:
        entries = [i.filename for i in archive.infolist() if not i.is_dir]
    bad = [e for e in entries if "\\" in e]
    if bad:
        sys.exit("payload contains backslash entry names: %r" % bad[:3])

    print("entries:", len(entries))
    print("size:", size)
    print("seconds:", int(time.time() - started))


if __name__ == "__main__":
    main()
