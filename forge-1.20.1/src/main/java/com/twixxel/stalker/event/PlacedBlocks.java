package com.twixxel.stalker.event;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Recuerda los bloques puestos por jugadores (el peeker solo se esconde detras de ellos). Se guarda en el mundo. */
public final class PlacedBlocks extends SavedData {
    private static final int MAX = 30000;           // los mas viejos se olvidan
    private final Map<String, LinkedHashSet<Long>> map = new HashMap<>();

    public static PlacedBlocks get(ServerLevel any) {
        ServerLevel overworld = any.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(PlacedBlocks::load, PlacedBlocks::new, "twixxel_placed");
    }

    public static PlacedBlocks load(CompoundTag tag) {
        PlacedBlocks p = new PlacedBlocks();
        for (String k : tag.getAllKeys()) {
            LinkedHashSet<Long> set = new LinkedHashSet<>();
            for (long l : tag.getLongArray(k)) set.add(l);
            p.map.put(k, set);
        }
        return p;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        for (Map.Entry<String, LinkedHashSet<Long>> e : map.entrySet()) {
            tag.putLongArray(e.getKey(), e.getValue().stream().mapToLong(Long::longValue).toArray());
        }
        return tag;
    }

    private static String dim(ServerLevel l) {
        return l.dimension().location().toString().replace(':', '_');
    }

    public void add(ServerLevel l, BlockPos pos) {
        Set<Long> set = map.computeIfAbsent(dim(l), k -> new LinkedHashSet<>());
        long key = pos.asLong();
        set.remove(key);
        set.add(key);
        while (set.size() > MAX) {
            Iterator<Long> it = set.iterator();
            it.next();
            it.remove();
        }
        setDirty();
    }

    public void remove(ServerLevel l, BlockPos pos) {
        Set<Long> set = map.get(dim(l));
        if (set != null && set.remove(pos.asLong())) setDirty();
    }

    public boolean contains(ServerLevel l, BlockPos pos) {
        Set<Long> set = map.get(dim(l));
        return set != null && set.contains(pos.asLong());
    }
}
