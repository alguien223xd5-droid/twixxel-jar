package com.twixxel.stalker.event;

import com.twixxel.stalker.TwixxelMod;
import com.twixxel.stalker.entity.StalkerEntity;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.joml.Vector3f;

/**
 * LA CABRA SE COME A TU MASCOTA. Si una mascota (salvaje o tuya) esta a 7 bloques o menos de la cabra, te este persiguiendo o no:
 * la cabra va hacia ella, la agarra con la garra, se la come a mordiscos, le arranca la cabeza (sangre), se gira, te apunta y te dice
 * "eres el siguiente" / "you're next", y desaparece con tu mascota.
 */
public final class GoatEatsPet {
    private static final double SEE = 7, REACH = 2.6, SPEED = .42, DROP = .9, TURN_DEG = 45, ARM_DEG = 125, FOG_RANGE = 96;
    private static final double ARM_LEN = 74.8 * .95 / 16, SHOULDER_Z = 13.24 / 16, SHOULDER_Y = 54.6 / 16;
    private static final int APPROACH_MAX = 100, RIP = 48, TURN_AT = 60, HOLD_TURN = 22, VOICE_OFF = 26, AFTER = 22;
    private static final int[] BITES = {14, 26, 38};

    private static final class F {
        StalkerEntity goat, pet;
        ServerLevel level;
        String phase = "approach";
        int t;
        Vec3 ppos, gpos;
        double lockYaw;
        java.util.UUID viewer;
    }

    private static F f;

    public static boolean active() {
        return f != null;
    }

    private static boolean es() {
        return Lang.es();
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
        l.sendParticles(new DustParticleOptions(new Vector3f(.55f, 0f, 0f), 1.2f), at.x, at.y + .4, at.z, n, .45, .4, .45, .02);
        l.sendParticles(ParticleTypes.CRIT, at.x, at.y, at.z, 6, .2, .2, .2, .1);
    }

    private static void snd(ServerLevel l, String id, Vec3 at, float vol, float pitch) {
        var s = BedHide.se(id);
        if (s != null) l.playSound(null, at.x, at.y, at.z, s, SoundSource.HOSTILE, vol, pitch);
    }

    private static Vec3 hand(F x, double yawDeg) {
        double tr = yawDeg * Math.PI / 180, lzx = Math.sin(tr), lzz = -Math.cos(tr), a = ARM_DEG * Math.PI / 180;
        double fwd = SHOULDER_Z + Math.sin(a) * ARM_LEN, up = SHOULDER_Y - Math.cos(a) * ARM_LEN;
        return new Vec3(x.gpos.x + lzx * fwd, x.gpos.y + up, x.gpos.z + lzz * fwd);
    }

    private static void finish(F x) {
        if (f == x) f = null;
        Vec3 c = x.goat != null && x.goat.isAlive() ? x.goat.position() : null;
        if (x.pet != null && x.pet.isAlive()) x.pet.discard();          // se lleva a tu mascota
        if (x.goat != null && x.goat.isAlive()) x.goat.discard();
        if (c == null) return;
        x.level.sendParticles(ParticleTypes.CRIT, c.x, c.y + 3, c.z, 20, .5, .5, .5, .2);
        var r = TwixxelMod.SOUND_EVENTS.get(TwixxelMod.loc("stalker.peeker_vanish"));
        for (ServerPlayer p : x.level.players()) {
            if (Math.hypot(p.getX() - c.x, p.getZ() - c.z) > FOG_RANGE || r == null) continue;
            p.playNotifySound(r.get(), SoundSource.MASTER, .8f, .7f);
        }
    }

    private static StalkerEntity findPet(StalkerEntity goat) {
        StalkerEntity best = null;
        double bd = 1e18;
        for (var e : goat.level().getEntitiesOfClass(StalkerEntity.class, goat.getBoundingBox().inflate(SEE, 4, SEE))) {
            if (!e.isAlive() || !e.getDefId().startsWith("mascota") || e.isScripted()) continue;
            double d = Math.hypot(e.getX() - goat.getX(), e.getZ() - goat.getZ());
            if (d <= SEE && d < bd) {
                bd = d;
                best = e;
            }
        }
        return best;
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        if (f == null) {
            if (server.getTickCount() % 10 != 0 || DuoFight.active() || PeleaEvent.active()) return;
            for (StalkerEntity g : StalkerEntity.ACTIVE) {
                if (!g.isAlive() || g.isScripted() || !g.getDefId().equals("stalker_goat") || !(g.level() instanceof ServerLevel sl)) continue;
                StalkerEntity pet = findPet(g);
                if (pet == null) continue;
                F x = new F();
                x.goat = g;
                x.pet = pet;
                x.level = sl;
                x.ppos = pet.position();
                g.freezeForScene();
                pet.freezeForScene();
                pet.addTag("sentada");
                g.setProp("fase", 1);
                for (ServerPlayer p : sl.players()) {
                    p.connection.send(new ClientboundSetTitleTextPacket(Component.empty()));
                    p.displayClientMessage(Component.empty(), true);
                }
                f = x;
                return;
            }
            return;
        }
        F x = f;
        if (x.goat == null || !x.goat.isAlive() || x.pet == null || !x.pet.isAlive()) {
            finish(x);
            return;
        }
        x.t++;
        Vec3 gl = x.goat.position();
        if (x.phase.equals("approach")) {
            place(x.pet, x.ppos, x.pet.getYRot());
            double dx = x.ppos.x - gl.x, dz = x.ppos.z - gl.z, d = Math.hypot(dx, dz);
            if (d > REACH && x.t < APPROACH_MAX) {
                double step = Math.min(SPEED, d - REACH + .05);
                double nx = gl.x + dx / d * step, nz = gl.z + dz / d * step;
                double y = gl.y < x.ppos.y ? Math.min(x.ppos.y, gl.y + .4) : Math.max(x.ppos.y, gl.y - .6);
                place(x.goat, new Vec3(nx, y, nz), goatYaw(dx, dz));
                return;
            }
            ServerPlayer v = nearest(x.level, gl);
            double tvx = v == null ? dx : v.getX() - gl.x, tvz = v == null ? dz : v.getZ() - gl.z;
            x.lockYaw = goatYaw(tvx, tvz);
            x.gpos = gl;
            x.viewer = v == null ? null : v.getUUID();
            x.goat.setProp("fase", 3);
            x.phase = "eat";
            x.t = 0;
            snd(x.level, "mob.goat.ambient.screamer", gl, 2f, .6f);
            return;
        }
        // con la mascota en la garra: se la come, le arranca la cabeza, te apunta y te habla
        ServerPlayer v = x.viewer == null ? null : server.getPlayerList().getPlayer(x.viewer);
        if (v == null || v.level() != x.level) v = nearest(x.level, x.gpos);
        double yaw = x.lockYaw;
        if (x.t >= TURN_AT && v != null) {
            double tr = x.lockYaw * Math.PI / 180, lzx = Math.sin(tr), lzz = -Math.cos(tr);
            double vx = v.getX() - x.gpos.x, vz = v.getZ() - x.gpos.z, vl = Math.max(1e-4, Math.hypot(vx, vz));
            vx /= vl;
            vz /= vl;
            double k = Math.min(1.0, (x.t - TURN_AT) / (double) HOLD_TURN), ease = k * k * (3 - 2 * k), ang = TURN_DEG * ease * Math.PI / 180;
            double tx = lzx * Math.cos(ang) + vx * Math.sin(ang), tz = lzz * Math.cos(ang) + vz * Math.sin(ang);
            yaw = Math.atan2(tx, -tz) * 180 / Math.PI;
        }
        place(x.goat, x.gpos, (float) yaw);
        Vec3 h = hand(x, yaw);
        Vec3 neck = new Vec3(h.x, h.y - .1, h.z);
        float pyaw = v == null ? (float) yaw : (float) (Math.atan2(-(v.getX() - h.x), v.getZ() - h.z) * 180 / Math.PI);
        place(x.pet, new Vec3(h.x, h.y - DROP, h.z), pyaw);
        for (int i = 0; i < BITES.length; i++) {
            if (x.t != BITES[i]) continue;
            blood(x.level, neck, 14);
            snd(x.level, "stalker.run_crack_" + (i + 1), neck, 2f, .9f + (float) Math.random() * .2f);
            x.level.playSound(null, neck.x, neck.y, neck.z, SoundEvents.GOAT_RAM_IMPACT, SoundSource.HOSTILE, 1f, .6f);
        }
        if (x.t == RIP) {
            x.pet.setProp("sin_cabeza", 1);
            blood(x.level, neck, 28);
            snd(x.level, "stalker.run_crack_3", neck, 2f, 1f);
        }
        if (x.t > RIP && x.t % 4 == 0) blood(x.level, new Vec3(h.x, h.y - DROP + .4, h.z), 1);
        int voiceAt = TURN_AT + VOICE_OFF, vt = es() ? 35 : 23;
        if (x.t == voiceAt) {
            var r = TwixxelMod.SOUND_EVENTS.get(TwixxelMod.loc("stalker.goat_next_voice"));
            for (ServerPlayer p : x.level.players()) {
                if (Math.hypot(p.getX() - x.gpos.x, p.getZ() - x.gpos.z) > FOG_RANGE) continue;
                if (r != null) p.playNotifySound(r.get(), SoundSource.MASTER, 1f, 1f);
                p.connection.send(new ClientboundSetTitlesAnimationPacket(0, vt + AFTER, 6));
                p.connection.send(new ClientboundSetTitleTextPacket(Component.literal(es() ? "§c§lEres el siguiente" : "§c§lYou're next")));
            }
        }
        if (x.t >= voiceAt + vt + AFTER) finish(x);
    }
}
