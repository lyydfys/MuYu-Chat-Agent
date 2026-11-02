#!/usr/bin/env python3
"""Record one acceptance case result in the machine-readable registry.

The registry is produced by `tools/agent/parity_cases.py`; this tool only updates the result
fields of an existing case, then rewrites the Markdown view through the same generator so the two
never drift.

Usage:
    python tools/agent/set_case_status.py WF-02 PASS \
        --evidence "app:testDebugUnitTest 18 tests exit=0" \
        --evidence "device AgentTaskAnchorTest 5 tests exit=0"
    python tools/agent/set_case_status.py MEM-02 BLOCKED --attempts 1 \
        --issue ISSUES.md#B06 --evidence "device install blocked: INSTALL_FAILED_UPDATE_INCOMPATIBLE"

Rules this tool enforces:
  - the case id must exist;
  - the status must be one of PASS/FAIL/NOT_RUN/BLOCKED/NOT_APPLICABLE;
  - PASS/FAIL require at least one evidence reference (no unsupported green light);
  - BLOCKED/FAIL require an issue reference.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import parity_cases

REPO = Path(__file__).resolve().parents[2]
REGISTRY = REPO / "artifacts/agent-native-compat/parity-cases.json"
MARKDOWN = REGISTRY.with_name("ACCEPTANCE_STATUS.md")
VALID_STATUS = {"PASS", "FAIL", "NOT_RUN", "BLOCKED", "NOT_APPLICABLE"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("case_id", help="acceptance id, e.g. WF-02")
    parser.add_argument("status", choices=sorted(VALID_STATUS))
    parser.add_argument("--evidence", action="append", default=[])
    parser.add_argument("--attempts", type=int, default=None)
    parser.add_argument("--issue", default=None, help="issue reference for FAIL/BLOCKED")
    parser.add_argument("--clear-evidence", action="store_true")
    args = parser.parse_args()

    if not REGISTRY.exists():
        raise SystemExit(f"{REGISTRY} is missing; run tools/agent/parity_cases.py first")
    payload = json.loads(REGISTRY.read_text(encoding="utf-8"))
    cases = payload.get("cases", [])
    case = next((item for item in cases if item.get("caseId") == args.case_id), None)
    if case is None:
        raise SystemExit(f"unknown acceptance case {args.case_id}")
    if args.status in {"PASS", "FAIL"} and not args.evidence and not args.clear_evidence:
        raise SystemExit(f"{args.status} requires at least one --evidence reference")
    if args.status in {"FAIL", "BLOCKED"} and not args.issue:
        raise SystemExit(f"{args.status} requires --issue so the failure stays traceable")

    case["status"] = args.status
    case["attempts"] = args.attempts if args.attempts is not None else int(case.get("attempts", 0))
    if args.clear_evidence:
        case["evidenceRefs"] = []
    case["evidenceRefs"] = sorted(set(case.get("evidenceRefs", [])) | set(args.evidence))
    case["issueRef"] = args.issue

    REGISTRY.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    ordered = sorted(cases, key=lambda item: parity_cases.case_sort_key(item["caseId"]))
    MARKDOWN.write_text(parity_cases.render_markdown(ordered), encoding="utf-8")
    print(f"{args.case_id}: {args.status} (attempts={case['attempts']}, evidence={len(case['evidenceRefs'])})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
