"""Turn the adb device captures into site assets.

Only crops + re-encode. Each job names the capture it came from so the page's figure
captions stay checkable.

Every capture named below was taken on the physical tablet 98a37aa7
(Xiaomi Pad 8 Pro / 25091RP04C, 2136x3200, MCA 0.4.1) or on the phone f9d98aa3
(Xiaomi 2304FPN6DC, 1440x3200, MCA 0.4.2) unless the comment says otherwise.

Run:  python docs/website/scripts/device-assets.py
"""

from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[3]
SRC = ROOT / "docs" / "website" / "assets" / "device"
OUT = ROOT / "docs" / "website" / "assets"

# (capture, output, crop box or None, target width, quality)
JOBS = [
    # DSH: the official harness UI with a model already bound (tablet, 2136x3200)
    ("a-state.png", "dsh-home", (0, 120, 2136, 2160), 760, 78),
    # DSH sidebar with 新会话 / 插件 / 自动化任务 / 工作区
    ("a-dsh-side-open.png", "dsh-sidebar", (0, 120, 2136, 2500), 760, 78),
    # Video workspace: engine switches + the 5-component asset list (phone, 1440x3200)
    ("probe-video.png", "video-assets", (0, 380, 1440, 2760), 720, 78),
    # Video workspace: download in progress with a real byte counter
    ("v-mid.png", "video-download", (0, 1500, 1440, 2500), 720, 78),
    # Video workspace on the tablet: Wan2.2 TI2V-5B components all pass size + SHA-256,
    # plus five real COMPLETED runs in 最近运行 (finalize -> 已出片).
    ("v-ready-tablet.png", "video-ready", (0, 300, 2136, 2900), 760, 78),
    # Video output preview after a real 480x832 tablet run:
    # 状态 已完成 / 阶段 收尾 / 资源已释放 / 发布到作品库.
    ("v-nodegraph.png", "video-completed", (0, 1550, 2136, 2680), 760, 80),
    # Video 作品库 on the tablet: 25 videos, each 480x832 · 24 fps with real byte sizes.
    ("v-library-tablet.png", "video-gallery", (0, 120, 2136, 2280), 760, 78),
    # Image workspace on the tablet: 8 template cards, filters, and a 24-image library.
    ("img-ws-tablet.png", "image-workspace", (30, 300, 2100, 2700), 760, 78),
    # Image result produced on the tablet by Anima Preview Q4 (CPU), prompt visible.
    ("cur-tablet.png", "image-result-tablet", (0, 240, 2136, 2760), 720, 80),
    # The advanced node canvas (from the shipped-APK evidence already in repo)
    # (handled by crop-workspace-shots.py)
]


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    for name, out_name, box, width, quality in JOBS:
        src = SRC / name
        if not src.exists():
            print(f"MISSING {src}")
            continue
        with Image.open(src) as im:
            im = im.convert("RGB")
            crop = im.crop(box) if box else im
            ratio = width / crop.size[0]
            crop = crop.resize((width, round(crop.size[1] * ratio)), Image.LANCZOS)
            out = OUT / f"{out_name}.webp"
            crop.save(out, "WEBP", quality=quality, method=6)
            print(f"{out.name:20s} {crop.size[0]}x{crop.size[1]:<5d} {out.stat().st_size/1024:6.1f} KB  <- {name}")


if __name__ == "__main__":
    main()
