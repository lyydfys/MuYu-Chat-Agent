"""Capture MCA screen states from a connected device over adb.

Every capture is a plain device screencap: nothing is composited or retouched.
The script only drives the UI (taps/swipes/back), waits, and pulls the PNG.

Usage:
  python docs/website/scripts/device-capture.py --serial f9d98aa3 --shot name
  python docs/website/scripts/device-capture.py --serial f9d98aa3 --tap 120 2400 --shot name
  python docs/website/scripts/device-capture.py --serial f9d98aa3 --swipe 700 2200 700 900
"""

import argparse
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / "docs" / "website" / "assets" / "device"
ADB = Path(r"D:\model\android-sdk\platform-tools\adb.exe")

PACKAGE = "com.muyuchat.mca"
ACTIVITY = f"{PACKAGE}/.MainActivity"


def adb(serial: str, *args: str, check: bool = True) -> str:
    cmd = [str(ADB), "-s", serial, *args]
    proc = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if check and proc.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {proc.stderr.strip()}")
    return proc.stdout


def launch(serial: str) -> None:
    adb(serial, "shell", "am", "force-stop", PACKAGE, check=False)
    adb(serial, "shell", "am", "start", "-W", "-n", ACTIVITY, check=False)


def screenshot(serial: str, name: str) -> Path:
    OUT.mkdir(parents=True, exist_ok=True)
    remote = f"/sdcard/_mca_shot_{name}.png"
    adb(serial, "shell", "screencap", "-p", remote)
    target = OUT / f"{name}.png"
    adb(serial, "pull", remote, str(target))
    adb(serial, "shell", "rm", "-f", remote, check=False)
    return target


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--shot", required=True, help="output file stem")
    ap.add_argument("--relaunch", action="store_true", help="force-stop and start MCA first")
    ap.add_argument("--tap", nargs=2, type=int, metavar=("X", "Y"))
    ap.add_argument("--swipe", nargs=4, type=int, metavar=("X1", "Y1", "X2", "Y2"))
    ap.add_argument("--wait", type=float, default=0.6, help="seconds to wait before capture")
    args = ap.parse_args()

    if args.relaunch:
        launch(args.serial)
        time.sleep(3.0)
    if args.tap:
        adb(args.serial, "shell", "input", "tap", str(args.tap[0]), str(args.tap[1]))
        time.sleep(args.wait)
    if args.swipe:
        x1, y1, x2, y2 = args.swipe
        adb(args.serial, "shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), "400")
        time.sleep(args.wait)

    path = screenshot(args.serial, args.shot)
    print(f"{path}  {path.stat().st_size/1024:.1f} KB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
