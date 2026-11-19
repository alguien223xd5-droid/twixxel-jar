package com.twixxel.stalker.entity;

import java.util.HashMap;
import java.util.Map;

/**
 * Parametros de los stalkers "de campo abierto" del addon (goat, spotted, smiler, unknown, ansiedad, hello, quad, hey).
 * Todos siguen el mismo esquema: aparecen lejos, esperan, un disparador (distancia o mirada) los activa y entonces
 * te persiguen, o desaparecen dejandote a oscuras. Los numeros salen del script original.
 */
public final class FieldProfile {
    public enum Action {CHASE, VANISH, FREEZE_TALK}

    public int[] dist;                 // distancias de aparicion (frente al jugador)
    public int stayTicks = 2400;       // se va solo si lo ignoras (0 = nunca)
    public String waitSound;           // sonido de espera (al aparecer)
    public double triggerDist = 0;     // se activa al acercarte a N bloques
    public int gazeTicks = 0;          // o al mirarlo N ticks seguidos
    public double gazeCos = 0.94;
    public Action action = Action.CHASE;
    public int darkMin = 60, darkMax = 60;  // oscuridad al desaparecer
    public int nauseaTicks = 0;
    public int preTicks = 0;           // animacion previa quieto (abrir boca, gritar...)
    public String preProp;
    public int preVal;
    public String preSound;
    public String chaseSound;
    public int chaseSoundRepeat = 0;   // cada cuantos ticks se repite (0 = una vez)
    public String runSound;            // "RUN" repetido
    public int runSoundEvery = 0;
    public String text;                // texto en pantalla durante la persecucion
    public int chaseTicks = 1200;
    public double catchDist = 2.0;
    public String runProp = "fase";
    public int runVal = 1;
    public float yawOffset = -90f;
    public int natMinTicks = 15 * 1200, natMaxTicks = 30 * 1200;   // aparicion natural
    public String followUp;            // evento que empieza al desaparecer (quad -> quad_red)
    public String freezeSound;
    public String freezeText;
    public int freezeTicks;
    public int waitRepeat = 0;         // la voz de espera se repite cada N ticks (RUN)
    public String waitText;
    public boolean natural = true;     // aparece solo
    public boolean spawnBehind = false;
    public String chaseSound2;         // segundo audio de la persecucion
    public int chaseSound2At = 0;
    public String runProp2;            // segunda propiedad al empezar a correr (screamer: run)
    public int runVal2 = 1;
    public double vanishNear = 0;      // si te acercas a N bloques antes de activarse: desaparece y te deja neblina
    public int darkAfter = 0;          // al acabar la persecucion te deja a oscuras N ticks
    public double seeCos = 0;          // miron: lo ves aunque sea un pedacito (cono muy amplio)
    public String preTextEn;           // idem en ingles (si es null se usa preText)
    public String preText;             // texto en pantalla durante la animacion previa
    public int watchTicks = 0;         // se activa solo tras N ticks de espera (o al acercarte a triggerDist)
    public boolean mineOnly = false;   // solo aparece si el jugador esta en una mina (bajo tierra)
    public boolean lightScare = false; // se va si te acercas a un bloque de luz
    public boolean spawnAround = false; // aparece a cualquier angulo (no solo delante)
    public int graceTicks = 0;         // triggerDist solo cuenta pasados N ticks de espera
    public boolean pitClimb = false;   // si te metes en un hoyo baja de cabeza a buscarte
    public boolean redlight = false;   // "luz verde, luz roja": quieto si lo miran
    public boolean darkDuring = false; // oscuridad durante toda la persecucion
    public boolean windowScene = false; // miron: en la persecucion, si miras una ventana la rompe y se asoma

    /** Un paso de la animacion previa (hablar, tronar el cuello...): quieto, con su propiedad, sonido y texto. */
    public static final class Step {
        public int ticks, ticksEn;         // duracion (ticksEn = 0: la misma)
        public String prop;
        public int val;
        public String sound, soundEn;      // sonido al empezar (soundEn = 0: el mismo)
        public String text, textEn;        // texto en pantalla (cada 10 ticks)
        public int[] crackAt = {};
        public String[] crackSnd = {};
    }

    public java.util.List<Step> steps = new java.util.ArrayList<>();
    public String vanishSound;             // VANISH: sonido al desaparecer
    public double[] gazeHeights;           // mirar a estas alturas del cuerpo (criaturas gigantes)
    public double gazeRadius = 0;          // tolerancia de la mirada en bloques (0 = usa gazeCos)
    public double gazeRange = 64;
    public double catchDy = 3;             // te alcanza aunque estes tantos bloques mas arriba
    public String textEn;                  // texto de la persecucion en ingles

    private static Step step(int ticks, int ticksEn, String prop, int val, String sound, String soundEn, String text, String textEn) {
        Step t = new Step();
        t.ticks = ticks;
        t.ticksEn = ticksEn;
        t.prop = prop;
        t.val = val;
        t.sound = sound;
        t.soundEn = soundEn;
        t.text = text;
        t.textEn = textEn;
        return t;
    }

    private static int[] range(int from, int to, int step) {
        int n = (from - to) / step + 1;
        int[] r = new int[n];
        for (int i = 0; i < n; i++) r[i] = from - i * step;
        return r;
    }

    private static int min(int m) {
        return m * 1200;
    }

    public static final Map<String, FieldProfile> ALL = new HashMap<>();

    /** Persecucion del RUN de "sal afuera": musica saf_musica 60 s, oscuridad y texto "No debiste salir". */
    public static final FieldProfile DREAM_RUN = new FieldProfile();
    public static final FieldProfile SAL_AFUERA = new FieldProfile();
    static {
        FieldProfile r = DREAM_RUN;   // RUN del sueño 4: quieto hasta que te acercas (7 bloques), truena la cabeza y te persigue; no se va solo
        r.natural = false;
        r.stayTicks = 24000;
        r.triggerDist = 7;
        r.preTicks = 48;
        r.preProp = "fase";
        r.preVal = 1;
        r.preSound = "stalker.run_crack";
        r.chaseSound = "stalker.run";
        r.chaseTicks = 1920;
        r.runVal = 2;
        r.text = "\u00a7c\u00a7lRUN";
        r.yawOffset = 0f;
    }

    static {
        FieldProfile q = SAL_AFUERA;
        q.natural = false;
        q.chaseSound = "stalker.saf_musica";
        q.chaseTicks = 1201;
        q.runVal = 2;
        q.text = "\u00a7c\u00a7lNO DEBISTE SALIR";
        q.textEn = "\u00a7c\u00a7lYOU SHOULDN'T HAVE GONE OUT";
        q.yawOffset = 0f;
        q.darkDuring = true;
        q.catchDist = 2.0;
    }

    static {
        FieldProfile p;

        p = new FieldProfile();   // CUERNOS: quieto mirandote; mirarlo 3 s -> "solo corre" y te persigue ~40 s con su musica gritando como la cabra
        p.dist = new int[]{26, 22, 30, 18, 14};
        p.stayTicks = 3 * 1200;
        p.gazeTicks = 60;
        p.gazeCos = 0.96;
        p.gazeHeights = new double[]{2.46, 1.5};
        p.gazeRange = 64;
        p.steps.add(step(44, 44, "fase", 1, "stalker.cu_voz_es", "stalker.cu_voz_en", "\u00a74\u00a7lSolo corre", "\u00a74\u00a7lJust run"));
        p.runVal = 2;
        p.chaseSound = "stalker.cu_chase";
        p.runSound = "stalker.goat_crawl_scream";
        p.runSoundEvery = 128;
        p.chaseTicks = 805;
        p.catchDist = 2;
        p.darkAfter = 60;
        p.natMinTicks = 21600;
        p.natMaxTicks = 21600;
        ALL.put("stalker_cuernos", p);

        p = new FieldProfile();   // CRANEO: quieto con su musica (60 s); a 2 bloques dobla manos y cuello, "solo corre" y te persigue 2 min en oscuridad
        p.dist = new int[]{26, 24, 22, 20, 18};
        p.stayTicks = 3 * 1200;
        p.triggerDist = 2;
        p.waitSound = "stalker.cr_musica";
        Step cr1 = step(30, 30, "fase", 1, null, null, "\u00a74\u00a7lSolo corre", "\u00a74\u00a7lJust run");
        cr1.crackAt = new int[]{8, 17, 26};
        cr1.crackSnd = new String[]{"stalker.js_crack1", "stalker.js_crack2", "stalker.js_crack3"};
        p.steps.add(cr1);
        p.steps.add(step(40, 40, "fase", 1, "stalker.cu_voz_es", "stalker.cu_voz_en", "\u00a74\u00a7lSolo corre", "\u00a74\u00a7lJust run"));
        p.runVal = 2;
        p.chaseSound = "stalker.cr_chase";
        p.chaseSoundRepeat = 457;
        p.runSound = "stalker.goat_crawl_scream";
        p.runSoundEvery = 128;
        p.chaseTicks = 2400;
        p.catchDist = 2;
        p.darkDuring = true;
        p.darkAfter = 0;
        p.natMinTicks = 18000;
        p.natMaxTicks = 36000;
        ALL.put("stalker_craneo", p);

        p = new FieldProfile();   // TREPADOR: en cuevas sale de la oscuridad detras de ti y te persigue caminando como arana con su musica (59.5 s)
        p.dist = new int[]{18, 15, 12, 20, 10};
        p.spawnBehind = true;
        p.mineOnly = true;
        p.stayTicks = 0;
        p.triggerDist = 100;
        p.runVal = 2;
        p.chaseSound = "stalker.trep_music";
        p.chaseTicks = 1190;
        p.catchDist = 2;
        p.natMinTicks = 6000;
        p.natMaxTicks = 12000;
        ALL.put("stalker_trepador", p);

        p = new FieldProfile();   // MAEBI (persecucion; la escena de salto/agarre la hace MaebiEvent): 60 s con su musica, grito de cabra y oscuridad
        p.dist = new int[]{26, 24, 22, 20, 18, 16};
        p.stayTicks = 6000;
        p.triggerDist = 7;           // si sale de un huevo: sin la escena, te persigue al acercarte
        p.natural = false;           // el natural lo maneja MaebiEvent
        p.runVal = 2;
        p.chaseSound = "stalker.mb_music";
        p.runSound = "stalker.goat_crawl_scream";
        p.runSoundEvery = 128;
        p.chaseTicks = 1200;
        p.catchDist = 2.2;
        p.darkDuring = true;
        ALL.put("stalker_maebi", p);

        p = new FieldProfile();   // THE LOCUST (huevo): aparece quieto mirandote 2.5 s, grita y te persigue; el del sueño 3 lo maneja SuenoEvent
        p.dist = new int[]{22, 20, 18, 16};
        p.natural = false;
        p.stayTicks = 1200;
        p.triggerDist = 100;
        p.steps.add(step(50, 50, "fase", 0, "stalker.goat_crawl_scream", "stalker.goat_crawl_scream", null, null));
        p.runVal = 2;
        p.runSound = "stalker.unknown_scream";
        p.runSoundEvery = 140;
        p.chaseTicks = 3600;
        p.catchDist = 1.8;
        ALL.put("the_locust", p);

        p = new FieldProfile();   // "ESCAPA DE MI...": quieto mirandote; verle la cara 3 s -> se doblan todos los huesos (crujidos), voz + texto rojo y te persigue 59.5 s con su musica
        p.dist = new int[]{24, 22, 20, 18, 16};
        p.stayTicks = 3 * 1200;
        p.gazeTicks = 60;
        p.gazeCos = 0.96;
        p.gazeHeights = new double[]{2.37, 1.42};
        p.gazeRange = 64;
        Step lc1 = step(80, 80, "fase", 1, null, null, null, null);
        lc1.crackAt = new int[]{11, 19, 27, 33, 41, 45, 49, 57, 61, 67};
        lc1.crackSnd = new String[]{"stalker.run_crack_1", "stalker.run_crack_2", "stalker.run_crack_3", "stalker.run_crack_1", "stalker.run_crack_2",
                "stalker.run_crack_3", "stalker.run_crack_1", "stalker.run_crack_2", "stalker.run_crack_3", "stalker.run_crack_1"};
        p.steps.add(lc1);
        p.steps.add(step(76, 76, "fase", 1, "stalker.lc_voz_es", "stalker.lc_voz_en", "\u00a74Escapa de m\u00ed...", "\u00a74Escape from me..."));
        p.runVal = 2;
        p.chaseSound = "stalker.lc_music";
        p.runSound = "stalker.goat_crawl_scream";
        p.runSoundEvery = 128;
        p.chaseTicks = 1190;
        p.catchDist = 2;
        p.natMinTicks = 9600;
        p.natMaxTicks = 19200;
        ALL.put("stalker_lc", p);

        p = new FieldProfile();   // NUESTRA DIVERSION (12 bloques): mirarle la cara 3 s o acercarte a 3 -> habla, truena los huesos y te persigue 94 s
        p.dist = new int[]{26, 22, 30, 18, 14};
        p.stayTicks = 0;
        p.triggerDist = 3;
        p.gazeTicks = 140;
        p.gazeCos = 0.985;
        p.gazeHeights = new double[]{11.2};
        p.gazeRange = 96;
        Step t1 = step(100, 72, "fase", 1, "stalker.diversion_voz_es", "stalker.diversion_voz_en", "\u00a74Tu sufrimiento es nuestra diversi\u00f3n", "\u00a74Your suffering is our fun");
        Step t2 = step(68, 68, "fase", 2, null, null, null, null);
        t2.crackAt = new int[]{10, 22, 34, 46};
        t2.crackSnd = new String[]{"stalker.js_crack1", "stalker.js_crack2", "stalker.js_crack3", "stalker.js_crack1"};
        p.steps.add(t1);
        p.steps.add(t2);
        p.runVal = 3;
        p.chaseSound = "stalker.diversion_music";
        p.chaseTicks = 1884;
        p.catchDist = 2.2;
        p.catchDy = 10;
        p.yawOffset = 0f;
        p.natMinTicks = min(5);
        p.natMaxTicks = min(10);
        ALL.put("stalker_diversion", p);

        p = new FieldProfile();   // SOLO CORRE: mirarlo (o acercarte a 5) -> truena el cuello, "no me mires... solo corre", abre la boca y corre 35 s
        p.dist = new int[]{26, 22, 30, 18, 34, 14};
        p.spawnAround = true;
        p.stayTicks = 3 * 1200;
        p.triggerDist = 5;
        p.gazeTicks = 140;
        p.gazeHeights = new double[]{3.6, 2.0};
        p.gazeRadius = 1.6;
        p.gazeRange = 96;
        Step c1 = step(48, 48, "fase", 1, "stalker.run_crack", null, null, null);
        Step c2 = step(90, 80, "fase", 2, "stalker.solocorre_voz_es", "stalker.solocorre_voz_en", "\u00a74\u00a7lDEJA DE MIRARME... SOLO CORRE", "\u00a74\u00a7lSTOP LOOKING AT ME... JUST RUN");
        p.steps.add(c1);
        p.steps.add(c2);
        p.runVal = 3;
        p.runProp2 = "boca";
        p.runVal2 = 1;
        p.chaseSound = "stalker.solocorre_musica";
        p.chaseSound2 = "stalker.solocorre_grito";
        p.chaseSound2At = 1;
        p.runSound = "stalker.luz_run";
        p.runSoundEvery = 160;
        p.text = "\u00a74\u00a7lCORRE CORRE CORRE";
        p.textEn = "\u00a74\u00a7lRUN RUN RUN";
        p.chaseTicks = 708;
        p.catchDist = 1.8;
        p.darkAfter = 60;
        p.yawOffset = 0f;
        p.natMinTicks = min(10);
        p.natMaxTicks = min(15);
        ALL.put("stalker_solocorre", p);

        p = new FieldProfile();   // watcher (1a vez): de pie, lejos, observandote; a 9 bloques desaparece, 10 s de oscuridad
        p.dist = new int[]{24, 22, 26, 20, 28, 18, 30};
        p.action = Action.VANISH;
        p.triggerDist = 9;
        p.darkMin = 200;
        p.darkMax = 200;
        p.vanishSound = "stalker.watcher";
        p.stayTicks = 12000;
        p.natMinTicks = min(20);
        p.natMaxTicks = min(30);
        p.natural = false;         // solo por el reloj: 20 min despues de ver el video 1 (WatcherClock)
        ALL.put("stalker_watcher", p);

        p = new FieldProfile();   // watcher (2a vez): mirarlo 7 s o acercarte a 8 bloques -> se truena la cabeza y te persigue ~34 s
        p.dist = new int[]{24, 22, 26, 20, 28, 18, 30};
        p.waitSound = "stalker.watcher_theme";
        p.triggerDist = 8;
        p.gazeTicks = 140;
        p.gazeCos = 0.95;
        p.preTicks = 48;
        p.preProp = "fase";
        p.preVal = 1;
        p.chaseSound = "stalker.watcher_chase";
        p.chaseSoundRepeat = 339;
        p.runSound = "stalker.hello_run";
        p.runSoundEvery = 60;
        p.text = "\u00a74\u00a7lCORRE";
        p.textEn = "\u00a74\u00a7lRUN";
        p.runProp = "fase";
        p.runVal = 2;
        p.chaseTicks = 678;
        p.catchDist = 1.8;
        p.stayTicks = 12000;
        p.natMinTicks = min(30);
        p.natMaxTicks = min(45);
        p.natural = false;         // 20 min despues de que el 1o desaparezca
        ALL.put("stalker_watcher2", p);

        p = new FieldProfile();   // cabra: a 15 bloques te persigue 60 s
        p.dist = range(28, 16, 2);
        p.waitSound = "stalker.goat";
        p.triggerDist = 15;
        p.chaseSound = "stalker.goat_chase";
        p.chaseSoundRepeat = 1200;
        p.chaseTicks = 1200;
        p.natMinTicks = min(5);
        p.natMaxTicks = min(10);
        ALL.put("stalker_goat", p);

        p = new FieldProfile();   // spotted: a 8 bloques dice RUN y te sigue 34.6 s
        p.dist = range(20, 10, 2);
        p.triggerDist = 8;
        p.chaseSound = "stalker.spotted_chase";
        p.chaseTicks = 693;
        p.natMinTicks = min(10);
        p.natMaxTicks = min(15);
        ALL.put("stalker_spotted", p);

        p = new FieldProfile();   // smiler: mirarlo 10 s lo activa
        p.dist = range(26, 14, 2);
        p.gazeTicks = 200;
        p.chaseSound = "stalker.smiler_chase";
        p.chaseSoundRepeat = 309;
        p.chaseTicks = 927;
        p.natMinTicks = min(15);
        p.natMaxTicks = min(20);
        ALL.put("stalker_smiler", p);

        p = new FieldProfile();   // desconocido: a 5 bloques grita (fase 2) y corre 30 s
        p.dist = new int[]{30, 31, 29, 32, 28, 33, 27, 26, 25};
        p.triggerDist = 5;
        p.preTicks = 36;
        p.preProp = "fase";
        p.preVal = 2;
        p.preSound = "stalker.unknown_scream";
        p.chaseTicks = 600;
        p.natMinTicks = min(30);
        p.natMaxTicks = min(35);
        ALL.put("stalker_unknown", p);

        p = new FieldProfile();   // ansiedad: a 5 bloques abre la cabeza y corre 46 s
        p.dist = range(20, 10, 2);
        p.waitSound = "stalker.ansiedad_spawn";
        p.triggerDist = 5;
        p.preTicks = 24;
        p.preProp = "fase";
        p.preVal = 1;
        p.chaseSound = "stalker.ansiedad_mom";
        p.chaseTicks = 923;
        p.runVal = 2;
        p.text = "§c§lRUN";
        p.yawOffset = 0f;
        p.natMinTicks = min(10);
        p.natMaxTicks = min(15);
        ALL.put("stalker_ansiedad", p);

        p = new FieldProfile();   // hello: no se va solo; a 5 bloques se dobla y te persigue 1 min
        p.dist = new int[]{16, 14, 12, 10, 8};
        p.stayTicks = 0;
        p.waitSound = "stalker.hello";
        p.triggerDist = 5;
        p.preTicks = 52;
        p.preProp = "fase";
        p.preVal = 1;
        p.chaseSound = "stalker.hello_chase";
        p.chaseSoundRepeat = 887;
        p.runSound = "stalker.hello_run";
        p.runSoundEvery = 140;
        p.text = "§c§lRUN";
        p.chaseTicks = 1200;
        p.catchDist = 1.3;
        p.runVal = 2;
        p.natMinTicks = 9000;
        p.natMaxTicks = 18000;
        ALL.put("stalker_hello", p);

        p = new FieldProfile();   // cuadrupedo blanco: sin sonido; si te acercas (4) desaparece y empieza el rojo
        p.dist = range(14, 6, 2);
        p.triggerDist = 4;
        p.action = Action.VANISH;
        p.followUp = "stalker_quad_red";
        p.natMinTicks = min(10);
        p.natMaxTicks = min(15);
        ALL.put("stalker_quad", p);

        p = new FieldProfile();   // hey: mirarlo 0.5 s -> voz, te quedas quieto, y se va dejandote a oscuras 3-11 s
        p.dist = range(18, 10, 2);
        p.gazeTicks = 10;
        p.action = Action.FREEZE_TALK;
        p.freezeSound = "stalker.hey_voice";
        p.freezeText = "§cHey human, look at this, you are next";
        p.freezeTicks = 84;
        p.darkMin = 60;
        p.darkMax = 220;
        p.natMinTicks = min(10);
        p.natMaxTicks = min(20);
        ALL.put("stalker_hey", p);

        p = new FieldProfile();   // RUN: aparece detras de ti (tras la puerta), te habla, a 7 bloques truena la cabeza y corre 96 s
        p.dist = new int[]{9, 8, 7, 6, 5, 4};
        p.natural = false;
        p.spawnBehind = true;
        p.stayTicks = 1200;
        p.waitSound = "stalker.run_hola";
        p.waitRepeat = 200;
        p.waitText = "\u00a7cCome here human, I won't do anything to you";
        p.triggerDist = 7;
        p.preTicks = 48;
        p.preProp = "fase";
        p.preVal = 1;
        p.preSound = "stalker.run_crack";
        p.chaseSound = "stalker.run";
        p.chaseTicks = 1920;
        p.runVal = 2;
        p.text = "\u00a7c\u00a7lRUN";
        p.yawOffset = 0f;
        ALL.put("stalker_run", p);

        p = new FieldProfile();   // LIGHT: solo en minas; te mira 3 s, truena el cuello y te persigue 94 s (se va con la luz)
        p.dist = new int[]{16, 14, 12, 18, 10, 20, 8};
        p.stayTicks = 6000;
        p.watchTicks = 60;
        p.triggerDist = 7;
        p.preTicks = 48;
        p.preProp = "fase";
        p.preVal = 1;
        p.preSound = "stalker.run_crack";
        p.chaseSound = "stalker.luz_chase";
        p.chaseTicks = 1884;
        p.runVal = 2;
        p.runSound = "stalker.luz_run";
        p.runSoundEvery = 160;
        p.text = "\u00a7c\u00a7lRUN";
        p.catchDist = 1.8;
        p.yawOffset = 0f;
        p.mineOnly = true;
        p.lightScare = true;
        p.natMinTicks = min(10);
        p.natMaxTicks = min(15);
        ALL.put("stalker_luz", p);

        p = new FieldProfile();   // LUZ ROJA: aparece a 30 bloques; quieto si lo miras, corre si no; te mata si te alcanza
        p.dist = new int[]{30};
        p.spawnAround = true;
        p.redlight = true;
        p.watchTicks = 2;
        p.stayTicks = 2400;
        p.chaseTicks = 1200;
        p.runProp = "run";
        p.runVal = 1;
        p.catchDist = 1.5;
        p.yawOffset = -90f;
        p.natMinTicks = 27000;
        p.natMaxTicks = 36000;
        ALL.put("stalker_redlight", p);

        p = new FieldProfile();   // evilblack (castigo): persigue lo que queda de su musica; solo lo inicia EvilblackEvent
        p.dist = new int[]{14};
        p.chaseTicks = 694;
        p.catchDist = 1.8;
        p.natural = false;
        ALL.put("evilblack", p);

        p = new FieldProfile();   // envuelto (persecucion de la ventana): 40 s, solo se mueve cuando nadie lo mira
        p.redlight = true;
        p.runProp = "cara";
        p.runVal = 1;
        p.chaseSound = "stalker.envuelto_chase";
        p.chaseTicks = 800;
        p.catchDist = 1.6;
        p.natural = false;
        ALL.put("envuelto_run", p);

        p = new FieldProfile();   // criatura gritante: audio pesado al aparecer; a 5 bloques dobla la cabeza y te sigue 1 min
        p.dist = range(30, 18, 2);
        p.waitSound = "stalker.screamer_spawn";
        p.triggerDist = 5;
        p.preTicks = 48;
        p.preProp = "fase";
        p.preVal = 1;
        p.chaseSound = "stalker.screamer_chase1";
        p.chaseSound2 = "stalker.screamer_chase2";
        p.chaseSound2At = 127;
        p.chaseTicks = 1200;
        p.runVal = 2;
        p.runProp2 = "run";
        p.catchDist = 1.7;
        p.natMinTicks = 18000;
        p.natMaxTicks = 27000;
        ALL.put("stalker_screamer", p);

        p = new FieldProfile();   // observador: quieto mirandote; a 5 bloques truena el cuello (3 crujidos) y corre 30.7 s
        p.dist = new int[]{30, 28, 26, 24, 22, 20, 18};
        p.stayTicks = 12000;
        p.triggerDist = 5;
        p.preTicks = 50;
        p.preProp = "fase";
        p.preVal = 1;
        p.preSound = "stalker.run_crack";
        p.chaseSound = "stalker.observer_chase";
        p.chaseSoundRepeat = 307;
        p.chaseTicks = 614;
        p.runVal = 2;
        p.text = "\u00a7c\u00a7lRUN";
        p.catchDist = 2.5;
        p.yawOffset = 0f;
        p.natMinTicks = min(15);
        p.natMaxTicks = min(40);
        ALL.put("stalker_observer", p);

        p = new FieldProfile();   // jester: mirarlo 3 s -> RUN y te sigue 44.3 s; si te acercas a 6 antes, desaparece
        p.dist = new int[]{22, 20, 18, 16, 14, 12};
        p.stayTicks = 6000;
        p.gazeTicks = 60;
        p.gazeCos = 0.95;
        p.vanishNear = 6;
        p.darkMin = 100;
        p.darkMax = 100;
        p.chaseSound = "stalker.jester_chase";
        p.chaseTicks = 886;
        p.catchDist = 2.0;
        p.text = "\u00a7c\u00a7lRUN";
        p.darkAfter = 100;
        p.natMinTicks = min(10);
        p.natMaxTicks = min(15);
        ALL.put("stalker_jester", p);

        p = new FieldProfile();   // GOAT JESTER: te saluda; si te acercas a 6 (pasados 2 s) truena el cuello y te persigue 44 s; si te metes en un hoyo baja de cabeza
        p.dist = new int[]{12, 11, 10, 13, 9, 14};
        p.stayTicks = 6000;
        p.triggerDist = 6;
        p.graceTicks = 40;
        p.waitSound = "stalker.hello_run";
        p.waitRepeat = 1000000;
        p.preTicks = 48;
        p.preProp = "fase";
        p.preVal = 1;
        p.preSound = "stalker.run_crack";
        p.chaseSound = "stalker.goat_jester_chase";
        p.chaseTicks = 886;
        p.runVal = 2;
        p.text = "\u00a7c\u00a7lRUN";
        p.catchDist = 2.0;
        p.darkAfter = 60;
        p.yawOffset = -90f;
        p.pitClimb = true;
        p.natMinTicks = min(10);
        p.natMaxTicks = min(15);
        ALL.put("stalker_goat_jester", p);

        p = new FieldProfile();   // solo te quiere ver: si ves aunque sea un pedacito, texto y corre 15.4 s; mirando una ventana la rompe
        p.dist = new int[]{24, 22, 20, 18, 16, 14};
        p.stayTicks = 2400;
        p.gazeTicks = 1;
        p.gazeCos = 0.62;
        p.preTicks = 30;
        p.preProp = "fase";
        p.preVal = 1;
        p.preText = "\u00a7cIgn\u00f3ralo, solo te quiere ver...";
        p.preTextEn = "\u00a7cIgnore it, it just wants to see you...";
        p.chaseSound = "stalker.smiler_chase";
        p.chaseTicks = 308;
        p.runProp = "fase";
        p.runVal = 2;
        p.runProp2 = "run";
        p.runVal2 = 1;
        p.catchDist = 1.6;
        p.windowScene = true;
        p.natMinTicks = min(10);
        p.natMaxTicks = min(15);
        ALL.put("stalker_miron", p);

        p = new FieldProfile();   // tortura (fase de persecucion): musica 46 s, corre
        p.natural = false;
        p.chaseSound = "stalker.tortura";
        p.chaseTicks = 915;
        p.runProp = "corre";
        p.runVal = 1;
        p.catchDist = 1.3;
        p.yawOffset = 0f;
        ALL.put("stalker_tortura_run", p);

        p = new FieldProfile();   // cueva (fase de persecucion): 30 s, al final truena y te deja a oscuras 3 s
        p.natural = false;
        p.chaseSound = null;
        p.chaseTicks = 600;
        p.runProp = "fase";
        p.runVal = 4;
        p.catchDist = 1.35;
        p.darkAfter = 60;
        p.yawOffset = 0f;
        ALL.put("stalker_cueva_run", p);
    }
}
