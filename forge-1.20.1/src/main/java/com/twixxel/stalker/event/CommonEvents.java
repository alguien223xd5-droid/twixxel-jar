package com.twixxel.stalker.event;

import com.twixxel.stalker.Config;
import com.twixxel.stalker.TwixxelMod;
import com.twixxel.stalker.entity.FieldProfile;
import com.twixxel.stalker.entity.ScareProfile;
import com.twixxel.stalker.entity.StalkerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

/** Atributos de las entidades + aparicion automatica por turnos (un stalker a la vez). */
public class CommonEvents {
    private static final int GAP_TICKS = 60 * 20;   // al menos 1 minuto entre un stalker y el siguiente
    private final Random rnd = new Random();

    private long nextDoor = -1;
    private long nextBipedal = -1, nextEvil = -1, gapUntil = 0;
    private boolean wasBusy = false;
    private final Map<String, Long> nextField = new HashMap<>();
    private int cfgVersion = 0;
    private boolean goatNext = true;                // primero goatevil, luego evil, luego goatevil...

    public static void onAttributes(EntityAttributeCreationEvent event) {
        TwixxelMod.TYPES.values().forEach(t -> event.put(t.get(), StalkerEntity.createAttributes().build()));
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        nextField.clear();
        nextDoor = -1;
        Director.clear();
        nextBipedal = nextEvil = -1;   // el reloj se reinicia al cargar el mundo
        gapUntil = 0;
        wasBusy = false;
        goatNext = true;
        StalkerEntity.ACTIVE.clear();
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        StalkerEntity.ACTIVE.clear();
        Director.clear();
    }

    /** Mientras el stalker de la puerta esta afuera no se puede usar ninguna puerta. */
    @SubscribeEvent
    public void onUseBlock(PlayerInteractEvent.RightClickBlock event) {
        var b = event.getLevel().getBlockState(event.getPos()).getBlock();
        if (!(b instanceof DoorBlock || b instanceof TrapDoorBlock || b instanceof FenceGateBlock)) return;
        for (StalkerEntity e : StalkerEntity.ACTIVE) {
            if (e.isDoorStalker() || e.locksDoors()) {
                event.setCanceled(true);
                return;
            }
        }
    }

    @SubscribeEvent
    public void onPlaced(net.minecraftforge.event.level.BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel sl && event.getEntity() instanceof net.minecraft.world.entity.player.Player) {
            PlacedBlocks.get(sl).add(sl, event.getPos());
        }
    }

    @SubscribeEvent
    public void onBroken(net.minecraftforge.event.level.BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel sl) PlacedBlocks.get(sl).remove(sl, event.getPos());
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        long now = server.getTickCount();
        Director.tick(server);
        if (now % 20 != 0 || !Config.NATURAL_SPAWNS.get()) return;

        List<ServerPlayer> players = server.getPlayerList().getPlayers().stream()
                .filter(p -> p.isAlive() && !p.isSpectator() && !SuenoEvent.isDreaming(p)).collect(Collectors.toList());
        if (players.isEmpty() || SuenoEvent.busy()) return;
        if (cfgVersion != StalkerConfig.version) {          // cambio la configuracion: los relojes empiezan de nuevo con los tiempos nuevos
            cfgVersion = StalkerConfig.version;
            nextField.clear();
            nextDoor = nextBipedal = nextEvil = -1;
        }

        if (!StalkerEntity.ACTIVE.isEmpty()) {
            wasBusy = true;
            return;
        }
        if (wasBusy) {
            wasBusy = false;
            gapUntil = now + (peekChain ? 60 : GAP_TICKS);   // tras la cabeza del RUN, la puerta sale a los 3 s
            peekChain = false;
        }
        if (nextBipedal < 0) nextBipedal = now + SpawnConfig.ticks("stalker_bipedal", Config.BIPEDAL_MIN_MINUTES.get() * 1200, Config.BIPEDAL_MAX_MINUTES.get() * 1200, rnd);
        if (nextEvil < 0) nextEvil = now + SpawnConfig.ticks("stalker_evil", Config.EVIL_MIN_MINUTES.get() * 1200, Config.EVIL_MAX_MINUTES.get() * 1200, rnd);
        if (now < gapUntil) return;

        ServerPlayer target = players.get(rnd.nextInt(players.size()));
        if (now >= nextEvil) {
            String id = goatNext ? "stalker_goatevil" : "stalker_evil";
            if (SpawnHelper.spawnChase(target, id, true)) {
                goatNext = !goatNext;
                nextEvil = now + SpawnConfig.ticks("stalker_evil", Config.EVIL_MIN_MINUTES.get() * 1200, Config.EVIL_MAX_MINUTES.get() * 1200, rnd);
            }
        } else if (spawnDueScare(now, target)) {
            // ventana / mujer / arbol
        } else if (spawnDueSpecial(now, target)) {
            // se asoma / no mires arriba
        } else if (spawnDueDoor(now, target)) {
            // el stalker de la puerta aparecio
        } else if (spawnDueField(now, target)) {
            // un stalker de campo abierto (goat, spotted, smiler...) aparecio
        } else if (now >= nextBipedal) {
            if (SpawnHelper.spawnBipedal(target)) {
                nextBipedal = now + SpawnConfig.ticks("stalker_bipedal", Config.BIPEDAL_MIN_MINUTES.get() * 1200, Config.BIPEDAL_MAX_MINUTES.get() * 1200, rnd);
            } else {
                nextBipedal = now + 100;   // no hubo sitio: reintenta en 5 s
            }
        }
    }

    private boolean spawnDueScare(long now, ServerPlayer target) {
        for (Map.Entry<String, ScareProfile> e : ScareProfile.ALL.entrySet()) {
            ScareProfile p = e.getValue();
            if (!p.natural) continue;
            long due = nextField.computeIfAbsent("s_" + e.getKey(), k -> now + SpawnConfig.ticks(e.getKey(), p.natMinTicks, p.natMaxTicks, rnd));
            if (now < due) continue;
            if (SpawnHelper.spawnScare(target, e.getKey())) {
                nextField.put("s_" + e.getKey(), now + SpawnConfig.ticks(e.getKey(), p.natMinTicks, p.natMaxTicks, rnd));
                return true;
            }
            nextField.put("s_" + e.getKey(), now + 100);   // no hay ventana/arbol adecuado: reintenta en 5 s
        }
        return false;
    }

    private boolean peekDone = false, peekChain = false;

    private static final Map<String, int[]> SPECIAL = new java.util.LinkedHashMap<>();

    static {
        SPECIAL.put("stalker_peeker", new int[]{6000, 6000});
        SPECIAL.put("stalker_peek1", new int[]{1200, 6000});
        SPECIAL.put("stalker_peek2", new int[]{1200, 6000});
        SPECIAL.put("stalker_run_asoma", new int[]{1200, 6000});
        SPECIAL.put("stalker_goat_asoma", new int[]{1200, 6000});
        SPECIAL.put("stalker_spotted_asoma", new int[]{1200, 6000});
        SPECIAL.put("stalker_vida_asoma", new int[]{18000, 36000});
        SPECIAL.put("stalker_arriba", new int[]{2400, 8400});
        SPECIAL.put("stalker_grip", new int[]{1200, 6000});
        SPECIAL.put("stalker_vida", new int[]{24000, 48000});
        SPECIAL.put("stalker_door2", new int[]{36000, 42000});
        SPECIAL.put("stalker_hole", new int[]{6000, 6000});
        SPECIAL.put("stalker_agujero", new int[]{4800, 9600});
    }

    public static int[] specialRange(String id) {
        return SPECIAL.get(id == null ? "" : id);
    }

    private boolean spawnDueSpecial(long now, ServerPlayer target) {
        for (Map.Entry<String, int[]> e : SPECIAL.entrySet()) {
            int[] r = e.getValue();
            String key = "x_" + e.getKey();
            long due = nextField.computeIfAbsent(key, k -> now + SpawnConfig.ticks(e.getKey(), r[0], r[1], rnd));
            if (now < due) continue;
            if (SpawnHelper.spawnSpecial(target, e.getKey())) {
                nextField.put(key, now + SpawnConfig.ticks(e.getKey(), r[0], r[1], rnd));
                return true;
            }
            nextField.put(key, now + 200);                          // sin escondite: reintenta a los 10 s
        }
        return false;
    }

    private boolean spawnDueDoor(long now, ServerPlayer target) {
        if (nextDoor < 0) nextDoor = now + SpawnConfig.ticks("stalker_door", 18000, 36000, rnd);
        if (now < nextDoor) return false;
        if (!peekDone && SpawnHelper.spawnScare(target, "stalker_run_peek")) {   // antes, la cabeza del RUN en una ventana 1x1
            peekDone = true;
            peekChain = true;
            return true;
        }
        if (SpawnHelper.spawnDoor(target)) {
            peekDone = false;
            nextDoor = now + SpawnConfig.ticks("stalker_door", 18000, 36000, rnd);
            return true;
        }
        nextDoor = now + 100;       // no hay puerta cerca: reintenta en 5 s
        return false;
    }

    /** Revisa el reloj propio de cada stalker de campo abierto; true si uno aparecio. */
    private boolean spawnDueField(long now, ServerPlayer target) {
        for (Map.Entry<String, FieldProfile> e : FieldProfile.ALL.entrySet()) {
            FieldProfile p = e.getValue();
            if (!p.natural) continue;
            long due = nextField.computeIfAbsent(e.getKey(), k -> now + SpawnConfig.ticks(e.getKey(), p.natMinTicks, p.natMaxTicks, rnd));
            if (now < due) continue;
            if (p.mineOnly && !SpawnHelper.inMine(target)) {
                nextField.put(e.getKey(), now + 200);       // no estas en una mina: revisa de nuevo en 10 s
                continue;
            }
            if (e.getKey().equals("stalker_goat") && !PeleaEvent.active() && rnd.nextDouble() < PeleaEvent.CHANCE) {
                // 20 %: la cabra aparece peleando con el spotted (y con el RUN un 30 % de las veces)
                if (PeleaEvent.startNear(target, rnd.nextDouble() < 0.30 ? "trio" : "duelo", p.dist)) {
                    nextField.put(e.getKey(), now + SpawnConfig.ticks(e.getKey(), p.natMinTicks, p.natMaxTicks, rnd));
                    return true;
                }
            }
            if (p.spawnAround ? SpawnHelper.spawnAround(target, e.getKey(), p.dist[0]) : SpawnHelper.spawnGround(target, e.getKey(), p.dist)) {
                if (e.getKey().equals("stalker_goat")) DuoFight.rollSpawn(now);     // 30 %: a los 3 s aparece el RUN y la cabra lo mata
                nextField.put(e.getKey(), now + SpawnConfig.ticks(e.getKey(), p.natMinTicks, p.natMaxTicks, rnd));
                return true;
            }
            nextField.put(e.getKey(), now + 100);   // sin sitio: reintenta en 5 s
        }
        return false;
    }

    private long minutes(int min, int max) {
        if (max < min) max = min;
        return (min + rnd.nextInt(max - min + 1)) * 1200L;
    }
}
