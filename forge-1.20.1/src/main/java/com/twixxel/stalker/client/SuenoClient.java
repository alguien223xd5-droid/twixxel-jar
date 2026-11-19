package com.twixxel.stalker.client;

import com.twixxel.stalker.event.SuenoEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Niebla clara del sueño (fog_start/fog_end del addon original: sueño 1 = 40..170, sueño 2 = 20..110). */
@Mod.EventBusSubscriber(modid = "twixxel", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SuenoClient {
    private SuenoClient() {
    }

    private static boolean inDream() {
        var mc = Minecraft.getInstance();
        return mc.level != null && mc.level.dimension() == SuenoEvent.DIM;
    }

    @SubscribeEvent
    public static void fog(ViewportEvent.RenderFog ev) {
        if (!inDream() || ev.getMode() != FogRenderer.FogMode.FOG_TERRAIN) return;
        var mc = Minecraft.getInstance();
        double x = mc.player == null ? 0 : mc.player.getX();
        if (x > 7500) {                       // sueño 4 (pueblo): niebla muy cerrada
            ev.setNearPlaneDistance(6f);
            ev.setFarPlaneDistance(38f);
        } else if (x > 4500) {                // sueño 3: de 55 % a 100 % de la distancia de render
            float far = ev.getFarPlaneDistance();
            ev.setNearPlaneDistance(far * 0.55f);
            ev.setFarPlaneDistance(far);
        } else {
            boolean two = x > 1500;
            ev.setNearPlaneDistance(two ? 20f : 40f);
            ev.setFarPlaneDistance(two ? 110f : 170f);
        }
        ev.setCanceled(true);
    }

    @SubscribeEvent
    public static void color(ViewportEvent.ComputeFogColor ev) {
        if (!inDream()) return;
        ev.setRed(0xc9 / 255f);
        ev.setGreen(0xd6 / 255f);
        ev.setBlue(0xe6 / 255f);
    }
}
