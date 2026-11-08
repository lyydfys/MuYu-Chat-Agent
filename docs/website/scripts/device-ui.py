"""Dump the MCA UI tree from a connected device and list tappable labelled nodes.

Usage:
  python docs/website/scripts/device-ui.py --serial f9d98aa3
  python docs/website/scripts/device-ui.py --serial f9d98aa3 --grep 视频
"""

import argparse
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
TMP = ROOT / ".dsh-tmp" / "shots"
ADB = Path(r"D:\model\android-sdk\platform-tools\adb.exe")

NODE_RE = re.compile(r"<node[^>]*>")
ATTR_RE = re.compile(r'([a-z-]+)="([^"]*)"')
BOUNDS_RE = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def adb(serial: str, *args: str) -> str:
    proc = subprocess.run([str(ADB), "-s", serial, *args], capture_output=True, text=True,
                          encoding="utf-8", errors="replace")
    return proc.stdout


def centre(bounds: str) -> tuple[int, int]:
    m = BOUNDS_RE.search(bounds or "")
    if not m:
        return (0, 0)
    x1, y1, x2, y2 = (int(g) for g in m.groups())
    return ((x1 + x2) // 2, (y1 + y2) // 2)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--grep", default="")
    ap.add_argument("--all", action="store_true", help="also print unlabelled clickable nodes")
    args = ap.parse_args()

    TMP.mkdir(parents=True, exist_ok=True)
    remote = "/sdcard/_mca_ui.xml"
    adb(args.serial, "shell", "uiautomator", "dump", remote)
    local = TMP / "ui.xml"
    adb(args.serial, "pull", remote, str(local))
    adb(args.serial, "shell", "rm", "-f", remote)

    text = local.read_text(encoding="utf-8", errors="replace")
    seen = 0
    for raw in NODE_RE.findall(text):
        attrs = dict(ATTR_RE.findall(raw))
        label = (attrs.get("text") or "").strip() or (attrs.get("content-desc") or "").strip()
        clickable = attrs.get("clickable") == "true"
        if not label and not (args.all and clickable):
            continue
        if args.grep and args.grep not in label:
            continue
        cx, cy = centre(attrs.get("bounds", ""))
        print(f"{cx:>5},{cy:<5}  click={str(clickable):5}  {label[:60]}")
        seen += 1
    print(f"--- {seen} node(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
