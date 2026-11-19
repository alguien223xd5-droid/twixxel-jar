package com.twixxel.stalker.entity;

import java.util.HashMap;
import java.util.Map;

/** Stalkers que se esconden tras un arbol o una pared y se asoman a vigilarte (peeker, peek1/2, run_asoma, goat_asoma). */
public final class PeekProfile {
    public enum Style {PEEKER, SINGLE, STATIC, GRIP}

    public Style style = Style.SINGLE;
    public boolean treeOnly = false;     // solo arboles (peek1/2)
    public boolean wallOnly = false;     // solo bloques puestos (peeker)
    public double range = 14, minDist = 4;
    public double behind = 0.95;         // queda escondido a esta distancia detras del bloque
    public double slide = 0.3;           // cuanto se corre al costado al asomarse
    public int inTicks = 32;             // tiempo asomandose
    public int sideFase = 1, topFase = 3, downFase = 0, hideFase = 0;
    public int sideCrackFase = 2, topCrackFase = 4, topDownFase = 5;
    public double headLat = 0.57, headUp = 2.55;   // cara asomada por el costado
    public double near = 2.0;            // si te acercas, desaparece y te deja a oscuras
    public int dark = 80;
    public double lookCos = 0.965;
    public int lookTicks = 4;
    public String scream;                // peeker: grito al asomarse la primera vez
    public String crackSound = "stalker.run_crack";
    public float crackVol = 1f;
    public String vanishSound;
    public String hello;                 // peek1/2: saludo al asomarse
    public int stay = 1200;              // si no lo ves en este tiempo, se va solo
    public int peeksMin = 2, peeksMax = 5;
    public int holdMin = 90, holdMax = 170, waitMin = 50, waitMax = 120;
    public int byeTicks = 60;            // STATIC: se va tras esconderse
    public float yawExtra = 0f;
    public double headStand = 0;         // altura de la cara derecha (para calcular "baja" al asomarse por arriba)
    public int bajaMax = 40;
    public String followUp;              // GRIP: al gritar se transforma en este stalker y te persigue
    public int screamTicks = 36;
    public String textEs, textEn;        // texto al aparecer
    public int natMinTicks = 1200, natMaxTicks = 6000;

    public static final Map<String, PeekProfile> ALL = new HashMap<>();

    static {
        PeekProfile p = new PeekProfile();      // el que se asoma: 2-5 asomadas, por arriba o por el costado
        p.style = Style.PEEKER;
        p.wallOnly = true;
        p.range = 24;
        p.minDist = 8;
        p.behind = 0.9;
        p.slide = 0.9;
        p.inTicks = 32;
        p.sideFase = 3;
        p.topFase = 1;
        p.downFase = 2;
        p.headLat = 0.95;
        p.headUp = 2.27;
        p.near = 6;
        p.dark = 60;
        p.lookTicks = 60;
        p.lookCos = 0.95;
        p.scream = "stalker.peeker_scream";
        p.crackSound = "stalker.run_crack_1";
        p.crackVol = 0.12f;
        p.vanishSound = "stalker.peeker_vanish";
        p.stay = 2400;
        p.natMinTicks = 6000;
        p.natMaxTicks = 6000;
        ALL.put("stalker_peeker", p);

        for (int i = 1; i <= 2; i++) {          // peek1 / peek2: imagenes quietas tras un arbol
            p = new PeekProfile();
            p.style = Style.STATIC;
            p.treeOnly = true;
            p.range = 10;
            p.minDist = 1;
            p.behind = 0.9;
            p.slide = 0.62;
            p.inTicks = 24;
            p.headLat = 0;
            p.headUp = i == 1 ? 2.1 : 2.9;
            p.near = 3.5;
            p.dark = 60;
            p.lookCos = 0.975;
            p.lookTicks = 1;
            p.hello = "stalker.peek_hola";
            p.vanishSound = null;
            p.stay = 1800;
            p.byeTicks = 60;
            p.natMinTicks = 1200;
            p.natMaxTicks = 6000;
            ALL.put("stalker_peek" + i, p);
        }

        p = new PeekProfile();                  // RUN que se asoma
        p.style = Style.SINGLE;
        p.behind = 0.95;
        p.slide = 0.3;
        p.headLat = 0.57;
        p.headUp = 2.55;
        p.headStand = 2.708;
        p.bajaMax = 40;
        p.natMinTicks = 1200;
        p.natMaxTicks = 6000;
        ALL.put("stalker_run_asoma", p);

        p = new PeekProfile();                  // cabra que se asoma (cuello largo)
        p.style = Style.SINGLE;
        p.behind = 0.95;
        p.slide = 0.08;
        p.headLat = 1.62;
        p.headUp = 2.01;
        p.headStand = 3.22;
        p.bajaMax = 90;
        p.yawExtra = -90f;
        p.natMinTicks = 1200;
        p.natMaxTicks = 6000;
        ALL.put("stalker_goat_asoma", p);

        p = new PeekProfile();                  // SPOTTED que se asoma (misma logica que el RUN, tamano del spotted)
        p.style = Style.SINGLE;
        p.behind = 0.95;
        p.slide = 0.3;
        p.headLat = 1.2;
        p.headUp = 2.6;
        p.headStand = 2.9;
        p.bajaMax = 40;
        p.natMinTicks = 1200;
        p.natMaxTicks = 6000;
        ALL.put("stalker_spotted_asoma", p);

        p = new PeekProfile();                  // TE GUSTA LA VIDA que se asoma: solo troncos; mirarlo 3 s -> se retira; texto rojo
        p.style = Style.SINGLE;
        p.treeOnly = true;
        p.behind = 0.95;
        p.slide = 0.25;
        p.headLat = 0.645;
        p.headUp = 1.787;
        p.lookCos = 0.96;
        p.lookTicks = 60;
        p.near = 2.0;
        p.dark = 80;
        p.textEs = "\u00a74\u00a7l\u00bfTe gusta la vida?..";
        p.textEn = "\u00a74\u00a7lDo you like life?..";
        p.natMinTicks = 18000;
        p.natMaxTicks = 36000;
        ALL.put("stalker_vida_asoma", p);

        p = new PeekProfile();                  // agarrado del arbol: "no me mires"; 3 s mirandolo -> grita y se vuelve RUN
        p.style = Style.GRIP;
        p.treeOnly = true;
        p.range = 10;
        p.minDist = 1;
        p.behind = 1.0;
        p.slide = 0;
        p.headLat = 0.5;
        p.headUp = 1.8;
        p.near = 2.2;
        p.dark = 60;
        p.lookTicks = 60;
        p.lookCos = 0.975;
        p.scream = "stalker.unknown_scream";
        p.followUp = "stalker_run";
        p.screamTicks = 36;
        p.textEs = "\u00a7cno me mires";
        p.textEn = "\u00a7cdon't look at me";
        p.stay = 2400;
        p.natMinTicks = 1200;
        p.natMaxTicks = 6000;
        ALL.put("stalker_grip", p);
    }
}
