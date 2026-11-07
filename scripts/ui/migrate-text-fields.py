#!/usr/bin/env python3
"""Migrate `OutlinedTextField(` call sites to the shared `McaTextField(`.

Why a script instead of 89 hand edits: the parameter names of `McaTextField` mirror
`OutlinedTextField`, so a call site migrates by renaming the symbol and adding the import. Doing
that by hand invites silent drift (a missed import, a call site left behind, one file converted
twice); doing it here is repeatable and auditable.

Safety rules:
  * A call is rewritten only when every argument is a named argument (`name = value`) or the two
    positional ones (`value`, `onValueChange`) match the shared signature. A call with a shape this
    script does not understand is reported and left untouched.
  * `trailingIcon` is preserved verbatim: `McaTextField` gives a caller-supplied icon priority over
    its own clear button, so a password toggle keeps working.
  * Imports are added only when the file actually references `McaTextField` afterwards and does not
    already import it.
  * Files are written only when the whole rewrite succeeded; a partially migrated file is never left
    behind.

Usage:
  python scripts/ui/migrate-text-fields.py --dry-run [paths...]
  python scripts/ui/migrate-text-fields.py --apply   [paths...]
"""
import io
import os
import re
import sys

REPO = r"E:\MAC"
IMPORT_LINE = "import com.muyuchat.core.designsystem.McaTextField"
SKIP_MARKERS = (
    "McaTextField(",
    "McaReadOnlyTextField(",
)


def split_top_level_args(text, start):
    """Split the argument list that begins right after `(` at [start]."""
    depth = 1
    i = start
    args = []
    current = []
    quote = None
    triple = False
    while i < len(text):
        ch = text[i]
        if triple:
            if text.startswith('"""', i):
                current.append('"""')
                i += 3
                triple = False
                continue
            current.append(ch)
            i += 1
            continue
        if quote:
            current.append(ch)
            if ch == "\\":
                current.append(text[i + 1] if i + 1 < len(text) else "")
                i += 2
                continue
            if ch == quote:
                quote = None
            i += 1
            continue
        if text.startswith('"""', i):
            current.append('"""')
            i += 3
            triple = True
            continue
        if ch in "\"'":
            quote = ch
            current.append(ch)
            i += 1
            continue
        if ch == "(" or ch == "[" or ch == "{":
            depth += 1
            current.append(ch)
            i += 1
            continue
        if ch == ")" or ch == "]" or ch == "}":
            depth -= 1
            if depth == 0:
                args.append("".join(current))
                return args, i
            current.append(ch)
            i += 1
            continue
        if ch == "," and depth == 1:
            args.append("".join(current))
            current = []
            i += 1
            continue
        current.append(ch)
        i += 1
    return None, -1


NAMED = re.compile(r"^\s*(?:@[A-Za-z]+:?\s*)?([A-Za-z_][A-Za-z0-9_]*)\s*=", re.S)

# Every named argument the shared wrapper accepts. A call using anything else (prefix, suffix, ...)
# is left alone: silently dropping an argument would change what the screen shows.
SUPPORTED_NAMES = {
    "value", "onValueChange", "modifier", "enabled", "readOnly", "textStyle", "label",
    "placeholder", "leadingIcon", "trailingIcon", "supportingText", "isError",
    "visualTransformation", "keyboardOptions", "keyboardActions", "singleLine",
    "maxLines", "minLines", "interactionSource", "shape", "colors",
    "clearGlyphSize", "showClearButton",
}


def is_named(arg):
    return bool(NAMED.match(arg))


def convert_call(match_text, args):
    """Return the migrated argument text, or None when the shape is unsupported."""
    positional = []
    for arg in args:
        if not arg.strip():
            continue
        if not is_named(arg):
            positional.append(arg.strip())
            continue
        name = NAMED.match(arg).group(1)
        if name not in SUPPORTED_NAMES:
            return None
    if len(positional) > 2:
        return None
    # The first two positional arguments of OutlinedTextField are value, onValueChange; the shared
    # wrapper declares them first as well, so position is preserved.
    return args


def migrate_file(path, apply):
    text = io.open(path, encoding="utf-8", newline="").read()
    newline = "\r\n" if text.count("\r\n") * 2 > text.count("\n") else "\n"
    body = text.replace("\r\n", "\n")
    if "OutlinedTextField(" not in body:
        return None
    if any(marker in body for marker in SKIP_MARKERS):
        return ("skipped: already uses the shared wrapper", 0)

    out = []
    index = 0
    converted = 0
    problems = []
    while True:
        found = body.find("OutlinedTextField(", index)
        if found < -1 or found == -1:
            break
        # Reject `SomeOutlinedTextField(`, which is a different symbol.
        if found > 0 and (body[found - 1].isalnum() or body[found - 1] in "_."):
            out.append(body[index:found + len("OutlinedTextField(")])
            index = found + len("OutlinedTextField(")
            continue
        paren_start = found + len("OutlinedTextField(")
        args, close = split_top_level_args(body, paren_start)
        if args is None:
            problems.append("unbalanced argument list at offset %d" % found)
            out.append(body[index:paren_start])
            index = paren_start
            continue
        args = [a for a in args if a.strip()]
        if convert_call(body[found:close], args) is None:
            problems.append("unsupported argument shape at offset %d" % found)
            out.append(body[index:paren_start])
            index = paren_start
            continue
        out.append(body[index:found])
        out.append("McaTextField(")
        out.append(",".join(args))
        out.append(")")
        index = close + 1
        converted += 1
    out.append(body[index:])
    migrated = "".join(out)

    if problems:
        return ("problems: " + "; ".join(problems), converted)
    if migrated == body:
        return ("no change", 0)

    if IMPORT_LINE not in migrated:
        anchor = "import com.muyuchat.core.designsystem."
        pos = migrated.find(anchor)
        if pos < 0:
            return ("no designsystem import block to extend", converted)
        # Insert in sorted position among the designsystem imports.
        line_start = migrated.rfind("\n", 0, pos) + 1
        insert = IMPORT_LINE + "\n"
        existing = sorted(
            line for line in migrated.split("\n")
            if line.startswith("import com.muyuchat.core.designsystem.")
        )
        placement = line_start
        for line in existing:
            if line < IMPORT_LINE:
                placement = migrated.find("\n", migrated.find(line, line_start)) + 1
        migrated = migrated[:placement] + insert + migrated[placement:]

    if apply:
        with open(path, "w", encoding="utf-8", newline="") as handle:
            handle.write(migrated.replace("\n", newline) if newline == "\r\n" else migrated)
    return (None, converted)


def main():
    apply = "--apply" in sys.argv
    targets = [a for a in sys.argv[1:] if not a.startswith("--")]
    roots = []
    if targets:
        roots = targets
    else:
        for module in ("app", "core", "feature"):
            roots.append(os.path.join(REPO, module))

    files = []
    for root in roots:
        if os.path.isfile(root):
            files.append(root)
            continue
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames[:] = [d for d in dirnames if d not in {"build", ".gradle", ".git", "third_party"}]
            if "/src/main/" not in dirpath.replace(os.sep, "/"):
                continue
            for fn in filenames:
                if fn.endswith(".kt"):
                    files.append(os.path.join(dirpath, fn))

    total_files = 0
    total_calls = 0
    issues = []
    for path in sorted(files):
        result = migrate_file(path, apply)
        if result is None:
            continue
        note, count = result
        if note:
            issues.append((path, note))
            continue
        if count:
            total_files += 1
            total_calls += count
            rel = path.replace(REPO + os.sep, "")
            print("  %-70s %d call(s)" % (rel, count))

    print("\nfiles: %d  calls: %d" % (total_files, total_calls))
    if issues:
        print("needs manual attention: %d" % len(issues))
        for path, note in issues[:20]:
            print("  !", path.replace(REPO + os.sep, ""), "->", note)
    print("dry run" if not apply else "WRITTEN")


if __name__ == "__main__":
    main()
