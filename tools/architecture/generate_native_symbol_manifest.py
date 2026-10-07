#!/usr/bin/env python3
"""
generate_native_symbol_manifest.py

Scans MCA C++ source files to extract all JNIEXPORT function declarations,
mapping them to target DSO, ABI support, entrypoint owners, and migration status.
"""

import os
import re
import json
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

NATIVE_SOURCES = [
    (REPO_ROOT / "core" / "native" / "src" / "main" / "cpp", "libmca_native.so"),
    (REPO_ROOT / "core" / "sd-native" / "src" / "main" / "cpp", "libmca_sd_native.so")
]

DSO_MAPPING = {
    "NativeLlamaBridge": "libmca_native.so",
    "NativeMnnBridge": "libmca_mnn_native.so",
    "NativeMnnDiffusionBridge": "libmca_mnn_native.so",
    "NativeQnnBridge": "libmca_qnn_native.so",
    "NativeStableDiffusionBridge": "libmca_sd_native.so"
}

OWNER_MAPPING = {
    "NativeLlamaBridge": "LlamaNativeEngineGateway",
    "NativeMnnBridge": "MnnNativeEngineGateway",
    "NativeMnnDiffusionBridge": "MnnDiffusionGateway",
    "NativeQnnBridge": "QnnNativeBridgeGateway",
    "NativeStableDiffusionBridge": "StableDiffusionNativeGateway"
}

JNI_REGEX = re.compile(r'JNIEXPORT\s+([A-Za-z0-9_]+)\s+JNICALL\s*\n?\s*(Java_[A-Za-z0-9_]+)\s*\(([^)]*)\)')

def main():
    print(f"Scanning JNI symbols in {REPO_ROOT}...")

    symbols = []
    seen = set()

    for src_dir, default_dso in NATIVE_SOURCES:
        if not src_dir.is_dir():
            continue
        for root, _, files in os.walk(src_dir):
            for file in files:
                if file.endswith((".cpp", ".c", ".cc")):
                    file_path = Path(root) / file
                    try:
                        content = file_path.read_text(encoding="utf-8", errors="ignore")
                    except Exception:
                        continue

                    for match in JNI_REGEX.finditer(content):
                        ret_type = match.group(1)
                        symbol_name = match.group(2)
                        params = match.group(3).strip()

                        if symbol_name in seen:
                            continue
                        seen.add(symbol_name)

                        # Extract class and method name: Java_com_muyuchat_core_nativebridge_NativeMnnBridge_loadModel
                        parts = symbol_name.split("_")
                        method_name = parts[-1]
                        class_name = parts[-2] if len(parts) >= 2 else "Unknown"

                        dso = DSO_MAPPING.get(class_name, default_dso)
                        owner = OWNER_MAPPING.get(class_name, "GenericNativeGateway")

                        symbols.append({
                            "symbol": symbol_name,
                            "method_name": method_name,
                            "java_class": class_name,
                            "return_type": ret_type,
                            "parameters": [p.strip() for p in params.split(",") if p.strip()],
                            "dso": dso,
                            "abis": ["arm64-v8a", "x86_64"],
                            "entry_owner": owner,
                            "source_file": str(file_path.relative_to(REPO_ROOT)).replace("\\", "/"),
                            "migration_status": "MIGRATION_PENDING_A2",
                            "legacy_path": "ACTIVE"
                        })

    symbols.sort(key=lambda s: s["symbol"])

    manifest = {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "version": "1.0.0",
        "generated_at": "2026-10-08T01:00:00Z",
        "total_symbols": len(symbols),
        "dso_summary": {
            "libmca_native.so": sum(1 for s in symbols if s["dso"] == "libmca_native.so"),
            "libmca_mnn_native.so": sum(1 for s in symbols if s["dso"] == "libmca_mnn_native.so"),
            "libmca_qnn_native.so": sum(1 for s in symbols if s["dso"] == "libmca_qnn_native.so"),
            "libmca_sd_native.so": sum(1 for s in symbols if s["dso"] == "libmca_sd_native.so")
        },
        "symbols": symbols
    }

    out_file = REPO_ROOT / "architecture" / "native-symbol-manifest.json"
    out_file.parent.mkdir(parents=True, exist_ok=True)
    with open(out_file, "w", encoding="utf-8", newline="\n") as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)
        f.write("\n")

    print(f"Native symbol manifest written to {out_file} (Total JNI symbols: {len(symbols)})")

if __name__ == "__main__":
    main()
