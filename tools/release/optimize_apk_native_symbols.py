#!/usr/bin/env python3
"""Externalize native debug symbols and strip an unsigned release APK.

The input APK must not be signed (or its signature is discarded). The output
APK is unsigned; the caller must zipalign and sign it. Every original native
library is preserved in the symbols ZIP. AArch64 ELF64 libraries are stripped
with --strip-unneeded only after their original bytes and SHA-256 are recorded.
Hexagon ELF32 DSP companions are copied unchanged.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import struct
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

EM_AARCH64 = 183
EM_HEXAGON = 164
SIGNATURE_PREFIXES = ("META-INF/",)


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def find_llvm_strip(explicit: str | None) -> Path:
    if explicit:
        path = Path(explicit)
        if path.is_file():
            return path
        raise FileNotFoundError(f"llvm-strip not found: {path}")
    candidates = []
    for root in (Path("E:/model/android-sdk/ndk"), Path(os.environ.get("ANDROID_HOME", "")) / "ndk"):
        if root.is_dir():
            candidates.extend(root.glob("*/toolchains/llvm/prebuilt/*/bin/llvm-strip*"))
    for path in sorted(candidates, reverse=True):
        if path.is_file():
            return path
    raise FileNotFoundError("llvm-strip was not found under the Android NDK")


def elf_identity(data: bytes) -> tuple[int | None, int | None]:
    if len(data) < 20 or data[:4] != b"\x7fELF" or data[5] != 1:
        return None, None
    return data[4], struct.unpack_from("<H", data, 18)[0]


def build_id(readelf: Path, path: Path) -> str | None:
    result = subprocess.run([str(readelf), "-n", str(path)], capture_output=True, text=True)
    if result.returncode != 0:
        return None
    match = re.search(r"Build ID:\s*([0-9a-fA-F]+)", result.stdout)
    return match.group(1).lower() if match else None


def deterministic_info(name: str, original: zipfile.ZipInfo | None = None) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    # Preserve Android's required ZIP storage mode (notably resources.arsc must
    # remain STORED and 4-byte aligned); only use deflate for new metadata.
    info.compress_type = (
        original.compress_type
        if original is not None and original.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED)
        else zipfile.ZIP_DEFLATED
    )
    info.create_system = 3
    if original is not None:
        info.external_attr = original.external_attr
        info.internal_attr = original.internal_attr
    return info


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-apk", required=True, type=Path)
    parser.add_argument("--output-apk", required=True, type=Path)
    parser.add_argument("--symbols-zip", required=True, type=Path)
    parser.add_argument("--llvm-strip", default=None)
    parser.add_argument("--readelf", default=None)
    args = parser.parse_args()

    input_apk = args.input_apk.resolve()
    output_apk = args.output_apk.resolve()
    symbols_zip = args.symbols_zip.resolve()
    strip = find_llvm_strip(args.llvm_strip)
    readelf = Path(args.readelf).resolve() if args.readelf else strip.with_name("llvm-readelf.exe" if os.name == "nt" else "llvm-readelf")
    if not readelf.is_file():
        raise FileNotFoundError(f"llvm-readelf not found: {readelf}")
    output_apk.parent.mkdir(parents=True, exist_ok=True)
    symbols_zip.parent.mkdir(parents=True, exist_ok=True)

    manifest = []
    stripped_count = 0
    original_bytes_total = 0
    output_bytes_total = 0
    with tempfile.TemporaryDirectory(prefix="mca-native-strip-") as temp_dir:
        temp_root = Path(temp_dir)
        with zipfile.ZipFile(input_apk) as source, zipfile.ZipFile(output_apk, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as target:
            for info in sorted(source.infolist(), key=lambda item: item.filename):
                name = info.filename
                if name.startswith("META-INF/") and name.upper().endswith((".SF", ".RSA", ".DSA", ".EC")):
                    continue
                if name.endswith("/"):
                    continue
                data = source.read(name)
                machine = None
                elf_class = None
                replaced = False
                if name.startswith("lib/arm64-v8a/") and name.endswith(".so"):
                    elf_class, machine = elf_identity(data)
                    original_bytes_total += len(data)
                    if elf_class == 2 and machine == EM_AARCH64:
                        extracted = temp_root / sha256_bytes(data)
                        extracted.write_bytes(data)
                        result = subprocess.run([str(strip), "--strip-unneeded", str(extracted)], capture_output=True, text=True)
                        if result.returncode != 0:
                            raise RuntimeError(f"llvm-strip failed for {name}: {result.stderr or result.stdout}")
                        stripped = extracted.read_bytes()
                        manifest.append({
                            "path": name,
                            "bytes": len(data),
                            "sha256": sha256_bytes(data),
                            "stripped_bytes": len(stripped),
                            "stripped_sha256": sha256_bytes(stripped),
                            "elf_class": elf_class,
                            "machine": machine,
                            "build_id": build_id(readelf, extracted),
                        })
                        data = stripped
                        replaced = True
                        stripped_count += 1
                output_bytes_total += len(data)
                target.writestr(deterministic_info(name, info), data)

        with zipfile.ZipFile(symbols_zip, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as symbols:
            for item in manifest:
                with zipfile.ZipFile(input_apk) as original:
                    original_data = original.read(item["path"])
                symbols.writestr(deterministic_info(item["path"]), original_data)
            manifest_bytes = (json.dumps({
                "schema": "mca.native-debug-symbols/1",
                "sourceApkSha256": sha256_hash(input_apk),
                "stripTool": str(strip),
                "libraries": manifest,
            }, indent=2, sort_keys=True) + "\n").encode("utf-8")
            symbols.writestr(deterministic_info("native-symbols-manifest.json"), manifest_bytes)

    result = {
        "schema": "mca.native-strip-result/1",
        "inputApk": str(input_apk),
        "outputApk": str(output_apk),
        "symbolsZip": str(symbols_zip),
        "strippedLibraries": stripped_count,
        "inputLibraryBytes": original_bytes_total,
        "outputLibraryBytes": output_bytes_total,
        "symbolsBytes": symbols_zip.stat().st_size,
        "outputApkBytes": output_apk.stat().st_size,
        "libraries": manifest,
    }
    print(json.dumps(result, indent=2, ensure_ascii=False))
    return 0


def sha256_hash(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


if __name__ == "__main__":
    raise SystemExit(main())