package com.twixxel.stalker.event;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Cadena puerta -> RUN: tareas diferidas y "vigilancia" para saber si saliste afuera. */
public final class Director {
    private static final int WATCH_TICKS = 1200;    // 1 min para salir tras irse el stalker de la puerta
    private static final int EXIT_TICKS = 10;       // 0.5 s a cielo abierto = saliste

    private record Task(long at, UUID player) {
    }

    private static final List<Task> TASKS = new ArrayList<>();
    private static final Map<UUID, Long> WATCH = new HashMap<>();
    private static final Map<UUID, Integer> OUT = new HashMap<>();
    private static long now = 0;

    private Director() {
    }

    public static void clear() {
        TASKS.clear();
        WATCH.clear();
        OUT.clear();
    }

    /** RUN aparece detras del jugador dentro de "delay" ticks. */
    public static void scheduleRun(ServerPlayer p, int delay) {
        TASKS.add(new Task(now + delay, p.getUUID()));
    }

    /** Si el jugador sale a cielo abierto en el proximo minuto, RUN aparece 3 s despues. */
    public static void startRunWatch(ServerPlayer p) {
        WATCH.put(p.getUUID(), now + WATCH_TICKS);
        OUT.put(p.getUUID(), 0);
    }

    public static void tick(MinecraftServer server) {
        now = server.getTickCount();
        if (TASKS.isEmpty() && WATCH.isEmpty()) return;

        for (Iterator<Task> it = TASKS.iterator(); it.hasNext(); ) {
            Task t = it.next();
            if (now < t.at()) continue;
            it.remove();
            ServerPlayer p = server.getPlayerList().getPlayer(t.player());
            if (p != null && p.isAlive()) SpawnHelper.spawnBehind(p, "stalker_run", new int[]{9, 8, 7, 6, 5, 4});
        }
        for (Iterator<Map.Entry<UUID, Long>> it = WATCH.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Long> e = it.next();
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null || !p.isAlive() || now > e.getValue()) {
                it.remove();
                OUT.remove(e.getKey());
                continue;
            }
            boolean sky = p.level().canSeeSky(p.blockPosition());
            int n = sky ? OUT.merge(e.getKey(), 1, Integer::sum) : 0;
            OUT.put(e.getKey(), n);
            if (n >= EXIT_TICKS) {
                it.remove();
                OUT.remove(e.getKey());
                scheduleRun(p, 60);
            }
        }
    }
}
