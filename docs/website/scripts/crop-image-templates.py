"""Crop the four template thumbnails out of the real image-workspace screenshot.

The source is a real 1440x3200 device capture; the crops are only derived from it
(nothing is drawn, retouched or synthesised). Output feeds the 02 / WORKSPACES
"生图" pane so the page shows the actual shipped templates.

Run:  python docs/website/scripts/crop-image-templates.py
"""

from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[3]
SRC = ROOT / "docs" / "assets" / "screenshots" / "03-images.png"
OUT = ROOT / "docs" / "website" / "assets"

# Crop boxes measured on the original 1440x3200 capture.
# y range covers the 4 thumbnail cards only; captions are excluded on purpose.
CARDS = [
    ("tpl-1", (60, 844, 352, 1240)),
    ("tpl-2", (392, 844, 680, 1240)),
    ("tpl-3", (732, 844, 1020, 1240)),
    ("tpl-4", (1070, 844, 1356, 1240)),
]


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    with Image.open(SRC) as im:
        im = im.convert("RGB")
        for name, box in CARDS:
            crop = im.crop(box)
            out = OUT / f"{name}.webp"
            crop.save(out, "WEBP", quality=78, method=6)
            print(f"{out.name}  {crop.size[0]}x{crop.size[1]}  {out.stat().st_size/1024:.1f} KB")


if __name__ == "__main__":
    main()
