package com.twixxel.stalker.event;

import com.twixxel.stalker.entity.StalkerEntity;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * RUN DE LA MINA: al romper una piedra/roca por el costado, 1 de cada 500 veces detras queda un agujero 1x2 y dentro esta el RUN
 * gateando: se truena el cuello 3 veces, saca las garras, sale gateando, se levanta y se vuelve el RUN que te persigue.
 * Huevo (o /twxrunmina): la proxima piedra que rompas por el costado trae el agujero.
 */
public final class MinaRunEvent {
    private static final double CHANCE = 1.0 / 500;
    private static final int[] CRACKS = {9, 17, 25};
    private static final int HANDS = 34, OUT = 58, END = 88;
    private static final Random RND = new Random();
    private static final Set<UUID> ARMED = new HashSet<>();

    private static StalkerEntity e;
    private static ServerLevel level;
    private static UUID pid;
    private static Vec3 p0, p1, s, h;
    private static float yaw;
    private static boolean small;
    private static int t;

    private static boolean rock(BlockState b) {
        String id = BuiltInRegistries.BLOCK.getKey(b.getBlock()).getPath();
        if (id.endsWith("_ore")) return true;
        if (id.contains("brick") || id.contains("slab") || id.contains("stairs") || id.contains("wall") || id.contains("button") || id.contains("plate")) return false;
        return id.equals("stone") || id.equals("cobblestone") || id.equals("mossy_cobblestone") || id.contains("deepslate") || id.equals("tuff")
                || id.equals("calcite") || id.equals("dripstone_block") || id.equals("granite") || id.equals("diorite") || id.equals("andesite")
                || id.equals("smooth_basalt") || id.equals("basalt") || id.equals("blackstone") || id.equals("netherrack") || id.equals("end_stone")
                || id.equals("sandstone") || id.equals("red_sandstone") || id.equals("obsidian") || id.equals("gravel") || id.equals("moss_block");
    }

    private static Vec3 lerp(Vec3 a, Vec3 b, double k) {
        k = Math.max(0, Math.min(1, k));
        return new Vec3(a.x + (b.x - a.x) * k, a.y + (b.y - a.y) * k, a.z + (b.z - a.z) * k);
    }

    private static void snd(String id, Vec3 at, float vol, float pitch) {
        SoundEvent se = BedHide.se(id);
        if (se != null) level.playSound(null, at.x, at.y, at.z, se, SoundSource.HOSTILE, vol, pitch);
    }

    private static boolean start(ServerPlayer p, BlockPos b) {
        if (e != null) return false;
        ServerLevel l = p.serverLevel();
        Vec3 eye = p.getEyePosition();
        double dx = eye.x - (b.getX() + .5), dy = eye.y - (b.getY() + .5), dz = eye.z - (b.getZ() + .5);
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        if (ay >= ax && ay >= az) return false;                 // solo por el costado
        if (ax < .12 && az < .12) return false;
        int nx = 0, nz = 0;
        if (ax >= az) nx = dx > 0 ? 1 : -1;
        else nz = dz > 0 ? 1 : -1;
        BlockPos c0 = b.offset(-nx, 0, -nz), c1 = c0.above();
        for (BlockPos c : new BlockPos[]{c0, c1}) {
            BlockState st = l.getBlockState(c);
            if (!st.isAir() && !rock(st)) return false;
        }
        for (BlockPos c : new BlockPos[]{c0, c1}) if (!l.getBlockState(c).isAir()) l.setBlock(c, Blocks.AIR.defaultBlockState(), 3);
        BlockState up = l.getBlockState(b.above());
        if (!up.isAir() && rock(up)) l.setBlock(b.above(), Blocks.AIR.defaultBlockState(), 3);
        level = l;
        h = new Vec3(c0.getX() + .5, c0.getY() + 1, c0.getZ() + .5);
        l.playSound(null, h.x, h.y, h.z, SoundEvents.STONE_BREAK, SoundSource.BLOCKS, 1f, .6f);
        s = new Vec3(b.getX() + .5 + nx * .6, b.getY(), b.getZ() + .5 + nz * .6);
        yaw = (float) (Math.atan2(-nx, nz) * 180 / Math.PI);
        p0 = new Vec3(c0.getX() + .5 - nx * 2.0, c0.getY() + .6, c0.getZ() + .5 - nz * 2.0);
        p1 = new Vec3(c0.getX() + .5 - nx * .6, c0.getY() + .6, c0.getZ() + .5 - nz * .6);
        BlockPos top = new BlockPos((int) Math.floor(s.x), (int) Math.floor(s.y) + 3, (int) Math.floor(s.z));
        small = !l.getBlockState(top).isAir();
        StalkerEntity st = SpawnHelper.createScripted(l, "stalker_run_mina", p0, yaw);
        if (st == null) return false;
        st.noCulling = true;
        st.setProp("fase", 0);
        e = st;
        pid = p.getUUID();
        t = 0;
        return true;
    }

    private static void end() {
        if (e != null && e.isAlive()) e.discard();
        e = null;
    }

    @SubscribeEvent
    public void onBreak(BlockEvent.BreakEvent ev) {
        if (!(ev.getPlayer() instanceof ServerPlayer p) || !(ev.getLevel() instanceof ServerLevel) || e != null) return;
        if (!rock(ev.getState())) return;
        boolean armed = ARMED.contains(p.getUUID());
        if (!armed && RND.nextDouble() >= CHANCE) return;
        for (StalkerEntity q : StalkerEntity.ACTIVE) if (q.isAlive() && (q.isFieldChasing() || q.isScripted())) return;
        BlockPos b = ev.getPos().immutable();
        ((ServerLevel) ev.getLevel()).getServer().execute(() -> {
            if (start(p, b)) ARMED.remove(p.getUUID());
        });
    }

    @SubscribeEvent
    public void onJoin(EntityJoinLevelEvent ev) {
        if (ev.getLevel().isClientSide || !(ev.getEntity() instanceof StalkerEntity q) || !q.getDefId().equals("stalker_run_mina") || q.isScripted()) return;
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
        if (near != null) arm(near);
    }

    private static int arm(ServerPlayer p) {
        ARMED.add(p.getUUID());
        p.displayClientMessage(Component.literal(Lang.es() ? "§7Rompe una piedra de la pared..." : "§7Break a stone block from the wall..."), true);
        return 1;
    }

    @SubscribeEvent
    public void commands(RegisterCommandsEvent ev) {
        ev.getDispatcher().register(Commands.literal("twxrunmina").requires(c -> c.hasPermission(2)).executes(c -> arm(c.getSource().getPlayerOrException())));
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END || e == null) return;
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(pid);
        if (!e.isAlive() || p == null || p.level() != level || !p.isAlive() || Math.hypot(p.getX() - h.x, p.getZ() - h.z) > 48) {
            end();
            return;
        }
        t++;
        Vec3 pos = t < HANDS ? p0 : t < OUT ? lerp(p0, p1, (t - HANDS) / (double) (OUT - HANDS)) : lerp(p1, s, (t - OUT) / 18.0);
        e.moveTo(pos.x, pos.y, pos.z, yaw, 0);
        e.setYHeadRot(yaw);
        e.yBodyRot = yaw;
        for (int i = 0; i < CRACKS.length; i++) if (t == CRACKS[i]) snd("stalker.run_crack_" + (i + 1), h, 1.2f, 1f);
        if (t == HANDS) {
            e.setProp("fase", 1);
            level.playSound(null, h.x, h.y, h.z, SoundEvents.STONE_BREAK, SoundSource.BLOCKS, 1f, .5f);
        }
        if (t == OUT) {
            e.setProp("fase", small ? 3 : 2);
            level.playSound(null, h.x, h.y, h.z, SoundEvents.STONE_BREAK, SoundSource.BLOCKS, 1f, .7f);
        }
        if (t >= END) {
            Vec3 at = s;
            boolean sm = small;
            end();
            StalkerEntity r = SpawnHelper.spawnChaseAtE(p, "stalker_run", at, true);
            if (r != null && sm) r.setProp("chico", 1);
        }
    }
}
