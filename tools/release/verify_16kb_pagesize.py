#!/usr/bin/env python3
"""
verify_16kb_pagesize.py

Validates 16 KB page-size alignment for ARM64 ELF dynamic shared objects (.so).
Android 15 (API 35+) requires all native binaries to align PT_LOAD segments to
at least 16 KB (0x4000 = 16,384 bytes).

Directly parses 64-bit ELF program headers using standard library struct,
guaranteeing platform-independent execution on Windows, Linux, and macOS.
"""

import json
import os
import struct
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
REPORT_PATH = REPO_ROOT / "tools" / "release" / "16kb_page_size_report.json"

REQUIRED_ALIGNMENT = 0x4000  # 16 KB = 16384 bytes

def check_elf_alignment(file_path: Path):
    """
    Parses ELF64 header and Program Header table.
    Returns (is_elf64, max_align, is_compliant, details)
    """
    try:
        with open(file_path, "rb") as f:
            magic = f.read(4)
            if magic != b"\x7fELF":
                return False, 0, False, "Not an ELF file"

            ei_class = f.read(1)[0]
            if ei_class != 2:  # 2 = ELFCLASS64
                return False, 0, False, f"Not 64-bit ELF (class={ei_class})"

            # Seek to e_phoff (offset 32 in ELF64 header)
            f.seek(32)
            e_phoff = struct.unpack("<Q", f.read(8))[0]

            # Seek to e_phentsize (offset 54) and e_phnum (offset 56)
            f.seek(54)
            e_phentsize = struct.unpack("<H", f.read(2))[0]
            e_phnum = struct.unpack("<H", f.read(2))[0]

            if e_phnum == 0 or e_phoff == 0:
                return True, 0, False, "No program headers found"

            max_align = 0
            pt_loads = []

            for i in range(e_phnum):
                f.seek(e_phoff + i * e_phentsize)
                p_type = struct.unpack("<I", f.read(4))[0]
                if p_type == 1:  # PT_LOAD
                    # In ELF64, p_align is at offset 48 from start of program header
                    f.seek(e_phoff + i * e_phentsize + 48)
                    p_align = struct.unpack("<Q", f.read(8))[0]
                    pt_loads.append(p_align)
                    if p_align > max_align:
                        max_align = p_align

            if not pt_loads:
                return True, 0, False, "No PT_LOAD segments found"

            is_compliant = max_align >= REQUIRED_ALIGNMENT
            return True, max_align, is_compliant, f"{len(pt_loads)} PT_LOAD segments found (max_align=0x{max_align:x})"
    except Exception as e:
        return False, 0, False, f"Read error: {e}"

def main():
    print("=== MCA 16 KB Page-Size Native Binary Alignment Verification ===")
    print(f"Required PT_LOAD alignment: >= 0x{REQUIRED_ALIGNMENT:x} ({REQUIRED_ALIGNMENT} bytes)\n")

    search_dirs = [
        REPO_ROOT / "vendor",
        REPO_ROOT / "app" / "src" / "main" / "jniLibs",
        REPO_ROOT / "core" / "native" / "build",
        REPO_ROOT / "core" / "sd-native" / "build",
    ]

    so_files = []
    for d in search_dirs:
        if d.is_dir():
            for root, _, files in os.walk(d):
                for f in files:
                    if f.endswith(".so"):
                        so_files.append(Path(root) / f)

    results = []
    compliant_count = 0
    non_compliant_count = 0

    print(f"Discovered {len(so_files)} native shared objects (.so):")
    for so_path in sorted(so_files):
        rel_path = so_path.relative_to(REPO_ROOT)
        is_elf64, max_align, is_compliant, details = check_elf_alignment(so_path)
        status_str = "COMPLIANT" if is_compliant else "LEGACY_4KB" if max_align == 0x1000 else "NON_COMPLIANT"

        print(f"  [{status_str:14}] {rel_path} -> align=0x{max_align:x} ({details})")

        if is_compliant:
            compliant_count += 1
        else:
            non_compliant_count += 1

        results.append({
            "path": str(rel_path).replace("\\", "/"),
            "is_elf64": is_elf64,
            "max_align": max_align,
            "is_16kb_compliant": is_compliant,
            "status": status_str,
            "details": details
        })

    report = {
        "title": "MCA 16 KB Page-Size Alignment Audit Report",
        "target_page_size_bytes": REQUIRED_ALIGNMENT,
        "total_binaries_scanned": len(so_files),
        "compliant_binaries": compliant_count,
        "legacy_or_unaligned_binaries": non_compliant_count,
        "binaries": results
    }

    REPORT_PATH.parent.mkdir(parents=True, exist_ok=True)
    REPORT_PATH.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"\nAudit report saved to: {REPORT_PATH}")
    print(f"Summary: {compliant_count} compliant, {non_compliant_count} legacy (advisory tracked for production packaging).")

if __name__ == "__main__":
    main()
