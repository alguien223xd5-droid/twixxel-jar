package com.twixxel.stalker.event;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * Idioma del mod (espanol / ingles), guardado por mundo como en el addon original.
 * Al entrar por primera vez al mundo sale "Language / Idioma" con dos botones; tambien se puede cambiar con /twxlang es|en.
 */
public class Lang {
    private static volatile boolean es = true;
    private static volatile boolean chosen = false;

    public static boolean es() {
        return es;
    }

    public static boolean chosen() {
        return chosen;
    }

    public static final class LangData extends SavedData {
        String code = "";

        static LangData load(CompoundTag t) {
            LangData d = new LangData();
            d.code = t.getString("code");
            return d;
        }

        @Override
        public CompoundTag save(CompoundTag t) {
            t.putString("code", code);
            return t;
        }
    }

    private static LangData data(MinecraftServer s) {
        return s.overworld().getDataStorage().computeIfAbsent(LangData::load, LangData::new, "twixxel_lang");
    }

    private static void refresh(MinecraftServer s) {
        String c = data(s).code;
        chosen = c.equals("es") || c.equals("en");
        es = !c.equals("en");
    }

    private static void set(MinecraftServer s, String code) {
        LangData d = data(s);
        d.code = code;
        d.setDirty();
        refresh(s);
        for (ServerPlayer p : s.getPlayerList().getPlayers()) Net.syncLang(p);
    }

    @SubscribeEvent
    public void onStarted(ServerStartedEvent ev) {
        refresh(ev.getServer());
    }

    @SubscribeEvent
    public void onStopped(ServerStoppedEvent ev) {
        es = true;
        chosen = false;
    }

    private static MutableComponent button(String label, String cmd, ChatFormatting col) {
        return Component.literal("[" + label + "] ").withStyle(st -> st.withColor(col).withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd)));
    }

    private static void ask(ServerPlayer p) {
        Net.ask(p, "lang");
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent ev) {
        if (ev.getEntity() instanceof ServerPlayer p && p.getServer() != null) {
            refresh(p.getServer());
            Net.syncLang(p);
            if (!chosen) ask(p);
        }
    }

    /** Se vuelve a preguntar cada 15 s hasta que alguien elija (como el addon). */
    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END || chosen) return;
        MinecraftServer s = ServerLifecycleHooks.getCurrentServer();
        if (s == null || s.getTickCount() % 300 != 0) return;
        if (!s.getPlayerList().getPlayers().isEmpty()) ask(s.getPlayerList().getPlayers().get(0));
    }

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent ev) {
        ev.getDispatcher().register(Commands.literal("twxlang")
                .then(Commands.argument("code", StringArgumentType.word())
                        .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(new String[]{"es", "en"}, b))
                        .executes(c -> {
                            String code = StringArgumentType.getString(c, "code");
                            if (!code.equals("es") && !code.equals("en")) {
                                c.getSource().sendFailure(Component.literal("es | en"));
                                return 0;
                            }
                            set(c.getSource().getServer(), code);
                            c.getSource().sendSuccess(() -> Component.literal(code.equals("en") ? "§aLanguage: English" : "§aIdioma: Español"), true);
                            return 1;
                        })));
    }
}
