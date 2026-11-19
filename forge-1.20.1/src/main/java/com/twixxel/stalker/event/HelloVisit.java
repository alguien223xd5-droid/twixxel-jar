package com.twixxel.stalker.event;

import com.twixxel.stalker.TwixxelMod;
import com.twixxel.stalker.entity.StalkerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerSleepInBedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * La visita del HELLO (cada 2 dias): 1) de dia se asoma en una ventana 2x2 y dice "Vendre esta noche";
 * 2) de noche tocan la puerta 3 veces; 3) al intentar dormir sale gateando de debajo de la cama,
 * se para, le truena el cuello y te persigue como el HELLO normal.
 * El estado se guarda en los datos del jugador (twx_hv_phase = "" | noche | cama).
 */
public final class HelloVisit {
    public static final String K_NEXT = "twx_hv_next", K_PHASE = "twx_hv_phase", K_NOW = "twx_hv_now";

    private static final class Knock {
        Vec3 at;
        int t = -160;      // el huevo "hello 3" empieza a tocar a los 8 s
        int round = 0;
    }

    private static final class Bed {
        UUID player;
        StalkerEntity e;
        Vec3 start, end;
        int t = 0;
        boolean stand = false;
    }

    private final Map<UUID, Knock> knocks = new HashMap<>();
    private final List<Bed> beds = new ArrayList<>();
    private final Map<UUID, Long> retry = new HashMap<>();

    private static boolean es() {
        return com.twixxel.stalker.event.Lang.es();
    }

    public static void setPhase(ServerPlayer p, String phase) {
        p.getPersistentData().putString(K_PHASE, phase);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        tickKnocks(server);
        tickBeds(server);
        long now = server.getTickCount();
        if (now % 20 != 0 || !com.twixxel.stalker.Config.NATURAL_SPAWNS.get()) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!p.isAlive() || p.isSpectator() || p.level().dimension() != Level.OVERWORLD) continue;
            CompoundTag d = p.getPersistentData();
            String phase = d.getString(K_PHASE);
            long day = p.level().getDayTime() / 24000L, dt = p.level().getDayTime() % 24000L;
            if (phase.isEmpty()) {
                if (!d.contains(K_NEXT)) d.putLong(K_NEXT, day + 2);
                if (day >= d.getLong(K_NEXT) && dt >= 1000 && dt <= 11000 && StalkerEntity.ACTIVE.isEmpty()
                        && now >= retry.getOrDefault(p.getUUID(), 0L)) {
                    if (!SpawnHelper.spawnScare(p, "stalker_hello3")) retry.put(p.getUUID(), now + 200);
                }
            } else if (phase.equals("noche") && !knocks.containsKey(p.getUUID())) {
                boolean night = dt >= 13000 && dt <= 23500;
                if (night || d.getBoolean(K_NOW)) startKnock(p);
            }
        }
    }

    public void startKnock(ServerPlayer p) {
        if (knocks.containsKey(p.getUUID())) return;
        ServerLevel level = p.serverLevel();
        BlockPos base = p.blockPosition(), best = null;
        double bd = 1e9;
        for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) for (int y = -3; y <= 3; y++) {
            BlockPos q = base.offset(x, y, z);
            if (!(level.getBlockState(q).getBlock() instanceof DoorBlock)) continue;
            double d = x * x + y * y + z * z;
            if (d < bd) {
                bd = d;
                best = q;
            }
        }
        Knock k = new Knock();
        if (best != null) {
            k.at = new Vec3(best.getX() + .5, best.getY() + .5, best.getZ() + .5);
        } else {                                                    // sin puerta: se oye en la pared, detras de ti
            Vec3 look = p.getLookAngle();
            double l = Math.max(1e-3, Math.hypot(look.x, look.z));
            k.at = new Vec3(p.getX() - look.x / l * 5, p.getY() + 1, p.getZ() - look.z / l * 5);
        }
        if (!p.getPersistentData().getBoolean(K_NOW)) k.t = 0;
        knocks.put(p.getUUID(), k);
    }

    private void tickKnocks(MinecraftServer server) {
        Iterator<Map.Entry<UUID, Knock>> it = knocks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Knock> en = it.next();
            ServerPlayer p = server.getPlayerList().getPlayer(en.getKey());
            Knock k = en.getValue();
            if (p == null) {
                it.remove();
                continue;
            }
            if (k.t >= 0 && k.t % 80 == 0) {
                if (k.round >= 3) {
                    p.getPersistentData().remove(K_NOW);
                    setPhase(p, "cama");                            // ahora te espera debajo de la cama
                    it.remove();
                    continue;
                }
                float[] pitches = {1.5f, 1.4f, 1.55f};
                int[] delays = {0, 5, 10};
                for (int i = 0; i < 3; i++) {
                    final int idx = i;
                    final Vec3 at = k.at;
                    final ServerLevel lv = p.serverLevel();
                    server.tell(new net.minecraft.server.TickTask(server.getTickCount() + delays[i], () ->
                            lv.playSound(null, at.x, at.y, at.z, SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR, SoundSource.BLOCKS, 0.7f, pitches[idx])));
                }
                k.round++;
            }
            k.t++;
        }
    }

    // ------------------------------------------------------------------ cama
    @SubscribeEvent
    public void onSleep(PlayerSleepInBedEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        if (!"cama".equals(p.getPersistentData().getString(K_PHASE))) return;
        BlockPos pos = event.getOptionalPos().orElse(null);
        if (pos == null || !beds.isEmpty()) return;
        ServerLevel level = p.serverLevel();
        BlockState st = level.getBlockState(pos);
        if (!(st.getBlock() instanceof BedBlock)) return;
        List<BlockPos> cells = new ArrayList<>();
        cells.add(pos);
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            BlockPos q = pos.offset(d[0], 0, d[1]);
            if (level.getBlockState(q).getBlock() instanceof BedBlock) {
                cells.add(q);
                break;
            }
        }
        BlockPos bestCell = null;
        int bdx = 0, bdz = 0;
        double bd = 1e9;
        for (BlockPos c : cells) for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            BlockPos q = c.offset(d[0], 0, d[1]);
            if (cells.contains(q) || !free(level, q)) continue;
            double dist = Math.hypot(q.getX() + .5 - p.getX(), q.getZ() + .5 - p.getZ());
            if (dist < bd) {
                bd = dist;
                bestCell = c;
                bdx = d[0];
                bdz = d[1];
            }
        }
        if (bestCell == null) return;
        Vec3 start = new Vec3(bestCell.getX() + .5, bestCell.getY(), bestCell.getZ() + .5);
        double len = free(level, bestCell.offset(bdx * 2, 0, bdz * 2)) ? 2.2 : 1.2;
        Vec3 end = start.add(bdx * len, 0, bdz * len);
        BlockPos wake = null;                                       // te saca de la cama por el otro lado
        for (int[] d : new int[][]{{-bdx, -bdz}, {bdz, bdx}, {-bdz, -bdx}}) {
            for (BlockPos c : cells) {
                BlockPos q = c.offset(d[0], 0, d[1]);
                if (!cells.contains(q) && free(level, q)) {
                    wake = q;
                    break;
                }
            }
            if (wake != null) break;
        }
        Vec3 w = wake != null ? new Vec3(wake.getX() + .5, wake.getY(), wake.getZ() + .5) : end.add(bdx * 1.5, 0, bdz * 1.5);
        p.teleportTo(w.x, w.y, w.z);
        double yaw = Math.atan2(-(end.x - w.x), end.z - w.z) * 180 / Math.PI;
        p.setYRot((float) yaw);
        p.setYHeadRot((float) yaw);
        float eyaw = (float) (Math.atan2(-(p.getX() - start.x), p.getZ() - start.z) * 180 / Math.PI) - 90f;
        StalkerEntity e = SpawnHelper.createScripted(level, "stalker_hello", start, eyaw);
        if (e == null) return;
        e.setProp("mov", 2);                                        // gateando
        Bed b = new Bed();
        b.player = p.getUUID();
        b.e = e;
        b.start = start;
        b.end = end;
        beds.add(b);
        setPhase(p, "");
        var se = TwixxelMod.SOUND_EVENTS.get("stalker.peeker_scream");
        if (se != null) {
            for (ServerPlayer q : level.players()) {
                if (q.distanceToSqr(start) <= 32 * 32) q.playNotifySound(se.get(), SoundSource.MASTER, 1f, 0.8f);
            }
        }
        event.setResult(Player.BedSleepingProblem.OTHER_PROBLEM);
    }

    private static boolean free(ServerLevel l, BlockPos q) {
        return l.getBlockState(q).getCollisionShape(l, q).isEmpty() && l.getBlockState(q.above()).getCollisionShape(l, q.above()).isEmpty()
                && !l.getBlockState(q.below()).getCollisionShape(l, q.below()).isEmpty();
    }

    private void tickBeds(MinecraftServer server) {
        Iterator<Bed> it = beds.iterator();
        while (it.hasNext()) {
            Bed b = it.next();
            ServerPlayer p = server.getPlayerList().getPlayer(b.player);
            if (p == null || !b.e.isAlive()) {
                if (b.e.isAlive()) b.e.discard();
                it.remove();
                continue;
            }
            b.t++;
            double fy;
            if (!b.stand) {
                double k = Math.min(1.0, b.t / 50.0);
                Vec3 pos = b.start.add(b.end.subtract(b.start).scale(k));
                fy = Math.atan2(-(p.getX() - pos.x), p.getZ() - pos.z) * 180 / Math.PI - 90;
                b.e.setPos(pos.x, pos.y, pos.z);
                if (b.t >= 50) {
                    b.stand = true;
                    b.t = 0;
                    b.e.setProp("mov", 3);                          // se para
                }
            } else {
                fy = Math.atan2(-(p.getX() - b.end.x), p.getZ() - b.end.z) * 180 / Math.PI - 90;
                b.e.setPos(b.end.x, b.end.y, b.end.z);
                if (b.t >= 26) {                                    // ya esta de pie: es el HELLO normal
                    b.e.discard();
                    SpawnHelper.spawnChaseAt(p, "stalker_hello", b.end, true);
                    it.remove();
                    continue;
                }
            }
            b.e.setYRot((float) fy);
            b.e.setYHeadRot((float) fy);
            b.e.setYBodyRot((float) fy);
        }
    }
}
