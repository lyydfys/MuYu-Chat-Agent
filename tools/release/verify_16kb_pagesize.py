#!/usr/bin/env python3
"""Audit every Android ABI native library packaged in a specific APK."""

import argparse
import hashlib
import json
import struct
import sys
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
REPORT_PATH = REPO_ROOT / "tools" / "release" / "16kb_page_size_report.json"
REQUIRED_ALIGNMENT = 0x4000
ANDROID_ABI_MACHINES = {"arm64-v8a": 183, "x86_64": 62}


def check_elf_alignment(data: bytes) -> dict:
    if len(data) < 64 or data[:4] != b"\x7fELF":
        return {"compliant": False, "details": "Not an ELF64 binary"}
    if data[4] != 2 or data[5] != 1:
        return {"compliant": False, "details": "Expected little-endian ELF64"}

    machine = struct.unpack_from("<H", data, 18)[0]
    phoff = struct.unpack_from("<Q", data, 32)[0]
    phentsize = struct.unpack_from("<H", data, 54)[0]
    phnum = struct.unpack_from("<H", data, 56)[0]
    if phoff == 0 or phnum == 0 or phentsize < 56:
        return {"compliant": False, "machine": machine, "details": "Invalid program header table"}

    alignments = []
    for index in range(phnum):
        offset = phoff + index * phentsize
        if offset + phentsize > len(data):
            return {"compliant": False, "machine": machine, "details": "Truncated program header table"}
        if struct.unpack_from("<I", data, offset)[0] != 1:
            continue
        file_offset = struct.unpack_from("<Q", data, offset + 8)[0]
        virtual_address = struct.unpack_from("<Q", data, offset + 16)[0]
        alignment = struct.unpack_from("<Q", data, offset + 48)[0]
        congruent = alignment in (0, 1) or (virtual_address - file_offset) % alignment == 0
        alignments.append({"p_align": alignment, "congruent": congruent})

    compliant = bool(alignments) and all(
        segment["p_align"] >= REQUIRED_ALIGNMENT and segment["congruent"]
        for segment in alignments
    )
    return {
        "compliant": compliant,
        "machine": machine,
        "pt_load_segments": alignments,
        "details": "All PT_LOAD segments meet 16 KB alignment" if compliant else
            "At least one PT_LOAD segment fails 16 KB alignment",
    }


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--apk",
        type=Path,
        default=REPO_ROOT / "app/build/outputs/apk/release/app-release.apk",
        help="APK to audit (defaults to the release APK)",
    )
    args = parser.parse_args()
    apk = args.apk.resolve()
    if not apk.is_file():
        print(f"FAILED: APK does not exist: {apk}", file=sys.stderr)
        return 2

    results = []
    with zipfile.ZipFile(apk) as archive:
        entries = sorted(
            name for name in archive.namelist()
            if name.startswith("lib/") and name.endswith(".so")
        )
        if not entries:
            print("FAILED: APK contains no packaged native libraries", file=sys.stderr)
            return 1

        for name in entries:
            parts = name.split("/")
            abi = parts[1] if len(parts) >= 3 else ""
            audit = check_elf_alignment(archive.read(name))
            expected_machine = ANDROID_ABI_MACHINES.get(abi)
            machine_matches = expected_machine is not None and audit.get("machine") == expected_machine
            compliant = audit["compliant"] and machine_matches
            results.append({
                "path": name,
                "abi": abi,
                "machine": audit.get("machine"),
                "machine_matches_abi": machine_matches,
                "is_16kb_compliant": compliant,
                "pt_load_segments": audit.get("pt_load_segments", []),
                "details": audit["details"] if machine_matches else
                    f"ELF machine does not match Android ABI {abi}",
            })
            status = "COMPLIANT" if compliant else "NON_COMPLIANT"
            print(f"[{status}] {name}: {results[-1]['details']}")

    compliant_count = sum(item["is_16kb_compliant"] for item in results)
    report = {
        "title": "MCA APK 16 KB Page-Size Alignment Audit",
        "apk_path": str(apk.relative_to(REPO_ROOT)) if apk.is_relative_to(REPO_ROOT) else str(apk),
        "apk_sha256": sha256_file(apk),
        "target_page_size_bytes": REQUIRED_ALIGNMENT,
        "total_binaries_scanned": len(results),
        "compliant_binaries": compliant_count,
        "non_compliant_binaries": len(results) - compliant_count,
        "binaries": results,
    }
    REPORT_PATH.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Report: {REPORT_PATH}")
    print(f"Summary: {compliant_count}/{len(results)} packaged libraries compliant")
    return 0 if compliant_count == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
