package com.twixxel.stalker;

import com.google.gson.Gson;
import com.mojang.logging.LogUtils;
import com.twixxel.stalker.entity.StalkerEntity;
import com.twixxel.stalker.event.CommonEvents;
import com.twixxel.stalker.event.StalkerCommands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.common.ForgeSpawnEggItem;
import org.slf4j.Logger;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@Mod(TwixxelMod.MODID)
public class TwixxelMod {
    public static RegistryObject<Item> LIN_OFF, LIN_ON, PILA, CONFIG_ITEM;

    public static final String MODID = "twixxel";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MODID);
    public static final DeferredRegister<net.minecraft.world.level.block.Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MODID);
    public static final String[] WOODS = {"acacia", "bamboo", "birch", "cherry", "crimson", "dark_oak", "jungle", "mangrove", "oak", "pale_oak", "spruce", "warped"};
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MODID);
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, MODID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    /** id corto -> definicion */
    public static final Map<String, StalkerDef> DEFS = new LinkedHashMap<>();
    /** id corto -> tipo de entidad */
    public static final Map<String, RegistryObject<EntityType<StalkerEntity>>> TYPES = new LinkedHashMap<>();
    /** nombre del evento de sonido (ej. "stalker.spawn") -> evento */
    public static final Map<String, RegistryObject<SoundEvent>> SOUND_EVENTS = new LinkedHashMap<>();

    public TwixxelMod() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();

        loadDefs();
        loadSounds();
        com.twixxel.stalker.client.MolangBridge.init();

        // entidades + huevos
        for (StalkerDef d : DEFS.values()) {
            RegistryObject<EntityType<StalkerEntity>> type = ENTITIES.register(d.id, () ->
                    EntityType.Builder.<StalkerEntity>of(StalkerEntity::new, MobCategory.MISC)
                            .sized((float) d.w, (float) d.h)
                            .fireImmune()
                            .clientTrackingRange(16)
                            .updateInterval(d.id.equals("jumpscare") ? 1 : 2)
                            .build(d.id));
            TYPES.put(d.id, type);
            if (d.egg != null) {
                ITEMS.register(d.id + "_spawn_egg", () ->
                        new ForgeSpawnEggItem(type, 0x101010, 0x8a0000, new Item.Properties()));
            }
        }
        // objetos simples
        ITEMS.register("mascot", () -> new com.twixxel.stalker.item.PetItem("mascota"));
        ITEMS.register("pet2", () -> new com.twixxel.stalker.item.PetItem("mascota2"));
        ITEMS.register("pet3", () -> new com.twixxel.stalker.item.PetItem("mascota3"));
        for (int i = 4; i <= 7; i++) {
            final String pid = "mascota" + i;
            ITEMS.register("pet" + i, () -> new com.twixxel.stalker.item.PetItem(pid));
        }
        ITEMS.register("zoom", com.twixxel.stalker.item.ZoomItem::new);
        CONFIG_ITEM = ITEMS.register("config", com.twixxel.stalker.item.ConfigItem::new);
        LIN_OFF = ITEMS.register("linterna", () -> new com.twixxel.stalker.item.LinternaItem(false));
        LIN_ON = ITEMS.register("linterna_prendida", () -> new com.twixxel.stalker.item.LinternaItem(true));
        PILA = ITEMS.register("pila", com.twixxel.stalker.item.PilaItem::new);
        ITEMS.register("celular", com.twixxel.stalker.item.CelularItem::new);
        ITEMS.register("verity_peluche", com.twixxel.stalker.item.VerityItem::new);
        for (String w : WOODS) {
            RegistryObject<net.minecraft.world.level.block.Block> blk = BLOCKS.register("armario_" + w, com.twixxel.stalker.block.ArmarioBlock::new);
            ITEMS.register("armario_" + w, () -> new net.minecraft.world.item.BlockItem(blk.get(), new Item.Properties()));
        }

        TABS.register("main", () -> CreativeModeTab.builder()
                .title(Component.translatable("itemGroup." + MODID + ".main"))
                .icon(() -> {
                    Item egg = ForgeRegistries.ITEMS.getValue(new ResourceLocation(MODID, "stalker_bipedal_spawn_egg"));
                    return new ItemStack(egg != null ? egg : net.minecraft.world.item.Items.ENDER_EYE);
                })
                .displayItems((params, out) -> {
                    for (RegistryObject<Item> it : ITEMS.getEntries()) out.accept(it.get());
                })
                .build());

        com.twixxel.stalker.event.Net.init();
        ENTITIES.register(bus);
        BLOCKS.register(bus);
        ITEMS.register(bus);
        SOUNDS.register(bus);
        TABS.register(bus);

        bus.addListener(CommonEvents::onAttributes);

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        MinecraftForge.EVENT_BUS.register(new CommonEvents());
        MinecraftForge.EVENT_BUS.register(new StalkerCommands());
        MinecraftForge.EVENT_BUS.register(new com.twixxel.stalker.event.HelloVisit());
        MinecraftForge.EVENT_BUS.register(new com.twixxel.stalker.event.BedStalker());
        MinecraftForge.EVENT_BUS.register(new com.twixxel.stalker.event.BedHide());
        MinecraftForge.EVENT_BUS.register(new com.twixxel.stalker.event.LifeEvent());
        MinecraftForge.EVENT_BUS.register(new com.twixxel.stalker.event.HallwayEvent());
        MinecraftForge.EVENT_BUS.register(new com.twixxel.stalker.event.GoatDoorEvent());
        MinecraftForge.EVENT_BUS.register(new com.twixxel.stalker.event.DoorsEvent());
        MinecraftForge.EVENT_BUS.register(new com.twixxel.stalker.event.VigilanteEvent());
        MinecraftForge.EVENT_BUS.register(new com.twixxel.stalker.event.GoatPuertaEvent());
        MinecraftForge.EVENT_BUS.register(new com.twixxel.stalker.event.MaebiEvent());
        MinecraftForge.EVENT_BUS.register(new com.twixxel.stalker.event.SalAfueraEvent());
        MinecraftForge.EVENT_BUS.register(new com.twixxel.stalker.event.SuenoEvent());
        MinecraftForge.EVENT_BUS.register(new com.twixxel.stalker.event.PeleaEvent());
        MinecraftForge.EVENT_BUS.register(new com.twixxel.stalker.event.DuoFight());
        MinecraftForge.EVENT_BUS.register(new com.twixxel.stalker.event.GoatEatsPet());
        MinecraftForge.EVENT_BUS.register(new com.twixxel.stalker.event.BoatBreaker());
        MinecraftForge.EVENT_BUS.register(new com.twixxel.stalker.event.Alcantarilla());
        MinecraftForge.EVENT_BUS.register(new com.twixxel.stalker.event.AsomandomeEvent());
        MinecraftForge.EVENT_BUS.register(new com.twixxel.stalker.event.EscaleraEvent());
        MinecraftForge.EVENT_BUS.register(new com.twixxel.stalker.event.EnvueltoAcerca());
        MinecraftForge.EVENT_BUS.register(new com.twixxel.stalker.event.MinaRunEvent());
        MinecraftForge.EVENT_BUS.register(new com.twixxel.stalker.event.EvilblackEvent());
        MinecraftForge.EVENT_BUS.register(new com.twixxel.stalker.event.JumpScare());
        MinecraftForge.EVENT_BUS.register(new com.twixxel.stalker.event.DoorBreaker());
        MinecraftForge.EVENT_BUS.register(new com.twixxel.stalker.event.Lang());
        MinecraftForge.EVENT_BUS.register(new com.twixxel.stalker.event.StalkerConfig());
        MinecraftForge.EVENT_BUS.register(new com.twixxel.stalker.event.Pets());
        MinecraftForge.EVENT_BUS.register(new com.twixxel.stalker.event.Linterna());
        MinecraftForge.EVENT_BUS.register(new com.twixxel.stalker.event.Verity());
        MinecraftForge.EVENT_BUS.register(new com.twixxel.stalker.event.Ambiente());
        MinecraftForge.EVENT_BUS.register(new com.twixxel.stalker.event.Opciones());
        MinecraftForge.EVENT_BUS.register(new com.twixxel.stalker.event.WatcherClock());
        MinecraftForge.EVENT_BUS.register(new com.twixxel.stalker.event.Trampillas());
    }

    private static void loadDefs() {
        try (Reader r = new InputStreamReader(TwixxelMod.class.getResourceAsStream("/data/twixxel/entity_defs.json"), StandardCharsets.UTF_8)) {
            StalkerDef[] arr = new Gson().fromJson(r, StalkerDef[].class);
            for (StalkerDef d : arr) {
                d.init();
                DEFS.put(d.id, d);
            }
            LOGGER.info("Parallel Stalker: {} entidades cargadas", DEFS.size());
        } catch (Exception e) {
            throw new RuntimeException("No se pudo leer entity_defs.json", e);
        }
    }

    private static void loadSounds() {
        try (Reader r = new InputStreamReader(TwixxelMod.class.getResourceAsStream("/data/twixxel/sound_events.json"), StandardCharsets.UTF_8)) {
            String[] names = new Gson().fromJson(r, String[].class);
            for (String n : names) {
                SOUND_EVENTS.put(n, SOUNDS.register(n, () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(MODID, n))));
            }
        } catch (Exception e) {
            throw new RuntimeException("No se pudo leer sound_events.json", e);
        }
    }

    /** Idioma elegido: devuelve la variante _es del sonido cuando existe (y peek_hola / peek_hi). */
    public static String loc(String ev) {
        if (ev == null) return null;
        boolean es = com.twixxel.stalker.event.Lang.es();
        if (ev.equals("stalker.peek_hola") || ev.equals("stalker.peek_hi")) return es ? "stalker.peek_hola" : "stalker.peek_hi";
        if (ev.endsWith("_es")) return es ? ev : ev.substring(0, ev.length() - 3);
        return es && SOUND_EVENTS.containsKey(ev + "_es") ? ev + "_es" : ev;
    }

    /** Todas las variantes de un sonido (para detenerlo sin importar el idioma). */
    public static String[] variants(String ev) {
        if (ev.equals("stalker.peek_hola") || ev.equals("stalker.peek_hi")) return new String[]{"stalker.peek_hola", "stalker.peek_hi"};
        String base = ev.endsWith("_es") ? ev.substring(0, ev.length() - 3) : ev;
        return SOUND_EVENTS.containsKey(base + "_es") ? new String[]{base, base + "_es"} : new String[]{base};
    }

    public static StalkerDef defOf(EntityType<?> type) {
        ResourceLocation key = ForgeRegistries.ENTITY_TYPES.getKey(type);
        return key == null ? null : DEFS.get(key.getPath());
    }
}
