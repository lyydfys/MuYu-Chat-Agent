#!/usr/bin/env python3
"""Read-only MediaTek Neuron runtime ABI probe for the S4 adapter.

It never loads a model, restores a DLA or runs inference. On a MediaTek device
it pulls the runtime libraries and records their symbols/hashes; otherwise it
reports NOT_RUN. See docs/MCA_DIMENSITY_NPU_S4_PREREQUISITES_2026-10-31.md.
"""

from __future__ import annotations

import argparse
import datetime as dt
import glob
import hashlib
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

RUNTIME_LIBRARIES = ("libneuronusdk.so", "neuron_sys_util.mtk.so", "libNeuronAdapter.so")
SEARCH_ROOTS = ("/vendor/lib64", "/system/lib64", "/vendor/lib", "/system/lib")


def log(message: str) -> None:
    print(message, flush=True)


def run(command: list[str], timeout: int = 60) -> tuple[int, str, str]:
    try:
        completed = subprocess.run(
            command,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
        )
        return completed.returncode, completed.stdout, completed.stderr
    except FileNotFoundError:
        return 127, "", f"not found: {command[0]}"
    except subprocess.TimeoutExpired:
        return 124, "", f"timeout: {' '.join(command)}"


def resolve_adb(explicit: str | None) -> str | None:
    candidates = [
        explicit,
        os.environ.get("ADB"),
        shutil.which("adb"),
        r"D:\model\android-sdk\platform-tools\adb.exe",
        r"C:\Users\%s\AppData\Local\Android\Sdk\platform-tools\adb.exe" % os.environ.get("USERNAME", ""),
    ]
    for candidate in candidates:
        if candidate and Path(candidate).is_file():
            return str(Path(candidate))
    return None


def resolve_readelf(explicit: str | None) -> str | None:
    candidates = [explicit, shutil.which("llvm-readelf"), shutil.which("readelf")]
    for root in (os.environ.get("ANDROID_NDK_HOME"), os.environ.get("NDK_HOME")):
        if root:
            candidates.append(str(Path(root) / "toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe"))
    for pattern in (
        r"D:\model\android-sdk\ndk\*\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe",
        r"C:\Users\*\AppData\Local\Android\Sdk\ndk\*\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe",
    ):
        candidates.extend(sorted(glob.glob(pattern), reverse=True))
    for candidate in candidates:
        if candidate and Path(candidate).is_file():
            return str(Path(candidate))
    return None


def adb_command(adb: str, serial: str, *args: str) -> list[str]:
    return [adb, "-s", serial, *args]


def getprop(adb: str, serial: str, name: str) -> str:
    code, out, _ = run(adb_command(adb, serial, "shell", "getprop", name))
    return out.strip() if code == 0 else ""


def detect_mediatek(props: dict[str, str]) -> bool:
    platform = props.get("ro.board.platform", "").lower()
    manufacturer = props.get("ro.soc.manufacturer", "").lower()
    model = props.get("ro.soc.model", "").lower()
    product = props.get("ro.product.manufacturer", "").lower()
    return (
        platform.startswith("mt")
        or "mediatek" in manufacturer
        or "mediatek" in product
        or "dimensity" in model
    )


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def find_libraries(adb: str, serial: str) -> dict[str, list[str]]:
    found: dict[str, list[str]] = {}
    for name in RUNTIME_LIBRARIES:
        paths: set[str] = set()
        for root in SEARCH_ROOTS:
            code, out, _ = run(adb_command(adb, serial, "shell", "ls", f"{root}/{name}"))
            if code == 0:
                for line in out.splitlines():
                    line = line.strip()
                    if line.endswith(name):
                        paths.add(line)
        if not paths:
            code, out, _ = run(
                adb_command(adb, serial, "shell", "find", "/vendor", "/system", "-name", name, "2>/dev/null"),
                timeout=120,
            )
            if code == 0:
                paths.update(line.strip() for line in out.splitlines() if line.strip().endswith(name))
        found[name] = sorted(paths)
    return found


def pull(adb: str, serial: str, remote: str, destination: Path) -> bool:
    destination.parent.mkdir(parents=True, exist_ok=True)
    code, _, error = run(adb_command(adb, serial, "pull", remote, str(destination)), timeout=180)
    if code != 0:
        log(f"  pull failed: {remote}: {error.strip()}")
        return False
    return destination.is_file()


def extract_neuron_symbols(readelf: str | None, library: Path, output: Path) -> list[str]:
    if readelf is None:
        return []
    code, out, error = run([readelf, "-Ws", str(library)], timeout=120)
    if code != 0:
        log(f"  readelf failed: {library.name}: {error.strip()}")
        return []
    output.write_text(out, encoding="utf-8")
    symbols = sorted({line.split()[-1] for line in out.splitlines() if "Neuron" in line and line.split()})
    return [symbol for symbol in symbols if symbol.startswith(("Neuron", "neuron"))]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="adb serial; defaults to the first online device")
    parser.add_argument("--adb", help="path to adb")
    parser.add_argument("--readelf", help="path to llvm-readelf/readelf")
    parser.add_argument("--out-dir", help="evidence directory (default: artifacts/device/mediatek-neuron-<utc>)")
    parser.add_argument("--force", action="store_true", help="probe even when the SoC does not look MediaTek")
    args = parser.parse_args()

    adb = resolve_adb(args.adb)
    if adb is None:
        log("NOT_RUN: adb was not found. Pass --adb or set ADB.")
        return 2

    code, out, error = run([adb, "devices", "-l"])
    if code != 0:
        log(f"NOT_RUN: adb devices failed: {error.strip()}")
        return 2
    devices = []
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            devices.append(parts[0])
    if not devices:
        log("NOT_RUN: no online adb device.")
        return 0
    serial = args.serial or devices[0]
    if serial not in devices:
        log(f"NOT_RUN: device '{serial}' is not online. Online: {', '.join(devices)}")
        return 0

    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out_dir = Path(args.out_dir or f"artifacts/device/mediatek-neuron-{stamp}")
    out_dir.mkdir(parents=True, exist_ok=True)

    prop_names = (
        "ro.board.platform",
        "ro.soc.manufacturer",
        "ro.soc.model",
        "ro.product.manufacturer",
        "ro.product.model",
        "ro.build.version.release",
        "ro.vendor.build.version.sdk",
        "ro.product.cpu.abi",
    )
    props = {name: getprop(adb, serial, name) for name in prop_names}
    mediatek = detect_mediatek(props)
    result: dict = {
        "probeVersion": 1,
        "utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "serial": serial,
        "deviceExecution": "NOT_RUN",
        "npuLoad": "NOT_RUN",
        "device": props,
        "mediatekDetected": mediatek,
        "runtimeLibraries": [],
        "neuronSymbols": {},
        "notes": [
            "Read-only probe: no model was loaded, no DLA was restored, no inference ran.",
            "A MediaTek device proves nothing until a real restore/run/close succeeds.",
        ],
    }

    if not mediatek and not args.force:
        result["status"] = "NOT_RUN_MEDIA_DEVICE_ABSENT"
        log(f"NOT_RUN: '{serial}' does not look MediaTek (platform={props.get('ro.board.platform')!r}).")
        (out_dir / "probe.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
        log(f"evidence: {out_dir / 'probe.json'}")
        return 0

    readelf = resolve_readelf(args.readelf)
    result["readelf"] = readelf
    libraries = find_libraries(adb, serial)
    pulled_any = False
    for name, remote_paths in libraries.items():
        for index, remote in enumerate(remote_paths):
            local = out_dir / "libs" / f"{index}-{Path(remote).name}"
            if not pull(adb, serial, remote, local):
                continue
            pulled_any = True
            symbols = extract_neuron_symbols(readelf, local, out_dir / "symbols" / f"{local.name}.readelf.txt")
            result["runtimeLibraries"].append(
                {
                    "remotePath": remote,
                    "localPath": str(local),
                    "bytes": local.stat().st_size,
                    "sha256": sha256(local),
                }
            )
            result["neuronSymbols"][remote] = symbols

    if not any(libraries.values()):
        result["status"] = "INCOMPLETE_RUNTIME_LIBRARIES_NOT_FOUND"
        log("INCOMPLETE: none of the Neuron runtime libraries were found on the device.")
    elif not pulled_any:
        result["status"] = "INCOMPLETE_PULL_FAILED"
        log("INCOMPLETE: libraries were located but could not be pulled.")
    else:
        result["status"] = "CAPTURED_ABI"
        log("CAPTURED_ABI: runtime libraries and symbol tables were recorded; execution is still NOT_RUN.")
    (out_dir / "probe.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    log(f"evidence: {out_dir / 'probe.json'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
