package com.twixxel.stalker.client;

import net.minecraft.client.Minecraft;

/** Idioma del mod en el cliente: el que se eligio en el mundo (sincronizado por el servidor). Hasta recibirlo, el de Minecraft. */
public final class ClientLang {
    private static volatile int state = -1;     // -1 sin recibir, 0 ingles, 1 espanol

    private ClientLang() {
    }

    public static void set(boolean es) {
        state = es ? 1 : 0;
        ModLang.apply();
    }

    /** -1 si el servidor aun no mando el idioma elegido, 0 ingles, 1 espanol. */
    public static int chosen() {
        return state;
    }

    public static void reset() {
        state = -1;
        ModLang.apply();
    }

    public static boolean es() {
        if (state >= 0) return state == 1;
        return Minecraft.getInstance().options.languageCode.startsWith("es");
    }
}
