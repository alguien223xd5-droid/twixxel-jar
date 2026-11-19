package com.twixxel.stalker.entity;

import java.util.HashMap;
import java.util.Map;

/** Stalkers que aparecen en una ventana o tras un arbol y hacen una animacion al mirarlos (ventana, mujer, arbol). */
public final class ScareProfile {
    public enum Kind {WINDOW, TREE, TUNNEL}

    public Kind kind = Kind.WINDOW;
    public int[][] sizes = {{2, 2}};   // tamanos de ventana {ancho, alto}
    public String voice;
    public int repeat = 80;            // la voz se repite cada N ticks hasta que lo mires
    public int voiceTicks = 32;        // el texto se ve mientras suena
    public String text;
    public int gazeTicks = 10;
    public double gazeCos = 0.966;
    public double range = 24;
    public double eye = 1.6;           // altura de su cara para saber si lo miras
    public int animTicks = 75;
    public boolean freeze = false;     // no te puedes mover durante la animacion
    public boolean lockDoors = false;  // mientras esta, no se abren puertas
    public String breakSound;          // ventana: cristal rompiendose
    public int breakAt = 40;
    public String scarySound;          // ventana: audio aterrador al final
    public int scaryAt = 110;
    public int totalTicks = 75;
    public int darkTicks = 0;          // oscuridad al terminar
    public int stayTicks = 3600;       // si no lo miras en 3 min se va
    public String activeProp = "activo";
    public String activateSound;       // sonido al activarse (arbol)
    public String activateText;
    public int natMinTicks = 15 * 1200, natMaxTicks = 30 * 1200;

    // ---- secuencias avanzadas (ventanas que se asoman, golpean el cristal y persiguen)
    public double out = 0.9;           // distancia al cristal
    public double spawnDy = 0;         // desplazamiento vertical inicial
    public double riseFrom = 0;        // empieza N bloques mas abajo y sube durante riseTicks
    public int riseTicks = 0;          // no cuenta la mirada hasta que termina de asomarse
    public int initVal = -1;           // valor inicial de activeProp (ej. fase=1 asomado)
    public int activeVal = 1;          // valor de activeProp al activarse
    public int[] stageAt = {};         // tick (desde activarse) en que activeProp cambia...
    public int[] stageVal = {};        // ...a este valor
    public int[] hits = {};            // ticks de cada golpe al cristal
    public String[] hitSounds = {};    // sonido de cada golpe
    public boolean breakGlass = false; // rompe el cristal en breakAt (aunque no haya breakSound)
    public String shatterSound;        // sonido al romperse
    public String popSound;            // sonido extra en popAt
    public int popAt = -1;
    public int damageAt = -1;          // salta sobre ti y te hace dano
    public float damage = 8f;
    public String followUp;            // al romper la ventana, este stalker aparece dentro y te persigue
    public int followAt = 0;
    public int leaveVal = -1;          // si no lo miras: activeProp pasa a este valor y se va
    public int leaveTicks = 20;
    public int freezeTicks = 0;        // congelado mas tiempo que la animacion (0 = animTicks)
    public int postAt = -1;            // tick en que desaparece y empieza la parte final (musica + oscuridad)
    public String postSound;
    public int postDark = 0;
    public int callEvery = 0;          // el stalker te llama por el chat
    public String[] callEs = {}, callEn = {};   // frases con %s = nombre del jugador (se alternan)
    public int esShift = 0;            // ticks extra de la animacion cuando la voz en espanol dura mas
    public String crackId;             // entidad de grietas del cristal (stalker_glass_crack*, stalker_glass_blood)
    public boolean gore = false;       // golpes con sonidos viscosos y sangre al reventar
    public double goatChance = 0;      // probabilidad de que la cabra se coma al stalker (run_window)
    public int goatAt = 62;
    public int activateDelay = 0;      // el sonido de activacion suena N ticks despues
    public int postNausea = 0;         // nauseas al desaparecer
    public String chaseAs;             // al terminar la secuencia, la misma entidad pasa a perseguir con este FieldProfile
    public int chaseAt = 0;
    public int lungeAt = -1;           // salta hacia adentro por la ventana rota
    public boolean autoStart = false;  // la secuencia empieza sola (sin esperar la mirada)
    public String riseProp;            // hello3: la propiedad "alto" sube de 0 a 1 mientras se asoma
    public int riseDown = 34;
    public double nearGo = 0;          // si te acercas a N bloques antes del final, empieza la persecucion ya
    public int runEvery = 0, runN = 0;  // tras activarse dice "corre" N veces, una cada runEvery ticks
    public String runSound, runSoundEn, runTextEs, runTextEn;
    public boolean selfGrieta = false; // las grietas son la propiedad "grieta" de la propia entidad
    public boolean followOut = false;  // el followUp aparece afuera de la ventana
    public boolean leaveChase = false; // si no lo miras en stayTicks, se convierte en el followUp
    public int[] aguAt = {};           // tirones de la cabeza por el agujero (sonido run_crack)
    public double nearVanish = 0;      // si te acercas a N bloques desaparece y te deja a oscuras (nearDark ticks)
    public int nearDark = 0;
    public boolean askVida = false;    // al mirarlo/acercarte pregunta "te gusta la vida": Si se agacha y se va, No rompe la ventana y persigue
    public double askNear = 4;
    public boolean natural = true;     // false: solo aparece encadenado (run_peek antes del stalker de la puerta)

    public static final Map<String, ScareProfile> ALL = new HashMap<>();

    static {
        ScareProfile p = new ScareProfile();   // ventana 2x2: mirarlo -> animacion, cristal se rompe, audio aterrador
        p.kind = Kind.WINDOW;
        p.sizes = new int[][]{{2, 2}};
        p.voice = "stalker.window_voice";
        p.text = "§cGO OUTSIDE";
        p.freeze = true;
        p.animTicks = 75;
        p.breakSound = "stalker.window_break";
        p.scarySound = "stalker.window_scary";
        p.totalTicks = 110 + 193;
        ALL.put("stalker_window", p);

        p = new ScareProfile();                // mujer de la ventana: ventana 1x1 o 3x3, cierra las puertas
        p.kind = Kind.WINDOW;
        p.sizes = new int[][]{{1, 1}, {3, 3}};
        p.voice = "stalker.woman_voice";
        p.repeat = 50;
        p.voiceTicks = 31;
        p.text = "§cgo outside";
        p.gazeCos = 0.95;
        p.eye = 1.5;
        p.lockDoors = true;
        p.animTicks = 93;
        p.totalTicks = 93;
        ALL.put("stalker_woman", p);

        p = new ScareProfile();                // arbol: lo miras (o te acercas a 3) y te hace la animacion
        p.kind = Kind.TREE;
        p.voice = null;
        p.activateSound = "stalker.tree_voice";
        p.activateText = "§cDON'T LOOK AT ME";
        p.gazeCos = 0.985;
        p.gazeTicks = 1;
        p.animTicks = 169;
        p.totalTicks = 169;
        p.darkTicks = 60;
        p.stayTicks = 2400;
        ALL.put("stalker_tree", p);

        p = new ScareProfile();                // ignore: ventana 2x2, audio en bucle; al mirarlo suena la musica y te quedas quieto
        p.kind = Kind.WINDOW;
        p.sizes = new int[][]{{2, 2}};
        p.voice = "stalker.ignore_voice";
        p.repeat = 127;
        p.voiceTicks = 0;
        p.eye = 1.1;
        p.activeProp = "fase";
        p.activateSound = "stalker.ignore_music";
        p.activateText = "\u00a7cIgnore him, he's just looking at you";
        p.freeze = true;
        p.lockDoors = true;
        p.animTicks = 129;
        p.totalTicks = 129;
        p.natMinTicks = 6000;
        p.natMaxTicks = 6000;
        ALL.put("stalker_ignore", p);

        p = new ScareProfile();                // cabra de la ventana 1x1: "Come with me"; si la miras se va a los 2 s
        p.kind = Kind.WINDOW;
        p.sizes = new int[][]{{1, 1}};
        p.voice = "stalker.goat_window_voice";
        p.repeat = 80;
        p.voiceTicks = 32;
        p.text = "\u00a7cCome with me";
        p.gazeTicks = 10;
        p.gazeCos = 0.966;
        p.eye = 0.7;
        p.animTicks = 40;
        p.totalTicks = 40;
        p.natMinTicks = 9000;
        p.natMaxTicks = 18000;
        ALL.put("stalker_goat_window", p);

        p = new ScareProfile();                // cabeceador: 3 cabezazos agrietan, el 4o rompe; luego musica y oscuridad
        p.kind = Kind.WINDOW;
        p.sizes = new int[][]{{2, 2}};
        p.eye = 1.0;
        p.out = 0.9;
        p.activateSound = "stalker.wb_video";         // el sonido del VIDEO (antes wb_break_seq)
        p.freeze = true;
        p.lockDoors = true;
        p.breakGlass = true;
        p.hits = new int[]{7, 42, 81};
        p.hitSounds = new String[]{"stalker.wb_crack1", "stalker.wb_crack2", "stalker.wb_crack3"};
        p.breakAt = 138;                              // en el video el vidrio revienta a los 8.5 s
        p.postAt = 162;                               // el video se pone negro a los 9.7 s
        p.postSound = "stalker.wb_music";
        p.postDark = 346;
        p.freezeTicks = 468;
        p.animTicks = 162;
        p.totalTicks = 468;
        p.stayTicks = 2400;
        p.natMinTicks = 3600;
        p.natMaxTicks = 9000;
        ALL.put("stalker_windowbreak", p);

        p = new ScareProfile();                // sufrimiento: gigante que sube tras la ventana, habla y se agacha
        p.kind = Kind.WINDOW;
        p.sizes = new int[][]{{2, 2}};
        p.activeProp = "fase";
        p.initVal = 0;
        p.activeVal = 1;
        p.riseTicks = 32;
        p.spawnDy = -3.6;
        p.eye = 4.63;
        p.range = 40;
        p.gazeTicks = 8;
        p.gazeCos = 0.95;
        p.activateSound = "stalker.sufrimiento_voice";
        p.stageAt = new int[]{96};
        p.stageVal = new int[]{2};
        p.animTicks = 96;
        p.totalTicks = 122;
        p.stayTicks = 2400;
        p.leaveVal = 2;
        p.leaveTicks = 26;
        p.esShift = 21;
        p.natMinTicks = 24000;
        p.natMaxTicks = 30000;
        ALL.put("stalker_sufrimiento", p);

        p = new ScareProfile();                // spotted de la ventana: se asoma, golpea 4 veces y entra a perseguirte
        p.kind = Kind.WINDOW;
        p.sizes = new int[][]{{2, 2}};
        p.activeProp = "fase";
        p.initVal = 1;
        p.activeVal = 2;
        p.out = 0.62;
        p.spawnDy = 0;
        p.riseFrom = 2.2;
        p.riseTicks = 28;
        p.eye = 1.3;
        p.gazeTicks = 12;
        p.activateSound = "stalker.peeker_scream";
        p.hits = new int[]{12, 26, 40};
        p.hitSounds = new String[]{"stalker.wb_crack1", "stalker.wb_crack2", "stalker.wb_crack3"};
        p.breakGlass = true;
        p.breakAt = 54;
        p.shatterSound = "stalker.wb_shatter";
        p.crackId = "stalker_glass_crack6";
        p.followUp = "stalker_spotted";
        p.followAt = 54;
        p.leaveVal = 3;
        p.leaveTicks = 20;
        p.animTicks = 62;
        p.totalTicks = 62;
        p.stayTicks = 2400;
        p.natMinTicks = 18000;
        p.natMaxTicks = 24000;
        ALL.put("stalker_spotted2", p);

        p = new ScareProfile();                // cabra malvada de la ventana: 3 golpes con sangre, revienta y te persigue
        p.kind = Kind.WINDOW;
        p.sizes = new int[][]{{2, 2}};
        p.activeProp = "fase";
        p.initVal = 1;
        p.activeVal = 2;
        p.out = 0.6;
        p.riseTicks = 28;
        p.eye = 1.5;
        p.gazeTicks = 12;
        p.hits = new int[]{20, 44, 68};
        p.hitSounds = new String[]{"stalker.wb_crack1", "stalker.wb_crack2", "stalker.wb_crack3"};
        p.popAt = 92;
        p.popSound = "stalker.run_crack";
        p.breakGlass = true;
        p.breakAt = 100;
        p.shatterSound = "stalker.wb_shatter";
        p.crackId = "stalker_glass_blood";
        p.gore = true;
        p.followUp = "stalker_goatevil";
        p.followAt = 100;
        p.leaveVal = 3;
        p.leaveTicks = 20;
        p.animTicks = 108;
        p.totalTicks = 108;
        p.stayTicks = 2400;
        p.natMinTicks = 18000;
        p.natMaxTicks = 24000;
        ALL.put("stalker_goatevil_window", p);

        p = new ScareProfile();                // RUN de la ventana: te llama, rompe el cristal, salta, cae, se para y te persigue
        p.kind = Kind.WINDOW;
        p.sizes = new int[][]{{2, 2}};
        p.activeProp = "fase";
        p.initVal = 0;
        p.activeVal = 1;
        p.eye = 1.5;
        p.freeze = true;
        p.lockDoors = true;
        p.freezeTicks = 82;
        p.callEvery = 100;
        p.callEs = new String[]{"ven aqu\u00ed, %s", "%s... ven aqu\u00ed", "ven aqu\u00ed %s, te estoy viendo", "\u00e1breme, %s"};
        p.callEn = new String[]{"come here, %s", "%s... come here", "come here %s, I can see you", "let me in, %s"};
        p.crackId = "stalker_glass_crack";
        p.goatChance = 0.40;
        p.hits = new int[]{20, 44, 68};
        p.hitSounds = new String[]{"stalker.wb_crack1", "stalker.wb_crack2", "stalker.wb_crack3"};
        p.breakGlass = true;
        p.breakAt = 82;
        p.shatterSound = "stalker.wb_shatter";
        p.stageAt = new int[]{82, 92, 132};
        p.stageVal = new int[]{2, 3, 4};
        p.damageAt = 90;
        p.damage = 8f;
        p.followUp = "stalker_run";
        p.followAt = 156;
        p.animTicks = 156;
        p.totalTicks = 158;
        p.stayTicks = 3000;
        p.natMinTicks = 6000;
        p.natMaxTicks = 18000;
        ALL.put("stalker_run_window", p);

        p = new ScareProfile();                // cabeza del RUN en una ventana 1x1 (antes del stalker de la puerta): miralo 3 s y se agacha
        p.kind = Kind.WINDOW;
        p.sizes = new int[][]{{1, 1}};
        p.activeProp = "fase";
        p.initVal = 0;
        p.activeVal = 1;
        p.out = 0.62;
        p.eye = 1.3;
        p.range = 26;
        p.gazeTicks = 60;
        p.gazeCos = 0.95;
        p.animTicks = 24;
        p.totalTicks = 24;
        p.stayTicks = 2400;
        p.natural = false;
        ALL.put("stalker_run_peek", p);

        p = new ScareProfile();                // hola 2: saluda, agrieta el vidrio a manotazos 3 veces y lo rompe; deja nauseas y oscuridad
        p.kind = Kind.WINDOW;
        p.sizes = new int[][]{{2, 2}};
        p.activeProp = "fase";
        p.initVal = 0;
        p.activeVal = 1;
        p.out = 0.65;
        p.spawnDy = 1.1 - 3.75;
        p.eye = 3.75;
        p.gazeTicks = 12;
        p.activateSound = "stalker.hello2_voice";
        p.activateDelay = 6;
        p.stageAt = new int[]{40};
        p.stageVal = new int[]{2};
        p.hits = new int[]{56, 70, 84};
        p.hitSounds = new String[]{"stalker.wb_crack1", "stalker.wb_crack2", "stalker.wb_crack3"};
        p.crackId = "stalker_glass_crack5";
        p.breakGlass = true;
        p.breakAt = 98;
        p.shatterSound = "stalker.wb_shatter";
        p.postAt = 98;
        p.postDark = 200;
        p.postNausea = 200;
        p.animTicks = 98;
        p.totalTicks = 100;
        p.stayTicks = 3600;
        p.natMinTicks = 6000;
        p.natMaxTicks = 12000;
        ALL.put("stalker_hello2", p);

        p = new ScareProfile();                // tortura: agarrado de la ventana, 4 cabezazos, entra y te persigue 46 s
        p.kind = Kind.WINDOW;
        p.sizes = new int[][]{{2, 2}};
        p.activeProp = "activo";
        p.initVal = 0;
        p.activeVal = 1;
        p.out = 0.7;
        p.spawnDy = 1.0 - 3.431;
        p.eye = 3.431;
        p.gazeTicks = 12;
        p.hits = new int[]{24, 44, 64};
        p.hitSounds = new String[]{"stalker.wb_crack1", "stalker.wb_crack2", "stalker.wb_crack3"};
        p.crackId = "stalker_glass_crack4";
        p.breakGlass = true;
        p.breakAt = 84;
        p.shatterSound = "stalker.wb_shatter";
        p.lungeAt = 90;
        p.chaseAs = "stalker_tortura_run";
        p.chaseAt = 148;
        p.freeze = true;
        p.freezeTicks = 90;
        p.lockDoors = true;
        p.animTicks = 148;
        p.totalTicks = 150;
        p.stayTicks = 3600;
        p.natMinTicks = 9600;
        p.natMaxTicks = 19200;
        ALL.put("stalker_tortura", p);

        p = new ScareProfile();                // cueva: se asoma por una esquina del tunel, saca la mano y viene por ti
        p.kind = Kind.TUNNEL;
        p.activeProp = "fase";
        p.initVal = 0;
        p.activeVal = 0;
        p.autoStart = true;
        p.stageAt = new int[]{10, 80, 150};
        p.stageVal = new int[]{1, 2, 3};
        p.nearGo = 1.6;
        p.chaseAs = "stalker_cueva_run";
        p.chaseAt = 174;
        p.animTicks = 174;
        p.totalTicks = 176;
        p.stayTicks = 600;
        p.natMinTicks = 6000;
        p.natMaxTicks = 12000;
        ALL.put("stalker_cueva", p);

        p = new ScareProfile();                // hello 3 (la visita): se asoma por la ventana de dia; "Vendre esta noche"
        p.kind = Kind.WINDOW;
        p.sizes = new int[][]{{2, 2}};
        p.riseProp = "alto";
        p.riseTicks = 40;
        p.riseDown = 34;
        p.out = 0.65;
        p.spawnDy = 1.0 - 6.2;
        p.eye = 6.2;
        p.range = 40;
        p.gazeTicks = 10;
        p.gazeCos = 0.95;
        p.stayTicks = 3600;
        p.natural = false;
        ALL.put("stalker_hello3", p);

        p = new ScareProfile();                // goat ventana 2: "hey" hasta que lo mires, 4 "corre", 3 grietas, rompe y se vuelve el goat
        p.kind = Kind.WINDOW;
        p.sizes = new int[][]{{1, 1}};
        p.activeProp = "fase";
        p.initVal = 0;
        p.activeVal = 1;
        p.out = 0.6;
        p.voice = "stalker.goat_v2_hey";
        p.repeat = 70;
        p.voiceTicks = 30;
        p.text = "\u00a74Hey";
        p.gazeTicks = 10;
        p.gazeCos = 0.95;
        p.range = 28;
        p.eye = 0.6;
        p.runEvery = 60;
        p.runN = 4;
        p.runSound = "stalker.hello_run_es";
        p.runSoundEn = "stalker.hello_run";
        p.runTextEs = "\u00a74\u00a7lCORRE";
        p.runTextEn = "\u00a74\u00a7lRUN";
        p.stageAt = new int[]{240, 340};
        p.stageVal = new int[]{2, 3};
        p.selfGrieta = true;
        p.hits = new int[]{264, 288, 312};
        p.hitSounds = new String[]{"stalker.wb_crack1", "stalker.wb_crack2", "stalker.wb_crack3"};
        p.breakGlass = true;
        p.breakAt = 332;
        p.shatterSound = "stalker.wb_shatter";
        p.aguAt = new int[]{358, 362, 367, 396, 401};
        p.followUp = "stalker_goat";
        p.followOut = true;
        p.followAt = 428;
        p.leaveChase = true;
        p.animTicks = 430;
        p.totalTicks = 432;
        p.stayTicks = 1200;
        p.natMinTicks = 12000;
        p.natMaxTicks = 18000;
        ALL.put("stalker_goat_ventana2", p);

        p = new ScareProfile();                // STALKER REAL 2 (cabra): agarrado de la ventana te llama por tu nombre; si lo miras rompe el cristal a cabezazos, se lanza (3 corazones), cae, se para y es el goat
        p.kind = Kind.WINDOW;
        p.sizes = new int[][]{{2, 2}};
        p.activeProp = "fase";
        p.initVal = 0;
        p.activeVal = 1;
        p.out = 0.7;
        p.eye = 1.0;
        p.gazeTicks = 12;
        p.gazeCos = 0.95;
        p.range = 28;
        p.callEvery = 70;
        p.callEs = new String[]{"%s...", "\u00e1breme, %s", "ven aqu\u00ed, %s", "%s, \u00e1breme la ventana", "te estoy viendo, %s", "solo quiero hablar, %s", "d\u00e9jame entrar, %s"};
        p.callEn = new String[]{"%s...", "open up, %s", "come here, %s", "%s, open the window", "I can see you, %s", "I just want to talk, %s", "let me in, %s"};
        p.hits = new int[]{16, 36, 56};
        p.hitSounds = new String[]{"stalker.wb_crack1", "stalker.wb_crack2", "stalker.wb_crack3"};
        p.crackId = "stalker_glass_crack";
        p.breakGlass = true;
        p.breakAt = 76;
        p.shatterSound = "stalker.wb_shatter";
        p.lungeAt = 84;
        p.stageAt = new int[]{84, 96, 136};
        p.stageVal = new int[]{2, 3, 4};
        p.damageAt = 96;
        p.damage = 6f;
        p.freeze = true;
        p.freezeTicks = 84;
        p.followUp = "stalker_goat";
        p.followAt = 160;
        p.animTicks = 160;
        p.totalTicks = 162;
        p.stayTicks = 3600;
        p.natMinTicks = 7200;
        p.natMaxTicks = 18000;
        ALL.put("stalker_goat_real2", p);

        p = new ScareProfile();                // tipo timido de la ventana: mirarlo -> rasga el cristal 12 s (3 grietas), lo rompe y te deja a oscuras
        p.kind = Kind.WINDOW;
        p.sizes = new int[][]{{2, 2}};
        p.out = 0.7;
        p.eye = 1.0;
        p.gazeTicks = 12;
        p.gazeCos = 0.95;
        p.nearVanish = 2;
        p.nearDark = 200;
        p.activateSound = "stalker.smiler_ventana_rompe";
        p.crackId = "stalker_glass_crack7";
        p.hits = new int[]{62, 124, 186};
        p.breakGlass = true;
        p.breakAt = 247;
        p.shatterSound = "stalker.wb_shatter";
        p.darkTicks = 200;
        p.animTicks = 248;
        p.totalTicks = 249;
        p.stayTicks = 3600;
        p.natMinTicks = 18000;
        p.natMaxTicks = 60000;
        ALL.put("stalker_smiler_ventana", p);

        p = new ScareProfile();                // te gusta la vida (ventana): se asoma, pregunta Si/No; No = 3 cabezazos, rompe y persigue
        p.kind = Kind.WINDOW;
        p.sizes = new int[][]{{2, 2}};
        p.activeProp = "fase";
        p.initVal = 1;
        p.activeVal = 2;
        p.out = 0.6;
        p.spawnDy = 1.0 - 2.776;
        p.eye = 2.776;
        p.riseFrom = 1.2;
        p.riseTicks = 30;
        p.gazeTicks = 20;
        p.gazeCos = 0.95;
        p.range = 32;
        p.askVida = true;
        p.askNear = 4;
        p.hits = new int[]{16, 36, 56};
        p.hitSounds = new String[]{"stalker.wb_crack1", "stalker.wb_crack2", "stalker.wb_crack3"};
        p.crackId = "stalker_glass_crack";
        p.breakGlass = true;
        p.breakAt = 76;
        p.shatterSound = "stalker.wb_shatter";
        p.followUp = "stalker_vida";
        p.followOut = true;
        p.followAt = 84;
        p.leaveVal = 3;
        p.leaveTicks = 22;
        p.animTicks = 90;
        p.totalTicks = 92;
        p.stayTicks = 3600;
        p.natMinTicks = 1200;
        p.natMaxTicks = 12000;
        ALL.put("stalker_vida_ventana", p);

        p = new ScareProfile();                // el envuelto (ventana): mirarlo 2 s -> 3 cabezazos, abre los ojos, rompe y te persigue 40 s (solo se mueve si no lo miras)
        p.kind = Kind.WINDOW;
        p.sizes = new int[][]{{1, 2}, {2, 2}};
        p.activeProp = "cara";
        p.initVal = 0;
        p.activeVal = 0;
        p.out = 0.7;
        p.eye = 2.5;
        p.gazeTicks = 40;
        p.gazeCos = 0.95;
        p.nearVanish = 2;
        p.nearDark = 160;
        p.hits = new int[]{0, 22, 44};
        p.hitSounds = new String[]{"stalker.wb_crack1", "stalker.wb_crack2", "stalker.wb_crack3"};
        p.crackId = "stalker_glass_crack4";
        p.stageAt = new int[]{58};
        p.stageVal = new int[]{1};
        p.breakGlass = true;
        p.breakAt = 84;
        p.shatterSound = "stalker.wb_shatter";
        p.chaseAs = "envuelto_run";
        p.chaseAt = 85;
        p.animTicks = 86;
        p.totalTicks = 88;
        p.stayTicks = 3600;
        p.natMinTicks = 24000;
        p.natMaxTicks = 66000;
        ALL.put("stalker_envuelto", p);
    }
}
