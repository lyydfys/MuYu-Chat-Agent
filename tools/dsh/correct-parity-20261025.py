#!/usr/bin/env python3
"""Targeted 2026-10-25 current-candidate ledger correction.

Two rows only:

* ``host.terminal-controller`` — the historical PASS is bound to 4918FD63. On the current
  candidate 7F790A16 the official web terminal is a piped shell (`tty` = "not a tty",
  `stty` ioctl fails, shell is a Node pipe child), so the current-candidate runtime state is
  NOT_RUN, not PASS. The historical note is kept.
* ``mca.target.target36-command-closure`` — keep the historical PASS/candidate, and append the
  current-candidate subset that was actually re-run (bash/node/npm/python/pip + real npm install);
  pnpm/rg/curl/git were not re-run.

Counts are recomputed from the rows. ``--check`` compares without writing.
"""

from __future__ import annotations

import argparse
import json
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
CURRENT_CANDIDATE = "7F790A1629009666CDE7DE4BC809E730DA7D189EEB8BFA8DEC0AA0348177C214"

CORRECTIONS = {
    "host.terminal-controller": (
        "NOT_RUN",
        "Current-candidate correction 2026-10-25 (candidate 7F790A16): the official web terminal "
        "opens but is a piped shell (tty='not a tty', stty ioctl fails, shell is a child of Node "
        "with pipe fds), so the MCA PTY seam is not what this UI exercised. Current-candidate PTY "
        "state is NOT_RUN; the historical 4918FD63 PTY proof is retained above. Evidence: "
        "artifacts/dsh-v2/verification/PTY-TERMINAL-7F790A16.md.",
    ),
    "mca.target.target36-command-closure": (
        None,
        "Current-candidate subset 2026-10-25 (candidate 7F790A16): through the official bash tool "
        "and the dispatcher payload-root fallback, node v26.4.0, npm 11.20.0, Python 3.14.6, pip "
        "26.1.2, `sh` (POSIX, BASH_VERSION unset), and a real `npm install is-number@7.0.0` + "
        "require all succeeded. pnpm/rg/curl/git were not re-run on this candidate. Evidence: "
        "artifacts/dsh-v2/verification/SHELL-FALLBACK-998593CF.md.",
    ),
    "mobile.tool.android_clipboard": (
        None,
        "Current-candidate rebind 2026-10-25 (candidate 7F790A16): direct authenticated "
        "/clipboard write 'mca-7f790a16' then read returned the same content (both HTTP 200). "
        "Scope: both actions; no agent-initiated call. Evidence: "
        "artifacts/dsh-v2/verification/PHONE-ROUTES-7F790A16.md.",
    ),
    "mobile.tool.android_notify": (
        None,
        "Current-candidate rebind 2026-10-25 (candidate 7F790A16): /notify returned "
        "{ok:true,posted:true} and dumpsys notification showed android.title='MCA notify 7F790A16'. "
        "Scope: notify action; user-facing permission flow unchanged. Evidence: "
        "artifacts/dsh-v2/verification/PHONE-ROUTES-7F790A16.md.",
    ),
}


def loads():
    return json.loads(MATRIX_PATH.read_text(encoding="utf-8"))


def dumps(document):
    return json.dumps(document, ensure_ascii=False, indent=2) + "\n"


def correct(document):
    rows = document.get("rows")
    if not isinstance(rows, list) or len(rows) != 485:
        raise SystemExit("ledger must contain exactly 485 rows")
    by_id = {row["id"]: row for row in rows}
    for row_id, (status, note) in CORRECTIONS.items():
        row = by_id.get(row_id)
        if row is None:
            raise SystemExit("missing row: " + row_id)
        if status is not None:
            runtime = row.setdefault("runtime", {})
            runtime["status"] = status
            if status == "PASS":
                runtime["candidateId"] = CURRENT_CANDIDATE
            else:
                runtime.pop("candidateId", None)
        implementation = row.setdefault("implementation", {})
        current = implementation.get("notes") or ""
        if note not in current:
            implementation["notes"] = (current + " " + note).strip()
        review = row.setdefault("profile", {}).setdefault("reviewEvidence", [])
        ref = "artifacts/dsh-v2/verification/LEDGER-CORRECTION-20261024.md"
        if ref not in review:
            review.append(ref)
    counts = document.setdefault("counts", {})
    counts["runtime"] = dict(sorted(Counter(row["runtime"]["status"] for row in rows).items()))
    counts["profileDisposition"] = dict(
        sorted(Counter(row["profile"]["disposition"] for row in rows).items())
    )
    return document


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args(argv)
    before = loads()
    after = correct(json.loads(json.dumps(before)))
    desired = dumps(after)
    current = MATRIX_PATH.read_text(encoding="utf-8")
    runtime = Counter(row["runtime"]["status"] for row in after["rows"])
    print("rows=%d" % len(after["rows"]))
    print("runtime: " + " ".join("%s=%d" % item for item in sorted(runtime.items())))
    if args.check:
        print("check: " + ("current" if current == desired else "stale"))
        return 0 if current == desired else 1
    if current == desired:
        print("write: no change")
        return 0
    MATRIX_PATH.write_text(desired, encoding="utf-8", newline="\n")
    print("write: updated")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
