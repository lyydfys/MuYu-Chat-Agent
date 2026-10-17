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
EM_HEXAGON = 164


def check_elf_alignment(data: bytes) -> dict:
    if len(data) < 52 or data[:4] != b"\x7fELF":
        return {"applicable": True, "compliant": False, "scope": "invalid", "details": "Not an ELF binary"}
    elf_class = data[4]
    if data[5] != 1:
        return {"applicable": True, "compliant": False, "scope": "invalid", "details": "Expected little-endian ELF"}
    machine = struct.unpack_from("<H", data, 18)[0]
    if elf_class == 2:
        if len(data) < 64:
            return {"applicable": True, "compliant": False, "scope": "invalid", "machine": machine, "details": "Truncated ELF64 header"}
        phoff = struct.unpack_from("<Q", data, 32)[0]
        phentsize = struct.unpack_from("<H", data, 54)[0]
        phnum = struct.unpack_from("<H", data, 56)[0]
        ph_format = "<IIQQQQQQ"
        min_phentsize = 56
        elf_class_name = "ELF64"
    elif elf_class == 1:
        if len(data) < 52:
            return {"applicable": True, "compliant": False, "scope": "invalid", "machine": machine, "details": "Truncated ELF32 header"}
        phoff = struct.unpack_from("<I", data, 28)[0]
        phentsize = struct.unpack_from("<H", data, 42)[0]
        phnum = struct.unpack_from("<H", data, 44)[0]
        ph_format = "<IIIIIIII"
        min_phentsize = 32
        elf_class_name = "ELF32"
    else:
        return {"applicable": True, "compliant": False, "scope": "invalid", "machine": machine, "details": "Unsupported ELF class"}

    applicable = machine != EM_HEXAGON
    scope = "hexagon_dsp_companion" if machine == EM_HEXAGON else "android_abi"
    if phoff == 0 or phnum == 0 or phentsize < min_phentsize:
        return {
            "applicable": applicable,
            "compliant": not applicable,
            "scope": scope,
            "machine": machine,
            "elf_class": elf_class_name,
            "details": "Invalid program header table",
        }

    alignments = []
    for index in range(phnum):
        offset = phoff + index * phentsize
        if offset + min_phentsize > len(data):
            return {
                "applicable": applicable,
                "compliant": not applicable,
                "scope": scope,
                "machine": machine,
                "elf_class": elf_class_name,
                "details": "Truncated program header table",
            }
        fields = struct.unpack_from(ph_format, data, offset)
        if fields[0] != 1:
            continue
        if elf_class == 2:
            file_offset, virtual_address, alignment = fields[2], fields[3], fields[7]
        else:
            file_offset, virtual_address, alignment = fields[1], fields[2], fields[7]
        congruent = alignment in (0, 1) or (virtual_address - file_offset) % alignment == 0
        alignments.append({"p_align": alignment, "congruent": congruent})

    alignment_ok = bool(alignments) and all(
        segment["p_align"] >= REQUIRED_ALIGNMENT and segment["congruent"]
        for segment in alignments
    )
    compliant = alignment_ok if applicable else True
    if applicable:
        details = "All PT_LOAD segments meet 16 KB alignment" if alignment_ok else \
            "At least one PT_LOAD segment fails 16 KB alignment"
    else:
        details = "Qualcomm Hexagon ELF32 DSP companion; Android 16 KB PT_LOAD rule is not applicable"
    return {
        "applicable": applicable,
        "compliant": compliant,
        "scope": scope,
        "machine": machine,
        "elf_class": elf_class_name,
        "alignment_meets_16kb": alignment_ok,
        "pt_load_segments": alignments,
        "details": details,
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
            applicable = bool(audit.get("applicable", True))
            machine_matches = (not applicable) or (
                expected_machine is not None and audit.get("machine") == expected_machine
            )
            compliant = bool(audit.get("compliant", False)) and machine_matches
            results.append({
                "path": name,
                "abi": abi,
                "machine": audit.get("machine"),
                "elf_class": audit.get("elf_class"),
                "scope": audit.get("scope", "android_abi"),
                "is_16kb_applicable": applicable,
                "machine_matches_abi": machine_matches,
                "is_16kb_compliant": compliant,
                "alignment_meets_16kb": audit.get("alignment_meets_16kb", False),
                "pt_load_segments": audit.get("pt_load_segments", []),
                "details": audit["details"] if machine_matches else
                    f"ELF machine does not match Android ABI {abi}",
            })
            status = "COMPLIANT" if compliant else "NON_COMPLIANT"
            if not applicable:
                status = "EXEMPT-DSP"
            print(f"[{status}] {name}: {results[-1]['details']}")

    applicable = [item for item in results if item["is_16kb_applicable"]]
    companions = [item for item in results if not item["is_16kb_applicable"]]
    compliant_count = sum(item["is_16kb_compliant"] for item in applicable)
    report = {
        "title": "MCA APK 16 KB Page-Size Alignment Audit",
        "apk_path": str(apk.relative_to(REPO_ROOT)) if apk.is_relative_to(REPO_ROOT) else str(apk),
        "apk_sha256": sha256_file(apk),
        "target_page_size_bytes": REQUIRED_ALIGNMENT,
        "rule_scope": "Android-loadable ABI ELF64 libraries; Hexagon ELF32 DSP companions are reported separately and exempt",
        "total_binaries_scanned": len(results),
        "applicable_binaries": len(applicable),
        "compliant_binaries": compliant_count,
        "non_compliant_binaries": len(applicable) - compliant_count,
        "exempt_hexagon_dsp_binaries": len(companions),
        "binaries": results,
    }
    REPORT_PATH.write_bytes((json.dumps(report, indent=2, ensure_ascii=False) + "\n").encode("utf-8"))
    print(f"Report: {REPORT_PATH}")
    print(f"Summary: {compliant_count}/{len(applicable)} Android ABI libraries compliant; " + f"{len(companions)} Hexagon DSP companions exempt")
    return 0 if compliant_count == len(applicable) else 1


if __name__ == "__main__":
    sys.exit(main())
