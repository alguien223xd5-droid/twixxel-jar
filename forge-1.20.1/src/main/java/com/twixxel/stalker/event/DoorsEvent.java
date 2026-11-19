package com.twixxel.stalker.event;

import com.twixxel.stalker.TwixxelMod;
import com.twixxel.stalker.entity.StalkerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.Random;
import java.util.UUID;

/**
 * PUERTAS (stalker_puertas): una cara aparece en una ventana de 1x1 cerca de una puerta doble. Te mira; luego abre las puertas,
 * una mano entra a agarrarte (si te alejas se esconde), la cara cruje, el cuerpo camina de la ventana a la puerta, entra y se
 * transforma en RUN que te persigue.
 */
public final class DoorsEvent {
    private static final int WATCH = 80, GRAB = 200, LEAVE = 20, CRACK_END = 74, DUCK = 12, WALK_MAX = 110, ENTER_END = 54, REACH = 80;
    private static final double SPEED = .09, GAZE_COS = .95, VANISH = 3, ARM_IN = .75, ARM_OUT = -1.9;

    private static final class S {
        UUID pid;
        ServerLevel level;
        BlockPos glass, d1, d2;
        double wox, woz;      // salida de la ventana (hacia afuera)
        double dox, doz;      // salida de la puerta
        Vec3 doorC, winC;
        String phase = "face";
        int t, gaze, repeat = -1;
        boolean scary;
        StalkerEntity face, hand, body;
        float fy, fp;
    }

    private static S s;
    private static long next = -1;
    private static final Random RND = new Random();

    private static boolean es() {
        return com.twixxel.stalker.event.Lang.es();
    }

    private static void snd(ServerPlayer p, String ev, float vol) {
        var se = TwixxelMod.SOUND_EVENTS.get(TwixxelMod.loc(ev));
        if (se != null) p.playNotifySound(se.get(), SoundSource.MASTER, vol, 1f);
    }

    private static float yawOf(double ox, double oz) {
        return (float) (Math.atan2(ox, -oz) * 180 / Math.PI);      // mira hacia adentro (opuesto a la salida)
    }

    private static void place(StalkerEntity e, Vec3 v, float yaw) {
        if (e == null || !e.isAlive()) return;
        e.setPos(v.x, v.y, v.z);
        e.setYRot(yaw);
        e.setYHeadRot(yaw);
        e.setYBodyRot(yaw);
    }

    public static boolean startFor(ServerPlayer p, boolean force) {
        if (s != null) return false;
        ServerLevel l = p.serverLevel();
        BlockPos pb = p.blockPosition();
        // puerta doble: dos puertas inferiores adyacentes
        BlockPos a = null, b = null;
        double bd = 1e9;
        for (int dx = -18; dx <= 18; dx++) for (int dz = -18; dz <= 18; dz++) for (int dy = -3; dy <= 3; dy++) {
            BlockPos q = pb.offset(dx, dy, dz);
            if (!Houses.isLowerDoor(l, q)) continue;
            for (int[] o : new int[][]{{1, 0}, {0, 1}}) {
                BlockPos r = q.offset(o[0], 0, o[1]);
                if (!Houses.isLowerDoor(l, r)) continue;
                double d = Math.hypot(q.getX() - p.getX(), q.getZ() - p.getZ());
                if (d < bd) {
                    bd = d;
                    a = q;
                    b = r;
                }
            }
        }
        if (a == null) return false;
        Vec3 dc = new Vec3((a.getX() + b.getX()) / 2.0 + .5, a.getY(), (a.getZ() + b.getZ()) / 2.0 + .5);
        var facing = l.getBlockState(a).getValue(DoorBlock.FACING);
        double nx = facing.getStepX(), nz = facing.getStepZ();
        double side = (p.getX() - dc.x) * nx + (p.getZ() - dc.z) * nz;
        double dox = side >= 0 ? -nx : nx, doz = side >= 0 ? -nz : nz;
        // ventana 1x1: vidrio con pared a ambos lados y libre delante y detras
        BlockPos g = null;
        double gd = 1e9;
        for (int dx = -7; dx <= 7; dx++) for (int dz = -7; dz <= 7; dz++) for (int dy = -1; dy <= 3; dy++) {
            BlockPos q = a.offset(dx, dy, dz);
            if (!Houses.isGlass(l, q)) continue;
            boolean ok = false;
            for (int[] o : new int[][]{{1, 0}, {0, 1}}) {
                boolean wall = Houses.cover(l, q.offset(o[0], 0, o[1])) && Houses.cover(l, q.offset(-o[0], 0, -o[1]));
                BlockPos in = q.offset(o[1], 0, o[0]), out = q.offset(-o[1], 0, -o[0]);
                if (wall && Houses.free(l, in) && Houses.free(l, out)) ok = true;
            }
            if (!ok || Houses.isGlass(l, q.above()) || Houses.isGlass(l, q.below())) continue;
            double d = Math.hypot(q.getX() + .5 - dc.x, q.getZ() + .5 - dc.z);
            if (d < gd) {
                gd = d;
                g = q;
            }
        }
        if (g == null) return false;
        double wnx, wnz;
        boolean wallX = Houses.cover(l, g.east()) && Houses.cover(l, g.west());
        wnx = wallX ? 0 : 1;
        wnz = wallX ? 1 : 0;
        double ws = (p.getX() - (g.getX() + .5)) * wnx + (p.getZ() - (g.getZ() + .5)) * wnz;
        S n = new S();
        n.pid = p.getUUID();
        n.level = l;
        n.glass = g;
        n.d1 = a;
        n.d2 = b;
        n.wox = ws >= 0 ? -wnx : wnx;
        n.woz = ws >= 0 ? -wnz : wnz;
        n.dox = dox;
        n.doz = doz;
        n.doorC = dc;
        n.winC = new Vec3(g.getX() + .5, g.getY(), g.getZ() + .5);
        Vec3 pos = new Vec3(n.winC.x + n.wox * .1, n.winC.y - .95, n.winC.z + n.woz * .1);
        n.face = SpawnHelper.createScripted(l, "stalker_puertas", pos, yawOf(n.wox, n.woz));
        if (n.face == null) return false;
        n.face.noCulling = true;
        s = n;
        return true;
    }

    public static boolean command(ServerPlayer p) {
        if (startFor(p, true)) return true;
        p.displayClientMessage(Component.literal(es() ? "§cNecesitas una puerta doble y una ventana 1x1 cerca" : "§cYou need a double door and a 1x1 window nearby"), true);
        return false;
    }

    private static void end(long now, boolean sound) {
        S x = s;
        s = null;
        next = now + SpawnConfig.ticks("puertas", 12000, 18000, RND);
        if (x == null) return;
        for (StalkerEntity e : new StalkerEntity[]{x.face, x.hand, x.body}) if (e != null && e.isAlive()) e.discard();
        if (sound) for (ServerPlayer q : x.level.players()) if (q.distanceToSqr(x.winC) < 40 * 40) snd(q, "stalker.peeker_vanish", 1f);
    }

    private static void doors(S x, boolean open) {
        Houses.setDoor(x.level, x.d1, open);
        Houses.setDoor(x.level, x.d2, open);
        x.level.playSound(null, x.d1, open ? SoundEvents.WOODEN_DOOR_OPEN : SoundEvents.WOODEN_DOOR_CLOSE, SoundSource.BLOCKS, 1f, open ? .6f : .5f);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        long now = server.getTickCount();
        if (s == null) {
            if (now % 20 != 0 || !com.twixxel.stalker.Config.NATURAL_SPAWNS.get()) return;
            if (next < 0) next = now + SpawnConfig.ticks("puertas", 12000, 18000, RND);
            if (now < next) return;
            if (!StalkerEntity.ACTIVE.isEmpty()) {
                next = now + 100;
                return;
            }
            var ps = new java.util.ArrayList<>(server.getPlayerList().getPlayers());
            java.util.Collections.shuffle(ps, RND);
            for (ServerPlayer p : ps) if (p.isAlive() && !p.isSpectator() && !SuenoEvent.isDreaming(p) && startFor(p, false)) return;
            next = now + 100;
            return;
        }
        S x = s;
        ServerPlayer p = server.getPlayerList().getPlayer(x.pid);
        if (p == null || !p.isAlive() || p.level() != x.level) {
            end(now, false);
            return;
        }
        x.t++;
        Vec3 faceBase = new Vec3(x.winC.x + x.wox * .1, x.winC.y, x.winC.z + x.woz * .1);
        float baseYaw = yawOf(x.wox, x.woz);
        boolean faceUp = x.face != null && x.face.isAlive();
        if (faceUp) {
            double rise = Math.min(1.0, x.t / 20.0);
            if (!x.phase.equals("duck") && !x.phase.equals("walk") && !x.phase.equals("enter")) {
                place(x.face, new Vec3(faceBase.x, faceBase.y - .95 * (1 - rise), faceBase.z), baseYaw);
                Vec3 eye = p.getEyePosition(), fc = new Vec3(faceBase.x, faceBase.y + .3, faceBase.z), v = eye.subtract(fc);
                float ty = Mth.wrapDegrees((float) (Math.atan2(-v.x, v.z) * 180 / Math.PI) - baseYaw);
                float tp = (float) -(Math.atan2(v.y, Math.hypot(v.x, v.z)) * 180 / Math.PI);
                ty = Mth.clamp(ty, -60, 60);
                tp = Mth.clamp(tp, -45, 45);
                x.fy += (ty - x.fy) * .35f;
                x.fp += (tp - x.fp) * .35f;
                x.face.setProp("yaw", x.fy);
                x.face.setProp("pitch", x.fp);
            }
            // mirada: suena el susto; acercarse a 3 bloques lo hace desaparecer
            Vec3 fc2 = new Vec3(faceBase.x, faceBase.y + .6, faceBase.z);
            for (ServerPlayer q : x.level.players()) {
                if (q.distanceToSqr(fc2) <= VANISH * VANISH && (x.phase.equals("face") || x.phase.equals("grab"))) {
                    end(now, true);
                    return;
                }
            }
            Vec3 eye = p.getEyePosition(), v = fc2.subtract(eye);
            double dist = v.length();
            boolean look = dist > .1 && dist < 40 && p.getLookAngle().dot(v) / dist >= GAZE_COS;
            x.gaze = look ? x.gaze + 1 : 0;
            if (x.gaze >= 6 && (!x.scary || (x.repeat >= 0 && x.t - x.repeat >= 193))) {
                x.scary = true;
                x.repeat = x.t;
                snd(p, "stalker.window_scary", 1f);
            }
        }
        switch (x.phase) {
            case "face":
                if (x.t >= WATCH) {
                    doors(x, true);
                    x.hand = SpawnHelper.createScripted(x.level, "stalker_puertas_mano", x.doorC, yawOf(x.dox, x.doz));
                    if (x.hand == null) {
                        end(now, false);
                        return;
                    }
                    x.hand.noCulling = true;
                    x.hand.setProp("lado", RND.nextInt(2));
                    x.hand.setProp("quieta", 0);
                    x.phase = "reach";
                    x.t = 0;
                }
                return;
            case "reach": {
                double k = Math.min(1.0, x.t / (double) REACH);
                k = k * k * (3 - 2 * k);
                double off = ARM_OUT + (ARM_IN - ARM_OUT) * k;
                place(x.hand, new Vec3(x.doorC.x - x.dox * off, x.doorC.y, x.doorC.z - x.doz * off), yawOf(x.dox, x.doz));
                if (x.t >= REACH) {
                    x.hand.setProp("quieta", 1);
                    x.phase = "grab";
                    x.t = 0;
                }
                return;
            }
            case "grab": {
                if (Math.hypot(p.getX() - x.doorC.x, p.getZ() - x.doorC.z) >= 10) {
                    x.phase = "leave";
                    x.t = 0;
                    return;
                }
                if (x.t >= GRAB) {
                    x.phase = "crack";
                    x.t = 0;
                    snd(p, "stalker.puertas_crack", 1f);
                }
                return;
            }
            case "leave":
                if (x.t >= LEAVE) {
                    doors(x, false);
                    end(now, true);
                }
                return;
            case "crack": {
                float c = x.t <= 14 ? -50 + 100f * x.t / 14f : 50 - 50f * Math.min(1f, (x.t - 14) / 20f);
                if (faceUp) x.face.setProp("cuello", c);
                if (x.t >= CRACK_END) {
                    x.phase = "duck";
                    x.t = 0;
                }
                return;
            }
            case "duck":
                if (faceUp) place(x.face, new Vec3(faceBase.x, faceBase.y - .95 * Math.min(1.0, x.t / (double) DUCK), faceBase.z), baseYaw);
                if (x.t >= DUCK) {
                    if (faceUp) x.face.discard();
                    if (x.hand != null && x.hand.isAlive()) x.hand.discard();
                    Vec3 st = new Vec3(x.winC.x + x.wox * 1.3, x.winC.y - .5, x.winC.z + x.woz * 1.3);
                    x.body = SpawnHelper.createScripted(x.level, "stalker_puertas_cuerpo", st, yawOf(x.wox, x.woz));
                    if (x.body == null) {
                        doors(x, false);
                        end(now, false);
                        return;
                    }
                    x.body.noCulling = true;
                    x.body.setProp("fase", 0);
                    x.phase = "walk";
                    x.t = 0;
                }
                return;
            case "walk": {
                Vec3 tg = new Vec3(x.doorC.x + x.dox * 1.6, x.winC.y - .5, x.doorC.z + x.doz * 1.6);
                Vec3 cur = x.body.position(), d = tg.subtract(cur);
                double h = Math.hypot(d.x, d.z);
                if (h <= SPEED * 1.5 || x.t >= WALK_MAX) {
                    x.body.setProp("fase", 1);
                    x.phase = "enter";
                    x.t = 0;
                    return;
                }
                Vec3 nx = cur.add(d.x / h * SPEED, 0, d.z / h * SPEED);
                place(x.body, new Vec3(nx.x, tg.y, nx.z), (float) (Math.atan2(-d.x, d.z) * 180 / Math.PI));
                return;
            }
            case "enter": {
                double k = Math.min(1.0, x.t / (double) ENTER_END);
                double off = -1.6 + 2.4 * k;
                place(x.body, new Vec3(x.doorC.x - x.dox * off, x.doorC.y, x.doorC.z - x.doz * off), yawOf(x.dox, x.doz));
                if (x.t == 16) x.level.playSound(null, x.d1, SoundEvents.WOODEN_DOOR_OPEN, SoundSource.BLOCKS, 1f, .5f);
                if (x.t >= ENTER_END) {
                    Vec3 at = x.body.position();
                    for (ServerPlayer q : x.level.players()) if (q.distanceToSqr(at) < 48 * 48) snd(q, "stalker.unknown_scream", 1f);
                    x.body.discard();
                    x.body = null;
                    ServerPlayer v = p;
                    end(now, false);
                    SpawnHelper.spawnChaseAt(v, "stalker_run", at, false);
                }
            }
            default:
        }
    }
}
