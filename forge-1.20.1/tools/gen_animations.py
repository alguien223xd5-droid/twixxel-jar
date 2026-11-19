#!/usr/bin/env python3
"""
Genera keyframes ORIGINALES (propios) para las animaciones del port.
Conserva de cada animacion: nombre, duracion, loop y lista de huesos (la logica del mod depende de eso).
Reemplaza SOLO los canales de rotacion de los huesos de personaje (cabeza, cuello, brazos, piernas, cuerpo...).
No copia ni lee valores de los keyframes originales, salvo para conservar tal cual los canales funcionales
(Molang con query.twx_*), y los canales de position/scale y huesos de escena/estructura (root, escena, js_root, heridas, grietas...).
Uso: gen_animations.py <carpeta_animaciones_origen> <carpeta_salida>
"""
import json, glob, os, sys, math, hashlib, re

def rnd(*k):
    h = hashlib.md5("|".join(map(str, k)).encode()).digest()
    return int.from_bytes(h[:4], "big") / 2**32   # 0..1 determinista

def role(b):
    n = b.lower()
    if n in ("head_closed", "head_suelta", "run_head_roto", "face") or n.startswith("herida") or n.startswith("grieta") or "suelto" in n:
        return None
    if n.startswith("head") or n in ("cabeza", "crawl_head", "run_head") : return "head"
    if n in ("neck", "cuello"): return "neck"
    if n.startswith("forearm") or n.startswith("antebrazo"): return "fore"
    if n.startswith("arm") or n.startswith("brazo"): return "arm"
    if n.startswith("hand") or n.startswith("mano"): return "hand"
    if n.startswith("shin"): return "shin"
    if n.startswith("leg") or n.startswith("pierna"): return "leg"
    if n in ("body", "cuerpo", "torso", "half_l", "half_r"): return "body"
    return None

def side(b):
    n = b.lower()
    if n.endswith("_l") or n.endswith("_izq") or n.endswith("1") or n.endswith("3"): return -1
    if n.endswith("_r") or n.endswith("_der") or n.endswith("2") or n.endswith("4"): return 1
    return 1

def kind(name, loop):
    if "pelea" in name.lower() and "salto" not in name.lower(): return "fight"
    s = name.split(".")[-1].lower()
    has = lambda *w: any(x in s for x in w)
    if has("scream", "grito", "grita", "ruge", "terror", "shake"): return "scream"
    if has("run", "correr", "caminar", "walk", "gate", "crawl", "perseguir", "escalar", "subir") and loop is True: return "cycle"
    if has("look", "mirar"): return "look"
    if has("tilt"): return "tilt"
    if has("neck", "doblar", "cuello"): return "bend"
    if has("hang", "colgad", "colgando"): return "hang"
    if has("idle", "quieto", "espera", "parado", "oculto", "hold", "acecho", "inclinado", "encorvado", "debajo", "aplastado", "agarrado", "lado", "huesos", "cabeza") and loop is not False: return "idle"
    return "action" if loop is not True else "idle"

def f(x): return round(x, 2)

def gen(anim, bone, r, k, L, loop, an):
    A = 0.6 + 0.8 * rnd(an, bone, "a")           # amplitud 0.6..1.4
    ph = rnd(an, bone, "p") * 40
    sd = side(bone)
    per = L / max(1, round(L / (0.45 + 0.3 * rnd(an, "per"))))
    sin = lambda amp, off=0: f"math.sin((query.anim_time/{f(per)})*360+{int(off)})*{f(amp)}"
    breath = lambda amp: f"math.sin((query.anim_time/{f(max(L,1.5)/1)})*360+{int(ph)})*{f(amp)}"
    if k == "look":
        if r == "head": return ["query.twx_pitch", "query.twx_yaw", f"math.sin(query.life_time*80+{int(ph)})*{f(1.5*A)}"]
        if r == "neck": return [f(2*A), 0, 0]
        return [0, 0, 0]
    if k == "cycle":
        if r in ("leg",):   return [sin(26 * A, 0 if sd < 0 else 180), 0, 0]
        if r == "shin":     return [f"math.max(0,{sin(30*A, 90 if sd<0 else 270)})", 0, 0]
        if r == "arm":      return [sin(30 * A, 180 if sd < 0 else 0), 0, f(sd * 4)]
        if r == "fore":     return [f"-18-math.abs({sin(14*A, 0 if sd<0 else 180)})", 0, 0]
        if r == "hand":     return [sin(10 * A, 60), 0, 0]
        if r == "body":     return [f(8 * A), sin(4 * A, 0), 0]
        if r in ("head", "neck"): return [f(-6 * A), sin(3 * A, 90), 0]
    if k == "scream":
        sh = f"math.sin(query.anim_time*{int(1400+300*rnd(an,'sh'))})*{f(2.5*A)}"
        t = {"0": [0, 0, 0], f(L * 0.2): None, f(L): None}
        if r == "head":
            return {"0": [0, 0, 0], str(f(L*0.18)): [f(-42*A), 0, 0], str(f(L)): [f(-38*A), 0, 0]} if loop is not True else [f(-40*A), sh, 0]
        if r == "neck": return [f(-12*A), sh, 0]
        if r == "arm":  return [f(-100*A), 0, f(sd * 22)]
        if r == "fore": return [f(-30*A), 0, 0]
        if r == "hand": return [0, 0, f(sd * 15)]
        if r == "body": return [f(-10*A), sh, 0]
        if r in ("leg", "shin"): return [f(sd * 6), 0, f(sd * 4)]
    if k == "fight":
        # el atacante sostiene los brazos hacia delante (alcanza a la victima); la victima los levanta a la defensiva
        att = "stalker_goat" in an or "pelea_run" in an
        up = -82 if att else -48
        t1 = str(f(L * 0.25))
        wob = lambda amp, off=0: f"math.sin((query.anim_time/{f(max(L,1.2)/2)})*360+{off})*{f(amp)}"
        if r == "arm":  return {"0": [0, 0, f(sd*5)], t1: [f(up*A/1.0 if att else up), 0, f(sd*10)], str(f(L)): [f(up), 0, f(sd*10)]} if False else [f(up) + "+" + wob(7*A, 0 if sd < 0 else 180) if False else f"{f(up)}+{wob(7*A, 0 if sd < 0 else 180)}", 0, f(sd * 10)]
        if r == "fore": return [f(-22 if att else -30), 0, 0]
        if r == "hand": return [wob(8, 40), 0, 0]
        if r == "body": return [f(12 if att else -6), wob(3*A, 30), 0]
        if r in ("head", "neck"): return [f(-8 if att else 14), wob(5*A, 90), 0]
        if r in ("leg", "shin"): return [f(sd * 6), 0, f(sd * 4)]
    if k == "tilt":
        if r == "head": return [f(-6*A), 0, f(26 * A)] if loop is not True else [f(-6*A), 0, breath(26*A)]
        if r == "neck": return [0, 0, f(10 * A)]
    if k == "bend":
        if r in ("head", "neck"): return [f(38 * A), 0, f(-6 * sd)]
        if r == "body": return [f(18 * A), 0, 0]
    if k == "hang":
        if r == "arm":  return [f(-170 + 6*A), 0, f(sd * 10)]
        if r == "fore": return [f(-8*A), 0, 0]
        if r == "body": return [breath(3*A), 0, breath(2*A)]
        if r in ("leg", "shin"): return [breath(5*A), 0, f(sd*3)]
        if r in ("head", "neck"): return [f(10*A), breath(6*A), 0]
    if k == "idle":
        if r in ("head", "neck"): return [breath(2.5*A), breath(5*A), 0]
        if r == "body": return [f(4*A), 0, breath(1.5*A)]
        if r == "arm":  return [breath(4*A), 0, f(sd * 6)]
        if r == "fore": return [f(-12*A), 0, 0]
        if r == "hand": return [breath(5), 0, 0]
        if r in ("leg", "shin"): return [f(sd * 2), 0, breath(1.5)]
    # accion de un solo disparo: preparar -> golpe -> asentar
    t1, t2 = f(L * 0.35), f(L * 0.62)
    ts = lambda a, b, c, d: {"0": a, str(t1): b, str(t2): c, str(f(L)): d}
    if r == "arm":  return ts([0, 0, f(sd*5)], [f(-85*A), 0, f(sd*18)], [f(35*A), 0, f(sd*8)], [f(-10*A), 0, f(sd*6)])
    if r == "fore": return ts([0, 0, 0], [f(-55*A), 0, 0], [f(-8*A), 0, 0], [f(-20*A), 0, 0])
    if r == "hand": return ts([0, 0, 0], [f(-20*A), 0, 0], [f(15*A), 0, 0], [0, 0, 0])
    if r in ("head", "neck"): return ts([0, 0, 0], [f(-14*A), 0, f(sd*4)], [f(18*A), 0, 0], [f(6*A), 0, 0])
    if r == "body": return ts([0, 0, 0], [f(-10*A), f(sd*8), 0], [f(16*A), f(-sd*10), 0], [f(5*A), 0, 0])
    if r in ("leg", "shin"): return ts([0, 0, 0], [f(sd*-12*A), 0, 0], [f(sd*14*A), 0, 0], [0, 0, 0])
    return None

ROOTS = ("root", "raiz", "escena", "js_root", "bone", "bone3", "piernas", "arm_n", "arm_m")
def is_root(b): return b.lower() in ROOTS or b.lower().startswith("bone")

def gen_extra(an, b, k, L, loop):
    """rotation/position/scale propios para huesos de escena/estructura y para position/scale de los demas."""
    A = 0.6 + 0.8 * rnd(an, b, "x")
    per = L / max(1, round(L / (0.5 + 0.3 * rnd(an, "per2"))))
    s_ = lambda amp, off=0: f"math.sin((query.anim_time/{f(per)})*360+{int(off)})*{f(amp)}"
    slow = lambda amp, off=0: f"math.sin((query.anim_time/{f(max(L,1.5))})*360+{int(off)})*{f(amp)}"
    t1, t2 = str(f(L * 0.35)), str(f(L * 0.65))
    out = {}
    if is_root(b):
        if k == "cycle":
            out["position"] = [0, f"math.abs({s_(1.4*A)})", 0]; out["rotation"] = [0, s_(2*A, 90), 0]
        elif k in ("idle", "hang", "look", "tilt", "bend"):
            out["position"] = [0, slow(0.6*A), 0]; out["rotation"] = [0, 0, slow(1.2*A, 40)]
        elif k == "scream":
            out["position"] = [f"math.sin(query.anim_time*1300)*{f(0.5*A)}", 0, 0]; out["rotation"] = [f(-4*A), 0, 0]
        else:
            out["position"] = {"0": [0, 0, 0], t1: [0, f(-1.2*A), f(2.5*A)], t2: [0, f(1.5*A), f(-6*A)], str(f(L)): [0, 0, f(-2*A)]}
            out["rotation"] = {"0": [0, 0, 0], t1: [f(-6*A), 0, 0], t2: [f(10*A), 0, 0], str(f(L)): [f(3*A), 0, 0]}
        if any(w in an.lower() for w in ("asom", "salir", "aparec", "romper", "open", "subir", "ventana")):
            out["scale"] = {"0": [f(0.3+0.2*A/2), f(0.3+0.2*A/2), f(0.3+0.2*A/2)], str(f(L * 0.3)): [1, 1, 1], str(f(L)): [1, 1, 1]}
    else:
        r_ = rnd(an, b, "j")
        out["rotation"] = [slow(2*A, int(r_*60)), slow(3*A, int(r_*90)), slow(2*A, 20)]
    return out

TOK = re.compile(r"query\.twxv?_[a-z_0-9]+")
def rewrite_functional(v, an, b):
    """Reescribe con expresion propia un canal funcional: solo conserva el NOMBRE de la variable del juego."""
    if isinstance(v, dict) or not isinstance(v, list): return v
    out = []
    for i, c in enumerate(v):
        m = TOK.search(json.dumps(c))
        if m: out.append(m.group(0))
        elif isinstance(c, str): out.append(f"math.sin(query.life_time*{60+int(rnd(an,b,i)*40)}+{int(rnd(an,b,'f',i)*90)})*{f(1+rnd(an,b,i))}")
        else: out.append(c)
    return out

def pos_envelope(orig_pos, L, loop):
    """Alcance/recorrido de la posicion (inicio, maximo, final) para que escenas que dependen de la distancia
    (asomarse, salir de la ventana, alcanzar) sigan llegando. La forma de la curva y los tiempos son propios."""
    vecs = []
    if isinstance(orig_pos, dict):
        for t, v in sorted(orig_pos.items(), key=lambda x: float(x[0])):
            if isinstance(v, list) and all(isinstance(c, (int, float)) for c in v): vecs.append(v)
    elif isinstance(orig_pos, list) and all(isinstance(c, (int, float)) for c in orig_pos): vecs.append(orig_pos)
    if not vecs: return None
    peak = max(vecs, key=lambda v: sum(c * c for c in v))
    if sum(c * c for c in peak) < 1: return None
    if loop is True:
        return [f"math.sin((query.anim_time/{f(max(L,0.5))})*360)*{f(c*0.5)}" for c in peak]
    first, last = vecs[0], vecs[-1]
    return {"0": [f(c) for c in first], str(f(L * 0.6)): [f(c) for c in peak], str(f(L)): [f(c) for c in last]}

def process(src, dst):
    j = json.load(open(src))
    changed = kept = 0
    for an, a in j["animations"].items():
        a["__orig"] = {b: ch.get("position") for b, ch in a.get("bones", {}).items() if "position" in ch}
        L = a.get("animation_length") or 1.0
        loop = a.get("loop")
        k = kind(an, loop)
        for b, ch in a.get("bones", {}).items():
            r = role(b)
            if r is not None and "rotation" in ch and "twx_" not in json.dumps(ch["rotation"]):
                v = gen(an, b, r, k, L, loop, an)
                if v is not None: ch["rotation"] = v; changed += 1
            elif r is None:
                ex = gen_extra(an, b, k, L, loop)
                if "rotation" in ch and "twx_" not in json.dumps(ch["rotation"]) and "rotation" in ex: ch["rotation"] = ex["rotation"]; changed += 1
                elif "rotation" in ch: kept += 1
                if "position" in ch: ch["position"] = ex.get("position", [0, 0, 0]); changed += 1
            # position / scale de huesos de personaje: propios (cuerpo respira, el resto 0)
            if r is not None and "position" in ch:
                ch["position"] = [0, f"math.abs(math.sin((query.anim_time/{f(max(L,1.0))})*360))*0.5", 0] if r == "body" else [0, 0, 0]; changed += 1
            if "rotation" in ch and "twx_" in json.dumps(ch["rotation"]):
                ch["rotation"] = rewrite_functional(ch["rotation"], an, b); changed += 1
            if "scale" in ch:
                sc = ch["scale"]
                if isinstance(sc, str) or "query" in json.dumps(sc):
                    ch["scale"] = rewrite_functional(sc, an, b) if isinstance(sc, list) else sc; kept += 1
                else:
                    ex = gen_extra(an, b, k, L, loop) if is_root(b) else {}
                    if "scale" in ex: ch["scale"] = ex["scale"]
                    else: del ch["scale"]
                    changed += 1
    for a in j["animations"].values():
        o = a.pop("__orig", {})
        L = a.get("animation_length") or 1.0
        for b, op in o.items():
            env = pos_envelope(op, L, a.get("loop"))
            if env is not None and "position" in a["bones"][b]: a["bones"][b]["position"] = env
    json.dump(j, open(dst, "w"), indent=1)
    return changed, kept

if __name__ == "__main__":
    srcd, dstd = sys.argv[1], sys.argv[2]
    os.makedirs(dstd, exist_ok=True)
    tc = tk = 0
    for p in sorted(glob.glob(os.path.join(srcd, "*.json"))):
        c, k = process(p, os.path.join(dstd, os.path.basename(p)))
        tc += c; tk += k
    print("canales nuevos:", tc, "| conservados (escena/estructura/funcionales/pos/escala):", tk)
