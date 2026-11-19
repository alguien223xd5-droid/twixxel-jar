package com.twixxel.stalker.event;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.twixxel.stalker.entity.StalkerEntity;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.Random;
import java.util.UUID;

/**
 * ASOMANDOME (stalker_asomandome): en cuevas y tuneles se esconde tras una esquina. Cuando miras hacia ella (o te acercas a 5 bloques)
 * suena su audio (20 s) y se asoma 13 s: inclina el cuerpo, la cabeza ladeada, estira el brazo largo hasta el piso y clava las garras,
 * con tirones de cabeza. Mientras tanto no te puedes acercar a menos de 2 bloques (te empuja). Al acabar se mete tras la esquina
 * y se transforma en el RUN que te persigue. Si no lo miras en 1 min, se va. Cada 10 min. Prueba: /twxasomandome
 */
public final class AsomandomeEvent {
    private static final String ID = "stalker_asomandome";
    private static final double R_MIN = 4.5, R_MAX = 16, BEHIND = .95, B = .28, HEAD_LAT = .85, HEAD_UP = 2.2, LOOK = .93, NEAR = 5, LOCK = 2;
    private static final double[] REACH = {2.9, 2.5};
    private static final int WAIT = 1200, ANIM = 264, RET = 28, RETRY = 1200, EVERY = 12000;
    private static final int[] CRACKS = {66, 144, 229, 255};
    private static final Random RND = new Random();

    private static final class Sp {
        Vec3 e, w, h, o, x, head;
        double rx, rz, fx, fz, score;
        int s, lvl;
    }

    private static final class A {
        StalkerEntity ent;
        ServerLevel level;
        Sp sp;
        boolean anim, ret;
        int t, pt;
    }

    private static A cur;
    private static long nextAt = -1;

    private static boolean es() {
        return Lang.es();
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

    private static boolean sight(ServerLevel l, Vec3 a, Vec3 b, ServerPlayer p) {
        return l.clip(new ClipContext(a, b, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, p)).getType() == HitResult.Type.MISS;
    }

    private static float yawTo(Vec3 a, Vec3 b) {
        return (float) (Math.atan2(-(b.x - a.x), b.z - a.z) * 180 / Math.PI);
    }

    private static boolean inCave(ServerPlayer p) {
        ServerLevel l = p.serverLevel();
        int y = (int) Math.floor(p.getY());
        if (l.canSeeSky(p.blockPosition())) return false;
        int solid = 0;
        for (int dy = 2; dy <= 12; dy++) if (!fr(l, p.getX(), y + dy, p.getZ())) solid++;
        return solid >= 3;
    }

    private static double freeDist(ServerLevel l, Vec3 o, double dx, double dz, double y0) {
        for (double d = .1; d <= 5; d += .1)
            for (double h : new double[]{.1, 1.0, 1.9}) if (!fr(l, o.x + dx * d, y0 + h, o.z + dz * d)) return d;
        return 5;
    }

    private static Sp tryCell(ServerLevel l, ServerPlayer p, Vec3 e, Vec3 w, double d) {
        double fx = w.x - e.x, fz = w.z - e.z, fl = Math.hypot(fx, fz);
        if (fl < 1e-4) fl = 1;
        fx /= fl;
        fz /= fl;
        double rx = -fz, rz = fx, y0 = w.y;
        Vec3 h = new Vec3(w.x + fx * BEHIND, y0, w.z + fz * BEHIND);
        if (fr(l, h.x, y0 - 1, h.z)) return null;
        for (int k = 0; k < 3; k++) if (!fr(l, h.x, y0 + k, h.z)) return null;
        double edge = .5 * (fl + BEHIND) / Math.max(1, fl - .5), slide = edge - B;
        int[] sides = RND.nextBoolean() ? new int[]{1, -1} : new int[]{-1, 1};
        for (int s : sides) {
            boolean ok = true;
            for (int k = 0; k < 3; k++) if (!fr(l, w.x + rx * s, y0 + k, w.z + rz * s)) ok = false;
            if (!ok) continue;
            Vec3 o = new Vec3(h.x + rx * s * slide, y0, h.z + rz * s * slide);
            for (int k = 0; k < 3; k++) if (!fr(l, o.x, y0 + k, o.z)) ok = false;
            if (!ok) continue;
            for (double hh : new double[]{.3, .9, 1.4}) if (sight(l, e, new Vec3(o.x, y0 + hh, o.z), p)) ok = false;   // el cuerpo no se ve
            if (!ok) continue;
            Vec3 head = new Vec3(o.x + rx * s * HEAD_LAT, y0 + HEAD_UP, o.z + rz * s * HEAD_LAT);
            if (!fr(l, head.x, head.y, head.z) || !sight(l, e, head, p)) continue;
            double dd = freeDist(l, o, rx * s, rz * s, y0);
            int lvl = -1;
            for (int i = 0; i < REACH.length; i++) if (REACH[i] <= dd - .2) {
                lvl = i;
                break;
            }
            if (lvl < 0) continue;
            Vec3 x = new Vec3(h.x + rx * s * 1.25, y0, h.z + rz * s * 1.25);
            if (fr(l, x.x, y0 - 1, x.z) || !fr(l, x.x, y0, x.z) || !fr(l, x.x, y0 + 1, x.z)) continue;
            Sp sp = new Sp();
            sp.e = e;
            sp.w = w;
            sp.h = h;
            sp.o = o;
            sp.x = x;
            sp.head = head;
            sp.rx = rx;
            sp.rz = rz;
            sp.fx = fx;
            sp.fz = fz;
            sp.s = s;
            sp.lvl = lvl;
            sp.score = Math.abs(d - 7) * .5 + RND.nextDouble() * 1.5 + lvl * .4;
            return sp;
        }
        return null;
    }

    private static Sp find(ServerPlayer p, Vec3 center, boolean loose) {
        ServerLevel l = p.serverLevel();
        if (!loose && !inCave(p)) return null;
        Vec3 e = p.getEyePosition();
        Vec3 view = p.getLookAngle();
        double vl = Math.max(1e-4, Math.hypot(view.x, view.z));
        Sp best = null;
        int r = (int) Math.ceil(R_MAX), px = (int) Math.floor(p.getX()), pz = (int) Math.floor(p.getZ()), py = (int) Math.floor(p.getY());
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double cx = px + dx + .5, cz = pz + dz + .5, d = Math.hypot(cx - e.x, cz - e.z);
                if (d < R_MIN || d > R_MAX) continue;
                for (int dy : new int[]{0, -1, 1}) {
                    double y0 = py + dy;
                    if (!cov(l, cx, y0, cz) || !cov(l, cx, y0 + 1, cz) || !cov(l, cx, y0 + 2, cz)) continue;
                    Sp sp = tryCell(l, p, e, new Vec3(cx, y0, cz), d);
                    if (sp == null) continue;
                    sp.score -= (sp.fx * view.x / vl + sp.fz * view.z / vl) * 2;
                    if (best == null || sp.score < best.score) best = sp;
                    break;
                }
            }
        }
        return best;
    }

    private static boolean start(ServerPlayer p, Sp sp) {
        if (cur != null || sp == null) return false;
        ServerLevel l = p.serverLevel();
        float yaw = yawTo(sp.h, sp.e) + (sp.s < 0 ? 180 : 0);
        StalkerEntity e = SpawnHelper.createScripted(l, ID, sp.o, yaw);
        if (e == null) return false;
        e.noCulling = true;
        e.setProp("brazo", sp.lvl);
        e.setProp("lado", sp.s < 0 ? 1 : 0);
        e.setProp("fase", 0);
        A a = new A();
        a.ent = e;
        a.level = l;
        a.sp = sp;
        cur = a;
        return true;
    }

    private static void stopSound(A a) {
        var r = seId("stalker.asomandome");
        if (r == null) return;
        for (ServerPlayer p : a.level.players()) p.connection.send(new ClientboundStopSoundPacket(r, SoundSource.HOSTILE));
    }

    private static ResourceLocation seId(String id) {
        var ro = com.twixxel.stalker.TwixxelMod.SOUND_EVENTS.get(id);
        return ro == null ? null : ro.getId();
    }

    private static void end(boolean retry, long now) {
        A a = cur;
        cur = null;
        nextAt = now + (retry ? RETRY : SpawnConfig.ticks("asomandome", EVERY, EVERY, new java.util.Random()));
        if (a != null && a.ent != null && a.ent.isAlive()) a.ent.discard();
    }

    private static void sound(A a, String id, Vec3 at, float vol, float pitch) {
        SoundEvent s = BedHide.se(id);
        if (s != null) a.level.playSound(null, at.x, at.y, at.z, s, SoundSource.HOSTILE, vol, pitch);
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
        if (near == null) return;
        final ServerPlayer p = near;
        sl.getServer().execute(() -> {
            if (cur != null) return;
            Sp sp = find(p, null, true);
            if (sp == null) p.displayClientMessage(Component.literal(es() ? "§4No hay ninguna esquina de cueva o túnel cerca" : "§4No cave or tunnel corner nearby"), true);
            else start(p, sp);
        });
    }

    @SubscribeEvent
    public void commands(RegisterCommandsEvent ev) {
        ev.getDispatcher().register(Commands.literal("twxasomandome").requires(s -> s.hasPermission(2)).executes(c -> {
            ServerPlayer p = c.getSource().getPlayerOrException();
            Sp sp = find(p, null, true);
            if (sp == null) {
                p.displayClientMessage(Component.literal(es() ? "§4No hay ninguna esquina cerca" : "§4No corner nearby"), true);
                return 0;
            }
            return start(p, sp) ? 1 : 0;
        }));
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END) return;
        MinecraftServer srv = ServerLifecycleHooks.getCurrentServer();
        if (srv == null) return;
        long now = srv.getTickCount();
        if (nextAt < 0) nextAt = now + SpawnConfig.ticks("asomandome", EVERY, EVERY, new java.util.Random());
        if (cur == null) {
            if (now < nextAt || now % 40 != 0) return;
            for (StalkerEntity e : StalkerEntity.ACTIVE) if (e.isAlive() && (e.isFieldChasing() || e.isScripted())) {
                nextAt = now + RETRY;
                return;
            }
            java.util.List<ServerPlayer> ps = new java.util.ArrayList<>(srv.getPlayerList().getPlayers());
            java.util.Collections.shuffle(ps);
            for (ServerPlayer p : ps) {
                if (!p.isAlive() || p.isSpectator() || BedHide.isHidden(p.getUUID())) continue;
                if (start(p, find(p, null, false))) {
                    nextAt = now + SpawnConfig.ticks("asomandome", EVERY, EVERY, new java.util.Random());
                    return;
                }
            }
            nextAt = now + RETRY;
            return;
        }
        A a = cur;
        a.t++;
        if (a.ent == null || !a.ent.isAlive()) {
            end(false, now);
            return;
        }
        Sp sp = a.sp;
        if (!a.anim) {
            ServerPlayer who = null;
            double nd = 1e18;
            for (ServerPlayer p : a.level.players()) {
                if (!p.isAlive() || p.isSpectator()) continue;
                double hd = Math.hypot(p.getX() - sp.w.x, p.getZ() - sp.w.z);
                nd = Math.min(nd, hd);
                if (hd <= NEAR) {
                    who = p;
                    break;
                }
                if (a.t % 2 == 0) {
                    Vec3 to = sp.head.subtract(p.getEyePosition());
                    double d = to.length();
                    if (d < 60 && p.getLookAngle().dot(to) / d >= LOOK && sight(a.level, p.getEyePosition(), sp.head, p)) {
                        who = p;
                        break;
                    }
                }
            }
            if (who != null) {
                a.anim = true;
                a.pt = 0;
                a.ent.setProp("fase", 1);
                sound(a, "stalker.asomandome", sp.head, 1.6f, 1f);
                return;
            }
            if (a.t >= WAIT || nd > 64) end(true, now);
            return;
        }
        a.pt++;
        if (!a.ret) {
            for (int i = 0; i < CRACKS.length; i++)
                if (a.pt == CRACKS[i]) sound(a, "stalker.run_crack_" + (i % 3 + 1), sp.head, 1.3f, .9f + RND.nextFloat() * .15f);
            for (ServerPlayer p : a.level.players()) {                  // no te puedes acercar a menos de 2 bloques
                if (!p.isAlive() || p.isSpectator()) continue;
                double dx = p.getX() - a.ent.getX(), dz = p.getZ() - a.ent.getZ(), d = Math.hypot(dx, dz);
                if (d < LOCK && d > 1e-3) {
                    Vec3 push = new Vec3(dx / d * .45, .1, dz / d * .45);
                    p.setDeltaMovement(push);
                    p.hurtMarked = true;
                    p.connection.send(new ClientboundSetEntityMotionPacket(p));
                }
            }
            if (a.pt >= ANIM) {
                a.ret = true;
                a.pt = 0;
                a.ent.setProp("fase", 2);
            }
            return;
        }
        if (a.pt < RET) return;
        // se mete tras la esquina, se va y se vuelve el RUN
        ServerPlayer target = null;
        double bd = 1e18;
        for (ServerPlayer p : a.level.players()) {
            if (!p.isAlive() || p.isSpectator()) continue;
            double d = p.distanceToSqr(sp.x);
            if (d < bd) {
                bd = d;
                target = p;
            }
        }
        stopSound(a);
        Vec3 pos = sp.x;
        end(false, now);
        if (target == null) return;
        for (StalkerEntity e : StalkerEntity.ACTIVE) if (e.isAlive() && e.isFieldChasing()) return;
        SpawnHelper.spawnChaseAtE(target, "stalker_run", pos, true);
        SoundEvent s = BedHide.se("stalker.run_crack_3");
        if (s != null) target.serverLevel().playSound(null, pos.x, pos.y + 1, pos.z, s, SoundSource.HOSTILE, 2f, .85f);
    }
}
