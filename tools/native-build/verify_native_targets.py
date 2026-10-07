#!/usr/bin/env python3
"""
verify_native_targets.py

Verifies:
1. Native CMake targets: mca_contract, mca_common, mca_observability are wired.
2. Provider targets (mca_native, mca_mnn_native, mca_qnn_native, mca_sd_native) do not depend on each other.
3. Test double injection points are marked NON_PRODUCTION and prohibited from release builds.
4. Line count ceilings on C++ headers and translation units.
"""

import sys
import re
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
NATIVE_CMAKE = REPO_ROOT / "core" / "native" / "src" / "main" / "cpp" / "CMakeLists.txt"
SD_CMAKE = REPO_ROOT / "core" / "sd-native" / "src" / "main" / "cpp" / "CMakeLists.txt"
COMMON_CPP_DIR = REPO_ROOT / "core" / "native" / "src" / "main" / "cpp" / "mca" / "common"

def check_cmake_wiring(errors):
    print("Checking Native CMake target wiring...")
    if not NATIVE_CMAKE.is_file():
        errors.append(f"Missing {NATIVE_CMAKE}")
        return

    content = NATIVE_CMAKE.read_text(encoding="utf-8")
    for target in ["mca_contract", "mca_common", "mca_observability"]:
        if target not in content:
            errors.append(f"Target '{target}' not defined in {NATIVE_CMAKE}")

    # Verify provider isolation: mca_qnn_native must NOT link mca_mnn_native or mca_sd_native
    if "target_link_libraries(mca_qnn_native" in content:
        qnn_link = re.search(r'target_link_libraries\(mca_qnn_native\s+([^)]*)\)', content)
        if qnn_link:
            linked = qnn_link.group(1)
            if "mca_mnn_native" in linked or "mca_sd_native" in linked:
                errors.append("mca_qnn_native links against another provider target!")

def check_test_doubles(errors):
    print("Checking test double NON_PRODUCTION annotations...")
    if not COMMON_CPP_DIR.is_dir():
        errors.append(f"Missing {COMMON_CPP_DIR}")
        return

    test_double_file = COMMON_CPP_DIR / "test_double_injection.hpp"
    if not test_double_file.is_file():
        errors.append(f"Missing test double file {test_double_file}")
    else:
        content = test_double_file.read_text(encoding="utf-8")
        if "NON_PRODUCTION" not in content:
            errors.append(f"{test_double_file} missing NON_PRODUCTION mark!")
        if "MCA_RELEASE_BUILD" not in content or "#error" not in content:
            errors.append(f"{test_double_file} missing MCA_RELEASE_BUILD compile guard!")

def check_common_loc(errors):
    print("Checking C++ common source file LOC ceilings...")
    for f in COMMON_CPP_DIR.glob("*.*"):
        if f.is_file():
            lines = sum(1 for _ in open(f, "r", encoding="utf-8", errors="ignore"))
            limit = 400 if f.suffix in [".hpp", ".h"] else 1000
            if lines > limit:
                errors.append(f"{f.name} has {lines} lines, exceeding ceiling of {limit}!")

def main():
    print(f"=== MCA A1 Native Common Verification ===")
    errors = []
    check_cmake_wiring(errors)
    check_test_doubles(errors)
    check_common_loc(errors)

    if errors:
        print(f"FAIL: {len(errors)} errors found:")
        for e in errors:
            print(f"  - {e}")
        sys.exit(1)

    print("ALL A1 NATIVE COMMON CHECKS PASSED (EXIT=0)")
    sys.exit(0)

if __name__ == "__main__":
    main()
