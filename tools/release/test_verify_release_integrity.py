#!/usr/bin/env python3
"""Pure-Python tests for release integrity fault evidence handling."""

from __future__ import annotations

import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from typing import Any, Dict, List

MODULE_PATH = Path(__file__).with_name("verify_release_integrity.py")
SPEC = importlib.util.spec_from_file_location("verify_release_integrity", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
integrity = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(integrity)


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")


class ReleaseIntegrityTests(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory(prefix="mca-release-integrity-")
        self.root = Path(self._tmp.name)
        self.apk = self.root / "app-release.apk"
        self.apk.write_bytes(b"synthetic-release-apk")
        self.apk_sha = hashlib.sha256(self.apk.read_bytes()).hexdigest()

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def fault_report(self, production: Any = None, device: Any = None) -> Dict[str, Any]:
        if production is None:
            production = {"count": 2}
        if device is None:
            device = {"count": 2}
        scenarios: List[Dict[str, Any]] = []
        for index in range(2):
            scenarios.append(
                {
                    "id": f"fi-{index + 1:02d}",
                    "status": "VERIFIED",
                    "dimensions": [
                        "MODEL_TEST",
                        "PRODUCTION_INJECTION",
                        "DEVICE_EXECUTION",
                    ],
                    "device": {
                        "serial": f"serial-{index + 1}",
                        "physical": True,
                        "abi": "arm64-v8a",
                    },
                    "evidence": {"path": f"raw/fi-{index + 1:02d}.json"},
                }
            )
        return {
            "schema": integrity.RUNTIME_EVIDENCE_SCHEMA,
            "report_type": "fault",
            "status": "VERIFIED",
            "apk_sha256": self.apk_sha,
            "dimensions": {
                "MODEL_TEST": {"count": 2},
                "PRODUCTION_INJECTION": production,
                "DEVICE_EXECUTION": device,
            },
            "mandatory_scenarios": scenarios,
        }

    def test_dimension_count_accepts_integer_and_count_object(self) -> None:
        dimensions = {
            "PRODUCTION_INJECTION": {"count": 3},
            "DEVICE_EXECUTION": 2,
        }
        self.assertEqual(
            integrity._dimension_count(dimensions, "PRODUCTION_INJECTION"), 3
        )
        self.assertEqual(
            integrity._dimension_count(dimensions, "DEVICE_EXECUTION"), 2
        )

    def test_dimension_count_rejects_invalid_shapes_without_exception(self) -> None:
        invalid_values = [
            {"count": {"nested": 1}},
            {"count": None},
            {"count": -1},
            {"count": True},
            ["1"],
            "1",
            1.5,
            None,
        ]
        for value in invalid_values:
            with self.subTest(value=value):
                dimensions = {"PRODUCTION_INJECTION": value}
                self.assertIsNone(
                    integrity._dimension_count(dimensions, "PRODUCTION_INJECTION")
                )

    def test_fault_report_accepts_production_count_objects(self) -> None:
        path = self.root / "fault-report.json"
        write_json(path, self.fault_report())
        result = integrity.evaluate_fault_report(path, self.apk_sha)
        self.assertEqual(result["status"], "PASSED", result["reason"])
        self.assertTrue(result["status_ok"])
        self.assertTrue(result["apk_bound"])
        self.assertEqual(result["dimension_counts"]["PRODUCTION_INJECTION"], 2)

    def test_fault_report_requires_verified_status(self) -> None:
        report = self.fault_report()
        report["status"] = "FAILED"
        path = self.root / "fault-report.json"
        write_json(path, report)
        result = integrity.evaluate_fault_report(path, self.apk_sha)
        self.assertEqual(result["status"], "FAILED")
        self.assertIn("status must be VERIFIED", result["reason"])

    def test_invalid_fault_dimension_type_fails_closed_without_traceback(self) -> None:
        path = self.root / "fault-report.json"
        write_json(path, self.fault_report(production={"unexpected": [1, 2]}))
        result = integrity.evaluate_fault_report(path, self.apk_sha)
        self.assertEqual(result["status"], "FAILED")
        self.assertIn("counts", result["reason"])

    def test_canonical_apk_expected_sha_and_missing_apk_are_fail_closed(self) -> None:
        matched = integrity.resolve_canonical_apk(str(self.apk), self.apk_sha)
        self.assertEqual(matched["status"], "PASSED")
        self.assertEqual(matched["sha256"], self.apk_sha)

        mismatch = integrity.resolve_canonical_apk(str(self.apk), "0" * 64)
        self.assertEqual(mismatch["status"], "FAILED")
        self.assertIn("does not match", mismatch["reason"])

        malformed = integrity.resolve_canonical_apk(str(self.apk), "bad")
        self.assertEqual(malformed["status"], "FAILED")
        self.assertIn("64 hexadecimal", malformed["reason"])

        missing = integrity.resolve_canonical_apk(str(self.root / "missing.apk"))
        self.assertEqual(missing["status"], "FAILED")
        self.assertIsNone(missing["sha256"])

    def test_cli_accepts_runtime_evidence_dir_and_canonical_apk_options(self) -> None:
        args = integrity._build_parser().parse_args(
            [
                "--runtime-evidence-dir",
                "custom/runtime",
                "--apk",
                "custom/app.apk",
                "--expected-apk-sha256",
                self.apk_sha,
            ]
        )
        self.assertEqual(args.runtime_evidence_dir, "custom/runtime")
        self.assertEqual(args.apk, "custom/app.apk")
        self.assertEqual(args.expected_apk_sha256, self.apk_sha)

    def test_default_runtime_evidence_directory_remains_compatible(self) -> None:
        args = integrity._build_parser().parse_args([])
        self.assertIsNone(args.runtime_evidence_dir)
        self.assertEqual(
            integrity._repo_path(
                args.runtime_evidence_dir or integrity.DEFAULT_RUNTIME_EVIDENCE_DIR
            ),
            (integrity.REPO_ROOT / "artifacts/rc/final").resolve(),
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
