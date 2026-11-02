#!/usr/bin/env python3
"""Bind CURRENT acceptance evidence for one case without touching its historical status.

This is the tool-enforced half of the ledger contract:

  * historical `status` / `evidenceRefs` stay untouched (they are HISTORICAL provenance);
  * CURRENT evidence is written to its own `currentStatus` / `currentEvidence` fields and to a
    structured record under `artifacts/agent-native-compat/evidence/<caseId>/<timestamp>.json`;
  * a PASS is refused unless the evidence is anchored to the CURRENT snapshot fingerprint, has
    executed tests, a zero exit code, and covers every required test layer of the case.

Usage (one line; run from the repository root):

    python tools/agent/bind_case_evidence.py --case WF-01 --status PASS \
        --fingerprint <64-hex build-guard fingerprint> --head <40-hex HEAD> \
        --command "verify-code.ps1 -Tests '*WorkflowDefinition*Test' (exit 0)" \
        --executed-tests 12 --exit-code 0 --layers U/I/D \
        --evidence-ref artifacts/agent-native-compat/verify-20261026/workflow-definitions

    python tools/agent/bind_case_evidence.py --case TOOL-19 --status BLOCKED \
        --head <40-hex HEAD> --command "device stdio probe not attempted" \
        --issue artifacts/agent-native-compat/ISSUES.md#device-01

Rules this tool enforces:
  - PASS requires current-snapshot.json to exist and the supplied fingerprint to equal its
    fingerprint (run tools/build-guards/McaWorktreeFingerprint.ps1 first);
  - PASS requires executed-tests > 0, exit-code == 0, a non-empty command, and supplied layers
    that are a superset of the case's required layers from parity-cases.json;
  - FAIL / BLOCKED require --issue so the result stays traceable;
  - the historical status field is never written by this tool.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

try:
    import parity_cases
except ImportError:  # pragma: no cover - direct module load from the repository root
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import parity_cases

FINGERPRINT_RE = re.compile(r"^[0-9a-f]{64}$")
HEAD_RE = re.compile(r"^[0-9a-f]{40}$")
LAYER_RE = re.compile(r"^[UIDMFVOS]$")
VALID_STATUS = {"PASS", "FAIL", "NOT_RUN", "BLOCKED", "NOT_APPLICABLE"}

REGISTRY_REL = Path("artifacts/agent-native-compat/parity-cases.json")
STATUS_MD_REL = Path("artifacts/agent-native-compat/ACCEPTANCE_STATUS.md")
SNAPSHOT_REL = Path("artifacts/agent-native-compat/current-snapshot.json")
EVIDENCE_REL = Path("artifacts/agent-native-compat/evidence")


class EvidenceError(Exception):
    """The requested binding violates the CURRENT evidence contract."""


def _registry_path(repo_root: Path) -> Path:
    return repo_root / REGISTRY_REL


def _status_md_path(repo_root: Path) -> Path:
    return repo_root / STATUS_MD_REL


def _snapshot_path(repo_root: Path) -> Path:
    return repo_root / SNAPSHOT_REL


def _evidence_root(repo_root: Path) -> Path:
    return repo_root / EVIDENCE_REL


def _load_json(path: Path) -> dict:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise EvidenceError(f"{path} is not valid JSON: {error}") from error
    if not isinstance(payload, dict):
        raise EvidenceError(f"{path} must contain a JSON object")
    return payload


def _load_registry(repo_root: Path) -> dict:
    path = _registry_path(repo_root)
    if not path.exists():
        raise EvidenceError(f"{path} is missing; run tools/agent/parity_cases.py first")
    return _load_json(path)


def _load_snapshot(repo_root: Path) -> dict | None:
    path = _snapshot_path(repo_root)
    if not path.exists():
        return None
    snapshot = _load_json(path)
    missing = [key for key in ("head", "fingerprint", "generatedAt") if not snapshot.get(key)]
    if missing:
        raise EvidenceError(f"{path} is missing required field(s): {', '.join(missing)}")
    if not FINGERPRINT_RE.fullmatch(str(snapshot["fingerprint"])):
        raise EvidenceError(f"{path} has a malformed fingerprint")
    if not HEAD_RE.fullmatch(str(snapshot["head"])):
        raise EvidenceError(f"{path} has a malformed head")
    return snapshot


def parse_layers(raw: str | None) -> list[str]:
    """Parse `U/I` (or `U,I`) into a de-duplicated layer list; unknown tokens are an error."""
    if not raw:
        return []
    tokens = [token.strip().upper() for token in re.split(r"[/,]", raw) if token.strip()]
    if not tokens:
        return []
    for token in tokens:
        if not LAYER_RE.fullmatch(token):
            raise EvidenceError(f"unknown layer '{token}' (expected one of U/I/D/M/F/V/O/S)")
    return list(dict.fromkeys(tokens))


def validate_binding(
    *,
    case: dict,
    status: str,
    fingerprint: str | None,
    head: str | None,
    command: str | None,
    executed_tests: int,
    exit_code: int,
    layers: list[str],
    issue: str | None,
    snapshot: dict | None,
) -> None:
    """Raise EvidenceError on the first contract violation; otherwise return normally."""
    if status not in VALID_STATUS:
        raise EvidenceError(f"invalid status {status!r}")
    if not head or not HEAD_RE.fullmatch(head):
        raise EvidenceError("--head is required and must be a full 40-hex git commit sha")
    if not command or not command.strip():
        raise EvidenceError("--command is required (record what actually ran)")
    if fingerprint is not None and not FINGERPRINT_RE.fullmatch(fingerprint):
        raise EvidenceError(
            "--fingerprint must be the 64-lowercase-hex build-guard fingerprint when supplied"
        )

    if status in {"FAIL", "BLOCKED"} and not issue:
        raise EvidenceError(f"{status} requires --issue so the failure stays traceable")

    if status != "PASS":
        return

    if not fingerprint:
        raise EvidenceError("PASS requires --fingerprint from the build-guard")
    if snapshot is None:
        raise EvidenceError(
            "PASS requires artifacts/agent-native-compat/current-snapshot.json; "
            "run tools/build-guards/McaWorktreeFingerprint.ps1 and record the snapshot first"
        )
    if fingerprint != snapshot["fingerprint"]:
        raise EvidenceError(
            "--fingerprint does not match current-snapshot.json; refresh the snapshot or pass "
            "the recorded fingerprint (stale evidence cannot be a PASS)"
        )
    if executed_tests <= 0:
        raise EvidenceError("PASS requires --executed-tests > 0 (no empty green light)")
    if exit_code != 0:
        raise EvidenceError("PASS requires --exit-code 0")
    required = set(case.get("layers", []))
    supplied = set(layers)
    missing = sorted(required - supplied)
    if missing:
        raise EvidenceError(
            f"layers {sorted(supplied)} do not cover required layers {sorted(required)}; "
            f"missing: {', '.join(missing)}"
        )


def _write_record(repo_root: Path, case_id: str, record: dict) -> Path:
    directory = _evidence_root(repo_root) / case_id
    directory.mkdir(parents=True, exist_ok=True)
    stamp = record["recordedAt"].replace("-", "").replace(":", "").replace("+", "_")
    path = directory / f"{stamp}.json"
    suffix = 2
    while path.exists():
        path = directory / f"{stamp}-{suffix}.json"
        suffix += 1
    path.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return path


def bind_evidence(
    repo_root: Path,
    *,
    case_id: str,
    status: str,
    fingerprint: str | None,
    head: str,
    command: str,
    executed_tests: int,
    exit_code: int,
    layers: str | None,
    evidence_refs: list[str],
    issue: str | None,
) -> Path:
    """Record one CURRENT result and rewrite the Markdown view; return the evidence record path.

    The historical `status` is only read (for the message); it is never assigned here.
    """
    repo_root = Path(repo_root)
    payload = _load_registry(repo_root)
    cases = payload.get("cases")
    if not isinstance(cases, list):
        raise EvidenceError("parity-cases.json has no cases list")
    case = next((item for item in cases if item.get("caseId") == case_id), None)
    if case is None:
        raise EvidenceError(f"unknown acceptance case {case_id}")

    snapshot = _load_snapshot(repo_root)
    parsed_layers = parse_layers(layers)
    validate_binding(
        case=case,
        status=status,
        fingerprint=fingerprint,
        head=head,
        command=command,
        executed_tests=executed_tests,
        exit_code=exit_code,
        layers=parsed_layers,
        issue=issue,
        snapshot=snapshot,
    )

    recorded_at = datetime.now(timezone.utc).astimezone().replace(microsecond=0).isoformat()
    record = {
        "schemaVersion": "mca.case-evidence/1",
        "tool": "tools/agent/bind_case_evidence.py",
        "caseId": case_id,
        "status": status,
        "recordedAt": recorded_at,
        "fingerprint": fingerprint,
        "head": head,
        "command": command,
        "executedTests": executed_tests,
        "exitCode": exit_code,
        "suppliedLayers": parsed_layers,
        "requiredLayers": sorted(case.get("layers", [])),
        "coversRequiredLayers": set(case.get("layers", [])).issubset(set(parsed_layers)),
        "evidenceRefs": list(evidence_refs),
        "issue": issue,
        "snapshot": (
            {
                "head": snapshot["head"],
                "fingerprint": snapshot["fingerprint"],
                "generatedAt": snapshot["generatedAt"],
                "definition": snapshot.get("definition"),
            }
            if snapshot
            else None
        ),
    }
    record_path = _write_record(repo_root, case_id, record)
    rel_record = record_path.relative_to(repo_root).as_posix()

    historical_status = case.get("status")
    case["currentStatus"] = status
    case["currentEvidence"] = {
        "status": status,
        "fingerprint": fingerprint,
        "head": head,
        "command": command,
        "executedTests": executed_tests,
        "exitCode": exit_code,
        "layers": parsed_layers,
        "requiredLayers": sorted(case.get("layers", [])),
        "recordPath": rel_record,
        "recordedAt": recorded_at,
        "issue": issue,
        "snapshotFingerprint": snapshot["fingerprint"] if snapshot else None,
    }
    compact = (
        f"current:{rel_record}; status={status}; fingerprint={fingerprint or 'none'}; "
        f"tests={executed_tests}; exit={exit_code}; head={head}"
    )
    refs = list(case.get("evidenceRefs", []))
    if compact not in refs:
        refs.append(compact)
    case["evidenceRefs"] = refs

    ordered = sorted(cases, key=lambda item: parity_cases.case_sort_key(item["caseId"]))
    payload["cases"] = ordered
    registry = _registry_path(repo_root)
    registry.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    status_md = _status_md_path(repo_root)
    status_md.write_text(parity_cases.render_markdown(ordered, snapshot), encoding="utf-8")

    print(
        f"bound {case_id} CURRENT={status} -> {rel_record}; "
        f"historical status unchanged: {historical_status}; "
        f"regenerated {status_md.relative_to(repo_root).as_posix()}"
    )
    return record_path


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Bind CURRENT acceptance evidence without touching historical status.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("--case", required=True, help="acceptance case id, e.g. WF-01")
    parser.add_argument("--status", required=True, choices=sorted(VALID_STATUS))
    parser.add_argument(
        "--fingerprint",
        default=None,
        help="64-lowercase-hex build-guard worktree fingerprint (required for PASS)",
    )
    parser.add_argument("--head", required=True, help="full 40-hex git HEAD")
    parser.add_argument("--command", required=True, help="what actually ran (secret-free)")
    parser.add_argument("--executed-tests", type=int, default=0, help="real executed test count")
    parser.add_argument("--exit-code", type=int, default=0, help="process exit code")
    parser.add_argument("--layers", default=None, help="covered layers, e.g. U/I")
    parser.add_argument(
        "--evidence-ref",
        action="append",
        default=[],
        dest="evidence_refs",
        help="repeatable raw-evidence reference",
    )
    parser.add_argument("--issue", default=None, help="issue reference (required for FAIL/BLOCKED)")
    parser.add_argument(
        "--repo-root",
        default=str(Path(__file__).resolve().parents[2]),
        help=argparse.SUPPRESS,
    )
    args = parser.parse_args(argv)
    try:
        bind_evidence(
            Path(args.repo_root),
            case_id=args.case,
            status=args.status,
            fingerprint=args.fingerprint,
            head=args.head,
            command=args.command,
            executed_tests=args.executed_tests,
            exit_code=args.exit_code,
            layers=args.layers,
            evidence_refs=args.evidence_refs,
            issue=args.issue,
        )
    except EvidenceError as error:
        print(f"bind_case_evidence: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
