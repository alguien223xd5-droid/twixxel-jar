package com.twixxel.stalker.event;

import com.twixxel.stalker.entity.StalkerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cualquier stalker que se esta moviendo (persiguiendote) abre la trampilla (alcantarilla) cuando pasa por ella o justo
 * encima / debajo, y la vuelve a cerrar cuando ya se alejo. Solo cierra las que abrio el. (v3.1.13 del addon)
 * Complementa a ChaseNav, que abre las del camino; esto cubre a los que se mueven por su cuenta.
 */
public final class Trampillas {
    private static final double NEAR = 1.25, CLOSE_DIST = 2.6;
    private static final int CLOSE_WAIT = 16, ACTIVE = 30;

    private static final class Mover {
        ServerLevel l;
        double x, y, z;
    }

    private static final Map<Integer, double[]> last = new HashMap<>();   // id entidad -> {x, y, z, act}
    private static final Map<String, Object[]> open = new HashMap<>();    // "dim|x,y,z" -> {ServerLevel, BlockPos, away}

    @SubscribeEvent
    public void onStopped(ServerStoppedEvent ev) {
        last.clear();
        open.clear();
    }

    private static boolean isIron(BlockState b) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b.getBlock()).getPath().contains("iron");
    }

    private static boolean set(ServerLevel l, BlockPos p, boolean on) {
        BlockState b = l.getBlockState(p);
        if (!(b.getBlock() instanceof TrapDoorBlock) || b.getValue(BlockStateProperties.OPEN) == on) return false;
        l.setBlock(p, b.setValue(BlockStateProperties.OPEN, on), 3);
        boolean iron = isIron(b);
        l.playSound(null, p, on ? (iron ? SoundEvents.IRON_TRAPDOOR_OPEN : SoundEvents.WOODEN_TRAPDOOR_OPEN)
                : (iron ? SoundEvents.IRON_TRAPDOOR_CLOSE : SoundEvents.WOODEN_TRAPDOOR_CLOSE), SoundSource.BLOCKS, 1f, .9f);
        return true;
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || server.getTickCount() % 2 != 0) return;
        if (StalkerEntity.ACTIVE.isEmpty() && open.isEmpty()) {
            last.clear();
            return;
        }
        List<Mover> movers = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (StalkerEntity e : new ArrayList<>(StalkerEntity.ACTIVE)) {
            if (!e.isAlive() || !e.getDefId().contains("stalker") || !(e.level() instanceof ServerLevel sl)) continue;
            int id = e.getId();
            seen.add(id);
            double[] prev = last.get(id);
            double act = prev == null ? 0 : prev[3];
            if (prev != null && Math.sqrt((e.getX() - prev[0]) * (e.getX() - prev[0]) + (e.getY() - prev[1]) * (e.getY() - prev[1]) + (e.getZ() - prev[2]) * (e.getZ() - prev[2])) > .08) act = ACTIVE;
            else if (act > 0) act -= 2;
            last.put(id, new double[]{e.getX(), e.getY(), e.getZ(), act});
            if (act > 0) {
                Mover m = new Mover();
                m.l = sl;
                m.x = e.getX();
                m.y = e.getY();
                m.z = e.getZ();
                movers.add(m);
            }
        }
        last.keySet().retainAll(seen);
        // abre las trampillas por donde pasa
        for (Mover m : movers) {
            int bx = (int) Math.floor(m.x), by = (int) Math.floor(m.y), bz = (int) Math.floor(m.z);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 2; dy++) {
                int x = bx + dx, y = by + dy, z = bz + dz;
                if (Math.hypot(x + .5 - m.x, z + .5 - m.z) > NEAR) continue;
                BlockPos p = new BlockPos(x, y, z);
                if (!m.l.hasChunkAt(p) || !(m.l.getBlockState(p).getBlock() instanceof TrapDoorBlock) || Alcantarilla.reserved(m.l, p)) continue;
                String key = m.l.dimension().location() + "|" + x + "," + y + "," + z;
                Object[] o = open.get(key);
                if (o != null) {
                    o[2] = 0;
                    continue;
                }
                if (set(m.l, p, true)) open.put(key, new Object[]{m.l, p, 0});
            }
        }
        // y las cierra cuando ya se alejo
        for (Map.Entry<String, Object[]> en : new ArrayList<>(open.entrySet())) {
            Object[] o = en.getValue();
            ServerLevel l = (ServerLevel) o[0];
            BlockPos p = (BlockPos) o[1];
            boolean near = false;
            for (Mover m : movers) {
                if (m.l == l && Math.sqrt((m.x - (p.getX() + .5)) * (m.x - (p.getX() + .5)) + (m.y - p.getY()) * (m.y - p.getY()) + (m.z - (p.getZ() + .5)) * (m.z - (p.getZ() + .5))) <= CLOSE_DIST) {
                    near = true;
                    break;
                }
            }
            if (near) {
                o[2] = 0;
                continue;
            }
            o[2] = (int) o[2] + 2;
            if ((int) o[2] < CLOSE_WAIT) continue;
            open.remove(en.getKey());
            if (!Alcantarilla.reserved(l, p) && l.hasChunkAt(p)) set(l, p, false);
        }
    }
}
