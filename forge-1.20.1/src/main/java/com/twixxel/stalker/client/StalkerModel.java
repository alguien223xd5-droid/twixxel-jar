package com.twixxel.stalker.client;

import com.twixxel.stalker.entity.StalkerEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class StalkerModel extends GeoModel<StalkerEntity> {
    @Override
    public ResourceLocation getModelResource(StalkerEntity entity) {
        return entity.geoLocation();
    }

    @Override
    public ResourceLocation getTextureResource(StalkerEntity entity) {
        return entity.texLocation();
    }

    @Override
    public ResourceLocation getAnimationResource(StalkerEntity entity) {
        return entity.animLocation();
    }

    /** Verity: la cara siempre mira a la camara (yaw calculado aqui, no depende del signo de la animacion Molang). */
    @Override
    public void setCustomAnimations(StalkerEntity entity, long instanceId, software.bernie.geckolib.core.animation.AnimationState<StalkerEntity> state) {
        super.setCustomAnimations(entity, instanceId, state);
        if (!"verity".equals(entity.getDefId())) return;
        var ball = getAnimationProcessor().getBone("ball");
        if (ball == null) return;
        float pt = state.getPartialTick();
        var cam = net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double dx = cam.x - net.minecraft.util.Mth.lerp(pt, entity.xo, entity.getX());
        double dz = cam.z - net.minecraft.util.Mth.lerp(pt, entity.zo, entity.getZ());
        double camYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double bodyYaw = net.minecraft.util.Mth.rotLerp(pt, entity.yBodyRotO, entity.yBodyRot);
        ball.setRotY((float) Math.toRadians(bodyYaw - camYaw));
    }
}
