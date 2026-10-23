#!/usr/bin/env python3
"""Convert the downloaded Lucide SVG icons into Android vector drawables."""
import os
import re
import sys
import xml.etree.ElementTree as ET

SRC = os.path.join(os.environ.get("TEMP", "/tmp"), "respira_assets", "icons")
DST = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "drawable")


def num(value, default=0.0):
    if value is None:
        return default
    return float(re.sub(r"[^0-9eE.+-]", "", value))


def path_for(el):
    tag = el.tag.split("}")[-1]
    a = el.attrib
    if tag == "path":
        return a.get("d")
    if tag == "circle":
        cx, cy, r = num(a.get("cx")), num(a.get("cy")), num(a.get("r"))
        return f"M{cx - r:g},{cy:g}a{r:g},{r:g} 0 1,0 {2 * r:g},0a{r:g},{r:g} 0 1,0 {-2 * r:g},0"
    if tag == "ellipse":
        cx, cy = num(a.get("cx")), num(a.get("cy"))
        rx, ry = num(a.get("rx")), num(a.get("ry"))
        return f"M{cx - rx:g},{cy:g}a{rx:g},{ry:g} 0 1,0 {2 * rx:g},0a{rx:g},{ry:g} 0 1,0 {-2 * rx:g},0"
    if tag == "line":
        x1, y1 = num(a.get("x1")), num(a.get("y1"))
        x2, y2 = num(a.get("x2")), num(a.get("y2"))
        return f"M{x1:g},{y1:g}L{x2:g},{y2:g}"
    if tag in ("polyline", "polygon"):
        pts = re.findall(r"-?\d+(?:\.\d+)?", a.get("points", ""))
        pairs = list(zip(pts[0::2], pts[1::2]))
        if not pairs:
            return None
        d = "M" + " L".join(f"{x},{y}" for x, y in pairs)
        return d + (" Z" if tag == "polygon" else "")
    if tag == "rect":
        x, y = num(a.get("x")), num(a.get("y"))
        w, h = num(a.get("width")), num(a.get("height"))
        rx = num(a.get("rx"), 0)
        ry = num(a.get("ry"), rx)
        if rx <= 0:
            return f"M{x:g},{y:g}h{w:g}v{h:g}h{-w:g}Z"
        return (
            f"M{x + rx:g},{y:g}h{w - 2 * rx:g}"
            f"a{rx:g},{ry:g} 0 0,1 {rx:g},{ry:g}v{h - 2 * ry:g}"
            f"a{rx:g},{ry:g} 0 0,1 {-rx:g},{ry:g}h{-(w - 2 * rx):g}"
            f"a{rx:g},{ry:g} 0 0,1 {-rx:g},{-ry:g}v{-(h - 2 * ry):g}"
            f"a{rx:g},{ry:g} 0 0,1 {rx:g},{-ry:g}Z"
        )
    return None


def convert(src_file, dst_file):
    tree = ET.parse(src_file)
    root = tree.getroot()
    paths = []
    for el in root.iter():
        d = path_for(el)
        if not d:
            continue
        fill = el.attrib.get("fill", root.attrib.get("fill", "none"))
        fill_color = "#FF000000" if fill not in ("none", "transparent") else "#00000000"
        paths.append(
            '    <path\n'
            f'        android:pathData="{d}"\n'
            '        android:strokeColor="#FF000000"\n'
            '        android:strokeWidth="2"\n'
            '        android:strokeLineCap="round"\n'
            '        android:strokeLineJoin="round"\n'
            f'        android:fillColor="{fill_color}" />'
        )
    if not paths:
        return False
    xml = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="24dp"\n'
        '    android:height="24dp"\n'
        '    android:viewportWidth="24"\n'
        '    android:viewportHeight="24">\n'
        + "\n".join(paths)
        + "\n</vector>\n"
    )
    with open(dst_file, "w", encoding="utf-8") as fh:
        fh.write(xml)
    return True


def main():
    os.makedirs(DST, exist_ok=True)
    made = 0
    for name in sorted(os.listdir(SRC)):
        if not name.endswith(".svg"):
            continue
        src = os.path.join(SRC, name)
        if os.path.getsize(src) < 60:
            continue
        out = "ic_" + name[:-4].replace("-", "_") + ".xml"
        try:
            if convert(src, os.path.join(DST, out)):
                made += 1
                print("ok  ", out)
        except Exception as exc:  # noqa: BLE001
            print("FAIL", name, exc)
    print(f"{made} icons written to {DST}")


if __name__ == "__main__":
    main()
