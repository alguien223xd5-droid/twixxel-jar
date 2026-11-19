package com.twixxel.stalker.event;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.twixxel.stalker.entity.StalkerEntity;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * ALCANTARILLA: una trampilla con un hueco detras. Si estas cerca de una (14 bloques), cada 5-10 min algo sale de ahi.
 *  - Tu arriba (trampilla en el piso): 1er intento de abrirla no se abre ("Sientes que hay algo abajo..."), 2do la abre,
 *    saca las manos, golpea el piso, asoma la cabeza, sube y se convierte en el RUN (o en la cabra) que te persigue.
 *  - Tu abajo (trampilla en el techo): la abre, asoma media cabeza, la mano, y baja a perseguirte.
 *  - Si la ignoras 3 min (o te alejas 32 bloques) se va. Prueba: /twxalcantarilla [run|goat]
 */
public final class Alcantarilla {
    private static final int R = 14, A_LEN = 104, B_LEN = 80, WAIT_MAX = 3 * 60 * 20, FAR = 32;
    private static final double B_NEAR = 5;
    private static final String[] VARIANTS = {"stalker_alcantarilla", "stalker_alcantarilla_goat"};
    private static final Random RND = new Random();

    private static final class S {
        String variant;
        ServerLevel level;
        BlockPos t;
        boolean modeA;
        Vec3 pos;
        StalkerEntity e;
        UUID player;
        boolean anim;
        int ticks, pt, tries, lastPoke = -99, textUntil;
    }

    private static final class Spot {
        BlockPos t;
        boolean modeA;
        double d;
    }

    private static S cur;

    /** La trampilla que usa el evento de la alcantarilla en curso: nadie mas la toca. */
    public static boolean reserved(ServerLevel l, BlockPos p) {
        return cur != null && cur.level == l && cur.t != null && cur.t.equals(p);
    }
    private static final Map<String, Long> next = new HashMap<>();
    private static final Map<UUID, int[]> RUN_TEXT = new HashMap<>();   // jugador -> {ticks restantes}

    private static boolean es() {
        return Lang.es();
    }

    private static boolean isTrap(BlockState b) {
        return b.getBlock() instanceof TrapDoorBlock;
    }

    private static boolean free(ServerLevel l, BlockPos p) {
        BlockState b = l.getBlockState(p);
        return b.getCollisionShape(l, p).isEmpty() && b.getFluidState().isEmpty();
    }

    private static boolean solidB(ServerLevel l, BlockPos p) {
        return !free(l, p) && !isTrap(l.getBlockState(p));
    }

    private static void setOpen(ServerLevel l, BlockPos t, boolean on) {
        BlockState b = l.getBlockState(t);
        if (isTrap(b) && b.getValue(TrapDoorBlock.OPEN) != on) l.setBlock(t, b.setValue(TrapDoorBlock.OPEN, on), 3);
    }

    private static Spot find(ServerPlayer p, Vec3 center, int rMax) {
        ServerLevel l = p.serverLevel();
        Vec3 c = center == null ? p.position() : center;
        int cx = (int) Math.floor(c.x), cy = (int) Math.floor(c.y), cz = (int) Math.floor(c.z);
        Spot best = null;
        Direction[] dirs = {Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH};
        for (int dx = -rMax; dx <= rMax; dx++) {
            for (int dz = -rMax; dz <= rMax; dz++) {
                if (dx * dx + dz * dz > rMax * rMax) continue;
                for (int dy = -6; dy <= 6; dy++) {
                    BlockPos t = new BlockPos(cx + dx, cy + dy, cz + dz);
                    if (!isTrap(l.getBlockState(t)) || !free(l, t.below())) continue;      // hueco abajo
                    int side = 0, sideHole = 0;
                    for (Direction d : dirs) {
                        if (solidB(l, t.relative(d))) side++;
                        if (solidB(l, t.below().relative(d))) sideHole++;
                    }
                    if (side < 2 && sideHole < 2) continue;                                  // no es una trampilla suelta
                    boolean above = free(l, t.above());
                    boolean a = p.getY() >= t.getY() - 0.2 && above;
                    boolean b = !a && p.getY() + 1.8 <= t.getY() && above && Math.hypot(p.getX() - (t.getX() + .5), p.getZ() - (t.getZ() + .5)) <= R;
                    if (!a && !b) continue;
                    double d = Math.sqrt(Math.pow(t.getX() + .5 - c.x, 2) + Math.pow(t.getY() - c.y, 2) + Math.pow(t.getZ() + .5 - c.z, 2));
                    if (best == null || d < best.d) {
                        best = new Spot();
                        best.t = t;
                        best.modeA = a;
                        best.d = d;
                    }
                }
            }
        }
        return best;
    }

    private static boolean spawn(ServerPlayer p, Spot sp, String variant) {
        if (cur != null) return false;
        ServerLevel l = p.serverLevel();
        setOpen(l, sp.t, false);
        Vec3 pos = sp.modeA ? new Vec3(sp.t.getX() + .5, sp.t.getY() + 1 - 1.5, sp.t.getZ() + .5) : new Vec3(sp.t.getX() + .5, sp.t.getY() + 1, sp.t.getZ() + .5);
        StalkerEntity e = SpawnHelper.createScripted(l, variant, pos, 0f);
        if (e == null) return false;
        e.setProp("fase", 0);
        S s = new S();
        s.variant = variant;
        s.level = l;
        s.t = sp.t;
        s.modeA = sp.modeA;
        s.pos = pos;
        s.e = e;
        s.player = p.getUUID();
        cur = s;
        return true;
    }

    private static void end() {
        S s = cur;
        cur = null;
        if (s != null && s.e != null && s.e.isAlive()) s.e.discard();
    }

    private static void play(S s, String id, float pitch, float vol) {
        SoundEvent se = BedHide.se(id);
        if (se != null) s.level.playSound(null, s.t.getX() + .5, s.t.getY() + .5, s.t.getZ() + .5, se, SoundSource.HOSTILE, vol, pitch);
    }

    private static void go(S s) {
        s.anim = true;
        s.pt = 0;
        setOpen(s.level, s.t, true);
        s.level.playSound(null, s.t, SoundEvents.WOODEN_TRAPDOOR_OPEN, SoundSource.BLOCKS, .6f, 1f);
        s.e.setProp("fase", s.modeA ? 1 : 2);
    }

    private static void transform(S s, MinecraftServer srv) {
        ServerPlayer p = srv.getPlayerList().getPlayer(s.player);
        BlockPos t = s.t;
        ServerLevel l = s.level;
        Vec3 pos = null;
        if (s.modeA) {                                                   // al lado del hueco, del lado del jugador
            Direction best = null;
            double bd = 1e18;
            for (Direction d : new Direction[]{Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH}) {
                BlockPos q = t.relative(d);
                if (!solidB(l, q) || !free(l, q.above()) || !free(l, q.above(2))) continue;
                double dd = p == null ? 0 : Math.hypot(p.getX() - (q.getX() + .5), p.getZ() - (q.getZ() + .5));
                if (dd < bd) {
                    bd = dd;
                    best = d;
                }
            }
            pos = best != null ? new Vec3(t.getX() + .5 + best.getStepX(), t.getY() + 1, t.getZ() + .5 + best.getStepZ()) : new Vec3(t.getX() + .5, t.getY() + 1, t.getZ() + .5);
        } else {                                                         // baja por la alcantarilla al tunel
            int y = t.getY() - 1;
            for (int k = 0; k < 8; k++) {
                if (!free(l, new BlockPos(t.getX(), y - 1, t.getZ()))) break;
                y--;
            }
            pos = new Vec3(t.getX() + .5, y, t.getZ() + .5);
        }
        String variant = s.variant;
        end();
        if (p == null || !p.isAlive() || p.level() != l) return;
        for (StalkerEntity e : StalkerEntity.ACTIVE) if (e.isAlive() && e.isFieldChasing()) return;   // ya hay una persecucion
        if (variant.equals("stalker_alcantarilla")) {
            SpawnHelper.spawnChaseAtE(p, "stalker_run", pos, true);              // grita (dobla el cuello) y te persigue
        } else {
            SpawnHelper.spawnChaseAtE(p, "stalker_goat", pos, false);
            RUN_TEXT.put(p.getUUID(), new int[]{4});
        }
    }

    // ------------------------------------------------------------------ interaccion
    @SubscribeEvent
    public void onUse(PlayerInteractEvent.RightClickBlock ev) {
        S s = cur;
        if (s == null || !s.modeA || ev.getLevel().isClientSide || !ev.getPos().equals(s.t) || ev.getLevel() != s.level) return;
        ev.setCanceled(true);                                            // la abre el, no tu
        ev.setCancellationResult(InteractionResult.SUCCESS);
        if (s.anim || ev.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND || !(ev.getEntity() instanceof ServerPlayer p)) return;
        if (s.ticks - s.lastPoke < 8) return;
        s.lastPoke = s.ticks;
        s.tries++;
        s.player = p.getUUID();
        if (s.tries == 1) {
            play(s, "stalker.js_golpe", .5f, .6f);
            s.textUntil = s.ticks + 70;
            p.displayClientMessage(Component.literal(es() ? "§4§lSientes que hay algo abajo..." : "§4§lYou feel something down there..."), true);
        } else {
            p.displayClientMessage(Component.empty(), true);
            s.textUntil = 0;
            go(s);
        }
    }

    @SubscribeEvent
    public void onBreak(BlockEvent.BreakEvent ev) {
        S s = cur;
        if (s != null && ev.getLevel() == s.level && ev.getPos().equals(s.t)) ev.setCanceled(true);
    }

    /** Huevo en creativo: va a la alcantarilla mas cercana. Los que cargan del disco, se borran. */
    @SubscribeEvent
    public void onJoin(EntityJoinLevelEvent ev) {
        if (ev.getLevel().isClientSide || !(ev.getEntity() instanceof StalkerEntity e)) return;
        String id = e.getDefId();
        if (!id.equals(VARIANTS[0]) && !id.equals(VARIANTS[1])) return;
        if (e.isScripted()) return;                                      // el nuestro
        ev.setCanceled(true);
        if (ev.loadedFromDisk() || !(ev.getLevel() instanceof ServerLevel sl)) return;
        ServerPlayer p = null;
        double bd = 1e18;
        for (ServerPlayer q : sl.players()) {
            double d = q.distanceToSqr(e.position());
            if (d < bd) {
                bd = d;
                p = q;
            }
        }
        if (p == null) return;
        final ServerPlayer pp = p;
        final Vec3 at = e.position();
        sl.getServer().execute(() -> {
            if (cur != null) {
                pp.displayClientMessage(Component.literal(es() ? "§4Ya hay uno en una alcantarilla" : "§4There is already one in a sewer"), true);
                return;
            }
            Spot sp = find(pp, at, 16);
            if (sp == null) pp.displayClientMessage(Component.literal(es() ? "§4No hay ninguna alcantarilla cerca (trampilla en el piso o techo con un hueco)" : "§4No sewer nearby (a trapdoor in the floor or ceiling with a hole)"), true);
            else spawn(pp, sp, id);
        });
    }

    @SubscribeEvent
    public void commands(RegisterCommandsEvent ev) {
        ev.getDispatcher().register(Commands.literal("twxalcantarilla").requires(s -> s.hasPermission(2))
                .executes(c -> test(c.getSource().getPlayerOrException(), "run"))
                .then(Commands.argument("tipo", StringArgumentType.word()).executes(c -> test(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "tipo")))));
    }

    private static int test(ServerPlayer p, String kind) {
        if (cur != null) return 0;
        Spot sp = find(p, null, R);
        if (sp == null) {
            p.displayClientMessage(Component.literal(es() ? "§4No hay ninguna alcantarilla cerca" : "§4No sewer nearby"), true);
            return 0;
        }
        return spawn(p, sp, kind.equals("goat") ? VARIANTS[1] : VARIANTS[0]) ? 1 : 0;
    }

    // ------------------------------------------------------------------ bucle
    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END) return;
        MinecraftServer srv = ServerLifecycleHooks.getCurrentServer();
        if (srv == null) return;
        long now = srv.getTickCount();
        if (!RUN_TEXT.isEmpty() && now % 10 == 0) {                      // "CORRE" abajo, 2 s
            for (UUID id : new java.util.ArrayList<>(RUN_TEXT.keySet())) {
                ServerPlayer p = srv.getPlayerList().getPlayer(id);
                int[] n = RUN_TEXT.get(id);
                if (p == null || --n[0] < 0) {
                    RUN_TEXT.remove(id);
                    continue;
                }
                p.displayClientMessage(Component.literal(es() ? "§4§lCORRE" : "§4§lRUN"), true);
            }
        }
        if (cur == null) {
            if (now % 40 != 0) return;
            for (StalkerEntity e : StalkerEntity.ACTIVE) if (e.isAlive() && (e.isFieldChasing() || e.isScripted())) return;
            for (String v : VARIANTS) {
                long due = next.computeIfAbsent(v, k -> now + SpawnConfig.ticks(k, 5 * 1200, 10 * 1200, RND));
                if (now < due) continue;
                java.util.List<ServerPlayer> ps = new java.util.ArrayList<>(srv.getPlayerList().getPlayers());
                java.util.Collections.shuffle(ps);
                boolean ok = false;
                for (ServerPlayer p : ps) {
                    if (!p.isAlive() || p.isSpectator() || BedHide.isHidden(p.getUUID())) continue;
                    Spot sp = find(p, null, R);
                    if (sp != null && spawn(p, sp, v)) {
                        ok = true;
                        break;
                    }
                }
                next.put(v, now + (ok ? SpawnConfig.ticks(v, 5 * 1200, 10 * 1200, RND) : 600));
                return;
            }
            return;
        }
        S s = cur;
        s.ticks++;
        if (s.e == null || !s.e.isAlive() || !isTrap(s.level.getBlockState(s.t))) {
            end();
            return;
        }
        if (!s.anim) {
            if (s.textUntil > s.ticks && s.ticks % 10 == 0) {
                ServerPlayer p = srv.getPlayerList().getPlayer(s.player);
                if (p != null) p.displayClientMessage(Component.literal(es() ? "§4§lSientes que hay algo abajo..." : "§4§lYou feel something down there..."), true);
            }
            if (s.ticks % 20 == 0) setOpen(s.level, s.t, false);         // que no la abran por otro lado
            double nd = 1e18;
            for (ServerPlayer p : s.level.players()) {
                if (!p.isAlive() || p.isSpectator()) continue;
                double d = Math.hypot(p.getX() - (s.t.getX() + .5), p.getZ() - (s.t.getZ() + .5));
                nd = Math.min(nd, d);
                if (!s.modeA && d <= B_NEAR && p.getY() + 1.8 <= s.t.getY() && p.getY() > s.t.getY() - 12) {
                    s.player = p.getUUID();
                    go(s);
                    return;
                }
            }
            if (s.ticks >= WAIT_MAX || nd > FAR) end();
            return;
        }
        s.pt++;
        boolean goat = s.variant.equals(VARIANTS[1]);
        float v = goat ? 1.3f : 1f;
        if (s.modeA) {
            if (s.pt == 18 || s.pt == 26) play(s, "stalker.js_golpe", .9f, .7f);
            if (goat && s.pt == 20) play(s, "mob.goat.ambient.screamer", 1.4f, .55f);
            if (s.pt == 52) play(s, "stalker.run_crack_1", 1f, v);
            if (s.pt == 62) play(s, "stalker.run_crack_2", 1f, v);
            if (s.pt == 84) play(s, "stalker.js_golpe", .7f, .8f);
            if (goat && s.pt == 92) play(s, "stalker.run_crack_3", 1f, v);
            if (s.pt >= A_LEN) transform(s, srv);
        } else {
            if (s.pt == 30) play(s, "stalker.run_crack_1", 1f, v);
            if (s.pt == 40) play(s, "stalker.run_crack_2", 1f, v);
            if (s.pt == 58) play(s, "stalker.js_golpe", .8f, .8f);
            if (s.pt == 68) play(s, "stalker.run_crack_3", 1f, v);
            if (s.pt >= B_LEN) transform(s, srv);
        }
    }
}
