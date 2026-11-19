package com.twixxel.stalker.event;

import com.twixxel.stalker.entity.FieldProfile;
import com.twixxel.stalker.entity.StalkerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Relojes del WATCHER (addon v3.1.16): 20 min despues de ver el VIDEO 1 del celular aparece el 1o afuera, lejos;
 * si se acerca desaparece y 20 min despues viene el 2o (persecucion). Si a la hora estas bajo techo, espera a que
 * salgas a cielo abierto. El reloj se guarda en el jugador (NBT).
 */
public final class WatcherClock {
    private static final long DELAY = 20L * 60 * 20;
    private static final String K1 = "twxWat1", K2 = "twxWat2";

    private static long now(ServerPlayer p) {
        return p.serverLevel().getServer().overworld().getGameTime();
    }

    public static void arm(ServerPlayer p) {
        CompoundTag d = p.getPersistentData();
        if (d.contains(K1) && d.getLong(K1) > now(p)) return;      // ya viene en camino
        d.putLong(K1, now(p) + DELAY);
    }

    public static void arm2(ServerPlayer p) {
        p.getPersistentData().putLong(K2, now(p) + DELAY);
    }

    private static boolean watcherActive() {
        for (StalkerEntity e : StalkerEntity.ACTIVE) {
            String id = e.getDefId();
            if (e.isAlive() && (id.equals("stalker_watcher") || id.equals("stalker_watcher2"))) return true;
        }
        return false;
    }

    private static boolean tryRun(ServerPlayer p, String key, String id) {
        CompoundTag d = p.getPersistentData();
        if (!d.contains(key) || now(p) < d.getLong(key)) return false;
        if (!p.serverLevel().canSeeSky(BlockPos.containing(p.getX(), p.getEyeY(), p.getZ()))) return false;   // espera cielo abierto
        if (watcherActive()) return false;
        FieldProfile fp = FieldProfile.ALL.get(id);
        if (fp == null || !SpawnHelper.spawnGround(p, id, fp.dist)) return false;
        d.remove(key);
        return true;
    }

    @SubscribeEvent
    public void onTick(TickEvent.PlayerTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END || !(ev.player instanceof ServerPlayer p) || p.tickCount % 40 != 0 || !p.isAlive()) return;
        if (!tryRun(p, K1, "stalker_watcher")) tryRun(p, K2, "stalker_watcher2");
    }
}
