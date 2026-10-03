#!/usr/bin/env python3
"""Pre-flight analysis for moving a member out of MainViewModel.

Every one of these checks exists because it was learned the hard way:

1. BOUNDARY -- a member ends at the last ``}``-only line before the NEXT MEMBER
   DECLARATION (fun/val/var/class/object/interface/init/constructor).  Counting
   only ``fun`` once measured a 35-line member as 261 lines; scanning backwards
   from a fixed offset once measured a 134-line member as 186.
2. PRIVATE ACCESS -- a moved body may not reference a ``private`` ViewModel
   member or extension; those have to go behind the port.  Missing this broke the
   build twice (``normalized``, ``toGenerationParams``).
3. SHADOWING -- a same-package declaration SHADOWS an imported type of the same
   name, so exporting imports by symbol name alone can emit the wrong type.
   This cost a full compile cycle on ``CandidateCanaryResult``.
4. REAL IMPORT PATHS -- the package must come from the actual declaration, not
   from where the type "looks like" it lives (``DeviceProfile`` is in
   ``core.deviceprofile``, the measurement types are in ``core.tuning``).
5. ANCHOR POLLUTION -- a delegate block introduces ``override fun <name>(``
   ahead of the real declaration, so ``indexOf("fun <name>(")`` used by source
   -text tests starts matching the one-line forwarder.

Usage:
    python refactor-extract.py <MemberName> [--method-type]
"""

import argparse
import os
import re
import subprocess
import sys

REPO = r"E:\MAC"
APP_SRC = os.path.join(REPO, "app", "src", "main", "java", "com", "muyuchat", "mca")
VM_PATH = os.path.join(APP_SRC, "MainViewModel.kt")

MEMBER_DECL = re.compile(
    r"^    (?:@\w+\s+)?"
    r"(?:private |internal |public |protected |override |open |suspend |inline |operator |"
    r"lateinit |const |abstract |final |data |sealed |enum |annotation |companion |tailrec |external )*"
    r"(?:fun|val|var|class|object|interface|init|constructor)\b"
)
# A declaration that is private to the ViewModel: unusable from another file.
VM_PRIVATE = re.compile(
    r"^    (?:@\w+\s+)*(?:private|@Volatile\s+private)\s+"
    r"(?:suspend\s+)?(?:fun|val|var)\s+(?:[\w.<>]+\.)?(\w+)",
    re.M,
)
IDENT = re.compile(r"(?<![\w.])([A-Za-z_]\w{2,})")


def find_member(lines, name):
    """Locate a member by the last identifier of its name, then apply the boundary rule."""
    pat = re.compile(r"\bfun\s+(?:[\w.<>]+\.)?" + re.escape(name) + r"\s*\(")
    for i, line in enumerate(lines):
        if not MEMBER_DECL.match(line) or not pat.search(line):
            continue
        sig_end = None
        depth = 0
        for k in range(i, min(len(lines), i + 40)):
            depth += lines[k].count("(") - lines[k].count(")")
            if depth <= 0 and lines[k].rstrip().endswith("{"):
                sig_end = k
                break
        if sig_end is None:
            return i, None, None, "expression body or unbalanced signature"
        nxt = sig_end + 1
        while nxt < len(lines) and not MEMBER_DECL.match(lines[nxt]):
            nxt += 1
        close = next(
            (x for x in range(nxt - 1, sig_end, -1) if lines[x].strip() == "}"), None
        )
        if close is None:
            return i, sig_end, None, "no closing brace before the next member"
        return i, sig_end, close, None
    return None, None, None, "member not found"


def declaration_of(symbol):
    """Where is this symbol really declared?  Search every module, keyed by file."""
    out = subprocess.run(
        ["git", "-C", REPO, "grep", "-nE",
         r"^(internal |private |public |abstract |sealed |data |value |enum )*"
         r"(class|object|interface|fun|val|var|typealias) " + re.escape(symbol) + r"\b",
         "--", "*.kt"],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    ).stdout
    hits = []
    for line in out.split("\n"):
        if not line.strip():
            continue
        path, lineno, text = line.split(":", 2)
        pkg = None
        try:
            with open(os.path.join(REPO, path.replace("/", os.sep)),
                      encoding="utf-8", errors="replace") as handle:
                for src_line in handle:
                    if src_line.startswith("package "):
                        pkg = src_line.split()[1]
                        break
                    if not src_line.startswith(("//", "/*", "*", "@")):
                        if "package" not in src_line and src_line.strip():
                            break
        except OSError:
            pass
        hits.append((symbol, pkg, path, lineno, text.strip()))
    return hits


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("member")
    args = parser.parse_args()

    with open(VM_PATH, encoding="utf-8", errors="replace") as handle:
        vm = handle.read()
    lines = vm.split("\n")

    print("=" * 78)
    print("1. BOUNDARY")
    print("=" * 78)
    start, sig_end, close, err = find_member(lines, args.member)
    if err:
        print("   FAILED:", err)
        return 1
    body = lines[sig_end + 1:close]
    print("   member      : lines %d..%d" % (start + 1, close + 1))
    print("   signature   : lines %d..%d" % (start + 1, sig_end + 1))
    print("   body        : %d lines" % len(body))
    print("   next member : line %d" % (close + 2))
    print("   signature   : %s" % lines[start].strip())

    text = "\n".join(body)

    print()
    print("=" * 78)
    print("2. PRIVATE VIEWMODEL ACCESS (must go behind the port)")
    print("=" * 78)
    private_names = {}
    for match in VM_PRIVATE.finditer(vm):
        private_names.setdefault(match.group(1), match.start())
    hits = []
    for name, offset in private_names.items():
        # Three call shapes have to be covered, and the first version missed two of them:
        #   * `name(...)`      -- a plain member call or property read/write;
        #   * `x.name(...)`    -- a PRIVATE EXTENSION, which is how `normalized` and
        #                         `toGenerationParams` were missed; the `(?<![\w.])` guard hid it;
        #   * bare `name`      -- a property read/write with no call at all, e.g.
        #                         `val model = activeModelForRuntimeProfile`.
        plain = re.search(r"(?<![\w.])" + re.escape(name) + r"(?![\w])", text) is not None
        qualified = re.search(r"\.\s*" + re.escape(name) + r"\s*[\(<]?[^\w]", text) is not None
        if not (plain or qualified):
            continue
        bare = [
            line for line in text.split("\n")
            if re.search(r"(?<![\w.])" + re.escape(name) + r"(?![\w])", line.split("//")[0])
            or re.search(r"\.\s*" + re.escape(name) + r"\s*\(", line.split("//")[0])
        ]
        if not bare:
            continue
        assigned = any(
            re.search(r"(?<![\w.])" + re.escape(name) + r"\s*(=[^=]|\+=|-=)", line)
            for line in bare
        )
        # A private extension is unusable from another file no matter how it is called, so say so.
        is_extension = bool(
            re.search(r"(?m)^    (?:@\w+\s+)*private (?:suspend )?fun [\w.<>]+\.\s*"
                      + re.escape(name) + r"\s*\(", vm)
        )
        shape = "extension" if is_extension else ("written" if assigned else "read")
        hits.append((name, vm[:offset].count("\n") + 1, shape))
    if hits:
        for name, lineno, usage in sorted(hits, key=lambda item: item[1]):
            print("   PRIVATE  %-44s declared at line %-6d %s" % (name, lineno, usage))
    else:
        print("   none")
    print("   _uiState uses            : %d" % text.count("_uiState"))
    print("   viewModelScope uses      : %d" % text.count("viewModelScope"))
    print("   withContext uses         : %d" % text.count("withContext"))
    print("   .launch uses             : %d" % len(re.findall(r"\.launch\b", text)))

    print()
    print("=" * 78)
    print("3. SYMBOLS THE BODY USES -- real declaration + shadowing risk")
    print("=" * 78)
    vm_imports = {}
    for line in lines:
        match = re.match(r"^import ([\w.]+)(?: as (\w+))?$", line)
        if match:
            vm_imports[match.group(2) or match.group(1).split(".")[-1]] = match.group(1)
    declared_in_package = set()
    for entry in os.listdir(APP_SRC):
        if not entry.endswith(".kt"):
            continue
        with open(os.path.join(APP_SRC, entry), encoding="utf-8", errors="replace") as handle:
            for match in re.finditer(
                r"^(?:internal |private |public |sealed |data |value |enum )*"
                r"(?:class|object|interface|typealias) (\w+)", handle.read(), re.M
            ):
                declared_in_package.add(match.group(1))
    candidates = sorted({name for name in IDENT.findall(text)
                         if import_like(name, declared_in_package, vm_imports)})
    for name in candidates:
        same = name in declared_in_package
        imported = vm_imports.get(name)
        note = []
        if same and imported:
            note.append("SHADOWED: same-package declaration wins over the import")
        if same:
            note.append("same package -> needs no import")
        elif imported:
            note.append("import " + imported)
        print("   %-44s %s" % (name, " | ".join(note) if note else "unresolved here"))

    print()
    print("=" * 78)
    print("4. ANCHOR POLLUTION (source-text tests)")
    print("=" * 78)
    first = vm.find("fun " + args.member + "(")
    real = re.search(r"(?m)^    (?:private |internal |public |protected )*(?:suspend )?fun "
                     r"(?:[\w.<>]+\.)?" + re.escape(args.member) + r"\(", vm)
    if first >= 0 and real:
        first_line = vm[:first].count("\n") + 1
        real_line = vm[:real.start()].count("\n") + 1
        if first_line < real_line:
            print("   POLLUTED: first 'fun %s(' at %d precedes the declaration at %d"
                  % (args.member, first_line, real_line))
        else:
            print("   ok: first match is the declaration (line %d)" % real_line)
    out = subprocess.run(
        ["git", "-C", REPO, "grep", "-nIE",
         r'functionBody\([^)]*"' + re.escape(args.member) + r'"', "--", "app/src/test",
         "app/src/androidTest", "api/local/src/test", "core/engine/src/test"],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    ).stdout
    anchors = [line for line in out.split("\n") if line.strip()]
    print("   functionBody anchors naming it: %d" % len(anchors))
    for line in anchors:
        print("      " + line[:150])
    return 0


def import_like(name, declared_in_package, vm_imports):
    if name in declared_in_package or name in vm_imports:
        return True
    return False


if __name__ == "__main__":
    sys.exit(main())
