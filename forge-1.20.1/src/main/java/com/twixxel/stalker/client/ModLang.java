package com.twixxel.stalker.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

/**
 * Los nombres de items, entidades y bloques del mod siguen el idioma elegido en el mundo (/twxlang), no el de Minecraft:
 * se pone encima del idioma actual un overlay con las claves de twixxel (assets/twixxel/lang/es_es.json o en_us.json).
 */
public final class ModLang {
    private ModLang() {
    }

    private static Language base;

    private static final class Overlay extends Language {
        final Language b;
        final Map<String, String> over;

        Overlay(Language b, Map<String, String> over) {
            this.b = b;
            this.over = over;
        }

        public String getOrDefault(String key) {
            String v = over.get(key);
            return v != null ? v : b.getOrDefault(key);
        }

        public String getOrDefault(String key, String def) {
            String v = over.get(key);
            return v != null ? v : b.getOrDefault(key, def);
        }

        public boolean has(String key) {
            return over.containsKey(key) || b.has(key);
        }

        public boolean isDefaultRightToLeft() {
            return b.isDefaultRightToLeft();
        }

        public FormattedCharSequence getVisualOrder(FormattedText t) {
            return b.getVisualOrder(t);
        }
    }

    public static void apply() {
        try {
            Language cur = Language.getInstance();
            if (cur instanceof Overlay o) cur = o.b;
            base = cur;
            int st = ClientLang.chosen();
            if (st < 0) {
                Language.inject(base);
                return;
            }
            String file = st == 1 ? "es_es" : "en_us";
            Map<String, String> m = new HashMap<>();
            var res = Minecraft.getInstance().getResourceManager().getResource(new ResourceLocation("twixxel", "lang/" + file + ".json"));
            if (res.isPresent()) {
                try (var in = new InputStreamReader(res.get().open(), "UTF-8")) {
                    JsonObject j = new Gson().fromJson(in, JsonObject.class);
                    for (var e : j.entrySet()) m.put(e.getKey(), e.getValue().getAsString());
                }
            }
            Language.inject(new Overlay(base, m));
        } catch (Exception ex) {
            // sin overlay: queda el idioma de Minecraft
        }
    }

    @Mod.EventBusSubscriber(modid = "twixxel", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModEvents {
        @SubscribeEvent
        public static void reload(RegisterClientReloadListenersEvent ev) {
            ev.registerReloadListener((ResourceManagerReloadListener) rm -> apply());
        }
    }

    @Mod.EventBusSubscriber(modid = "twixxel", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class ForgeEvents {
        @SubscribeEvent
        public static void out(ClientPlayerNetworkEvent.LoggingOut ev) {
            ClientLang.reset();
        }
    }
}
