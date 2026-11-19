package com.twixxel.stalker.event;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.twixxel.stalker.TwixxelMod;
import com.twixxel.stalker.entity.StalkerEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PELUCHE VERITY: bolita amarilla con carita. Te sigue rodando (hasta que la sientas), al apretarla rebota y suena su cancion
 * (19 trocitos de 2 s que suenan donde esta ella), cambia de cara al hablar / al pegarle. No se le puede hacer dano.
 */
public class Verity {
    private static final int CHUNKS = 19, TP_DIST = 12;
    private static final Map<UUID, int[]> SONG = new HashMap<>();      // {trocito siguiente, tick en que suena, trocito actual}
    private static final Map<UUID, Integer> HURT = new HashMap<>();    // tick hasta el que pone la cara del guino
    private static final Map<UUID, Integer> LAST = new HashMap<>();

    private static boolean es() {
        return Lang.es();
    }

    private static String t(String a, String b) {
        return es() ? a : b;
    }

    private static boolean isVerity(Entity e) {
        return e instanceof StalkerEntity s && s.getDefId().equals("verity");
    }

    private static boolean sitting(Entity e) {
        return e.getPersistentData().getBoolean("sentado");
    }

    private static void bar(ServerPlayer p, String m) {
        p.displayClientMessage(Component.literal(m), true);
    }

    // ------------------------------------------------------------------ colocar
    public static InteractionResult place(ServerPlayer p, ItemStack st, BlockPos clicked, Direction face) {
        ServerLevel l = p.serverLevel();
        List<BlockPos> tries = new ArrayList<>();
        if (clicked != null) {
            tries.add(clicked.relative(face == null ? Direction.UP : face));
            tries.add(clicked.above());
        } else {
            Vec3 v = p.getLookAngle();
            for (double d : new double[]{1.5, 1, 2}) tries.add(BlockPos.containing(p.getX() + v.x * d, p.getY(), p.getZ() + v.z * d));
        }
        BlockPos at = null;
        for (BlockPos b : tries) if (Houses.free(l, b)) {
            at = b;
            break;
        }
        if (at == null) {
            bar(p, t("§fNo hay espacio para ponerlo aquí", "§fThere is no room to place it here"));
            return InteractionResult.FAIL;
        }
        var ro = TwixxelMod.TYPES.get("verity");
        if (ro == null) return InteractionResult.FAIL;
        StalkerEntity e = ro.get().create(l);
        if (e == null) return InteractionResult.FAIL;
        double dx = p.getX() - (at.getX() + .5), dz = p.getZ() - (at.getZ() + .5);
        e.moveTo(at.getX() + .5, at.getY(), at.getZ() + .5, (float) (Math.atan2(-dx, dz) * 180 / Math.PI), 0);
        e.getPersistentData().putUUID("dueno", p.getUUID());
        e.getPersistentData().putBoolean("sentado", false);
        l.addFreshEntity(e);
        l.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.CHICKEN_EGG, SoundSource.NEUTRAL, .6f, 1.2f);
        if (!p.getAbilities().instabuild) st.shrink(1);
        return InteractionResult.SUCCESS;
    }

    @SubscribeEvent
    public void onJoin(EntityJoinLevelEvent ev) {
        if (ev.getLevel().isClientSide) return;
        Entity e = ev.getEntity();
        if (isVerity(e)) e.setInvulnerable(true);
        if (e instanceof ItemEntity ie && ie.getItem().getItem() instanceof com.twixxel.stalker.item.VerityItem) ie.setUnlimitedLifetime();
    }

    // ------------------------------------------------------------------ cancion
    private static void squeeze(StalkerEntity v) {
        int now = ServerLifecycleHooks.getCurrentServer().getTickCount();
        v.setDeltaMovement(v.getDeltaMovement().x, .42, v.getDeltaMovement().z);
        v.hasImpulse = true;
        if (!SONG.containsKey(v.getUUID())) {
            SONG.put(v.getUUID(), new int[]{0, now, -1});
            v.setProp("habla", 1);
            v.setProp("hablando", 1);
        } else {
            ((ServerLevel) v.level()).playSound(null, v.getX(), v.getY(), v.getZ(), SoundEvents.CHICKEN_EGG, SoundSource.NEUTRAL, .4f, .8f + (float) Math.random() * .4f);
        }
    }

    private static void stopChunk(StalkerEntity v, int i) {
        if (i < 0) return;
        var r = TwixxelMod.SOUND_EVENTS.get("twixxel.verity_m" + i);
        if (r == null) return;
        for (ServerPlayer p : ((ServerLevel) v.level()).players()) p.connection.send(new ClientboundStopSoundPacket(r.getId(), SoundSource.MASTER));
    }

    private static void silence(StalkerEntity v) {
        int[] s = SONG.remove(v.getUUID());
        if (s != null) {
            stopChunk(v, s[2]);
            stopChunk(v, s[2] - 1);
        }
        int now = ServerLifecycleHooks.getCurrentServer().getTickCount();
        if (HURT.getOrDefault(v.getUUID(), 0) <= now) v.setProp("habla", 0);
        v.setProp("hablando", 0);
    }

    // ------------------------------------------------------------------ interaccion
    @SubscribeEvent
    public void onInteract(PlayerInteractEvent.EntityInteract ev) {
        if (ev.getLevel().isClientSide || ev.getHand() != InteractionHand.MAIN_HAND || !(ev.getEntity() instanceof ServerPlayer p) || !isVerity(ev.getTarget())) return;
        StalkerEntity v = (StalkerEntity) ev.getTarget();
        ev.setCanceled(true);
        ev.setCancellationResult(InteractionResult.SUCCESS);
        int now = p.server.getTickCount();
        Integer last = LAST.get(p.getUUID());
        if (last != null && now - last < 6) return;
        LAST.put(p.getUUID(), now);
        if (p.isShiftKeyDown()) menu(v, p);
        else squeeze(v);
    }

    /** No se le puede pegar: sin dano, y pone la cara del guino medio segundo. */
    @SubscribeEvent
    public void onAttack(AttackEntityEvent ev) {
        if (ev.getEntity().level().isClientSide || !isVerity(ev.getTarget())) return;
        ev.setCanceled(true);
        StalkerEntity v = (StalkerEntity) ev.getTarget();
        v.setProp("habla", 2);
        HURT.put(v.getUUID(), ServerLifecycleHooks.getCurrentServer().getTickCount() + 10);
    }

    private static MutableComponent button(String label, String cmd, ChatFormatting c) {
        return Component.literal("[" + label + "] ").withStyle(s -> s.withColor(c).withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd)));
    }

    private static void menu(StalkerEntity v, ServerPlayer p) {
        String id = v.getUUID().toString();
        MutableComponent c = Component.literal("§eVerity §7— " + t("¿Qué quieres hacer?", "What do you want to do?") + "\n");
        c.append(button(t("Agarrarlo", "Pick up"), "/twxverity agarrar " + id, ChatFormatting.GREEN));
        c.append(button(sitting(v) ? t("Que me siga", "Follow me") : t("Sentarlo", "Sit"), "/twxverity sentar " + id, ChatFormatting.AQUA));
        c.append(button(t("Silenciar", "Mute"), "/twxverity callar " + id, ChatFormatting.GRAY));
        p.sendSystemMessage(c);
    }

    @SubscribeEvent
    public void commands(RegisterCommandsEvent ev) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("twxverity");
        for (String sub : new String[]{"agarrar", "sentar", "callar"}) {
            root.then(Commands.literal(sub).then(Commands.argument("id", StringArgumentType.word()).executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                Entity e;
                try {
                    e = p.serverLevel().getEntity(UUID.fromString(StringArgumentType.getString(c, "id")));
                } catch (IllegalArgumentException ex) {
                    return 0;
                }
                if (!isVerity(e) || e.distanceTo(p) > 8) return 0;
                StalkerEntity v = (StalkerEntity) e;
                switch (sub) {
                    case "agarrar": {
                        silence(v);
                        v.discard();
                        Item it = ForgeRegistries.ITEMS.getValue(new net.minecraft.resources.ResourceLocation(TwixxelMod.MODID, "verity_peluche"));
                        if (it != null) {
                            ItemStack st = new ItemStack(it);
                            if (!p.getInventory().add(st)) p.drop(st, false);
                        }
                        bar(p, t("§eTienes a Verity", "§eYou picked up Verity"));
                        break;
                    }
                    case "sentar":
                        if (sitting(v)) {
                            v.getPersistentData().putBoolean("sentado", false);
                            v.getPersistentData().putUUID("dueno", p.getUUID());
                            bar(p, t("§eVerity te sigue", "§eVerity follows you"));
                        } else {
                            v.getPersistentData().putBoolean("sentado", true);
                            v.getNavigation().stop();
                            v.setProp("mueve", 0);
                            bar(p, t("§eVerity se sentó", "§eVerity sat down"));
                        }
                        break;
                    default:
                        silence(v);
                        bar(p, t("§eVerity se calló", "§eVerity is muted"));
                }
                return 1;
            })));
        }
        ev.getDispatcher().register(root);
    }

    // ------------------------------------------------------------------ bucle
    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END) return;
        MinecraftServer s = ServerLifecycleHooks.getCurrentServer();
        if (s == null) return;
        int now = s.getTickCount();
        // cancion: un trocito cada 2 s, sonando donde esta Verity en ese momento
        if (!SONG.isEmpty()) {
            for (UUID id : new ArrayList<>(SONG.keySet())) {
                int[] st = SONG.get(id);
                StalkerEntity v = find(s, id);
                if (v == null) {
                    SONG.remove(id);
                    continue;
                }
                if (now < st[1]) continue;
                if (st[0] >= CHUNKS) {
                    silence(v);
                    continue;
                }
                SoundEvent se = soundOf(st[0]);
                if (se != null) ((ServerLevel) v.level()).playSound(null, v.getX(), v.getY(), v.getZ(), se, SoundSource.MASTER, 1f, 1f);
                st[2] = st[0];
                st[0]++;
                st[1] += 40;
            }
        }
        if (now % 5 != 0) return;
        for (ServerLevel l : s.getAllLevels()) {
            for (Entity e : l.getAllEntities()) {
                if (!isVerity(e) || !e.isAlive()) continue;
                StalkerEntity v = (StalkerEntity) e;
                Integer h = HURT.get(v.getUUID());
                if (h != null && now >= h) {
                    HURT.remove(v.getUUID());
                    v.setProp("habla", SONG.containsKey(v.getUUID()) ? 1 : 0);
                }
                follow(s, v, now);
            }
        }
    }

    private static SoundEvent soundOf(int i) {
        var r = TwixxelMod.SOUND_EVENTS.get("twixxel.verity_m" + i);
        return r == null ? null : r.get();
    }

    private static StalkerEntity find(MinecraftServer s, UUID id) {
        for (ServerLevel l : s.getAllLevels()) {
            Entity e = l.getEntity(id);
            if (e instanceof StalkerEntity v && v.isAlive()) return v;
        }
        return null;
    }

    private static void follow(MinecraftServer s, StalkerEntity v, int now) {
        if (sitting(v) || !v.getPersistentData().hasUUID("dueno")) {
            v.setProp("mueve", 0);
            return;
        }
        ServerPlayer owner = s.getPlayerList().getPlayer(v.getPersistentData().getUUID("dueno"));
        if (owner == null) return;
        if (owner.level() != v.level() || v.distanceTo(owner) > TP_DIST || v.getY() < v.level().getMinBuildHeight() + 2) {
            BlockPos q = owner.blockPosition();
            if (owner.level() != v.level()) {
                Entity ne = v.changeDimension((ServerLevel) owner.level());
                if (ne != null) ne.teleportTo((ServerLevel) owner.level(), q.getX() + .5, q.getY(), q.getZ() + .5, java.util.Set.of(), v.getYRot(), 0);
            } else v.teleportTo((ServerLevel) owner.level(), q.getX() + .5, q.getY(), q.getZ() + .5, java.util.Set.of(), v.getYRot(), 0);
            return;
        }
        double d = v.distanceTo(owner);
        if (d > 3.5) {
            v.getNavigation().moveTo(owner, 1.5);
            v.setProp("mueve", 1);
        } else if (d < 2.5) {
            v.getNavigation().stop();
            v.setProp("mueve", 0);
        }
    }
}
