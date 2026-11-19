package com.twixxel.stalker.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Random;

/** Sacudida de camara (camerashake del Bedrock): ruido en yaw/pitch/roll que se apaga solo. */
@Mod.EventBusSubscriber(modid = "twixxel", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShakeClient {
    private static float amp;
    private static int left;
    private static final Random R = new Random();

    private ShakeClient() {
    }

    public static void add(float a, int ticks) {
        amp = Math.max(left > 0 ? amp : 0, a);
        left = Math.max(left, ticks);
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent ev) {
        if (ev.phase == TickEvent.Phase.END && left > 0 && !Minecraft.getInstance().isPaused()) left--;
    }

    @SubscribeEvent
    public static void angles(ViewportEvent.ComputeCameraAngles ev) {
        if (left <= 0) return;
        float k = amp * Math.min(1f, left / 10f) * 4f;
        ev.setYaw(ev.getYaw() + (R.nextFloat() - .5f) * k);
        ev.setPitch(ev.getPitch() + (R.nextFloat() - .5f) * k);
        ev.setRoll(ev.getRoll() + (R.nextFloat() - .5f) * k);
    }
}
