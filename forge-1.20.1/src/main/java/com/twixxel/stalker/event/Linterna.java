package com.twixxel.stalker.event;

import com.twixxel.stalker.TwixxelMod;
import com.twixxel.stalker.entity.StalkerEntity;
import com.twixxel.stalker.item.LinternaItem;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Linterna (addon v3.7.6): usarla la prende 3 s (gasta 1 de 20 usos); despues se apaga y se recarga 10 s.
 * Prendida, la luz va contigo; si un stalker que te persigue toca la luz queda aturdido 3 s (no se mueve y no te
 * atrapa) y la linterna se apaga y se recarga 5 s. Pila (hierro + cobre) + linterna = linterna con 20 usos.
 */
public class Linterna {
    static final int ON_TICKS = 60, RECHARGE = 200, STUN_RECHARGE = 100, STUN_TICKS = 60, LEVEL = 15;

    static final Set<String> STUN_TYPES = Set.of(
            "evilblack",
            "pelea_run",
            "pelea_spotted",
            "stalker_agujero",
            "stalker_alcantarilla",
            "stalker_alcantarilla_goat",
            "stalker_ansiedad",
            "stalker_arriba",
            "stalker_asomandome",
            "stalker_bed",
            "stalker_bipedal",
            "stalker_cueva",
            "stalker_diversion",
            "stalker_door",
            "stalker_door2",
            "stalker_envuelto",
            "stalker_envuelto_acerca",
            "stalker_escalera",
            "stalker_evil",
            "stalker_goat",
            "stalker_goat_asoma",
            "stalker_goat_door",
            "stalker_goat_jester",
            "stalker_goat_puerta",
            "stalker_goat_ventana2",
            "stalker_goat_window",
            "stalker_goatevil",
            "stalker_goatevil_esc",
            "stalker_goatevil_window",
            "stalker_grip",
            "stalker_hello",
            "stalker_hello2",
            "stalker_hello3",
            "stalker_hey",
            "stalker_ignore",
            "stalker_jester",
            "stalker_life",
            "stalker_luz",
            "stalker_maebi",
            "stalker_miron",
            "stalker_peek1",
            "stalker_peek2",
            "stalker_peeker",
            "stalker_puertas",
            "stalker_puertas_cuerpo",
            "stalker_quad",
            "stalker_quad_red",
            "stalker_redlight",
            "stalker_run",
            "stalker_run_asoma",
            "stalker_run_bedpeek",
            "stalker_run_mina",
            "stalker_run_pasillo",
            "stalker_run_peek",
            "stalker_run_window",
            "stalker_screamer",
            "stalker_smiler",
            "stalker_smiler_ventana",
            "stalker_solocorre",
            "stalker_spotted",
            "stalker_spotted2",
            "stalker_spotted_asoma",
            "stalker_sufrimiento",
            "stalker_tortura",
            "stalker_tree",
            "stalker_trepador",
            "stalker_unknown",
            "stalker_vida",
            "stalker_vida_asoma",
            "stalker_vida_ventana",
            "stalker_vigilante",
            "stalker_watcher",
            "stalker_watcher2",
            "stalker_window",
            "stalker_windowbreak",
            "stalker_woman");

    private static final Map<UUID, Long> onUntil = new HashMap<>();
    private static final Map<UUID, Long> cool = new HashMap<>();
    private static final Map<UUID, Light> light = new HashMap<>();

    private record Light(ResourceKey<Level> dim, BlockPos pos) {
    }

    private static boolean isLin(ItemStack s) {
        return s.getItem() instanceof LinternaItem;
    }

    private static ItemStack make(ItemStack from, boolean on, int spend) {
        Item it = (on ? TwixxelMod.LIN_ON : TwixxelMod.LIN_OFF).get();
        ItemStack n = new ItemStack(it);
        if (from.hasCustomHoverName()) n.setHoverName(from.getHoverName());
        n.setDamageValue(Math.min(n.getMaxDamage(), from.getDamageValue() + spend));
        return n;
    }

    private static int usesLeft(ItemStack s) {
        return Math.max(0, s.getMaxDamage() - s.getDamageValue());
    }

    private static void click(ServerPlayer p, float pitch, float vol) {
        p.level().playSound(null, p.blockPosition(), SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.PLAYERS, vol, pitch);
    }

    /** Clic derecho con la linterna. */
    public static void use(ServerPlayer p, InteractionHand hand, ItemStack st) {
        long now = p.level().getGameTime();
        if (((LinternaItem) st.getItem()).on) {
            click(p, 1.0f, 0.6f);
            turnOff(p, RECHARGE);
            return;
        }
        if (cool.getOrDefault(p.getUUID(), 0L) > now) {
            click(p, 0.5f, 0.4f);
            return;
        }
        if (usesLeft(st) <= 0) {
            click(p, 0.4f, 0.4f);
            return;
        }
        p.setItemInHand(hand, make(st, true, 1));
        onUntil.put(p.getUUID(), now + ON_TICKS);
        click(p, 1.4f, 0.6f);
    }

    static void turnOff(ServerPlayer p, int recharge) {
        onUntil.remove(p.getUUID());
        lightOff(p);
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.getItem() instanceof LinternaItem li && li.on) p.getInventory().setItem(i, make(s, false, 0));
        }
        if (recharge > 0) {
            cool.put(p.getUUID(), p.level().getGameTime() + recharge);
            p.getCooldowns().addCooldown(TwixxelMod.LIN_OFF.get(), recharge);
            p.getCooldowns().addCooldown(TwixxelMod.LIN_ON.get(), recharge);
        }
    }

    // ---------- luz que va contigo ----------
    private static boolean isLightBlock(BlockState b) {
        return b.is(Blocks.LIGHT);
    }

    private static void lightOff(ServerPlayer p) {
        Light o = light.remove(p.getUUID());
        if (o == null || p.getServer() == null) return;
        ServerLevel l = p.getServer().getLevel(o.dim);
        if (l != null && isLightBlock(l.getBlockState(o.pos))) l.setBlock(o.pos, Blocks.AIR.defaultBlockState(), 3);
    }

    private static void lightFollow(ServerPlayer p) {
        ServerLevel l = p.serverLevel();
        BlockPos base = p.blockPosition();
        Light o = light.get(p.getUUID());
        for (int dy : new int[]{1, 0}) {
            BlockPos pos = base.above(dy);
            if (o != null && o.dim.equals(l.dimension()) && o.pos.equals(pos)) return;
            BlockState b = l.getBlockState(pos);
            if (!(b.isAir() || isLightBlock(b))) continue;
            lightOff(p);
            l.setBlock(pos, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, LEVEL), 3);
            light.put(p.getUUID(), new Light(l.dimension(), pos));
            return;
        }
    }

    // ---------- aturdir ----------
    private static boolean lit(BlockPos src, Vec3 l) {
        int d = Math.abs((int) Math.floor(l.x) - src.getX()) + Math.abs((int) Math.floor(l.z) - src.getZ());
        int fy = (int) Math.floor(l.y);
        int dy = Math.min(Math.abs(fy - src.getY()), Math.min(Math.abs(fy + 1 - src.getY()), Math.abs(fy + 2 - src.getY())));
        return d + dy <= LEVEL - 1;
    }

    private static void checkStun(ServerPlayer p) {
        Light o = light.get(p.getUUID());
        if (o == null) return;
        ServerLevel l = p.serverLevel();
        AABB box = new AABB(o.pos).inflate(LEVEL + 3);
        List<StalkerEntity> list = l.getEntitiesOfClass(StalkerEntity.class, box,
                e -> e.isAlive() && !e.isScripted() && !e.isStunned() && STUN_TYPES.contains(e.getDefId()) && e.recentlyMoved(30));
        boolean any = false;
        for (StalkerEntity e : list) {
            if (!lit(o.pos, e.position())) continue;
            e.stun(STUN_TICKS);
            l.playSound(null, e.blockPosition(), SoundEvents.FIRE_EXTINGUISH, SoundSource.HOSTILE, 0.8f, 0.6f);
            any = true;
        }
        if (any) {
            l.playSound(null, p.blockPosition(), SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.5f, 1.6f);
            turnOff(p, STUN_RECHARGE);
        }
    }

    /** Un aturdido cerca no te mata (lo consulta JumpScare.kill). */
    public static boolean blocksKill(ServerPlayer p, String defId) {
        for (StalkerEntity e : p.serverLevel().getEntitiesOfClass(StalkerEntity.class, p.getBoundingBox().inflate(12))) {
            if (e.isStunned() && e.getDefId().equals(defId)
                    && Math.hypot(e.getX() - p.getX(), e.getZ() - p.getZ()) < 12) return true;
        }
        return false;
    }

    // ---------- barrita de usos ----------
    private static String bar(int left) {
        StringBuilder sb = new StringBuilder();
        String col = left > 10 ? "§a" : left > 5 ? "§e" : "§c";
        for (int i = 0; i < 10; i++) {
            int v = left - i * 2;
            sb.append(v >= 1 ? col : "§8").append("█");
        }
        return sb.toString();
    }

    @SubscribeEvent
    public void onTick(TickEvent.PlayerTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END || !(ev.player instanceof ServerPlayer p)) return;
        long now = p.level().getGameTime();
        Long until = onUntil.get(p.getUUID());
        if (until != null && now >= until) {
            click(p, 0.8f, 0.5f);
            turnOff(p, RECHARGE);
        }
        ItemStack held = p.getMainHandItem();
        boolean on = held.getItem() instanceof LinternaItem li && li.on;
        if (on && !onUntil.containsKey(p.getUUID())) turnOff(p, 0);   // prendida sin tiempo (al entrar al mundo)
        else if (on) {
            lightFollow(p);
            if (now % 2 == 0) checkStun(p);
        } else if (light.containsKey(p.getUUID())) lightOff(p);
        if (isLin(held) && now % 4 == 0) p.displayClientMessage(Component.literal(bar(usesLeft(held))), true);
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent ev) {
        if (ev.getEntity() instanceof ServerPlayer p) {
            lightOff(p);
            onUntil.remove(p.getUUID());
        }
    }
}
