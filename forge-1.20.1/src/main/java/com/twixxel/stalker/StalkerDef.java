package com.twixxel.stalker;

import com.google.gson.annotations.SerializedName;
import com.twixxel.stalker.molang.Molang;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Definicion de una entidad, generada por tools/convert_addon.py (data/twixxel/entity_defs.json). */
public class StalkerDef {
    public String id;
    public double w, h;
    public boolean gravity;
    public String behavior;           // idle | watcher | chaser
    public String egg;                // textura del huevo (o null)
    public Map<String, String> geos;  // clave -> nombre de .geo.json
    public Map<String, String> texs;  // clave -> nombre de textura
    public Selector geoSel, texSel;
    public String scale;
    public List<String> pre;
    public Map<String, Prop> props;
    public boolean hasAnim;
    public List<Animate> animate;
    public List<List<String>> partVis;   // part_visibility del render controller: [patron, expresion]

    public static class Selector {
        public List<String> list;
        public String expr;
        public transient Molang.Expr compiled;
    }

    public static class Prop {
        public String type;
        @SerializedName("default")
        public double def;
        public Double min, max;
    }

    public static class Animate {
        public Controller controller;
        public String anim;
        public String cond;
        public transient Molang.Expr condE;

        public int layers() {
            if (controller == null) return 1;
            int m = 1;
            for (State s : controller.states.values()) m = Math.max(m, s.anims.size());
            return m;
        }
    }

    public static class Controller {
        public String initial;
        public Map<String, State> states;
    }

    public static class State {
        public List<AnimRef> anims;
        public List<List<String>> trans;
        public double blend;
        public double len;
        public transient List<Trans> compiled = new ArrayList<>();
    }

    public static class AnimRef {
        public String name;
        public String cond;
        public transient Molang.Expr condE;
    }

    public static class Trans {
        public final String target;
        public final Molang.Expr expr;

        Trans(String target, Molang.Expr expr) {
            this.target = target;
            this.expr = expr;
        }
    }

    // ---- runtime (se rellena en init) ----
    public transient Molang.Expr scaleE;
    public transient List<Molang.Expr> partVisE = new ArrayList<>();
    public transient List<String> preNames = new ArrayList<>();
    public transient List<Molang.Expr> preExprs = new ArrayList<>();
    public transient Map<String, ResourceLocation> geoLoc = new HashMap<>();
    public transient Map<String, ResourceLocation> texLoc = new HashMap<>();
    public transient ResourceLocation animLoc;

    public void init() {
        scaleE = Molang.compile(scale == null ? "1" : scale);
        if (pre == null) pre = new ArrayList<>();
        for (String s : pre) {
            int eq = s.indexOf('=');
            if (eq < 0) continue;
            String name = s.substring(0, eq).trim().replaceFirst("^(v|variable)\\.", "");
            preNames.add(name);
            preExprs.add(Molang.compile(s.substring(eq + 1).trim()));
        }
        if (props == null) props = new HashMap<>();
        if (partVis != null) for (List<String> r : partVis) partVisE.add(Molang.compile(r.get(1)));
        if (animate == null) animate = new ArrayList<>();
        for (Selector sel : new Selector[]{geoSel, texSel}) {
            if (sel != null && sel.expr != null) sel.compiled = Molang.compile(sel.expr);
        }
        for (Animate a : animate) {
            a.condE = a.cond == null ? null : Molang.compile(a.cond);
            if (a.controller == null) continue;
            for (State s : a.controller.states.values()) {
                for (AnimRef r : s.anims) r.condE = r.cond == null ? null : Molang.compile(r.cond);
                for (List<String> t : s.trans) s.compiled.add(new Trans(t.get(0), Molang.compile(t.get(1))));
            }
        }
        for (Map.Entry<String, String> e : geos.entrySet())
            geoLoc.put(e.getKey(), new ResourceLocation(TwixxelMod.MODID, "geo/" + e.getValue() + ".geo.json"));
        for (Map.Entry<String, String> e : texs.entrySet())
            texLoc.put(e.getKey(), new ResourceLocation(TwixxelMod.MODID, "textures/entity/" + e.getValue().toLowerCase() + ".png"));
        animLoc = new ResourceLocation(TwixxelMod.MODID, "animations/" + id + ".animation.json");
    }
}
