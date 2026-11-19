package com.twixxel.stalker.event;

import com.twixxel.stalker.entity.StalkerEntity;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.Random;

/**
 * EL ENVUELTO (SE ACERCA): afuera, a cielo abierto, de pie a ~30 bloques mirandote. Cada vez que dejas de mirarlo 2 s aparece
 * 5 bloques mas cerca. A 12 bloques abre los ojos. A 3 bloques se lanza, grita y te mata. Si lo miras fijo 5 s se desvanece
 * (oscuridad 4 s). Si no, se va al minuto. Cada 25 a 60 min. Prueba: huevo o /twxenvuelto.
 */
public final class EnvueltoAcerca {
    private static final String ID = "stalker_envuelto_acerca";
    private static final int STEP = 5, START = 30, OPEN = 12, CATCH = 3, AWAY = 40, STARE = 100, LIFE = 1200, DARK = 80;
    private static final Random RND = new Random();

    private static StalkerEntity e;
    private static ServerLevel level;
    private static java.util.UUID pid;
    private static Vec3 pos;
    private static int t, away, stare, breathAt;
    private static long next = -1;

    private static boolean es() {
        return Lang.es();
    }

    private static boolean seen(ServerPlayer p, Vec3 face) {
        Vec3 eye = p.getEyePosition(), to = face.subtract(eye);
        double d = to.length();
        if (d > 80 || d < .1) return false;
        if (p.getLookAngle().dot(to) / d < .95) return false;
        return level.clip(new ClipContext(eye, face, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, p)).getType() == HitResult.Type.MISS;
    }

    private static Double ground(ServerLevel l, double x, double z, double y0) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        for (int y = (int) y0 + 6; y >= (int) y0 - 8; y--) {
            BlockPos b = new BlockPos(bx, y, bz);
            if (!l.isLoaded(b)) return null;
            if (!l.getBlockState(b.below()).getCollisionShape(l, b.below()).isEmpty() && l.getBlockState(b).getCollisionShape(l, b).isEmpty()
                    && l.getBlockState(b.above()).getCollisionShape(l, b.above()).isEmpty() && l.getBlockState(b.above(2)).getCollisionShape(l, b.above(2)).isEmpty()
                    && l.getBlockState(b.below()).getFluidState().isEmpty()) {
                return (double) y;
            }
        }
        return null;
    }

    private static void face(Vec3 to) {
        float yaw = (float) (Math.atan2(-(to.x - pos.x), to.z - pos.z) * 180 / Math.PI);
        e.moveTo(pos.x, pos.y, pos.z, yaw, 0);
        e.setYHeadRot(yaw);
        e.yBodyRot = yaw;
    }

    private static boolean start(ServerPlayer p) {
        if (e != null) return false;
        ServerLevel l = p.serverLevel();
        Vec3 v = p.getLookAngle();
        double vl = Math.max(1e-4, Math.hypot(v.x, v.z)), fx = v.x / vl, fz = v.z / vl;
        for (double da : new double[]{0, .3, -.3, .6, -.6, .9, -.9}) {
            double a = Math.atan2(fz, fx) + da;
            double x = Math.floor(p.getX() + Math.cos(a) * START) + .5, z = Math.floor(p.getZ() + Math.sin(a) * START) + .5;
            Double gy = ground(l, x, z, p.getY());
            if (gy == null || !l.canSeeSky(new BlockPos((int) x, gy.intValue() + 1, (int) z))) continue;
            Vec3 at = new Vec3(x, gy, z);
            StalkerEntity s = SpawnHelper.createScripted(l, ID, at, 0);
            if (s == null) return false;
            s.noCulling = true;
            e = s;
            level = l;
            pid = p.getUUID();
            pos = at;
            t = 0;
            away = 0;
            stare = 0;
            breathAt = 0;
            s.setProp("cara", 0);
            face(p.position());
            return true;
        }
        return false;
    }

    private static void end(long now, boolean vanish) {
        if (e != null && e.isAlive()) e.discard();
        e = null;
        next = now + 30000 + (long) (RND.nextDouble() * 42000);
    }

    private static int test(ServerPlayer p) {
        if (e != null || !start(p)) {
            p.displayClientMessage(Component.literal(es() ? "§4No hay un lugar a cielo abierto" : "§4No open-sky spot"), true);
            return 0;
        }
        return 1;
    }

    @SubscribeEvent
    public void onJoin(EntityJoinLevelEvent ev) {
        if (ev.getLevel().isClientSide || !(ev.getEntity() instanceof StalkerEntity s) || !s.getDefId().equals(ID) || s.isScripted()) return;
        ev.setCanceled(true);
        if (ev.loadedFromDisk() || !(ev.getLevel() instanceof ServerLevel sl)) return;
        ServerPlayer near = null;
        double bd = 1e18;
        for (ServerPlayer q : sl.players()) {
            double d = q.distanceToSqr(s.position());
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

    @SubscribeEvent
    public void commands(RegisterCommandsEvent ev) {
        ev.getDispatcher().register(Commands.literal("twxenvuelto").requires(s -> s.hasPermission(2)).executes(c -> test(c.getSource().getPlayerOrException())));
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END) return;
        MinecraftServer srv = ServerLifecycleHooks.getCurrentServer();
        if (srv == null) return;
        long now = srv.getTickCount();
        if (next < 0) next = now + 30000 + (long) (RND.nextDouble() * 42000);
        if (e == null) {
            if (now < next || now % 20 != 0) return;
            for (StalkerEntity s : StalkerEntity.ACTIVE) if (s.isAlive() && (s.isFieldChasing() || s.isScripted())) {
                next = now + 200;
                return;
            }
            java.util.List<ServerPlayer> ps = new java.util.ArrayList<>();
            for (ServerPlayer p : srv.getPlayerList().getPlayers())
                if (p.isAlive() && !p.isSpectator() && !SuenoEvent.isDreaming(p) && p.level().dimension() == net.minecraft.world.level.Level.OVERWORLD && p.serverLevel().canSeeSky(p.blockPosition()) && !BedHide.isHidden(p.getUUID())) ps.add(p);
            if (ps.isEmpty() || !start(ps.get(RND.nextInt(ps.size())))) next = now + 600;
            return;
        }
        ServerPlayer p = srv.getPlayerList().getPlayer(pid);
        if (!e.isAlive() || p == null || p.level() != level || !p.isAlive()) {
            end(now, false);
            return;
        }
        t++;
        Vec3 f = new Vec3(pos.x, pos.y + 3.0, pos.z);
        double dist = Math.hypot(p.getX() - pos.x, p.getZ() - pos.z);
        if (t >= LIFE) {
            end(now, false);
            return;
        }
        if (t >= breathAt) {
            SoundEvent s = BedHide.se("stalker.js_luz_respira");
            if (s != null) level.playSound(null, pos.x, pos.y, pos.z, s, SoundSource.HOSTILE, 1.2f, .8f);
            breathAt = t + 80;
        }
        if (dist <= CATCH) {                                  // se lanza, grita y te mata
            e.setProp("cara", 1);
            face(p.position());
            JumpScare.kill(p, e);
            end(now, false);
            return;
        }
        boolean lk = seen(p, f);
        if (lk) {
            away = 0;
            stare++;
            if (stare >= STARE) {                             // lo miraste fijo: se desvanece
                p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, DARK, 0, false, false));
                p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, DARK, 0, false, false));
                end(now, true);
                return;
            }
        } else {
            stare = Math.max(0, stare - 2);
            away++;
            if (away >= AWAY) {                               // se acerca 5 bloques
                away = 0;
                Vec3 d = new Vec3(p.getX() - pos.x, 0, p.getZ() - pos.z);
                double l = Math.max(1e-4, d.length());
                double nx = pos.x + d.x / l * STEP, nz = pos.z + d.z / l * STEP;
                Double gy = ground(level, nx, nz, pos.y);
                if (gy != null) pos = new Vec3(nx, gy, nz);
                if (Math.hypot(p.getX() - pos.x, p.getZ() - pos.z) <= OPEN) e.setProp("cara", 1);
                face(p.position());
            }
        }
        if (t % 10 == 0) face(p.position());
    }
}
