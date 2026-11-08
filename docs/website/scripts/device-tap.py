"""Tap a UI node found by its label, using a fresh uiautomator dump each time.

Fixed coordinates go stale the moment the layout scrolls or the IME appears. This script
re-dumps the tree, matches a node by text / content-desc (exact or substring), and taps its
centre.

Usage:
  python docs/website/scripts/device-tap.py --serial f9d98aa3 --text 图片 --shot after-image
  python docs/website/scripts/device-tap.py --serial f9d98aa3 --text DSH --exact
"""

import argparse
import re
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
TMP = ROOT / ".dsh-tmp" / "shots"
ADB = Path(r"D:\model\android-sdk\platform-tools\adb.exe")
OUT = ROOT / "docs" / "website" / "assets" / "device"

NODE_RE = re.compile(r"<node[^>]*>")
ATTR_RE = re.compile(r'([a-z-]+)="([^"]*)"')
BOUNDS_RE = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def adb(serial: str, *args: str) -> str:
    proc = subprocess.run([str(ADB), "-s", serial, *args], capture_output=True, text=True,
                          encoding="utf-8", errors="replace")
    return proc.stdout


def dump(serial: str) -> list[dict]:
    TMP.mkdir(parents=True, exist_ok=True)
    remote = "/sdcard/_mca_tap.xml"
    adb(serial, "shell", "uiautomator", "dump", remote)
    local = TMP / "tap.xml"
    adb(serial, "pull", remote, str(local))
    adb(serial, "shell", "rm", "-f", remote)
    nodes = []
    for raw in NODE_RE.findall(local.read_text(encoding="utf-8", errors="replace")):
        attrs = dict(ATTR_RE.findall(raw))
        label = (attrs.get("text") or "").strip() or (attrs.get("content-desc") or "").strip()
        m = BOUNDS_RE.search(attrs.get("bounds", ""))
        if not label or not m:
            continue
        x1, y1, x2, y2 = (int(g) for g in m.groups())
        nodes.append({"label": label, "cx": (x1 + x2) // 2, "cy": (y1 + y2) // 2,
                      "w": x2 - x1, "h": y2 - y1})
    return nodes


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--text", required=True)
    ap.add_argument("--exact", action="store_true")
    ap.add_argument("--shot", default="")
    ap.add_argument("--wait", type=float, default=1.6)
    ap.add_argument("--retries", type=int, default=2)
    args = ap.parse_args()

    for attempt in range(args.retries + 1):
        nodes = dump(args.serial)
        if args.exact:
            matches = [n for n in nodes if n["label"] == args.text]
        else:
            matches = [n for n in nodes if args.text in n["label"]]
        if matches:
            target = min(matches, key=lambda n: (n["w"] * n["h"]))
            print(f"tap {args.text!r} -> {target['label'][:50]!r} @ {target['cx']},{target['cy']}")
            adb(args.serial, "shell", "input", "tap", str(target["cx"]), str(target["cy"]))
            time.sleep(args.wait)
            break
        print(f"attempt {attempt}: no node matching {args.text!r}")
        time.sleep(0.8)
    else:
        print("FAILED: label never appeared")
        return 1

    if args.shot:
        OUT.mkdir(parents=True, exist_ok=True)
        remote = f"/sdcard/_mca_{args.shot}.png"
        adb(args.serial, "shell", "screencap", "-p", remote)
        target_path = OUT / f"{args.shot}.png"
        adb(args.serial, "pull", remote, str(target_path))
        adb(args.serial, "shell", "rm", "-f", remote)
        print(f"{target_path}  {target_path.stat().st_size/1024:.1f} KB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
