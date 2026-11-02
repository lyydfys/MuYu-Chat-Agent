#!/usr/bin/env python3
"""D01/D02/D03 source boundary gate. JVM behavior and device acceptance are separate checks."""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
APP_REL = Path("app/src/main/java/com/muyuchat/mca")
# Existing composition entry extensions, not a prefix exemption for future business files.
VM_COMPOSITION_FILES = {
    "MainViewModel.kt", "MainViewModelAgentDelegates.kt", "MainViewModelChatExecutionDelegates.kt",
    "MainViewModelAssistantAndKnowledgeDelegates.kt", "MainViewModelAppUpdateDelegates.kt",
    "MainViewModelChatSessionDelegates.kt", "MainViewModelCloudAndHubDelegates.kt",
    "MainViewModelDshBindingRefresh.kt", "MainViewModelCoordinators.kt",
    "MainViewModelContainerProperties.kt", "MainViewModelIntentDelegates.kt",
    "MainViewModelImageDelegates.kt", "MainViewModelLocalApiDelegates.kt",
    "MainViewModelModelDelegates.kt", "MainViewModelPromptAndImageTurnDelegates.kt",
    "MainViewModelParamDelegates.kt", "MainViewModelTypes.kt",
}
# The startup ViewModel constructs the application container before handing off the composition
# context. This is one reviewed root entry, not an exemption for arbitrary *ViewModel.kt files.
CONTAINER_COMPOSITION_ENTRY_FILES = VM_COMPOSITION_FILES | {"AppStartupViewModel.kt"}


def code_only(source: str) -> str:
    """Mask Kotlin comments/strings, preserving offsets and line numbers; block comments nest."""
    out = list(source)
    i = 0
    while i < len(source):
        start = i
        if source.startswith("//", i):
            end = source.find("\n", i)
            i = len(source) if end < 0 else end
        elif source.startswith("/*", i):
            i += 2
            depth = 1
            while i < len(source) and depth:
                if source.startswith("/*", i):
                    depth += 1
                    i += 2
                elif source.startswith("*/", i):
                    depth -= 1
                    i += 2
                else:
                    i += 1
        elif source.startswith('"""', i):
            end = source.find('"""', i + 3)
            i = len(source) if end < 0 else end + 3
        elif source[i] in ('"', "'"):
            quote = source[i]
            i += 1
            while i < len(source):
                if source[i] == "\\":
                    i += 2
                elif source[i] == quote:
                    i += 1
                    break
                else:
                    i += 1
        else:
            i += 1
            continue
        for j in range(start, min(i, len(out))):
            if out[j] != "\n":
                out[j] = " "
    return "".join(out)


def inspect_source(relative: Path, source: str) -> list[str]:
    code = code_only(source)
    errors = []
    in_composition = relative.parts[0] == "composition"
    is_vm_composition = len(relative.parts) == 1 and relative.name in VM_COMPOSITION_FILES
    is_container_composition_entry = (
        len(relative.parts) == 1 and relative.name in CONTAINER_COMPOSITION_ENTRY_FILES
    )
    store_owner = relative == Path("UiStateStore.kt")
    patterns = {
        "retired coordinator host": r"\bMainCoordinatorHost(?:Adapter)?\b",
        "mutable-flow escape hatch": r"\bownerFlow\b",
        "root-returning reducer": r"\(\s*(?:\w+\s*:\s*)?MainUiState\s*\)\s*->\s*MainUiState\b",
        "untyped injected dependency": r"\binjected\w*\s*:\s*Any\??\b",
        "unbounded writer type": r"\bUiStateWriter\s*<\s*(?:MainUiState|UiStateFields|Any|\*)\s*>",
    }
    if not store_owner:
        patterns["global mutable flow outside owner"] = r"\bMutableStateFlow\s*<\s*MainUiState\s*>"
    if not (in_composition or is_vm_composition or store_owner):
        patterns["root store in business code"] = r"\bUiStateStore\b"
    if not (in_composition or is_container_composition_entry):
        # One token catches simple/fully qualified types, constructor references and imports,
        # including aliased imports. Comments and strings have already been masked above.
        patterns["application container in business code"] = r"\bMcaAppContainer\b"
    if relative not in {Path("UiStateStore.kt"), Path("UiStateWriter.kt"), Path("composition/UiStateDomainWriters.kt")}:
        patterns["raw writer issuance outside the reviewed factories"] = r"(?:\.\s*writer\s*(?:<[^>]+>)?\s*\(|\bUiStateWriter\s*\()"
    if not (in_composition or is_vm_composition):
        patterns["business imports a writer factory"] = r"\bimport\s+com\.muyuchat\.mca\.composition\.(?:\*(?!\w)|\w+Writer\b)"
    for label, pattern in patterns.items():
        for match in re.finditer(pattern, code):
            line = code.count("\n", 0, match.start()) + 1
            errors.append(f"{relative.as_posix()}:{line}: {label}")
    return errors


def verify_authorities(app: Path, manifest: dict[str, list[str]]) -> list[str]:
    errors = []
    factories = code_only((app / "composition/UiStateDomainWriters.kt").read_text(encoding="utf-8-sig"))
    for name, fields in manifest.items():
        path = app / f"{name}.kt"
        if not path.is_file():
            errors.append(f"Missing authority source: {name}")
            continue
        code = code_only(path.read_text(encoding="utf-8-sig"))
        constructor = re.search(rf"data\s+class\s+{name}\s*\((.*?)\)\s*:\s*UiStateFields", code, re.S)
        actual = re.findall(r"\bval\s+(\w+)\s*:", constructor[1]) if constructor else []
        if actual != ["snapshot", *fields]:
            errors.append(f"{name}: constructor does not match reviewed field authority")
        merge = re.search(rf"fun\s+{name}\.mergeInto\(current:\s*MainUiState\):\s*MainUiState\s*=\s*current.copy\((.*?)\)", code, re.S)
        expected = "".join(f"{field}={field}," for field in fields)
        if not merge or re.sub(r"\s+", "", merge[1]) != expected:
            errors.append(f"{name}: merge is not the fixed whitelist into the current CAS snapshot")
        stem = name.removesuffix("Fields")
        factory = stem[:1].lower() + stem[1:] + "Writer"
        expected_factory = (
            f"internal fun UiStateStore.{factory}(): UiStateWriter<{name}> = writer("
            f"project = {{ it.to{name}() }}, merge = {{ current, fields -> fields.mergeInto(current) }},)"
        )
        if re.sub(r"\s+", "", expected_factory) not in re.sub(r"\s+", "", factories):
            errors.append(f"{name}: missing or widened composition factory")
    actual_types = set(re.findall(r"UiStateWriter\s*<\s*(\w+)\s*>", factories))
    if actual_types != set(manifest):
        errors.append("Composition factory types differ from the reviewed authority manifest")
    return errors


def check(repo: Path) -> list[str]:
    app = repo / APP_REL
    if not app.is_dir():
        return [f"Missing application source directory: {app}"]
    errors = []
    for path in sorted(app.rglob("*.kt")):
        errors.extend(inspect_source(path.relative_to(app), path.read_text(encoding="utf-8-sig")))
    for retired in ("MainCoordinatorHost.kt", "MainCoordinatorHostAdapter.kt"):
        if (app / "composition" / retired).exists():
            errors.append(f"Retired host file restored: {retired}")
    manifest = json.loads((repo / "tools/architecture/state_write_authorities.json").read_text(encoding="utf-8"))
    errors.extend(verify_authorities(app, manifest))
    vm_lines = len((app / "MainViewModel.kt").read_text(encoding="utf-8-sig").splitlines())
    if vm_lines > 1000:
        errors.append(f"MainViewModel has {vm_lines} physical lines; ceiling is 1000")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=REPO_ROOT)
    args = parser.parse_args()
    errors = check(args.root)
    print("SCOPE: app Kotlin state authority, retired coordinator host, typed injection and container isolation;")
    print("fixed fields/merges/factories are checked against the reviewed authority manifest.")
    for error in errors:
        print(f"FAIL: {error}")
    print(f"{'FAIL' if errors else 'PASS'}: D01/D02/D03 source boundaries ({len(errors)} violations)")
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
