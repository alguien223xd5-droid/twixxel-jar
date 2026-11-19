package com.twixxel.stalker.event;

import com.twixxel.stalker.Config;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.MobEffectEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

/** Opciones del objeto de configuracion del addon: "Quitar movimiento" y "Quitar oscuridad de mods". */
public final class Opciones {
    public static boolean calm() {
        return Config.CALM.get();
    }

    /** Con "quitar oscuridad" activado el efecto de oscuridad nunca entra (y si entra por otro lado, se quita al tiro). */
    @SubscribeEvent
    public void onEffect(MobEffectEvent.Applicable ev) {
        if (Config.NO_DARK.get() && ev.getEffectInstance().getEffect() == MobEffects.DARKNESS && ev.getEntity() instanceof Player)
            ev.setResult(Event.Result.DENY);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || server.getTickCount() % 4 != 0 || !Config.NO_DARK.get()) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) if (p.hasEffect(MobEffects.DARKNESS)) p.removeEffect(MobEffects.DARKNESS);
    }
}
