package com.twixxel.stalker.event;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.twixxel.stalker.TwixxelMod;
import com.twixxel.stalker.entity.StalkerEntity;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.joml.Vector3f;

import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * LA PELEA: la cabra contra el spotted (duelo) o contra el spotted y el RUN (trio). Gana siempre la cabra, descuartiza a los otros
 * y te dice "eres el siguiente". Toda la linea de tiempo (animaciones, sonidos, sangre) viene de data/twixxel/pelea.json.
 * Mientras pelean una pared invisible no te deja acercarte; la cabra no ataca hasta que le miras la cara (o pasan 8 s).
 */
public final class PeleaEvent {
    public static final double CHANCE = 0.20;           // 20 % de que aparezca el spotted al salir la cabra
    private static final int RANGE = 96, DELAY = 40, WAIT_AUTO = 160, RETURN_DELAY = 40;
    private static final double LEJOS = 8, S_MIN = -16, S_MAX = 7, CARA_S = 2.3, CARA_Y = 5.3, CARA_R = 1.6;

    private static JsonObject TL;

    private static final class Actor {
        StalkerEntity e;
        double s, h;
    }

    private static final class Move {
        double s0, s1, h;
        int t0, n;
    }

    private static final class P {
        String mode;
        JsonArray ev;
        int i, t, espera = -1, finAt = -1;
        ServerLevel level;
        Vec3 A;
        double vx, vz, Rx, Rz, m = 1;
        float yaw;
        Map<String, Actor> actors = new HashMap<>();
        Map<String, Move> moves = new HashMap<>();
        UUID viewer;
    }

    private static P cur;
    private static int pendAt = -1;
    private static StalkerEntity pendGoat;
    private static String pendMode;
    private static UUID pendViewer;
    private static long returnAt = -1;
    private static UUID returnPlayer;

    private static boolean es() {
        return com.twixxel.stalker.event.Lang.es();
    }

    private static JsonObject tl() {
        if (TL == null) {
            try (var in = PeleaEvent.class.getResourceAsStream("/data/twixxel/pelea.json")) {
                TL = JsonParser.parseReader(new InputStreamReader(in, "UTF-8")).getAsJsonObject();
            } catch (Exception ex) {
                TL = new JsonObject();
            }
        }
        return TL;
    }

    public static boolean active() {
        return cur != null || pendGoat != null;
    }

    /** Pone la cabra en A (a nivel del suelo) y arma la pelea para dentro de 2 s. */
    public static boolean queue(ServerPlayer player, Vec3 a, String mode) {
        if (active() || !tl().has(mode)) return false;
        ServerLevel l = player.serverLevel();
        double vx = player.getX() - a.x, vz = player.getZ() - a.z, vl = Math.max(1e-4, Math.hypot(vx, vz));
        float yaw = (float) (Math.atan2(-vx / vl, vz / vl) * 180 / Math.PI - 90);
        StalkerEntity goat = SpawnHelper.createScripted(l, "stalker_goat", a, yaw);
        if (goat == null) return false;
        goat.noCulling = true;
        goat.setProp("fase", 7);
        pendGoat = goat;
        pendMode = mode;
        pendViewer = player.getUUID();
        MinecraftServer s = ServerLifecycleHooks.getCurrentServer();
        pendAt = s.getTickCount() + DELAY;
        return true;
    }

    /** Comando / huevo: busca sitio delante del jugador a 20 bloques. */
    public static boolean startNear(ServerPlayer p, String mode) {
        return startNear(p, mode, new int[]{22, 20, 24, 18, 26, 16, 28});
    }

    public static boolean startNear(ServerPlayer p, String mode, int[] dists) {
        if (active()) return false;
        Vec3 look = p.getLookAngle();
        double len = Math.max(1e-4, Math.hypot(look.x, look.z));
        for (int d : dists) {
            double x = p.getX() + look.x / len * d, z = p.getZ() + look.z / len * d;
            Integer y = SpawnHelper.groundY(p.serverLevel(), x, z, p.getY());
            if (y != null) return queue(p, new Vec3(x, y, z), mode);
        }
        return false;
    }

    private static Vec3 w(P p, double s, double y, double depth) {
        double k = p.m * s;
        return new Vec3(p.A.x + p.Rx * k + p.vx * depth, p.A.y + y, p.A.z + p.Rz * k + p.vz * depth);
    }

    private static double depthOf(String a) {
        JsonObject d = tl().getAsJsonObject("depth");
        return d != null && d.has(a) ? d.get(a).getAsDouble() : 0;
    }

    private static void place(P p, String a) {
        Actor ac = p.actors.get(a);
        if (ac == null || ac.e == null || !ac.e.isAlive()) return;
        Vec3 v = w(p, ac.s, ac.h, depthOf(a));
        ac.e.setPos(v.x, v.y, v.z);
        ac.e.setYRot(p.yaw);
        ac.e.setYHeadRot(p.yaw);
        ac.e.setYBodyRot(p.yaw);
    }

    private static List<ServerPlayer> players(P p) {
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer q : p.level.players()) if (Math.hypot(q.getX() - p.A.x, q.getZ() - p.A.z) <= RANGE) out.add(q);
        return out;
    }

    private static SoundEvent sound(String id) {
        switch (id) {
            case "mob.ravager.roar": return SoundEvents.RAVAGER_ROAR;
            case "mob.ravager.step": return SoundEvents.RAVAGER_STEP;
            case "mob.ravager.bite": return SoundEvents.RAVAGER_ATTACK;
            case "mob.ravager.hurt": return SoundEvents.RAVAGER_HURT;
            case "mob.warden.attack_impact": return SoundEvents.WARDEN_ATTACK_IMPACT;
            default: return BedHide.se(id);
        }
    }

    private static final DustParticleOptions RED = new DustParticleOptions(new Vector3f(.55f, 0f, 0f), 1.2f);

    private static void blood(P p, String kind, double s, double y, double dirS, double dirY, double size, String actor) {
        Vec3 v = w(p, s, y, depthOf(actor) + .25);
        double dx = p.Rx * p.m * dirS, dz = p.Rz * p.m * dirS;
        ServerLevel l = p.level;
        ParticleOptions dust = new DustParticleOptions(new Vector3f(.55f, 0f, 0f), (float) Math.max(.6, size));
        switch (kind) {
            case "gota":
                l.sendParticles(dust, v.x, v.y, v.z, 1, 0, 0, 0, 0);
                break;
            case "salpica":
                l.sendParticles(dust, v.x, v.y, v.z, 14, .25 + Math.abs(dx) * .3, .25, .25 + Math.abs(dz) * .3, .05);
                break;
            case "chorro":
                l.sendParticles(dust, v.x, v.y, v.z, 0, dx + .01, dirY + .2, dz + .01, .6);
                l.sendParticles(dust, v.x, v.y, v.z, 25, .1, .1, .1, .12);
                break;
            case "trozos":
                l.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.REDSTONE_BLOCK.defaultBlockState()), v.x, v.y, v.z, 12, .3, .3, .3, .15);
                break;
            case "fuente":
                l.sendParticles(dust, v.x, v.y, v.z, 6, .08, .08, .08, .1);
                break;
            case "charco":
                l.sendParticles(dust, v.x, v.y + .05, v.z, 20, .5 * Math.max(1, size), 0, .5 * Math.max(1, size), 0);
                break;
            default:
        }
    }

    private static void explosion(ServerLevel l, Vec3 v, int n) {
        for (int i = 0; i < n; i++) l.sendParticles(ParticleTypes.EXPLOSION, v.x, v.y + .6 + i * .9, v.z, 1, 0, 0, 0, 0);
    }

    private static boolean miran(P p) {
        Actor g = p.actors.get("G");
        Vec3[] pts = {
                w(p, g.s - CARA_S, CARA_Y + g.h, 0), w(p, g.s + CARA_S, CARA_Y + g.h, 0), w(p, g.s, CARA_Y + g.h, 0), w(p, g.s, 3 + g.h, 0)};
        for (ServerPlayer q : players(p)) {
            Vec3 eye = q.getEyePosition(), dir = q.getLookAngle();
            for (Vec3 W : pts) {
                Vec3 t = W.subtract(eye);
                double dist = Math.max(1e-4, t.length());
                double dot = t.dot(dir) / dist;
                double tol = Math.max(Math.atan((CARA_R + 1.2) / dist), .15);
                if (dot >= Math.cos(tol)) return true;
            }
        }
        return false;
    }

    private static void muro(P p) {
        for (ServerPlayer q : p.level.players()) {
            double dx = q.getX() - p.A.x, dz = q.getZ() - p.A.z;
            if (Math.abs(q.getY() - p.A.y) > 12) continue;
            double s = (dx * p.Rx + dz * p.Rz) * p.m, d = dx * p.vx + dz * p.vz;
            double qq = Math.max(S_MIN, Math.min(S_MAX, s)), es = s - qq, dist = Math.hypot(es, d);
            if (dist >= LEJOS) continue;
            double ns, nd;
            if (dist < .01) {
                ns = 0;
                nd = 1;
            } else {
                ns = es / dist;
                nd = d / dist;
            }
            double wx = p.Rx * p.m * ns + p.vx * nd, wz = p.Rz * p.m * ns + p.vz * nd;
            if (dist >= LEJOS - 3) {
                q.push(wx * .55, 0, wz * .55);
                q.hurtMarked = true;
                continue;
            }
            double s2 = qq + ns * (LEJOS + .5), d2 = nd * (LEJOS + .5), k = p.m * s2;
            q.teleportTo(p.level, p.A.x + p.Rx * k + p.vx * d2, q.getY(), p.A.z + p.Rz * k + p.vz * d2, q.getYRot(), q.getXRot());
        }
    }

    private static boolean fits(P p, double m) {
        p.m = m;
        for (JsonElement el : p.ev) {
            JsonArray e = el.getAsJsonArray();
            if (!"spawn".equals(e.get(1).getAsString())) continue;
            Vec3 v = w(p, e.get(3).getAsDouble(), 0, 0);
            Integer gy = SpawnHelper.groundY(p.level, v.x, v.z, p.A.y);
            if (gy == null || Math.abs(gy - p.A.y) > 2.5) return false;
        }
        return true;
    }

    private static boolean start(StalkerEntity goat, String mode, UUID viewer, ServerLevel l) {
        ServerPlayer player = ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayer(viewer);
        if (player == null) return false;
        P p = new P();
        p.mode = mode;
        p.level = l;
        p.A = goat.position();
        double vx = player.getX() - p.A.x, vz = player.getZ() - p.A.z, vl = Math.max(1e-4, Math.hypot(vx, vz));
        p.vx = vx / vl;
        p.vz = vz / vl;
        p.Rx = p.vz;
        p.Rz = -p.vx;
        p.ev = tl().getAsJsonObject(mode).getAsJsonArray("ev");
        p.m = fits(p, 1) ? 1 : (fits(p, -1) ? -1 : 1);
        p.yaw = (float) (Math.atan2(-p.vx, p.vz) * 180 / Math.PI - 90 + (p.m < 0 ? 180 : 0));
        p.viewer = viewer;
        Actor g = new Actor();
        g.e = goat;
        p.actors.put("G", g);
        goat.setProp("mov", 0);
        for (ServerPlayer q : players(p)) q.connection.send(new net.minecraft.network.protocol.game.ClientboundStopSoundPacket(
                new net.minecraft.resources.ResourceLocation(TwixxelMod.MODID, "stalker.goat_chase"), SoundSource.MASTER));
        cur = p;
        place(p, "G");
        return true;
    }

    private static void run(P p, JsonArray e) {
        String kind = e.get(1).getAsString();
        switch (kind) {
            case "spawn": {
                String a = e.get(2).getAsString();
                String id = a.equals("S") ? "pelea_spotted" : "pelea_run";
                Vec3 v = w(p, e.get(3).getAsDouble(), 0, depthOf(a));
                StalkerEntity ent = SpawnHelper.createScripted(p.level, id, v, p.yaw);
                if (ent == null) return;
                ent.noCulling = true;
                Actor ac = new Actor();
                ac.e = ent;
                ac.s = e.get(3).getAsDouble();
                p.actors.put(a, ac);
                ent.setProp("fase", 0);
                place(p, a);
                explosion(p.level, v, 3);
                return;
            }
            case "fase": {
                Actor ac = p.actors.get(e.get(2).getAsString());
                if (ac != null && ac.e.isAlive()) ac.e.setProp("fase", e.get(3).getAsDouble());
                return;
            }
            case "move": {
                Move mv = new Move();
                mv.s0 = e.get(3).getAsDouble();
                mv.s1 = e.get(4).getAsDouble();
                mv.t0 = p.t;
                mv.n = e.get(5).getAsInt();
                mv.h = e.get(6).getAsDouble();
                p.moves.put(e.get(2).getAsString(), mv);
                return;
            }
            case "snd": {
                SoundEvent se = sound(e.get(2).getAsString());
                if (se == null) return;
                float pitch = e.get(3).getAsFloat(), vol = e.get(4).getAsFloat();
                for (ServerPlayer q : players(p)) q.playNotifySound(se, SoundSource.MASTER, vol, pitch);
                return;
            }
            case "sangre":
                blood(p, e.get(2).getAsString(), e.get(3).getAsDouble(), e.get(4).getAsDouble(), e.get(5).getAsDouble(),
                        e.get(6).getAsDouble(), e.get(7).getAsDouble(), e.get(8).getAsString());
                return;
            case "voz": {
                var r = TwixxelMod.SOUND_EVENTS.get(TwixxelMod.loc("stalker.goat_next_voice"));
                for (ServerPlayer q : players(p)) {
                    if (r != null) q.playNotifySound(r.get(), SoundSource.MASTER, 1f, 1f);
                    Net.title(q, es() ? "Eres el siguiente" : "You're next", voiceTicks() + 31);
                }
                return;
            }
            case "fin_en":
                p.finAt = p.t + voiceTicks() + e.get(2).getAsInt();
                return;
            default:
        }
    }

    private static int voiceTicks() {
        return es() ? 35 : 23;
    }

    private static void end(P p, boolean ok) {
        if (cur == p) cur = null;
        for (Map.Entry<String, Actor> en : p.actors.entrySet()) {
            Actor ac = en.getValue();
            if (ac.e == null || !ac.e.isAlive()) continue;
            if (!en.getKey().equals("G")) {
                explosion(p.level, ac.e.position().add(0, .4, 0), 1);
                ac.e.discard();
            }
        }
        Actor g = p.actors.get("G");
        if (g != null && g.e != null && g.e.isAlive()) {
            explosion(p.level, g.e.position().add(0, 2.4, 0), 1);
            g.e.discard();
        }
        if (ok) {
            var r = TwixxelMod.SOUND_EVENTS.get(TwixxelMod.loc("stalker.peeker_vanish"));
            if (r != null) for (ServerPlayer q : players(p)) q.playNotifySound(r.get(), SoundSource.MASTER, .8f, .7f);
            returnAt = ServerLifecycleHooks.getCurrentServer().getTickCount() + RETURN_DELAY;
            returnPlayer = p.viewer;
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        long now = server.getTickCount();
        if (returnAt >= 0 && now >= returnAt) {
            returnAt = -1;
            ServerPlayer b = server.getPlayerList().getPlayer(returnPlayer);
            if (b != null && b.isAlive()) DuoFight.noRunNext = true;
            if (b != null && b.isAlive()) SpawnHelper.spawnGround(b, "stalker_goat", new int[]{28, 26, 24, 22, 20, 18, 16});   // la cabra vuelve y te persigue
        }
        if (pendGoat != null) {
            if (!pendGoat.isAlive()) {
                pendGoat = null;
            } else if (now >= pendAt) {
                StalkerEntity g = pendGoat;
                pendGoat = null;
                if (!start(g, pendMode, pendViewer, (ServerLevel) g.level())) g.discard();
            }
            return;
        }
        P p = cur;
        if (p == null) return;
        if (p.actors.get("G").e == null || !p.actors.get("G").e.isAlive() || players(p).isEmpty()) {
            end(p, false);
            return;
        }
        for (Actor ac : p.actors.values()) if (ac.e == null || !ac.e.isAlive()) {
            end(p, false);
            return;
        }
        muro(p);
        boolean esperando = false;
        while (p.i < p.ev.size() && p.ev.get(p.i).getAsJsonArray().get(0).getAsInt() <= p.t) {
            JsonArray e = p.ev.get(p.i).getAsJsonArray();
            if ("espera".equals(e.get(1).getAsString())) {
                if (!miran(p)) {
                    esperando = true;
                    break;
                }
                p.espera = -1;
                p.i++;
                continue;
            }
            run(p, e);
            p.i++;
        }
        if (esperando) {
            p.espera = Math.max(0, p.espera) + 1;
            if (p.espera % 10 == 0) for (String a : p.actors.keySet()) place(p, a);
            if (p.espera >= WAIT_AUTO) {
                p.espera = -1;
                p.i++;
            }
            return;
        }
        var it = p.moves.entrySet().iterator();
        while (it.hasNext()) {
            var en = it.next();
            Move mv = en.getValue();
            Actor ac = p.actors.get(en.getKey());
            if (ac == null) {
                it.remove();
                continue;
            }
            double k = Math.min(1, (p.t - mv.t0) / (double) mv.n);
            ac.s = mv.s0 + (mv.s1 - mv.s0) * k;
            ac.h = Math.sin(k * Math.PI) * mv.h;
            place(p, en.getKey());
            if (k >= 1) {
                ac.h = 0;
                it.remove();
            }
        }
        if (p.t % 10 == 0) for (String a : p.actors.keySet()) if (!p.moves.containsKey(a)) place(p, a);
        if (p.finAt >= 0 && p.t >= p.finAt) {
            end(p, true);
            return;
        }
        p.t++;
    }

    /** Huevos de prueba: pelea_spotted = duelo, pelea_run = trio. */
    @SubscribeEvent
    public void onJoin(EntityJoinLevelEvent ev) {
        if (ev.getLevel().isClientSide || !(ev.getEntity() instanceof StalkerEntity se) || se.isScripted()) return;
        String id = se.getDefId();
        if (!id.equals("pelea_spotted") && !id.equals("pelea_run")) return;
        ev.setCanceled(true);
        ServerPlayer near = null;
        double bd = 1e9;
        for (ServerPlayer q : ((ServerLevel) ev.getLevel()).players()) {
            double d = q.distanceToSqr(se);
            if (d < bd) {
                bd = d;
                near = q;
            }
        }
        if (near == null || active()) return;
        final ServerPlayer pl = near;
        final String mode = id.equals("pelea_spotted") ? "duelo" : "trio";
        ServerLifecycleHooks.getCurrentServer().execute(() -> startNear(pl, mode));
    }
}
