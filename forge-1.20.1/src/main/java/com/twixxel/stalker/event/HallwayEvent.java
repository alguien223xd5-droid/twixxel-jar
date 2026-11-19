package com.twixxel.stalker.event;

import com.twixxel.stalker.TwixxelMod;
import com.twixxel.stalker.entity.StalkerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * EL DEL PASILLO (stalker_run_pasillo): al acostarte (10 % cada vez) si desde la almohada se ve una esquina de pasillo:
 * 1) sigues acostado, la camara mira el pasillo y suena la musica; 2) se asoma la CABEZA de lado por la esquina;
 * 3) te despiertas de pie junto a la cama y saca la MANO larga hacia ti; 4) sale con todo el cuerpo y te persigue como el RUN.
 */
public final class HallwayEvent {
    private static final double RANGE_MIN = 3.5, RANGE_MAX = 14, BEHIND = .95, SC_TALL = .43, SC_LOW = .31;
    private static final double HEAD_OUT = 4, TILT = 90, SHOW = 6, REACH = 42, NEAR = 1.6;
    private static final int T_HEAD = 6, T_WAKE = 72, T_HAND = 76, REACH_AT = 112, T_OUT = 152, OUT_LEN = 24, T_RUN = 178;
    private static final double CHANCE = .10;

    private static final class Spot {
        Vec3 E, W, H, O, X, head;
        double rx, rz, fx, fz, sc, score;
        int s;
        boolean tall;
    }

    private static final class St {
        StalkerEntity e;
        UUID pid;
        ServerLevel level;
        Spot sp;
        List<BlockPos> cells;
        BlockPos headCell;
        float yaw, curYaw;
        Vec3 cur;
        int t;
        boolean awake;
        ArmorStand rig;
        boolean camSent;
    }

    private static St st;
    private static final Set<UUID> CHECKED = new HashSet<>(), ARMED = new HashSet<>();
    private static final Random RND = new Random();

    private static boolean es() {
        return com.twixxel.stalker.event.Lang.es();
    }

    private static double smooth(double x) {
        x = Math.max(0, Math.min(1, x));
        return x * x * (3 - 2 * x);
    }

    private static float yawTo(Vec3 a, Vec3 b) {
        return (float) (Math.atan2(-(b.x - a.x), b.z - a.z) * 180 / Math.PI);
    }

    private static boolean fr(ServerLevel l, double x, double y, double z) {
        BlockPos q = BlockPos.containing(x, y, z);
        BlockState s = l.getBlockState(q);
        return s.getCollisionShape(l, q).isEmpty() && s.getFluidState().isEmpty();
    }

    private static boolean cov(ServerLevel l, double x, double y, double z) {
        BlockPos q = BlockPos.containing(x, y, z);
        return l.getBlockState(q).isSolidRender(l, q);
    }

    private static boolean sight(ServerLevel l, Vec3 a, Vec3 b, net.minecraft.world.entity.Entity ctx) {
        return l.clip(new ClipContext(a, b, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, ctx)).getType() == HitResult.Type.MISS;
    }

    private static Vec3 lerp(Vec3 a, Vec3 b, double f) {
        return new Vec3(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, a.z + (b.z - a.z) * f);
    }

    // ------------------------------------------------------------------ geometria
    /** [k, hs, clat, cup, plat, pup, show] de la cabeza asomada de lado. */
    private static double[] headGeo(double sc) {
        double k = sc / 16, hs = sc >= .4 ? 2.0 : 1.7, a = TILT * Math.PI / 180;
        return new double[]{k, hs,
                (HEAD_OUT + 8 * hs * Math.sin(a)) * k, (88.64 + 8 * hs * Math.cos(a)) * k,
                (HEAD_OUT + hs * (-2.7 * Math.cos(a) + 9.7 * Math.sin(a))) * k, (88.64 + hs * (2.7 * Math.sin(a) + 9.7 * Math.cos(a))) * k,
                (HEAD_OUT + SHOW * hs) * k};
    }

    private static double edge(Vec3 E, Vec3 W) {
        double dw = Math.hypot(W.x - E.x, W.z - E.z);
        if (dw < 1e-4) dw = 1;
        return .5 * (dw + BEHIND) / Math.max(1, dw - .5);
    }

    private static Spot tryCell(ServerLevel l, ServerPlayer p, Vec3 E, Vec3 W, double d, double[] foot) {
        double fx = W.x - E.x, fz = W.z - E.z;
        double fl = Math.hypot(fx, fz);
        if (fl < 1e-4) fl = 1;
        fx /= fl;
        fz /= fl;
        double rx = -fz, rz = fx, y0 = W.y;
        Vec3 H = new Vec3(W.x + fx * BEHIND, y0, W.z + fz * BEHIND);
        if (fr(l, H.x, y0 - 1, H.z)) return null;                           // tiene que pisar suelo
        if (!fr(l, H.x, y0, H.z) || !fr(l, H.x, y0 + 1, H.z)) return null;
        boolean tall = fr(l, H.x, y0 + 2, H.z);
        double sc = tall ? SC_TALL : SC_LOW;
        double[] g = headGeo(sc);
        double edge = edge(E, W), slide = edge - g[6];
        int[] sides = RND.nextBoolean() ? new int[]{1, -1} : new int[]{-1, 1};
        for (int s : sides) {
            if (!fr(l, W.x + rx * s, y0, W.z + rz * s) || !fr(l, W.x + rx * s, y0 + 1, W.z + rz * s)) continue;
            Vec3 O = new Vec3(H.x + rx * s * slide, y0, H.z + rz * s * slide);
            if (!fr(l, O.x, y0, O.z) || !fr(l, O.x, y0 + 1, O.z)) continue;
            boolean hid = true;
            for (double h : new double[]{.3, .9, Math.max(.9, 74 * g[0])}) {
                for (double lat : new double[]{0, 10 * g[0] * s}) {
                    if (sight(l, E, new Vec3(O.x + rx * lat, y0 + h, O.z + rz * lat), p)) {
                        hid = false;
                        break;
                    }
                }
                if (!hid) break;
            }
            if (!hid) continue;
            Vec3 head = new Vec3(O.x + rx * s * g[2], y0 + g[3], O.z + rz * s * g[2]);
            Vec3 seen = new Vec3(O.x + rx * s * g[4], y0 + g[5], O.z + rz * s * g[4]);
            if (!fr(l, head.x, head.y, head.z) || !fr(l, head.x, head.y + .35, head.z)) continue;
            if (!sight(l, E, seen, p) || !sight(l, E, head, p)) continue;
            boolean handOk = true;
            double[][] hand = {{.4, 1.9}, {1.2, 1.5}, {2.0, .9}};
            for (double[] kh : hand) {
                if (!fr(l, H.x + rx * s * (edge + kh[0]), y0 + kh[1] * (g[0] * 16 / .43), H.z + rz * s * (edge + kh[0]))) {
                    handOk = false;
                    break;
                }
            }
            if (!handOk) continue;
            Vec3 X = new Vec3(H.x + rx * s * 1.25, y0, H.z + rz * s * 1.25);
            if (fr(l, X.x, y0 - 1, X.z) || !fr(l, X.x, y0, X.z) || !fr(l, X.x, y0 + 1, X.z)) continue;
            if (!sight(l, E, new Vec3(X.x, y0 + 1.0, X.z), p)) continue;
            double score = Math.abs(d - 6) * .6 + RND.nextDouble() * 1.5;
            if (foot != null) score += (1 - (fx * foot[0] + fz * foot[1])) * 2.5;
            if (!tall) score += 1.5;
            Spot sp = new Spot();
            sp.E = E;
            sp.W = W;
            sp.H = H;
            sp.O = O;
            sp.X = X;
            sp.rx = rx;
            sp.rz = rz;
            sp.fx = fx;
            sp.fz = fz;
            sp.s = s;
            sp.sc = sc;
            sp.tall = tall;
            sp.head = head;
            sp.score = score;
            return sp;
        }
        return null;
    }

    private static Spot find(ServerLevel l, ServerPlayer p, List<BlockPos> cells, BlockPos headCell) {
        Vec3 E = new Vec3(headCell.getX() + .5, headCell.getY() + 1.05, headCell.getZ() + .5);
        double[] foot = null;
        for (BlockPos c : cells) if (!c.equals(headCell)) foot = new double[]{c.getX() - headCell.getX(), c.getZ() - headCell.getZ()};
        Spot best = null;
        int R0 = (int) Math.ceil(RANGE_MAX);
        for (int dx = -R0; dx <= R0; dx++) for (int dz = -R0; dz <= R0; dz++) {
            double cx = headCell.getX() + dx + .5, cz = headCell.getZ() + dz + .5;
            double d = Math.hypot(cx - E.x, cz - E.z);
            if (d < RANGE_MIN || d > RANGE_MAX) continue;
            for (int dy : new int[]{0, -1, 1}) {
                double y0 = headCell.getY() + dy;
                if (!cov(l, cx, y0, cz) || !cov(l, cx, y0 + 1, cz)) continue;
                Spot c = tryCell(l, p, E, new Vec3(cx, y0, cz), d, foot);
                if (c != null) {
                    if (best == null || c.score < best.score) best = c;
                    break;
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ inicio / fin
    private static boolean isBed(ServerLevel l, BlockPos q) {
        return l.getBlockState(q).getBlock() instanceof BedBlock;
    }

    private static boolean start(ServerPlayer p) {
        if (st != null) return false;
        ServerLevel l = p.serverLevel();
        BlockPos b0 = p.getSleepingPos().orElse(null);
        if (b0 == null || !isBed(l, b0)) return false;
        List<BlockPos> cells = new ArrayList<>();
        cells.add(b0);
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            BlockPos q = b0.offset(d[0], 0, d[1]);
            if (isBed(l, q)) {
                cells.add(q);
                break;
            }
        }
        BlockPos head = cells.get(0);
        for (BlockPos c : cells) if (l.getBlockState(c).getValue(BedBlock.PART) == BedPart.HEAD) head = c;
        Spot sp = find(l, p, cells, head);
        if (sp == null) return false;
        float yaw = yawTo(sp.H, sp.E);
        StalkerEntity e = SpawnHelper.createScripted(l, "stalker_run_pasillo", sp.O, yaw);
        if (e == null) return false;
        e.noCulling = true;
        e.setProp("lado", sp.s > 0 ? 0 : 1);
        e.setProp("alto", sp.tall ? 1 : 0);
        e.setProp("fase", 0);
        St s = new St();
        s.e = e;
        s.pid = p.getUUID();
        s.level = l;
        s.sp = sp;
        s.cells = cells;
        s.headCell = head;
        s.yaw = yaw;
        // la camara: desde la almohada, un poco hacia la cabecera, mirando la cabeza que se asoma
        ArmorStand a = new ArmorStand(l, sp.E.x, sp.E.y, sp.E.z);
        a.setInvisible(true);
        a.setNoGravity(true);
        a.noPhysics = true;
        a.setInvulnerable(true);
        a.setSilent(true);
        l.addFreshEntity(a);
        s.rig = a;
        Vec3 loc = new Vec3(sp.E.x - sp.fx * .2, sp.E.y, sp.E.z - sp.fz * .2);
        double tx = sp.head.x - loc.x, ty = (sp.head.y - .6) - loc.y, tz = sp.head.z - loc.z;
        float cy = (float) (Math.atan2(-tx, tz) * 180 / Math.PI), cp = (float) (-(Math.atan2(ty, Math.hypot(tx, tz)) * 180 / Math.PI));
        a.setPos(loc.x, loc.y - a.getEyeHeight(), loc.z);
        a.setYRot(cy);
        a.setXRot(cp);
        a.setYHeadRot(cy);
        a.setYBodyRot(cy);
        var se = TwixxelMod.SOUND_EVENTS.get("stalker.pasillo");
        if (se != null) p.playNotifySound(se.get(), SoundSource.MASTER, 1f, 1f);
        st = s;
        return true;
    }

    /** Huevo / comando: la proxima vez que te acuestes pasa seguro. */
    public static boolean arm(ServerPlayer p) {
        ARMED.add(p.getUUID());
        CHECKED.remove(p.getUUID());
        p.sendSystemMessage(Component.literal(es() ? "§7Acuéstate en tu cama..." : "§7Lie down in your bed..."));
        return true;
    }

    private static void releaseCam(ServerPlayer p, St s) {
        if (s.rig != null) {
            if (p != null && s.camSent) p.connection.send(new ClientboundSetCameraPacket(p));
            s.rig.discard();
            s.rig = null;
        }
    }

    private static void end(ServerPlayer p, boolean removeEntity) {
        St s = st;
        st = null;
        if (s == null) return;
        releaseCam(p, s);
        if (removeEntity && s.e != null && s.e.isAlive()) s.e.discard();
    }

    private static void wake(ServerPlayer p, St s, boolean getUp) {
        s.awake = true;
        releaseCam(p, s);
        if (!getUp) return;
        p.stopSleeping();
        Vec3 best = null;
        double bd = 1e9;
        for (BlockPos c : s.cells) for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            BlockPos q = c.offset(d[0], 0, d[1]);
            if (s.cells.contains(q) || !fr(s.level, q.getX() + .5, q.getY(), q.getZ() + .5) || !fr(s.level, q.getX() + .5, q.getY() + 1, q.getZ() + .5)
                    || fr(s.level, q.getX() + .5, q.getY() - 1, q.getZ() + .5)) continue;
            double dd = Math.hypot(q.getX() + .5 - s.sp.X.x, q.getZ() + .5 - s.sp.X.z);
            if (dd < bd) {
                bd = dd;
                best = new Vec3(q.getX() + .5, q.getY(), q.getZ() + .5);
            }
        }
        if (best == null) best = new Vec3(s.headCell.getX() + .5, s.headCell.getY() + .5625, s.headCell.getZ() + .5);
        p.teleportTo(s.level, best.x, best.y, best.z, yawTo(best, s.sp.head), 5f);
    }

    private static void becomeRun(ServerPlayer p, St s) {
        Vec3 X = s.sp.X;
        end(p, true);
        if (p.isAlive()) SpawnHelper.spawnChaseAt(p, "stalker_run", X, false);
    }

    private static void put(StalkerEntity e, Vec3 pos, float yaw) {
        e.setPos(pos.x, pos.y, pos.z);
        e.setYRot(yaw);
        e.setYHeadRot(yaw);
        e.setYBodyRot(yaw);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        if (st != null) {
            tick(server);
            return;
        }
        if (server.getTickCount() % 5 != 0) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!p.isSleeping()) {
                CHECKED.remove(p.getUUID());
                continue;
            }
            if (CHECKED.contains(p.getUUID()) || !p.isAlive()) continue;
            if (!StalkerEntity.ACTIVE.isEmpty() || BedHide.isHidden(p.getUUID()) || BedStalker.active()
                    || "cama".equals(p.getPersistentData().getString(HelloVisit.K_PHASE))) continue;
            CHECKED.add(p.getUUID());
            boolean armed = ARMED.contains(p.getUUID());
            if (!armed && RND.nextDouble() >= CHANCE) continue;
            boolean ok = start(p);
            if (armed) {
                ARMED.remove(p.getUUID());
                if (!ok) p.displayClientMessage(Component.literal(es() ? "§cDesde la cama no se ve ningún pasillo con esquina"
                        : "§cNo hallway corner can be seen from the bed"), true);
            }
            if (ok) return;
        }
    }

    private static void tick(MinecraftServer server) {
        St r = st;
        ServerPlayer p = server.getPlayerList().getPlayer(r.pid);
        if (!r.e.isAlive() || p == null || !p.isAlive() || p.level() != r.level) {
            end(p, true);
            return;
        }
        r.t++;
        int t = r.t;
        Spot sp = r.sp;
        if (r.rig != null && !r.camSent && t >= 2) {
            p.connection.send(new ClientboundSetCameraPacket(r.rig));
            r.camSent = true;
        }
        if (!r.awake) {
            if (t == T_HEAD) r.e.setProp("fase", 1);                          // se asoma la cabeza
            if (!p.isSleeping() && t > 2) {                                   // te levantaste: la camara vuelve ya
                wake(p, r, false);
                r.t = Math.max(t, T_WAKE);
                return;
            }
            if (t >= T_WAKE) wake(p, r, true);
            return;
        }
        if (t == T_HAND) r.e.setProp("fase", 2);                              // saca la mano
        if (t >= REACH_AT && t < T_OUT) {                                     // la mano va entrando a la habitacion
            double b = (REACH * Math.PI / 180) * smooth((t - REACH_AT) / (double) (T_OUT - REACH_AT - 4));
            int s = sp.s;
            double lx = sp.rx * s * Math.cos(b) - sp.fx * Math.sin(b), lz = sp.rz * s * Math.cos(b) - sp.fz * Math.sin(b);
            double Rx = lx * s, Rz = lz * s;
            double ho = HEAD_OUT * (sp.sc / 16);
            Vec3 piv = new Vec3(sp.O.x + sp.rx * s * ho, 0, sp.O.z + sp.rz * s * ho);
            Vec3 pos = new Vec3(piv.x - Rx * s * ho, sp.O.y, piv.z - Rz * s * ho);
            float yaw = (float) (Math.atan2(Rz, Rx) * 180 / Math.PI);
            r.cur = pos;
            r.curYaw = yaw;
            put(r.e, pos, yaw);
        }
        if (t < T_OUT) {                                                      // te le acercaste: sale de una
            if (Math.hypot(p.getX() - sp.O.x, p.getZ() - sp.O.z) <= NEAR + .6 || Math.hypot(p.getX() - sp.X.x, p.getZ() - sp.X.z) <= NEAR) {
                r.t = T_OUT - 1;
                return;
            }
        }
        if (t == T_OUT) {
            r.e.setProp("fase", 3);                                           // sale todo el cuerpo
            var se = TwixxelMod.SOUND_EVENTS.get("stalker.run_crack_1");
            if (se != null) r.level.playSound(null, sp.X.x, sp.X.y, sp.X.z, se.get(), SoundSource.HOSTILE, 2f, .8f);
        }
        if (t > T_OUT) {
            double k = smooth((t - T_OUT - 4) / (double) (OUT_LEN - 4));
            Vec3 P0 = r.cur != null ? r.cur : sp.O;
            float Y0 = r.cur != null ? r.curYaw : r.yaw;
            Vec3 pos = new Vec3(P0.x + (sp.X.x - P0.x) * k, sp.O.y, P0.z + (sp.X.z - P0.z) * k);
            float yaw2 = yawTo(pos, p.position());
            double dy = ((yaw2 - Y0 + 540) % 360) - 180;
            put(r.e, pos, (float) (Y0 + dy * k));
        }
        if (t >= T_RUN) becomeRun(p, r);
    }

    @SubscribeEvent
    public void onDeath(LivingDeathEvent ev) {
        if (st != null && ev.getEntity() instanceof ServerPlayer p && p.getUUID().equals(st.pid)) end(p, true);
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent ev) {
        if (st != null && ev.getEntity() instanceof ServerPlayer p && p.getUUID().equals(st.pid)) end(null, true);
        CHECKED.remove(ev.getEntity().getUUID());
    }
}
