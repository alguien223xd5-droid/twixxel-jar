package com.twixxel.stalker.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ComputeFovModifierEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Zoom del palo: FOV 20 en lugar de ~70, con transicion suave de 0.15 s. Se quita al entrar al mundo. */
@Mod.EventBusSubscriber(modid = "twixxel", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ZoomClient {
    private static boolean on = false;
    private static float cur = 1f;
    private static long lastToggle = -99;

    private ZoomClient() {
    }

    public static void toggle() {
        long now = System.currentTimeMillis();
        if (now - lastToggle < 250) return;      // evita el doble clic en el mismo instante
        lastToggle = now;
        on = !on;
    }

    @SubscribeEvent
    public static void fov(ComputeFovModifierEvent ev) {
        float target = on ? 20f / 70f : 1f;
        cur += (target - cur) * 0.35f;
        ev.setNewFovModifier(ev.getNewFovModifier() * cur);
    }

    @SubscribeEvent
    public static void login(ClientPlayerNetworkEvent.LoggingIn ev) {
        on = false;
        cur = 1f;
    }
}
