#!/usr/bin/env python3
"""
verify_qnn_runtime_compiles.py

Compiles every translation unit of the `mca_qnn_runtime` CMake target with the Android NDK
clang++ in syntax-only mode, using the same language level and feature macros the real build
sets.  Fails if any module does not compile.

Why this gate exists
--------------------
The QNN native bridge was slimmed from ~11.9k lines to ~1k by moving its graph execution,
conditioning, semantic and quantizer logic into `core/native/src/main/cpp/mca/qnn/*`.  The old
gate for that work counted *lines of the bridge* (`BRIDGE_LINE_LIMIT`), so the extraction read as
complete while the modules that were supposed to receive the code did not compile at all: a class
referenced by qnn_semantic_pipeline.cpp had no definition anywhere in the tree, structs had been
truncated relative to their call sites, and helper functions had never been carried over.

A line count can never establish that a module is real.  This gate compiles the modules.

The source list is read from CMakeLists.txt rather than hard-coded, so a newly added module is
covered automatically and a deliberately-uncompiled one stays skipped with its stated reason.
"""

import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
CPP_ROOT = REPO_ROOT / "core" / "native" / "src" / "main" / "cpp"
CMAKE_LISTS = CPP_ROOT / "CMakeLists.txt"
TARGET_NAME = "mca_qnn_runtime"

# Feature macros the CMake target sets for this configuration.  MCA_QNN_SDK_ROOT_CONFIGURED is
# defined by CMakeLists.txt when a QNN SDK root is present; without it the sources that report
# their build configuration fail to parse, which would look like a source error.
FEATURE_DEFINES = [
    "MCA_QNN_SYSTEM_LOADER_HAS_SDK=1",
    "MCA_WITH_QNN_SDK_HEADERS=1",
    "MCA_QNN_SDK_ROOT_CONFIGURED=1",
]

NDK_FALLBACKS = [
    Path("D:/model/android-sdk/ndk/29.0.13113456"),
    Path("E:/model/android-sdk/ndk/29.0.13113456"),
]
QNN_SDK_FALLBACKS = [
    Path("E:/tools/qairt-sdk/2.45.0.260326"),
]


def find_ndk():
    for env_name in ("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "MCA_NDK_ROOT"):
        value = os.environ.get(env_name)
        if value and (Path(value) / "toolchains").is_dir():
            return Path(value)
    for env_name in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        value = os.environ.get(env_name)
        if not value:
            continue
        ndk_dir = Path(value) / "ndk"
        if ndk_dir.is_dir():
            candidates = sorted(p for p in ndk_dir.iterdir() if p.is_dir())
            if candidates:
                return candidates[-1]
    for candidate in NDK_FALLBACKS:
        if (candidate / "toolchains").is_dir():
            return candidate
    return None


def find_qnn_sdk():
    value = os.environ.get("MCA_QNN_SDK_ROOT")
    if value and (Path(value) / "include").is_dir():
        return Path(value)
    for candidate in QNN_SDK_FALLBACKS:
        if (candidate / "include").is_dir():
            return candidate
    return None


def read_target_sources():
    """Return the .cpp files listed for TARGET_NAME, skipping commented-out entries."""
    if not CMAKE_LISTS.is_file():
        return None, f"CMakeLists.txt not found at {CMAKE_LISTS}"
    text = CMAKE_LISTS.read_text(encoding="utf-8", errors="replace")
    # The argument list ends at the matching ')'.  It may sit on its own line or directly after the
    # last entry (`.../qnn_semantic_pipeline.cpp)`), so the scan tracks paren depth rather than
    # relying on a line shape -- a greedy/non-greedy shortcut here silently swallowed every
    # following target's sources.
    head = re.search(r"add_library\s*\(\s*%s\b" % re.escape(TARGET_NAME), text)
    if not head:
        return None, f"add_library({TARGET_NAME} ...) not found in CMakeLists.txt"
    depth = 1
    index = head.end()
    while index < len(text) and depth:
        char = text[index]
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
        index += 1
    if depth:
        return None, f"unterminated add_library({TARGET_NAME} ...) in CMakeLists.txt"
    body = text[head.end():index - 1]

    sources, skipped = [], []
    for raw in body.splitlines():
        line = raw.strip()
        if not line.endswith(".cpp") and not line.endswith(".cpp)"):
            continue
        line = line.rstrip(")")
        if line.startswith("#"):
            skipped.append(line.lstrip("# ").strip())
        else:
            sources.append(line)
    return (sources, skipped), None


def main():
    print("=================================================================")
    print("   QNN RUNTIME MODULES: DO THEY ACTUALLY COMPILE?                ")
    print("=================================================================\n")

    parsed, error = read_target_sources()
    if error:
        print(f"FAILED: {error}")
        return 1
    sources, skipped = parsed
    print(f"--> [QNN Compile Gate] target={TARGET_NAME} sources={len(sources)} skipped={len(skipped)}")
    for item in skipped:
        print(f"    skipped (deliberately): {item}")

    ndk = find_ndk()
    if ndk is None:
        print("FAILED: no Android NDK found (set ANDROID_NDK_HOME or ANDROID_SDK_ROOT).")
        return 1
    clang = ndk / "toolchains" / "llvm" / "prebuilt" / "windows-x86_64" / "bin" / "clang++.exe"
    if not clang.is_file():
        clang = ndk / "toolchains" / "llvm" / "prebuilt" / "linux-x86_64" / "bin" / "clang++"
    if not clang.is_file():
        print(f"FAILED: clang++ not found under {ndk}")
        return 1
    sysroot = ndk / "toolchains" / "llvm" / "prebuilt" / "windows-x86_64" / "sysroot"
    if not sysroot.is_dir():
        sysroot = ndk / "toolchains" / "llvm" / "prebuilt" / "linux-x86_64" / "sysroot"

    sdk = find_qnn_sdk()
    if sdk is None:
        print("FAILED: QNN SDK not found (set MCA_QNN_SDK_ROOT).")
        return 1
    sdk_include = sdk / "include"

    base_cmd = [
        str(clang),
        "-fsyntax-only",
        "-std=c++17",
        # NOTE: exceptions are deliberately left ENABLED.  The project does not build with
        # -fno-exceptions, and adding it makes every `try` in these modules a false error.
        "--target=aarch64-none-linux-android26",
        f"--sysroot={sysroot}",
    ]
    base_cmd += [f"-D{d}" for d in FEATURE_DEFINES]
    base_cmd += [
        f"-I{CPP_ROOT}",
        f"-I{CPP_ROOT / 'mca' / 'qnn'}",
        f"-I{CPP_ROOT / 'mca'}",
        f"-I{sdk_include}",
        f"-I{sdk_include / 'QNN'}",
    ]

    print(f"    ndk:     {ndk}")
    print(f"    clang:   {clang.name}")
    print(f"    sysroot: {sysroot}")
    print(f"    sdk:     {sdk}\n")

    failures = []
    for rel in sources:
        source = CPP_ROOT / rel
        if not source.is_file():
            failures.append((rel, ["file listed in CMakeLists.txt does not exist"]))
            print(f"    MISSING  {rel}")
            continue
        # -o NUL keeps clang from trying to emit anything for a syntax-only run.
        res = subprocess.run(
            base_cmd + [str(source)], capture_output=True, text=True, errors="replace"
        )
        errors = [
            line.strip()
            for line in (res.stderr or "").splitlines()
            if ": error:" in line
        ]
        if res.returncode == 0 and not errors:
            print(f"    OK       {rel}")
        else:
            failures.append((rel, errors or [f"clang exited {res.returncode}"]))
            print(f"    FAILED   {rel}  ({len(errors)} error(s))")

    print()
    if failures:
        print(f"RESULT: FAILED -- {len(failures)} of {len(sources)} module(s) do not compile.\n")
        for rel, errors in failures:
            print(f"  {rel}")
            for line in errors[:6]:
                print(f"      {line}")
            if len(errors) > 6:
                print(f"      ... {len(errors) - 6} more")
        print("\nA translation unit that does not compile carries no migrated behaviour. Do not")
        print("treat the bridge line count as evidence that its logic was moved successfully.")
        return 1

    print(f"RESULT: PASSED -- all {len(sources)} {TARGET_NAME} modules compile.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
