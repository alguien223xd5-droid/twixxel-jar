# Parallel Stalker 3.2.0 — port a Minecraft Java (Forge 1.20.1)

Port del addon de Bedrock **"parralel stalker 3.2.0"** (twixxel) a un mod de **Forge 1.20.1** con **GeckoLib 4.4.9**.

## Cómo compilarlo

Requisitos: **JDK 17** e internet (descarga Forge y GeckoLib la primera vez).

```
./gradlew build          # Windows: gradlew.bat build
```

El mod queda en `build/libs/twixxel-3.2.0.jar`. Ponlo en la carpeta `mods/` junto con
**Forge 1.20.1 (47.x)** y **GeckoLib 4.4.9+ para 1.20.1** (descárgalo de CurseForge/Modrinth).

Para probarlo en desarrollo: `./gradlew runClient`. También puedes abrir la carpeta con IntelliJ IDEA o Eclipse.

> ⚠️ Este proyecto se generó en un entorno sin acceso a los repositorios de Maven, así que **no se pudo compilar ni
> probar el juego**. La conversión de assets sí se validó (todos los modelos, texturas, animaciones y sonidos
> referenciados existen y los JSON son válidos) y el evaluador de Molang está probado, pero si Gradle da algún
> error de compilación (sobre todo por diferencias de versión de GeckoLib), dímelo y se ajusta.

## Qué se portó

**Todo el contenido visual y sonoro (automático, `tools/convert_addon.py`):**
- 69 entidades registradas (`twixxel:stalker_run`, `twixxel:stalker_window`, etc. — mismos ids que en Bedrock, así que `/summon` funciona igual)
- 88 modelos (.geo.json) y todas las animaciones (con sus expresiones Molang)
- Máquinas de animación de Bedrock (`animation_controllers`) convertidas: cada entidad cambia de animación según sus propiedades `twixxel:*` (fase, run, mov, lado, rompe…)
- Texturas, variantes de textura/modelo (boca abierta, grietas del cristal, lado izquierdo/derecho…)
- 123 sonidos/músicas (`sounds.json`), huevos de aparición con sus iconos, traducciones EN/ES (es_es, es_mx)
- Objetos: Zoom (con su receta de 3 palos), Mascot, Pet 2, Pet 3 (sin función especial, solo el objeto)

**Comportamiento (base jugable):**
- **Stalker normal (`stalker_bipedal`)**: aparece cada 15–30 min delante de ti (12→6 bloques, sobre pasto/tierra/arena), te mira siempre, suena su música; si te acercas a 2 bloques desaparece y te deja 3 s a oscuras (darkness + blindness); se va solo a los 2 min.
- **Evils (`stalker_goatevil`, `stalker_evil`, `stalker_quad_red`)**: cuenta regresiva de 21 s ("It is N blocks away from you"), luego aparece a 5 bloques detrás de ti y te persigue 1 min a ~5.6 bloques/s; si te toca, te mata. Los evils naturales alternan goatevil → evil cada 23–30 min. Si se atascan 7 s reaparecen detrás de ti.
- **Tanda 2 (campo abierto):** `stalker_goat` (a 15 bloques te persigue 60 s), `stalker_spotted` (a 8 bloques, 34 s), `stalker_smiler` (mirarlo 10 s lo activa), `stalker_unknown` (a 5 bloques grita y corre 30 s), `stalker_ansiedad` (a 5 bloques abre la cabeza, corre 46 s), `stalker_hello` (a 5 bloques se dobla y te persigue 1 min), `stalker_quad` (a 4 bloques desaparece, oscuridad y empieza el quad rojo) y `stalker_hey` (mirarlo te deja quieto mientras habla, luego oscuridad 3-11 s). Aparecen solos con los tiempos del script y se configuran en `entity/FieldProfile.java`.
- **Tanda 3:** `stalker_door` (puerta cerrada a tu derecha, "GO OUTSIDE", mirarla te congela 7.4 s y luego tienes 1 min para salir) y `stalker_run` (aparece tras salir a cielo abierto, truena la cabeza a 7 bloques y te persigue 96 s).
- **Tanda 4:** `stalker_window` (ventana de cristal 2x2: mirarlo, se rompe el cristal), `stalker_woman` (ventana 1x1 o 3x3, bloquea las puertas) y `stalker_tree` (detras de un arbol: mirarlo o acercarte a 3 bloques).
- **Tanda 5:** `stalker_ignore` (ventana 2x2, hay que ignorarlo; bloquea puertas) y `stalker_screamer` (aparece lejos, te persigue con dos sonidos de persecución).
- **Tanda 6:** variantes de ventana: `stalker_goat_window` (1x1, "Come with me"), `stalker_windowbreak` (cabezazos, rompe el cristal, musica y oscuridad), `stalker_sufrimiento` (gigante que sube), `stalker_spotted2` y `stalker_goatevil_window` (golpean el cristal y entran a perseguirte) y `stalker_run_window` (te llama por chat, salta, te quita 4 corazones y te persigue como RUN). Faltan: grietas/sangre en el cristal y la escena en que la cabra se come al RUN.
- **Tanda 7:** `stalker_observer` (quieto, a 5 bloques truena el cuello y corre), `stalker_arriba` ("No mires arriba": te quita 4 corazones), los que se asoman (`stalker_peeker`, `peek1`, `peek2`, `run_asoma`, `goat_asoma`: se esconden tras arboles o paredes de bloques puestos — tablas, ladrillos, cemento, lana, adoquin — y se asoman) y `stalker_run_peek` (cabeza del RUN en una ventana 1x1 antes del stalker de la puerta). Diferencias: los escondites se buscan entre bloques "de construccion" (no se recuerdan los bloques exactos que pusiste).
- **Retoques (pendientes de tandas 6 y 7):** grietas y sangre en el cristal (`stalker_glass_crack`, `crack6`, `glass_blood`) que cambian con cada golpe; sangre y sonidos viscosos en `goatevil_window`; escena de la cabra que se come al RUN en `run_window` (40 %); frases de `run_window` por chat (4, es/en); `baja` al asomarse por arriba (`run_asoma`, `goat_asoma`); el peeker solo se esconde tras bloques que puso un jugador (se guardan en el mundo); idioma de voces es/en (audios `_es`). La niebla de `windowbreak` se hace con Darkness + Blindness (Java no tiene el comando fog).
- **Tanda 8:** `stalker_jester` (mirarlo 3 s, te sigue 44 s), `stalker_hello2` (saluda y rompe la ventana a manotazos), `stalker_vida` ("¿Te gusta la vida?" con botones Sí/No en el chat; No = persecución y muerte), `stalker_grip` (agarrado del árbol, 3 s mirándolo y se vuelve RUN), `stalker_door2` ("No te muevas": si te mueves te quita 9 corazones) y `stalker_miron` (te sigue al verlo; si miras una ventana en la persecución la rompe y se asoma: "NO TE ESCONDAS"). Nota: miron aparece en campo abierto, no tras bloques.
- **Tanda 9 (parte 1):** `stalker_tortura` (agarrado de la ventana, 4 cabezazos, entra y te persigue 46 s), `stalker_cueva` (se asoma por una esquina de tunel, saca la mano y viene encorvado o a gatas), `stalker_hole` (cabeza asomada en un pozo con su musica de 60 s) y `stalker_agujero` (invisible en un tunel 2x2 hasta que lo miras). Aproximaciones: la caida/levantada de tortura es una pausa, y los pozos/tuneles se detectan por forma (no hay las variantes "dentro del pozo").
- **Tanda 9 (parte 2):** `stalker_hello3` (ventana 2x2 de dia, sube, te mira y dice "Vendre esta noche") + visita cada 2 dias (`HelloVisit`): de noche toca la puerta 3 rondas de 3 golpes; despues, al intentar dormir sale gateando de debajo de la cama, se para y te persigue como el HELLO. Pruebas: `/twixxel event stalker_hello3`, `hello_noche`, `hello_cama`. **Pendiente:** las entidades `stalker_bed`, `bedscene` y `run_bedpeek` (aun no portadas; la escena de cama usa el HELLO normal con animaciones de gateo/parado).
- **Tanda 10 (parte 1):** `stalker_bed` (`BedStalker`): de noche, al intentar dormir, 10 % por noche de que no te deje; el RUN sale arrastrandose de debajo de la cama (3 fases: quieto 1.5 s, gatea 3.5 s, se para 1.2 s), dice "Estoy saliendo de tu cama", truena el cuello y te persigue. Prueba: `/twixxel event stalker_bed` junto a una cama.
- **Tanda 10 (parte 2):** esconderse DEBAJO DE LA CAMA (`BedHide`): agachado + clic derecho en una cama (o clic en una cama mientras te persiguen). Camara a ras del suelo bajo la cama (soporte invisible + paquete de camara de espectador); salir: saltar, o soltar y volver a agacharte. Si ningun stalker te vio meterte te dejan en paz (siguen a otro jugador o se quedan quietos); si alguno te vio va a la cama y hace su escena: `stalker_run_bedpeek` (RUN: manos bajo el borde, cabeza dada vuelta, grito, embestida) y `stalker_bedscene` (13 titeres: lado, colgado, gatear, atras, glitch, aplastado, golpes, doblar, luces); los demas te sacan a rastras. Aproximaciones: el fundido rojo/negro es un Blindness corto, la camara se actualiza cada ~3 ticks (limite de las entidades), el `luz roja` solo tiene su escena si ya existe ese stalker.
- **Tanda 11:** `stalker_redlight` (luz roja: a 30 bloques; quieto si alguien lo mira, corre si no; rompe 1 bloque cada 3 s si se atasca; te mata si te alcanza; barra "ESTA A n BLOQUES DE TI"; cada 22-30 min), `stalker_luz` (solo en minas: te mira 3 s, truena el cuello y te persigue 94 s diciendo "run"; se va si te acercas a un bloque de luz; cada 10-15 min), `stalker_life` (`LifeEvent`: colgado de cabeza en una puerta; si lo miras 3 s dice "hola" y sube, si te acercas te deja a oscuras 10 s) y `stalker_run_pasillo` (`HallwayEvent`: al acostarte, 10 % de que desde la almohada se vea una esquina de pasillo: asoma la cabeza, saca la mano y te persigue como el RUN). Pruebas: `/twixxel event stalker_redlight|stalker_luz|stalker_life|stalker_run_pasillo` (este ultimo: acuestate despues). Aproximaciones: el A* de la luz roja es la navegacion normal de Minecraft; luz usa el nivel de luz del bloque (>= 2) para "bloque de luz".
- Un solo stalker activo a la vez, con al menos 1 min entre uno y el siguiente.
- El resto de stalkers se pueden sacar con su huevo o `/summon`: están en el mundo con su modelo, textura, animación y sonidos, y los que están de pie miran al jugador más cercano.

**Comandos (nivel 2):**
- `/twixxel prop <entidades> <nombre> <valor>` — cambia una propiedad (ej. `/twixxel prop @e[type=twixxel:stalker_run,limit=1] fase 3`) para activar sus animaciones.
- `/twixxel event <bipedal|stalker_evil|stalker_goatevil|stalker_quad_red> [jugador]` — lanza un evento al momento.

Configuración en `config/twixxel-common.toml` (apagar spawns naturales, tiempos).

## Qué NO se portó todavía (lógica del script original, ~22.000 líneas)

Las cinemáticas y comportamientos propios de los demás stalkers: ventana, árbol, puerta, RUN, ignore, mujer de la ventana, criatura gritante, etc. (aparición por escenarios, bloqueo de puertas,
rotura de ventanas, jumpscares, menú de configuración, mascotas con IA, partículas de sangre y niebla). Esos stalkers
existen y se ven/animan bien, pero no tienen su IA propia: cada uno es un siguiente paso independiente.

## Estructura

- `tools/convert_addon.py` — conversor reutilizable: `python3 tools/convert_addon.py <addon_extraido> src/main/resources`
- `src/main/resources/data/twixxel/entity_defs.json` — definición de cada entidad (generada)
- `src/main/java/com/twixxel/stalker/` — mod (entidad genérica, renderer GeckoLib, evaluador Molang, spawns, comandos)

- **Tanda 12**: `stalker_puertas` (+cuerpo/mano), `stalker_vigilante`, `stalker_goat_door` (clases `DoorsEvent`, `VigilanteEvent`, `GoatDoorEvent`, ayudante `Houses`) y `stalker_goat_jester` (perfil de campo + bajada de cabeza a hoyos). Aproximaciones: la casa se detecta con techo + 2 paredes; el vigilante no cambia de lado si queda expuesto; el goat_jester no gatea por huecos de 1 bloque ni sube escaleras.
- **Tanda 13**: `PeleaEvent` (`pelea_spotted` = duelo, `pelea_run` = trio; linea de tiempo en `data/twixxel/pelea.json` copiada del addon). 20 % de las apariciones naturales de la cabra son pelea (30 % de ellas con el RUN). Pared invisible a 8 bloques, la cabra espera a que le mires la cara (o 8 s), al terminar vuelve la cabra normal. Aproximacion: la sangre usa particulas de polvo rojo y bloques de redstone (el addon tenia particulas propias).
- **Tanda 14**: `DuoFight` (la cabra mata al RUN: 10 % durante la persecucion de la cabra, 30 % al aparecer; prueba `/twixxel event goat_run`; la neblina de 10 s se aproxima con Darkness) y `JumpScare` (18 jumpscares unicos de `data/twixxel/jumpscares.json`, camara con armor stand, te mata al final; todas las muertes por stalker pasan por aqui). Aproximaciones: los fundidos de color son Blindness (negro), el equipo se guarda en NBT persistente mientras dura la escena y se devuelve antes de morir.
- **Tanda 15 (final)**: `Pets` (mascota/mascota2/mascota3: pregunta con botones de chat, aparicion a los 30/60 min, adopcion con hueso/carne de vaca/chuleta cocinadas, seguir, teletransporte, sentarse, guardar y colocar como item, Mascot pelea por ti con 5 de dano cada 5 s; comandos `/twxpet ...`), `ZoomItem` + `ZoomClient` (FOV 20), `DoorBreaker` (los stalkers que persiguen rompen puertas con su animacion, tabla `rompe_puertas.json`) y pathfinding mejorado (abren puertas, suben bloques de 1, camino de 128). Aproximaciones: menus y preguntas por chat clicable en vez de formularios; sin temblor de camara al romper puertas; sin trepar escaleras ni gatear por huecos de 1 bloque.
- **Arreglo (visibilidad de partes)**: el port ignoraba `part_visibility` de los render controllers de Bedrock (la cabra mostraba la cabeza de gatear en el torso, pelea_spotted mostraba heridas/brazo suelto, la mano de las puertas mostraba las dos manos...). Ahora `entity_defs.json` trae `partVis` para 8 entidades (`stalker_goat`, `stalker_goat_door`, `stalker_goat_asoma`, `jumpscare`, `pelea_spotted`, `pelea_run`, `stalker_puertas_mano`, `stalker_door`) y `StalkerRenderer.preRender` oculta los huesos segun las reglas.


## Idioma (es / en)
Como en el addon: al entrar por primera vez a un mundo sale en el chat "Language / Idioma" con botones [Español] [English] (se repite cada 15 s hasta elegir; la pregunta de la mascota espera a que elijas). La elección se guarda en el mundo (`twixxel_lang`) y cambia textos, voces (`*_es`) y mensajes. Se puede cambiar luego con `/twxlang es|en`. Se eliminó la opción `language` del config. El ítem de configuración de tiempos de aparición NO se porta (ya está en el creativo).

## Preguntas en pantalla
Idioma y "activar mascota" salen como pantalla con botones (paquete `twixxel:main`, `ChoiceScreen`), primero el idioma y luego la mascota; se repiten cada 15 s hasta responder. Las animaciones son las originales del addon (se revirtió la regeneración; `tools/gen_animations.py` queda solo como herramienta).

## Tanda 16 (addon 5.0.0 Infdev)
Conversor actualizado (`tools/convert_addon.py`): lee JSON con comentarios, ignora todo lo de Classic Aspect (HecXD) y vanilla (solo se portan modelos/texturas/sonidos de nuestras entidades, sonidos `stalker.*`, `mascota*`, `twixxel.*`), y soporta texturas en subcarpetas (secuencias de cuadros de `tele`, `windowbreak`).
Entidades: 114 definiciones (45 nuevas, `stalker_observer` se conserva aunque 5.0 la quitó). Las nuevas ya existen y se pueden invocar con /summon, **pero todavía sin comportamiento propio** (eventos, `calma`/`aturdido`, linterna, celular, pila, cassette, VHS, verity, armarios, mascotas 4–7): van en las siguientes tandas.

## Tanda 17 (addon 5.0.0): linterna, pila y aturdido
- Ítems `linterna`, `linterna_prendida` (20 usos de durabilidad) y `pila`. Recetas: vidrio/redstone/palo en columna = linterna; hierro + cobre = pila; pila + linterna = linterna recargada.
- Clic derecho prende la linterna 3 s (gasta 1 uso; recarga 10 s). Prendida, un bloque de luz nivel 15 te sigue. Un stalker que te persigue y toque la luz queda aturdido 3 s (propiedad `aturdido`): se congela, sus temporizadores se pausan y NO te mata (`JumpScare.kill` lo respeta). La linterna se apaga y recarga 5 s al aturdir.
- Barrita de usos en la action bar (en el addon es una imagen propia; aquí son bloques de color).
- Quitadas 822 texturas de objetos de vanilla/Classic Aspect que el conversor copiaba por error.

## Tanda 18 (addon 5.0.0): celular
- Ítem `celular` (3 lingotes de hierro). Clic derecho: menú con 6 videos (Vulnerable, Stalker, Endless, Gato, Gameplay, Video 6); al elegir sale el teléfono en medio de la pantalla con el video y su audio en bucle; usarlo otra vez lo apaga y vuelve el menú. Se apaga solo si lo sueltas o mueres. Todo del lado del cliente (`CelularClient`).
- Los cuadros (3268 de 76x72) van en 6 atlas `textures/gui/cel_<letra>.png` (64 colores, ~5 MB en total) generados con `tools/make_phone_atlas.py`.
- Pendiente: el video 1 en el addon "llama al watcher" 20 min después (se hará con el watcher).

## Tanda 19 — armarios y Verity
- 12 armarios (`armario_<madera>`), bloque de 2 de alto con puerta. Receta: 9 tablones de la madera (roble pálido: 9 troncos de abedul sin corteza, porque 1.20.1 no tiene tablón pálido). Tócalo sin agacharte para esconderte; salta o agáchate para salir.
- Se esconde igual que la cama (mismo sistema `BedHide`): si el stalker te vio entrar, abre la puerta, te saca y te mata; si no, te pierde. Dentro solo miras hacia delante (vista `armario_vista` con las 3 rendijas).
- Peluche de Verity (`verity_peluche`): úsalo para ponerlo; te sigue, al tocarlo rebota y suena su canción (19 trocitos), agachado + tocar = menú (agarrar / sentar / silenciar). No se le puede pegar.
- Generador de assets: `tools/gen_armarios.py`.

## Tanda 20 — mascotas 4–7, reloj y cabra
- Mascotas 4, 5, 6 y 7 (ítems `pet4`..`pet7`): se adoptan con carne de cerdo cocinada (la 5, con pollo crudo o cocinado y dice "gracias"); tocarla suena su audio y rebota; agachado + tocar = menú (sentar / agarrar). La 5 salvaje dice "xd" cuando te acercas a 4 bloques. Solo el dueño puede tocar su mascota.
- Reloj de las mascotas (solo si elegiste "Sí"): cada una sale UNA sola vez contando el tiempo jugado del mundo: 10, 20, 30, 40, 50, 60 y 70 min (mascota 1 a 7), con aviso en el chat. `/twxpet reiniciar` lo reinicia; `/twxpet aparecer4`..`aparecer7` las hacen salir ya.
- Todas las mascotas rebotan al tocarlas (propiedad `rebote`, ver `tools/patch_pets.py`).
- La cabra se come tu mascota (`GoatEatsPet`): si una mascota está a ≤7 bloques de una cabra, va por ella, se la come a mordiscos, le arranca la cabeza, te dice "eres el siguiente" y desaparece con ella.

## Tanda 21 — Nuestra diversión, Solo Corre y barcos
- `stalker_diversion` (12 bloques): sale sola cada 5–10 min. Mirarle la cara 3 s o acercarte a 3 bloques → dice "Tu sufrimiento es nuestra diversión", truena los huesos (4 tronidos) y te persigue 94 s con su música. Te alcanza aunque estés 10 bloques más arriba.
- `stalker_solocorre`: sale sola cada 10–15 min, lejos. Mirarlo (o acercarte a 5) → truena el cuello, "no me mires... solo corre", abre la boca y corre 35 s ("CORRE CORRE CORRE"); si te pierde te deja a oscuras 3 s. Si nadie lo mira en 3 min se va.
- Hecho con `FieldProfile` (nuevos campos: `steps`, `gazeHeights`, `gazeRadius`, `catchDy`, `textEn`). Prueba: `/summon twixxel:stalker_diversion` o el huevo.
- Los stalkers rompen los barcos que tocan o en los que se suben (`BoatBreaker`).
- Aproximación: la persecución usa la navegación común de campo abierto (no el pathfinding propio del addon que trepaba paredes y se arrastraba por huecos de 1x1).

## Tanda 22 — Alcantarillas
- `Alcantarilla`: cada 5–10 min, si hay una trampilla con hueco detrás a ≤14 bloques, sale de ahí el RUN (`stalker_alcantarilla`) o la cabra (`stalker_alcantarilla_goat`).
  - Trampilla en el piso: el 1.er intento de abrirla no abre ("Sientes que hay algo abajo..."); el 2.º la abre, saca las manos, asoma la cabeza, sube y se vuelve el RUN/cabra que te persigue (la cabra dice "CORRE").
  - Trampilla en el techo: la abre sola si estás debajo a ≤5 bloques, asoma media cabeza, la mano, y baja a perseguirte.
  - Se va si la ignoras 3 min o te alejas 32 bloques. La trampilla no se puede romper mientras tanto.
  - Prueba: `/twxalcantarilla [run|goat]` (op) o el huevo cerca de una trampilla.
- Aún no hecho: que los stalkers que te siguen abran y cierren alcantarillas (v3.1.13).

## Tanda 23
- `AsomandomeEvent`: stalker_asomandome (se asoma por un costado, se acerca y se vuelve RUN). `/twxasomandome`.
- `EscaleraEvent`: stalker_escalera, cuelga del techo al final de una escalera (solo minas, 1-5 min). Mirarlo 3 s o acercarse -> se sacude y se vuelve RUN. `/twxescalera` o huevo (funciona fuera de minas).

## Tanda 24 (texturas de huevos)
- 53 texturas de `textures/item/eggs` eran casi todo negro sobre transparente (se veian como mancha negra en el inventario): se les agrego un fondo circular gris con borde claro. Originales en tools/eggs_orig.zip no incluidos (tamano); se regeneran con convert_addon.py.

## Tanda 25
- `stalker_goat_ventana2` (ScareProfile + campos runEvery/runN/selfGrieta/followOut/leaveChase/aguAt). Natural 10-15 min con ventana 1x1.
- Fix GeckoLib: keyframes duplicados ("0" y "0.00") unificados en todas las animaciones; `twx_verityball.geo` sin poly_mesh (4 cubos rotados que simulan la bola).
- Molang: `rotation_to_camera(0/1)`, `body_y_rotation`, `distance_from_camera` -> variables `query.twx_cam_pitch/yaw/dist`, `query.twx_body_yaw` (las fija StalkerRenderer).

## Tanda 25b (Verity)
- Verity usa la malla real (`assets/twixxel/verity_mesh.json`, convertida del poly_mesh) dibujada por `StalkerRenderer.renderCubesOfBone` en el hueso `ball`; el geo conserva los cubos como respaldo si la malla no carga.

## Tanda 26
- `stalker_smiler_ventana` (ScareProfile + nearVanish/nearDark). Verity: v de la malla invertida (estaba boca abajo).
- Tanda 27: `stalker_vida_ventana` (ScareProfile.askVida: pregunta Si/No por chat; Si = se agacha, No = 3 cabezazos, rompe y se vuelve el stalker_vida persiguiendo).
- Tanda 28: watcher (1a vez, VANISH a 9 bloques) y watcher2 (persecucion). Aproximacion: sin el reloj de 20 min del video 1 del celular; ambos salen solos con su propio temporizador.
- Tanda 29: envuelto (ventana: ScareProfile + FieldProfile envuelto_run redlight; se acerca: EnvueltoAcerca /twxenvuelto), run_mina (MinaRunEvent /twxrunmina, animacion por prop fase en entity_defs).
- Tanda 30: evilblack (EvilblackEvent: 30 animales -> castigo; /twxevilblack).
- Tanda 31: stalker_cuernos (FieldProfile, cada 18 min).

## Tanda 32: PATHFINDING DEL ADDON (ChaseNav)
- `entity/ChaseNav.java`: port de findPathLow/twxAdvance/twxLowCheck del main.js: A* (1400 nodos, replan cada 12 ticks) con escaleras y enredaderas, trepar paredes (costo 2.6),
  parkour (saltos 2-4), gateo por huecos de 1, nadar, bajar hasta 6, trampillas (las abre al pasar y las cierra, no las pisa). Mueve la entidad con moveTo cada tick (como el teleport del addon).
- Sustituye `getNavigation().moveTo + boostAlongPath` en: chaserTick (evil/goatevil), persecucion de campo (FieldProfile), redlight, vida. Prop `mov` 0/1/2 (stalker_run: fase 7 escalar / 6 gatear).
- Tanda 32b: ChaseNav.relocate/watchdog = runRelocate del addon (>34 bloques 2 s o atascado 2x2 s -> reaparece detras; no si estas dentro de una casa o hay una puerta de por medio).

## Tanda 33 - Puertas rotas por los stalkers
- `ChaseNav`: puertas cerradas en el camino/entre el stalker y el jugador -> se planta a `hold` bloques, golpea (sonidos g/t/r/c1-3 segun `door_break.json`), rompe la puerta (2 mitades) a `brk` ticks y sigue. Si abres la puerta antes, aborta. Prop `rompe` 1/0 para la animacion.
- `door_break.json`: configuracion por entidad (39 entidades).
- Corregido: `tickChase` ahora aplica `yawOffset` del perfil (cabras/redlight).
- No portado: variante 50 % "dk" (golpea/arrastra) y camara temblando.

## Tanda 34 - Goat de la puerta
- `GoatPuertaEvent` (/twixxel event stalker_goat_puerta o huevo): abre la puerta mas cercana (con pared al lado), saca cabeza (t=16) y mano, cuerpo siempre oculto tras el marco segun tu vista. Si le ves la cara 3 s o te acercas a 2.2 bloques, se esconde y sale como stalker_goat persiguiendote. Natural cada 15-30 min. Se va si en 5 min no la miras.
- Aproximado: sin comprobacion de "cuerpo expuesto" del original, y el stalker_goat no se fuerza a gatear toda la persecucion.

## Tanda 35 - Craneo y Trepador
- `stalker_craneo` (FieldProfile): quieto con su musica (se corta al activarse); a 2 bloques: manos/cuello con crujidos, voz "solo corre" y persecucion 2 min con oscuridad continua (`darkDuring`), musica cr_chase cada 457 ticks y grito cada 128. Natural 15-30 min.
- `stalker_trepador` (FieldProfile): solo en minas/cuevas, aparece detras de ti y te persigue 59.5 s con su musica. Aproximado: sin la subida por la escalera de mano del pozo.
- Nuevo campo `darkDuring`; la musica de espera se corta al activarse/terminar.

## Fix Verity (cara)
- `StalkerModel.setCustomAnimations`: para `verity` el yaw del hueso `ball` se calcula en Java (cuerpo - camara) para que la cara siempre mire al jugador.

## Tanda 36 - Maebi
- `MaebiEvent` (/twixxel event stalker_maebi, natural cada 30 min): quieta mirandote; a 7 bloques dobla la cabeza (3 crujidos), salta sobre ti (5 corazones), te agarra (inmoviliza ~2 s con slowness), rasguno (1 corazon, sangre, titulo "Eres mio"), 3 s de ventaja y persecucion 60 s (perfil `stalker_maebi`: musica mb_music, grito cada 128 ticks, oscuridad continua).
- Aproximado: sin camera shake; la inmovilizacion usa efectos en vez de permisos de input. Desde huevo (sin escena) te persigue al acercarte a 7 bloques.

## Tanda 37 - spotted_asoma y vida_asoma
- `stalker_spotted_asoma`: PeekProfile igual al RUN que se asoma (headLat 1.2, headUp 2.6, headStand 2.9); natural 1-5 min.
- `stalker_vida_asoma`: solo troncos, se asoma de costado con texto rojo "¿Te gusta la vida?.."; mirarlo 3 s -> se retira; a 2 bloques desaparece + oscuridad; natural 15-30 min.
- Corregido: `remove(RemovalReason)` duplicado en StalkerEntity.

## Tanda 38 - Escapa de mi (stalker_lc)
- Perfil `stalker_lc`: mirarle la cara 3 s -> 80 ticks doblando huesos (10 crujidos), voz + texto rojo "Escapa de mi..." y persecucion 59.5 s con lc_music y grito de cabra cada 128 ticks. Natural 8-16 min.
- Aproximado: la musica empieza al iniciar la persecucion (el original arranca al doblarse).

## Tanda 39 - goat_real2 y run_salafuera
- `stalker_goat_real2` (ScareProfile): agarrado de una ventana 2x2, te llama por tu nombre en el chat; al mirarlo (0.6 s) rompe el cristal a cabezazos (grietas a 16/36/56, rompe a 76), se lanza (3 corazones), cae, se para y es el stalker_goat.
- `SalAfueraEvent` (/twixxel event stalker_run_salafuera): RUN asomado por la ventana; sin mirarlo toca cada 5 s; al mirarlo "sal afuera" y se hunde; cuenta "Stalker: 10..0" en el chat; si sales a cielo abierto 0.5 s te persigue un RUN 60 s (musica saf_musica, oscuridad, "NO DEBISTE SALIR"). Natural 10-15 min. Aproximado: "afuera" = cielo abierto sobre ti; la cuenta usa ventanas de cristal (no vallas).
- `StalkerEntity.setFieldProfile` + `FieldProfile.SAL_AFUERA` (perfil suelto fuera de ALL).

## Tanda 40 - EL SUEÑO (base + sueños 1 y 2)
- Dimension propia `twixxel:sueno` (datapack: dimension, dimension_type, bioma sin mobs; mundo plano vacio, noche fija) y `SuenoEvent`.
- Al acostarte de noche (una vez por noche, sin stalkers activos), a los 3 s despiertas en el sueño. Etapas por jugador: sueño 1 -> sueño 2 (el 3 y el 4 llegan en las siguientes tandas). Prueba: `/twixxel event sueno` (siguiente etapa), `sueno_1`, `sueno_2`.
- Terreno 361x361 (sueño 1: arboles cada 20; sueño 2: arboles gigantes de roble oscuro cada 10, casa de abeto), casa de madera 9x9, TV + cassette (1) / VHS (2, video de 124 cuadros). Cassette/VHS se recoge con clic (un cassette por jugador en el sueño); clic derecho a la tele lo reproduce; al terminar el audio despiertas donde dormiste. Se construye por lotes la primera vez (pantalla negra mientras tanto).
- Niebla clara solo en el cliente (`SuenoClient`). Si te desconectas/mueres en el sueño, vuelves a donde estabas.
- Aproximado: el cassette/VHS no es un item del inventario (es un estado interno); el fundido es con ceguera.

## Tanda 41 - Sueño 3 (pasillo + THE LOCUST)
- `/twixxel event sueno_3` (o tercera etapa al dormir): pasillo de 120 bloques (piso de petalos rosas, pared de piedra, ventanales con mar de 40 bloques afuera, techo), VHS a la mitad del camino y la tele grande (tele3) al final con video de 263 cuadros (26.3 s). Clic al VHS, clic derecho a la tele: al terminar, THE LOCUST sale de la tele (6 s), cae, se levanta y te persigue (0.3 bloques/tick, pasos de arana, grito cada 7 s). Si te alcanza: grito, pantalla roja/negra y despiertas.
- `the_locust` tambien tiene perfil de huevo: aparece quieto, grita (2.5 s) y te persigue 3 min (mata). `/twixxel event the_locust`.
- Niebla del sueño 3: del 55 % al 100 % de la distancia de render.
- Aproximado: susto propio simple (sin escena de camara de jumpscares.json), sin temblor de camara.

## Tanda 42 — Sueño 4 (pueblo) + correcciones
- **Sueño 4**: pueblo de 29×29 casas de tablones (841), el mundo se repite al alejarte 100 bloques del centro; dura 4 minutos y despiertas. Un stalker RUN con la skin del sueño por cada soñador (aparece 15–25 bloques delante, en una esquina de calle). Si te atrapa: su susto (`run_sueno`) y despiertas, no mueres. Niebla muy cerrada. Comando: `/twixxel event sueno_4`.
- Durante un sueño el Director no genera eventos naturales para el soñador (`SuenoEvent.isDreaming/busy`).
- **Locust**: animaciones horneadas (idle / emerge / run / scr) como keyframes numéricos en `the_locust.animation.json` (antes eran expresiones Molang largas).
- **Jumpscares**: `modelOf` portado igual que `jsModelOf` del addon (spotted → solocorre, variantes por subcadena); añadidas las escenas `solocorre`, `diversion` y `run_sueno`; cada muerte escribe en el log `JumpScare: mata X -> escena Y`.
- **Maebi**: `purge()` elimina cualquier Maebi suelto al iniciar y antes de la persecución (solo uno a la vez).
- **TV**: se quitó la pantalla trasera de `twx_tele.geo.json`; el video sale solo por el frente.
- **Jumpscare**: los demás stalkers a menos de 64 bloques se quedan quietos mientras dura la escena; el puppet se sincroniza cada tick.

## Tanda 43 — jumpscares propios, ambiente y chat
- **Jumpscare propio (OJS del addon)** para Luster (`stalker_lc`), Cuernos, Craneo, Trepador, The Locust y Maebi: el stalker, con su modelo y su animación de matar, aparece delante, grita y se te tira a la cara (40 ticks). Antes caían a la escena `run` (el figurín de papel). El de Maebi es el largo (104 ticks): dobla el cuello, grita, te tira al piso, te rasguña y te pisotea. En el sueño solo despiertas.
- **Ambiente** (`event/Ambiente.java`): cada 1–30 min suena al azar uno de `amb_horror_drone / amb_creepy_wind / amb_dark_ambient / amb_cs_door` (no en sueños ni jumpscares).
- **Chat**: mientras algo te persigue te escribe `???:` cada 10 s (ES/EN según idioma); el stalker del sueño no habla.

## Tanda 44 — opciones de config (calma y sin oscuridad)
- `/twxcalma yes|no` (**Quitar movimiento**): sin temblores de cámara en jumpscares y escondites, cámara suave en los jumpscares de escena y animaciones de stalkers sin temblor (prop `calma`, sincronizada cada segundo). Se guarda en `twixxel-common.toml` (`options.calma`).
- `/twxoscuridad yes|no` (**Quitar oscuridad de mods**): el efecto Oscuridad nunca entra en jugadores (evento `MobEffectEvent.Applicable`) y, si se cuela, se quita cada 4 ticks (`options.sinOscuridad`).
- Ambos comandos no piden permisos, igual que `/twxvida`.

## Tanda 45 — alcantarillas y trampillas
- `event/Trampillas.java` (v3.1.13 del addon): cualquier stalker que se esté moviendo abre las trampillas a 1.25 bloques (también encima/debajo) y las cierra 0.8 s después de alejarse 2.6 bloques; solo cierra las que abrió. Complementa a ChaseNav (que abre las del camino). Las trampillas del evento de alcantarilla en curso no se tocan (`Alcantarilla.reserved`).

## Tanda 46 — "Configurar stalkers" e idioma
- **Item `config` ("Configurar stalkers")**: se entrega una sola vez a cada jugador, 2 s después de entrar al mundo (con aviso en el chat); si el inventario está lleno cae al suelo. Clic derecho abre el menú: una barra por stalker (67 entradas de la lista del addon; 0 = tiempo normal, 1–100 min), más los botones "Quitar movimiento" y "Quitar oscuridad de mods". Rueda del ratón para ver más.
- Se guarda por mundo (`twixxel_cfg`) y se aplica al instante (los relojes naturales se reinician). Solo opera quien tenga permiso 2 o el dueño en un mundo local. Los tiempos se aplican donde el mod usa `SpawnConfig.ticks` (los naturales del Director, de campo abierto, de ventana/árbol, puerta, especiales); eventos con reloj propio (p. ej. alcantarilla, escalera) aún usan el tiempo normal.
- **Idioma**: el idioma elegido en el mundo se sincroniza al cliente (`Net.LangSync` → `ClientLang`); el celular y las pantallas ya no dependen del idioma de Minecraft. Traducidos los textos que seguían fijos (cuenta atrás de los evils, texto previo del smiler, mensajes de comandos).
- Los nombres de ítems/entidades siguen el idioma de Minecraft (archivos `lang`), no el elegido en el mod.

## Tanda 47
- Arrastre por la puerta (Bedrock `dk_*`): 50 % de las veces el stalker toca fuerte (`stalker.dk_knock`), si te acercas a la puerta la rompe, se te tira encima (4 de daño), te arrastra 7 bloques afuera y te mata con su jumpscare. Otro jugador puede pegarle 10 veces (se cancela el daño, cuenta golpes) y te suelta; queda aturdido 10 s. En vez de la caja invisible `dk_cara` se golpea al stalker mismo.
- Sacudida de camara (`Net.Shake` + `client/ShakeClient`, desactivada en modo calma): al romper puertas, golpes del arrastre, tiron y salto.
- "Configurar stalkers" ahora tambien controla escalera y asomandome (usaban temporizador propio).

## Tanda 49
- Monstruo-video de la ventana (`stalker_windowbreak`): ahora avanza los 146 cuadros (15 fps: sale de abajo hasta el cuadro 24, al mirarlo sigue hasta el 145), con el audio del video (`stalker.wb_video`), cabezazos en 7/42/81 y el vidrio revienta a los 138 ticks (antes usaba el audio viejo y la propiedad `frame` nunca cambiaba).
- HUD (`client/HudClient`, overlay): titulos grandes en rojo, multiplicados (6 capas) y temblando (twxc) para RUN / "Eres el siguiente" / textos de jumpscare; imagen a pantalla completa `twx_foto` con `/twxfoto <nombre|off>` (op 2; incluida `cabra`). Ojo: en el addon nada dispara el HUD de foto, solo existe en la UI.
- Watcher por reloj: ver el video 1 del celular arma 20 min (guardado en el jugador); a la hora, si estas a cielo abierto, aparece el 1o; cuando desaparece arma otros 20 min para el 2o (persecucion). Ya no aparecen solos (`natural=false`).

## Tanda 50
- Arrastre por la puerta: ahora la victima ve su camara en tercera persona (`Net.Cam`, se restaura sola al soltarla, morir o salir del mundo) y va acostada (`Pose.SLEEPING` cada tick; se vuelve a `STANDING` al soltar).
- "Configurar stalkers" ahora tambien controla puertas (stalker de la puerta 3), goat bad, goat de la puerta, life, maebi y vigilante (antes tenian temporizador propio). El cambio aplica desde el proximo reloj de cada uno.
- Nombres de items/entidades/bloques siguen el idioma elegido en el mundo (`client/ModLang`: overlay sobre `Language` con assets/twixxel/lang/es_es|en_us.json; se quita al salir del mundo).

## Tanda 51
- Video del celular mas realista: fundido entre cuadros consecutivos (10 fps -> se ve fluido), filtro suave en los atlas (`cel_*.png.mcmeta` blur) con recorte de 1 px para evitar bordes, y el celular se escala con la pantalla (hasta x3). Limite: los cuadros originales son de 76x72 px a 10 fps, no se puede inventar mas detalle.
- Video del celular: ahora usa los 3268 cuadros como imagenes sueltas (`textures/gui/cel/a0000.png`...), igual que el addon, en vez de 6 hojas. Se cargan al reproducir y los ya vistos se sueltan de la GPU; el suavizado se aplica por codigo (se quitaron los `.mcmeta`).
