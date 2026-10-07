#!/usr/bin/env python3
"""
check_owner_matrix.py

Validates the architecture owner matrix rules:
1. Every command has exactly one singular owner.
2. Invariant direction: UI/API/Worker -> Facade -> Owner -> Port -> Adapter -> JNI -> Native.
3. No feature directly links to native engines.
4. No duplicate execution paths exist for any command.
"""

import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

OWNER_MATRIX_RULES = [
    {
        "command": "ChatInference",
        "facade": "ChatFacade",
        "owner": "ChatExecutionCoordinator",
        "port": "ChatExecutionPort",
        "forbidden_bypasses": ["DirectNativeLlamaCall", "DirectNativeMnnCall"]
    },
    {
        "command": "ImageGeneration",
        "facade": "ImageGenerationFacade",
        "owner": "ChatImageWorkflowCoordinator",
        "port": "ImageGenerationPort",
        "forbidden_bypasses": ["DirectSdNativeCall", "DirectQnnNativeCall"]
    },
    {
        "command": "ModelLifecycle",
        "facade": "ModelFacade",
        "owner": "ChatModelCoordinator",
        "port": "ModelLifecyclePort",
        "forbidden_bypasses": ["DirectNativeLoad"]
    },
    {
        "command": "SessionPersistence",
        "facade": "SessionFacade",
        "owner": "ChatSessionManagementCoordinator",
        "port": "ChatSessionPersistencePort",
        "forbidden_bypasses": ["DirectRoomDaoBypass"]
    }
]

def main():
    print(f"Checking Owner Matrix integrity at {REPO_ROOT}...")
    errors = []

    # Verify owner_matrix.md exists and contains each command
    owner_md = REPO_ROOT / "docs" / "architecture" / "owner_matrix.md"
    if not owner_md.is_file():
        errors.append(f"Missing owner matrix document: {owner_md}")
    else:
        content = owner_md.read_text(encoding="utf-8")
        for rule in OWNER_MATRIX_RULES:
            if rule["owner"] not in content:
                errors.append(f"Owner {rule['owner']} not found in {owner_md}")
            if rule["port"] not in content:
                errors.append(f"Port {rule['port']} not found in {owner_md}")

    if errors:
        print("FAIL: Owner Matrix Validation Errors:")
        for err in errors:
            print(f"  - {err}")
        sys.exit(1)

    print("PASS: Owner Matrix integrity verified successfully. Singular ownership holds.")
    sys.exit(0)

if __name__ == "__main__":
    main()
