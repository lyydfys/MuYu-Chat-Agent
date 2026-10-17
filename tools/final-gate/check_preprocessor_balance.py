#!/usr/bin/env python3
"""
check_preprocessor_balance.py

Structural preprocessor balance gate for MCA native bridge sources.

Why this exists
---------------
The "Giant Bridges Debt Audit" in verify_s_grade_final_gate.py only COUNTS LINES.
A bridge whose #if/#else/#endif tree is unbalanced still occupies N lines and
still scored PASS, even though the translation unit can no longer be
preprocessed at all -- the real regression in qnn_native_bridge.cpp was a
missing #if that left an #else/#endif pair unmatched.  Line count is a size
metric, not a structural one, so this script adds the structural check.

What it does
------------
Reads the target C/C++ source and walks it in file order with a stack, pairing
#if / #ifdef / #ifndef / #elif / #else / #endif.  Content that is not real
preprocessor text is ignored: // line comments, /* block comments */, and
string / character literals.

A violation is any of:
  * #elif / #else / #endif with no open conditional group;
  * #elif or #else appearing after the #else of the same conditional group;
  * #if / #ifdef / #ifndef still open at end of file.
Violations are printed with their line numbers and the script exits non-zero.

Usage
-----
    python tools/final-gate/check_preprocessor_balance.py [source.cpp]

Without an argument the canonical bridge
core/native/src/main/cpp/qnn_native_bridge.cpp is audited.

Exit codes
----------
    0  balanced
    1  structural violation(s) found
    2  usage / IO error (target missing or unreadable)
"""

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_TARGET = (
    REPO_ROOT / "core" / "native" / "src" / "main" / "cpp" / "qnn_native_bridge.cpp"
)

OPENERS = ("ifdef", "ifndef", "if")
BRANCHES = ("elif", "else")
# Longest alternatives first: `#ifdef` must not be read as `#if`.
DIRECTIVE_RE = re.compile(r"^\s*#\s*(ifdef|ifndef|elif|else|endif|if)\b")

EXIT_OK = 0
EXIT_VIOLATION = 1
EXIT_USAGE = 2


def mask_non_directive_text(text):
    """Blank out comments and literals, preserving every newline and offset.

    Returns the source with // comments, /* */ comments, string literals and
    character literals replaced by spaces.  Line numbers therefore still line
    up with the original file, and a directive-shaped token inside a comment or
    a literal can no longer be mistaken for a real directive.
    """
    out = list(text)
    i = 0
    n = len(text)
    state = "code"
    while i < n:
        ch = text[i]
        if state == "code":
            nxt = text[i + 1] if i + 1 < n else ""
            if ch == "/" and nxt == "*":
                out[i] = " "
                out[i + 1] = " "
                i += 2
                state = "block"
            elif ch == "/" and nxt == "/":
                out[i] = " "
                out[i + 1] = " "
                i += 2
                state = "line"
            elif ch == '"':
                out[i] = " "
                i += 1
                state = "string"
            elif ch == "'":
                out[i] = " "
                i += 1
                state = "char"
            else:
                i += 1
        elif state == "block":
            if ch == "*" and i + 1 < n and text[i + 1] == "/":
                out[i] = " "
                out[i + 1] = " "
                i += 2
                state = "code"
            else:
                if ch != "\n":
                    out[i] = " "
                i += 1
        elif state == "line":
            if ch == "\n":
                state = "code"
            else:
                out[i] = " "
            i += 1
        else:  # string / char literal
            closer = '"' if state == "string" else "'"
            if ch == "\\" and i + 1 < n:
                out[i] = " "
                if text[i + 1] != "\n":
                    out[i + 1] = " "
                i += 2
            elif ch == closer:
                out[i] = " "
                i += 1
                state = "code"
            elif ch == "\n":
                # Unterminated literal: resynchronise at the newline instead of
                # swallowing the rest of the file.
                state = "code"
                i += 1
            else:
                out[i] = " "
                i += 1
    return "".join(out)


def check_balance(text):
    """Stack-pair the conditional directives.  Returns (stats, violations)."""
    stats = {"opens": 0, "elifs": 0, "elses": 0, "endifs": 0, "directives": 0, "max_depth": 0}
    violations = []
    stack = []  # one entry per open group: line, keyword, else_line

    for lineno, line in enumerate(mask_non_directive_text(text).splitlines(), start=1):
        match = DIRECTIVE_RE.match(line)
        if not match:
            continue
        keyword = match.group(1)
        stats["directives"] += 1

        if keyword in OPENERS:
            stats["opens"] += 1
            stack.append({"line": lineno, "keyword": keyword, "else_line": None})
            stats["max_depth"] = max(stats["max_depth"], len(stack))
        elif keyword in BRANCHES:
            if keyword == "elif":
                stats["elifs"] += 1
            else:
                stats["elses"] += 1
            if not stack:
                violations.append(
                    f"line {lineno}: unmatched #{keyword} -- no open #if/#ifdef/#ifndef"
                )
                continue
            top = stack[-1]
            if top["else_line"] is not None:
                violations.append(
                    f"line {lineno}: #{keyword} after #else on line {top['else_line']} "
                    f"(conditional group opened at line {top['line']})"
                )
            elif keyword == "else":
                top["else_line"] = lineno
        else:  # endif
            stats["endifs"] += 1
            if not stack:
                violations.append(
                    f"line {lineno}: unmatched #endif -- no open #if/#ifdef/#ifndef"
                )
            else:
                stack.pop()

    for entry in stack:
        violations.append(
            f"line {entry['line']}: unclosed #{entry['keyword']} -- "
            f"no matching #endif before end of file"
        )

    return stats, violations


def display_path(path):
    try:
        return str(path.relative_to(REPO_ROOT))
    except ValueError:
        return str(path)


def main(argv):
    if len(argv) == 2 and argv[1] in ("-h", "--help"):
        print(__doc__.strip())
        return EXIT_OK
    if len(argv) > 2:
        print(
            "usage: check_preprocessor_balance.py [source.cpp] "
            "(default: the canonical QNN bridge)",
            file=sys.stderr,
        )
        return EXIT_USAGE

    target = Path(argv[1]) if len(argv) == 2 else DEFAULT_TARGET
    if not target.is_file():
        print(
            f"[preprocessor-balance] ERROR: audited source not found: {target}",
            file=sys.stderr,
        )
        return EXIT_USAGE

    try:
        text = target.read_text(encoding="utf-8", errors="replace")
    except OSError as exc:
        print(f"[preprocessor-balance] ERROR: cannot read {target}: {exc}", file=sys.stderr)
        return EXIT_USAGE

    stats, violations = check_balance(text)
    label = display_path(target)

    if violations:
        print("[preprocessor-balance] FAILED: unbalanced preprocessor structure", file=sys.stderr)
        print(f"    target: {label}", file=sys.stderr)
        for item in violations:
            print(f"    {item}", file=sys.stderr)
        print(
            "    => this translation unit cannot be preprocessed as written.",
            file=sys.stderr,
        )
        return EXIT_VIOLATION

    print("[preprocessor-balance] PASSED: preprocessor structure is balanced")
    print(f"    target: {label}")
    print(
        "    conditional directives: {directives} "
        "(#if/#ifdef/#ifndef={opens}, #elif={elifs}, #else={elses}, #endif={endifs})".format(
            **stats
        )
    )
    print(f"    max nesting depth: {stats['max_depth']}")
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main(sys.argv))
