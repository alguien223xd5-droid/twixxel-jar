package com.twixxel.stalker.event;

import com.twixxel.stalker.entity.StalkerEntity;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * STALKER DE LA ESCALERA: en las minas aparece colgando de cabeza del techo, justo al final (arriba) de una escalera de 3 escalones o mas,
 * mirandote desde arriba. Mirarlo 3 s (o acercarte a 2.5 bloques): se sacude tronandose, "RUN" en rojo y se vuelve el RUN que te persigue.
 * Si lo ignoras 2 min, sube al techo y se va. Cada 1 a 5 min en minas. Prueba: huevo o /twxescalera.
 */
public final class EscaleraEvent {
    private static final String ID = "stalker_escalera";
    private static final int R = 14, YLO = -4, YHI = 12, STEPS = 2, READY = 30, LOOK_NEED = 60, SHAKE = 28, UP_T = 30, MAX_T = 2400, RETRY = 300;
    private static final double H = 1.5, FACE = .45, LOOK_COS = .93, SEE = 40, NEAR = 2.5;
    private static final Random RND = new Random();

    private static final class Sp {
        Vec3 p;
        int dx, dz, h, n, tx, ty, tz;
        double score;
    }

    private static final class V {
        StalkerEntity e;
        ServerLevel level;
        java.util.UUID pid;
        Vec3 p;
        int tx, ty, tz, h, t, pt, looked, up = -1;
        boolean shake;
    }


    private static V cur;
    private static long nextAt = -1;

    private static boolean es() {
        return Lang.es();
    }

    private static int rd(ServerLevel l, Map<Long, Integer> cache, int x, int y, int z) {
        long k = BlockPos.asLong(x, y, z);
        Integer c = cache.get(k);
        if (c != null) return c;
        BlockPos q = new BlockPos(x, y, z);
        int r;
        if (!l.isLoaded(q)) r = 2;
        else {
            BlockState b = l.getBlockState(q);
            if (!b.getFluidState().isEmpty()) r = 2;
            else if (b.getBlock() instanceof StairBlock || b.getBlock() instanceof SlabBlock) r = 1;
            else r = b.getCollisionShape(l, q).isEmpty() ? 0 : 1;
        }
        cache.put(k, r);
        return r;
    }

    private static boolean step(ServerLevel l, Map<Long, Integer> c, int x, int y, int z) {
        return rd(l, c, x, y - 1, z) == 1 && rd(l, c, x, y, z) == 0;
    }

    private static int roof(ServerLevel l, Map<Long, Integer> c, int x, int y, int z) {
        for (int h = 1; h <= 6; h++) {
            int k = rd(l, c, x, y + h, z);
            if (k == 1) return h;
            if (k != 0) return -1;
        }
        return -1;
    }

    private static boolean sight(ServerLevel l, Vec3 a, Vec3 b, ServerPlayer p) {
        return l.clip(new ClipContext(a, b, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, p)).getType() == HitResult.Type.MISS;
    }

    private static Sp find(ServerPlayer pl, boolean egg) {
        ServerLevel l = pl.serverLevel();
        Map<Long, Integer> c = new HashMap<>();
        Vec3 eye = pl.getEyePosition(), view = pl.getLookAngle();
        double vl = Math.max(1e-4, Math.hypot(view.x, view.z));
        int px = (int) Math.floor(pl.getX()), py = (int) Math.floor(pl.getY()), pz = (int) Math.floor(pl.getZ());
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        Sp best = null;
        for (int x = px - R; x <= px + R; x++) {
            for (int z = pz - R; z <= pz + R; z++) {
                for (int y = py + YLO; y <= py + YHI; y++) {
                    if (!step(l, c, x, y, z)) continue;
                    for (int[] d : dirs) {
                        if (step(l, c, x + d[0], y + 1, z + d[1])) continue;               // es el ultimo escalon
                        int cx = x, cy = y, cz = z, len = 1;
                        while (len < 64 && step(l, c, cx - d[0], cy - 1, cz - d[1])) {
                            cx -= d[0];
                            cy--;
                            cz -= d[1];
                            len++;
                        }
                        int n = len - 1;
                        if (n < STEPS) continue;
                        // el escalon mas alto con techo encima y espacio para colgar
                        int hx = 0, hy = 0, hz = 0, hh = -1;
                        for (int k = 0; k < Math.min(len, 3) && hh < 0; k++) {
                            int sx = x - d[0] * k, sy = y - k, sz = z - d[1] * k;
                            int h = roof(l, c, sx, sy, sz);
                            if (h >= 2) {
                                hx = sx;
                                hy = sy;
                                hz = sz;
                                hh = h;
                            }
                        }
                        if (hh < 0) continue;
                        double cy2 = hy + hh;                                               // y del bloque del techo
                        Vec3 p = new Vec3(hx + .5, Math.max(hy + .25, cy2 - H), hz + .5);
                        Vec3 f = new Vec3(p.x, p.y + FACE, p.z);
                        double hd = Math.hypot(f.x - pl.getX(), f.z - pl.getZ());
                        if (hd < (egg ? 1.5 : 3) || hd > R + 2) continue;
                        if (!egg && pl.getY() > hy - .5) continue;                          // natural: tienes que estar mas abajo
                        boolean seen = sight(l, eye, f, pl);
                        double d3 = Math.max(1e-4, f.distanceTo(eye));
                        double front = ((f.x - eye.x) * view.x / vl + (f.z - eye.z) * view.z / vl) / d3;
                        double score = -n * .4 - front * 2 + Math.abs(hd - 7) * .2 + RND.nextDouble() * .5;
                        if (!seen) score += 10;
                        boolean topFree = roof(l, c, x, y, z) >= 2;
                        Sp sp = new Sp();
                        sp.p = p;
                        sp.dx = d[0];
                        sp.dz = d[1];
                        sp.h = hh;
                        sp.n = n;
                        sp.tx = topFree ? x : hx;
                        sp.ty = topFree ? y : hy;
                        sp.tz = topFree ? z : hz;
                        sp.score = score;
                        if (best == null || sp.score < best.score) best = sp;
                    }
                }
            }
        }
        return best;
    }

    private static boolean start(ServerPlayer pl, Sp sp) {
        if (cur != null || sp == null) return false;
        ServerLevel l = pl.serverLevel();
        float yaw = (float) (Math.atan2(sp.dx, -sp.dz) * 180 / Math.PI);       // mira hacia abajo de la escalera
        StalkerEntity e = SpawnHelper.createScripted(l, ID, sp.p, yaw);
        if (e == null) return false;
        e.noCulling = true;
        e.setProp("fase", 0);
        V v = new V();
        v.e = e;
        v.level = l;
        
        v.pid = pl.getUUID();
        v.p = sp.p;
        v.tx = sp.tx;
        v.ty = sp.ty;
        v.tz = sp.tz;
        v.h = sp.h;
        cur = v;
        return true;
    }

    private static void end(boolean retry, long now) {
        V v = cur;
        cur = null;
        nextAt = now + (retry ? RETRY : SpawnConfig.ticks("escalera", 1200, 6000, RND));
        if (v != null && v.e != null && v.e.isAlive()) v.e.discard();
    }

    private static boolean looking(ServerPlayer p, V v) {
        Vec3 eye = p.getEyePosition(), f = new Vec3(v.p.x, v.p.y + FACE, v.p.z), to = f.subtract(eye);
        double d = to.length();
        if (d > SEE || d < .1) return false;
        if (p.getLookAngle().dot(to) / d < LOOK_COS) return false;
        return sight(v.level, eye, f, p);
    }

    @SubscribeEvent
    public void onJoin(EntityJoinLevelEvent ev) {
        if (ev.getLevel().isClientSide || !(ev.getEntity() instanceof StalkerEntity e) || !e.getDefId().equals(ID) || e.isScripted()) return;
        ev.setCanceled(true);
        if (ev.loadedFromDisk() || !(ev.getLevel() instanceof ServerLevel sl)) return;
        ServerPlayer near = null;
        double bd = 1e18;
        for (ServerPlayer q : sl.players()) {
            double d = q.distanceToSqr(e.position());
            if (d < bd) {
                bd = d;
                near = q;
            }
        }
        if (near != null) {
            final ServerPlayer p = near;
            sl.getServer().execute(() -> test(p));
        }
    }

    private static int test(ServerPlayer p) {
        if (cur != null) {
            p.displayClientMessage(Component.literal(es() ? "§4Ya hay uno en una escalera" : "§4There is already one on some stairs"), true);
            return 0;
        }
        Sp sp = find(p, true);
        if (sp == null || !start(p, sp)) {
            p.displayClientMessage(Component.literal(es() ? "§4No hay ninguna escalera de 3 escalones o más cerca (con techo)" : "§4No stairs of 3+ steps nearby (with a ceiling)"), true);
            return 0;
        }
        p.displayClientMessage(Component.literal(es() ? "§7Mira hacia arriba de la escalera..." : "§7Look up the stairs..."), true);
        return 1;
    }

    @SubscribeEvent
    public void commands(RegisterCommandsEvent ev) {
        ev.getDispatcher().register(Commands.literal("twxescalera").requires(s -> s.hasPermission(2)).executes(c -> test(c.getSource().getPlayerOrException())));
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END) return;
        MinecraftServer srv = ServerLifecycleHooks.getCurrentServer();
        if (srv == null) return;
        long now = srv.getTickCount();
        if (nextAt < 0) nextAt = now + SpawnConfig.ticks("escalera", 1200, 6000, RND);
        if (cur == null) {
            if (now < nextAt || now % 20 != 0) return;
            for (StalkerEntity e : StalkerEntity.ACTIVE) if (e.isAlive() && (e.isFieldChasing() || e.isScripted())) {
                nextAt = now + 100;
                return;
            }
            java.util.List<ServerPlayer> ps = new java.util.ArrayList<>();
            for (ServerPlayer p : srv.getPlayerList().getPlayers())
                if (p.isAlive() && !p.isSpectator() && !SuenoEvent.isDreaming(p) && p.level().dimension() == net.minecraft.world.level.Level.OVERWORLD && !BedHide.isHidden(p.getUUID()) && SpawnHelper.inMine(p)) ps.add(p);
            if (ps.isEmpty()) {
                nextAt = now + RETRY;
                return;
            }
            ServerPlayer p = ps.get(RND.nextInt(ps.size()));
            if (start(p, find(p, false))) nextAt = now + 999999;            // se reprograma al terminar
            else nextAt = now + RETRY;
            return;
        }
        V v = cur;
        if (v.e == null || !v.e.isAlive()) {
            end(true, now);
            return;
        }
        ServerPlayer p = srv.getPlayerList().getPlayer(v.pid);
        if (p == null || p.level() != v.level || !p.isAlive()) {
            end(true, now);
            return;
        }
        v.t++;
        double dist = Math.sqrt(Math.pow(p.getX() - v.p.x, 2) + Math.pow(p.getY() + 1 - (v.p.y + FACE), 2) + Math.pow(p.getZ() - v.p.z, 2));
        if (v.up >= 0) {
            if (v.t - v.up >= UP_T) end(false, now);
            return;
        }
        if (v.shake) {
            v.pt++;
            if (v.pt == 1 || v.pt == 10 || v.pt == 19) {
                SoundEvent s = BedHide.se("stalker.run_crack_" + (1 + v.pt / 9));
                if (s != null) v.level.playSound(null, v.p.x, v.p.y, v.p.z, s, SoundSource.HOSTILE, 3f, .85f);
            }
            if (v.pt >= SHAKE) transform(v, p, now);
            return;
        }
        if (dist > 64) {
            end(true, now);
            return;
        }
        if (v.t < READY) return;
        if (looking(p, v)) v.looked++;
        else v.looked = Math.max(0, v.looked - 2);
        if (v.looked >= LOOK_NEED || dist <= NEAR) {
            v.shake = true;
            v.pt = 0;
            v.e.setProp("fase", 1);
            return;
        }
        if (v.t >= MAX_T) {
            v.up = v.t;
            v.e.setProp("fase", 2);
        }
    }

    private static void transform(V v, ServerPlayer p, long now) {
        Vec3 pos = new Vec3(v.tx + .5, v.ty, v.tz + .5);
        int h = v.h;
        end(false, now);
        for (StalkerEntity e : StalkerEntity.ACTIVE) if (e.isAlive() && e.isFieldChasing()) return;
        StalkerEntity r = SpawnHelper.spawnChaseAtE(p, "stalker_run", pos, true);
        if (r != null && h < 4) r.setProp("chico", 1);
        Net.title(p, "RUN", 50);
    }
}
