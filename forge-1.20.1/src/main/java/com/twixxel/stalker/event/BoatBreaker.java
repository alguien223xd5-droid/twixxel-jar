package com.twixxel.stalker.event;

import com.twixxel.stalker.entity.StalkerEntity;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

/** LOS STALKERS ROMPEN LOS BARCOS: si un stalker se mete en un barco, o queda pegado a uno, el barco se rompe. */
public final class BoatBreaker {
    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END) return;
        MinecraftServer s = ServerLifecycleHooks.getCurrentServer();
        if (s == null || s.getTickCount() % 10 != 0 || StalkerEntity.ACTIVE.isEmpty()) return;
        for (StalkerEntity st : StalkerEntity.ACTIVE) {
            if (!st.isAlive() || !(st.level() instanceof ServerLevel l)) continue;
            for (Boat b : l.getEntitiesOfClass(Boat.class, st.getBoundingBox().inflate(1.8))) {
                l.sendParticles(ParticleTypes.CRIT, b.getX(), b.getY() + .5, b.getZ(), 10, .3, .2, .3, .1);
                l.playSound(null, b.getX(), b.getY(), b.getZ(), SoundEvents.ITEM_BREAK, SoundSource.NEUTRAL, 1f, .8f);
                b.kill();
            }
        }
    }
}
