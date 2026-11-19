package com.twixxel.stalker.event;

import com.twixxel.stalker.TwixxelMod;
import com.twixxel.stalker.entity.StalkerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerSleepInBedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.UUID;

/**
 * stalker_bed: de noche, al intentar dormir hay 10 % (una tirada por noche) de que no te deje:
 * el RUN sale arrastrandose de debajo de la cama, se para, truena el cuello y te persigue.
 * Huevo/comando: /twixxel event stalker_bed (busca una cama a 8 bloques).
 */
public final class BedStalker {
    private static final double CHANCE = 0.10;
    private static final int PEEK = 30, CRAWL = 70, STAND = 24;
    private static final double HEAD = 2.8;
    private static final String K_NIGHT = "twx_bed_night";

    private static StalkerEntity ent;
    private static UUID player;
    private static Vec3 from, to;
    private static float yaw;
    private static int t, phase;      // 0 peek, 1 crawl, 2 stand
    private static boolean said;

    public static boolean active() {
        return ent != null;
    }

    private static boolean es() {
        return com.twixxel.stalker.event.Lang.es();
    }

    private static void sound(ServerLevel l, String ev, Vec3 at, float vol, float pitch) {
        var se = TwixxelMod.SOUND_EVENTS.get(TwixxelMod.loc(ev));
        if (se != null) l.playSound(null, at.x, at.y, at.z, se.get(), SoundSource.HOSTILE, vol, pitch);
    }

    @SubscribeEvent
    public void onSleep(PlayerSleepInBedEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || active()) return;
        ServerLevel level = p.serverLevel();
        long dt = level.getDayTime() % 24000L;
        if (dt < 12542 || dt > 23459 || p.isShiftKeyDown() || !StalkerEntity.ACTIVE.isEmpty()) return;
        if ("cama".equals(p.getPersistentData().getString(HelloVisit.K_PHASE))) return;   // lo maneja HelloVisit
        BlockPos pos = event.getOptionalPos().orElse(null);
        if (pos == null || !(level.getBlockState(pos).getBlock() instanceof BedBlock)) return;
        long night = (level.getDayTime() + 6000) / 24000L;
        if (p.getPersistentData().getLong(K_NIGHT) == night + 1) return;                    // ya se tiro el dado
        p.getPersistentData().putLong(K_NIGHT, night + 1);
        if (level.random.nextDouble() >= CHANCE) return;
        if (start(p, pos)) event.setResult(net.minecraft.world.entity.player.Player.BedSleepingProblem.OTHER_PROBLEM);
    }

    public static boolean startNear(ServerPlayer p) {
        ServerLevel level = p.serverLevel();
        BlockPos b = p.blockPosition(), best = null;
        double bd = 1e9;
        for (int x = -8; x <= 8; x++) for (int z = -8; z <= 8; z++) for (int y = -3; y <= 3; y++) {
            BlockPos q = b.offset(x, y, z);
            if (!(level.getBlockState(q).getBlock() instanceof BedBlock)) continue;
            double d = x * x + y * y * 2 + z * z;
            if (d < bd) {
                bd = d;
                best = q;
            }
        }
        if (best == null) {
            p.displayClientMessage(Component.literal(es() ? "§cNo hay una cama cerca" : "§cThere is no bed nearby"), true);
            return false;
        }
        if (active()) return false;
        if (!start(p, best)) {
            p.displayClientMessage(Component.literal(es() ? "§cNo hay espacio al lado de la cama" : "§cThere is no room next to the bed"), true);
            return false;
        }
        return true;
    }

    private static boolean free(ServerLevel l, BlockPos q) {
        return l.getBlockState(q).getCollisionShape(l, q).isEmpty() && l.getBlockState(q.above()).getCollisionShape(l, q.above()).isEmpty()
                && !l.getBlockState(q.below()).getCollisionShape(l, q.below()).isEmpty();
    }

    private static boolean start(ServerPlayer p, BlockPos bedPos) {
        ServerLevel level = p.serverLevel();
        java.util.List<BlockPos> cells = new java.util.ArrayList<>();
        cells.add(bedPos);
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            BlockPos q = bedPos.offset(d[0], 0, d[1]);
            if (level.getBlockState(q).getBlock() instanceof BedBlock) {
                cells.add(q);
                break;
            }
        }
        BlockPos hc = null;
        int fx = 0, fz = 0;
        double bd = 1e9;
        for (BlockPos c : cells) for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            BlockPos q = c.offset(d[0], 0, d[1]);
            if (cells.contains(q) || !free(level, q)) continue;
            double dist = Math.hypot(q.getX() + .5 - p.getX(), q.getZ() + .5 - p.getZ());
            if (dist < bd) {
                bd = dist;
                hc = c;
                fx = d[0];
                fz = d[1];
            }
        }
        if (hc == null) return false;
        double cx = hc.getX() + .5, cz = hc.getZ() + .5, y = hc.getY();
        double ex = cx + fx * .5, ez = cz + fz * .5;
        from = new Vec3(ex - fx * (HEAD + .15), y, ez - fz * (HEAD + .15));
        to = new Vec3(ex + fx * .9, y, ez + fz * .9);
        yaw = (float) (Math.atan2(-fx, fz) * 180 / Math.PI);
        ent = SpawnHelper.createScripted(level, "stalker_bed", from, yaw);
        if (ent == null) return false;
        ent.setProp("fase", 0);
        player = p.getUUID();
        t = 0;
        phase = 0;
        said = false;
        level.playSound(null, ex, y + .2, ez, SoundEvents.WOOD_BREAK, SoundSource.BLOCKS, 1f, .5f);
        var se = TwixxelMod.SOUND_EVENTS.get("stalker.peeker_vanish");
        if (se != null) p.playNotifySound(se.get(), SoundSource.MASTER, .8f, .8f);
        return true;
    }

    private static void end() {
        if (ent != null && ent.isAlive()) ent.discard();
        ent = null;
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ent == null) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(player);
        if (!ent.isAlive() || p == null || p.level() != ent.level()) {
            end();
            return;
        }
        ServerLevel level = (ServerLevel) ent.level();
        t++;
        if (phase == 0) {
            if (t % 10 == 0) level.playSound(null, from.x, from.y, from.z, SoundEvents.WOOL_STEP, SoundSource.BLOCKS, 1f, .6f);
            if (t >= PEEK) {
                phase = 1;
                t = 0;
                ent.setProp("fase", 1);
                if (!said) {
                    said = true;
                    for (ServerPlayer q : level.players()) {
                        if (q.distanceToSqr(from) > 32 * 32) continue;
                        var se = TwixxelMod.SOUND_EVENTS.get(TwixxelMod.loc("stalker.bed_saliendo_es"));
                        if (se != null) q.playNotifySound(se.get(), SoundSource.MASTER, 1f, 1f);
                        q.displayClientMessage(Component.literal(es() ? "§cEstoy saliendo de tu cama" : "§cI'm getting out of your bed"), true);
                    }
                }
            }
            return;
        }
        if (phase == 1) {
            double f = Math.min(1.0, t / (double) CRAWL), cyc = (t % 20) / 20.0;
            double pull = (Math.floor(t / 20.0) + (cyc < .5 ? cyc * 2 : 1)) / (CRAWL / 20.0);
            double g = Math.min(1.0, Math.max(f * .35, pull));
            Vec3 pos = from.add(to.subtract(from).scale(g));
            ent.setPos(pos.x, pos.y, pos.z);
            ent.setYRot(yaw);
            ent.setYHeadRot(yaw);
            ent.setYBodyRot(yaw);
            if (t % 10 == 5) level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.WOOL_STEP, SoundSource.BLOCKS, 1.2f, .5f + level.random.nextFloat() * .2f);
            if (t % 20 == 12) level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.WOOD_STEP, SoundSource.BLOCKS, .7f, .4f);
            if (t >= CRAWL) {
                phase = 2;
                t = 0;
                ent.setPos(to.x, to.y, to.z);
                ent.setProp("fase", 2);
                sound(level, "stalker.run_crack_2", to, 2f, .9f);
            }
            return;
        }
        if (t > 14) {                                                // se para girando hacia ti
            double want = Math.atan2(-(p.getX() - to.x), p.getZ() - to.z) * 180 / Math.PI;
            double d = ((want - yaw + 540) % 360) - 180;
            yaw += d * .35;
            ent.setYRot(yaw);
            ent.setYHeadRot(yaw);
            ent.setYBodyRot(yaw);
        }
        if (t >= STAND) {
            Vec3 at = to;
            end();
            if (p.isAlive()) SpawnHelper.spawnChaseAt(p, "stalker_run", at, true);
        }
    }
}
