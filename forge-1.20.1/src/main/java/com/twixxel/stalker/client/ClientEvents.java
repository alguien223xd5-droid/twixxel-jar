package com.twixxel.stalker.client;

import com.twixxel.stalker.TwixxelMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = TwixxelMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientEvents {
    private ClientEvents() {
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        TwixxelMod.TYPES.values().forEach(t -> event.registerEntityRenderer(t.get(), StalkerRenderer::new));
    }
}
