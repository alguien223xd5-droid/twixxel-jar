#!/usr/bin/env python3
"""Junta los cuadros de los videos del celular (RP/textures/ui/twx_phone/<letra>NNNN.png, 76x72) en un atlas por video.
Uso: make_phone_atlas.py <addon_RP> <salida_assets_twixxel>"""
import os, sys, json
from PIL import Image

RP, OUT = sys.argv[1], sys.argv[2]
FW, FH = 76, 72
src = os.path.join(RP, "textures", "ui", "twx_phone")
dst = os.path.join(OUT, "textures", "gui")
os.makedirs(dst, exist_ok=True)
meta = {}
for key in "abcdef":
    fr = sorted(f for f in os.listdir(src) if f.startswith(key) and f.endswith(".png"))
    if not fr:
        continue
    cols = min(len(fr), 4096 // FW)
    rows = -(-len(fr) // cols)
    atlas = Image.new("RGBA", (cols * FW, rows * FH), (0, 0, 0, 255))
    for i, f in enumerate(fr):
        atlas.paste(Image.open(os.path.join(src, f)).convert("RGBA"), ((i % cols) * FW, (i // cols) * FH))
    q = atlas.convert("RGB").quantize(colors=64, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
    q.save(os.path.join(dst, "cel_%s.png" % key), optimize=True)
    meta[key] = {"frames": len(fr), "cols": cols, "w": cols * FW, "h": rows * FH}
    print(key, len(fr), atlas.size, os.path.getsize(os.path.join(dst, "cel_%s.png" % key)) // 1024, "KB")
Image.open(os.path.join(RP, "textures", "ui", "twx_phone_frame.png")).save(os.path.join(dst, "cel_frame.png"), optimize=True)
json.dump(meta, open(os.path.join(OUT, "..", "..", "data", "twixxel", "phone_atlas.json"), "w"))
