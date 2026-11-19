package com.twixxel.stalker.client;

import com.twixxel.stalker.TwixxelMod;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.function.Supplier;

/**
 * Celular (addon v3.0.98 / v3.1.2): al usarlo sale un menu "que video quieres ver"; al elegir sale el celular al
 * medio de la pantalla con ese video y su audio (en bucle). Usarlo otra vez lo apaga y vuelve a salir el menu.
 * Se apaga solo si lo sueltas o mueres. Los cuadros son imagenes sueltas (textures/gui/cel/<letra><0000>.png).
 */
public final class CelularClient {
    private CelularClient() {
    }

    private record Video(String name, String key, int frames, int cols, int atlasW, int atlasH, String sound) {
    }

    private static final Video[] VIDEOS = {
            new Video("Vulnerable", "a", 94, 53, 4028, 144, "stalker.celular_video"),
            new Video("Stalker", "b", 272, 53, 4028, 432, "stalker.celular_video2"),
            new Video("Endless", "c", 133, 53, 4028, 216, "stalker.celular_video3"),
            new Video("Gato", "d", 143, 53, 4028, 216, "stalker.celular_video4"),
            new Video("Gameplay", "e", 2283, 53, 4028, 3168, "stalker.celular_video5"),
            new Video("Video 6", "f", 343, 53, 4028, 504, "stalker.celular_video6"),
    };
    private static final int FW = 76, FH = 72;
    private static final ResourceLocation FRAME = new ResourceLocation("twixxel", "textures/gui/cel_frame.png");

    private static int playing = -1;
    private static long t0;
    private static long lastToggle = -9999;
    private static SimpleSoundInstance sound;

    private static boolean es() {
        return ClientLang.es();
    }

    // ---------- control ----------
    public static void toggle() {
        long now = System.currentTimeMillis();
        if (now - lastToggle < 400) return;                // un solo toque
        lastToggle = now;
        Minecraft mc = Minecraft.getInstance();
        if (playing >= 0) {
            stop();
            mc.setScreen(new Menu());                      // se apaga y vuelve a preguntar
        } else if (!(mc.screen instanceof Menu)) {
            mc.setScreen(new Menu());
        }
    }

    private static void start(int v) {
        playing = v;
        t0 = System.currentTimeMillis();
        playSound();
    }

    private static void playSound() {
        stopSound();
        Supplier<SoundEvent> ev = TwixxelMod.SOUND_EVENTS.get(VIDEOS[playing].sound());
        if (ev == null) return;
        sound = SimpleSoundInstance.forUI(ev.get(), 1.0f, 1.0f);
        Minecraft.getInstance().getSoundManager().play(sound);
    }

    private static void stopSound() {
        if (sound != null) Minecraft.getInstance().getSoundManager().stop(sound);
        sound = null;
    }

    public static void stop() {
        playing = -1;
        stopSound();
    }

    // ---------- menu ----------
    private static final class Menu extends Screen {
        Menu() {
            super(Component.literal(es() ? "Celular" : "Phone"));
        }

        @Override
        protected void init() {
            int y = height / 2 - 70;
            for (int i = 0; i < VIDEOS.length; i++) {
                final int k = i;
                addRenderableWidget(Button.builder(Component.literal("Video " + (i + 1) + ": " + VIDEOS[i].name()),
                        b -> {
                            onClose();
                            start(k);
                            if (k == 0) com.twixxel.stalker.event.Net.CH.sendToServer(new com.twixxel.stalker.event.Net.CelVideo(0));
                        }).bounds(width / 2 - 80, y + i * 24, 160, 20).build());
            }
        }

        @Override
        public void render(GuiGraphics g, int mx, int my, float pt) {
            renderBackground(g);
            g.drawCenteredString(font, title, width / 2, height / 2 - 100, 0xFFFFFF);
            g.drawCenteredString(font, es() ? "¿Qué video quieres ver?" : "What video do you want to watch?", width / 2, height / 2 - 86, 0xCCCCCC);
            super.render(g, mx, my, pt);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }

    // ---------- pantalla del celular ----------
    private static void render(GuiGraphics g, int w, int h) {
        if (playing < 0) return;
        Video v = VIDEOS[playing];
        double ft = (System.currentTimeMillis() - t0) / 100.0;         // 10 cuadros por segundo (reloj real)
        int f = (int) ft;
        if (f >= v.frames()) {                                         // termino: vuelve a empezar
            t0 = System.currentTimeMillis();
            f = 0;
            ft = 0;
            playSound();
        }
        float frac = (float) (ft - f);
        int nf = f + 1 < v.frames() ? f + 1 : f;
        // el celular se escala con la pantalla (antes era fijo y chico)
        float s = Math.max(1f, Math.min(3f, h * .62f / 184f));
        var pose = g.pose();
        pose.pushPose();
        pose.translate(w / 2f, h / 2f, 0);
        pose.scale(s, s, 1);
        g.blit(FRAME, -50, -92, 100, 184, 0, 0, 200, 368, 200, 368);
        RenderSystem.enableBlend();
        // cuadro actual + fundido hacia el siguiente: 10 fps se ven fluidos como video; se recorta 1 px para que no se cuele el cuadro vecino
        blitFrame(g, v, f, 1f);
        if (nf != f && frac > .02f) blitFrame(g, v, nf, frac);
        if (f != lastF) {                                              // suelta de la GPU los cuadros ya vistos (son 3268 imagenes)
            var tm = Minecraft.getInstance().getTextureManager();
            for (int k = Math.max(0, f - 6); k < f - 2; k++) tm.release(frameLoc(v, k));
            lastF = f;
        }
        g.setColor(1f, 1f, 1f, 1f);
        pose.popPose();
    }

    private static int lastF = -1;

    private static ResourceLocation frameLoc(Video v, int f) {
        return new ResourceLocation("twixxel", String.format("textures/gui/cel/%s%04d.png", v.key(), f));
    }

    private static void blitFrame(GuiGraphics g, Video v, int f, float a) {
        ResourceLocation loc = frameLoc(v, f);
        Minecraft.getInstance().getTextureManager().getTexture(loc).setFilter(true, false);     // suavizado (como un video)
        g.setColor(1f, 1f, 1f, a);
        g.blit(loc, -45, -43, 90, 85, 0, 0, FW, FH, FW, FH);
    }

    // ---------- eventos ----------

    @Mod.EventBusSubscriber(modid = "twixxel", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class ForgeEvents {
        @SubscribeEvent
        public static void gui(RenderGuiEvent.Post ev) {
            render(ev.getGuiGraphics(), ev.getWindow().getGuiScaledWidth(), ev.getWindow().getGuiScaledHeight());
        }

        @SubscribeEvent
        public static void tick(TickEvent.ClientTickEvent ev) {
            if (ev.phase != TickEvent.Phase.END || playing < 0) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || !mc.player.isAlive()
                    || !(mc.player.getMainHandItem().getItem() instanceof com.twixxel.stalker.item.CelularItem)) stop();   // lo soltaste o moriste
        }
    }
}
