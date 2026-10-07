#!/usr/bin/env python3
"""
check_architecture_rules.py

Static architecture enforcement gate for MCA S-grade refactor:
1. Domain Android import = 0: core:engine contract files must not import android.* or androidx.*
2. Feature boundary: feature modules must not directly depend on :core:native or :core:sd-native
3. C++ contract purity: mca/contract/*.hpp must not include jni.h or platform UI headers
4. File LOC limits: enforces hard ceilings on contract and catalog files
5. Local-only policy guard: verifies AGENTS.md and UniversalDeviceAdmissionPolicyTest.kt are excluded
6. Universal Device Admission: verifies no device allowlist/whitelist patterns are added
7. Line ending purity: checks that all contract files have 0 CRLF (\r\n) line endings
"""

import os
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

CONTRACT_KOTLIN_DIR = REPO_ROOT / "core" / "engine" / "src" / "main" / "java" / "com" / "muyuchat" / "core" / "engine" / "contract"
CONTRACT_CPP_DIR = REPO_ROOT / "core" / "native" / "src" / "main" / "cpp" / "mca" / "contract"
FEATURE_DIR = REPO_ROOT / "feature"

HARD_LOC_LIMITS = {
    "Kotlin domain contract": (CONTRACT_KOTLIN_DIR, 500),
    "C++ contract header": (CONTRACT_CPP_DIR, 400),
}

LOCAL_ONLY_PATHS = [
    "AGENTS.md",
    "app/src/test/java/com/muyuchat/mca/UniversalDeviceAdmissionPolicyTest.kt"
]

def check_domain_android_imports(errors):
    """Rule 1: Domain Android import = 0."""
    print("Checking Rule 1: Domain Android imports in core:engine contract...")
    if not CONTRACT_KOTLIN_DIR.is_dir():
        errors.append(f"Contract directory not found: {CONTRACT_KOTLIN_DIR}")
        return

    forbidden_pattern = re.compile(r'^\s*import\s+(android\.|androidx\.)')
    for root, _, files in os.walk(CONTRACT_KOTLIN_DIR):
        for f in files:
            if f.endswith(".kt"):
                path = Path(root) / f
                with open(path, "r", encoding="utf-8") as file_obj:
                    for line_no, line in enumerate(file_obj, start=1):
                        if forbidden_pattern.match(line):
                            errors.append(
                                f"Rule 1 Violation: {path.relative_to(REPO_ROOT)}:{line_no} "
                                f"imports Android package: '{line.strip()}'"
                            )

def check_feature_native_dependencies(errors):
    """Rule 2: feature modules must not directly depend on core:native or core:sd-native."""
    print("Checking Rule 2: Feature module native dependencies...")
    if not FEATURE_DIR.is_dir():
        return

    forbidden_deps = [':core:native', ':core:sd-native']
    for root, _, files in os.walk(FEATURE_DIR):
        for f in files:
            if f == "build.gradle.kts":
                path = Path(root) / f
                content = path.read_text(encoding="utf-8")
                for dep in forbidden_deps:
                    if f'project("{dep}")' in content or f"project('{dep}')" in content:
                        errors.append(
                            f"Rule 2 Violation: {path.relative_to(REPO_ROOT)} directly depends on '{dep}'"
                        )

def check_cpp_contract_purity(errors):
    """Rule 3: C++ contract headers must be pure C++17, no jni.h."""
    print("Checking Rule 3: C++ contract header purity...")
    if not CONTRACT_CPP_DIR.is_dir():
        errors.append(f"C++ contract directory not found: {CONTRACT_CPP_DIR}")
        return

    forbidden_headers = re.compile(r'#include\s*[<"](?:jni\.h|android/|windows\.h)[>"]')
    for root, _, files in os.walk(CONTRACT_CPP_DIR):
        for f in files:
            if f.endswith((".hpp", ".h")):
                path = Path(root) / f
                with open(path, "r", encoding="utf-8") as file_obj:
                    for line_no, line in enumerate(file_obj, start=1):
                        if forbidden_headers.search(line):
                            errors.append(
                                f"Rule 3 Violation: {path.relative_to(REPO_ROOT)}:{line_no} "
                                f"includes platform header: '{line.strip()}'"
                            )

def check_file_loc_limits(errors):
    """Rule 4: Enforces hard ceilings on contract and catalog files."""
    print("Checking Rule 4: File LOC limits...")
    for label, (directory, limit) in HARD_LOC_LIMITS.items():
        if not directory.is_dir():
            continue
        for root, _, files in os.walk(directory):
            for f in files:
                if f.endswith((".kt", ".hpp", ".h")):
                    path = Path(root) / f
                    with open(path, "r", encoding="utf-8") as file_obj:
                        count = sum(1 for _ in file_obj)
                    if count > limit:
                        errors.append(
                            f"Rule 4 Violation: {path.relative_to(REPO_ROOT)} has {count} lines, "
                            f"exceeding hard ceiling of {limit} for {label}"
                        )

def check_local_only_exclusions(errors):
    """Rule 5: Local-only policy files must be excluded in git."""
    print("Checking Rule 5: Local-only exclusions via git check-ignore...")
    for rel_path in LOCAL_ONLY_PATHS:
        full_path = REPO_ROOT / rel_path
        if not full_path.exists():
            continue
        res = subprocess.run(
            ["git", "check-ignore", "-q", rel_path],
            cwd=str(REPO_ROOT),
            capture_output=True
        )
        if res.returncode != 0:
            errors.append(
                f"Rule 5 Violation: Local-only file '{rel_path}' is NOT ignored by git! "
                "Ensure it is in .git/info/exclude."
            )

def check_no_device_allowlist(errors):
    """Rule 6: Universal device admission - no allowlist/whitelist keywords in contract."""
    print("Checking Rule 6: No device allowlist or whitelist...")
    forbidden_pattern = re.compile(r'(?i)\b(?:device_allowlist|chipset_whitelist|allowed_devices|supported_chipsets_only)\b')
    for check_dir in [CONTRACT_KOTLIN_DIR, CONTRACT_CPP_DIR]:
        if not check_dir.is_dir():
            continue
        for root, _, files in os.walk(check_dir):
            for f in files:
                path = Path(root) / f
                content = path.read_text(encoding="utf-8", errors="ignore")
                match = forbidden_pattern.search(content)
                if match:
                    errors.append(
                        f"Rule 6 Violation: Forbidden allowlist pattern '{match.group(0)}' "
                        f"found in {path.relative_to(REPO_ROOT)}"
                    )

def check_line_endings(errors):
    """Rule 7: Contract files must have pure LF endings, 0 CRLF."""
    print("Checking Rule 7: Pure LF line endings (0 CRLF)...")
    for check_dir in [CONTRACT_KOTLIN_DIR, CONTRACT_CPP_DIR]:
        if not check_dir.is_dir():
            continue
        for root, _, files in os.walk(check_dir):
            for f in files:
                path = Path(root) / f
                raw = path.read_bytes()
                if b"\r\n" in raw:
                    errors.append(
                        f"Rule 7 Violation: {path.relative_to(REPO_ROOT)} contains CRLF line endings"
                    )

def check_api_worker_no_ui_dependencies(errors):
    """Rule 8: Local API and Worker packages must not import MainViewModel or UI Screens."""
    print("Checking Rule 8: API & Worker UI decoupling...")
    check_dirs = [
        REPO_ROOT / "api" / "local" / "src" / "main",
        REPO_ROOT / "app" / "src" / "main" / "java" / "com" / "muyuchat" / "mca" / "worker"
    ]
    forbidden_pattern = re.compile(r'^\s*import\s+.*(?:MainViewModel|ui\.screen).*')
    for d in check_dirs:
        if not d.is_dir():
            continue
        for root, _, files in os.walk(d):
            for f in files:
                if f.endswith(".kt"):
                    path = Path(root) / f
                    with open(path, "r", encoding="utf-8") as file_obj:
                        for line_no, line in enumerate(file_obj, start=1):
                            if forbidden_pattern.match(line):
                                errors.append(
                                    f"Rule 8 Violation: {path.relative_to(REPO_ROOT)}:{line_no} "
                                    f"imports UI component: '{line.strip()}'"
                                )

def main():
    print(f"=== MCA Architecture Gate Verification ===")
    print(f"Working tree: {REPO_ROOT}\n")

    errors = []

    check_domain_android_imports(errors)
    check_feature_native_dependencies(errors)
    check_cpp_contract_purity(errors)
    check_file_loc_limits(errors)
    check_local_only_exclusions(errors)
    check_no_device_allowlist(errors)
    check_line_endings(errors)
    check_api_worker_no_ui_dependencies(errors)

    print("\n-------------------------------------------")
    if errors:
        print(f"FAILED: Found {len(errors)} architecture rule violations:")
        for err in errors:
            print(f"  [X] {err}")
        sys.exit(1)
    else:
        print("ALL ARCHITECTURE GATES PASSED (EXIT=0)")
        sys.exit(0)

if __name__ == "__main__":
    main()

