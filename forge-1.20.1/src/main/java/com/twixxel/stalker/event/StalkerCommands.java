package com.twixxel.stalker.event;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.twixxel.stalker.entity.StalkerEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.List;

/**
 * /twixxel prop <entidades> <nombre> <valor>   -> cambia una propiedad "twixxel:*" (fase, lado, run, mov...)
 * /twixxel event <bipedal|stalker_evil|stalker_goatevil|stalker_quad_red> [jugador]
 */
public class StalkerCommands {
    private static final List<String> EVENTS = List.of("bipedal", "stalker_evil", "stalker_goatevil", "stalker_quad_red",
            "stalker_goat", "stalker_spotted", "stalker_smiler", "stalker_unknown", "stalker_ansiedad",
            "stalker_hello", "stalker_quad", "stalker_hey", "stalker_run", "stalker_door",
            "stalker_window", "stalker_woman", "stalker_tree", "stalker_ignore", "stalker_screamer", "stalker_goat_window", "stalker_windowbreak",
            "stalker_sufrimiento", "stalker_spotted2", "stalker_goatevil_window", "stalker_run_window", "stalker_observer", "stalker_peeker", "stalker_peek1", "stalker_peek2",
            "stalker_run_asoma", "stalker_goat_asoma", "stalker_spotted_asoma", "stalker_vida_asoma", "stalker_arriba", "stalker_run_peek",
            "stalker_jester", "stalker_hello2", "stalker_grip", "stalker_vida", "stalker_door2", "stalker_miron", "stalker_tortura", "stalker_cueva",
            "stalker_hole", "stalker_agujero", "stalker_hello3", "hello_noche", "hello_cama", "stalker_bed", "stalker_luz", "stalker_redlight", "stalker_life", "stalker_run_pasillo", "stalker_puertas", "stalker_vigilante", "stalker_goat_door", "stalker_goat_jester", "pelea_spotted", "pelea_run", "goat_run",
            "sueno", "sueno_1", "sueno_2", "sueno_3", "sueno_4", "stalker_goat_puerta", "stalker_craneo", "stalker_trepador", "stalker_maebi", "stalker_lc", "stalker_goat_real2", "stalker_run_salafuera",
            "stalker_goat_ventana2", "stalker_smiler_ventana", "stalker_vida_ventana", "stalker_envuelto", "stalker_watcher", "stalker_watcher2", "stalker_cuernos", "the_locust");

    @SubscribeEvent
    public void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        // respuesta de "te gusta la vida" (sin permisos: la usan los botones del chat)
        d.register(Commands.literal("twxvida")
                .then(Commands.literal("yes").executes(c -> vida(c.getSource(), true)))
                .then(Commands.literal("no").executes(c -> vida(c.getSource(), false))));
        // opciones del objeto de configuracion (sin permisos, como en el addon)
        d.register(Commands.literal("twxcalma")
                .then(Commands.literal("yes").executes(c -> opcion(c.getSource(), true, true)))
                .then(Commands.literal("no").executes(c -> opcion(c.getSource(), true, false))));
        d.register(Commands.literal("twxfoto").requires(s -> s.hasPermission(2))
                .then(Commands.argument("nombre", StringArgumentType.word()).executes(c -> {
                    var pl = c.getSource().getPlayerOrException();
                    Net.foto(pl, StringArgumentType.getString(c, "nombre"));
                    return 1;
                })));
        d.register(Commands.literal("twxoscuridad")
                .then(Commands.literal("yes").executes(c -> opcion(c.getSource(), false, true)))
                .then(Commands.literal("no").executes(c -> opcion(c.getSource(), false, false))));
        d.register(Commands.literal("twixxel").requires(s -> s.hasPermission(2))
                .then(Commands.literal("prop")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .then(Commands.argument("value", DoubleArgumentType.doubleArg())
                                                .executes(StalkerCommands::setProp)))))
                .then(Commands.literal("event")
                        .then(Commands.argument("type", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(EVENTS, b))
                                .executes(c -> runEvent(c, c.getSource().getPlayerOrException()))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(c -> runEvent(c, EntityArgument.getPlayer(c, "player")))))));
    }

    private static int opcion(CommandSourceStack src, boolean calma, boolean on) {
        if (calma) com.twixxel.stalker.Config.CALM.set(on);
        else com.twixxel.stalker.Config.NO_DARK.set(on);
        try {
            (calma ? com.twixxel.stalker.Config.CALM : com.twixxel.stalker.Config.NO_DARK).save();
        } catch (Exception ignored) {
        }
        String es = calma ? "Quitar movimiento" : "Quitar oscuridad de mods";
        String en = calma ? "Remove motion" : "Remove mod darkness";
        src.sendSuccess(() -> net.minecraft.network.chat.Component.literal("\u00a77" + (Lang.es() ? es : en) + ": " + (on ? "\u00a7aS\u00ed/Yes" : "\u00a7cNo")), false);
        return 1;
    }

    private static int setProp(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String name = StringArgumentType.getString(ctx, "name");
        double value = DoubleArgumentType.getDouble(ctx, "value");
        int ok = 0;
        for (Entity e : EntityArgument.getEntities(ctx, "targets")) {
            if (e instanceof StalkerEntity s && s.setProp(name, value)) ok++;
        }
        final int n = ok;
        ctx.getSource().sendSuccess(() -> Component.literal("twixxel:" + name + " = " + value + " en " + n + " entidad(es)"), true);
        return ok;
    }

    /** Pruebas de la visita del HELLO: noche (toca la puerta), cama (te espera bajo la cama). */
    private static boolean helloPhase(ServerPlayer p, String type) {
        if (type.equals("hello_noche")) {
            HelloVisit.setPhase(p, "noche");
            p.getPersistentData().putBoolean(HelloVisit.K_NOW, true);
        } else {
            HelloVisit.setPhase(p, "cama");
            p.sendSystemMessage(Component.literal(Lang.es() ? "\u00a77Ahora intenta dormir en tu cama..." : "\u00a77Now try to sleep in your bed..."));
        }
        return true;
    }

    private static int vida(CommandSourceStack src, boolean yes) {
        if (!(src.getEntity() instanceof ServerPlayer p)) return 0;
        for (com.twixxel.stalker.entity.StalkerEntity e : com.twixxel.stalker.entity.StalkerEntity.ACTIVE) e.vidaAnswer(p, yes);
        return 1;
    }

    private static int runEvent(CommandContext<CommandSourceStack> ctx, ServerPlayer player) {
        String type = StringArgumentType.getString(ctx, "type");
        com.twixxel.stalker.entity.FieldProfile fp = com.twixxel.stalker.entity.FieldProfile.ALL.get(type);
        boolean ok = type.equals("stalker_bed") ? BedStalker.startNear(player)
                : type.equals("stalker_life") ? LifeEvent.startFor(player)
                : type.equals("stalker_run_pasillo") ? HallwayEvent.arm(player)
                : type.equals("goat_run") ? DuoFight.startNearGoat(player)
                : type.equals("pelea_spotted") ? PeleaEvent.startNear(player, "duelo")
                : type.equals("pelea_run") ? PeleaEvent.startNear(player, "trio")
                : type.equals("stalker_goat_door") ? GoatDoorEvent.command(player)
                : type.equals("stalker_puertas") ? DoorsEvent.command(player)
                : type.equals("stalker_vigilante") ? VigilanteEvent.startFor(player)
                : type.equals("sueno") ? SuenoEvent.startFor(player, 0)
                : type.equals("sueno_1") ? SuenoEvent.startFor(player, 1)
                : type.equals("sueno_2") ? SuenoEvent.startFor(player, 2)
                : type.equals("sueno_3") ? SuenoEvent.startFor(player, 3)
                : type.equals("sueno_4") ? SuenoEvent.startFor(player, 4)
                : type.equals("stalker_run_salafuera") ? SalAfueraEvent.startFor(player)
                : type.equals("stalker_maebi") ? MaebiEvent.startFor(player)
                : type.equals("stalker_goat_puerta") ? GoatPuertaEvent.startFor(player)
                : com.twixxel.stalker.entity.ScareProfile.ALL.containsKey(type) ? SpawnHelper.spawnScare(player, type, type.equals("stalker_hello3"))
                : type.equals("hello_noche") || type.equals("hello_cama") ? helloPhase(player, type)
                : SpawnHelper.isSpecial(type) ? SpawnHelper.spawnSpecial(player, type)
                : type.equals("stalker_door") ? SpawnHelper.spawnDoor(player)
                : fp != null && fp.spawnAround ? SpawnHelper.spawnAround(player, type, fp.dist[0])
                : fp != null && fp.spawnBehind ? SpawnHelper.spawnBehind(player, type, fp.dist)
                : type.equals("bipedal") ? SpawnHelper.spawnBipedal(player)
                : fp != null ? SpawnHelper.spawnGround(player, type, fp.dist)
                : SpawnHelper.spawnChase(player, type, true);
        ctx.getSource().sendSuccess(() -> Component.literal(ok ? "Evento iniciado: " + type : "No se pudo iniciar: " + type), true);
        return ok ? 1 : 0;
    }
}
