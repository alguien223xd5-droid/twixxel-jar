package com.twixxel.stalker.event;

import com.twixxel.stalker.TwixxelMod;
import com.twixxel.stalker.entity.StalkerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.Random;
import java.util.UUID;

/**
 * stalker_life: aparece en una PUERTA colgando DE CABEZA, mirandote.
 *  - Si te acercas a la puerta: desaparece y te deja a oscuras 10 s con su musica.
 *  - Si lo miras 3 s: te dice "hola" y se va para arriba (detras de la pared).
 *  - Si pasas al otro lado de la puerta, se pasa al otro lado para seguir mirandote.
 *  - Aparece solo cada 10 a 15 min si hay una puerta cerca (/twixxel event stalker_life para probar).
 */
public final class LifeEvent {
    private static final int RANGE = 16, READY = 32, LOOK_NEED = 60, DARK = 200, HOLA_T = 50, UP_T = 30, MAX_T = 2400;
    private static final double MIN_DIST = 3.5, OUT = .53, EYES_Y = 1.7, LOOK_COS = .93, NEAR = 2.0;

    private static StalkerEntity e;
    private static UUID player;
    private static ServerLevel level;
    private static BlockPos door;
    private static int fx, fz;
    private static int t, looked, hola = -1, up = -1;
    private static long next = -1;
    private static final Random RND = new Random();

    private static boolean es() {
        return com.twixxel.stalker.event.Lang.es();
    }

    private static boolean isDoor(ServerLevel l, BlockPos q) {
        return l.getBlockState(q).getBlock() instanceof DoorBlock;
    }

    private static boolean free(ServerLevel l, BlockPos q) {
        BlockState s = l.getBlockState(q);
        return s.getCollisionShape(l, q).isEmpty() && s.getFluidState().isEmpty();
    }

    private static boolean cover(ServerLevel l, BlockPos q) {
        return l.getBlockState(q).isSolidRender(l, q);
    }

    private static boolean sight(ServerLevel l, Vec3 a, Vec3 b, net.minecraft.world.entity.Entity ctx) {
        return l.clip(new ClipContext(a, b, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, ctx)).getType() == HitResult.Type.MISS;
    }

    private static Vec3 pos(BlockPos d, int ffx, int ffz) {
        return new Vec3(d.getX() + .5 + ffx * OUT, d.getY(), d.getZ() + .5 + ffz * OUT);
    }

    private static float yaw(int ffx, int ffz) {
        return (float) (Math.atan2(ffx, -ffz) * 180 / Math.PI);          // mira hacia adentro
    }

    private static Vec3 eyes(BlockPos d, int ffx, int ffz) {
        return new Vec3(d.getX() + .5 + ffx * OUT, d.getY() + EYES_Y, d.getZ() + .5 + ffz * OUT);
    }

    /** Busca una puerta (mitad de abajo) cerca; el stalker cuelga del lado contrario al jugador. */
    private static Object[] find(ServerPlayer p, int rMax) {
        ServerLevel l = p.serverLevel();
        Vec3 E = p.getEyePosition();
        Vec3 view = p.getLookAngle();
        double vl = Math.hypot(view.x, view.z);
        if (vl < 1e-4) vl = 1;
        double vx = view.x / vl, vz = view.z / vl;
        BlockPos pb = p.blockPosition();
        Object[] best = null;
        double bestScore = 1e9;
        for (int dx = -rMax; dx <= rMax; dx++) for (int dz = -rMax; dz <= rMax; dz++) for (int dy = -3; dy <= 3; dy++) {
            BlockPos q = pb.offset(dx, dy, dz);
            double d = Math.hypot(q.getX() + .5 - E.x, q.getZ() + .5 - E.z);
            if (d > rMax || d < MIN_DIST) continue;
            BlockState st = l.getBlockState(q);
            if (!(st.getBlock() instanceof DoorBlock) || st.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) continue;
            for (int[] ax : new int[][]{{1, 0}, {0, 1}}) {
                if (!free(l, q.offset(ax[0], 0, ax[1])) || !free(l, q.offset(ax[0], 1, ax[1]))
                        || !free(l, q.offset(-ax[0], 0, -ax[1])) || !free(l, q.offset(-ax[0], 1, -ax[1]))) continue;
                double side = (q.getX() + .5 - p.getX()) * ax[0] + (q.getZ() + .5 - p.getZ()) * ax[1];
                if (Math.abs(side) < .6) continue;
                int ffx = side > 0 ? ax[0] : -ax[0], ffz = side > 0 ? ax[1] : -ax[1];
                Vec3 ey = eyes(q, ffx, ffz);
                boolean seen = sight(l, E, new Vec3(q.getX() + .5, q.getY() + 1.5, q.getZ() + .5), p);
                boolean wall = cover(l, q.above(2));
                double ex = (ey.x - E.x) / d, ez = (ey.z - E.z) / d;
                double score = Math.abs(d - 7) * .4 - (ex * vx + ez * vz) * 2 + RND.nextDouble() * .5;
                if (!seen) score += 6;
                if (!wall) score += 3;
                if (score < bestScore) {
                    bestScore = score;
                    best = new Object[]{q, ffx, ffz};
                }
            }
        }
        return best;
    }

    private static boolean start(ServerPlayer p, Object[] sp) {
        if (e != null) return false;
        level = p.serverLevel();
        door = (BlockPos) sp[0];
        fx = (Integer) sp[1];
        fz = (Integer) sp[2];
        BlockState st = level.getBlockState(door);
        if (st.getBlock() instanceof DoorBlock db && !st.getValue(DoorBlock.OPEN)) db.setOpen(null, level, st, door, true);   // la deja abierta para verlo
        e = SpawnHelper.createScripted(level, "stalker_life", pos(door, fx, fz), yaw(fx, fz));
        if (e == null) return false;
        e.noCulling = true;
        e.setProp("fase", 0);
        player = p.getUUID();
        t = 0;
        looked = 0;
        hola = -1;
        up = -1;
        return true;
    }

    public static boolean startFor(ServerPlayer p) {
        if (e != null) return false;
        Object[] sp = find(p, 20);
        if (sp != null) return start(p, sp);
        p.displayClientMessage(Component.literal(es() ? "§cNo hay ninguna puerta cerca (ponte a 4 bloques o más de una puerta)"
                : "§cNo door nearby (stand 4+ blocks from a door)"), true);
        return false;
    }

    private static void end(long now) {
        if (e != null && e.isAlive()) e.discard();
        e = null;
        next = now + SpawnConfig.ticks("life", 12000, 18000, RND);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        long now = server.getTickCount();
        if (e == null) {
            if (now % 20 != 0 || !com.twixxel.stalker.Config.NATURAL_SPAWNS.get()) return;
            if (next < 0) next = now + SpawnConfig.ticks("life", 12000, 18000, RND);
            if (now < next) return;
            var players = server.getPlayerList().getPlayers();
            if (players.isEmpty() || !StalkerEntity.ACTIVE.isEmpty()) {
                next = now + 100;
                return;
            }
            ServerPlayer p = players.get(RND.nextInt(players.size()));
            Object[] sp = p.isAlive() && !p.isSpectator() && !SuenoEvent.isDreaming(p) && !BedHide.isHidden(p.getUUID()) ? find(p, RANGE) : null;
            if (sp == null || !start(p, sp)) next = now + 600;
            return;
        }
        ServerPlayer p = server.getPlayerList().getPlayer(player);
        if (!e.isAlive() || p == null || p.level() != level) {
            end(now);
            return;
        }
        t++;
        double dDoor = Math.hypot(p.getX() - (door.getX() + .5), p.getZ() - (door.getZ() + .5));
        if (dDoor > 48) {
            end(now);
            return;
        }
        if (up >= 0) {                                                     // se va para arriba
            if (t - up >= UP_T) end(now);
            return;
        }
        if (dDoor <= NEAR) {                                               // te acercaste: oscuridad 10 s + musica
            p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, DARK, 0, false, false));
            p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, DARK, 0, false, false));
            var se = TwixxelMod.SOUND_EVENTS.get("stalker.life_music");
            if (se != null) p.playNotifySound(se.get(), SoundSource.MASTER, 1f, 1f);
            end(now);
            return;
        }
        if (hola >= 0) {                                                   // dijo hola: se queda el texto y despues sube
            if ((t - hola) % 20 == 0) p.displayClientMessage(Component.literal(es() ? "§cHola..." : "§cHello..."), true);
            if (t - hola >= HOLA_T) {
                up = t;
                e.setProp("fase", 2);
            }
            return;
        }
        if (t % 4 == 0) {                                                  // si pasas al otro lado, se pasa tambien
            double side = (door.getX() + .5 - p.getX()) * fx + (door.getZ() + .5 - p.getZ()) * fz;
            if (side < -.4) {
                fx = -fx;
                fz = -fz;
                Vec3 np = pos(door, fx, fz);
                e.setPos(np.x, np.y, np.z);
                e.setYRot(yaw(fx, fz));
                e.setYHeadRot(yaw(fx, fz));
                e.setYBodyRot(yaw(fx, fz));
            }
        }
        if (t < READY) return;
        Vec3 E = p.getEyePosition(), T = eyes(door, fx, fz), d = T.subtract(E);
        double len = Math.max(1e-4, d.length());
        boolean lookingAt = p.getLookAngle().dot(d) / len >= LOOK_COS
                && level.clip(new ClipContext(E, new Vec3(door.getX() + .5, door.getY() + 1.5, door.getZ() + .5), ClipContext.Block.VISUAL,
                ClipContext.Fluid.NONE, p)).getType() == HitResult.Type.MISS;
        looked = lookingAt ? looked + 1 : Math.max(0, looked - 2);
        if (looked >= LOOK_NEED) {                                         // "hola": audio + texto rojo
            hola = t;
            var se = TwixxelMod.SOUND_EVENTS.get(TwixxelMod.loc("stalker.peek_hola"));
            if (se != null) p.playNotifySound(se.get(), SoundSource.MASTER, 1f, 1f);
            p.displayClientMessage(Component.literal(es() ? "§cHola..." : "§cHello..."), true);
            return;
        }
        if (t >= MAX_T) {                                                  // si lo ignoras mucho rato se va solo
            up = t;
            e.setProp("fase", 2);
        }
    }
}
