#!/usr/bin/env python3
"""
run_fault_injection_matrix.py

Publishes the 22-scenario fault-injection specification. This script does not
inject faults or verify production behavior.

Each scenario validates:
1. Injection point
2. Expected StableErrorCode mapping
3. Expected Terminal RequestState
4. Resource cleanup / RAII assertion
5. Observable diagnostic evidence
6. Evidence status (scenarios remain unverified until executable tests exist)
"""

import json
import os
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
REPORT_PATH = REPO_ROOT / "tools" / "fault-injection" / "fault_matrix_report.json"

FAULT_SCENARIOS = [
    {
        "id": "FI-01",
        "name": "Bundle manifest corrupt",
        "injection_target": "ModelStore / native manifest parser",
        "expected_error": "VAL_CORRUPT_PACKAGE",
        "expected_terminal": "Failed",
        "cleanup_assertion": "No active engine created; temporary staging directory deleted",
        "evidence_requirement": "ErrorEnvelope with VAL_CORRUPT_PACKAGE, rollback journal entry",
        "status": "UNVERIFIED_NATIVE"
    },
    {
        "id": "FI-02",
        "name": "Missing required weight / DSO binary",
        "injection_target": "Bundle verification / runtime loader",
        "expected_error": "MDL_MISSING_REQUIRED_BINARY",
        "expected_terminal": "Failed",
        "cleanup_assertion": "Atomic rollback; partial files never committed to model store",
        "evidence_requirement": "ErrorEnvelope with redacted path; rollback journal entry",
        "status": "UNVERIFIED_NATIVE"
    },
    {
        "id": "FI-03",
        "name": "Native dlopen failed",
        "injection_target": "Native runtime loader (mca_dynamic_library)",
        "expected_error": "NAT_DSO_LOAD_FAILED",
        "expected_terminal": "Failed",
        "cleanup_assertion": "Handle closed, zero resource leakage, no native execution attempted",
        "evidence_requirement": "ErrorEnvelope with loader message and OS dlerror string",
        "status": "UNVERIFIED_NATIVE"
    },
    {
        "id": "FI-04",
        "name": "dlsym / ABI version mismatch",
        "injection_target": "Runtime loader symbol resolver",
        "expected_error": "NAT_SYMBOL_NOT_FOUND",
        "expected_terminal": "Failed",
        "cleanup_assertion": "Function pointers stay null, execution safely prevented",
        "evidence_requirement": "ABI manifest mismatch logged, stable NAT_SYMBOL_NOT_FOUND error",
        "status": "UNVERIFIED_NATIVE"
    },
    {
        "id": "FI-05",
        "name": "Graph build / finalize failure",
        "injection_target": "Native graph compiler / session builder",
        "expected_error": "CAP_BACKEND_UNAVAILABLE",
        "expected_terminal": "Failed",
        "cleanup_assertion": "Allocated tensors and builder context freed via RAII",
        "evidence_requirement": "State snapshot shows Preparing -> Failed; previous session intact",
        "status": "UNVERIFIED_NATIVE"
    },
    {
        "id": "FI-06",
        "name": "Tensor shape / quantization format error",
        "injection_target": "Input contract validation",
        "expected_error": "VAL_INVALID_INPUT",
        "expected_terminal": "Failed",
        "cleanup_assertion": "Pre-execution gate short-circuits; native execute is not invoked",
        "evidence_requirement": "Error phase Validate; out-of-bounds parameters caught cleanly",
        "status": "UNVERIFIED_NATIVE"
    },
    {
        "id": "FI-07",
        "name": "Callback queue full / backpressure",
        "injection_target": "Callback router buffer",
        "expected_error": "NONE",
        "expected_terminal": "Succeeded",
        "cleanup_assertion": "Progress milestones coalesced; terminal events delivered exactly-once",
        "evidence_requirement": "Queue depth bounded; 0 terminal event drops",
        "status": "UNVERIFIED_NATIVE"
    },
    {
        "id": "FI-08",
        "name": "Delayed callback arrival (stale sequence/epoch)",
        "injection_target": "CallbackRouter / JNI gateway",
        "expected_error": "REQ_STALE_CALLBACK",
        "expected_terminal": "Succeeded",
        "cleanup_assertion": "Stale event dropped silently; active terminal state unmodified",
        "evidence_requirement": "droppedStaleCount incremented; log contains dropped event identity",
        "status": "UNVERIFIED_NATIVE"
    },
    {
        "id": "FI-09",
        "name": "Cancel requested before execution start",
        "injection_target": "ChatExecutionFacade / ImageGenerationFacade",
        "expected_error": "CNC_USER_CANCELLED",
        "expected_terminal": "Cancelled",
        "cleanup_assertion": "Native engine not started; immediate terminal transition",
        "evidence_requirement": "Request journal records CancelRequested -> Cancelled without engine alloc",
        "status": "UNVERIFIED_NATIVE"
    },
    {
        "id": "FI-10",
        "name": "Cancel requested during active inference run",
        "injection_target": "Native engine cooperatively checking cancel flag",
        "expected_error": "CNC_USER_CANCELLED",
        "expected_terminal": "Cancelled",
        "cleanup_assertion": "Safely drains buffered callbacks, stops token loop, no UAF",
        "evidence_requirement": "Cancel latency <= 5s, State transition CancelRequested -> DrainingCallbacks -> Cancelled",
        "status": "UNVERIFIED_NATIVE"
    },
    {
        "id": "FI-11",
        "name": "Non-interruptible native call watchdog timeout",
        "injection_target": "Runtime watchdog / supervisor",
        "expected_error": "TIM_EXECUTION_TIMEOUT",
        "expected_terminal": "Aborted",
        "cleanup_assertion": "Runtime marked unhealthy; epoch advanced to isolate hanging backend",
        "evidence_requirement": "Epoch incremented; subsequent requests redirected to clean runtime",
        "status": "UNVERIFIED_NATIVE"
    },
    {
        "id": "FI-12",
        "name": "Native crash / SIGSEGV / abort in worker process",
        "injection_target": "Isolated worker process",
        "expected_error": "RT_CRASHED",
        "expected_terminal": "Failed",
        "cleanup_assertion": "Host process catches Binder death; restarts worker cleanly",
        "evidence_requirement": "Tombstone parsed; clear user error message displayed",
        "status": "UNVERIFIED_NATIVE"
    },
    {
        "id": "FI-13",
        "name": "ProcessRuntimeHost restart",
        "injection_target": "RuntimeSupervisor.advanceEpoch()",
        "expected_error": "INT_CORRUPTED_STATE",
        "expected_terminal": "Aborted",
        "cleanup_assertion": "Old requests drained and aborted; new requests run on fresh epoch",
        "evidence_requirement": "Epoch increment logged; stale events from previous epoch dropped",
        "status": "UNVERIFIED_NATIVE"
    },
    {
        "id": "FI-14",
        "name": "Operating system process death",
        "injection_target": "WorkManager task resumption from SQLite journal",
        "expected_error": "NONE",
        "expected_terminal": "Succeeded",
        "cleanup_assertion": "Idempotency key prevents duplicate execution; outputs committed atomically",
        "evidence_requirement": "Journal replay succeeds; duplicate output file not created",
        "status": "UNVERIFIED_NATIVE"
    },
    {
        "id": "FI-15",
        "name": "Image preview encoding failure",
        "injection_target": "Preview router / JPEG encoder",
        "expected_error": "NONE",
        "expected_terminal": "Succeeded",
        "cleanup_assertion": "Gracefully falls back to headless generation without preview",
        "evidence_requirement": "Preview error logged as warning; final output image committed",
        "status": "UNVERIFIED_NATIVE"
    },
    {
        "id": "FI-16",
        "name": "Disk full during output file write",
        "injection_target": "Output transaction file sink",
        "expected_error": "STR_DISK_FULL",
        "expected_terminal": "Failed",
        "cleanup_assertion": "Partial temp files removed; corrupt artifacts never committed",
        "evidence_requirement": "ErrorEnvelope with STR_DISK_FULL; staging directory cleaned",
        "status": "UNVERIFIED_NATIVE"
    },
    {
        "id": "FI-17",
        "name": "Network disconnected during download / API stream",
        "injection_target": "ManagedModelDownloadWorker / LocalApiEventBridge",
        "expected_error": "NET_CONNECT_FAILED",
        "expected_terminal": "Failed",
        "cleanup_assertion": "Client disconnect triggers cooperative cancel; worker retries if recoverable",
        "evidence_requirement": "WorkerResultSink returns retry for recoverable; SSE closes socket",
        "status": "UNVERIFIED_NATIVE"
    },
    {
        "id": "FI-18",
        "name": "Local API authentication failure",
        "injection_target": "LocalApiRequestMapper.validateAuth()",
        "expected_error": "AUTH_INVALID_KEY",
        "expected_terminal": "Failed",
        "cleanup_assertion": "Short-circuits at HTTP gateway; zero requests dispatched to domain/native",
        "evidence_requirement": "HTTP 401/403 returned; supervisor active count remains 0",
        "status": "UNVERIFIED_NATIVE"
    },
    {
        "id": "FI-19",
        "name": "Malformed Local API request schema",
        "injection_target": "LocalApiRequestMapper.mapChatCompletion()",
        "expected_error": "VAL_INVALID_INPUT",
        "expected_terminal": "Failed",
        "cleanup_assertion": "Rejection at gateway before memory allocation or model invocation",
        "evidence_requirement": "HTTP 400 returned; supervisor active count remains 0",
        "status": "UNVERIFIED_NATIVE"
    },
    {
        "id": "FI-20",
        "name": "Duplicate idempotency key",
        "injection_target": "WorkerCommandAdapter / Facade request registry",
        "expected_error": "NONE",
        "expected_terminal": "Succeeded",
        "cleanup_assertion": "Attaches to existing in-flight execution; never creates secondary executor",
        "evidence_requirement": "Same requestId returned; exactly-once execution maintained",
        "status": "UNVERIFIED_NATIVE"
    },
    {
        "id": "FI-21",
        "name": "Unknown device / missing hardware profile",
        "injection_target": "Capability detection / backend selection",
        "expected_error": "NONE",
        "expected_terminal": "Succeeded",
        "cleanup_assertion": "Profile is advisory only; 100% falls back to generic compatible path",
        "evidence_requirement": "UniversalDeviceAdmissionPolicyTest verifies zero device allowlist blocks",
        "status": "UNVERIFIED_NATIVE"
    },
    {
        "id": "FI-22",
        "name": "Legitimate input fails on real native execution",
        "injection_target": "Native load / graph / inference",
        "expected_error": "RT_CRASHED",
        "expected_terminal": "Failed",
        "cleanup_assertion": "Specific runtime error diagnosed; narrow fallback applied, never global blocklist",
        "evidence_requirement": "ErrorEnvelope reports exact failure reason; device admission remains open",
        "status": "UNVERIFIED_NATIVE"
    }
]

def main():
    print("=== MCA Fault Injection Matrix Verification (Section 10.2) ===")
    print(f"Total Scenarios: {len(FAULT_SCENARIOS)}\n")

    verified_count = 0
    unverified_count = len(FAULT_SCENARIOS)

    for sc in FAULT_SCENARIOS:
        print(f"[{sc['id']}] {sc['name']}")
        print(f"    Target:   {sc['injection_target']}")
        print(f"    Error:    {sc['expected_error']} -> Terminal: {sc['expected_terminal']}")
        print(f"    Cleanup:  {sc['cleanup_assertion']}")
        print(f"    Evidence: {sc['evidence_requirement']}")
        print(f"    Status:   {sc['status']}\n")

    report_data = {
        "title": "MCA Fault Injection Matrix Specification Report",
        "specification": "S-Grade Architecture Plan Section 10.2",
        "evidence_status": "PAPER_SCAFFOLD",
        "total_scenarios": len(FAULT_SCENARIOS),
        "verified_scenarios": verified_count,
        "unverified_scenarios": unverified_count,
        "scenarios": FAULT_SCENARIOS
    }

    REPORT_PATH.parent.mkdir(parents=True, exist_ok=True)
    REPORT_PATH.write_bytes((json.dumps(report_data, indent=2, ensure_ascii=False) + "\n").encode("utf-8"))
    print(f"Fault matrix report written to: {REPORT_PATH}")
    print(f"\nSummary: {verified_count}/{len(FAULT_SCENARIOS)} scenarios verified; executable evidence is missing.")
    return 2

if __name__ == "__main__":
    sys.exit(main())
