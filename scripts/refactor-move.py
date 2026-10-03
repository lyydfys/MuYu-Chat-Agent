#!/usr/bin/env python3
"""Move a member (or a run of adjacent members) out of MainViewModel into an Operations file.

This exists because the same six failures kept recurring while doing this by hand:

  * the boundary being measured wrong (a 35-line member once measured as 261; a 134-line one as 186);
  * a `private` ViewModel member or extension being referenced from the new file;
  * a same-package declaration SHADOWING an imported type of the same name;
  * import paths guessed from where a type "looks like" it lives;
  * a hand-written import block that misses what the body actually uses;
  * duplicate imports / duplicate `package` lines when a generated header is concatenated.

So the mover never guesses: it derives the import set from the OWNER's own import table, keyed by the
symbols the moved code really uses, and it refuses to write anything until the substitution left no
bare reference to a ViewModel-private name behind.

Usage:
    python refactor-move.py --member saveAssistantProfile --out SaveAssistantProfileOperations.kt \
        --port-name SaveAssistantProfilePort --function saveAssistantProfile \
        --param sessionless... [--suspend]

The substitution map and the port/interface/delegate text are supplied by small JSON side files so
that one script covers every slice:
    %TEMP%/mca-move.json   {"subs": [[regex, replacement], ...]}
    %TEMP%/mca-port.txt    the port interface + the moved function's header/doc, ending right before
                           the body is appended
    %TEMP%/mca-delegate.txt the delegate block that replaces the member(s) in MainViewModel.kt
"""

import argparse
import json
import os
import re
import sys

REPO = r"E:\MAC"
APP = os.path.join(REPO, "app", "src", "main", "java", "com", "muyuchat", "mca")
VM_PATH = os.path.join(APP, "MainViewModel.kt")
TMP = os.environ.get("TEMP", ".")

MEMBER_DECL = re.compile(
    r"^    (?:@\w+\s+)?"
    r"(?:private |internal |public |protected |override |open |suspend |inline |operator |lateinit |"
    r"const |abstract |final |data |sealed |enum |annotation |companion |tailrec |external )*"
    r"(?:fun|val|var|class|object|interface|init|constructor)\b"
)
VM_PRIVATE = re.compile(
    r"^    (?:@\w+\s+)*(?:private|@Volatile\s+private)\s+(?:suspend\s+)?(?:fun|val|var)\s+"
    r"(?:[\w.<>]+\.)?(\w+)",
    re.M,
)


def member_span(lines, name):
    pat = re.compile(r"\bfun\s+(?:[\w.<>]+\.)?" + re.escape(name) + r"\s*\(")
    for i, line in enumerate(lines):
        if not MEMBER_DECL.match(line) or not pat.search(line):
            continue
        sig_end, depth = None, 0
        for k in range(i, min(len(lines), i + 40)):
            depth += lines[k].count("(") - lines[k].count(")")
            if depth <= 0 and lines[k].rstrip().endswith("{"):
                sig_end = k
                break
        if sig_end is None:
            raise SystemExit("member %s has no braced body" % name)
        nxt = sig_end + 1
        while nxt < len(lines) and not MEMBER_DECL.match(lines[nxt]):
            nxt += 1
        close = next((x for x in range(nxt - 1, sig_end, -1) if lines[x].strip() == "}"), None)
        if close is None:
            raise SystemExit("member %s has no closing brace before the next member" % name)
        return i, sig_end, close
    raise SystemExit("member %s not found" % name)


def rebuild_imports(text, owner_text):
    """Single import block, derived from the owner's table, keyed by real symbol use."""
    owner_imports = {}
    for match in re.finditer(r"(?m)^import ([\w.]+)(?: as (\w+))?$", owner_text):
        simple = match.group(2) or match.group(1).split(".")[-1]
        owner_imports[simple] = "import " + match.group(1) + (
            (" as " + match.group(2)) if match.group(2) else "")
    lines = text.split("\n")
    pkg = next((l for l in lines if l.startswith("package ")), "package com.muyuchat.mca")
    body = [l for l in lines if not l.startswith(("package ", "import "))]
    while body and body[0].strip() == "":
        body.pop(0)
    code = "\n".join(l.split("//")[0] for l in body)
    wanted = {line for simple, line in owner_imports.items()
              if re.search(r"(?<![\w.])" + re.escape(simple) + r"\b", code)}
    # Keep any import the port file already carried for symbols that come from elsewhere.
    for l in lines:
        if l.startswith("import "):
            wanted.add(l)
    return "\n".join([pkg, ""] + sorted(wanted) + [""] + body)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--member", required=True, action="append",
                    help="member name; repeat to move a run of adjacent members together")
    ap.add_argument("--out", required=True)
    ap.add_argument("--port-file", default=os.path.join(TMP, "mca-port.txt"))
    ap.add_argument("--delegate-file", default=os.path.join(TMP, "mca-delegate.txt"))
    ap.add_argument("--subs-file", default=os.path.join(TMP, "mca-move.json"))
    args = ap.parse_args()

    owner = open(VM_PATH, encoding="utf-8", errors="replace").read()
    lines = owner.split("\n")
    subs = [(p, r) for p, r in json.load(open(args.subs_file, encoding="utf-8"))["subs"]]

    spans = [member_span(lines, name) for name in args.member]
    for prev, cur in zip(spans, spans[1:]):
        if cur[0] - prev[2] > 3:
            raise SystemExit("members %s are not adjacent" % args.member)

    bodies = []
    for name, (start, sig_end, close) in zip(args.member, spans):
        body = "\n".join(lines[sig_end + 1:close])
        for pattern, replacement in subs:
            body = re.sub(pattern, replacement, body)
        # The substitution must leave nothing that can only be resolved from inside the ViewModel.
        private = {m.group(1) for m in VM_PRIVATE.finditer(owner)}
        left = sorted({n for n in private
                       if re.search(r"(?<![\w.])" + re.escape(n) + r"(?![\w])", body)})
        if left:
            print("  REFUSING: %s still references ViewModel-private %s" % (name, left))
            return 1
        bodies.append(body)
        print("  %-34s body %d lines, %d port. calls"
              % (name, len(body.split("\n")), body.count("port.")))

    port = open(args.port_file, encoding="utf-8").read().rstrip("\n")
    assembled = port + "\n\n" + "\n\n".join(b + "\n}" if False else b for b in bodies) + "\n}\n"
    assembled = rebuild_imports(assembled, owner)
    out_path = os.path.join(APP, args.out)
    open(out_path, "w", encoding="utf-8", newline="\n").write(assembled)
    print("  wrote %s (%d lines, %d imports)"
          % (args.out, len(assembled.split("\n")),
             len([l for l in assembled.split("\n") if l.startswith("import ")])))
    if assembled.count("package ") != 1:
        print("  REFUSING: %d package lines" % assembled.count("package "))
        return 1

    delegate = open(args.delegate_file, encoding="utf-8").read().rstrip("\n")
    merged = "\n".join(lines[:spans[0][0]] + delegate.split("\n") + lines[spans[-1][2] + 1:])
    open(VM_PATH, "w", encoding="utf-8", newline="\n").write(merged)
    print("  MainViewModel: %d -> %d lines (net %+d)"
          % (len(lines), len(merged.split("\n")), len(merged.split("\n")) - len(lines)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
