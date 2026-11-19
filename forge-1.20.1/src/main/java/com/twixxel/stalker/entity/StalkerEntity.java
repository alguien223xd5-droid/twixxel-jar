package com.twixxel.stalker.entity;

import com.twixxel.stalker.StalkerDef;
import com.twixxel.stalker.TwixxelMod;
import com.twixxel.stalker.molang.Molang;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.Animation;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entidad generica de todos los stalkers del addon. Cada tipo se describe con un {@link StalkerDef}
 * (modelo, texturas, propiedades "twixxel:*" y maquinas de animacion convertidas del addon de Bedrock).
 */
public class StalkerEntity extends PathfinderMob implements GeoEntity, Molang.Context {
    private static final EntityDataAccessor<String> PROPS =
            SynchedEntityData.defineId(StalkerEntity.class, EntityDataSerializers.STRING);

    /** Stalkers activos (watcher / chaser) en el servidor: el spawner natural espera a que no haya ninguno. */
    public static final Set<StalkerEntity> ACTIVE = ConcurrentHashMap.newKeySet();

    // tiempos del evento "evil" (igual que el addon)
    public static final int EVIL_COUNTDOWN_TICKS = 21 * 20;
    public static final int EVIL_CHASE_TICKS = 1200;
    private static final double EVIL_KILL_DISTANCE = 1.3;
    private static final int WATCHER_LIFETIME = 2 * 60 * 20;   // se va a los 2 min
    private static final double WATCHER_TRIGGER = 2.0;         // a 2 bloques desaparece + oscuridad
    private static final int DARK_TICKS = 60;

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    public final StalkerDef def;

    private final Map<String, Double> props = new HashMap<>();
    private final Map<String, Double> vars = new HashMap<>();
    private String lastRaw = "";

    // maquinas de estados (una por entrada "animate")
    private final String[] ctlState;
    private final int[] stateTicks;
    private int evalIdx = 0;

    // comportamiento
    private UUID victimId;
    private boolean countdown = false;      // cuenta regresiva (evento natural)
    private boolean loadedFromDisk = false;
    private String musicEvent = null;
    private boolean started = false;
    private Vec3 lastPos = Vec3.ZERO;
    private int stuck = 0;
    private int chaseStartTick = 0;
    private FieldProfile profile;
    private final ScareProfile scare;
    private final PeekProfile peek;
    private final boolean arriba;
    private int phase = 0;          // 0 espera, 1 previa, 2 persecucion, 3 hablando (hey)
    private int phaseTick = 0;
    private int gaze = 0;

    public StalkerEntity(EntityType<? extends StalkerEntity> type, Level level) {
        super(type, level);
        this.def = TwixxelMod.defOf(type);
        this.ctlState = new String[def.animate.size()];
        this.stateTicks = new int[def.animate.size()];
        for (int i = 0; i < def.animate.size(); i++) {
            if (def.animate.get(i).controller != null) ctlState[i] = def.animate.get(i).controller.initial;
        }
        for (Map.Entry<String, StalkerDef.Prop> e : def.props.entrySet()) props.put(e.getKey(), e.getValue().def);
        this.noCulling = true;
        this.setInvulnerable(true);
        this.setPersistenceRequired();
        this.scare = ScareProfile.ALL.get(def.id);
        this.profile = scare == null ? FieldProfile.ALL.get(def.id) : null;
        this.peek = PeekProfile.ALL.get(def.id);
        this.arriba = "stalker_arriba".equals(def.id);
        boolean chaser = "chaser".equals(def.behavior) || profile != null || "stalker_vida".equals(def.id)
                || (scare != null && scare.chaseAs != null);
        this.setNoGravity(!def.gravity && !chaser);
        this.noPhysics = !def.gravity && !chaser && "idle".equals(def.behavior);
        // Verity y las mascotas te siguen con el pathfinding (Verity.follow / Pets): sin IA el navegador no avanza nunca
        boolean follower = "verity".equals(def.id) || def.id.startsWith("mascota");
        this.setNoAi(!chaser && !follower);
        if (!level.isClientSide) {
            entityData.set(PROPS, encode());
            // un mob avanza ~43 * atributo bloques/s (el jugador con 0.1 va a 4.3)
            var sp = getAttribute(Attributes.MOVEMENT_SPEED);
            if (sp != null) sp.setBaseValue(com.twixxel.stalker.Config.CHASE_SPEED.get() / 43.2);
        }
    }

    /** Pathfinding mejorado (el A* del addon): camino largo (128), abre puertas, rodea y sube bloques de 1 de alto sin atascarse. */
    @Override
    protected net.minecraft.world.entity.ai.navigation.PathNavigation createNavigation(Level lvl) {
        var nav = new net.minecraft.world.entity.ai.navigation.GroundPathNavigation(this, lvl);
        nav.setCanOpenDoors(true);
        nav.setCanPassDoors(true);
        nav.setCanFloat(true);
        return nav;
    }

    @Override
    public float maxUpStep() {
        return 1.0f;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 1000.0)
                .add(Attributes.MOVEMENT_SPEED, 0.13)       // ~5.6 bloques/s, como el jugador corriendo
                .add(Attributes.FOLLOW_RANGE, 128.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(PROPS, "");
    }

    // ------------------------------------------------------------------ propiedades "twixxel:*"
    private String encode() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Double> e : props.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private void decode(String raw) {
        for (String part : raw.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            try {
                props.put(part.substring(0, eq), Double.parseDouble(part.substring(eq + 1)));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void syncFromData() {
        if (!level().isClientSide) return;
        String raw = entityData.get(PROPS);
        if (!raw.equals(lastRaw)) {
            lastRaw = raw;
            decode(raw);
        }
    }

    public double getProp(String name) {
        syncFromData();
        Double v = props.get(name);
        return v == null ? 0.0 : v;
    }

    /** Cambia una propiedad (con el rango definido en el addon). Devuelve false si no existe. */
    public boolean setProp(String name, double value) {
        StalkerDef.Prop p = def.props.get(name);
        if (p == null) return false;
        if (p.min != null && p.max != null) value = Mth.clamp(value, p.min, p.max);
        if ("int".equals(p.type) || "bool".equals(p.type)) value = Math.round(value);
        Double old = props.get(name);
        if (old != null && old == value) return true;
        props.put(name, value);
        if (!level().isClientSide) entityData.set(PROPS, encode());
        return true;
    }

    // ------------------------------------------------------------------ Molang.Context
    @Override
    public double prop(String name) {
        return getProp(name);
    }

    @Override
    public double var(String name) {
        Double v = vars.get(name);
        return v == null ? 0.0 : v;
    }

    @Override
    public boolean allAnimationsFinished() {
        if (evalIdx < 0 || evalIdx >= ctlState.length) return true;
        StalkerDef.Animate a = def.animate.get(evalIdx);
        if (a.controller == null) return true;
        StalkerDef.State s = a.controller.states.get(ctlState[evalIdx]);
        return s == null || stateTicks[evalIdx] / 20.0 >= s.len;
    }

    @Override
    public double animTime() {
        return stateTicks.length > 0 && evalIdx >= 0 && evalIdx < stateTicks.length ? stateTicks[evalIdx] / 20.0 : 0.0;
    }

    @Override
    public double lifeTime() {
        return tickCount / 20.0;
    }

    /** Variables v.* (pre_animation) para el renderer. */
    public Map<String, Double> evalPreAnimation() {
        evalIdx = -1;
        for (int i = 0; i < def.preNames.size(); i++) {
            vars.put(def.preNames.get(i), def.preExprs.get(i).eval(this));
        }
        return vars;
    }

    public double renderScale() {
        evalIdx = -1;
        double s = def.scaleE.eval(this);
        return s <= 0 ? 1.0 : s;
    }

    private static int select(StalkerDef.Selector sel, StalkerEntity e) {
        if (sel == null || sel.list == null || sel.list.isEmpty()) return 0;
        if (sel.compiled == null) return 0;
        e.evalIdx = -1;
        int i = (int) Math.floor(sel.compiled.eval(e));
        return Mth.clamp(i, 0, sel.list.size() - 1);
    }

    public ResourceLocation geoLocation() {
        String key = def.geoSel.list.get(select(def.geoSel, this));
        ResourceLocation r = def.geoLoc.get(key);
        return r != null ? r : def.geoLoc.values().iterator().next();
    }

    public ResourceLocation texLocation() {
        String key = def.texSel.list.get(select(def.texSel, this));
        ResourceLocation r = def.texLoc.get(key);
        return r != null ? r : def.texLoc.values().iterator().next();
    }

    public ResourceLocation animLocation() {
        return def.animLoc;
    }

    // ------------------------------------------------------------------ animaciones (GeckoLib)
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        for (int i = 0; i < def.animate.size(); i++) {
            final int idx = i;
            int layers = def.animate.get(i).layers();
            for (int l = 0; l < layers; l++) {
                final int layer = l;
                controllers.add(new AnimationController<StalkerEntity>(this, "c" + idx + "_" + layer, 3,
                        state -> playLayer(idx, layer, state)));
            }
        }
    }

    private PlayState playLayer(int idx, int layer, AnimationState<StalkerEntity> state) {
        StalkerDef.Animate a = def.animate.get(idx);
        evalIdx = idx;
        if (a.condE != null && !Molang.truthy(a.condE.eval(this))) return PlayState.STOP;
        String name;
        if (a.controller != null) {
            StalkerDef.State s = a.controller.states.get(ctlState[idx]);
            if (s == null || layer >= s.anims.size()) return PlayState.STOP;
            StalkerDef.AnimRef r = s.anims.get(layer);
            if (r.condE != null && !Molang.truthy(r.condE.eval(this))) return PlayState.STOP;
            name = r.name;
        } else {
            name = a.anim;
        }
        state.getController().setAnimation(RawAnimation.begin().then(name, Animation.LoopType.DEFAULT));
        return PlayState.CONTINUE;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    private void tickMachines() {
        for (int i = 0; i < ctlState.length; i++) {
            StalkerDef.Animate a = def.animate.get(i);
            if (a.controller == null) continue;
            evalIdx = i;
            stateTicks[i]++;
            StalkerDef.State cur = a.controller.states.get(ctlState[i]);
            if (cur == null) {
                ctlState[i] = a.controller.initial;
                stateTicks[i] = 0;
                continue;
            }
            for (StalkerDef.Trans t : cur.compiled) {
                if (a.controller.states.containsKey(t.target) && Molang.truthy(t.expr.eval(this))) {
                    ctlState[i] = t.target;
                    stateTicks[i] = 0;
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------ ciclo de vida
    @Override
    public void onAddedToWorld() {
        super.onAddedToWorld();
        if (!level().isClientSide && (!"idle".equals(def.behavior) || profile != null || scare != null || peek != null || arriba || special())) ACTIVE.add(this);
    }

    @Override
    public void onRemovedFromWorld() {
        super.onRemovedFromWorld();
        ACTIVE.remove(this);
    }

    @Override
    public void remove(RemovalReason reason) {
        if (nav != null) nav.finish();
        if (!level().isClientSide && musicEvent != null) stopMusic();
        if (!level().isClientSide) {
            if (crack != null && crack.isAlive()) crack.discard();
            if (goatEnt != null && goatEnt.isAlive()) goatEnt.discard();
            if (mironHead != null && mironHead.isAlive()) mironHead.discard();
            if (d2Door != null && d2Phase < 3) d2SetDoor(false);
        }
        super.remove(reason);
    }

    // ---- aturdido (linterna) ----
    private int stunUntil = 0;
    private int lastMoveTick = -999;

    public void stun(int ticks) {
        stunUntil = tickCount + ticks;
        setProp("aturdido", 1);
        getNavigation().stop();
        setDeltaMovement(0, getDeltaMovement().y, 0);
    }

    public boolean isStunned() {
        return stunUntil > tickCount;
    }

    /** Se movio en los ultimos n ticks (los que te persiguen). */
    public boolean recentlyMoved(int n) {
        return tickCount - lastMoveTick <= n;
    }

    @Override
    public void tick() {
        super.tick();
        tickMachines();
        if (!level().isClientSide) {
            if (Math.hypot(getX() - xo, getZ() - zo) > 0.03) lastMoveTick = tickCount;
            if (stunUntil != 0) {
                if (isStunned()) {                        // se tapa los ojos: no se mueve y no te atrapa
                    getNavigation().stop();
                    setDeltaMovement(0, getDeltaMovement().y, 0);
                    return;
                }
                stunUntil = 0;
                setProp("aturdido", 0);
                lastMoveTick = -999;
            }
            if (tickCount % 20 == 0) setProp("calma", com.twixxel.stalker.Config.CALM.get() ? 1 : 0);   // las animaciones no tiemblan
            if (!scripted && com.twixxel.stalker.event.JumpScare.freezes(this)) {      // jumpscare en marcha: nadie se mueve
                getNavigation().stop();
                setDeltaMovement(0, getDeltaMovement().y, 0);
                return;
            }
            serverTick();
        }
    }

    private void serverTick() {
        if (scripted) return;
        if (loadedFromDisk && (def.id.startsWith("stalker_glass_") || def.id.equals("stalker_bedscene") || def.id.equals("stalker_life") || def.id.equals("stalker_run_pasillo") || def.id.equals("stalker_run_bedpeek") || def.id.equals("stalker_bed") || def.id.equals("stalker_puertas") || def.id.equals("stalker_puertas_cuerpo") || def.id.equals("stalker_puertas_mano") || def.id.equals("stalker_vigilante") || def.id.equals("stalker_goat_door"))) {
            discard();
            return;
        }
        if (loadedFromDisk && (!"idle".equals(def.behavior) || profile != null || scare != null || peek != null || arriba || special())) {
            discard();   // los eventos no sobreviven a recargar el mundo
            return;
        }
        if (profile != null) {
            fieldTick();
            return;
        }
        if (scare != null) {
            scareTick();
            return;
        }
        if (peek != null) {
            peekTick();
            return;
        }
        if (arriba) {
            arribaTick();
            return;
        }
        if (def.id.equals("stalker_vida")) {
            vidaTick();
            return;
        }
        if (def.id.equals("stalker_door2")) {
            door2Tick();
            return;
        }
        if (def.id.equals("stalker_hole")) {
            holeTick();
            return;
        }
        if (def.id.equals("stalker_agujero")) {
            agujeroTick();
            return;
        }
        switch (def.behavior) {
            case "watcher":
                watcherTick();
                break;
            case "chaser":
                chaserTick();
                break;
            case "door":
                doorTick();
                break;
            default:
                if (def.gravity && tickCount % 4 == 0) {
                    Player p = level().getNearestPlayer(this, 48);
                    if (p != null) faceTowards(p);
                }
        }
    }

    // ------------------------------------------------------------------ utilidades
    private void faceTowards(Player p) {
        faceTowards(p, 0f);
    }

    private void faceTowards(Player p, float offset) {
        double dx = p.getX() - getX(), dz = p.getZ() - getZ();
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f + offset;
        setYRot(yaw);
        setYHeadRot(yaw);
        setYBodyRot(yaw);
        yRotO = yaw;
    }

    /** Corta la musica de persecucion (escenas bajo la cama). */
    public void silence() {
        stopMusic();
        if (profile != null) {
            stopSoundAll(profile.chaseSound);
            stopSoundAll(profile.runSound);
        }
    }

    private ServerPlayer victim() {
        if (victimId == null || level().getServer() == null) return null;
        ServerPlayer p = level().getServer().getPlayerList().getPlayer(victimId);
        return (p != null && p.level() == level() && p.isAlive() && !p.isSpectator()) ? p : null;
    }

    private ServerPlayer nearestPlayer() {
        Player p = level().getNearestPlayer(this, 256);
        return p instanceof ServerPlayer sp && !sp.isSpectator() ? sp : null;
    }

    private SoundEvent soundOf(String ev) {
        var ro = TwixxelMod.SOUND_EVENTS.get(TwixxelMod.loc(ev));
        return ro == null ? null : ro.get();
    }

    private void playMusicToAll(String ev) {
        SoundEvent se = soundOf(ev);
        if (se == null || level().getServer() == null) return;
        musicEvent = ev;
        for (ServerPlayer p : level().getServer().getPlayerList().getPlayers()) {
            if (p.level() == level()) p.playNotifySound(se, SoundSource.MASTER, 1.0f, 1.0f);
        }
    }

    private void stopMusic() {
        if (level().getServer() == null || musicEvent == null) return;
        for (String v : TwixxelMod.variants(musicEvent)) {
            ClientboundStopSoundPacket pk = new ClientboundStopSoundPacket(new ResourceLocation(TwixxelMod.MODID, v), SoundSource.MASTER);
            for (ServerPlayer p : level().getServer().getPlayerList().getPlayers()) p.connection.send(pk);
        }
        musicEvent = null;
    }

    // ------------------------------------------------------------------ WATCHER (stalker normal)
    private void watcherTick() {
        if (!started) {
            started = true;
            playMusicToAll("stalker.spawn");
        }
        if (tickCount > WATCHER_LIFETIME) {
            discard();
            return;
        }
        ServerPlayer p = nearestPlayer();
        if (p == null) return;
        if (distanceTo(p) <= WATCHER_TRIGGER) {
            p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, DARK_TICKS, 0, false, false));
            p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, DARK_TICKS, 0, false, false));
            discard();
            return;
        }
        faceTowards(p);
    }

    // ------------------------------------------------------------------ CHASER (evil, goatevil, quad rojo)
    /** Lo llama el spawner natural: cuenta regresiva de 21 s y luego persecucion de 1 min. */
    public void startNaturalEvent(ServerPlayer victim) {
        this.victimId = victim.getUUID();
        this.countdown = true;
        setInvisible(true);
    }

    private void chaserTick() {
        String music = "stalker_goatevil".equals(def.id) ? "stalker.goatevil" : "stalker.evil";
        if (!started) {
            started = true;
            if (victimId == null) {
                ServerPlayer n = nearestPlayer();
                if (n != null) victimId = n.getUUID();
            }
            playMusicToAll(music);
            if (countdown) setInvisible(true);
            else chaseStartTick = tickCount;
        }
        ServerPlayer v = victim();
        if (v == null) {
            ServerPlayer n = nearestPlayer();
            if (n == null) {
                if (tickCount > 200) discard();
                return;
            }
            victimId = n.getUUID();
            v = n;
        }

        if (com.twixxel.stalker.event.BedHide.guard(this, v)) {
            getNavigation().stop();
            return;
        }

        // ---- cuenta regresiva: 400 -> 0 bloques en 21 s
        if (countdown && tickCount < EVIL_COUNTDOWN_TICKS) {
            getNavigation().stop();
            if (tickCount % 4 == 0) {
                int blocks = Math.max(0, Math.round(400f * (1f - tickCount / (float) EVIL_COUNTDOWN_TICKS)));
                v.connection.send(new ClientboundSetActionBarTextPacket(
                        Component.literal(com.twixxel.stalker.event.Lang.es() ? "§cEstá a §f" + blocks + "§c bloques de ti" : "§cIt is §f" + blocks + "§c blocks away from you")));
            }
            return;
        }
        if (countdown && tickCount == EVIL_COUNTDOWN_TICKS) {
            setInvisible(false);
            placeBehind(v);
            chaseStartTick = tickCount;
        }

        // ---- persecucion
        setProp("run", 1);
        if (tickCount - chaseStartTick > EVIL_CHASE_TICKS) {
            discard();
            return;
        }
        if (distanceTo(v) <= EVIL_KILL_DISTANCE) {
            com.twixxel.stalker.event.JumpScare.kill(v, this);
            discard();
            return;
        }
        getLookControl().setLookAt(v, 30f, 30f);
        navChase(v);
    }

    /**
     * Garantiza la velocidad de persecucion configurada: mide lo que avanzo en el ultimo tick y, si va mas lento,
     * se empuja hacia el siguiente punto del camino (no depende de las unidades internas del atributo).
     */
    public boolean hasProp(String n) {
        return def.props.containsKey(n);
    }

    private ChaseNav nav;
    private int lastMov = 0;

    /** Persecucion con el pathfinding del addon (A* con escaleras, trepar paredes, parkour, gateo, nadar, trampillas). */
    private void navChase(net.minecraft.world.entity.player.Player v) {
        if (nav == null) nav = new ChaseNav(this);
        int mov = nav.tickChase(v, com.twixxel.stalker.Config.CHASE_SPEED.get() / 20.0, profile != null ? profile.yawOffset : 0f);
        double catchD = profile != null ? profile.catchDist : 1.8;
        nav.watchdog(v, Math.hypot(v.getX() - getX(), v.getZ() - getZ()), catchD);
        if (def.props.containsKey("mov")) setProp("mov", mov);
        if ("stalker_run".equals(def.id) && profile != null && mov != lastMov) {
            if (mov == 1) setProp("fase", 7);
            else if (mov == 2) setProp("fase", 6);
            else setProp(profile.runProp == null ? "fase" : profile.runProp, profile.runVal);
        }
        lastMov = mov;
    }

    private void boostAlongPath() {
        if (!onGround()) return;
        double target = com.twixxel.stalker.Config.CHASE_SPEED.get() / 20.0;
        double dx, dz;
        Path path = getNavigation().getPath();
        if (path != null && !path.isDone()) {
            Vec3 n = path.getNextEntityPos(this);
            dx = n.x - getX();
            dz = n.z - getZ();
        } else {                                                    // sin camino: directo hacia la victima
            net.minecraft.world.entity.player.Player v = victimId == null ? null : level().getPlayerByUUID(victimId);
            if (v == null) return;
            dx = v.getX() - getX();
            dz = v.getZ() - getZ();
        }
        double h = Math.hypot(dx, dz);
        if (h < 0.05) return;
        // velocidad fija (la friccion del suelo se aplica DESPUES de mover, asi que el desplazamiento es este)
        Vec3 dm = getDeltaMovement();
        setDeltaMovement(dx / h * target, dm.y, dz / h * target);
    }

    /** Coloca la entidad 5 bloques detras del jugador en un hueco libre. */
    private void placeBehind(ServerPlayer v) {
        if (nav != null) nav.replan();
        Vec3 look = v.getLookAngle();
        double len = Math.hypot(look.x, look.z);
        if (len < 1e-4) len = 1;
        double bx = v.getX() - look.x / len * 5.0;
        double bz = v.getZ() - look.z / len * 5.0;
        for (int dy : new int[]{0, 1, -1, 2, -2, 3}) {
            BlockPos pos = BlockPos.containing(bx, v.getY() + dy, bz);
            if (isFree(pos)) {
                moveTo(bx, pos.getY(), bz, v.getYRot() + 180f, 0f);
                getNavigation().stop();
                return;
            }
        }
        moveTo(v.getX(), v.getY(), v.getZ(), v.getYRot() + 180f, 0f);
    }

    private boolean isFree(BlockPos pos) {
        BlockState a = level().getBlockState(pos), b = level().getBlockState(pos.above());
        BlockState below = level().getBlockState(pos.below());
        return a.getCollisionShape(level(), pos).isEmpty() && b.getCollisionShape(level(), pos.above()).isEmpty()
                && !below.getCollisionShape(level(), pos.below()).isEmpty() && a.getFluidState().isEmpty();
    }

    // ------------------------------------------------------------------ CAMPO ABIERTO (goat, spotted, smiler, ...)
    private void playTo(ServerPlayer p, String ev) {
        SoundEvent se = soundOf(ev);
        if (se != null && p != null) p.playNotifySound(se, SoundSource.MASTER, 1.0f, 1.0f);
    }

    private void playAll(String ev) {
        SoundEvent se = soundOf(ev);
        if (se == null || level().getServer() == null) return;
        for (ServerPlayer p : level().getServer().getPlayerList().getPlayers())
            if (p.level() == level()) p.playNotifySound(se, SoundSource.MASTER, 1.0f, 1.0f);
    }

    private void stopSoundAll(String ev) {
        if (ev == null || level().getServer() == null) return;
        for (String v : TwixxelMod.variants(ev)) {
            ClientboundStopSoundPacket pk = new ClientboundStopSoundPacket(new ResourceLocation(TwixxelMod.MODID, v), SoundSource.MASTER);
            for (ServerPlayer p : level().getServer().getPlayerList().getPlayers()) p.connection.send(pk);
        }
    }

    private boolean looking(ServerPlayer p, double cos) {
        return looking(p, cos, true);
    }

    private boolean lookingBig(ServerPlayer p) {
        FieldProfile pr = profile;
        Vec3 eye = p.getEyePosition(), dir = p.getLookAngle();
        for (double hy : pr.gazeHeights) {
            Vec3 to = position().add(0, hy, 0).subtract(eye);
            double d = to.length();
            if (d > pr.gazeRange || d < 0.5) continue;
            double cos = pr.gazeRadius > 0 ? Math.cos(Math.atan(pr.gazeRadius / d) + 0.05) : pr.gazeCos;
            if (dir.dot(to) / d < cos) continue;
            if (level().clip(new net.minecraft.world.level.ClipContext(eye, position().add(0, hy, 0), net.minecraft.world.level.ClipContext.Block.VISUAL,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, p)).getType() == net.minecraft.world.phys.HitResult.Type.MISS) return true;
        }
        return false;
    }

    private String chaseText() {
        FieldProfile pr = profile;
        return pr.textEn != null && !com.twixxel.stalker.event.Lang.es() ? pr.textEn : pr.text;
    }

    private int stepIdx = 0, stepTick = 0;

    private void beginStep(ServerPlayer p) {
        FieldProfile.Step st = profile.steps.get(stepIdx);
        stepTick = 0;
        boolean es = com.twixxel.stalker.event.Lang.es();
        if (st.prop != null) setProp(st.prop, st.val);
        String snd = !es && st.soundEn != null ? st.soundEn : st.sound;
        if (snd != null) playAll(snd);
    }

    private void stepTick(ServerPlayer p) {
        FieldProfile pr = profile;
        FieldProfile.Step st = pr.steps.get(stepIdx);
        boolean es = com.twixxel.stalker.event.Lang.es();
        faceTowards(p, pr.yawOffset);
        stepTick++;
        String tx = !es && st.textEn != null ? st.textEn : st.text;
        if (tx != null && stepTick % 10 == 1)
            for (ServerPlayer q : ((net.minecraft.server.level.ServerLevel) level()).players())
                if (q.distanceTo(this) < 96) q.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(tx)));
        for (int i = 0; i < st.crackAt.length; i++) if (stepTick == st.crackAt[i]) playAll(st.crackSnd[i]);
        int len = !es && st.ticksEn > 0 ? st.ticksEn : st.ticks;
        if (stepTick < len) return;
        if (tx != null) for (ServerPlayer q : ((net.minecraft.server.level.ServerLevel) level()).players()) q.connection.send(new ClientboundSetActionBarTextPacket(Component.empty()));
        if (++stepIdx < pr.steps.size()) beginStep(p);
        else startFieldChase(p);
    }

    private boolean looking(ServerPlayer p, double cos, boolean needLos) {
        Vec3 eye = p.getEyePosition();
        Vec3 to = position().add(0, getBbHeight() * 0.6, 0).subtract(eye);
        double d = to.length();
        if (d > 64 || d < 0.1) return false;
        if (p.getLookAngle().dot(to) / d < cos) return false;
        return !needLos || p.hasLineOfSight(this);
    }

    private void lightAway(ServerPlayer p) {
        playTo(p, "stalker.peeker_vanish");
        p.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(com.twixxel.stalker.event.Lang.es()
                ? "\u00a7eLa luz lo ahuyent\u00f3..." : "\u00a7eThe light scared it away...")));
        if (level() instanceof net.minecraft.server.level.ServerLevel sl)
            sl.sendParticles(net.minecraft.core.particles.ParticleTypes.EXPLOSION, getX(), getY() + 1, getZ(), 1, 0, 0, 0, 0);
        stopSoundAll(profile.chaseSound);
        stopSoundAll(profile.runSound);
        discard();
    }

    private void finishField() {
        if (profile.waitSound != null && profile.waitRepeat == 0) stopSoundAll(profile.waitSound);
        if (profile.redlight) {
            for (Player q : level().players()) if (q instanceof ServerPlayer sp) sp.connection.send(new ClientboundSetActionBarTextPacket(Component.empty()));
        }
        stopSoundAll(profile.chaseSound);
        stopSoundAll(profile.runSound);
        discard();
    }

    private void fieldTick() {
        FieldProfile pr = profile;
        if (!started) {
            started = true;
            if (pr.waitSound != null && pr.waitRepeat == 0) {
                playMusicToAll(pr.waitSound);
            }
        }
        ServerPlayer p = phase == 0 ? nearestPlayer() : victim();
        if (p == null) {
            if (phase != 0 || tickCount > 200) {
                finishField();
            }
            return;
        }
        phaseTick++;
        if (pr.lightScare && tickCount % 4 == 0 && com.twixxel.stalker.event.SpawnHelper.nearLight((net.minecraft.server.level.ServerLevel) level(), p.blockPosition(), 2)) {
            lightAway(p);
            return;
        }
        switch (phase) {
            case 0: {   // espera
                if (pr.stayTicks > 0 && tickCount > pr.stayTicks) {
                    stopMusic();
                    discard();
                    return;
                }
                faceTowards(p, pr.yawOffset);
                if (pr.waitRepeat > 0 && tickCount % pr.waitRepeat == 1) {
                    playTo(p, pr.waitSound);
                    if (pr.waitText != null) p.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(pr.waitText)));
                }
                if (pr.vanishNear > 0 && distanceTo(p) <= pr.vanishNear) {
                    int dark = pr.darkMin + (pr.darkMax > pr.darkMin ? random.nextInt(pr.darkMax - pr.darkMin + 1) : 0);
                    p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, dark, 0, false, false));
                    p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, dark, 0, false, false));
                    stopMusic();
                    discard();
                    return;
                }
                boolean trig;
                if (pr.gazeTicks > 0) {
                    gaze = (pr.gazeHeights != null ? lookingBig(p) : looking(p, pr.gazeCos)) ? gaze + 1 : 0;
                    trig = gaze >= pr.gazeTicks || (pr.triggerDist > 0 && distanceTo(p) <= pr.triggerDist);
                } else if (pr.watchTicks > 0) {
                    trig = phaseTick >= pr.watchTicks || (pr.triggerDist > 0 && distanceTo(p) <= pr.triggerDist);
                } else {
                    trig = distanceTo(p) <= pr.triggerDist && phaseTick >= pr.graceTicks;
                }
                if (!trig) return;
                victimId = p.getUUID();
                stopMusic();
                if (pr.action == FieldProfile.Action.VANISH) {
                    int dark = pr.darkMin + (pr.darkMax > pr.darkMin ? random.nextInt(pr.darkMax - pr.darkMin + 1) : 0);
                    p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, dark, 0, false, false));
                    p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, dark, 0, false, false));
                    if (pr.vanishSound != null) playTo(p, pr.vanishSound);
                    if (getDefId().equals("stalker_watcher")) com.twixxel.stalker.event.WatcherClock.arm2(p);
                    if (pr.followUp != null) com.twixxel.stalker.event.SpawnHelper.spawnChase(p, pr.followUp, true);
                    discard();
                } else if (pr.action == FieldProfile.Action.FREEZE_TALK) {
                    phase = 3;
                    phaseTick = 0;
                    playTo(p, pr.freezeSound);
                    p.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(pr.freezeText)));
                    p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, pr.freezeTicks, 10, false, false));
                    p.addEffect(new MobEffectInstance(MobEffects.JUMP, pr.freezeTicks, 200, false, false));
                } else if (!pr.steps.isEmpty()) {
                    if (pr.waitSound != null && pr.waitRepeat == 0) stopSoundAll(pr.waitSound);     // se corta la musica
                    phase = 6;
                    phaseTick = 0;
                    stepIdx = 0;
                    beginStep(p);
                } else if (pr.preTicks > 0) {
                    phase = 1;
                    phaseTick = 0;
                    if (pr.preProp != null) setProp(pr.preProp, pr.preVal);
                    if (pr.preSound != null) playAll(pr.preSound);
                    if (pr.preText != null) p.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(!com.twixxel.stalker.event.Lang.es() && pr.preTextEn != null ? pr.preTextEn : pr.preText)));
                } else {
                    startFieldChase(p);
                }
                return;
            }
            case 1:     // animacion previa quieto
                faceTowards(p, pr.yawOffset);
                if (phaseTick >= pr.preTicks) startFieldChase(p);
                return;
            case 3: {   // hey: habla, luego se va y deja oscuridad
                faceTowards(p, pr.yawOffset);
                if (phaseTick >= pr.freezeTicks) {
                    int dark = pr.darkMin + random.nextInt(pr.darkMax - pr.darkMin + 1);
                    p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, dark, 0, false, false));
                    p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, dark, 0, false, false));
                    p.connection.send(new ClientboundSetActionBarTextPacket(Component.empty()));
                    discard();
                }
                return;
            }
            case 6:     // pasos previos (hablar, tronar los huesos...)
                stepTick(p);
                return;
            case 5:     // miron: asomado por la ventana
                mironScene(p);
                return;
            default:    // 2: persecucion
                fieldChase(p);
        }
    }

    private void startFieldChase(ServerPlayer p) {
        phase = 2;
        phaseTick = 0;
        setProp(profile.runProp, profile.runVal);
        if (profile.runProp2 != null) setProp(profile.runProp2, profile.runVal2);
        if (profile.chaseSound != null) playAll(profile.chaseSound);
        if (profile.runSound != null) playTo(p, profile.runSound);
        if (profile.text != null) p.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(chaseText())));
        lastPos = position();
        stuck = 0;
    }

    private int rompeUntil = 0;
    private Vec3 rlLast = Vec3.ZERO;

    /** Lo ve este jugador? (en su pantalla, a menos de 80 bloques y sin bloques opacos en medio; el vidrio no tapa) */
    private boolean seenBy(ServerPlayer q) {
        Vec3 eye = q.getEyePosition(), dir = q.getLookAngle();
        for (double h : new double[]{2.6, 1.5, 0.4}) {
            Vec3 t = new Vec3(getX(), getY() + h, getZ());
            Vec3 d = t.subtract(eye);
            double len = d.length();
            if (len < 0.1) return true;
            if (len > 80 || dir.dot(d) / len < 0.70) continue;
            if (level().clip(new net.minecraft.world.level.ClipContext(eye, t, net.minecraft.world.level.ClipContext.Block.VISUAL,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, q)).getType() == net.minecraft.world.phys.HitResult.Type.MISS) return true;
        }
        return false;
    }

    /** Si se atasca rompe UN bloque de 1x1x1 hacia el jugador. */
    private void breakAhead(ServerPlayer v) {
        Vec3 d = new Vec3(v.getX() - getX(), 0, v.getZ() - getZ());
        if (d.lengthSqr() < 1e-4) return;
        d = d.normalize();
        for (int dy = 0; dy <= 1; dy++) {
            BlockPos b = BlockPos.containing(getX() + d.x, getY() + dy, getZ() + d.z);
            BlockState st = level().getBlockState(b);
            if (st.isAir() || st.getDestroySpeed(level(), b) < 0 || st.getCollisionShape(level(), b).isEmpty()) continue;
            level().destroyBlock(b, false);
            setProp("rompe", 1);
            rompeUntil = phaseTick + 12;
            return;
        }
    }

    private void redlightChase() {
        FieldProfile pr = profile;
        java.util.List<ServerPlayer> ps = new java.util.ArrayList<>();
        boolean seen = false;
        for (Player q : level().players()) {
            if (!(q instanceof ServerPlayer sp) || !sp.isAlive() || sp.isSpectator()) continue;
            ps.add(sp);
            if (!com.twixxel.stalker.event.BedHide.isHidden(sp.getUUID()) && seenBy(sp)) seen = true;
        }
        if (rompeUntil > 0 && phaseTick >= rompeUntil) {
            setProp("rompe", 0);
            rompeUntil = 0;
        }
        if (phaseTick % 40 == 0 || victim() == null) {                // persigue al mas cercano
            ServerPlayer best = null;
            double bd = 1e18;
            for (ServerPlayer sp : ps) {
                if (com.twixxel.stalker.event.BedHide.isHidden(sp.getUUID())) continue;
                double dd = distanceToSqr(sp);
                if (dd < bd) {
                    bd = dd;
                    best = sp;
                }
            }
            if (best != null) setVictim(best);
        }
        ServerPlayer v = victim();
        if (v == null) {
            getNavigation().stop();
            return;
        }
        if (phaseTick % 2 == 0) {                                       // a cuantos bloques esta (abajo, en rojo)
            boolean es = com.twixxel.stalker.event.Lang.es();
            for (ServerPlayer sp : ps) {
                int dist = (int) Math.round(distanceTo(sp));
                sp.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(es
                        ? "\u00a7cEST\u00c1 A " + dist + " BLOQUES DE TI" : "\u00a7cIT IS " + dist + " BLOCKS AWAY FROM YOU")));
            }
        }
        faceTowards(v, pr.yawOffset);
        if (seen) {                                                     // luz roja: lo miran -> se queda quieto
            getNavigation().stop();
            Vec3 dm = getDeltaMovement();
            setDeltaMovement(0, dm.y, 0);
            setProp("run", 0);
            lastPos = position();
            return;
        }
        for (ServerPlayer sp : ps) {                                    // luz verde: si te alcanza, te mata
            if (com.twixxel.stalker.event.BedHide.isHidden(sp.getUUID())) continue;
            if (Math.hypot(sp.getX() - getX(), sp.getZ() - getZ()) <= pr.catchDist && Math.abs(sp.getY() - getY()) <= 2.5) {
                com.twixxel.stalker.event.JumpScare.kill(sp, this);
                finishField();
                return;
            }
        }
        setProp("run", 1);
        navChase(v);
        if (phaseTick % 60 == 0) {                                      // atascado 3 s: rompe un bloque
            if (position().distanceToSqr(lastPos) < 0.5 && distanceTo(v) > 2.5) breakAhead(v);
            lastPos = position();
        }
    }

    private int pitMode = 0, pitT = 0;

    /** El jugador esta en un hoyo estrecho (4 paredes a 4 bloques, mas bajo que el stalker, cielo abierto)? */
    private boolean inPit(ServerPlayer v) {
        if (getY() - v.getY() < 1.8 || !level().canSeeSky(v.blockPosition())) return false;
        BlockPos b = v.blockPosition();
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            boolean hit = false;
            for (int k = 1; k <= 4 && !hit; k++) {
                BlockPos q = b.offset(d[0] * k, 0, d[1] * k);
                if (!level().getBlockState(q).getCollisionShape(level(), q).isEmpty()) hit = true;
            }
            if (!hit) return false;
        }
        return true;
    }

    /** Goat jester: llega al borde del hoyo, truena y baja de cabeza por la pared. true = este tick lo maneja el. */
    private boolean pitStep(ServerPlayer v, double horiz) {
        FieldProfile pr = profile;
        if (pitMode == 0) {
            if (horiz > 10 || !inPit(v)) return false;
            pitMode = 3;
            pitT = 0;
            getNavigation().stop();
            setProp("fase", 3);
        }
        pitT++;
        faceTowards(v, pr.yawOffset);
        if (pitMode == 3) {
            if (pitT == 20 || pitT == 28) playAll("stalker.run_crack");
            if (pitT >= 40) {
                pitMode = 4;
                pitT = 0;
                setProp("fase", 4);
                setNoGravity(true);
                noPhysics = true;
            }
            return true;
        }
        if (!inPit(v)) {                          // saliste del hoyo: sigue la persecucion normal
            pitMode = 0;
            noPhysics = false;
            setNoGravity(false);
            setProp(pr.runProp, pr.runVal);
            return false;
        }
        double dx = v.getX() - getX(), dz = v.getZ() - getZ();
        double h = Math.hypot(dx, dz), step = Math.min(h, .06);
        double ny = Math.max(v.getY(), getY() - .12);
        if (h > 1e-3) setPos(getX() + dx / h * step, ny, getZ() + dz / h * step);
        else setPos(getX(), ny, getZ());
        if (h <= 2 && Math.abs(v.getY() - getY()) <= 1.5) {
            noPhysics = false;
            setNoGravity(false);
            com.twixxel.stalker.event.JumpScare.kill(v, this);
            finishField();
        }
        return true;
    }

    private void fieldChase(ServerPlayer v) {
        FieldProfile pr = profile;
        if (com.twixxel.stalker.event.BedHide.guard(this, v)) {
            getNavigation().stop();
            return;
        }
        if (phaseTick >= pr.chaseTicks) {
            if (pr.darkAfter > 0) {
                v.addEffect(new MobEffectInstance(MobEffects.DARKNESS, pr.darkAfter, 0, false, false));
                v.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, pr.darkAfter, 0, false, false));
            }
            finishField();
            return;
        }
        if (pr.redlight) {
            redlightChase();
            return;
        }
        if (pr.windowScene && mironWindow(v)) return;
        if (phaseTick % 10 == 0 && def.props.containsKey("alto")) setProp("alto", ceilingHeight() >= 3 ? 1 : 0);
        if (pr.darkDuring && phaseTick % 40 == 0) v.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 60, 0, false, false));
        if (pr.chaseSoundRepeat > 0 && phaseTick % pr.chaseSoundRepeat == 0) playAll(pr.chaseSound);
        if (pr.chaseSound2 != null && phaseTick == pr.chaseSound2At) playAll(pr.chaseSound2);
        if (pr.runSoundEvery > 0 && phaseTick % pr.runSoundEvery == 0) {
            playTo(v, pr.runSound);
            if (pr.text != null) v.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(chaseText())));
        }
        double horiz = Math.hypot(v.getX() - getX(), v.getZ() - getZ());
        if (horiz <= pr.catchDist && Math.abs(v.getY() - getY()) <= pr.catchDy) {
            com.twixxel.stalker.event.JumpScare.kill(v, this);
            finishField();
            return;
        }
        if (pr.pitClimb && pitStep(v, horiz)) return;
        faceTowards(v, pr.yawOffset);
        navChase(v);
    }

    // ------------------------------------------------------------------ STALKER DE LA PUERTA
    private int doorPhase = 0;   // 0 espera afuera de la puerta, 1 animacion
    private int doorT = 0, doorGaze = 0, doorInside = 0, doorOut = 0;

    public void setVictim(ServerPlayer p) {
        this.victimId = p.getUUID();
    }

    public boolean isDoorStalker() {
        return "door".equals(def.behavior);
    }

    private void doorTick() {
        ServerPlayer p = victim();
        if (p == null) {
            discard();
            return;
        }
        doorT++;
        if (doorPhase == 0) {
            if (doorT > 3600) {            // 3 min sin que hagas nada: se va
                discard();
                return;
            }
            if (doorT % 80 == 1) {
                playTo(p, "stalker.door_voice");
                p.connection.send(new ClientboundSetActionBarTextPacket(Component.literal("\u00a7cGO OUTSIDE")));
            } else if (doorT % 80 == 33) {
                p.connection.send(new ClientboundSetActionBarTextPacket(Component.empty()));
            }
            // saliste a cielo abierto estando el stalker afuera: se va y viene RUN
            boolean sky = p.level().canSeeSky(p.blockPosition());
            if (!sky) doorInside++;
            doorOut = (sky && doorInside >= 20) ? doorOut + 1 : 0;
            if (doorOut >= 10) {
                p.connection.send(new ClientboundSetActionBarTextPacket(Component.empty()));
                com.twixxel.stalker.event.Director.scheduleRun(p, 60);
                discard();
                return;
            }
            // mirar la puerta 0.5 s activa la animacion
            doorGaze = (distanceTo(p) <= 24 && looking(p, 0.966, false)) ? doorGaze + 1 : 0;
            if (doorGaze >= 10) {
                doorPhase = 1;
                doorT = 0;
                setProp("activo", 1);
                stopSoundAll("stalker.door_voice");
                playTo(p, "stalker.door_anim");
                p.connection.send(new ClientboundSetActionBarTextPacket(Component.empty()));
                p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 148, 10, false, false));
                p.addEffect(new MobEffectInstance(MobEffects.JUMP, 148, 200, false, false));
            }
        } else if (doorT >= 148) {         // acaba la animacion: te puedes mover; tienes 1 min para salir
            com.twixxel.stalker.event.Director.startRunWatch(p);
            discard();
        }
    }

    // ------------------------------------------------------------------ VENTANA / MUJER / ARBOL
    private int sT = 0, sGaze = 0, sPhase = 0;
    private java.util.List<BlockPos> glass = new java.util.ArrayList<>();
    private boolean glassBroken = false;

    public void setGlass(java.util.List<BlockPos> g) {
        this.glass = g;
    }

    public boolean locksDoors() {
        return scare != null && scare.lockDoors;
    }

    private boolean lookingAtEye(ServerPlayer p, double cos, double range, double eye) {
        Vec3 to = position().add(0, eye, 0).subtract(p.getEyePosition());
        double d = to.length();
        if (d > range || d < 0.1) return false;
        return p.getLookAngle().dot(to) / d >= cos;
    }

    private boolean sInit = false, followed = false, postDone = false;
    private int sCalls = 0, sShift = 0;

    private boolean special() {
        return def.id.equals("stalker_vida") || def.id.equals("stalker_door2") || def.id.equals("stalker_hole") || def.id.equals("stalker_agujero");
    }
    private StalkerEntity crack;
    private boolean scripted = false;
    private boolean goatRoll = false;
    private StalkerEntity goatEnt;
    private int goatL = 0, goatT = -1, goatIn = 0;
    private Vec3 goatS, goatG, goatEat, goatEatAt;
    private float goatYaw;
    private double scareY = 0, winX, winY, winZ, winNX, winNZ;

    /** Posicion de la ventana y su normal (hacia afuera, donde esta el stalker). */
    public void setWindow(double x, double y, double z, double nx, double nz) {
        winX = x;
        winY = y;
        winZ = z;
        winNX = nx;
        winNZ = nz;
    }

    /** Empieza ya la persecucion contra el jugador (stalkers que salen de una ventana rota). */
    public void forceChase(ServerPlayer p, boolean behind) {
        victimId = p.getUUID();
        if (profile != null) {
            started = true;
            phase = 2;
            phaseTick = 0;
            startFieldChase(p);
            if (behind) placeBehind(p);
        } else {
            countdown = false;
            chaseStartTick = tickCount;
            if (behind) placeBehind(p);
        }
    }

    /** Cambia el perfil de campo (persecuciones con musica/texto propios) antes de forceChase. */
    public void setFieldProfile(FieldProfile p) {
        this.profile = p;
    }

    private boolean hvFull = false;

    public void setHvFull(boolean b) {
        hvFull = b;
    }

    /** hello 3: sube hasta asomarse, si lo miras baja y dice "Vendre esta noche" (de noche tocan la puerta). */
    private void riseSeq(ServerPlayer p) {
        ScareProfile sc = scare;
        if (sPhase == 0) {
            setProp(sc.riseProp, Math.min(1.0, sT / (double) sc.riseTicks));
            if (sT > sc.riseTicks) {
                boolean seen = lookingAtEye(p, sc.gazeCos, sc.range, sc.eye);
                sGaze = seen ? sGaze + 1 : Math.max(0, sGaze - 1);
                if (sGaze >= sc.gazeTicks || sT >= sc.stayTicks) {
                    sPhase = 1;
                    sT = 0;
                    SoundEvent se = soundOf("stalker.peeker_scream");
                    if (seen && se != null) p.playNotifySound(se, SoundSource.MASTER, 0.5f, 0.7f);
                }
            }
            return;
        }
        setProp(sc.riseProp, Math.max(0.0, 1.0 - sT / (double) sc.riseDown));
        if (sT >= sc.riseDown) {
            p.sendSystemMessage(Component.literal(es() ? "\u00a7c<HELLO> Vendr\u00e9 esta noche..." : "\u00a7c<HELLO> I will come tonight..."));
            net.minecraft.nbt.CompoundTag d = p.getPersistentData();
            d.putString("twx_hv_phase", "noche");
            d.putLong("twx_hv_next", level().getDayTime() / 24000L + 2);
            if (hvFull) d.putBoolean("twx_hv_now", true);
            discard();
        }
    }

    /** Empieza (con la animacion previa, si tiene) la persecucion de un stalker de campo abierto. */
    public void forceStart(ServerPlayer p) {
        victimId = p.getUUID();
        started = true;
        if (profile != null && profile.preTicks > 0) {
            phase = 1;
            phaseTick = 0;
            if (profile.preProp != null) setProp(profile.preProp, profile.preVal);
            if (profile.preSound != null) playAll(profile.preSound);
        } else {
            forceChase(p, false);
        }
    }

    private int ceilingHeight() {
        BlockPos b = blockPosition();
        int h = 0;
        while (h < 4 && level().getBlockState(b.above(h)).getCollisionShape(level(), b.above(h)).isEmpty()) h++;
        return h;
    }

    /** La secuencia termino: esta misma entidad pasa a perseguir al jugador. */
    private void becomeChaser(ServerPlayer p) {
        FieldProfile fp = FieldProfile.ALL.get(scare.chaseAs);
        if (fp == null) {
            discard();
            return;
        }
        if (!glassBroken) {
            glassBroken = true;
            for (BlockPos bp : glass) level().destroyBlock(bp, false);
        }
        if (crack != null) {
            crack.discard();
            crack = null;
        }
        profile = fp;
        started = true;
        phase = 2;
        phaseTick = 0;
        victimId = p.getUUID();
        setNoGravity(false);
        noPhysics = false;
        setNoAi(false);
        if (def.props.containsKey("alto")) setProp("alto", ceilingHeight() >= 3 ? 1 : 0);
        startFieldChase(p);
    }

    public void setCrack(StalkerEntity c) {
        this.crack = c;
    }

    /** Entidad manejada por otra escena (no corre su propia IA). */
    /** Cabra/stalker de campo abierto esperando (aun no te persigue). */
    public boolean isFieldWaiting() {
        return profile != null && phase == 0 && !scripted && !level().isClientSide;
    }

    /** Jugador al que esta persiguiendo ahora (null si no persigue a nadie). */
    public ServerPlayer chasePlayer() {
        if (scripted || level().isClientSide) return null;
        boolean chasing = profile != null ? phase == 2 : ("chaser".equals(def.behavior) && getProp("run") == 1);
        if (!chasing) return null;
        ServerPlayer v = victim();
        return v != null ? v : nearestPlayer();
    }

    /** El RUN con la skin del sueño 4. */
    public boolean hasSkin() {
        return getProp("skin") == 1;
    }

    /** Persecucion de campo abierto en curso. */
    public boolean isFieldChasing() {
        return profile != null && phase == 2 && !scripted;
    }

    /** Lo convierte en titere de una escena: sin IA, sin fisicas, sin musica. */
    public void freezeForScene() {
        silence();
        scripted = true;
        setNoGravity(true);
        noPhysics = true;
        getNavigation().stop();
        setDeltaMovement(Vec3.ZERO);
    }

    public boolean isScripted() {
        return scripted;
    }

    public String getDefId() {
        return def.id;
    }

    public void setScripted(boolean v) {
        this.scripted = v;
    }

    private void bloodAtWindow(int n) {
        if (!(level() instanceof net.minecraft.server.level.ServerLevel sl)) return;
        sl.sendParticles(new net.minecraft.core.particles.BlockParticleOption(net.minecraft.core.particles.ParticleTypes.BLOCK,
                        net.minecraft.world.level.block.Blocks.REDSTONE_BLOCK.defaultBlockState()),
                winX - winNX * 0.3, winY + 1.0, winZ - winNZ * 0.3, n, 0.5, 0.5, 0.5, 0.05);
    }

    /** 40 %: la cabra se lanza sobre el RUN de la ventana, se lo come, rompe el cristal y entra a perseguirte. */
    private void goatScene(ServerPlayer p) {
        net.minecraft.server.level.ServerLevel sl = (net.minecraft.server.level.ServerLevel) level();
        if (goatEnt == null) {
            double fx = -winNX, fz = -winNZ;                    // hacia el jugador
            double sx = -fz, sz = fx;                           // a lo largo de la ventana
            double hx = winX + winNX * scare.out, hy = winY + 1.0, hz = winZ + winNZ * scare.out;
            double lat = 4.0844, depth = 1.2, up = 2.0501;
            goatG = new Vec3(hx - sx * lat - fx * depth, hy - up, hz - sz * lat - fz * depth);
            double r = Math.hypot(lat, depth);
            double ux = (sx * lat + fx * depth) / r, uz = (sz * lat + fz * depth) / r;
            goatYaw = (float) (Math.atan2(ux, -uz) * 180 / Math.PI);
            goatEatAt = new Vec3(hx + sx * -0.5, hy + 0.2, hz + sz * -0.5);
            goatEat = new Vec3(goatEatAt.x - ux * 1.7072, goatEatAt.y - 2.2705, goatEatAt.z - uz * 1.7072);
            goatS = new Vec3(goatG.x - fx * 4 - sx * 2, goatG.y, goatG.z - fz * 4 - sz * 2);
            goatEnt = com.twixxel.stalker.event.SpawnHelper.createScripted(sl, "stalker_goat", goatS, goatYaw);
            if (goatEnt == null) {
                goatRoll = false;
                return;
            }
            goatEnt.setProp("fase", 1);
            playAll("stalker.goat");
            goatL = 0;
            goatT = -1;
        }
        // fase de carrera: 8 ticks hasta la ventana
        if (goatT < 0) {
            goatL++;
            double k = Math.min(1, goatL / 8.0);
            goatEnt.setPos(goatS.x + (goatG.x - goatS.x) * k, goatS.y, goatS.z + (goatG.z - goatS.z) * k);
            if (goatL >= 8) {
                goatT = 0;
                goatEnt.setProp("fase", 4);
                setProp(scare.activeProp, 5);
            }
            return;
        }
        goatT++;
        int t = goatT;
        Vec3 gp = goatG;
        if (t >= 18 && t < 42) gp = lerp3(goatG, goatEat, ease((t - 18) / 6.0));
        else if (t >= 42 && t < 74) gp = lerp3(goatEat, goatG, ease((t - 42) / 5.0));
        if (t < 74) goatEnt.setPos(gp.x, gp.y, gp.z);
        if (t == 6 || t == 12) p.playNotifySound(SoundEvents.ZOMBIE_BREAK_WOODEN_DOOR, SoundSource.MASTER, 0.4f, 1.8f);
        if (t == 27 || t == 32 || t == 37) {
            sl.playSound(null, goatEatAt.x, goatEatAt.y, goatEatAt.z, SoundEvents.GENERIC_EAT, SoundSource.HOSTILE, 1.5f, 0.5f);
        }
        if (t > 27 && t < 52 && t % 2 == 0) {
            sl.sendParticles(new net.minecraft.core.particles.BlockParticleOption(net.minecraft.core.particles.ParticleTypes.BLOCK,
                    net.minecraft.world.level.block.Blocks.REDSTONE_BLOCK.defaultBlockState()),
                    goatEatAt.x, goatEatAt.y - 0.5, goatEatAt.z, 5, 0.3, 0.4, 0.3, 0.05);
        }
        if (t == 42) {
            sl.playSound(null, goatEatAt.x, goatEatAt.y, goatEatAt.z, SoundEvents.ZOMBIE_INFECT, SoundSource.HOSTILE, 1.5f, 0.5f);
            sl.playSound(null, goatEatAt.x, goatEatAt.y, goatEatAt.z, SoundEvents.PLAYER_BURP, SoundSource.HOSTILE, 1.5f, 0.4f);
        }
        if (t == 55) {
            playTo(p, "stalker.wb_crack3");
            if (crack != null) crack.setProp("grieta", 3);
        }
        if (t == 64) {
            playTo(p, "stalker.wb_shatter");
            for (BlockPos bp : glass) level().destroyBlock(bp, false);
            glassBroken = true;
            if (crack != null) {
                crack.discard();
                crack = null;
            }
        }
        if (t == 74) {
            goatEnt.setProp("fase", 5);                         // se agacha para entrar por la ventana
            playAll("stalker.goat_crawl_scream");
        }
        if (t > 74) {
            goatIn++;
            double k = Math.min(1, goatIn / 10.0);
            Vec3 in = new Vec3(winX - winNX * 1.3, winY, winZ - winNZ * 1.3);
            Vec3 cur = lerp3(goatG, in, k);
            goatEnt.setPos(cur.x, cur.y, cur.z);
            if (goatIn >= 10) {
                goatEnt.discard();
                goatEnt = null;
                com.twixxel.stalker.event.SpawnHelper.spawnChaseAt(p, "stalker_goat", in);
                discard();                                      // con la cabra no aparece el RUN
            }
        }
    }

    private static Vec3 lerp3(Vec3 a, Vec3 b, double k) {
        return new Vec3(a.x + (b.x - a.x) * k, a.y + (b.y - a.y) * k, a.z + (b.z - a.z) * k);
    }

    private static double ease(double k) {
        k = Math.max(0, Math.min(1, k));
        return k * k * (3 - 2 * k);
    }

    private void scareTick() {
        ScareProfile sc = scare;
        ServerPlayer p = victim();
        if (p == null) {
            discard();
            return;
        }
        if (!sInit) {
            sInit = true;
            scareY = getY();
            setNoGravity(true);
            if (sc.initVal >= 0) setProp(sc.activeProp, sc.initVal);
            if (sc.spawnDy != 0 || sc.riseFrom > 0) setPos(getX(), scareY + sc.spawnDy - sc.riseFrom, getZ());
        }
        sT++;
        if (getDefId().equals("stalker_windowbreak")) {             // el video: 15 fps; sale de abajo (24 cuadros) y se queda; al mirarlo sigue hasta el cuadro 145
            int f = sPhase == 0 ? Math.min(24, sT * 15 / 20) : Math.min(145, 24 + sT * 15 / 20);
            if (f != (int) getProp("frame")) setProp("frame", f);
        }
        if (sc.nearVanish > 0 && Math.hypot(p.getX() - getX(), p.getZ() - getZ()) <= sc.nearVanish) {
            if (sc.activateSound != null) stopSoundAll(sc.activateSound);
            if (sc.nearDark > 0) {
                p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, sc.nearDark, 0, false, false));
            }
            if (crack != null) crack.discard();
            discard();
            return;
        }
        if (sc.riseProp != null) {
            riseSeq(p);
            return;
        }
        if (sPhase == 0) {
            if (sc.riseFrom > 0 && sT <= sc.riseTicks) {
                double f = sT / (double) sc.riseTicks;
                setPos(getX(), scareY + sc.spawnDy - sc.riseFrom * (1 - f), getZ());
            }
            if (sT > sc.stayTicks) {
                if (sc.leaveChase && sc.followUp != null) {
                    stopSoundAll(sc.voice);
                    p.connection.send(new ClientboundSetActionBarTextPacket(Component.empty()));
                    com.twixxel.stalker.event.SpawnHelper.spawnChaseAt(p, sc.followUp, new Vec3(winX + winNX * 1.6, winY - 1.0, winZ + winNZ * 1.6));
                    discard();
                    return;
                }
                if (sc.leaveVal >= 0) {
                    sPhase = 3;
                    sT = 0;
                    setProp(sc.activeProp, sc.leaveVal);
                } else {
                    discard();
                }
                return;
            }
            if (sc.voice != null && sc.repeat > 0) {
                if (sT % sc.repeat == 1) {
                    playTo(p, sc.voice);
                    if (sc.text != null) p.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(sc.text)));
                } else if (sT % sc.repeat == sc.voiceTicks + 1) {
                    p.connection.send(new ClientboundSetActionBarTextPacket(Component.empty()));
                }
            }
            if (sc.callEvery > 0 && sT % sc.callEvery == 1) {
                String[] list = com.twixxel.stalker.event.Lang.es() ? sc.callEs : sc.callEn;
                String line = String.format(list[sCalls++ % list.length], p.getName().getString());
                p.sendSystemMessage(Component.literal("\u00a7c<Stalker>\u00a7r " + line));
            }
            if (sT <= sc.riseTicks) return;
            boolean trig;
            if (sc.autoStart) {
                trig = true;
            } else if (sc.kind == ScareProfile.Kind.TREE) {
                trig = distanceTo(p) <= 3 || lookingAtEye(p, sc.gazeCos, 40, 1.6);
            } else {
                sGaze = lookingAtEye(p, sc.gazeCos, sc.range, sc.eye) ? sGaze + 1 : Math.max(0, sGaze - 2);
                trig = sGaze >= sc.gazeTicks || (sc.askVida && Math.hypot(p.getX() - winX, p.getZ() - winZ) <= sc.askNear);
            }
            if (!trig) return;
            if (sc.askVida) {                                     // espera la respuesta Si / No
                sPhase = 6;
                sT = 0;
                vidaAsk(p);
                return;
            }
            sPhase = 1;
            sT = 0;
            sShift = sc.esShift > 0 && com.twixxel.stalker.event.Lang.es() ? sc.esShift : 0;
            goatRoll = sc.goatChance > 0 && random.nextDouble() < sc.goatChance;
            setProp(sc.activeProp, sc.activeVal);
            if (sc.voice != null) stopSoundAll(sc.voice);
            p.connection.send(new ClientboundSetActionBarTextPacket(Component.empty()));
            if (sc.activateSound != null && sc.activateDelay == 0) playTo(p, sc.activateSound);
            if (sc.activateText != null) p.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(sc.activateText)));
            if (sc.freeze) {
                int fz = sc.freezeTicks > 0 ? sc.freezeTicks : sc.animTicks;
                p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, fz, 10, false, false));
                p.addEffect(new MobEffectInstance(MobEffects.JUMP, fz, 200, false, false));
            }
            if (sc.breakSound != null) playTo(p, sc.breakSound);
            return;
        }
        if (sPhase == 6) {   // esperando la respuesta de "te gusta la vida"
            if (sT % 400 == 0) vidaAsk(p);
            if (sT > 2400) {
                sPhase = 3;
                sT = 0;
                setProp(sc.activeProp, sc.leaveVal);
            }
            return;
        }
        if (sPhase == 3) {   // se va sin que lo hayas mirado
            if (sT >= sc.leaveTicks) discard();
            return;
        }
        // animacion en curso
        if (sc.kind == ScareProfile.Kind.TUNNEL) faceYaw(p, 0f);
        if (sc.chaseAs != null && sc.nearGo > 0 && distanceTo(p) <= sc.nearGo) {
            becomeChaser(p);
            return;
        }
        if (sc.lungeAt > 0 && sT == sc.lungeAt) {                // salta hacia adentro por la ventana rota
            Vec3 in = new Vec3(winX - winNX * 1.2, winY, winZ - winNZ * 1.2);
            setPos(in.x, in.y, in.z);
            faceYaw(p, 0f);
        }
        if (sc.chaseAs != null && sT >= sc.chaseAt) {
            becomeChaser(p);
            return;
        }
        if (sc.activateSound != null && sc.activateDelay > 0 && sT == sc.activateDelay) playTo(p, sc.activateSound);
        if (sc.runN > 0 && sT < sc.runEvery * sc.runN) {
            boolean es = com.twixxel.stalker.event.Lang.es();
            int k = sT % sc.runEvery;
            if (k == 0) playTo(p, es || sc.runSoundEn == null ? sc.runSound : sc.runSoundEn);
            if (k == 1) p.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(es ? sc.runTextEs : sc.runTextEn)));
            if (k == 31) p.connection.send(new ClientboundSetActionBarTextPacket(Component.empty()));
        }
        for (int a : sc.aguAt) if (sT == a) playTo(p, "stalker.run_crack_" + (1 + random.nextInt(3)));
        for (int i = 0; i < sc.stageAt.length; i++) {
            if (sT == sc.stageAt[i] + sShift) setProp(sc.activeProp, sc.stageVal[i]);
        }
        if (goatRoll && sT >= sc.goatAt) {
            goatScene(p);
            return;
        }
        for (int i = 0; i < sc.hits.length; i++) {
            if (sT == sc.hits[i]) {
                if (sc.hitSounds.length > 0) playTo(p, sc.hitSounds[Math.min(i, sc.hitSounds.length - 1)]);
                if (crack != null) crack.setProp("grieta", i + 1);
                else if (sc.selfGrieta) setProp("grieta", i + 1);
                if (sc.gore) p.playNotifySound(SoundEvents.SLIME_SQUISH, SoundSource.MASTER, 0.8f, 0.6f);
            }
        }
        if (sc.popAt > 0 && sT == sc.popAt) {
            if (sc.popSound != null) playTo(p, sc.popSound);
            if (crack != null) crack.setProp("grieta", 4);
            if (sc.gore) {
                p.playNotifySound(SoundEvents.ZOMBIE_BREAK_WOODEN_DOOR, SoundSource.MASTER, 0.9f, 0.5f);
                p.playNotifySound(SoundEvents.SLIME_SQUISH, SoundSource.MASTER, 1.0f, 0.4f);
            }
        }
        if (sc.gore && sc.popAt > 0 && sT > sc.popAt && sT < sc.breakAt && sT % 2 == 0) bloodAtWindow(6);
        if (sc.damageAt > 0 && sT == sc.damageAt) p.hurt(damageSources().mobAttack(this), sc.damage);
        if ((sc.breakSound != null || sc.breakGlass) && !glassBroken && sT >= sc.breakAt) {
            glassBroken = true;
            if (sc.selfGrieta) setProp("grieta", 0);
            for (BlockPos bp : glass) level().destroyBlock(bp, false);
            if (crack != null) {
                crack.discard();
                crack = null;
            }
            if (sc.shatterSound != null) playTo(p, sc.shatterSound);
        }
        if (sc.followUp != null && !followed && sT >= sc.followAt) {
            followed = true;
            Vec3 at = sc.followOut ? new Vec3(winX + winNX * 1.6, winY - 1.0, winZ + winNZ * 1.6) : new Vec3(winX - winNX * 1.2, winY, winZ - winNZ * 1.2);
            if (sc.askVida) {
                StalkerEntity v = com.twixxel.stalker.event.SpawnHelper.spawnChaseAtE(p, sc.followUp, at, false);
                if (v != null) v.vidaStartChase(p);
            } else com.twixxel.stalker.event.SpawnHelper.spawnChaseAt(p, sc.followUp, at);
        }
        if (sc.postAt > 0 && !postDone && sT >= sc.postAt) {
            postDone = true;
            setInvisible(true);
            if (sc.postSound != null) playTo(p, sc.postSound);
            if (sc.postNausea > 0) p.addEffect(new MobEffectInstance(MobEffects.CONFUSION, sc.postNausea, 0, false, false));
            if (sc.postDark > 0) {
                p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, sc.postDark, 0, false, false));
                p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, sc.postDark, 0, false, false));
            }
        }
        if (sc.scarySound != null && sT == sc.scaryAt) playTo(p, sc.scarySound);
        if (sT >= sc.totalTicks + sShift) {
            if (sc.darkTicks > 0) {
                p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, sc.darkTicks, 0, false, false));
                p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, sc.darkTicks, 0, false, false));
            }
            p.connection.send(new ClientboundSetActionBarTextPacket(Component.empty()));
            discard();
        }
    }

    // ------------------------------------------------------------------ LOS QUE SE ASOMAN
    private int pPhase = -1, pT = 0, pMode = 0, pSgn = 1, pDone = 0, pPeeks = 0, pLook = 0, pAge = 0, pHold = 0;
    private double hideX, hideY, hideZ, pFx, pFz, pWallH = 2, pStartDist = 0;
    private boolean pScreamed = false;

    /** La define el spawner: posicion escondida, direccion jugador->escondite y altura de la pared. */
    public void setPeek(double x, double y, double z, double fx, double fz, double wallH, boolean tree) {
        hideX = x;
        hideY = y;
        hideZ = z;
        pFx = fx;
        pFz = fz;
        pWallH = wallH;
        pTree = tree;
    }

    private boolean pTree = false;

    private float yawToward(ServerPlayer p) {
        return (float) (Math.atan2(-(p.getX() - getX()), p.getZ() - getZ()) * 180 / Math.PI) + peek.yawExtra;
    }

    private Vec3 peekHead() {
        double rx = -pFz, rz = pFx;
        if (pMode == 1) return new Vec3(getX(), hideY + pWallH + 0.45, getZ());
        return new Vec3(getX() + rx * pSgn * peek.headLat, hideY + peek.headUp, getZ() + rz * pSgn * peek.headLat);
    }

    private boolean seesPoint(ServerPlayer p, Vec3 pt, double cos) {
        Vec3 eye = p.getEyePosition();
        Vec3 to = pt.subtract(eye);
        double d = to.length();
        if (d > 56 || d < 0.1) return false;
        if (p.getLookAngle().dot(to) / d < cos) return false;
        var hit = level().clip(new net.minecraft.world.level.ClipContext(eye, pt,
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, p));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS;
    }

    private void placeAt(double off) {
        double rx = -pFz, rz = pFx;
        setPos(hideX + rx * pSgn * off, hideY, hideZ + rz * pSgn * off);
    }

    private void peekVanish(ServerPlayer v) {
        for (ServerPlayer q : ((net.minecraft.server.level.ServerLevel) level()).players()) {
            if (q != v && q.distanceTo(this) >= 10) continue;
            q.addEffect(new MobEffectInstance(MobEffects.DARKNESS, peek.dark, 0, false, false));
            if (peek.vanishSound != null) playTo(q, peek.vanishSound);
        }
        peekEnd();
    }

    private void peekEnd() {
        if (peek.scream != null) stopSoundAll(peek.scream);
        discard();
    }

    private void peekTick() {
        PeekProfile pk = peek;
        ServerPlayer p = victim();
        if (p == null) {
            discard();
            return;
        }
        pAge++;
        if (pPhase < 0) {                       // primer tick: escondido, parado
            pPhase = 0;
            pT = 12;
            pPeeks = pk.peeksMin + random.nextInt(pk.peeksMax - pk.peeksMin + 1);
            pStartDist = Math.hypot(p.getX() - hideX, p.getZ() - hideZ);
            setProp("fase", pk.hideFase);
            setNoGravity(true);
            setPos(hideX, hideY, hideZ);
        }
        setYRot(yawToward(p));
        setYHeadRot(getYRot());
        setYBodyRot(getYRot());
        if (pAge > 3000) {
            peekEnd();
            return;
        }
        // si te acercas, desaparece y te deja a oscuras
        double dHide = Math.hypot(p.getX() - hideX, p.getZ() - hideZ);
        double near = pk.style == PeekProfile.Style.PEEKER ? Math.min(pk.near, Math.max(2, pStartDist - 2)) : pk.near;
        if (dHide < near || (pk.style == PeekProfile.Style.PEEKER && dHide < pStartDist - 4)) {
            peekVanish(p);
            return;
        }
        if (pk.style == PeekProfile.Style.GRIP) {
            gripTick(p, dHide);
            return;
        }
        double ease;
        switch (pPhase) {
            case 0: {   // escondido (entre asomadas)
                if (pk.style != PeekProfile.Style.PEEKER && pAge > pk.stay) {
                    peekEnd();
                    return;
                }
                if (--pT > 0 && pk.style == PeekProfile.Style.PEEKER) return;
                pSgn = random.nextBoolean() ? 1 : -1;
                setProp("lado", pSgn > 0 ? 0 : 1);
                int baja = pk.headStand > 0 ? (int) Math.round((pk.headStand - (pWallH + 0.45)) * 32) : 0;
                boolean topOk = !pTree && pWallH >= 2 && pWallH <= 5 && pk.style != PeekProfile.Style.STATIC
                        && (pk.headStand == 0 || (baja <= pk.bajaMax && baja >= -100));
                pMode = topOk && random.nextBoolean() ? 1 : 0;
                if (pk.style == PeekProfile.Style.STATIC) pMode = 0;
                setProp("baja", pMode == 1 ? baja : 0);
                setProp("fase", pMode == 1 ? pk.topFase : pk.sideFase);
                if (pk.scream != null && !pScreamed) {
                    pScreamed = true;
                    playTo(p, pk.scream);
                }
                if (pk.hello != null) playTo(p, pk.hello);
                pPhase = 1;
                pT = 0;
                pLook = 0;
                return;
            }
            case 1: {   // asomandose
                pT++;
                ease = Math.min(1.0, pT / (double) pk.inTicks);
                ease = ease * ease * (3 - 2 * ease);
                if (pMode == 0) placeAt(pk.slide * ease);
                if (pT >= pk.inTicks) {
                    pPhase = 2;
                    pT = 0;
                    pHold = pk.style == PeekProfile.Style.PEEKER ? pk.holdMin + random.nextInt(pk.holdMax - pk.holdMin + 1) : Integer.MAX_VALUE;
                }
                return;
            }
            case 2: {   // te vigila
                pT++;
                boolean seen = seesPoint(p, peekHead(), pk.lookCos);
                pLook = seen ? pLook + 1 : Math.max(0, pLook - 1);
                setProp("mirado", seen ? 1 : 0);
                if (pLook >= pk.lookTicks) {
                    if (pk.style == PeekProfile.Style.SINGLE) {
                        setProp("fase", pMode == 1 ? pk.topCrackFase : pk.sideCrackFase);
                        playTo(p, pk.crackSound);
                        pPhase = 4;
                    } else {
                        if (pk.style == PeekProfile.Style.PEEKER) playTo(p, pk.crackSound);
                        hideNow();
                        pPhase = 3;
                    }
                    pT = 0;
                    return;
                }
                if (pAge > pk.stay || pT >= pHold) {
                    hideNow();
                    pPhase = 3;
                    pT = 0;
                }
                return;
            }
            case 3: {   // escondiendose
                pT++;
                int len = pMode == 1 ? 16 : pk.inTicks * 3 / 4;
                ease = Math.min(1.0, pT / (double) Math.max(1, len));
                if (pMode == 0) placeAt(pk.slide * (1 - ease));
                if (pT < len) return;
                setProp("fase", pk.hideFase);
                pDone++;
                if (pk.style == PeekProfile.Style.PEEKER && pDone < pPeeks) {
                    pPhase = 0;
                    pT = pk.waitMin + random.nextInt(pk.waitMax - pk.waitMin + 1);
                    return;
                }
                if (pk.style == PeekProfile.Style.STATIC && pT < len + pk.byeTicks) return;
                peekEnd();
                return;
            }
            default: {  // 4: cruje el cuello y se va hacia atras
                pT++;
                if (pT == 48) setProp("fase", pk.hideFase);
                if (pT > 48) {
                    double back = (pT - 48) / 14.0;
                    setPos(hideX + pFx * 0.8 * Math.min(1, back), hideY, hideZ + pFz * 0.8 * Math.min(1, back));
                }
                if (pT >= 62) peekEnd();
            }
        }
    }

    private void hideNow() {
        PeekProfile pk = peek;
        if (pMode == 1) setProp("fase", pk.style == PeekProfile.Style.PEEKER ? pk.downFase : pk.topDownFase);
    }

    // ------------------------------------------------------------------ NO MIRES ARRIBA
    private int aT = 0, aLook = 0, aPhase = 0;

    private void arribaTick() {
        ServerPlayer p = victim();
        if (p == null) {
            discard();
            return;
        }
        aT++;
        Vec3 look = p.getLookAngle();
        double hl = Math.hypot(look.x, look.z);
        double hx = hl < 1e-3 ? 0 : look.x / hl, hz = hl < 1e-3 ? 1 : look.z / hl;
        if (aPhase <= 1) {
            double bx = p.getX() - hx * 1.7, bz = p.getZ() - hz * 1.7;
            double k = 1.0;
            if (aPhase == 1 && aT >= 8 && aT < 13) k = 1.0 - 1.45 * (aT - 8) / 5.0;     // se tira a tu cara
            else if (aPhase == 1 && aT >= 13) k = 0.15;
            setPos(p.getX() - hx * 1.7 * k, p.getY(), p.getZ() - hz * 1.7 * k);
            setYRot(net.minecraft.util.Mth.approachDegrees(getYRot(), p.getYRot(), 4f));
            setYHeadRot(getYRot());
            setYBodyRot(getYRot());
        }
        if (aPhase == 0) {
            if (aT % 20 == 1) p.connection.send(new ClientboundSetActionBarTextPacket(Component.literal("\u00a7cNo mires arriba")));
            if (aT > 1200) {
                discard();
                return;
            }
            aLook = p.getXRot() < -40f ? aLook + 1 : 0;
            if (aLook >= 3) {
                aPhase = 1;
                aT = 0;
                setProp("fase", 1);
                playTo(p, "stalker.arriba_grito");
                p.connection.send(new ClientboundSetActionBarTextPacket(Component.empty()));
            }
        } else if (aPhase == 1) {
            if (aT == 30) {
                p.hurt(damageSources().mobAttack(this), 8f);
                aPhase = 2;
                aT = 0;
                setProp("fase", 2);
                setPos(p.getX() - hx * 14, p.getY(), p.getZ() - hz * 14);
            }
        } else if (aT >= 40) {
            discard();
        }
    }

    // ------------------------------------------------------------------ utilidades de tanda 8
    private static boolean es() {
        return com.twixxel.stalker.event.Lang.es();
    }

    private static void title(ServerPlayer p, String text, int stay) {
        p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(0, stay, 0));
        p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(Component.literal(text)));
    }

    private void faceYaw(ServerPlayer p, float offset) {
        float yaw = (float) (Math.atan2(-(p.getX() - getX()), p.getZ() - getZ()) * 180 / Math.PI) + offset;
        setYRot(yaw);
        setYHeadRot(yaw);
        setYBodyRot(yaw);
    }

    // ------------------------------------------------------------------ AGARRADO DEL ARBOL (grip)
    private void gripTick(ServerPlayer p, double dHide) {
        PeekProfile pk = peek;
        if (pPhase < 2) {
            pSgn = random.nextBoolean() ? 1 : -1;
            setProp("lado", pSgn > 0 ? 0 : 1);
            setProp("fase", 0);
            p.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(es() ? pk.textEs : pk.textEn)));
            pPhase = 2;
            pT = 0;
            pLook = 0;
            return;
        }
        if (pPhase == 2) {
            if (dHide > 48 || pAge > pk.stay) {
                peekEnd();
                return;
            }
            boolean seen = seesPoint(p, peekHead(), pk.lookCos);
            pLook = seen ? pLook + 1 : Math.max(0, pLook - 1);
            if (pLook >= pk.lookTicks) {
                pPhase = 5;
                pT = 0;
                setProp("fase", 1);
                playTo(p, pk.scream);
                p.connection.send(new ClientboundSetActionBarTextPacket(Component.empty()));
            }
            return;
        }
        pT++;
        if (pT >= pk.screamTicks) {                      // se transforma en el RUN y te persigue
            com.twixxel.stalker.event.SpawnHelper.spawnChaseAt(p, pk.followUp, new Vec3(getX(), getY(), getZ()));
            discard();
        }
    }

    // ------------------------------------------------------------------ TE GUSTA LA VIDA
    private int vPhase = 0, vT = 0;

    private void vidaAsk(ServerPlayer p) {
        net.minecraft.network.chat.MutableComponent msg = Component.literal(es() ? "\u00a7e\u00bfTe gusta la vida? " : "\u00a7eDo you like life? ");
        msg.append(Component.literal(es() ? "\u00a7a[S\u00ed]" : "\u00a7a[Yes]").withStyle(st -> st.withClickEvent(
                new net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND, "/twxvida yes"))));
        msg.append(Component.literal("  "));
        msg.append(Component.literal("\u00a7c[No]").withStyle(st -> st.withClickEvent(
                new net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND, "/twxvida no"))));
        p.sendSystemMessage(msg);
    }

    /** Respuesta de la pregunta (comando /twxvida yes|no). */
    public void vidaAnswer(ServerPlayer from, boolean yes) {
        if (scare != null && scare.askVida && sPhase == 6 && victim() != null && victim().getUUID().equals(from.getUUID())) {
            if (yes) {
                sPhase = 3;
                sT = 0;
                setProp(scare.activeProp, scare.leaveVal);
            } else {
                sPhase = 1;
                sT = 0;
                sShift = 0;
                goatRoll = false;
                setProp(scare.activeProp, scare.activeVal);
                playTo(from, "stalker.unknown_scream");
            }
            return;
        }
        if (vPhase != 1 || victimId == null || !victimId.equals(from.getUUID())) return;
        if (yes) {
            from.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 140, 0, false, false));
            from.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 140, 0, false, false));
            discard();
        } else {
            vPhase = 2;
            vT = 0;
            playTo(from, "stalker.unknown_scream");
            setProp("fase", 2);
            lastPos = position();
            stuck = 0;
        }
    }

    /** Empieza ya en la fase de persecucion (viene de la ventana). */
    public void vidaStartChase(ServerPlayer p) {
        victimId = p.getUUID();
        vPhase = 2;
        vT = 0;
        setProp("fase", 2);
        lastPos = position();
        stuck = 0;
        playTo(p, "stalker.vida_chase");
    }

    private void vidaTick() {
        ServerPlayer v = victim();
        if (v == null) {
            ServerPlayer n = nearestPlayer();
            if (n == null) {
                if (tickCount > 200) discard();
                return;
            }
            victimId = n.getUUID();
            v = n;
        }
        vT++;
        switch (vPhase) {
            case 0:
                faceYaw(v, 0f);
                if (vT > 3600) {
                    discard();
                    return;
                }
                if (distanceTo(v) <= 5) {
                    vPhase = 1;
                    vT = 0;
                    setProp("fase", 1);
                    vidaAsk(v);
                }
                return;
            case 1:
                faceYaw(v, 0f);
                if (vT % 400 == 0) vidaAsk(v);
                if (vT > 2400) discard();
                return;
            case 2: {
                if (vT % 379 == 1) playTo(v, "stalker.vida_chase");
                faceYaw(v, 0f);
                if (distanceTo(v) <= 1.5) {
                    vPhase = 3;
                    vT = 0;
                    getNavigation().stop();
                    setProp("fase", 3);
                    return;
                }
                navChase(v);
                return;
            }
            default:
                faceYaw(v, 0f);
                if (vT == 16) com.twixxel.stalker.event.JumpScare.kill(v, this);
                if (vT >= 28) {
                    stopSoundAll("stalker.vida_chase");
                    discard();
                }
        }
    }

    // ------------------------------------------------------------------ STALKER DE LA PUERTA 2
    private BlockPos d2Door;
    private double d2NX, d2NZ;
    private int d2Phase = 0, d2T = 0;
    private Vec3 d2Ref;
    private double d2LYaw = Double.NaN, d2LPitch = Double.NaN;

    public void setDoor2(BlockPos door, double nx, double nz) {
        d2Door = door;
        d2NX = nx;
        d2NZ = nz;
    }

    private Vec3 d2Pos(double k) {
        double cx = d2Door.getX() + 0.5, cz = d2Door.getZ() + 0.5;
        double ox = cx - d2NX * 1.1, oz = cz - d2NZ * 1.1, ix = cx + d2NX * 0.15, iz = cz + d2NZ * 0.15;
        return new Vec3(ox + (ix - ox) * k, d2Door.getY(), oz + (iz - oz) * k);
    }

    private void d2SetDoor(boolean open) {
        BlockState st = level().getBlockState(d2Door);
        if (st.getBlock() instanceof net.minecraft.world.level.block.DoorBlock db && st.getValue(net.minecraft.world.level.block.DoorBlock.OPEN) != open) {
            db.setOpen(null, level(), st, d2Door, open);
        }
    }

    private void d2Look(ServerPlayer p) {
        double ey = getYRot();
        double dx = p.getX() - getX(), dz = p.getZ() - getZ(), dy = p.getEyeY() - (getY() + 1.5);
        double yaw = Math.atan2(-dx, dz) * 180 / Math.PI - ey;
        while (yaw > 180) yaw -= 360;
        while (yaw < -180) yaw += 360;
        yaw = Math.max(-80, Math.min(80, yaw));
        double pitch = Math.max(-60, Math.min(60, -Math.atan2(dy, Math.hypot(dx, dz)) * 180 / Math.PI));
        d2LYaw = Double.isNaN(d2LYaw) ? yaw : d2LYaw + (yaw - d2LYaw) * 0.35;
        d2LPitch = Double.isNaN(d2LPitch) ? pitch : d2LPitch + (pitch - d2LPitch) * 0.35;
        setProp("yaw", Math.round(d2LYaw * 10) / 10.0);
        setProp("pitch", Math.round(d2LPitch * 10) / 10.0);
    }

    private void door2Tick() {
        ServerPlayer p = victim();
        if (p == null || d2Door == null) {
            discard();
            return;
        }
        d2T++;
        float yaw = (float) (Math.atan2(-d2NX, d2NZ) * 180 / Math.PI);
        setYRot(yaw);
        setYHeadRot(yaw);
        setYBodyRot(yaw);
        switch (d2Phase) {
            case 0:     // detras de la puerta cerrada
                if (d2T >= 20) {
                    d2SetDoor(true);
                    level().playSound(null, d2Door, SoundEvents.WOODEN_DOOR_OPEN, SoundSource.BLOCKS, 1f, 0.7f);
                    d2Phase = 1;
                    d2T = 0;
                }
                return;
            case 1: {   // se asoma hacia adentro
                Vec3 q = d2Pos(Math.min(1.0, d2T / 14.0));
                setPos(q.x, q.y, q.z);
                d2Look(p);
                if (d2T >= 14) {
                    d2Phase = 2;
                    d2T = 0;
                    title(p, "\u00a7f" + (es() ? "No te muevas" : "Don't move"), 20);
                }
                return;
            }
            case 2: {   // vigila: si te mueves, te castiga
                d2Look(p);
                if (d2T == 4) d2Ref = p.position();
                if (d2T > 4 && d2Ref != null && p.position().distanceTo(d2Ref) > 0.3) {
                    title(p, "\u00a7c" + (es() ? "Te lo advert\u00ed" : "I warned you"), 20);
                    p.hurt(damageSources().magic(), 18f);
                    p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 200, 0, false, false));
                    playTo(p, "stalker.unknown_scream");
                    d2SetDoor(false);
                    discard();
                    return;
                }
                if (d2T >= 400) {
                    d2Phase = 3;
                    d2T = 0;
                }
                return;
            }
            default: {  // se vuelve a meter y cierra la puerta
                Vec3 q = d2Pos(1.0 - Math.min(1.0, d2T / 12.0));
                setPos(q.x, q.y, q.z);
                if (d2T >= 12) {
                    d2SetDoor(false);
                    level().playSound(null, d2Door, SoundEvents.WOODEN_DOOR_CLOSE, SoundSource.BLOCKS, 1f, 1f);
                    discard();
                }
            }
        }
    }

    // ------------------------------------------------------------------ LA CABEZA DEL POZO (hole)
    private int hT = 0, hPh = 0, hT0 = 0, hGaze = 0;
    private Vec3 hStart;

    private void holeTick() {
        ServerPlayer p = victim();
        if (p == null) {
            discard();
            return;
        }
        hT++;
        if (hT == 1) {
            hStart = position();
            playTo(p, "stalker.hole_music");
        }
        faceYaw(p, 0f);
        double dx = p.getX() - getX(), dz = p.getZ() - getZ(), dy = p.getEyeY() - (getY() + 0.5);
        double horiz = Math.hypot(dx, dz);
        setProp("pitch", Math.round(Math.max(-90, Math.min(90, -Math.atan2(dy, horiz) * 180 / Math.PI))));
        if (horiz < 3) {                                        // te acercas: desaparece de golpe y te deja a oscuras
            p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 60, 0, false, false));
            stopSoundAll("stalker.hole_music");
            discard();
            return;
        }
        if (hPh == 0) {
            if (hT >= 1201) {
                stopSoundAll("stalker.hole_music");
                discard();
                return;
            }
            boolean seen = seesPoint(p, position().add(0, 0.5, 0), 0.95);
            hGaze = seen ? hGaze + 1 : 0;
            setProp("ojos", seen ? 1 : 0);
            if (hGaze >= 10) {                                  // lo miras: no te puedes mover y se retira despacio
                hPh = 1;
                hT0 = hT;
                p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 10, false, false));
                p.addEffect(new MobEffectInstance(MobEffects.JUMP, 60, 200, false, false));
            }
            return;
        }
        double k = Math.min(1.0, (hT - hT0) / 50.0);
        double lx = dx / Math.max(horiz, 1e-3), lz = dz / Math.max(horiz, 1e-3);
        setPos(hStart.x - lx * 0.8 * k, hStart.y - 0.9 * k, hStart.z - lz * 0.8 * k);
        if (k >= 1.0) {
            stopSoundAll("stalker.hole_music");
            discard();
        }
    }

    // ------------------------------------------------------------------ ASOMADO DEL AGUJERO 2x2
    private int agPh = 0, agT = 0, agAge = 0;

    private void agujeroTick() {
        ServerPlayer p = victim();
        if (p == null) {
            discard();
            return;
        }
        agAge++;
        if (agAge == 1) setInvisible(true);                     // invisible hasta que lo miras
        faceYaw(p, 0f);
        double horiz = Math.hypot(p.getX() - getX(), p.getZ() - getZ());
        if (horiz < 2.0) {                                      // desaparece de golpe (crack) y te deja a oscuras 10 s
            playTo(p, "stalker.run_crack");
            p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 200, 0, false, false));
            discard();
            return;
        }
        if (agPh == 0) {
            if (agAge > 1200) {
                discard();
                return;
            }
            if (seesPoint(p, position().add(0, 1.48, 0), 0.94)) {
                agPh = 1;
                agT = 0;
                setInvisible(false);
                setProp("fase", 1);
            }
            return;
        }
        agT++;
        if (agT == 26) playTo(p, "stalker.run_crack");           // dobla la cabeza de golpe
        if (agT == 190) setProp("fase", 2);                      // se echa hacia atras
        if (agT >= 216) discard();
    }

    // ------------------------------------------------------------------ MIRON: ventana rota
    private StalkerEntity mironHead;
    private Vec3 mironWin;

    private boolean mironWindow(ServerPlayer v) {
        if (phaseTick < 10 || phaseTick % 4 != 0 || level().canSeeSky(v.blockPosition())) return false;
        Vec3 eye = v.getEyePosition();
        var hit = level().clip(new net.minecraft.world.level.ClipContext(eye, eye.add(v.getLookAngle().scale(10)),
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, v));
        if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) return false;
        BlockPos bp = hit.getBlockPos();
        var key = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(level().getBlockState(bp).getBlock());
        if (key == null || !key.getPath().contains("glass")) return false;
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            BlockPos q = bp.offset(dx, dy, dz);
            var k2 = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(level().getBlockState(q).getBlock());
            if (k2 != null && k2.getPath().contains("glass")) level().destroyBlock(q, false);
        }
        double nx = v.getX() - (bp.getX() + 0.5), nz = v.getZ() - (bp.getZ() + 0.5);
        double nl = Math.max(1e-3, Math.hypot(nx, nz));
        nx /= nl;
        nz /= nl;
        mironWin = new Vec3(bp.getX() + 0.5 - nx * 0.35, bp.getY() - 0.3, bp.getZ() + 0.5 - nz * 0.35);
        stopSoundAll(profile.chaseSound);
        getNavigation().stop();
        setInvisible(true);
        phase = 5;
        phaseTick = 0;
        var ro = TwixxelMod.TYPES.get("stalker_miron_cabeza");
        if (ro != null) {
            StalkerEntity h = ro.get().create(level());
            if (h != null) {
                h.setScripted(true);
                h.setNoGravity(true);
                h.noPhysics = true;
                h.moveTo(mironWin.x, mironWin.y - 1.2, mironWin.z, (float) (Math.atan2(-nx * -1, nz * -1) * 180 / Math.PI) - 90f, 0f);
                level().addFreshEntity(h);
                h.setProp("fase", 1);
                mironHead = h;
            }
        }
        playTo(v, "stalker.unknown_scream");
        title(v, "\u00a7c" + (es() ? "NO TE ESCONDAS" : "DON'T HIDE"), 40);
        return true;
    }

    private void mironScene(ServerPlayer v) {
        int t = phaseTick;
        if (mironHead != null) {
            double k = t < 8 ? t / 8.0 : (t < 58 ? 1.0 : Math.max(0, 1.0 - (t - 58) / 30.0));
            mironHead.setPos(mironWin.x, mironWin.y - 1.2 + 1.2 * ease(k), mironWin.z);
        }
        if (t >= 90) {
            v.sendSystemMessage(Component.literal(es() ? "<???> Vendr\u00e9 m\u00e1s tarde" : "<???> I'll come back later"));
            if (mironHead != null) mironHead.discard();
            discard();
        }
    }

    // ------------------------------------------------------------------ varios
    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean canBeLeashed(Player player) {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double dist) {
        return false;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("TwxProps", encode());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("TwxProps")) {
            decode(tag.getString("TwxProps"));
            if (!level().isClientSide) entityData.set(PROPS, encode());
            loadedFromDisk = true;   // solo si viene de un guardado real (no de /summon)
        }
    }
}
