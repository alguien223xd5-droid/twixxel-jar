package com.twixxel.stalker.event;

import com.twixxel.stalker.entity.StalkerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.player.PlayerSleepInBedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * EL SUEÑO. De noche, al acostarte, te despiertas en una dimension vacia (twixxel:sueno):
 *  - Sueño 1: pasto, arboles ordenados cada 20 bloques, casa de roble con un TELEVISOR y un CASSETTE. Coges el cassette,
 *    lo metes en la tele (clic derecho), suena "Missalan" y despiertas en tu cama.
 *  - Sueño 2: bosque de arboles gigantes muy juntos, casa de abeto, VHS y tele con video (124 cuadros).
 * - Sueño 3: pasillo de 120 bloques con mar afuera, VHS a la mitad y la tele al final (video de 263 cuadros): al terminar sale
 *   THE LOCUST de la tele y te persigue; si te alcanza, susto y despiertas.
 * Un sueño por etapa (1 -> 2 -> 3). Se prueba con /twixxel event sueno_1 | sueno_2 | sueno_3 | sueno_4.
 * - Sueño 4 (el ultimo): pueblo de 841 casas viejas que se repite al alejarte 100 bloques del centro; 4 minutos y despiertas. Hay un
 *   stalker RUN (skin del sueño) por cada jugador que sueña; si te atrapa, susto y despiertas.
 */
public final class SuenoEvent {
    public static final ResourceKey<Level> DIM = ResourceKey.create(Registries.DIMENSION, new ResourceLocation("twixxel", "sueno"));
    private static final String K_ETAPA = "twx_sueno_etapa", K_NIGHT = "twx_sueno_night", K_BACK = "twx_sueno_back", TAG = "twx_sueno_ent";
    private static final int Y = 64, MAX_ETAPA = 4, SUE2_FRAMES = 124, BUDGET = 9000, N4 = 29, P4 = 10, MIN4 = 4 * 60 * 20, WRAP4 = 100;

    private static final class Sess {
        UUID pid;
        int n, t;
        String phase = "load";
        boolean hasTape, padded;
        ServerLevel backLevel;
        Vec3 back;
        float backYaw, backPitch;
    }

    private static final class Op {
        final int x0, y0, z0, x1, y1, z1;
        final BlockState st;

        Op(int x0, int y0, int z0, int x1, int y1, int z1, BlockState st) {
            this.x0 = x0;
            this.y0 = y0;
            this.z0 = z0;
            this.x1 = x1;
            this.y1 = y1;
            this.z1 = z1;
            this.st = st;
        }
    }

    private static final Map<UUID, Sess> SES = new HashMap<>();
    private static final Map<UUID, Long> SLEEP_AT = new HashMap<>();
    private static final Map<Integer, ArrayDeque<Op>> BUILD = new HashMap<>();
    private static final Map<Integer, Boolean> BUILT = new HashMap<>();
    private static final Map<Integer, StalkerEntity> TV = new HashMap<>();
    private static final Map<Integer, Integer> PLAYING = new HashMap<>();     // n -> tick de inicio de la cinta (o -1)

    // ---------------------------------------------------------------- geometria
    static final int LEN = 120, SEA = 40;       // sueño 3: el pasillo mide 120 bloques

    static int cx(int n) {
        return n == 4 ? 9000 : n == 3 ? 6000 : n == 2 ? 3000 : 0;
    }

    static int half(int n) {
        return n == 3 ? 65 : n == 4 ? 150 : n == 2 ? 150 : 180;
    }

    static Vec3 spawn(int n) {
        if (n == 3) return new Vec3(cx(3) - LEN / 2.0 + 2.5, Y, 2.5);
        if (n == 4) return new Vec3(cx(4) + 13.5, Y, 8.5);
        return new Vec3(cx(n) - 48 + .5, Y, .5);
    }

    static Vec3 tvPos(int n) {
        if (n == 3) return new Vec3(cx(3) + LEN / 2.0 - 2, Y, 2.5);
        return new Vec3(hx(n) + 7.0, Y, hz() + 4.5);
    }

    static Vec3 tapePos(int n, int i) {
        if (n == 3) return new Vec3(cx(3) + 0.5 - (i / 4) * 0.9, Y, 1.0 + (i % 4) * 0.9);
        return new Vec3(hx(n) + 6.6 - (i % 4) * 0.9, Y, hz() + 2.6 - (i / 4) * 0.9);
    }

    static net.minecraft.world.phys.AABB area(int n) {
        if (n == 4) return new net.minecraft.world.phys.AABB(cx(4) - 160, Y - 2, -160, cx(4) + 160, Y + 20, 160);
        if (n == 3) return new net.minecraft.world.phys.AABB(cx(3) - LEN / 2.0 - 3, Y - 2, -3, cx(3) + LEN / 2.0 + 3, Y + 8, 9);
        return new net.minecraft.world.phys.AABB(hx(n) - 2, Y - 2, hz() - 2, hx(n) + 14, Y + 6, hz() + 12);
    }

    static int hx(int n) {
        return cx(n) + 40;
    }

    static int hz() {
        return -4;
    }

    private static String audio(int n) {
        return n == 3 ? "stalker.tele3_video" : n == 2 ? "stalker.vhs_video" : "stalker.missalan";
    }

    private static int audioTicks(int n) {
        return n == 3 ? 527 : n == 2 ? 250 : 430;
    }

    private static boolean es() {
        return Lang.es();
    }

    private static void bar(ServerPlayer p, String s) {
        p.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(s)));
    }

    // ---------------------------------------------------------------- etapa
    private static CompoundTag data(Player p) {
        CompoundTag t = p.getPersistentData();
        if (!t.contains(Player.PERSISTED_NBT_TAG)) t.put(Player.PERSISTED_NBT_TAG, new CompoundTag());
        return t.getCompound(Player.PERSISTED_NBT_TAG);
    }

    public static int etapa(Player p) {
        return data(p).getInt(K_ETAPA);
    }

    private static void setEtapa(Player p, int v) {
        data(p).putInt(K_ETAPA, Math.max(etapa(p), v));
    }

    // ---------------------------------------------------------------- construccion
    private static BlockState leaves(Block b) {
        return b.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
    }

    private static void fill(List<Op> L, int x0, int y0, int z0, int x1, int y1, int z1, BlockState st) {
        int ax = Math.min(x0, x1), bx = Math.max(x0, x1), az = Math.min(z0, z1), bz = Math.max(z0, z1);
        for (int x = ax; x <= bx; x = (x | 15) + 1) {
            int x2 = Math.min(bx, x | 15);
            for (int z = az; z <= bz; z = (z | 15) + 1) {
                int z2 = Math.min(bz, z | 15);
                L.add(new Op(x, Math.min(y0, y1), z, x2, Math.max(y0, y1), z2, st));
            }
        }
    }

    private static List<Op> corridorOps() {
        List<Op> L = new ArrayList<>();
        int X = cx(3), Z = 0, x0 = X - LEN / 2, x1 = X + LEN / 2 - 1;
        BlockState air = Blocks.AIR.defaultBlockState(), stone = Blocks.STONE.defaultBlockState();
        net.minecraft.core.Direction[] dirs = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.EAST};
        int[] grow = {3, 2, 3, 1, 3};
        fill(L, x0 - 1, Y - 2, Z - 1, x1 + 1, Y - 2, Z + 5, Blocks.DIRT.defaultBlockState());
        fill(L, x0 - 1, Y - 1, Z - 1, x1 + 1, Y - 1, Z + 5, Blocks.GRASS_BLOCK.defaultBlockState());
        fill(L, x0, Y, Z, x1, Y + 4, Z + 4, air);
        for (int i = 0; i < 5; i++) {
            BlockState pet = Blocks.PINK_PETALS.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.PinkPetalsBlock.AMOUNT, Math.min(4, grow[i]))
                    .setValue(net.minecraft.world.level.block.PinkPetalsBlock.FACING, dirs[i]);
            fill(L, x0, Y, Z + i, x1, Y, Z + i, pet);
        }
        fill(L, x0, Y, Z - 1, x1, Y + 4, Z - 1, stone);                         // pared izquierda
        fill(L, x0, Y + 5, Z - 1, x1, Y + 5, Z + 5, stone);                    // techo
        fill(L, x0, Y, Z + 5, x1, Y + 4, Z + 5, Blocks.GLASS_PANE.defaultBlockState());   // ventanales
        for (int px = x0; px <= x1; px += 4) fill(L, px, Y, Z + 5, px, Y + 4, Z + 5, Blocks.SMOOTH_STONE.defaultBlockState());
        fill(L, x0 - 1, Y - 15, Z + 6, x1 + 1, Y - 15, Z + 6 + SEA, Blocks.BARRIER.defaultBlockState());   // el mar de afuera
        fill(L, x0 - 1, Y - 14, Z + 6, x1 + 1, Y - 14, Z + 6 + SEA, Blocks.WATER.defaultBlockState());
        fill(L, x0 - 1, Y - 14, Z + 5, x1 + 1, Y - 14, Z + 5, Blocks.BARRIER.defaultBlockState());
        fill(L, x0 - 1, Y - 14, Z + 6 + SEA + 1, x1 + 1, Y - 14, Z + 6 + SEA + 1, Blocks.BARRIER.defaultBlockState());
        fill(L, x0 - 1, Y - 14, Z + 5, x0 - 1, Y - 14, Z + 7 + SEA, Blocks.BARRIER.defaultBlockState());
        fill(L, x1 + 1, Y - 14, Z + 5, x1 + 1, Y - 14, Z + 7 + SEA, Blocks.BARRIER.defaultBlockState());
        fill(L, x0 - 1, Y - 1, Z - 1, x0 - 1, Y + 5, Z + 5, stone);            // tapas de los extremos
        fill(L, x1 + 1, Y - 1, Z - 1, x1 + 1, Y + 5, Z + 5, stone);            // (ultima: marca "terminado")
        return L;
    }

    private static List<Op> villageOps() {
        List<Op> L = new ArrayList<>();
        int X = cx(4), Z = 0, H = half(4);
        fill(L, X - H, Y - 2, Z - H, X + H, Y - 2, Z + H, Blocks.DIRT.defaultBlockState());
        fill(L, X - H, Y - 1, Z - H, X + H, Y - 1, Z + H, Blocks.GRASS_BLOCK.defaultBlockState());
        BlockState PL = Blocks.OAK_PLANKS.defaultBlockState(), CB = Blocks.COBBLESTONE.defaultBlockState(), LG = Blocks.OAK_LOG.defaultBlockState(), GL = Blocks.GLASS_PANE.defaultBlockState();
        BlockState d = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.SOUTH);
        int ox = X - 145, oz = Z - 145;
        for (int i = 0; i < N4; i++) for (int j = 0; j < N4; j++) {
            int x0 = ox + i * P4, z0 = oz + j * P4, x6 = x0 + 6, z6 = z0 + 6;
            L.add(new Op(x0, Y - 1, z0, x6, Y - 1, z6, CB));                       // piso
            L.add(new Op(x0, Y, z0, x6, Y + 3, z0, PL));                           // paredes de tablones
            L.add(new Op(x0, Y, z6, x6, Y + 3, z6, PL));
            L.add(new Op(x0, Y, z0, x0, Y + 3, z6, PL));
            L.add(new Op(x6, Y, z0, x6, Y + 3, z6, PL));
            L.add(new Op(x0, Y, z0, x6, Y, z0, CB));                               // base de piedra
            L.add(new Op(x0, Y, z6, x6, Y, z6, CB));
            L.add(new Op(x0, Y, z0, x0, Y, z6, CB));
            L.add(new Op(x6, Y, z0, x6, Y, z6, CB));
            for (int[] c : new int[][]{{x0, z0}, {x6, z0}, {x0, z6}, {x6, z6}}) L.add(new Op(c[0], Y, c[1], c[0], Y + 3, c[1], LG));
            L.add(new Op(x0 + 2, Y + 2, z0, x0 + 4, Y + 2, z0, GL));              // ventanas
            L.add(new Op(x0, Y + 2, z0 + 2, x0, Y + 2, z0 + 4, GL));
            L.add(new Op(x6, Y + 2, z0 + 2, x6, Y + 2, z0 + 4, GL));
            L.add(new Op(x0 + 1, Y + 2, z6, x0 + 1, Y + 2, z6, GL));
            L.add(new Op(x0 + 5, Y + 2, z6, x0 + 5, Y + 2, z6, GL));
            L.add(new Op(x0 + 3, Y, z6, x0 + 3, Y, z6, d.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)));      // puerta (lado sur)
            L.add(new Op(x0 + 3, Y + 1, z6, x0 + 3, Y + 1, z6, d.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER)));
            L.add(new Op(x0, Y + 4, z0, x6, Y + 4, z6, PL));                       // techo escalonado
            L.add(new Op(x0 + 1, Y + 5, z0 + 1, x6 - 1, Y + 5, z6 - 1, PL));
            L.add(new Op(x0 + 2, Y + 6, z0 + 2, x6 - 2, Y + 6, z6 - 2, CB));
            L.add(new Op(x0 + 3, Y + 1, z0 + 1, x0 + 3, Y + 1, z0 + 1, Blocks.TORCH.defaultBlockState()));    // antorcha adentro
        }
        return L;
    }

    private static List<Op> buildOps(int n) {
        if (n == 4) return villageOps();
        if (n == 3) return corridorOps();
        List<Op> L = new ArrayList<>();
        int X = cx(n), Z = 0, H = half(n);
        // piso (tierra + pasto) y aire encima
        fill(L, X - H, Y - 3, Z - H, X + H, Y - 2, Z + H, Blocks.DIRT.defaultBlockState());
        fill(L, X - H, Y - 1, Z - H, X + H, Y - 1, Z + H, Blocks.GRASS_BLOCK.defaultBlockState());
        BlockState bar = Blocks.BARRIER.defaultBlockState();
        fill(L, X - H - 1, Y - 3, Z - H - 1, X + H + 1, Y + 14, Z - H - 1, bar);
        fill(L, X - H - 1, Y - 3, Z + H + 1, X + H + 1, Y + 14, Z + H + 1, bar);
        fill(L, X - H - 1, Y - 3, Z - H - 1, X - H - 1, Y + 14, Z + H + 1, bar);
        fill(L, X + H + 1, Y - 3, Z - H - 1, X + H + 1, Y + 14, Z + H + 1, bar);
        int hx = hx(n), hz = hz();
        Vec3 sp = spawn(n);
        if (n == 2) {                                   // bosque de arboles gigantes (roble oscuro 2x2), cada 10 bloques
            for (int ax = X - 145; ax <= X + 145; ax += 10) {
                for (int az = Z - 145; az <= Z + 145; az += 10) {
                    if (ax > hx - 7 && ax < hx + 16 && az > hz - 7 && az < hz + 16) continue;
                    if (Math.abs(ax - sp.x) < 5 && Math.abs(az - sp.z) < 5) continue;
                    BlockState lv = leaves(Blocks.DARK_OAK_LEAVES);
                    L.add(new Op(ax - 3, Y + 6, az - 3, ax + 4, Y + 7, az + 4, lv));
                    L.add(new Op(ax - 2, Y + 8, az - 2, ax + 3, Y + 8, az + 3, lv));
                    L.add(new Op(ax - 1, Y + 9, az - 1, ax + 2, Y + 9, az + 2, lv));
                    L.add(new Op(ax, Y, az, ax + 1, Y + 7, az + 1, Blocks.DARK_OAK_LOG.defaultBlockState()));
                }
            }
        } else {                                        // arboles ordenados y separados (cada 20 bloques)
            for (int ax = X - 170; ax <= X + 170; ax += 20) {
                for (int az = Z - 170; az <= Z + 170; az += 20) {
                    if (ax > hx - 5 && ax < hx + 14 && az > hz - 5 && az < hz + 14) continue;
                    BlockState lv = leaves(Blocks.OAK_LEAVES);
                    L.add(new Op(ax - 2, Y + 3, az - 2, ax + 2, Y + 4, az + 2, lv));
                    L.add(new Op(ax - 1, Y + 5, az - 1, ax + 1, Y + 6, az + 1, lv));
                    L.add(new Op(ax, Y, az, ax, Y + 4, az, Blocks.OAK_LOG.defaultBlockState()));
                }
            }
        }
        // la casa de madera 9x9 con ventanas 2x2 y la puerta mirando al oeste (de donde apareces)
        int x0 = hx, z0 = hz, x1 = hx + 8, z1 = hz + 8;
        BlockState PL = (n == 2 ? Blocks.SPRUCE_PLANKS : Blocks.OAK_PLANKS).defaultBlockState();
        BlockState LG = (n == 2 ? Blocks.SPRUCE_LOG : Blocks.OAK_LOG).defaultBlockState();
        BlockState GL = Blocks.GLASS_PANE.defaultBlockState();
        L.add(new Op(x0, Y - 1, z0, x1, Y - 1, z1, PL));
        L.add(new Op(x0, Y, z0, x1, Y + 3, z1, PL));
        L.add(new Op(x0 + 1, Y, z0 + 1, x1 - 1, Y + 3, z1 - 1, Blocks.AIR.defaultBlockState()));
        for (int[] c : new int[][]{{x0, z0}, {x0, z1}, {x1, z0}, {x1, z1}}) L.add(new Op(c[0], Y, c[1], c[0], Y + 3, c[1], LG));
        L.add(new Op(x0, Y + 4, z0, x1, Y + 4, z1, PL));
        for (int wz : new int[]{z0 + 1, z0 + 6}) {
            L.add(new Op(x0, Y + 1, wz, x0, Y + 2, wz + 1, GL));
            L.add(new Op(x1, Y + 1, wz, x1, Y + 2, wz + 1, GL));
        }
        for (int wx : new int[]{x0 + 1, x0 + 6}) {
            L.add(new Op(wx, Y + 1, z0, wx + 1, Y + 2, z0, GL));
            L.add(new Op(wx, Y + 1, z1, wx + 1, Y + 2, z1, GL));
        }
        L.add(new Op(x0, Y, z0 + 4, x0, Y + 1, z0 + 4, Blocks.AIR.defaultBlockState()));
        Block door = n == 2 ? Blocks.SPRUCE_DOOR : Blocks.OAK_DOOR;
        BlockState d = door.defaultBlockState().setValue(DoorBlock.FACING, Direction.WEST);
        L.add(new Op(x0, Y, z0 + 4, x0, Y, z0 + 4, d.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)));
        L.add(new Op(x0, Y + 1, z0 + 4, x0, Y + 1, z0 + 4, d.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER)));   // ultimo: marca "terminado"
        return L;
    }

    private static boolean built(ServerLevel l, int n) {
        if (BUILT.getOrDefault(n, false)) return true;
        BlockPos dp = n == 4 ? new BlockPos(cx(4) - 145 + (N4 - 1) * P4 + 3, Y + 1, -145 + (N4 - 1) * P4 + 1)
                : n == 3 ? new BlockPos(cx(3) + LEN / 2, Y, 0) : new BlockPos(hx(n), Y + 1, hz() + 4);
        if (l.hasChunkAt(dp) && (n == 4 ? l.getBlockState(dp).is(Blocks.TORCH) : n == 3 ? l.getBlockState(dp).is(Blocks.STONE) : l.getBlockState(dp).getBlock() instanceof DoorBlock)) {
            BUILT.put(n, true);
            return true;
        }
        return false;
    }

    private static void runBuild(ServerLevel l, int n) {
        ArrayDeque<Op> q = BUILD.get(n);
        if (q == null) return;
        int budget = BUDGET;
        while (budget > 0 && !q.isEmpty()) {
            Op o = q.poll();
            l.getChunk(o.x0 >> 4, o.z0 >> 4);
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            for (int x = o.x0; x <= o.x1; x++) for (int z = o.z0; z <= o.z1; z++) for (int y = o.y0; y <= o.y1; y++) {
                l.setBlock(m.set(x, y, z), o.st, 2 | 16);
            }
            budget -= (o.x1 - o.x0 + 1) * (o.z1 - o.z0 + 1) * (o.y1 - o.y0 + 1) + 8;
        }
        if (q.isEmpty()) {
            BUILD.remove(n);
            BUILT.put(n, true);
        }
    }

    // ---------------------------------------------------------------- entidades del sueño
    private static StalkerEntity tv(ServerLevel l, int n) {
        StalkerEntity t = TV.get(n);
        if (t != null && t.isAlive()) return t;
        String id = n == 3 ? "tele3" : n == 2 ? "tele2" : "tele";
        for (StalkerEntity e : l.getEntitiesOfClass(StalkerEntity.class, area(n))) {
            if (e.getDefId().equals(id)) {
                TV.put(n, e);
                return e;
            }
        }
        t = SpawnHelper.createScripted(l, id, tvPos(n), 90f);
        if (t != null) {
            t.addTag(TAG);
            TV.put(n, t);
        }
        return t;
    }

    private static List<StalkerEntity> tapes(ServerLevel l, int n) {
        String id = n >= 2 ? "vhs" : "cassette";
        List<StalkerEntity> L = new ArrayList<>();
        for (StalkerEntity e : l.getEntitiesOfClass(StalkerEntity.class, area(n))) {
            if (e.getDefId().equals(id)) L.add(e);
        }
        return L;
    }

    private static int dreamers(int n, boolean withoutTape) {
        int k = 0;
        for (Sess s : SES.values()) if (s.n == n && s.phase.equals("dream") && (!withoutTape || !s.hasTape)) k++;
        return k;
    }

    private static void ensureEnts(ServerLevel l, int n) {
        if (n == 4) return;                                  // el pueblo no tiene tele ni cassette
        if (n == 3 && W3 != 0) return;                       // ya se metio el VHS
        tv(l, n);
        List<StalkerEntity> have = tapes(l, n);
        int need = dreamers(n, true);
        String id = n >= 2 ? "vhs" : "cassette";
        for (int i = have.size(); i < need; i++) {
            StalkerEntity c = SpawnHelper.createScripted(l, id, tapePos(n, i), 0f);
            if (c != null) c.addTag(TAG);
        }
    }

    private static final List<StalkerEntity> RUNS4 = new ArrayList<>();
    private static long next4 = 0;

    private static void cleanup(ServerLevel l, int n) {
        if (n == 4) {
            for (StalkerEntity r : RUNS4) if (r.isAlive()) r.discard();
            RUNS4.clear();
            return;
        }
        for (StalkerEntity e : tapes(l, n)) e.discard();
        StalkerEntity t = TV.remove(n);
        if (t != null && t.isAlive()) t.discard();
        for (StalkerEntity e : l.getEntitiesOfClass(StalkerEntity.class, area(n))) {
            if (e.getDefId().startsWith("tele") || e.getDefId().equals("the_locust")) e.discard();
        }
        if (n == 3) {
            locust = null;
            W3 = 0;
            W3t = 0;
        }
        PLAYING.remove(n);
    }

    // ---------------------------------------------------------------- entrar / salir
    public static boolean startFor(ServerPlayer p, int force) {
        if (SES.containsKey(p.getUUID())) return false;
        int et = etapa(p);
        int n = force > 0 ? force : et + 1;
        if (n < 1 || n > MAX_ETAPA) return false;
        if (BedHide.isHidden(p.getUUID())) return false;
        MinecraftServer server = p.getServer();
        ServerLevel dl = server == null ? null : server.getLevel(DIM);
        if (dl == null) {
            p.sendSystemMessage(Component.literal(Lang.es() ? "§cLa dimension del sueño no cargo (twixxel:sueno)." : "§cThe dream dimension did not load (twixxel:sueno)."));
            return false;
        }
        Sess s = new Sess();
        s.pid = p.getUUID();
        s.n = n;
        s.backLevel = p.serverLevel();
        s.back = p.position();
        s.backYaw = p.getYRot();
        s.backPitch = p.getXRot();
        SES.put(s.pid, s);
        if (force <= 0) setEtapa(p, n);                                      // con solo ir, ese sueño ya no vuelve
        CompoundTag b = new CompoundTag();
        b.putDouble("x", s.back.x);
        b.putDouble("y", s.back.y);
        b.putDouble("z", s.back.z);
        b.putFloat("yaw", s.backYaw);
        b.putFloat("pitch", s.backPitch);
        b.putString("dim", s.backLevel.dimension().location().toString());
        data(p).put(K_BACK, b);
        p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 60, 0, false, false));
        if (!built(dl, n) && !BUILD.containsKey(n)) BUILD.put(n, new ArrayDeque<>(buildOps(n)));
        return true;
    }

    private static void end(UUID pid, boolean back) {
        Sess s = SES.remove(pid);
        if (s == null) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(pid);
        if (p != null) {
            p.removeEffect(MobEffects.NIGHT_VISION);
            p.removeEffect(MobEffects.DAMAGE_RESISTANCE);
            p.removeEffect(MobEffects.SLOW_FALLING);
            p.connection.send(new ClientboundStopSoundPacket(new ResourceLocation("twixxel", audio(s.n)), null));
            if (back) {
                ServerLevel bl = s.backLevel != null ? s.backLevel : server.overworld();
                p.teleportTo(bl, s.back.x, s.back.y, s.back.z, s.backYaw, s.backPitch);
            }
            data(p).remove(K_BACK);
        }
        if (server != null) {
            ServerLevel dl = server.getLevel(DIM);
            boolean any = false;
            for (Sess o : SES.values()) if (o.n == s.n) any = true;
            if (dl != null && !any) cleanup(dl, s.n);
        }
    }

    /** Si el jugador se desconecto o murio dentro del sueño: vuelve a donde estaba. */
    private static void rescue(ServerPlayer p) {
        if (p.level().dimension() != DIM || SES.containsKey(p.getUUID())) return;
        CompoundTag b = data(p).getCompound(K_BACK);
        MinecraftServer server = p.getServer();
        if (server == null) return;
        ServerLevel bl = server.getLevel(ResourceKey.create(Registries.DIMENSION, new ResourceLocation(b.contains("dim") ? b.getString("dim") : "minecraft:overworld")));
        if (bl == null) bl = server.overworld();
        if (b.contains("x")) p.teleportTo(bl, b.getDouble("x"), b.getDouble("y"), b.getDouble("z"), b.getFloat("yaw"), b.getFloat("pitch"));
        else {
            BlockPos sp = bl.getSharedSpawnPos();
            p.teleportTo(bl, sp.getX() + .5, sp.getY(), sp.getZ() + .5, 0, 0);
        }
        data(p).remove(K_BACK);
        p.removeEffect(MobEffects.NIGHT_VISION);
    }

    // ---------------------------------------------------------------- interaccion
    private static StalkerEntity asProp(net.minecraft.world.entity.Entity e) {
        return e instanceof StalkerEntity s && e.getTags().contains(TAG) ? s : null;
    }

    private static void touch(ServerPlayer p, StalkerEntity e) {
        Sess s = SES.get(p.getUUID());
        if (s == null || !s.phase.equals("dream")) return;
        String id = e.getDefId();
        if (id.equals("cassette") || id.equals("vhs")) {
            if (s.hasTape) {
                bar(p, es() ? "§7Ya tienes uno" : "§7You already have one");
                return;
            }
            e.discard();
            s.hasTape = true;
            p.playNotifySound(net.minecraft.sounds.SoundEvents.ITEM_PICKUP, SoundSource.MASTER, .8f, .8f);
            bar(p, s.n == 2 ? (es() ? "§7Recogiste el VHS" : "§7You picked up the VHS") : (es() ? "§7Recogiste el cassette" : "§7You picked up the cassette"));
        } else if (id.startsWith("tele")) {
            if (s.n == 3) {
                insert3(p, s, e);
                return;
            }
            if (PLAYING.containsKey(s.n)) {
                bar(p, es() ? "§7Ya se esta reproduciendo..." : "§7It's already playing...");
                return;
            }
            if (!s.hasTape) {
                bar(p, s.n == 2 ? (es() ? "§7Le falta un VHS..." : "§7It needs a VHS...") : (es() ? "§7Le falta un cassette..." : "§7It needs a cassette..."));
                return;
            }
            s.hasTape = false;
            if (s.n == 2) e.setProp("frame", 1);
            else e.setProp("prendida", 1);
            PLAYING.put(s.n, 0);
            p.playNotifySound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.MASTER, 1f, .6f);
            var se = BedHide.se(audio(s.n));
            if (se != null) p.playNotifySound(se, SoundSource.MASTER, 1f, 1f);
            s.phase = "tape";
            s.t = 0;
        }
    }

    // ---------------------------------------------------------------- sueño 4: el pueblo + el stalker del sueño
    /** Hay un stalker por cada jugador que esta soñando el sueño 4; si atrapan a alguien, ese despierta y se repone otro. */
    private static void manage4(MinecraftServer server, ServerLevel dl) {
        List<ServerPlayer> dr = new ArrayList<>();
        for (Sess s : SES.values()) {
            if (s.n != 4 || !s.phase.equals("dream") || s.t < 60) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(s.pid);
            if (p != null) dr.add(p);
        }
        RUNS4.removeIf(r -> !r.isAlive());
        if (dr.isEmpty()) return;
        if (RUNS4.size() >= dr.size()) return;
        long now = server.getTickCount();
        if (now < next4) return;
        next4 = now + 100;
        ServerPlayer p = dr.get(dl.random.nextInt(dr.size()));
        Vec3 look = p.getLookAngle();
        double len = Math.max(1e-3, Math.hypot(look.x, look.z));
        double ox = cx(4) - 145 + 8, oz = -145 + 8;
        for (int dist : new int[]{20, 15, 25, 30, 35}) {
            double tx = p.getX() + look.x / len * dist, tz = p.getZ() + look.z / len * dist;
            double sx = ox + Math.round((tx - ox) / P4) * P4 + .5, sz = oz + Math.round((tz - oz) / P4) * P4 + .5;
            if (Math.hypot(sx - p.getX(), sz - p.getZ()) < 10) continue;
            boolean close = false;
            for (StalkerEntity r : RUNS4) if (Math.hypot(r.getX() - sx, r.getZ() - sz) < 3) close = true;
            if (close) continue;
            var ro = com.twixxel.stalker.TwixxelMod.TYPES.get("stalker_run");
            if (ro == null) return;
            StalkerEntity r = ro.get().create(dl);
            if (r == null) return;
            r.setFieldProfile(com.twixxel.stalker.entity.FieldProfile.DREAM_RUN);
            r.moveTo(sx, Y, sz, (float) (Math.atan2(-(p.getX() - sx), p.getZ() - sz) * 180 / Math.PI), 0f);
            r.setProp("skin", 1);
            r.addTag(TAG);
            dl.addFreshEntity(r);
            RUNS4.add(r);
            return;
        }
    }

    public static boolean isDreaming(Player p) {
        return p != null && SES.containsKey(p.getUUID());
    }

    public static boolean busy() {
        return !SES.isEmpty();
    }

    /** Llamado por el jumpscare: si estabas soñando no mueres, despiertas. */
    public static boolean wakeInstead(ServerPlayer p) {
        Sess s = SES.get(p.getUUID());
        if (s == null) return false;
        s.phase = "wake";
        s.t = 0;
        return true;
    }

    // ---------------------------------------------------------------- sueño 3: THE LOCUST
    private static int W3 = 0, W3t = 0;                 // 0 libre, 1 video, 2 sale de la tele, 3 persigue, 4 susto
    private static StalkerEntity locust;
    private static UUID victim;
    private static final int TELO_EMERGE = 120, VIDEO3 = 527, FRAMES3 = 263;
    private static final double TELO_SPEED = 0.3, TELO_CATCH = 1.8;

    private static List<ServerPlayer> dreamers3(MinecraftServer server) {
        List<ServerPlayer> L = new ArrayList<>();
        for (Sess s : SES.values()) {
            if (s.n != 3 || !s.phase.equals("dream")) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(s.pid);
            if (p != null) L.add(p);
        }
        return L;
    }

    private static void insert3(ServerPlayer p, Sess s, StalkerEntity tv) {
        if (W3 != 0) {
            bar(p, es() ? "\u00a77Ya se esta reproduciendo..." : "\u00a77It's already playing...");
            return;
        }
        if (!s.hasTape) {
            bar(p, es() ? "\u00a77Le falta un VHS..." : "\u00a77It needs a VHS...");
            return;
        }
        s.hasTape = false;
        W3 = 1;
        W3t = 0;
        tv.setProp("frame", 1);
        for (ServerPlayer q : dreamers3(p.getServer())) {
            q.playNotifySound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.MASTER, 1f, .6f);
            var se = BedHide.se(audio(3));
            if (se != null) q.playNotifySound(se, SoundSource.MASTER, 1f, 1f);
            setEtapa(q, 3);
        }
    }

    private static void snd(ServerLevel l, String id, Vec3 at, float vol, float pitch) {
        var se = BedHide.se(id);
        if (se != null) l.playSound(null, at.x, at.y, at.z, se, SoundSource.HOSTILE, vol, pitch);
    }

    private static void tick3(MinecraftServer server, ServerLevel dl) {
        if (W3 == 0) return;
        W3t++;
        Vec3 tv = tvPos(3);
        List<ServerPlayer> PL = dreamers3(server);
        if (W3 == 1) {                                                  // el video
            StalkerEntity t = tv(dl, 3);
            int f = Math.min(FRAMES3, 1 + W3t / 2);
            if (t != null && f != (int) t.getProp("frame")) t.setProp("frame", f);
            if (W3t >= VIDEO3) {
                Vec3 p0 = new Vec3(tv.x + 1.0, Y + 1.25, tv.z);
                locust = SpawnHelper.createScripted(dl, "the_locust", p0, 90f);
                if (locust == null) {
                    W3 = 0;
                    return;
                }
                locust.addTag(TAG);
                locust.setProp("fase", 1);
                W3 = 2;
                W3t = 0;
                for (ServerPlayer q : PL) q.playNotifySound(BedHide.se("stalker.js_crack2"), SoundSource.MASTER, 1f, .6f);
            }
            return;
        }
        if (locust == null || !locust.isAlive()) {
            locust = null;
            W3 = 0;
            ensureEnts(dl, 3);
            return;
        }
        if (W3 == 2) {                                                  // sale de la tele poco a poco
            locust.setProp("fase", 1);
            double k = Math.min(1.0, W3t / (double) TELO_EMERGE);
            double fx, fy;
            if (k < 0.6) {
                fx = tv.x + 1.0 - 1.9 * (k / 0.6);
                fy = Y + 1.25;
            } else if (k < 0.8) {
                double q = (k - 0.6) / 0.2;
                fx = tv.x - 0.9 - 1.1 * q;
                fy = Y + 1.25 * (1 - q * q);
            } else {
                fx = tv.x - 2.0;
                fy = Y;
            }
            locust.moveTo(fx, fy, tv.z, 90f, 0f);
            locust.setYHeadRot(90f);
            locust.setYBodyRot(90f);
            if (W3t == 25 || W3t == 50 || W3t == 75) for (ServerPlayer q : PL) q.playNotifySound(BedHide.se("stalker.js_crack2"), SoundSource.MASTER, 1f, .5f + dl.random.nextFloat() * .3f);
            if (W3t == 96) dl.playSound(null, locust.blockPosition(), net.minecraft.sounds.SoundEvents.SPIDER_STEP, SoundSource.HOSTILE, 1.6f, .4f);
            if (W3t >= TELO_EMERGE) {
                W3 = 3;
                W3t = 0;
                StalkerEntity t = tv(dl, 3);
                if (t != null) t.setProp("frame", 0);
                for (ServerPlayer q : PL) q.playNotifySound(BedHide.se("stalker.goat_crawl_scream"), SoundSource.MASTER, 1.2f, .7f);
            }
            return;
        }
        if (W3 == 4) {                                                  // susto (lo mueve el estado "caught" del jugador)
            if (W3t > 80) {
                W3 = 3;
                W3t = 0;
                locust.setProp("fase", 2);
            }
            return;
        }
        ServerPlayer best = null;
        double bd = 1e9;
        for (ServerPlayer q : PL) {
            double d = Math.hypot(q.getX() - locust.getX(), q.getZ() - locust.getZ());
            if (d < bd) {
                bd = d;
                best = q;
            }
        }
        if (best == null) {
            locust.setProp("fase", 0);
            return;
        }
        double dx = best.getX() - locust.getX(), dz = best.getZ() - locust.getZ();
        if (bd <= TELO_CATCH && Math.abs(best.getY() - locust.getY()) < 3) {
            Sess s = SES.get(best.getUUID());
            if (s != null) {
                s.phase = "caught";
                s.t = 0;
                setEtapa(best, 3);
            }
            W3 = 4;
            W3t = 0;
            victim = best.getUUID();
            locust.setProp("fase", 4);
            float yaw = (float) (Math.atan2(-dx, dz) * 180 / Math.PI);
            locust.moveTo(locust.getX(), locust.getY(), locust.getZ(), yaw, 0f);
            locust.setYHeadRot(yaw);
            locust.setYBodyRot(yaw);
            best.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 120, 10, false, false));
            best.addEffect(new MobEffectInstance(MobEffects.JUMP, 120, 200, false, false));
            best.connection.send(new net.minecraft.network.protocol.game.ClientboundPlayerLookAtPacket(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, locust, net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES));
            best.playNotifySound(BedHide.se("stalker.goat_crawl_scream"), SoundSource.MASTER, 1.4f, .65f);
            return;
        }
        locust.setProp("fase", 2);
        double step = Math.min(TELO_SPEED, bd), c = cx(3);
        double nx = Math.max(c - LEN / 2.0 + 0.5, Math.min(c + LEN / 2.0 - 0.5, locust.getX() + dx / (bd == 0 ? 1 : bd) * step));
        double nz = Math.max(0.5, Math.min(4.5, locust.getZ() + dz / (bd == 0 ? 1 : bd) * step));
        float yaw = (float) (Math.atan2(-dx, dz) * 180 / Math.PI);
        locust.moveTo(nx, Y, nz, yaw, 0f);
        locust.setYHeadRot(yaw);
        locust.setYBodyRot(yaw);
        if (W3t % 9 == 0) dl.playSound(null, locust.blockPosition(), net.minecraft.sounds.SoundEvents.SPIDER_STEP, SoundSource.HOSTILE, 1.4f, .45f);
        if (W3t % 140 == 70) snd(dl, "stalker.unknown_scream", locust.position(), .7f, .6f);
    }

    @SubscribeEvent
    public void onInteract(PlayerInteractEvent.EntityInteract ev) {
        StalkerEntity e = asProp(ev.getTarget());
        if (e == null) return;
        ev.setCanceled(true);
        if (ev.getHand() == InteractionHand.MAIN_HAND && ev.getEntity() instanceof ServerPlayer p) touch(p, e);
    }

    @SubscribeEvent
    public void onAttack(AttackEntityEvent ev) {
        StalkerEntity e = asProp(ev.getTarget());
        if (e == null) return;
        ev.setCanceled(true);
        if (ev.getEntity() instanceof ServerPlayer p) touch(p, e);
    }

    // ---------------------------------------------------------------- dormir
    @SubscribeEvent
    public void onSleep(PlayerSleepInBedEvent ev) {
        if (!(ev.getEntity() instanceof ServerPlayer p) || !p.level().isNight() || p.isShiftKeyDown()) return;
        if (SES.containsKey(p.getUUID()) || SLEEP_AT.containsKey(p.getUUID()) || p.level().dimension() == DIM) return;
        if (etapa(p) >= MAX_ETAPA || BedHide.isHidden(p.getUUID()) || !StalkerEntity.ACTIVE.isEmpty()) return;
        long night = p.level().getDayTime() / 24000L;
        CompoundTag d = data(p);
        if (d.contains(K_NIGHT) && d.getLong(K_NIGHT) == night) return;       // una vez por noche
        d.putLong(K_NIGHT, night);
        MinecraftServer s = p.getServer();
        if (s != null) SLEEP_AT.put(p.getUUID(), (long) s.getTickCount() + 60);   // te dejas dormir 3 s...
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent ev) {
        if (ev.getEntity() instanceof ServerPlayer p) rescue(p);
    }

    @SubscribeEvent
    public void onRespawn(PlayerEvent.PlayerRespawnEvent ev) {
        if (ev.getEntity() instanceof ServerPlayer p) {
            SES.remove(p.getUUID());
            rescue(p);
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent ev) {
        if (ev.getEntity() instanceof ServerPlayer p) end(p.getUUID(), false);
    }

    // ---------------------------------------------------------------- tick
    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        long now = server.getTickCount();
        for (var it = new ArrayList<>(SLEEP_AT.entrySet()).iterator(); it.hasNext(); ) {
            var en = it.next();
            if (now < en.getValue()) continue;
            SLEEP_AT.remove(en.getKey());
            ServerPlayer p = server.getPlayerList().getPlayer(en.getKey());
            if (p != null && p.isAlive() && p.isSleeping()) {
                p.stopSleeping();
                startFor(p, 0);
            }
        }
        ServerLevel dl = server.getLevel(DIM);
        if (dl == null) return;
        for (int n : new ArrayList<>(BUILD.keySet())) runBuild(dl, n);
        tick3(server, dl);
        manage4(server, dl);
        for (Sess s : new ArrayList<>(SES.values())) {
            ServerPlayer p = server.getPlayerList().getPlayer(s.pid);
            if (p == null || !p.isAlive()) {
                end(s.pid, false);
                continue;
            }
            s.t++;
            Vec3 sp = spawn(s.n);
            switch (s.phase) {
                case "load" -> {
                    if (s.t < 10) continue;
                    if (BUILD.containsKey(s.n) && s.t < 1400) {
                        if (s.t % 20 == 0) p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 40, 0, false, false));
                        if (s.t == 10) tpSpawn(p, dl, sp);
                        continue;
                    }
                    if (!s.padded) {
                        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
                            dl.setBlock(new BlockPos((int) Math.floor(sp.x) + dx, Y - 1, (int) Math.floor(sp.z) + dz), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
                        s.padded = true;
                    }
                    tpSpawn(p, dl, sp);
                    p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 20000, 0, false, false));
                    p.removeEffect(MobEffects.DAMAGE_RESISTANCE);
                    p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 20, 0, false, false));
                    s.phase = "dream";
                    s.t = 0;
                    ensureEnts(dl, s.n);
                }
                case "dream" -> {
                    if (p.level() != dl) {
                        end(s.pid, false);
                        continue;
                    }
                    int H = half(s.n);
                    if (s.n == 4) {
                        if (p.getY() < Y - 8) tpSpawn(p, dl, sp);
                        double c = cx(4), wx = 0, wz = 0;
                        if (p.getX() - c > WRAP4) wx = -2 * WRAP4;
                        else if (c - p.getX() > WRAP4) wx = 2 * WRAP4;
                        if (p.getZ() > WRAP4) wz = -2 * WRAP4;
                        else if (p.getZ() < -WRAP4) wz = 2 * WRAP4;
                        if (wx != 0 || wz != 0) p.teleportTo(dl, p.getX() + wx, p.getY(), p.getZ() + wz, p.getYRot(), p.getXRot());
                        if (s.t == 1) setEtapa(p, 4);
                        if (s.t == MIN4 - 24) p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 60, 0, false, false));
                        if (s.t >= MIN4) {
                            s.phase = "wake";
                            s.t = 10;
                        }
                    } else if (s.n == 3) {
                        if (p.getY() < Y - 3 || p.getY() > Y + 6 || p.getZ() < -0.5 || p.getZ() > 5.5 || Math.abs(p.getX() - cx(3)) > LEN / 2.0 + 2) tpSpawn(p, dl, sp);
                    } else if (p.getY() < Y - 8 || Math.abs(p.getX() - cx(s.n)) > H + 3 || Math.abs(p.getZ()) > H + 3) tpSpawn(p, dl, sp);
                    if (s.t % 40 == 0) ensureEnts(dl, s.n);
                }
                case "tape" -> {
                    StalkerEntity t = tv(dl, s.n);
                    if (s.n == 2 && t != null) {
                        int f = Math.min(SUE2_FRAMES, 1 + s.t / 2);
                        if (f != (int) t.getProp("frame")) t.setProp("frame", f);
                    }
                    if (s.t == 1) setEtapa(p, s.n);
                    if (s.t == audioTicks(s.n) - 24) p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 60, 0, false, false));
                    if (s.t >= audioTicks(s.n)) {
                        s.phase = "wake";
                        s.t = 10;
                    }
                }
                case "caught" -> {                                      // susto de The Locust: grito, rojo y despiertas
                    if (s.t == 10) p.playNotifySound(BedHide.se("stalker.unknown_scream"), SoundSource.MASTER, 1.2f, .55f);
                    if (s.t == 10) p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 60, 0, false, false));
                    if (s.t >= 70) {
                        p.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
                        p.removeEffect(MobEffects.JUMP);
                        s.phase = "wake";
                        s.t = 0;
                    }
                }
                case "wake" -> {
                    if (s.t == 11) p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 40, 0, false, false));
                    if (s.t >= 18) end(s.pid, true);
                }
                default -> {
                }
            }
        }
    }

    private static void tpSpawn(ServerPlayer p, ServerLevel dl, Vec3 sp) {
        p.teleportTo(dl, sp.x, sp.y, sp.z, -90f, 0f);
        p.setDeltaMovement(Vec3.ZERO);
        p.fallDistance = 0;
    }
}
