package com.twixxel.stalker.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.twixxel.stalker.entity.StalkerEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.twixxel.stalker.molang.Molang;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

import java.util.Map;

public class StalkerRenderer extends GeoEntityRenderer<StalkerEntity> {
    public StalkerRenderer(EntityRendererProvider.Context context) {
        super(context, new StalkerModel());
        this.shadowRadius = 0f;
    }

    @Override
    public void render(StalkerEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight) {
        applyMolangVariables(entity);
        float s = (float) entity.renderScale();
        poseStack.pushPose();
        poseStack.scale(s, s, s);
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
        poseStack.popPose();
    }

    // ---- malla real de Verity (poly_mesh de Bedrock; GeckoLib no la soporta): se dibuja en el hueso "ball"
    private static float[] MESH;
    private static boolean meshTried = false;

    private static float[] mesh() {
        if (!meshTried) {
            meshTried = true;
            try (java.io.InputStream in = StalkerRenderer.class.getResourceAsStream("/assets/twixxel/verity_mesh.json")) {
                if (in != null) {
                    com.google.gson.JsonObject o = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                    com.google.gson.JsonArray a = o.getAsJsonArray("v");
                    float[] f = new float[a.size()];
                    for (int i = 0; i < f.length; i++) f[i] = a.get(i).getAsFloat();
                    MESH = f;
                }
            } catch (Exception e) {
                com.twixxel.stalker.TwixxelMod.LOGGER.warn("No se pudo cargar la malla de Verity: {}", e.toString());
            }
        }
        return MESH;
    }

    @Override
    public void renderCubesOfBone(PoseStack poseStack, GeoBone bone, VertexConsumer buffer, int packedLight, int packedOverlay,
                                  float red, float green, float blue, float alpha) {
        float[] m;
        if (bone.getName().equals("ball") && (m = mesh()) != null) {
            PoseStack.Pose last = poseStack.last();
            org.joml.Matrix4f pm = last.pose();
            org.joml.Matrix3f nm = last.normal();
            for (int i = 0; i + 7 < m.length; i += 8) {
                buffer.vertex(pm, -m[i], m[i + 1], m[i + 2]).color(red, green, blue, alpha).uv(m[i + 6], m[i + 7])
                        .overlayCoords(packedOverlay).uv2(packedLight).normal(nm, -m[i + 3], m[i + 4], m[i + 5]).endVertex();
            }
            return;
        }
        super.renderCubesOfBone(poseStack, bone, buffer, packedLight, packedOverlay, red, green, blue, alpha);
    }

    /** part_visibility del render controller de Bedrock: las reglas se aplican en orden (la ultima que coincide gana). */
    @Override
    public void preRender(PoseStack poseStack, StalkerEntity entity, BakedGeoModel model, MultiBufferSource bufferSource,
                          VertexConsumer buffer, boolean isReRender, float partialTick, int packedLight, int packedOverlay,
                          float red, float green, float blue, float alpha) {
        if (entity.def.partVis != null && !entity.def.partVis.isEmpty()) {
            for (GeoBone b : model.topLevelBones()) applyVisibility(entity, b);
        }
        super.preRender(poseStack, entity, model, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, red, green, blue, alpha);
    }

    private static void applyVisibility(StalkerEntity entity, GeoBone bone) {
        boolean vis = true;
        var rules = entity.def.partVis;
        for (int i = 0; i < rules.size(); i++) {
            String pat = rules.get(i).get(0);
            boolean match = pat.equals("*") || pat.equals(bone.getName())
                    || (pat.endsWith("*") && bone.getName().startsWith(pat.substring(0, pat.length() - 1)));
            if (match) vis = Molang.truthy(entity.def.partVisE.get(i).eval(entity));
        }
        bone.setHidden(!vis);
        for (GeoBone c : bone.getChildBones()) applyVisibility(entity, c);
    }

    /**
     * Las animaciones convertidas usan query.twx_<propiedad> (antes query.property('twixxel:...'))
     * y query.twxv_<x> (antes v.<x>, calculadas en pre_animation).
     */
    private static void applyMolangVariables(StalkerEntity entity) {
        net.minecraft.world.phys.Vec3 cam = net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double cdx = cam.x - entity.getX(), cdy = cam.y - (entity.getY() + entity.getBbHeight() * 0.5), cdz = cam.z - entity.getZ();
        MolangBridge.set(MolangBridge.CAM_PITCH, -Math.toDegrees(Math.atan2(cdy, Math.hypot(cdx, cdz))));
        MolangBridge.set(MolangBridge.CAM_YAW, Math.toDegrees(Math.atan2(-cdx, cdz)));
        MolangBridge.set(MolangBridge.CAM_DIST, Math.sqrt(cdx * cdx + cdy * cdy + cdz * cdz));
        MolangBridge.set(MolangBridge.BODY_YAW, net.minecraft.util.Mth.wrapDegrees(entity.yBodyRot));
        for (String name : entity.def.props.keySet()) {
            MolangBridge.set(MolangBridge.PROP + name, entity.getProp(name));
        }
        for (Map.Entry<String, Double> e : entity.evalPreAnimation().entrySet()) {
            MolangBridge.set(MolangBridge.VAR + e.getKey(), e.getValue());
        }
    }
}
