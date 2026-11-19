package com.twixxel.stalker.client;

import com.twixxel.stalker.event.CfgList;
import com.twixxel.stalker.event.Net;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Menu "Configurar stalkers": una barra por stalker (0 = tiempo normal, 1 a 100 minutos) y las opciones de calma y oscuridad. */
public final class ConfigScreen extends Screen {
    private static final int ROW = 24;
    private final int[] vals;
    private final boolean es;
    private boolean calm, noDark;
    private int scroll = 0;

    private ConfigScreen(int[] vals, boolean es, boolean calm, boolean noDark) {
        super(Component.literal(es ? "Configurar stalkers" : "Stalker settings"));
        this.vals = vals;
        this.es = es;
        this.calm = calm;
        this.noDark = noDark;
    }

    public static void open(int[] vals, boolean es, boolean calm, boolean noDark) {
        Minecraft.getInstance().setScreen(new ConfigScreen(vals, es, calm, noDark));
    }

    private int visibleRows() {
        return Math.max(3, (height - 96) / ROW);
    }

    private final class Bar extends AbstractSliderButton {
        private final int index;

        Bar(int x, int y, int w, int index) {
            super(x, y, w, 20, Component.empty(), vals[index] / (double) CfgList.MAX_MIN);
            this.index = index;
            updateMessage();
        }

        private int v() {
            return (int) Math.round(value * CfgList.MAX_MIN);
        }

        @Override
        protected void updateMessage() {
            int m = v();
            String name = CfgList.ALL[index][es ? 1 : 2];
            setMessage(Component.literal(name + ": " + (m == 0 ? (es ? "normal" : "default") : m + " min")));
        }

        @Override
        protected void applyValue() {
            vals[index] = v();
        }

        @Override
        public void onRelease(double mx, double my) {
            super.onRelease(mx, my);
            Net.CH.sendToServer(new Net.SetConfig(index, v()));
        }
    }

    @Override
    protected void init() {
        clearWidgets();
        int w = Math.min(300, width - 20), x = width / 2 - w / 2;
        int rows = visibleRows();
        scroll = Math.max(0, Math.min(scroll, CfgList.ALL.length - rows));
        for (int i = 0; i < rows && scroll + i < CfgList.ALL.length; i++) addRenderableWidget(new Bar(x, 28 + i * ROW, w, scroll + i));
        int by = height - 52, bw = (w - 8) / 2;
        addRenderableWidget(Button.builder(opt(es ? "Quitar movimiento" : "Remove motion", calm), b -> {
            calm = !calm;
            Net.CH.sendToServer(new Net.SetConfig(-1, calm ? 1 : 0));
            b.setMessage(opt(es ? "Quitar movimiento" : "Remove motion", calm));
        }).bounds(x, by, bw, 20).build());
        addRenderableWidget(Button.builder(opt(es ? "Quitar oscuridad de mods" : "Remove mod darkness", noDark), b -> {
            noDark = !noDark;
            Net.CH.sendToServer(new Net.SetConfig(-2, noDark ? 1 : 0));
            b.setMessage(opt(es ? "Quitar oscuridad de mods" : "Remove mod darkness", noDark));
        }).bounds(x + bw + 8, by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal(es ? "Listo" : "Done"), b -> onClose()).bounds(x, by + 24, w, 20).build());
    }

    private Component opt(String name, boolean on) {
        return Component.literal(name + ": " + (on ? (es ? "Sí" : "Yes") : "No"));
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double d) {
        int old = scroll;
        scroll -= (int) Math.signum(d) * 2;
        scroll = Math.max(0, Math.min(scroll, CfgList.ALL.length - visibleRows()));
        if (old != scroll) init();
        return true;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        g.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
        g.drawCenteredString(font, es ? "Rueda del ratón para ver más · 0 = tiempo normal" : "Mouse wheel for more · 0 = default time", width / 2, 18, 0xAAAAAA);
        super.render(g, mx, my, pt);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
