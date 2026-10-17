#!/usr/bin/env python3
"""Pure-Python tests for verify_s_grade_final_gate.py.

All fixtures are synthetic and live under a temporary directory. These tests do
not run Gradle, adb, a device, the runtime evidence validator, or the full gate.
"""

from __future__ import annotations

import hashlib
import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional

MODULE_PATH = Path(__file__).with_name("verify_s_grade_final_gate.py")
SPEC = importlib.util.spec_from_file_location("verify_s_grade_final_gate", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")


def validator_payload(
    status: str,
    exit_code: int,
    *,
    failed_checks: Optional[List[str]] = None,
    unverified_checks: Optional[List[str]] = None,
    computed: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    checks: Dict[str, Any] = {}
    if status == "PASS":
        checks["apk.signature"] = {
            "status": "PASS",
            "reason": "synthetic apksigner accepted the APK",
        }
    payload = {
        "schema": "mca-runtime-evidence-verification/1.0",
        "checks": checks,
        "summary": {
            "status": status,
            "exit_code": exit_code,
            "counts": {"PASS": len(checks), "FAIL": 0, "UNVERIFIED": 0},
            "failed_checks": failed_checks or [],
            "unverified_checks": unverified_checks or [],
            "reasons": ["synthetic validator result"],
        },
    }
    if computed is not None:
        payload["computed"] = computed
    return payload


class FakeRunner:
    def __init__(
        self,
        returncode: int,
        payload: Optional[Dict[str, Any]] = None,
        *,
        stdout: Optional[str] = None,
        stderr: str = "",
    ) -> None:
        self.returncode = returncode
        self.payload = payload
        self.stdout = stdout
        self.stderr = stderr
        self.calls: List[List[str]] = []
        self.kwargs: List[Dict[str, Any]] = []

    def __call__(self, command: Iterable[str], **kwargs: Any) -> subprocess.CompletedProcess:
        command_list = list(command)
        self.calls.append(command_list)
        self.kwargs.append(dict(kwargs))
        stdout = self.stdout
        if stdout is None:
            stdout = json.dumps(self.payload or {})
        return subprocess.CompletedProcess(
            command_list,
            self.returncode,
            stdout=stdout,
            stderr=self.stderr,
        )


def policy_xml(
    path: Path,
    *,
    tests: int = 5,
    failures: int = 0,
    errors: int = 0,
    skipped: int = 0,
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    testcase_lines: List[str] = []
    remaining_failures = failures
    remaining_errors = errors
    remaining_skipped = skipped
    for index in range(tests):
        if remaining_failures:
            testcase_lines.append(
                f'  <testcase name="failure-{index}"><failure message="synthetic"/></testcase>'
            )
            remaining_failures -= 1
        elif remaining_errors:
            testcase_lines.append(
                f'  <testcase name="error-{index}"><error message="synthetic"/></testcase>'
            )
            remaining_errors -= 1
        elif remaining_skipped:
            testcase_lines.append(
                f'  <testcase name="skipped-{index}"><skipped/></testcase>'
            )
            remaining_skipped -= 1
        else:
            testcase_lines.append(f'  <testcase name="pass-{index}"/>')
    body = "\n".join(testcase_lines)
    path.write_text(
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        f'<testsuite name="com.muyuchat.mca.UniversalDeviceAdmissionPolicyTest" '
        f'tests="{tests}" failures="{failures}" errors="{errors}" skipped="{skipped}" time="1.5">\n'
        f"{body}\n"
        "</testsuite>\n",
        encoding="utf-8",
    )


class FinalGateTests(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory(prefix="mca-final-gate-")
        self.root = Path(self._tmp.name)
        self.apk = self.root / gate.RELEASE_APK_RELATIVE
        self.apk.parent.mkdir(parents=True, exist_ok=True)
        self.apk.write_bytes(b"synthetic-release-apk")
        self.validator = self.root / gate.RUNTIME_VALIDATOR_RELATIVE
        self.validator.parent.mkdir(parents=True, exist_ok=True)
        self.validator.write_text("# synthetic validator placeholder\n", encoding="utf-8")
        self.evidence_dir = self.root / "artifacts/rc/final"

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def make_evidence(
        self,
        *,
        soak: bool = True,
        fault: bool = True,
        device_count: int = 1,
    ) -> None:
        self.evidence_dir.mkdir(parents=True, exist_ok=True)
        if soak:
            write_json(self.evidence_dir / "soak-report.json", {"synthetic": "soak"})
        if fault:
            write_json(self.evidence_dir / "fault-report.json", {"synthetic": "fault"})
        for index in range(device_count):
            write_json(
                self.evidence_dir / f"device-{index + 1}.json",
                {"synthetic": "device", "index": index},
            )

    def run_runtime(self, runner: FakeRunner) -> Dict[str, Any]:
        return gate.verify_runtime_evidence(
            self.root,
            self.evidence_dir,
            release_apk=self.apk,
            validator_path=self.validator,
            runner=runner,
        )

    def test_no_release_apk_is_unverified_and_validator_is_not_called(self) -> None:
        self.make_evidence()
        self.apk.unlink()
        runner = FakeRunner(0, validator_payload("PASS", 0))
        result = self.run_runtime(runner)
        self.assertEqual(result["status"], gate.UNVERIFIED)
        self.assertIsNone(result["validator_exit_code"])
        self.assertEqual(runner.calls, [])

    def test_missing_soak_is_unverified_and_validator_is_not_called(self) -> None:
        self.make_evidence(soak=False)
        runner = FakeRunner(0, validator_payload("PASS", 0))
        result = self.run_runtime(runner)
        self.assertEqual(result["status"], gate.UNVERIFIED)
        self.assertIsNone(result["validator_exit_code"])
        self.assertIn("soak-report.json", result["evidence"]["missing"])
        self.assertEqual(runner.calls, [])

    def test_validator_exit_two_maps_to_unverified(self) -> None:
        self.make_evidence()
        payload = validator_payload(
            "UNVERIFIED",
            2,
            unverified_checks=["apk.signature"],
        )
        result = self.run_runtime(FakeRunner(2, payload))
        self.assertEqual(result["status"], gate.UNVERIFIED)
        self.assertEqual(result["validator_exit_code"], 2)
        self.assertEqual(result["raw_summary"], payload["summary"])

    def test_validator_exit_one_maps_to_failed(self) -> None:
        self.make_evidence()
        payload = validator_payload("FAIL", 1, failed_checks=["soak.duration"])
        result = self.run_runtime(FakeRunner(1, payload))
        self.assertEqual(result["status"], gate.FAILED)
        self.assertEqual(result["validator_exit_code"], 1)
        self.assertIn("soak.duration", result["reason"])

    def test_validator_exit_zero_passes_and_uses_24h_1000_minimums(self) -> None:
        self.make_evidence(device_count=2)
        payload = validator_payload("PASS", 0)
        runner = FakeRunner(0, payload)
        result = self.run_runtime(runner)
        self.assertEqual(result["status"], gate.PASSED)
        self.assertEqual(result["validator_exit_code"], 0)
        self.assertEqual(result["raw_summary"], payload["summary"])
        command = runner.calls[0]
        self.assertEqual(command[command.index("--min-soak-hours") + 1], "24")
        self.assertEqual(command[command.index("--min-requests") + 1], "1000")
        self.assertEqual(command[command.index("--min-devices") + 1], "2")
        self.assertEqual(command.count("--device-report"), 2)

    def test_old_production_smoke_booleans_cannot_pass(self) -> None:
        legacy = self.root / "artifacts/device-smoke/production-smoke.json"
        write_json(
            legacy,
            {
                "status": "PASSED",
                "mainActivityUiSmoke": "PASSED",
                "authenticatedLocalApiSmoke": "PASSED",
                "crashFreeSoak": "PASSED",
                "anrSoak": "PASSED",
                "apkSha256": "a" * 64,
            },
        )
        runner = FakeRunner(0, validator_payload("PASS", 0))
        result = self.run_runtime(runner)
        self.assertEqual(result["status"], gate.UNVERIFIED)
        self.assertIsNone(result["validator_exit_code"])
        self.assertEqual(runner.calls, [])

    def test_policy_test_missing_is_unverified(self) -> None:
        selected, _ = gate.select_policy_test_xml(self.root)
        self.assertIsNone(selected)
        result = gate.parse_junit_xml(selected)
        self.assertEqual(result["status"], gate.UNVERIFIED)

    def test_policy_test_counter_only_xml_is_supported(self) -> None:
        path = self.root / gate.POLICY_TEST_PRIMARY_RELATIVE
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            '<testsuite name="com.muyuchat.mca.UniversalDeviceAdmissionPolicyTest" '
            'tests="5" failures="0" errors="0" skipped="0" />\n',
            encoding="utf-8",
        )
        result = gate.parse_junit_xml(path)
        self.assertEqual(result["status"], gate.PASSED)
        self.assertEqual(result["tests"], 5)

    def test_policy_test_failure_is_failed(self) -> None:
        path = self.root / gate.POLICY_TEST_PRIMARY_RELATIVE
        policy_xml(path, tests=5, failures=1)
        result = gate.parse_junit_xml(path)
        self.assertEqual(result["status"], gate.FAILED)
        self.assertEqual(result["failures"], 1)

    def test_policy_test_skipped_is_unverified(self) -> None:
        path = self.root / gate.POLICY_TEST_PRIMARY_RELATIVE
        policy_xml(path, tests=5, skipped=1)
        result = gate.parse_junit_xml(path)
        self.assertEqual(result["status"], gate.UNVERIFIED)
        self.assertEqual(result["skipped"], 1)

    def test_policy_test_pass_is_passed(self) -> None:
        path = self.root / gate.POLICY_TEST_PRIMARY_RELATIVE
        policy_xml(path, tests=5)
        result = gate.parse_junit_xml(path)
        self.assertEqual(result["status"], gate.PASSED)
        self.assertEqual(result["tests"], 5)
        self.assertEqual(result["failures"], 0)
        self.assertEqual(result["errors"], 0)
        self.assertEqual(result["skipped"], 0)

    def test_policy_test_priority_prefers_debug_result_dir(self) -> None:
        primary = self.root / gate.POLICY_TEST_PRIMARY_RELATIVE
        fallback = (
            self.root
            / "artifacts/architecture/20261017-example/full-app-rerun/xml"
            / gate.POLICY_TEST_FILENAME
        )
        policy_xml(primary, tests=5)
        policy_xml(fallback, tests=1)
        selected, _ = gate.select_policy_test_xml(self.root)
        self.assertEqual(selected, primary)

    def test_contract_model_evidence_is_annotation_only(self) -> None:
        report = self.root / gate.CONTRACT_MODEL_REPORT_RELATIVE
        write_json(
            report,
            {
                "evidence_scope": "contract_model_only",
                "evidence_status": "EXECUTED",
                "total_scenarios": 22,
                "contract_model_scenarios": 22,
                "production_injection_scenarios": 0,
                "device_verified_scenarios": 0,
                "verified_scenarios": 22,
            },
        )
        result = gate.collect_contract_model_evidence(self.root)
        self.assertEqual(result["status"], "ANNOTATION_ONLY")
        self.assertFalse(result["counts_toward_production_verified"])
        self.assertFalse(result["production_verified"])
        self.assertEqual(result["gate_impact"], "NONE")
        self.assertFalse(result["invoked"])

    def test_artifact_report_must_be_apk_bound(self) -> None:
        apk_sha = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        report = self.root / "artifacts/rc/final/build-provenance.json"
        write_json(
            report,
            {"status": "PASSED", "apk_sha256": apk_sha},
        )
        passed = gate.evaluate_artifact_report(report, apk_sha)
        self.assertEqual(passed["status"], gate.PASSED)

        write_json(
            report,
            {"status": "PASSED", "apk_sha256": "0" * 64},
        )
        stale = gate.evaluate_artifact_report(report, apk_sha)
        self.assertEqual(stale["status"], gate.FAILED)

    def test_artifact_failed_check_overrides_top_level_pass(self) -> None:
        apk_sha = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        report = self.root / "artifacts/rc/final/reproducible-build-report.json"
        write_json(
            report,
            {
                "status": "PASSED",
                "apk_sha256": apk_sha,
                "checks": {"second_clean_build": {"status": "FAILED"}},
            },
        )
        result = gate.evaluate_artifact_report(report, apk_sha)
        self.assertEqual(result["status"], gate.FAILED)

    def test_environment_override_and_explicit_override(self) -> None:
        env_dir = gate.resolve_runtime_evidence_dir(
            None,
            repo_root=self.root,
            environ={"MCA_RUNTIME_EVIDENCE_DIR": "custom/runtime"},
        )
        self.assertEqual(env_dir, (self.root / "custom/runtime").resolve())

        explicit_dir = gate.resolve_runtime_evidence_dir(
            "explicit/runtime",
            repo_root=self.root,
            environ={"MCA_RUNTIME_EVIDENCE_DIR": "custom/runtime"},
        )
        self.assertEqual(explicit_dir, (self.root / "explicit/runtime").resolve())

    def make_strict_evidence(
        self,
        *,
        apk_sha: Optional[str] = None,
        device_count: int = 2,
        serials: Optional[List[str]] = None,
        device_shas: Optional[List[str]] = None,
        soak_status: str = "PASSED",
        formal_gate_eligible: bool = True,
        fault_status: str = "VERIFIED",
    ) -> None:
        apk_sha = apk_sha or hashlib.sha256(self.apk.read_bytes()).hexdigest()
        serials = serials or [f"serial-{index + 1}" for index in range(device_count)]
        device_shas = device_shas or [apk_sha] * device_count
        self.evidence_dir.mkdir(parents=True, exist_ok=True)
        write_json(
            self.evidence_dir / "soak-report.json",
            {
                "schema": gate.RUNTIME_EVIDENCE_SCHEMA,
                "report_type": "soak",
                "status": soak_status,
                "formal_gate_eligible": formal_gate_eligible,
                "apk_sha256": apk_sha,
            },
        )
        write_json(
            self.evidence_dir / "fault-report.json",
            {
                "schema": gate.RUNTIME_EVIDENCE_SCHEMA,
                "report_type": "fault",
                "status": fault_status,
                "apk_sha256": apk_sha,
                "dimensions": {
                    "PRODUCTION_INJECTION": {"count": 1},
                    "DEVICE_EXECUTION": {"count": 1},
                },
                "mandatory_scenarios": [
                    {
                        "id": "fi-01",
                        "status": "VERIFIED",
                        "dimensions": ["MODEL_TEST", "PRODUCTION_INJECTION", "DEVICE_EXECUTION"],
                        "device": {
                            "serial": serials[0] if serials else "serial-1",
                            "physical": True,
                            "abi": "arm64-v8a",
                        },
                        "evidence": {"path": "raw/fi-01.json"},
                    }
                ],
            },
        )
        for index in range(device_count):
            serial = serials[index] if index < len(serials) else f"serial-{index + 1}"
            declared_sha = (
                device_shas[index] if index < len(device_shas) else apk_sha
            )
            write_json(
                self.evidence_dir / f"device-{index + 1}.json",
                {
                    "schema": gate.RUNTIME_EVIDENCE_SCHEMA,
                    "report_type": "device",
                    "serial": serial,
                    "apk_sha256": declared_sha,
                    "installed_sha256": declared_sha,
                    "device": {
                        "serial": serial,
                        "physical": True,
                        "abi": "arm64-v8a",
                        "manufacturer": "Synthetic",
                        "model": f"Phone-{index + 1}",
                    },
                },
            )

    def test_release_integrity_command_passes_evidence_dir_apk_and_sha(self) -> None:
        apk_sha = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        command = gate.build_release_integrity_command(
            self.root,
            self.evidence_dir,
            {"path": str(self.apk), "sha256": apk_sha},
        )
        self.assertEqual(
            command[command.index("--runtime-evidence-dir") + 1],
            str(self.evidence_dir),
        )
        self.assertEqual(command[command.index("--apk") + 1], str(self.apk))
        self.assertEqual(
            command[command.index("--expected-apk-sha256") + 1], apk_sha
        )

    def test_expected_apk_sha_is_forwarded_to_runtime_validator(self) -> None:
        self.make_evidence(device_count=2)
        apk_sha = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        runner = FakeRunner(0, validator_payload("PASS", 0))
        result = gate.verify_runtime_evidence(
            self.root,
            self.evidence_dir,
            release_apk=self.apk,
            validator_path=self.validator,
            runner=runner,
            expected_apk_sha256=apk_sha,
        )
        self.assertEqual(result["status"], gate.PASSED)
        command = runner.calls[0]
        self.assertEqual(
            command[command.index("--expected-apk-sha256") + 1], apk_sha
        )

    def test_canonical_apk_rejects_an_expected_sha_mismatch(self) -> None:
        apk_sha = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        matched = gate.resolve_canonical_apk(self.root, str(self.apk), apk_sha)
        self.assertEqual(matched["status"], gate.PASSED)
        self.assertEqual(matched["sha256"], apk_sha)

        mismatch = gate.resolve_canonical_apk(self.root, str(self.apk), "0" * 64)
        self.assertEqual(mismatch["status"], gate.FAILED)
        invalid = gate.resolve_canonical_apk(self.root, str(self.apk), "not-a-sha")
        self.assertEqual(invalid["status"], gate.FAILED)

    def test_missing_apksigner_is_explicitly_unverified(self) -> None:
        discovery = gate.discover_apksigner(
            self.root,
            environ={"PATH": "", "APKSIGNER": "", "ANDROID_HOME": "", "ANDROID_SDK_ROOT": ""},
            search_common=False,
        )
        self.assertEqual(discovery["status"], gate.UNVERIFIED)
        signed = gate.evaluate_signature_check(
            {"validator_output": validator_payload("PASS", 0)}, discovery
        )
        self.assertEqual(signed["status"], gate.UNVERIFIED)

    def test_artifact_checks_cannot_pass_signature_without_apksigner(self) -> None:
        apk_sha = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        apk_bytes = self.apk.stat().st_size
        write_json(
            self.evidence_dir / "reproducibility-report.json",
            {
                "status": "PASSED",
                "apk_sha256": apk_sha,
                "apk_bytes": apk_bytes,
                "equal": True,
                "builds": [
                    {"sha256": apk_sha, "bytes": apk_bytes},
                    {"sha256": apk_sha, "bytes": apk_bytes},
                ],
            },
        )
        write_json(
            self.evidence_dir / "build-provenance.json",
            {"status": "PASSED", "apk_sha256": apk_sha},
        )
        missing_signer = gate.discover_apksigner(
            self.root,
            environ={"PATH": "", "APKSIGNER": "", "ANDROID_HOME": "", "ANDROID_SDK_ROOT": ""},
            search_common=False,
        )
        checks = gate.evaluate_release_artifact_checks(
            self.root,
            self.evidence_dir,
            self.apk,
            {"validator_output": validator_payload("PASS", 0)},
            canonical_apk={
                "status": gate.PASSED,
                "sha256": apk_sha,
                "bytes": apk_bytes,
            },
            signature_tool=missing_signer,
        )
        self.assertEqual(checks["signed"]["status"], gate.UNVERIFIED)

    def test_provenance_sdk_discovers_apksigner_and_injects_env(self) -> None:
        sdk = self.root / "provenance-sdk"
        signer = sdk / "build-tools" / "36.0.0" / (
            "apksigner.bat" if gate.os.name == "nt" else "apksigner"
        )
        signer.parent.mkdir(parents=True, exist_ok=True)
        signer.write_text("synthetic signer\n", encoding="utf-8")
        provenance = self.root / "build-provenance.json"
        write_json(provenance, {"toolchain": {"androidSdkRoot": str(sdk)}})
        discovery = gate.discover_apksigner(
            self.root,
            provenance,
            environ={"PATH": "", "APKSIGNER": ""},
            search_common=False,
        )
        self.assertEqual(discovery["status"], "AVAILABLE")
        self.assertEqual(discovery["environment"]["APKSIGNER"], str(signer.resolve()))
        self.assertEqual(discovery["environment"]["ANDROID_HOME"], str(sdk.resolve()))

        self.make_evidence(device_count=2)
        runner = FakeRunner(0, validator_payload("PASS", 0))
        gate.verify_runtime_evidence(
            self.root,
            self.evidence_dir,
            release_apk=self.apk,
            validator_path=self.validator,
            runner=runner,
            expected_apk_sha256=hashlib.sha256(self.apk.read_bytes()).hexdigest(),
            extra_env=discovery["environment"],
        )
        self.assertEqual(
            runner.kwargs[0]["env"]["APKSIGNER"], str(signer.resolve())
        )
        self.assertEqual(
            runner.kwargs[0]["env"]["ANDROID_HOME"], str(sdk.resolve())
        )

    def test_runtime_contract_accepts_two_unique_physical_arm64_devices(self) -> None:
        apk_sha = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        apk_bytes = self.apk.stat().st_size
        self.make_strict_evidence(apk_sha=apk_sha)
        payload = validator_payload(
            "PASS",
            0,
            computed={"devices": {"serials": ["serial-1", "serial-2"]}},
        )
        result = gate.evaluate_runtime_evidence_contract(
            self.evidence_dir, apk_sha, apk_bytes, payload
        )
        self.assertEqual(result["status"], gate.PASSED, result["reason"])

    def test_runtime_contract_rejects_missing_second_device(self) -> None:
        apk_sha = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        apk_bytes = self.apk.stat().st_size
        self.make_strict_evidence(apk_sha=apk_sha, device_count=1)
        result = gate.evaluate_runtime_evidence_contract(
            self.evidence_dir, apk_sha, apk_bytes
        )
        self.assertEqual(result["status"], gate.FAILED)
        self.assertIn("device.report_count", result["failed_checks"])

    def test_runtime_contract_rejects_different_device_sha(self) -> None:
        apk_sha = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        apk_bytes = self.apk.stat().st_size
        self.make_strict_evidence(
            apk_sha=apk_sha, device_shas=[apk_sha, "f" * 64]
        )
        result = gate.evaluate_runtime_evidence_contract(
            self.evidence_dir, apk_sha, apk_bytes
        )
        self.assertEqual(result["status"], gate.FAILED)
        self.assertIn("device.same_apk_sha256", result["failed_checks"])

    def test_runtime_contract_rejects_duplicate_serial_and_short_validator_set(self) -> None:
        apk_sha = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        apk_bytes = self.apk.stat().st_size
        self.make_strict_evidence(
            apk_sha=apk_sha, serials=["same-serial", "same-serial"]
        )
        duplicate = gate.evaluate_runtime_evidence_contract(
            self.evidence_dir, apk_sha, apk_bytes
        )
        self.assertEqual(duplicate["status"], gate.FAILED)
        self.assertIn("device.unique_physical_arm64", duplicate["failed_checks"])

        self.make_strict_evidence(apk_sha=apk_sha)
        payload = validator_payload(
            "PASS", 0, computed={"devices": {"serials": ["serial-1"]}}
        )
        short_output = gate.evaluate_runtime_evidence_contract(
            self.evidence_dir, apk_sha, apk_bytes, payload
        )
        self.assertEqual(short_output["status"], gate.FAILED)
        self.assertIn("device.validator_output_serials", short_output["failed_checks"])

    def test_runtime_contract_requires_soak_and_fault_terminal_status(self) -> None:
        apk_sha = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        apk_bytes = self.apk.stat().st_size
        self.make_strict_evidence(
            apk_sha=apk_sha, soak_status="FAILED", formal_gate_eligible=False
        )
        soak_result = gate.evaluate_runtime_evidence_contract(
            self.evidence_dir, apk_sha, apk_bytes
        )
        self.assertEqual(soak_result["status"], gate.FAILED)
        self.assertIn("soak.terminal_status", soak_result["failed_checks"])

        self.make_strict_evidence(apk_sha=apk_sha, fault_status="FAILED")
        fault_result = gate.evaluate_runtime_evidence_contract(
            self.evidence_dir, apk_sha, apk_bytes
        )
        self.assertEqual(fault_result["status"], gate.FAILED)
        self.assertIn("fault.status", fault_result["failed_checks"])

    def test_reproducibility_requires_equal_and_two_apk_bound_builds(self) -> None:
        apk_sha = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        apk_bytes = self.apk.stat().st_size
        report = self.root / "reproducibility-report.json"
        valid = {
            "status": "PASSED",
            "apk_sha256": apk_sha,
            "apk_bytes": apk_bytes,
            "equal": True,
            "builds": [
                {"sha256": apk_sha, "bytes": apk_bytes},
                {"sha256": apk_sha, "bytes": apk_bytes},
            ],
        }
        write_json(report, valid)
        passed = gate.evaluate_reproducibility_report(report, apk_sha, apk_bytes)
        self.assertEqual(passed["status"], gate.PASSED, passed["reason"])

        valid["equal"] = False
        write_json(report, valid)
        unequal = gate.evaluate_reproducibility_report(report, apk_sha, apk_bytes)
        self.assertEqual(unequal["status"], gate.FAILED)

        valid["equal"] = True
        valid["builds"] = valid["builds"][:1]
        write_json(report, valid)
        missing_build = gate.evaluate_reproducibility_report(
            report, apk_sha, apk_bytes
        )
        self.assertEqual(missing_build["status"], gate.FAILED)

        valid["builds"] = [
            {"sha256": apk_sha, "bytes": apk_bytes},
            {"sha256": apk_sha, "bytes": apk_bytes + 1},
        ]
        write_json(report, valid)
        drifted_bytes = gate.evaluate_reproducibility_report(
            report, apk_sha, apk_bytes
        )
        self.assertEqual(drifted_bytes["status"], gate.FAILED)

    def test_ratings_never_pass_sr_or_so_without_runtime_validator(self) -> None:
        policy = {"status": gate.PASSED, "tests": 5}
        runtime = {"status": gate.UNVERIFIED, "reason": "no APK"}
        artifacts = {
            "signed": {"status": gate.PASSED},
            "reproducible": {"status": gate.PASSED},
            "provenance": {"status": gate.PASSED},
        }
        ratings = gate.derive_rating_statuses(
            {
                "architecture_rules": gate.PASSED,
                "source_level_s_c": gate.PASSED,
                "release_integrity": gate.PASSED,
            },
            policy,
            runtime,
            artifacts,
            viewmodel_budget_passed=True,
        )
        self.assertTrue(ratings["passed"]["S-C"])
        self.assertFalse(ratings["passed"]["S-R"])
        self.assertFalse(ratings["passed"]["S-O"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
