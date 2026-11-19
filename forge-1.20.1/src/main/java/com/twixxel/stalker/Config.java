package com.twixxel.stalker;

import net.minecraftforge.common.ForgeConfigSpec;

public final class Config {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue NATURAL_SPAWNS;
    public static final ForgeConfigSpec.IntValue BIPEDAL_MIN_MINUTES;
    public static final ForgeConfigSpec.IntValue BIPEDAL_MAX_MINUTES;
    public static final ForgeConfigSpec.IntValue EVIL_MIN_MINUTES;
    public static final ForgeConfigSpec.IntValue EVIL_MAX_MINUTES;
    public static final ForgeConfigSpec.DoubleValue CHASE_SPEED;
    public static final ForgeConfigSpec.BooleanValue CALM;
    public static final ForgeConfigSpec.BooleanValue NO_DARK;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("spawns");
        NATURAL_SPAWNS = b.comment("Aparicion automatica de los stalkers (stalker normal y evils).")
                .define("naturalSpawns", true);
        BIPEDAL_MIN_MINUTES = b.comment("Minutos minimos entre apariciones del stalker normal.")
                .defineInRange("bipedalMinMinutes", 15, 1, 600);
        BIPEDAL_MAX_MINUTES = b.defineInRange("bipedalMaxMinutes", 30, 1, 600);
        EVIL_MIN_MINUTES = b.comment("Minutos entre persecuciones (alternan goatevil y evil).")
                .defineInRange("evilMinMinutes", 23, 1, 600);
        EVIL_MAX_MINUTES = b.defineInRange("evilMaxMinutes", 30, 1, 600);
        b.pop();
        CHASE_SPEED = b.comment("Velocidad de persecucion en bloques por segundo (5.6 = jugador corriendo, 7 = corriendo con salto).")
                .defineInRange("chaseSpeedBlocksPerSecond", 7.0, 1.0, 30.0);
        b.push("options");
        CALM = b.comment("Quitar movimiento: sin temblores de camara, camara suave en los jumpscares y animaciones que no tiemblan. (/twxcalma yes|no)")
                .define("calma", false);
        NO_DARK = b.comment("Quitar oscuridad de mods: ningun stalker te pone el efecto de oscuridad. (/twxoscuridad yes|no)")
                .define("sinOscuridad", false);
        b.pop();
        SPEC = b.build();
    }

    private Config() {
    }
}
