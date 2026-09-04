#!/usr/bin/env python3
"""Convert the boss's Nav icon SVGs (Nav Icon SVGs/) into Android vector drawables.

Generates a light + dark variant per icon (the Nav overlay picks by its theme pref,
which can override system night mode). Re-run after icon drops:

    python3 utils/nav_icon_svg2vector.py
"""

import math
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SRC = REPO / "Nav Icon SVGs"
OUT = REPO / "app/src/main/res/drawable"

# Icons referenced by NavAction/NavRegion. Drag move/drop + diagonal-focus icons are
# converted when their pages/features land (avoids unused-resource lint noise).
ICONS = [
    "ic_nav_android_app_switcher",
    "ic_nav_android_back",
    "ic_nav_android_home",
    "ic_nav_double_tap",
    "ic_nav_drag_select",
    "ic_nav_focus_down",
    "ic_nav_focus_left",
    "ic_nav_focus_right",
    "ic_nav_focus_up",
    "ic_nav_keyboard_move_down",
    "ic_nav_keyboard_move_down_left",
    "ic_nav_keyboard_move_down_right",
    "ic_nav_keyboard_move_left",
    "ic_nav_keyboard_move_right",
    "ic_nav_keyboard_move_up",
    "ic_nav_keyboard_move_up_left",
    "ic_nav_keyboard_move_up_right",
    "ic_nav_layer_1_to_2",
    "ic_nav_layer_2_to_1",
    "ic_nav_long_press",
    "ic_nav_mode_drag",
    "ic_nav_mode_move_keyboard",
    "ic_nav_mode_scroll",
    "ic_nav_path_longer",
    "ic_nav_path_shorter",
    "ic_nav_return_to_layer_2",
    "ic_nav_scroll_down",
    "ic_nav_scroll_left",
    "ic_nav_scroll_right",
    "ic_nav_scroll_up",
    "ic_nav_tap",
]

PALETTES = {
    "": {  # light key faces
        "line": "#17212B",
        "accent": "#006D77",
        "transform": "#C2410C",
        "soft_fill": "#E5E9EE",
        "soft_stroke": "#8B98A5",
        "ghost_fill": "#EEF1F4",
        "ghost_stroke": "#A6B0BA",
    },
    "_dark": {  # dark key faces (#424242)
        "line": "#FFFFFF",
        "accent": "#26A69A",
        "transform": "#FB923C",
        "soft_fill": "#455A64",
        "soft_stroke": "#90A4AE",
        "ghost_fill": "#37474F",
        "ghost_stroke": "#78909C",
    },
}

# Base classes from the SVGs' shared stylesheet. Values: fill key, stroke key,
# stroke width, round caps, alpha.
BASE_CLASSES = {
    "stroke": (None, "line", 5.0, True, None),
    "thin": (None, "line", 3.2, True, None),
    "clock": (None, "line", 7.0, True, None),
    "gridline": (None, "line", 2.2, True, None),
    "gridline-accent": (None, "accent", 2.2, True, None),
    "guide": (None, "accent", 2.6, True, 0.9),
    "move": (None, "transform", 2.2, True, None),
    "fill": ("line", None, None, False, None),
    "soft": ("soft_fill", "soft_stroke", 2.8, False, None),
    "ghost": ("ghost_fill", "ghost_stroke", 3.0, False, 0.65),
    "tiny-text": ("line", None, None, False, None),
    "mini-text": ("line", None, None, False, None),
    "accent-fill": ("accent", None, None, False, None),
    "transform-fill": ("transform", None, None, False, None),
}
STROKE_MODIFIERS = {"accent": "accent", "transform": "transform"}
FILL_MODIFIERS = {"accent-fill": "accent", "transform-fill": "transform"}


def resolve_style(class_attr):
    """Return (fill_key, stroke_key, stroke_width, round_caps, alpha) for a class list."""
    tokens = (class_attr or "fill").split()
    base = next((t for t in tokens if t in BASE_CLASSES), None)
    if base is None:
        raise ValueError(f"no base class in {tokens!r}")
    fill, stroke, width, rnd, alpha = BASE_CLASSES[base]
    for t in tokens:
        if t == base:
            continue
        if t in STROKE_MODIFIERS and stroke is not None:
            stroke = STROKE_MODIFIERS[t]
        elif t in FILL_MODIFIERS and fill is not None:
            fill = FILL_MODIFIERS[t]
        elif t in STROKE_MODIFIERS and fill is not None:
            # e.g. class="fill accent" (defensive): tint the fill
            fill = STROKE_MODIFIERS[t]
        elif t not in BASE_CLASSES:
            raise ValueError(f"unknown class token {t!r} in {tokens!r}")
    return fill, stroke, width, rnd, alpha


def fnum(v):
    s = f"{float(v):.2f}".rstrip("0").rstrip(".")
    return s if s else "0"


def rect_path(el):
    x, y = float(el.get("x", 0)), float(el.get("y", 0))
    w, h = float(el.get("width")), float(el.get("height"))
    r = min(float(el.get("rx", 0)), w / 2, h / 2)
    if r <= 0:
        return f"M{fnum(x)} {fnum(y)} H{fnum(x + w)} V{fnum(y + h)} H{fnum(x)} Z"
    a = f"{fnum(r)} {fnum(r)} 0 0 1"
    return (
        f"M{fnum(x + r)} {fnum(y)} H{fnum(x + w - r)} A{a} {fnum(x + w)} {fnum(y + r)} "
        f"V{fnum(y + h - r)} A{a} {fnum(x + w - r)} {fnum(y + h)} H{fnum(x + r)} "
        f"A{a} {fnum(x)} {fnum(y + h - r)} V{fnum(y + r)} A{a} {fnum(x + r)} {fnum(y)} Z"
    )


def circle_path(el):
    cx, cy, r = (float(el.get(k)) for k in ("cx", "cy", "r"))
    d = fnum(2 * r)
    return f"M{fnum(cx - r)} {fnum(cy)} a{fnum(r)} {fnum(r)} 0 1 0 {d} 0 a{fnum(r)} {fnum(r)} 0 1 0 -{d} 0 Z"


def digit_path(digit, x, y):
    """Small hand-drawn '1'/'2' stroke glyphs replacing SVG <text> (11px bold, baseline y)."""
    if digit == "1":
        return f"M{fnum(x - 2.3)} {fnum(y - 5.4)} L{fnum(x + 0.4)} {fnum(y - 7.6)} L{fnum(x + 0.4)} {fnum(y - 1)}"
    if digit == "2":
        return (
            f"M{fnum(x - 2.6)} {fnum(y - 5.3)} Q{fnum(x - 2.5)} {fnum(y - 7.6)} {fnum(x)} {fnum(y - 7.6)} "
            f"Q{fnum(x + 2.6)} {fnum(y - 7.6)} {fnum(x + 2.6)} {fnum(y - 5.4)} "
            f"Q{fnum(x + 2.6)} {fnum(y - 3.9)} {fnum(x + 0.9)} {fnum(y - 2.5)} "
            f"L{fnum(x - 2.6)} {fnum(y - 1)} L{fnum(x + 2.7)} {fnum(y - 1)}"
        )
    raise ValueError(f"unsupported text glyph {digit!r}")


def _arc_points(x0, y0, rx, ry, rot, large, sweep, x1, y1):
    """Sample an SVG arc (endpoint parameterization, W3C F.6.5) for bounds."""
    if rx == 0 or ry == 0 or (x0 == x1 and y0 == y1):
        return [(x1, y1)]
    rx, ry = abs(rx), abs(ry)
    phi = math.radians(rot)
    cosp, sinp = math.cos(phi), math.sin(phi)
    dx, dy = (x0 - x1) / 2, (y0 - y1) / 2
    x1p = cosp * dx + sinp * dy
    y1p = -sinp * dx + cosp * dy
    lam = (x1p / rx) ** 2 + (y1p / ry) ** 2
    if lam > 1:
        s = math.sqrt(lam)
        rx, ry = rx * s, ry * s
    num = rx**2 * ry**2 - rx**2 * y1p**2 - ry**2 * x1p**2
    den = rx**2 * y1p**2 + ry**2 * x1p**2
    co = math.sqrt(max(0.0, num / den)) if den else 0.0
    if large == sweep:
        co = -co
    cxp, cyp = co * rx * y1p / ry, -co * ry * x1p / rx
    cx = cosp * cxp - sinp * cyp + (x0 + x1) / 2
    cy = sinp * cxp + cosp * cyp + (y0 + y1) / 2
    th1 = math.atan2((y1p - cyp) / ry, (x1p - cxp) / rx)
    th2 = math.atan2((-y1p - cyp) / ry, (-x1p - cxp) / rx)
    dth = th2 - th1
    if not sweep and dth > 0:
        dth -= 2 * math.pi
    elif sweep and dth < 0:
        dth += 2 * math.pi
    pts = []
    for i in range(17):
        th = th1 + dth * i / 16
        px = cx + rx * math.cos(th) * cosp - ry * math.sin(th) * sinp
        py = cy + rx * math.cos(th) * sinp + ry * math.sin(th) * cosp
        pts.append((px, py))
    return pts


def path_bounds(d):
    """True-ish bounds of a path: lines exact, beziers/arcs sampled."""
    toks = re.findall(r"([MmLlHhVvCcSsQqTtAaZz])|(-?\d*\.?\d+(?:e-?\d+)?)", d)
    cmds = []
    cur = None
    for c, n in toks:
        if c:
            cur = (c, [])
            cmds.append(cur)
        else:
            cur[1].append(float(n))
    xs, ys = [], []
    x = y = sx = sy = 0.0

    def bez(p0, ctrl):
        pts = [p0] + ctrl
        for i in range(1, 17):
            t = i / 16
            q = pts
            while len(q) > 1:
                q = [((1 - t) * a[0] + t * b[0], (1 - t) * a[1] + t * b[1]) for a, b in zip(q, q[1:])]
            xs.append(q[0][0])
            ys.append(q[0][1])

    for c, n in cmds:
        rel = c.islower()
        u = c.upper()
        i = 0
        while True:
            if u == "Z":
                x, y = sx, sy
                break
            if u == "M" and i + 2 <= len(n):
                x, y = (x + n[i], y + n[i + 1]) if rel else (n[i], n[i + 1])
                sx, sy = x, y
                u = "L"  # implicit lineto for subsequent pairs
            elif u == "L" and i + 2 <= len(n):
                x, y = (x + n[i], y + n[i + 1]) if rel else (n[i], n[i + 1])
            elif u == "H" and i + 1 <= len(n):
                x = x + n[i] if rel else n[i]
                xs.append(x)
                ys.append(y)
                i += 1
                continue
            elif u == "V" and i + 1 <= len(n):
                y = y + n[i] if rel else n[i]
                xs.append(x)
                ys.append(y)
                i += 1
                continue
            elif u in "CQ" and i + (6 if u == "C" else 4) <= len(n):
                cnt = 6 if u == "C" else 4
                pts = n[i : i + cnt]
                if rel:
                    pts = [pts[j] + (x if j % 2 == 0 else y) for j in range(cnt)]
                ctrl = [(pts[j], pts[j + 1]) for j in range(0, cnt, 2)]
                bez((x, y), ctrl)
                x, y = ctrl[-1]
                i += cnt
                xs.append(x)
                ys.append(y)
                continue
            elif u == "A" and i + 7 <= len(n):
                ex, ey = n[i + 5], n[i + 6]
                if rel:
                    ex, ey = x + ex, y + ey
                for px, py in _arc_points(x, y, n[i], n[i + 1], n[i + 2], bool(n[i + 3]), bool(n[i + 4]), ex, ey):
                    xs.append(px)
                    ys.append(py)
                x, y = ex, ey
                i += 7
                continue
            elif u in "ST":
                raise ValueError("S/T path commands not used by this corpus")
            else:
                break
            i += 2
            xs.append(x)
            ys.append(y)
            if i >= len(n):
                break
    return min(xs), min(ys), max(xs), max(ys)


def convert(name, palette, suffix):
    tree = ET.parse(SRC / f"{name}.svg")
    root = tree.getroot()
    vb = [float(v) for v in root.get("viewBox").split()]
    if vb[0] != 0 or vb[1] != 0:
        raise ValueError(f"{name}: viewBox offset unsupported: {vb}")
    vw, vh = vb[2], vb[3]

    paths = []
    bounds = []
    ns = "{http://www.w3.org/2000/svg}"
    for el in root.iter():
        tag = el.tag.replace(ns, "")
        if tag in ("svg", "title", "style"):
            continue
        if tag == "path":
            data = el.get("d")
        elif tag == "rect":
            data = rect_path(el)
        elif tag == "circle":
            data = circle_path(el)
        elif tag == "text":
            data = digit_path((el.text or "").strip(), float(el.get("x")), float(el.get("y")))
        else:
            raise ValueError(f"{name}: unsupported element <{tag}>")

        fill, stroke, width, rnd, alpha = resolve_style(el.get("class"))
        if tag == "text":
            # Digits render as strokes in the text fill color.
            stroke, fill, width, rnd = fill, None, 1.9, True
        attrs = [f'android:pathData="{data}"']
        attrs.append(f'android:fillColor="{palette[fill]}"' if fill else 'android:fillColor="#00000000"')
        if fill and alpha:
            attrs.append(f'android:fillAlpha="{alpha}"')
        if stroke:
            attrs.append(f'android:strokeColor="{palette[stroke]}"')
            attrs.append(f'android:strokeWidth="{fnum(width)}"')
            if rnd:
                attrs.append('android:strokeLineCap="round"')
                attrs.append('android:strokeLineJoin="round"')
            if alpha:
                attrs.append(f'android:strokeAlpha="{alpha}"')
        paths.append(attrs)
        bx0, by0, bx1, by1 = path_bounds(data)
        half = (width if tag != "text" else 1.9) / 2 if stroke else 0.0
        bounds.append((bx0 - half, by0 - half, bx1 + half, by1 + half))

    # Center the artwork: source SVGs are often off-center in (or overflow) their
    # viewBox, and vector drawables hard-clip at the viewport.
    x0 = min(b[0] for b in bounds)
    y0 = min(b[1] for b in bounds)
    x1 = max(b[2] for b in bounds)
    y1 = max(b[3] for b in bounds)
    margin = 0.03 * max(vw, vh)
    vw = max(vw, x1 - x0 + 2 * margin)
    vh = max(vh, y1 - y0 + 2 * margin)
    dx = vw / 2 - (x0 + x1) / 2
    dy = vh / 2 - (y0 + y1) / 2

    # Non-square icons keep their aspect ratio at ~24dp max edge.
    scale = 24.0 / max(vw, vh)
    wdp, hdp = fnum(vw * scale), fnum(vh * scale)

    shift = abs(dx) > 0.05 or abs(dy) > 0.05
    pad = "        " if shift else "    "
    body = "\n".join(
        f"{pad}<path\n" + "\n".join(f"{pad}    {a}" for a in attrs) + "/>" for attrs in paths
    )
    if shift:
        body = (
            "    <group\n"
            f'        android:translateX="{fnum(dx)}"\n'
            f'        android:translateY="{fnum(dy)}">\n'
            f"{body}\n"
            "    </group>"
        )
    xml = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!-- Generated by utils/nav_icon_svg2vector.py — edit the SVG source, not this file. -->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        f'    android:width="{wdp}dp"\n'
        f'    android:height="{hdp}dp"\n'
        f'    android:viewportWidth="{fnum(vw)}"\n'
        f'    android:viewportHeight="{fnum(vh)}">\n'
        f"{body}\n"
        "</vector>\n"
    )
    (OUT / f"{name}{suffix}.xml").write_text(xml)


def main():
    missing = [n for n in ICONS if not (SRC / f"{n}.svg").exists()]
    if missing:
        sys.exit(f"missing SVG sources: {missing}")
    for name in ICONS:
        for suffix, palette in (("", PALETTES[""]), ("_dark", PALETTES["_dark"])):
            convert(name, palette, suffix)
    print(f"wrote {len(ICONS) * 2} drawables to {OUT}")


if __name__ == "__main__":
    main()
