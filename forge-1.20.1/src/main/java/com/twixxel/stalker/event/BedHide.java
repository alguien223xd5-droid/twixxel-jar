package com.twixxel.stalker.event;

import com.twixxel.stalker.TwixxelMod;
import com.twixxel.stalker.entity.StalkerEntity;
import com.twixxel.stalker.block.ArmarioBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Esconderse DEBAJO DE LA CAMA (agachado + clic derecho en una cama, o clic en una cama mientras te persiguen).
 * La camara queda a ras del suelo bajo la cama (se manda con el paquete de camara de espectador a un
 * soporte invisible). Salir: saltar, o soltar y volver a agacharte.
 * - Si ningun stalker que te persigue te vio meterte, te dejan en paz (siguen a otro o se quedan quietos).
 * - Si alguno te vio, va a la cama y hace SU escena: el RUN mete las manos y asoma la cabeza dada vuelta;
 *   los demas tienen un titere (stalker_bedscene) con su propia animacion; el resto te saca a rastras.
 * Todas terminan igual: gritan, se lanzan y te matan.
 */
public final class BedHide {
    private static final double GRAB_RANGE = 2.8;
    private static final double SEE_RANGE = 48;
    private static final int GRAB_TICKS = 46, KILL_AT = 52;
    private static final double PEEK_Y = 0.3;

    // fases del titere (stalker_bedscene)
    private static final int F_QUIETO = 0, F_CAMINAR = 1, F_CAER = 2, F_LADO = 3, F_LADO_GRITO = 4, F_COLGADO = 5,
            F_COLGADO_GRITO = 6, F_GATEAR = 7, F_APLASTADO = 8, F_DOBLAR = 9;

    private static final class Cfg {
        final int tipo;
        final float yaw;
        final double headY;
        final String kind;
        boolean knock, voice, lateral;
        final String[] scream;
        final float[] pitch;

        Cfg(int tipo, float yaw, double headY, String kind, String[] scream, float[] pitch) {
            this.tipo = tipo;
            this.yaw = yaw;
            this.headY = headY;
            this.kind = kind;
            this.scream = scream;
            this.pitch = pitch;
        }
    }

    private static final Map<String, Cfg> TYPES = new HashMap<>();

    static {
        String US = "stalker.unknown_scream", PS = "stalker.peeker_scream", GS = "mob.goat.ambient.screamer";
        TYPES.put("stalker_evil", new Cfg(0, 0, 5.478, "lado", new String[]{US}, new float[]{.7f}));
        TYPES.put("stalker_goatevil", new Cfg(1, -90, 4.65, "colgado", new String[]{US, GS}, new float[]{.55f, .6f}));
        TYPES.put("stalker_quad_red", new Cfg(2, -90, 4.318, "gatear", new String[]{US}, new float[]{.6f}));
        TYPES.put("stalker_goat", new Cfg(3, -90, 5.135, "atras", new String[]{GS, US}, new float[]{.5f, .75f}));
        TYPES.put("stalker_spotted", new Cfg(4, -90, 2.226, "glitch", new String[]{PS}, new float[]{1f}));
        TYPES.put("stalker_ansiedad", new Cfg(5, 0, 3.966, "lado", new String[]{PS}, new float[]{.8f}));
        TYPES.put("stalker_smiler", new Cfg(6, -90, 1.355, "aplastado", new String[]{US}, new float[]{1.35f}));
        Cfg unk = new Cfg(7, -90, 1.809, "colgado", new String[]{US}, new float[]{1f});
        unk.knock = true;
        TYPES.put("stalker_unknown", unk);
        TYPES.put("stalker_screamer", new Cfg(8, -90, 4.515, "golpes", new String[]{US, PS}, new float[]{.5f, .6f}));
        Cfg hel = new Cfg(9, -90, 6.234, "doblar", new String[]{US}, new float[]{.8f});
        hel.voice = true;
        TYPES.put("stalker_hello", hel);
        TYPES.put("stalker_redlight", new Cfg(10, -90, 2.323, "luces", new String[]{PS}, new float[]{.7f}));
        Cfg obs = new Cfg(11, 0, 6.501, "doblar", new String[]{PS}, new float[]{.9f});
        obs.lateral = true;
        TYPES.put("stalker_observer", obs);
        TYPES.put("stalker_tortura", new Cfg(12, 0, 3.433, "golpes", new String[]{GS, US}, new float[]{.45f, .7f}));
    }

    private static final class Grab {
        String mode;                       // generic | run | scene
        int t;
        StalkerEntity st;
        Vec3 sl;
        // run
        StalkerEntity head, handR, handL;
        Vec3[] handPos = new Vec3[2];
        Vec3 headPos, headPosS;
        boolean screamed;
        // scene
        Cfg cfg;
        String kind;
        StalkerEntity pup;
        int fase = -1;
        boolean hidden = true;
        int side = 1;
        double backAlong, backSide;
        boolean hasBack;
        Vec3 hid;
    }

    private static final class H {
        UUID pid;
        ServerLevel level;
        Vec3 bed, body, cam;
        BlockPos bedPos;
        float outYaw;
        UUID caught;
        boolean sneakUp;
        int t;
        ArmorStand rig;
        boolean camSent;
        double dx, dz, px, pz;
        Grab g;
        float cp, cy;                      // rotacion actual de la camara
        boolean arm;                       // escondido en un armario
        BlockPos armBase;
        StalkerEntity mask;                // vista con las 3 rendijas
        int flash;                         // ticks que la puerta sigue abierta
    }

    private static final Map<UUID, H> HIDE = new HashMap<>();
    private static final Map<UUID, Long> UNNOTICED = new HashMap<>();
    private static final Map<StalkerEntity, Long> CHASING = new HashMap<>();

    private static boolean es() {
        return com.twixxel.stalker.event.Lang.es();
    }

    private static long now() {
        MinecraftServer s = ServerLifecycleHooks.getCurrentServer();
        return s == null ? 0 : s.getTickCount();
    }

    // ------------------------------------------------------------------ utilidades
    private static double lerp(double a, double b, double f) {
        return a + (b - a) * f;
    }

    private static double ease(double f) {
        return f < .5 ? 2 * f * f : 1 - Math.pow(-2 * f + 2, 2) / 2;
    }

    private static Vec3 lerpP(Vec3 a, Vec3 b, double f) {
        return new Vec3(lerp(a.x, b.x, f), lerp(a.y, b.y, f), lerp(a.z, b.z, f));
    }

    private static float yawTo(Vec3 from, Vec3 to) {
        return (float) (Math.atan2(-(to.x - from.x), to.z - from.z) * 180 / Math.PI);
    }

    private static double rnd() {
        return Math.random() - .5;
    }

    private static Vec3 P(H h, double along, double side, double y) {
        return new Vec3(h.bed.x + h.dx * along + h.px * side, h.bed.y + y, h.bed.z + h.dz * along + h.pz * side);
    }

    private static SoundEvent vanilla(String id) {
        switch (id) {
            case "dig.cloth": return SoundEvents.WOOL_STEP;
            case "dig.wood":
            case "step.wood": return SoundEvents.WOOD_STEP;
            case "dig.gravel": return SoundEvents.GRAVEL_STEP;
            case "step.stone": return SoundEvents.STONE_STEP;
            case "mob.zombie.woodbreak": return SoundEvents.ZOMBIE_BREAK_WOODEN_DOOR;
            case "mob.slime.big": return SoundEvents.SLIME_SQUISH;
            case "mob.spider.step": return SoundEvents.SPIDER_STEP;
            case "mob.warden.heartbeat": return SoundEvents.WARDEN_HEARTBEAT;
            case "mob.endermen.portal": return SoundEvents.ENDERMAN_TELEPORT;
            case "random.click": return SoundEvents.UI_BUTTON_CLICK.get();
            case "game.player.hurt": return SoundEvents.PLAYER_HURT;
            case "mob.goat.ambient.screamer": return SoundEvents.GOAT_SCREAMING_AMBIENT;
            default: return null;
        }
    }

    public static SoundEvent se(String id) {
        if (id.startsWith("stalker.")) {
            var r = TwixxelMod.SOUND_EVENTS.get(TwixxelMod.loc(id));
            return r == null ? null : r.get();
        }
        return vanilla(id);
    }

    private static void snd(ServerLevel l, String id, Vec3 at, float vol, float pitch) {
        SoundEvent s = se(id);
        if (s != null) l.playSound(null, at.x, at.y, at.z, s, SoundSource.HOSTILE, vol, pitch);
    }

    private static void psnd(ServerPlayer p, String id, float vol, float pitch) {
        SoundEvent s = se(id);
        if (s != null) p.playNotifySound(s, SoundSource.MASTER, vol, pitch);
    }

    private static void fade(ServerPlayer p, int ticks) {
        p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, ticks, 0, false, false));
    }

    private static boolean isBed(ServerLevel l, BlockPos q) {
        return l.getBlockState(q).getBlock() instanceof BedBlock;
    }

    private static boolean nonSolid(ServerLevel l, BlockPos q) {
        return l.getBlockState(q).getCollisionShape(l, q).isEmpty();
    }

    // ------------------------------------------------------------------ camara
    private static void spawnRig(H h) {
        ArmorStand a = new ArmorStand(h.level, h.cam.x, h.cam.y, h.cam.z);
        a.setInvisible(true);
        a.setNoGravity(true);
        a.noPhysics = true;
        a.setInvulnerable(true);
        a.setSilent(true);
        h.level.addFreshEntity(a);
        h.rig = a;
        h.cp = 0;
        h.cy = h.outYaw;
        h.camSent = false;
        moveRig(h, h.cam, 0, h.outYaw);
    }

    /** loc = posicion de los OJOS de la camara. */
    private static void moveRig(H h, Vec3 loc, float pitch, float yaw) {
        if (h.rig == null) return;
        h.cp = pitch;
        h.cy = yaw;
        h.rig.setPos(loc.x, loc.y - h.rig.getEyeHeight(), loc.z);
        h.rig.setYRot(yaw);
        h.rig.setXRot(pitch);
        h.rig.setYHeadRot(yaw);
        h.rig.setYBodyRot(yaw);
    }

    private static void look(H h, Vec3 camPos, Vec3 target, double shake) {
        if (Opciones.calm()) shake = 0;
        double dx = target.x - camPos.x, dy = target.y - camPos.y, dz = target.z - camPos.z;
        float yaw = (float) (Math.atan2(-dx, dz) * 180 / Math.PI);
        float pitch = (float) (-(Math.atan2(dy, Math.hypot(dx, dz)) * 180 / Math.PI));
        Vec3 loc = camPos;
        if (shake > 0) {
            loc = new Vec3(camPos.x + rnd() * shake, camPos.y + rnd() * shake * .4, camPos.z + rnd() * shake);
            pitch += rnd() * shake * 60;
            yaw += rnd() * shake * 80;
        }
        moveRig(h, loc, pitch, yaw);
    }

    private static void restoreCam(ServerPlayer p, H h) {
        if (h.rig != null) {
            if (h.camSent) p.connection.send(new ClientboundSetCameraPacket(p));
            h.rig.discard();
            h.rig = null;
        }
    }

    private static void armSet(H h, boolean open) {
        if (!h.arm || h.armBase == null) return;
        for (int dy = 0; dy < 2; dy++) {
            BlockPos q = h.armBase.above(dy);
            BlockState bs = h.level.getBlockState(q);
            if (bs.getBlock() instanceof ArmarioBlock && bs.getValue(ArmarioBlock.OPEN) != open) h.level.setBlock(q, bs.setValue(ArmarioBlock.OPEN, open), 3);
        }
    }

    private static void armFlash(H h, boolean open, Vec3 at) {
        armSet(h, open);
        h.level.playSound(null, at.x, at.y + 1, at.z, open ? SoundEvents.WOODEN_DOOR_OPEN : SoundEvents.WOODEN_DOOR_CLOSE, SoundSource.BLOCKS, 1f, .85f);
        h.flash = open ? 9 : 0;
    }

    private static void dropMask(H h) {
        if (h.mask != null && h.mask.isAlive()) h.mask.discard();
        h.mask = null;
    }

    private static void maskEnsure(H h) {
        if (!h.arm || (h.mask != null && h.mask.isAlive())) return;
        StalkerEntity m = SpawnHelper.createScripted(h.level, "armario_vista", new Vec3(h.cam.x, h.cam.y - .4375, h.cam.z), h.outYaw);
        if (m != null) {
            m.noCulling = true;
            h.mask = m;
        }
    }

    private static void release(ServerPlayer p, H h) {
        dropMask(h);
        armSet(h, false);
        restoreCam(p, h);
        p.removeEffect(MobEffects.INVISIBILITY);
        p.removeEffect(MobEffects.NIGHT_VISION);
    }

    private static void killPlayer(ServerPlayer p, StalkerEntity st) {
        p.removeEffect(MobEffects.BLINDNESS);
        JumpScare.kill(p, st);
    }

    // ------------------------------------------------------------------ vision de los stalkers
    private static boolean lineClear(ServerLevel l, Vec3 a, Vec3 b, net.minecraft.world.entity.Entity ctx) {
        return l.clip(new ClipContext(a, b, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, ctx)).getType() == HitResult.Type.MISS;
    }

    private static boolean sees(StalkerEntity st, ServerPlayer p) {
        if (!st.isAlive() || st.level() != p.level()) return false;
        if (st.distanceTo(p) > SEE_RANGE) return false;
        ServerLevel l = (ServerLevel) st.level();
        for (double sh : new double[]{1.6, .9}) {
            Vec3 from = new Vec3(st.getX(), st.getY() + sh, st.getZ());
            for (double ph : new double[]{1.5, .9, .3}) {
                if (lineClear(l, from, new Vec3(p.getX(), p.getY() + ph, p.getZ()), st)) return true;
            }
        }
        return false;
    }

    private static boolean ignoredAt(ServerPlayer p, StalkerEntity st) {
        H h = HIDE.get(p.getUUID());
        if (h != null) return h.caught == null && h.g == null;
        if (!UNNOTICED.containsKey(p.getUUID())) return false;
        if (st.level() != p.level()) return true;
        if (sees(st, p)) {
            UNNOTICED.remove(p.getUUID());
            return false;
        }
        return true;
    }

    private static List<StalkerEntity> activeChasers() {
        long n = now();
        List<StalkerEntity> out = new ArrayList<>();
        Iterator<Map.Entry<StalkerEntity, Long>> it = CHASING.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (n - e.getValue() > 5 || !e.getKey().isAlive()) it.remove();
            else out.add(e.getKey());
        }
        return out;
    }

    public static boolean isHidden(UUID id) {
        return HIDE.containsKey(id);
    }

    /** Lo llama cada persecucion cada tick. true = este tick no te sigue ni te mata. */
    public static boolean guard(StalkerEntity st, ServerPlayer p) {
        CHASING.put(st, now());
        H h = HIDE.get(p.getUUID());
        if (h == null) {
            return UNNOTICED.containsKey(p.getUUID()) && ignoredAt(p, st);
        }
        if (h.g != null) return true;
        if (h.caught != null) {
            double hd = Math.hypot(st.getX() - h.body.x, st.getZ() - h.body.z);
            if (st.level() == p.level() && hd <= GRAB_RANGE && Math.abs(st.getY() - h.body.y) <= 3) {
                startGrab(p, h, st);
                return true;
            }
            return false;
        }
        // escondido sin que te vieran: busca a otro jugador
        for (ServerPlayer q : ((ServerLevel) st.level()).players()) {
            if (q == p || !q.isAlive() || q.isSpectator() || ignoredAt(q, st)) continue;
            st.setVictim(q);
            break;
        }
        return true;
    }

    // ------------------------------------------------------------------ entrar / salir
    @SubscribeEvent
    public void onUse(PlayerInteractEvent.RightClickBlock ev) {
        if (!(ev.getEntity() instanceof ServerPlayer p) || !(ev.getLevel() instanceof ServerLevel level)) return;
        BlockPos pos = ev.getPos();
        if (!isBed(level, pos)) return;
        if (HIDE.containsKey(p.getUUID())) {
            ev.setCanceled(true);
            ev.setCancellationResult(InteractionResult.FAIL);
            return;
        }
        if (!p.isShiftKeyDown() && activeChasers().isEmpty()) return;      // si no, es dormir normal
        ev.setCanceled(true);                                              // no se acuesta ni cambia el punto de aparicion
        ev.setCancellationResult(InteractionResult.SUCCESS);
        if (ev.getHand() == InteractionHand.MAIN_HAND) enter(p, level, pos);
    }

    private static void enter(ServerPlayer p, ServerLevel level, BlockPos bedPos) {
        if (HIDE.containsKey(p.getUUID())) return;
        List<BlockPos> halves = new ArrayList<>();
        halves.add(bedPos);
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            BlockPos q = bedPos.offset(d[0], 0, d[1]);
            if (isBed(level, q)) halves.add(q);
        }
        BlockPos hb = null;
        int ox = 0, oz = 0;
        double bd = 1e9;
        for (BlockPos c : halves) for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            BlockPos q = c.offset(d[0], 0, d[1]);
            if (isBed(level, q) || !nonSolid(level, q) || !nonSolid(level, q.above())) continue;
            if (nonSolid(level, q.below())) continue;
            double dist = Math.hypot(q.getX() + .5 - p.getX(), q.getZ() + .5 - p.getZ());
            if (dist < bd) {
                bd = dist;
                hb = c;
                ox = d[0];
                oz = d[1];
            }
        }
        if (hb == null) {
            p.displayClientMessage(Component.literal(es() ? "§7No hay espacio para meterte bajo esta cama" : "§7There is no room to get under this bed"), true);
            return;
        }
        H h = new H();
        h.pid = p.getUUID();
        h.level = level;
        h.bedPos = bedPos;
        h.bed = new Vec3(hb.getX() + .5, hb.getY(), hb.getZ() + .5);
        h.body = new Vec3(hb.getX() + .5 + ox, hb.getY(), hb.getZ() + .5 + oz);
        h.cam = new Vec3(h.bed.x + ox * .15, hb.getY() + .12, h.bed.z + oz * .15);
        h.outYaw = yawTo(h.bed, h.body);
        double dl = Math.hypot(ox, oz);
        h.dx = ox / dl;
        h.dz = oz / dl;
        h.px = -h.dz;
        h.pz = h.dx;
        for (StalkerEntity st : activeChasers()) {
            if (sees(st, p)) {
                h.caught = st.getUUID();
                break;
            }
        }
        UNNOTICED.remove(p.getUUID());
        HIDE.put(p.getUUID(), h);
        p.teleportTo(level, h.body.x, h.body.y, h.body.z, h.outYaw, 0f);
        p.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 20000000, 0, false, false));
        spawnRig(h);
        psnd(p, "dig.cloth", .8f, .8f);
        p.displayClientMessage(Component.literal(es() ? "§7Estás debajo de la cama  §8(salta para salir)" : "§7You are under the bed  §8(jump to get out)"), true);
    }

    private static String armMsg() {
        return es() ? "§7Estás dentro del armario  §8(salta para salir)" : "§7You are inside the wardrobe  §8(jump to get out)";
    }

    public static void enterArmario(ServerPlayer p, ServerLevel level, BlockPos base) {
        if (HIDE.containsKey(p.getUUID())) return;
        BlockState bs = level.getBlockState(base);
        if (!(bs.getBlock() instanceof ArmarioBlock)) return;
        for (H o : HIDE.values()) {
            if (o.arm && o.level == level && base.equals(o.armBase)) {
                p.displayClientMessage(Component.literal(es() ? "§7Ya hay alguien escondido aquí" : "§7Someone is already hiding here"), true);
                return;
            }
        }
        Direction f = bs.getValue(ArmarioBlock.FACING);
        BlockPos front = base.relative(f);
        if (!nonSolid(level, front) || !nonSolid(level, front.above())) {
            p.displayClientMessage(Component.literal(es() ? "§7No hay espacio delante del armario" : "§7There is no room in front of the wardrobe"), true);
            return;
        }
        H h = new H();
        h.pid = p.getUUID();
        h.level = level;
        h.arm = true;
        h.armBase = base;
        h.bedPos = base;
        h.bed = new Vec3(base.getX() + .5, base.getY(), base.getZ() + .5);
        h.body = new Vec3(front.getX() + .5, base.getY(), front.getZ() + .5);
        h.cam = new Vec3(h.bed.x + f.getStepX() * .2, base.getY() + 1.45, h.bed.z + f.getStepZ() * .2);
        h.outYaw = yawTo(h.bed, h.body);
        h.dx = f.getStepX();
        h.dz = f.getStepZ();
        h.px = -h.dz;
        h.pz = h.dx;
        for (StalkerEntity st : activeChasers()) {
            if (sees(st, p)) {
                h.caught = st.getUUID();
                break;
            }
        }
        UNNOTICED.remove(p.getUUID());
        HIDE.put(p.getUUID(), h);
        p.teleportTo(level, h.body.x, h.body.y, h.body.z, h.outYaw, 0f);
        p.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 20000000, 0, false, false));
        spawnRig(h);
        maskEnsure(h);
        armFlash(h, true, h.body);
        p.displayClientMessage(Component.literal(armMsg()), true);
    }

    @SubscribeEvent
    public void onJoinMask(EntityJoinLevelEvent ev) {
        if (!ev.getLevel().isClientSide && ev.loadedFromDisk() && ev.getEntity() instanceof StalkerEntity se && se.getDefId().equals("armario_vista")) ev.setCanceled(true);
    }

    private static void exit(ServerPlayer p, H h) {
        if (h.g != null) return;
        HIDE.remove(h.pid);
        if (h.arm) h.level.playSound(null, h.body.x, h.body.y + 1, h.body.z, SoundEvents.WOODEN_DOOR_OPEN, SoundSource.BLOCKS, 1f, .85f);
        release(p, h);
        p.displayClientMessage(Component.literal(""), true);
        if (h.caught == null && !activeChasers().isEmpty()) UNNOTICED.put(h.pid, now());   // no saben que saliste hasta verte
    }

    private static void abort(ServerPlayer p, H h) {
        if (h.g != null) cleanupGrab(h.g);
        HIDE.remove(h.pid);
        if (p != null) release(p, h);
        else {
            dropMask(h);
            armSet(h, false);
            if (h.rig != null) h.rig.discard();
        }
    }

    @SubscribeEvent
    public void onDeath(LivingDeathEvent ev) {
        if (!(ev.getEntity() instanceof ServerPlayer p)) return;
        H h = HIDE.get(p.getUUID());
        if (h != null) abort(p, h);
        UNNOTICED.remove(p.getUUID());
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent ev) {
        if (!(ev.getEntity() instanceof ServerPlayer p)) return;
        H h = HIDE.get(p.getUUID());
        if (h != null) abort(p, h);
        UNNOTICED.remove(p.getUUID());
    }

    @SubscribeEvent
    public void onRespawn(PlayerEvent.PlayerRespawnEvent ev) {
        if (ev.getEntity() instanceof ServerPlayer p) {
            p.connection.send(new ClientboundSetCameraPacket(p));
            p.removeEffect(MobEffects.INVISIBILITY);
        }
    }

    // ------------------------------------------------------------------ bucle
    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        if (HIDE.isEmpty()) {
            if (!UNNOTICED.isEmpty() && server.getTickCount() % 20 == 0 && activeChasers().isEmpty()) UNNOTICED.clear();
            return;
        }
        for (H h : new ArrayList<>(HIDE.values())) {
            ServerPlayer p = server.getPlayerList().getPlayer(h.pid);
            if (p == null || !p.isAlive() || p.level() != h.level) {
                abort(p, h);
                continue;
            }
            h.t++;
            if (h.rig != null && !h.camSent && h.t >= 2) {                // el soporte ya existe en el cliente
                p.connection.send(new ClientboundSetCameraPacket(h.rig));
                h.camSent = true;
            }
            if (h.arm && h.flash > 0 && --h.flash == 0) armFlash(h, false, h.body);
            if (h.g != null) {
                grabTick(p, h);
                continue;
            }
            if (h.arm ? !(h.level.getBlockState(h.armBase).getBlock() instanceof ArmarioBlock) : !isBed(h.level, h.bedPos)) {                                // rompieron la cama
                exit(p, h);
                continue;
            }
            boolean sneak = p.isShiftKeyDown();
            if (!sneak) h.sneakUp = true;
            boolean jumped = p.getY() > h.body.y + .2;
            if (h.t > 10 && (jumped || (sneak && h.sneakUp))) {
                exit(p, h);
                p.teleportTo(h.level, h.body.x, h.body.y, h.body.z, p.getYRot(), p.getXRot());
                continue;
            }
            if (Math.hypot(p.getX() - h.body.x, p.getZ() - h.body.z) > .25 || Math.abs(p.getY() - h.body.y) > .6) {
                p.teleportTo(h.level, h.body.x, h.body.y, h.body.z, p.getYRot(), p.getXRot());
            }
            p.setDeltaMovement(0, Math.min(0, p.getDeltaMovement().y), 0);
            if (h.arm) {
                float dy = ((p.getYRot() - h.outYaw + 540f) % 360f) - 180f;   // solo se ve hacia adelante (por las rendijas)
                dy = Math.max(-70f, Math.min(70f, dy));
                moveRig(h, h.cam, Math.max(-35f, Math.min(35f, p.getXRot())), h.outYaw + dy);
                if (h.t % 20 == 0) maskEnsure(h);
            } else {
                float pitch = Math.max(-12f, Math.min(30f, p.getXRot()));    // tienes la cama encima
                moveRig(h, h.cam, pitch, p.getYRot());
            }
            if (h.t % 20 == 0) p.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 20000000, 0, false, false));
            if (h.t % 40 == 0 && h.caught == null && h.arm) p.displayClientMessage(Component.literal(armMsg()), true);
            else if (h.t % 40 == 0 && h.caught == null) {
                p.displayClientMessage(Component.literal(es() ? "§7Estás debajo de la cama  §8(salta para salir)" : "§7You are under the bed  §8(jump to get out)"), true);
            }
        }
    }

    // ------------------------------------------------------------------ agarre
    private static void startGrab(ServerPlayer p, H h, StalkerEntity st) {
        if (h.g != null) return;
        Grab g = new Grab();
        g.st = st;
        g.sl = st.position();
        h.g = g;
        String id = st.def.id;
        st.silence();
        if (h.arm) {
            g.mode = "generic";
            armFlash(h, true, h.body);
            h.flash = 0;
            psnd(p, "stalker.peeker_scream", 1f, 1f);
            p.displayClientMessage(Component.literal(es() ? "§c§lTE VIO" : "§c§lIT SAW YOU"), true);
        } else if (id.equals("stalker_run")) {
            runStart(p, h, g);
        } else if (TYPES.containsKey(id)) {
            sceneStart(p, h, g, TYPES.get(id));
        } else {
            g.mode = "generic";
            psnd(p, "stalker.peeker_scream", 1f, 1f);
            snd(h.level, "stalker.run_crack_1", h.body, 2f, 1f);
            p.displayClientMessage(Component.literal(es() ? "§c§lTE VIO" : "§c§lIT SAW YOU"), true);
        }
    }

    private static StalkerEntity puppet(H h, String id, Vec3 pos, float yaw) {
        StalkerEntity e = SpawnHelper.createScripted(h.level, id, pos, yaw);
        if (e != null) e.noCulling = true;
        return e;
    }

    private static void put(StalkerEntity e, Vec3 pos, float yaw) {
        if (e == null || !e.isAlive()) return;
        e.setPos(pos.x, pos.y, pos.z);
        e.setYRot(yaw);
        e.setYHeadRot(yaw);
        e.setYBodyRot(yaw);
    }

    private static void cleanupGrab(Grab g) {
        for (StalkerEntity e : new StalkerEntity[]{g.head, g.handR, g.handL, g.pup}) {
            if (e != null && e.isAlive()) e.discard();
        }
        if (g.st != null && g.st.isAlive()) g.st.setInvisible(false);
    }

    private static void grabTick(ServerPlayer p, H h) {
        Grab g = h.g;
        p.removeEffect(MobEffects.DARKNESS);
        if (g.st != null && !g.st.isAlive() && !g.mode.equals("generic")) { /* el titere sigue solo */ }
        switch (g.mode) {
            case "run": runTick(p, h, g); break;
            case "scene": sceneTick(p, h, g); break;
            default: genericTick(p, h, g);
        }
    }

    /** Termina: se devuelve la camara y se mata al jugador. */
    private static void finish(ServerPlayer p, H h, boolean removeStalker) {
        Grab g = h.g;
        StalkerEntity st = g.st;
        cleanupGrab(g);
        HIDE.remove(h.pid);
        release(p, h);
        if (removeStalker && st != null && st.isAlive()) st.discard();
        killPlayer(p, st);
    }

    // ---- generico: te agarra, te arrastra fuera de la cama y te mata
    private static void genericTick(ServerPlayer p, H h, Grab g) {
        g.t++;
        Vec3 sl = g.st != null && g.st.isAlive() ? g.st.position() : g.sl;
        Vec3 head = new Vec3(sl.x, sl.y + 1.7, sl.z);
        Vec3 out = new Vec3(lerp(h.body.x, sl.x, .35), h.body.y + .35, lerp(h.body.z, sl.z, .35));
        if (g.t <= 10) {
            double k = .06;
            moveRig(h, new Vec3(h.cam.x + rnd() * k, h.cam.y + rnd() * k * .5, h.cam.z + rnd() * k), (float) (rnd() * 8), (float) (h.outYaw + rnd() * 12));
            if (g.t == 1) psnd(p, "game.player.hurt", 1f, .7f);
        } else if (g.t <= 34) {
            double f = ease((g.t - 10) / 24.0);
            Vec3 loc = new Vec3(lerp(h.cam.x, out.x, f) + rnd() * .04, lerp(h.cam.y, out.y, f), lerp(h.cam.z, out.z, f) + rnd() * .04);
            look(h, loc, head, 0);
            if (g.t % 6 == 0) snd(h.level, "dig.cloth", h.body, 1f, .6f);
        } else if (g.t <= GRAB_TICKS) {
            look(h, new Vec3(out.x + rnd() * .03, out.y, out.z + rnd() * .03), head, 0);
        }
        if (g.t == GRAB_TICKS) {
            p.teleportTo(h.level, out.x, h.body.y, out.z, yawTo(out, sl), -20f);
            restoreCam(p, h);
            p.removeEffect(MobEffects.INVISIBILITY);
        }
        if (g.t >= KILL_AT) finish(p, h, false);
    }

    // ---- RUN: manos bajo el borde, cabeza dada vuelta
    private static void runStart(ServerPlayer p, H h, Grab g) {
        g.mode = "run";
        Vec3 cam = P(h, -.3, 0, .09);
        Vec3 hr = P(h, .62, -.2, .3), hl = P(h, .62, .2, .3), hd = P(h, .58, 0, .3);
        g.head = puppet(h, "stalker_run_bedpeek", hd, yawTo(hd, cam));
        g.handR = puppet(h, "stalker_run_bedpeek", hr, yawTo(hr, cam));
        g.handL = puppet(h, "stalker_run_bedpeek", hl, yawTo(hl, cam));
        if (g.head != null) { g.head.setProp("parte", 0); g.head.setProp("fase", 0); }
        if (g.handR != null) g.handR.setProp("parte", 2);
        if (g.handL != null) g.handL.setProp("parte", 1);
        g.handPos[0] = hr;
        g.handPos[1] = hl;
        g.headPos = hd;
        g.st.setInvisible(true);
        p.removeEffect(MobEffects.DARKNESS);
        p.displayClientMessage(Component.literal(""), true);
        look(h, cam, P(h, .5, 0, .08), 0);
    }

    private static void runTick(ServerPlayer p, H h, Grab g) {
        g.t++;
        int t = g.t;
        Vec3 cam = P(h, -.3, 0, .09);
        if (t == 2 || t == 8) snd(h.level, "step.wood", P(h, .7, 0, 0), 1f, .6f);
        if (t == 13) snd(h.level, "dig.cloth", P(h, .6, 0, 0), 1f, .5f);
        final int HAND_R = 14, HAND_L = 21, HAND_DOWN = 9, CRAWL = 32, HEAD = 56, HEAD_DOWN = 82, CREEP = 98, SCREAM = 122, LUNGE_AT = 134, LUNGE = 6, KILL = 146;
        double[] sides = {-.2, .2};
        int[] starts = {HAND_R, HAND_L};
        StalkerEntity[] hands = {g.handR, g.handL};
        for (int i = 0; i < 2; i++) {
            int s0 = starts[i];
            double along = .62, side = sides[i], y = .3;
            if (t >= s0) {
                double f = Math.min(1, (t - s0) / (double) HAND_DOWN);
                y = lerp(.3, 0, f * f);
                if (t == s0 + HAND_DOWN) snd(h.level, "dig.wood", P(h, .6, side, 0), 1f, .45f);
            }
            if (t >= CRAWL) {
                int k = (t - CRAWL) + (i == 1 ? -5 : 0);
                double pulls = Math.max(0, Math.min(2, k / 10.0));
                double whole = Math.floor(pulls), frac = pulls - whole;
                double pg = Math.min(2, whole + ease(Math.min(1, frac * 1.6))) / 2;
                along = lerp(.62, .32, pg);
                side = sides[i] * lerp(1, .85, pg);
                if (k >= 0 && k <= 20 && k % 10 == 2) snd(h.level, "dig.cloth", P(h, along, side, 0), 1.2f, (float) (.45 + Math.random() * .15));
            }
            if (t >= LUNGE_AT) {
                double f = Math.min(1, (t - LUNGE_AT) / (double) LUNGE);
                along = lerp(.32, .05, f * f);
            }
            double jit = t >= SCREAM ? .012 : (t % 7 == i ? .004 : 0);
            Vec3 pos = P(h, along + rnd() * jit, side + rnd() * jit, y);
            g.handPos[i] = pos;
            put(hands[i], pos, yawTo(pos, cam));
        }
        double along = .58, y = .3, shake = 0;
        if (t >= HEAD) {
            double f = ease(Math.min(1, (t - HEAD) / (double) (HEAD_DOWN - HEAD)));
            y = lerp(.3, .005, f);
            if (t == HEAD + 4) snd(h.level, "dig.wood", P(h, .6, 0, .1), .6f, .4f);
        }
        if (t >= CREEP) {
            double f = ease(Math.min(1, (t - CREEP) / (double) (SCREAM - CREEP)));
            along = lerp(.58, .36, f);
            shake = .004 + .01 * f;
        } else if (t >= HEAD_DOWN) {
            shake = .004;
        }
        if (t >= SCREAM) {
            if (!g.screamed) {
                g.screamed = true;
                if (g.head != null) g.head.setProp("fase", 1);              // cara de grito
                psnd(p, "stalker.unknown_scream", 1f, .85f);
                snd(h.level, "stalker.unknown_scream", P(h, along, 0, .1), 3f, .85f);
                psnd(p, "stalker.run_crack_1", 1f, .8f);
            }
            along = .36;
            shake = .03;
            if (t >= LUNGE_AT) {
                double f = Math.min(1, (t - LUNGE_AT) / (double) LUNGE);
                along = lerp(.36, -.12, f * f);
                y = .005 + .02 * f;
                shake = .05;
            }
            if (t == LUNGE_AT + LUNGE + 2) {
                fade(p, 8);
                psnd(p, "game.player.hurt", 1f, .6f);
            }
        }
        double sj = t >= SCREAM ? .02 : (t >= HEAD_DOWN ? .004 : 0);
        Vec3 hpos = P(h, along + rnd() * sj, rnd() * sj, y);
        g.headPos = hpos;
        put(g.head, hpos, yawTo(hpos, cam));
        Vec3 target;
        if (t < HEAD + 8) {
            target = new Vec3((g.handPos[0].x + g.handPos[1].x) / 2, cam.y - .01, (g.handPos[0].z + g.handPos[1].z) / 2);
        } else {
            target = new Vec3(hpos.x, Math.min(hpos.y + .09, cam.y + .03), hpos.z);
        }
        look(h, h.cam, target, shake);
        if (t >= KILL) finish(p, h, true);
    }

    // ---- los demas: titere con su animacion
    private static int free(ServerLevel l, Vec3 pos) {          // 1 libre, 2 cama, 0 bloqueado
        BlockPos q = BlockPos.containing(pos);
        if (isBed(l, q)) return 2;
        return nonSolid(l, q) ? 1 : 0;
    }

    private static void sceneStart(ServerPlayer p, H h, Grab g, Cfg cfg) {
        g.mode = "scene";
        g.cfg = cfg;
        g.kind = cfg.kind;
        g.hid = P(h, 0, 0, -8);
        g.pup = puppet(h, "stalker_bedscene", g.hid, 0);
        if (g.pup != null) {
            g.pup.setProp("tipo", cfg.tipo);
            g.pup.setProp("fase", 0);
        }
        g.fase = 0;
        g.side = Math.random() < .5 ? -1 : 1;
        if (g.kind.equals("atras")) {
            double[][] opts = {{-1, 0}, {0, 1}, {0, -1}};
            boolean ok = false;
            for (double[] o : opts) {
                int n1 = free(h.level, P(h, o[0], o[1], .2));
                if (n1 == 1) {
                    g.backAlong = o[0] * .85;
                    g.backSide = o[1] * .75;
                    ok = true;
                    break;
                }
                if (n1 == 2 && free(h.level, P(h, o[0] * 2, o[1] * 2, .2)) == 1) {
                    g.backAlong = o[0] * 1.6;
                    g.backSide = o[1] * 1.6;
                    ok = true;
                    break;
                }
            }
            g.hasBack = ok;
            if (!ok) g.kind = "lado";
        }
        g.st.setInvisible(true);
        p.removeEffect(MobEffects.DARKNESS);
        p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 400, 0, false, false));
        p.displayClientMessage(Component.literal(""), true);
        look(h, h.cam, P(h, 1.2, 0, .15), 0);
    }

    private static void sput(H h, Grab g, Vec3 pos, int fase) {
        if (g.pup == null) return;
        if (fase >= 0 && fase != g.fase) {
            g.fase = fase;
            g.pup.setProp("fase", fase);
        }
        g.headPosS = pos;
        put(g.pup, pos, yawTo(pos, h.cam) + g.cfg.yaw);
    }

    private static void shide(Grab g) {
        g.hidden = true;
        if (g.pup != null) g.pup.setPos(g.hid.x, g.hid.y, g.hid.z);
    }

    private static Vec3 target(H h, Vec3 pos) {
        return new Vec3(pos.x, Math.min(pos.y, h.bed.y + .4), pos.z);
    }

    private static Vec3 walk(H h, Grab g, int t, int t0, int t1, Vec3 w0, Vec3 w1) {
        double f = Math.max(0, Math.min(1, (t - t0) / (double) (t1 - t0)));
        Vec3 pos = lerpP(w0, w1, f);
        sput(h, g, pos, F_CAMINAR);
        g.hidden = false;
        if ((t - t0) % 7 == 0) snd(h.level, "step.wood", new Vec3(pos.x, h.bed.y, pos.z), 1f, (float) (.55 + Math.random() * .1));
        return pos;
    }

    /** Final comun. Devuelve el shake de la camara o -1 si ya termino. */
    private static double finale(ServerPlayer p, H h, Grab g, int t, int S, Vec3 fromPos, int faseGrito) {
        if (t < S) return 0;
        if (!g.screamed) {
            g.screamed = true;
            if (faseGrito >= 0) sput(h, g, fromPos, faseGrito);
            for (int i = 0; i < g.cfg.scream.length; i++) {
                psnd(p, g.cfg.scream[i], 1f, g.cfg.pitch[i]);
                snd(h.level, g.cfg.scream[i], fromPos, 3f, g.cfg.pitch[i]);
            }
            psnd(p, "stalker.run_crack_1", 1f, .8f);
        }
        int LUNGE = S + 12, END = S + 18;
        Vec3 pos = fromPos;
        double shake = .03;
        if (t >= LUNGE) {
            double f = Math.min(1, (t - LUNGE) / (double) (END - LUNGE));
            Vec3 near = new Vec3(lerp(h.cam.x, fromPos.x, .18), h.cam.y + .02, lerp(h.cam.z, fromPos.z, .18));
            pos = lerpP(fromPos, near, f * f);
            shake = .05;
        }
        double j = .02;
        sput(h, g, new Vec3(pos.x + rnd() * j, pos.y + rnd() * j, pos.z + rnd() * j), -1);
        if (t == END + 2) {
            fade(p, 12);
            psnd(p, "game.player.hurt", 1f, .6f);
        }
        if (t >= END + 8) return -1;
        return shake;
    }

    private static void sceneTick(ServerPlayer p, H h, Grab g) {
        g.t++;
        int t = g.t;
        Cfg cfg = g.cfg;
        int s = g.side;
        double HY = cfg.headY, PY = PEEK_Y;
        Vec3 look = null;
        double shake = 0;
        double res = 0;
        boolean fin = false;
        switch (g.kind) {
            case "lado": {
                Vec3 W0 = P(h, 4.2, s * 2.6, HY), W1 = P(h, 1.3, 0, HY), D = P(h, 1.0, 0, PY), IN = P(h, .42, 0, PY);
                int S = 122;
                if (t <= 44) {
                    look = target(h, walk(h, g, t, 0, 44, W0, W1));
                } else if (t <= 62) {
                    sput(h, g, W1, F_QUIETO);
                    look = target(h, W1);
                    if (t == 50) psnd(p, "mob.warden.heartbeat", 1f, .8f);
                } else if (t <= 77) {
                    Vec3 q = lerpP(W1, D, ease((t - 62) / 15.0));
                    sput(h, g, q, F_CAER);
                    look = target(h, q);
                    if (t == 63) snd(h.level, "dig.cloth", D, 1f, .5f);
                    if (t == 72) {
                        snd(h.level, "step.wood", D, 1.5f, .4f);
                        shake = .02;
                    }
                } else if (t < S) {
                    double f = ease(Math.min(1, (t - 77) / (double) (S - 77 - 6)));
                    Vec3 q = lerpP(D, IN, f);
                    sput(h, g, q, F_LADO);
                    look = q;
                    shake = .004 + .008 * f;
                    if (t % 12 == 0) snd(h.level, "dig.cloth", q, .8f, .45f);
                    if (cfg.tipo == 5 && t == 96) psnd(p, "stalker.ansiedad_mom", .7f, .8f);
                }
                if (t >= S) {
                    res = finale(p, h, g, t, S, IN, F_LADO_GRITO);
                    look = g.headPosS;
                    shake = res;
                    fin = res == -1;
                }
                break;
            }
            case "colgado": {
                Vec3 W0 = P(h, 4.0, s * 2.4, HY), W1 = P(h, 1.3, 0, HY);
                Vec3 TOP = P(h, .62, 0, 1.15), LOW = P(h, .62, 0, PY), SW = P(h, .56, s * .05, PY);
                int S = 132;
                if (t <= 40) {
                    look = target(h, walk(h, g, t, 0, 40, W0, W1));
                } else if (t <= 50) {
                    sput(h, g, W1, F_QUIETO);
                    look = target(h, W1);
                } else if (t <= 80) {
                    if (!g.hidden) shide(g);
                    if (t == 51) {
                        snd(h.level, "step.wood", P(h, 0, 0, .6), 2f, .4f);
                        shake = .04;
                    }
                    if (t == 57) {
                        snd(h.level, "dig.wood", P(h, 0, 0, .6), 1.5f, .35f);
                        shake = .05;
                    }
                    if (t > 51 && t < 62) shake = .025;
                    if (cfg.knock && (t == 66 || t == 71 || t == 76)) {
                        snd(h.level, "dig.wood", P(h, 0, 0, .6), 2f, .5f);
                        shake = .03;
                    }
                    look = t < 66 ? P(h, .1, 0, .45) : P(h, .9, 0, .12);
                } else if (t <= 108) {
                    Vec3 q = lerpP(TOP, LOW, ease((t - 80) / 28.0));
                    g.hidden = false;
                    sput(h, g, q, F_COLGADO);
                    look = target(h, q);
                    shake = .004;
                    if (t == 82) snd(h.level, "dig.cloth", TOP, 1f, .4f);
                } else if (t < S) {
                    Vec3 q = lerpP(LOW, SW, (t - 108) / (double) (S - 108));
                    sput(h, g, q, F_COLGADO);
                    look = q;
                    shake = .006;
                }
                if (t >= S) {
                    res = finale(p, h, g, t, S, SW, F_COLGADO_GRITO);
                    look = g.headPosS;
                    shake = res;
                    fin = res == -1;
                }
                break;
            }
            case "gatear": {
                double[] stops = {7.0, 5.4, 3.8, 2.2, .55};
                int S = 112;
                if (t < 20) {
                    sput(h, g, P(h, stops[0], s * .5, PY), F_LADO);
                    g.hidden = false;
                    look = g.headPosS;
                    if (t == 4) snd(h.level, "dig.gravel", g.headPosS, 1.5f, .4f);
                } else if (t < S) {
                    int k = t - 20, seg = k / 20, inSeg = k % 20;
                    Vec3 q;
                    if (seg >= stops.length - 1) {
                        q = P(h, stops[stops.length - 1], 0, PY);
                        sput(h, g, q, F_LADO);
                        shake = .006;
                    } else {
                        double a0 = stops[seg], a1 = stops[seg + 1];
                        double sd0 = s * .5 * (1 - seg / 4.0), sd1 = s * .5 * (1 - (seg + 1) / 4.0);
                        if (inSeg < 8) {
                            double f = ease(inSeg / 8.0);
                            q = P(h, lerp(a0, a1, f), lerp(sd0, sd1, f), PY);
                            sput(h, g, q, F_GATEAR);
                            if (inSeg % 3 == 0) snd(h.level, "dig.gravel", q, 1.3f, (float) (.35 + Math.random() * .15));
                        } else {
                            q = P(h, a1, sd1, PY);
                            sput(h, g, q, F_LADO);
                        }
                    }
                    look = q;
                }
                if (t >= S) {
                    res = finale(p, h, g, t, S, P(h, .55, 0, PY), F_LADO_GRITO);
                    look = g.headPosS;
                    shake = res;
                    fin = res == -1;
                }
                break;
            }
            case "atras": {
                Vec3 W0 = P(h, 4.0, s * 2.4, HY), W1 = P(h, 1.3, 0, HY);
                Vec3 B = P(h, g.backAlong, g.backSide, PY);
                int S = 120;
                float frontYaw = yawTo(h.cam, P(h, 1.2, 0, .1)), backYaw = yawTo(h.cam, B);
                if (t <= 40) {
                    look = target(h, walk(h, g, t, 0, 40, W0, W1));
                } else if (t <= 55) {
                    sput(h, g, W1, F_QUIETO);
                    look = target(h, W1);
                } else if (t < 104) {
                    if (t == 56) shide(g);
                    if (t == 58) snd(h.level, "step.wood", P(h, 2.5, s * 2, 0), .8f, .55f);
                    if (t == 66) snd(h.level, "step.wood", P(h, 3.5, s * 3, 0), .5f, .55f);
                    if (t == 74) snd(h.level, "step.wood", P(h, 4.5, s * 4, 0), .25f, .55f);
                    if (t == 80) {
                        sput(h, g, B, F_LADO);
                        g.hidden = false;
                    }
                    if (t == 86) psnd(p, "mob.warden.heartbeat", 1f, .7f);
                    float yaw = frontYaw, pitch = 0;
                    if (t >= 84) {
                        double f = ease(Math.min(1, (t - 84) / 20.0));
                        double dy = backYaw - frontYaw;
                        while (dy > 180) dy -= 360;
                        while (dy < -180) dy += 360;
                        yaw = (float) (frontYaw + dy * f);
                        pitch = (float) (-3 * f);
                    }
                    moveRig(h, h.cam, pitch, yaw);       // la camara se da vuelta sola, despacio
                    return;
                } else if (t < S) {
                    sput(h, g, B, F_LADO);
                    look = B;
                    shake = .004;
                }
                if (t >= S) {
                    res = finale(p, h, g, t, S, B, F_LADO_GRITO);
                    look = g.headPosS;
                    shake = res;
                    fin = res == -1;
                }
                break;
            }
            case "glitch": {
                double[][] pts = {{3.2, s * 1.4}, {2.3, -s * .9}, {1.5, s * .5}, {.9, -s * .2}, {.42, 0}};
                int S = 112;
                if (t < 10) {
                    look = P(h, 1.5, 0, .15);
                } else if (t < S) {
                    int k = t - 10, raw = k / 19, i = Math.min(pts.length - 1, raw), inI = k % 19;
                    Vec3 q = P(h, pts[i][0], pts[i][1], PY);
                    if (inI < 3 && raw < pts.length) {
                        shide(g);
                        if (inI == 0) {
                            psnd(p, "mob.endermen.portal", 1f, (float) (.5 + i * .1));
                            fade(p, 2);
                        }
                        shake = .05;
                        look = q;
                    } else {
                        sput(h, g, q, F_LADO);
                        g.hidden = false;
                        look = q;
                        shake = .004;
                    }
                }
                if (t >= S) {
                    res = finale(p, h, g, t, S, P(h, .42, 0, PY), F_LADO_GRITO);
                    look = g.headPosS;
                    shake = res;
                    fin = res == -1;
                }
                break;
            }
            case "aplastado": {
                Vec3 W0 = P(h, 4.0, s * 2.2, HY), W1 = P(h, 1.4, 0, HY), FL = P(h, 1.0, 0, .28), IN = P(h, .12, 0, .26);
                int S = 82;
                if (t <= 36) {
                    look = target(h, walk(h, g, t, 0, 36, W0, W1));
                } else if (t <= 56) {
                    sput(h, g, W1, F_QUIETO);
                    look = target(h, W1);
                } else if (t <= 61) {
                    Vec3 q = lerpP(W1, FL, (t - 56) / 5.0);
                    sput(h, g, q, F_APLASTADO);
                    look = target(h, q);
                    if (t == 57) snd(h.level, "mob.slime.big", FL, 1f, .6f);
                } else if (t < S) {
                    double f = Math.min(1, (t - 61) / 12.0);
                    Vec3 q = lerpP(FL, IN, f * f);
                    sput(h, g, q, F_APLASTADO);
                    look = q;
                    shake = .006;
                    if (t % 2 == 0 && t < 74) snd(h.level, "mob.spider.step", q, 1f, 1.3f);
                }
                if (t >= S) {
                    res = finale(p, h, g, t, S, IN, -1);
                    look = g.headPosS;
                    shake = res;
                    fin = res == -1;
                }
                break;
            }
            case "golpes": {
                Vec3 IN = P(h, .55, 0, PY);
                int S = 66;
                if (t < S) {
                    if (!g.hidden) shide(g);
                    if (t == 2 || t == 10 || t == 18) {
                        snd(h.level, "step.stone", P(h, 1.5, 0, 0), 2f, .35f);
                        shake = .02;
                    }
                    for (int hT : new int[]{26, 40, 51, 59}) {
                        if (t == hT) {
                            snd(h.level, "mob.zombie.woodbreak", P(h, 0, 0, .6), 1.5f, .5f);
                            snd(h.level, "dig.wood", P(h, 0, 0, .6), 2f, .3f);
                            psnd(p, "game.player.hurt", .4f, .4f);
                        }
                        if (t >= hT && t < hT + 5) shake = .09;
                    }
                    look = t < 24 ? P(h, 1.2, 0, .15) : P(h, .1, 0, .45 - Math.min(1, (t - 24) / 40.0) * .3);
                }
                if (t >= S) {
                    if (t == S) {
                        sput(h, g, IN, F_LADO_GRITO);
                        g.hidden = false;
                    }
                    res = finale(p, h, g, t, S, IN, F_LADO_GRITO);
                    look = g.headPosS;
                    shake = res;
                    fin = res == -1;
                }
                break;
            }
            case "doblar": {
                boolean lat = cfg.lateral;
                Vec3 W0 = lat ? P(h, 1.4, s * 4.5, HY) : P(h, 4.2, s * 2.4, HY);
                Vec3 W1 = lat ? P(h, 1.4, s * .3, HY) : P(h, 1.4, 0, HY);
                Vec3 D = P(h, 1.0, 0, PY), IN = P(h, .42, 0, PY);
                int S = 142;
                if (t <= 44) {
                    look = target(h, walk(h, g, t, 0, 44, W0, W1));
                } else if (t <= 56) {
                    sput(h, g, W1, F_QUIETO);
                    look = target(h, W1);
                } else if (t <= 96) {
                    sput(h, g, W1, F_DOBLAR);
                    look = target(h, W1);
                    if (t == 64) { psnd(p, "stalker.run_crack_1", 1f, .9f); shake = .02; }
                    if (t == 74) { psnd(p, "stalker.run_crack_2", 1f, .85f); shake = .02; }
                    if (t == 84) { psnd(p, "stalker.run_crack_3", 1f, .8f); shake = .02; }
                } else if (t <= 111) {
                    Vec3 q = lerpP(W1, D, ease((t - 96) / 15.0));
                    sput(h, g, q, F_CAER);
                    look = target(h, q);
                    if (t == 106) snd(h.level, "step.wood", D, 1.5f, .4f);
                } else if (t < S) {
                    double f = ease(Math.min(1, (t - 111) / (double) (S - 111 - 6)));
                    Vec3 q = lerpP(D, IN, f);
                    sput(h, g, q, F_LADO);
                    look = q;
                    shake = .004 + .008 * f;
                    if (cfg.voice && t == 114) psnd(p, "stalker.hello_run", .7f, .85f);
                }
                if (t >= S) {
                    res = finale(p, h, g, t, S, IN, F_LADO_GRITO);
                    look = g.headPosS;
                    shake = res;
                    fin = res == -1;
                }
                break;
            }
            case "luces": {
                Vec3[] ps = {P(h, 5.5, s * 1.0, HY), P(h, 3.2, -s * .6, HY), P(h, 1.6, s * .3, PY), P(h, .8, 0, PY), P(h, .38, 0, PY)};
                int[] fs = {F_QUIETO, F_QUIETO, F_LADO, F_LADO, F_LADO};
                int S = 114;
                if (t < S) {
                    int[] fadeAt = {24, 48, 72, 96};
                    for (int fa : fadeAt) {
                        if (t == fa) {
                            fade(p, 7);
                            psnd(p, "random.click", 1f, .5f);
                            if (t >= 72) psnd(p, "mob.warden.heartbeat", 1f, .9f);
                        }
                    }
                    int idx = 0;
                    for (int k = 0; k < fadeAt.length; k++) if (t >= fadeAt[k] + 5) idx = k + 1;
                    idx = Math.min(idx, ps.length - 1);
                    sput(h, g, ps[idx], fs[idx]);
                    g.hidden = false;
                    look = target(h, ps[idx]);
                    shake = idx >= 3 ? .006 : 0;
                }
                if (t >= S) {
                    res = finale(p, h, g, t, S, P(h, .38, 0, PY), F_LADO_GRITO);
                    look = g.headPosS;
                    shake = res;
                    fin = res == -1;
                }
                break;
            }
            default:
                fin = true;
        }
        if (fin) {
            finish(p, h, false);
            return;
        }
        if (look != null) look(h, h.cam, look, shake);
    }
}
