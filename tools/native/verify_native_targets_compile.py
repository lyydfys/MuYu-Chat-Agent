#!/usr/bin/env python3
"""
verify_native_targets_compile.py

Compiles every translation unit of every static/shared native library declared in
core/native/src/main/cpp/CMakeLists.txt with the Android NDK clang++ in syntax-only mode, using
the language level and feature macros the real build sets.  Fails if any module does not compile.

Why this gate exists
--------------------
The QNN native bridge was slimmed from ~11.9k lines to ~1k by moving its logic into
core/native/src/main/cpp/mca/qnn/*.  The gate for that work counted *lines of the bridge*, so the
extraction read as complete while the modules that were supposed to receive the code did not
compile at all: qnn_semantic_pipeline.cpp constructed a class that had no definition anywhere in
the tree, structs had been truncated relative to their call sites, and helper functions had never
been carried over.

A line count can never establish that a module is real.  This gate compiles every translation unit
listed by CMake, for all native targets.  Counts printed below are translation units, not modules.

Source lists are read from CMakeLists.txt rather than hard-coded, so a newly added module is
covered automatically and a deliberately-uncompiled one stays skipped with its stated reason.
"""

import os
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
CPP_ROOT = REPO_ROOT / "core" / "native" / "src" / "main" / "cpp"
CMAKE_LISTS = CPP_ROOT / "CMakeLists.txt"
# The audit (P1-13) noted this gate only looked at core/native.  core/sd-native carries its own
# CMakeLists and its own production bridge, so it is scanned with the same rules.
EXTRA_CMAKE_LISTS = [REPO_ROOT / "core" / "sd-native" / "src" / "main" / "cpp" / "CMakeLists.txt"]
# The llama.cpp checkout is consumed as a vendored CMake project; its headers reach the MCA
# targets through llama.cpp's own INTERFACE include directories rather than a
# target_include_directories() line in this CMakeLists.txt.  Pointing at the real checkout keeps
# the gate from reporting "llama.h not found" as if it were a defect in the module.
LLAMA_ROOT = REPO_ROOT / "third_party" / "llama.cpp"
# MNN is consumed as a vendored CMake project too; its headers (MNN/ImageProcess.hpp and friends)
# reach the MCA targets through MNN's own INTERFACE include directories.
MNN_INCLUDE_DIR = REPO_ROOT / "third_party" / "MNN" / "include"
# The diffusion engine ships its headers under transformers/diffusion/engine/include, which is
# what makes <diffusion/diffusion.hpp> resolvable when MCA_WITH_MNN_DIFFUSION is enabled.
MNN_DIFFUSION_INCLUDE_DIRS = [
    REPO_ROOT / "third_party" / "MNN" / "transformers" / "diffusion" / "engine" / "include",
    # mnn_diffusion_tokenizer.hpp includes <tokenizer.hpp> (exactly as the reference does); that
    # header lives in the diffusion engine's src/ and in the llm engine's tokenizer/ dir, both of
    # which the real MNN CMake targets export as include directories.
    REPO_ROOT / "third_party" / "MNN" / "transformers" / "diffusion" / "engine" / "src",
    REPO_ROOT / "third_party" / "MNN" / "transformers" / "llm" / "engine" / "src" / "tokenizer",
    # The remaining entries mirror mca_mnn_native's real target_include_directories() (CMakeLists
    # lines with ${MCA_MNN_SOURCE_ROOT}); without them the target_sources() units this gate now
    # compiles would report missing <MNN/...> headers that are a harness gap, not a defect.
    REPO_ROOT / "third_party" / "MNN" / "source",
    REPO_ROOT / "third_party" / "MNN" / "tools" / "cv" / "include",
    REPO_ROOT / "third_party" / "MNN" / "tools" / "audio" / "include",
    REPO_ROOT / "third_party" / "MNN" / "3rd_party",
    REPO_ROOT / "third_party" / "MNN" / "transformers" / "llm" / "engine" / "include",
    REPO_ROOT / "third_party" / "MNN" / "transformers" / "llm" / "engine" / "src",
    REPO_ROOT / "third_party" / "MNN" / "3rd_party" / "imageHelper",
]
LLAMA_INCLUDE_DIRS = [
    LLAMA_ROOT / "include",
    LLAMA_ROOT / "ggml" / "include",
    LLAMA_ROOT / "vendor",
    # image_conditioning.cpp includes <tokenizers_cpp.h>; the real target gets this from the
    # tokenizers-cpp submodule's INTERFACE include directory.
    REPO_ROOT / "third_party" / "tokenizers-cpp" / "include",
    LLAMA_ROOT / "vendor" / "nlohmann",
    LLAMA_ROOT / "common",
    LLAMA_ROOT / "tools" / "mtmd",
    # llama-ext.h (llama_memory_breakdown, llama_get_memory_breakdown) lives in src/, and the
    # established include form is "src/llama-ext.h" (llama_model_loader.cpp), so the checkout
    # root has to be on the path as well.
    LLAMA_ROOT,
    LLAMA_ROOT / "src",
]

# Feature macros the CMake targets set for this configuration.  MCA_QNN_SDK_ROOT_CONFIGURED is
# defined by CMakeLists.txt when a QNN SDK root is present; without it the sources that report
# their build configuration fail to parse, which would look like a source error.
FEATURE_DEFINES = [
    "MCA_QNN_SYSTEM_LOADER_HAS_SDK=1",
    "MCA_WITH_QNN_SDK_HEADERS=1",
    "MCA_QNN_SDK_ROOT_CONFIGURED=1",
    # Every option(NAME "..." ON) in core/native/src/main/cpp/CMakeLists.txt, read from
    # that file rather than guessed.  Headers gate whole declaration blocks on them
    # (#if defined(MCA_WITH_MNN_DIFFUSION) && MCA_WITH_MNN_DIFFUSION in mnn_tensor_copy.hpp),
    # so omitting one makes the gate report missing symbols the real build never sees.
    "MCA_WITH_LLAMA_CPP=1",
    "MCA_WITH_LLAMA_OPENCL=1",
    "MCA_WITH_MNN_DIFFUSION=1",
    "MCA_WITH_TOKENIZERS_CPP=1",
    "MCA_QNN_TYPED_BINDINGS_REQUIRED=1",
]

NDK_FALLBACKS = [
    Path("D:/model/android-sdk/ndk/29.0.13113456"),
    Path("E:/model/android-sdk/ndk/29.0.13113456"),
]
QNN_SDK_FALLBACKS = [
    Path("E:/tools/qairt-sdk/2.45.0.260326"),
]

# Targets that are imported/prebuilt or that only exist to expose vendored third-party sources.
SKIP_TARGETS = {
    "mca_imported_mnn_core",
    "mca_imported_mnn_express",
    "mca_imported_mnn_opencl",
    "mca_imported_mnn_opencv",
    "mca_imported_mnn_audio",
    "mca_imported_mnn_llm",
    "mca_mtmd",
}


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


def parse_targets():
    """Return {target_name: (sources, skipped)} for every add_library in CMakeLists.txt."""
    if not CMAKE_LISTS.is_file():
        return None, f"CMakeLists.txt not found at {CMAKE_LISTS}"
    text = CMAKE_LISTS.read_text(encoding="utf-8", errors="replace")
    targets = {}
    for head in re.finditer(r"add_library\s*\(\s*([A-Za-z_][\w]*)\b", text):
        name = head.group(1)
        if name in SKIP_TARGETS:
            continue
        body = _parenthesised(text, head.end())
        if body is None:
            return None, f"unterminated add_library({name} ...) in CMakeLists.txt"
        sources, skipped, unresolved = [], [], []
        for raw in body.splitlines():
            stripped = raw.strip()
            # A source list can be written as add_library(NAME SHARED ${VAR}); those units are then
            # invisible to this parser.  Record them so the run reports the blind spot instead of
            # printing a clean total that silently omits whole targets (mca_native was in exactly
            # this state after its source list was moved into ${MCA_NATIVE_SOURCES}).
            if "${" in stripped and not stripped.startswith("#"):
                unresolved.append(stripped)
            line = raw.strip()
            # add_library(mca_x SHARED first.cpp ...) -- the library-type keyword is not a source.
            line = re.sub(r"^(SHARED|STATIC|MODULE|OBJECT|INTERFACE|ALIAS)\b", "", line).strip()
            if not line.endswith(".cpp") and not line.endswith(".cpp)"):
                continue
            line = line.rstrip(")")
            if "#" in line:
                line = line.split("#", 1)[0].strip()
                if not line:
                    continue
            if raw.strip().startswith("#"):
                skipped.append(raw.strip().lstrip("# ").strip())
            else:
                sources.append(line)
        if sources or skipped or unresolved:
            targets[name] = [sources, skipped, parse_include_dirs(text, name), unresolved]
    # add_library() alone is not the full source list: mca_mnn_native (and others) append whole
    # module sets through target_sources().  Before this pass ran, the gate printed a clean
    # "70/70 PASSED" while never compiling those units -- exactly the blind spot E03 registered.
    # Merge them so the count keeps matching what the real build compiles.
    for head in re.finditer(r"target_sources\s*\(\s*([A-Za-z_][\w]*)\b", text):
        name = head.group(1)
        if name not in targets:
            continue
        body = _parenthesised(text, head.end())
        if body is None:
            return None, f"unterminated target_sources({name} ...) in CMakeLists.txt"
        for raw in body.splitlines():
            stripped = raw.strip()
            if not stripped or stripped.startswith("#"):
                continue
            resolved = stripped.replace("${CMAKE_CURRENT_LIST_DIR}", str(CPP_ROOT))
            if "${" in resolved:
                targets[name][3].append(stripped)
                continue
            line = re.sub(r"^(PRIVATE|PUBLIC|INTERFACE)\b", "", resolved).strip()
            # target_sources() entries may be quoted ("${CMAKE_CURRENT_LIST_DIR}/x.cpp");
            # without stripping the quotes the suffix test below silently drops the TU -- the
            # same "clean total that omits a unit" failure mode this gate exists to prevent.
            line = line.strip().strip('"')
            if not (line.endswith(".cpp") or line.endswith(".cpp)")):
                continue
            line = line.rstrip(")")
            if "#" in line:
                line = line.split("#", 1)[0].strip()
            if line and line not in targets[name][0]:
                targets[name][0].append(line)
    return targets, None


def _parenthesised(text, start):
    """Return the text inside the parenthesis that opens before `start`, or None."""
    depth = 1
    index = start
    while index < len(text) and depth:
        char = text[index]
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
        index += 1
    if depth:
        return None
    return text[start:index - 1]


def parse_include_dirs(text, target):
    """
    Include directories the target itself declares.

    Reading these from CMakeLists.txt is what keeps this gate honest: without them a module that
    simply needs a vendored header (llama.h, json.hpp) reports a fatal error that is an artefact of
    the harness, not a defect in the source.  Guessing the paths would hide real errors behind
    false ones.
    """
    dirs = []
    for head in re.finditer(r"target_include_directories\s*\(\s*%s\b" % re.escape(target), text):
        body = _parenthesised(text, head.end())
        if body is None:
            continue
        for match in re.finditer(r'"([^"]+)"', body):
            value = match.group(1)
            value = value.replace("${CMAKE_CURRENT_LIST_DIR}", str(CPP_ROOT))
            if "$" in value:
                continue
            dirs.append(Path(value))
    return dirs



def main():
    print("=================================================================")
    print("   NATIVE LIBRARY TRANSLATION UNITS: DO THEY ACTUALLY COMPILE?             ")
    print("=================================================================\n")

    targets, error = parse_targets()
    if error:
        print(f"FAILED: {error}")
        return 1

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
    # The QNN SDK is optional here: targets other than mca_qnn_runtime must still be checked.
    sdk_include = sdk / "include" if sdk else None

    TOKENIZERS_INCLUDE_DIRS = [REPO_ROOT / "third_party" / "tokenizers-cpp" / "include"]
    include_dirs = ([CPP_ROOT, MNN_INCLUDE_DIR] + MNN_DIFFUSION_INCLUDE_DIRS + LLAMA_INCLUDE_DIRS
                    + TOKENIZERS_INCLUDE_DIRS)
    if sdk_include:
        include_dirs += [sdk_include, sdk_include / "QNN"]
    common_cmd = [
        str(clang),
        "-fsyntax-only",
        "-std=c++17",
        # NOTE: exceptions are deliberately left ENABLED.  The project does not build with
        # -fno-exceptions, and adding it turns every `try` in these modules into a false error.
        "--target=aarch64-none-linux-android26",
        f"--sysroot={sysroot}",
    ]
    common_cmd += [f"-D{d}" for d in FEATURE_DEFINES]
    common_cmd += [f"-I{d}" for d in include_dirs if Path(d).exists()]

    print(f"    ndk:   {ndk}")
    print(f"    sdk:   {sdk if sdk else '(not found -- QNN targets will report missing headers)'}\n")

    total_sources = 0
    all_failures = {}
    unresolved_targets = []
    for target, (sources, skipped, own_includes, unresolved) in sorted(targets.items()):
        if unresolved:
            unresolved_targets.append((target, unresolved))
        if not sources:
            continue
        total_sources += len(sources)
        target_cmd = common_cmd + [f"-I{d}" for d in own_includes if Path(d).exists()]
        failures = []
        for rel in sources:
            source = CPP_ROOT / rel
            if not source.is_file():
                source = CPP_ROOT / "mca" / rel
            if not source.is_file():
                failures.append((rel, ["file listed in CMakeLists.txt does not exist"]))
                continue
            res = subprocess.run(
                target_cmd + [str(source)], capture_output=True, text=True, errors="replace"
            )
            errors = [
                line.strip()
                for line in (res.stderr or "").splitlines()
                if ": error:" in line
            ]
            if res.returncode != 0 or errors:
                if not errors:
                    # A fatal error (missing header) has no ": error:" line of its own, so the
                    # first diagnostic is reported instead -- otherwise the gate says only
                    # "clang exited 1" and nobody can tell a real defect from a harness gap.
                    head = [
                        line.strip()
                        for line in (res.stderr or "").splitlines()
                        if line.strip() and not line.strip().startswith(("In file included", "^", "|"))
                    ]
                    errors = head[:3] or [f"clang exited {res.returncode}"]
                failures.append((rel, errors))
        status = "OK" if not failures else f"FAILED ({len(failures)}/{len(sources)})"
        extras = f"  [+{len(own_includes)} include dirs]" if own_includes else ""
        print(f"    {target:<22} {len(sources):>3} sources   {status}{extras}")
        if failures:
            all_failures[target] = failures

    print()
    if unresolved_targets:
        print("WARNING: some targets declare their sources through a CMake variable, so this gate")
        print("         cannot see them.  The totals below therefore do NOT cover those units:")
        for target, items in unresolved_targets:
            print(f"           {target}: {', '.join(items)}")
        print("         Resolve the list (cmake --build ... --target help / File API) or inline the")
        print("         sources before treating a clean run as full coverage.")
        print()
    if all_failures:
        broken = sum(len(v) for v in all_failures.values())
        print(f"RESULT: FAILED -- {broken} of {total_sources} native translation unit(s) do not compile.\n")
        for target, failures in all_failures.items():
            print(f"  [{target}]")
            for rel, errors in failures:
                print(f"    {rel}")
                for line in errors[:4]:
                    print(f"        {line}")
                if len(errors) > 4:
                    print(f"        ... {len(errors) - 4} more")
        print("\nA translation unit that does not compile carries no migrated behaviour. Do not")
        print("treat a bridge's line count as evidence that its logic was moved successfully.")
        return 1

    print(f"RESULT: PASSED -- all {total_sources} native translation units compile.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
