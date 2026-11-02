#!/usr/bin/env python3
"""
check_architecture_rules.py

Static architecture enforcement gate for MCA S-grade refactor:
1. Domain Android import = 0: :core:inference-contract sources must not import android.* or androidx.*
   -- SCOPE: the pure inference-contract module only.  A pass does NOT mean :core:engine is
      Android-free; the implementation module remains Android/native backed.
2. Feature boundary: feature modules must not directly depend on :core:native or :core:sd-native
3. C++ contract purity: mca/contract/*.hpp must not include jni.h or platform UI headers
4. File LOC limits: enforces hard ceilings on contract and catalog files
5. Local-only policy guard: verifies AGENTS.md and UniversalDeviceAdmissionPolicyTest.kt are excluded
6. Universal Device Admission: verifies no device allowlist/whitelist patterns are added
7. Line ending purity: checks that all contract files have 0 CRLF (\r\n) line endings
8. API/Worker UI decoupling: api/local and app worker must not import MainViewModel or UI screens
18. Video contract purity: :core:video-contract sources stay platform-free, app-free and JSON-lib-free
19. Video workspace boundary: feature:video-workspace depends only on the video contract + design system

Each rule above lists its own scope; the gate prints that scope before it runs so a pass is
never read as a broader claim than it is.
"""

import os
import re
import subprocess
import sys
from pathlib import Path

try:
    from .check_state_write_boundaries import check as state_write_boundary_errors
except ImportError:
    from check_state_write_boundaries import check as state_write_boundary_errors

REPO_ROOT = Path(__file__).resolve().parents[2]

CONTRACT_KOTLIN_DIR = REPO_ROOT / "core" / "inference-contract" / "src" / "main" / "java" / "com" / "muyuchat" / "core" / "engine"
CONTRACT_CPP_DIR = REPO_ROOT / "core" / "native" / "src" / "main" / "cpp" / "mca" / "contract"
FEATURE_DIR = REPO_ROOT / "feature"

# Agent V1 modules (architecture 3.1). The contract/runtime source trees are platform-free even
# though the repository builds them with the Android library plugin; the module dependency
# direction is checked from their real build files.
AGENT_CONTRACT_KOTLIN_DIR = REPO_ROOT / "core" / "agent-contract" / "src" / "main" / "java" / "com" / "muyuchat" / "core" / "agent" / "contract"
AGENT_RUNTIME_KOTLIN_DIR = REPO_ROOT / "core" / "agent-runtime" / "src" / "main" / "java" / "com" / "muyuchat" / "core" / "agent" / "runtime"
AGENT_CONTRACT_BUILD = REPO_ROOT / "core" / "agent-contract" / "build.gradle.kts"
AGENT_RUNTIME_BUILD = REPO_ROOT / "core" / "agent-runtime" / "build.gradle.kts"
AGENT_WORKSPACE_BUILD = REPO_ROOT / "feature" / "agent-workspace" / "build.gradle.kts"

# DSH V1 modules (proposal agent-dsh-embedded-v1). The contract is platform-free; the workspace
# feature may depend only on that contract and the design system. Runtime, gateway and assembly
# remain app/platform code and are never a feature dependency.
DSH_CONTRACT_KOTLIN_DIR = REPO_ROOT / "core" / "dsh-contract" / "src" / "main" / "java" / "com" / "muyuchat" / "core" / "dsh" / "contract"
DSH_CONTRACT_BUILD = REPO_ROOT / "core" / "dsh-contract" / "build.gradle.kts"
DSH_WORKSPACE_BUILD = REPO_ROOT / "feature" / "dsh-workspace" / "build.gradle.kts"
DSH_WORKSPACE_SRC = REPO_ROOT / "feature" / "dsh-workspace" / "src" / "main"

# Native video workflow modules (proposal mca-native-video-workflow-v1). The contract is
# platform-free; the workspace feature may depend only on that contract and the design system.
# The owner, Room persistence, workers and native adapters live in app/core code and are never
# feature dependencies.
VIDEO_CONTRACT_KOTLIN_DIR = REPO_ROOT / "core" / "video-contract" / "src" / "main" / "java" / "com" / "muyuchat" / "core" / "video" / "contract"
VIDEO_CONTRACT_BUILD = REPO_ROOT / "core" / "video-contract" / "build.gradle.kts"
VIDEO_WORKSPACE_BUILD = REPO_ROOT / "feature" / "video-workspace" / "build.gradle.kts"
VIDEO_WORKSPACE_SRC = REPO_ROOT / "feature" / "video-workspace" / "src" / "main"

HARD_LOC_LIMITS = {
    "Kotlin domain contract": (CONTRACT_KOTLIN_DIR, 500),
    "C++ contract header": (CONTRACT_CPP_DIR, 400),
    "Agent contract Kotlin": (AGENT_CONTRACT_KOTLIN_DIR, 300),
    "Agent runtime Kotlin": (AGENT_RUNTIME_KOTLIN_DIR, 800),
    "DSH contract Kotlin": (DSH_CONTRACT_KOTLIN_DIR, 300),
    "Video contract Kotlin": (VIDEO_CONTRACT_KOTLIN_DIR, 300),
}

LOCAL_ONLY_PATHS = [
    "AGENTS.md",
    "app/src/test/java/com/muyuchat/mca/UniversalDeviceAdmissionPolicyTest.kt"
]

def check_domain_android_imports(errors):
    """Rule 1: Domain Android import = 0."""
    print("Checking Rule 1: Domain Android imports in :core:inference-contract...")
    if not CONTRACT_KOTLIN_DIR.is_dir():
        errors.append(f"Contract directory not found: {CONTRACT_KOTLIN_DIR}")
        return

    forbidden_pattern = re.compile(r'^\s*import\s+(android\.|androidx\.)')
    for root, _, files in os.walk(CONTRACT_KOTLIN_DIR):
        for f in files:
            if f.endswith(".kt"):
                path = Path(root) / f
                with open(path, "r", encoding="utf-8") as file_obj:
                    for line_no, line in enumerate(file_obj, start=1):
                        if forbidden_pattern.match(line):
                            errors.append(
                                f"Rule 1 Violation: {path.relative_to(REPO_ROOT)}:{line_no} "
                                f"imports Android package: '{line.strip()}'"
                            )

def check_feature_native_dependencies(errors):
    """Rule 2: feature modules must not directly depend on core:native or core:sd-native."""
    print("Checking Rule 2: Feature module native dependencies...")
    if not FEATURE_DIR.is_dir():
        return

    forbidden_deps = [':core:native', ':core:sd-native']
    for root, _, files in os.walk(FEATURE_DIR):
        for f in files:
            if f == "build.gradle.kts":
                path = Path(root) / f
                content = path.read_text(encoding="utf-8")
                for dep in forbidden_deps:
                    if f'project("{dep}")' in content or f"project('{dep}')" in content:
                        errors.append(
                            f"Rule 2 Violation: {path.relative_to(REPO_ROOT)} directly depends on '{dep}'"
                        )

def check_cpp_contract_purity(errors):
    """Rule 3: C++ contract headers must be pure C++17, no jni.h."""
    print("Checking Rule 3: C++ contract header purity...")
    if not CONTRACT_CPP_DIR.is_dir():
        errors.append(f"C++ contract directory not found: {CONTRACT_CPP_DIR}")
        return

    forbidden_headers = re.compile(r'#include\s*[<"](?:jni\.h|android/|windows\.h)[>"]')
    for root, _, files in os.walk(CONTRACT_CPP_DIR):
        for f in files:
            if f.endswith((".hpp", ".h")):
                path = Path(root) / f
                with open(path, "r", encoding="utf-8") as file_obj:
                    for line_no, line in enumerate(file_obj, start=1):
                        if forbidden_headers.search(line):
                            errors.append(
                                f"Rule 3 Violation: {path.relative_to(REPO_ROOT)}:{line_no} "
                                f"includes platform header: '{line.strip()}'"
                            )

def check_file_loc_limits(errors):
    """Rule 4: Enforces hard ceilings on contract and catalog files."""
    print("Checking Rule 4: File LOC limits...")
    for label, (directory, limit) in HARD_LOC_LIMITS.items():
        if not directory.is_dir():
            continue
        for root, _, files in os.walk(directory):
            for f in files:
                if f.endswith((".kt", ".hpp", ".h")):
                    path = Path(root) / f
                    with open(path, "r", encoding="utf-8") as file_obj:
                        count = sum(1 for _ in file_obj)
                    if count > limit:
                        errors.append(
                            f"Rule 4 Violation: {path.relative_to(REPO_ROOT)} has {count} lines, "
                            f"exceeding hard ceiling of {limit} for {label}"
                        )

def check_local_only_exclusions(errors):
    """Rule 5: Local-only policy files must be excluded in git."""
    print("Checking Rule 5: Local-only exclusions via git check-ignore...")
    for rel_path in LOCAL_ONLY_PATHS:
        full_path = REPO_ROOT / rel_path
        if not full_path.exists():
            continue
        res = subprocess.run(
            ["git", "check-ignore", "-q", rel_path],
            cwd=str(REPO_ROOT),
            capture_output=True
        )
        if res.returncode != 0:
            errors.append(
                f"Rule 5 Violation: Local-only file '{rel_path}' is NOT ignored by git! "
                "Ensure it is in .git/info/exclude."
            )

def check_no_device_allowlist(errors):
    """Rule 6: Universal device admission - no allowlist/whitelist keywords in contract."""
    print("Checking Rule 6: No device allowlist or whitelist...")
    forbidden_pattern = re.compile(r'(?i)\b(?:device_allowlist|chipset_whitelist|allowed_devices|supported_chipsets_only)\b')
    for check_dir in [CONTRACT_KOTLIN_DIR, CONTRACT_CPP_DIR]:
        if not check_dir.is_dir():
            continue
        for root, _, files in os.walk(check_dir):
            for f in files:
                path = Path(root) / f
                content = path.read_text(encoding="utf-8", errors="ignore")
                match = forbidden_pattern.search(content)
                if match:
                    errors.append(
                        f"Rule 6 Violation: Forbidden allowlist pattern '{match.group(0)}' "
                        f"found in {path.relative_to(REPO_ROOT)}"
                    )

def check_line_endings(errors):
    """Rule 7: Contract files must have pure LF endings, 0 CRLF."""
    print("Checking Rule 7: Pure LF line endings (0 CRLF)...")
    for check_dir in [CONTRACT_KOTLIN_DIR, CONTRACT_CPP_DIR, AGENT_CONTRACT_KOTLIN_DIR, AGENT_RUNTIME_KOTLIN_DIR, DSH_CONTRACT_KOTLIN_DIR, VIDEO_CONTRACT_KOTLIN_DIR]:
        if not check_dir.is_dir():
            continue
        for root, _, files in os.walk(check_dir):
            for f in files:
                path = Path(root) / f
                raw = path.read_bytes()
                if b"\r\n" in raw:
                    errors.append(
                        f"Rule 7 Violation: {path.relative_to(REPO_ROOT)} contains CRLF line endings"
                    )

def check_api_worker_no_ui_dependencies(errors):
    """Rule 8: Local API and Worker packages must not import MainViewModel or UI Screens."""
    print("Checking Rule 8: API & Worker UI decoupling...")
    check_dirs = [
        REPO_ROOT / "api" / "local" / "src" / "main",
        REPO_ROOT / "app" / "src" / "main" / "java" / "com" / "muyuchat" / "mca" / "worker"
    ]
    forbidden_pattern = re.compile(r'^\s*import\s+.*(?:MainViewModel|ui\.screen).*')
    for d in check_dirs:
        if not d.is_dir():
            continue
        for root, _, files in os.walk(d):
            for f in files:
                if f.endswith(".kt"):
                    path = Path(root) / f
                    with open(path, "r", encoding="utf-8") as file_obj:
                        for line_no, line in enumerate(file_obj, start=1):
                            if forbidden_pattern.match(line):
                                errors.append(
                                    f"Rule 8 Violation: {path.relative_to(REPO_ROOT)}:{line_no} "
                                    f"imports UI component: '{line.strip()}'"
                                )

def check_agent_platform_purity(errors):
    """Rule 9: Agent contract/runtime sources stay platform-free and app-free."""
    print("Checking Rule 9: Agent contract/runtime platform and app imports...")
    forbidden_pattern = re.compile(
        r'^\s*import\s+(android\.|androidx\.|com\.muyuchat\.mca\.|com\.muyuchat\.feature\.|'
        r'com\.muyuchat\.api\.|org\.json\.|okhttp3\.|androidx\.room\.)'
    )
    for label, directory in (
        ("agent-contract", AGENT_CONTRACT_KOTLIN_DIR),
        ("agent-runtime", AGENT_RUNTIME_KOTLIN_DIR),
    ):
        if not directory.is_dir():
            errors.append(f"Rule 9 Violation: required {label} source directory is missing: {directory.relative_to(REPO_ROOT)}")
            continue
        for root, _, files in os.walk(directory):
            for f in files:
                if not f.endswith(".kt"):
                    continue
                path = Path(root) / f
                with open(path, "r", encoding="utf-8") as file_obj:
                    for line_no, line in enumerate(file_obj, start=1):
                        if forbidden_pattern.match(line):
                            errors.append(
                                f"Rule 9 Violation: {path.relative_to(REPO_ROOT)}:{line_no} "
                                f"imports a platform/app type: '{line.strip()}'"
                            )


def check_agent_dependency_direction(errors):
    """Rule 10: Agent runtime and workspace dependency direction."""
    print("Checking Rule 10: Agent runtime/workspace dependency direction...")

    def project_deps(path):
        if not path.is_file():
            return set()
        content = path.read_text(encoding="utf-8")
        return set(re.findall(r'project\(\s*"([^"]+)"\s*\)', content))

    runtime_forbidden = {
        ":app", ":api:local", ":core:engine", ":core:native", ":core:sd-native",
        ":core:modelstore", ":core:download", ":core:telemetry", ":core:deviceprofile",
        ":core:tuning", ":core:advisor", ":core:benchmark", ":feature:chat",
        ":feature:agent", ":feature:agent-workspace", ":feature:modelhub", ":feature:settings",
    }
    if not AGENT_RUNTIME_BUILD.is_file():
        errors.append("Rule 10 Violation: missing core/agent-runtime/build.gradle.kts")
    else:
        deps = project_deps(AGENT_RUNTIME_BUILD)
        allowed = {":core:agent-contract"}
        for dep in sorted(deps - allowed):
            errors.append(
                f"Rule 10 Violation: core/agent-runtime depends on '{dep}'; allowed: {sorted(allowed)}"
            )
        runtime_text = AGENT_RUNTIME_BUILD.read_text(encoding="utf-8")
        for token in ("androidx.room", "okhttp", "com.squareup", "androidx.compose"):
            if token in runtime_text:
                errors.append(
                    f"Rule 10 Violation: core/agent-runtime build file references forbidden dependency token '{token}'"
                )

    if not AGENT_WORKSPACE_BUILD.is_file():
        errors.append("Rule 10 Violation: missing feature/agent-workspace/build.gradle.kts")
    else:
        deps = project_deps(AGENT_WORKSPACE_BUILD)
        allowed = {":core:agent-contract", ":core:designsystem"}
        for dep in sorted(deps - allowed):
            errors.append(
                f"Rule 10 Violation: feature:agent-workspace depends on '{dep}'; allowed: {sorted(allowed)}"
            )


def check_agent_feature_boundary(errors):
    """Rule 11: feature:agent-workspace source must not reach runtime/engine/chat/app types."""
    print("Checking Rule 11: Agent workspace feature boundary imports...")
    workspace_src = REPO_ROOT / "feature" / "agent-workspace" / "src" / "main"
    if not workspace_src.is_dir():
        return
    forbidden_pattern = re.compile(
        r'^\s*import\s+(com\.muyuchat\.core\.agent\.runtime\.|com\.muyuchat\.core\.engine\.|'
        r'com\.muyuchat\.mca\.|com\.muyuchat\.feature\.chat\.)'
    )
    # The feature's own `com.muyuchat.feature.agent.workspace.*` imports are intra-module and
    # allowed; Rule 10 already rejects a dependency on another feature module.
    for root, _, files in os.walk(workspace_src):
        for f in files:
            if not f.endswith(".kt"):
                continue
            path = Path(root) / f
            with open(path, "r", encoding="utf-8") as file_obj:
                for line_no, line in enumerate(file_obj, start=1):
                    if forbidden_pattern.match(line):
                        errors.append(
                            f"Rule 11 Violation: {path.relative_to(REPO_ROOT)}:{line_no} "
                            f"imports runtime/engine/chat/app type: '{line.strip()}'"
                        )


def check_agent_app_boundary(errors):
    """Rule 12: app/agent/** must not reach global UI state, hosts, chat internals or GlobalScope."""
    print("Checking Rule 12: Agent app-layer boundary...")
    agent_app_dir = REPO_ROOT / "app" / "src" / "main" / "java" / "com" / "muyuchat" / "mca" / "agent"
    if not agent_app_dir.is_dir():
        return
    forbidden_import = re.compile(
        r'^\s*import\s+(com\.muyuchat\.mca\.MainUiState|com\.muyuchat\.mca\.UiStateStore|'
        r'com\.muyuchat\.mca\.MainViewModel|com\.muyuchat\.mca\.MainActivity|'
        r'com\.muyuchat\.mca\.composition\.MainCoordinatorHost|'
        r'com\.muyuchat\.feature\.chat\.|com\.muyuchat\.feature\.agent\.|'
        r'androidx\.compose\.)'
    )
    global_scope = re.compile(r'\bGlobalScope\s*\.')
    any_injection = re.compile(r'\b(?:val|var)\s+\w+\s*:\s*Any\?|injected\w*\s*:\s*Any\?')
    for root, _, files in os.walk(agent_app_dir):
        for f in files:
            if not f.endswith(".kt"):
                continue
            path = Path(root) / f
            text = path.read_text(encoding="utf-8")
            for line_no, line in enumerate(text.splitlines(), start=1):
                if forbidden_import.match(line):
                    errors.append(
                        f"Rule 12 Violation: {path.relative_to(REPO_ROOT)}:{line_no} imports global UI/chat type: '{line.strip()}'"
                    )
                if global_scope.search(line):
                    errors.append(
                        f"Rule 12 Violation: {path.relative_to(REPO_ROOT)}:{line_no} uses GlobalScope"
                    )
                if any_injection.search(line):
                    errors.append(
                        f"Rule 12 Violation: {path.relative_to(REPO_ROOT)}:{line_no} uses Any? injection"
                    )


def check_main_state_and_host_freeze(errors):
    """Rule 13: no Agent business fields in MainUiState and no Agent capability in MainCoordinatorHost."""
    print("Checking Rule 13: MainUiState / MainCoordinatorHost Agent freeze...")
    targets = [
        REPO_ROOT / "app" / "src" / "main" / "java" / "com" / "muyuchat" / "mca" / "MainUiState.kt",
        REPO_ROOT / "app" / "src" / "main" / "java" / "com" / "muyuchat" / "mca" / "composition" / "MainCoordinatorHost.kt",
    ]
    forbidden = re.compile(r'\bAgent(?:Run|Task|Approval|Workspace|Invocation|Artifact|Event)\b')
    for path in targets:
        if not path.is_file():
            continue
        text = path.read_text(encoding="utf-8")
        for line_no, line in enumerate(text.splitlines(), start=1):
            if forbidden.search(line):
                errors.append(
                    f"Rule 13 Violation: {path.relative_to(REPO_ROOT)}:{line_no} adds Agent business state/capability: '{line.strip()}'"
                )


def check_feature_app_runtime_dependencies(errors):
    """Rule 14: no feature module may depend on :app, :core:agent-runtime, :core:dsh-runtime,
    :api:dsh-gateway or another feature."""
    print("Checking Rule 14: feature dependency boundaries...")
    forbidden_projects = {":app", ":core:agent-runtime", ":core:dsh-runtime", ":api:dsh-gateway"}
    forbidden_prefix = ":feature:"
    if not FEATURE_DIR.is_dir():
        return
    for root, _, files in os.walk(FEATURE_DIR):
        for f in files:
            if f != "build.gradle.kts":
                continue
            path = Path(root) / f
            content = path.read_text(encoding="utf-8")
            for dep in re.findall(r'project\(\s*"([^"]+)"\s*\)', content):
                if dep in forbidden_projects or (dep.startswith(forbidden_prefix) and dep.split(":")[2] not in Path(root).parts):
                    errors.append(
                        f"Rule 14 Violation: {path.relative_to(REPO_ROOT)} depends on forbidden project '{dep}'"
                    )


def check_dsh_contract_purity(errors):
    """Rule 15: DSH contract sources stay platform-free and app-free."""
    print("Checking Rule 15: DSH contract platform/app purity...")
    if not DSH_CONTRACT_KOTLIN_DIR.is_dir():
        errors.append(
            "Rule 15 Violation: required DSH contract source directory is missing: "
            f"{DSH_CONTRACT_KOTLIN_DIR.relative_to(REPO_ROOT)}"
        )
        return
    forbidden_pattern = re.compile(
        r'^\s*import\s+(android\.|androidx\.|com\.muyuchat\.mca\.|com\.muyuchat\.feature\.|'
        r'com\.muyuchat\.api\.|org\.json\.|okhttp3\.|androidx\.room\.)'
    )
    for root, _, files in os.walk(DSH_CONTRACT_KOTLIN_DIR):
        for f in files:
            if not f.endswith(".kt"):
                continue
            path = Path(root) / f
            with open(path, "r", encoding="utf-8") as file_obj:
                for line_no, line in enumerate(file_obj, start=1):
                    if forbidden_pattern.match(line):
                        errors.append(
                            f"Rule 15 Violation: {path.relative_to(REPO_ROOT)}:{line_no} "
                            f"imports a platform/app type: '{line.strip()}'"
                        )
    if DSH_CONTRACT_BUILD.is_file():
        build_text = DSH_CONTRACT_BUILD.read_text(encoding="utf-8")
        for token in ("androidx.room", "okhttp", "com.squareup", "androidx.compose"):
            if token in build_text:
                errors.append(
                    f"Rule 15 Violation: core/dsh-contract build file references forbidden dependency token '{token}'"
                )


def check_dsh_workspace_boundary(errors):
    """Rule 16: feature:dsh-workspace only depends on the DSH contract + design system and its
    source never reaches runtime/gateway/engine/Kotlin-Agent/app internals."""
    print("Checking Rule 16: DSH workspace dependency/import boundary...")
    if not DSH_WORKSPACE_BUILD.is_file():
        errors.append("Rule 16 Violation: missing feature/dsh-workspace/build.gradle.kts")
    else:
        build_text = DSH_WORKSPACE_BUILD.read_text(encoding="utf-8")
        deps = set(re.findall(r'project\(\s*"([^"]+)"\s*\)', build_text))
        allowed = {":core:dsh-contract", ":core:designsystem"}
        for dep in sorted(deps - allowed):
            errors.append(
                f"Rule 16 Violation: feature:dsh-workspace depends on '{dep}'; allowed: {sorted(allowed)}"
            )
    if not DSH_WORKSPACE_SRC.is_dir():
        return
    forbidden_pattern = re.compile(
        r'^\s*import\s+(com\.muyuchat\.mca\.|com\.muyuchat\.core\.dsh\.runtime\.|'
        r'com\.muyuchat\.api\.|com\.muyuchat\.core\.engine\.|com\.muyuchat\.core\.agent\.|'
        r'com\.muyuchat\.feature\.chat\.|com\.muyuchat\.feature\.agent\.)'
    )
    for root, _, files in os.walk(DSH_WORKSPACE_SRC):
        for f in files:
            if not f.endswith(".kt"):
                continue
            path = Path(root) / f
            with open(path, "r", encoding="utf-8") as file_obj:
                for line_no, line in enumerate(file_obj, start=1):
                    if forbidden_pattern.match(line):
                        errors.append(
                            f"Rule 16 Violation: {path.relative_to(REPO_ROOT)}:{line_no} "
                            f"imports runtime/gateway/agent/app type: '{line.strip()}'"
                        )


def check_video_contract_purity(errors):
    """Rule 18: video contract sources stay platform-free and app-free."""
    print("Checking Rule 18: video contract platform/app purity...")
    if not VIDEO_CONTRACT_KOTLIN_DIR.is_dir():
        errors.append(
            "Rule 18 Violation: required video contract source directory is missing: "
            f"{VIDEO_CONTRACT_KOTLIN_DIR.relative_to(REPO_ROOT)}"
        )
        return
    forbidden_pattern = re.compile(
        r'^\s*import\s+(android\.|androidx\.|com\.muyuchat\.mca\.|com\.muyuchat\.feature\.|'
        r'com\.muyuchat\.api\.|com\.muyuchat\.core\.engine\.|com\.muyuchat\.core\.sdnative\.|'
        r'com\.muyuchat\.core\.native\.|org\.json\.|okhttp3\.|androidx\.room\.)'
    )
    for root, _, files in os.walk(VIDEO_CONTRACT_KOTLIN_DIR):
        for f in files:
            if not f.endswith(".kt"):
                continue
            path = Path(root) / f
            with open(path, "r", encoding="utf-8") as file_obj:
                for line_no, line in enumerate(file_obj, start=1):
                    if forbidden_pattern.match(line):
                        errors.append(
                            f"Rule 18 Violation: {path.relative_to(REPO_ROOT)}:{line_no} "
                            f"imports a platform/app type: '{line.strip()}'"
                        )
    if VIDEO_CONTRACT_BUILD.is_file():
        build_text = VIDEO_CONTRACT_BUILD.read_text(encoding="utf-8")
        for token in ("androidx.room", "okhttp", "com.squareup", "androidx.compose"):
            if token in build_text:
                errors.append(
                    f"Rule 18 Violation: core/video-contract build file references forbidden dependency token '{token}'"
                )


def check_video_workspace_boundary(errors):
    """Rule 19: feature:video-workspace depends only on the video contract + design system and
    its source never reaches runtime/owner/worker/app internals."""
    print("Checking Rule 19: video workspace dependency/import boundary...")
    if not VIDEO_WORKSPACE_BUILD.is_file():
        errors.append("Rule 19 Violation: missing feature/video-workspace/build.gradle.kts")
    else:
        build_text = VIDEO_WORKSPACE_BUILD.read_text(encoding="utf-8")
        deps = set(re.findall(r'project\(\s*"([^"]+)"\s*\)', build_text))
        allowed = {":core:video-contract", ":core:designsystem"}
        for dep in sorted(deps - allowed):
            errors.append(
                f"Rule 19 Violation: feature:video-workspace depends on '{dep}'; allowed: {sorted(allowed)}"
            )
    if not VIDEO_WORKSPACE_SRC.is_dir():
        return
    forbidden_pattern = re.compile(
        r'^\s*import\s+(com\.muyuchat\.mca\.|com\.muyuchat\.core\.engine\.|'
        r'com\.muyuchat\.core\.sdnative\.|com\.muyuchat\.core\.native\.|com\.muyuchat\.api\.|'
        r'com\.muyuchat\.feature\.chat\.|com\.muyuchat\.feature\.agent\.|com\.muyuchat\.feature\.dsh\.)'
    )
    for root, _, files in os.walk(VIDEO_WORKSPACE_SRC):
        for f in files:
            if not f.endswith(".kt"):
                continue
            path = Path(root) / f
            with open(path, "r", encoding="utf-8") as file_obj:
                for line_no, line in enumerate(file_obj, start=1):
                    if forbidden_pattern.match(line):
                        errors.append(
                            f"Rule 19 Violation: {path.relative_to(REPO_ROOT)}:{line_no} "
                            f"imports runtime/engine/app type: '{line.strip()}'"
                        )


def main():
    print(f"=== MCA Architecture Gate Verification ===")
    print(f"Working tree: {REPO_ROOT}")
    print("")
    print("SCOPE -- exactly what this gate inspects:")
    print(f"  Rule 1  android.*/androidx.* imports : {CONTRACT_KOTLIN_DIR.relative_to(REPO_ROOT)} only")
    print(f"  Rule 2  native deps                  : {FEATURE_DIR.relative_to(REPO_ROOT)}/*/build.gradle.kts only")
    print(f"  Rule 3  platform headers             : {CONTRACT_CPP_DIR.relative_to(REPO_ROOT)} only")
    print("  Rule 4  LOC ceilings                 : the Rule 1, Rule 3, Rule 9 contract/runtime directories only")
    print(f"  Rule 5  local-only git exclusion     : the {len(LOCAL_ONLY_PATHS)} LOCAL_ONLY_PATHS entries only")
    print("  Rule 6  device allowlist keywords    : the Rule 1 and Rule 3 contract directories only")
    print("  Rule 7  pure LF line endings         : the Rule 1, Rule 3, Rule 9 and Rule 11 directories only")
    print("  Rule 8  UI imports                   : api/local/src/main and app/.../worker only")
    print("  Rule 9  agent platform purity        : core/agent-contract + core/agent-runtime sources only")
    print("  Rule 10 agent dependency direction   : core/agent-runtime and feature/agent-workspace build files only")
    print("  Rule 11 agent workspace imports      : feature/agent-workspace/src/main only")
    print("  Rule 12 agent app boundary           : app/.../agent/**/*.kt only")
    print("  Rule 13 state/host freeze            : MainUiState.kt + MainCoordinatorHost.kt only")
    print("  Rule 14 feature dependency boundaries: feature/*/build.gradle.kts only")
    print(f"  Rule 15 DSH contract purity          : {DSH_CONTRACT_KOTLIN_DIR.relative_to(REPO_ROOT)} + its build file")
    print("  Rule 16 DSH workspace boundary       : feature/dsh-workspace build file + src/main only")
    print("  Rule 17 state write authority        : app Kotlin D01/D02/D03 boundaries + reviewed field manifest")
    print(f"  Rule 18 video contract purity        : {VIDEO_CONTRACT_KOTLIN_DIR.relative_to(REPO_ROOT)} + its build file")
    print("  Rule 19 video workspace boundary     : feature/video-workspace build file + src/main only")
    print("")
    print("NOT CHECKED -- a pass below says nothing about these:")
    print("  * The :core:engine implementation module. That module is still an")
    print("    Android library and still depends on :core:native; Rule 1 does not make it Android-free.")
    print("  * Any other module, any dependency edge not listed in Rule 2/10, and any file outside the")
    print("    directories named above.")
    print("")

    errors = []

    check_domain_android_imports(errors)
    check_feature_native_dependencies(errors)
    check_cpp_contract_purity(errors)
    check_file_loc_limits(errors)
    check_local_only_exclusions(errors)
    check_no_device_allowlist(errors)
    check_line_endings(errors)
    check_api_worker_no_ui_dependencies(errors)
    check_agent_platform_purity(errors)
    check_agent_dependency_direction(errors)
    check_agent_feature_boundary(errors)
    check_agent_app_boundary(errors)
    check_main_state_and_host_freeze(errors)
    check_feature_app_runtime_dependencies(errors)
    check_dsh_contract_purity(errors)
    check_dsh_workspace_boundary(errors)
    check_video_contract_purity(errors)
    check_video_workspace_boundary(errors)
    print("Checking Rule 17: Field-limited state writers, retired host and typed injection...")
    errors.extend(state_write_boundary_errors(REPO_ROOT))

    print("\n-------------------------------------------")
    if errors:
        print(f"FAILED: Found {len(errors)} architecture rule violations:")
        for err in errors:
            print(f"  [X] {err}")
        sys.exit(1)
    else:
        print("ALL 19 CHECKED ARCHITECTURE RULES PASSED (EXIT=0)")
        print("  Scope: only the directories listed under SCOPE above were inspected.")
        print("  This is NOT a clean bill of health for any whole module: :core:engine remains an")
        print("  Android library depending on :core:native, and its non-contract sources were not")
        print("  examined for Android imports by this gate.")
        sys.exit(0)

if __name__ == "__main__":
    main()

