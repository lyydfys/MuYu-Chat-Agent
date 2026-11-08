"""Generate deployable site assets from the repo screenshots.

Source: docs/assets/screenshots/*.png (tracked, do not modify)
Output: docs/website/assets/*.webp  (committed, served by the site)

Run:  python docs/website/scripts/optimize-assets.py
"""

from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[3]  # E:\MAC
SRC = ROOT / "docs" / "assets" / "screenshots"
OUT = ROOT / "docs" / "website" / "assets"

# (source name, output base name, widths, quality)
JOBS = [
    ("01-home.png", "hero", [720, 1080], 74),
    ("03-images.png", "images", [720], 72),
    ("04-model-management.png", "models", [720], 72),
]


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    total = 0
    for src_name, base, widths, quality in JOBS:
        src = SRC / src_name
        if not src.exists():
            print(f"MISSING {src}")
            continue
        original = src.stat().st_size
        with Image.open(src) as im:
            im = im.convert("RGB")
            w, h = im.size
            for target_w in widths:
                target_h = round(h * target_w / w)
                resized = im.resize((target_w, target_h), Image.LANCZOS)
                out = OUT / f"{base}-{target_w}.webp"
                resized.save(out, "WEBP", quality=quality, method=6)
                size = out.stat().st_size
                total += size
                print(f"{out.name:22s} {target_w}x{target_h:<5d} {size/1024:7.1f} KB  (source {original/1024:7.1f} KB)")
    print(f"total webp: {total/1024:.1f} KB")


if __name__ == "__main__":
    main()
