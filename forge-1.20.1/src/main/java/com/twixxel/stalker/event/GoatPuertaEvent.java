package com.twixxel.stalker.event;

import com.twixxel.stalker.entity.StalkerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.Random;
import java.util.UUID;

/**
 * GOAT DE LA PUERTA (stalker_goat_puerta): abre una puerta cercana, saca la cabeza por el marco y luego la mano.
 * Se queda mirandote hasta que le ves la cara 3 s (o te acercas a la puerta): entonces se esconde y sale gateando
 * a perseguirte (stalker_goat). Natural cada 15-30 min.
 */
public final class GoatPuertaEvent {
    private static final double HEAD_LAT = 1.59, EDGE = 1.25, DY = -.45, DEPTH = .72, NEAR = 2.2, FAR = 40, LOOK_COS = .95, RANGE = 16;
    private static final int T_PEEK = 16, T_HEAD = T_PEEK + 50, T_HAND = T_PEEK + 114, LOOK_NEED = 60, LOOK_GAP = 8, STAY = 6000, RETIRA = 7;

    private static StalkerEntity e;
    private static UUID pid;
    private static ServerLevel level;
    private static BlockPos door;
    private static double fx, fz, px, pz;      // f = hacia afuera, perp = lateral
    private static int k, t, go, look, lookGap, lado;
    private static Vec3 head;
    private static long next = -1;
    private static final Random RND = new Random();

    public static boolean active() {
        return pid != null;
    }

    private static void snd(String id, Vec3 at, float vol, float pitch) {
        var se = BedHide.se(id);
        if (se != null && level != null) level.playSound(null, at.x, at.y, at.z, se, SoundSource.HOSTILE, vol, pitch);
    }

    private static boolean cover(BlockPos b) {
        return Houses.cover(level, b) && Houses.cover(level, b.above());
    }

    public static boolean startFor(ServerPlayer p) {
        if (pid != null) return false;
        ServerLevel l = p.serverLevel();
        BlockPos pb = p.blockPosition(), best = null;
        double bd = 1e9;
        for (int dx = -(int) RANGE; dx <= RANGE; dx++) for (int dz = -(int) RANGE; dz <= RANGE; dz++) for (int dy = -2; dy <= 2; dy++) {
            BlockPos q = pb.offset(dx, dy, dz);
            if (!Houses.isLowerDoor(l, q)) continue;
            double d = Math.hypot(q.getX() + .5 - p.getX(), q.getZ() + .5 - p.getZ());
            if (d < 3 || d > RANGE) continue;
            if (d < bd) {
                bd = d;
                best = q;
            }
        }
        if (best == null) return false;
        var facing = l.getBlockState(best).getValue(DoorBlock.FACING);
        double nx = facing.getStepX(), nz = facing.getStepZ();
        double side = (p.getX() - (best.getX() + .5)) * nx + (p.getZ() - (best.getZ() + .5)) * nz;
        fx = side >= 0 ? -nx : nx;
        fz = side >= 0 ? -nz : nz;
        px = -fz;
        pz = fx;
        level = l;
        door = best;
        int kk = 0;
        for (int s : new int[]{1, -1}) {
            BlockPos j = best.offset((int) (px * s), 0, (int) (pz * s));
            if (cover(j)) {
                kk = s;
                break;
            }
        }
        if (kk == 0) {
            level = null;
            door = null;
            return false;
        }
        k = kk;
        pid = p.getUUID();
        t = 0;
        go = -1;
        look = 0;
        lookGap = 0;
        lado = -1;
        e = SpawnHelper.createScripted(l, "stalker_goat_puerta", new Vec3(best.getX() + .5, best.getY(), best.getZ() + .5), 0f);
        if (e == null) {
            end();
            return false;
        }
        e.noCulling = true;
        e.setProp("fase", 0);
        Houses.setDoor(l, best, true);
        snd("random.door_open", new Vec3(best.getX() + .5, best.getY() + 1, best.getZ() + .5), 1f, .7f);
        pose(p);
        return true;
    }

    /** cara fija en el borde y cuerpo detras del marco segun desde donde miras. */
    private static void pose(ServerPlayer p) {
        double lat = .5 + EDGE - HEAD_LAT;
        double tx = door.getX() + .5 + px * k * lat + fx * DEPTH, tz = door.getZ() + .5 + pz * k * lat + fz * DEPTH;
        double ty = door.getY() + DY;
        Vec3 E = p.getEyePosition();
        double vx = tx - E.x, vz = tz - E.z, vl = Math.hypot(vx, vz);
        if (vl < 1e-4) vl = 1;
        vx /= vl;
        vz /= vl;
        double rx = -vz, rz = vx;                                  // a tu derecha
        double sgn = (rx * px * k + rz * pz * k) > 0 ? -1 : 1;
        double ox = tx - rx * sgn * HEAD_LAT, oz = tz - rz * sgn * HEAD_LAT;
        e.setPos(ox, ty, oz);
        float yaw = (float) (Math.atan2(-(E.x - ox), E.z - oz) * 180 / Math.PI);
        e.setYRot(yaw);
        e.setYHeadRot(yaw);
        e.setYBodyRot(yaw);
        head = new Vec3(tx, door.getY() + 2.09 + DY, tz);
        int ld = sgn > 0 ? 1 : 0;
        if (ld != lado) {
            lado = ld;
            e.setProp("lado", ld);
        }
    }

    private static void end() {
        if (e != null && e.isAlive()) e.discard();
        e = null;
        pid = null;
        level = null;
        door = null;
        MinecraftServer s = ServerLifecycleHooks.getCurrentServer();
        if (s != null) next = s.getTickCount() + SpawnConfig.ticks("goat_puerta", 18000, 36000, RND);
    }

    private static void chase(ServerPlayer p) {
        Vec3 a = new Vec3(door.getX() + .5 + px * k + fx, door.getY(), door.getZ() + .5 + pz * k + fz);
        Vec3 b = new Vec3(door.getX() + .5 + fx, door.getY(), door.getZ() + .5 + fz);
        BlockPos pa = BlockPos.containing(a);
        Vec3 P = (Houses.free(level, pa) && Houses.free(level, pa.above())) ? a : b;
        end();
        if (!p.isAlive()) return;
        snd("stalker.goat_crawl_scream", P, 1.6f, .6f);
        SpawnHelper.spawnChaseAtE(p, "stalker_goat", P, false);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        long now = server.getTickCount();
        if (pid == null) {
            if (now % 40 != 0 || !com.twixxel.stalker.Config.NATURAL_SPAWNS.get()) return;
            if (next < 0) next = now + SpawnConfig.ticks("goat_puerta", 18000, 36000, RND);
            if (now < next) return;
            if (!StalkerEntity.ACTIVE.isEmpty() || VigilanteEvent.active()) {
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
        double dDoor = Math.hypot(p.getX() - (door.getX() + .5), p.getZ() - (door.getZ() + .5));
        if (dDoor > FAR) {
            end();
            return;
        }
        if (go >= 0) {
            if (t - go >= RETIRA) chase(p);
            return;
        }
        if (t % 2 == 0) pose(p);
        if (t == T_PEEK) e.setProp("fase", 1);
        if (t == T_PEEK + 20) snd("stalker.run_crack", head, .9f, 1.25f);
        if (t == T_HEAD + 6) snd("stalker.goat_crawl_scream", head, .35f, .5f);
        if (t == T_HAND - 8) snd("stalker.js_golpe", head, .5f, .8f);
        boolean looking = false;
        if (t >= T_HEAD - 10) {
            Vec3 eye = p.getEyePosition(), v = head.subtract(eye);
            double d = v.length();
            looking = d > .1 && d < 48 && p.getLookAngle().dot(v) / d >= LOOK_COS && Houses.sight(level, eye, head, p);
        }
        if (looking) {
            look++;
            lookGap = 0;
        } else if (look > 0 && ++lookGap > LOOK_GAP) look = 0;
        if (t >= STAY && look == 0) {
            e.setProp("fase", 2);
            go = 1000000;
            end();
            return;
        }
        if (look >= LOOK_NEED || (t > T_PEEK && dDoor <= NEAR)) {
            go = t;
            e.setProp("fase", 2);
            snd("stalker.run_crack_2", head, 1f, 1.2f);
        }
    }
}
