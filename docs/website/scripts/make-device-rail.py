"""Build the device-rail artwork from real MCA phone/tablet captures.

Two rules learned the hard way:

1. **Output width must exceed display width.** The phone column is ~390px wide, so a
   300px asset gets upscaled on a normal screen and looks soft. These are exported at
   720px (half of the 1440px capture), i.e. ~1.8x over-sampling at display size.
2. **The image must be taller than the frame.** The page crops each phone with
   `object-fit: cover` in a fixed aspect-ratio window and slides `object-position`
   from `top` to `bottom` on hover. That only works if the asset keeps the full
   screen height, so phone assets are exported *whole*, not trimmed to a band.

Device chrome (bezel, radius, shadow) is CSS on the page and is NOT baked into these
images, so a capture can be swapped without redoing the frame.

Sources:
  tablet -> img-ws-tablet.png   Xiaomi Pad 8 Pro (98a37aa7), 2136x3200, MCA 0.4.1
  phone  -> m-drawer.png        Xiaomi 2304FPN6DC (f9d98aa3), 1440x3200, MCA 0.4.2
  phone  -> rail-4-video.png    same phone, video workspace, all SHA-256 confirmed
  phone  -> rail-3-dsh.png      same phone, official DSH harness start screen

Run:  python docs/website/scripts/make-device-rail.py
"""

from pathlib import Path

from PIL import Image

# This script lives in docs/website/scripts, so parents[1] is docs/website.
SITE = Path(__file__).resolve().parents[1]
SRC = SITE / "assets" / "device"
OUT = SITE / "assets"

# (source, output, crop box, target width, quality)
# Phone jobs keep the full 1440x3200 screen (crop box spans the whole capture) so
# the hover scroll has real pixels to travel through.
JOBS = [
    # Tablet: template row -> gallery filters -> the "24 张图片" counter.
    # Exported at 1240px so it stays sharp at the ~600px it renders at.
    ("img-ws-tablet.png", "rail-tablet", (40, 1180, 2090, 2180), 1240, 82),
    # Phone: workspace drawer.
    ("m-drawer.png", "rail-phone-entry", (0, 0, 1440, 3200), 720, 80),
    # Phone: video workspace with every Wan component SHA-256 confirmed.
    ("rail-4-video.png", "rail-phone-video", (0, 0, 1440, 3200), 720, 80),
    # Phone: the system-settings centre, the densest real screen available. The DSH
    # start screen was tried here first and rejected: it is ~97% white in both halves,
    # so scrolling it produced a byte-identical frame (measured pixel delta = 0.0).
    ("probe-settings.png", "rail-phone-settings", (0, 0, 1440, 3200), 720, 80),
    # Tablet, second frame of the same hover-scroll: a CONTIGUOUS band directly below
    # the first one, same pixel size, so sliding from frame 1 to frame 2 reads as one
    # continuous scroll instead of a zoom jump.
    ("img-ws-tablet.png", "rail-tablet-lower", (40, 2180, 2090, 3180), 1240, 82),
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
            print(f"{out.name:26s} {crop.size[0]}x{crop.size[1]:<5d} "
                  f"{out.stat().st_size/1024:6.1f} KB  <- {name}")


if __name__ == "__main__":
    main()
