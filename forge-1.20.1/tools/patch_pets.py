#!/usr/bin/env python3
"""Tanda 20: todas las mascotas rebotan al tocarlas (propiedad 'rebote' + animacion) y modelos de item pet2/pet4-7."""
import json
R = "src/main/resources"
p = f"{R}/data/twixxel/entity_defs.json"
d = json.load(open(p))
for x in d:
    if not x["id"].startswith("mascota"):
        continue
    x.setdefault("props", {})["rebote"] = {"type": "int", "default": 0, "min": 0, "max": 1}
    anim = "animation.mascota.rebote_bb" if x["id"] == "mascota" else "animation.mascota5.rebote"
    if not any(a.get("anim") == anim for a in x.get("animate", [])):
        x.setdefault("animate", []).append({"anim": anim, "cond": "query.property('twixxel:rebote') == 1"})
    x["hasAnim"] = True
json.dump(d, open(p, "w"), ensure_ascii=False, separators=(",", ":"))
for n in ("pet2", "pet5"):
    egg = "mascota2_egg" if n == "pet2" else "mascota5_egg"
    json.dump({"parent": "minecraft:item/generated", "textures": {"layer0": f"twixxel:item/eggs/{egg}"}}, open(f"{R}/assets/twixxel/models/item/{n}.json", "w"))
for n in ("pet4", "pet6", "pet7"):
    json.dump({"parent": "minecraft:item/generated", "textures": {"layer0": f"twixxel:item/{n}"}}, open(f"{R}/assets/twixxel/models/item/{n}.json", "w"))
for f, es in (("en_us.json", False), ("es_es.json", True), ("es_mx.json", True)):
    q = f"{R}/assets/twixxel/lang/{f}"
    l = json.load(open(q))
    for i in range(4, 8):
        l.setdefault(f"item.twixxel.pet{i}", f"mascota{i}")
    json.dump(l, open(q, "w"), ensure_ascii=False, indent=1)
