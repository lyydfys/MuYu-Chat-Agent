#!/usr/bin/env python3
"""Extract the 121 acceptance cases from PARITY_ACCEPTANCE.md into a status registry.

The acceptance document is the single source of truth for case ids, required test layers and
owning task cards. This tool turns it into a machine-readable registry so evidence can be attached
per case without hand-copying ids, and so a completed run can prove that no case disappeared.

Usage:
    python tools/agent/parity_cases.py              # write artifacts/agent-native-compat/parity-cases.json
    python tools/agent/parity_cases.py --check      # parse and validate only (no write)

Existing historical status/evidence values are preserved on regeneration; only the structural
fields (layer, taskCards) come from the document. CURRENT evidence fields (`currentStatus`,
`currentEvidence`) are preserved too and never merged into the historical `status`. The generated
Markdown reads the optional `current-snapshot.json` so the HISTORICAL-vs-CURRENT header (and the
CURRENT column) survive a regeneration instead of disappearing with a hand edit. Never silently
drops a case: a parse miss is an error.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DOC = REPO / "docs/proposals/agent-native-compat-v1/PARITY_ACCEPTANCE.md"
OUT = REPO / "artifacts/agent-native-compat/parity-cases.json"
SNAPSHOT = OUT.with_name("current-snapshot.json")

CASE_ID = re.compile(r"\b(TOOL|PKG|MEM|WF|OFF|REG)-(\d{2})\b")
TABLE_ROW = re.compile(r"^\|\s*([A-Z]+-\d{2})\s*\|")
LAYER_ALIASES = {"静态审查": "S"}
VALID_STATUS = {"PASS", "FAIL", "NOT_RUN", "BLOCKED", "NOT_APPLICABLE"}
STATUS_ORDER = ("PASS", "FAIL", "BLOCKED", "NOT_RUN", "NOT_APPLICABLE")
FINGERPRINT_RE = re.compile(r"^[0-9a-f]{64}$")
HEAD_RE = re.compile(r"^[0-9a-f]{40}$")
_UNSET = object()


def split_row(line: str) -> list[str]:
    return [cell.strip() for cell in line.strip().strip("|").split("|")]


def parse_cases() -> list[dict]:
    text = DOC.read_text(encoding="utf-8")
    cases: dict[str, dict] = {}
    for line in text.splitlines():
        match = TABLE_ROW.match(line)
        if not match:
            continue
        case_id = match.group(1)
        cells = split_row(line)
        if len(cells) < 5:
            raise SystemExit(f"row for {case_id} has {len(cells)} cells, expected at least 5")
        layer_cell = cells[3]
        card_cell = cells[4]
        layers = [part.strip() for part in layer_cell.split("/") if part.strip()]
        if not layers:
            raise SystemExit(f"row for {case_id} declares no test layer")
        layers = [LAYER_ALIASES.get(layer, layer) for layer in layers]
        for layer in layers:
            if not re.fullmatch(r"[UIDMFVOS]", layer):
                raise SystemExit(f"row for {case_id} has an unknown test layer '{layer}'")
        cards = sorted(
            {card for card in re.findall(r"N\d{2}", card_cell)},
        )
        if not cards:
            raise SystemExit(f"row for {case_id} names no owning task card")
        value = cases.get(case_id)
        if value is not None:
            # Some cases are listed once per section; merge instead of failing on the duplicate.
            value["layers"] = sorted(set(value["layers"]) | set(layers))
            value["taskCards"] = sorted(set(value["taskCards"]) | set(cards))
            continue
        cases[case_id] = {"caseId": case_id, "layers": layers, "taskCards": cards}
    return [cases[key] for key in sorted(cases, key=case_sort_key)]


def case_sort_key(case_id: str) -> tuple[int, int]:
    family, number = case_id.split("-")
    order = {"TOOL": 0, "PKG": 1, "MEM": 2, "WF": 3, "OFF": 4, "REG": 5}
    return order[family], int(number)


def load_current_snapshot(path: Path = SNAPSHOT) -> dict | None:
    """Load the optional CURRENT snapshot anchor; absent means only HISTORICAL is bound.

    A malformed snapshot is a hard error: silently dropping the CURRENT header would let a
    regenerated status view look historical-only without telling anyone.
    """
    if not path.exists():
        return None
    try:
        snapshot = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise SystemExit(f"{path} is not valid JSON: {error}") from error
    if not isinstance(snapshot, dict):
        raise SystemExit(f"{path} must contain a JSON object")
    missing = [key for key in ("head", "fingerprint", "generatedAt") if not snapshot.get(key)]
    if missing:
        raise SystemExit(f"{path} is missing required field(s): {', '.join(missing)}")
    if not FINGERPRINT_RE.fullmatch(str(snapshot["fingerprint"])):
        raise SystemExit(f"{path} has a malformed fingerprint (expected 64 lowercase hex chars)")
    if not HEAD_RE.fullmatch(str(snapshot["head"])):
        raise SystemExit(f"{path} has a malformed head (expected 40 lowercase hex chars)")
    return snapshot


def effective_current_status(case: dict, snapshot: dict | None) -> str:
    """Return the CURRENT status that may be counted for this case.

    CURRENT evidence counts only when it is a structured record whose fingerprint matches the
    loaded snapshot. Anything else renders as NOT_RUN, so a historical PASS can never migrate into
    the CURRENT column and stale evidence cannot inflate the current rate.
    """
    evidence = case.get("currentEvidence")
    status = case.get("currentStatus")
    if not isinstance(evidence, dict) or status not in VALID_STATUS:
        return "NOT_RUN"
    if not snapshot:
        return "NOT_RUN"
    if evidence.get("fingerprint") != snapshot.get("fingerprint"):
        return "NOT_RUN"
    return status


def current_stale_count(cases: list[dict], snapshot: dict | None) -> int:
    count = 0
    for case in cases:
        evidence = case.get("currentEvidence")
        if not isinstance(evidence, dict):
            continue
        if not snapshot or evidence.get("fingerprint") != snapshot.get("fingerprint"):
            count += 1
    return count


def render_current_header(cases: list[dict], snapshot: dict | None) -> list[str]:
    historical = Counter(case.get("status", "NOT_RUN") for case in cases)
    current = Counter(effective_current_status(case, snapshot) for case in cases)
    stale = current_stale_count(cases, snapshot)

    def counts(counter: Counter) -> str:
        return "，".join(f"{key}={counter.get(key, 0)}" for key in STATUS_ORDER)

    lines: list[str] = []
    if snapshot:
        definition = snapshot.get("definition") or (
            "CURRENT PASS 仅当证据绑定当前 worktree fingerprint（或更晚 fingerprint），"
            "并同时给出命令、真实测试数/执行步骤、退出码与 case 全部必需层；历史 status 永不改写。"
        )
        lines += [
            "> ## HISTORICAL vs CURRENT（由 `tools/agent/parity_cases.py` 生成；勿手改）",
            ">",
            "> “历史状态”列是 **HISTORICAL-NOT-CURRENT**：旧 revision / 旧 APK 上登记的结果，只作来源，"
            "不代表当前完成。`CURRENT` 列只采纳与当前快照指纹匹配的结构化证据，否则一律 `NOT_RUN`。",
            ">",
            f"> - 当前快照：HEAD `{snapshot['head']}`；worktree fingerprint `{snapshot['fingerprint']}`；"
            f"记录时间 `{snapshot['generatedAt']}`。",
            f"> - CURRENT 定义：{definition}",
            f"> - 历史计数（**不得**当当前完成）：{counts(historical)}。",
            f"> - 当前计数（绑定快照指纹，分母 {len(cases)}）：{counts(current)}；"
            f"有当前证据但指纹不匹配/无快照：{stale}（按 `NOT_RUN` 计）。",
            "> - 逐项字段：`parity-cases.json` 的 `currentStatus` / `currentEvidence`；"
            "原始记录：`evidence/<caseId>/<timestamp>.json`。",
            "> - 快照来源：`tools/build-guards/McaWorktreeFingerprint.ps1`；刷新与绑定契约见 "
            "`docs/proposals/agent-native-compat-v1/EVIDENCE_BINDING.md`。",
        ]
    else:
        lines += [
            "> ## HISTORICAL vs CURRENT（CURRENT 未绑定；由 `tools/agent/parity_cases.py` 生成）",
            ">",
            "> 未找到 `artifacts/agent-native-compat/current-snapshot.json`，因此本表只有 "
            "**HISTORICAL-NOT-CURRENT** 状态，`CURRENT` 列全部按 `NOT_RUN` 显示。",
            "> 先由 runner 用 `tools/build-guards/McaWorktreeFingerprint.ps1 -EmitJson` 生成快照，"
            "再用 `tools/agent/bind_case_evidence.py` 绑定 CURRENT 证据；不要把历史 PASS 当当前完成。",
            f"> - 历史计数（不得当当前完成）：{counts(historical)}。",
        ]
    return lines


def merge_existing(cases: list[dict], registry_path: Path | None = None) -> list[dict]:
    """Merge structural fields from the document with preserved result/evidence fields.

    The historical `status` and the CURRENT fields are independent: regeneration copies both
    through unchanged and never derives one from the other.
    """
    path = OUT if registry_path is None else registry_path
    preserved = {}
    if path.exists():
        try:
            previous = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as error:
            raise SystemExit(f"{path} is not valid JSON: {error}") from error
        preserved = {entry["caseId"]: entry for entry in previous.get("cases", [])}
    for case in cases:
        old = preserved.get(case["caseId"], {})
        status = old.get("status", "NOT_RUN")
        if status not in VALID_STATUS:
            raise SystemExit(f"case {case['caseId']} has an invalid status {status!r}")
        case["status"] = status
        case["attempts"] = int(old.get("attempts", 0))
        case["evidenceRefs"] = list(old.get("evidenceRefs", []))
        case["issueRef"] = old.get("issueRef")
        if "currentStatus" in old:
            current_status = old["currentStatus"]
            if current_status not in VALID_STATUS:
                raise SystemExit(
                    f"case {case['caseId']} has an invalid currentStatus {current_status!r}"
                )
            case["currentStatus"] = current_status
        if "currentEvidence" in old:
            evidence = old["currentEvidence"]
            if not isinstance(evidence, dict):
                raise SystemExit(f"case {case['caseId']} has a non-object currentEvidence")
            case["currentEvidence"] = evidence
    return cases


def render_markdown(cases: list[dict], snapshot=_UNSET) -> str:
    if snapshot is _UNSET:
        snapshot = load_current_snapshot()
    historical = Counter(case.get("status", "NOT_RUN") for case in cases)
    current = Counter(effective_current_status(case, snapshot) for case in cases)

    def counts(counter: Counter) -> str:
        return "，".join(f"{key}={counter.get(key, 0)}" for key in STATUS_ORDER)

    lines = ["# 121 项验收状态（生成物，勿手改）", ""]
    lines += render_current_header(cases, snapshot)
    lines += [
        "",
        "来源：`docs/proposals/agent-native-compat-v1/PARITY_ACCEPTANCE.md`。",
        "本表由 `tools/agent/parity_cases.py` 从文档抽取，再叠加 `parity-cases.json` 中已登记的"
        "历史状态与 CURRENT 证据字段。",
        "历史状态只能是 `PASS / FAIL / NOT_RUN / BLOCKED / NOT_APPLICABLE`；未运行=NOT_RUN，"
        "不得写成通过。CURRENT 列见上方定义。",
        "",
        "| ID | 层 | 卡 | 历史状态 | CURRENT | 尝试 | 证据 |",
        "| --- | --- | --- | --- | --- | --- | --- |",
    ]
    for case in cases:
        evidence_parts = list(case["evidenceRefs"])
        current_evidence = case.get("currentEvidence")
        if isinstance(current_evidence, dict) and current_evidence.get("recordPath"):
            evidence_parts.append(f"CURRENT: {current_evidence['recordPath']}")
        evidence = "<br>".join(evidence_parts) if evidence_parts else "-"
        lines.append(
            f"| {case['caseId']} | {'/'.join(case['layers'])} | "
            f"{'/'.join(case['taskCards'])} | {case['status']} | "
            f"{effective_current_status(case, snapshot)} | {case['attempts']} | {evidence} |"
        )
    lines += ["", f"合计 {len(cases)} 项：历史 {counts(historical)}；CURRENT {counts(current)}。", ""]
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="parse and validate without writing")
    args = parser.parse_args()

    cases = merge_existing(parse_cases())
    if len(cases) != 121:
        raise SystemExit(f"expected 121 acceptance cases, parsed {len(cases)}")
    families = {case["caseId"].split("-")[0] for case in cases}
    print(f"parsed {len(cases)} cases across {len(families)} families")
    for family, expected in (("TOOL", 27), ("PKG", 23), ("MEM", 25), ("WF", 30), ("OFF", 6), ("REG", 10)):
        actual = sum(1 for case in cases if case["caseId"].startswith(family + "-"))
        if actual != expected:
            raise SystemExit(f"{family}: expected {expected} cases, found {actual}")
    snapshot = load_current_snapshot()
    if snapshot:
        print(
            "current snapshot: head=%s fingerprint=%s generatedAt=%s"
            % (snapshot["head"], snapshot["fingerprint"], snapshot["generatedAt"])
        )
    else:
        print("current snapshot: absent (CURRENT column renders NOT_RUN)")
    if args.check:
        return 0

    payload = {
        "schemaVersion": 1,
        "sourceDocument": str(DOC.relative_to(REPO)).replace("\\", "/"),
        "referenceCommit": "dbf71916fae9750cfdc9f9a774f5a0fee56633fb",
        "cases": cases,
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    markdown = OUT.with_name("ACCEPTANCE_STATUS.md")
    markdown.write_text(render_markdown(cases, snapshot), encoding="utf-8")
    print(f"wrote {OUT.relative_to(REPO)} and {markdown.relative_to(REPO)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
