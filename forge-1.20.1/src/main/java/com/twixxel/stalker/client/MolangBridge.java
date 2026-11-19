package com.twixxel.stalker.client;

import com.twixxel.stalker.StalkerDef;
import com.twixxel.stalker.TwixxelMod;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.DoubleSupplier;

/**
 * Pasa las propiedades "twixxel:*" de cada entidad al Molang de GeckoLib (query.twx_<prop> y query.twxv_<var>).
 * Se usa reflexion porque la API de Molang de GeckoLib 4 cambio entre versiones menores
 * (MathParser.setVariable en 4.4+, MolangParser.INSTANCE.setValue antes).
 */
public final class MolangBridge {
    public static final String PROP = "query.twx_";
    public static final String VAR = "query.twxv_";
    public static final String CAM_PITCH = "query.twx_cam_pitch", CAM_YAW = "query.twx_cam_yaw",
            CAM_DIST = "query.twx_cam_dist", BODY_YAW = "query.twx_body_yaw";

    private static Method setStatic;      // MathParser.setVariable(String, DoubleSupplier)
    private static Object parser;         // MolangParser.INSTANCE (API antigua)
    private static Method setOnParser;    // MolangParser#setValue(String, DoubleSupplier)
    private static boolean ready = false;

    private MolangBridge() {
    }

    /** Registra todas las variables antes de que se carguen las animaciones. */
    public static void init() {
        Set<String> names = new LinkedHashSet<>();
        names.add(CAM_PITCH);
        names.add(CAM_YAW);
        names.add(CAM_DIST);
        names.add(BODY_YAW);
        for (StalkerDef d : TwixxelMod.DEFS.values()) {
            for (String p : d.props.keySet()) names.add(PROP + p);
            for (String v : d.preNames) names.add(VAR + v);
        }
        try {
            Class<?> mp = Class.forName("software.bernie.geckolib.loading.math.MathParser");
            Class<?> vc = Class.forName("software.bernie.geckolib.loading.math.value.Variable");
            Method reg = mp.getMethod("registerVariable", vc);
            Constructor<?> ctor = vc.getConstructor(String.class, double.class);
            for (String n : names) {
                try {
                    reg.invoke(null, ctor.newInstance(n, 0.0));
                } catch (ReflectiveOperationException ignored) {
                }
            }
            setStatic = mp.getMethod("setVariable", String.class, DoubleSupplier.class);
            ready = true;
            TwixxelMod.LOGGER.info("Molang: usando MathParser ({} variables)", names.size());
            return;
        } catch (Throwable t) {
            TwixxelMod.LOGGER.debug("MathParser no disponible: {}", t.toString());
        }
        try {
            Class<?> mp = Class.forName("software.bernie.geckolib.core.molang.MolangParser");
            Field inst = mp.getField("INSTANCE");
            parser = inst.get(null);
            setOnParser = mp.getMethod("setValue", String.class, DoubleSupplier.class);
            ready = true;
            TwixxelMod.LOGGER.info("Molang: usando MolangParser");
        } catch (Throwable t) {
            TwixxelMod.LOGGER.warn("No se encontro la API de Molang de GeckoLib: las animaciones que dependen "
                    + "de propiedades dinamicas (inclinacion de cabeza, etc.) no se actualizaran. {}", t.toString());
        }
    }

    public static void set(String name, double value) {
        if (!ready) return;
        DoubleSupplier s = () -> value;
        try {
            if (setStatic != null) setStatic.invoke(null, name, s);
            else if (setOnParser != null) setOnParser.invoke(parser, name, s);
        } catch (ReflectiveOperationException ignored) {
        }
    }
}
