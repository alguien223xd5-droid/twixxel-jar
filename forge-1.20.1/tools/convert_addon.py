#!/usr/bin/env python3
"""
Convierte el addon de Bedrock "parralel stalker" (RP + BP) en los recursos de un mod
de Forge 1.20.1 + GeckoLib 4.

Uso:  python3 tools/convert_addon.py <carpeta_addon_extraida> <carpeta_resources_destino>
      (carpeta_addon_extraida contiene RP/ y BP/;  destino = src/main/resources)
"""
import json, os, re, shutil, sys, collections

SRC = sys.argv[1]
DST = sys.argv[2]
NS = "twixxel"
RP = os.path.join(SRC, "RP")
BP = os.path.join(SRC, "BP")
ASSETS = os.path.join(DST, "assets", NS)
DATA = os.path.join(DST, "data", NS)

warnings = []


def warn(m):
    warnings.append(m)
    print("WARN:", m)


def _strip_json5(t):
    """Quita comentarios // y /* */ y comas finales (los modelos nuevos los traen)."""
    out, i, n, ins = [], 0, len(t), False
    while i < n:
        c = t[i]
        if ins:
            out.append(c)
            if c == "\\" and i + 1 < n:
                out.append(t[i + 1]); i += 1
            elif c == '"':
                ins = False
        elif c == '"':
            ins = True; out.append(c)
        elif c == "/" and i + 1 < n and t[i + 1] == "/":
            while i < n and t[i] != "\n": i += 1
            continue
        elif c == "/" and i + 1 < n and t[i + 1] == "*":
            i = t.index("*/", i + 2) + 1
        else:
            out.append(c)
        i += 1
    return re.sub(r",(\s*[}\]])", r"\1", "".join(out))


def jload(p):
    with open(p, encoding="utf-8-sig") as f:
        t = f.read()
    try:
        return json.loads(t)
    except json.JSONDecodeError:
        return json.loads(_strip_json5(t))


def jdump(p, o, indent=None):
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w", encoding="utf-8") as f:
        json.dump(o, f, indent=indent, ensure_ascii=False)


def shortid(s):
    return s.split(":", 1)[-1]


# --------------------------------------------------------------------------
# Molang
# --------------------------------------------------------------------------
EXPR_HINT = re.compile(r"(query\.|math\.|\bq\.|\bv\.|variable\.|\bt\.|temp)")


def fix_molang(s):
    """Convierte Molang de Bedrock a lo que entiende GeckoLib (query.property -> variable.p_x)."""
    if not isinstance(s, str) or not EXPR_HINT.search(s):
        return s
    # propiedades -> query.twx_<prop>; variables de pre_animation (v.x) -> query.twxv_<x>
    # (el mod las registra y las actualiza en cada frame: ver MolangBridge.java)
    s = re.sub(r"query\.property\('" + NS + r":([a-z_0-9]+)'\)", r"query.twx_\1", s)
    s = re.sub(r"\bq\.", "query.", s)
    s = re.sub(r"\b(?:v|variable)\.([a-z_0-9]+)", r"query.twxv_\1", s)
    s = re.sub(r"\btrue\b", "1", s)
    s = re.sub(r"\bfalse\b", "0", s)
    return s


def fix_tree(x):
    if isinstance(x, str):
        return fix_molang(x)
    if isinstance(x, list):
        return [fix_tree(i) for i in x]
    if isinstance(x, dict):
        return {k: fix_tree(v) for k, v in x.items()}
    return x


# --------------------------------------------------------------------------
# 1. Modelos (un .geo.json por geometria)
# --------------------------------------------------------------------------
def convert_geo():
    out = os.path.join(ASSETS, "geo")
    os.makedirs(out, exist_ok=True)
    names = set()
    d = os.path.join(RP, "models", "entity")
    for f in sorted(os.listdir(d)):
        if not f.endswith(".json"):
            continue
        doc = jload(os.path.join(d, f))
        for g in doc.get("minecraft:geometry", []):      # formatos viejos (vanilla / Classic Aspect): se omiten
            ident = g["description"]["identifier"]
            name = ident.replace("geometry.", "", 1).lower()
            single = {"format_version": doc["format_version"], "minecraft:geometry": [g]}
            jdump(os.path.join(out, name + ".geo.json"), single)
            names.add(name)
    print("modelos:", len(names))
    return names


# --------------------------------------------------------------------------
# 2. Animaciones y controladores
# --------------------------------------------------------------------------
def load_animations():
    idx = {}
    d = os.path.join(RP, "animations")
    for f in sorted(os.listdir(d)):
        if not f.endswith(".json"):
            continue
        for n, a in jload(os.path.join(d, f)).get("animations", {}).items():
            idx[n] = a
    return idx


def load_controllers():
    idx = {}
    d = os.path.join(RP, "animation_controllers")
    for f in sorted(os.listdir(d)):
        if not f.endswith(".json"):
            continue
        for n, c in jload(os.path.join(d, f)).get("animation_controllers", {}).items():
            idx[n] = c
    return idx


def load_render_controllers():
    idx = {}
    d = os.path.join(RP, "render_controllers")
    for f in sorted(os.listdir(d)):
        if f.endswith(".json"):
            idx.update(jload(os.path.join(d, f)).get("render_controllers", {}))
    return idx


# --------------------------------------------------------------------------
# 3. Entidades
# --------------------------------------------------------------------------
BEHAVIOR = {
    "stalker_bipedal": "watcher",
    "stalker_evil": "chaser",
    "stalker_goatevil": "chaser",
    "stalker_quad_red": "chaser",
    "stalker_door": "door",
}


def parse_selector(value, arrays, entity_keys):
    """'Array.geo[expr]' -> {'list':[claves], 'expr':expr}   |  'Geometry.default' -> {'list':['default']}"""
    m = re.match(r"Array\.([a-z_]+)\[(.*)\]$", value)
    if m:
        arr = arrays[("Array." + m.group(1))]
        keys = [k.split(".", 1)[1] for k in arr]
        return {"list": keys, "expr": m.group(2)}
    return {"list": [value.split(".", 1)[1]]}


CLIENT = {}


def load_client_entities():
    d = os.path.join(RP, "entity")
    for f in sorted(os.listdir(d)):
        if f.endswith(".json"):
            desc = jload(os.path.join(d, f))["minecraft:client_entity"]["description"]
            CLIENT[shortid(desc["identifier"])] = desc


def convert_entities(geo_names, anim_idx, ctl_idx, rc_idx):
    item_tex = jload(os.path.join(RP, "textures", "item_texture.json"))["texture_data"]
    defs = []
    anim_out = os.path.join(ASSETS, "animations")
    os.makedirs(anim_out, exist_ok=True)

    for f in sorted(os.listdir(os.path.join(BP, "entities"))):
        bp = jload(os.path.join(BP, "entities", f))["minecraft:entity"]
        desc = bp["description"]
        eid = shortid(desc["identifier"])
        cl = CLIENT.get(eid)
        if cl is None:
            warn("sin definicion de cliente: " + eid)
            continue

        comps = dict(bp.get("components", {}))
        for g in bp.get("component_groups", {}).values():
            for k, v in g.items():
                comps.setdefault(k, v)
        cb = comps.get("minecraft:collision_box", {"width": 0.6, "height": 1.8})
        gravity = comps.get("minecraft:physics", {}).get("has_gravity", True)

        props = {}
        for pn, p in desc.get("properties", {}).items():
            t = p["type"]
            entry = {"type": t, "default": p.get("default", 0)}
            if t in ("int", "float"):
                entry["min"], entry["max"] = p["range"]
            if t == "bool":
                entry["default"] = 1 if p.get("default") else 0
            props[shortid(pn)] = entry

        # geometrias y texturas
        geos = {}
        for k, v in cl.get("geometry", {}).items():
            n = v.replace("geometry.", "", 1).lower()
            if n not in geo_names:
                warn("%s: geometria desconocida %s" % (eid, v))
            geos[k] = n
        texs = {k: (v.split("textures/entity/", 1)[1] if "textures/entity/" in v else v.split("/")[-1]).lower()
                for k, v in cl.get("textures", {}).items()}

        rc = rc_idx.get((cl.get("render_controllers") or [""])[0], {})
        arrays = {}
        for kind in rc.get("arrays", {}).values():
            arrays.update(kind)
        geo_sel = parse_selector(rc.get("geometry", "Geometry.default"), arrays, geos)
        tex_sel = parse_selector((rc.get("textures") or ["Texture.default"])[0], arrays, texs)

        scripts = cl.get("scripts", {})
        pre = [s.strip().rstrip(";") for s in scripts.get("pre_animation", [])]
        scale = str(scripts.get("scale", "1"))

        # animaciones: nombres cortos -> largos
        shorts = cl.get("animations", {})
        used = {}
        controllers = {}
        for sn, full in shorts.items():
            if full.startswith("controller."):
                c = ctl_idx.get(full)
                if not c:
                    warn("%s: controlador no encontrado %s" % (eid, full))
                    continue
                controllers[sn] = c
            else:
                a = anim_idx.get(full)
                if a is None:
                    warn("%s: animacion no encontrada %s" % (eid, full))
                    continue
                used[full] = a

        def anim_len(full):
            a = used.get(full)
            return float(a.get("animation_length", 0)) if a else 0.0

        def short_to_full(sn):
            return shorts.get(sn)

        animate = []
        for item in scripts.get("animate", []):
            if isinstance(item, dict):
                (sn, expr), = item.items()
            else:
                sn, expr = item, None
            full = short_to_full(sn)
            if sn in controllers:
                c = controllers[sn]
                states = {}
                for stn, st in c["states"].items():
                    al = []
                    for a in st.get("animations", []):
                        if isinstance(a, dict):
                            (asn, aexpr), = a.items()
                        else:
                            asn, aexpr = a, None
                        af = short_to_full(asn)
                        if af is None:
                            warn("%s: animacion '%s' sin mapear" % (eid, asn))
                            continue
                        al.append({"name": af, "cond": aexpr})
                    tr = []
                    for t in st.get("transitions", []):
                        (tgt, texpr), = t.items()
                        tr.append([tgt, texpr])
                    states[stn] = {
                        "anims": al,
                        "trans": tr,
                        "blend": st.get("blend_transition", 0.0),
                        "len": max([anim_len(x["name"]) for x in al] or [0.0]),
                    }
                animate.append({"controller": {"initial": c["initial_state"], "states": states}, "cond": expr})
            elif full:
                animate.append({"anim": full, "cond": expr})

        # archivo de animacion de la entidad (todas las animaciones que usa)
        fixed = {}
        for n, a in used.items():
            a = fix_tree(a)
            if a.get("hold_on_last_frame") is True and "loop" not in a:
                a["loop"] = "hold_on_last_frame"
            a.pop("hold_on_last_frame", None)
            fixed[n] = a
        jdump(os.path.join(anim_out, eid + ".animation.json"),
              {"format_version": "1.8.0", "animations": fixed})

        egg = None
        se = cl.get("spawn_egg")
        if se and se.get("texture") in item_tex:
            egg = item_tex[se["texture"]]["textures"].replace("textures/items/", "")

        defs.append({
            "id": eid,
            "w": cb["width"],
            "h": cb["height"],
            "gravity": bool(gravity),
            "behavior": BEHAVIOR.get(eid, "idle"),
            "egg": egg,
            "geos": geos,
            "texs": texs,
            "geoSel": geo_sel,
            "texSel": tex_sel,
            "scale": scale,
            "pre": pre,
            "props": props,
            "hasAnim": bool(used),
            "animate": animate,
        })
    return defs


# --------------------------------------------------------------------------
# 4. Texturas, sonidos, lang, items
# --------------------------------------------------------------------------
def copy_textures(defs):
    src = os.path.join(RP, "textures", "entity")
    dst = os.path.join(ASSETS, "textures", "entity")
    os.makedirs(dst, exist_ok=True)
    # solo las texturas que usan nuestras entidades (el resto es de Classic Aspect / vanilla: NO se porta)
    for d in defs:
        for t in d["texs"].values():
            f = os.path.join(src, t + ".png")
            if not os.path.exists(f):
                f2 = [x for x in (os.path.join(src, t.split("/")[-1] + ".png"),) if os.path.exists(x)]
                f = f2[0] if f2 else None
            if f:
                tgt = os.path.join(dst, t + ".png")
                os.makedirs(os.path.dirname(tgt), exist_ok=True)
                shutil.copy(f, tgt)
    # items + huevos
    src = os.path.join(RP, "textures", "items")
    dst = os.path.join(ASSETS, "textures", "item")
    # solo los iconos de NUESTROS objetos y huevos (el resto de textures/items es de Classic Aspect / vanilla: NO se porta)
    itx = jload(os.path.join(RP, "textures", "item_texture.json"))["texture_data"]
    keep = set(d["egg"] for d in defs if d["egg"])
    bp_items = os.path.join(BP, "items")
    for fn in os.listdir(bp_items) if os.path.isdir(bp_items) else []:
        ic = jload(os.path.join(bp_items, fn))["minecraft:item"]["components"].get("minecraft:icon")
        k = ic if isinstance(ic, str) else ((ic or {}).get("textures", {}).get("default") or (ic or {}).get("texture"))
        v = itx.get(k, {}).get("textures")
        v = v[0] if isinstance(v, list) else v
        if v:
            keep.add(v.replace("textures/items/", ""))
    for t in keep:
        f = os.path.join(src, t + ".png")
        if os.path.exists(f):
            os.makedirs(os.path.dirname(os.path.join(dst, t + ".png")), exist_ok=True)
            shutil.copy(f, os.path.join(dst, t + ".png"))
    # modelos de item
    for d in defs:
        if d["egg"]:
            jdump(os.path.join(ASSETS, "models", "item", d["id"] + "_spawn_egg.json"),
                  {"parent": "minecraft:item/generated",
                   "textures": {"layer0": "%s:item/%s" % (NS, d["egg"])}})
    for it in ("mascot", "pet2", "pet3", "zoom"):
        jdump(os.path.join(ASSETS, "models", "item", it + ".json"),
              {"parent": "minecraft:item/handheld" if it == "zoom" else "minecraft:item/generated",
               "textures": {"layer0": "%s:item/%s" % (NS, it)}})
    # comprobar texturas
    for d in defs:
        for t in d["texs"].values():
            if not os.path.exists(os.path.join(ASSETS, "textures", "entity", t + ".png")):
                warn("%s: falta la textura %s.png" % (d["id"], t))


KEEP_SND = ("stalker.", "mascota", "twixxel.")     # lo demas es de Classic Aspect (HecXD) / vanilla: NO se porta


def convert_sounds():
    sdir = os.path.join(RP, "sounds")
    ddir = os.path.join(ASSETS, "sounds")
    sd = jload(os.path.join(sdir, "sound_definitions.json"))["sound_definitions"]
    out = {}
    for ev, v in sd.items():
        if not ev.startswith(KEEP_SND):
            continue
        entries = []
        for s in v["sounds"]:
            if isinstance(s, str):
                s = {"name": s}
            name = s["name"].replace("sounds/", "", 1)
            src = os.path.join(sdir, name + ".ogg")
            if not os.path.exists(src):
                warn("sonido sin archivo: %s (%s)" % (ev, name))
                continue
            t = os.path.join(ddir, name + ".ogg")
            os.makedirs(os.path.dirname(t), exist_ok=True)
            shutil.copy(src, t)
            e = {"name": "%s:%s" % (NS, name), "volume": min(float(s.get("volume", 1.0)), 1.0)}
            if s.get("stream"):
                e["stream"] = True
            entries.append(e)
        if entries:
            out[ev] = {"sounds": entries}
    jdump(os.path.join(ASSETS, "sounds.json"), out, indent=1)
    jdump(os.path.join(DATA, "sound_events.json"), sorted(out.keys()))
    print("sonidos:", len(out))


def convert_lang(defs):
    for lf, jf in (("en_US", "en_us"), ("es_ES", "es_es"), ("es_MX", "es_mx")):
        out = {}
        with open(os.path.join(RP, "texts", lf + ".lang"), encoding="utf-8") as fh:
            for line in fh:
                line = line.rstrip("\n")
                if "=" not in line or line.lstrip().startswith("#"):
                    continue
                k, v = line.split("=", 1)
                v = v.split("\t#")[0].strip()
                m = re.match(r"item\.spawn_egg\.entity\.%s:(.+)\.name$" % NS, k)
                if m:
                    out["item.%s.%s_spawn_egg" % (NS, m.group(1))] = v
                    continue
                m = re.match(r"entity\.%s:(.+)\.name$" % NS, k)
                if m:
                    out["entity.%s.%s" % (NS, m.group(1))] = v
                    continue
                m = re.match(r"item\.%s:(.+)\.name$" % NS, k)
                if m:
                    out["item.%s.%s" % (NS, m.group(1))] = v
        for d in defs:
            out.setdefault("entity.%s.%s" % (NS, d["id"]), d["id"].replace("_", " ").title())
        out["itemGroup.%s.main" % NS] = "Parallel Stalker"
        jdump(os.path.join(ASSETS, "lang", jf + ".json"), out, indent=1)


def copy_recipe():
    jdump(os.path.join(DATA, "recipes", "zoom.json"), {
        "type": "minecraft:crafting_shapeless",
        "ingredients": [{"item": "minecraft:stick"}] * 3,
        "result": {"item": NS + ":zoom"}})


def main():
    geo_names = convert_geo()
    load_client_entities()
    anim_idx = load_animations()
    ctl_idx = load_controllers()
    rc_idx = load_render_controllers()
    defs = convert_entities(geo_names, anim_idx, ctl_idx, rc_idx)
    copy_textures(defs)
    # modelos: solo los que usan nuestras entidades
    used = set()
    for d in defs:
        used.update(v.lower() for v in d["geos"].values())
    gd = os.path.join(ASSETS, "geo")
    for f in os.listdir(gd):
        if f[:-len(".geo.json")] not in used:
            os.remove(os.path.join(gd, f))
    convert_sounds()
    convert_lang(defs)
    copy_recipe()
    jdump(os.path.join(DATA, "entity_defs.json"), defs)
    print("entidades:", len(defs), " avisos:", len(warnings))


if __name__ == "__main__":
    main()
