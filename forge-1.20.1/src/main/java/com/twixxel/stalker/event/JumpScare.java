package com.twixxel.stalker.event;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.twixxel.stalker.TwixxelMod;
import com.twixxel.stalker.entity.StalkerEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * JUMPSCARES UNICOS: cada stalker tiene el suyo (camara, poses, grito, efectos; algunos te levantan). Las escenas vienen de
 * data/twixxel/jumpscares.json (copiadas del addon). Camara = un armor stand invisible; el stalker es la entidad "jumpscare"
 * (props js = modelo, jf = fase, jb = textura B, js_s = escala). Al terminar la escena el jugador muere.
 */
public final class JumpScare {
    private static final String GEAR_KEY = "twx_js_gear";
    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND, EquipmentSlot.MAINHAND};
    private static JsonObject SCENES;
    private static final Random RND = new Random();

    private static final class St {
        UUID pid;
        ServerLevel level;
        StalkerEntity ent;
        ArmorStand rig;
        boolean camSent;
        Vec3 eye, feet;
        float yaw;
        double fx, fz, rx, rz, freeF, freeB, freeR, freeL, freeUp;
        JsonObject sc;
        int t = -1, evi, lastFase = -1, lastJb = -1;
        double lastS = -1, shakeAmp;
        int shakeUntil;
        boolean wasInvulnerable;
        Ojs ojs;
        String ojsId;
        double ojsFree;
    }

    private static final Map<UUID, St> ACTIVE = new HashMap<>();

    /** Jumpscare propio (OJS del addon): el stalker con su mismo modelo aparece delante, grita y se te tira a la cara.
     *  {faceY, fase, dist0, dist1} + sonido del grito. */
    private static final class Ojs {
        final double faceY, d0, d1;
        final int fase;
        final String scream;

        Ojs(double faceY, int fase, String scream, double d0, double d1) {
            this.faceY = faceY;
            this.fase = fase;
            this.scream = scream;
            this.d0 = d0;
            this.d1 = d1;
        }
    }

    private static final Map<String, Ojs> OJS = new HashMap<>();
    private static final int OJS_LEN = 40;
    private static final int MBJ_BEND_END = 30, MBJ_THROW = 50, MBJ_SCRATCH_END = 72, MBJ_LEN = 104;
    private static final double MB_FACE_Y = 5.0;

    static {
        OJS.put("stalker_lc", new Ojs(2.37, 3, "stalker.goat_crawl_scream", 3.4, .85));
        OJS.put("stalker_cuernos", new Ojs(2.46, 4, "stalker.goat_crawl_scream", 3.4, .85));
        OJS.put("stalker_craneo", new Ojs(2.46, 4, "stalker.goat_crawl_scream", 3.4, .85));
        OJS.put("stalker_trepador", new Ojs(2.6, 4, "stalker.goat_crawl_scream", 3.0, .8));
        OJS.put("the_locust", new Ojs(5.8, 4, "stalker.unknown_scream", 4.5, 1.9));
        OJS.put("stalker_maebi", new Ojs(MB_FACE_Y, 1, "stalker.js_goat", 4.0, 1.3));
    }

    private static JsonObject scenes() {
        if (SCENES == null) {
            try (var in = JumpScare.class.getResourceAsStream("/data/twixxel/jumpscares.json")) {
                SCENES = JsonParser.parseReader(new InputStreamReader(in, "UTF-8")).getAsJsonObject();
            } catch (Exception ex) {
                SCENES = new JsonObject();
            }
        }
        return SCENES;
    }

    /** Mientras un jumpscare esta en marcha, los demas stalkers cercanos (64 bloques) se quedan quietos. */
    public static boolean freezes(net.minecraft.world.entity.Entity e) {
        if (ACTIVE.isEmpty()) return false;
        for (St s : ACTIVE.values()) {
            if (s.level == e.level() && e.distanceToSqr(s.feet.x, s.feet.y, s.feet.z) < 64 * 64) return true;
        }
        return false;
    }

    public static boolean active(UUID id) {
        return ACTIVE.containsKey(id);
    }

    private static boolean es() {
        return com.twixxel.stalker.event.Lang.es();
    }

    private static String modelOf(String id) {
        if (id == null) return "run";
        switch (id) {                                          // igual que jsModelOf del addon
            case "stalker_run_sueno": return "run_sueno";
            case "stalker_evil": return "evil";
            case "stalker_goatevil": return "goatevil";
            case "stalker_quad_red": return "quad_red";
            case "stalker_goat": return "goat";
            case "stalker_spotted": case "stalker_spotted2": return "solocorre";     // todos los spotted usan el jumpscare de Sol
            case "stalker_ansiedad": return "ansiedad";
            case "stalker_smiler": return "smiler";
            case "stalker_unknown": return "unknown";
            case "stalker_run": case "stalker_cueva": case "stalker_bed": case "stalker_bedscene": case "stalker_run_pasillo": case "stalker_puertas": case "stalker_agujero": return "run";
            case "stalker_screamer": case "stalker_miron": return "screamer";
            case "stalker_hello": return "hello";
            case "stalker_redlight": return "redlight";
            case "stalker_tortura": return "tortura";
            case "stalker_vida": return "vida";
            case "stalker_jester": return "jester";
            case "stalker_goat_jester": return "goat_jester";
            case "stalker_luz": return "luz";
            case "stalker_diversion": return "diversion";
            case "stalker_solocorre": return "solocorre";
            default:
        }
        // las variantes usan el jumpscare de su stalker
        if (id.contains("goatevil")) return "goatevil";
        if (id.contains("goat")) return "goat";
        if (id.contains("spotted") || id.contains("solocorre")) return "solocorre";
        if (id.contains("vida") || id.contains("evilblack")) return "vida";
        if (id.contains("smiler")) return "smiler";
        if (id.contains("hello")) return "hello";
        if (id.contains("jester")) return "jester";
        if (id.contains("quad")) return "quad_red";
        if (id.contains("evil")) return "evil";
        if (id.contains("miron") || id.contains("screamer")) return "screamer";
        if (id.contains("ansiedad")) return "ansiedad";
        if (id.contains("unknown")) return "unknown";
        if (id.contains("tortura")) return "tortura";
        if (id.contains("luz")) return "luz";
        return "run";
    }

    /** Camara suave (modo calma): promedio de la camara en +-6 ticks. */
    private static double[] smooth(JsonArray cam, double t) {
        double[] out = new double[5];
        int[] offs = {-6, -4, -2, 0, 2, 4, 6};
        for (int o : offs) {
            double[] c = lerp(cam, Math.max(0, t + o));
            for (int i = 0; i < 5; i++) out[i] += c[i] / offs.length;
        }
        return out;
    }

    private static double rn() {
        return Opciones.calm() ? 0 : RND.nextDouble() - .5;
    }

    private static double[] lerp(JsonArray list, double t) {
        JsonArray first = list.get(0).getAsJsonArray();
        if (t <= first.get(0).getAsDouble()) return vals(first);
        for (int i = 0; i < list.size() - 1; i++) {
            JsonArray a = list.get(i).getAsJsonArray(), b = list.get(i + 1).getAsJsonArray();
            double ta = a.get(0).getAsDouble(), tb = b.get(0).getAsDouble();
            if (ta <= t && t <= tb) {
                if (tb == ta) return vals(b);
                double k = (t - ta) / (tb - ta);
                double[] r = new double[a.size() - 1];
                for (int j = 1; j < a.size(); j++) r[j - 1] = a.get(j).getAsDouble() + (b.get(j).getAsDouble() - a.get(j).getAsDouble()) * k;
                return r;
            }
        }
        return vals(list.get(list.size() - 1).getAsJsonArray());
    }

    private static double[] vals(JsonArray a) {
        double[] r = new double[a.size() - 1];
        for (int j = 1; j < a.size(); j++) r[j - 1] = a.get(j).getAsDouble();
        return r;
    }

    private static int step(JsonArray list, int t) {
        int v = list.get(0).getAsJsonArray().get(1).getAsInt();
        for (var el : list) {
            JsonArray k = el.getAsJsonArray();
            if (k.get(0).getAsDouble() <= t) v = k.get(1).getAsInt();
        }
        return v;
    }

    private static double free(ServerLevel l, ServerPlayer p, Vec3 from, Vec3 dir, double max) {
        var hit = l.clip(new ClipContext(from, from.add(dir.scale(max)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        if (hit.getType() == HitResult.Type.BLOCK) return Math.max(0, hit.getLocation().distanceTo(from) - .25);
        return max;
    }

    /** El stalker te atrapo: hace su jumpscare y despues te mata. */
    public static void kill(ServerPlayer p, StalkerEntity killer) {
        if (killer != null && killer.isStunned()) return;      // aturdido con la linterna: no te mata
        String id = killer == null ? "stalker_run" : killer.getDefId();
        if (killer != null && id.equals("stalker_run") && killer.getProp("skin") == 1) id = "stalker_run_sueno";     // el RUN del sueño tiene su propio susto
        kill(p, id);
    }

    public static void kill(ServerPlayer p, String defId) {
        if (p == null || ACTIVE.containsKey(p.getUUID()) || !p.isAlive()) return;
        if (Linterna.blocksKill(p, defId)) return;              // un aturdido cerca: no te mata
        if (OJS.containsKey(defId) && killOjs(p, defId)) return;
        JsonObject all = scenes();
        String model = modelOf(defId);
        JsonObject sc = all.has(model) ? all.getAsJsonObject(model) : all.getAsJsonObject("run");
        TwixxelMod.LOGGER.info("JumpScare: mata {} -> escena '{}' (js={})", defId, all.has(model) ? model : "run", sc == null ? -1 : sc.get("n").getAsInt());
        ServerLevel l = p.serverLevel();
        if (sc == null) {
            p.kill();
            return;
        }
        St s = new St();
        s.pid = p.getUUID();
        s.level = l;
        s.sc = sc;
        s.eye = p.getEyePosition();
        s.feet = p.position();
        s.yaw = p.getYRot();
        double rad = s.yaw * Math.PI / 180;
        s.fx = -Math.sin(rad);
        s.fz = Math.cos(rad);
        s.rx = -Math.cos(rad);
        s.rz = -Math.sin(rad);
        s.freeF = free(l, p, s.eye, new Vec3(s.fx, 0, s.fz), 7);
        s.freeB = free(l, p, s.eye, new Vec3(-s.fx, 0, -s.fz), 3);
        s.freeR = free(l, p, s.eye, new Vec3(s.rx, 0, s.rz), 3);
        s.freeL = free(l, p, s.eye, new Vec3(-s.rx, 0, -s.rz), 3);
        s.freeUp = free(l, p, s.eye, new Vec3(0, 1, 0), 3);
        s.ent = SpawnHelper.createScripted(l, "jumpscare", s.eye, s.yaw);
        if (s.ent == null) {
            p.kill();
            return;
        }
        s.ent.noCulling = true;
        s.ent.setProp("js", sc.get("n").getAsDouble());
        s.ent.setProp("js_s", 0);
        s.rig = new ArmorStand(l, s.eye.x, s.eye.y - 1.78, s.eye.z);
        s.rig.setInvisible(true);
        s.rig.setNoGravity(true);
        s.rig.noPhysics = true;
        s.rig.setInvulnerable(true);
        l.addFreshEntity(s.rig);
        s.wasInvulnerable = p.isInvulnerable();
        p.setInvulnerable(true);
        hideGear(p);
        p.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 400, 0, false, false));
        p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 400, 0, false, false));
        p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 400, 10, false, false));
        p.addEffect(new MobEffectInstance(MobEffects.JUMP, 400, 200, false, false));
        p.removeEffect(MobEffects.BLINDNESS);
        p.connection.send(new ClientboundSetTitleTextPacket(Component.empty()));
        Net.title(p, "", 0);
        p.displayClientMessage(Component.empty(), true);
        ACTIVE.put(p.getUUID(), s);
    }

    private static void snd(ServerPlayer p, String id, float vol, float pitch) {
        SoundEvent se = BedHide.se(id);
        if (se != null) p.playNotifySound(se, SoundSource.MASTER, vol, pitch);
    }

    /** Arranca el jumpscare propio: un titere nuevo del mismo stalker (su modelo y su animacion de matar). */
    private static boolean killOjs(ServerPlayer p, String defId) {
        ServerLevel l = p.serverLevel();
        St s = new St();
        s.pid = p.getUUID();
        s.level = l;
        s.ojs = OJS.get(defId);
        s.ojsId = defId;
        s.eye = p.getEyePosition();
        s.feet = p.position();
        s.yaw = p.getYRot();
        double rad = s.yaw * Math.PI / 180;
        s.fx = -Math.sin(rad);
        s.fz = Math.cos(rad);
        s.freeF = free(l, p, s.eye, new Vec3(s.fx, 0, s.fz), 4);
        s.freeUp = free(l, p, s.eye, new Vec3(0, 1, 0), 3);
        s.ojsFree = Math.max(1.2, s.freeF);
        s.ent = SpawnHelper.createScripted(l, defId, s.eye, s.yaw);
        if (s.ent == null) return false;
        s.ent.noCulling = true;
        s.ent.setProp("mov", 0);
        s.ent.setProp("rompe", 0);
        s.ent.setProp("fase", s.ojs.fase);
        TwixxelMod.LOGGER.info("JumpScare: mata {} -> jumpscare propio", defId);
        s.rig = new ArmorStand(l, s.eye.x, s.eye.y - 1.78, s.eye.z);
        s.rig.setInvisible(true);
        s.rig.setNoGravity(true);
        s.rig.noPhysics = true;
        s.rig.setInvulnerable(true);
        l.addFreshEntity(s.rig);
        s.wasInvulnerable = p.isInvulnerable();
        p.setInvulnerable(true);
        hideGear(p);
        p.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 400, 0, false, false));
        p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 400, 0, false, false));
        p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 400, 10, false, false));
        p.addEffect(new MobEffectInstance(MobEffects.JUMP, 400, 200, false, false));
        p.removeEffect(MobEffects.BLINDNESS);
        p.connection.send(new ClientboundSetTitleTextPacket(Component.empty()));
        Net.title(p, "", 0);
        p.displayClientMessage(Component.empty(), true);
        ACTIVE.put(p.getUUID(), s);
        return true;
    }

    private static void blood(St s, Vec3 at) {
        s.level.sendParticles(new net.minecraft.core.particles.DustParticleOptions(new org.joml.Vector3f(.6f, 0f, 0f), 1.3f), at.x, at.y, at.z, 14, .25, .2, .25, 0.02);
    }

    /** Un fotograma del jumpscare propio. Devuelve true al terminar. */
    private static boolean ojsTick(St s, ServerPlayer p) {
        int t = s.t;
        boolean mb = s.ojsId.equals("stalker_maebi");
        double jit, d, ey = s.eye.y - s.ojs.faceY, pitch = 0;
        Vec3 cam = s.eye;
        int fase = s.ojs.fase;
        double feetY = s.eye.y - 1.62;
        if (!mb) {
            if (t < 5) d = s.ojs.d0;
            else if (t < 10) {
                double f = (t - 5) / 5.0;
                d = s.ojs.d0 + (s.ojs.d1 - s.ojs.d0) * f * f;
            } else d = s.ojs.d1;
            d = Math.min(d, s.ojsFree);
            jit = t < 5 ? 0 : .05;
            double cj = t < 5 ? 0 : .03;
            cam = s.eye.add(rn() * cj, rn() * cj, rn() * cj);
            ey += rn() * jit;
            if (t == 1) snd(p, "stalker.js_crack2", 1f, .7f);
            if (t == 5) {
                snd(p, s.ojs.scream, 1.3f, 1.05f);
                s.shakeAmp = .16;
                s.shakeUntil = t + 28;
            }
            if (t == 10) {
                snd(p, "stalker.js_golpe", 1f, .8f);
                s.shakeAmp = .18;
                s.shakeUntil = t + 12;
            }
            if (t == OJS_LEN - 8) p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 30, 0, false, false));
        } else {
            double sh = .06;
            if (t < MBJ_BEND_END) {                              // 1) delante tuyo, doblando el cuello
                double f = Math.min(1, t / (double) (MBJ_BEND_END - 4)), e = f * f * (3 - 2 * f);
                d = Math.min(2.6, s.ojsFree);
                ey = feetY - (MB_FACE_Y - 1.62) * e;
                fase = 1;
                pitch = -48 + 44 * e;
                if (t == 6) snd(p, "stalker.js_crack1", 1f, .9f);
                if (t == 12) snd(p, "stalker.js_crack2", 1f, .9f);
                if (t == 19) snd(p, "stalker.js_crack3", 1f, .9f);
                if (t == 6 || t == 12 || t == 19) { s.shakeAmp = .03; s.shakeUntil = t + 3; }
            } else if (t < MBJ_THROW) {                          // 2) te grita en la cara
                double f = Math.min(1, (t - MBJ_BEND_END) / 4.0);
                d = Math.min(2.6 - 1.3 * f, s.ojsFree);
                ey = s.eye.y - MB_FACE_Y - .25 * f;
                fase = 4;
                cam = s.eye.add(rn() * sh, rn() * sh, rn() * sh);
                pitch = -2;
                if (t == MBJ_BEND_END) { snd(p, "stalker.js_goat", 1.2f, 1f); s.shakeAmp = .16; s.shakeUntil = t + 28; }
            } else if (t < MBJ_SCRATCH_END) {                    // 3) te bota al piso y te rasguña
                double f = Math.min(1, (t - MBJ_THROW) / 4.0);
                d = Math.min(1.6, s.ojsFree);
                ey = feetY;
                fase = 5;
                cam = new Vec3(s.eye.x - s.fx * .3 * f, s.eye.y - 1.35 * f + rn() * .04, s.eye.z - s.fz * .3 * f);
                pitch = -40 - 25 * f;
                if (t == MBJ_THROW) { snd(p, "stalker.js_golpe", 1f, .7f); s.shakeAmp = .18; s.shakeUntil = t + 10; }
                if (t == MBJ_THROW + 3) snd(p, "game.player.hurt", 1f, .8f);
                if (t == MBJ_THROW + 8 || t == MBJ_THROW + 15) {
                    snd(p, "mob.zombie.woodbreak", .6f, 1.6f);
                    snd(p, "game.player.hurt", 1f, .9f);
                    s.shakeAmp = .08;
                    s.shakeUntil = t + 7;
                    blood(s, new Vec3(s.eye.x + s.fx * .6, feetY + .3, s.eye.z + s.fz * .6));
                }
            } else {                                             // 4) te pisotea hasta matarte
                d = Math.min(1.2, s.ojsFree);
                ey = feetY;
                fase = 6;
                cam = new Vec3(s.eye.x - s.fx * .3 + rn() * .08, feetY + .27 + rn() * .05, s.eye.z - s.fz * .3 + rn() * .08);
                pitch = -68;
                if ((t - MBJ_SCRATCH_END) % 6 == 3) {
                    snd(p, "stalker.js_golpe", 1f, .6f + RND.nextFloat() * .2f);
                    snd(p, "mob.ravager.step", 1.2f, .5f);
                    snd(p, "game.player.hurt", .8f, .7f);
                    if ((t - MBJ_SCRATCH_END) % 12 == 3) snd(p, "stalker.js_crack" + (1 + RND.nextInt(3)), 1f, .8f);
                    s.shakeAmp = .13;
                    s.shakeUntil = t + 6;
                    blood(s, new Vec3(s.eye.x + s.fx * .3, feetY + .1, s.eye.z + s.fz * .3));
                }
                if (t == MBJ_LEN - 10) p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 40, 0, false, false));
            }
        }
        if (s.ent != null && s.ent.isAlive()) {
            if (fase != s.lastFase) {
                s.lastFase = fase;
                s.ent.setProp("fase", fase);
            }
            float eyaw = s.yaw + 180;
            s.ent.setPos(s.eye.x + s.fx * d, ey, s.eye.z + s.fz * d);
            s.ent.setYRot(eyaw);
            s.ent.setYHeadRot(eyaw);
            s.ent.setYBodyRot(eyaw);
        }
        if (t < s.shakeUntil && !Opciones.calm()) cam = cam.add(rn() * s.shakeAmp, rn() * s.shakeAmp, rn() * s.shakeAmp);
        if (s.rig != null && s.rig.isAlive()) {
            s.rig.setPos(cam.x, cam.y - s.rig.getEyeHeight(), cam.z);
            s.rig.setYRot(s.yaw);
            s.rig.setYHeadRot(s.yaw);
            s.rig.setYBodyRot(s.yaw);
            s.rig.setXRot((float) pitch);
            if (!s.camSent && t >= 2) {
                p.connection.send(new ClientboundSetCameraPacket(s.rig));
                s.camSent = true;
            }
        }
        if (t % 2 == 0) p.teleportTo(s.level, s.feet.x, s.feet.y, s.feet.z, s.yaw, 0f);
        p.setDeltaMovement(Vec3.ZERO);
        return t >= (mb ? MBJ_LEN : OJS_LEN);
    }

    private static void hideGear(ServerPlayer p) {
        CompoundTag root = new CompoundTag();
        for (EquipmentSlot sl : SLOTS) {
            ItemStack it = p.getItemBySlot(sl);
            if (it.isEmpty()) continue;
            root.put(sl.getName(), it.save(new CompoundTag()));
            p.setItemSlot(sl, ItemStack.EMPTY);
        }
        p.getPersistentData().put(GEAR_KEY, root);
    }

    private static void restoreGear(ServerPlayer p) {
        CompoundTag pd = p.getPersistentData();
        if (!pd.contains(GEAR_KEY)) return;
        CompoundTag root = pd.getCompound(GEAR_KEY);
        pd.remove(GEAR_KEY);
        for (EquipmentSlot sl : SLOTS) {
            if (!root.contains(sl.getName())) continue;
            ItemStack it = ItemStack.of(root.getCompound(sl.getName()));
            if (it.isEmpty()) continue;
            if (p.getItemBySlot(sl).isEmpty()) p.setItemSlot(sl, it);
            else if (!p.getInventory().add(it)) p.drop(it, false);
        }
    }

    private static Vec3 world(St s, double f, double u, double sd, boolean clamp) {
        if (clamp) {
            f = f > 0 ? Math.min(f, Math.max(.45, s.freeF)) : Math.max(f, -Math.max(.3, s.freeB));
            sd = sd > 0 ? Math.min(sd, Math.max(.3, s.freeR)) : Math.max(sd, -Math.max(.3, s.freeL));
            if (u > 0) u = Math.min(u, Math.max(0, s.freeUp - .2));
        }
        return new Vec3(s.eye.x + s.fx * f + s.rx * sd, s.eye.y + u, s.eye.z + s.fz * f + s.rz * sd);
    }

    private static void event(St s, ServerPlayer p, JsonArray e) {
        String k = e.get(1).getAsString();
        switch (k) {
            case "snd": {
                String id = e.get(2).getAsString();
                var r = TwixxelMod.SOUND_EVENTS.get(TwixxelMod.loc(id));
                SoundEvent se = r != null ? r.get() : BedHide.se(id);
                if (se == null) return;
                float pitch = e.size() > 3 ? e.get(3).getAsFloat() : 1f, vol = e.size() > 4 ? e.get(4).getAsFloat() : 1f;
                p.playNotifySound(se, SoundSource.MASTER, vol, pitch);
                return;
            }
            case "shake":
                if (Opciones.calm()) return;
                s.shakeAmp = Math.min(4, e.get(2).getAsDouble()) * .06;
                s.shakeUntil = s.t + (int) Math.round(e.get(3).getAsDouble() * 20);
                return;
            case "fade": {
                int ticks = (int) Math.round((e.get(3).getAsDouble() + e.get(4).getAsDouble()) * 20) + 10;
                p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, Math.max(10, ticks), 0, false, false));   // aproximacion: negro
                return;
            }
            case "title":
                Net.title(p, e.get(es() ? 2 : 3).getAsString(), 35);
                return;
            default:
        }
    }

    private static void finish(St s, ServerPlayer p, boolean kill) {
        ACTIVE.remove(s.pid);
        if (s.ent != null && s.ent.isAlive()) s.ent.discard();
        if (p == null) {
            if (s.rig != null && s.rig.isAlive()) s.rig.discard();
            return;
        }
        if (s.camSent) p.connection.send(new ClientboundSetCameraPacket(p));
        if (s.rig != null && s.rig.isAlive()) s.rig.discard();
        restoreGear(p);
        p.setInvulnerable(s.wasInvulnerable);
        for (var ef : new net.minecraft.world.effect.MobEffect[]{MobEffects.INVISIBILITY, MobEffects.NIGHT_VISION, MobEffects.MOVEMENT_SLOWDOWN, MobEffects.JUMP, MobEffects.BLINDNESS})
            p.removeEffect(ef);
        p.teleportTo(p.serverLevel(), s.feet.x, s.feet.y, s.feet.z, s.yaw, 0f);
        if (kill && SuenoEvent.wakeInstead(p)) kill = false;     // en el sueño no mueres: despiertas
        if (kill) {
            p.setInvulnerable(false);
            p.hurt(p.damageSources().genericKill(), Float.MAX_VALUE);
            if (p.isAlive()) p.kill();
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END || ACTIVE.isEmpty()) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        for (St s : new ArrayList<>(ACTIVE.values())) {
            ServerPlayer p = server.getPlayerList().getPlayer(s.pid);
            if (p == null || !p.isAlive()) {
                finish(s, null, false);
                continue;
            }
            s.t++;
            if (s.ojs != null) {
                if (ojsTick(s, p)) finish(s, p, true);
                continue;
            }
            int t = s.t;
            JsonObject sc = s.sc;
            JsonArray evs = sc.getAsJsonArray("ev");
            while (s.evi < evs.size() && evs.get(s.evi).getAsJsonArray().get(0).getAsDouble() <= t) {
                event(s, p, evs.get(s.evi).getAsJsonArray());
                s.evi++;
            }
            // camara
            double[] c = Opciones.calm() ? smooth(sc.getAsJsonArray("cam"), t) : lerp(sc.getAsJsonArray("cam"), t);
            Vec3 cp = world(s, c[0], c[1], c[2], true);
            if (t < s.shakeUntil) cp = cp.add((RND.nextDouble() - .5) * s.shakeAmp, (RND.nextDouble() - .5) * s.shakeAmp, (RND.nextDouble() - .5) * s.shakeAmp);
            float camYaw = (float) (s.yaw + c[4]), camPitch = (float) c[3];
            if (s.rig != null && s.rig.isAlive()) {
                s.rig.setPos(cp.x, cp.y - s.rig.getEyeHeight(), cp.z);
                s.rig.setYRot(camYaw);
                s.rig.setYHeadRot(camYaw);
                s.rig.setYBodyRot(camYaw);
                s.rig.setXRot(camPitch);
                if (!s.camSent && t >= 2) {
                    p.connection.send(new ClientboundSetCameraPacket(s.rig));
                    s.camSent = true;
                }
            }
            // el jugador queda quieto (o sube con la camara)
            double lu = sc.get("lift").getAsBoolean() ? Math.max(0, Math.min(c[0], s.freeUp - .2)) : 0;
            if (t % 2 == 0 || lu > 0) p.teleportTo(s.level, s.feet.x, s.feet.y + lu, s.feet.z, s.yaw, 0f);
            p.setDeltaMovement(Vec3.ZERO);
            // el stalker
            if (s.ent != null && s.ent.isAlive()) {
                double sSc = t < 2 ? 0 : Math.max(0, lerp(sc.getAsJsonArray("sc"), t)[0]);
                int fase = step(sc.getAsJsonArray("fase"), t), jb = step(sc.getAsJsonArray("jb"), t);
                if (fase != s.lastFase) {
                    s.lastFase = fase;
                    s.ent.setProp("jf", fase);
                }
                if (jb != s.lastJb) {
                    s.lastJb = jb;
                    s.ent.setProp("jb", jb);
                }
                if (Math.abs(sSc - s.lastS) > .0005) {
                    s.lastS = sSc;
                    s.ent.setProp("js_s", sSc);
                }
                double[] q = lerp(sc.getAsJsonArray("pup"), t);
                Vec3 pos = sSc > .001 ? world(s, q[0], q[1], q[2], true) : s.eye;
                float ey = (float) (s.yaw + 180 + q[3] + sc.get("yaw").getAsDouble());
                s.ent.setPos(pos.x, pos.y, pos.z);
                s.ent.setYRot(ey);
                s.ent.setYHeadRot(ey);
                s.ent.setYBodyRot(ey);
            }
            if (t >= sc.get("len").getAsInt()) finish(s, p, true);
        }
    }

    /** Si el jugador entra con equipo guardado o camara pegada (cierre en mitad de la escena), se arregla. */
    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent ev) {
        if (!(ev.getEntity() instanceof ServerPlayer p) || ACTIVE.containsKey(p.getUUID())) return;
        restoreGear(p);
        p.connection.send(new ClientboundSetCameraPacket(p));
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent ev) {
        if (!(ev.getEntity() instanceof ServerPlayer p)) return;
        St s = ACTIVE.get(p.getUUID());
        if (s != null) finish(s, p, true);
    }
}
