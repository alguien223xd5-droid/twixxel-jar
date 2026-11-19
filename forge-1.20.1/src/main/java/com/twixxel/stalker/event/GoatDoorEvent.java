package com.twixxel.stalker.event;

import com.twixxel.stalker.TwixxelMod;
import com.twixxel.stalker.entity.StalkerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.Random;
import java.util.UUID;

/**
 * GOAT BAD (stalker_goat_door): en tu casa, cada 4 a 7 min, si hay una puerta doble 2x2 y una ventana 2x2 cerca:
 * 1) te llama "ven afuera"; 2) si miras la puerta se abre y la cabra esta ahi doblandose los huesos (musica del RUN);
 * 3) va a la ventana, se asoma y cuando la miras la golpea (3 grietas, la 4a la rompe); 4) oscuridad 10 s y se va.
 */
public final class GoatDoorEvent {
    private static final int VOICE_EVERY = 80, CALL_MAX = 2400, GAZE_TICKS = 10, BONES = 80, HIT_FIRST = 8, HIT_EVERY = 18, HITS = 4, DARK = 200, PEEK_MAX = 2400;
    private static final double GAZE_COS = .94, SEE = 24, IN = .7, KEEP_OUT = 2, WIN_GAZE_COS = .93;
    private static final double HEAD_F = (40.6 * .6) / 16, HEAD_U = (82.2 * .6) / 16;

    private static final class G {
        UUID pid;
        ServerLevel level;
        Houses.Win door, win;
        String phase = "call";
        int t, gaze, hits, wgaze;
        StalkerEntity e, crack;
        Vec3 face, wpos;
        float wyaw;
    }

    private static G g;
    private static long next = -1;
    private static final Random RND = new Random();

    private static boolean es() {
        return com.twixxel.stalker.event.Lang.es();
    }

    private static float goatYaw(double dx, double dz) {
        return (float) (Math.atan2(-dx, dz) * 180 / Math.PI - 90);
    }

    private static void snd(ServerPlayer p, String ev, float vol) {
        var se = TwixxelMod.SOUND_EVENTS.get(TwixxelMod.loc(ev));
        if (se != null) p.playNotifySound(se.get(), SoundSource.MASTER, vol, 1f);
    }

    private static void stop(ServerPlayer p, String... evs) {
        for (String ev : evs) for (String v : TwixxelMod.variants(ev))
            p.connection.send(new ClientboundStopSoundPacket(new ResourceLocation(TwixxelMod.MODID, v), SoundSource.MASTER));
    }

    public static boolean startFor(ServerPlayer p, boolean force) {
        if (g != null) return false;
        if (!force && !Houses.isInsideHouse(p)) return false;
        Houses.Win d = Houses.find2x2(p, true);
        if (d == null) return false;
        Houses.Win w = Houses.find2x2(p, false);
        if (w == null) return false;
        G n = new G();
        n.pid = p.getUUID();
        n.level = p.serverLevel();
        n.door = d;
        n.win = w;
        g = n;
        return true;
    }

    public static boolean command(ServerPlayer p) {
        if (startFor(p, true)) return true;
        p.displayClientMessage(Component.literal(es() ? "§cNecesitas una puerta 2x2 y una ventana 2x2 cerca" : "§cYou need a 2x2 door and a 2x2 window nearby"), true);
        return false;
    }

    private static void end(boolean keepMusic, long now) {
        G s = g;
        g = null;
        next = now + SpawnConfig.ticks("goat_door", 4800, 8400, RND);
        if (s == null) return;
        if (s.e != null && s.e.isAlive()) s.e.discard();
        if (s.crack != null && s.crack.isAlive()) s.crack.discard();
        for (ServerPlayer q : s.level.players()) {
            if (!keepMusic) stop(q, "stalker.run", "stalker.window_voice");
            if (q.getUUID().equals(s.pid)) q.displayClientMessage(Component.empty(), true);
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        long now = server.getTickCount();
        if (g == null) {
            if (now % 20 != 0 || !com.twixxel.stalker.Config.NATURAL_SPAWNS.get()) return;
            if (next < 0) next = now + SpawnConfig.ticks("goat_door", 4800, 8400, RND);
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
        G s = g;
        ServerPlayer p = server.getPlayerList().getPlayer(s.pid);
        if (p == null || !p.isAlive() || p.level() != s.level || (s.e != null && !s.e.isAlive())) {
            end(false, now);
            return;
        }
        s.t++;
        Houses.Win d = s.door, w = s.win;
        Vec3 doorSpot = new Vec3(d.cx + d.qx * IN, d.y, d.cz + d.qz * IN);
        switch (s.phase) {
            case "call": {
                if (s.t >= CALL_MAX) {
                    end(false, now);
                    return;
                }
                if (s.t == 1 || s.t % VOICE_EVERY == 0) snd(p, "stalker.window_voice", 1f);
                if (s.t % 10 == 1) p.displayClientMessage(Component.literal(es() ? "§cVen afuera" : "§cCome outside"), true);
                Vec3 eye = p.getEyePosition(), tg = new Vec3(doorSpot.x, d.y + 1, doorSpot.z), v = tg.subtract(eye);
                double dist = v.length();
                boolean look = dist <= SEE && dist > .1 && p.getLookAngle().dot(v) / dist >= GAZE_COS && Houses.sight(s.level, eye, tg, p);
                s.gaze = look ? s.gaze + 1 : 0;
                if (s.gaze < GAZE_TICKS) return;
                for (BlockPos b : d.blocks) Houses.setDoor(s.level, b, true);                       // se abre la puerta
                s.e = SpawnHelper.createScripted(s.level, "stalker_goat_door", doorSpot, goatYaw(d.qx, d.qz));
                if (s.e == null) {
                    end(false, now);
                    return;
                }
                s.e.noCulling = true;
                s.e.setProp("fase", 1);
                stop(p, "stalker.window_voice");
                p.displayClientMessage(Component.empty(), true);
                for (ServerPlayer q : s.level.players()) if (q.distanceToSqr(doorSpot) <= 48 * 48) snd(q, "stalker.run", 1f);
                s.phase = "bones";
                s.t = 0;
                return;
            }
            case "bones": {
                if (s.t % 10 == 0) place(s.e, doorSpot, goatYaw(d.qx, d.qz));
                if (s.t < BONES) return;
                boolean thin = false;
                var key = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.level.getBlockState(w.blocks.get(0)).getBlock());
                if (key != null) thin = key.getPath().contains("pane") || key.getPath().contains("bars") || key.getPath().contains("fence");
                double outD = thin ? .25 : .62;
                float yaw = goatYaw(w.qx, w.qz);
                double tr = yaw * Math.PI / 180, lzx = Math.sin(tr), lzz = -Math.cos(tr);
                Vec3 glass = new Vec3(w.cx - w.qx * outD, w.y + 1, w.cz - w.qz * outD);
                Vec3 pos = new Vec3(glass.x - lzx * HEAD_F, glass.y - HEAD_U, glass.z - lzz * HEAD_F);
                place(s.e, pos, yaw);
                s.e.setProp("fase", 3);                                                              // se asoma y espera a que la mires
                s.face = glass;
                float cyaw = (float) (Math.atan2(-w.qx, w.qz) * 180 / Math.PI);
                Vec3 cpos = new Vec3(w.cx + w.qx * (thin ? .12 : .53), w.y, w.cz + w.qz * (thin ? .12 : .53));
                s.crack = SpawnHelper.createScripted(s.level, "stalker_glass_crack", cpos, cyaw);
                if (s.crack != null) s.crack.setProp("grieta", 0);
                s.wpos = pos;
                s.wyaw = yaw;
                s.hits = 0;
                s.wgaze = 0;
                s.phase = "peek";
                s.t = 0;
                return;
            }
            default:
        }
        // no te deja acercarte a la ventana (menos de 2 bloques)
        double rx = p.getX() - w.cx, rz = p.getZ() - w.cz;
        double along = rx * w.qx + rz * w.qz, lat = Math.abs(rx * -w.qz + rz * w.qx);
        if (along > -.2 && along < KEEP_OUT && lat < 2.5 && Math.abs(p.getY() - w.y) < 3) {
            double push = KEEP_OUT - along + .05;
            p.teleportTo(s.level, p.getX() + w.qx * push, p.getY(), p.getZ() + w.qz * push, p.getYRot(), p.getXRot());
        }
        if (s.t % 10 == 0) place(s.e, s.wpos, s.wyaw);
        if (s.phase.equals("peek")) {
            if (s.t >= PEEK_MAX) {
                end(false, now);
                return;
            }
            Vec3 eye = p.getEyePosition(), v = s.face.subtract(eye);
            double dist = v.length();
            boolean look = dist <= SEE && dist > .1 && p.getLookAngle().dot(v) / dist >= WIN_GAZE_COS && Houses.sight(s.level, eye, s.face, p);
            s.wgaze = look ? s.wgaze + 1 : 0;
            if (s.wgaze < GAZE_TICKS) return;
            s.e.setProp("fase", 2);                                                                  // empieza a romperla a golpes
            s.phase = "window";
            s.t = 0;
            return;
        }
        if (s.phase.equals("window")) {
            if (s.t < HIT_FIRST || (s.t - HIT_FIRST) % HIT_EVERY != 0) return;
            s.hits++;
            if (s.hits < HITS) {
                if (s.crack != null) s.crack.setProp("grieta", s.hits);
                snd(p, "stalker.wb_crack" + Math.min(s.hits, 3), 1f);
                return;
            }
            if (s.crack != null) s.crack.discard();
            s.crack = null;
            for (BlockPos b : w.blocks) s.level.destroyBlock(b, false);                              // la rompe
            for (ServerPlayer q : s.level.players()) {
                if (Math.hypot(q.getX() - w.cx, q.getZ() - w.cz) > 48) continue;
                stop(q, "stalker.run");
                snd(q, "stalker.goat_door_break", 1f);
                q.addEffect(new MobEffectInstance(MobEffects.DARKNESS, DARK, 0, false, false));
            }
            end(true, now);
        }
    }

    private static void place(StalkerEntity e, Vec3 pos, float yaw) {
        if (e == null || !e.isAlive()) return;
        e.setPos(pos.x, pos.y, pos.z);
        e.setYRot(yaw);
        e.setYHeadRot(yaw);
        e.setYBodyRot(yaw);
    }
}
