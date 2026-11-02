#!/usr/bin/env python3
"""Drive the S4 Neuron smoke on an attached device.

Builds the input kit, pushes it (and optionally the 447 MB DLA) to the test
app's external files dir, installs the self-contained androidTest APK only when
--install is given, runs MediaTekNeuronSmokeTest and pulls report.json.

The script never installs or runs anything by default; it refuses restore mode
on a non-MediaTek device unless --force is given.
"""

from __future__ import annotations

import argparse
import json
import os
import pathlib
import shutil
import subprocess
import sys

PACKAGE = "com.muyuchat.core.mediatek.test"
RUNNER = f"{PACKAGE}/androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS = "com.muyuchat.core.mediatek.MediaTekNeuronSmokeTest"
DEFAULT_APK = "core/mediatek-native/build/outputs/apk/androidTest/debug/mediatek-native-debug-androidTest.apk"
DEFAULT_REMOTE = f"/sdcard/Android/data/{PACKAGE}/files/mca-neuron-smoke"
BUILD_KIT = "artifacts/architecture/2026-10-31-mtkpaint-rea/reference-input-pack-build.txt"
DLA_NAME = "ditc1c_w8_w16L1024s4.dla"
VAE_DLA_NAME = "vaedec_64.dla"


def log(message: str) -> None:
    print(message, flush=True)


def run(command: list[str], timeout: int = 600) -> tuple[int, str, str]:
    try:
        completed = subprocess.run(
            command, capture_output=True, text=True, encoding="utf-8",
            errors="replace", timeout=timeout,
        )
        return completed.returncode, completed.stdout, completed.stderr
    except FileNotFoundError:
        return 127, "", f"not found: {command[0]}"
    except subprocess.TimeoutExpired:
        return 124, "", f"timeout: {' '.join(command)}"


def resolve_adb(explicit: str | None) -> str | None:
    candidates = [
        explicit, os.environ.get("ADB"), shutil.which("adb"),
        r"D:\model\android-sdk\platform-tools\adb.exe",
        r"C:\Users\%s\AppData\Local\Android\Sdk\platform-tools\adb.exe" % os.environ.get("USERNAME", ""),
    ]
    for candidate in candidates:
        if candidate and pathlib.Path(candidate).is_file():
            return str(pathlib.Path(candidate))
    return None


def getprop(adb: str, serial: str, name: str) -> str:
    code, out, _ = run([adb, "-s", serial, "shell", "getprop", name], timeout=30)
    return out.strip() if code == 0 else ""


def is_mediatek(props: dict[str, str]) -> bool:
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


def first_device(adb: str, serial: str | None) -> str | None:
    code, out, error = run([adb, "devices", "-l"], timeout=30)
    if code != 0:
        log(f"NOT_RUN: adb devices failed: {error.strip()}")
        return None
    devices = [line.split()[0] for line in out.splitlines()[1:] if len(line.split()) >= 2 and line.split()[1] == "device"]
    if not devices:
        log("NOT_RUN: no online adb device.")
        return None
    if serial:
        if serial not in devices:
            log(f"NOT_RUN: {serial} is not online. Online: {', '.join(devices)}")
            return None
        return serial
    return devices[0]


def build_kit(kit_dir: pathlib.Path) -> bool:
    command = [sys.executable, BUILD_KIT, "--write-pack", str(kit_dir)]
    code, out, error = run(command, timeout=300)
    if code != 0:
        log(f"kit build failed: {error.strip() or out.strip()}")
        return False
    for name in ("input-pack.bin", "hidden-int16.bin", "smoke-config.json"):
        if not (kit_dir / name).is_file():
            log(f"kit build did not produce {name}")
            return False
    log(f"kit: {kit_dir}")
    return True


def build_chain_kit(kit_dir: pathlib.Path, assets: str, run_steps: int, dynamic_text: bool = False, prompt: str = "a cat") -> bool:
    command = [
        sys.executable, "tools/mediatek/build_chain_kit.py",
        "--assets", assets, "--out", str(kit_dir), "--run-steps", str(run_steps),
    ]
    if dynamic_text:
        command += ["--dynamic-text", "--prompt", prompt]
    code, out, error = run(command, timeout=600)
    if code != 0:
        log(f"chain kit build failed: {error.strip() or out.strip()}")
        return False
    required = ["chain-config.json", "chain-manifest.json", "latent.f32"]
    if not dynamic_text:
        required.append("ctx.bin")
    for name in required:
        if not (kit_dir / name).is_file():
            log(f"chain kit did not produce {name}")
            return False
    log(f"chain kit: {kit_dir} (runSteps={run_steps}, dynamicText={dynamic_text})")
    return True


def build_apk() -> bool:
    command = [
        "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
        "tools/_build_locked.ps1", "-Tasks", ":core:mediatek-native:assembleDebugAndroidTest",
        "-LogPath", "build/s4-core-androidtest-apk.log",
    ]
    code, out, error = run(command, timeout=1200)
    if code != 0:
        log(f"APK build failed: {error.strip() or out.strip()}")
        return False
    return True


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("probe", "restore", "vae", "chain"), default="restore")
    parser.add_argument("--run-steps", type=int, default=1, help="chain mode: partial steps to execute")
    parser.add_argument("--dynamic-text", action="store_true", help="chain mode: DYNAMIC_TEXT bundle with on-device Qwen3 encoding")
    parser.add_argument("--prompt", default="a cat", help="chain mode: prompt used for DYNAMIC_TEXT")
    parser.add_argument("--serial")
    parser.add_argument("--adb")
    parser.add_argument("--assets", default=r"E:\mtk-npu-reference-assets")
    parser.add_argument("--kit-dir", default="artifacts/device/mca-neuron-smoke")
    parser.add_argument("--remote-dir", default=DEFAULT_REMOTE)
    parser.add_argument("--apk", default=DEFAULT_APK)
    parser.add_argument("--install", action="store_true", help="install the androidTest APK before running")
    parser.add_argument("--build", action="store_true", help="build the androidTest APK first")
    parser.add_argument("--skip-kit", action="store_true")
    parser.add_argument("--skip-push", action="store_true")
    parser.add_argument("--force", action="store_true", help="run restore mode even when the SoC is not MediaTek")
    args = parser.parse_args()

    adb = resolve_adb(args.adb)
    if adb is None:
        log("NOT_RUN: adb was not found. Pass --adb or set ADB.")
        return 2
    serial = first_device(adb, args.serial)
    if serial is None:
        return 0

    props = {name: getprop(adb, serial, name) for name in (
        "ro.board.platform", "ro.soc.manufacturer", "ro.soc.model",
        "ro.product.manufacturer", "ro.product.model", "ro.build.version.release",
    )}
    mediatek = is_mediatek(props)
    log(f"device: {serial} platform={props.get('ro.board.platform')} soc={props.get('ro.soc.model')} mediatek={mediatek}")
    if args.mode in ("restore", "vae", "chain") and not mediatek and not args.force:
        log("NOT_RUN: restore/VAE/chain mode requires a MediaTek device (use --force only to observe the BLOCKED path).")
        return 0

    kit_dir = pathlib.Path(args.kit_dir)
    if args.mode == "restore" and not args.skip_kit and not build_kit(kit_dir):
        return 2
    if args.mode == "chain" and not args.skip_kit and not build_chain_kit(kit_dir, args.assets, args.run_steps, args.dynamic_text, args.prompt):
        return 2

    apk = pathlib.Path(args.apk)
    if args.build and not build_apk():
        return 2
    if not apk.is_file():
        log(f"test APK not found: {apk}. Run: powershell -File tools/_build_locked.ps1 -Tasks :core:mediatek-native:assembleDebugAndroidTest")
        return 2
    if args.install:
        code, out, error = run([adb, "-s", serial, "install", "-r", str(apk)], timeout=300)
        if code != 0:
            log(f"install failed: {error.strip() or out.strip()}")
            return 2
        log("installed androidTest APK")

    if not args.skip_push and args.mode != "probe":
        code, _, error = run([adb, "-s", serial, "shell", "mkdir", "-p", args.remote_dir], timeout=30)
        if code != 0:
            log(f"cannot create {args.remote_dir}: {error.strip()}")
            return 2
        if args.mode == "restore":
            for name in ("input-pack.bin", "hidden-int16.bin", "smoke-config.json"):
                source = kit_dir / name
                code, _, error = run([adb, "-s", serial, "push", str(source), args.remote_dir + "/"], timeout=180)
                if code != 0:
                    log(f"push {name} failed: {error.strip()}")
                    return 2
            dla_names = [DLA_NAME]
        elif args.mode == "chain":
            config = json.loads((kit_dir / "chain-config.json").read_text(encoding="utf-8"))
            for source in sorted(kit_dir.iterdir()):
                if not source.is_file():
                    continue
                code, _, error = run([adb, "-s", serial, "push", str(source), args.remote_dir + "/"], timeout=300)
                if code != 0:
                    log(f"push {source.name} failed: {error.strip()}")
                    return 2
            dla_names = [entry["dlaFile"] for entry in config["chunks"]] + [config["vae"]["dlaFile"]]
            extra_assets = [config["modulationFile"]]
            if config.get("conditioningMode") == "DYNAMIC_TEXT":
                extra_assets += [config["textWeightsFile"], config["textTokenizerFile"]]
            for name in extra_assets:
                source = pathlib.Path(args.assets) / name
                if not source.is_file():
                    log(f"chain kit asset not found: {source}")
                    return 2
                log(f"pushing {name} ({source.stat().st_size} bytes)")
                code, _, error = run([adb, "-s", serial, "push", str(source), args.remote_dir + "/"], timeout=1800)
                if code != 0:
                    log(f"push {name} failed: {error.strip()}")
                    return 2
        else:
            dla_names = [VAE_DLA_NAME]
        for dla_name in dla_names:
            dla = pathlib.Path(args.assets) / dla_name
            if not dla.is_file():
                log(f"DLA not found: {dla}")
                return 2
            log(f"pushing {dla_name} ({dla.stat().st_size} bytes); this can take a while")
            code, _, error = run([adb, "-s", serial, "push", str(dla), args.remote_dir + "/"], timeout=1800)
            if code != 0:
                log(f"DLA push failed: {error.strip()}")
                return 2

    method = {
        "restore": "restoreBindRunClose",
        "vae": "vaeOnly",
        "probe": "probeOnly",
        "chain": "fullChain",
    }[args.mode]
    log(f"running {TEST_CLASS}#{method}")
    code, out, error = run(
        [adb, "-s", serial, "shell", "am", "instrument", "-w",
         "-e", "class", f"{TEST_CLASS}#{method}",
         "-e", "mcaNeuronSmokeDir", args.remote_dir,
         RUNNER],
        timeout=1800,
    )
    print(out.strip())
    if error.strip():
        print(error.strip(), file=sys.stderr)

    kit_dir.mkdir(parents=True, exist_ok=True)
    report_local = kit_dir / "report.json"
    code_pull, _, error_pull = run(
        [adb, "-s", serial, "pull", f"{args.remote_dir}/report.json", str(report_local)], timeout=60
    )
    if code_pull != 0:
        log(f"report pull failed: {error_pull.strip()}")
        return 1 if code != 0 else 2
    report = json.loads(report_local.read_text(encoding="utf-8"))
    print(json.dumps(report, ensure_ascii=False, indent=2))
    actx_name = (report.get("textEncoding") or {}).get("actxFile")
    if actx_name:
        code_actx, _, error_actx = run(
            [adb, "-s", serial, "pull", f"{args.remote_dir}/{actx_name}", str(kit_dir / actx_name)],
            timeout=120,
        )
        if code_actx != 0:
            log(f"ACTX pull failed: {error_actx.strip()}")
            return 1
        log(f"pulled {actx_name}: {kit_dir / actx_name}")
    latent_name = (report.get("baseline") or {}).get("finalLatentFile")
    if latent_name:
        code_latent, _, error_latent = run(
            [adb, "-s", serial, "pull", f"{args.remote_dir}/{latent_name}", str(kit_dir / latent_name)],
            timeout=120,
        )
        if code_latent != 0:
            log(f"final latent pull failed: {error_latent.strip()}")
            return 1
        log(f"pulled {latent_name}: {kit_dir / latent_name}")
    status = report.get("status")
    if status == "COMPLETED" and (report.get("baseline") or {}).get("schema"):
        validator = pathlib.Path(__file__).with_name("validate_chain_report.py")
        if not validator.is_file():
            log(f"evidence validator is missing: {validator}")
            return 1
        code_val, out_val, error_val = run(
            [sys.executable, str(validator), str(report_local)], timeout=120
        )
        print(out_val.strip())
        if error_val.strip():
            print(error_val.strip(), file=sys.stderr)
        if code_val != 0:
            log("chain report evidence validation failed; keep report, tensors and logcat")
            return 1
    if status == "COMPLETED":
        return 0
    if status == "BLOCKED" and args.mode == "probe":
        log("probe recorded BLOCKED; an actual MediaTek runtime is required for a positive result.")
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
