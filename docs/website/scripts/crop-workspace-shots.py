"""Derive the workspace artwork in docs/website/assets from real device captures.

Nothing is drawn, retouched or synthesised: every output is a crop and re-encode of a
capture that already exists in this repository. Sources are listed next to each job so
the provenance of each picture on the site stays checkable.

Run:  python docs/website/scripts/crop-workspace-shots.py
"""

from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / "docs" / "website" / "assets"
OUT.mkdir(parents=True, exist_ok=True)

# (source file, output name, crop box, target width, quality)
JOBS = [
    # DSH: the MCA runtime console and the official DeepSeek Harness UI inside the app.
    (
        ROOT / "artifacts/dsh-v2/verification/device-r35/screenshots/A12-console.png",
        "dsh-console",
        (0, 130, 2136, 2180),
        720,
        76,
    ),
    (
        ROOT / "artifacts/dsh-v2/verification/device-model-acceptance/screenshots/34-sidebar.png",
        "dsh-ui",
        (0, 30, 1400, 1500),
        720,
        76,
    ),
    # Video: node list, node canvas, completed run, and the 作品 library.
    (
        ROOT / "build/evidence/v1-06/98a-video-workspace.png",
        "video-nodes",
        (40, 150, 2090, 2180),
        720,
        76,
    ),
    (
        ROOT / "build/evidence/v1-06/98a-qa2-panned.png",
        "video-canvas",
        (40, 620, 2090, 1560),
        760,
        80,
    ),
    (
        ROOT / "build/evidence/v1-06/f9d-completed-preview.png",
        "video-run",
        (40, 690, 1400, 1770),
        620,
        76,
    ),
    (
        ROOT / "build/evidence/v1-06/f9d-library.png",
        "video-library",
        (40, 120, 1400, 1120),
        620,
        76,
    ),
    # Image: an on-device generated result shown in the chat surface.
    (
        ROOT / "artifacts/architecture/2026-10-25-dimensity-anima/phase7/ui-result.png",
        "image-result",
        (40, 300, 2100, 1780),
        720,
        76,
    ),
]


def main() -> None:
    for src, name, box, target_w, quality in JOBS:
        if not src.exists():
            print(f"MISSING {src}")
            continue
        with Image.open(src) as im:
            im = im.convert("RGB")
            crop = im.crop(box)
            ratio = target_w / crop.size[0]
            crop = crop.resize((target_w, round(crop.size[1] * ratio)), Image.LANCZOS)
            out = OUT / f"{name}.webp"
            crop.save(out, "WEBP", quality=quality, method=6)
            print(f"{out.name:20s} {crop.size[0]}x{crop.size[1]:<5d} {out.stat().st_size/1024:6.1f} KB")


if __name__ == "__main__":
    main()
