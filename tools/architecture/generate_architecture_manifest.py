#!/usr/bin/env python3
"""
generate_architecture_manifest.py

Scans the MCA repository to produce a machine-readable architecture manifest
detailing modules, source files, line counts, owners, associated tests,
and legacy migration status.
"""

import os
import sys
import json
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

MODULE_MAP = {
    "app": "app",
    "core:engine": "core/engine",
    "core:agent-contract": "core/agent-contract",
    "core:agent-runtime": "core/agent-runtime",
    "core:native": "core/native",
    "core:sd-native": "core/sd-native",
    "core:modelstore": "core/modelstore",
    "core:download": "core/download",
    "core:telemetry": "core/telemetry",
    "core:deviceprofile": "core/deviceprofile",
    "core:tuning": "core/tuning",
    "core:advisor": "core/advisor",
    "core:benchmark": "core/benchmark",
    "core:designsystem": "core/designsystem",
    "api:local": "api/local",
    "feature:chat": "feature/chat",
    "feature:agent": "feature/agent",
    "feature:agent-workspace": "feature/agent-workspace",
    "feature:modelhub": "feature/modelhub",
    "feature:settings": "feature/settings",
}

OWNER_RULES = [
    ("contract", "Contract/Lead Architecture"),
    ("nativebridge", "Native JNI Boundary"),
    ("native", "Native Inference Engine"),
    ("sdnative", "Native Diffusion Engine"),
    ("coordinator", "Domain Feature Coordinator"),
    ("operations", "Domain Operations UseCase"),
    ("assembly", "Composition Root Assembly"),
    ("viewmodel", "Presentation ViewModel"),
    ("ui", "UI Presentation"),
    ("port", "Port Interface"),
    ("adapter", "Platform / Port Adapter"),
    ("store", "Persistence / Storage"),
    ("download", "Download Manager"),
    ("tuning", "Tuning / Benchmark"),
]

def get_owner_for_path(path_str: str) -> str:
    path_lower = path_str.lower()
    for keyword, owner in OWNER_RULES:
        if keyword in path_lower:
            return owner
    return "General Component"

def count_file_lines(path: Path) -> int:
    try:
        with open(path, "r", encoding="utf-8", errors="ignore") as f:
            return sum(1 for _ in f)
    except Exception:
        return 0

def find_tests_for_file(file_name: str, test_files: list) -> list:
    stem = Path(file_name).stem
    matches = []
    for tf in test_files:
        if stem in tf.name:
            matches.append(str(tf.relative_to(REPO_ROOT)).replace("\\", "/"))
    return matches

def main():
    print(f"Generating architecture manifest for MCA at {REPO_ROOT}...")

    manifest = {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "version": "1.0.0",
        "project": "MCA Enterprise",
        "baseline_commit": "d90dd61b",
        "modules": []
    }

    all_test_files = list(REPO_ROOT.glob("**/src/test/**/*.*"))

    for module_name, rel_dir in MODULE_MAP.items():
        module_path = REPO_ROOT / rel_dir
        if not module_path.is_dir():
            continue

        src_dir = module_path / "src" / "main"
        if not src_dir.is_dir():
            src_dir = module_path

        files_info = []
        total_loc = 0

        for root, _, files in os.walk(src_dir):
            for file in files:
                if file.endswith((".kt", ".java", ".cpp", ".hpp", ".h", ".c")):
                    full_p = Path(root) / file
                    rel_p = str(full_p.relative_to(REPO_ROOT)).replace("\\", "/")
                    loc = count_file_lines(full_p)
                    total_loc += loc
                    owner = get_owner_for_path(rel_p)
                    matched_tests = find_tests_for_file(file, all_test_files)

                    legacy_status = "LEGACY_ACTIVE"
                    if "contract" in rel_p.lower():
                        legacy_status = "S_GRADE_CONTRACT"
                    elif "coordinator" in rel_p.lower() or "assembly" in rel_p.lower():
                        legacy_status = "MODERNIZED_ACTIVE"

                    files_info.append({
                        "file_path": rel_p,
                        "file_name": file,
                        "loc": loc,
                        "owner": owner,
                        "status": legacy_status,
                        "associated_tests": matched_tests[:5]
                    })

        # Sort files by LOC descending
        files_info.sort(key=lambda x: x["loc"], reverse=True)

        manifest["modules"].append({
            "module_name": module_name,
            "path": rel_dir,
            "total_files": len(files_info),
            "total_loc": total_loc,
            "key_files": files_info[:15],
            "all_files_count": len(files_info)
        })

    out_file = REPO_ROOT / "architecture" / "architecture-manifest.json"
    out_file.parent.mkdir(parents=True, exist_ok=True)
    with open(out_file, "w", encoding="utf-8", newline="\n") as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)
        f.write("\n")

    print(f"Manifest written to {out_file} (Modules: {len(manifest['modules'])})")

if __name__ == "__main__":
    main()
