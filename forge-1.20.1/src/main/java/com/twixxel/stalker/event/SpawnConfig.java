package com.twixxel.stalker.event;

import java.util.Random;

public final class SpawnConfig {
    private SpawnConfig() {}

    /** Ticks hasta la proxima aparicion: los minutos configurados en "Configurar stalkers", o lo normal (entre min y max). */
    public static long ticks(String id, int min, int max, Random r) {
        int cfg = StalkerConfig.minutes(id);
        if (cfg > 0) return cfg * 60L * 20L;
        if (max <= min) return min;
        return min + (long) (r.nextDouble() * (max - min));
    }
}
