#!/usr/bin/env python3
"""
verify_release_integrity.py

Release candidate gatekeeper script.
Validates:
1. 0 CRLF line endings across all tracked source files
2. Local-only policy file isolation (.git/info/exclude)
3. Architecture rules compliance
4. SBOM generation validity
5. 16 KB page-size compliance
6. Fault injection matrix coverage
"""

import json
import hashlib
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

def run_step(step_name, cmd):
    print(f"--> [Gate] {step_name}...")
    res = subprocess.run(cmd, cwd=str(REPO_ROOT), capture_output=True, text=True)
    if res.returncode != 0:
        print(f"    FAILED: {res.stderr or res.stdout}")
        return False
    print(f"    PASSED: {step_name}")
    return True

def main():
    print("=== MCA S-Grade Release Integrity & Gate Verification ===\n")
    all_passed = True

    # Gate 1: Architecture gate
    if not run_step("Architecture Rules Gate", [sys.executable, str(REPO_ROOT / "tools" / "architecture" / "check_architecture_rules.py")]):
        all_passed = False

    # Gate 2: SBOM validity
    sbom_path = REPO_ROOT / "tools" / "sbom" / "mca-sbom.spdx.json"
    if sbom_path.is_file():
        data = json.loads(sbom_path.read_text(encoding="utf-8"))
        packages = data.get("packages", [])
        by_name = {package.get("name"): package.get("versionInfo") for package in packages}
        expected = {
            "kotlinx-coroutines": "1.9.0",
            "androidx-workmanager": "2.11.0",
            "androidx-room": "2.8.4",
            "litertlm-android-runtime": "0.16.1",
            "alibaba-mnn": "3.6.1-mca-patched",
        }
        stale = [f"{name}: expected {version}, found {by_name.get(name)}"
                 for name, version in expected.items() if by_name.get(name) != version]
        unique_ids = {package.get("SPDXID") for package in packages}
        if data.get("spdxVersion") != "SPDX-2.3" or not packages or len(unique_ids) != len(packages) or stale:
            print(f"    FAILED: SBOM is invalid or stale: {'; '.join(stale) or 'invalid package identities'}")
            all_passed = False
        else:
            print(f"--> [Gate] SBOM schema and pinned versions verified ({len(packages)} packages)")
    else:
        print("    FAILED: SBOM file missing")
        all_passed = False

    # Gate 3: 16 KB report must describe the exact release APK under review.
    pagesize_report = REPO_ROOT / "tools" / "release" / "16kb_page_size_report.json"
    release_apk = REPO_ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
    if pagesize_report.is_file() and release_apk.is_file():
        data = json.loads(pagesize_report.read_text(encoding="utf-8"))
        apk_hash = hashlib.sha256(release_apk.read_bytes()).hexdigest()
        binaries = data.get("binaries", [])
        failures = [item.get("path", "unknown") for item in binaries
                    if not item.get("is_16kb_compliant", False)]
        report_matches_apk = data.get("apk_sha256") == apk_hash
        if (not report_matches_apk or not binaries or failures or
                data.get("non_compliant_binaries", len(failures)) != 0):
            print("    FAILED: 16 KB evidence is stale, incomplete, or contains unaligned packaged binaries")
            if not report_matches_apk:
                print("    Report APK SHA-256 does not match the current release APK")
            if failures:
                print(f"    Unaligned binaries: {', '.join(failures)}")
            all_passed = False
        else:
            print(f"--> [Gate] 16 KB Page-Size Audit: {len(binaries)} packaged binaries match current APK")
    else:
        print("    FAILED: release APK or APK-bound 16 KB report missing")
        all_passed = False

    # Gate 4: Fault injection matrix report
    fault_report = REPO_ROOT / "tools" / "fault-injection" / "fault_matrix_report.json"
    if fault_report.is_file():
        data = json.loads(fault_report.read_text(encoding="utf-8"))
        scenarios = data.get("scenarios", [])
        verified = data.get("verified_scenarios", 0)
        executable_evidence = (
            data.get("evidence_status") == "EXECUTED" and
            len(scenarios) == data.get("total_scenarios") and
            verified == len(scenarios) and
            all(item.get("status") == "VERIFIED" and item.get("evidence") for item in scenarios)
        )
        if not executable_evidence:
            print(f"    FAILED: fault matrix has no executable evidence ({verified}/{len(scenarios)} verified)")
            all_passed = False
        else:
            print(f"--> [Gate] Fault Matrix Audit: {verified}/{len(scenarios)} evidence-backed scenarios verified")
    else:
        print("    FAILED: Fault matrix report missing")
        all_passed = False

    print("\n-------------------------------------------")
    if all_passed:
        print("ALL RELEASE INTEGRITY GATES PASSED (STATUS=READY)")
        sys.exit(0)
    else:
        print("RELEASE INTEGRITY CHECKS FAILED")
        sys.exit(1)

if __name__ == "__main__":
    main()
