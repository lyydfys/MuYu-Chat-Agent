"""Render the 1200x630 social share card (og.png) with Pillow.

Keeps the card in the same instrument-manual language as the site: paper ground,
hairline rules, mono labels, the launcher mark, and the one-line claim.

Run:  python docs/website/scripts/make-og.py
"""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / "docs" / "website" / "og.png"

W, H = 1200, 630
PAPER = (248, 249, 250)
INK = (23, 25, 28)
MUTED = (98, 103, 109)
FAINT = (107, 113, 120)
LINE = (228, 231, 234)
BLUE = (26, 115, 232)

FONT_DIR = Path(r"C:\Windows\Fonts")
SANS = FONT_DIR / "msyh.ttc"
SANS_BOLD = FONT_DIR / "msyhbd.ttc"
MONO = FONT_DIR / "consola.ttf"


def font(path: Path, size: int, fallback: Path | None = None) -> ImageFont.FreeTypeFont:
    for candidate in (path, fallback):
        if candidate and candidate.exists():
            return ImageFont.truetype(str(candidate), size)
    return ImageFont.load_default()


def main() -> None:
    img = Image.new("RGB", (W, H), PAPER)
    d = ImageDraw.Draw(img)

    # faint blueprint grid, right half only
    grid = Image.new("RGB", (W, H), PAPER)
    gd = ImageDraw.Draw(grid)
    for x in range(0, W, 56):
        gd.line([(x, 0), (x, H)], fill=(240, 242, 244))
    for y in range(0, H, 56):
        gd.line([(0, y), (W, y)], fill=(240, 242, 244))
    mask = Image.new("L", (W, H), 0)
    md = ImageDraw.Draw(mask)
    md.ellipse([W * 0.55, -140, W * 1.25, H + 140], fill=70)
    img.paste(grid, (0, 0), mask)
    d = ImageDraw.Draw(img)

    # top hairline + mono labels
    d.line([(72, 88), (W - 72, 88)], fill=LINE, width=1)
    mono_small = font(MONO, 19)
    d.text((72, 56), "MCA / MUYU CHAT AGENT", font=mono_small, fill=MUTED)
    label = "ANDROID · LOCAL-FIRST AI WORKSPACE"
    tw = d.textlength(label, font=mono_small)
    d.text((W - 72 - tw, 56), label, font=mono_small, fill=FAINT)

    # brand mark: speech bubble + four-point star + four dots
    mark = Image.open(ROOT / "docs" / "website" / "preview" / "mark.png") if (ROOT / "docs" / "website" / "preview" / "mark.png").exists() else None
    if mark is not None:
        img.paste(mark, (72, 140), mark)
    else:
        # draw a simplified vector mark at 96px
        mx, my, size = 72, 140, 96
        s = size / 68.0
        cx, cy = mx + 34 * s, my + 30 * s
        d.ellipse([cx - 34 * s, cy - 30 * s, cx + 34 * s, cy + 30 * s], fill=BLUE)
        d.polygon(
            [
                (cx, cy - 16 * s), (cx + 3 * s, cy - 3 * s), (cx + 16 * s, cy),
                (cx + 3 * s, cy + 3 * s), (cx, cy + 16 * s), (cx - 3 * s, cy + 3 * s),
                (cx - 16 * s, cy), (cx - 3 * s, cy - 3 * s),
            ],
            fill=(255, 255, 255),
        )
        d.ellipse([cx - 18 * s, cy - 12 * s, cx - 12 * s, cy - 6 * s], fill=(255, 255, 255))
        d.ellipse([cx + 12 * s, cy + 8 * s, cx + 18 * s, cy + 14 * s], fill=(255, 255, 255))

    # headline
    h1 = font(SANS_BOLD, 96, SANS)
    d.text((72, 268), "断网，也能用", font=h1, fill=INK)
    period_x = 72 + d.textlength("断网，也能用", font=h1) + 6
    d.ellipse([period_x, 344, period_x + 13, 357], fill=BLUE)

    # supporting line
    lead = font(SANS, 30)
    d.text((72, 396), "模型、角色卡、生图、生视频、DSH 与 Local API，都在一台 Android 手机上。", font=lead, fill=MUTED)

    # fact rail
    d.line([(72, 470), (W - 72, 470)], fill=LINE, width=1)
    facts = [("VERSION", "v0.4.1"), ("ABI", "arm64-v8a"), ("SIZE", "612.7 MB"), ("LICENSE", "Apache-2.0")]
    mono_k = font(MONO, 17)
    sans_v = font(SANS, 24)
    x = 72
    for k, v in facts:
        d.text((x, 500), k, font=mono_k, fill=FAINT)
        d.text((x, 526), v, font=sans_v, fill=INK)
        x += 232

    # bottom rule
    d.line([(72, 600), (W - 72, 600)], fill=LINE, width=1)

    img.save(OUT, "PNG", optimize=True)
    print(f"wrote {OUT} ({OUT.stat().st_size / 1024:.1f} KB)")


if __name__ == "__main__":
    main()
