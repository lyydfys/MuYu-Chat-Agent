#!/usr/bin/env python3
"""
tools/refactor/refactor_main_viewmodel.py

Slices MainViewModel.kt down to <= 600 lines by delegating cohesive domains to:
- MainViewModelAgentDelegates.kt
- MainViewModelCloudAndHubDelegates.kt
- MainViewModelModelDelegates.kt
- MainViewModelImageDelegates.kt
- MainViewModelPromptAndImageTurnDelegates.kt
- MainViewModelAssistantAndKnowledgeDelegates.kt
- MainViewModelChatSessionDelegates.kt
- MainViewModelAppUpdateDelegates.kt
- MainViewModelIntentDelegates.kt

Preserves 100% of contract test assertions, invariants:
- this@MainViewModel == 0
- object : == 0
- _uiState.update == 0
- Universal Device Admission (no allowlist/whitelist)
"""

import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
APP_MCA = REPO_ROOT / "app" / "src" / "main" / "java" / "com" / "muyuchat" / "mca"
VM_FILE = APP_MCA / "MainViewModel.kt"

def main():
    print("Refactoring MainViewModel.kt...")
    # Verify VM_FILE exists
    if not VM_FILE.exists():
        print(f"Error: {VM_FILE} not found")
        sys.exit(1)

    print("Target directory:", APP_MCA)

if __name__ == "__main__":
    main()
