package com.twixxel.stalker.event;

import com.twixxel.stalker.entity.StalkerEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Dos detalles de ambiente del addon:
 *  - Sonidos de ambiente: a cada jugador le suena uno al azar cada 1 a 30 minutos (no sonando ni en un jumpscare ni durante un sueño).
 *  - Los que te persiguen te hablan por el chat como "???:" cada 10 s; si se van, ya no te hablan.
 */
public final class Ambiente {
    private static final Object[][] SOUNDS = {
            {"stalker.amb_horror_drone", 895},     // 44.7 s
            {"stalker.amb_creepy_wind", 2145},     // 107.3 s
            {"stalker.amb_dark_ambient", 107},     // 5.4 s
            {"stalker.amb_cs_door", 421},          // 21.1 s
    };
    private static final int AMB_MIN = 1 * 60 * 20, AMB_MAX = 30 * 60 * 20;
    private static final int CHAT_EVERY = 200;
    private static final String[] CHAT_ES = {"Ven aquí, solo quiero jugar.", "Ven conmigo, solo quiero hablar.", "No te vayas.",
            "No tienes escapatoria.", "No tienes escondite.", "No tienes salida."};
    private static final String[] CHAT_EN = {"Come here, I just want to play.", "Come with me, I just want to talk.", "Don't go.",
            "There is no escape.", "There is nowhere to hide.", "There is no way out."};

    private static final Random RND = new Random();
    private static final Map<UUID, Long> ambNext = new HashMap<>();
    private static final Map<UUID, Integer> ambLast = new HashMap<>();
    private static final Map<UUID, long[]> chat = new HashMap<>();   // {ultimo tick visto, proximo mensaje, ultimo indice}

    private static long roll(long now) {
        return now + AMB_MIN + RND.nextInt(AMB_MAX - AMB_MIN);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        long now = server.getTickCount();
        if (now % 20 != 0) return;
        ambient(server, now);
        chase(server, now);
    }

    private static boolean busy(ServerPlayer p) {
        return SuenoEvent.isDreaming(p) || JumpScare.active(p.getUUID());
    }

    private static void ambient(MinecraftServer server, long now) {
        java.util.Set<UUID> alive = new java.util.HashSet<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            UUID id = p.getUUID();
            alive.add(id);
            Long nx = ambNext.get(id);
            if (nx == null) {
                ambNext.put(id, roll(now));
                continue;
            }
            if (now < nx) continue;
            if (busy(p)) {
                ambNext.put(id, now + 600);                    // lo intenta de nuevo en 30 s
                continue;
            }
            int i = RND.nextInt(SOUNDS.length);
            if (ambLast.containsKey(id) && i == ambLast.get(id)) i = (i + 1) % SOUNDS.length;
            ambLast.put(id, i);
            var se = BedHide.se((String) SOUNDS[i][0]);
            if (se != null) p.playNotifySound(se, SoundSource.MASTER, 0.8f, 1f);
            ambNext.put(id, roll(now) + (int) SOUNDS[i][1]);   // el proximo cuenta desde que este termina
        }
        ambNext.keySet().removeIf(k -> !alive.contains(k));
        ambLast.keySet().removeIf(k -> !alive.contains(k));
    }

    private static void chase(MinecraftServer server, long now) {
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        for (StalkerEntity e : new java.util.ArrayList<>(StalkerEntity.ACTIVE)) {
            ServerPlayer p = e.isAlive() ? e.chasePlayer() : null;
            if (p == null || e.hasSkin()) continue;               // el stalker del sueño no habla
            UUID id = p.getUUID();
            seen.add(id);
            long[] c = chat.get(id);
            if (c == null || now - c[0] > 40) {
                c = new long[]{now, now + CHAT_EVERY, RND.nextInt(CHAT_ES.length)};
                chat.put(id, c);
            }
            c[0] = now;
            if (now < c[1]) continue;
            c[1] = now + CHAT_EVERY;
            if (BedHide.isHidden(id) || busy(p)) continue;
            String[] L = Lang.es() ? CHAT_ES : CHAT_EN;
            int k = RND.nextInt(L.length);
            if (k == c[2]) k = (k + 1) % L.length;
            c[2] = k;
            p.sendSystemMessage(Component.literal("§4???:§c " + L[k]));
        }
        Iterator<Map.Entry<UUID, long[]>> it = chat.entrySet().iterator();
        while (it.hasNext()) if (now - it.next().getValue()[0] > 400) it.remove();
    }
}
