package com.twixxel.stalker.event;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * "Configurar stalkers": cada cuantos minutos aparece cada stalker (1 a 100; 0 = tiempo normal). Se guarda por mundo.
 * El item se da una sola vez a cada jugador al entrar.
 */
public final class StalkerConfig {
    private static final Map<String, Integer> MIN = new HashMap<>();
    private static final Set<UUID> GIVEN = new HashSet<>();
    /** Sube cada vez que cambia algo: el reloj natural se reinicia con los tiempos nuevos. */
    public static volatile int version = 0;

    public static final class Data extends SavedData {
        static Data load(CompoundTag t) {
            Data d = new Data();
            MIN.clear();
            CompoundTag m = t.getCompound("min");
            for (String k : m.getAllKeys()) MIN.put(k, m.getInt(k));
            GIVEN.clear();
            ListTag g = t.getList("given", 8);
            for (int i = 0; i < g.size(); i++) {
                try {
                    GIVEN.add(UUID.fromString(g.getString(i)));
                } catch (IllegalArgumentException ignored) {
                }
            }
            return d;
        }

        @Override
        public CompoundTag save(CompoundTag t) {
            CompoundTag m = new CompoundTag();
            for (Map.Entry<String, Integer> e : MIN.entrySet()) if (e.getValue() > 0) m.putInt(e.getKey(), e.getValue());
            t.put("min", m);
            ListTag g = new ListTag();
            for (UUID u : GIVEN) g.add(StringTag.valueOf(u.toString()));
            t.put("given", g);
            return t;
        }
    }

    private static Data data(MinecraftServer s) {
        return s.overworld().getDataStorage().computeIfAbsent(Data::load, Data::new, "twixxel_cfg");
    }

    /** Minutos configurados para un stalker (id de entidad o clave corta); 0 = normal. */
    public static int minutes(String id) {
        if (id == null) return 0;
        String k = id.startsWith("stalker_") ? id.substring(8) : id;
        Integer v = MIN.get(k);
        return v == null ? 0 : Math.max(0, Math.min(CfgList.MAX_MIN, v));
    }

    public static int[] values() {
        int[] v = new int[CfgList.ALL.length];
        for (int i = 0; i < v.length; i++) v[i] = minutes(CfgList.ALL[i][0]);
        return v;
    }

    public static void set(MinecraftServer s, String key, int minutes) {
        MIN.put(key, Math.max(0, Math.min(CfgList.MAX_MIN, minutes)));
        data(s).setDirty();
        version++;
    }

    @SubscribeEvent
    public void onStarted(ServerStartedEvent ev) {
        MIN.clear();
        GIVEN.clear();
        data(ev.getServer());
        version++;
    }

    @SubscribeEvent
    public void onStopped(ServerStoppedEvent ev) {
        MIN.clear();
        GIVEN.clear();
    }

    private static final Map<UUID, Integer> PENDING = new HashMap<>();

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent ev) {
        if (ev.getEntity() instanceof ServerPlayer p && !GIVEN.contains(p.getUUID())) PENDING.put(p.getUUID(), 40);   // 2 s despues de entrar
    }

    @SubscribeEvent
    public void onTick(net.minecraftforge.event.TickEvent.ServerTickEvent ev) {
        if (ev.phase != net.minecraftforge.event.TickEvent.Phase.END || PENDING.isEmpty()) return;
        MinecraftServer s = ServerLifecycleHooks.getCurrentServer();
        if (s == null) return;
        for (UUID id : new HashSet<>(PENDING.keySet())) {
            int t = PENDING.get(id) - 1;
            if (t > 0) {
                PENDING.put(id, t);
                continue;
            }
            PENDING.remove(id);
            ServerPlayer p = s.getPlayerList().getPlayer(id);
            if (p == null || GIVEN.contains(id)) continue;
            boolean has = false;
            for (ItemStack it : p.getInventory().items) if (it.getItem() == com.twixxel.stalker.TwixxelMod.CONFIG_ITEM.get()) has = true;
            GIVEN.add(id);
            data(s).setDirty();
            if (has) continue;
            ItemStack st = new ItemStack(com.twixxel.stalker.TwixxelMod.CONFIG_ITEM.get());
            if (!p.getInventory().add(st)) p.drop(st, false);
            p.sendSystemMessage(Component.literal(Lang.es()
                    ? "§aTe dieron el item §fConfigurar stalkers§a: úsalo para elegir cada cuántos minutos aparece cada stalker."
                    : "§aYou got the §fStalker settings§a item: use it to choose how many minutes each stalker takes to appear."));
        }
    }
}
