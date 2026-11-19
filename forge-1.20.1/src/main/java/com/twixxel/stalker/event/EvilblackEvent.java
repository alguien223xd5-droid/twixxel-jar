package com.twixxel.stalker.event;

import com.twixxel.stalker.entity.StalkerEntity;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * EVILBLACK (castigo por matar 30 animales): a los 2 s aparece delante de ti; si te acercas dice "seras castigado" (1 vez),
 * desaparece y empieza su musica con todo oscuro y "Esta a 400 bloques de ti" bajando a 0 en 21 s. Al llegar a 0 aparece delante
 * y te persigue lo que queda de la musica, con "SERAS CASTIGADO" en pantalla. Si te alcanza te mata. Prueba: huevo o /twxevilblack.
 */
public final class EvilblackEvent {
    private static final String ID = "evilblack";
    private static final int KILLS = 30, VOZ_T = 76, COUNT_T = 420, FROM = 400, CHASE_T = 694;
    private static final Map<UUID, Integer> KILLED = new HashMap<>();

    private static final class S {
        ServerLevel level;
        UUID pid;
        StalkerEntity e;
        int phase, t;       // 0 espera, 1 habla, 2 cuenta, 3 persecucion
        Vec3 pos;
    }

    private static final Map<UUID, S> RUN = new HashMap<>();
    private static boolean spawning = false;

    private static boolean es() {
        return Lang.es();
    }

    private static void snd(ServerPlayer p, String id) {
        SoundEvent se = BedHide.se(id);
        if (se != null) p.playNotifySound(se, SoundSource.MASTER, 1f, 1f);
    }

    private static void bar(ServerPlayer p, String s) {
        p.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(s)));
    }

    private static boolean animal(Entity e) {
        return e instanceof Animal || e instanceof WaterAnimal || e instanceof Bat || e instanceof Villager;
    }

    @SubscribeEvent
    public void onDeath(LivingDeathEvent ev) {
        if (ev.getEntity().level().isClientSide || !animal(ev.getEntity())) return;
        DamageSource src = ev.getSource();
        Entity k = src.getEntity();
        if (!(k instanceof ServerPlayer p)) return;
        int n = KILLED.merge(p.getUUID(), 1, Integer::sum);
        if (n >= KILLS) {
            KILLED.put(p.getUUID(), 0);
            p.serverLevel().getServer().execute(() -> {
                // retraso de 2 s mediante estado: se crea ya con t negativo
                S s = begin(p);
                if (s != null) s.t = -40;
            });
        }
    }

    private static S begin(ServerPlayer p) {
        if (RUN.containsKey(p.getUUID())) return null;
        ServerLevel l = p.serverLevel();
        Vec3 v = p.getLookAngle();
        double vl = Math.max(1e-4, Math.hypot(v.x, v.z)), fx = v.x / vl, fz = v.z / vl;
        for (int dist : new int[]{18, 16, 14, 12, 10}) {
            double x = p.getX() + fx * dist, z = p.getZ() + fz * dist;
            Double gy = ground(l, x, z, p.getY());
            if (gy == null) continue;
            Vec3 at = new Vec3(x, gy, z);
            float yaw = (float) (Math.atan2(-(p.getX() - x), p.getZ() - z) * 180 / Math.PI);
            StalkerEntity e = SpawnHelper.createScripted(l, ID, at, yaw);
            if (e == null) return null;
            e.noCulling = true;
            S s = new S();
            s.level = l;
            s.pid = p.getUUID();
            s.e = e;
            s.pos = at;
            RUN.put(p.getUUID(), s);
            return s;
        }
        return null;
    }

    private static Double ground(ServerLevel l, double x, double z, double y0) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        for (int y = (int) y0 + 5; y >= (int) y0 - 8; y--) {
            net.minecraft.core.BlockPos b = new net.minecraft.core.BlockPos(bx, y, bz);
            if (!l.isLoaded(b)) return null;
            if (!l.getBlockState(b.below()).getCollisionShape(l, b.below()).isEmpty() && l.getBlockState(b).getCollisionShape(l, b).isEmpty()
                    && l.getBlockState(b.above()).getCollisionShape(l, b.above()).isEmpty() && l.getBlockState(b.below()).getFluidState().isEmpty()) return (double) y;
        }
        return null;
    }

    @SubscribeEvent
    public void onJoin(EntityJoinLevelEvent ev) {
        if (ev.getLevel().isClientSide || !(ev.getEntity() instanceof StalkerEntity q) || !q.getDefId().equals(ID) || q.isScripted() || spawning) return;
        ev.setCanceled(true);
        if (ev.loadedFromDisk() || !(ev.getLevel() instanceof ServerLevel sl)) return;
        ServerPlayer near = null;
        double bd = 1e18;
        for (ServerPlayer x : sl.players()) {
            double d = x.distanceToSqr(q.position());
            if (d < bd) {
                bd = d;
                near = x;
            }
        }
        if (near != null) {
            final ServerPlayer p = near;
            sl.getServer().execute(() -> begin(p));
        }
    }

    @SubscribeEvent
    public void commands(RegisterCommandsEvent ev) {
        ev.getDispatcher().register(Commands.literal("twxevilblack").requires(c -> c.hasPermission(2)).executes(c -> begin(c.getSource().getPlayerOrException()) != null ? 1 : 0));
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END || RUN.isEmpty()) return;
        var srv = ServerLifecycleHooks.getCurrentServer();
        if (srv == null) return;
        for (var it = RUN.entrySet().iterator(); it.hasNext(); ) {
            S s = it.next().getValue();
            ServerPlayer p = srv.getPlayerList().getPlayer(s.pid);
            if (p == null || !p.isAlive() || p.level() != s.level) {
                if (s.e != null && s.e.isAlive()) s.e.discard();
                it.remove();
                continue;
            }
            s.t++;
            if (s.t < 0) continue;
            switch (s.phase) {
                case 0 -> {
                    if (s.e == null || !s.e.isAlive()) {
                        it.remove();
                        continue;
                    }
                    double d = Math.hypot(p.getX() - s.pos.x, p.getZ() - s.pos.z);
                    if (d <= 5 || s.t > 3600) {
                        if (s.t > 3600) {
                            s.e.discard();
                            it.remove();
                            continue;
                        }
                        s.phase = 1;
                        s.t = 0;
                        snd(p, es() ? "stalker.evilblack_voz1" : "stalker.evilblack_voz2");
                        bar(p, es() ? "§4Serás castigado" : "§4You will be punished");
                    }
                }
                case 1 -> {
                    if (s.t >= VOZ_T) {
                        s.e.discard();
                        s.phase = 2;
                        s.t = 0;
                        p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, COUNT_T + 20, 0, false, false));
                        p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, COUNT_T, 0, false, false));
                        snd(p, "stalker.evilblack_music");
                    }
                }
                case 2 -> {
                    if (s.t % 2 == 0) {
                        int n = Math.max(0, Math.round(FROM * (1f - s.t / (float) COUNT_T)));
                        bar(p, es() ? "§4Está a " + n + " bloques de ti" : "§4It is " + n + " blocks away");
                    }
                    if (s.t >= COUNT_T) {
                        p.removeEffect(MobEffects.BLINDNESS);
                        p.removeEffect(MobEffects.DARKNESS);
                        Vec3 v = p.getLookAngle();
                        double vl = Math.max(1e-4, Math.hypot(v.x, v.z));
                        StalkerEntity c = null;
                        for (int dist : new int[]{14, 12, 16, 10, 8}) {
                            double x = p.getX() + v.x / vl * dist, z = p.getZ() + v.z / vl * dist;
                            Double gy = ground(s.level, x, z, p.getY());
                            if (gy == null) continue;
                            spawning = true;
                            try {
                                c = SpawnHelper.spawnChaseAtE(p, ID, new Vec3(x, gy, z), false);
                            } finally {
                                spawning = false;
                            }
                            if (c != null) break;
                        }
                        s.e = c;
                        s.phase = 3;
                        s.t = 0;
                    }
                }
                default -> {
                    if (s.e == null || !s.e.isAlive() || s.t > CHASE_T + 40) {
                        if (s.e != null && s.e.isAlive()) s.e.discard();
                        bar(p, "");
                        it.remove();
                        continue;
                    }
                    if (s.t % 10 == 0) bar(p, es() ? "§4§lSERÁS CASTIGADO" : "§4§lYOU WILL BE PUNISHED");
                }
            }
        }
    }
}
