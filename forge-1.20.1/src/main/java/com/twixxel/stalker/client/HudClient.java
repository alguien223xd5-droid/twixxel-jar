package com.twixxel.stalker.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * HUD del addon (RP/ui/hud_screen.json):
 *  - "twxc": los textos grandes salen rojos, multiplicados (6 capas) y temblando.
 *  - "twx_foto": una imagen a pantalla completa (textures/gui/foto/NOMBRE.png), se quita con /twxfoto off.
 */
public final class HudClient {
    private HudClient() {
    }

    private static String title = "";
    private static long titleUntil, titleStart;
    private static String foto = "";
    private static final int[] JX = {-5, 5, -5, 5, -3}, JY = {4, -4, -4, 4, 2};
    private static final float[][] COL = {{.25f, 0, 0, .7f}, {.4f, 0, 0, .8f}, {.6f, 0, 0, .9f}, {.8f, 0, 0, 1}, {1, 0, 0, 1}, {1, .15f, .15f, 1}};

    private static boolean calmT;

    public static void title(String text, int ticks, boolean calm) {
        calmT = calm;
        title = text == null ? "" : text.replaceAll("\u00a7.", "");
        titleStart = System.currentTimeMillis();
        titleUntil = titleStart + ticks * 50L;
    }

    private static net.minecraft.client.CameraType prevCam;

    /** Tercera persona mientras te arrastran; al terminar vuelve la camara de antes. */
    public static void thirdPerson(boolean on) {
        var o = Minecraft.getInstance().options;
        if (on) {
            if (prevCam == null) prevCam = o.getCameraType();
            o.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
        } else if (prevCam != null) {
            o.setCameraType(prevCam);
            prevCam = null;
        }
    }

    public static void foto(String name) {
        foto = name == null || name.equals("off") ? "" : name;
    }

    private static void render(GuiGraphics g, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        if (!foto.isEmpty()) {
            int iw = 512, ih = 380;
            double k = Math.min(w / (double) iw, h / (double) ih);
            int dw = (int) (iw * k), dh = (int) (ih * k);
            g.blit(new ResourceLocation("twixxel", "textures/gui/foto/" + foto + ".png"), (w - dw) / 2, (h - dh) / 2, dw, dh, 0, 0, iw, ih, iw, ih);
        }
        if (title.isEmpty() || now > titleUntil) return;
        float fade = Math.min(1f, (titleUntil - now) / 500f);
        boolean calm = calmT;
        for (int i = 0; i < COL.length; i++) {
            int j = (int) ((now / 70 + i * 3) % 5);
            int ox = calm ? 0 : JX[j] * (i + 1) / 6, oy = calm ? 0 : JY[j] * (i + 1) / 6;
            float[] c = COL[i];
            int argb = (Math.max(4, (int) (c[3] * fade * 255)) << 24) | ((int) (c[0] * 255) << 16) | ((int) (c[1] * 255) << 8) | (int) (c[2] * 255);
            g.pose().pushPose();
            g.pose().translate(w / 2f + ox, h / 2f + oy, 0);
            float s = i >= 4 ? 3.26f : 3.2f;
            g.pose().scale(s, s, 1);
            g.drawString(mc.font, title, -mc.font.width(title) / 2, -4, argb, true);
            g.pose().popPose();
        }
    }

    @Mod.EventBusSubscriber(modid = "twixxel", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class ForgeEvents {
        @SubscribeEvent
        public static void gui(RenderGuiEvent.Post ev) {
            render(ev.getGuiGraphics(), ev.getWindow().getGuiScaledWidth(), ev.getWindow().getGuiScaledHeight());
        }

        @SubscribeEvent
        public static void out(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut ev) {
            prevCam = null;
            title = "";
            foto = "";
        }
    }

}
