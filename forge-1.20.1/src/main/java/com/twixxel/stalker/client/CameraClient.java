package com.twixxel.stalker.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Mientras la camara esta en un soporte (esconderse bajo la cama, escenas con camara fija):
 *  - no se dibuja tu avatar ni tu mano;
 *  - los clics (atacar / usar / pick block) se ignoran. Si no, el rayo parte dentro de tu propio cuerpo,
 *    el cliente "ataca" a tu jugador y el servidor te expulsa con "Attempting to attack an invalid entity".
 */
@Mod.EventBusSubscriber(modid = "twixxel", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CameraClient {
    private CameraClient() {
    }

    private static boolean detached() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.getCameraEntity() != null && mc.getCameraEntity() != mc.player;
    }

    @SubscribeEvent
    public static void click(InputEvent.InteractionKeyMappingTriggered ev) {
        if (!detached()) return;
        ev.setCanceled(true);
        ev.setSwingHand(false);
    }

    @SubscribeEvent
    public static void avatar(RenderPlayerEvent.Pre ev) {
        if (detached() && ev.getEntity() == Minecraft.getInstance().player) ev.setCanceled(true);
    }

    @SubscribeEvent
    public static void hand(RenderHandEvent ev) {
        if (detached()) ev.setCanceled(true);
    }
}
