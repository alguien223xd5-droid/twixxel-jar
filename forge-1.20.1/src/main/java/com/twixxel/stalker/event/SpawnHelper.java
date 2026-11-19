package com.twixxel.stalker.event;

import com.twixxel.stalker.TwixxelMod;
import com.twixxel.stalker.entity.StalkerEntity;
import com.twixxel.stalker.entity.ScareProfile;
import net.minecraft.core.BlockPos;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Aparicion de los stalkers (igual que el script original, simplificado). */
public final class SpawnHelper {
    private static final int[] DISTANCES = {12, 10, 8, 6};
    private static final int[] DY = {0, 1, -1, 2, -2, 3, -3, 4, -4, -5};

    private SpawnHelper() {
    }

    /** Stalker normal: aparece delante del jugador sobre pasto, tierra o arena. */
    public static boolean spawnBipedal(ServerPlayer player) {
        return spawnGround(player, "stalker_bipedal", DISTANCES);
    }

    /** Aparece delante del jugador a una de las distancias dadas, sobre pasto/tierra/arena. */
    public static boolean spawnGround(ServerPlayer player, String id, int[] distances) {
        var ro = TwixxelMod.TYPES.get(id);
        if (ro == null) return false;
        ServerLevel level = player.serverLevel();
        Vec3 look = player.getLookAngle();
        double len = Math.hypot(look.x, look.z);
        if (len < 1e-4) return false;
        for (int dist : distances) {
            double x = player.getX() + look.x / len * dist;
            double z = player.getZ() + look.z / len * dist;
            Integer y = groundY(level, x, z, player.getY());
            if (y == null) continue;
            EntityType<StalkerEntity> type = ro.get();
            StalkerEntity e = type.create(level);
            if (e == null) return false;
            e.moveTo(x, y, z, 0f, 0f);
            e.setYRot((float) (Math.atan2(player.getZ() - z, player.getX() - x) * 180 / Math.PI) - 90f);
            level.addFreshEntity(e);
            return true;
        }
        return false;
    }

    /** Evento de persecucion: id = stalker_evil | stalker_goatevil | stalker_quad_red. */
    public static boolean spawnChase(ServerPlayer player, String id, boolean natural) {
        var ro = TwixxelMod.TYPES.get(id);
        if (ro == null) return false;
        ServerLevel level = player.serverLevel();
        StalkerEntity e = ro.get().create(level);
        if (e == null) return false;
        e.moveTo(player.getX(), player.getY(), player.getZ(), player.getYRot() + 180f, 0f);
        if (natural) e.startNaturalEvent(player);
        level.addFreshEntity(e);
        return true;
    }

    /** Aparece detras del jugador (RUN). */
    public static boolean spawnBehind(ServerPlayer player, String id, int[] distances) {
        var ro = TwixxelMod.TYPES.get(id);
        if (ro == null) return false;
        ServerLevel level = player.serverLevel();
        Vec3 look = player.getLookAngle();
        double len = Math.hypot(look.x, look.z);
        if (len < 1e-4) len = 1;
        for (int dist : distances) {
            double x = player.getX() - look.x / len * dist;
            double z = player.getZ() - look.z / len * dist;
            for (int dy : new int[]{0, 1, -1, 2, -2}) {
                BlockPos pos = BlockPos.containing(x, player.getY() + dy, z);
                if (passable(level, pos) && passable(level, pos.above())
                        && !passable(level, pos.below())) {
                    StalkerEntity e = ro.get().create(level);
                    if (e == null) return false;
                    e.moveTo(x, pos.getY(), z, 0f, 0f);
                    e.setYRot((float) (Math.atan2(player.getZ() - z, player.getX() - x) * 180 / Math.PI) - 90f);
                    level.addFreshEntity(e);
                    return true;
                }
            }
        }
        return false;
    }

    /** Stalker de la puerta: busca una puerta cerrada cerca y aparece al otro lado de ella. */
    public static boolean spawnDoor(ServerPlayer player) {
        var ro = TwixxelMod.TYPES.get("stalker_door");
        if (ro == null) return false;
        ServerLevel level = player.serverLevel();
        if (level.canSeeSky(player.blockPosition())) return false;   // tienes que estar dentro de un lugar
        Vec3 view = player.getLookAngle();
        double rx = -view.z, rz = view.x;                           // "derecha" del jugador
        BlockPos base = player.blockPosition();
        BlockPos best = null;
        double bestDist = 1e9;
        for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) for (int y = -4; y <= 5; y++) {
            BlockPos pos = base.offset(x, y, z);
            BlockState st = level.getBlockState(pos);
            if (!(st.getBlock() instanceof DoorBlock)) continue;
            if (st.getValue(DoorBlock.OPEN) || st.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) continue;
            double dx = pos.getX() + .5 - player.getX(), dz = pos.getZ() + .5 - player.getZ();
            if (dx * rx + dz * rz <= 0.25) continue;                 // solo puertas a la derecha
            double d = Math.hypot(dx, dz);
            if (d > 17 || d >= bestDist) continue;
            best = pos;
            bestDist = d;
        }
        if (best == null) return false;
        BlockState st = level.getBlockState(best);
        Direction.Axis axis = st.getValue(DoorBlock.FACING).getAxis();    // eje perpendicular a la hoja
        int[][] dirs = axis == Direction.Axis.Z ? new int[][]{{0, 1}, {0, -1}} : new int[][]{{1, 0}, {-1, 0}};
        for (int[] d : dirs) {
            double front = (player.getX() - (best.getX() + .5)) * d[0] + (player.getZ() - (best.getZ() + .5)) * d[1];
            if (front >= 0) continue;                                // debe estar al otro lado que el jugador
            BlockPos t = best.offset(d[0], 0, d[1]);
            if (!passable(level, t) || !passable(level, t.above()) || passable(level, t.below())) continue;
            StalkerEntity e = ro.get().create(level);
            if (e == null) return false;
            e.moveTo(t.getX() + .5, t.getY(), t.getZ() + .5, 0f, 0f);
            e.setYRot((float) (Math.atan2(-d[1], -d[0]) * 180 / Math.PI) - 90f);   // mira hacia la puerta
            e.setVictim(player);
            level.addFreshEntity(e);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ ventana / mujer / arbol
    private static boolean isGlass(ServerLevel level, BlockPos pos) {
        var key = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(level.getBlockState(pos).getBlock());
        return key != null && key.getPath().contains("glass");
    }

    public static boolean spawnScare(ServerPlayer player, String id) {
        return spawnScare(player, id, false);
    }

    public static boolean spawnScare(ServerPlayer player, String id, boolean full) {
        ScareProfile sc = ScareProfile.ALL.get(id);
        var ro = TwixxelMod.TYPES.get(id);
        if (sc == null || ro == null) return false;
        ServerLevel level = player.serverLevel();
        hvFullFlag = full;
        if (sc.kind == ScareProfile.Kind.TUNNEL) return spawnCueva(player, level, ro.get());
        return sc.kind == ScareProfile.Kind.TREE ? spawnTree(player, level, ro.get())
                : spawnWindow(player, level, ro.get(), sc);
    }

    private static boolean spawnWindow(ServerPlayer player, ServerLevel level,
                                       EntityType<StalkerEntity> type, ScareProfile sc) {
        BlockPos base = player.blockPosition();
        double bestD = 1e9;
        List<BlockPos> bestBlocks = null;
        double bx = 0, by = 0, bz = 0;
        int bAxis = 0;
        for (int[] size : sc.sizes) {
            int w = size[0], h = size[1];
            for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) for (int y = -4; y <= 5; y++) {
                BlockPos o = base.offset(x, y, z);
                if (!isGlass(level, o)) continue;
                for (int axis = 0; axis < 2; axis++) {          // 0: ventana a lo largo de X, 1: a lo largo de Z
                    int ax = axis == 0 ? 1 : 0, az = axis == 0 ? 0 : 1;
                    // o debe ser la esquina inferior "izquierda": el bloque anterior no es cristal
                    if (isGlass(level, o.offset(-ax, 0, -az)) || isGlass(level, o.below())) continue;
                    boolean ok = true;
                    List<BlockPos> blocks = new ArrayList<>();
                    for (int i = 0; i < w && ok; i++) for (int j = 0; j < h && ok; j++) {
                        BlockPos q = o.offset(ax * i, j, az * i);
                        if (!isGlass(level, q)) ok = false;
                        else blocks.add(q);
                    }
                    if (!ok) continue;
                    for (int i = -1; i <= w && ok; i++) {        // el marco no debe ser cristal (tamano exacto)
                        if (isGlass(level, o.offset(ax * i, -1, az * i)) || isGlass(level, o.offset(ax * i, h, az * i))) ok = false;
                    }
                    for (int j = 0; j < h && ok; j++) {
                        if (isGlass(level, o.offset(-ax, j, -az)) || isGlass(level, o.offset(ax * w, j, az * w))) ok = false;
                    }
                    if (!ok) continue;
                    double cx = o.getX() + .5 + ax * (w - 1) / 2.0, cz = o.getZ() + .5 + az * (w - 1) / 2.0;
                    double d = Math.hypot(cx - player.getX(), cz - player.getZ());
                    if (d > 12 || d >= bestD) continue;
                    bestD = d;
                    bestBlocks = blocks;
                    bx = cx;
                    by = o.getY();
                    bz = cz;
                    bAxis = axis;
                }
            }
        }
        if (bestBlocks == null) return false;
        // normal de la ventana: el stalker va al lado opuesto al jugador, a 0.9 bloques del cristal
        double nx = bAxis == 0 ? 0 : 1, nz = bAxis == 0 ? 1 : 0;
        double front = (player.getX() - bx) * nx + (player.getZ() - bz) * nz;
        if (front > 0) {
            nx = -nx;
            nz = -nz;
        }
        double sx = bx + nx * sc.out, sz = bz + nz * sc.out;
        BlockPos sp = BlockPos.containing(sx, by, sz);
        if (!passable(level, sp) || !passable(level, sp.above())) return false;
        StalkerEntity e = type.create(level);
        if (e == null) return false;
        e.moveTo(sx, by, sz, 0f, 0f);
        e.setYRot((float) (Math.atan2(-nz, -nx) * 180 / Math.PI) - 90f);
        e.setYHeadRot(e.getYRot());
        e.setYBodyRot(e.getYRot());
        e.setVictim(player);
        e.setGlass(bestBlocks);
        e.setWindow(bx, by, bz, nx, nz);
        e.setHvFull(hvFullFlag);
        if (sc.crackId != null) {                                   // grietas / sangre en la cara de adentro del cristal
            var cro = TwixxelMod.TYPES.get(sc.crackId);
            StalkerEntity c = cro == null ? null : cro.get().create(level);
            if (c != null) {
                var key = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(level.getBlockState(bestBlocks.get(0)).getBlock());
                boolean thin = key != null && (key.getPath().contains("pane") || key.getPath().contains("bars"));
                double k = thin ? 0.12 : 0.53;
                c.moveTo(bx - nx * k, by, bz - nz * k, (float) (Math.atan2(nx, -nz) * 180 / Math.PI), 0f);
                level.addFreshEntity(c);
                e.setCrack(c);
            }
        }
        level.addFreshEntity(e);
        return true;
    }

    /** Entidad que maneja otra escena (la cabra que se come al RUN): sin IA propia ni gravedad. */
    public static StalkerEntity createScripted(ServerLevel level, String id, Vec3 pos, float yaw) {
        var ro = TwixxelMod.TYPES.get(id);
        if (ro == null) return null;
        StalkerEntity e = ro.get().create(level);
        if (e == null) return null;
        e.setScripted(true);
        e.setNoGravity(true);
        e.noPhysics = true;
        e.moveTo(pos.x, pos.y, pos.z, yaw, 0f);
        e.setYHeadRot(yaw);
        e.setYBodyRot(yaw);
        level.addFreshEntity(e);
        return e;
    }

    /** Un stalker aparece en una posicion (dentro de la ventana rota) y empieza ya a perseguir al jugador. */
    public static boolean spawnChaseAt(ServerPlayer player, String id, Vec3 at) {
        return spawnChaseAt(player, id, at, false);
    }

    public static boolean spawnChaseAt(ServerPlayer player, String id, Vec3 at, boolean withPre) {
        return spawnChaseAtE(player, id, at, withPre) != null;
    }

    public static StalkerEntity spawnChaseAtE(ServerPlayer player, String id, Vec3 at, boolean withPre) {
        var ro = TwixxelMod.TYPES.get(id);
        if (ro == null) return null;
        ServerLevel level = player.serverLevel();
        StalkerEntity e = ro.get().create(level);
        if (e == null) return null;
        BlockPos bp = BlockPos.containing(at);
        boolean ok = passable(level, bp) && passable(level, bp.above());
        e.moveTo(ok ? at.x : player.getX(), ok ? at.y : player.getY(), ok ? at.z : player.getZ(), player.getYRot() + 180f, 0f);
        level.addFreshEntity(e);
        if (withPre) e.forceStart(player);
        else e.forceChase(player, !ok);
        return e;
    }

    private static boolean spawnTree(ServerPlayer player, ServerLevel level, EntityType<StalkerEntity> type) {
        BlockPos base = player.blockPosition();
        BlockPos best = null;
        double bestD = 1e9;
        for (int x = -10; x <= 10; x++) for (int z = -10; z <= 10; z++) for (int y = -3; y <= 4; y++) {
            BlockPos p = base.offset(x, y, z);
            BlockState st = level.getBlockState(p);
            if (!st.is(net.minecraft.tags.BlockTags.LOGS)) continue;
            if (!level.getBlockState(p.below()).is(BlockTags.DIRT)) continue;       // base sobre suelo natural
            int h = 0;
            while (level.getBlockState(p.above(h)).is(net.minecraft.tags.BlockTags.LOGS)) h++;
            if (h < 3) continue;
            boolean leaves = false;
            for (int dx = -2; dx <= 2 && !leaves; dx++) for (int dz = -2; dz <= 2 && !leaves; dz++) for (int dy = 0; dy <= 3; dy++) {
                if (level.getBlockState(p.above(h - 1).offset(dx, dy, dz)).is(net.minecraft.tags.BlockTags.LEAVES)) {
                    leaves = true;
                    break;
                }
            }
            if (!leaves) continue;
            double d = Math.hypot(p.getX() + .5 - player.getX(), p.getZ() + .5 - player.getZ());
            if (d < 1 || d > 10 || d >= bestD) continue;
            best = p;
            bestD = d;
        }
        if (best == null) return false;
        double dx = best.getX() + .5 - player.getX(), dz = best.getZ() + .5 - player.getZ();
        double len = Math.hypot(dx, dz);
        double sx = best.getX() + .5 + dx / len * 0.8, sz = best.getZ() + .5 + dz / len * 0.8;   // detras del tronco
        BlockPos sp = BlockPos.containing(sx, best.getY(), sz);
        if (!passable(level, sp) || !passable(level, sp.above())) return false;
        StalkerEntity e = type.create(level);
        if (e == null) return false;
        e.moveTo(sx, best.getY(), sz, 0f, 0f);
        e.setYRot((float) (Math.atan2(-dz, -dx) * 180 / Math.PI) - 90f);
        e.setYHeadRot(e.getYRot());
        e.setYBodyRot(e.getYRot());
        Vec3 look = player.getLookAngle();
        e.setProp("lado", (look.x * dz - look.z * dx) > 0 ? 1 : 0);
        e.setVictim(player);
        level.addFreshEntity(e);
        return true;
    }

    // ------------------------------------------------------------------ los que se asoman / no mires arriba
    private static boolean hvFullFlag = false;

    private static final String[] MADE = {"planks", "brick", "concrete", "wool", "cobblestone", "polished", "smooth_",
            "cut_", "chiseled", "terracotta", "sandstone", "quartz", "prismarine", "purpur", "blackstone"};

    private static boolean isLog(BlockState st) {
        return st.is(BlockTags.LOGS);
    }

    /** Bloque "puesto por el jugador" (no natural): tablas, ladrillos, cemento, lana, adoquin... */
    private static boolean isMade(BlockState st) {
        if (!st.isSolidRender(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) return false;
        var key = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(st.getBlock());
        if (key == null) return false;
        String n = key.getPath();
        for (String m : MADE) if (n.contains(m)) return true;
        return false;
    }

    /** Bloque que tapa: el peeker solo usa bloques puestos por jugadores; los demas, cualquier bloque solido. */
    private static boolean cover(ServerLevel level, com.twixxel.stalker.entity.PeekProfile pk, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        if (!st.isSolidRender(level, pos) || st.is(BlockTags.LEAVES)) return false;
        return !pk.wallOnly || PlacedBlocks.get(level).contains(level, pos);
    }

    private static boolean isTreeTrunk(ServerLevel level, BlockPos pos) {
        if (!isLog(level.getBlockState(pos))) return false;
        if (!level.getBlockState(pos.below()).is(BlockTags.DIRT) && !isLog(level.getBlockState(pos.below()))) return false;
        int top = 0;
        while (top < 20 && isLog(level.getBlockState(pos.above(top + 1)))) top++;
        if (top < 2) return false;                                  // tronco de 3+ de alto
        BlockPos crown = pos.above(top + 1);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = -2; dy <= 1; dy++) {
            if (level.getBlockState(crown.offset(dx, dy, dz)).is(BlockTags.LEAVES)) return true;
        }
        return false;
    }

    private static boolean hidden(ServerLevel level, ServerPlayer p, Vec3 pt) {
        var hit = level.clip(new net.minecraft.world.level.ClipContext(p.getEyePosition(), pt,
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, p));
        return hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS;
    }

    /** Busca un arbol / pared y aparece escondido detras (visto desde el jugador). */
    public static boolean spawnPeek(ServerPlayer player, String id) {
        var pk = com.twixxel.stalker.entity.PeekProfile.ALL.get(id);
        var ro = TwixxelMod.TYPES.get(id);
        if (pk == null || ro == null) return false;
        ServerLevel level = player.serverLevel();
        BlockPos base = player.blockPosition();
        Vec3 look = player.getLookAngle();
        double ll = Math.hypot(look.x, look.z);
        if (ll < 1e-4) ll = 1;
        List<double[]> good = new ArrayList<>(), any = new ArrayList<>();
        int r = (int) pk.range;
        java.util.Random rnd = new java.util.Random();
        for (int i = 0; i < 1400; i++) {                            // muestreo aleatorio (sin lag)
            int dx = rnd.nextInt(2 * r + 1) - r, dz = rnd.nextInt(2 * r + 1) - r, dy = rnd.nextInt(9) - 4;
            double d = Math.hypot(dx, dz);
            if (d < pk.minDist || d > pk.range) continue;
            BlockPos b = base.offset(dx, dy, dz);
            boolean tree = isTreeTrunk(level, b);
            if (pk.treeOnly && !tree) continue;
            if (!tree && !cover(level, pk, b)) continue;
            double fx = (b.getX() + 0.5 - player.getX()) / d, fz = (b.getZ() + 0.5 - player.getZ()) / d;
            double rx = -fz, rz = fx;
            double wallH = tree ? 3 : 0;
            if (!tree) {
                BlockPos s1 = b.offset((int) Math.round(rx), 0, (int) Math.round(rz));
                BlockPos s2 = b.offset((int) -Math.round(rx), 0, (int) -Math.round(rz));
                boolean c1 = cover(level, pk, s1), c2 = cover(level, pk, s2);
                int h = 1;
                while (h < 6 && cover(level, pk, b.above(h))) h++;
                if (c1 && c2 && h >= 2 && h <= (pk.wallOnly ? 4 : 5)) wallH = h;      // pared de 3 de ancho
                boolean edge = !c1 || !c2;                                           // pilar o esquina
                if (pk.wallOnly ? wallH == 0 : (wallH == 0 && !edge)) continue;
                if (!passable(level, b.above(h))) continue;                          // la parte de arriba debe estar libre
            }
            double sx = b.getX() + 0.5 + fx * pk.behind, sz = b.getZ() + 0.5 + fz * pk.behind;
            int gy = Integer.MIN_VALUE;
            for (int y = b.getY() - 2; y <= b.getY() + 2; y++) {
                BlockPos c = BlockPos.containing(sx, y, sz);
                if (passable(level, c) && passable(level, c.above()) && !passable(level, c.below())) {
                    gy = y;
                    break;
                }
            }
            if (gy == Integer.MIN_VALUE) continue;
            if (!tree && gy != b.getY()) continue;                  // el bloque es la base de la pared
            if (tree && Math.abs(gy - b.getY()) > 1) continue;
            Vec3 chest = new Vec3(sx, gy + 1.2, sz), foot = new Vec3(sx, gy + 0.4, sz);
            if (!hidden(level, player, chest) || !hidden(level, player, foot)) continue;
            double[] c = {sx, gy, sz, fx, fz, wallH, tree ? 1 : 0};
            any.add(c);
            if (look.x * fx / 1 + look.z * fz >= 0.3 * ll) good.add(c);
        }
        List<double[]> pool = good.isEmpty() ? any : good;
        if (pool.isEmpty()) return false;
        double[] c = pool.get(rnd.nextInt(pool.size()));
        StalkerEntity e = ro.get().create(level);
        if (e == null) return false;
        e.moveTo(c[0], c[1], c[2], 0f, 0f);
        e.setVictim(player);
        e.setPeek(c[0], c[1], c[2], c[3], c[4], c[5], c[6] > 0);
        level.addFreshEntity(e);
        return true;
    }

    /** "No mires arriba": aparece detras del jugador, inclinado sobre el. */
    public static boolean spawnArriba(ServerPlayer player) {
        var ro = TwixxelMod.TYPES.get("stalker_arriba");
        if (ro == null) return false;
        ServerLevel level = player.serverLevel();
        StalkerEntity e = ro.get().create(level);
        if (e == null) return false;
        Vec3 look = player.getLookAngle();
        double hl = Math.hypot(look.x, look.z);
        double hx = hl < 1e-3 ? 0 : look.x / hl, hz = hl < 1e-3 ? 1 : look.z / hl;
        e.moveTo(player.getX() - hx * 1.7, player.getY(), player.getZ() - hz * 1.7, player.getYRot(), 0f);
        e.setVictim(player);
        level.addFreshEntity(e);
        return true;
    }

    // ------------------------------------------------------------------ tanda 9: cueva, hole, agujero
    private static boolean standable(ServerLevel l, BlockPos c) {
        return passable(l, c) && passable(l, c.above()) && !passable(l, c.below());
    }

    private static boolean sees(ServerLevel l, ServerPlayer p, Vec3 pt) {
        var hit = l.clip(new net.minecraft.world.level.ClipContext(p.getEyePosition(), pt,
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, p));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS;
    }

    private static int freeAbove(ServerLevel l, BlockPos c) {
        int h = 0;
        while (h < 6 && passable(l, c.above(h))) h++;
        return h;
    }

    /** Stalker de la cueva: dentro de un tunel, aparece tras una esquina que se ve. */
    private static boolean spawnCueva(ServerPlayer player, ServerLevel level, EntityType<StalkerEntity> type) {
        BlockPos start = player.blockPosition();
        if (level.canSeeSky(start) || freeAbove(level, start) > 4) return false;     // hay que estar en un tunel
        java.util.Map<BlockPos, BlockPos> parent = new java.util.HashMap<>();
        java.util.ArrayDeque<BlockPos> q = new java.util.ArrayDeque<>();
        parent.put(start, null);
        q.add(start);
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        List<BlockPos> cands = new ArrayList<>();
        while (!q.isEmpty() && parent.size() < 900) {
            BlockPos c = q.poll();
            for (int[] d : dirs) for (int dy = -1; dy <= 1; dy++) {
                BlockPos n = c.offset(d[0], dy, d[1]);
                if (parent.containsKey(n) || !standable(level, n)) continue;
                if (Math.hypot(n.getX() - start.getX(), n.getZ() - start.getZ()) > 16) continue;
                parent.put(n, c);
                q.add(n);
                double dist = Math.hypot(n.getX() + .5 - player.getX(), n.getZ() + .5 - player.getZ());
                if (dist < 4.5 || dist > 15) continue;
                if (sees(level, player, Vec3.atCenterOf(n).add(0, 0.4, 0))) continue;   // el stalker queda oculto
                BlockPos pr = parent.get(n), pr2 = pr == null ? null : parent.get(pr);
                boolean corner = (pr != null && sees(level, player, Vec3.atCenterOf(pr).add(0, 0.4, 0)))
                        || (pr2 != null && sees(level, player, Vec3.atCenterOf(pr2).add(0, 0.4, 0)));
                if (corner) cands.add(n);
            }
        }
        if (cands.isEmpty()) return false;
        BlockPos c = cands.get(new java.util.Random().nextInt(cands.size()));
        StalkerEntity e = type.create(level);
        if (e == null) return false;
        e.moveTo(c.getX() + .5, c.getY(), c.getZ() + .5, 0f, 0f);
        e.setVictim(player);
        e.setProp("lado", new java.util.Random().nextInt(2));
        e.setProp("alto", freeAbove(level, c) >= 3 ? 1 : 0);
        level.addFreshEntity(e);
        return true;
    }

    /** La cabeza en el pozo: se asoma por el borde de un agujero (a 3+ bloques de profundidad). */
    public static boolean spawnHole(ServerPlayer player) {
        var ro = TwixxelMod.TYPES.get("stalker_hole");
        if (ro == null) return false;
        ServerLevel level = player.serverLevel();
        BlockPos base = player.blockPosition();
        List<double[]> pool = new ArrayList<>();
        for (int x = -8; x <= 8; x++) for (int z = -8; z <= 8; z++) for (int y = -5; y <= 2; y++) {
            BlockPos m = base.offset(x, y, z);
            double d = Math.hypot(x, z);
            if (d < 3.5 || d > 8) continue;
            if (!passable(level, m) || !passable(level, m.below()) || !passable(level, m.below(2)) || !passable(level, m.below(3))) continue;
            // el borde: un vecino con suelo a la altura del brocal y libre encima
            boolean rim = false;
            int walls = 0;
            for (int[] dd : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                BlockPos n = m.offset(dd[0], 0, dd[1]);
                if (passable(level, n) && passable(level, n.above()) && !passable(level, n.below())) rim = true;
                if (!passable(level, n.below())) walls++;
            }
            if (!rim || walls < 2) continue;
            if (!passable(level, m.above()) || !passable(level, m.above(2))) continue;
            double ax = m.getX() + .5 - player.getX(), az = m.getZ() + .5 - player.getZ();
            double al = Math.hypot(ax, az);
            double ex = m.getX() + .5 + ax / al * 0.3, ez = m.getZ() + .5 + az / al * 0.3;   // la pared del lado lejano
            double ey = m.getY() - 0.45;
            if (!sees(level, player, new Vec3(ex, ey + 0.8, ez))) continue;
            pool.add(new double[]{ex, ey, ez});
        }
        if (pool.isEmpty()) return false;
        double[] c = pool.get(new java.util.Random().nextInt(pool.size()));
        StalkerEntity e = ro.get().create(level);
        if (e == null) return false;
        e.moveTo(c[0], c[1], c[2], 0f, 0f);
        e.setVictim(player);
        level.addFreshEntity(e);
        return true;
    }

    /** Stalker del agujero 2x2: espera invisible dentro del tunel y solo se muestra si lo miras. */
    public static boolean spawnAgujero(ServerPlayer player) {
        var ro = TwixxelMod.TYPES.get("stalker_agujero");
        if (ro == null) return false;
        ServerLevel level = player.serverLevel();
        BlockPos base = player.blockPosition();
        List<double[]> pool = new ArrayList<>();
        for (int x = -14; x <= 14; x++) for (int z = -14; z <= 14; z++) for (int y = -3; y <= 3; y++) {
            BlockPos b = base.offset(x, y, z);
            double d = Math.hypot(x, z);
            if (d < 1.5 || d > 14) continue;
            for (int axis = 0; axis < 2; axis++) {
                int ax = axis == 0 ? 1 : 0, az = axis == 0 ? 0 : 1;      // ancho
                int nx = az, nz = ax;                                     // profundidad del tunel
                boolean ok = true;
                for (int k = 0; k < 3 && ok; k++) {
                    for (int w = 0; w < 2 && ok; w++) for (int h = 0; h < 2 && ok; h++) {
                        if (!passable(level, b.offset(ax * w + nx * k, h, az * w + nz * k))) ok = false;
                    }
                    for (int h = 0; h < 2 && ok; h++) {
                        if (passable(level, b.offset(-ax + nx * k, h, -az + nz * k)) || passable(level, b.offset(ax * 2 + nx * k, h, az * 2 + nz * k))) ok = false;
                    }
                    if (ok && (passable(level, b.offset(nx * k, -1, nz * k)) || passable(level, b.offset(ax + nx * k, -1, az + nz * k))
                            || passable(level, b.offset(nx * k, 2, nz * k)) || passable(level, b.offset(ax + nx * k, 2, az + nz * k)))) ok = false;
                }
                if (!ok) continue;
                // el tunel debe alejarse del jugador (si no, se encuentra desde el otro extremo)
                double toward = (player.getX() - (b.getX() + .5 + ax * .5)) * nx + (player.getZ() - (b.getZ() + .5 + az * .5)) * nz;
                if (toward > 0) continue;
                int side = new java.util.Random().nextBoolean() ? 0 : 1;
                double px = b.getX() + .5 + ax * side + nx * 2;
                double pz = b.getZ() + .5 + az * side + nz * 2;
                if (!sees(level, player, new Vec3(px, b.getY() + 1.4, pz))) continue;
                pool.add(new double[]{px, b.getY(), pz, ax, az, nx, nz});
            }
        }
        if (pool.isEmpty()) return false;
        double[] c = pool.get(new java.util.Random().nextInt(pool.size()));
        StalkerEntity e = ro.get().create(level);
        if (e == null) return false;
        e.moveTo(c[0], c[1], c[2], 0f, 0f);
        e.setVictim(player);
        e.setPeek(c[0], c[1], c[2], c[5], c[6], 2, false);
        level.addFreshEntity(e);
        return true;
    }

    public static boolean isSpecial(String id) {
        return id.equals("stalker_arriba") || id.equals("stalker_vida") || id.equals("stalker_door2")
                || id.equals("stalker_hole") || id.equals("stalker_agujero")
                || com.twixxel.stalker.entity.PeekProfile.ALL.containsKey(id);
    }

    public static boolean spawnSpecial(ServerPlayer player, String id) {
        switch (id) {
            case "stalker_arriba":
                return spawnArriba(player);
            case "stalker_vida":
                return spawnGround(player, id, new int[]{18, 16, 14, 12, 10});
            case "stalker_door2":
                return spawnDoor2(player);
            case "stalker_hole":
                return spawnHole(player);
            case "stalker_agujero":
                return spawnAgujero(player);
            default:
                return spawnPeek(player, id);
        }
    }

    /** Stalker de la puerta 2: aparece tras una puerta cerrada (a 3+ bloques), la abre y mete la cabeza. */
    public static boolean spawnDoor2(ServerPlayer player) {
        var ro = TwixxelMod.TYPES.get("stalker_door2");
        if (ro == null) return false;
        ServerLevel level = player.serverLevel();
        if (level.canSeeSky(player.blockPosition())) return false;
        BlockPos base = player.blockPosition();
        BlockPos best = null;
        double bestDist = 1e9;
        for (int x = -16; x <= 16; x++) for (int z = -16; z <= 16; z++) for (int y = -4; y <= 5; y++) {
            BlockPos pos = base.offset(x, y, z);
            BlockState st = level.getBlockState(pos);
            if (!(st.getBlock() instanceof DoorBlock)) continue;
            if (st.getValue(DoorBlock.OPEN) || st.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) continue;
            double d = Math.hypot(pos.getX() + .5 - player.getX(), pos.getZ() + .5 - player.getZ());
            if (d < 3 || d > 16 || d >= bestDist) continue;
            best = pos;
            bestDist = d;
        }
        if (best == null) return false;
        BlockState st = level.getBlockState(best);
        Direction.Axis axis = st.getValue(DoorBlock.FACING).getAxis();
        int[][] dirs = axis == Direction.Axis.Z ? new int[][]{{0, 1}, {0, -1}} : new int[][]{{1, 0}, {-1, 0}};
        for (int[] d : dirs) {                                       // n apunta hacia el lado del jugador
            double toward = (player.getX() - (best.getX() + .5)) * d[0] + (player.getZ() - (best.getZ() + .5)) * d[1];
            if (toward <= 0) continue;
            BlockPos out = BlockPos.containing(best.getX() + .5 - d[0] * 1.1, best.getY(), best.getZ() + .5 - d[1] * 1.1);
            if (!passable(level, out) || !passable(level, out.above())) continue;
            StalkerEntity e = ro.get().create(level);
            if (e == null) return false;
            e.setDoor2(best, d[0], d[1]);
            e.moveTo(best.getX() + .5 - d[0] * 1.1, best.getY(), best.getZ() + .5 - d[1] * 1.1, 0f, 0f);
            e.setVictim(player);
            level.addFreshEntity(e);
            return true;
        }
        return false;
    }

    /** Aparece a "dist" bloques en un angulo al azar, de pie sobre tierra/pasto/arena (luz roja). */
    public static boolean spawnAround(ServerPlayer player, String id, int dist) {
        var ro = TwixxelMod.TYPES.get(id);
        if (ro == null) return false;
        ServerLevel level = player.serverLevel();
        for (int i = 0; i < 60; i++) {
            double a = level.random.nextDouble() * Math.PI * 2;
            double x = Math.floor(player.getX() + Math.cos(a) * dist) + .5, z = Math.floor(player.getZ() + Math.sin(a) * dist) + .5;
            Integer y = groundY(level, x, z, player.getY());
            if (y == null) continue;
            StalkerEntity e = ro.get().create(level);
            if (e == null) return false;
            e.moveTo(x, y, z, 0f, 0f);
            e.setYRot((float) (Math.atan2(player.getZ() - z, player.getX() - x) * 180 / Math.PI) - 90f);
            level.addFreshEntity(e);
            return true;
        }
        return false;
    }

    /** Hay un bloque que da luz a "r" bloques de esta posicion? */
    public static boolean nearLight(ServerLevel level, BlockPos c, int r) {
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) for (int dy = -1; dy <= 2; dy++) {
            BlockPos q = c.offset(dx, dy, dz);
            if (level.getBlockState(q).getLightEmission(level, q) >= 2) return true;
        }
        return false;
    }

    /** Estas en una mina? bajo tierra, sin cielo arriba y lejos de la luz. */
    public static boolean inMine(ServerPlayer p) {
        ServerLevel l = p.serverLevel();
        if (l.dimension() != net.minecraft.world.level.Level.OVERWORLD || p.getY() > 62) return false;
        BlockPos b = p.blockPosition();
        if (l.canSeeSky(b.above())) return false;
        int solid = 0;
        for (int dy = 2; dy <= 12; dy++) if (!l.getBlockState(b.above(dy)).isAir()) solid++;
        if (solid < 3) return false;
        return !nearLight(l, b, 2);
    }

    public static Integer groundY(ServerLevel level, double x, double z, double baseY) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z), by = (int) Math.floor(baseY);
        for (int dy : DY) {
            int y = by + dy;
            BlockPos below = new BlockPos(bx, y - 1, bz);
            BlockState bs = level.getBlockState(below);
            if (!(bs.is(BlockTags.DIRT) || bs.is(BlockTags.SAND))) continue;
            if (!passable(level, new BlockPos(bx, y, bz)) || !passable(level, new BlockPos(bx, y + 1, bz))) continue;
            return y;
        }
        return null;
    }

    private static boolean passable(ServerLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return s.getCollisionShape(level, pos).isEmpty() && s.getFluidState().isEmpty();
    }
}
