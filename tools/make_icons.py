#!/usr/bin/env python3
"""Turn a finished full-bleed app icon into Android adaptive-icon layers.

Android >= 26 launchers mask the icon themselves, so the art cannot be shipped as-is:
only the central 72 of 108dp is guaranteed visible, and a circular mask keeps just the
inscribed circle of that (radius 33.3% of the canvas). Both source icons put content well
outside it, so a Pixel launcher would crop the JT key grid and the HeadBoard arrows.

So: separate the art into the two layers the format wants.
  background — the flat field, extended to the full canvas so masking never reveals a seam
               (HeadBoard's art is a squircle on black; the black is replaced by its teal)
  foreground — the glyph alone, on transparency, scaled so its farthest pixel sits inside
               the safe radius, with the layer's own 108/72 padding applied

Usage: make_icons.py <source.png> <out_dir> <field_rgb> <mode> [--anchor-cyan]

--anchor-cyan centres the mark on its cyan key rather than on its bounding box. The JT
monogram's box is pulled up and left by the full-width top bar and the J's descender, so
box-centring leaves the key — which the eye reads as the centre — visibly off-centre.
"""
import sys
from PIL import Image

SRC, OUT, FIELD, MODE = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
ANCHOR_CYAN = "--anchor-cyan" in sys.argv
field = tuple(int(FIELD[i:i + 2], 16) for i in (0, 2, 4))

# mipmap densities: 48dp icon at 1x..4x, plus the 108dp adaptive layer at each density
LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
ADAPTIVE = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
SAFE_RADIUS = 0.333          # of the 108dp canvas
CONTENT_SCALE = 72.0 / 108.0  # foreground content lives in the inner 72dp


def dist(a, b):
    return sum((x - y) ** 2 for x, y in zip(a[:3], b[:3])) ** 0.5


def extract_glyph(im):
    """Glyph with alpha: everything that is not the flat field (or the black surround)."""
    im = im.convert("RGBA")
    w, h = im.size
    px = im.load()
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    op = out.load()
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            # near-black corners belong to HeadBoard's baked squircle, not the glyph
            if r + g + b < 40:
                continue
            d = dist((r, g, b), field)
            if d > 90:                       # clearly not the field -> glyph
                op[x, y] = (r, g, b, 255)
            elif d > 45:                     # anti-aliased rim -> feather it
                op[x, y] = (r, g, b, int(255 * (d - 45) / 45))
    return out


def bbox_radius(im):
    """Farthest opaque pixel from centre, as a fraction of width."""
    w, h = im.size
    px = im.load()
    cx, cy = w / 2, h / 2
    worst = 0.0
    for y in range(0, h, 2):
        for x in range(0, w, 2):
            if px[x, y][3] > 40:
                worst = max(worst, ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5)
    return worst / w


def cyan_centre(im):
    """Centre of the cyan key, in image coordinates, or None."""
    w, h = im.size
    px = im.load()
    xs, ys = [], []
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a > 100 and b > 150 and g > 140 and r < 120 and (g - r) > 60:
                xs.append(x)
                ys.append(y)
    if not xs:
        return None
    return ((min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2)


def place(canvas_size, glyph, anchor):
    """Paste glyph so `anchor` (or its centre) lands on the canvas centre, shrinking until
    the farthest opaque pixel is inside the safe radius."""
    gw, gh = glyph.size
    ax, ay = anchor if anchor else (gw / 2, gh / 2)
    for _ in range(24):
        canvas = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
        canvas.paste(glyph, (int(canvas_size / 2 - ax), int(canvas_size / 2 - ay)), glyph)
        if bbox_radius(canvas) <= SAFE_RADIUS:
            return canvas
        f = 0.97
        gw, gh = max(1, int(gw * f)), max(1, int(gh * f))
        ax, ay = ax * f, ay * f
        glyph = glyph.resize((gw, gh), Image.LANCZOS)
    return canvas


def main():
    src = Image.open(SRC).convert("RGBA")
    glyph = extract_glyph(src)

    # Crop to the glyph so scaling is driven by content, not by the source canvas.
    box = glyph.getbbox()
    anchor_src = cyan_centre(glyph) if ANCHOR_CYAN else None
    glyph = glyph.crop(box)
    anchor_cropped = (anchor_src[0] - box[0], anchor_src[1] - box[1]) if anchor_src else None

    import os
    os.makedirs(OUT, exist_ok=True)

    for dens, size in ADAPTIVE.items():
        target = size * CONTENT_SCALE
        gw, gh = glyph.size
        scale = target / max(gw, gh)
        g = glyph.resize((max(1, int(gw * scale)), max(1, int(gh * scale))), Image.LANCZOS)
        a = (anchor_cropped[0] * scale, anchor_cropped[1] * scale) if anchor_cropped else None
        canvas = place(size, g, a)
        d = f"{OUT}/mipmap-{dens}"
        os.makedirs(d, exist_ok=True)
        canvas.save(f"{d}/ic_launcher_foreground.png")

    # Legacy bitmaps keep the original art untouched, for anything that ignores adaptive icons.
    for dens, size in LEGACY.items():
        d = f"{OUT}/mipmap-{dens}"
        os.makedirs(d, exist_ok=True)
        src.resize((size, size), Image.LANCZOS).convert("RGB").save(f"{d}/ic_launcher.png")

    final = Image.open(f"{OUT}/mipmap-xxxhdpi/ic_launcher_foreground.png")
    print(f"{SRC.split('/')[-1]}: glyph radius now {bbox_radius(final):.1%} "
          f"(safe {SAFE_RADIUS:.1%})")


main()
