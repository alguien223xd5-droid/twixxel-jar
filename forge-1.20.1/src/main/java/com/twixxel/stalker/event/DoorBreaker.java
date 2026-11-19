package com.twixxel.stalker.event;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.twixxel.stalker.TwixxelMod;
import com.twixxel.stalker.entity.StalkerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Los stalkers que te persiguen ya no atraviesan las puertas: se paran frente a la puerta, hacen SU animacion de romperla
 * (golpes, astillas), la rompen y siguen persiguiendote. Tabla por stalker en data/twixxel/rompe_puertas.json.
 */
public final class DoorBreaker {
    private static final int LEN = 38;
    private static JsonObject TAB;
    private static final Random RND = new Random();

    private static final class S {
        double lx, ly, lz;
        int cool;
        Br br;
        Dk dk;
    }

    private static final class Br {
        BlockPos d;
        Vec3 c, h;
        double ax, az;
        float yaw;
        int t;
    }


    /** Arrastre (Bedrock dk*): toca fuerte, te agarra de las patas, te arrastra afuera; 10 golpes en la cara de otro jugador lo detienen. */
    private static final class Dk {
        String phase = "knock";
        BlockPos d;
        Vec3 c, h, from;
        double ax, az, total, dist, y, gap;
        float yaw;
        int t, pt, hits, hitT = -10;
        UUID victim;
        java.util.List<Vec3> path;
    }

    private static final Map<String, Double> REACH = new HashMap<>();
    static {
        REACH.put("stalker_evil", 5.5d);
        REACH.put("stalker_goatevil", 4.03d);
        REACH.put("stalker_quad_red", 3.93d);
        REACH.put("stalker_goat", 4.56d);
        REACH.put("stalker_spotted", 2.86d);
        REACH.put("stalker_ansiedad", 3.52d);
        REACH.put("stalker_smiler", 2.7d);
        REACH.put("stalker_unknown", 2.7d);
        REACH.put("stalker_run", 6.03d);
        REACH.put("stalker_screamer", 4.66d);
        REACH.put("stalker_hello", 5.73d);
        REACH.put("stalker_redlight", 2.7d);
        REACH.put("stalker_tortura", 3.15d);
        REACH.put("stalker_vida", 2.7d);
        REACH.put("stalker_jester", 2.7d);
        REACH.put("stalker_goat_jester", 2.7d);
        REACH.put("stalker_luz", 2.7d);
        REACH.put("stalker_miron", 4.66d);
        REACH.put("stalker_cueva", 6.03d);
        REACH.put("stalker_solocorre", 2.7d);
        REACH.put("stalker_diversion", 6.65d);
        REACH.put("stalker_run_mina", 6.03d);
        REACH.put("stalker_run_pasillo", 6.03d);
        REACH.put("stalker_asomandome", 6.03d);
        REACH.put("stalker_run_asoma", 6.03d);
        REACH.put("stalker_alcantarilla", 6.03d);
        REACH.put("stalker_alcantarilla_goat", 4.56d);
        REACH.put("stalker_goatevil_esc", 4.03d);
        REACH.put("stalker_watcher", 4.5d);
        REACH.put("stalker_watcher2", 4.5d);
        REACH.put("stalker_envuelto_acerca", 3.5d);
        REACH.put("stalker_lc", 2.8d);
        REACH.put("stalker_trepador", 2.4d);
        REACH.put("stalker_maebi", 2.6d);
        REACH.put("stalker_cuernos", 2.8d);
        REACH.put("stalker_craneo", 2.8d);
        REACH.put("evilblack", 2.7d);
        REACH.put("stalker_ignore", 3.0d);
        REACH.put("stalker_quad", 3.93d);
    }
    private static final double DK_NEAR = 1.6, DK_SPEED = .055, DK_OUT = 7, DK_LIFT = .45;
    private static final int DK_CYCLE = 52, DK_MAX = 3600, DK_POUNCE = 8, DK_HITS = 10, DK_STUN = 200;
    private static final double DK_CHANCE = .5;

    private static final Map<UUID, S> STATE = new HashMap<>();

    private static JsonObject tab() {
        if (TAB == null) {
            try (var in = DoorBreaker.class.getResourceAsStream("/data/twixxel/rompe_puertas.json")) {
                TAB = JsonParser.parseReader(new InputStreamReader(in, "UTF-8")).getAsJsonObject();
            } catch (Exception ex) {
                TAB = new JsonObject();
            }
        }
        return TAB;
    }

    private static void snd(ServerLevel l, String id, Vec3 at, float vol, float pitch) {
        var r = TwixxelMod.SOUND_EVENTS.get(id);
        if (r != null) l.playSound(null, at.x, at.y, at.z, r.get(), SoundSource.HOSTILE, vol, pitch);
    }

    private static void fx(ServerLevel l, BlockState st, Vec3 c, double ax, double az, int n, double speed) {
        l.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, st), c.x - ax * .35, c.y + 1, c.z - az * .35, n, .3, .5, .3, speed * .05);
    }

    /** Hay una puerta (mitad de abajo) delante, en la direccion en la que corre? */
    private static BlockPos doorAhead(ServerLevel l, Vec3 p, double ux, double uz, double reach) {
        for (double k = 0; k <= reach + .6; k += .25) {
            int bx = (int) Math.floor(p.x + ux * k), bz = (int) Math.floor(p.z + uz * k);
            for (int dy = 0; dy <= 1; dy++) {
                int by = (int) Math.floor(p.y + .2) + dy;
                BlockPos b = new BlockPos(bx, by, bz);
                if (!Houses.isDoor(l, b)) continue;
                return Houses.isDoor(l, b.below()) ? b.below() : b;
            }
        }
        return null;
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END) return;
        JsonObject tab = tab();
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        for (StalkerEntity e : StalkerEntity.ACTIVE) {
            if (!e.isAlive() || e.isScripted() || !(e.level() instanceof ServerLevel l)) continue;
            String key = "twixxel:" + e.getDefId();
            if (!tab.has(key)) continue;
            JsonObject cfg = tab.getAsJsonObject(key);
            seen.add(e.getUUID());
            S s = STATE.get(e.getUUID());
            Vec3 pos = e.position();
            if (s == null) {
                s = new S();
                s.lx = pos.x;
                s.ly = pos.y;
                s.lz = pos.z;
                STATE.put(e.getUUID(), s);
                e.setProp("rompe", 0);
                continue;
            }
            if (s.dk != null) {
                try {
                    tickDk(l, e, s, cfg);
                } catch (Exception ex) {
                    release(l, s.dk);
                    s.dk = null;
                    e.setProp("rompe", 0);
                }
                continue;
            }
            if (s.br != null) {
                try {
                    tick(l, e, s, cfg);
                } catch (Exception ex) {
                    s.br = null;
                    e.setProp("rompe", 0);
                }
                continue;
            }
            double dx = pos.x - s.lx, dz = pos.z - s.lz;
            s.lx = pos.x;
            s.ly = pos.y;
            s.lz = pos.z;
            if (s.cool > 0) {
                s.cool--;
                continue;
            }
            double sp = Math.hypot(dx, dz);
            if (sp < .06 || sp > 2.5) continue;                           // quieto o se teletransporto
            boolean near = false;
            for (ServerPlayer p : l.players()) if (Math.hypot(p.getX() - pos.x, p.getZ() - pos.z) < 48) {
                near = true;
                break;
            }
            if (!near) continue;
            double ux = dx / sp, uz = dz / sp;
            double hold = cfg.get("hold").getAsDouble();
            BlockPos d = doorAhead(l, pos, ux, uz, hold);
            if (d != null) {
                if (REACH.containsKey(key.substring(8)) && RND.nextDouble() < DK_CHANCE && startDk(e, s, cfg, d, ux, uz)) continue;
                start(e, s, cfg, d, ux, uz);
            }
        }
        var srv = ServerLifecycleHooks.getCurrentServer();
        Iterator<Map.Entry<UUID, S>> it = STATE.entrySet().iterator();
        while (it.hasNext()) {
            var en = it.next();
            if (seen.contains(en.getKey())) continue;
            if (en.getValue().dk != null && srv != null) release(srv.overworld(), en.getValue().dk);      // el stalker desaparecio: te suelta
            it.remove();
        }
    }

    private static void start(StalkerEntity e, S s, JsonObject cfg, BlockPos d, double ux, double uz) {
        double ax, az;
        if (Math.abs(ux) >= Math.abs(uz)) {
            ax = Math.signum(ux) == 0 ? 1 : Math.signum(ux);
            az = 0;
        } else {
            ax = 0;
            az = Math.signum(uz) == 0 ? 1 : Math.signum(uz);
        }
        Vec3 c = new Vec3(d.getX() + .5, d.getY(), d.getZ() + .5), l = e.position();
        if ((c.x - l.x) * ax + (c.z - l.z) * az <= .2) return;                 // ya la paso
        if (Math.abs((c.x - l.x) * az - (c.z - l.z) * ax) > .9) return;        // no va hacia la puerta
        double hold = cfg.get("hold").getAsDouble();
        Br b = new Br();
        b.d = d;
        b.c = c;
        b.ax = ax;
        b.az = az;
        b.h = new Vec3(c.x - ax * hold, l.y, c.z - az * hold);
        b.yaw = (float) (Math.atan2(-ax, az) * 180 / Math.PI + cfg.get("yo").getAsDouble());
        s.br = b;
        e.getNavigation().stop();
        e.setProp("rompe", 1);
        place(e, b);
    }

    private static void place(StalkerEntity e, Br b) {
        e.setPos(b.h.x, b.h.y, b.h.z);
        e.setDeltaMovement(Vec3.ZERO);
        e.setYRot(b.yaw);
        e.setYHeadRot(b.yaw);
        e.setYBodyRot(b.yaw);
    }

    private static void tick(ServerLevel l, StalkerEntity e, S s, JsonObject cfg) {
        Br b = s.br;
        b.t++;
        place(e, b);
        Vec3 c = b.c;
        BlockState st = l.getBlockState(b.d);
        JsonArray hits = cfg.getAsJsonArray("hits");
        for (var el : hits) {
            JsonArray h = el.getAsJsonArray();
            if (h.get(0).getAsInt() != b.t) continue;
            String kind = h.get(1).getAsString();
            float pitch = h.get(2).getAsFloat();
            String id;
            switch (kind) {
                case "t": id = "stalker.puerta_toc"; break;
                case "r": id = "stalker.puerta_rasguno"; break;
                case "c1": id = "stalker.run_crack_1"; break;
                case "c2": id = "stalker.run_crack_2"; break;
                case "c3": id = "stalker.run_crack_3"; break;
                default: id = "stalker.puerta_golpe" + (1 + RND.nextInt(3));
            }
            snd(l, id, c.add(0, 1, 0), 2f, pitch);
            if ((kind.equals("g") || kind.equals("t") || kind.equals("r")) && Houses.isDoor(l, b.d)) fx(l, st, c, b.ax, b.az, kind.equals("g") ? 6 : 3, 2.5);
        }
        if (b.t == cfg.get("brk").getAsInt()) {
            if (Houses.isDoor(l, b.d)) {
                l.destroyBlock(b.d, false);
                if (Houses.isDoor(l, b.d.above())) l.destroyBlock(b.d.above(), false);
            }
            snd(l, "stalker.puerta_rompe", c.add(0, 1, 0), 2.6f, cfg.get("pitch").getAsFloat());
            fx(l, st, c, b.ax, b.az, 40, 4.5);
            Net.shakeNear(l, c, 16, .7f, 10);
        }
        if (b.t >= LEN) {
            s.br = null;
            s.cool = 20;
            s.lx = b.h.x;
            s.ly = b.h.y;
            s.lz = b.h.z;
            e.setProp("rompe", 0);
        }
    }

    private static boolean es() {
        return Lang.es();
    }

    private static void bar(ServerPlayer p, String es, String en) {
        p.displayClientMessage(Component.literal(es() ? es : en), true);
    }

    private static double yawTo(double fx, double fz, double tx, double tz) {
        return Math.atan2(-(tx - fx), tz - fz) * 180 / Math.PI;
    }

    private static Vec3 along(java.util.List<Vec3> path, double d) {
        for (int i = 0; i < path.size() - 1; i++) {
            Vec3 a = path.get(i), b = path.get(i + 1);
            double len = Math.hypot(b.x - a.x, b.z - a.z);
            if (d <= len || i == path.size() - 2) {
                double f = len > 0 ? d / len : 0;
                return new Vec3(a.x + (b.x - a.x) * f, a.y, a.z + (b.z - a.z) * f);
            }
            d -= len;
        }
        return path.get(path.size() - 1);
    }

    private static boolean startDk(StalkerEntity e, S s, JsonObject cfg, BlockPos d, double ux, double uz) {
        double ax, az;
        if (Math.abs(ux) >= Math.abs(uz)) {
            ax = Math.signum(ux) == 0 ? 1 : Math.signum(ux);
            az = 0;
        } else {
            ax = 0;
            az = Math.signum(uz) == 0 ? 1 : Math.signum(uz);
        }
        Vec3 c = new Vec3(d.getX() + .5, d.getY(), d.getZ() + .5), l = e.position();
        if ((c.x - l.x) * ax + (c.z - l.z) * az <= .2) return false;
        if (Math.abs((c.x - l.x) * az - (c.z - l.z) * ax) > .9) return false;
        double hold = cfg.get("hold").getAsDouble();
        Dk k = new Dk();
        k.d = d;
        k.c = c;
        k.ax = ax;
        k.az = az;
        k.h = new Vec3(c.x - ax * hold, l.y, c.z - az * hold);
        k.yaw = (float) (Math.atan2(-ax, az) * 180 / Math.PI + cfg.get("yo").getAsDouble());
        s.dk = k;
        e.getNavigation().stop();
        e.setProp("rompe", 3);
        placeAt(e, k.h, k.yaw);
        return true;
    }

    private static void placeAt(StalkerEntity e, Vec3 p, float yaw) {
        e.setPos(p.x, p.y, p.z);
        e.setDeltaMovement(Vec3.ZERO);
        e.setYRot(yaw);
        e.setYHeadRot(yaw);
        e.setYBodyRot(yaw);
    }

    private static void release(ServerLevel l, Dk k) {
        if (k == null || k.victim == null || !k.phase.equals("drag")) return;
        ServerPlayer v = l.getServer().getPlayerList().getPlayer(k.victim);
        if (v != null) {
            Net.thirdPerson(v, false);
            v.setPose(net.minecraft.world.entity.Pose.STANDING);
        }
    }

    private static void endDk(StalkerEntity e, S s, int cool) {
        if (e.level() instanceof ServerLevel sl) release(sl, s.dk);
        s.dk = null;
        s.cool = cool;
        s.lx = e.getX();
        s.ly = e.getY();
        s.lz = e.getZ();
        e.setProp("rompe", 0);
    }

    private static void tickDk(ServerLevel l, StalkerEntity e, S s, JsonObject cfg) {
        Dk k = s.dk;
        k.t++;
        float yo = cfg.get("yo").getAsFloat();
        if (k.phase.equals("knock")) {
            BlockState st = l.getBlockState(k.d);
            if (st.getBlock() instanceof DoorBlock && st.hasProperty(DoorBlock.OPEN) && st.getValue(DoorBlock.OPEN)) {
                endDk(e, s, 40);                                   // le abriste: deja de tocar y corre hacia ti
                return;
            }
            placeAt(e, k.h, k.yaw);
            int ct = k.t % DK_CYCLE;
            if (ct == 1 || ct == 27) {
                snd(l, "stalker.dk_knock", k.c.add(0, 1, 0), 3f, 1f);
                if (Houses.isDoor(l, k.d)) fx(l, st, k.c, k.ax, k.az, 2, 2.0);
                Net.shakeNear(l, k.c, 12, .15f, 6);
            }
            ServerPlayer victim = null;
            boolean far = true;
            for (ServerPlayer p : l.players()) {
                if (!p.isAlive() || p.isSpectator()) continue;
                double hd = Math.hypot(p.getX() - k.c.x, p.getZ() - k.c.z);
                if (hd < 32) far = false;
                boolean inside = (p.getX() - k.c.x) * k.ax + (p.getZ() - k.c.z) * k.az > -.2;
                if (hd <= DK_NEAR && inside && Math.abs(p.getY() - k.c.y) <= 2) {
                    victim = p;
                    break;
                }
            }
            if (victim != null) {
                k.phase = "break";
                k.pt = 0;
                k.victim = victim.getUUID();
                e.setProp("rompe", 0);
                return;
            }
            if (k.t >= DK_MAX || far) {                            // nadie vino: la rompe como siempre
                Br b = new Br();
                b.d = k.d;
                b.c = k.c;
                b.h = k.h;
                b.ax = k.ax;
                b.az = k.az;
                b.yaw = k.yaw;
                s.dk = null;
                s.br = b;
                e.setProp("rompe", 1);
            }
            return;
        }
        ServerPlayer v = l.getServer().getPlayerList().getPlayer(k.victim);
        if (v == null || !v.isAlive() || v.level() != l) {
            endDk(e, s, 60);
            return;
        }
        Vec3 V = v.position();
        if (k.phase.equals("break")) {
            k.pt++;
            if (k.pt == 1) e.setProp("rompe", 1);
            placeAt(e, k.h, k.yaw);
            if (k.pt == 4) {
                BlockState st = l.getBlockState(k.d);
                if (Houses.isDoor(l, k.d)) {
                    l.destroyBlock(k.d, false);
                    if (Houses.isDoor(l, k.d.above())) l.destroyBlock(k.d.above(), false);
                }
                snd(l, "stalker.puerta_rompe", k.c.add(0, 1, 0), 2.6f, cfg.get("pitch").getAsFloat());
                fx(l, st, k.c, k.ax, k.az, 22, 4.5);
                Net.shakeNear(l, k.c, 16, .7f, 10);
            }
            if (k.pt >= 6) {
                e.setProp("rompe", 0);
                k.phase = "pounce";
                k.pt = 0;
                k.from = e.position();
                snd(l, "stalker.js_golpe", V, 1.6f, .8f);
            }
            return;
        }
        double gap = REACH.getOrDefault(e.getDefId(), 2.7);
        if (k.phase.equals("pounce")) {
            k.pt++;
            double f = Math.min(1, k.pt / (double) DK_POUNCE);
            Vec3 q = new Vec3(V.x - k.ax * gap, V.y, V.z - k.az * gap);
            Vec3 pos = new Vec3(k.from.x + (q.x - k.from.x) * f, k.from.y + (q.y - k.from.y) * f + Math.sin(f * Math.PI) * 1.2, k.from.z + (q.z - k.from.z) * f);
            placeAt(e, pos, (float) (yawTo(pos.x, pos.z, V.x, V.z) + yo));
            if (k.pt >= DK_POUNCE) {
                v.hurt(l.damageSources().mobAttack(e), 4f);
                Net.shake(v, .9f, 10);
                if (!v.isAlive()) {
                    endDk(e, s, 100);
                    return;
                }
                k.path = new java.util.ArrayList<>();
                k.path.add(new Vec3(V.x, V.y, V.z));
                k.path.add(new Vec3(k.c.x, V.y, k.c.z));
                k.path.add(new Vec3(k.c.x - k.ax * DK_OUT, V.y, k.c.z - k.az * DK_OUT));
                k.total = Math.hypot(k.path.get(1).x - k.path.get(0).x, k.path.get(1).z - k.path.get(0).z) + DK_OUT;
                k.dist = 0;
                k.y = V.y;
                k.gap = gap;
                k.hits = 0;
                k.phase = "drag";
                k.pt = 0;
                e.setProp("rompe", 2);
                Net.thirdPerson(v, true);
            }
            return;
        }
        if (k.phase.equals("drag")) {
            k.pt++;
            k.dist = Math.min(k.total, k.dist + DK_SPEED);
            Vec3 P = along(k.path, k.dist), S2 = along(k.path, k.dist + k.gap);
            double bob = Math.sin(k.pt * .5) * .06;
            e.getNavigation().stop();
            placeAt(e, new Vec3(S2.x, k.y, S2.z), (float) (yawTo(S2.x, S2.z, P.x, P.z) + yo));
            v.connection.teleport(P.x, k.y + DK_LIFT + bob, P.z, (float) (yawTo(P.x, P.z, S2.x, S2.z) + 180), -18f);
            v.setDeltaMovement(Vec3.ZERO);
            v.fallDistance = 0;
            v.setPose(net.minecraft.world.entity.Pose.SLEEPING);      // acostado, te jala de las patas
            if (k.pt % 20 == 0) e.setProp("rompe", 2);
            if (k.pt % 12 == 1) {
                snd(l, "stalker.puerta_rasguno", new Vec3(P.x, k.y, P.z), 1.4f, .7f);
                Net.shake(v, .3f, 5);
            }
            if (k.pt % 4 == 1) {
                int pct = (int) Math.floor(k.dist / k.total * 100);
                bar(v, "§4Te está arrastrando afuera... " + pct + "%", "§4It is dragging you outside... " + pct + "%");
                for (ServerPlayer q : l.players()) {
                    if (q == v || !q.isAlive() || Math.hypot(q.getX() - S2.x, q.getZ() - S2.z) > 24) continue;
                    bar(q, "§4Pégale en la cara 10 veces para salvarlo (" + k.hits + "/" + DK_HITS + ")", "§4Hit it in the face 10 times to save them (" + k.hits + "/" + DK_HITS + ")");
                }
            }
            if (k.dist >= k.total) {                               // te saco afuera: jumpscare y te mata
                endDk(e, s, 100);
                JumpScare.kill(v, e);
            }
        }
    }

    /** Otro jugador le pega mientras te arrastra: 10 golpes y te suelta (queda aturdido 10 s). */
    @SubscribeEvent
    public void onHit(LivingAttackEvent ev) {
        if (!(ev.getEntity() instanceof StalkerEntity e) || !(ev.getSource().getEntity() instanceof ServerPlayer p)) return;
        S s = STATE.get(e.getUUID());
        if (s == null || s.dk == null || !s.dk.phase.equals("drag") || p.getUUID().equals(s.dk.victim)) return;
        ev.setCanceled(true);
        Dk k = s.dk;
        if (e.tickCount - k.hitT < 3) return;
        k.hitT = e.tickCount;
        k.hits++;
        if (e.level() instanceof ServerLevel l) {
            snd(l, "stalker.js_golpe", e.position(), 1f, 1.3f);
            l.sendParticles(ParticleTypes.CRIT, e.getX(), e.getY() + .5, e.getZ(), 8, .3, .3, .3, .1);
        }
        bar(p, "§4Pégale en la cara 10 veces para salvarlo (" + k.hits + "/" + DK_HITS + ")", "§4Hit it in the face 10 times to save them (" + k.hits + "/" + DK_HITS + ")");
        if (k.hits >= DK_HITS) {
            ServerPlayer v = p.getServer().getPlayerList().getPlayer(k.victim);
            if (v != null) bar(v, "§4¡Te soltó! CORRE", "§4It let you go! RUN");
            bar(p, "§4¡Lo soltó!", "§4It let them go!");
            endDk(e, s, 100 + DK_STUN);
            e.stun(DK_STUN);
        }
    }
}
