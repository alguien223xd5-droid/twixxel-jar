#!/usr/bin/env python3
"""Genera blockstates, modelos, loot, recetas y lang de los 12 armarios (tanda 19)."""
import json, os, shutil, sys
SRC = sys.argv[1]            # .../RP/textures
R = "src/main/resources"
W = {"acacia":("Acacia","acacia"),"bamboo":("Bambú","bamboo"),"birch":("Abedul","birch"),"cherry":("Cerezo","cherry"),
     "crimson":("Carmesí","crimson"),"dark_oak":("Roble oscuro","dark_oak"),"jungle":("Jungla","jungle"),
     "mangrove":("Mangle","mangrove"),"oak":("Roble","oak"),"pale_oak":("Roble pálido","pale_oak"),
     "spruce":("Abeto","spruce"),"warped":("Deformado","warped")}
EN = {"acacia":"Acacia","bamboo":"Bamboo","birch":"Birch","cherry":"Cherry","crimson":"Crimson","dark_oak":"Dark Oak",
      "jungle":"Jungle","mangrove":"Mangrove","oak":"Oak","pale_oak":"Pale Oak","spruce":"Spruce","warped":"Warped"}
def wr(p, d):
    p = os.path.join(R, p); os.makedirs(os.path.dirname(p), exist_ok=True)
    json.dump(d, open(p, "w"), ensure_ascii=False, indent=1)
tdir = os.path.join(R, "assets/twixxel/textures/block/armario"); os.makedirs(tdir, exist_ok=True)
for w in W:
    for n in ("front_bottom","front_top","open_bottom","open_top","side"):
        shutil.copy(f"{SRC}/blocks/armario/{w}_{n}.png", f"{tdir}/{w}_{n}.png")
    T = lambda n: f"twixxel:block/armario/{w}_{n}"
    for half in ("bottom","top"):
        for op, pre in ((False,"front"),(True,"open")):
            name = f"armario_{w}_{'open_' if op else ''}{half}"
            wr(f"assets/twixxel/models/block/{name}.json", {"parent":"minecraft:block/cube","textures":{
                "particle":T("side"),"down":T("side"),"up":T("side"),"north":T(f"{pre}_{half}"),
                "east":T("side"),"south":T("side"),"west":T("side")}})
    variants = {}
    for fy, ang in (("north",0),("east",90),("south",180),("west",270)):
        for half in ("lower","upper"):
            for op in ("false","true"):
                m = f"twixxel:block/armario_{w}_{'open_' if op=='true' else ''}{'bottom' if half=='lower' else 'top'}"
                v = {"model": m}
                if ang: v["y"] = ang
                variants[f"facing={fy},half={half},open={op}"] = v
    wr(f"assets/twixxel/blockstates/armario_{w}.json", {"variants": variants})
    wr(f"assets/twixxel/models/item/armario_{w}.json", {"parent": f"twixxel:block/armario_{w}_bottom"})
    wr(f"data/twixxel/loot_tables/blocks/armario_{w}.json", {"type":"minecraft:block","pools":[{"rolls":1,"bonus_rolls":0,
        "entries":[{"type":"minecraft:item","name":f"twixxel:armario_{w}"}],
        "conditions":[{"condition":"minecraft:block_state_property","block":f"twixxel:armario_{w}","properties":{"half":"lower"}},
                      {"condition":"minecraft:survives_explosion"}]}]})
    plank = {"pale_oak":"minecraft:stripped_birch_log"}.get(w, f"minecraft:{W[w][1]}_planks")
    wr(f"data/twixxel/recipes/armario_{w}.json", {"type":"minecraft:crafting_shaped","category":"building",
        "pattern":["###","###","###"],"key":{"#":{"item":plank}},"result":{"item":f"twixxel:armario_{w}","count":1}})
wr("data/minecraft/tags/blocks/mineable/axe.json", {"replace":False,"values":[f"twixxel:armario_{w}" for w in W]})
# item de Verity
shutil.copy(f"{SRC}/items/verity.png", f"{R}/assets/twixxel/textures/item/verity_peluche.png")
wr("assets/twixxel/models/item/verity_peluche.json", {"parent":"minecraft:item/generated","textures":{"layer0":"twixxel:item/verity_peluche"}})
# lang
for f, es in (("en_us.json",False),("es_es.json",True),("es_mx.json",True)):
    p = f"{R}/assets/twixxel/lang/{f}"; d = json.load(open(p))
    for w in W:
        d[f"block.twixxel.armario_{w}"] = (f"Armario de {W[w][0].lower()}" if es else f"{EN[w]} Wardrobe")
    d["item.twixxel.verity_peluche"] = "Peluche de Verity" if es else "Verity Plushie"
    d["entity.twixxel.verity"] = "Verity"
    json.dump(d, open(p,"w"), ensure_ascii=False, indent=1)
