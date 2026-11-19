package com.twixxel.stalker.event;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Utilidades compartidas por los eventos de casas: puertas, ventanas de vidrio, techo, vision. */
public final class Houses {
    private static final Random RND = new Random();
    public static final int WIN_RANGE = 12, WIN_VRANGE = 4;

    /** Ventana o puerta 2x2 (o 1x1): bloques, centro, y la normal "hacia adentro" (hacia el jugador). */
    public static final class Win {
        public List<BlockPos> blocks;
        public double cx, cz;
        public int y, qx, qz;
        public boolean bottom;
        public boolean axisX;
    }

    public static boolean isGlass(ServerLevel l, BlockPos p) {
        Block b = l.getBlockState(p).getBlock();
        var k = BuiltInRegistries.BLOCK.getKey(b);
        return k != null && k.getPath().contains("glass");
    }

    public static boolean isDoor(ServerLevel l, BlockPos p) {
        return l.getBlockState(p).getBlock() instanceof DoorBlock;
    }

    public static boolean isLowerDoor(ServerLevel l, BlockPos p) {
        BlockState s = l.getBlockState(p);
        return s.getBlock() instanceof DoorBlock && s.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER;
    }

    public static boolean free(ServerLevel l, BlockPos p) {
        BlockState s = l.getBlockState(p);
        return s.getCollisionShape(l, p).isEmpty() && s.getFluidState().isEmpty();
    }

    /** Bloque opaco que tapa la vista (no vidrio, no hojas). */
    public static boolean cover(ServerLevel l, BlockPos p) {
        return l.getBlockState(p).isSolidRender(l, p);
    }

    public static boolean sight(ServerLevel l, Vec3 a, Vec3 b, net.minecraft.world.entity.Entity ctx) {
        return l.clip(new ClipContext(a, b, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, ctx)).getType() == HitResult.Type.MISS;
    }

    public static void setDoor(ServerLevel l, BlockPos lower, boolean open) {
        BlockState s = l.getBlockState(lower);
        if (!(s.getBlock() instanceof DoorBlock d) || s.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) return;
        if (s.getValue(DoorBlock.OPEN) != open) d.setOpen(null, l, s, lower, open);
    }

    /** Estas dentro de una casa? techo encima y al menos 2 paredes. */
    public static boolean isInsideHouse(ServerPlayer p) {
        ServerLevel l = p.serverLevel();
        BlockPos b = p.blockPosition();
        boolean roof = false;
        for (int[] o : new int[][]{{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            for (int dy = 2; dy <= 14 && !roof; dy++) {
                BlockPos q = b.offset(o[0], dy, o[1]);
                BlockState s = l.getBlockState(q);
                if (!s.getCollisionShape(l, q).isEmpty() && !s.is(net.minecraft.tags.BlockTags.LEAVES)) roof = true;
            }
            if (roof) break;
        }
        if (!roof) return false;
        int walls = 0;
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            boolean hit = false;
            for (int k = 1; k <= 16 && !hit; k++) for (int h = 0; h <= 3; h++) {
                BlockPos q = b.offset(d[0] * k, h, d[1] * k);
                BlockState s = l.getBlockState(q);
                if (!s.isAir() && s.getFluidState().isEmpty() && !s.is(net.minecraft.tags.BlockTags.REPLACEABLE) && !s.is(net.minecraft.tags.BlockTags.LEAVES)
                        && !s.getCollisionShape(l, q).isEmpty()) {
                    hit = true;
                    break;
                }
            }
            if (hit) walls++;
        }
        return walls >= 2;
    }

    /** Ventana de vidrio 2x2 (doors=false) o puerta doble 2x2 (doors=true) cerca del jugador; null si no hay. */
    public static Win find2x2(ServerPlayer p, boolean doors) {
        ServerLevel l = p.serverLevel();
        BlockPos pb = p.blockPosition();
        Map<BlockPos, Boolean> set = new HashMap<>();
        for (int dx = -WIN_RANGE; dx <= WIN_RANGE; dx++) for (int dz = -WIN_RANGE; dz <= WIN_RANGE; dz++) for (int dy = -WIN_VRANGE; dy <= WIN_VRANGE + 1; dy++) {
            BlockPos q = pb.offset(dx, dy, dz);
            if (doors ? isDoor(l, q) : isGlass(l, q)) set.put(q, true);
        }
        if (set.size() < 4) return null;
        Vec3 look = p.getLookAngle();
        double ll = Math.max(1e-4, Math.hypot(look.x, look.z));
        double fx = look.x / ll, fz = look.z / ll;
        List<List<Win>> buckets = new ArrayList<>();
        for (int i = 0; i < 4; i++) buckets.add(new ArrayList<>());
        for (BlockPos q : set.keySet()) {
            for (int axis = 0; axis < 2; axis++) {
                int ax = axis == 0 ? 1 : 0, az = axis == 0 ? 0 : 1;
                BlockPos b1 = q.offset(ax, 0, az), b2 = q.above(), b3 = q.offset(ax, 1, az);
                if (!set.containsKey(b1) || !set.containsKey(b2) || !set.containsKey(b3)) continue;
                double cx = axis == 0 ? q.getX() + 1 : q.getX() + .5, cz = axis == 0 ? q.getZ() + .5 : q.getZ() + 1;
                double dxp = p.getX() - cx, dzp = p.getZ() - cz;
                double along = axis == 0 ? dzp : dxp;
                if (Math.abs(along) < .3) continue;
                double hd = Math.hypot(dxp, dzp);
                if (hd > WIN_RANGE) continue;
                int out = along > 0 ? -1 : 1;
                boolean bottom = !set.containsKey(q.below()) && !set.containsKey(b1.below());
                boolean exact = !set.containsKey(q.offset(-ax, 0, -az)) && !set.containsKey(b1.offset(ax, 0, az))
                        && !set.containsKey(q.above(2)) && !set.containsKey(b1.above(2));
                boolean front = (dxp * -fx + dzp * -fz) / (hd == 0 ? 1 : hd) >= .3;
                int tier = (front ? 0 : 2) + (exact ? 0 : 1);
                Win w = new Win();
                w.blocks = List.of(q, b1, b2, b3);
                w.cx = cx;
                w.cz = cz;
                w.y = q.getY();
                w.qx = axis == 0 ? 0 : -out;
                w.qz = axis == 0 ? -out : 0;
                w.bottom = bottom;
                w.axisX = axis == 0;
                buckets.get(tier).add(w);
            }
        }
        for (List<Win> list : buckets) {
            if (list.isEmpty()) continue;
            List<Win> low = new ArrayList<>();
            for (Win w : list) if (w.bottom) low.add(w);
            List<Win> pick = low.isEmpty() ? list : low;
            return pick.get(RND.nextInt(pick.size()));
        }
        return null;
    }
}
