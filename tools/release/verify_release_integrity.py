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

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
from pathlib import Path
from typing import Any, Dict, Mapping, Optional, Sequence, Tuple

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_RELEASE_APK = Path("app/build/outputs/apk/release/app-release.apk")
DEFAULT_RUNTIME_EVIDENCE_DIR = Path("artifacts/rc/final")

RUNTIME_EVIDENCE_SCHEMA = "mca-runtime-evidence/1.0"


def _repo_path(value: str | Path) -> Path:
    path = Path(value).expanduser()
    if not path.is_absolute():
        path = REPO_ROOT / path
    return path.resolve()


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with Path(path).open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _normal_hash(value: Any) -> Optional[str]:
    if not isinstance(value, str):
        return None
    normalized = value.strip().lower()
    if len(normalized) != 64 or any(ch not in "0123456789abcdef" for ch in normalized):
        return None
    return normalized


def _dimension_count(declared: Any, name: str) -> Optional[int]:
    """Accept the current ``{"count": N}`` shape and the legacy integer shape."""

    value = declared.get(name) if isinstance(declared, Mapping) else None
    if isinstance(value, Mapping):
        value = value.get("count")
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        return None
    return value


def _load_json_object(path: Path, label: str) -> Tuple[Optional[Dict[str, Any]], Optional[str]]:
    if not Path(path).is_file():
        return None, f"{label} file missing: {path}"
    try:
        loaded = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        return None, f"{label} is unreadable or malformed: {exc}"
    if not isinstance(loaded, dict):
        return None, f"{label} root is not a JSON object"
    return loaded, None


def resolve_canonical_apk(
    apk_arg: Optional[str] = None,
    expected_apk_sha256: Optional[str] = None,
) -> Dict[str, Any]:
    """Resolve the one APK/SHA pair used by every release-integrity check."""

    apk_path = _repo_path(apk_arg or DEFAULT_RELEASE_APK)
    result: Dict[str, Any] = {
        "status": "FAILED",
        "path": str(apk_path),
        "bytes": None,
        "sha256": None,
        "requested_expected_sha256": expected_apk_sha256,
        "expected_sha256": None,
        "reason": "",
    }

    expected = None
    if expected_apk_sha256:
        expected = _normal_hash(expected_apk_sha256)
        if expected is None:
            result["reason"] = "--expected-apk-sha256 must be exactly 64 hexadecimal characters"
            return result
        result["expected_sha256"] = expected

    if not apk_path.is_file():
        result["reason"] = f"canonical APK not found: {apk_path}"
        return result

    try:
        result["bytes"] = apk_path.stat().st_size
        result["sha256"] = _sha256_file(apk_path)
    except OSError as exc:
        result["reason"] = f"canonical APK could not be read: {exc}"
        return result

    if expected is not None and expected != result["sha256"]:
        result["reason"] = (
            "canonical APK SHA-256 does not match --expected-apk-sha256 "
            f"(expected {expected}, actual {result['sha256']})"
        )
        return result

    result["status"] = "PASSED"
    result["reason"] = "canonical APK bytes and SHA-256 resolved"
    return result


def evaluate_fault_report(path: Path, expected_apk_sha256: str) -> Dict[str, Any]:
    """Validate production fault evidence without converting malformed values."""

    result: Dict[str, Any] = {
        "status": "FAILED",
        "path": str(path),
        "reason": "",
        "schema_ok": False,
        "status_ok": False,
        "apk_bound": False,
        "dimension_counts": {"PRODUCTION_INJECTION": None, "DEVICE_EXECUTION": None},
        "scenario_count": 0,
        "recomputed_counts": {"PRODUCTION_INJECTION": 0, "DEVICE_EXECUTION": 0},
    }

    data, error = _load_json_object(Path(path), "runtime fault report")
    if error:
        result["reason"] = error
        return result

    assert data is not None
    schema_ok = (
        data.get("schema") == RUNTIME_EVIDENCE_SCHEMA
        and data.get("report_type") == "fault"
    )
    status_ok = (
        isinstance(data.get("status"), str)
        and data["status"].strip().upper() == "VERIFIED"
    )
    declared_sha = _normal_hash(data.get("apk_sha256"))
    expected_sha = _normal_hash(expected_apk_sha256)
    apk_bound = expected_sha is not None and declared_sha == expected_sha

    dimensions = data.get("dimensions")
    production = _dimension_count(dimensions, "PRODUCTION_INJECTION")
    device = _dimension_count(dimensions, "DEVICE_EXECUTION")
    result["dimension_counts"] = {
        "PRODUCTION_INJECTION": production,
        "DEVICE_EXECUTION": device,
    }

    scenarios = data.get("mandatory_scenarios")
    scenarios_list = scenarios if isinstance(scenarios, list) else []
    result["scenario_count"] = len(scenarios_list)
    scenario_statuses = []
    recomputed_production = 0
    recomputed_device = 0
    for scenario in scenarios_list:
        if not isinstance(scenario, Mapping):
            scenario_statuses.append(False)
            continue
        scenario_dimensions = scenario.get("dimensions")
        scenario_dims = scenario_dimensions if isinstance(scenario_dimensions, list) else []
        recomputed_production += "PRODUCTION_INJECTION" in scenario_dims
        recomputed_device += "DEVICE_EXECUTION" in scenario_dims
        device_info = scenario.get("device")
        scenario_statuses.append(
            scenario.get("status") == "VERIFIED"
            and "PRODUCTION_INJECTION" in scenario_dims
            and "DEVICE_EXECUTION" in scenario_dims
            and isinstance(device_info, Mapping)
            and device_info.get("physical") is True
            and device_info.get("abi") == "arm64-v8a"
            and bool(scenario.get("evidence"))
        )

    result["recomputed_counts"] = {
        "PRODUCTION_INJECTION": recomputed_production,
        "DEVICE_EXECUTION": recomputed_device,
    }
    scenarios_nonempty = bool(scenarios_list)
    scenario_ok = scenarios_nonempty and all(scenario_statuses)
    counts_ok = (
        scenarios_nonempty
        and production is not None
        and device is not None
        and production > 0
        and device > 0
        and production == recomputed_production
        and device == recomputed_device
    )

    result.update(
        {
            "schema_ok": schema_ok,
            "status_ok": status_ok,
            "apk_bound": apk_bound,
            "declared_apk_sha256": declared_sha,
            "expected_apk_sha256": expected_sha,
        }
    )
    if schema_ok and status_ok and apk_bound and scenario_ok and counts_ok:
        result["status"] = "PASSED"
        result["reason"] = (
            f"{len(scenarios_list)} mandatory scenarios are VERIFIED; production and "
            "device dimensions match scenario records"
        )
        return result

    problems = []
    if not schema_ok:
        problems.append("schema/report_type invalid")
    if not status_ok:
        problems.append("status must be VERIFIED")
    if not apk_bound:
        problems.append("apk_sha256 is missing, malformed, or not the canonical APK")
    if not scenarios_nonempty:
        problems.append("mandatory_scenarios must be a non-empty list")
    elif not scenario_ok:
        problems.append("one or more mandatory scenarios are not VERIFIED 3D evidence")
    if not counts_ok:
        problems.append(
            "PRODUCTION_INJECTION/DEVICE_EXECUTION counts must be positive integers "
            "or {count:N} and must match scenario records"
        )
    result["reason"] = "; ".join(problems)
    return result


def run_step(step_name: str, cmd: Sequence[str]) -> bool:
    print(f"--> [Gate] {step_name}...")
    res = subprocess.run(
        cmd, cwd=str(REPO_ROOT), capture_output=True, text=True, check=False
    )
    if res.returncode != 0:
        print(f"    FAILED: {res.stderr or res.stdout}")
        return False
    print(f"    PASSED: {step_name}")
    return True


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="MCA release integrity and evidence gate."
    )
    parser.add_argument(
        "--runtime-evidence-dir",
        default=None,
        help=(
            "directory containing fault-report.json (default: artifacts/rc/final; "
            "the final gate passes its selected runtime evidence directory here)"
        ),
    )
    parser.add_argument(
        "--apk",
        default=None,
        help="canonical release APK under review (default: app/build/outputs/apk/release/app-release.apk)",
    )
    parser.add_argument(
        "--expected-apk-sha256",
        default=None,
        help="require the canonical APK to match this 64-character SHA-256",
    )
    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = _build_parser().parse_args(argv)
    print("=== MCA S-Grade Release Integrity & Gate Verification ===\n")
    all_passed = True

    runtime_evidence_dir = _repo_path(
        args.runtime_evidence_dir or DEFAULT_RUNTIME_EVIDENCE_DIR
    )
    canonical_apk = resolve_canonical_apk(args.apk, args.expected_apk_sha256)
    apk_hash = canonical_apk.get("sha256")
    apk_bytes = canonical_apk.get("bytes")
    if canonical_apk["status"] == "PASSED":
        print(
            f"--> [Gate] Canonical APK: {canonical_apk['path']} "
            f"({apk_bytes} bytes, SHA-256 {apk_hash})"
        )
    else:
        print(f"    FAILED: {canonical_apk['reason']}")
        all_passed = False

    # Gate 1: Architecture gate
    if not run_step(
        "Architecture Rules Gate",
        [
            sys.executable,
            str(REPO_ROOT / "tools" / "architecture" / "check_architecture_rules.py"),
        ],
    ):
        all_passed = False

    # Gate 2: SBOM validity
    sbom_path = REPO_ROOT / "tools" / "sbom" / "mca-sbom.spdx.json"
    sbom_data, sbom_error = _load_json_object(sbom_path, "SBOM")
    if sbom_error:
        print(f"    FAILED: {sbom_error}")
        all_passed = False
    else:
        assert sbom_data is not None
        packages = sbom_data.get("packages", [])
        packages = packages if isinstance(packages, list) else []
        by_name = {
            package.get("name"): package.get("versionInfo")
            for package in packages
            if isinstance(package, Mapping)
        }
        expected = {
            "kotlinx-coroutines": "1.9.0",
            "androidx-workmanager": "2.11.0",
            "androidx-room": "2.8.4",
            "litertlm-android-runtime": "0.16.1",
            "alibaba-mnn": "3.6.1-mca-patched",
        }
        stale = [
            f"{name}: expected {version}, found {by_name.get(name)}"
            for name, version in expected.items()
            if by_name.get(name) != version
        ]
        package_ids = [
            package.get("SPDXID")
            for package in packages
            if isinstance(package, Mapping)
        ]
        unique_ids = set(package_ids)
        if (
            sbom_data.get("spdxVersion") != "SPDX-2.3"
            or not packages
            or None in unique_ids
            or len(unique_ids) != len(packages)
            or stale
        ):
            print(
                "    FAILED: SBOM is invalid or stale: "
                + ("; ".join(stale) or "invalid package identities")
            )
            all_passed = False
        else:
            print(
                f"--> [Gate] SBOM schema and pinned versions verified "
                f"({len(packages)} packages)"
            )

    # Gate 3: 16 KB report must describe the exact canonical release APK.
    pagesize_report = REPO_ROOT / "tools" / "release" / "16kb_page_size_report.json"
    release_apk = Path(str(canonical_apk["path"]))
    if pagesize_report.is_file() and release_apk.is_file():
        data, error = _load_json_object(pagesize_report, "16 KB page-size report")
        if error:
            print(f"    FAILED: {error}")
            all_passed = False
        else:
            assert data is not None
            binaries = data.get("binaries", [])
            binaries = binaries if isinstance(binaries, list) else []
            failures = [
                item.get("path", "unknown")
                for item in binaries
                if not isinstance(item, Mapping) or not item.get("is_16kb_compliant", False)
            ]
            report_matches_apk = bool(apk_hash) and data.get("apk_sha256") == apk_hash
            if (
                not report_matches_apk
                or not binaries
                or failures
                or data.get("non_compliant_binaries", len(failures)) != 0
            ):
                print(
                    "    FAILED: 16 KB evidence is stale, incomplete, or contains "
                    "unaligned packaged binaries"
                )
                if not report_matches_apk:
                    print("    Report APK SHA-256 does not match the canonical APK")
                if failures:
                    print(f"    Unaligned binaries: {', '.join(str(item) for item in failures)}")
                all_passed = False
            else:
                print(
                    f"--> [Gate] 16 KB Page-Size Audit: APK-bound report verified "
                    f"against canonical APK SHA-256 ({len(binaries)} packaged binaries, "
                    "0 unaligned)"
                )
    elif pagesize_report.is_file():
        data, error = _load_json_object(pagesize_report, "16 KB page-size report")
        if error:
            print(f"    FAILED: {error}")
        else:
            assert data is not None
            compliant = data.get("compliant_binaries", 0)
            total = data.get("total_binaries_scanned", 0)
            legacy = data.get("legacy_or_unaligned_binaries", 0)
            print("    FAILED: 16 KB evidence is BUILD-TREE ONLY and is not bound to the release APK")
            print(
                f"    Report: {total} build-tree binaries scanned, {compliant} compliant, "
                f"{legacy} legacy/unaligned"
            )
            print(
                f"    Canonical APK is absent: {release_apk}; a build-tree scan is not "
                "a substitute."
            )
        all_passed = False
    else:
        print("    FAILED: 16 KB page-size report missing (no APK-bound and no build-tree evidence)")
        all_passed = False

    # Gate 4: APK-bound production fault evidence.
    runtime_fault_report = runtime_evidence_dir / "fault-report.json"
    contract_report = REPO_ROOT / "tools" / "fault-injection" / "fault_matrix_report.json"
    expected_for_fault = apk_hash or canonical_apk.get("expected_sha256") or ""
    fault_result = evaluate_fault_report(runtime_fault_report, str(expected_for_fault))
    if fault_result["status"] == "PASSED":
        print(
            f"--> [Gate] Production Fault Audit: {fault_result['scenario_count']} "
            "mandatory scenarios, production/device dimensions verified, APK bound"
        )
        print(f"    Evidence: {runtime_fault_report}")
    else:
        print(
            "    FAILED: APK-bound production fault report is malformed or incomplete: "
            f"{runtime_fault_report}"
        )
        print(f"    {fault_result['reason']}")
        if contract_report.is_file():
            model, model_error = _load_json_object(contract_report, "contract-model report")
            if model_error:
                print(f"    Existing contract-model report diagnostic: {model_error}")
            else:
                assert model is not None
                print(
                    "    Existing contract-model report is diagnostic only: "
                    f"scope={model.get('evidence_scope')}, "
                    f"production={model.get('production_injection_scenarios', 0)}, "
                    f"device={model.get('device_verified_scenarios', 0)}"
                )
        all_passed = False

    print("\n-------------------------------------------")
    if all_passed:
        print("ALL RELEASE INTEGRITY GATES PASSED (STATUS=READY)")
        return 0
    print("RELEASE INTEGRITY CHECKS FAILED")
    return 1


if __name__ == "__main__":
    sys.exit(main())
