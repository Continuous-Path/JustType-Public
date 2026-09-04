from __future__ import annotations

import html
import json
import re
import shutil
from pathlib import Path


ROOT = Path(__file__).resolve().parent
SOURCE = ROOT / "system-navigation-icon-proposals.html"
OUT = ROOT / "justtype-system-navigation-icons"
SVG_DIR = OUT / "svg"

STYLE = """:root {
  --line: #17212b;
  --accent: #006d77;
  --transform: #c2410c;
}
.stroke {
  fill: none;
  stroke: var(--line);
  stroke-width: 5;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.thin {
  fill: none;
  stroke: var(--line);
  stroke-width: 3.2;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.clock {
  fill: none;
  stroke: var(--line);
  stroke-width: 7;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.fill {
  fill: var(--line);
}
.soft {
  fill: #e5e9ee;
  stroke: #8b98a5;
  stroke-width: 2.8;
}
.ghost {
  fill: #eef1f4;
  stroke: #a6b0ba;
  stroke-width: 3;
  opacity: 0.65;
}
.accent {
  stroke: var(--accent);
}
.accent-fill {
  fill: var(--accent);
}
.transform {
  stroke: var(--transform);
}
.transform-fill {
  fill: var(--transform);
}
.gridline {
  fill: none;
  stroke: var(--line);
  stroke-width: 2.2;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.gridline-accent {
  fill: none;
  stroke: var(--accent);
  stroke-width: 2.2;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.mini-text {
  font-size: 15px;
  font-weight: 800;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
  fill: var(--line);
  text-anchor: middle;
}
.tiny-text {
  font-size: 11px;
  font-weight: 800;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
  fill: var(--line);
  text-anchor: middle;
}
.tiny-text.accent-fill {
  fill: var(--accent);
}
"""

PREFERRED_NAMES = {
    "Android Home": "ic_nav_android_home.svg",
    "Android Back": "ic_nav_android_back.svg",
    "Android App Switcher": "ic_nav_android_app_switcher.svg",
    "Single Tap": "ic_nav_tap.svg",
    "Double Tap": "ic_nav_double_tap.svg",
    "Tap and Hold": "ic_nav_long_press.svg",
    "Layer 1 to 2": "ic_nav_layer_1_to_2.svg",
    "Layer 2 to 1": "ic_nav_layer_2_to_1.svg",
    "Back to Nav 1": "ic_nav_return_to_layer_1.svg",
    "Back to Nav 2": "ic_nav_return_to_layer_2.svg",
    "Longer Path": "ic_nav_path_longer.svg",
    "Shorter Path": "ic_nav_path_shorter.svg",
    "Move Keyboard Mode": "ic_nav_mode_move_keyboard.svg",
    "Scroll Mode": "ic_nav_mode_scroll.svg",
    "Drag Mode": "ic_nav_mode_drag.svg",
    "Navigate Up": "ic_nav_focus_up.svg",
    "Navigate Left": "ic_nav_focus_left.svg",
    "Navigate Right": "ic_nav_focus_right.svg",
    "Navigate Down": "ic_nav_focus_down.svg",
    "Navigate Up Left": "ic_nav_focus_up_left.svg",
    "Navigate Up Right": "ic_nav_focus_up_right.svg",
    "Navigate Down Left": "ic_nav_focus_down_left.svg",
    "Navigate Down Right": "ic_nav_focus_down_right.svg",
    "Move Keyboard Up Left": "ic_nav_keyboard_move_up_left.svg",
    "Move Keyboard Up": "ic_nav_keyboard_move_up.svg",
    "Move Keyboard Up Right": "ic_nav_keyboard_move_up_right.svg",
    "Move Keyboard Left": "ic_nav_keyboard_move_left.svg",
    "Move Keyboard Right": "ic_nav_keyboard_move_right.svg",
    "Move Keyboard Down Left": "ic_nav_keyboard_move_down_left.svg",
    "Move Keyboard Down": "ic_nav_keyboard_move_down.svg",
    "Move Keyboard Down Right": "ic_nav_keyboard_move_down_right.svg",
    "Scroll Up": "ic_nav_scroll_up.svg",
    "Scroll Left": "ic_nav_scroll_left.svg",
    "Scroll Right": "ic_nav_scroll_right.svg",
    "Scroll Down": "ic_nav_scroll_down.svg",
    "Pick Up": "ic_nav_drag_select.svg",
    "Drag Up": "ic_nav_drag_up.svg",
    "Drag Left": "ic_nav_drag_left.svg",
    "Drag Right": "ic_nav_drag_right.svg",
    "Drag Down": "ic_nav_drag_down.svg",
    "Drop": "ic_nav_drop.svg",
}


def make_standalone(svg: str, label: str) -> str:
    svg = svg.strip()
    svg = re.sub(r"<svg\b", '<svg xmlns="http://www.w3.org/2000/svg"', svg, count=1)
    svg = re.sub(r'\saria-label="[^"]*"', "", svg, count=1)
    svg = re.sub(r"<svg([^>]*)>", rf"<svg\1>\n  <title>{html.escape(label)}</title>\n  <style>{STYLE}</style>", svg, count=1)
    return svg + "\n"


def main() -> None:
    text = SOURCE.read_text(encoding="utf-8")
    cards = re.findall(
        r'<article class="card">[\s\S]*?<div class="sample">\s*(<svg[\s\S]*?</svg>)\s*</div>\s*<div class="label">([^<]+)</div>',
        text,
    )
    if not cards:
        raise RuntimeError("No icon cards found in source HTML")

    if OUT.exists():
        shutil.rmtree(OUT)
    SVG_DIR.mkdir(parents=True)

    manifest = []
    used = set()
    for svg, label in cards:
        filename = PREFERRED_NAMES.get(label)
        if filename is None:
            slug = re.sub(r"[^a-z0-9]+", "_", label.lower()).strip("_")
            filename = f"ic_nav_{slug}.svg"
        if filename in used:
            raise RuntimeError(f"Duplicate filename generated: {filename}")
        used.add(filename)

        path = SVG_DIR / filename
        path.write_text(make_standalone(svg, label), encoding="utf-8")
        manifest.append({"label": label, "file": f"svg/{filename}"})

    for name in [
        "system-navigation-icon-proposals.html",
        "system-navigation-icon-proposals-unlabeled.html",
        "system-navigation-icon-proposals-mode-text-experiment.html",
    ]:
        shutil.copy2(ROOT / name, OUT / name)

    (OUT / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

    lines = [
        "# JustType System Navigation Icon Export",
        "",
        "Standalone SVG exports generated from `system-navigation-icon-proposals.html`.",
        "",
        "## Contents",
        "",
        "- `svg/`: individual standalone SVG icon files.",
        "- `manifest.json`: label-to-file mapping.",
        "- HTML review files: latest labeled and unlabeled review sheets.",
        "",
        "## Icons",
        "",
    ]
    for item in manifest:
        lines.append(f"- `{item['file']}`: {item['label']}")
    (OUT / "README.md").write_text("\n".join(lines) + "\n", encoding="utf-8")

    print(f"Exported {len(manifest)} icons to {OUT}")


if __name__ == "__main__":
    main()
