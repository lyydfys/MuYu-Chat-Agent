#!/usr/bin/env python3
"""Self-tests for verify_runtime_evidence.py.

All fixtures are synthetic and live in a temporary directory. The APK is a
minimal ZIP with a real ELF64 AArch64 header; it is not a distributable APK.
A fake apksigner is placed first on PATH so the signature check is exercised
without relying on a local Android SDK.
"""

from __future__ import annotations

import copy
import hashlib
import json
import os
import stat
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from typing import Any, Dict, List


VALIDATOR = Path(__file__).with_name("verify_runtime_evidence.py")
REQUIRED_SCENARIOS = (
    "CANCEL_RETRY",
    "CLIENT_DISCONNECT",
    "WORKER_DEATH",
    "MAIN_PROCESS_KILL_COLD_START",
    "LOAD_UNLOAD_UI",
    "BACKGROUND_FOREGROUND",
    "AUTH_REJECTION",
)


def sha256_bytes(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def sha256_path(path: Path) -> str:
    return sha256_bytes(path.read_bytes())


def minimal_arm64_elf(*, file_backed: bool = True) -> bytes:
    ident = bytearray(16)
    ident[:4] = b"\x7fELF"
    ident[4] = 2  # ELFCLASS64
    ident[5] = 1  # little endian
    ident[6] = 1  # EV_CURRENT
    header = struct.pack(
        "<16sHHIQQQIHHHHHH",
        bytes(ident),
        3,       # ET_DYN
        183,     # EM_AARCH64
        1,       # EV_CURRENT
        0,       # entry
        64,      # program-header offset
        0,       # section-header offset
        0,       # flags
        64,      # ELF header size
        56,      # program-header entry size
        1,       # one program header
        0,
        0,
        0,
    )
    payload = b"synthetic-aarch64-load\0" if file_backed else b""
    file_size = 64 + 56 + len(payload) if file_backed else 0
    program_header = struct.pack(
        "<IIQQQQQQ",
        1,       # PT_LOAD
        5,       # R+X
        0,       # p_offset
        0,       # p_vaddr
        0,       # p_paddr
        file_size,
        file_size,
        0x4000,  # p_align, 16 KiB
    )
    return header + program_header + payload


def minimal_hexagon_elf() -> bytes:
    ident = bytearray(16)
    ident[:4] = b"\x7fELF"
    ident[4] = 1  # ELFCLASS32
    ident[5] = 1  # little endian
    ident[6] = 1
    header = struct.pack(
        "<16sHHIIIIIHHHHHH",
        bytes(ident),
        3,       # ET_DYN
        164,     # EM_HEXAGON
        1,
        0,
        52,      # program-header offset
        0,
        0,
        52,      # ELF header size
        32,      # program-header entry size
        1,
        0,
        0,
        0,
    )
    payload = b"synthetic-hexagon-load\0"
    file_size = 52 + 32 + len(payload)
    program_header = struct.pack(
        "<IIIIIIII",
        1, 0, 0, 0, file_size, file_size + 16, 5, 0x1000,
    )
    return header + program_header + payload


def descriptor(root: Path, path: Path) -> Dict[str, Any]:
    relative = path.relative_to(root).as_posix()
    return {
        "path": relative,
        "sha256": sha256_path(path),
        "bytes": path.stat().st_size,
    }


class RuntimeEvidenceFixture(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory(prefix="mca-runtime-evidence-")
        self.root = Path(self._tmp.name)
        self.raw_dir = self.root / "raw"
        self.raw_dir.mkdir()
        self.bin_dir = self.root / "bin"
        self.bin_dir.mkdir()

        self.apk = self.root / "app-release.apk"
        with zipfile.ZipFile(self.apk, "w", compression=zipfile.ZIP_STORED) as archive:
            archive.writestr("AndroidManifest.xml", b"synthetic-manifest")
            archive.writestr("classes.dex", b"dex\n035\0synthetic")
            archive.writestr("lib/arm64-v8a/libmca_native.so", minimal_arm64_elf())
            archive.writestr("lib/arm64-v8a/libggml-htp-v79.so", minimal_hexagon_elf())
        self.apk_sha = sha256_path(self.apk)
        self.apk_bytes = self.apk.stat().st_size

        if os.name == "nt":
            signer = self.bin_dir / "apksigner.bat"
            signer.write_text(
                "@echo off\r\n"
                "echo Verified using v2 scheme (APK Signature Scheme v2): true\r\n"
                "exit /b 0\r\n",
                encoding="ascii",
            )
        else:
            signer = self.bin_dir / "apksigner"
            signer.write_text(
                "#!/bin/sh\n"
                "printf '%s\n' 'Verified using v2 scheme (APK Signature Scheme v2): true'\n"
                "exit 0\n",
                encoding="ascii",
            )
            signer.chmod(signer.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
        self.signer = signer

        self.soak_log = self.raw_dir / "soak.log"
        self.soak_log.write_text("synthetic soak log\n" * 20, encoding="utf-8")
        self.request_log = self.raw_dir / "requests.ndjson"
        self.request_log.write_text('{"request_id": "req-0000", "outcome": "success"}\n', encoding="utf-8")
        self.samples_log = self.raw_dir / "samples.ndjson"
        self.samples_log.write_text('{"type": "memory_sample", "pid": 1001}\n', encoding="utf-8")
        self.optional_empty_logs = [
            self.raw_dir / name
            for name in (
                "device-stderr.log",
                "crash-buffer.log",
                "anr-events.log",
                "runtime-errors.log",
            )
        ]
        for path in self.optional_empty_logs:
            path.write_bytes(b"")
        self.fault_logs: Dict[str, Path] = {}
        for scenario_id in REQUIRED_SCENARIOS:
            path = self.raw_dir / f"fault-{scenario_id.lower()}.json"
            path.write_text(json.dumps({"synthetic": scenario_id}) + "\n", encoding="utf-8")
            self.fault_logs[scenario_id] = path
        self.ui_log = self.raw_dir / "main-activity.json"
        self.ui_log.write_text('{"synthetic": "main-activity"}\n', encoding="utf-8")
        self.api_log = self.raw_dir / "local-api.json"
        self.api_log.write_text('{"synthetic": "local-api"}\n', encoding="utf-8")

        self.soak_path = self.root / "soak-report.json"
        self.fault_path = self.root / "fault-report.json"
        self.device_path = self.root / "device-1.json"
        self.device2_path = self.root / "device-2.json"
        self._write_json(self.soak_path, self.valid_soak_report())
        self._write_json(self.fault_path, self.valid_fault_report())
        self._write_json(self.device_path, self.valid_device_report("SERIAL-001", "Xiaomi 14 Pro"))
        self._write_json(self.device2_path, self.valid_device_report("SERIAL-002", "Pixel 9 Pro"))

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def _write_json(self, path: Path, payload: Dict[str, Any]) -> None:
        path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")

    def rebuild_apk(self, libraries: Dict[str, bytes]) -> None:
        with zipfile.ZipFile(self.apk, "w", compression=zipfile.ZIP_STORED) as archive:
            archive.writestr("AndroidManifest.xml", b"synthetic-manifest")
            archive.writestr("classes.dex", b"dex\n035\0synthetic")
            for name, payload in libraries.items():
                archive.writestr(f"lib/arm64-v8a/{name}", payload)
        self.apk_sha = sha256_path(self.apk)
        self.apk_bytes = self.apk.stat().st_size
        self._write_json(self.soak_path, self.valid_soak_report())
        self._write_json(self.fault_path, self.valid_fault_report())
        self._write_json(self.device_path, self.valid_device_report("SERIAL-001", "Xiaomi 14 Pro"))
        self._write_json(self.device2_path, self.valid_device_report("SERIAL-002", "Pixel 9 Pro"))

    def valid_soak_report(self) -> Dict[str, Any]:
        request_ids = [f"req-{index:04d}" for index in range(1200)]
        return {
            "schema": "mca-runtime-evidence/1.0",
            "report_type": "soak",
            "status": "PASSED",
            "campaign_kind": "FORMAL_RC",
            "formal_gate_eligible": True,
            "apk_sha256": self.apk_sha,
            "apk_size_bytes": self.apk_bytes,
            "started_at": "2026-10-01T00:00:00Z",
            "ended_at": "2026-10-02T00:10:00Z",
            "duration_hours": 24.1666666667,
            "warmup": {
                "completed": True,
                "requests": 50,
                "duration_seconds": 600,
            },
            "requests": {
                "planned": 1200,
                "attempted": 1200,
                "success": 1190,
                "failure": 8,
                "cancel": 2,
                "request_ids": request_ids,
                "unexplained_failures": 0,
            },
            "logical_counts": {
                "stream": 900,
                "nonstream": 300,
                "load_unload": 160,
                "cancel": 2,
                "retry": 8,
                "worker_death": 3,
                "restart": 3,
            },
            "coverage": {name: True for name in (
                "stream",
                "nonstream",
                "load_unload",
                "cancel",
                "retry",
                "worker_death",
                "restart",
            )},
            "memory_samples": [
                {
                    "timestamp": "2026-10-01T23:10:00Z",
                    "processes": [
                        {"name": "com.muyuchat.mca", "pid": 1001, "pss_bytes": 210000000},
                        {"name": "com.muyuchat.mca:chat_worker", "pid": 1002, "pss_bytes": 90000000},
                    ],
                    "rss_bytes": 350000000,
                    "native_heap_bytes": 64000000,
                    "fd_count": 42,
                    "thread_count": 37,
                },
                {
                    "timestamp": "2026-10-02T00:10:00Z",
                    "process_name": "com.muyuchat.mca",
                    "pid": 1001,
                    "pss_bytes": 211000000,
                    "rss_bytes": 351000000,
                    "native_heap_bytes": 64500000,
                    "fd_count": 43,
                    "thread_count": 37,
                },
            ],
            "raw_logs": [
                descriptor(self.root, self.soak_log),
                descriptor(self.root, self.request_log),
                descriptor(self.root, self.samples_log),
                *[descriptor(self.root, path) for path in self.optional_empty_logs],
            ],
            "errors": [],
        }

    def valid_fault_report(self) -> Dict[str, Any]:
        dimensions = ["MODEL_TEST", "PRODUCTION_INJECTION", "DEVICE_EXECUTION"]
        scenarios = []
        for scenario_id in REQUIRED_SCENARIOS:
            path = self.fault_logs[scenario_id]
            scenarios.append(
                {
                    "id": scenario_id,
                    "status": "VERIFIED",
                    "dimensions": list(dimensions),
                    "device": {"serial": "SERIAL-001", "physical": True, "abi": "arm64-v8a"},
                    "evidence": descriptor(self.root, path),
                }
            )
        return {
            "schema": "mca-runtime-evidence/1.0",
            "report_type": "fault",
            "status": "VERIFIED",
            "apk_sha256": self.apk_sha,
            "dimensions": {
                "MODEL_TEST": len(REQUIRED_SCENARIOS),
                "PRODUCTION_INJECTION": len(REQUIRED_SCENARIOS),
                "DEVICE_EXECUTION": len(REQUIRED_SCENARIOS),
            },
            "mandatory_scenarios": scenarios,
            "errors": [],
        }

    def valid_device_report(self, serial: str, model: str) -> Dict[str, Any]:
        return {
            "schema": "mca-runtime-evidence/1.0",
            "report_type": "device",
            "status": "PASSED",
            "serial": serial,
            "apk_sha256": self.apk_sha,
            "installed_sha256": self.apk_sha,
            "device": {
                "serial": serial,
                "physical": True,
                "abi": "arm64-v8a",
                "manufacturer": "Synthetic",
                "model": model,
            },
            "main_activity_ui": {
                "status": "PASSED",
                "activity": "com.muyuchat.mca/.MainActivity",
                "evidence": descriptor(self.root, self.ui_log),
            },
            "authenticated_local_api": {
                "status": "PASSED",
                "authenticated": True,
                "http_status": 200,
                "endpoint": "/v1/chat/completions",
                "request_id": f"api-{serial}",
                "evidence": descriptor(self.root, self.api_log),
            },
            "crash_count": 0,
            "anr_count": 0,
            "errors": [],
        }

    def run_validator(
        self,
        *,
        apk: Path | None = None,
        soak: Path | None = None,
        fault: Path | None = None,
        devices: List[Path] | None = None,
        extra_args: List[str] | None = None,
    ) -> tuple[subprocess.CompletedProcess[str], Dict[str, Any]]:
        environment = os.environ.copy()
        environment["PATH"] = str(self.bin_dir) + os.pathsep + environment.get("PATH", "")
        environment["PYTHONDONTWRITEBYTECODE"] = "1"
        environment.pop("APKSIGNER", None)
        command = [
            sys.executable,
            "-B",
            str(VALIDATOR),
            "--apk",
            str(apk or self.apk),
            "--soak-report",
            str(soak or self.soak_path),
            "--fault-report",
            str(fault or self.fault_path),
        ]
        for path in (devices if devices is not None else [self.device_path]):
            command.extend(["--device-report", str(path)])
        command.extend(extra_args or [])
        completed = subprocess.run(
            command,
            cwd=str(VALIDATOR.parent.parent.parent),
            capture_output=True,
            text=True,
            env=environment,
            timeout=60,
            check=False,
        )
        try:
            payload = json.loads(completed.stdout)
        except json.JSONDecodeError as exc:
            self.fail(
                f"validator did not emit JSON: {exc}\n"
                f"stdout={completed.stdout}\nstderr={completed.stderr}"
            )
        return completed, payload

    def assert_check_failed(self, payload: Dict[str, Any], name: str) -> None:
        self.assertIn(name, payload["checks"], payload.get("summary"))
        self.assertEqual(payload["checks"][name]["status"], "FAIL", payload["checks"][name])

    def test_01_synthetic_all_checks_pass(self) -> None:
        completed, payload = self.run_validator(devices=[self.device_path, self.device2_path])
        self.assertEqual(completed.returncode, 0, payload["summary"])
        self.assertEqual(payload["summary"]["status"], "PASS")
        self.assertEqual(payload["summary"]["counts"], {"PASS": len(payload["checks"]), "FAIL": 0, "UNVERIFIED": 0})
        self.assertTrue(all(item["status"] == "PASS" for item in payload["checks"].values()))
        self.assertEqual(payload["computed"]["soak"]["request_count"], 1200)
        self.assertEqual(payload["computed"]["devices"]["serials"], ["SERIAL-001", "SERIAL-002"])
        elf_check = payload["checks"]["apk.elf_arm64"]
        self.assertEqual(elf_check["status"], "PASS")
        self.assertEqual(elf_check["computed"]["hexagon_dsp_companion_count"], 1)

    def test_02_rejects_apk_hash_mismatch(self) -> None:
        soak = self.valid_soak_report()
        soak["apk_sha256"] = "0" * 64
        path = self.root / "soak-hash-mismatch.json"
        self._write_json(path, soak)
        completed, payload = self.run_validator(soak=path)
        self.assertEqual(completed.returncode, 1)
        self.assert_check_failed(payload, "soak.apk_sha256")

    def test_03_rejects_23_9_hour_soak(self) -> None:
        soak = self.valid_soak_report()
        soak["ended_at"] = "2026-10-01T23:54:00Z"
        soak.pop("duration_hours", None)
        soak["memory_samples"][0]["timestamp"] = "2026-10-01T23:00:00Z"
        soak["memory_samples"][1]["timestamp"] = "2026-10-01T23:54:00Z"
        path = self.root / "soak-23.9h.json"
        self._write_json(path, soak)
        completed, payload = self.run_validator(soak=path)
        self.assertEqual(completed.returncode, 1)
        self.assert_check_failed(payload, "soak.duration")
        self.assertLess(payload["checks"]["soak.duration"]["computed"]["duration_hours"], 24.0)

    def test_04_rejects_999_requests(self) -> None:
        soak = self.valid_soak_report()
        soak["requests"].update({"attempted": 999, "success": 990, "failure": 7, "cancel": 2})
        soak["requests"]["request_ids"] = [f"req-{index:04d}" for index in range(999)]
        soak["logical_counts"]["stream"] = 749
        soak["logical_counts"]["nonstream"] = 250
        path = self.root / "soak-999.json"
        self._write_json(path, soak)
        completed, payload = self.run_validator(soak=path)
        self.assertEqual(completed.returncode, 1)
        self.assert_check_failed(payload, "soak.request_counts")

    def test_05_rejects_fault_model_only(self) -> None:
        fault = self.valid_fault_report()
        fault["dimensions"] = {
            "MODEL_TEST": 2,
            "PRODUCTION_INJECTION": 0,
            "DEVICE_EXECUTION": 0,
        }
        for scenario in fault["mandatory_scenarios"]:
            scenario["dimensions"] = ["MODEL_TEST"]
        path = self.root / "fault-model-only.json"
        self._write_json(path, fault)
        completed, payload = self.run_validator(fault=path)
        self.assertEqual(completed.returncode, 1)
        self.assert_check_failed(payload, "fault.model_only_guard")
        self.assert_check_failed(payload, "fault.scenario[0].verification")

    def test_06_rejects_missing_raw_log(self) -> None:
        self.soak_log.unlink()
        completed, payload = self.run_validator()
        self.assertEqual(completed.returncode, 1)
        self.assert_check_failed(payload, "soak.raw_logs[0]")
        self.assert_check_failed(payload, "soak.raw_logs")

    def test_07_rejects_bad_raw_log_hash(self) -> None:
        soak = self.valid_soak_report()
        soak["raw_logs"][0]["sha256"] = "f" * 64
        path = self.root / "soak-bad-raw-hash.json"
        self._write_json(path, soak)
        completed, payload = self.run_validator(soak=path)
        self.assertEqual(completed.returncode, 1)
        self.assert_check_failed(payload, "soak.raw_logs[0]")
        self.assert_check_failed(payload, "soak.raw_logs")

    def test_08_rejects_duplicate_request_ids(self) -> None:
        soak = self.valid_soak_report()
        soak["requests"]["request_ids"][-1] = soak["requests"]["request_ids"][0]
        path = self.root / "soak-duplicate-ids.json"
        self._write_json(path, soak)
        completed, payload = self.run_validator(soak=path)
        self.assertEqual(completed.returncode, 1)
        self.assert_check_failed(payload, "soak.request_ids")
        self.assertGreater(payload["checks"]["soak.request_ids"]["computed"]["duplicates"], 0)

    def test_09_rejects_missing_final_24h_sample(self) -> None:
        soak = self.valid_soak_report()
        soak["memory_samples"][0]["timestamp"] = "2026-10-01T00:00:00Z"
        soak["memory_samples"][1]["timestamp"] = "2026-10-01T00:05:00Z"
        path = self.root / "soak-no-final-window-sample.json"
        self._write_json(path, soak)
        completed, payload = self.run_validator(soak=path)
        self.assertEqual(completed.returncode, 1)
        self.assert_check_failed(payload, "soak.memory_samples")
        self.assertEqual(payload["checks"]["soak.memory_samples"]["computed"]["samples_in_final_24h"], 0)

    def test_10_rejects_missing_memory_fields(self) -> None:
        soak = self.valid_soak_report()
        soak["memory_samples"][0].pop("native_heap_bytes")
        path = self.root / "soak-missing-memory-field.json"
        self._write_json(path, soak)
        completed, payload = self.run_validator(soak=path)
        self.assertEqual(completed.returncode, 1)
        self.assert_check_failed(payload, "soak.memory_samples")

    def test_11_rejects_coverage_flag_without_actual_count(self) -> None:
        soak = self.valid_soak_report()
        soak["logical_counts"]["worker_death"] = 0
        path = self.root / "soak-empty-worker-death.json"
        self._write_json(path, soak)
        completed, payload = self.run_validator(soak=path)
        self.assertEqual(completed.returncode, 1)
        self.assert_check_failed(payload, "soak.coverage")
        self.assertFalse(payload["checks"]["soak.coverage"]["computed"]["worker_death"]["pass"])

    def test_12_rejects_nonphysical_device_and_crash(self) -> None:
        device = self.valid_device_report("SERIAL-003", "Synthetic Phone")
        device["device"]["physical"] = False
        device["device"]["device_type"] = "PHYSICAL_ARM64"
        device["device"]["representativeDeviceType"] = "PHYSICAL_ARM64"
        device["crash_count"] = 1
        path = self.root / "device-bad.json"
        self._write_json(path, device)
        completed, payload = self.run_validator(devices=[path])
        self.assertEqual(completed.returncode, 1)
        self.assert_check_failed(payload, "device[0].identity")
        self.assert_check_failed(payload, "device.all_reports_physical_arm64")
        self.assert_check_failed(payload, "device[0].crash_anr")
        self.assert_check_failed(payload, "device[0].evidence")

    def test_13_rejects_second_device_with_different_sha(self) -> None:
        second = self.valid_device_report("SERIAL-004", "Synthetic Phone 2")
        second["apk_sha256"] = "1" * 64
        second["installed_sha256"] = "1" * 64
        path = self.root / "device-wrong-sha.json"
        self._write_json(path, second)
        completed, payload = self.run_validator(
            devices=[self.device_path, path], extra_args=["--min-devices", "2"]
        )
        self.assertEqual(completed.returncode, 1)
        self.assert_check_failed(payload, "device.same_apk_sha256")
        self.assert_check_failed(payload, "device[1].apk_sha256")

    def test_14_rejects_expected_apk_sha_mismatch(self) -> None:
        completed, payload = self.run_validator(extra_args=["--expected-apk-sha256", "2" * 64])
        self.assertEqual(completed.returncode, 1)
        self.assert_check_failed(payload, "apk.expected_sha256")

    def test_15_rejects_ui_evidence_hash_mismatch(self) -> None:
        device = self.valid_device_report("SERIAL-005", "Synthetic Phone 3")
        device["main_activity_ui"]["evidence"]["sha256"] = "3" * 64
        path = self.root / "device-bad-ui-hash.json"
        self._write_json(path, device)
        completed, payload = self.run_validator(devices=[path])
        self.assertEqual(completed.returncode, 1)
        self.assert_check_failed(payload, "device[0].main_activity_ui.evidence_file")
        self.assert_check_failed(payload, "device[0].main_activity_ui")

    def test_16_missing_devices_are_unverified_not_silently_passed(self) -> None:
        completed, payload = self.run_validator(devices=[])
        self.assertEqual(completed.returncode, 2)
        self.assertEqual(payload["summary"]["status"], "UNVERIFIED")
        self.assertEqual(payload["checks"]["device.set"]["status"], "UNVERIFIED")
        self.assertIn("device.set", payload["summary"]["unverified_checks"])

    def test_17_missing_request_ids_are_rejected(self) -> None:
        soak = self.valid_soak_report()
        soak["requests"].pop("request_ids")
        path = self.root / "soak-counts-only.json"
        self._write_json(path, soak)
        completed, payload = self.run_validator(soak=path)
        self.assertEqual(completed.returncode, 1)
        self.assert_check_failed(payload, "soak.request_ids")
        self.assertFalse(payload["checks"]["soak.request_ids"]["computed"]["ids_supplied"])

    def test_18_out_writes_the_same_verification_json(self) -> None:
        output = self.root / "verification.json"
        completed, payload = self.run_validator(extra_args=["--out", str(output)])
        self.assertEqual(completed.returncode, 0, payload["summary"])
        self.assertTrue(output.is_file())
        self.assertEqual(json.loads(output.read_text(encoding="utf-8")), payload)

    def test_19_zero_byte_optional_logs_pass_only_with_matching_empty_sha(self) -> None:
        self.assertTrue(all(path.stat().st_size == 0 for path in self.optional_empty_logs))
        completed, payload = self.run_validator()
        self.assertEqual(completed.returncode, 0, payload["summary"])
        self.assertEqual(payload["checks"]["soak.raw_logs"]["status"], "PASS")
        self.assertEqual(payload["checks"]["soak.core_raw_logs"]["status"], "PASS")
        self.assertEqual(
            set(payload["checks"]["soak.raw_logs"]["computed"]["optional_empty_logs"]),
            {path.name for path in self.optional_empty_logs},
        )

        soak = self.valid_soak_report()
        soak["raw_logs"][-1]["sha256"] = "f" * 64
        path = self.root / "soak-bad-empty-optional-sha.json"
        self._write_json(path, soak)
        completed, payload = self.run_validator(soak=path)
        self.assertEqual(completed.returncode, 1)
        self.assert_check_failed(payload, "soak.raw_logs[6]")
        self.assert_check_failed(payload, "soak.raw_logs")

    def test_20_all_empty_or_missing_core_raw_logs_fail(self) -> None:
        only_optional_empty = self.valid_soak_report()
        only_optional_empty["raw_logs"] = only_optional_empty["raw_logs"][3:]
        path = self.root / "soak-only-empty-optional-logs.json"
        self._write_json(path, only_optional_empty)
        completed, payload = self.run_validator(soak=path)
        self.assertEqual(completed.returncode, 1)
        self.assert_check_failed(payload, "soak.core_raw_logs")
        self.assertEqual(payload["checks"]["soak.raw_logs"]["status"], "PASS")

        empty_requests = self.raw_dir / "requests.empty.ndjson"
        empty_samples = self.raw_dir / "samples.empty.ndjson"
        empty_requests.write_bytes(b"")
        empty_samples.write_bytes(b"")
        empty_core = self.valid_soak_report()
        empty_core["raw_logs"] = [
            empty_core["raw_logs"][0],
            descriptor(self.root, empty_requests),
            descriptor(self.root, empty_samples),
            *empty_core["raw_logs"][3:],
        ]
        path = self.root / "soak-empty-core-logs.json"
        self._write_json(path, empty_core)
        completed, payload = self.run_validator(soak=path)
        self.assertEqual(completed.returncode, 1)
        self.assert_check_failed(payload, "soak.raw_logs[1]")
        self.assert_check_failed(payload, "soak.raw_logs[2]")
        self.assert_check_failed(payload, "soak.core_raw_logs")

    def test_21_malformed_hexagon_elf_is_not_exempted(self) -> None:
        self.rebuild_apk(
            {
                "libmca_native.so": minimal_arm64_elf(),
                "libggml-htp-v79.so": minimal_hexagon_elf()[:20],
            }
        )
        completed, payload = self.run_validator()
        self.assertEqual(completed.returncode, 1)
        self.assert_check_failed(payload, "apk.elf_arm64")
        computed = payload["checks"]["apk.elf_arm64"]["computed"]
        self.assertEqual(computed["hexagon_dsp_companion_count"], 1)
        self.assertEqual(computed["invalid_library_count"], 1)

    def test_22_aarch64_requires_a_nonzero_file_backed_load_segment(self) -> None:
        completed, payload = self.run_validator()
        self.assertEqual(completed.returncode, 0, payload["summary"])
        libraries = {
            Path(item["path"]).name: item
            for item in payload["checks"]["apk.elf_arm64"]["computed"]["libraries"]
        }
        native_loads = libraries["libmca_native.so"]["load_segments"]
        self.assertTrue(any(segment["p_filesz"] > 0 for segment in native_loads))
        self.assertTrue(any(segment["file_backed"] for segment in native_loads))

        self.rebuild_apk(
            {
                "libmca_native.so": minimal_arm64_elf(file_backed=False),
                "libggml-htp-v79.so": minimal_hexagon_elf(),
            }
        )
        completed, payload = self.run_validator()
        self.assertEqual(completed.returncode, 1)
        self.assert_check_failed(payload, "apk.elf_arm64")
        computed = payload["checks"]["apk.elf_arm64"]["computed"]
        native = next(
            item for item in computed["libraries"] if item["path"].endswith("libmca_native.so")
        )
        self.assertFalse(native["valid"])
        self.assertIn("non-zero file-backed PT_LOAD", native["error"])

        malformed_cases = {
            "memsz-smaller-than-filesz": lambda payload: struct.pack_into(
                "<Q", payload, 64 + 40, 1
            ),
            "file-range-outside": lambda payload: struct.pack_into(
                "<Q", payload, 64 + 8, len(payload) + 1
            ),
        }
        for name, mutate in malformed_cases.items():
            with self.subTest(name=name):
                payload = bytearray(minimal_arm64_elf())
                mutate(payload)
                self.rebuild_apk(
                    {
                        "libmca_native.so": bytes(payload),
                        "libggml-htp-v79.so": minimal_hexagon_elf(),
                    }
                )
                completed, payload_result = self.run_validator()
                self.assertEqual(completed.returncode, 1)
                self.assert_check_failed(payload_result, "apk.elf_arm64")

    def test_23_critical_aarch64_library_cannot_masquerade_as_hexagon(self) -> None:
        self.rebuild_apk(
            {
                "libmca_native.so": minimal_hexagon_elf(),
                "libdummy.so": minimal_arm64_elf(),
            }
        )
        completed, payload = self.run_validator()
        self.assertEqual(completed.returncode, 1)
        self.assert_check_failed(payload, "apk.elf_arm64")
        computed = payload["checks"]["apk.elf_arm64"]["computed"]
        self.assertEqual(computed["invalid_library_count"], 0)
        self.assertIn("libmca_native.so", computed["critical_aarch64_libraries"])

    def test_24_soak_top_level_status_errors_and_counter_drift_fail_closed(self) -> None:
        cases = {
            "status": lambda soak: soak.__setitem__("status", "FAILED"),
            "campaign": lambda soak: soak.__setitem__("campaign_kind", "PILOT"),
            "formal": lambda soak: soak.__setitem__("formal_gate_eligible", False),
            "errors": lambda soak: soak.__setitem__("errors", ["synthetic failure"]),
            "unexplained": lambda soak: soak["requests"].__setitem__("unexplained_failures", 1),
            "planned": lambda soak: soak["requests"].__setitem__("planned", 1199),
        }
        for name, mutate in cases.items():
            with self.subTest(name=name):
                soak = self.valid_soak_report()
                mutate(soak)
                path = self.root / f"soak-top-level-{name}.json"
                self._write_json(path, soak)
                completed, payload = self.run_validator(soak=path)
                self.assertEqual(completed.returncode, 1)
                self.assert_check_failed(payload, "soak.top_level")

    def test_25_fault_top_level_status_and_errors_fail_closed(self) -> None:
        cases = {
            "status": lambda fault: fault.__setitem__("status", "FAILED"),
            "errors": lambda fault: fault.__setitem__("errors", ["synthetic failure"]),
        }
        for name, mutate in cases.items():
            with self.subTest(name=name):
                fault = self.valid_fault_report()
                mutate(fault)
                path = self.root / f"fault-top-level-{name}.json"
                self._write_json(path, fault)
                completed, payload = self.run_validator(fault=path)
                self.assertEqual(completed.returncode, 1)
                self.assert_check_failed(payload, "fault.top_level")

    def test_26_device_top_level_status_and_errors_fail_closed(self) -> None:
        cases = {
            "status": lambda device: device.__setitem__("status", "FAILED"),
            "errors": lambda device: device.__setitem__("errors", ["synthetic failure"]),
        }
        for name, mutate in cases.items():
            with self.subTest(name=name):
                device = self.valid_device_report("SERIAL-TOP", "Top Level")
                mutate(device)
                path = self.root / f"device-top-level-{name}.json"
                self._write_json(path, device)
                completed, payload = self.run_validator(devices=[path])
                self.assertEqual(completed.returncode, 1)
                self.assert_check_failed(payload, "device[0].top_level")

    def test_27_missing_mandatory_fault_scenario_fails(self) -> None:
        fault = self.valid_fault_report()
        fault["mandatory_scenarios"] = [
            scenario
            for scenario in fault["mandatory_scenarios"]
            if scenario["id"] != "CLIENT_DISCONNECT"
        ]
        for dimension in fault["dimensions"]:
            fault["dimensions"][dimension] = len(fault["mandatory_scenarios"])
        path = self.root / "fault-missing-mandatory-scenario.json"
        self._write_json(path, fault)
        completed, payload = self.run_validator(fault=path)
        self.assertEqual(completed.returncode, 1)
        self.assert_check_failed(payload, "fault.required_scenarios")
        self.assertIn(
            "CLIENT_DISCONNECT",
            payload["checks"]["fault.required_scenarios"]["computed"]["missing"],
        )

    def test_28_device_serial_activity_and_endpoint_are_strict(self) -> None:
        cases = [
            (
                "serial-mismatch",
                lambda device: device.__setitem__("serial", "SERIAL-OTHER"),
                "device[0].serial_consistency",
            ),
            (
                "serial-missing",
                lambda device: device["device"].pop("serial"),
                "device[0].serial_consistency",
            ),
            (
                "activity",
                lambda device: device["main_activity_ui"].__setitem__(
                    "activity", "com.example.app/.MainActivity"
                ),
                "device[0].main_activity_ui",
            ),
            (
                "endpoint",
                lambda device: device["authenticated_local_api"].__setitem__(
                    "endpoint", "/v1/chat"
                ),
                "device[0].authenticated_local_api",
            ),
        ]
        for name, mutate, check_name in cases:
            with self.subTest(name=name):
                device = self.valid_device_report("SERIAL-STRICT", "Strict Device")
                mutate(device)
                path = self.root / f"device-strict-{name}.json"
                self._write_json(path, device)
                completed, payload = self.run_validator(devices=[path])
                self.assertEqual(completed.returncode, 1)
                self.assert_check_failed(payload, check_name)

    def test_29_fault_scenario_serial_must_match_verified_device_set(self) -> None:
        fault = self.valid_fault_report()
        fault["mandatory_scenarios"][0]["device"]["serial"] = "SERIAL-NOT-VERIFIED"
        path = self.root / "fault-unverified-device-serial.json"
        self._write_json(path, fault)
        completed, payload = self.run_validator(fault=path)
        self.assertEqual(completed.returncode, 1)
        self.assert_check_failed(payload, "fault.scenario[0].verification")
        self.assert_check_failed(payload, "fault.device_serial_binding")
        self.assert_check_failed(payload, "fault.evidence")

    def test_30_two_distinct_physical_devices_with_same_apk_pass_min_two(self) -> None:
        completed, payload = self.run_validator(
            devices=[self.device_path, self.device2_path],
            extra_args=["--min-devices", "2"],
        )
        self.assertEqual(completed.returncode, 0, payload["summary"])
        self.assertEqual(payload["checks"]["device.minimum_count"]["status"], "PASS")
        self.assertEqual(
            payload["checks"]["device.minimum_count"]["computed"]["verified_unique_serials"],
            ["SERIAL-001", "SERIAL-002"],
        )

    def test_31_one_device_fails_when_min_two_is_required(self) -> None:
        completed, payload = self.run_validator(
            devices=[self.device_path], extra_args=["--min-devices", "2"]
        )
        self.assertEqual(completed.returncode, 1)
        self.assert_check_failed(payload, "device.minimum_count")
        self.assertEqual(
            payload["checks"]["device.minimum_count"]["computed"]["verified_unique_count"], 1
        )

    def test_32_missing_second_device_report_fails(self) -> None:
        missing = self.root / "device-missing.json"
        completed, payload = self.run_validator(
            devices=[self.device_path, missing], extra_args=["--min-devices", "2"]
        )
        self.assertEqual(completed.returncode, 1)
        self.assert_check_failed(payload, "device[1].input")
        self.assert_check_failed(payload, "device.minimum_count")


if __name__ == "__main__":
    unittest.main(verbosity=2)
