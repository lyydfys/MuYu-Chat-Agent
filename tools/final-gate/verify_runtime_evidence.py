#!/usr/bin/env python3
"""Validate APK-bound soak, fault-injection, and physical-device evidence.

The validator deliberately treats evidence as untrusted input. It recomputes
the APK identity and packaged ELF facts, checks report timestamps and counters,
and hashes every referenced evidence file relative to the report that names it.
It never treats a PASSED boolean as proof by itself.

Exit codes (fail closed):
    0  every check is PASS
    1  at least one check is FAIL
    2  no check failed, but at least one check is UNVERIFIED
"""

from __future__ import annotations

import argparse
import datetime as _dt
import hashlib
import json
import math
import os
import re
import shutil
import struct
import subprocess
import sys
import zipfile
from pathlib import Path
from typing import Any, Dict, List, Mapping, Optional, Sequence, Tuple


SCHEMA_ID = "mca-runtime-evidence/1.0"
VERIFIED_SCHEMA = "mca-runtime-evidence-verification/1.0"
REPORT_TYPES = {"soak", "fault", "device"}
COVERAGE_FIELDS = (
    "stream",
    "nonstream",
    "load_unload",
    "cancel",
    "retry",
    "worker_death",
    "restart",
)
DIMENSION_NAMES = ("MODEL_TEST", "PRODUCTION_INJECTION", "DEVICE_EXECUTION")
HEX64_RE = re.compile(r"^[0-9a-fA-F]{64}$")
RFC3339_RE = re.compile(
    r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$"
)
WINDOWS_DRIVE_RE = re.compile(r"^([A-Za-z]):[\/](.*)$")
MAX_JSON_BYTES = 64 * 1024 * 1024
MAX_PROGRAM_HEADER_BYTES = 4 * 1024 * 1024
PASS = "PASS"
FAIL = "FAIL"
UNVERIFIED = "UNVERIFIED"
PT_LOAD = 1
AARCH64_MACHINE = 183
HEXAGON_MACHINE = 164
EMPTY_OPTIONAL_SOAK_LOG_NAMES = {
    "device-stderr.log",
    "crash-buffer.log",
    "anr-events.log",
    "runtime-errors.log",
}
REQUIRED_FAULT_SCENARIOS = {
    "CANCEL_RETRY",
    "CLIENT_DISCONNECT",
    "WORKER_DEATH",
    "MAIN_PROCESS_KILL_COLD_START",
    "LOAD_UNLOAD_UI",
    "BACKGROUND_FOREGROUND",
    "AUTH_REJECTION",
}
MAIN_ACTIVITY_PACKAGE = "com.muyuchat.mca"
MAIN_ACTIVITY_CLASS = "com.muyuchat.mca.MainActivity"
LOCAL_API_ENDPOINT = "/v1/chat/completions"
CRITICAL_AARCH64_LIBRARY_RE = re.compile(
    r"^lib(?:"
    r"mca_(?:native|qnn_native|mnn_native|sd_native|prompt_handoff)|"
    r"ggml(?:-base|-cpu[^/]*|-hexagon)?|"
    r"llama(?:-common)?|mtmd|"
    r"MNN(?:Audio|OpenCV|_CL|_Express)?|"
    r"geniex[^/]*|hta_hexagon_runtime_[^/]*"
    r")\.so$",
    re.IGNORECASE,
)
REQUIRED_CRITICAL_AARCH64_LIBRARIES = {"libmca_native.so"}


def _add_check(
    checks: Dict[str, Dict[str, Any]],
    name: str,
    status: str,
    reason: str,
    computed: Optional[Mapping[str, Any]] = None,
) -> None:
    """Add one uniquely named reviewable check."""
    if status not in {PASS, FAIL, UNVERIFIED}:
        raise ValueError(f"invalid status: {status}")
    if name in checks:
        raise ValueError(f"duplicate check name: {name}")
    checks[name] = {
        "status": status,
        "reason": reason,
        "computed": dict(computed or {}),
    }


def _coerce_cli_path(raw: str) -> Path:
    """Make Windows drive paths usable by an MSYS/POSIX Python as well."""
    if os.name != "nt":
        match = WINDOWS_DRIVE_RE.match(raw)
        if match:
            mounted = Path("/mnt") / match.group(1).lower() / match.group(2).replace("\\", "/")
            if mounted.exists():
                return mounted
    return Path(raw)


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _normal_hash(value: Any) -> Optional[str]:
    if not isinstance(value, str) or not HEX64_RE.fullmatch(value):
        return None
    return value.lower()


def _is_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _nonnegative_int(value: Any) -> Optional[int]:
    return value if _is_int(value) and value >= 0 else None


def _positive_int(value: Any) -> Optional[int]:
    return value if _is_int(value) and value > 0 else None


def _evidence_basename(descriptor: Mapping[str, Any]) -> str:
    raw_path = descriptor.get("path")
    if not isinstance(raw_path, str):
        return ""
    return re.split(r"[\\/]", raw_path.strip())[-1].lower()


def _soak_raw_log_family(descriptor: Mapping[str, Any]) -> Optional[str]:
    name = _evidence_basename(descriptor)
    if name == "requests.ndjson" or name.startswith(("requests.", "request.")):
        return "requests"
    if name == "samples.ndjson" or name.startswith(("samples.", "sample.")):
        return "samples"
    return None


def _normalize_main_activity_component(value: Any) -> Optional[str]:
    if not isinstance(value, str):
        return None
    component = value.strip()
    if component.startswith("ComponentInfo{") and component.endswith("}"):
        component = component[len("ComponentInfo{"):-1].strip()
    if not component:
        return None
    if "/" in component:
        package, class_name = component.split("/", 1)
        package = package.strip()
        class_name = class_name.strip()
        if class_name.startswith("."):
            class_name = package + class_name
        if package == MAIN_ACTIVITY_PACKAGE and class_name == MAIN_ACTIVITY_CLASS:
            return f"{MAIN_ACTIVITY_PACKAGE}/.MainActivity"
        return None
    if component == MAIN_ACTIVITY_CLASS:
        return f"{MAIN_ACTIVITY_PACKAGE}/.MainActivity"
    return None


def _empty_json_array(value: Any) -> bool:
    return isinstance(value, list) and not value


def _parse_rfc3339(value: Any) -> Optional[_dt.datetime]:
    if not isinstance(value, str) or not RFC3339_RE.fullmatch(value):
        return None
    normalized = value[:-1] + "+00:00" if value.endswith("Z") else value
    try:
        parsed = _dt.datetime.fromisoformat(normalized)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return None
    return parsed.astimezone(_dt.timezone.utc)


def _utc_now() -> str:
    return _dt.datetime.now(_dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _load_json_report(
    raw_path: Optional[str],
    label: str,
    checks: Dict[str, Dict[str, Any]],
) -> Tuple[Optional[Path], Optional[Dict[str, Any]]]:
    if not raw_path:
        _add_check(checks, f"{label}.input", UNVERIFIED, f"{label} report path was not supplied")
        return None, None

    path = _coerce_cli_path(raw_path).expanduser()
    if not path.is_file():
        _add_check(
            checks,
            f"{label}.input",
            FAIL,
            f"{label} report does not exist or is not a regular file",
            {"path": str(path)},
        )
        return path, None
    try:
        size = path.stat().st_size
        if size <= 0 or size > MAX_JSON_BYTES:
            raise ValueError(f"report size {size} is outside (0, {MAX_JSON_BYTES}]")
        parsed = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(parsed, dict):
            raise ValueError("JSON root must be an object")
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as exc:
        _add_check(
            checks,
            f"{label}.input",
            FAIL,
            f"{label} report is not readable strict JSON: {exc}",
            {"path": str(path), "bytes": path.stat().st_size if path.exists() else None},
        )
        return path, None

    _add_check(
        checks,
        f"{label}.input",
        PASS,
        f"{label} report parsed as a JSON object",
        {"path": str(path.resolve()), "bytes": size},
    )
    return path.resolve(), parsed


def _check_schema(
    report: Mapping[str, Any],
    expected_type: str,
    label: str,
    checks: Dict[str, Dict[str, Any]],
) -> bool:
    schema = report.get("schema")
    schema_version = report.get("schema_version")
    report_type = report.get("report_type", report.get("kind"))
    canonical = schema == SCHEMA_ID
    version_alias = schema_version in {1, "1", "1.0", SCHEMA_ID}
    type_ok = expected_type in REPORT_TYPES and report_type == expected_type
    ok = type_ok and (canonical or version_alias)
    _add_check(
        checks,
        f"{label}.schema",
        PASS if ok else FAIL,
        "schema identifier and report_type match the runtime-evidence contract"
        if ok
        else "expected schema 'mca-runtime-evidence/1.0' (or schema_version 1.0) and the exact report_type",
        {
            "schema": schema,
            "schema_version": schema_version,
            "report_type": report_type,
            "expected_report_type": expected_type,
        },
    )
    return ok


def _safe_relative_evidence_path(base_dir: Path, raw_path: Any) -> Tuple[Optional[Path], str]:
    if not isinstance(raw_path, str) or not raw_path.strip():
        return None, "path must be a non-empty string"
    if "\x00" in raw_path:
        return None, "path contains NUL"
    if "://" in raw_path:
        return None, "URLs are not accepted as local evidence paths"
    if Path(raw_path).is_absolute() or WINDOWS_DRIVE_RE.match(raw_path) or raw_path.startswith(("\\\\", "//")):
        return None, "path must be relative to the report file"

    normalized = raw_path.replace("\\", "/")
    parts = normalized.split("/")
    if ".." in parts:
        return None, "parent traversal ('..') is not allowed"
    if any(part in {"", "."} for part in parts):
        return None, "path must not contain empty or '.' components"

    base = base_dir.resolve()
    candidate = (base / normalized).resolve()
    try:
        candidate.relative_to(base)
    except ValueError:
        return None, "resolved path escapes the report directory"
    return candidate, ""


def _evidence_descriptor_from_scenario(scenario: Mapping[str, Any]) -> Optional[Dict[str, Any]]:
    evidence = scenario.get("evidence")
    if isinstance(evidence, dict) and "path" in evidence:
        return {
            "path": evidence.get("path"),
            "sha256": evidence.get("sha256", evidence.get("evidence_sha256")),
            "bytes": evidence.get("bytes", evidence.get("size_bytes")),
        }
    if scenario.get("evidence_path") is not None:
        return {
            "path": scenario.get("evidence_path"),
            "sha256": scenario.get("evidence_sha256"),
            "bytes": scenario.get("evidence_bytes"),
        }
    return None


def _verify_evidence_descriptor(
    descriptor: Optional[Mapping[str, Any]],
    report_dir: Path,
    check_name: str,
    checks: Dict[str, Dict[str, Any]],
    purpose: str,
    *,
    allow_empty: bool = False,
) -> bool:
    if not isinstance(descriptor, Mapping):
        _add_check(
            checks,
            check_name,
            FAIL,
            f"{purpose}: missing evidence descriptor with path, sha256, and bytes",
        )
        return False

    raw_path = descriptor.get("path")
    declared_hash = _normal_hash(descriptor.get("sha256"))
    declared_bytes = _nonnegative_int(descriptor.get("bytes"))
    candidate, path_error = _safe_relative_evidence_path(report_dir, raw_path)
    if candidate is None:
        _add_check(
            checks,
            check_name,
            FAIL,
            f"{purpose}: {path_error}",
            {"path": raw_path},
        )
        return False
    if not candidate.is_file():
        _add_check(
            checks,
            check_name,
            FAIL,
            f"{purpose}: evidence file is missing or not regular",
            {"path": str(candidate)},
        )
        return False
    if declared_hash is None:
        _add_check(
            checks,
            check_name,
            FAIL,
            f"{purpose}: evidence sha256 must be 64 hexadecimal characters",
            {"path": str(candidate), "declared_sha256": descriptor.get("sha256")},
        )
        return False
    if declared_bytes is None or (declared_bytes == 0 and not allow_empty):
        _add_check(
            checks,
            check_name,
            FAIL,
            f"{purpose}: evidence bytes must be "
            f"{'a non-negative integer' if allow_empty else 'a positive integer'}",
            {
                "path": str(candidate),
                "declared_bytes": descriptor.get("bytes"),
                "empty_allowed": allow_empty,
            },
        )
        return False

    try:
        actual_bytes = candidate.stat().st_size
        actual_hash = _sha256_file(candidate)
    except OSError as exc:
        _add_check(
            checks,
            check_name,
            FAIL,
            f"{purpose}: evidence file could not be read: {exc}",
            {"path": str(candidate)},
        )
        return False

    ok = actual_bytes == declared_bytes and actual_hash == declared_hash
    reasons: List[str] = []
    if actual_bytes != declared_bytes:
        reasons.append(f"bytes declared={declared_bytes} actual={actual_bytes}")
    if actual_hash != declared_hash:
        reasons.append(f"sha256 declared={declared_hash} actual={actual_hash}")
    _add_check(
        checks,
        check_name,
        PASS if ok else FAIL,
        f"{purpose}: evidence bytes and SHA-256 match" if ok else f"{purpose}: " + "; ".join(reasons),
        {
            "path": str(candidate),
            "declared_sha256": declared_hash,
            "actual_sha256": actual_hash,
            "declared_bytes": declared_bytes,
            "actual_bytes": actual_bytes,
            "empty_allowed": allow_empty,
        },
    )
    return ok


def _parse_elf_program_headers(
    stream: Any,
    file_size: int,
    elf_class: int,
    header: bytes,
) -> Dict[str, Any]:
    if elf_class == 1:
        header_size = 52
        minimum_phentsize = 32
        if len(header) < header_size:
            return {"ok": False, "error": "truncated ELF32 header"}
        e_type = struct.unpack_from("<H", header, 16)[0]
        e_phoff = struct.unpack_from("<I", header, 28)[0]
        e_phentsize = struct.unpack_from("<H", header, 42)[0]
        e_phnum = struct.unpack_from("<H", header, 44)[0]
    elif elf_class == 2:
        header_size = 64
        minimum_phentsize = 56
        if len(header) < header_size:
            return {"ok": False, "error": "truncated ELF64 header"}
        e_type = struct.unpack_from("<H", header, 16)[0]
        e_phoff = struct.unpack_from("<Q", header, 32)[0]
        e_phentsize = struct.unpack_from("<H", header, 54)[0]
        e_phnum = struct.unpack_from("<H", header, 56)[0]
    else:
        return {"ok": False, "error": f"unsupported ELF class {elf_class}"}

    if e_type != 3:
        return {"ok": False, "error": f"expected ET_DYN(3), got {e_type}", "type": e_type}
    if e_phoff < header_size or e_phnum == 0 or e_phentsize < minimum_phentsize:
        return {
            "ok": False,
            "error": "invalid or empty program-header table",
            "type": e_type,
        }
    if e_phnum == 0xFFFF:
        return {
            "ok": False,
            "error": "extended program-header count is not supported",
            "type": e_type,
        }

    table_bytes = e_phentsize * e_phnum
    if table_bytes > MAX_PROGRAM_HEADER_BYTES:
        return {
            "ok": False,
            "error": "program-header table is unreasonably large",
            "type": e_type,
        }
    if e_phoff > file_size or table_bytes > file_size - e_phoff:
        return {
            "ok": False,
            "error": "program-header table lies outside the file",
            "type": e_type,
        }
    try:
        stream.seek(e_phoff)
        table = stream.read(table_bytes)
    except (OSError, ValueError) as exc:
        return {
            "ok": False,
            "error": f"could not read program-header table: {exc}",
            "type": e_type,
        }
    if len(table) != table_bytes:
        return {"ok": False, "error": "truncated program-header table", "type": e_type}

    load_segments: List[Dict[str, Any]] = []
    segment_errors: List[str] = []
    for index in range(e_phnum):
        offset = index * e_phentsize
        p_type = struct.unpack_from("<I", table, offset)[0]
        if p_type != PT_LOAD:
            continue
        if elf_class == 1:
            p_offset = struct.unpack_from("<I", table, offset + 4)[0]
            p_vaddr = struct.unpack_from("<I", table, offset + 8)[0]
            p_filesz = struct.unpack_from("<I", table, offset + 16)[0]
            p_memsz = struct.unpack_from("<I", table, offset + 20)[0]
            p_align = struct.unpack_from("<I", table, offset + 28)[0]
        else:
            p_offset = struct.unpack_from("<Q", table, offset + 8)[0]
            p_vaddr = struct.unpack_from("<Q", table, offset + 16)[0]
            p_filesz = struct.unpack_from("<Q", table, offset + 32)[0]
            p_memsz = struct.unpack_from("<Q", table, offset + 40)[0]
            p_align = struct.unpack_from("<Q", table, offset + 48)[0]

        within_file = p_offset <= file_size and p_filesz <= file_size - p_offset
        memory_bounds_ok = p_memsz >= p_filesz
        file_backed = p_filesz > 0 and within_file
        congruent = p_align in (0, 1) or (p_vaddr - p_offset) % p_align == 0
        if not within_file:
            segment_errors.append(
                f"PT_LOAD[{index}] file range offset={p_offset} filesz={p_filesz} exceeds {file_size}"
            )
        if not memory_bounds_ok:
            segment_errors.append(
                f"PT_LOAD[{index}] memsz={p_memsz} is smaller than filesz={p_filesz}"
            )
        load_segments.append(
            {
                "p_offset": p_offset,
                "p_vaddr": p_vaddr,
                "p_filesz": p_filesz,
                "p_memsz": p_memsz,
                "p_align": p_align,
                "within_file": within_file,
                "memory_bounds_ok": memory_bounds_ok,
                "file_backed": file_backed,
                "congruent": congruent,
                "at_least_16kb": p_align >= 0x4000 and congruent,
            }
        )

    if not load_segments:
        return {"ok": False, "error": "ELF has no PT_LOAD segment", "type": e_type}
    if segment_errors:
        return {
            "ok": False,
            "error": "; ".join(segment_errors[:4]),
            "type": e_type,
            "load_segments": load_segments,
        }
    if not any(segment["file_backed"] for segment in load_segments):
        return {
            "ok": False,
            "error": "ELF has no non-zero file-backed PT_LOAD segment",
            "type": e_type,
            "load_segments": load_segments,
        }
    return {
        "ok": True,
        "error": "",
        "type": e_type,
        "load_segments": load_segments,
        "all_load_segments_16kb_aligned": all(
            segment["at_least_16kb"] for segment in load_segments
        ),
    }


def _parse_elf_metadata(stream: Any) -> Dict[str, Any]:
    try:
        stream.seek(0)
        header = stream.read(64)
        stream.seek(0, os.SEEK_END)
        file_size = stream.tell()
    except (OSError, ValueError) as exc:
        return {"valid": False, "error": f"could not read ELF: {exc}", "applicable": True}

    if len(header) < 20 or header[:4] != b"\x7fELF":
        return {"valid": False, "error": "not an ELF header", "applicable": True}
    if header[5] != 1:
        return {"valid": False, "error": "not little-endian ELF", "applicable": True}
    if header[6] != 1:
        return {"valid": False, "error": "unsupported ELF identification version", "applicable": True}

    elf_class = header[4]
    e_machine = struct.unpack_from("<H", header, 18)[0]
    if elf_class == 1 and e_machine == HEXAGON_MACHINE:
        parsed = _parse_elf_program_headers(stream, file_size, elf_class, header)
        return {
            "valid": bool(parsed.get("ok")),
            "applicable": False,
            "companion": True,
            "scope": "hexagon_dsp_companion",
            "error": parsed.get("error", ""),
            "type": parsed.get("type"),
            "machine": e_machine,
            "file_size": file_size,
            "load_segment_count": len(parsed.get("load_segments", [])),
            "load_segments": parsed.get("load_segments", []),
            "all_load_segments_16kb_aligned": None,
        }
    if elf_class != 2:
        return {
            "valid": False,
            "applicable": True,
            "companion": False,
            "error": "not ELFCLASS64 AArch64 or ELF32 Hexagon",
            "machine": e_machine,
            "file_size": file_size,
        }

    parsed = _parse_elf_program_headers(stream, file_size, elf_class, header)
    machine_ok = e_machine == AARCH64_MACHINE
    valid = bool(parsed.get("ok")) and machine_ok
    error = parsed.get("error", "")
    if not machine_ok:
        error = f"expected AArch64 EM_AARCH64(183), got {e_machine}"
    load_segments = parsed.get("load_segments", [])
    return {
        "valid": valid,
        "applicable": True,
        "companion": False,
        "scope": "android_abi",
        "error": error,
        "type": parsed.get("type"),
        "machine": e_machine,
        "file_size": file_size,
        "load_segment_count": len(load_segments),
        "load_segments": load_segments,
        "all_load_segments_16kb_aligned": bool(load_segments)
        and all(segment["at_least_16kb"] for segment in load_segments),
    }


def _find_apksigner() -> Optional[Path]:
    explicit = os.environ.get("APKSIGNER", "").strip()
    if explicit:
        candidate = _coerce_cli_path(explicit)
        if candidate.is_file():
            return candidate.resolve()

    for name in ("apksigner", "apksigner.bat"):
        found = shutil.which(name)
        if found:
            return Path(found).resolve()

    sdk_roots: List[Path] = []
    for key in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        value = os.environ.get(key, "").strip()
        if value:
            sdk_roots.append(_coerce_cli_path(value))
    home = Path.home()
    sdk_roots.extend(
        [
            home / "AppData" / "Local" / "Android" / "Sdk",
            home / "Library" / "Android" / "sdk",
            home / "Android" / "Sdk",
        ]
    )

    candidates: List[Path] = []
    seen = set()
    for sdk in sdk_roots:
        build_tools = sdk / "build-tools"
        if not build_tools.is_dir():
            continue
        for version_dir in build_tools.iterdir():
            if not version_dir.is_dir():
                continue
            for name in ("apksigner.bat", "apksigner"):
                candidate = version_dir / name
                if candidate.is_file():
                    key = str(candidate.resolve())
                    if key not in seen:
                        seen.add(key)
                        candidates.append(candidate.resolve())

    def version_key(path: Path) -> Tuple[Any, ...]:
        numbers = re.findall(r"\d+", path.parent.name)
        return tuple(int(number) for number in numbers) or (0,)

    return max(candidates, key=version_key) if candidates else None


def _run_apksigner(tool: Path, apk: Path) -> subprocess.CompletedProcess[str]:
    args = ["verify", "--verbose", "--print-certs", str(apk)]
    if os.name == "nt" and tool.suffix.lower() in {".bat", ".cmd"}:
        command = [os.environ.get("COMSPEC", "cmd.exe"), "/c", str(tool), *args]
    else:
        command = [str(tool), *args]
    return subprocess.run(
        command,
        capture_output=True,
        text=True,
        timeout=30,
        check=False,
    )


def _inspect_apk(
    apk_arg: str,
    expected_apk_sha256: Optional[str],
    checks: Dict[str, Dict[str, Any]],
) -> Dict[str, Any]:
    apk = _coerce_cli_path(apk_arg).expanduser()
    result: Dict[str, Any] = {"path": str(apk)}
    if not apk.is_file():
        _add_check(
            checks,
            "apk.present",
            FAIL,
            "APK path does not exist or is not a regular file",
            {"path": str(apk)},
        )
        return result

    apk = apk.resolve()
    result["path"] = str(apk)
    try:
        size = apk.stat().st_size
        sha256 = _sha256_file(apk)
    except OSError as exc:
        _add_check(
            checks,
            "apk.present",
            FAIL,
            f"APK could not be read: {exc}",
            {"path": str(apk)},
        )
        return result

    result.update({"bytes": size, "sha256": sha256})
    _add_check(checks, "apk.present", PASS, "APK exists and is readable", {"path": str(apk)})
    _add_check(
        checks,
        "apk.sha256",
        PASS,
        "APK SHA-256 independently recomputed",
        {"sha256": sha256, "bytes": size},
    )
    _add_check(
        checks,
        "apk.size",
        PASS if size > 0 else FAIL,
        "APK is non-empty" if size > 0 else "APK is empty",
        {"bytes": size},
    )

    if expected_apk_sha256 is not None:
        expected = _normal_hash(expected_apk_sha256)
        if expected is None:
            _add_check(
                checks,
                "apk.expected_sha256",
                FAIL,
                "--expected-apk-sha256 must be 64 hexadecimal characters",
                {"declared_sha256": expected_apk_sha256},
            )
        else:
            ok = expected == sha256
            _add_check(
                checks,
                "apk.expected_sha256",
                PASS if ok else FAIL,
                "APK SHA-256 matches --expected-apk-sha256"
                if ok
                else "APK SHA-256 does not match --expected-apk-sha256",
                {"expected_sha256": expected, "actual_sha256": sha256},
            )

    if not zipfile.is_zipfile(apk):
        _add_check(
            checks,
            "apk.zip_structure",
            FAIL,
            "APK is not a readable ZIP archive",
            {"path": str(apk)},
        )
        return result

    try:
        with zipfile.ZipFile(apk) as archive:
            bad_member = archive.testzip()
            names = set(archive.namelist())
            arm64_libs = sorted(
                name
                for name in names
                if name.startswith("lib/arm64-v8a/") and name.endswith(".so")
            )
            elf_records: List[Dict[str, Any]] = []
            for name in arm64_libs:
                try:
                    with archive.open(name) as stream:
                        metadata = _parse_elf_metadata(stream)
                except (OSError, RuntimeError, struct.error, zipfile.BadZipFile, ValueError) as exc:
                    metadata = {"valid": False, "error": f"could not read ELF: {exc}"}
                metadata["path"] = name
                elf_records.append(metadata)
    except (OSError, RuntimeError, zipfile.BadZipFile, ValueError) as exc:
        _add_check(
            checks,
            "apk.zip_structure",
            FAIL,
            f"APK ZIP archive could not be inspected: {exc}",
            {"path": str(apk)},
        )
        return result

    zip_ok = bad_member is None
    _add_check(
        checks,
        "apk.zip_structure",
        PASS if zip_ok else FAIL,
        "APK ZIP archive CRC test passed" if zip_ok else f"APK ZIP member failed CRC: {bad_member}",
        {"member_count": len(names), "bad_member": bad_member},
    )
    manifest_ok = "AndroidManifest.xml" in names
    _add_check(
        checks,
        "apk.android_manifest",
        PASS if manifest_ok else FAIL,
        "AndroidManifest.xml is packaged" if manifest_ok else "AndroidManifest.xml is absent",
        {"path": "AndroidManifest.xml"},
    )
    dex_entries = sorted(name for name in names if re.fullmatch(r"classes\d*\.dex", name))
    _add_check(
        checks,
        "apk.classes_dex",
        PASS if dex_entries else FAIL,
        "DEX bytecode is packaged" if dex_entries else "no classes*.dex entry is packaged",
        {"entries": dex_entries},
    )

    applicable_elfs = [item for item in elf_records if item.get("applicable", True)]
    valid_elfs = [item for item in applicable_elfs if item.get("valid")]
    companion_elfs = [item for item in elf_records if item.get("companion") is True]
    invalid_elfs = [item for item in elf_records if not item.get("valid")]
    critical_elfs = [
        item
        for item in elf_records
        if CRITICAL_AARCH64_LIBRARY_RE.fullmatch(Path(str(item.get("path", ""))).name)
    ]
    critical_names = {Path(str(item.get("path", ""))).name for item in critical_elfs}
    critical_required_present = REQUIRED_CRITICAL_AARCH64_LIBRARIES <= critical_names
    critical_ok = critical_required_present and bool(critical_elfs) and all(
        item.get("valid") is True
        and item.get("applicable") is True
        and item.get("companion") is not True
        for item in critical_elfs
    )
    elf_ok = bool(applicable_elfs) and not invalid_elfs and critical_ok
    elf_reason = (
        f"{len(applicable_elfs)} packaged Android ABI ELF64 AArch64 shared objects are ET_DYN with PT_LOAD; "
        f"{len(companion_elfs)} structurally valid Hexagon DSP companions are exempt"
        if elf_ok
        else "no Android ABI ELF is packaged"
        if not applicable_elfs
        else "one or more packaged ELF records are structurally invalid"
        if invalid_elfs
        else "a required critical AArch64 library is missing or disguised as a non-AArch64 ELF"
    )
    _add_check(
        checks,
        "apk.elf_arm64",
        PASS if elf_ok else FAIL,
        elf_reason,
        {
            "arm64_library_count": len(arm64_libs),
            "applicable_android_library_count": len(applicable_elfs),
            "valid_library_count": len(valid_elfs),
            "invalid_library_count": len(invalid_elfs),
            "hexagon_dsp_companion_count": len(companion_elfs),
            "critical_aarch64_library_count": len(critical_elfs),
            "critical_aarch64_libraries": sorted(critical_names),
            "required_critical_aarch64_libraries": sorted(REQUIRED_CRITICAL_AARCH64_LIBRARIES),
            "libraries": elf_records,
            "all_load_segments_16kb_aligned": bool(applicable_elfs)
            and all(item.get("all_load_segments_16kb_aligned") is True for item in applicable_elfs),
        },
    )
    result["elf"] = elf_records

    signature_tool = _find_apksigner()
    if signature_tool is None:
        _add_check(
            checks,
            "apk.signature",
            UNVERIFIED,
            "apksigner was not found; signature verification is explicitly UNVERIFIED",
            {"tool": None},
        )
    else:
        try:
            completed = _run_apksigner(signature_tool, apk)
        except (OSError, subprocess.TimeoutExpired) as exc:
            _add_check(
                checks,
                "apk.signature",
                FAIL,
                f"apksigner could not verify the APK: {exc}",
                {"tool": str(signature_tool)},
            )
        else:
            output = (completed.stdout or "") + "\n" + (completed.stderr or "")
            schemes = sorted(
                set(
                    re.findall(
                        r"Verified using v[1-4](?:\.\d+)? scheme[^:]*:\s*true",
                        output,
                        flags=re.IGNORECASE,
                    )
                )
            )
            signer_match = re.search(r"Number of signers:\s*(\d+)", output, flags=re.IGNORECASE)
            signer_count = int(signer_match.group(1)) if signer_match else None
            signature_ok = completed.returncode == 0
            _add_check(
                checks,
                "apk.signature",
                PASS if signature_ok else FAIL,
                "apksigner accepted the APK signature"
                if signature_ok
                else f"apksigner rejected the APK signature (exit {completed.returncode})",
                {
                    "tool": str(signature_tool),
                    "exit_code": completed.returncode,
                    "verified_schemes": schemes,
                    "signer_count": signer_count,
                },
            )
            result["signature"] = {
                "tool": str(signature_tool),
                "exit_code": completed.returncode,
                "verified_schemes": schemes,
                "signer_count": signer_count,
            }
    return result


def _validate_soak(
    report_path: Optional[Path],
    report: Optional[Mapping[str, Any]],
    apk: Mapping[str, Any],
    min_soak_hours: float,
    min_requests: int,
    checks: Dict[str, Dict[str, Any]],
) -> Dict[str, Any]:
    if report_path is None or report is None:
        _add_check(checks, "soak.evidence", UNVERIFIED, "soak evidence was not available")
        return {}
    if not _check_schema(report, "soak", "soak", checks):
        _add_check(checks, "soak.evidence", FAIL, "soak evidence failed schema validation")
        return {}

    actual_sha = _normal_hash(apk.get("sha256"))
    declared_sha = _normal_hash(report.get("apk_sha256"))
    sha_ok = actual_sha is not None and declared_sha == actual_sha
    _add_check(
        checks,
        "soak.apk_sha256",
        PASS if sha_ok else FAIL,
        "soak report is bound to the recomputed APK SHA-256"
        if sha_ok
        else "soak apk_sha256 is missing, malformed, or does not match the APK",
        {"declared_sha256": declared_sha, "actual_sha256": actual_sha},
    )

    if report.get("apk_size_bytes") is not None:
        declared_bytes = _nonnegative_int(report.get("apk_size_bytes"))
        actual_bytes = _nonnegative_int(apk.get("bytes"))
        size_ok = declared_bytes is not None and declared_bytes == actual_bytes
        _add_check(
            checks,
            "soak.apk_size_bytes",
            PASS if size_ok else FAIL,
            "soak apk_size_bytes matches the recomputed APK size"
            if size_ok
            else "soak apk_size_bytes is malformed or does not match the APK",
            {"declared_bytes": declared_bytes, "actual_bytes": actual_bytes},
        )

    started = _parse_rfc3339(report.get("started_at", report.get("start_time")))
    ended = _parse_rfc3339(report.get("ended_at", report.get("end_time")))
    timestamps_ok = started is not None and ended is not None and ended > started
    _add_check(
        checks,
        "soak.timestamps",
        PASS if timestamps_ok else FAIL,
        "started_at and ended_at are timezone-qualified RFC3339 timestamps in increasing order"
        if timestamps_ok
        else "started_at/ended_at must be timezone-qualified RFC3339 timestamps with end after start",
        {
            "started_at": report.get("started_at", report.get("start_time")),
            "ended_at": report.get("ended_at", report.get("end_time")),
        },
    )

    duration_hours = 0.0
    if timestamps_ok:
        assert started is not None and ended is not None
        duration_seconds = (ended - started).total_seconds()
        duration_hours = duration_seconds / 3600.0
        duration_ok = duration_hours >= min_soak_hours
        _add_check(
            checks,
            "soak.duration",
            PASS if duration_ok else FAIL,
            f"computed soak duration is at least {min_soak_hours:g} hours"
            if duration_ok
            else f"computed soak duration is below {min_soak_hours:g} hours",
            {
                "started_at_utc": started.isoformat().replace("+00:00", "Z"),
                "ended_at_utc": ended.isoformat().replace("+00:00", "Z"),
                "duration_seconds": round(duration_seconds, 3),
                "duration_hours": round(duration_hours, 6),
                "minimum_hours": min_soak_hours,
            },
        )
        declared_duration = report.get("duration_hours")
        if declared_duration is not None:
            declared_ok = (
                isinstance(declared_duration, (int, float))
                and not isinstance(declared_duration, bool)
                and math.isfinite(float(declared_duration))
                and abs(float(declared_duration) - duration_hours) <= 0.01
            )
            _add_check(
                checks,
                "soak.duration_hours_consistency",
                PASS if declared_ok else FAIL,
                "declared duration_hours agrees with recomputed timestamps"
                if declared_ok
                else "declared duration_hours disagrees with the timestamps",
                {"declared_hours": declared_duration, "computed_hours": round(duration_hours, 6)},
            )
    else:
        _add_check(checks, "soak.duration", FAIL, "duration cannot be computed without valid timestamps")

    warmup = report.get("warmup")
    warmup_ok = False
    warmup_computed: Dict[str, Any] = {}
    if isinstance(warmup, Mapping):
        completed = warmup.get("completed") is True
        warmup_requests = _positive_int(warmup.get("requests"))
        warmup_ok = completed and warmup_requests is not None
        warmup_computed = {
            "completed": warmup.get("completed"),
            "requests": warmup.get("requests"),
            "duration_seconds": warmup.get("duration_seconds"),
        }
    _add_check(
        checks,
        "soak.warmup",
        PASS if warmup_ok else FAIL,
        "warmup completed and contains a positive request count"
        if warmup_ok
        else "warmup must be an object with completed=true and requests>0",
        warmup_computed,
    )

    request_obj = report.get("requests")
    attempted = success = failure = cancel = None
    requests_ok = False
    if isinstance(request_obj, Mapping):
        attempted = _nonnegative_int(request_obj.get("attempted", request_obj.get("total")))
        success = _nonnegative_int(request_obj.get("success"))
        failure = _nonnegative_int(request_obj.get("failure"))
        cancel = _nonnegative_int(request_obj.get("cancel"))
        requests_ok = (
            attempted is not None
            and success is not None
            and failure is not None
            and cancel is not None
            and attempted >= min_requests
            and attempted == success + failure + cancel
        )
    _add_check(
        checks,
        "soak.request_counts",
        PASS if requests_ok else FAIL,
        "request counts reconcile and meet --min-requests"
        if requests_ok
        else "requests must contain non-negative attempted/success/failure/cancel; "
        "attempted=success+failure+cancel and attempted>=minimum",
        {
            "attempted": attempted,
            "success": success,
            "failure": failure,
            "cancel": cancel,
            "minimum_requests": min_requests,
            "sum": (
                success + failure + cancel
                if success is not None and failure is not None and cancel is not None
                else None
            ),
        },
    )

    ids_ok = False
    ids_computed: Dict[str, Any] = {"ids_supplied": False, "attempted": attempted}
    raw_ids = request_obj.get("request_ids") if isinstance(request_obj, Mapping) else None
    if isinstance(raw_ids, list):
        ids_computed["ids_supplied"] = True
        if not raw_ids or not all(
            isinstance(item, str) and bool(item.strip()) for item in raw_ids
        ):
            ids_computed["problem"] = "request_ids must be a non-empty list of non-empty strings"
        else:
            unique_count = len(set(raw_ids))
            count_matches = attempted is not None and len(raw_ids) == attempted
            ids_ok = unique_count == len(raw_ids) and count_matches
            ids_computed.update(
                {
                    "count": len(raw_ids),
                    "unique_count": unique_count,
                    "attempted": attempted,
                    "duplicates": len(raw_ids) - unique_count,
                    "count_matches_attempted": count_matches,
                }
            )
    else:
        ids_computed["problem"] = "request_ids must be supplied as a list"
    _add_check(
        checks,
        "soak.request_ids",
        PASS if ids_ok else FAIL,
        "request IDs are non-empty, unique, and match the attempted count"
        if ids_ok
        else "request IDs are absent, malformed, duplicated, or do not match attempted count",
        ids_computed,
    )

    planned_nested = request_obj.get("planned") if isinstance(request_obj, Mapping) else None
    planned_top = report.get("planned")
    planned_values = [value for value in (planned_nested, planned_top) if value is not None]
    planned = _nonnegative_int(
        planned_nested if planned_nested is not None else planned_top
    )
    unexplained_nested = request_obj.get("unexplained_failures") if isinstance(request_obj, Mapping) else None
    unexplained_top = report.get("unexplained_failures")
    unexplained_values = [
        value for value in (unexplained_nested, unexplained_top) if value is not None
    ]
    unexplained_zero = all(_nonnegative_int(value) == 0 for value in unexplained_values)
    formal_status_ok = (
        report.get("status") == "PASSED"
        and report.get("campaign_kind") == "FORMAL_RC"
        and report.get("formal_gate_eligible") is True
        and _empty_json_array(report.get("errors"))
        and bool(unexplained_values)
        and unexplained_zero
        and planned is not None
        and attempted is not None
        and planned == attempted
        and all(_nonnegative_int(value) == planned for value in planned_values)
    )
    _add_check(
        checks,
        "soak.top_level",
        PASS if formal_status_ok else FAIL,
        "soak report is PASSED/FORMAL_RC/formal-gate eligible with no errors, zero unexplained "
        "failures, and planned==attempted"
        if formal_status_ok
        else "soak report must have status=PASSED, campaign_kind=FORMAL_RC, "
        "formal_gate_eligible=true, errors=[], unexplained_failures=0, and planned==attempted",
        {
            "status": report.get("status"),
            "campaign_kind": report.get("campaign_kind"),
            "formal_gate_eligible": report.get("formal_gate_eligible"),
            "errors": report.get("errors"),
            "planned_nested": planned_nested,
            "planned_top_level": planned_top,
            "attempted": attempted,
            "unexplained_failures_nested": unexplained_nested,
            "unexplained_failures_top_level": unexplained_top,
        },
    )

    logical_counts = report.get("logical_counts")
    logical_ok = isinstance(logical_counts, Mapping)
    logical_computed: Dict[str, Any] = {}
    if isinstance(logical_counts, Mapping):
        for field in COVERAGE_FIELDS:
            value = _nonnegative_int(logical_counts.get(field))
            logical_computed[field] = value
            if value is None:
                logical_ok = False
        if logical_ok and attempted is not None:
            logical_ok = logical_counts["stream"] + logical_counts["nonstream"] == attempted
        if logical_ok and cancel is not None:
            logical_ok = logical_counts["cancel"] >= cancel
    _add_check(
        checks,
        "soak.logical_counts",
        PASS if logical_ok else FAIL,
        "logical counts are non-negative, stream+nonstream=attempted, and cancel covers request cancels"
        if logical_ok
        else "logical_counts must carry all seven non-negative counters and reconcile with requests",
        logical_computed,
    )

    coverage = report.get("coverage")
    coverage_ok = isinstance(coverage, Mapping)
    coverage_computed: Dict[str, Any] = {}
    if isinstance(coverage, Mapping):
        for field in COVERAGE_FIELDS:
            flag = coverage.get(field)
            count = logical_counts.get(field) if isinstance(logical_counts, Mapping) else None
            field_ok = flag is True and _positive_int(count) is not None
            coverage_computed[field] = {"flag": flag, "count": count, "pass": field_ok}
            coverage_ok = coverage_ok and field_ok
    _add_check(
        checks,
        "soak.coverage",
        PASS if coverage_ok else FAIL,
        "all seven coverage flags are true and backed by positive logical counts"
        if coverage_ok
        else "each stream/nonstream/load-unload/cancel/retry/worker-death/restart flag "
        "must be true and have a positive logical count",
        coverage_computed,
    )

    samples = report.get("memory_samples", report.get("samples"))
    samples_ok = False
    samples_in_window = 0
    sample_errors: List[str] = []
    process_count = 0
    sample_computed: Dict[str, Any] = {}
    if not isinstance(samples, list) or not samples:
        sample_errors.append("memory_samples must be a non-empty list")
    elif not timestamps_ok:
        sample_errors.append("cannot place samples without valid report timestamps")
    else:
        assert started is not None and ended is not None
        window_start = ended - _dt.timedelta(hours=24)
        for index, sample in enumerate(samples):
            if not isinstance(sample, Mapping):
                sample_errors.append(f"sample[{index}] is not an object")
                continue
            sample_time = _parse_rfc3339(sample.get("timestamp"))
            elapsed_value = sample.get("elapsed_seconds")
            if (
                sample_time is None
                and isinstance(elapsed_value, (int, float))
                and not isinstance(elapsed_value, bool)
            ):
                elapsed = float(elapsed_value)
                if math.isfinite(elapsed) and elapsed >= 0:
                    sample_time = started + _dt.timedelta(seconds=elapsed)
            if sample_time is None:
                sample_errors.append(f"sample[{index}] has no valid timestamp/elapsed_seconds")
                continue
            if sample_time < started or sample_time > ended:
                sample_errors.append(f"sample[{index}] timestamp lies outside the soak window")
                continue
            if window_start <= sample_time <= ended:
                samples_in_window += 1

            memory = sample.get("memory") if isinstance(sample.get("memory"), Mapping) else {}
            rss = _positive_int(sample.get("rss_bytes", memory.get("rss_bytes")))
            native_heap = _nonnegative_int(
                sample.get("native_heap_bytes", memory.get("native_heap_bytes"))
            )
            fd_count = _nonnegative_int(sample.get("fd_count", memory.get("fd_count")))
            thread_count = _nonnegative_int(
                sample.get("thread_count", memory.get("thread_count"))
            )
            if rss is None or native_heap is None or fd_count is None or thread_count is None:
                sample_errors.append(
                    f"sample[{index}] requires positive rss_bytes and non-negative "
                    "native_heap_bytes/fd_count/thread_count"
                )

            processes = sample.get("processes")
            if isinstance(processes, list) and processes:
                sample_process_count = 0
                pss_total = 0
                for process_index, process in enumerate(processes):
                    if not isinstance(process, Mapping):
                        sample_errors.append(f"sample[{index}].processes[{process_index}] is not an object")
                        continue
                    name = process.get("name", process.get("process_name"))
                    pid = _positive_int(process.get("pid"))
                    pss = _positive_int(process.get("pss_bytes"))
                    if not isinstance(name, str) or not name.strip() or pid is None or pss is None:
                        sample_errors.append(
                            f"sample[{index}].processes[{process_index}] requires name, pid>0, pss_bytes>0"
                        )
                    else:
                        sample_process_count += 1
                        pss_total += pss
                process_count += sample_process_count
            else:
                name = sample.get("process_name", sample.get("process"))
                pid = _positive_int(sample.get("pid"))
                pss = _positive_int(sample.get("pss_bytes", memory.get("pss_bytes")))
                if not isinstance(name, str) or not name.strip() or pid is None or pss is None:
                    sample_errors.append(
                        f"sample[{index}] requires process/process_name, pid>0, and pss_bytes>0"
                    )
                else:
                    process_count += 1
                    pss_total = pss

        samples_ok = not sample_errors and samples_in_window > 0
        if samples_in_window == 0:
            sample_errors.append("no memory sample falls within the final 24-hour window")
        sample_computed = {
            "sample_count": len(samples),
            "samples_in_final_24h": samples_in_window,
            "process_sample_count": process_count,
            "sample_errors": sample_errors[:10],
            "final_24h_window_start_utc": window_start.isoformat().replace("+00:00", "Z"),
        }
    _add_check(
        checks,
        "soak.memory_samples",
        PASS if samples_ok else FAIL,
        "memory samples carry process/PID/PSS/RSS/native-heap/FD/thread fields and at least one "
        "sample is in the final 24-hour window"
        if samples_ok
        else "memory samples are missing, malformed, lack required fields, or do not cover the final 24h",
        sample_computed,
    )

    raw_logs = report.get("raw_logs")
    if isinstance(raw_logs, Mapping):
        raw_logs = [{"path": path, **value} for path, value in raw_logs.items() if isinstance(value, Mapping)]
    raw_logs_ok = isinstance(raw_logs, list) and bool(raw_logs)
    raw_log_results: List[bool] = []
    core_family_present = {"requests": False, "samples": False}
    core_family_nonempty = {"requests": False, "samples": False}
    optional_empty_logs: List[str] = []
    if isinstance(raw_logs, list):
        for index, descriptor in enumerate(raw_logs):
            allow_empty = isinstance(descriptor, Mapping) and (
                _evidence_basename(descriptor) in EMPTY_OPTIONAL_SOAK_LOG_NAMES
            )
            verified = _verify_evidence_descriptor(
                descriptor,
                report_path.parent,
                f"soak.raw_logs[{index}]",
                checks,
                f"soak raw log[{index}]",
                allow_empty=allow_empty,
            )
            raw_log_results.append(verified)
            if isinstance(descriptor, Mapping):
                basename = _evidence_basename(descriptor)
                if allow_empty and _nonnegative_int(descriptor.get("bytes")) == 0:
                    optional_empty_logs.append(basename)
                family = _soak_raw_log_family(descriptor)
                if family is not None:
                    core_family_present[family] = True
                    if verified and _positive_int(descriptor.get("bytes")) is not None:
                        core_family_nonempty[family] = True
        raw_logs_ok = raw_logs_ok and all(raw_log_results)
    core_logs_ok = all(core_family_nonempty.values())
    _add_check(
        checks,
        "soak.raw_logs",
        PASS if raw_logs_ok else FAIL,
        "all raw soak logs exist and match declared byte counts and SHA-256 values; "
        "only the named optional empty logs may be zero-byte"
        if raw_logs_ok
        else "raw_logs must be a non-empty list whose files match bytes/SHA-256 and whose "
        "non-optional entries are non-empty",
        {
            "declared_count": len(raw_logs) if isinstance(raw_logs, list) else None,
            "verified_count": sum(raw_log_results),
            "optional_empty_logs": sorted(optional_empty_logs),
        },
    )
    _add_check(
        checks,
        "soak.core_raw_logs",
        PASS if core_logs_ok else FAIL,
        "non-empty request and sample NDJSON evidence is present"
        if core_logs_ok
        else "non-empty requests.* and samples.* raw evidence is mandatory",
        {
            "requests_present": core_family_present["requests"],
            "requests_nonempty": core_family_nonempty["requests"],
            "samples_present": core_family_present["samples"],
            "samples_nonempty": core_family_nonempty["samples"],
        },
    )

    return {
        "duration_hours": round(duration_hours, 6) if timestamps_ok else None,
        "samples_in_final_24h": samples_in_window,
        "request_count": attempted,
        "memory_process_sample_count": process_count,
    }


def _dimension_count(declared: Any, name: str) -> Optional[int]:
    value = declared.get(name) if isinstance(declared, Mapping) else None
    if isinstance(value, Mapping):
        value = value.get("count")
    return _nonnegative_int(value)


def _validate_fault(
    report_path: Optional[Path],
    report: Optional[Mapping[str, Any]],
    apk: Mapping[str, Any],
    checks: Dict[str, Dict[str, Any]],
    verified_device_serials: Sequence[str],
) -> Dict[str, Any]:
    if report_path is None or report is None:
        _add_check(checks, "fault.evidence", UNVERIFIED, "fault evidence was not available")
        return {}
    if not _check_schema(report, "fault", "fault", checks):
        _add_check(checks, "fault.evidence", FAIL, "fault evidence failed schema validation")
        return {}

    actual_sha = _normal_hash(apk.get("sha256"))
    declared_sha = _normal_hash(report.get("apk_sha256"))
    sha_ok = actual_sha is not None and declared_sha == actual_sha
    _add_check(
        checks,
        "fault.apk_sha256",
        PASS if sha_ok else FAIL,
        "fault report is bound to the recomputed APK SHA-256"
        if sha_ok
        else "fault apk_sha256 is missing, malformed, or does not match the APK",
        {"declared_sha256": declared_sha, "actual_sha256": actual_sha},
    )

    top_level_ok = (
        report.get("status") == "VERIFIED" and _empty_json_array(report.get("errors"))
    )
    _add_check(
        checks,
        "fault.top_level",
        PASS if top_level_ok else FAIL,
        "fault report has status=VERIFIED and no errors"
        if top_level_ok
        else "fault report must have status=VERIFIED and an empty errors array",
        {"status": report.get("status"), "errors": report.get("errors")},
    )

    dimensions = report.get("dimensions")
    dimension_values = {
        name: _dimension_count(dimensions, name) for name in DIMENSION_NAMES
    }
    dimensions_shape_ok = (
        isinstance(dimensions, Mapping)
        and set(dimensions.keys()) == set(DIMENSION_NAMES)
        and all(value is not None for value in dimension_values.values())
    )
    _add_check(
        checks,
        "fault.dimensions",
        PASS if dimensions_shape_ok else FAIL,
        "all three exact evidence dimensions are declared with integer counts"
        if dimensions_shape_ok
        else "dimensions must contain exactly MODEL_TEST, PRODUCTION_INJECTION, and "
        "DEVICE_EXECUTION with non-negative integer counts",
        dimension_values,
    )

    scenarios = report.get("mandatory_scenarios")
    if not isinstance(scenarios, list) or not scenarios:
        _add_check(
            checks,
            "fault.scenarios",
            FAIL,
            "mandatory_scenarios must be a non-empty list",
            {"scenario_count": len(scenarios) if isinstance(scenarios, list) else None},
        )
        _add_check(
            checks,
            "fault.model_only_guard",
            FAIL,
            "cannot establish production/device execution without mandatory scenarios",
            dimension_values,
        )
        _add_check(checks, "fault.evidence", FAIL, "fault mandatory scenario evidence is missing")
        return {}

    computed_counts = {name: 0 for name in DIMENSION_NAMES}
    scenario_ids: List[str] = []
    scenario_device_serials: List[Optional[str]] = []
    all_scenarios_ok = True
    production_and_device_all = True
    verified_serial_set = set(verified_device_serials)

    for index, scenario in enumerate(scenarios):
        prefix = f"fault.scenario[{index}]"
        if not isinstance(scenario, Mapping):
            _add_check(checks, f"{prefix}.record", FAIL, "mandatory scenario is not an object")
            all_scenarios_ok = False
            production_and_device_all = False
            continue
        scenario_id = scenario.get("id")
        if not isinstance(scenario_id, str) or not scenario_id.strip():
            scenario_id = f"index-{index}"
        scenario_ids.append(scenario_id)

        scenario_dimensions = scenario.get("dimensions")
        if not isinstance(scenario_dimensions, list) or not all(
            isinstance(item, str) for item in scenario_dimensions
        ):
            scenario_dimensions = []
        invalid_dimensions = sorted(set(scenario_dimensions) - set(DIMENSION_NAMES))
        scenario_dimension_set = set(scenario_dimensions)
        for dimension_name in scenario_dimension_set & set(DIMENSION_NAMES):
            computed_counts[dimension_name] += 1

        status_ok = scenario.get("status") == "VERIFIED"
        dimensions_ok = (
            not invalid_dimensions
            and len(scenario_dimension_set) == len(scenario_dimensions)
            and scenario_dimension_set == set(DIMENSION_NAMES)
        )
        production_and_device_all = production_and_device_all and dimensions_ok

        device = scenario.get("device")
        device_ok = False
        device_identity: Any = device
        scenario_serial: Optional[str] = None
        if isinstance(device, Mapping):
            serial = device.get("serial")
            scenario_serial = serial if isinstance(serial, str) and serial.strip() else None
            serial_membership_ok = (
                scenario_serial in verified_serial_set if verified_serial_set else True
            )
            device_ok = (
                scenario_serial is not None
                and device.get("physical") is True
                and device.get("abi") == "arm64-v8a"
                and serial_membership_ok
            )
            device_identity = serial
        scenario_device_serials.append(scenario_serial)

        descriptor = _evidence_descriptor_from_scenario(scenario)
        evidence_ok = _verify_evidence_descriptor(
            descriptor,
            report_path.parent,
            f"{prefix}.evidence_file",
            checks,
            f"fault scenario {scenario_id} raw evidence",
        )
        record_ok = status_ok and dimensions_ok and device_ok and evidence_ok
        reasons: List[str] = []
        if not status_ok:
            reasons.append("status != VERIFIED")
        if invalid_dimensions:
            reasons.append(f"unknown dimensions: {', '.join(invalid_dimensions)}")
        if scenario_dimension_set != set(DIMENSION_NAMES):
            reasons.append("every mandatory scenario must carry all three evidence dimensions")
        if not device_ok:
            reasons.append(
                "device must identify a physical arm64-v8a execution target whose serial is in "
                "the verified physical-device set"
            )
        if not evidence_ok:
            reasons.append("raw evidence file/hash is invalid")
        _add_check(
            checks,
            f"{prefix}.verification",
            PASS if record_ok else FAIL,
            f"mandatory scenario {scenario_id} is fully evidence-backed"
            if record_ok
            else f"mandatory scenario {scenario_id}: " + "; ".join(reasons),
            {
                "id": scenario.get("id"),
                "status": scenario.get("status"),
                "dimensions": scenario_dimensions,
                "device": device_identity,
                "evidence_file_verified": evidence_ok,
            },
        )
        all_scenarios_ok = all_scenarios_ok and record_ok

    duplicate_ids = sorted({item for item in scenario_ids if scenario_ids.count(item) > 1})
    ids_ok = not duplicate_ids
    _add_check(
        checks,
        "fault.scenario_ids",
        PASS if ids_ok else FAIL,
        "mandatory scenario IDs are unique" if ids_ok else f"duplicate scenario IDs: {duplicate_ids}",
        {"count": len(scenario_ids), "duplicate_ids": duplicate_ids},
    )

    missing_required = sorted(REQUIRED_FAULT_SCENARIOS - set(scenario_ids))
    required_scenarios_ok = not missing_required
    _add_check(
        checks,
        "fault.required_scenarios",
        PASS if required_scenarios_ok else FAIL,
        "all seven production fault scenarios are present"
        if required_scenarios_ok
        else f"missing mandatory production fault scenarios: {missing_required}",
        {
            "required": sorted(REQUIRED_FAULT_SCENARIOS),
            "observed": scenario_ids,
            "missing": missing_required,
        },
    )

    if not verified_serial_set:
        binding_status = UNVERIFIED
    elif all(
        isinstance(serial, str) and serial.strip() and serial in verified_serial_set
        for serial in scenario_device_serials
    ):
        binding_status = PASS
    else:
        binding_status = FAIL
    _add_check(
        checks,
        "fault.device_serial_binding",
        binding_status,
        "every fault scenario serial belongs to a fully verified physical ARM64 device report"
        if binding_status == PASS
        else "fault scenario serial binding cannot be verified because no verified device set exists"
        if binding_status == UNVERIFIED
        else "one or more fault scenario serials do not belong to the verified device serial set",
        {
            "scenario_serials": scenario_device_serials,
            "verified_device_serials": sorted(verified_serial_set),
        },
    )

    counts_match = dimensions_shape_ok and dimension_values == computed_counts
    _add_check(
        checks,
        "fault.dimension_count_consistency",
        PASS if counts_match else FAIL,
        "declared dimension counts equal counts recomputed from mandatory scenarios"
        if counts_match
        else "declared dimension counts do not equal counts recomputed from mandatory scenarios",
        {"declared": dimension_values, "computed": computed_counts},
    )

    production_and_device_globally = (
        (dimension_values.get("PRODUCTION_INJECTION") or 0) > 0
        and (dimension_values.get("DEVICE_EXECUTION") or 0) > 0
    )
    model_only_guard = (
        production_and_device_globally
        and computed_counts["PRODUCTION_INJECTION"] > 0
        and computed_counts["DEVICE_EXECUTION"] > 0
        and production_and_device_all
    )
    _add_check(
        checks,
        "fault.model_only_guard",
        PASS if model_only_guard else FAIL,
        "every mandatory scenario includes PRODUCTION_INJECTION and DEVICE_EXECUTION evidence"
        if model_only_guard
        else "model-only evidence is insufficient; every mandatory scenario needs production "
        "injection and physical-device execution evidence",
        {
            "declared": dimension_values,
            "computed": computed_counts,
            "all_mandatory_scenarios_have_production_and_device": production_and_device_all,
        },
    )

    evidence_core_ok = (
        all_scenarios_ok
        and ids_ok
        and required_scenarios_ok
        and counts_match
        and model_only_guard
        and sha_ok
        and top_level_ok
    )
    evidence_ok = evidence_core_ok and binding_status == PASS
    _add_check(
        checks,
        "fault.evidence",
        PASS if evidence_ok else UNVERIFIED if evidence_core_ok and binding_status == UNVERIFIED else FAIL,
        "fault evidence is APK-bound and all mandatory scenarios have verified 3D evidence"
        if evidence_ok
        else "fault evidence is complete but no verified physical-device set is available"
        if evidence_core_ok and binding_status == UNVERIFIED
        else "fault evidence is incomplete or contains model-only/device-unverified scenarios",
        {"scenario_count": len(scenarios), "computed_dimensions": computed_counts},
    )
    return {"dimensions": computed_counts, "scenario_count": len(scenarios)}


def _device_serial(report: Mapping[str, Any]) -> Optional[str]:
    top = report.get("serial")
    device = report.get("device")
    nested = device.get("serial") if isinstance(device, Mapping) else None
    if (
        isinstance(top, str)
        and bool(top.strip())
        and isinstance(nested, str)
        and bool(nested.strip())
        and top == nested
    ):
        return top
    return None


def _validate_device_report(
    index: int,
    report_path: Optional[Path],
    report: Optional[Mapping[str, Any]],
    apk: Mapping[str, Any],
    checks: Dict[str, Dict[str, Any]],
) -> Dict[str, Any]:
    prefix = f"device[{index}]"
    if report_path is None or report is None:
        _add_check(checks, f"{prefix}.evidence", UNVERIFIED, f"device report[{index}] was not available")
        return {}
    if not _check_schema(report, "device", prefix, checks):
        _add_check(checks, f"{prefix}.evidence", FAIL, f"device report[{index}] failed schema validation")
        return {}

    actual_sha = _normal_hash(apk.get("sha256"))
    declared_apk = _normal_hash(report.get("apk_sha256"))
    installed_sha = _normal_hash(report.get("installed_sha256"))
    sha_ok = actual_sha is not None and declared_apk == actual_sha and installed_sha == actual_sha
    _add_check(
        checks,
        f"{prefix}.apk_sha256",
        PASS if sha_ok else FAIL,
        "report APK and installed APK SHA-256 both match the recomputed APK"
        if sha_ok
        else "apk_sha256 and installed_sha256 must both match the recomputed APK SHA-256",
        {
            "declared_apk_sha256": declared_apk,
            "declared_installed_sha256": installed_sha,
            "actual_sha256": actual_sha,
        },
    )

    serial = _device_serial(report)
    device = report.get("device") if isinstance(report.get("device"), Mapping) else {}
    physical = device.get("physical") is True
    abi = device.get("abi")
    supported_abis = device.get("supported_abis", device.get("supportedAbis", []))
    abi_ok = abi == "arm64-v8a" and (
        not supported_abis
        or (isinstance(supported_abis, list) and "arm64-v8a" in supported_abis)
    )
    model = device.get("model")
    manufacturer = device.get("manufacturer")
    identity_ok = (
        isinstance(serial, str)
        and bool(serial.strip())
        and physical
        and abi_ok
        and isinstance(model, str)
        and bool(model.strip())
        and isinstance(manufacturer, str)
        and bool(manufacturer.strip())
    )
    top_serial = report.get("serial")
    nested_serial = device.get("serial")
    serial_consistency_ok = (
        isinstance(top_serial, str)
        and bool(top_serial.strip())
        and isinstance(nested_serial, str)
        and bool(nested_serial.strip())
        and top_serial == nested_serial
    )
    _add_check(
        checks,
        f"{prefix}.serial_consistency",
        PASS if serial_consistency_ok else FAIL,
        "top-level and nested device serials both exist and agree"
        if serial_consistency_ok
        else "top-level and nested device serials must both exist and agree",
        {"top_level": top_serial, "nested": nested_serial},
    )
    _add_check(
        checks,
        f"{prefix}.identity",
        PASS if identity_ok else FAIL,
        "representative physical ARM64 device identity is complete"
        if identity_ok
        else "serial, physical=true, arm64-v8a ABI, manufacturer, and model are required",
        {
            "serial": serial,
            "physical": physical,
            "abi": abi,
            "supported_abis": supported_abis,
            "manufacturer": manufacturer,
            "model": model,
        },
    )

    ui = report.get("main_activity_ui")
    if not isinstance(ui, Mapping):
        ui = report.get("mainActivityUiSmoke") if isinstance(report.get("mainActivityUiSmoke"), Mapping) else {}
    ui_activity = ui.get("activity", ui.get("component"))
    normalized_activity = _normalize_main_activity_component(ui_activity)
    ui_fields_ok = (
        ui.get("status") == "PASSED"
        and normalized_activity == f"{MAIN_ACTIVITY_PACKAGE}/.MainActivity"
    )
    ui_descriptor = ui.get("evidence") if isinstance(ui.get("evidence"), Mapping) else None
    ui_file_ok = _verify_evidence_descriptor(
        ui_descriptor,
        report_path.parent,
        f"{prefix}.main_activity_ui.evidence_file",
        checks,
        f"device report[{index}] MainActivity UI evidence",
    )
    ui_ok = ui_fields_ok and ui_file_ok
    _add_check(
        checks,
        f"{prefix}.main_activity_ui",
        PASS if ui_ok else FAIL,
        "MainActivity UI PASSED with hashed raw evidence"
        if ui_ok
        else "MainActivity UI requires status=PASSED, component com.muyuchat.mca/.MainActivity, "
        "and hashed raw evidence",
        {
            "status": ui.get("status"),
            "activity": ui_activity,
            "normalized_activity": normalized_activity,
            "evidence_file_verified": ui_file_ok,
        },
    )

    api = report.get("authenticated_local_api")
    if not isinstance(api, Mapping):
        api = (
            report.get("authenticatedLocalApiSmoke")
            if isinstance(report.get("authenticatedLocalApiSmoke"), Mapping)
            else {}
        )
    http_status = api.get("http_status")
    request_id = api.get("request_id")
    endpoint = api.get("endpoint")
    api_fields_ok = (
        api.get("status") == "PASSED"
        and api.get("authenticated") is True
        and _is_int(http_status)
        and 200 <= http_status <= 299
        and isinstance(endpoint, str)
        and endpoint.strip() == LOCAL_API_ENDPOINT
        and isinstance(request_id, str)
        and bool(request_id.strip())
    )
    api_descriptor = api.get("evidence") if isinstance(api.get("evidence"), Mapping) else None
    api_file_ok = _verify_evidence_descriptor(
        api_descriptor,
        report_path.parent,
        f"{prefix}.authenticated_local_api.evidence_file",
        checks,
        f"device report[{index}] authenticated Local API evidence",
    )
    api_ok = api_fields_ok and api_file_ok
    _add_check(
        checks,
        f"{prefix}.authenticated_local_api",
        PASS if api_ok else FAIL,
        "authenticated Local API request PASSED with request ID and hashed raw evidence"
        if api_ok
        else "authenticated Local API requires status=PASSED, authenticated=true, 2xx status, "
        "endpoint=/v1/chat/completions, request_id, and hashed raw evidence",
        {
            "status": api.get("status"),
            "authenticated": api.get("authenticated"),
            "http_status": http_status,
            "endpoint": endpoint,
            "request_id": request_id,
            "evidence_file_verified": api_file_ok,
        },
    )

    crash_count = _nonnegative_int(report.get("crash_count"))
    anr_count = _nonnegative_int(report.get("anr_count"))
    crash_anr_ok = crash_count == 0 and anr_count == 0
    _add_check(
        checks,
        f"{prefix}.crash_anr",
        PASS if crash_anr_ok else FAIL,
        "crash and ANR counts are both zero"
        if crash_anr_ok
        else "crash_count and anr_count must both be integer zero",
        {"crash_count": crash_count, "anr_count": anr_count},
    )

    top_level_ok = (
        report.get("status") == "PASSED" and _empty_json_array(report.get("errors"))
    )
    _add_check(
        checks,
        f"{prefix}.top_level",
        PASS if top_level_ok else FAIL,
        "device report has status=PASSED and no errors"
        if top_level_ok
        else "device report must have status=PASSED and an empty errors array",
        {"status": report.get("status"), "errors": report.get("errors")},
    )

    report_ok = (
        sha_ok
        and serial_consistency_ok
        and identity_ok
        and ui_ok
        and api_ok
        and crash_anr_ok
        and top_level_ok
    )
    _add_check(
        checks,
        f"{prefix}.evidence",
        PASS if report_ok else FAIL,
        "device report is APK-bound and proves MainActivity UI, authenticated Local API, "
        "zero crashes, and zero ANRs"
        if report_ok
        else "device report has incomplete or inconsistent evidence",
        {"serial": serial, "report_type": report.get("report_type")},
    )
    return {
        "serial": serial,
        "model": model,
        "manufacturer": manufacturer,
        "apk_sha256": declared_apk,
        "installed_sha256": installed_sha,
        "physical_arm64": identity_ok,
        "verified": report_ok,
    }


def _validate_device_set(
    candidates: Sequence[Tuple[Optional[Path], Optional[Mapping[str, Any]]]],
    apk: Mapping[str, Any],
    checks: Dict[str, Dict[str, Any]],
    min_devices: int,
) -> Dict[str, Any]:
    if not candidates:
        _add_check(checks, "device.set", UNVERIFIED, "at least one --device-report is required")
        _add_check(
            checks,
            "device.minimum_count",
            UNVERIFIED,
            f"no device reports were supplied; {min_devices} verified physical ARM64 device(s) required",
            {"required": min_devices, "verified_unique_serials": []},
        )
        return {"reports": [], "serials": [], "verified_serials": []}

    devices = [
        _validate_device_report(index, path, report, apk, checks)
        for index, (path, report) in enumerate(candidates)
    ]
    serials = [item.get("serial") for item in devices if item.get("serial")]
    verified_serials = sorted(
        {
            str(item.get("serial"))
            for item in devices
            if item.get("verified") is True and item.get("physical_arm64") is True
        }
    )
    duplicate_serials = sorted({serial for serial in serials if serials.count(serial) > 1})
    _add_check(
        checks,
        "device.unique_serials",
        PASS if not duplicate_serials else FAIL,
        "all device reports have distinct serials"
        if not duplicate_serials
        else f"duplicate device serials: {duplicate_serials}",
        {"serials": serials, "duplicate_serials": duplicate_serials},
    )

    all_physical_arm64 = bool(devices) and all(
        item.get("physical_arm64") is True for item in devices
    )
    _add_check(
        checks,
        "device.all_reports_physical_arm64",
        PASS if all_physical_arm64 else FAIL,
        "every supplied device report identifies a physical ARM64 device"
        if all_physical_arm64
        else "one or more supplied device reports are not physical ARM64 devices",
        {
            "report_count": len(devices),
            "physical_arm64_count": sum(
                1 for item in devices if item.get("physical_arm64") is True
            ),
        },
    )

    actual_sha = _normal_hash(apk.get("sha256"))
    same_sha = bool(devices) and all(
        item.get("apk_sha256") == actual_sha and item.get("installed_sha256") == actual_sha
        for item in devices
    )
    _add_check(
        checks,
        "device.same_apk_sha256",
        PASS if same_sha else FAIL,
        "every supplied device report is bound to the same APK"
        if same_sha
        else "one or more device reports are not bound to the recomputed APK SHA-256",
        {
            "report_count": len(devices),
            "serials": serials,
            "actual_sha256": actual_sha,
        },
    )

    any_representative = any(item.get("physical_arm64") is True for item in devices)
    _add_check(
        checks,
        "device.representative_arm64",
        PASS if any_representative else FAIL,
        "at least one representative physical ARM64 device is present"
        if any_representative
        else "no representative physical ARM64 device report passed",
        {"device_count": len(devices), "serials": serials},
    )

    minimum_count_ok = len(verified_serials) >= min_devices
    _add_check(
        checks,
        "device.minimum_count",
        PASS if minimum_count_ok else FAIL,
        f"{min_devices} verified physical ARM64 device serial(s) are present"
        if minimum_count_ok
        else f"only {len(verified_serials)} verified physical ARM64 device serial(s) are present; "
        f"{min_devices} required",
        {
            "required": min_devices,
            "verified_unique_serials": verified_serials,
            "verified_unique_count": len(verified_serials),
        },
    )
    return {
        "reports": devices,
        "serials": serials,
        "verified_serials": verified_serials,
    }


def validate(
    apk_path: str,
    soak_report_path: Optional[str],
    fault_report_path: Optional[str],
    device_report_paths: Sequence[str],
    min_soak_hours: float,
    min_requests: int,
    expected_apk_sha256: Optional[str],
    min_devices: int = 1,
) -> Dict[str, Any]:
    checks: Dict[str, Dict[str, Any]] = {}
    inputs = {
        "apk": str(_coerce_cli_path(apk_path)),
        "soak_report": soak_report_path,
        "fault_report": fault_report_path,
        "device_reports": list(device_report_paths),
        "min_soak_hours": min_soak_hours,
        "min_requests": min_requests,
        "min_devices": min_devices,
        "expected_apk_sha256": expected_apk_sha256,
    }

    apk_computed = _inspect_apk(apk_path, expected_apk_sha256, checks)

    soak_path, soak_report = _load_json_report(soak_report_path, "soak", checks)
    fault_path, fault_report = _load_json_report(fault_report_path, "fault", checks)
    device_candidates: List[Tuple[Optional[Path], Optional[Mapping[str, Any]]]] = []
    for index, raw_path in enumerate(device_report_paths):
        path, report = _load_json_report(raw_path, f"device[{index}]", checks)
        device_candidates.append((path, report))

    soak_computed = _validate_soak(
        soak_path,
        soak_report,
        apk_computed,
        min_soak_hours,
        min_requests,
        checks,
    )
    device_computed = _validate_device_set(device_candidates, apk_computed, checks, min_devices)
    fault_computed = _validate_fault(
        fault_path,
        fault_report,
        apk_computed,
        checks,
        device_computed.get("verified_serials", []),
    )

    actual_sha = _normal_hash(apk_computed.get("sha256"))
    report_hashes = [
        _normal_hash((soak_report or {}).get("apk_sha256")),
        _normal_hash((fault_report or {}).get("apk_sha256")),
        *[
            _normal_hash((report or {}).get("apk_sha256"))
            for _, report in device_candidates
        ],
    ]
    supplied_hashes = [value for value in report_hashes if value is not None]
    consistent = bool(supplied_hashes) and all(value == actual_sha for value in supplied_hashes)
    _add_check(
        checks,
        "evidence.consistent_apk_sha256",
        PASS if consistent else FAIL,
        "all supplied reports are bound to the same recomputed APK SHA-256"
        if consistent
        else "one or more supplied reports do not share the recomputed APK SHA-256",
        {"actual_sha256": actual_sha, "report_sha256_values": report_hashes},
    )

    status_counts = {PASS: 0, FAIL: 0, UNVERIFIED: 0}
    for check in checks.values():
        status_counts[check["status"]] += 1
    if status_counts[FAIL]:
        overall = FAIL
        exit_code = 1
    elif status_counts[UNVERIFIED]:
        overall = UNVERIFIED
        exit_code = 2
    else:
        overall = PASS
        exit_code = 0

    failed = sorted(name for name, check in checks.items() if check["status"] == FAIL)
    unverified = sorted(name for name, check in checks.items() if check["status"] == UNVERIFIED)
    reasons: List[str] = []
    if failed:
        reasons.append(f"{len(failed)} check(s) failed")
    if unverified:
        reasons.append(f"{len(unverified)} check(s) remain unverified")
    if not reasons:
        reasons.append("all checks passed")

    return {
        "schema": VERIFIED_SCHEMA,
        "generated_at": _utc_now(),
        "inputs": inputs,
        "computed": {
            "apk": apk_computed,
            "soak": soak_computed,
            "fault": fault_computed,
            "devices": device_computed,
        },
        "checks": checks,
        "summary": {
            "status": overall,
            "exit_code": exit_code,
            "counts": status_counts,
            "failed_checks": failed,
            "unverified_checks": unverified,
            "reasons": reasons,
        },
    }


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Fail-closed validator for MCA APK-bound soak, fault, and device evidence."
    )
    parser.add_argument("--apk", required=True, help="APK under review")
    parser.add_argument("--soak-report", help="APK-bound soak report JSON")
    parser.add_argument("--fault-report", help="APK-bound production/device fault-injection report JSON")
    parser.add_argument(
        "--device-report",
        action="append",
        default=[],
        help="APK-bound physical-device report JSON; repeat for a second representative device",
    )
    parser.add_argument("--min-soak-hours", type=float, default=24.0)
    parser.add_argument("--min-requests", type=int, default=1000)
    parser.add_argument(
        "--min-devices",
        type=int,
        default=1,
        help="minimum number of distinct fully verified physical ARM64 device serials (default: 1)",
    )
    parser.add_argument("--expected-apk-sha256")
    parser.add_argument("--out", help="also write the verification JSON to this path")
    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)
    if not math.isfinite(args.min_soak_hours) or args.min_soak_hours <= 0:
        parser.error("--min-soak-hours must be a finite number greater than zero")
    if args.min_requests < 0:
        parser.error("--min-requests must be non-negative")
    if args.min_devices < 1:
        parser.error("--min-devices must be at least one")

    result = validate(
        apk_path=args.apk,
        soak_report_path=args.soak_report,
        fault_report_path=args.fault_report,
        device_report_paths=args.device_report,
        min_soak_hours=args.min_soak_hours,
        min_requests=args.min_requests,
        expected_apk_sha256=args.expected_apk_sha256,
        min_devices=args.min_devices,
    )

    if args.out:
        out_path = _coerce_cli_path(args.out).expanduser()
        try:
            out_path.parent.mkdir(parents=True, exist_ok=True)
            out_path.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        except OSError as exc:
            _add_check(
                result["checks"],
                "output.write",
                FAIL,
                f"could not write --out JSON: {exc}",
                {"path": str(out_path)},
            )
            counts = {PASS: 0, FAIL: 0, UNVERIFIED: 0}
            for check in result["checks"].values():
                counts[check["status"]] += 1
            result["summary"]["status"] = FAIL
            result["summary"]["exit_code"] = 1
            result["summary"]["counts"] = counts
            result["summary"]["failed_checks"] = sorted(
                name for name, check in result["checks"].items() if check["status"] == FAIL
            )
            result["summary"]["unverified_checks"] = sorted(
                name for name, check in result["checks"].items() if check["status"] == UNVERIFIED
            )
            result["summary"]["reasons"] = ["failed to write --out JSON"]

    print(json.dumps(result, indent=2, ensure_ascii=False))
    return int(result["summary"]["exit_code"])


if __name__ == "__main__":
    sys.exit(main())
