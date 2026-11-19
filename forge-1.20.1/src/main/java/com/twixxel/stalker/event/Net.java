package com.twixxel.stalker.event;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/** Canal de red: el servidor pide abrir en pantalla la pregunta de idioma o de mascota. */
public final class Net {
    private static final String VER = "1";
    public static final SimpleChannel CH = NetworkRegistry.newSimpleChannel(new ResourceLocation("twixxel", "main"),
            () -> VER, VER::equals, VER::equals);

    private Net() {
    }

    public static final class OpenChoice {
        final String kind;
        final boolean es;

        OpenChoice(String kind, boolean es) {
            this.kind = kind;
            this.es = es;
        }

        static void enc(OpenChoice m, FriendlyByteBuf b) {
            b.writeUtf(m.kind);
            b.writeBoolean(m.es);
        }

        static OpenChoice dec(FriendlyByteBuf b) {
            return new OpenChoice(b.readUtf(), b.readBoolean());
        }

        static void handle(OpenChoice m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.twixxel.stalker.client.ChoiceScreen.open(m.kind, m.es)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** Servidor -> cliente: abre el menu de configuracion con los valores actuales. */
    public static final class OpenConfig {
        final int[] vals;
        final boolean es, calm, noDark;

        OpenConfig(int[] vals, boolean es, boolean calm, boolean noDark) {
            this.vals = vals;
            this.es = es;
            this.calm = calm;
            this.noDark = noDark;
        }

        static void enc(OpenConfig m, FriendlyByteBuf b) {
            b.writeVarInt(m.vals.length);
            for (int v : m.vals) b.writeVarInt(v);
            b.writeBoolean(m.es);
            b.writeBoolean(m.calm);
            b.writeBoolean(m.noDark);
        }

        static OpenConfig dec(FriendlyByteBuf b) {
            int[] v = new int[b.readVarInt()];
            for (int i = 0; i < v.length; i++) v[i] = b.readVarInt();
            return new OpenConfig(v, b.readBoolean(), b.readBoolean(), b.readBoolean());
        }

        static void handle(OpenConfig m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.twixxel.stalker.client.ConfigScreen.open(m.vals, m.es, m.calm, m.noDark)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** Cliente -> servidor: cambia el tiempo de un stalker (index en CfgList) o una opcion (index -1 = calma, -2 = oscuridad). */
    public static final class SetConfig {
        final int index, value;

        public SetConfig(int index, int value) {
            this.index = index;
            this.value = value;
        }

        static void enc(SetConfig m, FriendlyByteBuf b) {
            b.writeVarInt(m.index + 2);
            b.writeVarInt(m.value);
        }

        static SetConfig dec(FriendlyByteBuf b) {
            return new SetConfig(b.readVarInt() - 2, b.readVarInt());
        }

        static void handle(SetConfig m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer p = ctx.get().getSender();
                if (p == null || !(p.hasPermissions(2) || p.getServer().isSingleplayer())) return;
                if (m.index == -1) com.twixxel.stalker.Config.CALM.set(m.value != 0);
                else if (m.index == -2) com.twixxel.stalker.Config.NO_DARK.set(m.value != 0);
                else if (m.index >= 0 && m.index < CfgList.ALL.length) StalkerConfig.set(p.getServer(), CfgList.ALL[m.index][0], m.value);
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /** Servidor -> cliente: el idioma elegido para el mod. */
    public static final class LangSync {
        final boolean es;

        LangSync(boolean es) {
            this.es = es;
        }

        static void enc(LangSync m, FriendlyByteBuf b) {
            b.writeBoolean(m.es);
        }

        static LangSync dec(FriendlyByteBuf b) {
            return new LangSync(b.readBoolean());
        }

        static void handle(LangSync m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.twixxel.stalker.client.ClientLang.set(m.es)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** Servidor -> cliente: sacudida de camara (amplitud, ticks). */
    public static final class Shake {
        final float amp;
        final int ticks;

        Shake(float amp, int ticks) {
            this.amp = amp;
            this.ticks = ticks;
        }

        static void enc(Shake m, FriendlyByteBuf b) {
            b.writeFloat(m.amp);
            b.writeVarInt(m.ticks);
        }

        static Shake dec(FriendlyByteBuf b) {
            return new Shake(b.readFloat(), b.readVarInt());
        }

        static void handle(Shake m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.twixxel.stalker.client.ShakeClient.add(m.amp, m.ticks)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** Sacude la camara del jugador (no hace nada en modo calma). */
    public static void shake(ServerPlayer p, float amp, int ticks) {
        if (Opciones.calm()) return;
        CH.send(PacketDistributor.PLAYER.with(() -> p), new Shake(amp, ticks));
    }

    public static void shakeNear(net.minecraft.server.level.ServerLevel l, net.minecraft.world.phys.Vec3 at, double r, float amp, int ticks) {
        for (ServerPlayer p : l.players()) if (p.position().distanceTo(at) <= r) shake(p, amp, ticks);
    }

    /** Servidor -> cliente: texto grande rojo/multiplicado (twxc) o imagen a pantalla completa (twxf). */
    public static final class Hud {
        final boolean isFoto, calm;
        final String text;
        final int ticks;

        Hud(boolean isFoto, String text, int ticks, boolean calm) {
            this.isFoto = isFoto;
            this.text = text;
            this.ticks = ticks;
            this.calm = calm;
        }

        static void enc(Hud m, FriendlyByteBuf b) {
            b.writeBoolean(m.isFoto);
            b.writeUtf(m.text);
            b.writeVarInt(m.ticks);
            b.writeBoolean(m.calm);
        }

        static Hud dec(FriendlyByteBuf b) {
            return new Hud(b.readBoolean(), b.readUtf(), b.readVarInt(), b.readBoolean());
        }

        static void handle(Hud m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                if (m.isFoto) com.twixxel.stalker.client.HudClient.foto(m.text);
                else com.twixxel.stalker.client.HudClient.title(m.text, m.ticks, m.calm);
            }));
            ctx.get().setPacketHandled(true);
        }
    }

    /** Titulo grande estilo addon (rojo, multiplicado, temblando). Texto vacio = quitarlo. */
    public static void title(ServerPlayer p, String text, int ticks) {
        CH.send(PacketDistributor.PLAYER.with(() -> p), new Hud(false, text, ticks, Opciones.calm()));
    }

    /** Imagen a pantalla completa (textures/gui/foto/NOMBRE.png); "off" la quita. */
    public static void foto(ServerPlayer p, String name) {
        CH.send(PacketDistributor.PLAYER.with(() -> p), new Hud(true, name, 0, false));
    }

    /** Cliente -> servidor: el jugador puso un video del celular (el 0 arma el reloj del watcher: 20 min). */
    public static final class CelVideo {
        final int idx;

        public CelVideo(int idx) {
            this.idx = idx;
        }

        static void enc(CelVideo m, FriendlyByteBuf b) {
            b.writeVarInt(m.idx);
        }

        static CelVideo dec(FriendlyByteBuf b) {
            return new CelVideo(b.readVarInt());
        }

        static void handle(CelVideo m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer p = ctx.get().getSender();
                if (p != null && m.idx == 0) WatcherClock.arm(p);
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /** Servidor -> cliente: camara en tercera persona (te ves tirado en el suelo) y de vuelta a la de antes. */
    public static final class Cam {
        final boolean on;

        Cam(boolean on) {
            this.on = on;
        }

        static void enc(Cam m, FriendlyByteBuf b) {
            b.writeBoolean(m.on);
        }

        static Cam dec(FriendlyByteBuf b) {
            return new Cam(b.readBoolean());
        }

        static void handle(Cam m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.twixxel.stalker.client.HudClient.thirdPerson(m.on)));
            ctx.get().setPacketHandled(true);
        }
    }

    public static void thirdPerson(ServerPlayer p, boolean on) {
        CH.send(PacketDistributor.PLAYER.with(() -> p), new Cam(on));
    }

    public static void syncLang(ServerPlayer p) {
        CH.send(PacketDistributor.PLAYER.with(() -> p), new LangSync(Lang.es()));
    }

    public static void openConfig(ServerPlayer p) {
        CH.send(PacketDistributor.PLAYER.with(() -> p),
                new OpenConfig(StalkerConfig.values(), Lang.es(), com.twixxel.stalker.Config.CALM.get(), com.twixxel.stalker.Config.NO_DARK.get()));
    }

    public static void init() {
        CH.registerMessage(1, OpenConfig.class, OpenConfig::enc, OpenConfig::dec, OpenConfig::handle,
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CH.registerMessage(3, LangSync.class, LangSync::enc, LangSync::dec, LangSync::handle,
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CH.registerMessage(2, SetConfig.class, SetConfig::enc, SetConfig::dec, SetConfig::handle,
                java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CH.registerMessage(4, Shake.class, Shake::enc, Shake::dec, Shake::handle,
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CH.registerMessage(5, Hud.class, Hud::enc, Hud::dec, Hud::handle,
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CH.registerMessage(6, CelVideo.class, CelVideo::enc, CelVideo::dec, CelVideo::handle,
                java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CH.registerMessage(7, Cam.class, Cam::enc, Cam::dec, Cam::handle,
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CH.registerMessage(0, OpenChoice.class, OpenChoice::enc, OpenChoice::dec, OpenChoice::handle,
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    /** kind = "lang" | "pet" */
    public static void ask(ServerPlayer p, String kind) {
        CH.send(PacketDistributor.PLAYER.with(() -> p), new OpenChoice(kind, Lang.es()));
    }
}
