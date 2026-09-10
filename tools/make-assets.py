#!/usr/bin/env python3
"""
Regenerate the in-app brand assets from the source files in `theme/`.

Why this is a committed script and not a one-off: the logo needs *two* variants, and the
campus photo needs a specific crop. Both decisions have reasons that are easy to lose —
they are recorded here so a fork can regenerate the assets instead of guessing.

Source (in `theme/`, or supply your own):
  slai_logo_black.png   the official logo, 760x121, black wordmark + magenta loop mark
  slai_campus_pic.jpg   campus photo, 6676x4461 (used as the Home header background)

Output (into `app/src/main/res/drawable-nodpi/`):
  slai_logo_on_light.png      original, black wordmark   -> for light backgrounds
  slai_logo_on_dark.png       white wordmark             -> for dark backgrounds
  slai_campus.jpg             upper facade only, 1600px wide, ~170 KB
  ic_launcher_foreground.png  the loop mark, adaptive-icon safe area

Requires Pillow:  pip install Pillow
Usage:            python3 tools/make-assets.py
"""

from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "theme"
OUT = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"

# The loop mark occupies the left ~19.5% of the logo; everything to the right is the wordmark.
MARK_FRACTION = 0.195

# Adaptive icons get masked to an arbitrary shape. The safe zone is the inner 66/108 of the
# canvas, so content must stay inside ~61% or the launcher will clip it.
ICON_CANVAS = 432
ICON_SAFE = 264

# The campus photo is cropped to its upper half on purpose: the middle horizontal band is the
# building's own signage ("深圳河套学院 / Shenzhen Loop Area Institute"), which collides with
# our logo overlay at every card height. Cropping it out is more reliable than dodging it
# with alignment in the UI.
CAMPUS_KEEP_HEIGHT = 0.50
CAMPUS_WIDTH = 1600


def recolour_wordmark(img: Image.Image, rgb: tuple[int, int, int]) -> Image.Image:
    """Turn only the near-black wordmark into `rgb`, leaving the magenta mark untouched."""
    out = img.copy()
    px = out.load()
    width, height = out.size
    mark_right = int(width * MARK_FRACTION)
    for y in range(height):
        for x in range(mark_right, width):
            r, g, b, a = px[x, y]
            if a > 0 and r < 140 and g < 140 and b < 140:
                px[x, y] = (rgb[0], rgb[1], rgb[2], a)
    return out


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)

    logo_path = SRC / "slai_logo_black.png"
    campus_path = SRC / "slai_campus_pic.jpg"
    if not logo_path.exists():
        raise SystemExit(f"missing source logo: {logo_path}")
    if not campus_path.exists():
        raise SystemExit(f"missing source photo: {campus_path}")

    logo = Image.open(logo_path).convert("RGBA")
    width, height = logo.size
    mark_right = int(width * MARK_FRACTION)

    # 1. Two logo variants. Name them by *where they may be placed*, not by their own colour:
    #    the original is "on light", the recoloured one is "on dark".
    logo.save(OUT / "slai_logo_on_light.png")
    recolour_wordmark(logo, (255, 255, 255)).save(OUT / "slai_logo_on_dark.png")

    # 2. Square app mark, from the loop only.
    mark = logo.crop((0, 0, mark_right, height))
    side = max(mark.size)
    square = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    square.paste(mark, ((side - mark.width) // 2, (side - mark.height) // 2), mark)

    icon = Image.new("RGBA", (ICON_CANVAS, ICON_CANVAS), (0, 0, 0, 0))
    scaled = square.resize((ICON_SAFE, ICON_SAFE), Image.LANCZOS)
    icon.paste(scaled, ((ICON_CANVAS - ICON_SAFE) // 2, (ICON_CANVAS - ICON_SAFE) // 2), scaled)
    icon.save(OUT / "ic_launcher_foreground.png")

    # 3. Campus photo: crop, then downscale. The 6676px original would add ~2 MB to the APK
    #    for a header that is never wider than 1600 physical pixels.
    photo = Image.open(campus_path).convert("RGB")
    cropped = photo.crop((0, 0, photo.width, int(photo.height * CAMPUS_KEEP_HEIGHT)))
    resized = cropped.resize(
        (CAMPUS_WIDTH, int(CAMPUS_WIDTH * cropped.height / cropped.width)), Image.LANCZOS
    )
    resized.save(OUT / "slai_campus.jpg", quality=82, optimize=True, progressive=True)

    for name in (
        "slai_logo_on_light.png",
        "slai_logo_on_dark.png",
        "slai_campus.jpg",
        "ic_launcher_foreground.png",
    ):
        path = OUT / name
        print(f"  {path.stat().st_size / 1024:8.1f} KB  {path.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
