package com.twixxel.stalker.event;

import com.twixxel.stalker.TwixxelMod;
import com.twixxel.stalker.entity.FieldProfile;
import com.twixxel.stalker.entity.StalkerEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.Random;
import java.util.UUID;

/**
 * RUN "SAL AFUERA": desde dentro de tu casa, el RUN se asoma por una ventana 2x2 y saca la mano. Sin mirarlo toca el
 * cristal cada 5 s; al mirarlo dice "sal afuera", se hunde y desaparece. Entonces el chat cuenta "Stalker: 10..0":
 * si sales afuera mientras cuenta, te persigue 60 s (musica, oscuridad, "NO DEBISTE SALIR"). Natural cada 10-15 min.
 */
public final class SalAfueraEvent {
    private static final int RISE = 30, GAZE = 6, VOICE = 50, GONE = 52, KNOCK_AFTER = 100, KNOCK_TICKS = 40, UNSEEN_MAX = 2400, EXIT_TICKS = 10, COUNT_FROM = 10;
    private static final double OUT = 0.9, SPAWN_DIST = 15;

    private static StalkerEntity e;
    private static UUID pid;
    private static ServerLevel level;
    private static Vec3 head;
    private static String phase;
    private static int t, gaze, unseen, total, knockT, said, leaveT, n, out;
    private static long next = -1;
    private static final Random RND = new Random();

    public static boolean active() {
        return pid != null;
    }

    private static void snd(String id, Vec3 at, float vol) {
        var se = BedHide.se(id);
        if (se != null && level != null) level.playSound(null, at.x, at.y, at.z, se, SoundSource.HOSTILE, vol, 1f);
    }

    private static void bar(ServerPlayer p, String s) {
        p.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(s)));
    }

    public static boolean startFor(ServerPlayer p) {
        if (pid != null || !StalkerEntity.ACTIVE.isEmpty()) return false;
        Houses.Win w = Houses.find2x2(p, false);
        if (w == null) return false;
        level = p.serverLevel();
        double nx = -w.qx, nz = -w.qz;
        Vec3 pos = new Vec3(w.cx + nx * OUT, w.y, w.cz + nz * OUT);
        e = SpawnHelper.createScripted(level, "stalker_run_salafuera", pos, (float) (Math.atan2(-nz, -nx) * 180 / Math.PI) - 90f);
        if (e == null) {
            level = null;
            return false;
        }
        e.noCulling = true;
        e.setProp("fase", 0);
        head = new Vec3(pos.x, pos.y + 1.6, pos.z);
        pid = p.getUUID();
        phase = "peek";
        t = gaze = unseen = total = out = 0;
        knockT = -1;
        said = -1;
        leaveT = -1;
        return true;
    }

    private static void end() {
        if (e != null && e.isAlive()) e.discard();
        e = null;
        pid = null;
        level = null;
        phase = null;
        MinecraftServer s = ServerLifecycleHooks.getCurrentServer();
        if (s != null) next = s.getTickCount() + 12000 + RND.nextInt(6001);
    }

    private static boolean looking(ServerPlayer p) {
        Vec3 eye = p.getEyePosition(), v = head.subtract(eye);
        double d = v.length();
        return d > .1 && d < 28 && p.getLookAngle().dot(v) / d >= .95 && Houses.sight(level, eye, head, p);
    }

    private static void chase(ServerPlayer p) {
        ServerLevel l = p.serverLevel();
        var ro = TwixxelMod.TYPES.get("stalker_run");
        end();
        if (ro == null) return;
        StalkerEntity r = ro.get().create(l);
        if (r == null) return;
        Vec3 at = null;
        for (double ang = 0; ang < 360 && at == null; ang += 30) {
            double a = Math.toRadians(p.getYRot() + 180 + (ang % 2 == 0 ? ang : -ang));
            double x = p.getX() - Math.sin(a) * SPAWN_DIST, z = p.getZ() + Math.cos(a) * SPAWN_DIST;
            int y = l.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(x), (int) Math.floor(z));
            if (Math.abs(y - p.getY()) <= 6) at = new Vec3(x, y, z);
        }
        if (at == null) at = new Vec3(p.getX(), p.getY(), p.getZ());
        r.setFieldProfile(FieldProfile.SAL_AFUERA);
        r.moveTo(at.x, at.y, at.z, p.getYRot() + 180f, 0f);
        l.addFreshEntity(r);
        r.forceChase(p, false);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        long now = server.getTickCount();
        if (pid == null) {
            if (now % 20 != 0 || !com.twixxel.stalker.Config.NATURAL_SPAWNS.get()) return;
            if (next < 0) next = now + 12000 + RND.nextInt(6001);
            if (now < next) return;
            if (!StalkerEntity.ACTIVE.isEmpty() || VigilanteEvent.active() || GoatPuertaEvent.active() || MaebiEvent.active()) {
                next = now + 600;
                return;
            }
            var ps = new java.util.ArrayList<>(server.getPlayerList().getPlayers());
            java.util.Collections.shuffle(ps, RND);
            for (ServerPlayer p : ps) if (p.isAlive() && !p.isSpectator() && !SuenoEvent.isDreaming(p) && Houses.isInsideHouse(p) && startFor(p)) return;
            next = now + 600;
            return;
        }
        ServerPlayer p = server.getPlayerList().getPlayer(pid);
        if (p == null || !p.isAlive() || p.level() != level) {
            end();
            return;
        }
        t++;
        if (phase.equals("peek")) {
            if (e == null || !e.isAlive()) {
                end();
                return;
            }
            if (t < RISE) return;
            if (leaveT >= 0) {
                if (t - leaveT >= GONE) {
                    e.discard();
                    e = null;
                    if (said < 0) {
                        end();
                        return;
                    }
                    phase = "count";
                    t = 0;
                    n = COUNT_FROM;
                    out = 0;
                    p.sendSystemMessage(Component.literal("§4Stalker:§r " + n));
                }
                return;
            }
            if (said >= 0) {
                int k = t - said;
                if (k % 10 == 0) bar(p, Lang.es() ? "§cSal afuera" : "§cGo outside");
                if (k >= VOICE) {
                    bar(p, "");
                    leaveT = t;
                    e.setProp("fase", 1);
                }
                return;
            }
            if (looking(p)) {
                gaze++;
                unseen = 0;
            } else {
                gaze = Math.max(0, gaze - 1);
                unseen++;
            }
            total++;
            if (gaze >= GAZE) {
                if (knockT >= 0) {
                    knockT = -1;
                    e.setProp("fase", 0);
                }
                said = t;
                snd("stalker.saf_voz", head, 1f);
                bar(p, Lang.es() ? "§cSal afuera" : "§cGo outside");
                return;
            }
            if (knockT >= 0) {
                if (t - knockT >= KNOCK_TICKS) {
                    knockT = -1;
                    unseen = 0;
                    e.setProp("fase", 0);
                }
                return;
            }
            if (unseen >= KNOCK_AFTER) {
                knockT = t;
                e.setProp("fase", 2);
                snd("stalker.saf_toc", head, 1.5f);
                return;
            }
            if (total >= UNSEEN_MAX) {
                leaveT = t;
                e.setProp("fase", 1);
            }
            return;
        }
        // cuenta atras: sales a cielo abierto -> te persigue
        if (level.canSeeSky(p.blockPosition())) out++;
        else out = 0;
        if (out >= EXIT_TICKS) {
            chase(p);
            return;
        }
        if (t % 20 == 0) {
            n--;
            if (n < 0) {
                end();
                return;
            }
            p.sendSystemMessage(Component.literal("§4Stalker:§r " + n));
        }
    }
}
