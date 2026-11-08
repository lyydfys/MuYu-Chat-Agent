"""Poll the Wan component download on the device and report progress.

Usage:
  python docs/website/scripts/watch-download.py --serial f9d98aa3 --until-done
"""

import argparse
import re
import subprocess
import sys
import time
from pathlib import Path

ADB = Path(r"D:\model\android-sdk\platform-tools\adb.exe")
TMP = Path(r"E:\MAC\.dsh-tmp\shots")
NODE_RE = re.compile(r"<node[^>]*>")
ATTR_RE = re.compile(r'([a-z-]+)="([^"]*)"')


def adb(serial: str, *args: str) -> str:
    proc = subprocess.run([str(ADB), "-s", serial, *args], capture_output=True, text=True,
                          encoding="utf-8", errors="replace")
    return proc.stdout


def labels(serial: str) -> list[str]:
    remote = "/sdcard/_mca_watch.xml"
    adb(serial, "shell", "uiautomator", "dump", remote)
    local = TMP / "watch.xml"
    adb(serial, "pull", remote, str(local))
    adb(serial, "shell", "rm", "-f", remote)
    out = []
    for raw in NODE_RE.findall(local.read_text(encoding="utf-8", errors="replace")):
        attrs = dict(ATTR_RE.findall(raw))
        text = (attrs.get("text") or "").strip() or (attrs.get("content-desc") or "").strip()
        if text:
            out.append(text)
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--until-done", action="store_true")
    ap.add_argument("--every", type=int, default=30)
    ap.add_argument("--max-minutes", type=int, default=60)
    args = ap.parse_args()

    deadline = time.time() + args.max_minutes * 60
    last = ""
    while time.time() < deadline:
        texts = labels(args.serial)
        progress = [t for t in texts if "GB" in t and ("/" in t or "已下载" in t)]
        state = [t for t in texts if "正在下载" in t or "组件" in t or "缺失" in t]
        marker = " | ".join(progress[:2]) or "no progress line"
        if marker != last:
            print(f"[{time.strftime('%H:%M:%S')}] {marker}", flush=True)
            last = marker
        done = any("全部就绪" in t or "校验通过" in t and "缺失" not in t for t in texts)
        no_download = not any("正在下载" in t for t in texts)
        if args.until_done and (done or (no_download and progress and "7.9 GB" in " ".join(progress) and not any("717" in p for p in progress))):
            if done or no_download:
                print("download appears finished")
                return 0
        time.sleep(args.every)
    print("timeout waiting for download")
    return 1


if __name__ == "__main__":
    sys.exit(main())
