package com.twixxel.stalker.event;

import com.twixxel.stalker.TwixxelMod;
import com.twixxel.stalker.entity.StalkerEntity;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.joml.Vector3f;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * LA CABRA MATA AL RUN. Dos formas de empezar:
 *  - 10 %: mientras la cabra te persigue aparece el RUN detras de ti y te persigue tambien; a los 3 s (si estan a menos de 14 bloques,
 *    o a los 15 s si o si) la cabra lo ve y va por el.
 *  - 30 %: al aparecer la cabra, 3 s despues aparece el RUN a su lado, se truena el cuello 2.5 s y la cabra va por el.
 * La cabra lo agarra, le arranca la cabeza (3 tronidos + sangre), lo sostiene, se gira hacia ti, dice "eres el siguiente" y desaparecen
 * los dos dejando neblina 10 s. Despues vuelve la cabra (sin RUN).
 */
public final class DuoFight {
    public static final double SPAWN_CHANCE = 0.30;
    private static final double CHASE_CHANCE = 0.10;
    private static final int MIN_TICKS = 60, FORCE_TICKS = 300, APPROACH_MAX = 100, GRAB_TICKS = 70, RIP_TICK = 33, GSR_DELAY = 60, GSR_CRACK = 50,
            HOLD_TURN = 22, HOLD_VOICE_AT = 26, HOLD_AFTER = 22, RETURN_DELAY = 40, FOG_TICKS = 200;
    private static final double SEE = 14, SPEED = .42, REACH = 3.9, HOLD_TURN_DEG = 45, ARM_DEG = 125, GSR_SIDE = 6, RUN_NECK = 4.9, FOG_RANGE = 96;
    private static final double ARM_LEN = 74.8 * .95 / 16, SHOULDER_Z = 13.24 / 16, SHOULDER_Y = 54.6 / 16;

    private static final class F {
        StalkerEntity g, r;
        String phase;     // watch | wait | crack | approach | grab | hold
        int t, duoT;
        double lockYaw, runYaw;
        Vec3 gpos, rpos;
        UUID viewer;
        ServerLevel level;
    }

    private static F f;
    private static long gsrAt = -1;
    private static final Set<UUID> seen = new HashSet<>();
    public static boolean noRunNext = false;
    private static long returnAt = -1;
    private static UUID returnPlayer;
    private static final Random RND = new Random();

    private static boolean es() {
        return com.twixxel.stalker.event.Lang.es();
    }

    public static boolean active() {
        return f != null;
    }

    private static float goatYaw(double dx, double dz) {
        return (float) (Math.atan2(-dx, dz) * 180 / Math.PI - 90);
    }

    private static void place(StalkerEntity e, Vec3 v, float yaw) {
        if (e == null || !e.isAlive()) return;
        e.setPos(v.x, v.y, v.z);
        e.setYRot(yaw);
        e.setYHeadRot(yaw);
        e.setYBodyRot(yaw);
    }

    private static void snd(ServerLevel l, String ev, Vec3 at, float vol, float pitch) {
        var r = TwixxelMod.SOUND_EVENTS.get(TwixxelMod.loc(ev));
        if (r == null) return;
        l.playSound(null, at.x, at.y, at.z, r.get(), SoundSource.MASTER, vol, pitch);
    }

    private static void stopFor(ServerPlayer p, String... evs) {
        for (String ev : evs) for (String v : TwixxelMod.variants(ev))
            p.connection.send(new ClientboundStopSoundPacket(new ResourceLocation(TwixxelMod.MODID, v), SoundSource.MASTER));
    }

    private static void duoSilence(ServerLevel l) {
        for (ServerPlayer p : l.players()) {
            stopFor(p, "stalker.goat_chase", "stalker.goat", "stalker.run", "stalker.hello_run", "stalker.run_crack");
            p.connection.send(new ClientboundSetTitleTextPacket(Component.empty()));
            p.displayClientMessage(Component.empty(), true);
        }
    }

    private static ServerPlayer nearest(ServerLevel l, Vec3 at) {
        ServerPlayer best = null;
        double bd = 1e18;
        for (ServerPlayer p : l.players()) {
            if (!p.isAlive() || p.isSpectator()) continue;
            double d = p.distanceToSqr(at);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    private static void blood(ServerLevel l, Vec3 at, int n) {
        var dust = new DustParticleOptions(new Vector3f(.55f, 0f, 0f), 1.2f);
        l.sendParticles(dust, at.x, at.y + .4, at.z, n, .45, .4, .45, .02);
        l.sendParticles(ParticleTypes.CRIT, at.x, at.y, at.z, 6, .2, .2, .2, .1);
    }

    /** Llamado al aparecer una cabra de forma natural: 30 % de que 3 s despues aparezca el RUN a su lado. */
    public static void rollSpawn(long now) {
        if (f == null && gsrAt < 0 && !PeleaEvent.active() && RND.nextDouble() < SPAWN_CHANCE) gsrAt = now + GSR_DELAY;
    }

    public static boolean startNearGoat(ServerPlayer p) {
        if (f != null) return false;
        gsrAt = ServerLifecycleHooks.getCurrentServer().getTickCount() + 1;
        return SpawnHelper.spawnGround(p, "stalker_goat", new int[]{22, 20, 24, 18, 26, 16, 28});
    }

    private static void beginFight(F x) {
        x.g.freezeForScene();
        x.r.freezeForScene();
        x.g.setProp("fase", 1);
        x.r.setProp("fase", 0);
        x.phase = "approach";
        x.t = 0;
        duoSilence(x.level);
    }

    private static void finish(F x) {
        if (f == x) f = null;
        Vec3 c = x.r != null && x.r.isAlive() ? x.r.position() : (x.g != null ? x.g.position() : null);
        if (x.g != null && x.g.isAlive()) x.g.discard();
        if (x.r != null && x.r.isAlive()) x.r.discard();
        if (c == null) return;
        x.level.sendParticles(ParticleTypes.CRIT, c.x, c.y + 3, c.z, 20, .5, .5, .5, .2);
        for (ServerPlayer p : x.level.players()) {
            if (Math.hypot(p.getX() - c.x, p.getZ() - c.z) > FOG_RANGE) continue;
            stopFor(p, "stalker.goat_chase", "stalker.goat", "stalker.run", "stalker.run_crack");
            var r = TwixxelMod.SOUND_EVENTS.get(TwixxelMod.loc("stalker.peeker_vanish"));
            if (r != null) p.playNotifySound(r.get(), SoundSource.MASTER, .8f, .7f);
            p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, FOG_TICKS, 0, false, false));   // aproximacion de la neblina de 10 s
        }
        ServerPlayer back = x.viewer == null ? null : ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayer(x.viewer);
        if (back == null) back = nearest(x.level, c);
        if (back != null) {
            returnAt = ServerLifecycleHooks.getCurrentServer().getTickCount() + RETURN_DELAY;
            returnPlayer = back.getUUID();
        }
    }

    private static Vec3 handPos(F x, double yawDeg) {
        double tr = yawDeg * Math.PI / 180, lzx = Math.sin(tr), lzz = -Math.cos(tr), a = ARM_DEG * Math.PI / 180;
        double fwd = SHOULDER_Z + Math.sin(a) * ARM_LEN, up = SHOULDER_Y - Math.cos(a) * ARM_LEN;
        return new Vec3(x.gpos.x + lzx * fwd, x.gpos.y + up, x.gpos.z + lzz * fwd);
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
            if (b != null && b.isAlive()) {
                noRunNext = true;       // con esta cabra ya no aparece el RUN
                SpawnHelper.spawnGround(b, "stalker_goat", new int[]{28, 26, 24, 22, 20, 18, 16});
            }
        }
        if (f == null) {
            if (gsrAt >= 0 && now >= gsrAt) {
                gsrAt = -1;
                startGsr();
                return;
            }
            if (now % 5 == 0) scanChasers();
            return;
        }
        F x = f;
        if (x.g == null || !x.g.isAlive() || (x.r != null && !x.r.isAlive() && !x.phase.equals("wait"))) {
            if (x.phase.equals("hold") || x.phase.equals("grab") || x.phase.equals("approach")) finish(x);
            else {
                if (x.r != null && x.r.isAlive()) x.r.discard();
                f = null;
            }
            return;
        }
        x.t++;
        switch (x.phase) {
            case "watch": {
                if (!x.g.isFieldChasing()) {
                    if (x.r.isAlive()) x.r.discard();
                    f = null;
                    return;
                }
                x.duoT++;
                if (x.duoT < MIN_TICKS) return;
                double d = Math.hypot(x.g.getX() - x.r.getX(), x.g.getZ() - x.r.getZ());
                if (d > SEE && x.duoT < FORCE_TICKS) return;
                beginFight(x);
                return;
            }
            case "crack":
                place(x.r, x.r.position(), (float) x.runYaw);
                if (x.t >= GSR_CRACK) beginFight(x);
                return;
            case "approach": {
                ServerPlayer v = nearest(x.level, x.r.position());
                Vec3 rl = x.r.position(), gl = x.g.position();
                double vdx = v == null ? 0 : v.getX() - rl.x, vdz = v == null ? 10 : v.getZ() - rl.z;
                float runYaw = (float) (Math.atan2(-vdx, vdz) * 180 / Math.PI);
                place(x.r, rl, runYaw);
                double dx = rl.x - gl.x, dz = rl.z - gl.z, d = Math.hypot(dx, dz);
                if (d > REACH + .5 && x.t < APPROACH_MAX) {
                    double step = Math.min(SPEED, d - REACH);
                    double nx = gl.x + dx / d * step, nz = gl.z + dz / d * step;
                    double y = gl.y < rl.y ? Math.min(rl.y, gl.y + .4) : Math.max(rl.y, gl.y - .6);
                    place(x.g, new Vec3(nx, y, nz), goatYaw((v == null ? nx : v.getX()) - nx, (v == null ? nz + 10 : v.getZ()) - nz));
                    return;
                }
                float th = goatYaw(vdx, vdz);
                double tr = th * Math.PI / 180, lzx = Math.sin(tr), lzz = -Math.cos(tr), vl = Math.max(1e-4, Math.hypot(vdx, vdz));
                Vec3 gp = new Vec3(rl.x - lzx * REACH + vdx / vl * .12, rl.y, rl.z - lzz * REACH + vdz / vl * .12);
                place(x.g, gp, th);
                place(x.r, rl, runYaw);
                x.lockYaw = th;
                x.runYaw = runYaw;
                x.gpos = gp;
                x.rpos = rl;
                x.g.setProp("fase", 2);
                x.r.setProp("fase", 4);
                x.phase = "grab";
                x.t = 0;
                return;
            }
            case "grab": {
                if (x.t % 10 == 0) {
                    place(x.g, x.gpos, (float) x.lockYaw);
                    place(x.r, x.rpos, (float) x.runYaw);
                }
                Vec3 neck = new Vec3(x.rpos.x, x.rpos.y + 4.9, x.rpos.z);
                if (x.t == RIP_TICK || x.t == RIP_TICK + 3 || x.t == RIP_TICK + 6) {
                    snd(x.level, "stalker.run_crack_" + (1 + (x.t - RIP_TICK) / 3), neck, 4f, .9f + RND.nextFloat() * .2f);
                    blood(x.level, neck, 14);
                }
                if (x.t > RIP_TICK && x.t < RIP_TICK + 30 && x.t % 3 == 0) blood(x.level, neck, 3);
                if (x.t >= GRAB_TICKS) {
                    ServerPlayer v = nearest(x.level, x.gpos);
                    x.viewer = v == null ? null : v.getUUID();
                    x.g.setProp("fase", 3);
                    x.r.setProp("fase", 5);
                    x.phase = "hold";
                    x.t = 0;
                }
                return;
            }
            case "hold": {
                ServerPlayer v = x.viewer == null ? null : server.getPlayerList().getPlayer(x.viewer);
                if (v == null) v = nearest(x.level, x.gpos);
                double yaw = x.lockYaw;
                if (v != null) {
                    double tr = x.lockYaw * Math.PI / 180, lzx = Math.sin(tr), lzz = -Math.cos(tr);
                    double vx = v.getX() - x.gpos.x, vz = v.getZ() - x.gpos.z, vlen = Math.max(1e-4, Math.hypot(vx, vz));
                    vx /= vlen;
                    vz /= vlen;
                    double k = Math.min(1.0, x.t / (double) HOLD_TURN), ease = k * k * (3 - 2 * k), ang = HOLD_TURN_DEG * ease * Math.PI / 180;
                    double tx = lzx * Math.cos(ang) + vx * Math.sin(ang), tz = lzz * Math.cos(ang) + vz * Math.sin(ang);
                    yaw = Math.atan2(tx, -tz) * 180 / Math.PI;
                }
                place(x.g, x.gpos, (float) yaw);
                Vec3 hand = handPos(x, yaw);
                double hx = hand.x, hz = hand.z;
                if (v != null) {
                    double dx = v.getX() - hand.x, dz = v.getZ() - hand.z, dl = Math.max(1e-4, Math.hypot(dx, dz));
                    hx += dx / dl * .15;
                    hz += dz / dl * .15;
                }
                float ryaw = v == null ? (float) x.runYaw : (float) (Math.atan2(-(v.getX() - hx), v.getZ() - hz) * 180 / Math.PI);
                place(x.r, new Vec3(hx, hand.y - RUN_NECK, hz), ryaw);
                if (x.t % 4 == 0) blood(x.level, new Vec3(hx, hand.y - .5, hz), 1);
                if (x.t == HOLD_VOICE_AT) {
                    int vt = es() ? 35 : 23;
                    var r = TwixxelMod.SOUND_EVENTS.get(TwixxelMod.loc("stalker.goat_next_voice"));
                    for (ServerPlayer p : x.level.players()) {
                        if (Math.hypot(p.getX() - x.gpos.x, p.getZ() - x.gpos.z) > FOG_RANGE) continue;
                        if (r != null) p.playNotifySound(r.get(), SoundSource.MASTER, 1f, 1f);
                        p.connection.send(new ClientboundSetTitlesAnimationPacket(0, vt + HOLD_AFTER, 6));
                        p.connection.send(new ClientboundSetTitleTextPacket(Component.literal(es() ? "§c§lEres el siguiente" : "§c§lYou're next")));
                    }
                }
                if (x.t >= HOLD_VOICE_AT + (es() ? 35 : 23) + HOLD_AFTER) finish(x);
                return;
            }
            default:
        }
    }

    /** Busca cabras que acaban de empezar a perseguir: 10 % de que tambien aparezca el RUN detras de ti. */
    private void scanChasers() {
        for (StalkerEntity e : StalkerEntity.ACTIVE) {
            if (!e.isAlive() || !e.getDefId().equals("stalker_goat") || !e.isFieldChasing() || !seen.add(e.getUUID())) continue;
            if (seen.size() > 200) seen.clear();
            if (noRunNext) {
                noRunNext = false;
                continue;
            }
            if (RND.nextDouble() >= CHASE_CHANCE || PeleaEvent.active()) continue;
            ServerLevel l = (ServerLevel) e.level();
            ServerPlayer p = nearest(l, e.position());
            if (p == null) continue;
            Vec3 look = p.getLookAngle();
            double len = Math.max(1e-4, Math.hypot(look.x, look.z));
            for (int d : new int[]{16, 15, 17, 14, 18, 12, 20}) {
                double x = p.getX() - look.x / len * d, z = p.getZ() - look.z / len * d;
                Integer y = SpawnHelper.groundY(l, x, z, p.getY());
                if (y == null) continue;
                StalkerEntity r = SpawnHelper.spawnChaseAtE(p, "stalker_run", new Vec3(x, y, z), false);
                if (r == null) return;
                r.setProp("fase", 2);
                snd(l, "stalker.run_crack", r.position().add(0, 1.5, 0), 4f, 1f);
                F n = new F();
                n.g = e;
                n.r = r;
                n.level = l;
                n.phase = "watch";
                n.viewer = p.getUUID();
                f = n;
                return;
            }
        }
    }

    private void startGsr() {
        StalkerEntity goat = null;
        ServerPlayer pl = null;
        for (StalkerEntity e : StalkerEntity.ACTIVE) {
            if (!e.isAlive() || !e.getDefId().equals("stalker_goat") || !e.isFieldWaiting()) continue;
            ServerPlayer p = nearest((ServerLevel) e.level(), e.position());
            if (p == null) continue;
            goat = e;
            pl = p;
            break;
        }
        if (goat == null) return;
        ServerLevel l = (ServerLevel) goat.level();
        Vec3 gl = goat.position();
        double tx = pl.getX() - gl.x, tz = pl.getZ() - gl.z, tl = Math.max(1e-4, Math.hypot(tx, tz));
        tx /= tl;
        tz /= tl;
        double px = -tz, pz = tx;
        int[] sides = RND.nextBoolean() ? new int[]{1, -1} : new int[]{-1, 1};
        Vec3 pt = null;
        outer:
        for (double d : new double[]{GSR_SIDE, GSR_SIDE - 1, GSR_SIDE + 1, GSR_SIDE - 2, GSR_SIDE + 2}) {
            for (int s : sides) {
                double x = gl.x + px * d * s + tx * 1.5, z = gl.z + pz * d * s + tz * 1.5;
                Integer y = SpawnHelper.groundY(l, x, z, gl.y);
                if (y != null) {
                    pt = new Vec3(x, y, z);
                    break outer;
                }
            }
        }
        if (pt == null) return;
        float ry = (float) (Math.atan2(-(pl.getX() - pt.x), pl.getZ() - pt.z) * 180 / Math.PI);
        StalkerEntity r = SpawnHelper.createScripted(l, "stalker_run", pt, ry);
        if (r == null) return;
        r.noCulling = true;
        r.setProp("fase", 1);                                                       // se truena el cuello
        snd(l, "stalker.run_crack", pt.add(0, 1.5, 0), 4f, 1f);
        goat.freezeForScene();
        place(goat, gl, goatYaw(pt.x - gl.x, pt.z - gl.z));
        F n = new F();
        n.g = goat;
        n.r = r;
        n.level = l;
        n.phase = "crack";
        n.runYaw = ry;
        n.viewer = pl.getUUID();
        f = n;
    }
}
