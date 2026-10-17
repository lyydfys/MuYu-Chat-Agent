#!/usr/bin/env python3
"""
verify_s_grade_final_gate.py

Final integration gate for MCA release-candidate evidence.

This gate is intentionally evidence-driven. It does not infer an S rating from
summary booleans or model-only fixtures. In particular:

* Runtime soak/fault/device evidence is accepted only through
  verify_runtime_evidence.py, then checked again against final-gate-only
  terminal-status, two-device, canonical-APK, and reproducibility contracts.
* The local universal-device-admission policy is accepted only from a passing
  JUnit XML report.
* The contract fault model may be reported as an annotation, but it can never
  discharge the production runtime-evidence gate.
* Line-count budgets are advisory/structural signals and cannot establish a
  functional S rating by themselves.
"""

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any, Callable, Dict, List, Mapping, Optional, Sequence, Tuple

REPO_ROOT = Path(__file__).resolve().parents[2]
FINAL_REPORT_PATH = REPO_ROOT / "architecture-final-report.json"

PASSED = "PASSED"
FAILED = "FAILED"
UNVERIFIED = "UNVERIFIED"
NOT_AVAILABLE = "NOT_AVAILABLE"

RELEASE_APK_RELATIVE = Path("app/build/outputs/apk/release/app-release.apk")
RUNTIME_VALIDATOR_RELATIVE = Path("tools/final-gate/verify_runtime_evidence.py")
DEFAULT_RUNTIME_EVIDENCE_DIR = Path("artifacts/rc/final")
RUNTIME_EVIDENCE_DIR_ENV = "MCA_RUNTIME_EVIDENCE_DIR"
RUNTIME_EVIDENCE_DIR_ENV_ALIAS = "MCA_FINAL_GATE_RUNTIME_EVIDENCE_DIR"

POLICY_TEST_FILENAME = "TEST-com.muyuchat.mca.UniversalDeviceAdmissionPolicyTest.xml"
POLICY_TEST_PRIMARY_RELATIVE = (
    Path("app/build/test-results/testDebugUnitTest") / POLICY_TEST_FILENAME
)
POLICY_TEST_RELEASE_RELATIVE = (
    Path("app/build/test-results/testReleaseUnitTest") / POLICY_TEST_FILENAME
)
POLICY_TEST_ARCHITECTURE_GLOBS = (
    f"artifacts/architecture/*/full-app-rerun/xml/{POLICY_TEST_FILENAME}",
    f"artifacts/architecture/*/full-app-rerun/xml/app/{POLICY_TEST_FILENAME}",
)

MIN_SOAK_HOURS = 24.0
MIN_REQUESTS = 1000
MIN_PHYSICAL_DEVICES = 2
VALID_SOAK_STATUSES = {"PASSED", "FORMAL_RC"}
BRIDGE_LINE_LIMIT = 1500
VIEWMODEL_LINE_LIMIT = 600

CONTRACT_MODEL_REPORT_RELATIVE = Path("tools/fault-injection/fault_matrix_report.json")
REPRODUCIBILITY_REPORTS = (
    "reproducible-build-report.json",
    "reproducibility-report.json",
)
PROVENANCE_REPORTS = (
    "build-provenance.json",
    "release-provenance.json",
    "provenance.json",
)
REPRODUCIBILITY_REPORT_ENV = "MCA_REPRODUCIBILITY_REPORT"
PROVENANCE_REPORT_ENV = "MCA_PROVENANCE_REPORT"
RELEASE_INTEGRITY_RELATIVE = Path("tools/release/verify_release_integrity.py")
RUNTIME_EVIDENCE_SCHEMA = "mca-runtime-evidence/1.0"


def _repo_path(repo_root: Path, value: str | Path) -> Path:
    path = Path(value).expanduser()
    if not path.is_absolute():
        path = repo_root / path
    return path


def resolve_runtime_evidence_dir(
    explicit: Optional[str] = None,
    *,
    repo_root: Path = REPO_ROOT,
    environ: Optional[Mapping[str, str]] = None,
) -> Path:
    """Resolve CLI > environment > default runtime-evidence directory."""

    env = os.environ if environ is None else environ
    selected = explicit
    if not selected:
        selected = env.get(RUNTIME_EVIDENCE_DIR_ENV) or env.get(
            RUNTIME_EVIDENCE_DIR_ENV_ALIAS
        )
    if not selected:
        selected = str(DEFAULT_RUNTIME_EVIDENCE_DIR)
    return _repo_path(Path(repo_root), selected).resolve()


def discover_runtime_evidence(evidence_dir: Path) -> Dict[str, Any]:
    """Discover the exact APK-bound evidence files consumed by the validator."""

    evidence_dir = Path(evidence_dir)
    soak = evidence_dir / "soak-report.json"
    fault = evidence_dir / "fault-report.json"
    devices = sorted(
        (path for path in evidence_dir.glob("device-*.json") if path.is_file()),
        key=lambda path: path.name,
    )

    missing: List[str] = []
    if not soak.is_file():
        missing.append("soak-report.json")
    if not fault.is_file():
        missing.append("fault-report.json")
    if not devices:
        missing.append("device-*.json")

    return {
        "evidence_dir": str(evidence_dir),
        "expected_soak_report": str(soak),
        "expected_fault_report": str(fault),
        "soak_report": str(soak) if soak.is_file() else None,
        "fault_report": str(fault) if fault.is_file() else None,
        "device_reports": [str(path) for path in devices],
        "missing": missing,
    }


def _output_excerpt(text: str, max_lines: int = 12) -> str:
    lines = [line.rstrip() for line in (text or "").splitlines() if line.strip()]
    if not lines:
        return "(no output)"
    return "\n".join(lines[-max_lines:])


def run_command(name: str, cmd: Sequence[str]) -> Dict[str, Any]:
    print(f"--> [Final Gate] Running {name}...")
    try:
        completed = subprocess.run(
            list(cmd),
            cwd=str(REPO_ROOT),
            capture_output=True,
            text=True,
            check=False,
        )
    except OSError as exc:
        reason = f"{name} could not be started: {exc}"
        print(f"    FAILED: {reason}")
        return {
            "passed": False,
            "exit_code": None,
            "stdout": "",
            "stderr": str(exc),
            "reason": reason,
        }

    stdout = completed.stdout or ""
    stderr = completed.stderr or ""
    passed = completed.returncode == 0
    if passed:
        print(f"    PASSED: {name}")
        reason = f"{name} exited 0"
    else:
        detail = _output_excerpt(stderr or stdout)
        print(f"    FAILED: {detail}")
        reason = f"{name} exited {completed.returncode}: {detail}"
    return {
        "passed": passed,
        "exit_code": completed.returncode,
        "stdout": stdout,
        "stderr": stderr,
        "reason": reason,
    }


def get_jdk_version() -> Tuple[int, str, str]:
    java_home = os.environ.get("JAVA_HOME")
    if not java_home:
        fallback = Path("E:/model/toolchains/jdk-21/jdk-21.0.12.1+1")
        if fallback.exists():
            java_home = str(fallback)
    if not java_home:
        print("ERROR: JAVA_HOME is not set and fallback JDK-21 path not found.")
        raise SystemExit(1)

    java_exe = Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")
    if not java_exe.exists():
        print(f"ERROR: JAVA_HOME does not contain java binary: {java_exe}")
        raise SystemExit(1)

    completed = subprocess.run([str(java_exe), "-version"], capture_output=True, text=True)
    output = completed.stderr or completed.stdout
    match = re.search(r'version "(\d+)', output)
    if not match:
        print(f"ERROR: Unable to parse Java version from:\n{output}")
        raise SystemExit(1)

    major = int(match.group(1))
    if major < 21:
        fallback = Path("E:/model/toolchains/jdk-21/jdk-21.0.12.1+1")
        if fallback.exists() and str(fallback) != java_home:
            java_home = str(fallback)
            java_exe = Path(java_home) / "bin" / (
                "java.exe" if os.name == "nt" else "java"
            )
            completed = subprocess.run(
                [str(java_exe), "-version"], capture_output=True, text=True
            )
            output = completed.stderr or completed.stdout
            match = re.search(r'version "(\d+)', output)
            if match and int(match.group(1)) >= 21:
                major = int(match.group(1))
                raw_version_line = output.splitlines()[0] if output.splitlines() else f"JDK {major}"
                return major, raw_version_line.strip(), str(java_exe)

        print(
            f"ERROR: JAVA_HOME is JDK {major}; MCA requires JDK 21+ "
            "(LiteRT-LM SDK classes are class-file 65.0)."
        )
        print("Hard fail to avoid false-red test results on JDK 17.")
        raise SystemExit(1)

    raw_version_line = output.splitlines()[0] if output.splitlines() else f"JDK {major}"
    return major, raw_version_line.strip(), str(java_exe)


def count_lines(path: Path) -> int:
    if not path.exists():
        return 0
    with open(path, "r", encoding="utf-8", errors="ignore") as handle:
        return sum(1 for _ in handle)


def _summary_reason(summary: Optional[Mapping[str, Any]], fallback: str) -> str:
    if not isinstance(summary, Mapping):
        return fallback
    pieces: List[str] = []
    reasons = summary.get("reasons")
    if isinstance(reasons, list) and reasons:
        pieces.append("; ".join(str(item) for item in reasons))
    failed = summary.get("failed_checks")
    if isinstance(failed, list) and failed:
        pieces.append("failed_checks=" + ",".join(str(item) for item in failed[:12]))
    unverified = summary.get("unverified_checks")
    if isinstance(unverified, list) and unverified:
        pieces.append("unverified_checks=" + ",".join(str(item) for item in unverified[:12]))
    return "; ".join(pieces) if pieces else fallback


def verify_runtime_evidence(
    repo_root: Path,
    evidence_dir: Path,
    *,
    release_apk: Optional[Path] = None,
    validator_path: Optional[Path] = None,
    runner: Optional[Callable[..., Any]] = None,
    expected_apk_sha256: Optional[str] = None,
    extra_env: Optional[Mapping[str, str]] = None,
) -> Dict[str, Any]:
    """Run the APK-bound runtime validator and preserve its raw summary.

    Missing release APK/evidence is UNVERIFIED without being converted into a
    validator FAIL. Once the complete evidence set is present, validator exit
    code 0 is mandatory for PASS, 1 maps to FAIL, and 2 maps to UNVERIFIED.
    """

    repo_root = Path(repo_root)
    evidence_dir = Path(evidence_dir)
    release_apk = release_apk or (repo_root / RELEASE_APK_RELATIVE)
    validator_path = validator_path or (repo_root / RUNTIME_VALIDATOR_RELATIVE)
    discovery = discover_runtime_evidence(evidence_dir)

    result: Dict[str, Any] = {
        "status": UNVERIFIED,
        "reason": "",
        "evidence": discovery,
        "release_apk": str(release_apk),
        "validator_path": str(validator_path),
        "command": None,
        "validator_exit_code": None,
        "validator_stderr": "",
        "validator_output": None,
        "raw_summary": None,
        "expected_apk_sha256": None,
        "process_environment": {},
    }

    expected_sha = None
    if expected_apk_sha256:
        expected_sha = _normal_hash(expected_apk_sha256)
        if expected_sha is None:
            result["status"] = FAILED
            result["reason"] = (
                "expected APK SHA-256 must be exactly 64 hexadecimal characters"
            )
            return result
        result["expected_apk_sha256"] = expected_sha

    if not Path(release_apk).is_file():
        result["reason"] = f"release APK not found: {release_apk}"
        return result

    if discovery["missing"]:
        result["reason"] = "required runtime evidence is missing: " + ", ".join(
            discovery["missing"]
        )
        return result

    if not Path(validator_path).is_file():
        result["status"] = FAILED
        result["reason"] = f"runtime evidence validator not found: {validator_path}"
        return result

    command = [
        sys.executable,
        "-B",
        str(validator_path),
        "--apk",
        str(release_apk),
        "--soak-report",
        str(discovery["soak_report"]),
        "--fault-report",
        str(discovery["fault_report"]),
    ]
    if expected_sha is not None:
        command.extend(["--expected-apk-sha256", expected_sha])
    for device_report in discovery["device_reports"]:
        command.extend(["--device-report", device_report])
    command.extend(
        [
            "--min-soak-hours",
            str(int(MIN_SOAK_HOURS)) if MIN_SOAK_HOURS.is_integer() else str(MIN_SOAK_HOURS),
            "--min-requests",
            str(MIN_REQUESTS),
            "--min-devices",
            str(MIN_PHYSICAL_DEVICES),
        ]
    )
    result["command"] = command
    child_env = os.environ.copy()
    if extra_env:
        child_env.update({str(key): str(value) for key, value in extra_env.items()})
    result["process_environment"] = {
        key: child_env[key]
        for key in ("APKSIGNER", "ANDROID_HOME", "ANDROID_SDK_ROOT")
        if child_env.get(key)
    }

    print("--> [Final Gate] Running APK-bound Runtime Evidence Validator...")
    try:
        completed = (runner or subprocess.run)(
            command,
            cwd=str(repo_root),
            capture_output=True,
            text=True,
            check=False,
            env=child_env,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        result["status"] = FAILED
        result["reason"] = f"runtime evidence validator could not run: {exc}"
        print(f"    FAILED: {result['reason']}")
        return result

    exit_code = int(completed.returncode)
    stdout = completed.stdout or ""
    stderr = completed.stderr or ""
    result["validator_exit_code"] = exit_code
    result["validator_stderr"] = stderr

    parsed: Optional[Dict[str, Any]] = None
    try:
        loaded = json.loads(stdout)
        if isinstance(loaded, dict):
            parsed = loaded
    except json.JSONDecodeError:
        parsed = None

    if parsed is not None:
        result["validator_output"] = parsed
        raw_summary = parsed.get("summary")
        result["raw_summary"] = raw_summary if isinstance(raw_summary, dict) else None

    summary = result["raw_summary"]
    if parsed is None:
        detail = _output_excerpt(stderr or stdout)
        if exit_code == 2:
            result["status"] = UNVERIFIED
            result["reason"] = f"validator exited 2 without parseable JSON: {detail}"
        else:
            result["status"] = FAILED
            result["reason"] = (
                f"validator output was missing or invalid JSON (exit {exit_code}): {detail}"
            )
    elif exit_code == 1:
        result["status"] = FAILED
        result["reason"] = _summary_reason(summary, "validator reported FAIL")
    elif exit_code == 2:
        result["status"] = UNVERIFIED
        result["reason"] = _summary_reason(summary, "validator reported UNVERIFIED")
    elif exit_code == 0:
        summary_status = summary.get("status") if isinstance(summary, Mapping) else None
        summary_exit = summary.get("exit_code") if isinstance(summary, Mapping) else None
        if summary_status == "PASS" and summary_exit == 0:
            result["status"] = PASSED
            result["reason"] = _summary_reason(summary, "validator verified all runtime evidence")
        else:
            result["status"] = FAILED
            result["reason"] = (
                "validator exited 0 but its summary did not report PASS/exit_code=0"
            )
    else:
        result["status"] = FAILED
        result["reason"] = _summary_reason(
            summary, f"validator returned unexpected exit code {exit_code}"
        )

    print(f"    Runtime validator exit={exit_code}; gate={result['status']}")
    print(f"    {result['reason']}")
    return result


def policy_test_candidates(repo_root: Path) -> List[Path]:
    repo_root = Path(repo_root)
    candidates = [
        repo_root / POLICY_TEST_PRIMARY_RELATIVE,
        repo_root / POLICY_TEST_RELEASE_RELATIVE,
    ]
    fallback = []
    for pattern in POLICY_TEST_ARCHITECTURE_GLOBS:
        fallback.extend(repo_root.glob(pattern))
    fallback.sort(key=lambda path: path.stat().st_mtime if path.is_file() else 0, reverse=True)
    candidates.extend(fallback)

    deduped: List[Path] = []
    seen = set()
    for path in candidates:
        key = str(path.resolve())
        if key not in seen:
            seen.add(key)
            deduped.append(path)
    return deduped


def select_policy_test_xml(
    repo_root: Path, explicit: Optional[Path] = None
) -> Tuple[Optional[Path], List[Path]]:
    if explicit is not None:
        return Path(explicit), [Path(explicit)]
    candidates = policy_test_candidates(repo_root)
    for path in candidates:
        if path.is_file():
            return path, candidates
    return None, candidates


def _junit_int(element: ET.Element, attribute: str) -> Optional[int]:
    raw = element.get(attribute)
    if raw is None:
        return None
    try:
        value = int(raw)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"invalid {attribute} attribute: {raw!r}") from exc
    if value < 0:
        raise ValueError(f"negative {attribute} attribute: {raw!r}")
    return value


def parse_junit_xml(path: Optional[Path]) -> Dict[str, Any]:
    """Parse and fail closed on the local policy test's JUnit XML."""

    result: Dict[str, Any] = {
        "path": str(path) if path is not None else None,
        "status": UNVERIFIED,
        "reason": "",
        "tests": 0,
        "failures": 0,
        "errors": 0,
        "skipped": 0,
        "testcases_observed": 0,
        "time_seconds": 0.0,
    }

    if path is None or not Path(path).is_file():
        result["reason"] = "policy test JUnit XML is missing"
        return result

    try:
        root = ET.parse(str(path)).getroot()
    except (ET.ParseError, OSError) as exc:
        result["status"] = FAILED
        result["reason"] = f"policy test JUnit XML is unreadable or malformed: {exc}"
        return result

    if root.tag == "testsuite":
        suites = [root]
    else:
        suites = [element for element in root.iter() if element.tag == "testsuite"]
    if not suites:
        result["status"] = FAILED
        result["reason"] = "policy test JUnit XML contains no testsuite"
        return result

    tests = failures = errors = skipped = observed = 0
    time_seconds = 0.0
    inconsistent: List[str] = []
    try:
        for suite in suites:
            testcases = suite.findall("testcase")
            observed_count = len(testcases)

            declared_tests = _junit_int(suite, "tests")
            suite_tests = declared_tests if declared_tests is not None else observed_count

            nested_failures = sum(
                1 for testcase in testcases if testcase.find("failure") is not None
            )
            nested_errors = sum(
                1 for testcase in testcases if testcase.find("error") is not None
            )
            nested_skipped = sum(
                1 for testcase in testcases if testcase.find("skipped") is not None
            )
            declared_failures = _junit_int(suite, "failures")
            declared_errors = _junit_int(suite, "errors")
            declared_skipped = _junit_int(suite, "skipped")
            suite_failures = (
                declared_failures if declared_failures is not None else nested_failures
            )
            suite_errors = declared_errors if declared_errors is not None else nested_errors
            suite_skipped = (
                declared_skipped if declared_skipped is not None else nested_skipped
            )

            if declared_failures is not None and nested_failures > declared_failures:
                inconsistent.append(
                    f"{nested_failures} failure elements exceed attribute failures={declared_failures}"
                )
            if declared_errors is not None and nested_errors > declared_errors:
                inconsistent.append(
                    f"{nested_errors} error elements exceed attribute errors={declared_errors}"
                )
            if declared_skipped is not None and nested_skipped > declared_skipped:
                inconsistent.append(
                    f"{nested_skipped} skipped elements exceed attribute skipped={declared_skipped}"
                )

            tests += suite_tests
            observed += observed_count
            failures += suite_failures
            errors += suite_errors
            skipped += suite_skipped
            try:
                time_seconds += float(suite.get("time", "0") or 0)
            except ValueError:
                pass
    except ValueError as exc:
        result["status"] = FAILED
        result["reason"] = f"policy test JUnit XML has invalid counters: {exc}"
        return result

    result.update(
        {
            "tests": tests,
            "failures": failures,
            "errors": errors,
            "skipped": skipped,
            "testcases_observed": observed,
            "time_seconds": round(time_seconds, 3),
        }
    )

    if tests <= 0:
        result["status"] = UNVERIFIED
        result["reason"] = "policy test XML reports zero tests"
    elif inconsistent:
        result["status"] = UNVERIFIED
        result["reason"] = "policy test JUnit counters are inconsistent: " + "; ".join(
            inconsistent
        )
    elif failures > 0 or errors > 0:
        result["status"] = FAILED
        result["reason"] = (
            f"policy test XML reports failures={failures}, errors={errors}"
        )
    elif skipped > 0:
        result["status"] = UNVERIFIED
        result["reason"] = f"policy test XML reports skipped={skipped}; skip is not a pass"
    else:
        result["status"] = PASSED
        result["reason"] = (
            f"policy test XML proves {tests} tests with 0 failures, 0 errors, 0 skipped"
        )
    return result


def collect_contract_model_evidence(repo_root: Path) -> Dict[str, Any]:
    """Load legacy/model output as annotation only; never invoke it as a gate."""

    report_path = Path(repo_root) / CONTRACT_MODEL_REPORT_RELATIVE
    result: Dict[str, Any] = {
        "status": NOT_AVAILABLE,
        "path": str(report_path),
        "invoked": False,
        "gate_impact": "NONE",
        "counts_toward_production_verified": False,
        "production_verified": False,
        "reason": "contract-model report is optional annotation and is never executed by this gate",
    }
    if not report_path.is_file():
        return result

    try:
        data = json.loads(report_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        result["status"] = "INVALID_ANNOTATION"
        result["reason"] = f"contract-model annotation is unreadable: {exc}"
        return result

    if not isinstance(data, Mapping):
        result["status"] = "INVALID_ANNOTATION"
        result["reason"] = "contract-model annotation root is not a JSON object"
        return result

    result["status"] = "ANNOTATION_ONLY"
    result["report_summary"] = {
        "evidence_scope": data.get("evidence_scope"),
        "evidence_status": data.get("evidence_status"),
        "total_scenarios": data.get("total_scenarios"),
        "contract_model_scenarios": data.get("contract_model_scenarios"),
        "production_injection_scenarios": data.get("production_injection_scenarios"),
        "device_verified_scenarios": data.get("device_verified_scenarios"),
        "verified_scenarios": data.get("verified_scenarios"),
    }
    result["reason"] = (
        "model/contract evidence retained for review only; production fault evidence "
        "must come from runtime_evidence_verification"
    )
    return result


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with Path(path).open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _normalized_status(value: Any) -> Optional[str]:
    if not isinstance(value, str):
        return None
    normalized = value.strip().upper().replace("-", "_").replace(" ", "_")
    if normalized in {"PASS", "PASSED", "VERIFIED", "READY", "OK", "SUCCESS"}:
        return PASSED
    if normalized in {"FAIL", "FAILED", "ERROR", "REJECTED", "NOT_READY"}:
        return FAILED
    if normalized in {"UNVERIFIED", "UNKNOWN", "INCONCLUSIVE", "PENDING", "SKIPPED"}:
        return UNVERIFIED
    return None


def _artifact_report_candidates(
    repo_root: Path,
    evidence_dir: Path,
    filenames: Sequence[str],
    *,
    environ: Optional[Mapping[str, str]] = None,
    env_name: Optional[str] = None,
) -> List[Path]:
    env = os.environ if environ is None else environ
    candidates: List[Path] = []
    if env_name and env.get(env_name):
        candidates.append(_repo_path(Path(repo_root), env[env_name]))

    search_dirs = [
        Path(evidence_dir),
        Path(repo_root) / "artifacts/rc/final",
        Path(repo_root) / "artifacts/rc",
        Path(repo_root) / "build",
    ]
    for directory in search_dirs:
        for filename in filenames:
            candidates.append(Path(directory) / filename)

    deduped: List[Path] = []
    seen = set()
    for path in candidates:
        key = str(path.resolve())
        if key not in seen:
            seen.add(key)
            deduped.append(path)
    return deduped


def _first_existing(candidates: Sequence[Path]) -> Optional[Path]:
    for path in candidates:
        if path.is_file():
            return path
    return None


def _normal_hash(value: Any) -> Optional[str]:
    if not isinstance(value, str):
        return None
    normalized = value.strip().lower()
    if len(normalized) != 64 or any(ch not in "0123456789abcdef" for ch in normalized):
        return None
    return normalized


def resolve_canonical_apk(
    repo_root: Path,
    apk: Optional[str] = None,
    expected_apk_sha256: Optional[str] = None,
) -> Dict[str, Any]:
    """Resolve one canonical APK/SHA pair shared by every final-gate check."""

    repo_root = Path(repo_root)
    apk_path = _repo_path(repo_root, apk or RELEASE_APK_RELATIVE).resolve()
    result: Dict[str, Any] = {
        "status": FAILED,
        "path": str(apk_path),
        "bytes": None,
        "sha256": None,
        "requested_expected_sha256": expected_apk_sha256,
        "expected_sha256": None,
        "reason": "",
    }

    expected: Optional[str] = None
    if expected_apk_sha256:
        expected = _normal_hash(expected_apk_sha256)
        if expected is None:
            result["reason"] = (
                "--expected-apk-sha256 must be exactly 64 hexadecimal characters"
            )
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

    result["status"] = PASSED
    result["reason"] = "canonical APK bytes and SHA-256 resolved"
    return result


def build_release_integrity_command(
    repo_root: Path,
    evidence_dir: Path,
    canonical_apk: Mapping[str, Any],
    expected_apk_sha256: Optional[str] = None,
) -> List[str]:
    command = [
        sys.executable,
        "-B",
        str(Path(repo_root) / RELEASE_INTEGRITY_RELATIVE),
        "--runtime-evidence-dir",
        str(Path(evidence_dir)),
        "--apk",
        str(canonical_apk.get("path") or (Path(repo_root) / RELEASE_APK_RELATIVE)),
    ]
    expected = (
        expected_apk_sha256
        or canonical_apk.get("expected_sha256")
        or canonical_apk.get("sha256")
    )
    if expected:
        command.extend(["--expected-apk-sha256", str(expected)])
    return command


def _sdk_roots_from_provenance(path: Optional[Path]) -> List[Path]:
    if path is None or not Path(path).is_file():
        return []
    try:
        data = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return []

    roots: List[Path] = []
    wanted = {
        "androidsdkroot",
        "android_sdk_root",
        "androidhome",
        "android_home",
        "sdkroot",
    }

    def visit(value: Any) -> None:
        if isinstance(value, Mapping):
            for key, child in value.items():
                if isinstance(key, str) and key.strip().lower() in wanted:
                    if isinstance(child, str) and child.strip():
                        roots.append(Path(child.strip()).expanduser())
                visit(child)
        elif isinstance(value, list):
            for child in value:
                visit(child)

    visit(data)
    return roots


def _local_properties_sdk_root(repo_root: Path) -> Optional[Path]:
    properties = Path(repo_root) / "local.properties"
    if not properties.is_file():
        return None
    try:
        lines = properties.read_text(encoding="utf-8", errors="ignore").splitlines()
    except OSError:
        return None
    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        if key.strip() == "sdk.dir" and value.strip():
            raw_path = value.strip().replace("\\:", ":").replace("\\\\", "\\")
            return Path(raw_path).expanduser()
    return None


def _build_tools_version_key(path: Path) -> Tuple[Any, ...]:
    numbers = tuple(int(item) for item in re.findall(r"\d+", path.name))
    return numbers or (0,)


def _apksigner_under_sdk(sdk_root: Path) -> Optional[Path]:
    build_tools = Path(sdk_root) / "build-tools"
    if not build_tools.is_dir():
        return None
    names = ("apksigner.bat", "apksigner.exe", "apksigner") if os.name == "nt" else (
        "apksigner",
        "apksigner.bat",
    )
    try:
        directories = sorted(
            (item for item in build_tools.iterdir() if item.is_dir()),
            key=_build_tools_version_key,
            reverse=True,
        )
    except OSError:
        return None
    for directory in directories:
        for name in names:
            candidate = directory / name
            if candidate.is_file():
                return candidate.resolve()
    return None


def discover_apksigner(
    repo_root: Path,
    provenance_path: Optional[Path] = None,
    *,
    environ: Optional[Mapping[str, str]] = None,
    explicit: Optional[str] = None,
    search_common: bool = True,
) -> Dict[str, Any]:
    """Find a real apksigner and return child-process env injection details."""

    repo_root = Path(repo_root)
    env = os.environ if environ is None else environ
    result: Dict[str, Any] = {
        "status": UNVERIFIED,
        "tool": None,
        "sdk_root": None,
        "source": None,
        "environment": {},
        "reason": "",
    }

    if explicit:
        tool = _repo_path(repo_root, explicit)
        if not tool.is_file():
            result["reason"] = f"explicit apksigner path does not exist: {tool}"
            return result
    else:
        configured = str(env.get("APKSIGNER", "")).strip()
        if configured:
            tool = _repo_path(repo_root, configured)
            if not tool.is_file():
                result["reason"] = f"APKSIGNER does not name an existing tool: {tool}"
                return result
        else:
            located = shutil.which("apksigner", path=str(env.get("PATH", ""))) or shutil.which(
                "apksigner.bat", path=str(env.get("PATH", ""))
            )
            tool = Path(located).resolve() if located else None

    roots: List[Path] = []
    for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = str(env.get(key, "")).strip()
        if value:
            roots.append(_repo_path(repo_root, value))
    roots.extend(
        _repo_path(repo_root, root)
        for root in _sdk_roots_from_provenance(provenance_path)
    )
    local_sdk = _local_properties_sdk_root(repo_root)
    if local_sdk is not None:
        roots.append(_repo_path(repo_root, local_sdk))
    if search_common:
        roots.extend(
            [
                Path("E:/model/android-sdk"),
                Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk",
            ]
        )

    deduped_roots: List[Path] = []
    seen_roots = set()
    for root in roots:
        try:
            key = str(root.resolve())
        except OSError:
            key = str(root)
        if key not in seen_roots:
            seen_roots.add(key)
            deduped_roots.append(root)

    sdk_root: Optional[Path] = None
    if tool is None:
        for root in deduped_roots:
            candidate = _apksigner_under_sdk(root)
            if candidate is not None:
                tool = candidate
                sdk_root = root.resolve()
                break
    else:
        for parent in tool.parents:
            if parent.name.lower() == "build-tools":
                sdk_root = parent.parent.resolve()
                break

    if tool is None:
        roots_text = ", ".join(str(root) for root in deduped_roots[:6]) or "(none)"
        result["reason"] = (
            "apksigner was not found in APKSIGNER, PATH, provenance/Android SDK roots, "
            f"or common SDK locations; signature verification is explicitly UNVERIFIED. "
            f"Searched roots: {roots_text}"
        )
        return result

    environment = {"APKSIGNER": str(tool)}
    if sdk_root is not None:
        environment["ANDROID_HOME"] = str(sdk_root)
        environment["ANDROID_SDK_ROOT"] = str(sdk_root)
    result.update(
        {
            "status": "AVAILABLE",
            "tool": str(tool),
            "sdk_root": str(sdk_root) if sdk_root is not None else None,
            "source": "explicit" if explicit else "environment/PATH/provenance/SDK discovery",
            "environment": environment,
            "reason": f"apksigner available for runtime verification: {tool}",
        }
    )
    return result


def _report_status(data: Mapping[str, Any]) -> Optional[str]:
    statuses: List[str] = []
    for key in ("status", "result", "verdict", "overall_status", "conclusion"):
        status = _normalized_status(data.get(key))
        if status is not None:
            statuses.append(status)

    checks = data.get("checks")
    if isinstance(checks, Mapping) and checks:
        for check in checks.values():
            if isinstance(check, Mapping):
                status = _normalized_status(check.get("status") or check.get("result"))
            else:
                status = _normalized_status(check)
            if status is not None:
                statuses.append(status)

    passed = data.get("passed")
    if isinstance(passed, bool):
        statuses.append(PASSED if passed else FAILED)

    if FAILED in statuses:
        return FAILED
    if UNVERIFIED in statuses:
        return UNVERIFIED
    if statuses:
        return PASSED
    return None


def evaluate_artifact_report(
    path: Optional[Path], expected_apk_sha256: str
) -> Dict[str, Any]:
    if path is None:
        return {
            "status": NOT_AVAILABLE,
            "path": None,
            "reason": "no machine-readable artifact check report was provided",
        }
    path = Path(path)
    if not path.is_file():
        return {
            "status": NOT_AVAILABLE,
            "path": str(path),
            "reason": "artifact check report is missing",
        }

    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        return {
            "status": FAILED,
            "path": str(path),
            "reason": f"artifact check report is unreadable: {exc}",
        }
    if not isinstance(data, Mapping):
        return {
            "status": FAILED,
            "path": str(path),
            "reason": "artifact check report root is not a JSON object",
        }

    status = _report_status(data)
    if status is None:
        return {
            "status": UNVERIFIED,
            "path": str(path),
            "reason": "artifact check report has no recognizable PASS result",
        }
    if status == FAILED:
        return {
            "status": FAILED,
            "path": str(path),
            "declared_status": status,
            "reason": "artifact check report explicitly reports FAIL",
        }

    declared_sha = None
    for key in ("apk_sha256", "release_apk_sha256", "artifact_sha256", "primary_apk_sha256"):
        value = data.get(key)
        if isinstance(value, str) and value.strip():
            declared_sha = value.strip().lower()
            break
    expected_sha = _normal_hash(expected_apk_sha256)
    if not expected_apk_sha256:
        return {
            "status": UNVERIFIED,
            "path": str(path),
            "declared_status": status,
            "reason": "release APK SHA-256 is unavailable for artifact binding",
        }
    if expected_sha is None:
        return {
            "status": FAILED,
            "path": str(path),
            "declared_status": status,
            "reason": "expected APK SHA-256 is malformed",
        }
    if declared_sha is None:
        return {
            "status": UNVERIFIED,
            "path": str(path),
            "declared_status": status,
            "reason": "artifact report is not APK-bound (no apk_sha256 field)",
        }
    if declared_sha != expected_sha:
        return {
            "status": FAILED,
            "path": str(path),
            "declared_status": status,
            "declared_apk_sha256": declared_sha,
            "expected_apk_sha256": expected_sha,
            "reason": "artifact report is bound to a different APK SHA-256",
        }

    return {
        "status": status,
        "path": str(path),
        "declared_status": status,
        "apk_sha256": declared_sha,
        "reason": "artifact report is APK-bound and its result was parsed"
        if status != PASSED
        else "artifact report is APK-bound and PASS",
    }


def _load_json_mapping(path: Path) -> Tuple[Optional[Mapping[str, Any]], Optional[str]]:
    if not Path(path).is_file():
        return None, f"file missing: {path}"
    try:
        loaded = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        return None, f"unreadable or malformed JSON: {exc}"
    if not isinstance(loaded, Mapping):
        return None, "root is not a JSON object"
    return loaded, None


def _runtime_dimension_count(declared: Any, name: str) -> Optional[int]:
    value = declared.get(name) if isinstance(declared, Mapping) else None
    if isinstance(value, Mapping):
        value = value.get("count")
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        return None
    return value


def _device_physical_arm64(report: Mapping[str, Any]) -> bool:
    device = report.get("device")
    if not isinstance(device, Mapping):
        return False
    physical = (
        device.get("physical") is True
        or device.get("device_type") == "PHYSICAL_ARM64"
        or device.get("representativeDeviceType") == "PHYSICAL_ARM64"
    )
    abi = device.get("abi")
    supported = device.get("supported_abis", device.get("supportedAbis", []))
    abi_ok = abi == "arm64-v8a" and (
        not supported
        or (isinstance(supported, list) and "arm64-v8a" in supported)
    )
    return physical and abi_ok


def _device_serial(report: Mapping[str, Any]) -> Optional[str]:
    top = report.get("serial")
    device = report.get("device")
    nested = device.get("serial") if isinstance(device, Mapping) else None
    top_value = top.strip() if isinstance(top, str) and top.strip() else None
    nested_value = (
        nested.strip() if isinstance(nested, str) and nested.strip() else None
    )
    if top_value and nested_value and top_value != nested_value:
        return None
    return top_value or nested_value


def evaluate_runtime_evidence_contract(
    evidence_dir: Path,
    expected_apk_sha256: Optional[str],
    expected_apk_bytes: Optional[int],
    validator_output: Optional[Mapping[str, Any]] = None,
) -> Dict[str, Any]:
    """Enforce final-gate-only evidence requirements missing from the validator.

    The runtime validator historically accepted one device and did not require
    soak/fault terminal status. This function is deliberately stricter and is
    applied after the validator so those gaps fail closed in the final gate.
    """

    evidence_dir = Path(evidence_dir)
    checks: Dict[str, Dict[str, Any]] = {}

    def add(name: str, ok: bool, reason: str, computed: Any = None) -> None:
        checks[name] = {
            "status": PASSED if ok else FAILED,
            "reason": reason,
            "computed": computed,
        }

    canonical_sha = _normal_hash(expected_apk_sha256)
    canonical_bytes = (
        expected_apk_bytes
        if isinstance(expected_apk_bytes, int) and not isinstance(expected_apk_bytes, bool)
        else None
    )
    add(
        "apk.canonical",
        canonical_sha is not None and canonical_bytes is not None and canonical_bytes > 0,
        "canonical APK SHA-256 and byte size are available"
        if canonical_sha is not None and canonical_bytes is not None and canonical_bytes > 0
        else "canonical APK SHA-256/byte size is unavailable",
        {"sha256": canonical_sha, "bytes": canonical_bytes},
    )

    soak_path = evidence_dir / "soak-report.json"
    soak, soak_error = _load_json_mapping(soak_path)
    soak_status = None
    formal_eligible = None
    soak_sha = None
    if soak_error:
        add("soak.report", False, f"soak report invalid: {soak_error}", {"path": str(soak_path)})
    else:
        assert soak is not None
        raw_status = soak.get("status")
        soak_status = (
            raw_status.strip().upper().replace("-", "_")
            if isinstance(raw_status, str)
            else None
        )
        formal_eligible = soak.get("formal_gate_eligible")
        soak_sha = _normal_hash(soak.get("apk_sha256"))
        status_ok = soak_status in VALID_SOAK_STATUSES and formal_eligible is True
        add(
            "soak.terminal_status",
            status_ok,
            "soak status is PASSED/FORMAL_RC and formal_gate_eligible=true"
            if status_ok
            else "soak must have status PASSED or FORMAL_RC and formal_gate_eligible=true",
            {
                "status": raw_status,
                "formal_gate_eligible": formal_eligible,
            },
        )
        sha_ok = canonical_sha is not None and soak_sha == canonical_sha
        add(
            "soak.apk_sha256",
            sha_ok,
            "soak report is bound to the canonical APK"
            if sha_ok
            else "soak apk_sha256 does not match the canonical APK",
            {"declared_sha256": soak_sha, "canonical_sha256": canonical_sha},
        )

    fault_path = evidence_dir / "fault-report.json"
    fault, fault_error = _load_json_mapping(fault_path)
    if fault_error:
        add("fault.report", False, f"fault report invalid: {fault_error}", {"path": str(fault_path)})
    else:
        assert fault is not None
        raw_fault_status = fault.get("status")
        fault_status = (
            raw_fault_status.strip().upper()
            if isinstance(raw_fault_status, str)
            else None
        )
        fault_status_ok = fault_status == "VERIFIED"
        add(
            "fault.status",
            fault_status_ok,
            "fault status is VERIFIED"
            if fault_status_ok
            else "fault status must be VERIFIED",
            {"status": raw_fault_status},
        )
        fault_sha = _normal_hash(fault.get("apk_sha256"))
        fault_sha_ok = canonical_sha is not None and fault_sha == canonical_sha
        add(
            "fault.apk_sha256",
            fault_sha_ok,
            "fault report is bound to the canonical APK"
            if fault_sha_ok
            else "fault apk_sha256 does not match the canonical APK",
            {"declared_sha256": fault_sha, "canonical_sha256": canonical_sha},
        )

        dimensions = fault.get("dimensions")
        production = _runtime_dimension_count(dimensions, "PRODUCTION_INJECTION")
        device_execution = _runtime_dimension_count(dimensions, "DEVICE_EXECUTION")
        dimensions_ok = (
            production is not None
            and device_execution is not None
            and production > 0
            and device_execution > 0
        )
        add(
            "fault.dimensions",
            dimensions_ok,
            "production and device dimension counts are positive and integer-shaped"
            if dimensions_ok
            else "PRODUCTION_INJECTION and DEVICE_EXECUTION count objects/integers must be positive",
            {"PRODUCTION_INJECTION": production, "DEVICE_EXECUTION": device_execution},
        )

        scenarios = fault.get("mandatory_scenarios")
        scenarios_list = scenarios if isinstance(scenarios, list) else []
        scenario_rows: List[Dict[str, Any]] = []
        scenario_ok = bool(scenarios_list)
        recomputed_production = 0
        recomputed_device = 0
        for index, scenario in enumerate(scenarios_list):
            if not isinstance(scenario, Mapping):
                scenario_ok = False
                scenario_rows.append({"index": index, "ok": False, "reason": "not an object"})
                continue
            scenario_dimensions = scenario.get("dimensions")
            dims = scenario_dimensions if isinstance(scenario_dimensions, list) else []
            recomputed_production += "PRODUCTION_INJECTION" in dims
            recomputed_device += "DEVICE_EXECUTION" in dims
            scenario_device = scenario.get("device")
            device_ok = (
                isinstance(scenario_device, Mapping)
                and isinstance(scenario_device.get("serial"), str)
                and bool(scenario_device["serial"].strip())
                and scenario_device.get("physical") is True
                and scenario_device.get("abi") == "arm64-v8a"
            )
            row_ok = (
                scenario.get("status") == "VERIFIED"
                and "PRODUCTION_INJECTION" in dims
                and "DEVICE_EXECUTION" in dims
                and device_ok
                and bool(scenario.get("evidence"))
            )
            scenario_ok = scenario_ok and row_ok
            scenario_rows.append({"index": index, "id": scenario.get("id"), "ok": row_ok})
        counts_match = (
            production == recomputed_production
            and device_execution == recomputed_device
            and recomputed_production > 0
            and recomputed_device > 0
        )
        add(
            "fault.mandatory_scenarios",
            scenario_ok,
            "every mandatory fault scenario is VERIFIED with production/device evidence"
            if scenario_ok
            else "mandatory fault scenarios are missing or not fully VERIFIED",
            {"scenarios": scenario_rows},
        )
        add(
            "fault.dimension_count_consistency",
            counts_match,
            "declared fault counts match mandatory scenario records"
            if counts_match
            else "declared fault counts do not match mandatory scenario records",
            {
                "declared": {
                    "PRODUCTION_INJECTION": production,
                    "DEVICE_EXECUTION": device_execution,
                },
                "computed": {
                    "PRODUCTION_INJECTION": recomputed_production,
                    "DEVICE_EXECUTION": recomputed_device,
                },
            },
        )

    device_paths = sorted(
        (path for path in evidence_dir.glob("device-*.json") if path.is_file()),
        key=lambda path: path.name,
    )
    device_count_ok = len(device_paths) >= MIN_PHYSICAL_DEVICES
    add(
        "device.report_count",
        device_count_ok,
        f"at least {MIN_PHYSICAL_DEVICES} device-*.json reports are present"
        if device_count_ok
        else f"at least {MIN_PHYSICAL_DEVICES} device-*.json reports are required",
        {"report_count": len(device_paths), "paths": [str(path) for path in device_paths]},
    )

    serials: List[str] = []
    device_rows: List[Dict[str, Any]] = []
    reports_valid = bool(device_paths)
    same_canonical_sha = bool(device_paths) and canonical_sha is not None
    all_physical_arm64 = bool(device_paths)
    for device_path in device_paths:
        device, error = _load_json_mapping(device_path)
        if error:
            reports_valid = False
            same_canonical_sha = False
            all_physical_arm64 = False
            device_rows.append({"path": str(device_path), "ok": False, "reason": error})
            continue
        assert device is not None
        serial = _device_serial(device)
        if serial:
            serials.append(serial)
        declared_sha = _normal_hash(device.get("apk_sha256"))
        installed_sha = _normal_hash(device.get("installed_sha256"))
        sha_ok = (
            canonical_sha is not None
            and declared_sha == canonical_sha
            and installed_sha == canonical_sha
        )
        physical_ok = _device_physical_arm64(device)
        row_ok = (
            device.get("report_type") == "device"
            and serial is not None
            and sha_ok
            and physical_ok
        )
        reports_valid = reports_valid and row_ok
        same_canonical_sha = same_canonical_sha and sha_ok
        all_physical_arm64 = all_physical_arm64 and physical_ok
        device_rows.append(
            {
                "path": str(device_path),
                "serial": serial,
                "apk_sha256": declared_sha,
                "installed_sha256": installed_sha,
                "physical_arm64": physical_ok,
                "ok": row_ok,
            }
        )

    unique_serials = sorted(set(serials))
    unique_count_ok = len(unique_serials) >= MIN_PHYSICAL_DEVICES
    add(
        "device.unique_physical_arm64",
        unique_count_ok and all_physical_arm64 and reports_valid,
        f"{len(unique_serials)} unique physical ARM64 device serials are present"
        if unique_count_ok and all_physical_arm64 and reports_valid
        else "two unique physical ARM64 device reports with complete identity are required",
        {"serials": serials, "unique_serials": unique_serials, "reports": device_rows},
    )
    add(
        "device.same_apk_sha256",
        same_canonical_sha,
        "every device report and installed SHA-256 matches the canonical APK"
        if same_canonical_sha
        else "one or more device reports are not bound to the canonical APK SHA-256",
        {"canonical_sha256": canonical_sha, "reports": device_rows},
    )

    if isinstance(validator_output, Mapping):
        computed = validator_output.get("computed")
        devices_computed = computed.get("devices") if isinstance(computed, Mapping) else None
        if isinstance(devices_computed, Mapping) and "verified_serials" in devices_computed:
            output_serials = devices_computed.get("verified_serials")
        else:
            output_serials = (
                devices_computed.get("serials")
                if isinstance(devices_computed, Mapping)
                else None
            )
        output_serials = (
            [item for item in output_serials if isinstance(item, str) and item.strip()]
            if isinstance(output_serials, list)
            else []
        )
        output_count_ok = len(set(output_serials)) >= MIN_PHYSICAL_DEVICES
        add(
            "device.validator_output_serials",
            output_count_ok,
            "validator output contains at least two unique device serials"
            if output_count_ok
            else "validator output must contain at least two unique device serials",
            {"serials": output_serials},
        )

    failed = sorted(name for name, check in checks.items() if check["status"] != PASSED)
    return {
        "status": PASSED if not failed else FAILED,
        "reason": (
            "final-gate runtime contract satisfied"
            if not failed
            else "final-gate runtime contract failed: " + ", ".join(failed)
        ),
        "failed_checks": failed,
        "checks": checks,
    }


def evaluate_reproducibility_report(
    path: Optional[Path],
    expected_apk_sha256: str,
    expected_apk_bytes: Optional[int],
) -> Dict[str, Any]:
    base = evaluate_artifact_report(path, expected_apk_sha256)
    result = dict(base)
    result["equal_required"] = True
    result["minimum_builds"] = 2
    if base.get("status") != PASSED:
        return result

    assert path is not None
    data, error = _load_json_mapping(Path(path))
    if error:
        result.update({"status": FAILED, "reason": f"reproducibility report invalid: {error}"})
        return result
    assert data is not None

    equal_ok = data.get("equal") is True
    builds = data.get("builds")
    builds_list = builds if isinstance(builds, list) else []
    builds_ok = len(builds_list) >= 2
    expected_sha = _normal_hash(expected_apk_sha256)
    expected_bytes = (
        expected_apk_bytes
        if isinstance(expected_apk_bytes, int) and not isinstance(expected_apk_bytes, bool)
        else None
    )
    top_bytes = data.get("apk_bytes")
    top_bytes_ok = (
        expected_bytes is not None
        and isinstance(top_bytes, int)
        and not isinstance(top_bytes, bool)
        and top_bytes == expected_bytes
    )

    build_rows: List[Dict[str, Any]] = []
    builds_bound = bool(builds_list)
    for index, build in enumerate(builds_list):
        if not isinstance(build, Mapping):
            builds_bound = False
            build_rows.append({"index": index, "ok": False, "reason": "not an object"})
            continue
        build_sha = _normal_hash(build.get("sha256"))
        build_bytes = build.get("bytes")
        bytes_ok = (
            expected_bytes is not None
            and isinstance(build_bytes, int)
            and not isinstance(build_bytes, bool)
            and build_bytes == expected_bytes
        )
        row_ok = expected_sha is not None and build_sha == expected_sha and bytes_ok
        builds_bound = builds_bound and row_ok
        build_rows.append(
            {
                "index": index,
                "sha256": build_sha,
                "bytes": build_bytes,
                "ok": row_ok,
            }
        )

    passed = equal_ok and builds_ok and top_bytes_ok and builds_bound
    result.update(
        {
            "status": PASSED if passed else FAILED,
            "equal": data.get("equal"),
            "builds_observed": len(builds_list),
            "expected_apk_sha256": expected_sha,
            "expected_apk_bytes": expected_bytes,
            "top_level_apk_bytes": top_bytes,
            "builds": build_rows,
            "reason": (
                "reproducibility equal=true, at least two builds, and every "
                "SHA-256/byte count matches the canonical APK"
                if passed
                else "reproducibility requires equal=true, builds>=2, and every "
                "build SHA-256/byte count matching the canonical APK"
            ),
        }
    )
    return result


def evaluate_signature_check(
    runtime_verification: Mapping[str, Any],
    signature_tool: Optional[Mapping[str, Any]],
) -> Dict[str, Any]:
    raw = runtime_verification.get("validator_output")
    checks = raw.get("checks") if isinstance(raw, Mapping) else None
    signature_check = checks.get("apk.signature") if isinstance(checks, Mapping) else None
    validator_status = (
        _normalized_status(signature_check.get("status"))
        if isinstance(signature_check, Mapping)
        else None
    )
    tool_status = signature_tool.get("status") if isinstance(signature_tool, Mapping) else None
    tool_path = signature_tool.get("tool") if isinstance(signature_tool, Mapping) else None
    tool_available = tool_status == "AVAILABLE" and isinstance(tool_path, str) and Path(tool_path).is_file()

    if not tool_available:
        reason = (
            signature_tool.get("reason")
            if isinstance(signature_tool, Mapping)
            else "apksigner discovery result is unavailable"
        )
        return {
            "status": UNVERIFIED,
            "source": "runtime_evidence_verification.checks.apk.signature",
            "tool": tool_path,
            "reason": reason or "apksigner is unavailable; signature verification is explicitly UNVERIFIED",
        }
    if validator_status is None:
        return {
            "status": UNVERIFIED,
            "source": "runtime_evidence_verification.checks.apk.signature",
            "tool": tool_path,
            "reason": "runtime validator did not provide an APK signature check",
        }
    return {
        "status": validator_status,
        "source": "runtime_evidence_verification.checks.apk.signature",
        "tool": tool_path,
        "reason": (
            signature_check.get("reason")
            if isinstance(signature_check, Mapping)
            else "runtime validator signature check completed"
        ),
    }


def evaluate_release_artifact_checks(
    repo_root: Path,
    evidence_dir: Path,
    release_apk: Path,
    runtime_verification: Mapping[str, Any],
    *,
    environ: Optional[Mapping[str, str]] = None,
    canonical_apk: Optional[Mapping[str, Any]] = None,
    signature_tool: Optional[Mapping[str, Any]] = None,
) -> Dict[str, Any]:
    apk_sha256 = ""
    apk_bytes: Optional[int] = None
    if isinstance(canonical_apk, Mapping) and canonical_apk.get("status") == PASSED:
        apk_sha256 = str(canonical_apk.get("sha256") or "")
        raw_bytes = canonical_apk.get("bytes")
        if isinstance(raw_bytes, int) and not isinstance(raw_bytes, bool):
            apk_bytes = raw_bytes
    elif Path(release_apk).is_file():
        apk_sha256 = _sha256_file(Path(release_apk))
        apk_bytes = Path(release_apk).stat().st_size

    signed = evaluate_signature_check(runtime_verification, signature_tool)

    repro_candidates = _artifact_report_candidates(
        repo_root,
        evidence_dir,
        REPRODUCIBILITY_REPORTS,
        environ=environ,
        env_name=REPRODUCIBILITY_REPORT_ENV,
    )
    provenance_candidates = _artifact_report_candidates(
        repo_root,
        evidence_dir,
        PROVENANCE_REPORTS,
        environ=environ,
        env_name=PROVENANCE_REPORT_ENV,
    )
    reproducibility = evaluate_reproducibility_report(
        _first_existing(repro_candidates), apk_sha256, apk_bytes
    )
    provenance = evaluate_artifact_report(
        _first_existing(provenance_candidates), apk_sha256
    )
    reproducibility["candidates"] = [str(path) for path in repro_candidates]
    provenance["candidates"] = [str(path) for path in provenance_candidates]

    return {
        "signed": signed,
        "reproducible": reproducibility,
        "provenance": provenance,
        "canonical_apk": {
            "sha256": apk_sha256 or None,
            "bytes": apk_bytes,
        },
    }


def all_artifact_checks_passed(artifact_checks: Mapping[str, Any]) -> bool:
    return all(
        isinstance(artifact_checks.get(name), Mapping)
        and artifact_checks[name].get("status") == PASSED
        for name in ("signed", "reproducible", "provenance")
    )


def derive_rating_statuses(
    gate_results: Mapping[str, str],
    policy_test: Mapping[str, Any],
    runtime_verification: Mapping[str, Any],
    artifact_checks: Mapping[str, Any],
    *,
    viewmodel_budget_passed: bool,
) -> Dict[str, Any]:
    s_c_passed = (
        gate_results.get("architecture_rules") == PASSED
        and policy_test.get("status") == PASSED
        and viewmodel_budget_passed
    )
    s_r_passed = runtime_verification.get("status") == PASSED
    s_o_passed = (
        gate_results.get("release_integrity") == PASSED
        and runtime_verification.get("status") == PASSED
        and all_artifact_checks_passed(artifact_checks)
    )

    if s_c_passed:
        s_c = PASSED
        s_c_reason = "architecture rules, policy JUnit XML, and MainViewModel limit all passed"
    else:
        s_c = "BLOCKED"
        s_c_reason = (
            "S-C requires architecture rules PASS, policy test XML PASS, and the "
            "MainViewModel line limit; bridge line budget remains advisory"
        )

    if s_r_passed:
        s_r = PASSED
        s_r_reason = "APK-bound runtime validator exited 0 with raw summary PASS"
    elif runtime_verification.get("status") == FAILED:
        s_r = "FAILED_RUNTIME_EVIDENCE"
        s_r_reason = str(runtime_verification.get("reason") or "runtime validator FAILED")
    else:
        s_r = "UNVERIFIED_RUNTIME_EVIDENCE"
        s_r_reason = str(
            runtime_verification.get("reason")
            or "S-R requires runtime validator PASS"
        )

    artifact_failed = any(
        isinstance(artifact_checks.get(name), Mapping)
        and artifact_checks[name].get("status") == FAILED
        for name in ("signed", "reproducible", "provenance")
    )
    if s_o_passed:
        s_o = PASSED
        s_o_reason = (
            "release integrity, runtime validator, signature, reproducibility, and "
            "provenance checks all passed"
        )
    elif gate_results.get("release_integrity") != PASSED:
        s_o = "BLOCKED_RELEASE_INTEGRITY"
        s_o_reason = "S-O requires the release integrity gate to PASS"
    elif runtime_verification.get("status") != PASSED:
        s_o = "UNVERIFIED_RUNTIME_EVIDENCE"
        s_o_reason = "S-O requires the APK-bound runtime validator to PASS"
    elif artifact_failed:
        s_o = "BLOCKED_RELEASE_ARTIFACTS"
        s_o_reason = "one or more available release artifact checks failed"
    else:
        s_o = "UNVERIFIED_RELEASE_ARTIFACTS"
        s_o_reason = (
            "S-O requires PASS for signature, reproducibility, and provenance artifact checks"
        )

    return {
        "statuses": {"S-C": s_c, "S-R": s_r, "S-O": s_o},
        "passed": {"S-C": s_c_passed, "S-R": s_r_passed, "S-O": s_o_passed},
        "reasons": {"S-C": s_c_reason, "S-R": s_r_reason, "S-O": s_o_reason},
    }


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "MCA final gate: verify release integrity, APK-bound runtime evidence, "
            "and the local universal-device-admission JUnit policy test."
        )
    )
    parser.add_argument(
        "--runtime-evidence-dir",
        help=(
            "directory containing soak-report.json, fault-report.json, and "
            "device-*.json (overrides MCA_RUNTIME_EVIDENCE_DIR; default: "
            "artifacts/rc/final)"
        ),
    )
    parser.add_argument(
        "--apk",
        help=(
            "canonical release APK used by both release integrity and runtime "
            "validation (default: app/build/outputs/apk/release/app-release.apk)"
        ),
    )
    parser.add_argument(
        "--expected-apk-sha256",
        help="require the canonical APK and runtime validator to match this SHA-256",
    )
    parser.add_argument(
        "--apksigner",
        help=(
            "explicit apksigner tool; otherwise discover APKSIGNER, Android SDK roots "
            "from release provenance, local.properties, PATH, and common SDK locations"
        ),
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="print resolved paths and planned gates without running commands or writing a report",
    )
    return parser


def _print_dry_run(
    repo_root: Path,
    evidence_dir: Path,
    canonical_apk: Mapping[str, Any],
    signature_tool: Mapping[str, Any],
) -> None:
    release_apk = Path(str(canonical_apk.get("path")))
    validator = repo_root / RUNTIME_VALIDATOR_RELATIVE
    discovery = discover_runtime_evidence(evidence_dir)
    policy_path, policy_candidates = select_policy_test_xml(repo_root)
    print("MCA FINAL GATE DRY RUN - no commands executed, no report written")
    print(f"repo_root={repo_root}")
    print(f"release_apk={release_apk} exists={release_apk.is_file()}")
    print(f"canonical_apk_status={canonical_apk.get('status')}")
    print(f"canonical_apk_sha256={canonical_apk.get('sha256')}")
    print(f"canonical_apk_bytes={canonical_apk.get('bytes')}")
    print(f"canonical_apk_reason={canonical_apk.get('reason')}")
    print(
        "apksigner_status="
        f"{signature_tool.get('status')} tool={signature_tool.get('tool')} "
        f"reason={signature_tool.get('reason')}"
    )
    print(f"runtime_validator={validator} exists={validator.is_file()}")
    print(f"runtime_evidence_dir={evidence_dir}")
    print(f"soak_report={discovery['soak_report']}")
    print(f"fault_report={discovery['fault_report']}")
    print(f"device_reports={discovery['device_reports']}")
    print(f"runtime_evidence_missing={discovery['missing']}")
    print(f"policy_test_xml={policy_path}")
    print("policy_test_candidates=")
    for candidate in policy_candidates:
        print(f"  {candidate} exists={candidate.is_file()}")
    print(
        "planned_gates=canonical_apk,architecture_rules,release_integrity,"
        "runtime_evidence,runtime_evidence_contract,policy_test,"
        "local_only_guard,required_reports,preprocessor_balance,"
        "qnn_runtime_compiles,viewmodel_line_budget"
    )
    print("DRY_RUN_COMPLETE")


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = _build_parser().parse_args(argv)
    repo_root = REPO_ROOT
    runtime_evidence_dir = resolve_runtime_evidence_dir(
        args.runtime_evidence_dir, repo_root=repo_root
    )
    canonical_apk = resolve_canonical_apk(
        repo_root, args.apk, args.expected_apk_sha256
    )
    provenance_candidates = _artifact_report_candidates(
        repo_root,
        runtime_evidence_dir,
        PROVENANCE_REPORTS,
        environ=os.environ,
        env_name=PROVENANCE_REPORT_ENV,
    )
    provenance_path = _first_existing(provenance_candidates)
    signature_tool = discover_apksigner(
        repo_root,
        provenance_path,
        environ=os.environ,
        explicit=args.apksigner,
    )

    if args.dry_run:
        _print_dry_run(
            repo_root, runtime_evidence_dir, canonical_apk, signature_tool
        )
        return 0

    print("=================================================================")
    print("     MCA S-GRADE ENTERPRISE REFACTOR: HONEST AUDIT GATE          ")
    print("=================================================================\n")

    jdk_major, jdk_info, java_bin = get_jdk_version()
    print("--> [Final Gate] JDK Environment Guard:")
    print(f"    Version: {jdk_info}")
    print(f"    Binary:  {java_bin}")
    print(f"    Status:  PASSED (JDK {jdk_major} >= 21)\n")

    results: Dict[str, str] = {}
    gate_reasons: Dict[str, str] = {}
    results["canonical_apk"] = str(canonical_apk["status"])
    gate_reasons["canonical_apk"] = str(canonical_apk["reason"])

    architecture = run_command(
        "Architecture Rules Gate",
        [
            sys.executable,
            str(repo_root / "tools/architecture/check_architecture_rules.py"),
        ],
    )
    results["architecture_rules"] = PASSED if architecture["passed"] else FAILED
    gate_reasons["architecture_rules"] = architecture["reason"]

    release_command = build_release_integrity_command(
        repo_root,
        runtime_evidence_dir,
        canonical_apk,
        args.expected_apk_sha256,
    )
    release = run_command("Release Integrity Gate", release_command)
    results["release_integrity"] = PASSED if release["passed"] else FAILED
    gate_reasons["release_integrity"] = release["reason"]

    expected_for_runtime = args.expected_apk_sha256 or canonical_apk.get("sha256")
    runtime_verification = verify_runtime_evidence(
        repo_root,
        runtime_evidence_dir,
        expected_apk_sha256=(
            str(expected_for_runtime) if expected_for_runtime else None
        ),
        extra_env=signature_tool.get("environment", {}),
    )
    runtime_contract = evaluate_runtime_evidence_contract(
        runtime_evidence_dir,
        canonical_apk.get("sha256") if isinstance(canonical_apk.get("sha256"), str) else None,
        canonical_apk.get("bytes") if isinstance(canonical_apk.get("bytes"), int) else None,
        runtime_verification.get("validator_output"),
    )
    runtime_verification["contract"] = runtime_contract
    if runtime_contract["status"] != PASSED:
        runtime_verification["status"] = FAILED
        contract_reason = str(runtime_contract["reason"])
        existing_reason = str(runtime_verification.get("reason") or "").strip()
        runtime_verification["reason"] = (
            f"{existing_reason}; {contract_reason}" if existing_reason else contract_reason
        )
    runtime_status = str(runtime_verification["status"])
    results["runtime_evidence"] = runtime_status
    results["runtime_evidence_contract"] = str(runtime_contract["status"])
    # Backward-compatible aliases. Both are governed exclusively by the runtime
    # validator now; no production-smoke booleans or model-only matrix can pass them.
    results["fault_injection_matrix"] = runtime_status
    results["device_and_reliability_evidence"] = runtime_status
    gate_reasons["runtime_evidence"] = str(runtime_verification["reason"])
    gate_reasons["runtime_evidence_contract"] = str(runtime_contract["reason"])
    gate_reasons["fault_injection_matrix"] = (
        "production fault evidence is governed only by runtime_evidence_verification"
    )
    gate_reasons["device_and_reliability_evidence"] = (
        "device/UI/API/soak evidence is governed only by runtime_evidence_verification"
    )

    policy_path, _ = select_policy_test_xml(repo_root)
    policy_test = parse_junit_xml(policy_path)
    policy_test["architecture_rules"] = {
        "status": results["architecture_rules"],
        "reason": gate_reasons["architecture_rules"],
        "role": "additional evidence only",
    }
    results["policy_test"] = str(policy_test["status"])
    gate_reasons["policy_test"] = str(policy_test["reason"])
    if policy_test["status"] == PASSED:
        universal_device_admission = "VERIFIED_COMPLIANT"
    elif policy_test["status"] == FAILED:
        universal_device_admission = FAILED
    else:
        universal_device_admission = UNVERIFIED

    local_files = [
        "AGENTS.md",
        "app/src/test/java/com/muyuchat/mca/UniversalDeviceAdmissionPolicyTest.kt",
    ]
    local_ignored = True
    local_reasons: List[str] = []
    for local_file in local_files:
        path = repo_root / local_file
        if not path.exists():
            local_reasons.append(f"{local_file}: absent")
            continue
        checked = subprocess.run(
            ["git", "check-ignore", "-q", local_file],
            cwd=str(repo_root),
            check=False,
        )
        if checked.returncode != 0:
            local_ignored = False
            local_reasons.append(f"{local_file}: NOT excluded")
        else:
            local_reasons.append(f"{local_file}: excluded")
    results["local_only_guard"] = PASSED if local_ignored else FAILED
    gate_reasons["local_only_guard"] = "; ".join(local_reasons)
    print(
        "--> [Final Gate] Local-Only Policy Isolation: "
        f"{'PASSED' if local_ignored else 'FAILED'}"
    )

    reports = {
        "sbom": (repo_root / "tools/sbom/mca-sbom.spdx.json").is_file(),
        "page_size_16kb": (repo_root / "tools/release/16kb_page_size_report.json").is_file(),
    }
    missing_reports = [name for name, present in reports.items() if not present]
    results["required_reports"] = PASSED if not missing_reports else FAILED
    gate_reasons["required_reports"] = (
        "all required release reports are present"
        if not missing_reports
        else "missing required release reports: " + ", ".join(missing_reports)
    )

    bridges = {
        "qnn_native_bridge.cpp": count_lines(
            repo_root / "core/native/src/main/cpp/qnn_native_bridge.cpp"
        ),
        "mnn_native_engine.cpp": count_lines(
            repo_root / "core/native/src/main/cpp/mnn_native_engine.cpp"
        ),
        "native_engine.cpp": count_lines(
            repo_root / "core/native/src/main/cpp/native_engine.cpp"
        ),
        "stable_diffusion_bridge.cpp": count_lines(
            repo_root / "core/sd-native/src/main/cpp/stable_diffusion_bridge.cpp"
        ),
    }
    total_bridge_lines = sum(bridges.values())
    bridge_over_budget = {
        name: line_count
        for name, line_count in bridges.items()
        if line_count > BRIDGE_LINE_LIMIT
    }
    results["bridge_line_budgets"] = (
        "ADVISORY_OVER_BUDGET" if bridge_over_budget else "WITHIN_BUDGET"
    )
    gate_reasons["bridge_line_budgets"] = (
        "advisory only; over budget: "
        + ", ".join(f"{name}={lines}" for name, lines in bridge_over_budget.items())
        if bridge_over_budget
        else "advisory only; all bridges within the directional threshold"
    )
    print("--> [Final Gate] Giant Bridges Debt Audit:")
    for bridge_name, bridge_lines in bridges.items():
        print(f"    {bridge_name}: {bridge_lines} lines")
    print(f"    Total unmigrated bridge lines: {total_bridge_lines}")

    qnn_bridge_path = repo_root / "core/native/src/main/cpp/qnn_native_bridge.cpp"
    preprocessor = run_command(
        "Preprocessor Structural Balance",
        [
            sys.executable,
            str(repo_root / "tools/final-gate/check_preprocessor_balance.py"),
            str(qnn_bridge_path),
        ],
    )
    results["preprocessor_balance"] = PASSED if preprocessor["passed"] else FAILED
    gate_reasons["preprocessor_balance"] = preprocessor["reason"]

    qnn_compile = run_command(
        "QNN Runtime Modules Compile",
        [sys.executable, str(repo_root / "tools/native/verify_qnn_runtime_compiles.py")],
    )
    results["qnn_runtime_compiles"] = PASSED if qnn_compile["passed"] else FAILED
    gate_reasons["qnn_runtime_compiles"] = qnn_compile["reason"]

    vm_lines = count_lines(
        repo_root / "app/src/main/java/com/muyuchat/mca/MainViewModel.kt"
    )
    viewmodel_budget_passed = vm_lines <= VIEWMODEL_LINE_LIMIT
    results["viewmodel_line_budget"] = PASSED if viewmodel_budget_passed else FAILED
    gate_reasons["viewmodel_line_budget"] = (
        f"MainViewModel.kt has {vm_lines} lines; hard limit is {VIEWMODEL_LINE_LIMIT}"
    )
    print(
        f"--> [Final Gate] MainViewModel Lines: {vm_lines} "
        f"(Hard Cap: {VIEWMODEL_LINE_LIMIT})"
    )

    release_apk = Path(str(canonical_apk["path"]))
    artifact_checks = evaluate_release_artifact_checks(
        repo_root,
        runtime_evidence_dir,
        release_apk,
        runtime_verification,
        canonical_apk=canonical_apk,
        signature_tool=signature_tool,
    )
    for artifact_name in ("signed", "reproducible", "provenance"):
        artifact_result = artifact_checks.get(artifact_name)
        if isinstance(artifact_result, Mapping):
            gate_reasons[f"release_artifact:{artifact_name}"] = str(
                artifact_result.get("reason", "")
            )

    contract_model_evidence = collect_contract_model_evidence(repo_root)

    ratings = derive_rating_statuses(
        results,
        policy_test,
        runtime_verification,
        artifact_checks,
        viewmodel_budget_passed=viewmodel_budget_passed,
    )
    gate_reasons.update(ratings["reasons"])

    required_gates = [
        "canonical_apk",
        "architecture_rules",
        "release_integrity",
        "runtime_evidence",
        "runtime_evidence_contract",
        "policy_test",
        "local_only_guard",
        "required_reports",
        "preprocessor_balance",
        "qnn_runtime_compiles",
        "viewmodel_line_budget",
    ]
    blockers = [
        name for name in required_gates if results.get(name) != PASSED
    ]
    for dimension in ("S-C", "S-R", "S-O"):
        if not ratings["passed"][dimension]:
            blockers.append(dimension)

    runtime_raw_summary = runtime_verification.get("raw_summary")
    signature_ok = (
        isinstance(artifact_checks.get("signed"), Mapping)
        and artifact_checks["signed"].get("status") == PASSED
    )
    explicit_s_grade_evidence = (
        results.get("canonical_apk") == PASSED
        and runtime_verification.get("status") == PASSED
        and runtime_verification.get("validator_exit_code") == 0
        and results.get("runtime_evidence_contract") == PASSED
        and isinstance(runtime_raw_summary, Mapping)
        and runtime_raw_summary.get("status") == "PASS"
        and runtime_raw_summary.get("exit_code") == 0
        and policy_test.get("status") == PASSED
        and int(policy_test.get("tests", 0)) > 0
        and int(policy_test.get("failures", 0)) == 0
        and int(policy_test.get("errors", 0)) == 0
        and int(policy_test.get("skipped", 0)) == 0
        and results.get("release_integrity") == PASSED
        and signature_ok
        and all_artifact_checks_passed(artifact_checks)
    )
    overall_status = (
        "S_GRADE_READY"
        if not blockers and explicit_s_grade_evidence
        else "IN_PROGRESS_NOT_S_GRADE"
    )

    final_report = {
        "title": "MCA Enterprise S-Grade Architecture Audit Report (Evidence-Bound)",
        "working_tree": str(repo_root),
        "jdk_environment": {
            "version": jdk_info,
            "major": jdk_major,
            "binary": java_bin,
            "status": "COMPLIANT_JDK21",
        },
        "overall_status": overall_status,
        "blockers": blockers,
        "required_gates": required_gates,
        "gate_results": results,
        "gate_reasons": gate_reasons,
        "canonical_apk": canonical_apk,
        "apksigner_discovery": signature_tool,
        "runtime_evidence_verification": {
            "status": runtime_verification.get("status"),
            "exit_code": runtime_verification.get("validator_exit_code"),
            "command": runtime_verification.get("command"),
            "expected_apk_sha256": runtime_verification.get("expected_apk_sha256"),
            "process_environment": runtime_verification.get("process_environment"),
            "summary": runtime_raw_summary,
            "raw": runtime_verification.get("validator_output"),
            "reason": runtime_verification.get("reason"),
            "evidence": runtime_verification.get("evidence"),
            "contract": runtime_contract,
        },
        "policy_test": policy_test,
        "universal_device_admission": universal_device_admission,
        "local_only_guard": "VERIFIED_ISOLATED" if local_ignored else FAILED,
        "contract_model_evidence": contract_model_evidence,
        "release_artifact_checks": artifact_checks,
        "rating_evaluation": ratings["statuses"],
        "rating_reasons": ratings["reasons"],
        "bridge_line_budget": {
            "advisory": True,
            "limit": BRIDGE_LINE_LIMIT,
            "over_budget": bridge_over_budget,
            "status": results["bridge_line_budgets"],
        },
        "giant_bridges_debt": {
            **bridges,
            "total_unmigrated_lines": total_bridge_lines,
        },
        "viewmodel_debt": {
            "MainViewModel.kt": vm_lines,
            "target_lines": VIEWMODEL_LINE_LIMIT,
            "over_lines": max(0, vm_lines - VIEWMODEL_LINE_LIMIT),
        },
    }

    final_report_path = repo_root / "architecture-final-report.json"
    final_report_path.write_bytes(
        (json.dumps(final_report, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
    )
    print(f"\nAudit Report written to: {final_report_path}")
    print("\n-----------------------------------------------------------------")
    print(f"AUDIT STATUS: {overall_status}")
    print(f"Blocking gates: {', '.join(blockers) if blockers else 'none'}")
    print(
        f"Giant Bridges Debt: {total_bridge_lines} lines | "
        f"VM Debt: +{max(0, vm_lines - VIEWMODEL_LINE_LIMIT)} lines"
    )
    return 0 if overall_status == "S_GRADE_READY" else 1


if __name__ == "__main__":
    sys.exit(main())
