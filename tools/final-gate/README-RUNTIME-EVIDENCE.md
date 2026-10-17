# MCA Runtime Evidence Validator

The script verify_runtime_evidence.py is a fail-closed validator for APK-bound
R8/R9 soak, fault-injection, and physical-device evidence. It is designed to be
called by the final gate as a subprocess or imported as validate(...).

It does not run Gradle, adb, a device workload, fault injection, or a soak.
It validates artifacts that were produced elsewhere. Its synthetic self-tests
only prove that the validator accepts/rejects its documented schema; they are
not product evidence.

## Invocation and exit codes

    python -B tools/final-gate/verify_runtime_evidence.py ^
      --apk app/build/outputs/apk/release/app-release.apk ^
      --soak-report <evidence>/soak-report.json ^
      --fault-report <evidence>/fault-report.json ^
      --device-report <evidence>/device-1.json ^
      --device-report <evidence>/device-2.json ^
      --min-devices 2 ^
      --min-soak-hours 24 ^
      --min-requests 1000 ^
      --expected-apk-sha256 <64 hex chars> ^
      --out <evidence>/runtime-evidence-verification.json

The verification JSON is always printed to stdout. --out additionally writes
the same JSON to a file.

| Exit | Meaning |
|---|---|
| 0 | every check is PASS |
| 1 | at least one check is FAIL |
| 2 | no check failed, but at least one is UNVERIFIED |

UNVERIFIED is never treated as a pass. In particular, if apksigner is not
available, apk.signature is UNVERIFIED and the overall exit code is 2. The
validator never infers a signature from ZIP contents.

## Verification output

The output schema is mca-runtime-evidence-verification/1.0:

    {
      "schema": "mca-runtime-evidence-verification/1.0",
      "generated_at": "RFC3339 UTC",
      "inputs": {},
      "computed": {
        "apk": {},
        "soak": {},
        "fault": {},
        "devices": {}
      },
      "checks": {
        "<stable.check.name>": {
          "status": "PASS|FAIL|UNVERIFIED",
          "reason": "human-readable explanation",
          "computed": {}
        }
      },
      "summary": {
        "status": "PASS|FAIL|UNVERIFIED",
        "exit_code": 0,
        "counts": {"PASS": 0, "FAIL": 0, "UNVERIFIED": 0},
        "failed_checks": [],
        "unverified_checks": [],
        "reasons": []
      }
    }

## Common report identity

Every report is a JSON object with:

    {
      "schema": "mca-runtime-evidence/1.0",
      "report_type": "soak|fault|device"
    }

schema_version: "1.0" plus report_type is also accepted for compatibility.
Evidence paths are local, relative paths relative to the report file's
directory. Absolute paths, URLs, .., and symlink escapes are rejected.

Every raw evidence descriptor is:

    {
      "path": "raw/file.json",
      "sha256": "<64 hex chars>",
      "bytes": 1234
    }

The validator independently hashes and sizes every named evidence file. A
descriptor is not proof by itself.

## Soak report

Required top-level fields:

    {
      "schema": "mca-runtime-evidence/1.0",
      "report_type": "soak",
      "status": "PASSED",
      "campaign_kind": "FORMAL_RC",
      "formal_gate_eligible": true,
      "apk_sha256": "<APK SHA-256>",
      "started_at": "2026-10-01T00:00:00Z",
      "ended_at": "2026-10-02T00:10:00Z",
      "warmup": {"completed": true, "requests": 50},
      "requests": {
        "planned": 1200,
        "attempted": 1200,
        "success": 1190,
        "failure": 8,
        "cancel": 2,
        "request_ids": ["req-0001"],
        "unexplained_failures": 0
      },
      "logical_counts": {
        "stream": 900,
        "nonstream": 300,
        "load_unload": 100,
        "cancel": 2,
        "retry": 8,
        "worker_death": 1,
        "restart": 1
      },
      "coverage": {
        "stream": true,
        "nonstream": true,
        "load_unload": true,
        "cancel": true,
        "retry": true,
        "worker_death": true,
        "restart": true
      },
      "memory_samples": [],
      "raw_logs": [],
      "errors": []
    }

Rules:

- started_at and ended_at are timezone-qualified RFC3339 timestamps.
  Duration is computed from them. duration_hours is optional but, if present,
  must agree within 0.01 hour.
- The top-level report must have status=PASSED, campaign_kind=FORMAL_RC,
  formal_gate_eligible=true, an empty errors array, and
  requests.unexplained_failures=0. requests.planned must equal attempted.
- requests.attempted must be at least --min-requests and exactly equal
  success + failure + cancel.
- request_ids is mandatory. It must be a non-empty list of unique non-empty
  strings and its length must equal attempted. Counts alone do not pass.
- All seven logical_counts are required non-negative integers.
  stream + nonstream must equal attempted, and logical_counts.cancel must
  cover requests.cancel.
- Every coverage flag must be true and backed by a positive logical count.
- All memory samples must carry a timestamp (or elapsed_seconds) inside the
  soak interval plus process, PID, PSS, RSS, native heap, FD, and thread fields.
  At least one sample must fall in the final 24-hour window.
- raw_logs must be a non-empty list of evidence descriptors. Missing files,
  byte-count drift, and SHA-256 drift fail the report. requests.* and
  samples.* are core evidence and must be present and non-empty. Only
  device-stderr.log, crash-buffer.log, anr-events.log, and runtime-errors.log
  may be zero bytes, and their declared SHA-256 must still match the empty file.
- apk_size_bytes is optional. If supplied it must equal the recomputed APK
  size.

A sample may use either flat process fields:

    {
      "timestamp": "2026-10-02T00:00:00Z",
      "process_name": "com.muyuchat.mca",
      "pid": 1001,
      "pss_bytes": 210000000,
      "rss_bytes": 350000000,
      "native_heap_bytes": 64000000,
      "fd_count": 42,
      "thread_count": 37
    }

or a non-empty processes array with name, pid, and pss_bytes per process.
The latter is appropriate for main plus worker process samples.

## Fault report

Required top-level fields:

    {
      "schema": "mca-runtime-evidence/1.0",
      "report_type": "fault",
      "status": "VERIFIED",
      "apk_sha256": "<APK SHA-256>",
      "dimensions": {
        "MODEL_TEST": 7,
        "PRODUCTION_INJECTION": 7,
        "DEVICE_EXECUTION": 7
      },
      "mandatory_scenarios": [],
      "errors": []
    }

Each mandatory scenario must be:

    {
      "id": "CANCEL_RETRY",
      "status": "VERIFIED",
      "dimensions": [
        "MODEL_TEST",
        "PRODUCTION_INJECTION",
        "DEVICE_EXECUTION"
      ],
      "device": {
        "serial": "physical-device-serial",
        "physical": true,
        "abi": "arm64-v8a"
      },
      "evidence": {
        "path": "raw/FI-01.json",
        "sha256": "<64 hex chars>",
        "bytes": 1234
      }
    }

Rules:

- The top-level report must have status=VERIFIED and an empty errors array.
- dimensions must contain exactly the three dimension names.
- Mandatory scenario IDs must be unique.
- The mandatory scenario set must cover all seven production categories:
  CANCEL_RETRY, CLIENT_DISCONNECT, WORKER_DEATH,
  MAIN_PROCESS_KILL_COLD_START, LOAD_UNLOAD_UI, BACKGROUND_FOREGROUND, and
  AUTH_REJECTION.
- Every mandatory scenario must be VERIFIED and must carry all three
  dimensions. Every one must carry a physical arm64-v8a device identity and a
  hash-verified raw evidence file.
- Each scenario device serial must belong to the set of fully verified
  physical ARM64 device reports supplied through --device-report.
- Declared dimension counts must equal counts recomputed from the mandatory
  scenario records.
- PRODUCTION_INJECTION and DEVICE_EXECUTION must both be positive, and every
  mandatory scenario must carry both. A model-only report cannot pass.

## Device report

Required fields:

    {
      "schema": "mca-runtime-evidence/1.0",
      "report_type": "device",
      "status": "PASSED",
      "serial": "physical-device-serial",
      "apk_sha256": "<APK SHA-256>",
      "installed_sha256": "<installed APK SHA-256>",
      "device": {
        "serial": "physical-device-serial",
        "physical": true,
        "abi": "arm64-v8a",
        "manufacturer": "manufacturer",
        "model": "model"
      },
      "main_activity_ui": {
        "status": "PASSED",
        "activity": "com.muyuchat.mca/.MainActivity",
        "evidence": {
          "path": "raw/main-activity.json",
          "sha256": "<64 hex chars>",
          "bytes": 1234
        }
      },
      "authenticated_local_api": {
        "status": "PASSED",
        "authenticated": true,
        "http_status": 200,
        "endpoint": "/v1/chat/completions",
        "request_id": "request-id",
        "evidence": {
          "path": "raw/local-api.json",
          "sha256": "<64 hex chars>",
          "bytes": 1234
        }
      },
      "crash_count": 0,
      "anr_count": 0,
      "errors": []
    }

Rules:

- apk_sha256 and installed_sha256 must both match the independently computed
  APK SHA-256.
- The top-level report must have status=PASSED and an empty errors array.
- Top-level and nested serials must both be present and agree. The device must
  be explicitly physical=true and ARM64 (arm64-v8a); device_type values never
  override physical=false.
- The MainActivity UI record and authenticated Local API record must be PASSED,
  carry a 2xx authenticated request with request_id, and reference raw evidence
  whose bytes and SHA-256 verify. The UI component must normalize to
  com.muyuchat.mca/.MainActivity, and the API endpoint must be exactly
  /v1/chat/completions.
- crash_count and anr_count must both be integer zero.
- --device-report may be repeated. Every supplied report must use the same APK;
  serials must be unique and every report must identify a physical ARM64 device.
  --min-devices defaults to 1; the S-grade invocation passes 2 and requires two
  distinct fully verified serials bound to the same APK. If two reports are
  supplied, both are fully validated and a missing/invalid report cannot be
  replaced by a count from the other.

## APK checks

The validator independently computes:

- APK bytes and SHA-256, and compares an optional --expected-apk-sha256.
- ZIP structural/CRC presence of AndroidManifest.xml and classes*.dex.
- Every packaged lib/arm64-v8a/*.so is parsed as a complete ELF:
  - AArch64 libraries must be ELF64 little-endian ET_DYN with a valid
    program-header table and at least one non-zero file-backed PT_LOAD whose
    p_memsz >= p_filesz and whose file range lies inside the file.
  - ELF32 EM_HEXAGON DSP companions must be little-endian ET_DYN with a valid
    program-header table and at least one non-zero file-backed PT_LOAD. A
    malformed Hexagon header/table/load is a failure, not an exemption.
  - Critical AArch64 names such as libmca_native.so cannot masquerade as
    Hexagon ELF32. The validator also records PT_LOAD p_align and whether all
    AArch64 load segments meet 16 KiB alignment.
- Signature validity with apksigner when a tool is discoverable via APKSIGNER,
  PATH, ANDROID_SDK_ROOT/ANDROID_HOME build-tools, or common SDK locations.
  If the tool is absent, the result is UNVERIFIED and the process exits 2.

## Producing the reports from real evidence

Do not create fields by copying a PASSED status. Populate them from primary
artifacts generated during the same APK-bound run:

- Soak start/end: the workload runner's real UTC timestamps covering warm-up
  completion through the final request.
- Warm-up and request counts: the workload journal or API request ledger.
  Request IDs must be the actual IDs emitted by the product path. Counts come
  from terminal states, not from a summary boolean.
- Logical counts and coverage: explicit event markers for stream, non-stream,
  load/unload, cancel, retry, worker death, and restart. A flag is true only
  when the corresponding event appears in the raw logs.
- Memory samples: timestamped adb/dumpsys or /proc samples for the main process
  and every relevant worker PID. Include total PSS, RSS, native heap, FD count,
  and thread count at each sample.
- Raw soak logs: preserve the logs as files next to the report and record their
  exact byte count and SHA-256.
- Fault report: one raw evidence artifact per mandatory scenario, produced by
  the actual production injection and physical-device execution. Model/contract
  results may appear only alongside, never instead of, both of those dimensions.
- Device report: serial and properties from the physical device running the same
  APK; installed_sha256 from the artifact actually installed; raw MainActivity
  UI and authenticated Local API transcripts; crash/ANR counts from the run.

The directory layout recommended for review is:

    <session>/
      soak-report.json
      fault-report.json
      device-1.json
      device-2.json
      raw/
        soak.log
        fi-01.json
        ...
        main-activity.json
        local-api.json

## Final-gate integration

The coordinator can invoke the script and accept only exit code 0. A nonzero
exit must stay a blocker. The JSON summary is stable enough to place under a
dedicated gate result, for example runtime_evidence, with the full checks object
retained for review.

This validator only establishes that the supplied evidence is internally
APK-bound, sufficiently long, count-reconciled, three-dimensional, physically
device-bound, and hash-verified. It cannot make missing real measurements exist,
and it does not by itself establish final S-grade readiness.
