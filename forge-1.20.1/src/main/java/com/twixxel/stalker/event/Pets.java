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
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * MASCOTAS: mascota (Mascot, huesos), mascota2 (PET 2, carne de vaca cocinada), mascota3 (Mascot3, chuleta cocinada).
 * Aparecen solas (Mascot a los 30 min si dijiste que si; PET 2 cada 60 min y 50 % de que salga tambien Mascot3), se adoptan,
 * te siguen, se teletransportan, se sientan, se guardan como item y Mascot pelea por ti.
 */
public final class Pets {
    private static final int MASC_DELAY = 30 * 60 * 20, PET2_EVERY = 60 * 60 * 20, BONES = 2, TP_DIST = 12, ATK_CD = 100;
    private static final double GREET = 1.6, ATK_REACH = 2.3, ATK_DMG = 5;
    private static final Random RND = new Random();
    private static final String[] PLAN = {"mascota", "mascota2", "mascota3", "mascota4", "mascota5", "mascota6", "mascota7"};
    private static final int ALL_ONCE = (1 << 7) - 1;

    // ------------------------------------------------------------------ datos del mundo
    public static final class PetData extends SavedData {
        String opt = "";
        long ticks, pet2Ticks, clock;
        int once;                       // bit n = ya salio la mascota n+1 (reloj de las mascotas)

        static PetData load(CompoundTag t) {
            PetData d = new PetData();
            d.opt = t.getString("opt");
            d.ticks = t.getLong("ticks");
            d.pet2Ticks = t.getLong("pet2");
            d.clock = t.getLong("clock");
            d.once = t.getInt("once");
            return d;
        }

        @Override
        public CompoundTag save(CompoundTag t) {
            t.putString("opt", opt);
            t.putLong("ticks", ticks);
            t.putLong("pet2", pet2Ticks);
            t.putLong("clock", clock);
            t.putInt("once", once);
            return t;
        }
    }

    private static PetData data(MinecraftServer s) {
        return s.overworld().getDataStorage().computeIfAbsent(PetData::load, PetData::new, "twixxel_pets");
    }

    private static boolean es() {
        return com.twixxel.stalker.event.Lang.es();
    }

    private static String t(String es, String en) {
        return es() ? es : en;
    }

    private static final Set<UUID> asked = new HashSet<>();
    private static final Map<UUID, Integer> lastAsk = new HashMap<>();
    private static final Map<UUID, Integer> lastInteract = new HashMap<>();
    private static final Map<UUID, Integer> lastSnd = new HashMap<>();
    private static final Map<UUID, double[]> track = new HashMap<>();
    private static final Map<UUID, int[]> targets = new HashMap<>();       // mascota -> {t0, lastAtk}
    private static final Map<UUID, UUID> targetId = new HashMap<>();
    private static final Set<String> greeted = new HashSet<>();
    private static long pet3At = -1;
    private static int reviveLeft = 0;
    private static boolean quiet = false;
    private static final Set<String> xdNear = new HashSet<>();
    private static final Map<UUID, Integer> xdLast = new HashMap<>();

    private static boolean isPet(Entity e) {
        return e instanceof StalkerEntity s && s.getDefId().startsWith("mascota");
    }

    private static boolean tamed(Entity e) {
        return e.getTags().contains("domesticada");
    }

    private static boolean sitting(Entity e) {
        return e.getTags().contains("sentada");
    }

    private static UUID ownerOf(Entity e) {
        CompoundTag d = e.getPersistentData();
        return d.hasUUID("dueno") ? d.getUUID("dueno") : null;
    }

    private static void setOwner(Entity e, Player p) {
        e.getPersistentData().putUUID("dueno", p.getUUID());
    }

    private static List<StalkerEntity> pets(MinecraftServer s, String id) {
        List<StalkerEntity> out = new ArrayList<>();
        for (ServerLevel l : s.getAllLevels())
            for (Entity e : l.getAllEntities()) if (e instanceof StalkerEntity se && se.isAlive() && (id == null ? se.getDefId().startsWith("mascota") : se.getDefId().equals(id))) out.add(se);
        return out;
    }

    private static void bar(ServerPlayer p, String msg) {
        p.displayClientMessage(Component.literal(msg), true);
    }

    private static void hearts(Entity e, int n) {
        if (e.level() instanceof ServerLevel l) l.sendParticles(ParticleTypes.HEART, e.getX(), e.getY() + 1, e.getZ(), n, .45, .45, .45, 0);
    }

    private static void sound(Entity e, String ev, float vol) {
        var r = TwixxelMod.SOUND_EVENTS.get(TwixxelMod.loc(ev));
        if (r == null || !(e.level() instanceof ServerLevel l)) return;
        for (ServerPlayer p : l.players()) if (Math.hypot(p.getX() - e.getX(), p.getZ() - e.getZ()) <= 24)
            p.playNotifySound(r.get(), SoundSource.MASTER, vol, 1f);
    }

    private static void touch(StalkerEntity m, String ev) {
        int now = ServerLifecycleHooks.getCurrentServer().getTickCount();
        Integer last = lastSnd.get(m.getUUID());
        if (last != null && now - last < 10) return;
        lastSnd.put(m.getUUID(), now);
        sound(m, ev, 1.5f);
    }

    private static void consume(ServerPlayer p, Item item) {
        if (p.isCreative()) return;
        ItemStack st = p.getMainHandItem();
        if (st.is(item)) st.shrink(1);
    }

    // ------------------------------------------------------------------ comandos (botones del chat)
    @SubscribeEvent
    public void commands(RegisterCommandsEvent ev) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("twxpet");
        root.then(Commands.literal("opt").then(Commands.argument("v", StringArgumentType.word()).executes(c -> {
            String v = StringArgumentType.getString(c, "v");
            data(c.getSource().getServer()).opt = v.equals("si") ? "si" : "no";
            data(c.getSource().getServer()).setDirty();
            c.getSource().sendSuccess(() -> Component.literal(v.equals("si") ? t("§aMascota activada", "§aPet enabled") : t("§7Sin mascota", "§7No pet")), false);
            return 1;
        })));
        root.then(Commands.literal("agarrar").then(Commands.argument("id", StringArgumentType.word()).executes(c -> {
            ServerPlayer p = c.getSource().getPlayerOrException();
            Entity e = p.serverLevel().getEntity(UUID.fromString(StringArgumentType.getString(c, "id")));
            if (isPet(e) && tamed(e) && e.distanceTo(p) <= 8) pickUp((StalkerEntity) e, p);
            return 1;
        })));
        root.then(Commands.literal("sentar").then(Commands.argument("id", StringArgumentType.word()).executes(c -> {
            ServerPlayer p = c.getSource().getPlayerOrException();
            Entity e = p.serverLevel().getEntity(UUID.fromString(StringArgumentType.getString(c, "id")));
            if (isPet(e) && tamed(e) && e.distanceTo(p) <= 8) toggleSit((StalkerEntity) e, p);
            return 1;
        })));
        root.then(Commands.literal("aparecer").requires(s -> s.hasPermission(2)).executes(c -> {
            spawnPet1(c.getSource().getServer());
            return 1;
        }));
        root.then(Commands.literal("aparecer2").requires(s -> s.hasPermission(2)).executes(c -> {
            spawnPet(c.getSource().getServer(), "mascota2");
            return 1;
        }));
        root.then(Commands.literal("aparecer3").requires(s -> s.hasPermission(2)).executes(c -> {
            spawnPet(c.getSource().getServer(), "mascota3");
            return 1;
        }));
        for (int i = 4; i <= 7; i++) {
            final String pid = "mascota" + i;
            root.then(Commands.literal("aparecer" + i).requires(src -> src.hasPermission(2)).executes(c -> {
                spawnPet(c.getSource().getServer(), pid);
                return 1;
            }));
        }
        root.then(Commands.literal("preguntar").requires(s -> s.hasPermission(2)).executes(c -> {
            data(c.getSource().getServer()).opt = "";
            data(c.getSource().getServer()).setDirty();
            asked.clear();
            return 1;
        }));
        root.then(Commands.literal("reiniciar").requires(s -> s.hasPermission(2)).executes(c -> {
            PetData d = data(c.getSource().getServer());
            d.opt = "";
            d.ticks = 0;
            d.pet2Ticks = 0;
            d.clock = 0;
            d.once = 0;
            d.setDirty();
            asked.clear();
            return 1;
        }));
        ev.getDispatcher().register(root);
    }

    private static MutableComponent button(String label, String cmd, ChatFormatting color) {
        return Component.literal("[" + label + "] ").withStyle(s -> s.withColor(color).withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd)));
    }

    private static void menu(StalkerEntity m, ServerPlayer p, boolean withSit, String name) {
        MutableComponent c = Component.literal("§f" + name + " §7— " + t("¿Qué quieres hacer?", "What do you want to do?") + "\n");
        String id = m.getUUID().toString();
        if (withSit) c.append(button(sitting(m) ? t("Pararse", "Stand up") : t("Sentarse", "Sit down"), "/twxpet sentar " + id, ChatFormatting.AQUA));
        c.append(button(t("Agarrar (ítem)", "Pick up (item)"), "/twxpet agarrar " + id, ChatFormatting.GREEN));
        p.sendSystemMessage(c);
    }

    private static String nameOf(StalkerEntity m) {
        switch (m.getDefId()) {
            case "mascota": return "Mascot";
            case "mascota2": return "PET 2";
            case "mascota3": return "Mascot3";
            default: return m.getDefId();
        }
    }

    private static String itemOf(StalkerEntity m) {
        String id = m.getDefId();
        switch (id) {
            case "mascota": return "mascot";
            case "mascota2": return "pet2";
            case "mascota3": return "pet3";
            default: return "pet" + id.substring(7);
        }
    }

    /** Rebote de peluche (0.7 s) al tocarla. */
    private static final Map<UUID, Integer> bounceUntil = new HashMap<>();

    private static void bounce(StalkerEntity m) {
        m.setProp("rebote", 1);
        bounceUntil.put(m.getUUID(), ServerLifecycleHooks.getCurrentServer().getTickCount() + 14);
    }

    private static void toggleSit(StalkerEntity m, ServerPlayer p) {
        String n = nameOf(m);
        if (sitting(m)) {
            m.removeTag("sentada");
            bar(p, "§f" + n + t(" se paró", " stood up"));
        } else {
            m.addTag("sentada");
            m.getNavigation().stop();
            bar(p, "§f" + n + t(" se sentó", " sat down"));
        }
    }

    private static void pickUp(StalkerEntity m, ServerPlayer p) {
        Item it = ForgeRegistries.ITEMS.getValue(new net.minecraft.resources.ResourceLocation(TwixxelMod.MODID, itemOf(m)));
        if (it == null) return;
        ItemStack st = new ItemStack(it);
        st.setHoverName(Component.literal(nameOf(m)));
        m.discard();
        if (!p.getInventory().add(st)) p.drop(st, false);
        bar(p, "§f" + t("Tienes a ", "You picked up ") + nameOf(m));
    }

    // ------------------------------------------------------------------ interaccion
    @SubscribeEvent
    public void onInteract(PlayerInteractEvent.EntityInteract ev) {
        if (ev.getLevel().isClientSide || ev.getHand() != InteractionHand.MAIN_HAND || !(ev.getEntity() instanceof ServerPlayer p) || !isPet(ev.getTarget())) return;
        StalkerEntity m = (StalkerEntity) ev.getTarget();
        ev.setCanceled(true);
        ev.setCancellationResult(InteractionResult.SUCCESS);
        int now = p.server.getTickCount();
        Integer last = lastInteract.get(p.getUUID());
        if (last != null && now - last < 6) return;
        lastInteract.put(p.getUUID(), now);
        ItemStack held = p.getMainHandItem();
        boolean tamed = tamed(m);
        UUID ow = ownerOf(m);
        if (tamed && ow != null && !ow.equals(p.getUUID())) return;      // no es tu mascota: no puedes hacerle nada
        bounce(m);
        switch (m.getDefId()) {
            case "mascota":
                if (!tamed) {
                    if (!held.is(Items.BONE)) return;
                    if (!p.isShiftKeyDown()) {
                        bar(p, t("Agáchate para darle un hueso", "Sneak to give it a bone"));
                        return;
                    }
                    consume(p, Items.BONE);
                    int n = m.getPersistentData().getInt("huesos") + 1;
                    m.getPersistentData().putInt("huesos", n);
                    hearts(m, 6);
                    sound(m, "mascota.hueso", 1.5f);
                    if (n >= BONES) {
                        m.addTag("domesticada");
                        setOwner(m, p);
                        hearts(m, 12);
                        bar(p, t("¡Ahora es tu mascota!", "It is your pet now!"));
                    }
                    return;
                }
                if (!p.isShiftKeyDown()) menu(m, p, false, "Mascot");
                else toggleSit(m, p);
                return;
            case "mascota2":
                if (!tamed) {
                    if (!held.is(Items.COOKED_BEEF)) {
                        touch(m, "mascota2.toque");
                        return;
                    }
                    consume(p, Items.COOKED_BEEF);
                    m.addTag("domesticada");
                    setOwner(m, p);
                    hearts(m, 12);
                    bar(p, t("§f¡Ahora PET 2 es tu mascota!", "§fPET 2 is now your pet!"));
                    return;
                }
                touch(m, "mascota2.toque");
                if (!p.isShiftKeyDown()) menu(m, p, true, "PET 2");
                else toggleSit(m, p);
                return;
            default: {                                          // mascota3 a mascota7
                String id = m.getDefId();
                boolean chicken = id.equals("mascota5");
                boolean food = chicken ? (held.is(Items.COOKED_CHICKEN) || held.is(Items.CHICKEN)) : held.is(Items.COOKED_PORKCHOP);
                String snd = id + ".toque";
                if (!tamed) {
                    if (!food) return;
                    consume(p, held.getItem());
                    m.addTag("domesticada");
                    setOwner(m, p);
                    hearts(m, 12);
                    lastSnd.remove(m.getUUID());
                    touch(m, chicken ? "mascota5.gracias" : snd);
                    bar(p, t("§f¡Ahora " + nameOf(m) + " es tu mascota!", "§f" + nameOf(m) + " is now your pet!"));
                    return;
                }
                touch(m, snd);
                if (p.isShiftKeyDown()) menu(m, p, true, nameOf(m));
            }
        }
    }

    /** Pegarle cuenta como tocarlo (PET 2 siempre, Mascot3 si ya es tuya); no se le hace dano. Si pegas a un mob, Mascot pelea. */
    @SubscribeEvent
    public void onAttack(AttackEntityEvent ev) {
        if (ev.getEntity().level().isClientSide || !(ev.getEntity() instanceof ServerPlayer p)) return;
        Entity t = ev.getTarget();
        if (isPet(t)) {
            StalkerEntity m = (StalkerEntity) t;
            if (m.getDefId().equals("mascota2")) touch(m, "mascota2.toque");
            else if (!m.getDefId().equals("mascota") && !m.getDefId().equals("mascota2") && tamed(m)) touch(m, m.getDefId() + ".toque");
            bounce(m);
            ev.setCanceled(true);
            return;
        }
        if (!(t instanceof LivingEntity le) || t instanceof ArmorStand || t == p) return;
        var key = ForgeRegistries.ENTITY_TYPES.getKey(t.getType());
        if (key != null && key.getNamespace().equals(TwixxelMod.MODID)) return;
        int now = p.server.getTickCount();
        for (StalkerEntity m : pets(p.server, "mascota")) {
            if (!tamed(m) || sitting(m) || m.level() != p.level()) continue;
            if (!p.getUUID().equals(ownerOf(m))) continue;
            if (Math.hypot(m.getX() - t.getX(), m.getZ() - t.getZ()) > 40) continue;
            UUID prev = targetId.get(m.getUUID());
            if (prev != null && prev.equals(le.getUUID())) {
                targets.get(m.getUUID())[0] = now;
                continue;
            }
            targetId.put(m.getUUID(), le.getUUID());
            targets.put(m.getUUID(), new int[]{now, 0});
        }
    }

    // ------------------------------------------------------------------ colocar el item
    public static InteractionResult place(ServerPlayer p, ItemStack st, String petId, BlockPos clicked, Direction face) {
        ServerLevel l = p.serverLevel();
        List<BlockPos> tries = new ArrayList<>();
        if (clicked != null) {
            tries.add(clicked.relative(face == null ? Direction.UP : face));
            tries.add(clicked.above());
        } else {
            Vec3 v = p.getLookAngle();
            for (double d : new double[]{3, 2, 1.5, 1}) {
                tries.add(BlockPos.containing(p.getX() + v.x * d, p.getY(), p.getZ() + v.z * d));
                tries.add(BlockPos.containing(p.getX() + v.x * d, p.getY() + 1, p.getZ() + v.z * d));
            }
        }
        BlockPos at = null;
        for (BlockPos b : tries) {
            if (Houses.free(l, b) && Houses.free(l, b.above())) {
                at = b;
                break;
            }
        }
        if (at == null) {
            bar(p, t("§fNo hay espacio para ponerlo aquí", "§fThere is no room to place it here"));
            return InteractionResult.FAIL;
        }
        var ro = TwixxelMod.TYPES.get(petId);
        if (ro == null) return InteractionResult.FAIL;
        StalkerEntity e = ro.get().create(l);
        if (e == null) return InteractionResult.FAIL;
        e.moveTo(at.getX() + .5, at.getY(), at.getZ() + .5, p.getYRot() + 180, 0);
        quiet = true;
        l.addFreshEntity(e);
        quiet = false;
        e.addTag("domesticada");
        setOwner(e, p);
        if (petId.equals("mascota")) e.getPersistentData().putInt("huesos", BONES);
        hearts(e, 6);
        st.shrink(1);
        bar(p, "§f" + nameOf(e) + t(" está aquí", " is here"));
        return InteractionResult.SUCCESS;
    }

    @SubscribeEvent
    public void onJoin(EntityJoinLevelEvent ev) {
        if (ev.getLevel().isClientSide) return;
        Entity e = ev.getEntity();
        if (e instanceof ItemEntity ie && ie.getItem().getItem() instanceof com.twixxel.stalker.item.PetItem) ie.setUnlimitedLifetime();   // el item no desaparece
        if (isPet(e)) {
            e.setInvulnerable(true);                                    // inmortal
            if (!ev.loadedFromDisk() && !quiet && !((StalkerEntity) e).getDefId().equals("mascota") && !((StalkerEntity) e).getDefId().equals("mascota2") && ev.getLevel().getServer() != null)
                ev.getLevel().getServer().getPlayerList().broadcastSystemMessage(Component.literal(t("§e" + nameOf((StalkerEntity) e) + " apareció", "§e" + nameOf((StalkerEntity) e) + " appeared")), false);
        }
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent ev) {
        asked.remove(ev.getEntity().getUUID());
    }

    // ------------------------------------------------------------------ aparicion
    private static boolean freeSpot(ServerLevel l, BlockPos p) {
        BlockState below = l.getBlockState(p.below());
        if (!below.isFaceSturdy(l, p.below(), Direction.UP) || below.is(BlockTags.LEAVES) || below.is(BlockTags.LOGS)) return false;
        return Houses.free(l, p) && Houses.free(l, p.above());
    }

    private static BlockPos findSpot(ServerPlayer pl) {
        ServerLevel l = pl.serverLevel();
        for (int i = 0; i < 160; i++) {
            double ang = RND.nextDouble() * Math.PI * 2, r = 6 + RND.nextDouble() * 26;
            int sx = (int) Math.floor(pl.getX() + Math.cos(ang) * r), sz = (int) Math.floor(pl.getZ() + Math.sin(ang) * r);
            int top = l.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, sx, sz);
            for (int y = top; y >= top - 22; y--) {
                BlockPos lp = new BlockPos(sx, y, sz);
                if (!l.getBlockState(lp).is(BlockTags.LOGS)) continue;
                int base = y;
                while (l.getBlockState(new BlockPos(sx, base - 1, sz)).is(BlockTags.LOGS)) base--;
                for (int[] o : new int[][]{{2, 0}, {-2, 0}, {0, 2}, {0, -2}, {2, 2}, {-2, -2}, {2, -2}, {-2, 2}})
                    for (int gy : new int[]{base, base + 1, base - 1}) {
                        BlockPos c = new BlockPos(sx + o[0], gy, sz + o[1]);
                        if (freeSpot(l, c)) return c;
                    }
                break;
            }
        }
        // sin arboles: un sitio abierto cerca
        for (int i = 0; i < 40; i++) {
            double ang = RND.nextDouble() * Math.PI * 2, r = 8 + RND.nextDouble() * 14;
            int x = (int) Math.floor(pl.getX() + Math.cos(ang) * r), z = (int) Math.floor(pl.getZ() + Math.sin(ang) * r);
            Integer gy = SpawnHelper.groundY(l, x, z, pl.getY());
            if (gy != null && freeSpot(l, new BlockPos(x, gy, z))) return new BlockPos(x, gy, z);
        }
        return null;
    }

    private static boolean spawnPet(MinecraftServer s, String id) {
        List<ServerPlayer> ps = new ArrayList<>();
        for (ServerPlayer p : s.getPlayerList().getPlayers()) if (p.isAlive() && !p.isSpectator() && !SuenoEvent.isDreaming(p) && p.level().dimension() == net.minecraft.world.level.Level.OVERWORLD) ps.add(p);
        if (ps.isEmpty()) return false;
        for (StalkerEntity m : pets(s, id)) if (!tamed(m)) return true;                // ya hay una salvaje esperando
        ServerPlayer pl = ps.get(RND.nextInt(ps.size()));
        BlockPos spot = findSpot(pl);
        if (spot == null) return false;
        var ro = TwixxelMod.TYPES.get(id);
        if (ro == null) return false;
        StalkerEntity e = ro.get().create(pl.serverLevel());
        if (e == null) return false;
        e.moveTo(spot.getX() + .5, spot.getY(), spot.getZ() + .5, RND.nextFloat() * 360, 0);
        quiet = true;
        pl.serverLevel().addFreshEntity(e);
        quiet = false;
        String nm = nameOf(e);
        s.getPlayerList().broadcastSystemMessage(Component.literal(t("§e" + nm + " apareció", "§e" + nm + " appeared")), false);
        return true;
    }

    private static boolean spawnPet1(MinecraftServer s) {
        boolean ok = spawnPet(s, "mascota");
        if (ok) reviveLeft = 8;
        return ok;
    }

    // ------------------------------------------------------------------ tick
    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END) return;
        MinecraftServer s = ServerLifecycleHooks.getCurrentServer();
        if (s == null || s.getPlayerList().getPlayers().isEmpty()) return;
        int now = s.getTickCount();
        PetData d = data(s);
        if (now % 20 == 0) {
            // pregunta (una vez por sesion hasta que se responda)
            if (d.opt.isEmpty()) {
                for (ServerPlayer p : s.getPlayerList().getPlayers()) {
                    if (!Lang.chosen()) break;   // primero el idioma y despues la mascota
                    if (now - lastAsk.getOrDefault(p.getUUID(), -9999) < 300) continue;
                    lastAsk.put(p.getUUID(), now);
                    Net.ask(p, "pet");
                    break;
                }
            } else if (d.opt.equals("si") && d.once != ALL_ONCE) {
                // reloj de las mascotas: cada una sale UNA sola vez (10, 20, 30, 40, 50, 60 y 70 min de mundo jugado)
                d.clock += 20;
                for (int i = 0; i < PLAN.length; i++) {
                    if ((d.once & (1 << i)) != 0 || d.clock < (long) (i + 1) * 10 * 60 * 20) continue;
                    boolean ok = i == 0 ? spawnPet1(s) : spawnPet(s, PLAN[i]);
                    if (ok) d.once |= 1 << i;
                    break;                                         // una por vez
                }
                d.setDirty();
            }
        }
        if (reviveLeft > 0 && now % 10 == 0) {
            reviveLeft--;
            for (ServerPlayer p : s.getPlayerList().getPlayers()) bar(p, t("§fTu mascota ha resucitado", "§fYour pet has revived"));
        }
        List<StalkerEntity> all = null;
        if (now % 2 == 0) all = pets(s, null);
        if (all == null) return;
        for (StalkerEntity m : all) petTick(s, m, now);
    }

    private static ServerPlayer nearestPlayer(MinecraftServer s, Entity m) {
        ServerPlayer best = null;
        double bd = 1e18;
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            if (p.level() != m.level() || p.isSpectator()) continue;
            double dd = p.distanceToSqr(m);
            if (dd < bd) {
                bd = dd;
                best = p;
            }
        }
        return best;
    }

    private static void look(StalkerEntity m, double dx, double dz) {
        float yaw = (float) (Math.atan2(-dx, dz) * 180 / Math.PI - 90);
        m.setYRot(yaw);
        m.setYHeadRot(yaw);
        m.setYBodyRot(yaw);
    }

    private static BlockPos nearSpot(ServerPlayer owner) {
        ServerLevel l = owner.serverLevel();
        for (int i = 0; i < 14; i++) {
            double ang = RND.nextDouble() * Math.PI * 2, r = 1.5 + RND.nextDouble() * 1.5;
            int x = (int) Math.floor(owner.getX() + Math.cos(ang) * r), z = (int) Math.floor(owner.getZ() + Math.sin(ang) * r);
            for (int dy : new int[]{0, 1, -1, 2, -2}) {
                BlockPos c = new BlockPos(x, (int) Math.floor(owner.getY()) + dy, z);
                if (freeSpot(l, c)) return c;
            }
        }
        return owner.blockPosition();
    }

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();
    private static final Map<UUID, Integer> moveRetry = new HashMap<>();

    /**
     * Lleva una mascota (o la Verity) a otra dimension detras de su dueno SIN el portal de vanilla
     * (changeDimension buscaba/creaba portales y crasheaba el juego al cruzar con la mascota suelta).
     * Va directo al sitio de destino; si algo falla se registra en el log y se reintenta mas tarde en vez de tumbar el servidor.
     */
    public static void followAcross(MinecraftServer s, Entity m, ServerLevel dest, double x, double y, double z) {
        int now = s.getTickCount();
        Integer next = moveRetry.get(m.getUUID());
        if (next != null && now < next) return;
        if (m.isRemoved() || !m.isAlive() || dest == null) return;
        try {
            if (m instanceof net.minecraft.world.entity.Mob mob) mob.getNavigation().stop();
            m.unRide();
            targetId.remove(m.getUUID());
            targets.remove(m.getUUID());
            track.remove(m.getUUID());
            m.teleportTo(dest, x, y, z, java.util.Set.of(), m.getYRot(), 0);
            moveRetry.remove(m.getUUID());
        } catch (Exception ex) {
            moveRetry.put(m.getUUID(), now + 200);
            LOG.error("[twixxel] mascota: no pude llevarla a {} (se reintenta en 10 s)", dest.dimension().location(), ex);
        }
    }

    private static void petTick(MinecraftServer s, StalkerEntity m, int now) {
        if (m.isScripted()) return;                                         // escena de la cabra
        if (m.isNoAi() && !m.getPersistentData().getBoolean("sentado")) m.setNoAi(false);   // los guardados de antes quedaron sin IA
        String id = m.getDefId();
        Integer bu = bounceUntil.get(m.getUUID());
        if (bu != null && now >= bu) {
            bounceUntil.remove(m.getUUID());
            m.setProp("rebote", 0);
        }
        ServerPlayer near = nearestPlayer(s, m);
        boolean tamed = tamed(m);
        // mascota5 salvaje: te dice "xd" cada vez que llegas cerca (a 4 bloques)
        if (id.equals("mascota5") && !tamed && now % 5 == 0) {
            for (ServerPlayer p : s.getPlayerList().getPlayers()) {
                String key = m.getUUID() + "|" + p.getUUID();
                boolean nr = p.level() == m.level() && p.distanceTo(m) <= 4;
                boolean was = xdNear.contains(key);
                if (nr) xdNear.add(key); else xdNear.remove(key);
                if (!nr || was || now - xdLast.getOrDefault(m.getUUID(), -9999) < 100) continue;
                xdLast.put(m.getUUID(), now);
                sound(m, "mascota5.xd", 1.5f);
                bounce(m);
            }
        }
        // Mascot salvaje: saluda al acercarte a 1 bloque
        if (id.equals("mascota") && !tamed && now % 4 == 0) {
            for (ServerPlayer p : s.getPlayerList().getPlayers()) {
                String key = m.getUUID() + "|" + p.getUUID();
                if (p.level() != m.level()) {
                    greeted.remove(key);
                    continue;
                }
                double dd = p.distanceTo(m);
                if (dd <= GREET) {
                    if (greeted.add(key)) {
                        var r = TwixxelMod.SOUND_EVENTS.get(TwixxelMod.loc("mascota.saludo"));
                        if (r != null) p.playNotifySound(r.get(), SoundSource.MASTER, 1.5f, 1f);
                    }
                } else if (dd > 4) greeted.remove(key);
            }
        }
        // pelea: va por el objetivo y lo golpea cada 5 s
        UUID tid = targetId.get(m.getUUID());
        if (tid != null) {
            Entity te = ((ServerLevel) m.level()).getEntity(tid);
            int[] st = targets.get(m.getUUID());
            if (!(te instanceof LivingEntity tl) || !te.isAlive() || st == null || now - st[0] > 1200 || sitting(m) || !tamed) {
                targetId.remove(m.getUUID());
                targets.remove(m.getUUID());
                m.getNavigation().stop();
            } else {
                look(m, te.getX() - m.getX(), te.getZ() - m.getZ());
                double dd = Math.hypot(te.getX() - m.getX(), te.getZ() - m.getZ());
                if (dd > ATK_REACH) {
                    if (now % 10 == 0 || m.getNavigation().isDone()) m.getNavigation().moveTo(te, 2.6);
                } else {
                    m.getNavigation().stop();
                    if (now - st[1] >= ATK_CD || st[1] == 0) {
                        st[1] = now;
                        tl.hurt(m.damageSources().mobAttack(m), (float) ATK_DMG);
                    }
                }
                return;
            }
        }
        // siempre te mira a la cara (es un plano)
        if (near != null && near.distanceToSqr(m) > .0025) look(m, near.getX() - m.getX(), near.getZ() - m.getZ());
        if (!tamed) return;
        // nunca se pierde: si cae al vacio vuelve contigo
        UUID ou = ownerOf(m);
        ServerPlayer owner = ou == null ? near : s.getPlayerList().getPlayer(ou);
        if (owner == null) return;
        if (m.getY() < m.level().getMinBuildHeight() + 2) {
            BlockPos sp = nearSpot(owner);
            m.teleportTo((ServerLevel) owner.level(), sp.getX() + .5, sp.getY(), sp.getZ() + .5, java.util.Set.of(), m.getYRot(), 0);
            return;
        }
        if (sitting(m)) {
            m.getNavigation().stop();
            return;
        }
        if (now % 10 != 0) return;
        boolean same = owner.level() == m.level();
        double dist = same ? owner.distanceTo(m) : Double.MAX_VALUE;
        boolean tp = !same || dist > TP_DIST;
        double[] tr = track.computeIfAbsent(m.getUUID(), k -> new double[]{m.getX(), m.getZ(), 0});
        double moved = Math.hypot(m.getX() - tr[0], m.getZ() - tr[1]);
        tr[2] = moved < .05 && dist > 6 ? tr[2] + 10 : 0;
        tr[0] = m.getX();
        tr[1] = m.getZ();
        if (tr[2] >= 60) {
            tp = true;
            tr[2] = 0;
        }
        if (tp) {
            BlockPos sp = nearSpot(owner);
            if (!same) followAcross(s, m, (ServerLevel) owner.level(), sp.getX() + .5, sp.getY(), sp.getZ() + .5);
            else m.teleportTo((ServerLevel) owner.level(), sp.getX() + .5, sp.getY(), sp.getZ() + .5, java.util.Set.of(), m.getYRot(), 0);
            return;
        }
        if (dist > 3.5) m.getNavigation().moveTo(owner, 1.5);
        else if (dist < 2.5) m.getNavigation().stop();
    }
}
