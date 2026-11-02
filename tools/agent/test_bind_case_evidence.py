#!/usr/bin/env python3
"""Tests for tools/agent/bind_case_evidence.py and the CURRENT header in parity_cases.py.

Run from the repository root:

    python -m unittest discover -s tools/agent -p "test_bind_case_evidence.py" -v

The tests copy the real 121-case registry into a temporary repository layout, so they exercise the
real case/layer requirements without mutating the working registry or status Markdown.
"""

from __future__ import annotations

import json
import shutil
import tempfile
import unittest
from pathlib import Path

import bind_case_evidence
import parity_cases

REPO = Path(__file__).resolve().parents[2]
REAL_REGISTRY = REPO / "artifacts/agent-native-compat/parity-cases.json"
HEAD = "b" * 40
FINGERPRINT = "a" * 64


class BindCaseEvidenceTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.repo = Path(self._tmp.name)
        self.out = self.repo / "artifacts/agent-native-compat"
        self.out.mkdir(parents=True)
        shutil.copyfile(REAL_REGISTRY, self.out / "parity-cases.json")
        self.snapshot = {
            "schemaVersion": "mca.current-snapshot/1",
            "head": HEAD,
            "fingerprint": FINGERPRINT,
            "generatedAt": "2026-10-26T12:00:00Z",
            "definition": "test CURRENT definition",
        }
        (self.out / "current-snapshot.json").write_text(
            json.dumps(self.snapshot, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        self.before = json.loads((self.out / "parity-cases.json").read_text(encoding="utf-8"))

    def tearDown(self) -> None:
        self._tmp.cleanup()

    # -- helpers ---------------------------------------------------------
    def _bind(self, **overrides):
        values = {
            "case_id": "WF-01",
            "status": "PASS",
            "fingerprint": FINGERPRINT,
            "head": HEAD,
            "command": "python -m unittest (test evidence)",
            "executed_tests": 5,
            "exit_code": 0,
            "layers": "U/I/D",
            "evidence_refs": ["artifacts/agent-native-compat/verify-20261026/test.log"],
            "issue": None,
        }
        values.update(overrides)
        return bind_case_evidence.bind_evidence(self.repo, **values)

    def _registry(self) -> dict:
        return json.loads((self.out / "parity-cases.json").read_text(encoding="utf-8"))

    def _case(self, case_id: str) -> dict:
        return next(item for item in self._registry()["cases"] if item["caseId"] == case_id)

    # -- PASS rejection rules -------------------------------------------
    def test_pass_rejected_without_fingerprint(self) -> None:
        with self.assertRaises(bind_case_evidence.EvidenceError):
            self._bind(fingerprint=None)

    def test_pass_rejected_with_malformed_fingerprint(self) -> None:
        with self.assertRaises(bind_case_evidence.EvidenceError):
            self._bind(fingerprint="not-a-fingerprint")

    def test_pass_rejected_with_stale_fingerprint(self) -> None:
        with self.assertRaises(bind_case_evidence.EvidenceError):
            self._bind(fingerprint="c" * 64)

    def test_pass_rejected_without_snapshot(self) -> None:
        (self.out / "current-snapshot.json").unlink()
        with self.assertRaises(bind_case_evidence.EvidenceError):
            self._bind()

    def test_pass_rejected_with_zero_tests(self) -> None:
        with self.assertRaises(bind_case_evidence.EvidenceError):
            self._bind(executed_tests=0)

    def test_pass_rejected_with_nonzero_exit(self) -> None:
        with self.assertRaises(bind_case_evidence.EvidenceError):
            self._bind(exit_code=3)

    def test_pass_rejected_when_layers_are_strict_subset(self) -> None:
        # TOOL-04 requires U/I/F; U/I is a strict subset and must be refused.
        with self.assertRaises(bind_case_evidence.EvidenceError):
            self._bind(case_id="TOOL-04", layers="U/I")

    def test_pass_rejected_with_unknown_layer(self) -> None:
        with self.assertRaises(bind_case_evidence.EvidenceError):
            self._bind(layers="U/I/X")

    # -- other status rules ---------------------------------------------
    def test_blocked_requires_issue(self) -> None:
        with self.assertRaises(bind_case_evidence.EvidenceError):
            self._bind(status="BLOCKED", fingerprint=None, executed_tests=0, layers=None, issue=None)

    def test_blocked_accepted_with_issue_and_keeps_historical_status(self) -> None:
        self._bind(
            case_id="TOOL-19",
            status="BLOCKED",
            fingerprint=None,
            executed_tests=0,
            layers=None,
            issue="artifacts/agent-native-compat/ISSUES.md#device-01",
        )
        case = self._case("TOOL-19")
        self.assertNotEqual(case["status"], "BLOCKED")  # historical status was NOT_RUN and stays so
        self.assertEqual(case["currentStatus"], "BLOCKED")
        self.assertEqual(case["currentEvidence"]["issue"], "artifacts/agent-native-compat/ISSUES.md#device-01")

    # -- core separation / persistence ----------------------------------
    def test_pass_writes_current_fields_without_touching_historical_status(self) -> None:
        before = {item["caseId"]: item.get("status") for item in self.before["cases"]}
        record_path = self._bind(case_id="WF-01", layers="U/I/D")
        after_payload = self._registry()
        after = {item["caseId"]: item.get("status") for item in after_payload["cases"]}
        self.assertEqual(before, after)  # every historical status byte-for-value unchanged
        case = self._case("WF-01")
        self.assertEqual(case["status"], "NOT_RUN")  # historical field untouched
        self.assertEqual(case["currentStatus"], "PASS")
        self.assertEqual(case["currentEvidence"]["fingerprint"], FINGERPRINT)
        self.assertEqual(case["currentEvidence"]["recordPath"], record_path.relative_to(self.repo).as_posix())
        self.assertTrue(any(ref.startswith("current:") for ref in case["evidenceRefs"]))
        self.assertEqual(len(after_payload["cases"]), 121)

        record = json.loads(record_path.read_text(encoding="utf-8"))
        self.assertEqual(record["caseId"], "WF-01")
        self.assertEqual(record["fingerprint"], FINGERPRINT)
        self.assertEqual(record["executedTests"], 5)
        self.assertEqual(record["exitCode"], 0)
        self.assertEqual(record["requiredLayers"], ["D", "I", "U"])
        self.assertTrue(record["coversRequiredLayers"])

    def test_markdown_renders_separated_historical_and_current_columns(self) -> None:
        self._bind(case_id="WF-01", layers="U/I/D")
        markdown = (self.out / "ACCEPTANCE_STATUS.md").read_text(encoding="utf-8")
        self.assertIn("HISTORICAL vs CURRENT", markdown)
        self.assertIn(FINGERPRINT, markdown)
        self.assertIn("| ID | 层 | 卡 | 历史状态 | CURRENT | 尝试 | 证据 |", markdown)
        row = next(line for line in markdown.splitlines() if line.startswith("| WF-01 |"))
        cells = [cell.strip() for cell in row.strip().strip("|").split("|")]
        self.assertEqual(cells[3], "NOT_RUN")  # historical
        self.assertEqual(cells[4], "PASS")  # current (bound to snapshot)
        self.assertIn("current:", " ".join(cells))

    def test_regeneration_preserves_current_binding_and_historical_status(self) -> None:
        self._bind(case_id="WF-01", layers="U/I/D")
        parsed = parity_cases.parse_cases()
        merged = parity_cases.merge_existing(parsed, self.out / "parity-cases.json")
        self.assertEqual(len(merged), 121)
        case = next(item for item in merged if item["caseId"] == "WF-01")
        self.assertEqual(case["status"], "NOT_RUN")
        self.assertEqual(case["currentStatus"], "PASS")
        self.assertTrue(case["currentEvidence"]["recordPath"].startswith("artifacts/agent-native-compat/evidence/"))
        markdown = parity_cases.render_markdown(merged, self.snapshot)
        self.assertIn("HISTORICAL vs CURRENT", markdown)
        self.assertIn("当前计数", markdown)

    def test_regeneration_preserves_all_118_historical_evidence_refs(self) -> None:
        before_refs = sum(len(case.get("evidenceRefs", [])) for case in self.before["cases"])
        self.assertEqual(before_refs, 118)
        parsed = parity_cases.parse_cases()
        merged = parity_cases.merge_existing(parsed, self.out / "parity-cases.json")
        after_refs = sum(len(case.get("evidenceRefs", [])) for case in merged)
        self.assertEqual(after_refs, 118)

    def test_header_absent_snapshot_renders_warning(self) -> None:
        markdown = parity_cases.render_markdown(self.before["cases"], None)
        self.assertIn("CURRENT 未绑定", markdown)
        self.assertIn("NOT_RUN", markdown)


if __name__ == "__main__":
    unittest.main()
