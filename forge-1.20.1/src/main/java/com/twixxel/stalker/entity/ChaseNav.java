package com.twixxel.stalker.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.regex.Pattern;

/**
 * PATHFINDING DEL ADDON ORIGINAL (portado de main.js: findPathLow / twxAdvance / twxLowCheck):
 * A* sobre celdas "de pie" con escaleras de mano y enredaderas, TREPAR PAREDES (costo 2.6), PARKOUR (saltos de 2 a 4 bloques),
 * GATEO por huecos de 1 bloque, NADAR, trampillas (las abre al pasar, no las pisa) y bajar hasta 6 bloques.
 * Se mueve poniendo la posicion directamente cada tick (como el teleport del addon). Estado "mov": 0 normal, 1 escalando, 2 gateando.
 */
public final class ChaseNav {
    public static final double RUN_SPEED = 0.2806;       // bloques por tick = 5.6 bloques/s
    private static final int REPLAN = 12, MAX_NODES = 1400;
    private static final double CLIMB_SPEED = 0.16, JUMP_H = 1.1, WALL_COST = 2.6, LOW_MIN_SPEED = 0.06;
    private static final int RUN_JUMP_MAX = 4, LOW_HOLD = 8, CLIMB_HOLD = 6;
    private static final Pattern CLIMB_RE = Pattern.compile("(^|:)(ladder|vine|twisting_vines|twisting_vines_plant|weeping_vines|weeping_vines_plant|cave_vines|cave_vines_plant)$");
    private static final int[][] NEIGH8 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    public static final class Cell {
        public final int x, y, z;
        public final boolean jump;

        Cell(int x, int y, int z, boolean jump) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.jump = jump;
        }
    }

    private static final class Node {
        int x, y, z;
        double g, f;
        Node parent;
        boolean closed, jump;
    }

    public static final class Step {
        public double x, y, z;
        public boolean climbing, jumping;
    }

    private final StalkerEntity ent;
    private ServerLevel level;
    private final Map<Long, Byte> kinds = new HashMap<>();
    private final Map<Long, Boolean> climbs = new HashMap<>();
    private final Map<Long, Boolean> traps = new HashMap<>();
    private int cacheT = 0;
    private List<Cell> path = new ArrayList<>();
    private int idx = 0;
    private boolean partial = false;
    private int lastPlan = -999, tick = 0;
    private int jIdx = -1;
    private double jfx, jfy, jfz, jlen;
    private boolean jValid = false;
    private boolean low = false, pLow = false;
    private int lowHold = 0, climbHold = 0;
    private double pSpeed = 0, pvx = 0, pvz = 0;
    private boolean pvInit = false;
    private int trapT = -99;
    public int mov = 0;
    private float yawOffset = 0f;

    // ------------------------------------------------------------------ ROMPER PUERTAS (rbStart/rbTick del addon)
    private static final class Cfg {
        double hold, pitch;
        int brk, yo;
        int[] hitAt;
        String[] hitKind;
        float[] hitPitch;
    }

    private static Map<String, Cfg> DOOR_CFG;
    private static final int RB_LEN = 38;

    private static Map<String, Cfg> doorCfg() {
        if (DOOR_CFG != null) return DOOR_CFG;
        Map<String, Cfg> m = new HashMap<>();
        try (java.io.InputStream in = ChaseNav.class.getResourceAsStream("/data/twixxel/door_break.json")) {
            if (in != null) {
                var o = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                for (var en : o.entrySet()) {
                    var j = en.getValue().getAsJsonObject();
                    Cfg c = new Cfg();
                    c.hold = j.get("hold").getAsDouble();
                    c.pitch = j.get("pitch").getAsDouble();
                    c.brk = j.get("brk").getAsInt();
                    c.yo = j.get("yo").getAsInt();
                    var h = j.getAsJsonArray("hits");
                    c.hitAt = new int[h.size()];
                    c.hitKind = new String[h.size()];
                    c.hitPitch = new float[h.size()];
                    for (int i = 0; i < h.size(); i++) {
                        var a = h.get(i).getAsJsonArray();
                        c.hitAt[i] = a.get(0).getAsInt();
                        c.hitKind[i] = a.get(1).getAsString();
                        c.hitPitch[i] = a.get(2).getAsFloat();
                    }
                    m.put(en.getKey(), c);
                }
            }
        } catch (Exception ex) {
            com.twixxel.stalker.TwixxelMod.LOGGER.warn("door_break.json: {}", ex.toString());
        }
        DOOR_CFG = m;
        return m;
    }

    private static final String[] SND_G = {"stalker.puerta_golpe1", "stalker.puerta_golpe2", "stalker.puerta_golpe3"};

    private static final class Br {
        int dx, dy, dz;
        double cx, cz, ax, az, hx, hz;
        float yaw;
        int t;
        Cfg cfg;
    }

    private Br br = null;
    private int doorCool = 0;

    private boolean closedDoor(int x, int y, int z) {
        BlockPos p = new BlockPos(x, y, z);
        if (!level.hasChunkAt(p)) return false;
        BlockState b = level.getBlockState(p);
        return b.getBlock() instanceof DoorBlock && !b.getValue(BlockStateProperties.OPEN);
    }

    private boolean anyDoor(int x, int y, int z) {
        BlockPos p = new BlockPos(x, y, z);
        return level.hasChunkAt(p) && level.getBlockState(p).getBlock() instanceof DoorBlock;
    }

    /** rbDoorAhead: puerta cerrada adelante (mitad de abajo) o null. */
    private int[] doorAhead(double ux, double uz, double reach) {
        for (double k = -0.6; k <= reach + 0.6; k += 0.25) {
            double px = ent.getX() + ux * k, pz = ent.getZ() + uz * k;
            for (int dy = 0; dy <= 1; dy++) {
                int bx = (int) Math.floor(px), by = (int) Math.floor(ent.getY() + 0.2) + dy, bz = (int) Math.floor(pz);
                if (!closedDoor(bx, by, bz)) continue;
                int y = by;
                if (anyDoor(bx, by - 1, bz)) y = by - 1;
                return new int[]{bx, y, bz};
            }
        }
        return null;
    }

    private void sound(String id, double x, double y, double z, float vol, float pitch) {
        var se = com.twixxel.stalker.event.BedHide.se(id);
        if (se != null) level.playSound(null, x, y, z, se, SoundSource.HOSTILE, vol, pitch);
    }

    private void splinters(double cx, double cy, double cz, double ax, double az, int n, int dx, int dy, int dz) {
        BlockState st = level.getBlockState(new BlockPos(dx, dy, dz));
        if (st.isAir()) return;
        level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(net.minecraft.core.particles.ParticleTypes.BLOCK, st),
                cx - ax * 0.35, cy + 1.0, cz - az * 0.35, n, 0.25, 0.4, 0.25, 0.05);
    }

    private boolean startBreak(double ux, double uz, int[] D, Cfg cfg) {
        double ax, az;
        if (Math.abs(ux) >= Math.abs(uz)) {
            ax = Math.signum(ux) == 0 ? 1 : Math.signum(ux);
            az = 0;
        } else {
            ax = 0;
            az = Math.signum(uz) == 0 ? 1 : Math.signum(uz);
        }
        double cx = D[0] + 0.5, cz = D[2] + 0.5;
        if ((cx - ent.getX()) * ax + (cz - ent.getZ()) * az <= -0.6) return false;               // ya la paso
        if (Math.abs((cx - ent.getX()) * az - (cz - ent.getZ()) * ax) > 0.9) return false;      // no va hacia la puerta
        Br b = new Br();
        b.dx = D[0];
        b.dy = D[1];
        b.dz = D[2];
        b.cx = cx;
        b.cz = cz;
        b.ax = ax;
        b.az = az;
        b.hx = cx - ax * cfg.hold;
        b.hz = cz - az * cfg.hold;
        b.yaw = (float) (Math.atan2(-ax, az) * 180 / Math.PI + cfg.yo);
        b.cfg = cfg;
        br = b;
        if (ent.hasProp("rompe")) ent.setProp("rompe", 1);
        ent.moveTo(b.hx, ent.getY(), b.hz, b.yaw, 0);
        return true;
    }

    private void tickBreak() {
        Br b = br;
        b.t++;
        BlockPos dp = new BlockPos(b.dx, b.dy, b.dz);
        if (b.t < b.cfg.brk && !closedDoor(b.dx, b.dy, b.dz)) {          // te la abrieron / ya no esta: sigue
            endBreak(40);
            return;
        }
        ent.setNoGravity(true);
        ent.noPhysics = true;
        ent.setDeltaMovement(0, 0, 0);
        ent.moveTo(b.hx, ent.getY(), b.hz, b.yaw, 0);
        ent.setYHeadRot(b.yaw);
        ent.yBodyRot = b.yaw;
        for (int i = 0; i < b.cfg.hitAt.length; i++) {
            if (b.cfg.hitAt[i] != b.t) continue;
            String k = b.cfg.hitKind[i];
            String id = switch (k) {
                case "t" -> "stalker.puerta_toc";
                case "r" -> "stalker.puerta_rasguno";
                case "c1" -> "stalker.run_crack_1";
                case "c2" -> "stalker.run_crack_2";
                case "c3" -> "stalker.run_crack_3";
                default -> SND_G[(int) (Math.random() * SND_G.length)];
            };
            sound(id, b.cx, b.dy + 1, b.cz, 2f, b.cfg.hitPitch[i]);
            if (k.equals("g") || k.equals("t") || k.equals("r")) splinters(b.cx, b.dy, b.cz, b.ax, b.az, k.equals("g") ? 6 : 3, b.dx, b.dy, b.dz);
        }
        if (b.t == b.cfg.brk) {
            splinters(b.cx, b.dy, b.cz, b.ax, b.az, 22, b.dx, b.dy, b.dz);
            if (anyDoor(b.dx, b.dy, b.dz)) level.destroyBlock(dp, false);
            if (anyDoor(b.dx, b.dy + 1, b.dz)) level.destroyBlock(dp.above(), false);
            sound("stalker.puerta_rompe", b.cx, b.dy + 1, b.cz, 2.6f, (float) b.cfg.pitch);
            kinds.remove(BlockPos.asLong(b.dx, b.dy, b.dz));
            kinds.remove(BlockPos.asLong(b.dx, b.dy + 1, b.dz));
        }
        if (b.t >= RB_LEN) endBreak(20);
    }

    private void endBreak(int cool) {
        br = null;
        doorCool = tick + cool;
        if (ent.hasProp("rompe")) ent.setProp("rompe", 0);
        lastPlan = -999;
    }

    private final List<int[]> opened = new ArrayList<>();   // {x,y,z,tickOpened}

    public ChaseNav(StalkerEntity ent) {
        this.ent = ent;
    }

    // ------------------------------------------------------------------ celdas
    /** 0 libre, 1 solido, 2 desconocido, 3 lava. */
    private int kind(int x, int y, int z) {
        long k = BlockPos.asLong(x, y, z);
        Byte c = kinds.get(k);
        if (c != null) return c;
        BlockPos p = new BlockPos(x, y, z);
        int v;
        if (y < level.getMinBuildHeight() || y >= level.getMaxBuildHeight() || !level.hasChunkAt(p)) v = 2;
        else {
            BlockState b = level.getBlockState(p);
            if (!b.getFluidState().isEmpty()) v = b.getFluidState().is(FluidTags.WATER) ? 0 : 3;
            else if (b.isAir()) v = 0;
            else {
                var blk = b.getBlock();
                String id = BuiltInRegistries.BLOCK.getKey(blk).getPath();
                if (blk instanceof DoorBlock || blk instanceof FenceGateBlock || blk instanceof TrapDoorBlock) v = 0;
                else if (CLIMB_RE.matcher(id).find()) v = 0;
                else {
                    var shape = b.getCollisionShape(level, p);
                    v = shape.isEmpty() || shape.max(net.minecraft.core.Direction.Axis.Y) <= 0.25 ? 0 : 1;
                }
            }
        }
        kinds.put(k, (byte) v);
        return v;
    }

    private boolean water(int x, int y, int z) {
        BlockPos p = new BlockPos(x, y, z);
        return level.hasChunkAt(p) && level.getFluidState(p).is(FluidTags.WATER);
    }

    private boolean climbCell(int x, int y, int z) {
        long k = BlockPos.asLong(x, y, z);
        Boolean c = climbs.get(k);
        if (c != null) return c;
        BlockPos p = new BlockPos(x, y, z);
        boolean v = level.hasChunkAt(p) && CLIMB_RE.matcher(BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).getBlock()).getPath()).find();
        climbs.put(k, v);
        return v;
    }

    private boolean trapCell(int x, int y, int z) {
        long k = BlockPos.asLong(x, y, z);
        Boolean c = traps.get(k);
        if (c != null) return c;
        BlockPos p = new BlockPos(x, y, z);
        boolean v = level.hasChunkAt(p) && level.getBlockState(p).getBlock() instanceof TrapDoorBlock;
        traps.put(k, v);
        return v;
    }

    private boolean canStand(int x, int y, int z) {
        return kind(x, y, z) == 0 && kind(x, y + 1, z) == 0 && (kind(x, y - 1, z) == 1 || water(x, y, z));
    }

    private boolean canStandJump(int x, int y, int z) {
        return kind(x, y, z) == 0 && kind(x, y + 1, z) == 0 && ((kind(x, y - 1, z) == 1 && !trapCell(x, y - 1, z)) || water(x, y, z));
    }

    private boolean canClimbAt(int x, int y, int z) {
        if (kind(x, y, z) != 0 || kind(x, y + 1, z) != 0) return false;
        return climbCell(x, y, z) || climbCell(x, y - 1, z);
    }

    private boolean canStandLow(int x, int y, int z) {
        return kind(x, y, z) == 0 && (kind(x, y - 1, z) == 1 || water(x, y, z));
    }

    private boolean roofCell(int x, int y, int z) {
        return kind(x, y, z) == 1 || trapCell(x, y, z);
    }

    private boolean sideSolid(int x, int y, int z) {
        return kind(x + 1, y, z) == 1 || kind(x - 1, y, z) == 1 || kind(x, y, z + 1) == 1 || kind(x, y, z - 1) == 1;
    }

    private boolean wallHang(int x, int y, int z) {
        if (kind(x, y, z) != 0 || kind(x, y + 1, z) != 0) return false;
        return sideSolid(x, y, z) || sideSolid(x, y + 1, z);
    }

    private boolean wallStep(int x, int y, int z, int vy) {
        int ny = y + vy;
        if (vy > 0) return kind(x, y + 2, z) == 0 && kind(x, ny, z) == 0 && (sideSolid(x, y, z) || sideSolid(x, ny, z));
        if (kind(x, ny, z) != 0) return false;
        return wallHang(x, ny, z) && (sideSolid(x, ny, z) || sideSolid(x, y, z));
    }

    private boolean hanging(double fx, double fy, double fz) {
        int x = (int) Math.floor(fx), y = (int) Math.floor(fy + 0.05), z = (int) Math.floor(fz);
        if (kind(x, y - 1, z) == 1 || water(x, y, z)) return false;
        return wallHang(x, y, z);
    }

    private double swimCost(int x, int y, int z) {
        if (!water(x, y, z)) return 0;
        return 0.5 + (water(x, y + 1, z) ? 1.0 : 0);
    }

    private int[] lowCellNear(double fx, double fy, double fz) {
        int x = (int) Math.floor(fx), z = (int) Math.floor(fz), y = (int) Math.floor(fy + 0.05);
        if (canStand(x, y, z) || canClimbAt(x, y, z)) return new int[]{x, y, z};
        if (canStandLow(x, y, z)) return new int[]{x, y, z};
        for (int dy : new int[]{0, -1, -2, -3, 1, 2, -4}) if (canStand(x, y + dy, z)) return new int[]{x, y + dy, z};
        for (int dy : new int[]{0, -1, 1, -2}) if (canStandLow(x, y + dy, z)) return new int[]{x, y + dy, z};
        return new int[]{x, y, z};
    }

    private int[] startCell(double x, double y, double z) {
        if (hanging(x, y, z)) return new int[]{(int) Math.floor(x), (int) Math.floor(y + 0.05), (int) Math.floor(z)};
        return lowCellNear(x, y, z);
    }

    // ------------------------------------------------------------------ A* (findPathLow)
    private static long key(int x, int y, int z, int[] st) {
        return (((long) (x - st[0] + 80) * 161L + (z - st[2] + 80)) * 61L + (y - st[1] + 30));
    }

    private static double h(int x, int y, int z, int[] goal) {
        double dx = Math.abs(x - goal[0]), dz = Math.abs(z - goal[2]);
        return (Math.max(dx, dz) + 0.414 * Math.min(dx, dz) + Math.abs(y - goal[1]) * 0.5) * 1.4;
    }

    private void relax(Map<Long, Node> nodes, PriorityQueue<Node> open, int[] st, int[] goal, Node n, int x, int y, int z, double cost, boolean jump) {
        long k = key(x, y, z, st);
        double g = n.g + cost;
        Node m = nodes.get(k);
        if (m == null) {
            m = new Node();
            m.x = x;
            m.y = y;
            m.z = z;
            m.g = g;
            m.f = g + h(x, y, z, goal);
            m.parent = n;
            m.jump = jump;
            nodes.put(k, m);
            open.add(m);
        } else if (g < m.g && !m.closed) {
            m.g = g;
            m.f = g + h(x, y, z, goal);
            m.parent = n;
            m.jump = jump;
            open.add(m);
        }
    }

    private boolean findPath(int[] st, int[] goal, int maxNodes) {
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        Map<Long, Node> nodes = new HashMap<>();
        Node s = new Node();
        s.x = st[0];
        s.y = st[1];
        s.z = st[2];
        s.f = h(s.x, s.y, s.z, goal);
        nodes.put(key(s.x, s.y, s.z, st), s);
        open.add(s);
        Node best = s, found = null;
        double bestH = s.f;
        int expanded = 0;
        while (!open.isEmpty() && expanded < maxNodes) {
            Node n = open.poll();
            if (n.closed) continue;
            n.closed = true;
            expanded++;
            double hn = h(n.x, n.y, n.z, goal);
            if (hn < bestH) {
                bestH = hn;
                best = n;
            }
            if (Math.abs(n.x - goal[0]) <= 1 && Math.abs(n.z - goal[2]) <= 1 && Math.abs(n.y - goal[1]) <= 1) {
                found = n;
                break;
            }
            // escaleras de mano / trepar paredes
            for (int vy : new int[]{1, -1}) {
                int ny2 = n.y + vy;
                if (Math.abs(ny2 - st[1]) > 28) continue;
                boolean ok;
                if (vy > 0) ok = climbCell(n.x, n.y, n.z) && canClimbAt(n.x, ny2, n.z);
                else ok = climbCell(n.x, ny2, n.z) && (canClimbAt(n.x, ny2, n.z) || canStandLow(n.x, ny2, n.z));
                double stepCost = 1.15;
                if (!ok && wallStep(n.x, n.y, n.z, vy)) {
                    ok = true;
                    stepCost = WALL_COST;
                }
                if (!ok) continue;
                relax(nodes, open, st, goal, n, n.x, ny2, n.z, stepCost, false);
            }
            for (int[] d : NEIGH8) {
                int dx = d[0], dz = d[1];
                int nx = n.x + dx, nz = n.z + dz;
                if (Math.abs(nx - st[0]) > 78 || Math.abs(nz - st[2]) > 78) continue;
                boolean diag = dx != 0 && dz != 0;
                if (diag) {
                    if (kind(n.x + dx, n.y, n.z) != 0 || kind(n.x, n.y, n.z + dz) != 0) continue;
                }
                int ny = Integer.MIN_VALUE;
                double cost = diag ? 1.414 : 1;
                if (canStandLow(nx, n.y, nz)) ny = n.y;
                else if (!diag && canClimbAt(nx, n.y, nz)) ny = n.y;
                else if (kind(n.x, n.y + 1, n.z) == 0 && canStandLow(nx, n.y + 1, nz)) {
                    ny = n.y + 1;
                    cost += 0.6;
                } else {
                    boolean nearGoal = Math.abs(nx - goal[0]) <= 1 && Math.abs(nz - goal[2]) <= 1 && goal[1] < n.y;
                    for (int dd = 1; dd <= 6; dd++) {
                        if (kind(nx, n.y - dd + 1, nz) != 0) break;
                        if (!nearGoal && (trapCell(nx, n.y - dd + 1, nz) || trapCell(nx, n.y - dd, nz))) break;
                        if (canStandLow(nx, n.y - dd, nz)) {
                            ny = n.y - dd;
                            cost += 0.4 * dd + (dd > 3 ? 1.5 : 0);
                            break;
                        }
                    }
                }
                // PARKOUR
                boolean gapAhead = !diag && kind(nx, n.y, nz) == 0 && kind(nx, n.y + 1, nz) == 0
                        && (kind(nx, n.y - 1, nz) != 1 || trapCell(nx, n.y - 1, nz) || trapCell(nx, n.y, nz));
                if (gapAhead && kind(n.x, n.y + 2, n.z) == 0 && kind(n.x, n.y - 1, n.z) == 1) {
                    for (int jd = 2; jd <= RUN_JUMP_MAX; jd++) {
                        boolean clear = true;
                        for (int i = 1; i < jd && clear; i++) {
                            int ix = n.x + dx * i, iz = n.z + dz * i;
                            for (int k2 = 0; k2 <= 2; k2++) if (kind(ix, n.y + k2, iz) != 0) {
                                clear = false;
                                break;
                            }
                        }
                        if (!clear) break;
                        int lx = n.x + dx * jd, lz = n.z + dz * jd;
                        int ly = Integer.MIN_VALUE;
                        int[] dys = jd <= 3 ? new int[]{0, 1, -1, -2} : new int[]{0, -1, -2};
                        for (int dyL : dys) {
                            if (dyL > 0 && kind(lx, n.y + 2, lz) != 0) continue;
                            if (canStandJump(lx, n.y + dyL, lz)) {
                                ly = n.y + dyL;
                                break;
                            }
                        }
                        if (ly == Integer.MIN_VALUE) continue;
                        if (Math.abs(ly - st[1]) > 28) break;
                        relax(nodes, open, st, goal, n, lx, ly, lz, jd + 1.2, true);
                        break;
                    }
                }
                if (ny == Integer.MIN_VALUE) continue;
                cost += swimCost(nx, ny, nz);
                if (Math.abs(ny - st[1]) > 28) continue;
                if (kind(nx, ny + 1, nz) != 0) cost += 0.3;
                relax(nodes, open, st, goal, n, nx, ny, nz, cost, false);
            }
        }
        Node end = found != null ? found : best;
        List<Cell> out = new ArrayList<>();
        for (Node n = end; n != null; n = n.parent) out.add(new Cell(n.x, n.y, n.z, n.jump));
        java.util.Collections.reverse(out);
        this.path = out;
        this.partial = found == null;
        this.idx = out.size() > 1 ? 1 : out.size();
        return found != null;
    }

    // ------------------------------------------------------------------ seguimiento (twxAdvance)
    private Step advance(double x, double y, double z, double budget, boolean crouched) {
        Step r = new Step();
        boolean climbing = false;
        openTrapsAhead(x, y, z);
        if (idx < path.size() && path.get(idx).jump && !crouched) {
            Cell w = path.get(idx);
            double wx = w.x + 0.5, wz = w.z + 0.5;
            if (jIdx != idx || !jValid) {
                jIdx = idx;
                jfx = x;
                jfy = y;
                jfz = z;
                jValid = true;
                jlen = Math.max(0.5, Math.hypot(wx - x, wz - z));
            }
            double ddx = wx - x, ddz = wz - z, dd = Math.hypot(ddx, ddz), step = budget * 1.15;
            if (dd <= step) {
                x = wx;
                z = wz;
                y = w.y;
                idx++;
                jValid = false;
            } else {
                x += ddx / dd * step;
                z += ddz / dd * step;
            }
            if (jValid) {
                double f = Math.min(1, 1 - Math.hypot(wx - x, wz - z) / jlen);
                y = jfy + (w.y - jfy) * f + JUMP_H * 4 * f * (1 - f) + (w.y > jfy ? 0.5 * Math.sin(f * Math.PI) : 0);
            }
            r.x = x;
            r.y = y;
            r.z = z;
            r.climbing = true;
            r.jumping = true;
            return r;
        }
        while (budget > 0.0001 && idx < path.size()) {
            Cell w = path.get(idx);
            if (w.jump && !crouched) break;
            double wx = w.x + 0.5, wz = w.z + 0.5;
            Cell pw = idx > 0 ? path.get(idx - 1) : null;
            if (pw != null && pw.x == w.x && pw.z == w.z && pw.y != w.y) {
                climbing = true;
                double hx = wx - x, hz = wz - z, hd = Math.hypot(hx, hz);
                if (hd > 0.05) {
                    double m = Math.min(hd, budget);
                    x += hx / hd * m;
                    z += hz / hd * m;
                }
                double vdy = w.y - y;
                if (Math.abs(vdy) <= CLIMB_SPEED) {
                    y = w.y;
                    idx++;
                } else y += Math.signum(vdy) * CLIMB_SPEED;
                break;
            }
            double ddx = wx - x, ddz = wz - z, dd = Math.hypot(ddx, ddz);
            if (dd <= budget) {
                x = wx;
                z = wz;
                budget -= dd;
                idx++;
                y = w.y;
            } else {
                x += ddx / dd * budget;
                z += ddz / dd * budget;
                budget = 0;
                if (y < w.y) y = Math.min(w.y, y + 0.4);
                else if (y > w.y) y = Math.max(w.y, y - 0.6);
            }
        }
        if (!climbing && !crouched) {
            int fx = (int) Math.floor(x), fy = (int) Math.floor(y + 0.05), fz = (int) Math.floor(z);
            if (climbCell(fx, fy, fz) && kind(fx, fy - 1, fz) != 1) climbing = true;
            else if (hanging(x, y, z)) climbing = true;
        }
        r.x = x;
        r.y = y;
        r.z = z;
        r.climbing = climbing;
        return r;
    }

    // ------------------------------------------------------------------ trampillas: las abre al pasar y las vuelve a cerrar
    private void openTrapsAhead(double x, double y, double z) {
        if (path.isEmpty() || idx >= path.size() || tick - trapT < 4) return;
        trapT = tick;
        for (int i = Math.max(0, idx - 1); i < Math.min(path.size(), idx + 3); i++) {
            Cell w = path.get(i), pw = i > 0 ? path.get(i - 1) : null;
            if (Math.hypot(w.x + 0.5 - x, w.z + 0.5 - z) > 2.2 || Math.abs(w.y - y) > 3) continue;
            List<Integer> ys = new ArrayList<>();
            ys.add(w.y);
            ys.add(w.y + 1);
            if (pw != null && pw.y != w.y) {
                ys.add(Math.min(pw.y, w.y));
                ys.add(Math.max(pw.y, w.y) + 1);
            }
            if (pw != null && w.y < pw.y) ys.add(pw.y - 1);
            if (pw != null && w.y > pw.y) {
                ys.add(w.y - 1);
                ys.add(pw.y + 2);
            }
            for (int ty : ys) {
                BlockPos p = new BlockPos(w.x, ty, w.z);
                BlockState b = level.getBlockState(p);
                if (!(b.getBlock() instanceof TrapDoorBlock) || BuiltInRegistries.BLOCK.getKey(b.getBlock()).getPath().contains("iron")) continue;
                if (b.getValue(BlockStateProperties.OPEN)) continue;
                level.setBlock(p, b.setValue(BlockStateProperties.OPEN, true), 3);
                level.playSound(null, p, SoundEvents.WOODEN_TRAPDOOR_OPEN, SoundSource.BLOCKS, 1f, 1f);
                kinds.remove(BlockPos.asLong(w.x, ty, w.z));
                traps.remove(BlockPos.asLong(w.x, ty, w.z));
                opened.add(new int[]{w.x, ty, w.z, tick});
            }
        }
    }

    private void closeTraps(boolean force) {
        for (int i = opened.size() - 1; i >= 0; i--) {
            int[] o = opened.get(i);
            boolean passed = Math.hypot(ent.getX() - (o[0] + 0.5), ent.getZ() - (o[2] + 0.5)) > 2.5 || Math.abs(ent.getY() - (o[1] + 0.5)) > 2.5;
            if (force || (tick - o[3] > 30 && passed) || tick - o[3] > 600) {
                BlockPos p = new BlockPos(o[0], o[1], o[2]);
                BlockState b = level.getBlockState(p);
                if (b.getBlock() instanceof TrapDoorBlock && b.getValue(BlockStateProperties.OPEN)) {
                    level.setBlock(p, b.setValue(BlockStateProperties.OPEN, false), 3);
                    level.playSound(null, p, SoundEvents.WOODEN_TRAPDOOR_CLOSE, SoundSource.BLOCKS, 1f, 1f);
                }
                opened.remove(i);
            }
        }
    }

    /** Llamar al terminar la persecucion: cierra las trampillas que abrio. */
    public void finish() {
        if (level != null) closeTraps(true);
    }

    // ------------------------------------------------------------------ un tick de persecucion
    private boolean playerLow(Player p) {
        int x = (int) Math.floor(p.getX()), y = (int) Math.floor(p.getY() + 0.05), z = (int) Math.floor(p.getZ());
        return roofCell(x, y + 1, z) || roofCell(x, (int) Math.floor(p.getY() + 0.7), z);
    }

    private boolean lowCheck(Player p, double horiz) {
        pSpeedTrack(p);
        boolean lw = false;
        int cx = (int) Math.floor(ent.getX()), cy = (int) Math.floor(ent.getY() + 0.05), cz = (int) Math.floor(ent.getZ());
        if (roofCell(cx, cy + 1, cz)) lw = true;
        for (int i = idx; !lw && i < Math.min(path.size(), idx + 2); i++) {
            Cell w = path.get(i);
            if (roofCell(w.x, w.y + 1, w.z)) lw = true;
        }
        if (!lw && pLow && horiz < 4) lw = true;
        if (lw) lowHold = LOW_HOLD;
        else if (lowHold > 0) lowHold--;
        low = lw || lowHold > 0;
        return low;
    }

    private void pSpeedTrack(Player p) {
        if (pvInit) {
            double v = Math.hypot(p.getX() - pvx, p.getZ() - pvz);
            double vv = v > 1.5 ? 0 : v;
            pSpeed = pSpeed * 0.7 + vv * 0.3;
        }
        pvInit = true;
        pvx = p.getX();
        pvz = p.getZ();
    }

    /**
     * Avanza un tick hacia el jugador. speed = bloques por tick. Devuelve el estado de movimiento (0 normal, 1 escalando/saltando, 2 gateando).
     */
    public int tickChase(Player target, double speed, float yawOff) {
        if (!(ent.level() instanceof ServerLevel sl)) return 0;
        this.level = sl;
        this.yawOffset = yawOff;
        tick++;
        if (br != null) {
            tickBreak();
            return 0;
        }
        if (tick >= doorCool) {
            Cfg cfg = doorCfg().get(ent.getDefId());
            if (cfg != null) {
                double tx = target.getX() - ent.getX(), tz = target.getZ() - ent.getZ();
                double td = Math.hypot(tx, tz);
                if (td > 0.5) {
                    double ux = tx / td, uz = tz / td;
                    if (idx < path.size()) {                       // direccion del camino
                        Cell w = path.get(idx);
                        double wx = w.x + 0.5 - ent.getX(), wz = w.z + 0.5 - ent.getZ(), wd = Math.hypot(wx, wz);
                        if (wd > 0.05) {
                            ux = wx / wd;
                            uz = wz / wd;
                        }
                    }
                    int[] D = doorAhead(ux, uz, cfg.hold);
                    if (D == null) D = doorAhead(tx / td, tz / td, Math.min(td, cfg.hold + 1.8));     // puerta entre el y tu
                    if (D != null && startBreak(ux, uz, D, cfg)) return 0;
                    if (D != null && startBreak(tx / td, tz / td, D, cfg)) return 0;
                }
            }
        }
        if (tick - cacheT > 60) {
            kinds.clear();
            climbs.clear();
            traps.clear();
            cacheT = tick;
        }
        boolean pl = playerLow(target);
        if (pl && !pLow) lastPlan = -999;
        pLow = pl;
        double horiz = Math.hypot(target.getX() - ent.getX(), target.getZ() - ent.getZ());
        int every = partial ? REPLAN * 2 : REPLAN;
        if (tick - lastPlan >= every || (idx >= path.size() && tick - lastPlan >= 4)) {
            int[] goal = lowCellNear(target.getX(), target.getY(), target.getZ());
            int[] start = startCell(ent.getX(), ent.getY(), ent.getZ());
            findPath(start, goal, partial ? MAX_NODES / 2 : MAX_NODES);
            lastPlan = tick;
        }
        boolean lw = lowCheck(target, horiz);
        double budget = lw ? Math.min(speed, Math.max(LOW_MIN_SPEED, pSpeed)) : speed;
        Step a = advance(ent.getX(), ent.getY(), ent.getZ(), budget, lw);
        double y = a.y;
        if (!a.climbing) y = antiSink(a.x, y, a.z);
        if (a.climbing) climbHold = CLIMB_HOLD;
        else if (climbHold > 0) climbHold--;
        mov = (a.climbing || climbHold > 0) ? 1 : (lw ? 2 : 0);
        float yaw = (float) (Math.atan2(-(target.getX() - a.x), target.getZ() - a.z) * 180 / Math.PI) + yawOffset;
        ent.setNoGravity(true);
        ent.noPhysics = true;
        ent.setDeltaMovement(0, 0, 0);
        ent.fallDistance = 0;
        ent.moveTo(a.x, y, a.z, yaw, 0);
        ent.setYHeadRot(yaw);
        ent.yBodyRot = yaw;
        closeTraps(false);
        return mov;
    }

    private double antiSink(double x, double y, double z) {
        int fy = (int) Math.floor(y + 0.001);
        boolean bumped = false;
        for (int i = 0; i < 4; i++) {
            BlockPos p = new BlockPos((int) Math.floor(x), fy, (int) Math.floor(z));
            if (level.hasChunkAt(p) && !level.getBlockState(p).getCollisionShape(level, p).isEmpty()
                    && level.getBlockState(p).getFluidState().isEmpty() && kind(p.getX(), p.getY(), p.getZ()) == 1) {
                fy++;
                bumped = true;
            } else break;
        }
        return bumped ? Math.max(y, fy) : y;
    }

    // ------------------------------------------------------------------ runRelocate del addon: reaparece detras de ti si esta lejos o atascado
    private static final java.util.Set<String> GROUND = java.util.Set.of("grass_block", "dirt", "coarse_dirt", "podzol", "mycelium", "rooted_dirt",
            "sand", "red_sand", "dirt_path");
    private static final Pattern NOT_WALL = Pattern.compile("grass|fern|flower|dandelion|poppy|tulip|orchid|allium|bluet|daisy|lily|rose|peony|lilac|sapling|torch|rail|carpet|vine|snow|mushroom|berry|bush|cobweb|web$|sign|button|lever|pressure_plate|redstone_wire|banner|leaves");
    private static final double RUN_FAR = 34;
    private int stuckChecks = 0, farTicks = 0, lastCheck = 0;
    private double wdX, wdY, wdZ;
    private boolean wdInit = false;

    private String bid(int x, int y, int z) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(new BlockPos(x, y, z)).getBlock()).getPath();
    }

    private boolean solidAt(int x, int y, int z) {
        BlockPos p = new BlockPos(x, y, z);
        return level.hasChunkAt(p) && !level.getBlockState(p).getCollisionShape(level, p).isEmpty();
    }

    /** isInsideHouse del addon: techo encima (hasta 14) y al menos 2 de los 4 lados con pared (hasta 16 bloques). */
    private boolean insideHouse(Player p) {
        int bx = (int) Math.floor(p.getX()), by = (int) Math.floor(p.getY()), bz = (int) Math.floor(p.getZ());
        boolean roof = false;
        for (int[] o : new int[][]{{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            for (int dy = 2; dy <= 14 && !roof; dy++) {
                if (solidAt(bx + o[0], by + dy, bz + o[1]) && !bid(bx + o[0], by + dy, bz + o[1]).contains("leaves")) roof = true;
            }
            if (roof) break;
        }
        if (!roof) return false;
        int walls = 0;
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            boolean hit = false;
            for (int k = 1; k <= 16 && !hit; k++) {
                for (int hh = 0; hh <= 3; hh++) {
                    BlockPos q = new BlockPos(bx + d[0] * k, by + hh, bz + d[1] * k);
                    if (!level.hasChunkAt(q)) continue;
                    var st = level.getBlockState(q);
                    if (!st.isAir() && st.getFluidState().isEmpty() && !NOT_WALL.matcher(BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath()).find()) {
                        hit = true;
                        break;
                    }
                }
            }
            if (hit) walls++;
        }
        return walls >= 2;
    }

    private boolean doorBetween(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = bx - ax, dz = bz - az;
        int n = (int) Math.ceil(Math.hypot(dx, dz) / 0.3);
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int i = 1; i < n; i++) {
            int x = (int) Math.floor(ax + dx * i / n), z = (int) Math.floor(az + dz * i / n);
            for (int y : new int[]{(int) Math.floor(ay + 0.2), (int) Math.floor(by + 0.2)}) {
                if (!seen.add(BlockPos.asLong(x, y, z))) continue;
                BlockPos q = new BlockPos(x, y, z);
                if (level.hasChunkAt(q) && level.getBlockState(q).getBlock() instanceof DoorBlock) return true;
            }
        }
        return false;
    }

    private double[] spawnPoint(Player p, int[] dists, boolean groundOnly) {
        var look = p.getLookAngle();
        double len = Math.max(1e-4, Math.hypot(look.x, look.z));
        double bx = -look.x / len, bz = -look.z / len;
        int py = (int) Math.floor(p.getY());
        for (int dist : dists) {
            for (int a : new int[]{0, 30, -30, 60, -60, 90, -90, 135, -135, 180}) {
                double r = Math.toRadians(a);
                double x = p.getX() + (bx * Math.cos(r) - bz * Math.sin(r)) * dist;
                double z = p.getZ() + (bx * Math.sin(r) + bz * Math.cos(r)) * dist;
                int cx = (int) Math.floor(x), cz = (int) Math.floor(z), cy = (int) Math.floor(p.getY() + 0.05);
                int fy = Integer.MIN_VALUE;
                for (int dy : new int[]{0, -1, -2, -3, 1, 2, -4}) if (canStand(cx, cy + dy, cz)) {
                    fy = cy + dy;
                    break;
                }
                if (fy == Integer.MIN_VALUE || Math.abs(fy - py) > 4) continue;
                if (groundOnly && !GROUND.contains(bid(cx, fy - 1, cz))) continue;
                return new double[]{cx + 0.5, fy, cz + 0.5};
            }
        }
        return null;
    }

    /** runRelocate: false si no se puede (esta dentro de una casa, hay una puerta de por medio...). */
    public boolean relocate(Player p) {
        if (insideHouse(p)) {
            stuckChecks = 0;
            farTicks = 0;
            return false;
        }
        int[] dists = {14, 12, 10, 8, 6};
        double[] pt = spawnPoint(p, dists, true);
        if (pt == null) pt = spawnPoint(p, dists, false);
        if (pt == null) return false;
        if (doorBetween(pt[0], pt[1], pt[2], p.getX(), p.getY(), p.getZ())) {
            stuckChecks = 0;
            return false;
        }
        ent.moveTo(pt[0], pt[1], pt[2], ent.getYRot(), 0);
        path = new ArrayList<>();
        idx = 0;
        lastPlan = -999;
        stuckChecks = 0;
        farTicks = 0;
        lowHold = 0;
        wdX = pt[0];
        wdY = pt[1];
        wdZ = pt[2];
        return true;
    }

    /** Vigilante del addon: a mas de 34 bloques por 2 s, o casi sin moverse 2 s seguidos dos veces -> reaparece detras de ti. */
    public boolean watchdog(Player p, double horiz, double catchDist) {
        if (level == null) return false;
        if (!wdInit) {
            wdInit = true;
            wdX = ent.getX();
            wdY = ent.getY();
            wdZ = ent.getZ();
            lastCheck = tick;
        }
        if (horiz > RUN_FAR) farTicks++;
        else farTicks = 0;
        if (farTicks > 40 && relocate(p)) return true;
        if (tick - lastCheck >= 20) {
            double mv = Math.sqrt(Math.pow(ent.getX() - wdX, 2) + Math.pow(ent.getY() - wdY, 2) + Math.pow(ent.getZ() - wdZ, 2));
            double stuckMin = !low ? 0.8 : Math.min(0.8, Math.max(LOW_MIN_SPEED, pSpeed) * 20 * 0.5);
            if (mv < stuckMin && horiz > catchDist + 1.5) stuckChecks++;
            else stuckChecks = 0;
            wdX = ent.getX();
            wdY = ent.getY();
            wdZ = ent.getZ();
            lastCheck = tick;
            if (stuckChecks >= 2 && relocate(p)) return true;
        }
        return false;
    }

    /** Fuerza el recalculo del camino en el proximo tick. */
    public void replan() {
        lastPlan = -999;
    }
}
