#!/usr/bin/env python3
"""Evidence-backed runtime promotion for completion-v2/PARITY_MATRIX_V2.json.

This is the second, deliberately narrow updater for the ledger. It exists because the original
``update-parity-matrix.py`` never touches ``runtime.status``: its conservative "no runtime proof"
rule is correct in general, but it left device-proven rows frozen at NOT_RUN after the 2026-10-23
device rounds.

Rules enforced here:

* only the exact row ids in ``PROMOTIONS`` may change; every other row is untouched;
* a promotion needs a concrete device/raw artifact, a candidate APK SHA-256, and a scoped claim;
* rows are never bulk-promoted by owner card, family, kind or a gate result;
* a PASS is bound to the historical candidate that produced the evidence in
  ``runtime.candidateId``; the current 2026-10-24 source candidate is not claimed;
* ``runtime`` counts are recomputed from the rows, so the header cannot drift.

The companion record is ``artifacts/dsh-v2/verification/LEDGER-CORRECTION-20261024.md``.

Usage:
    python tools/dsh/promote-parity-evidence.py --check
    python tools/dsh/promote-parity-evidence.py
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
MATRIX_PATH = (
    REPO_ROOT
    / "docs"
    / "proposals"
    / "agent-dsh-embedded-v1"
    / "completion-v2"
    / "PARITY_MATRIX_V2.json"
)

EXPECTED_TOTAL = 485
EXPECTED_KIND_COUNTS = {
    "official-manifest-row": 422,
    "mobile-tool-export": 43,
    "mca-target-design": 20,
}

# (candidate sha256, evidence note). The note is appended to implementation.notes so the original
# gate evidence array keeps the strict "gate task=..." shape used by update-parity-matrix.py.
PROMOTIONS = {
    "mobile.tool.android_a11y_status": (
        "CFF571C52321DDFC0221C047B99E25E91F0490C4450E0399C9C4752BEF7F0F97",
        "Device evidence 2026-10-26 (final candidate CFF571C5, device 98a37aa7): the production a11y "
        "service answered /status with {ok:true,running:true,package:com.muyuchat.mca.devicetest,"
        "nodeCount:22,apiLevel:37,canScreenshot:true,windowId:48551}. The call was repeated after the "
        "service rebind and before the swipe run (raw/a11y-scroll-cff571c5/swipe-log.txt and the dumps "
        "that follow). Scope: the status route only; no agent-initiated call.",
    ),
    "mobile.tool.android_see": (
        "CFF571C52321DDFC0221C047B99E25E91F0490C4450E0399C9C4752BEF7F0F97",
        "Device evidence 2026-10-26 (final candidate CFF571C5, device 98a37aa7): /dump returned a "
        "bounded accessibility tree (count 47-51, package/cls/text/desc/bounds/scrollable/ref) for the "
        "Settings app list; five dumps were saved during the swipe proof "
        "(raw/a11y-scroll-cff571c5/dump-sw1..3.json, dump-after-up.json). Scope: the dump route; "
        "/screenshot is not claimed.",
    ),
    "mobile.tool.android_swipe": (
        "CFF571C52321DDFC0221C047B99E25E91F0490C4450E0399C9C4752BEF7F0F97",
        "Device evidence 2026-10-26 (installed APK 52926FEA, device 98a37aa7; the final frozen candidate CFF571C5, built under the lock at 15:43 and installed at 15:48:19): the production a11y "
        "HTTP service (DshA11yService, apiLevel 37) was bound and served /swipe. On "
        "com.android.settings '所有应用' (~100 apps) four vertical swipes at fx=0.5, fy 0.85->0.15, "
        "300 ms returned {ok:true} and advanced the list MNN Chat -> 小米创作 -> 小红书/迅雷 (end); "
        "two reverse swipes moved back toward 多看. Raw: raw/a11y-scroll-cff571c5/"
        "dump-sw*.json, dump-after-up.json, swipe-log.txt, screen-end.png, screen-up.png. Scope: "
        "vertical swipe scrolling only; /scroll repeat is not promoted (after the first step the "
        "first scrollable node is a Spinner and the action is rejected). No agent-initiated call.",
    ),
    "mobile.tool.android_clipboard": (
        "114E13F144CBD0D6E59E4B0F245E303DB22B57C5B8DF90137187C1FFF95A6068",
        "Device evidence 2026-10-23 (candidate 114E13F1): PHONE-TOOLS.md 'Re-verified on the "
        "current build' -> /clipboard write 'mca-clip-114f1' then read returned the same content; "
        "the earlier 83943124 candidate round-tripped 'mca-clipboard-test-42'. Scope: both "
        "clipboard actions; no agent-initiated call in this proof.",
    ),
    "mobile.tool.android_notify": (
        "114E13F144CBD0D6E59E4B0F245E303DB22B57C5B8DF90137187C1FFF95A6068",
        "Device evidence 2026-10-23 (candidate 114E13F1): PHONE-TOOLS.md -> /notify without "
        "POST_NOTIFICATIONS returned typed PERMISSION_DENIED; after pm grant, posted=true and "
        "dumpsys notification showed android.title='MCA notify 114f1'. Scope: notify action; the "
        "user-facing permission-request flow is still missing.",
    ),
    "mobile.tool.browser_caps": (
        "83943124AEB984ECA0914BD32667548AB7BDDAAD8BAC5E87FAE6BA7413A1F069",
        "Device evidence 2026-10-23 (candidate 83943124; the later protocol-fix rebuild was "
        "blocked): PHONE-TOOLS.md -> /browser caps returned {ok:true,surface:'browser',"
        "engine:'android-webview',...}. Scope: caps only; no agent-initiated call.",
    ),
    "mobile.tool.browser_open": (
        "83943124AEB984ECA0914BD32667548AB7BDDAAD8BAC5E87FAE6BA7413A1F069",
        "Device evidence 2026-10-23 (candidate 83943124): PHONE-TOOLS.md -> after appops "
        "SYSTEM_ALERT_WINDOW allow and restart, /browser open https://example.com returned "
        "{ok:true,tabId:'t1',title:'Example Domain',ready:true}. Scope: one tab; tabs/close/"
        "process-death not proven.",
    ),
    "mobile.tool.browser_read": (
        "83943124AEB984ECA0914BD32667548AB7BDDAAD8BAC5E87FAE6BA7413A1F069",
        "Device evidence 2026-10-23 (candidate 83943124): PHONE-TOOLS.md -> /browser read after "
        "open returned the real page text ('This domain is for use in documentation examples'). "
        "Scope: read of one page; snapshot/find/click/type/nav/scroll not proven.",
    ),
    # host.terminal-controller: the historical 4918FD63 PTY PASS is deliberately NOT promoted
    # again. On the 2026-10-25 candidate 7F790A16 the web terminal is a piped shell, so the
    # current-candidate runtime state is NOT_RUN. See tools/dsh/correct-parity-20261025.py and
    # artifacts/dsh-v2/verification/PTY-TERMINAL-7F790A16.md.
    "mca.target.target36-command-closure": (
        "4918FD6301BF2588DD2C4ADA457637F7504A59FD4725E7EEC65884C600C8B8E8",
        "Device evidence 2026-10-23 (candidate 4918FD63): TERMINAL-PACKAGES.md sections on the "
        "target36 closure -> node/npm/pnpm/pip/rg/curl/git exit 0; bash reports gnuBash=false "
        "honestly. Scope: the listed executables only; TLS/proxy and Chinese/space paths "
        "(EXEC-12) remain NOT_RUN.",
    ),
}


def load_matrix():
    return json.loads(MATRIX_PATH.read_text(encoding="utf-8"))


def dump_matrix(document):
    return json.dumps(document, ensure_ascii=False, indent=2) + "\n"


def promote(document):
    rows = document.get("rows")
    if not isinstance(rows, list) or len(rows) != EXPECTED_TOTAL:
        raise SystemExit("ledger must contain exactly %d rows" % EXPECTED_TOTAL)
    ids = [row.get("id") for row in rows]
    if len(set(ids)) != len(ids):
        raise SystemExit("ledger row ids are not unique")
    kinds = Counter(row["source"]["kind"] for row in rows)
    if dict(kinds) != EXPECTED_KIND_COUNTS:
        raise SystemExit("source kind counts changed: %r" % dict(kinds))

    by_id = {row["id"]: row for row in rows}
    for row_id, (candidate, note) in PROMOTIONS.items():
        row = by_id.get(row_id)
        if row is None:
            raise SystemExit("promotion row is missing: %s" % row_id)
        runtime = row.setdefault("runtime", {})
        if runtime.get("status") == "PASS":
            if runtime.get("candidateId") != candidate:
                raise SystemExit("row already PASS for a different candidate: %s" % row_id)
        else:
            runtime["status"] = "PASS"
            runtime["candidateId"] = candidate
        implementation = row.setdefault("implementation", {})
        current = implementation.get("notes") or ""
        if note not in current:
            implementation["notes"] = (current + " " + note).strip()
        review = row.setdefault("profile", {}).setdefault("reviewEvidence", [])
        evidence_ref = "artifacts/dsh-v2/verification/LEDGER-CORRECTION-20261024.md"
        if evidence_ref not in review:
            review.append(evidence_ref)

    counts = document.setdefault("counts", {})
    counts["runtime"] = dict(sorted(Counter(row["runtime"]["status"] for row in rows).items()))
    counts["profileDisposition"] = dict(
        sorted(Counter(row["profile"]["disposition"] for row in rows).items())
    )
    return document


def summary(document):
    runtime = Counter(row["runtime"]["status"] for row in document["rows"])
    implementation = Counter(row["implementation"]["status"] for row in document["rows"])
    profile = Counter(row["profile"]["disposition"] for row in document["rows"])
    return runtime, implementation, profile


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args(argv)

    before = load_matrix()
    after = promote(json.loads(json.dumps(before)))
    desired = dump_matrix(after)
    current = MATRIX_PATH.read_text(encoding="utf-8")

    runtime, implementation, profile = summary(after)
    print("rows=%d" % len(after["rows"]))
    print("runtime: " + " ".join("%s=%d" % item for item in sorted(runtime.items())))
    print("implementation: " + " ".join("%s=%d" % item for item in sorted(implementation.items())))
    print("profile: " + " ".join("%s=%d" % item for item in sorted(profile.items())))
    print("bound candidates: " + ", ".join(sorted({candidate for candidate, _ in PROMOTIONS.values()})))

    if args.check:
        if current == desired:
            print("check: ledger promotions are current")
            return 0
        print("check: ledger promotions are stale; run without --check")
        return 1
    if current == desired:
        print("write: no change")
        return 0
    MATRIX_PATH.write_text(desired, encoding="utf-8", newline="\n")
    print("write: updated %s" % MATRIX_PATH.relative_to(REPO_ROOT))
    return 0


if __name__ == "__main__":
    sys.exit(main())
