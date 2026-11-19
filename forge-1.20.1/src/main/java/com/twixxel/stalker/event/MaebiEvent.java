package com.twixxel.stalker.event;

import com.twixxel.stalker.entity.StalkerEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.Random;
import java.util.UUID;

/**
 * MAEBI: cabra gigante quieta mirandote (aparece cada 30 min). A 7 bloques dobla la cabeza (crack), se te tira encima
 * (5 corazones), te agarra y te rasgua (1 corazon, sangre, "Eres mio"), te da 3 s de ventaja y te persigue 60 s con su
 * musica y oscuridad (perfil stalker_maebi). Si te alcanza, su jumpscare.
 */
public final class MaebiEvent {
    private static final int STAY = 6000, BEND = 36, POUNCE = 8, GRAB = 44, SCRATCH_AT = 16, HEADSTART = 60;
    private static final double NEAR = 7;
    private static final int[] CRACKS = {6, 12, 19};

    private static StalkerEntity e;
    private static UUID pid;
    private static ServerLevel level;
    private static String phase;
    private static int t;
    private static Vec3 from, grabPos;
    private static long next = -1;
    private static final Random RND = new Random();

    public static boolean active() {
        return pid != null;
    }

    private static void snd(String id, Vec3 at, float vol, float pitch) {
        var se = BedHide.se(id);
        if (se != null && level != null) level.playSound(null, at.x, at.y, at.z, se, SoundSource.HOSTILE, vol, pitch);
    }

    private static void freeze(ServerPlayer p, boolean on) {
        if (on) {
            p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 80, 10, false, false));
            p.addEffect(new MobEffectInstance(MobEffects.JUMP, 80, 200, false, false));
        } else {
            p.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
            p.removeEffect(MobEffects.JUMP);
        }
    }

    private static void end() {
        if (e != null && e.isAlive()) e.discard();
        e = null;
        MinecraftServer s = ServerLifecycleHooks.getCurrentServer();
        if (s != null && pid != null) {
            ServerPlayer p = s.getPlayerList().getPlayer(pid);
            if (p != null) freeze(p, false);
        }
        pid = null;
        level = null;
        phase = null;
        if (s != null) next = s.getTickCount() + SpawnConfig.ticks("maebi", 36000, 36000, RND);
    }

    /** Borra todo Maebi suelto (menos el indicado): solo puede haber uno a la vez. */
    private static void purge(MinecraftServer server, StalkerEntity keep) {
        if (server == null) return;
        for (ServerLevel lv : server.getAllLevels()) {
            for (var en : lv.getAllEntities()) {
                if (en instanceof StalkerEntity se && se != keep && se.getDefId().equals("stalker_maebi") && se.isAlive()) se.discard();
            }
        }
    }

    public static boolean startFor(ServerPlayer p) {
        if (pid != null) return false;
        purge(p.getServer(), null);
        ServerLevel l = p.serverLevel();
        Vec3 look = p.getLookAngle();
        double len = Math.hypot(look.x, look.z);
        if (len < 1e-3) return false;
        for (int dist : new int[]{26, 24, 22, 20, 18, 16}) {
            double x = p.getX() + look.x / len * dist, z = p.getZ() + look.z / len * dist;
            int y = l.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(x), (int) Math.floor(z));
            if (Math.abs(y - p.getY()) > 12 || !l.getFluidState(new net.minecraft.core.BlockPos((int) Math.floor(x), y - 1, (int) Math.floor(z))).isEmpty()) continue;
            level = l;
            e = SpawnHelper.createScripted(l, "stalker_maebi", new Vec3(x, y, z), 0f);
            if (e == null) {
                level = null;
                return false;
            }
            e.noCulling = true;
            e.setProp("fase", 0);
            pid = p.getUUID();
            phase = "watch";
            t = 0;
            face(p.position());
            return true;
        }
        return false;
    }

    private static void face(Vec3 to) {
        float yaw = (float) (Math.atan2(-(to.x - e.getX()), to.z - e.getZ()) * 180 / Math.PI);
        e.setYRot(yaw);
        e.setYHeadRot(yaw);
        e.setYBodyRot(yaw);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        long now = server.getTickCount();
        if (pid == null) {
            if (now % 20 != 0 || !com.twixxel.stalker.Config.NATURAL_SPAWNS.get()) return;
            if (next < 0) next = now + SpawnConfig.ticks("maebi", 36000, 36000, RND);
            if (now < next) return;
            if (!StalkerEntity.ACTIVE.isEmpty()) {
                next = now + 600;
                return;
            }
            var ps = new java.util.ArrayList<>(server.getPlayerList().getPlayers());
            java.util.Collections.shuffle(ps, RND);
            for (ServerPlayer p : ps) if (p.isAlive() && !p.isSpectator() && !SuenoEvent.isDreaming(p) && startFor(p)) return;
            next = now + 600;
            return;
        }
        ServerPlayer p = server.getPlayerList().getPlayer(pid);
        if (p == null || !p.isAlive() || p.level() != level || e == null || !e.isAlive()) {
            end();
            return;
        }
        t++;
        Vec3 sl = e.position();
        switch (phase) {
            case "watch" -> {
                if (t >= STAY) {
                    end();
                    return;
                }
                if (t % 2 == 0) face(p.position());
                if (Math.hypot(p.getX() - sl.x, p.getZ() - sl.z) <= NEAR && Math.abs(p.getY() - sl.y) <= 4) {
                    phase = "bend";
                    t = 0;
                    e.setProp("fase", 1);
                }
            }
            case "bend" -> {
                face(p.position());
                for (int i = 0; i < CRACKS.length; i++) if (t == CRACKS[i]) snd("stalker.run_crack_" + (i + 1), sl, 1.5f, .8f);
                if (t >= BEND) {
                    phase = "pounce";
                    t = 0;
                    from = sl;
                    e.setProp("fase", 3);
                    snd("stalker.goat_crawl_scream", sl, 1.2f, 1f);
                }
            }
            case "pounce" -> {
                Vec3 v = p.position();
                double dx = v.x - from.x, dz = v.z - from.z, d = Math.max(.001, Math.hypot(dx, dz));
                Vec3 to = new Vec3(v.x - dx / d * 1.1, v.y, v.z - dz / d * 1.1);
                double f = Math.min(1, (double) t / POUNCE);
                e.setPos(from.x + (to.x - from.x) * f, from.y + (to.y - from.y) * f + Math.sin(f * Math.PI) * 1.6, from.z + (to.z - from.z) * f);
                face(v);
                if (t >= POUNCE) {
                    p.hurt(level.damageSources().mobAttack(e), 10f);
                    snd("stalker.js_golpe", sl, 1f, .8f);
                    phase = "grab";
                    t = 0;
                    grabPos = to;
                    e.setProp("fase", 5);
                }
            }
            case "grab" -> {
                freeze(p, true);
                e.setPos(grabPos.x, grabPos.y, grabPos.z);
                face(p.position());
                if (t == SCRATCH_AT) {
                    p.hurt(level.damageSources().mobAttack(e), 2f);
                    for (int i = 0; i < 2; i++)
                        level.sendParticles(new net.minecraft.core.particles.DustParticleOptions(new org.joml.Vector3f(.65f, 0f, 0f), 1.4f),
                                p.getX(), p.getY() + 1.2 - i * .2, p.getZ(), 18, .3, .4, .3, .02);
                    level.playSound(null, p.blockPosition(), SoundEvents.ZOMBIE_BREAK_WOODEN_DOOR, SoundSource.HOSTILE, .6f, 1.6f);
                    String txt = Lang.es() ? "§4§lEres mío" : "§4§lYou are mine";
                    p.connection.send(new ClientboundSetTitlesAnimationPacket(2, 40, 10));
                    p.connection.send(new ClientboundSetTitleTextPacket(Component.literal(txt)));
                }
                if (t >= GRAB) {
                    freeze(p, false);
                    phase = "headstart";
                    t = 0;
                    e.setProp("fase", 0);
                }
            }
            case "headstart" -> {
                if (t % 2 == 0) face(p.position());
                if (t >= HEADSTART) {
                    Vec3 at = e.position();
                    e.discard();
                    e = null;
                    pid = null;
                    level = null;
                    phase = null;
                    next = now + SpawnConfig.ticks("maebi", 36000, 36000, RND);
                    purge(server, null);
                    SpawnHelper.spawnChaseAtE(p, "stalker_maebi", at, false);
                }
            }
            default -> end();
        }
    }
}
