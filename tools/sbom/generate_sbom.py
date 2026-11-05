#!/usr/bin/env python3
"""
Generate and verify a deterministic SPDX 2.3 SBOM from MCA repository inputs.

The generator is deliberately offline and static: it parses Gradle catalogs and
build files, local vendor manifests/binaries, Git submodule records, and the
locally installed Android/QAIRT SDK manifests. It never invokes Gradle.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tomllib
import zipfile
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable
from urllib.parse import quote

sys.dont_write_bytecode = True

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUTPUT = REPO_ROOT / "tools" / "sbom" / "mca-sbom.spdx.json"
DOCUMENT_ID = "SPDXRef-DOCUMENT"
GENERATOR_VERSION = "2.0.0"
LOCAL_ABSOLUTE_PATH_RE = re.compile(
    r"(?i)(?:\b[A-Z]:[\\/]|"
    r"\\\\[^\\\s]+\\[^\\\s]+|"
    r"file:(?:/{2,3})[^\s;,)]*|"
    r"(?:^|[\s=])/(?!/)[^\s;,)]*)"
)

# Compatibility identities consumed by release tooling. Package fields are
# assembled from repository inputs; labels that cannot be derived from a
# current file are marked provenance=static-fallback.
CORE_CONTRACT = {
    "mca-core-engine": "1.0.0-sgrade",
    "mca-native-common": "1.0.0-sgrade",
    "kotlin": "2.3.21",
    "kotlinx-coroutines": "1.9.0",
    "onnxruntime": "1.17.1",
    "qairt-sdk": "2.45.0.260326",
    "alibaba-mnn": "3.6.1-mca-patched",
    "llama-cpp": "v0.9.0-mca-patched",
    "stable-diffusion-cpp": "master-mca-pinned",
    "androidx-workmanager": "2.11.0",
    "androidx-room": "2.8.4",
    "litertlm-android-runtime": "0.16.1",
}

LEGACY_MODULE_VERSIONS = {
    ":core:native": "1.0.0-sgrade",
    ":core:engine": "1.0.0-sgrade",
}

VALID_RELATIONSHIPS = {
    "AMENDS", "ANCESTOR_OF", "BUILD_DEPENDENCY_OF", "BUILD_TOOL_OF",
    "CONTAINED_BY", "CONTAINS", "COPY_OF", "DATA_FILE_OF",
    "DEPENDENCY_MANIFEST_OF", "DEPENDENCY_OF", "DEPENDS_ON",
    "DESCENDANT_OF", "DESCRIBED_BY", "DESCRIBES", "DEV_DEPENDENCY_OF",
    "DEV_TOOL_OF", "DISTRIBUTION_ARTIFACT", "DYNAMIC_LINK", "EXAMPLE_OF",
    "EXPANDED_FROM_ARCHIVE", "FILE_ADDED", "FILE_DELETED", "FILE_MODIFIED",
    "GENERATED_FROM", "GENERATES", "HAS_PREREQUISITE", "METAFILE_OF",
    "OPTIONAL_COMPONENT_OF", "OTHER", "PACKAGE_OF", "PATCH_APPLIED",
    "PATCH_FOR", "PREREQUISITE_FOR", "PROVIDED_DEPENDENCY_OF",
    "REQUIREMENT_DESCRIPTION_FOR", "RUNTIME_DEPENDENCY_OF",
    "SPECIFICATION_FOR", "STATIC_LINK", "TEST_CASE_OF", "TEST_DEPENDENCY_OF",
    "TEST_OF", "TEST_TOOL_OF", "VARIANT_OF",
}

SUPPLIERS = {
    "androidx": "Organization: Google LLC",
    "android": "Organization: Google LLC",
    "com.android": "Organization: Google LLC",
    "com.google": "Organization: Google LLC",
    "org.jetbrains": "Organization: JetBrains s.r.o.",
    "org.jetbrains.kotlinx": "Organization: JetBrains s.r.o.",
    "com.squareup.okhttp3": "Organization: Block, Inc.",
    "org.jsoup": "Organization: Jonathan Hedley and contributors",
}

LICENSES = {
    ("androidx.core", "core-ktx"): "Apache-2.0",
    ("androidx.activity", "activity-compose"): "Apache-2.0",
    ("androidx.lifecycle", "lifecycle-runtime-ktx"): "Apache-2.0",
    ("androidx.lifecycle", "lifecycle-runtime-compose"): "Apache-2.0",
    ("androidx.lifecycle", "lifecycle-process"): "Apache-2.0",
    ("androidx.lifecycle", "lifecycle-viewmodel-compose"): "Apache-2.0",
    ("androidx.lifecycle", "lifecycle-viewmodel-ktx"): "Apache-2.0",
    ("androidx.work", "work-runtime-ktx"): "Apache-2.0",
    ("androidx.room", "room-runtime"): "Apache-2.0",
    ("androidx.room", "room-ktx"): "Apache-2.0",
    ("androidx.room", "room-compiler"): "Apache-2.0",
    ("org.jetbrains.kotlinx", "kotlinx-coroutines-android"): "Apache-2.0",
    ("com.squareup.okhttp3", "okhttp"): "Apache-2.0",
    ("org.jsoup", "jsoup"): "MIT",
    ("com.google.re2j", "re2j"): "BSD-3-Clause",
    ("com.google.ai.edge.litertlm", "litertlm-android"): "Apache-2.0",
}

MANIFEST_LICENSES = {
    "tensorflow": "Apache-2.0", "tflite": "Apache-2.0",
    "torch": "BSD-3-Clause", "onnx": "Apache-2.0",
    "onnxruntime": "MIT", "onnxsimplifier": "Apache-2.0",
    "android-ndk": "Apache-2.0",
}

MANIFEST_SUPPLIERS = {
    "tensorflow": "Organization: Google LLC",
    "tflite": "Organization: Google LLC",
    "torch": "Organization: Meta Platforms, Inc.",
    "onnx": "Organization: LF AI & Data Foundation",
    "onnxruntime": "Organization: Microsoft Corporation",
    "onnxsimplifier": "Organization: ONNX Simplifier contributors",
    "android-ndk": "Organization: Google LLC",
}

MANIFEST_DOWNLOADS = {
    "tensorflow": "https://github.com/tensorflow/tensorflow",
    "tflite": "https://github.com/tensorflow/tensorflow",
    "torch": "https://github.com/pytorch/pytorch",
    "onnx": "https://github.com/onnx/onnx",
    "onnxruntime": "https://github.com/microsoft/onnxruntime",
    "onnxsimplifier": "https://github.com/daquexian/onnx-simplifier",
    "android-ndk": "https://developer.android.com/ndk",
}


class SbomError(RuntimeError):
    pass


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def file_digests(path: Path) -> tuple[str, str]:
    sha1 = hashlib.sha1()
    sha256 = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            sha1.update(chunk)
            sha256.update(chunk)
    return sha1.hexdigest(), sha256.hexdigest()


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


def rel_posix(path: Path, root: Path) -> str | None:
    try:
        return path.resolve().relative_to(root.resolve()).as_posix()
    except ValueError:
        return None


def slug(value: str) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9.-]+", "-", value).strip("-.")
    return cleaned or "Unnamed"


def short_hash(value: str, length: int = 10) -> str:
    return hashlib.sha1(value.encode("utf-8")).hexdigest()[:length]


def contains_local_absolute_path(value: Any) -> bool:
    if isinstance(value, str):
        return LOCAL_ABSOLUTE_PATH_RE.search(value) is not None
    if isinstance(value, dict):
        return any(contains_local_absolute_path(item) for item in value.values())
    if isinstance(value, list):
        return any(contains_local_absolute_path(item) for item in value)
    return False


def parse_properties(path: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    if not path.is_file():
        return result
    for raw in read_text(path).splitlines():
        line = raw.strip()
        if not line or line.startswith(("#", "!")) or "=" not in line:
            continue
        key, value = line.split("=", 1)
        result[key.strip()] = value.strip()
    return result


def parse_gitmodules(path: Path) -> list[dict[str, str]]:
    entries: list[dict[str, str]] = []
    current: dict[str, str] | None = None
    if not path.is_file():
        return entries
    for raw in read_text(path).splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        section = re.fullmatch(r'\[submodule\s+"([^"]+)"\]', line)
        if section:
            if current:
                entries.append(current)
            current = {"name": section.group(1)}
        elif current is not None and "=" in line:
            key, value = line.split("=", 1)
            current[key.strip()] = value.strip()
    if current:
        entries.append(current)
    return sorted(entries, key=lambda item: item.get("path", ""))


def run_readonly(cmd: list[str], cwd: Path) -> str | None:
    try:
        result = subprocess.run(
            cmd, cwd=str(cwd), check=False, capture_output=True, text=True,
            encoding="utf-8", errors="replace",
        )
    except OSError:
        return None
    return result.stdout.strip() if result.returncode == 0 else None


def git_submodule_commit(root: Path, submodule_path: str) -> str | None:
    status = run_readonly(["git", "submodule", "status", "--", submodule_path], root)
    if status:
        match = re.match(r"^[ +\-U]?([0-9a-f]{40,64})\s+", status.splitlines()[0])
        if match:
            return match.group(1)
    tree = run_readonly(["git", "ls-tree", "HEAD", "--", submodule_path], root)
    if tree:
        match = re.match(r"^\d+\s+commit\s+([0-9a-f]{40,64})\s+", tree)
        if match:
            return match.group(1)
    return None


def git_describe(path: Path) -> str | None:
    return run_readonly(["git", "describe", "--tags", "--always", "--dirty"], path) if path.is_dir() else None


def git_remote(path: Path) -> str | None:
    return run_readonly(["git", "remote", "get-url", "origin"], path) if path.is_dir() else None


def find_balanced_block(text: str, keyword: str) -> str | None:
    match = re.search(rf"\b{re.escape(keyword)}\s*\{{", text)
    if not match:
        return None
    start = match.end() - 1
    depth = 0
    quote_char: str | None = None
    escaped = False
    line_comment = False
    block_comment = False
    index = start
    while index < len(text):
        char = text[index]
        nxt = text[index + 1] if index + 1 < len(text) else ""
        if line_comment:
            if char == "\n":
                line_comment = False
        elif block_comment:
            if char == "*" and nxt == "/":
                block_comment = False
                index += 1
        elif quote_char:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote_char:
                quote_char = None
        elif char == "/" and nxt == "/":
            line_comment = True
            index += 1
        elif char == "/" and nxt == "*":
            block_comment = True
            index += 1
        elif char in ('"', "'"):
            quote_char = char
        elif char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return text[start + 1:index]
        index += 1
    return None


def paren_balance(value: str) -> int:
    depth = 0
    quote_char: str | None = None
    escaped = False
    for char in value:
        if quote_char:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote_char:
                quote_char = None
        elif char in ('"', "'"):
            quote_char = char
        elif char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
    return depth


def dependency_calls(block: str) -> list[tuple[str, str]]:
    calls: list[tuple[str, str]] = []
    pending: str | None = None
    config = ""
    for raw in block.splitlines():
        line = raw.split("//", 1)[0].strip()
        if not line or line.startswith(("/*", "*")):
            continue
        if pending is not None:
            pending += "\n" + line
            if paren_balance(pending) <= 0:
                calls.append((config, pending))
                pending = None
            continue
        match = re.match(r"^([A-Za-z_][A-Za-z0-9_]*)\s*\(", line)
        if not match:
            continue
        config = match.group(1)
        pending = line
        if paren_balance(line) <= 0:
            calls.append((config, pending))
            pending = None
    return calls


def normalized_alias(value: str) -> str:
    return re.sub(r"[^a-z0-9]", "", value.lower())


def scan_version_catalogs(root: Path) -> dict[str, Any]:
    candidates: set[Path] = set(root.glob("gradle/*.versions.toml"))
    candidates.update(root.glob("**/libs.versions.toml"))
    paths: list[Path] = []
    for path in sorted(candidates, key=lambda item: item.as_posix()):
        rel = rel_posix(path, root)
        if rel and not any(part in {"build", ".gradle", ".git", "artifacts", "third_party"} for part in rel.split("/")):
            paths.append(path)
    versions: dict[str, str] = {}
    libraries: dict[str, dict[str, str]] = {}
    plugins: dict[str, dict[str, str]] = {}
    sources: list[Path] = []
    for path in paths:
        try:
            data = tomllib.loads(read_text(path))
        except tomllib.TOMLDecodeError as exc:
            raise SbomError(f"cannot parse version catalog {path}: {exc}") from exc
        sources.append(path)
        for key, value in (data.get("versions") or {}).items():
            if isinstance(value, str):
                versions[str(key)] = value
            elif isinstance(value, dict) and isinstance(value.get("ref"), str):
                versions[str(key)] = value["ref"]
        for alias, spec in (data.get("libraries") or {}).items():
            if not isinstance(spec, dict):
                continue
            group, artifact = spec.get("group"), spec.get("name")
            module = spec.get("module")
            if isinstance(module, str) and ":" in module:
                group, artifact = module.split(":", 1)
            version: str | None = None
            spec_version = spec.get("version")
            if isinstance(spec_version, str):
                version = spec_version
            elif isinstance(spec_version, dict) and isinstance(spec_version.get("ref"), str):
                version = versions.get(spec_version["ref"], spec_version["ref"])
            if isinstance(group, str) and isinstance(artifact, str):
                libraries[normalized_alias(str(alias))] = {
                    "group": group, "artifact": artifact, "version": version or "",
                    "catalog": rel_posix(path, root) or path.as_posix(),
                }
        for alias, spec in (data.get("plugins") or {}).items():
            if not isinstance(spec, dict) or not isinstance(spec.get("id"), str):
                continue
            version = spec.get("version") if isinstance(spec.get("version"), str) else ""
            if not version and isinstance(spec.get("version"), dict):
                ref = spec["version"].get("ref")
                version = versions.get(ref, ref) if isinstance(ref, str) else ""
            plugins[normalized_alias(str(alias))] = {
                "id": spec["id"], "version": version or "",
                "catalog": rel_posix(path, root) or path.as_posix(),
            }
    return {"versions": versions, "libraries": libraries, "plugins": plugins,
            "files": sorted(set(sources), key=lambda item: item.as_posix())}


def parse_settings_modules(root: Path) -> dict[str, Path]:
    settings = root / "settings.gradle.kts"
    text = read_text(settings) if settings.is_file() else ""
    modules: dict[str, Path] = {}
    for match in re.finditer(r'include\s*\(([^)]*)\)', text, flags=re.DOTALL):
        for value in re.findall(r'"(:[^"]+)"', match.group(1)):
            modules[value] = root.joinpath(*value.strip(":").split(":"))
    return dict(sorted(modules.items()))


def parse_module_dependencies(root: Path, module_dir: Path, catalogs: dict[str, Any]) -> list[dict[str, Any]]:
    build_file = module_dir / "build.gradle.kts"
    if not build_file.is_file():
        return []
    block = find_balanced_block(read_text(build_file), "dependencies")
    if block is None:
        return []
    dependencies: list[dict[str, Any]] = []
    ignored = {"testImplementation", "androidTestImplementation", "debugImplementation",
               "releaseImplementation", "testRuntimeOnly", "testCompileOnly"}
    allowed = {"implementation", "api", "compileOnly", "runtimeOnly", "ksp",
               "annotationProcessor", "kapt"}
    source = rel_posix(build_file, root) or build_file.as_posix()
    for config, call in dependency_calls(block):
        if config not in allowed or config in ignored:
            continue
        project_match = re.search(r'project\s*\(\s*"(:[^"]+)"\s*\)', call)
        if project_match:
            dependencies.append({"kind": "project", "path": project_match.group(1),
                                 "config": config, "source": source})
            continue
        managed_by = ""
        unwrapped = call
        platform_match = re.search(r'platform\s*\(\s*(.*?)\s*\)', call, flags=re.DOTALL)
        if platform_match and "files(" not in call:
            managed_by = platform_match.group(1).strip()
            unwrapped = managed_by
        alias_match = re.search(r"\blibs\.([A-Za-z0-9_.-]+)", unwrapped)
        if alias_match:
            library = catalogs["libraries"].get(normalized_alias(alias_match.group(1)))
            if library:
                dependencies.append({"kind": "maven", "group": library["group"],
                    "artifact": library["artifact"], "version": library["version"],
                    "version_source": library["catalog"], "managed_by": managed_by,
                    "config": config, "source": source})
            else:
                dependencies.append({"kind": "unresolved_alias",
                    "alias": alias_match.group(1), "config": config, "source": source})
            continue
        local_file_match = re.search(r'(?:rootProject\.)?file\s*\(\s*"([^"]+)"\s*\)', call)
        if "files(" in call and local_file_match:
            dependencies.append({"kind": "local_file", "path": local_file_match.group(1),
                                 "config": config, "source": source})
            continue
        for literal in re.findall(r'"([^"]+)"', call):
            if literal.count(":") >= 2:
                group, artifact, version = literal.split(":", 2)
                dependencies.append({"kind": "maven", "group": group,
                    "artifact": artifact, "version": version, "version_source": source,
                    "managed_by": managed_by, "config": config, "source": source})
                break
            if literal.count(":") == 1:
                group, artifact = literal.split(":", 1)
                dependencies.append({"kind": "maven", "group": group,
                    "artifact": artifact, "version": "", "version_source": "managed-platform",
                    "managed_by": managed_by, "config": config, "source": source})
                break
    return dependencies


def app_version_info(root: Path) -> dict[str, str]:
    text = read_text(root / "app" / "build.gradle.kts") if (root / "app" / "build.gradle.kts").is_file() else ""
    name = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    code = re.search(r'versionCode\s*=\s*(\d+)', text)
    abis = re.search(r'\.orElse\("([^"]+)"\)', text)
    return {"versionName": name.group(1) if name else "NOASSERTION",
            "versionCode": code.group(1) if code else "NOASSERTION",
            "defaultAbis": abis.group(1) if abis else "NOASSERTION"}


def package_name_for_module(path: str) -> str:
    if path == ":app":
        return "mca-android-app"
    if path == ":core:native":
        return "mca-native-common"
    if path == ":core:engine":
        return "mca-core-engine"
    return "mca-" + path.strip(":").replace(":", "-")


def module_source_files(module_dir: Path) -> list[Path]:
    files: set[Path] = set()
    build_file = module_dir / "build.gradle.kts"
    if build_file.is_file():
        files.add(build_file)
    source_root = module_dir / "src" / "main"
    if source_root.is_dir():
        files.update(path for path in source_root.rglob("*") if path.is_file() and ".git" not in path.parts)
    return sorted(files, key=lambda item: item.as_posix())


def license_for_coordinate(group: str, artifact: str) -> str:
    return LICENSES.get((group, artifact)) or (
        "Apache-2.0" if group.startswith(("androidx.", "com.android.", "com.google.ai.", "org.jetbrains."))
        or group == "com.squareup.okhttp3" else "NOASSERTION"
    )


def supplier_for_group(group: str) -> str:
    for prefix, supplier in SUPPLIERS.items():
        if group == prefix or group.startswith(prefix + "."):
            return supplier
    return "NOASSERTION"


def maven_download_url(group: str, artifact: str, version: str) -> str:
    if not version or version == "NOASSERTION":
        return "NOASSERTION"
    base = ("https://dl.google.com/dl/android/maven2"
            if group.startswith(("androidx.", "com.android.", "com.google."))
            else "https://repo.maven.apache.org/maven2")
    return f"{base}/{group.replace('.', '/')}/{artifact}/{version}/"


def purl_for_coordinate(group: str, artifact: str, version: str) -> str:
    base = f"pkg:maven/{quote(group, safe='.')}/{quote(artifact, safe='.-')}"
    return base if not version or version == "NOASSERTION" else base + "@" + quote(version, safe="._-")


def compatibility_component(group: str, artifact: str, version: str) -> tuple[str, str]:
    if group == "androidx.room":
        return "androidx-room", version
    if group == "androidx.work" and artifact.startswith("work-"):
        return "androidx-workmanager", version
    if group == "org.jetbrains.kotlinx" and artifact.startswith("kotlinx-coroutines"):
        return "kotlinx-coroutines", version
    if group == "com.google.ai.edge.litertlm" and artifact == "litertlm-android":
        return "litertlm-android-runtime", version
    return group.replace(".", "-") + "-" + artifact, version


def parse_maven_bom_versions(group: str, artifact: str, version: str) -> dict[tuple[str, str], str]:
    if not version or version == "NOASSERTION":
        return {}
    home = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    base = home / "caches" / "modules-2" / "files-2.1" / group / artifact / version
    result: dict[tuple[str, str], str] = {}
    for pom in sorted(base.rglob("*.pom"), key=lambda item: item.as_posix()) if base.is_dir() else []:
        try:
            root = ET.fromstring(read_text(pom))
        except ET.ParseError:
            continue
        for node in root.iter():
            if node.tag.rsplit("}", 1)[-1] != "dependency":
                continue
            values = {child.tag.rsplit("}", 1)[-1]: (child.text or "").strip() for child in node}
            dep_group = values.get("groupId", "")
            dep_artifact = values.get("artifactId", "")
            dep_version = values.get("version", "")
            if dep_group and dep_artifact and dep_version:
                result[(dep_group, dep_artifact)] = dep_version
    return result


def find_gradle_cache_artifact(group: str, artifact: str, version: str) -> Path | None:
    if not version or version == "NOASSERTION":
        return None
    home = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    root = home / "caches" / "modules-2" / "files-2.1" / group / artifact / version
    if not root.is_dir():
        return None
    candidates = [path for path in root.rglob("*") if path.is_file()
                  and path.suffix.lower() in {".aar", ".jar"}
                  and not path.name.lower().endswith(("-sources.jar", "-javadoc.jar"))]
    return sorted(candidates, key=lambda item: (0 if item.suffix.lower() == ".aar" else 1, item.as_posix()))[0] if candidates else None


def find_gradle_cache_metadata(group: str, artifact: str, version: str) -> Path | None:
    if not version or version == "NOASSERTION":
        return None
    home = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    root = home / "caches" / "modules-2" / "files-2.1" / group / artifact / version
    if not root.is_dir():
        return None
    for suffix in (".module", ".pom"):
        candidates = sorted(root.rglob("*" + suffix), key=lambda item: item.as_posix())
        if candidates:
            return candidates[0]
    return None


def is_ignored_path(path: Path) -> bool:
    return any(part in {".git", "build", ".gradle", "__pycache__"} for part in path.parts)


class FileRegistry:
    def __init__(self, root: Path):
        self.root = root
        self.files: dict[str, dict[str, Any]] = {}
        self.path_to_id: dict[str, str] = {}
        self.display_to_id: dict[str, str] = {}

    def add(self, path: Path, display_name: str | None = None, comment: str | None = None) -> str:
        path = path.resolve()
        if not path.is_file():
            raise SbomError(f"file does not exist: {path}")
        key = str(path).casefold()
        if key in self.path_to_id:
            return self.path_to_id[key]
        relative_name = rel_posix(path, self.root)
        name = display_name or relative_name
        if not name:
            name = f"external/local-input-{sha256_file(path)[:16]}{path.suffix}"
        name = name.replace("\\", "/")
        if name in self.display_to_id:
            name_path = Path(name)
            name = str(name_path.with_name(
                f"{name_path.stem}-{short_hash(sha256_file(path), 8)}{name_path.suffix}"
            ))
        base_id = "SPDXRef-File-" + slug(name)
        spdx_id = base_id
        counter = 2
        while spdx_id in self.files:
            spdx_id = f"{base_id}-{short_hash(sha256_file(path) + str(counter), 8)}"
            counter += 1
        sha1, sha256 = file_digests(path)
        record: dict[str, Any] = {
            "SPDXID": spdx_id, "fileName": name,
            "checksums": [{"algorithm": "SHA1", "checksumValue": sha1},
                          {"algorithm": "SHA256", "checksumValue": sha256}],
            "licenseConcluded": "NOASSERTION", "copyrightText": "NOASSERTION",
        }
        if comment:
            record["comment"] = comment
        elif rel_posix(path, self.root) is None:
            record["comment"] = f"externalInput={name}"
        self.files[spdx_id] = record
        self.path_to_id[key] = spdx_id
        self.display_to_id[name] = spdx_id
        return spdx_id

    def sha1(self, spdx_id: str) -> str:
        for checksum in self.files[spdx_id]["checksums"]:
            if checksum["algorithm"] == "SHA1":
                return checksum["checksumValue"]
        raise SbomError(f"missing SHA1 for {spdx_id}")


class PackageRegistry:
    def __init__(self, root: Path):
        self.root = root
        self.files = FileRegistry(root)
        self.packages: dict[str, dict[str, Any]] = {}
        self.identity_to_id: dict[tuple[str, str], str] = {}
        self.relationships: set[tuple[str, str, str]] = set()

    def add_relationship(self, left: str, relation: str, right: str) -> None:
        self.relationships.add((left, relation, right))

    def _package_id(self, name: str, version: str) -> str:
        identity = (name, version)
        if identity in self.identity_to_id:
            return self.identity_to_id[identity]
        base = "SPDXRef-Package-" + slug(name)
        spdx_id = base
        if spdx_id in self.packages:
            spdx_id = f"{base}-{short_hash(name + chr(0) + version, 10)}"
        counter = 2
        while spdx_id in self.packages:
            spdx_id = f"{base}-{short_hash(name + chr(0) + version + str(counter), 10)}"
            counter += 1
        self.identity_to_id[identity] = spdx_id
        return spdx_id

    def add_package(
        self, *, name: str, version: str, supplier: str, originator: str,
        download_location: str, license_declared: str, license_concluded: str,
        description: str, provenance: str, files: Iterable[Path] = (),
        external_files: Iterable[tuple[Path, str]] = (),
        source_info: Iterable[str] = (), external_refs: Iterable[dict[str, str]] = (),
        files_analyzed: bool | None = None, checksums: Iterable[dict[str, str]] = (),
        primary_purpose: str = "LIBRARY", package_file_name: str | None = None,
        extra_comment: str = "",
    ) -> str:
        version = version or "NOASSERTION"
        spdx_id = self._package_id(name, version)
        if spdx_id in self.packages:
            raise SbomError(f"duplicate package identity: {name}@{version}")
        file_ids: list[str] = []
        for path in files:
            if path.is_file():
                file_ids.append(self.files.add(path))
        for path, display_name in external_files:
            if path.is_file():
                file_ids.append(self.files.add(path, display_name=display_name))
        file_ids = sorted(set(file_ids))
        source_values = sorted(set(str(value) for value in source_info if value))
        refs_by_key: dict[tuple[str, str, str], dict[str, str]] = {}
        for ref in external_refs:
            key = (ref.get("referenceCategory", "OTHER"), ref.get("referenceType", "OTHER"),
                   ref.get("referenceLocator", "NOASSERTION"))
            refs_by_key[key] = dict(ref)
        checksum_values: list[dict[str, str]] = []
        checksum_scope = ""
        if file_ids and not list(checksums):
            canonical = hashlib.sha256()
            for file_id in file_ids:
                record = self.files.files[file_id]
                value = next(item["checksumValue"] for item in record["checksums"] if item["algorithm"] == "SHA256")
                canonical.update(f"{value}  {record['fileName']}\n".encode("utf-8"))
            checksum_values.append({"algorithm": "SHA256", "checksumValue": canonical.hexdigest()})
            checksum_scope = "checksumScope=canonical-file-set"
        else:
            checksum_values.extend(sorted(checksums, key=lambda item: (item["algorithm"], item["checksumValue"])))
        comment = "; ".join(part for part in [
            f"provenance={provenance}", extra_comment, checksum_scope,
        ] if part)
        package: dict[str, Any] = {
            "SPDXID": spdx_id, "name": name, "versionInfo": version,
            "supplier": supplier or "NOASSERTION", "originator": originator or "NOASSERTION",
            "downloadLocation": download_location or "NOASSERTION",
            "filesAnalyzed": bool(file_ids) if files_analyzed is None else bool(files_analyzed),
            "licenseDeclared": license_declared or "NOASSERTION",
            "licenseConcluded": license_concluded or "NOASSERTION",
            "copyrightText": "NOASSERTION", "description": description,
            "primaryPackagePurpose": primary_purpose, "comment": comment,
            "sourceInfo": "; ".join(source_values) if source_values else "NOASSERTION",
        }
        if package_file_name:
            package["packageFileName"] = package_file_name
        if refs_by_key:
            package["externalRefs"] = [refs_by_key[key] for key in sorted(refs_by_key)]
        if checksum_values:
            package["checksums"] = checksum_values
        if file_ids and package["filesAnalyzed"]:
            verification = hashlib.sha1(
                "".join(sorted(self.files.sha1(file_id) for file_id in file_ids)).encode("ascii")
            ).hexdigest()
            package["packageVerificationCode"] = {
                "packageVerificationCodeValue": verification,
                "packageVerificationCodeExcludedFiles": [],
            }
            package["hasFiles"] = file_ids
            for file_id in file_ids:
                self.add_relationship(spdx_id, "CONTAINS", file_id)
        self.packages[spdx_id] = package
        return spdx_id

    def id_for(self, name: str, version: str | None = None) -> str:
        matches = [spdx_id for spdx_id, package in self.packages.items()
                   if package["name"] == name and (version is None or package["versionInfo"] == version)]
        if len(matches) != 1:
            raise SbomError(f"expected one package {name}@{version}, found {len(matches)}")
        return matches[0]


def resolve_creation_time(root: Path) -> str:
    source_epoch = os.environ.get("SOURCE_DATE_EPOCH")
    if source_epoch and source_epoch.isdigit():
        return datetime.fromtimestamp(int(source_epoch), timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    commit_epoch = run_readonly(["git", "log", "-1", "--format=%ct"], root)
    if commit_epoch and commit_epoch.isdigit():
        return datetime.fromtimestamp(int(commit_epoch), timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    return "1970-01-01T00:00:00Z"


def parse_qairt_manifest(path: Path) -> dict[str, Any]:
    metadata: dict[str, str] = {}
    dependencies: dict[str, str] = {}
    dependency_marker = False
    for raw in read_text(path).splitlines():
        line = raw.rstrip()
        if "Below are the list of SDK dependencies" in line:
            dependency_marker = True
            continue
        if not line or line.lstrip().startswith("#"):
            continue
        top_level = not line.startswith((" ", "\t"))
        match = re.match(r"^\s*([A-Za-z0-9_.-]+)\s*:\s*(.+?)\s*$", line)
        if not match:
            continue
        key, value = match.group(1), match.group(2)
        if dependency_marker and top_level:
            dependencies[key] = value
        elif top_level and key in {"product", "version", "build_id", "qnn_backend_api_version"}:
            metadata[key] = value
    if metadata.get("version") and metadata.get("build_id"):
        metadata["version"] = metadata["version"] + "." + metadata["build_id"][:6]
    return {"metadata": metadata, "dependencies": dependencies}


def find_qairt_sdk(root: Path) -> Path | None:
    version = CORE_CONTRACT["qairt-sdk"]
    candidates: list[Path] = []
    for env_name in ("MCA_QNN_SDK_ROOT", "QAIRT_SDK_ROOT", "QNN_SDK_ROOT"):
        value = os.environ.get(env_name)
        if value:
            candidates.append(Path(value))
    candidates.extend([
        root / "build" / "qnn-runtime-sdk" / "qairt" / version,
        Path.home() / "qairt-sdk" / version,
    ])
    for base in sorted(candidates, key=lambda item: item.as_posix()):
        if (base / "sdk.yaml").is_file():
            return base.resolve()
    for candidate in sorted(root.glob("build/**/sdk.yaml"), key=lambda item: item.as_posix()):
        if "qairt" in candidate.as_posix().lower():
            return candidate.parent.resolve()
    return None


def detect_license(directory: Path, fallback: str = "NOASSERTION") -> str:
    for name in ("LICENSE", "LICENSE.txt", "LICENSE.md", "COPYING"):
        path = directory / name
        if not path.is_file():
            continue
        text = read_text(path)[:200000].lower()
        if "apache license" in text and "version 2.0" in text:
            return "Apache-2.0"
        if "permission is hereby granted, free of charge" in text:
            return "MIT"
        if "redistribution and use in source and binary forms" in text:
            return "BSD-3-Clause"
    return fallback


def add_if_file(paths: list[Path], path: Path) -> None:
    if path.is_file():
        paths.append(path)


def submodule_files(directory: Path, names: Iterable[str]) -> list[Path]:
    result: list[Path] = []
    for name in names:
        add_if_file(result, directory / name)
    return result


def android_sdk_root(root: Path) -> Path | None:
    local = root / "local.properties"
    values = parse_properties(local) if local.is_file() else {}
    raw = values.get("sdk.dir", "")
    if raw:
        path = Path(re.sub(r"\\(.)", r"\1", raw))
        if path.is_dir():
            return path.resolve()
    for name in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = os.environ.get(name)
        if value and Path(value).is_dir():
            return Path(value).resolve()
    return None


def apk_metadata(path: Path, app: dict[str, str]) -> dict[str, str]:
    sha256 = sha256_file(path)
    try:
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
    except (OSError, zipfile.BadZipFile) as exc:
        raise SbomError(f"APK is not a readable ZIP archive: {path}: {exc}") from exc
    abis = sorted({parts[1] for name in names if name.startswith("lib/")
                   and (parts := name.split("/")) and len(parts) > 2})
    variation = ";".join([
        f"abi={','.join(abis) if abis else 'NOASSERTION'}",
        f"versionCode={app.get('versionCode', 'NOASSERTION')}",
        f"versionName={app.get('versionName', 'NOASSERTION')}",
    ])
    return {"sha256": sha256, "bytes": str(path.stat().st_size),
            "abis": ",".join(abis) if abis else "NOASSERTION", "variation": variation}


def build_sbom(root: Path, apk_path: Path | None = None) -> tuple[dict[str, Any], list[str]]:
    warnings: list[str] = []
    registry = PackageRegistry(root)
    catalogs = scan_version_catalogs(root)
    modules = parse_settings_modules(root)
    app = app_version_info(root)
    app_version = (f"{app['versionName']}+{app['versionCode']}"
                   if app["versionName"] != "NOASSERTION" and app["versionCode"] != "NOASSERTION"
                   else app["versionName"])

    module_dependencies = {
        module_path: parse_module_dependencies(root, module_dir, catalogs)
        for module_path, module_dir in modules.items()
    }
    reachable: set[str] = {":app"}
    changed = True
    while changed:
        changed = False
        for module_path in sorted(reachable):
            for dependency in module_dependencies.get(module_path, []):
                if dependency["kind"] == "project" and dependency["path"] in modules and dependency["path"] not in reachable:
                    reachable.add(dependency["path"])
                    changed = True

    module_ids: dict[str, str] = {}
    for module_path in sorted(reachable):
        if module_path not in modules:
            warnings.append(f"project dependency has no included build: {module_path}")
            continue
        module_dir = modules[module_path]
        legacy_version = LEGACY_MODULE_VERSIONS.get(module_path)
        version = legacy_version or app_version
        provenance = "static-fallback" if legacy_version else "repository-input"
        extra = f"gradleProject={module_path}; appVersion={app_version}"
        if legacy_version:
            extra += "; versionInfoCompatibility=static-fallback"
        module_ids[module_path] = registry.add_package(
            name=package_name_for_module(module_path), version=version,
            supplier="Organization: MuYuChat MCA Team",
            originator="Organization: MuYuChat MCA Team",
            download_location="NOASSERTION",
            license_declared="Apache-2.0", license_concluded="Apache-2.0",
            description=f"MCA Gradle module {module_path}; source snapshot is build.gradle.kts plus src/main.",
            provenance=provenance, files=module_source_files(module_dir),
            source_info=[f"gradleProject={module_path}",
                         f"buildFile={rel_posix(module_dir / 'build.gradle.kts', root)}",
                         f"appVersionName={app['versionName']}", f"appVersionCode={app['versionCode']}"],
            external_refs=[{"referenceCategory": "OTHER", "referenceType": "mca-gradle-project",
                            "referenceLocator": f"mca:gradle-project:{module_path}"}],
            primary_purpose="APPLICATION" if module_path == ":app" else "LIBRARY",
            extra_comment=extra,
        )

    for module_path in sorted(module_ids):
        for dependency in module_dependencies.get(module_path, []):
            if dependency["kind"] == "project" and dependency["path"] in module_ids:
                registry.add_relationship(module_ids[module_path], "DEPENDS_ON", module_ids[dependency["path"]])

    build_tools = [
        ("kotlin", catalogs["versions"].get("kotlin", CORE_CONTRACT["kotlin"]),
         "Organization: JetBrains s.r.o.", "Apache-2.0", "https://kotlinlang.org/", catalogs["files"]),
        ("android-gradle-plugin", catalogs["versions"].get("agp", "NOASSERTION"),
         "Organization: Google LLC", "Apache-2.0", "https://developer.android.com/build", catalogs["files"]),
        ("ksp", catalogs["versions"].get("ksp", "NOASSERTION"),
         "Organization: Google LLC", "Apache-2.0", "https://github.com/google/ksp", catalogs["files"]),
    ]
    wrapper = root / "gradle" / "wrapper" / "gradle-wrapper.properties"
    wrapper_version = "NOASSERTION"
    if wrapper.is_file():
        match = re.search(r"gradle-([0-9][^/]*?)-(?:bin|all)\.zip", read_text(wrapper))
        if match:
            wrapper_version = match.group(1)
    build_tools.append(("gradle", wrapper_version, "Organization: Gradle, Inc.",
                        "Apache-2.0", "https://gradle.org/", [wrapper] if wrapper.is_file() else []))
    for name, version, supplier, license_name, download, files in build_tools:
        provenance = "repository-input"
        if not version or version == "NOASSERTION":
            warnings.append(f"build tool version unresolved: {name}")
            version = "NOASSERTION"
            provenance = "static-fallback"
        package_id = registry.add_package(
            name=name, version=version, supplier=supplier, originator=supplier,
            download_location=download, license_declared=license_name,
            license_concluded=license_name,
            description=f"Gradle build tool component {name}.", provenance=provenance,
            files=files,
            source_info=["gradle/libs.versions.toml" if name != "gradle"
                         else "gradle/wrapper/gradle-wrapper.properties"],
            external_refs=[{"referenceCategory": "OTHER", "referenceType": "build-tool",
                            "referenceLocator": f"mca:build-tool:{name}"}],
            primary_purpose="OTHER", extra_comment="role=build-tool",
        )
        if ":app" in module_ids:
            registry.add_relationship(package_id, "BUILD_DEPENDENCY_OF", module_ids[":app"])

    qairt_root = find_qairt_sdk(root)
    qairt_manifest: dict[str, Any] = {"metadata": {}, "dependencies": {}}
    qairt_version = CORE_CONTRACT["qairt-sdk"]
    qairt_provenance = "static-fallback"
    qairt_sources: list[str] = []
    qairt_files: list[tuple[Path, str]] = []
    if qairt_root:
        manifest_path = qairt_root / "sdk.yaml"
        qairt_manifest = parse_qairt_manifest(manifest_path)
        qairt_version = qairt_manifest["metadata"].get("version") or qairt_version
        qairt_provenance = "repository-input"
        qairt_sources = ["sdkRoot=external-sdk",
                         f"qairtManifest=external/qairt-sdk/{qairt_version}/sdk.yaml"]
        qairt_files.append((manifest_path, f"external/qairt-sdk/{qairt_version}/sdk.yaml"))
        for notice_name in ("QNN_NOTICE.txt", "QAIRT_ReleaseNotes.txt"):
            notice = qairt_root / notice_name
            if notice.is_file():
                qairt_files.append((notice, f"external/qairt-sdk/{qairt_version}/{notice_name}"))
    else:
        warnings.append("QAIRT SDK manifest not found; qairt-sdk uses static fallback version")

    qairt_manifest_sha256 = (sha256_file(qairt_root / "sdk.yaml") if qairt_root else "")
    qairt_id = registry.add_package(
        name="qairt-sdk", version=qairt_version,
        supplier="Organization: Qualcomm Technologies, Inc.",
        originator="Organization: Qualcomm Technologies, Inc.",
        download_location="NOASSERTION",
        license_declared="LicenseRef-Qualcomm-Proprietary",
        license_concluded="LicenseRef-Qualcomm-Proprietary",
        description="Qualcomm AI Runtime (QAIRT/QNN) SDK manifest and runtime inputs.",
        provenance=qairt_provenance, external_files=qairt_files,
        source_info=qairt_sources or ["static fallback identity because no local sdk.yaml was found"],
        external_refs=[{"referenceCategory": "OTHER", "referenceType": "sdk-manifest",
                        "referenceLocator": f"mca:qairt:{qairt_version}"}],
        extra_comment="manifest=sdk.yaml; externalSdkFilesAreNotCopiedIntoRepository=true",
    )
    if ":core:native" in module_ids:
        registry.add_relationship(module_ids[":core:native"], "DEPENDS_ON", qairt_id)

    for dependency_name, dependency_version in sorted(qairt_manifest["dependencies"].items()):
        package_name = "qairt-build-android-ndk" if dependency_name == "android-ndk" else dependency_name
        supplier = MANIFEST_SUPPLIERS.get(dependency_name, "NOASSERTION")
        license_name = MANIFEST_LICENSES.get(dependency_name, "NOASSERTION")
        package_id = registry.add_package(
            name=package_name, version=dependency_version or "NOASSERTION",
            supplier=supplier, originator=supplier,
            download_location=MANIFEST_DOWNLOADS.get(dependency_name, "NOASSERTION"),
            license_declared=license_name, license_concluded=license_name,
            description=f"Dependency declared by the local QAIRT SDK manifest as {dependency_name}.",
            provenance="qairt-sdk-manifest",
            source_info=[f"qairtManifest=external/qairt-sdk/{qairt_version}/sdk.yaml"
                         if qairt_root else "qairt-sdk-manifest unavailable",
                         f"declaredDependencyName={dependency_name}"],
            external_refs=[{"referenceCategory": "OTHER", "referenceType": "sdk-dependency",
                            "referenceLocator": f"mca:qairt-dependency:{dependency_name}"}],
            files_analyzed=False,
            checksums=([{"algorithm": "SHA256", "checksumValue": qairt_manifest_sha256}]
                       if qairt_manifest_sha256 else []),
            extra_comment=("versionSource=qairt-sdk-manifest; checksumScope=qairt-sdk-manifest; "
                           "checksumSource=sdk.yaml"),
        )
        registry.add_relationship(qairt_id, "DEPENDS_ON", package_id)

    gitmodules = {entry.get("path", ""): entry for entry in parse_gitmodules(root / ".gitmodules")}
    mnn_props_path = root / "vendor" / "mnn" / "mnn-vendor.properties"
    mnn_props = parse_properties(mnn_props_path)
    mnn_patch = root / "vendor" / "mnn" / mnn_props.get("patch", "mca-mnn-3.6.1.patch")
    mnn_ref = mnn_props.get("ref") or "3.6.1"
    mnn_version = CORE_CONTRACT["alibaba-mnn"] if mnn_patch.is_file() else mnn_ref
    mnn_files: list[Path] = [mnn_props_path]
    add_if_file(mnn_files, mnn_patch)
    mnn_dir = root / "third_party" / "MNN"
    mnn_commit = mnn_props.get("commit")
    if mnn_dir.is_dir():
        mnn_commit = run_readonly(["git", "rev-parse", "HEAD"], mnn_dir) or mnn_commit
        for relative_name in mnn_props.get("files", "").split("|"):
            if relative_name:
                add_if_file(mnn_files, mnn_dir / relative_name)
    mnn_id = registry.add_package(
        name="alibaba-mnn", version=mnn_version,
        supplier="Organization: Alibaba Group", originator="Organization: Alibaba Group",
        download_location=mnn_props.get("repository", "NOASSERTION"),
        license_declared="Apache-2.0", license_concluded="Apache-2.0",
        description="Alibaba MNN inference engine with repository-owned MCA patch.",
        provenance="repository-input", files=mnn_files,
        source_info=["input=vendor/mnn/mnn-vendor.properties",
                     f"repository={mnn_props.get('repository', 'NOASSERTION')}",
                     f"ref={mnn_ref}", f"commit={mnn_commit or 'NOASSERTION'}",
                     f"patchSha256={mnn_props.get('patchSha256', 'NOASSERTION')}"],
        external_refs=[{"referenceCategory": "OTHER", "referenceType": "vcs",
                        "referenceLocator": f"git+{mnn_props.get('repository', 'NOASSERTION')}@{mnn_commit or 'NOASSERTION'}"}],
        extra_comment="versionInfo=ref+mca-patched; sourceCheckout=third_party/MNN",
    )
    if ":core:native" in module_ids:
        registry.add_relationship(module_ids[":core:native"], "DEPENDS_ON", mnn_id)

    llama_props_path = root / "vendor" / "llama" / "llama-vendor.properties"
    llama_props = parse_properties(llama_props_path)
    llama_dir = root / "third_party" / "llama.cpp"
    llama_commit = git_submodule_commit(root, "third_party/llama.cpp") or llama_props.get("baseCommit")
    llama_url = gitmodules.get("third_party/llama.cpp", {}).get("url") or git_remote(llama_dir) or "NOASSERTION"
    llama_files: list[Path] = [llama_props_path]
    add_if_file(llama_files, root / ".gitmodules")
    for patch_name in ("llama-stq1_0.patch", "llama-kleidiai-kquant-fallback.patch", "llama-vulkan-no-coopmat.patch"):
        add_if_file(llama_files, root / "vendor" / "llama" / patch_name)
    llama_files.extend(submodule_files(llama_dir, [
        "LICENSE", "CMakeLists.txt", "README.md", ".gitmodules",
        "src/llama.cpp", "ggml/src/ggml.c", "ggml/include/ggml.h",
    ]))
    llama_id = registry.add_package(
        name="llama-cpp", version=CORE_CONTRACT["llama-cpp"],
        supplier="Person: Georgi Gerganov and contributors",
        originator="Person: Georgi Gerganov", download_location=llama_url,
        license_declared="MIT", license_concluded="MIT",
        description="llama.cpp source submodule with MCA patches.",
        provenance="static-fallback", files=llama_files,
        source_info=["input=vendor/llama/llama-vendor.properties",
                     f"resolvedCommit={llama_commit or 'NOASSERTION'}",
                     f"gitDescribe={git_describe(llama_dir) or 'NOASSERTION'}",
                     f"baseRef={llama_props.get('baseRef', 'NOASSERTION')}",
                     f"baseTag={llama_props.get('baseTag', 'NOASSERTION')}"],
        external_refs=[{"referenceCategory": "OTHER", "referenceType": "vcs",
                        "referenceLocator": f"git+{llama_url}@{llama_commit or 'NOASSERTION'}"}],
        extra_comment="versionInfoCompatibility=static-fallback; resolvedCommit is source authority",
    )
    if ":core:native" in module_ids:
        registry.add_relationship(module_ids[":core:native"], "DEPENDS_ON", llama_id)

    sd_dir = root / "third_party" / "stable-diffusion.cpp"
    sd_commit = git_submodule_commit(root, "third_party/stable-diffusion.cpp")
    sd_url = gitmodules.get("third_party/stable-diffusion.cpp", {}).get("url") or git_remote(sd_dir) or "NOASSERTION"
    sd_ggml_props_path = root / "vendor" / "stable-diffusion-ggml.properties"
    sd_ggml_props = parse_properties(sd_ggml_props_path)
    sd_ggml_dir = sd_dir / "ggml"
    sd_ggml_commit = run_readonly(["git", "rev-parse", "HEAD"], sd_ggml_dir)
    sd_ggml_commit = sd_ggml_commit or sd_ggml_props.get("baseCommit", "NOASSERTION")
    sd_ggml_patch = root / sd_ggml_props.get(
        "patch", "third_party/patches/stable-diffusion-ggml-wan-5d.patch")
    sd_ggml_patch_sha = sha256_file(sd_ggml_patch) if sd_ggml_patch.is_file() else ""
    expected_sd_ggml_patch_sha = sd_ggml_props.get("patchSha256", "").lower()
    if expected_sd_ggml_patch_sha and sd_ggml_patch_sha != expected_sd_ggml_patch_sha:
        warnings.append(
            "stable-diffusion nested ggml MCA patch checksum mismatch: "
            f"declared={expected_sd_ggml_patch_sha} actual={sd_ggml_patch_sha or 'MISSING'}"
        )
    sd_files: list[Path] = []
    add_if_file(sd_files, root / ".gitmodules")
    add_if_file(sd_files, root / "third_party" / "patches" / "stable-diffusion.cpp-mca-android.patch")
    add_if_file(sd_files, sd_ggml_props_path)
    add_if_file(sd_files, sd_ggml_patch)
    sd_files.extend(submodule_files(sd_dir, [
        "LICENSE", "CMakeLists.txt", "README.md", ".gitmodules",
        "src/stable-diffusion.cpp", "include/stable-diffusion.h",
    ]))
    sd_id = registry.add_package(
        name="stable-diffusion-cpp", version=CORE_CONTRACT["stable-diffusion-cpp"],
        supplier="Person: leejet and contributors", originator="Person: leejet",
        download_location=sd_url, license_declared="MIT", license_concluded="MIT",
        description="stable-diffusion.cpp source with MCA Android and pinned nested ggml Wan 5D patches.",
        provenance="static-fallback", files=sd_files,
        source_info=["input=.gitmodules", f"resolvedCommit={sd_commit or 'NOASSERTION'}",
                     f"gitDescribe={git_describe(sd_dir) or 'NOASSERTION'}",
                     f"nestedGgmlCommit={sd_ggml_commit}",
                     f"nestedGgmlPatchSha256={sd_ggml_patch_sha or 'NOASSERTION'}"],
        external_refs=[{"referenceCategory": "OTHER", "referenceType": "vcs",
                        "referenceLocator": f"git+{sd_url}@{sd_commit or 'NOASSERTION'}"}],
        extra_comment="versionInfoCompatibility=static-fallback; resolvedCommit is source authority",
    )
    if ":core:sd-native" in module_ids:
        registry.add_relationship(module_ids[":core:sd-native"], "DEPENDS_ON", sd_id)

    tokenizers_dir = root / "third_party" / "tokenizers-cpp"
    tokenizers_commit = git_submodule_commit(root, "third_party/tokenizers-cpp")
    tokenizers_url = gitmodules.get("third_party/tokenizers-cpp", {}).get("url") or git_remote(tokenizers_dir) or "NOASSERTION"
    tokenizers_manifest_path = root / "vendor" / "tokenizers-cpp.properties"
    tokenizers_props = parse_properties(tokenizers_manifest_path)
    tokenizers_patch = root / tokenizers_props.get("patch", "third_party/patches/tokenizers-cpp-mca.patch")
    expected_tokenizers_patch_sha = tokenizers_props.get("patchSha256", "").lower()
    actual_tokenizers_patch_sha = sha256_file(tokenizers_patch) if tokenizers_patch.is_file() else ""
    if expected_tokenizers_patch_sha and actual_tokenizers_patch_sha != expected_tokenizers_patch_sha:
        warnings.append(
            "tokenizers-cpp MCA patch checksum mismatch: "
            f"declared={expected_tokenizers_patch_sha} actual={actual_tokenizers_patch_sha or 'MISSING'}"
        )
    tokenizers_files = submodule_files(tokenizers_dir, ["LICENSE", "CMakeLists.txt", "README.md", "src/tokenizers.cpp"])
    add_if_file(tokenizers_files, root / ".gitmodules")
    add_if_file(tokenizers_files, tokenizers_manifest_path)
    add_if_file(tokenizers_files, tokenizers_patch)
    tokenizers_id = registry.add_package(
        name="tokenizers-cpp", version=git_describe(tokenizers_dir) or tokenizers_commit or "NOASSERTION",
        supplier="Organization: MLC AI", originator="Organization: MLC AI",
        download_location=tokenizers_url,
        license_declared=detect_license(tokenizers_dir, "Apache-2.0"),
        license_concluded=detect_license(tokenizers_dir, "Apache-2.0"),
        description="tokenizers-cpp source submodule with the pinned MCA error-boundary and rebuild-dependency patch.",
        provenance="repository-input",
        files=tokenizers_files,
        source_info=["input=.gitmodules", f"resolvedCommit={tokenizers_commit or 'NOASSERTION'}",
                     f"gitDescribe={git_describe(tokenizers_dir) or 'NOASSERTION'}",
                     f"patchBase={tokenizers_props.get('baseCommit', 'NOASSERTION')}",
                     f"patchSha256={actual_tokenizers_patch_sha or 'NOASSERTION'}"],
        external_refs=[{"referenceCategory": "OTHER", "referenceType": "vcs",
                        "referenceLocator": f"git+{tokenizers_url}@{tokenizers_commit or 'NOASSERTION'}"}],
    )
    if ":core:native" in module_ids:
        registry.add_relationship(module_ids[":core:native"], "DEPENDS_ON", tokenizers_id)


    geniex_props_path = root / "vendor" / "geniex" / "geniex-vendor.properties"
    geniex_props = parse_properties(geniex_props_path)
    geniex_aar = root / "vendor" / "geniex" / "geniex-android-0.10.0-mca1.aar"
    geniex_files = [path for path in [
        geniex_aar, geniex_props_path,
        root / "vendor" / "geniex" / "LICENSE-GenieX.txt",
        root / "vendor" / "geniex" / "NOTICE-GenieX.txt",
    ] if path.is_file()]
    geniex_checksums: list[dict[str, str]] = []
    if geniex_aar.is_file():
        actual = sha256_file(geniex_aar)
        geniex_checksums.append({"algorithm": "SHA256", "checksumValue": actual})
        declared = geniex_props.get("vendoredAarSha256", "").lower()
        if declared and declared != actual:
            warnings.append(f"GenieX vendored AAR checksum mismatch: declared={declared} actual={actual}")
    geniex_id = registry.add_package(
        name="geniex-android", version=geniex_props.get("localVersion", "NOASSERTION"),
        supplier="Organization: Qualcomm Technologies, Inc.",
        originator="Organization: Qualcomm Technologies, Inc.",
        download_location=geniex_props.get("upstreamRepository", "NOASSERTION"),
        license_declared=geniex_props.get("license", "BSD-3-Clause"),
        license_concluded=geniex_props.get("license", "BSD-3-Clause"),
        description="Official Qualcomm GenieX Android AAR, vendored without source modifications.",
        provenance="repository-input", files=geniex_files,
        source_info=["input=vendor/geniex/geniex-vendor.properties",
                     f"coordinates={geniex_props.get('coordinates', 'NOASSERTION')}",
                     f"upstreamTag={geniex_props.get('upstreamTag', 'NOASSERTION')}",
                     f"vendoredAarSha256={geniex_props.get('vendoredAarSha256', 'NOASSERTION')}"],
        external_refs=[{"referenceCategory": "OTHER", "referenceType": "vcs",
                        "referenceLocator": f"git+{geniex_props.get('upstreamRepository', 'NOASSERTION')}@{geniex_props.get('upstreamTag', 'NOASSERTION')}"}],
        checksums=geniex_checksums, extra_comment="artifact=geniex-android-0.10.0-mca1.aar",
    )
    if ":core:engine" in module_ids:
        registry.add_relationship(module_ids[":core:engine"], "DEPENDS_ON", geniex_id)
    if ":app" in module_ids:
        registry.add_relationship(module_ids[":app"], "DEPENDS_ON", geniex_id)

    geniex_reference_props_path = root / "vendor" / "geniex" / "geniex-0.3.12-reference.properties"
    geniex_reference_props = parse_properties(geniex_reference_props_path)
    geniex_reference_aar = root / "vendor" / "geniex" / "geniex-android-0.3.12-mca1.aar"
    geniex_reference_files = [path for path in [
        geniex_reference_aar, geniex_reference_props_path,
        root / "vendor" / "geniex" / "0001-reserve-llm-chat-message-string-storage.patch",
        root / "vendor" / "geniex" / "LICENSE-GenieX.txt",
        root / "vendor" / "geniex" / "NOTICE-GenieX.txt",
    ] if path.is_file()]
    geniex_reference_checksums: list[dict[str, str]] = []
    if geniex_reference_aar.is_file():
        actual = sha256_file(geniex_reference_aar)
        geniex_reference_checksums.append({"algorithm": "SHA256", "checksumValue": actual})
        declared = geniex_reference_props.get("vendoredAarSha256", "").lower()
        if declared and declared != actual:
            warnings.append(
                f"GenieX reference AAR checksum mismatch: declared={declared} actual={actual}"
            )
    geniex_reference_id = registry.add_package(
        name="geniex-android-reference",
        version=geniex_reference_props.get("localVersion", "NOASSERTION"),
        supplier="Organization: Qualcomm Technologies, Inc.",
        originator="Organization: Qualcomm Technologies, Inc.",
        download_location=geniex_reference_props.get("upstreamRepository", "NOASSERTION"),
        license_declared=geniex_reference_props.get("license", "BSD-3-Clause"),
        license_concluded=geniex_reference_props.get("license", "BSD-3-Clause"),
        description="Archived Qualcomm GenieX AAR retained for rollback/reference; not used by production variants.",
        provenance="repository-input", files=geniex_reference_files,
        source_info=["input=vendor/geniex/geniex-0.3.12-reference.properties",
                     f"coordinates={geniex_reference_props.get('coordinates', 'NOASSERTION')}",
                     f"upstreamCommit={geniex_reference_props.get('upstreamCommit', 'NOASSERTION')}",
                     f"vendoredAarSha256={geniex_reference_props.get('vendoredAarSha256', 'NOASSERTION')}"],
        external_refs=[{"referenceCategory": "OTHER", "referenceType": "vcs",
                        "referenceLocator": f"git+{geniex_reference_props.get('upstreamRepository', 'NOASSERTION')}@{geniex_reference_props.get('upstreamCommit', 'NOASSERTION')}"}],
        checksums=geniex_reference_checksums,
        extra_comment="artifact=geniex-android-0.3.12-mca1.aar; runtimeDependency=false",
    )

    litert_root = root / "vendor" / "litert" / "qualcomm"
    litert_files = sorted((path for path in litert_root.rglob("*.so") if path.is_file()),
                          key=lambda item: item.as_posix()) if litert_root.is_dir() else []
    litert_id = registry.add_package(
        name="litert-qualcomm-runtime", version=qairt_version,
        supplier="Organization: Qualcomm Technologies, Inc.",
        originator="Organization: Qualcomm Technologies, Inc.",
        download_location="NOASSERTION",
        license_declared="LicenseRef-Qualcomm-Proprietary",
        license_concluded="LicenseRef-Qualcomm-Proprietary",
        description="Vendored LiteRT Qualcomm dispatch/QNN transport binaries for Android.",
        provenance="repository-input" if qairt_root else "static-fallback",
        files=litert_files,
        source_info=["input=vendor/litert/qualcomm",
                     f"runtimeQairtCompatibility={qairt_version}",
                     f"binaryCount={len(litert_files)}"],
        external_refs=[{"referenceCategory": "OTHER", "referenceType": "vendored-runtime",
                        "referenceLocator": "mca:vendor:litert-qualcomm"}],
        extra_comment="versionInfo=QAIRT compatibility label; no embedded LiteRT Qualcomm binary manifest",
    )
    if ":app" in module_ids:
        registry.add_relationship(module_ids[":app"], "DEPENDS_ON", litert_id)

    qwen_root = root / "vendor" / "qwen-image-2.1"
    qwen_files: list[Path] = []
    for path in [qwen_root / "CMakeLists.txt",
                 qwen_root / "arm64-v8a" / "runtime-manifest.json",
                 qwen_root / "SHA256SUMS.txt",
                 qwen_root / "LICENSE-MNN-Apache-2.0.txt",
                 qwen_root / "LICENSE-QwenImage21-Apache-2.0.txt"]:
        add_if_file(qwen_files, path)
    if (qwen_root / "arm64-v8a").is_dir():
        qwen_files.extend(sorted((path for path in (qwen_root / "arm64-v8a").glob("*.so") if path.is_file()),
                                 key=lambda item: item.as_posix()))
    if (qwen_root / "licenses").is_dir():
        qwen_files.extend(sorted((path for path in (qwen_root / "licenses").rglob("*.txt") if path.is_file()),
                                 key=lambda item: item.as_posix()))
    qwen_id = registry.add_package(
        name="qwen-image-2.1-mnn-runtime", version="2.1-mca-pinned",
        supplier="Organization: Alibaba Group", originator="Organization: Alibaba Group",
        download_location="NOASSERTION",
        license_declared="Apache-2.0", license_concluded="Apache-2.0",
        description="Qwen-Image-2.1 private MNN runtime and JNI bridge binaries.",
        provenance="static-fallback", files=qwen_files,
        source_info=["input=vendor/qwen-image-2.1/arm64-v8a/runtime-manifest.json",
                     "input=vendor/qwen-image-2.1/CMakeLists.txt",
                     "binariesAreLocallyVendored=true"],
        external_refs=[{"referenceCategory": "OTHER", "referenceType": "vendored-runtime",
                        "referenceLocator": "mca:vendor:qwen-image-2.1"}],
        extra_comment="versionInfoCompatibility=static-fallback; runtime manifest has file hashes but no component version",
    )
    if ":app" in module_ids:
        registry.add_relationship(module_ids[":app"], "DEPENDS_ON", qwen_id)
    registry.add_relationship(qwen_id, "DEPENDS_ON", mnn_id)

    maven_aggregate: dict[tuple[str, str], dict[str, Any]] = {}
    unresolved_aliases: list[str] = []
    compose_bom_version = catalogs["versions"].get("composeBom", "")
    compose_bom_versions = parse_maven_bom_versions(
        "androidx.compose", "compose-bom", compose_bom_version)
    for module_path in sorted(reachable):
        for dependency in module_dependencies.get(module_path, []):
            if dependency["kind"] == "unresolved_alias":
                unresolved_aliases.append(f"{module_path}:{dependency['alias']} in {dependency['source']}")
                continue
            if dependency["kind"] != "maven":
                continue
            version = dependency.get("version") or "NOASSERTION"
            version_source = "unresolved-managed-platform"
            if version == "NOASSERTION":
                resolved = compose_bom_versions.get((dependency["group"], dependency["artifact"]))
                if resolved:
                    version = resolved
                    version_source = "local-maven-bom"
            package_name, package_version = compatibility_component(
                dependency["group"], dependency["artifact"], version)
            aggregate = maven_aggregate.setdefault((package_name, package_version), {
                "name": package_name, "version": package_version,
                "coordinates": set(), "consumers": set(), "sources": set(), "managed": set(),
            })
            aggregate["coordinates"].add((dependency["group"], dependency["artifact"]))
            aggregate["consumers"].add(module_path)
            aggregate["sources"].add(dependency["source"])
            aggregate.setdefault("versionSources", set()).add(version_source)
            if dependency.get("managed_by"):
                aggregate["managed"].add(dependency["managed_by"])
    for text in unresolved_aliases:
        warnings.append(f"unresolved Gradle version catalog alias: {text}")

    for key in sorted(maven_aggregate):
        aggregate = maven_aggregate[key]
        coordinates = sorted(aggregate["coordinates"])
        checksums: list[dict[str, str]] = []
        checksum_source = "not-available-static"
        checksum_scope = "not-available"
        for group, artifact in coordinates:
            cached = find_gradle_cache_artifact(group, artifact, aggregate["version"])
            if cached:
                checksums = [{"algorithm": "SHA256", "checksumValue": sha256_file(cached)}]
                checksum_source = "gradle-cache-artifact"
                checksum_scope = "gradle-artifact"
                break
        if not checksums:
            for group, artifact in coordinates:
                metadata = find_gradle_cache_metadata(group, artifact, aggregate["version"])
                if metadata:
                    checksums = [{"algorithm": "SHA256", "checksumValue": sha256_file(metadata)}]
                    checksum_source = "gradle-cache-metadata"
                    checksum_scope = "gradle-metadata"
                    break
        provenance = "static-fallback" if aggregate["version"] == "NOASSERTION" else "repository-input"
        version_source = ",".join(sorted(aggregate.get("versionSources", [])))
        download = (maven_download_url(coordinates[0][0], coordinates[0][1], aggregate["version"])
                    if len(coordinates) == 1 else "NOASSERTION")
        refs = [{"referenceCategory": "PACKAGE-MANAGER", "referenceType": "purl",
                 "referenceLocator": purl_for_coordinate(group, artifact, aggregate["version"])}
                for group, artifact in coordinates]
        license_names = sorted({license_for_coordinate(group, artifact) for group, artifact in coordinates})
        license_name = license_names[0] if len(license_names) == 1 else " AND ".join(license_names)
        suppliers = sorted({supplier_for_group(group) for group, _ in coordinates})
        supplier = suppliers[0] if len(suppliers) == 1 else "NOASSERTION"
        package_id = registry.add_package(
            name=aggregate["name"], version=aggregate["version"],
            supplier=supplier, originator=supplier,
            download_location=download, license_declared=license_name,
            license_concluded=license_name,
            description="Direct Maven/Gradle production dependency declared by reachable MCA modules.",
            provenance=provenance, source_info=sorted(aggregate["sources"]),
            external_refs=refs, files_analyzed=False, checksums=checksums,
            extra_comment=(f"coordinates={','.join(group + ':' + artifact for group, artifact in coordinates)}; "
                           f"versionSource={version_source}; checksumScope={checksum_scope}; checksumSource={checksum_source}"
                           + (f"; managedBy={','.join(sorted(aggregate['managed']))}" if aggregate["managed"] else "")),
        )
        for consumer in sorted(aggregate["consumers"]):
            if consumer in module_ids:
                registry.add_relationship(module_ids[consumer], "DEPENDS_ON", package_id)

    android_sdk = android_sdk_root(root)
    compile_sdk = catalogs["versions"].get("compileSdk", "NOASSERTION")
    ndk_version = catalogs["versions"].get("ndk", "NOASSERTION")
    platform_manifest = android_sdk / "platforms" / f"android-{compile_sdk}" / "source.properties" if android_sdk else None
    ndk_manifest = android_sdk / "ndk" / ndk_version / "source.properties" if android_sdk else None
    platform_files: list[tuple[Path, str]] = []
    ndk_files: list[tuple[Path, str]] = []
    if platform_manifest and platform_manifest.is_file():
        platform_files.append((platform_manifest, f"external/android-sdk/platforms/android-{compile_sdk}/source.properties"))
    if ndk_manifest and ndk_manifest.is_file():
        ndk_files.append((ndk_manifest, f"external/android-sdk/ndk/{ndk_version}/source.properties"))
    platform_id = registry.add_package(
        name="android-sdk-platform", version=compile_sdk,
        supplier="Organization: Google LLC", originator="Organization: Google LLC",
        download_location="https://developer.android.com/studio",
        license_declared="LicenseRef-Android-SDK", license_concluded="LicenseRef-Android-SDK",
        description="Android SDK platform used by compileSdk.",
        provenance="repository-input" if platform_files else "static-fallback",
        external_files=platform_files,
        source_info=["sdkRoot=external/android-sdk" if android_sdk else "sdkRoot=NOT_FOUND",
                     f"compileSdk={compile_sdk}"],
        extra_comment="manifest=source.properties" if platform_files else "manifestUnavailable=true",
    )
    ndk_id = registry.add_package(
        name="android-ndk", version=ndk_version,
        supplier="Organization: Google LLC", originator="Organization: Google LLC",
        download_location="https://developer.android.com/ndk/downloads",
        license_declared="LicenseRef-Android-SDK", license_concluded="LicenseRef-Android-SDK",
        description="Android NDK used by MCA native modules.",
        provenance="repository-input" if ndk_files else "static-fallback",
        external_files=ndk_files,
        source_info=["sdkRoot=external/android-sdk" if android_sdk else "sdkRoot=NOT_FOUND",
                     f"ndkVersion={ndk_version}"],
        extra_comment="manifest=source.properties" if ndk_files else "manifestUnavailable=true",
    )
    if ":app" in module_ids:
        registry.add_relationship(platform_id, "BUILD_DEPENDENCY_OF", module_ids[":app"])
    if ":core:native" in module_ids:
        registry.add_relationship(ndk_id, "BUILD_DEPENDENCY_OF", module_ids[":core:native"])

    apk_id: str | None = None
    if apk_path is not None:
        apk_path = apk_path.resolve()
        if not apk_path.is_file():
            raise SbomError(f"APK does not exist: {apk_path}")
        metadata = apk_metadata(apk_path, app)
        apk_id = registry.add_package(
            name="mca-android-apk", version=app_version,
            supplier="Organization: MuYuChat MCA Team",
            originator="Organization: MuYuChat MCA Team",
            download_location="NOASSERTION",
            license_declared="Apache-2.0", license_concluded="Apache-2.0",
            description="Final MCA Android APK artifact bound by SHA256 and byte size.",
            provenance="repository-input",
            source_info=[f"apkInput={rel_posix(apk_path, root) or 'external-apk'}",
                         f"apkSha256={metadata['sha256']}", f"abi={metadata['abis']}",
                         f"variation={metadata['variation']}"],
            files_analyzed=False,
            checksums=[{"algorithm": "SHA256", "checksumValue": metadata["sha256"]}],
            primary_purpose="APPLICATION", package_file_name=apk_path.name,
            external_refs=[{"referenceCategory": "OTHER", "referenceType": "apk-artifact",
                            "referenceLocator": f"mca:apk:{apk_path.name}"}],
            extra_comment=f"artifact=APK; bytes={metadata['bytes']}; abi={metadata['abis']}; variation={metadata['variation']}",
        )
        if ":app" in module_ids:
            registry.add_relationship(module_ids[":app"], "CONTAINED_BY", apk_id)
            registry.add_relationship(apk_id, "CONTAINS", module_ids[":app"])

    if ":app" not in module_ids:
        raise SbomError("Gradle :app module was not discovered")
    registry.add_relationship(DOCUMENT_ID, "DESCRIBES", module_ids[":app"])
    for core_name, core_version in sorted(CORE_CONTRACT.items()):
        try:
            core_id = registry.id_for(core_name, core_version)
        except SbomError:
            continue
        registry.add_relationship(DOCUMENT_ID, "DESCRIBES", core_id)
    for described_id in (geniex_id, geniex_reference_id, litert_id, qwen_id, tokenizers_id):
        registry.add_relationship(DOCUMENT_ID, "DESCRIBES", described_id)
    if apk_id:
        registry.add_relationship(DOCUMENT_ID, "DESCRIBES", apk_id)

    packages = [registry.packages[key] for key in sorted(registry.packages)]
    files = [registry.files.files[key] for key in sorted(registry.files.files)]
    relationships = [{"spdxElementId": left, "relationshipType": relation,
                      "relatedSpdxElement": right}
                     for left, relation, right in sorted(registry.relationships)]
    creation_time = resolve_creation_time(root)
    seed = json.dumps({"created": creation_time, "packages": packages,
                       "files": files, "relationships": relationships},
                      ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    namespace_hash = hashlib.sha256(seed).hexdigest()
    fallback_count = sum("provenance=static-fallback" in package["comment"] for package in packages)
    document = {
        "spdxVersion": "SPDX-2.3", "dataLicense": "CC0-1.0", "SPDXID": DOCUMENT_ID,
        "name": "MuYu-Chat-Agent-SBOM",
        "documentNamespace": f"https://muyuchat.com/spdxdocs/mca-{namespace_hash}",
        "creationInfo": {
            "created": creation_time,
            "creators": [f"Tool: MCA-SBOM-Generator-{GENERATOR_VERSION}",
                         "Organization: MuYuChat Engineering Team"],
            "comment": ("provenance=repository-input; scan=offline-static; "
                        f"staticFallbackPackages={fallback_count}; "
                        f"apk={'bound' if apk_id else 'absent'}"),
        },
        "packages": packages, "files": files, "relationships": relationships,
    }
    return document, sorted(set(warnings))


def serialize_document(document: dict[str, Any]) -> str:
    return json.dumps(document, indent=2, sort_keys=True, ensure_ascii=False) + "\n"


def checksum_errors(checksums: Any, owner: str) -> list[str]:
    errors: list[str] = []
    if checksums is None:
        return errors
    if not isinstance(checksums, list):
        return [f"{owner}: checksums must be a list"]
    patterns = {"SHA1": r"^[0-9a-f]{40}$", "SHA256": r"^[0-9a-f]{64}$", "MD5": r"^[0-9a-f]{32}$"}
    for item in checksums:
        if not isinstance(item, dict):
            errors.append(f"{owner}: malformed checksum record")
            continue
        algorithm, value = item.get("algorithm"), item.get("checksumValue")
        if algorithm not in patterns:
            errors.append(f"{owner}: unsupported checksum algorithm {algorithm!r}")
        elif not isinstance(value, str) or not re.fullmatch(patterns[algorithm], value):
            errors.append(f"{owner}: malformed {algorithm} checksum")
    return errors


def verify_document(document: dict[str, Any], output_path: Path, root: Path,
                    apk_path: Path | None = None) -> list[str]:
    errors: list[str] = []
    if contains_local_absolute_path(document):
        errors.append("document contains a local absolute path")
    if document.get("spdxVersion") != "SPDX-2.3":
        errors.append("spdxVersion must be SPDX-2.3")
    if document.get("dataLicense") != "CC0-1.0":
        errors.append("dataLicense must be CC0-1.0")
    if document.get("SPDXID") != DOCUMENT_ID:
        errors.append("document SPDXID must be SPDXRef-DOCUMENT")
    if not document.get("documentNamespace"):
        errors.append("documentNamespace is missing")
    creation_info = document.get("creationInfo") or {}
    if not creation_info.get("created"):
        errors.append("creationInfo.created is missing")
    if not creation_info.get("creators"):
        errors.append("creationInfo.creators is missing")

    packages = document.get("packages")
    files = document.get("files", [])
    relationships = document.get("relationships")
    if not isinstance(packages, list) or not packages:
        return errors + ["packages must be a non-empty list"]
    if not isinstance(files, list):
        return errors + ["files must be a list"]
    if not isinstance(relationships, list) or not relationships:
        return errors + ["relationships must be a non-empty list"]

    package_ids = [package.get("SPDXID") for package in packages if isinstance(package, dict)]
    file_ids = [record.get("SPDXID") for record in files if isinstance(record, dict)]
    if len(package_ids) != len(set(package_ids)):
        errors.append("duplicate package SPDXID")
    if len(file_ids) != len(set(file_ids)):
        errors.append("duplicate file SPDXID")
    all_ids = {DOCUMENT_ID, *package_ids, *file_ids}
    by_file_id = {record.get("SPDXID"): record for record in files}

    required = {"SPDXID", "name", "versionInfo", "supplier", "originator",
                "downloadLocation", "licenseDeclared", "licenseConcluded",
                "filesAnalyzed", "description", "comment", "sourceInfo"}
    for package in packages:
        pid = package.get("SPDXID", "<missing-id>")
        missing = sorted(required - set(package))
        if missing:
            errors.append(f"{pid}: missing package fields {', '.join(missing)}")
        if not package.get("name"):
            errors.append(f"{pid}: empty name")
        if not package.get("versionInfo"):
            errors.append(f"{pid}: empty versionInfo")
        if not isinstance(package.get("filesAnalyzed"), bool):
            errors.append(f"{pid}: filesAnalyzed must be boolean")
        if "provenance=" not in str(package.get("comment", "")):
            errors.append(f"{pid}: missing provenance= marker")
        if package.get("sourceInfo") in (None, "", "NOASSERTION") and not package.get("externalRefs"):
            errors.append(f"{pid}: needs sourceInfo or externalRefs")
        errors.extend(checksum_errors(package.get("checksums"), f"{pid} package"))
        if package.get("filesAnalyzed"):
            verification = (package.get("packageVerificationCode") or {}).get(
                "packageVerificationCodeValue", "")
            if not isinstance(verification, str) or not re.fullmatch(r"[0-9a-f]{40}", verification):
                errors.append(f"{pid}: invalid packageVerificationCode")
            has_files = package.get("hasFiles")
            if not isinstance(has_files, list) or not has_files:
                errors.append(f"{pid}: analyzed package has no hasFiles")
            else:
                if len(has_files) != len(set(has_files)):
                    errors.append(f"{pid}: duplicate hasFiles entry")
                dangling = sorted(set(has_files) - set(file_ids))
                if dangling:
                    errors.append(f"{pid}: hasFiles references unknown files {dangling[:5]}")
                else:
                    expected = hashlib.sha1("".join(sorted(
                        next(checksum["checksumValue"]
                             for checksum in by_file_id[file_id]["checksums"]
                             if checksum["algorithm"] == "SHA1")
                        for file_id in has_files
                    )).encode("ascii")).hexdigest()
                    if expected != verification:
                        errors.append(f"{pid}: packageVerificationCode does not match hasFiles")

    for record in files:
        fid = record.get("SPDXID", "<missing-id>")
        if not record.get("fileName"):
            errors.append(f"{fid}: fileName is missing")
        errors.extend(checksum_errors(record.get("checksums"), f"{fid} file"))
        algorithms = {item.get("algorithm") for item in record.get("checksums", [])
                      if isinstance(item, dict)}
        if not {"SHA1", "SHA256"}.issubset(algorithms):
            errors.append(f"{fid}: file requires SHA1 and SHA256")

    seen_relations: set[tuple[str, str, str]] = set()
    relation_counts: dict[str, int] = {}
    describes_targets: set[str] = set()
    for relation in relationships:
        if not isinstance(relation, dict):
            errors.append("malformed relationship record")
            continue
        left = relation.get("spdxElementId")
        relation_type = relation.get("relationshipType")
        right = relation.get("relatedSpdxElement")
        triple = (left, relation_type, right)
        if triple in seen_relations:
            errors.append(f"duplicate relationship {triple}")
        seen_relations.add(triple)
        if relation_type not in VALID_RELATIONSHIPS:
            errors.append(f"unsupported relationship type {relation_type!r}")
        if left not in all_ids:
            errors.append(f"dangling relationship source {left!r}")
        if right not in all_ids:
            errors.append(f"dangling relationship target {right!r}")
        relation_counts[relation_type] = relation_counts.get(relation_type, 0) + 1
        if left == DOCUMENT_ID and relation_type == "DESCRIBES" and right:
            describes_targets.add(right)
    if not describes_targets:
        errors.append("document must DESCRIBES at least one package")
    if relation_counts.get("CONTAINS", 0) == 0:
        errors.append("relationship graph has no CONTAINS edges")
    if relation_counts.get("DEPENDS_ON", 0) + relation_counts.get("DEPENDENCY_OF", 0) == 0:
        errors.append("relationship graph has no dependency edges")

    package_versions: dict[str, set[str]] = {}
    for package in packages:
        package_versions.setdefault(package.get("name", ""), set()).add(package.get("versionInfo", ""))
    for name, expected in sorted(CORE_CONTRACT.items()):
        versions = package_versions.get(name, set())
        if versions != {expected}:
            errors.append(f"core package {name}: expected {expected}, found {sorted(versions) or 'missing'}")

    apk_packages = [package for package in packages if package.get("name") == "mca-android-apk"]
    if len(apk_packages) > 1:
        errors.append("more than one mca-android-apk package")
    apk_package = apk_packages[0] if apk_packages else None
    resolved_apk = apk_path.resolve() if apk_path else None
    if resolved_apk is None and apk_package:
        candidate = Path(apk_package.get("packageFileName", ""))
        if not candidate.is_absolute():
            candidate = root / candidate
        if candidate.is_file():
            resolved_apk = candidate
        else:
            errors.append("SBOM contains mca-android-apk but --apk was not supplied and the referenced file is absent")
    if resolved_apk:
        if not apk_package:
            errors.append("--apk supplied but SBOM has no mca-android-apk package")
        elif not resolved_apk.is_file():
            errors.append(f"APK file is missing: {resolved_apk}")
        else:
            actual_sha256 = sha256_file(resolved_apk)
            package_sha256 = {item.get("checksumValue") for item in apk_package.get("checksums", [])
                              if isinstance(item, dict) and item.get("algorithm") == "SHA256"}
            if package_sha256 != {actual_sha256}:
                errors.append("APK SHA256 is not bound to the mca-android-apk package")
            actual_bytes = resolved_apk.stat().st_size
            byte_match = re.search(r"(?:^|; )bytes=(\d+)(?:;|$)", apk_package.get("comment", ""))
            if not byte_match or int(byte_match.group(1)) != actual_bytes:
                errors.append("APK byte size is not bound to the mca-android-apk package")
            comment = apk_package.get("comment", "")
            if "variation=" not in comment or "abi=" not in comment:
                errors.append("APK package is missing ABI/variation metadata")
            if apk_path is not None:
                metadata = apk_metadata(resolved_apk, app_version_info(root))
                if f"variation={metadata['variation']}" not in comment:
                    errors.append("APK variation does not match the supplied artifact")

    if output_path.is_file():
        raw = output_path.read_bytes()
        if b"\r\n" in raw:
            errors.append("SBOM contains CRLF; output must use LF")
        if not raw.endswith(b"\n"):
            errors.append("SBOM must end with LF")
        try:
            raw.decode("utf-8")
        except UnicodeDecodeError:
            errors.append("SBOM is not valid UTF-8")
    else:
        errors.append(f"SBOM file is missing: {output_path}")
    return errors


def emit_summary(document: dict[str, Any], prefix: str) -> None:
    packages = document.get("packages", [])
    files = document.get("files", [])
    relationships = document.get("relationships", [])
    counts: dict[str, int] = {}
    for relation in relationships:
        key = relation.get("relationshipType", "")
        counts[key] = counts.get(key, 0) + 1
    analyzed = sum(1 for package in packages if package.get("filesAnalyzed"))
    fallback = sum("provenance=static-fallback" in package.get("comment", "") for package in packages)
    relation_summary = ", ".join(f"{key}={counts[key]}" for key in sorted(counts))
    print(f"{prefix}: packages={len(packages)} files={len(files)} relationships={len(relationships)} "
          f"filesAnalyzedPackages={analyzed} staticFallbackPackages={fallback}")
    print(f"{prefix}: relationships {relation_summary}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Generate or verify the MCA SPDX 2.3 SBOM")
    parser.add_argument("--root", type=Path, default=REPO_ROOT)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--apk", type=Path, help="optional final APK to bind by SHA256/bytes/ABI")
    parser.add_argument("--verify", action="store_true", help="verify without rewriting the SBOM")
    args = parser.parse_args(argv)
    root = args.root.resolve()
    output = args.output.resolve()
    apk = args.apk.resolve() if args.apk else None

    if args.verify:
        if not output.is_file():
            print(f"SBOM verify: FAIL: file missing: {output}", file=sys.stderr)
            return 1
        try:
            document = json.loads(read_text(output))
        except (json.JSONDecodeError, OSError) as exc:
            print(f"SBOM verify: FAIL: cannot read JSON: {exc}", file=sys.stderr)
            return 1
        errors = verify_document(document, output, root, apk)
        if errors:
            print("SBOM verify: FAIL", file=sys.stderr)
            for error in errors:
                print(f"  - {error}", file=sys.stderr)
            return 1
        emit_summary(document, "SBOM verify: PASS")
        print(f"SBOM verify: path={output} schema={document.get('spdxVersion')} apk="
              f"{'bound' if apk or any(p.get('name') == 'mca-android-apk' for p in document.get('packages', [])) else 'absent'}")
        return 0

    try:
        document, warnings = build_sbom(root, apk)
        text = serialize_document(document)
        output.parent.mkdir(parents=True, exist_ok=True)
        with output.open("w", encoding="utf-8", newline="\n") as stream:
            stream.write(text)
    except SbomError as exc:
        print(f"SBOM generation: FAIL: {exc}", file=sys.stderr)
        return 1
    for warning in warnings:
        print(f"SBOM warning: {warning}", file=sys.stderr)
    raw = output.read_bytes()
    emit_summary(document, "SBOM generated")
    print(f"SBOM generated: path={output} schema={document.get('spdxVersion')} "
          f"bytes={len(raw)} sha256={sha256_file(output)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
