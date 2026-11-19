package com.twixxel.stalker.event;

import com.twixxel.stalker.TwixxelMod;
import com.twixxel.stalker.entity.StalkerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.Random;
import java.util.UUID;

/**
 * VIGILANTE (stalker_vigilante): estas dentro de una casa con una puerta a 4+ bloques. Alguien golpea 3 veces, rompe la puerta
 * y se asoma desde detras del marco con musica. Si lo miras mucho o pasa mucho tiempo se esconde; si te acercas a 2 bloques
 * te asusta (oscuridad) y desaparece.
 */
public final class VigilanteEvent {
    private static final int K1 = 20, K2 = 42, K3 = 64, BREAK = 86, PEEK = 92, LOOK_NEED = 200, MAX_PEEK = 7200, HIDE_T = 28, DARK = 200;
    private static final double MIN_DOOR = 4, RANGE = 16, LOOK_COS = .94, NEAR = 2.0, HEAD_LAT = .62;

    private static StalkerEntity e;
    private static UUID pid;
    private static ServerLevel level;
    private static BlockPos door;       // mitad inferior
    private static double outX, outZ, latX, latZ;
    private static int t, looked, fase;
    private static long next = -1;
    private static final Random RND = new Random();

    public static boolean active() {
        return pid != null;
    }

    private static void snd(ServerPlayer p, String ev, float vol) {
        var se = TwixxelMod.SOUND_EVENTS.get(TwixxelMod.loc(ev));
        if (se != null) p.playNotifySound(se.get(), SoundSource.MASTER, vol, 1f);
    }

    private static void stopMusic(ServerPlayer p) {
        p.connection.send(new ClientboundStopSoundPacket(new ResourceLocation(TwixxelMod.MODID, "stalker.vigilante_music"), SoundSource.MASTER));
    }

    private static boolean cover(BlockPos b) {
        return Houses.cover(level, b) && Houses.cover(level, b.above());
    }

    public static boolean startFor(ServerPlayer p) {
        if (pid != null) return false;
        ServerLevel l = p.serverLevel();
        BlockPos pb = p.blockPosition();
        BlockPos best = null;
        double bd = 1e9;
        for (int dx = -(int) RANGE; dx <= RANGE; dx++) for (int dz = -(int) RANGE; dz <= RANGE; dz++) for (int dy = -2; dy <= 2; dy++) {
            BlockPos q = pb.offset(dx, dy, dz);
            if (!Houses.isLowerDoor(l, q)) continue;
            double d = Math.hypot(q.getX() + .5 - p.getX(), q.getZ() + .5 - p.getZ());
            if (d < MIN_DOOR || d > RANGE) continue;
            if (d < bd) {
                bd = d;
                best = q;
            }
        }
        if (best == null) return false;
        var st = l.getBlockState(best);
        var facing = st.getValue(DoorBlock.FACING);
        double nx = facing.getStepX(), nz = facing.getStepZ();
        double side = (p.getX() - (best.getX() + .5)) * nx + (p.getZ() - (best.getZ() + .5)) * nz;
        double ox = side >= 0 ? -nx : nx, oz = side >= 0 ? -nz : nz;
        double lx = -oz, lz = ox;
        level = l;
        door = best;
        // la casilla junto a la puerta debe ser pared para esconderse detras del marco
        BlockPos j1 = best.offset((int) lx, 0, (int) lz), j2 = best.offset((int) -lx, 0, (int) -lz);
        if (cover(j1)) {
        } else if (cover(j2)) {
            lx = -lx;
            lz = -lz;
        } else {
            level = null;
            door = null;
            return false;
        }
        outX = ox;
        outZ = oz;
        latX = lx;
        latZ = lz;
        pid = p.getUUID();
        t = 0;
        looked = 0;
        fase = 0;
        Vec3 hid = hidden();
        e = SpawnHelper.createScripted(l, "stalker_vigilante", hid, yaw());
        if (e == null) {
            end(0);
            return false;
        }
        e.noCulling = true;
        e.setProp("fase", 0);
        snd(p, "stalker.vigilante_music", 1f);
        return true;
    }

    private static float yaw() {
        return (float) (Math.atan2(-(-outX), -outZ) * 180 / Math.PI);   // mira hacia adentro de la casa
    }

    private static Vec3 hidden() {
        return new Vec3(door.getX() + .5 + outX * 1.0 + latX * 1.0, door.getY(), door.getZ() + .5 + outZ * 1.0 + latZ * 1.0);
    }

    private static Vec3 peekPos() {
        return new Vec3(door.getX() + .5 + outX * .55 + latX * HEAD_LAT, door.getY(), door.getZ() + .5 + outZ * .55 + latZ * HEAD_LAT);
    }

    private static void place(Vec3 v) {
        if (e == null || !e.isAlive()) return;
        e.setPos(v.x, v.y, v.z);
        float y = yaw();
        e.setYRot(y);
        e.setYHeadRot(y);
        e.setYBodyRot(y);
    }

    private static void end(int minDelay) {
        ServerLevel l = level;
        if (e != null && e.isAlive()) e.discard();
        e = null;
        if (l != null) for (ServerPlayer q : l.players()) stopMusic(q);
        pid = null;
        level = null;
        door = null;
        MinecraftServer s = ServerLifecycleHooks.getCurrentServer();
        if (s != null) next = s.getTickCount() + SpawnConfig.ticks("vigilante", 18000, 24000, RND) + minDelay;
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        long now = server.getTickCount();
        if (pid == null) {
            if (now % 20 != 0 || !com.twixxel.stalker.Config.NATURAL_SPAWNS.get()) return;
            if (next < 0) next = now + SpawnConfig.ticks("vigilante", 18000, 24000, RND);
            if (now < next) return;
            if (!StalkerEntity.ACTIVE.isEmpty()) {
                next = now + 600;
                return;
            }
            var ps = new java.util.ArrayList<>(server.getPlayerList().getPlayers());
            java.util.Collections.shuffle(ps, RND);
            for (ServerPlayer p : ps) if (p.isAlive() && !p.isSpectator() && !SuenoEvent.isDreaming(p) && Houses.isInsideHouse(p) && startFor(p)) return;
            next = now + 600;
            return;
        }
        ServerPlayer p = server.getPlayerList().getPlayer(pid);
        if (p == null || !p.isAlive() || p.level() != level || e == null || !e.isAlive()) {
            end(0);
            return;
        }
        t++;
        Vec3 dc = new Vec3(door.getX() + .5, door.getY() + .5, door.getZ() + .5);
        if (fase == 0) {
            if (t == K1 || t == K2 || t == K3) level.playSound(null, door, SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR, SoundSource.HOSTILE, 1.2f, .8f);
            if (t == BREAK) {
                level.destroyBlock(door, false);
                level.destroyBlock(door.above(), false);
                level.playSound(null, door, SoundEvents.ZOMBIE_BREAK_WOODEN_DOOR, SoundSource.HOSTILE, 1.4f, .8f);
            }
            if (t == PEEK) {
                fase = 1;
                t = 0;
                e.setProp("fase", 1);
                place(peekPos());
            }
            return;
        }
        if (fase == 1) {
            Vec3 head = peekPos().add(0, 1.48, 0);
            Vec3 eye = p.getEyePosition(), v = head.subtract(eye);
            double dist = v.length();
            if (dist < .1) return;
            boolean look = p.getLookAngle().dot(v) / dist >= LOOK_COS && Houses.sight(level, eye, head, p);
            looked = look ? looked + 1 : looked;
            if (Math.hypot(p.getX() - dc.x, p.getZ() - dc.z) <= NEAR || dist <= NEAR) {
                snd(p, "stalker.run_crack_3", 1f);
                level.sendParticles(ParticleTypes.EXPLOSION, head.x, head.y, head.z, 1, 0, 0, 0, 0);
                p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, DARK, 0, false, false));
                p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 60, 0, false, false));
                end(0);
                return;
            }
            if (looked >= LOOK_NEED || t >= MAX_PEEK) {
                fase = 2;
                t = 0;
                e.setProp("fase", 2);
                stopMusic(p);
                place(hidden());
            }
            return;
        }
        if (t >= HIDE_T) end(0);
    }
}
