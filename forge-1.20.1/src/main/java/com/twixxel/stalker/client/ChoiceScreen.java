package com.twixxel.stalker.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Pregunta en pantalla (como los formularios del addon): idioma y activar mascota. */
public final class ChoiceScreen extends Screen {
    private final String kind;
    private final boolean es;

    private ChoiceScreen(String kind, boolean es) {
        super(Component.literal(kind.equals("lang") ? "Language / Idioma" : (es ? "Mascota" : "Pet")));
        this.kind = kind;
        this.es = es;
    }

    public static void open(String kind, boolean es) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof ChoiceScreen) return;            // ya esta abierta
        mc.setScreen(new ChoiceScreen(kind, es));
    }

    private void run(String cmd) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.connection.sendCommand(cmd);
        onClose();
    }

    @Override
    protected void init() {
        int cx = width / 2, y = height / 2 + 10;
        if (kind.equals("lang")) {
            addRenderableWidget(Button.builder(Component.literal("Español"), b -> run("twxlang es")).bounds(cx - 105, y, 100, 20).build());
            addRenderableWidget(Button.builder(Component.literal("English"), b -> run("twxlang en")).bounds(cx + 5, y, 100, 20).build());
        } else {
            addRenderableWidget(Button.builder(Component.literal(es ? "Sí" : "Yes"), b -> run("twxpet opt si")).bounds(cx - 105, y, 100, 20).build());
            addRenderableWidget(Button.builder(Component.literal("No"), b -> run("twxpet opt no")).bounds(cx + 5, y, 100, 20).build());
        }
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        g.drawCenteredString(font, title, width / 2, height / 2 - 40, 0xFFFFFF);
        String[] lines = kind.equals("lang")
                ? new String[]{"¿Quieres este mod en inglés o español?", "Do you want this mod in English or Spanish?"}
                : new String[]{es ? "¿Quieres activar a tu mascota?" : "Do you want to activate your pet?"};
        int y = height / 2 - 22;
        for (String l : lines) {
            g.drawCenteredString(font, l, width / 2, y, 0xCCCCCC);
            y += 12;
        }
        super.render(g, mx, my, pt);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
