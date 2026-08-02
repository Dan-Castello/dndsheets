# Auditoría: D&D Sheets como VTT completo

Objetivo declarado (actualizado 2026-08-01): convertir Minecraft en un VTT (virtual tabletop)
**casi completamente independiente — sin un DM humano llevando la partida en vivo**. La
mayoría de cálculos y procesos (tiradas, CA/PG, daño, curación, descansos, turnos, muerte) los
resuelve el propio mod sobre el estado real del jugador; el rol humano que queda es de
"diseñador de contenido / configurador inicial", no de árbitro en tiempo real durante la
sesión. Libertad total de agregar material vía JSON (armas, hechizos, monstruos, presets,
rasgos) sigue siendo pilar del proyecto, sin recompilar nada. Sin salir de Minecraft ni
depender de Roll20/Foundry.

Esto es un cambio de objetivo respecto a versiones anteriores de esta auditoría, que asumían
un DM llevando la sesión en vivo con la Vara de DM. La sección 0 audita cuánto del código
actual ya sirve para "sin DM" y cuánto todavía asume un operador humano jugando ese rol en
cada sesión.

Este documento es una foto del estado real del código (mod original + todo lo añadido en
sesiones anteriores), no una lista de deseos. Cada afirmación se verificó con `grep` sobre el
código fuente antes de escribirse.

---

## 0. El objetivo cambió: ¿cuánto del código de hoy sirve para "sin DM"?

Verificado con grep sobre el código actual.

### Ya es DM-less hoy, sin cambios
- **El loop jugador → monstruo ya es automático.** `CombatManager.onAttackEntity` /
  `onProjectileImpact` cancelan el golpe vanilla y resuelven un ataque de 5e real (tirada vs.
  CA, daño real) en cuanto un jugador golpea a un monstruo con un arma configurada — ningún
  comando ni intervención humana de por medio. Es el núcleo del combate y ya funciona sin
  nadie llevando la partida.
- **Todo lo que un jugador hace sobre sí mismo es self-service**: tirar, atacar, lanzar
  hechizos de su grimorio, aplicar un preset de clase, activar sus propios recursos de clase
  (Furia, Segundo Aliento, Inspiración Bárdica, Forma Salvaje, Metamagia, Castigo Divino,
  Marca del Cazador, Escudo, Contrahechizo), descansar (voto entre jugadores conectados, sin
  aprobación externa) y las salvaciones de muerte. Nada de esto pasa por un comando de
  operador.
- **El contenido ya es JSON puro** (armas/hechizos/monstruos/presets/rasgos en
  `dndsheets/*/`), que es literalmente el otro pilar del objetivo — un diseñador prepara el
  material de antemano y nadie necesita tocar código ni estar presente en directo.

### Todavía asume un DM humano en directo — el gap real
- ~~Los monstruos se invocan sin IA y nunca contraatacan solos.~~ RESUELTO (2026-08-01): siguen
  invocados con `setNoAi(true)` (no persiguen ni se mueven por sí solos — eso sigue siendo cierto,
  ver más abajo), pero ya no necesitan la Vara de DM para actuar en SU turno: `TurnManager.beginTurn`
  llama a `MonsterActionManager.autoAct`, que ataca al jugador más cercano con el primer ataque/hechizo
  disponible sin intervención humana. El "maniquí que nunca contraataca" ya no es el estado por
  defecto — ver sección 2, "Combate autónomo de monstruos en su propio turno".
- **Los monstruos siguen sin moverse por sí solos** (`setNoAi(true)` no se tocó, a propósito: darles
  pathfinding real hacia el jugador es un problema aparte — IA de movimiento, no de resolución de
  ataque). En la práctica esto es aceptable para el "loop" de combate: si el monstruo ya está al
  alcance cuando le toca, actúa solo; si no, se queda plantado hasta que un jugador se acerque. No
  persigue activamente. Queda fuera de esta pasada.
- **Todos los comandos de contenido/administración exigen nivel de operador** (`.requires(...)`
  verificado por grep en `SheetCommand`, `MonsterCommand`, `TraitCommand`, `TurnCommand`,
  `SpellCommand`, `WeaponCommand`, `PresetCommand`). Poner un monstruo en el mundo por primera vez
  (`/dndmonsters spawn`), o tocar oro/slots/ventaja de un jugador, sigue necesitando a alguien con
  permisos, en vivo — es un candado deliberado (se añadió la sesión pasada precisamente para que un
  jugador normal no pudiera hacer estas cosas), no un descuido.
- ~~El orden de turnos no arranca solo.~~ RESUELTO (2026-08-01) para el caso que de verdad importa:
  `CombatManager.autoStartCombatIfNeeded` llama a `TurnCommand.startAt` (el mismo punto de entrada
  del Panel de DM) en cuanto un jugador golpea a un monstruo con el modo turnos apagado — nadie tiene
  que escribir `/dndturns start` a tiempo. Sigue sin haber disparo por pura proximidad sin contacto
  ("un jugador se acerca y ya empieza"), pero no hace falta: el motor ya resolvía el golpe jugador→
  monstruo sin turnos activos, así que ese golpe es el gatillo natural.

**Conclusión de esta sección (actualizada tras resolver ataque automático + arranque automático)**:
el objetivo nuevo no fue una reescritura. Con el combate del jugador ya automático desde antes, el de
los monstruos automático desde la pasada anterior, y el arranque del encuentro automático desde esta,
el "loop" central de un encuentro (alguien golpea → se tira iniciativa sola → ataques en ambos
sentidos → daño real → muerte) ya no necesita a nadie llevando la partida en vivo, del primer golpe en
adelante. Lo que queda es puramente administrativo: quién pone al monstruo en el mundo la primera vez
(`/dndmonsters spawn`, sigue exigiendo operador — delegable a datapack para encuentros preparados de
antemano, ver sección 4) y la arquitectura de 1 hoja = 1 UUID. Ver sección 2 "Pendiente de verdad" y
sección 5 para el detalle.

---

## 1. Qué cubre bien el sistema actual

### Para el jugador
- Hoja de personaje completa (características, salvaciones, habilidades, CA, PG, dados de
  golpe), con PG/PG máx/PG temp/nivel/competencia **calculados solos** desde el estado real
  del jugador (vida, XP) y el arma/armadura que lleva puesta — ya no hay que llevarlos a mano.
- Distinción visual (color ámbar) entre campos automáticos y campos que se escriben a mano.
- Tirar cualquier cosa con un botón, con feedback en chat coloreado (impacto/fallo/daño) y
  partículas/sonidos nativos de Minecraft, no solo texto plano.
- Pestaña de Ataques auto-poblada con las armas que llevas encima, con su propio dado
  editable por ti.
- Grimorio para hechizos aprendidos, con báculos de lanzado rápido.
- Presets de clase para no rellenar 6 características y el dado de golpe a mano cada vez.

### Para el DM
- **Combate real sin salir de Minecraft**: golpear a otro jugador, a un monstruo invocado o
  a un armor stand con un arma configurada hace una tirada de ataque de verdad contra la CA
  real del objetivo, y si acierta, aplica daño real — sin tocar comandos de por medio.
- **Monstruos con bloque de estadísticas real** (CA, PG, características, ataques, hechizos
  especiales), invocados como mobs vanilla sin IA, controlados a mano con la Vara de DM
  (incluye borrarlos si se spawnearon de más).
- **Contenido 100% en JSON, sin tocar código**: armas, hechizos, monstruos y presets de clase
  se cargan solos desde `dndsheets/{weapons,spells,monsters,presets}/` al arrancar el
  servidor. Esto es, literalmente, "libertad completa en los recursos": un DM puede diseñar
  su propio bestiario y grimorio sin recompilar nada.
- Pestaña creativa con todo lo cargado (armas, báculos, cartas de invocación, Vara de DM) para
  no tener que teclear ningún id de memoria.
- Sistema de caída/salvaciones de muerte que reemplaza la muerte instantánea de Minecraft.

**Conclusión de esta sección**: el "loop" central de una sesión de D&D — tirar, golpear,
lanzar un hechizo, que alguien caiga a 0 PG — ya funciona de punta a punta dentro del juego.
Lo que falta es todo lo que rodea ese loop: economía de recursos, turnos, y reglas finas de
5e que hoy se simplificaron a propósito.

---

## 2. Qué falta (y cuánto importa)

**Actualización tras la sesión de Descansos/Turnos/Ventaja-Crítico/Tipos de daño**: todos los puntos
de "Alto impacto" y casi todos los de "Impacto medio" de esta sección ya están resueltos. Se deja el
detalle de cada uno y, al final, lo que sigue pendiente de verdad.

### Alto impacto — RESUELTO
- ~~No hay forma de configurar `spellSlotsMax`.~~ `/dndsheet setslots <jugadores> <max> [actual]` y
  los presets de clase (`spellSlotsMax` en el JSON del preset) ya lo escriben.
- ~~No hay descanso corto/largo.~~ `RestManager` + Kit de Descanso (ítem, pestaña creativa): usarlo
  propone corto/largo, todos los jugadores conectados votan (`RestVoteScreen`), y solo si todos
  aceptan se aplica (largo = PG y espacios llenos; corto = mitad de los PG que faltaban).
- ~~No hay rastreador de iniciativa/turnos.~~ `TurnManager` + `/dndturns start|next|cancel|end`: tira
  iniciativa para jugadores y monstruos etiquetados en un radio, arma el orden, tickea efectos de
  estado (veneno/daño por turno) al empezar cada turno, y las acciones fuera de turno (ataques,
  hechizos) quedan en cola (`runOrQueue`) y se resuelven solas al llegarle el turno a quien las inició.
  Idempotente por diseño: un mismo tick de servidor no puede duplicar un avance de turno ni una
  ejecución de acción en cola.

### Impacto medio — RESUELTO
- ~~Sin ventaja/desventaja.~~ `DiceManager.rollAttack(..., Advantage)` tira dos veces y se queda con
  el total mayor/menor; `/dndsheet advantage <jugadores> <normal|ventaja|desventaja>` lo fija para el
  siguiente ataque (arma o hechizo) de cada jugador, y se consume solo.
- ~~Sin críticos.~~ Un natural 20 en la tirada de ataque acierta siempre y dobla los dados de daño (no
  el modificador plano); un natural 1 falla siempre, sin importar el total. Aplica a armas, hechizos
  de ataque y ataques de monstruo.
- ~~Sin tipos de daño ni resistencias.~~ Armas/hechizos/ataques de monstruo llevan un `damageType`
  opcional en su JSON (por defecto "fisico"); `/dndsheet damagetype <jugadores> <tipo>
  <normal|resistant|vulnerable|immune>` fija la afinidad en la hoja del jugador (`DamageTypes`).
- ~~Sin puntuaciones pasivas.~~ `PassiveScores` reutiliza la MISMA fórmula que el jugador ya tiene en
  "skills" (sustituyendo el d20 por un 10 fijo); `/dndsheet passive <jugador>` es la "tirada secreta
  del DM" — el resultado solo lo ve quien ejecuta el comando.
- ~~Sin concentración.~~ `ConcentrationManager`: lanzar un hechizo marcado `"concentration": true` en
  su JSON reemplaza cualquier concentración previa; recibir daño real obliga a una salvación de
  Constitución (CD = máx(10, daño/2)) o se pierde. Enganchado en los tres puntos donde un jugador
  recibe daño real (PvP, monstruo, efecto de estado/hechizo).
- ~~Sin área de efecto real.~~ Un hechizo `mode:"save"` con `"aoeRadius": N` en su JSON ya no exige
  mirar directamente a una entidad: golpea a todo lo que esté a N bloques del punto de impacto
  (entidad mirada, o el terreno si no hay ninguna). **Simplificación deliberada**: el radio es
  esférico, sin oclusión de terreno (una pared no bloquea la explosión) — ver sección 3, la
  precisión de terreno sigue siendo un proyecto aparte.

### Feedback de playtesting (2026-08-01) — parcialmente RESUELTO
Tras una sesión de prueba con jugadores reales, surgieron quejas puntuales. Dos ya están resueltas
esta pasada; el resto queda en "Pendiente de verdad" más abajo.
- ~~Sin separación de permisos DM/jugador.~~ Verificado con grep: ningún comando tenía `.requires(...)`,
  así que cualquier jugador podía invocar monstruos, repartir armas/hechizos, aplicar presets o tocar
  `/dndsheet` (oro, ventaja, slots...) igual que el DM. Ahora todos los comandos `dndmonsters`,
  `dndweapons`, `dndspells`, `dndpresets`, `dndsheet` y `dndturns` exigen permiso de operador (nivel 2);
  `/roll` y `/r` siguen abiertos a todos, son la única acción que le corresponde a un jugador normal.
  También se cerró el hueco de la Vara de DM: si un jugador sin permisos llegara a tenerla en la mano
  (loot, trueque...), ya no le funciona (`MonsterActionManager.onInteractWithMonster`).
- ~~El modo turnos no bloqueaba el movimiento.~~ Antes solo limitaba a una acción por turno; se podía
  caminar libremente aunque no te tocara, e incluso a quien SÍ le tocaba el turno se movía con libertad
  total de Minecraft. `TurnManager` ahora ancla a quien no tiene el turno a la posición donde estaba
  cuando dejó de ser su turno (`beginTurn`/`advance` fijan y sueltan el ancla) y un listener de tick
  (`onPlayerTick`) lo teletransporta de vuelta ahí en cuanto se aleja, avisándole por qué. Actualización
  2026-08-01: quien SÍ tiene el turno ya no se mueve sin límite tampoco — `enforceMovementBudget` mide la
  distancia en línea recta desde dónde empezó su turno contra la "speed" de su hoja (5 pies = 1 bloque,
  30 pies por defecto) y lo devuelve a la última posición dentro de rango en cuanto se pasa. **Simplificación
  deliberada**: distancia en línea recta desde el origen del turno, no ruta acumulada por casillas ni
  separada de la vertical — corta el "vuelo libre" que pedía el feedback, no es un tracker de rejilla real.

### Motor de rasgos y monstruos editables en vivo (2026-08-01) — RESUELTO parcialmente
Segunda ronda de feedback: pedían mantener el patrón de "una sección JSON por tipo de contenido" también
para pasivas/habilidades de clase, y poder editar o dar ataques distintos a un enemigo ya invocado sin
salir del juego.
- **Nuevo tipo de contenido: rasgos** (`dndsheets/traits/*.json`, `TraitRegistry`, comandos `/dndtraits
  load|list|grant`, todos bajo el mismo patrón que armas/hechizos/monstruos/presets). Un preset de clase
  concede rasgos por id con un campo `"traits": [...]` en su JSON (`PresetRegistry.applyToSheet` →
  `TraitRegistry.grant`); `/dndtraits grant` los concede a mano.
- **Primer rasgo real: Artes Marciales del monje.** Resuelve la queja concreta ("el monje no pega más a
  mano"): un puñetazo solo se resolvía como golpe flojo de Minecraft porque `CombatManager.identifyWeapon`
  nunca reconocía la mano vacía como arma. Ahora, si el atacante tiene un rasgo con
  `"unarmedDiceByLevel"`, el puñetazo se resuelve como un ataque de 5e real (tirada de ataque vs. CA,
  daño con el dado y la característica del rasgo, escalando por nivel — 1d4/1d6/1d8/1d10 en niveles
  1/5/11/17, igual que el monje de verdad). Sin ese rasgo, el puñetazo se comporta exactamente igual que
  antes (arma sin configurar = Minecraft de siempre), así que esto no cambia nada para el resto de clases.
  Añadir el siguiente rasgo (Ki, Sentido Draconico, etc.) es un campo nuevo en `TraitRegistry` + una rama
  donde se consuma, el mismo patrón que un campo nuevo en `spells.json` — sigue sin hacer falta un motor
  de reglas genérico para esto. **Rellenar el resto de clases con sus propios rasgos queda pendiente**,
  ver más abajo.
- **Monstruos editables en vivo.** Antes, el bloque de estadísticas de un monstruo (incluidos sus
  ataques) era compartido por TODAS las instancias de su id y solo se podía cambiar recargando JSON —
  no había forma de darle un ataque extra a un solo goblin de la tanda a mitad de encuentro. Ahora
  `/dndmonsters attack add|remove|clear <objetivo>` guarda ataques personalizados en la propia etiqueta
  NBT de ESA instancia (`MonsterRegistry.addCustomAttack`/`customAttacksOf`), sin tocar el bloque
  compartido ni afectar a los demás monstruos de la misma especie; el menú de la Vara de DM
  (`MonsterActionManager`) los incluye igual que los predefinidos. **Simplificación deliberada**: un
  ataque personalizado no admite `appliesEffect` (veneno, etc.), solo ataque+daño — si hace falta un
  efecto de estado, se sigue necesitando el JSON completo del monstruo.
- **NPC base en blanco.** `/dndmonsters spawn generic <nombre> [entidadBase] [CA] [PG]` registra un
  bloque de estadísticas nuevo al vuelo (sin JSON, sin reinicio) y lo invoca sin ataques — pensado como
  punto de partida para rellenar con `attack add` según lo que necesite ese encuentro concreto, en vez de
  tener que autorar un monstruo entero de antemano para un NPC de usar y tirar.

### Panel de DM (2026-08-01) — RESUELTO para lo añadido esta sesión
Pedido explícito: que todo ajuste/configuración de DM se pueda hacer con interfaz gráfica, no solo con
comandos escritos. Se añadió un **Panel de DM** (`client.gui.DmPanelScreen`), abierto con una tecla propia
(por defecto **P**, ver `DndsheetsModKeyMappings.DM_PANEL`) que solo hace algo si el cliente ya sabe que
es operador — el servidor vuelve a comprobar el permiso en cada mensaje de red que el panel manda, un
cliente modificado no basta para saltárselo:
- **Modo turnos** (`TurnControlScreen`): botones Iniciar/Siguiente/Saltar/Terminar, equivalentes a
  `/dndturns start|next|cancel|end` (radio siempre el por defecto, 30 bloques — para otro radio sigue
  haciendo falta el comando).
- **NPC genérico** (`SpawnGenericScreen`): formulario con nombre, entidad base, CA y PG, equivalente a
  `/dndmonsters spawn generic`.
- **Conceder rasgo** (`PlayerPickerScreen` → `TraitGrantScreen`): primero a quién (lista de jugadores
  conectados, ya la conoce el cliente) y luego qué rasgo (pedido al servidor, el registro solo vive ahí),
  equivalente a `/dndtraits grant`.
- **Ataques de un monstruo ya invocado**: en vez de vivir en el panel general, se quedaron donde ya
  tenían sentido — el menú que abre la Vara de DM al hacer clic derecho sobre el monstruo
  (`MonsterActionScreen`), que ahora suma dos botones: "+ Añadir ataque" (`AddMonsterAttackScreen`, con
  botones cíclicos para habilidad de ataque/daño y tipo de daño en vez de tener que escribirlos bien) y
  "Gestionar ataques personalizados" (`ManageCustomAttacksScreen`, quitar uno o todos). Equivalente a
  `/dndmonsters attack add|remove|clear`, sin necesitar saber el id de la entidad de memoria — se apunta
  con el mouse.
- Todos los comandos siguen existiendo tal cual (radios distintos, automatización por función de
  datapack, etc.) — el panel es un atajo con interfaz, no un reemplazo.
- **Ajustes de hoja** (`PlayerPickerScreen` → `SheetAdjustScreen`, equivalente a `/dndsheet
  gold|setslots|advantage|damagetype|passive`): al elegir jugador, el panel le pide al servidor sus
  valores reales de oro y espacios de conjuro (`SheetSummaryRequestMessage`/`SheetSummaryMessage`) para
  mostrarlos ya rellenos en vez de en blanco. Ventaja y tipo de daño/afinidad se eligen con botones
  cíclicos (mismo patrón que "+ Añadir ataque"); percepción pasiva es un botón que responde por chat
  privado al DM, igual que el comando. `PlayerPickerScreen` se generalizó para aceptar un callback
  (a quién, y qué hacer con ese UUID después) en vez de tener el flujo de rasgos escrito a fuego, así que
  "Conceder rasgo" y "Ajustes de hoja" comparten la misma pantalla de "elegir jugador".

### Armas de una/dos manos (2026-08-01) — RESUELTO
`Config.WeaponDefault` suma `hands` ("one" por defecto, "two", o "versatile") y `versatileDice`. Un arma
versátil (espada larga, lanza, alabarda, tridente, bastón, hacha de batalla, martillo de guerra) usa el
dado grande solo con la otra mano de verdad vacía (`CombatManager.findWeaponExpression`, mirando
`player.getOffhandItem().isEmpty()`); las de "two" (mandoble, hacha grande, pica, guja, almádena, garrote
grande) son puramente informativas por ahora. **Identificación real, no solo en el JSON**: las armas
personalizadas dadas por `/dndweapons give` (o la pestaña creativa) llevan una línea de lore visible en
el tooltip del ítem ("Versátil (1d8 a una mano, 1d10 a dos)" / "A dos manos") — resuelve el "identificar"
del feedback, no solo el "bonificación". **Simplificación deliberada**: no impide llevar un escudo en la
otra mano junto a un arma "two-handed" (Minecraft no separa "empuñar a dos manos" de "algo en la otra
mano" de forma nativa); solo se ve afectado el dado de daño real, que es lo que pedía el feedback. El
catálogo de armas (`dndsheets/weapons/weapons.json`) ya trae marcadas las versátiles/a-dos-manos reales
de 5e.

### Segundo rasgo real: Ataque Furtivo del pícaro (2026-08-01) — RESUELTO
`TraitRegistry` suma un segundo tipo de efecto, `sneakAttackDiceByLevel` (misma forma que
`unarmedDiceByLevel`, tabla nivel→dado — ahora ambas comparten el registro `LevelDice`): dados extra que
se SUMAN a la tirada de daño cuando el ataque se hizo con ventaja de verdad. Escala 1d6→10d6 en niveles
impares 1–19, igual que 5e. **Simplificación deliberada**: 5e también lo permite con un aliado adyacente
sin desventaja; el motor no tiene noción de "aliado adyacente", así que aquí solo cuenta la ventaja real.

Detalle técnico que casi rompe esto: el motor de dados (librería `dicebot`) tiene un bug de origen
documentado en el README ("Roll expressions don't work correctly when faced with multiple dice groups")
— meter el dado de Ataque Furtivo en la MISMA expresión que el dado del arma (p.ej. "1d8 + 2d6") habría
producido resultados mal calculados. Se tira aparte (`DiceManager.rollDamage` con el mismo `critical`,
para que también doble sus dados en un golpe crítico como en la regla real) y se suman los montos en
Java — nunca dos grupos de dados en una sola expresión para el parser.

### Furia del bárbaro y Segundo Aliento del guerrero (2026-08-01) — RESUELTO
A diferencia de Artes Marciales/Ataque Furtivo (pasivas de `TraitRegistry`, sin activación), estos dos son
RECURSOS: se activan a voluntad y tienen su propio gestor, no un campo más en un rasgo.

- **Furia** (`BarbarianRageManager`): clic derecho en el Tótem de Furia (`/dndsheet rageitem`) da
  resistencia a daño físico (cortante/perforante/contundente/físico) y +2 al daño cuerpo a cuerpo con
  Fuerza durante 10 asaltos. **Esto es lo que motivó el aviso de "no solo por ticks, también por
  turnos"**: si el modo turnos está activo cuando se activa la Furia, los 10 asaltos se cuentan como 10
  vueltas completas del orden de turnos (`TurnManager.onRoundsPass`, nuevo — una cola de callbacks que
  `TurnManager.advance()` descuenta cada vez que empieza una ronda nueva, y que `TurnManager.end()`
  dispara de golpe si el modo turnos termina antes de que se cumplan). Fuera de modo turnos, cae a
  temporizador real de 1 minuto (`DndsheetsMod.queueServerWork`, ya existía). La resistencia se resuelve
  en `DamageTypes.multiplierFor` (ahora recibe también la entidad objetivo, no solo la hoja) tomando el
  mínimo entre la afinidad de la hoja y la de Furia, para no pisar una resistencia/inmunidad ya fijada por
  el DM. **Simplificaciones deliberadas**: sin límite de usos por descanso largo (en 5e real sí lo hay,
  aquí se puede re-activar cuando se quiera), y el bono de daño es fijo (+2) en vez de escalar con el
  nivel del personaje (+3/+4 en niveles altos de 5e).
- **Segundo Aliento** (`FighterSecondWindManager`): clic derecho (`/dndsheet secondwinditem`) cura
  `1d10 + nivel`, una vez por descanso — corto O largo, como en 5e (`RestManager.applyRest` llama a
  `resetOnRest` en los dos casos). Sin duración que contar: es un simple "usado/no usado", no necesita
  nada de `TurnManager`.

### Clérigo y mago (2026-08-01) — RESUELTO
Los dos son casters de base — su "rasgo icónico" real está en el sistema de hechizos, no en un rasgo de
`TraitRegistry` ni en un recurso de combate como Furia.

- **Curación de verdad** (`SpellCastManager`, nuevo `mode:"heal"` en `spells.json`, sin cambios de esquema
  en `SpellRegistry` — el campo `mode` ya era texto libre). Sin tirada de ataque ni salvación: se tira
  `dice` (que en curación SÍ suma la característica, p.ej. `"1d8 + $wis"` para Curar Heridas — a
  diferencia del daño de ataque/salvación, que en 5e nunca suma característica) contra la hoja del
  LANZADOR, y el objetivo recupera PG de verdad (`heal()` en un jugador, PG tope en un monstruo). Mirar a
  nadie ya no es un fallo para este modo: sin objetivo a la vista se cura a uno mismo, el caso más común
  de Curar Heridas. Este hueco (el mod entero era daño-solamente) era el bloqueo real para que el clérigo
  se sintiera como clérigo — se añadieron Curar Heridas y Palabra Curativa a `spells.json` como ejemplo.
- **Recuperación Arcana del mago** (`WizardArcaneRecoveryManager`): automática, no un ítem — el siguiente
  descanso CORTO tras un descanso largo devuelve `ceil(nivel/2)` espacios de conjuro (sin superar el
  máximo), enganchado directo en `RestManager.applyRest`. Solo aplica a personajes cuya "Clase y Nivel"
  contenga "mago"/"wizard" (mismo patrón por subcadena que `Config.hitDieFor` ya usa para el dado de
  golpe) — a diferencia de Furia/Segundo Aliento, esto no lo activa un ítem que cualquiera podría llevar,
  así que necesitaba una forma de saber "es un mago de verdad" sin pedir un rasgo aparte.

### Bardo, druida y hechicero (2026-08-01) — RESUELTO
- **Inspiración Bárdica** (`BardInspirationManager`): clic derecho del bardo sobre OTRO jugador (Cuerno de
  Inspiración) le tira y concede un `1d6` que se suma a su PRÓXIMA tirada de ataque, durante 100
  asaltos/10 minutos reales (mismo patrón de duración por asaltos-o-ticks que Furia). **Alcance reducido a
  propósito**: 5e también deja usarlo en pruebas de característica y salvaciones; aquí solo se engancha al
  único punto donde ya vivía la ventaja/desventaja (ataques en `CombatManager`/`SpellCastManager`) —
  extenderlo a pruebas/salvaciones tocaría también la pantalla de la hoja (`RollAnnouncerProcedure`).
- **Forma Salvaje** (`DruidWildShapeManager`): mientras está activa, un golpe a mano desnuda es un zarpazo
  real (`1d6` por Fuerza) — mismo mecanismo que Artes Marciales del monje pero TEMPORAL, así que vive en
  su propio gestor con duración por asaltos/ticks en vez de en `TraitRegistry`. **Simplificación grande y
  documentada en el propio archivo**: no transforma de verdad al jugador (sin modelo de bestia, sin bloque
  de estadísticas ni PG propios) — es "las manos desnudas pegan como un animal", no la mecánica completa.
- **Metamagia: Hechizo Gemelo** (`SorcererMetamagicManager`): clic derecho marca un flag de un solo uso;
  el siguiente hechizo de un único objetivo (`SpellCastManager`) se resuelve una SEGUNDA vez contra el
  objetivo válido más cercano distinto del primero, sin gastar un espacio de conjuro extra (el coste real
  en 5e son puntos de hechicero, que este mod no modela). No se consume en hechizos de área — gemelar algo
  que ya reparte daño a un radio no tiene sentido, ni en 5e real ni aquí.

### Paladín, explorador y brujo (2026-08-01) — RESUELTO — las 12 clases tienen ya al menos un rasgo real
- **Castigo Divino** (`PaladinSmiteManager`): clic derecho marca un flag de un solo uso (mismo patrón que
  Hechizo Gemelo); el PRÓXIMO golpe de arma (no a mano desnuda) que ACIERTE gasta un espacio de conjuro y
  suma `2d8` radiante, tirado aparte y sumado igual que Ataque Furtivo/Marca del Cazador. Se consume
  dentro de `CombatManager.computeDamageRoll`, que solo se llama tras confirmar el impacto — fallar el
  ataque nunca gasta el espacio. **Simplificación deliberada**: el dado es fijo (`2d8`); en 5e real escala
  con el nivel del espacio gastado, pero el pool de espacios de este mod es un contador plano sin niveles
  por ranura.
- **Marca del Cazador** (`RangerHunterMarkManager`): clic derecho sobre un objetivo lo marca 100
  asaltos/10 minutos reales (mismo patrón de duración que Furia); cada golpe del explorador CONTRA ESE
  OBJETIVO concreto suma `1d6`, tirado aparte igual que los otros "dado extra" de esta pasada.
  **Simplificación deliberada**: en 5e es un hechizo de concentración (un golpe fuerte podría acabarlo
  antes de tiempo); aquí no está enganchado a `ConcentrationManager`, dura su tiempo fijo pase lo que pase.
- **Magia de Pacto del brujo** (`WarlockPactMagicManager`): a diferencia de todos los demás casters
  (que solo recuperan espacios con un descanso LARGO), el brujo los recupera ENTEROS con cualquier
  descanso, incluido el corto — la diferencia mecánica real que distingue a un brujo de un mago en 5e.
  Mismo gancho que Recuperación Arcana del mago (`RestManager.applyRest`, comprobación por subcadena de
  clase), pero más simple: recupera todo, no la mitad del nivel, y sin límite de una vez por descanso
  largo. El Pacto de la Cadena/Hoja/Vara (una elección permanente al hacer el pacto) queda fuera — sigue
  en "Pendiente de verdad" más abajo.

### Reacciones, Pacto del brujo y nivel desacoplado del XP (2026-08-01) — RESUELTO
Tercera ronda: se resolvieron los dos hallazgos de mayor prioridad de "Pendiente de verdad" (Reacciones y
Pacto del brujo), y de paso se terminó un tercero que ya estaba a medio escribir y rompía la compilación
(`SheetLoader.characterLevelOf` ya leía `"characterLevel"` de la hoja y `/dndsheet setlevel` ya estaba
registrado en el árbol de comandos, pero el método que debía escribirlo nunca se implementó).

- **Reacciones**: `TurnManager` suma un segundo contador de "una vez y se acabó" además de
  `actedThisTurn` — `reactionUsed` (público vía `tryReact`), que a diferencia de la acción normal se puede
  gastar en el turno de CUALQUIERA, no solo el propio, y se recupera al empezar el turno propio (regla real
  de 5e), no al pasar de ronda. Con ese primitivo, tres reacciones reales:
  - **Ataque de Oportunidad**: `TurnManager` ya sabía qué jugador se mueve libremente (tiene el turno) y
    tenía a los demás anclados; ahora, cada tick de quien se mueve, si un monstruo del orden de turnos que
    estaba a alcance cuerpo a cuerpo (3 bloques, aproximando el alcance real de Minecraft) deja de estarlo
    sin haber gastado ya su reacción, el monstruo dispara su primer ataque real disponible contra quien se
    aleja (`MonsterActionManager.resolveOpportunityAttack`, reutilizando `resolveAttack` tal cual — no una
    copia). **Simplificación deliberada**: solo monstruo-contra-jugador, no PvP (los demás jugadores están
    anclados y no pueden moverse mientras no sea su turno, así que ese caso no puede darse de todas formas).
  - **Escudo**: clic derecho dejaba el hechizo "listo" (mismo patrón que Castigo Divino), pero a diferencia
    de un flag de un solo uso normal NO se consume por dispararse una vez — se comprueba en el mismo punto
    donde ya se compara la tirada de ataque contra la CA (`CombatManager.onLivingHurt`,
    `MonsterActionManager.resolveAttack`) y solo gasta espacio de conjuro + reacción cuando el +5 de CA de
    verdad convierte un acierto en un fallo (`ShieldManager.effectiveAc`). Si el golpe iba a fallar igual, o
    acertaría de todas formas incluso con Escudo, no se gasta nada y sigue listo para el siguiente ataque de
    la ronda — más fiel a 5e (donde decides ya sabiendo la tirada) que un flag ciego.
  - **Contrahechizo**: mismo patrón "listo, se activa solo cuando ayuda" que Escudo, pero reacciona a que
    otro lanzador (jugador o monstruo del DM) empiece a lanzar cerca —
    `CounterspellManager.findCounterer` se llama justo antes de resolver el efecto, tanto desde
    `SpellCastManager.handleCastRequest` (hechizos de jugador) como desde `MonsterActionManager.resolveSpell`
    (hechizos de monstruo), así que protege en los dos sentidos: un jugador puede contrarrestar a otro
    jugador O a un lanzador enemigo del DM. El espacio del lanzador original se gasta igual aunque lo
    anulen (como en 5e real). **Simplificación deliberada**: en 5e real un Contrahechizo de nivel 3 anula
    automático hechizos de nivel ≤3 y contra los demás hace falta una prueba de característica (CD 10 +
    nivel del hechizo); aquí el pool de espacios es plano sin niveles por ranura (mismo motivo que el dado
    fijo de Castigo Divino), así que cualquier Contrahechizo listo con un espacio disponible anula cualquier
    hechizo, sin tirada de por medio.
- **Pacto del brujo** (`/dndsheet pact <jugadores> <cadena|hoja|vara>`): elección permanente grabada en la
  hoja (`warlockPact`), al estilo de un preset. Único gancho mecánico que encajaba sin inventar un
  subsistema nuevo: Pacto de la Hoja cambia la característica de ataque con arma a Carisma
  (`CombatManager.resolveWeapon`), la diferencia mecánica real de ese pacto. **Simplificación deliberada
  y documentada**: Cadena (familiar) y Vara (cantrips extra del Libro de las Sombras) se quedan como
  identidad grabada en la hoja sin efecto de código — este mod no modela familiares como entidades propias
  ni una lista de "hechizos conocidos" por personaje (los hechizos se conceden por ítem/báculo, no por
  hoja), así que darles un gancho real habría significado construir esos dos subsistemas enteros para un
  solo pacto cada uno. Igual que con Forma Salvaje, mejor una simplificación declarada que una mecánica a
  medias.
- ~~**Nivel de personaje atado al nivel de XP de Minecraft.**~~ RESUELTO — `/dndsheet setlevel` ya estaba
  medio escrito (el comando y la lectura en `SheetLoader.characterLevelOf` existían, faltaba el método que
  escribe `"characterLevel"` en la hoja); se completó porque sin él el proyecto no compilaba.

### Combate autónomo de monstruos en su propio turno (2026-08-01) — RESUELTO
Era el gap #1 de la sección 0: un monstruo invocado nunca actuaba solo, así que "sin DM" era de boquilla.
`TurnManager.beginTurn` ahora llama a `MonsterActionManager.autoAct` en cuanto le toca a un combatiente
sin sheet de jugador asociado (`MonsterRegistry.statBlockOf(entity) != null`): ataca al jugador más
cercano (30 bloques, mismo radio que ya usaba `resolveAction`) con su primer ataque disponible (de
especie, luego los personalizados de esa instancia vía `attack add`), o su primer hechizo si no tiene
ataques — reutilizando `resolveAttack`/`resolveSpell`, los mismos métodos privados que ya usaba el menú
de la Vara de DM y el ataque de oportunidad, no una copia. Sigue gastando `TurnManager.tryAct` como
cualquier acción de turno, así que un DM que además quiera intervenir a mano en otro momento (entre
rondas, con un ataque distinto) se comporta exactamente igual que antes. **Simplificación deliberada**:
sin selección táctica — siempre el jugador más cercano, siempre el primer ataque/hechizo de la lista, sin
huir con poca vida ni variar de objetivo. Es jugable y predecible, no "inteligente"; si un encuentro
necesita más variedad, el orden de ataques en el JSON del monstruo (o los personalizados por instancia)
es la palanca para lograrlo sin tocar código.

### Arranque de encuentro sin operador humano (2026-08-01) — RESUELTO para el disparo por combate
El otro gap de la sección 0: `/dndturns start` seguía exigiendo a alguien con permisos escribiendo el
comando en el momento justo. Ahora `CombatManager.autoStartCombatIfNeeded` se llama en cuanto un jugador
golpea a un monstruo (cuerpo a cuerpo en `onAttackEntity`, a distancia en `onProjectileImpact`) mientras
el modo turnos está apagado: si `!TurnManager.isActive()`, arranca solo con `TurnCommand.startAt`
(el mismo punto de entrada que ya usaba el Panel de DM, radio por defecto de 30 bloques centrado en el
monstruo) — nadie necesita op ni estar mirando la pantalla para que un combate empiece. **Sin golpe
gratis**: el golpe que dispara el encuentro pasa por el `tryAct` de siempre después de arrancar, así que
si el jugador no gana la iniciativa contra su propio objetivo, ese primer golpe queda bloqueado igual
que cualquier otro fuera de turno — coherente con cómo ya se gobierna el resto del modo turnos, no un
caso especial.
- **Sigue exigiendo operador**: `/dndmonsters spawn` (poner el monstruo en el mundo) y
  `/dndturns start` manual (para armar un encuentro sin que nadie haya golpeado todavía, p.ej. una
  emboscada). El punto que quedaba —"quién arranca el combate cuando ya hay monstruo y jugador en el
  mismo sitio"— es justo el que se resolvió acá.
- **Un solo personaje por jugador** (por UUID). Cambiar esto es un cambio de arquitectura real (las
  hojas están indexadas 1:1 por UUID de jugador en todo el código — `SheetLoader`, red, comandos),
  no una extensión aislada; se deja fuera de esta pasada.

### Turno automático + HUD + ayudas visuales para jugadores nuevos (2026-08-01) — RESUELTO
Pedido explícito: dejar de necesitar `/dndturns next` a mano, mostrar en pantalla las acciones
disponibles del turno, y agregar ayudas visuales para quien nunca jugó D&D.

- **El turno pasa solo.** `TurnManager.tryAct` ahora, en cuanto acepta la acción de quien tiene el
  turno, llama a `scheduleAutoAdvance` (un tick de margen vía `DndsheetsMod.queueServerWork`, para que
  el resultado del ataque se vea en el chat antes del aviso de la ronda siguiente) que avanza el turno
  solo — sin que nadie escriba el comando. Cubre jugadores y monstruos por igual, porque
  `MonsterActionManager.autoAct` también pasa por `tryAct`. **Bug real atrapado al construir esto**: el
  orden original de `autoAct` comprobaba si había un jugador cerca ANTES de llamar a `tryAct`; un
  monstruo sin nadie a distancia de ataque nunca hubiera gastado su acción, y sin acción gastada nunca
  se hubiera disparado el auto-avance — el encuentro se habría quedado colgado para siempre en ese
  monstruo. Se corrigió invirtiendo el orden: `tryAct` siempre primero, la búsqueda de objetivo después.
  Mismo blindaje para combatientes muertos o desconectados: `TurnManager.beginTurn` ahora detecta
  `entity == null || !entity.isAlive()` (antes Y después de `tickEffects`, por si el propio veneno lo
  mata) y salta su turno solo en vez de quedarse esperando una acción que nunca va a llegar.
- **HUD del modo turnos** (`network.TurnStateMessage` → `client.TurnHudState` → `client.TurnHudOverlay`,
  mismo patrón cliente-espejo que ya usa `ResourceHudOverlay` para oro/espacios de conjuro): siempre que
  el modo turnos está activo, arriba a la derecha se ve la ronda y de quién es el turno; si es el turno
  del jugador local, además si su acción sigue disponible y cuánto movimiento le queda — calculado en el
  cliente contra el origen del turno que ya manda el mensaje, sin pedirle nada más al servidor. Se manda
  de nuevo cada vez que algo visible cambia (arranca, avanza, se gasta o deshace una acción).
- **Ayudas visuales para jugadores completamente nuevos**: (1) quien tiene el turno brilla con el efecto
  vanilla Brillo (visible a través de paredes, sin partículas propias para no ensuciarle la pantalla al
  afectado — `visible=false` en el `MobEffectInstance`), así que no hace falta leer el chat para saber a
  quién le toca; (2) la primera línea del HUD incluye un recordatorio en texto plano ("Ataca o lanza un
  hechizo para actuar") mientras la acción siga disponible; (3) al arrancar un encuentro, un único
  mensaje explica la regla en una frase ("Por turnos: 1 acción y tu velocidad de movimiento cada uno; tu
  turno pasa solo al actuar"); (4) `TurnManager.notifyCantAct` ahora dice de quién es el turno en vez de
  un genérico "No es tu turno.", para que quien se equivoca de momento sepa a quién esperar.
- **Simplificación deliberada**: el turno termina apenas se gasta la acción, no cuando el jugador decide
  que terminó — en 5e real se puede mover antes y después de actuar; acá, una vez actuás, te queda ~1
  tick de margen y el turno pasa. Coincide con el pedido explícito ("en cuanto se acaben sus acciones") y
  con el modelo de acción única que ya tenía el motor; si hace falta separar "terminar turno" de "gastar
  la acción", es un botón más en `TurnItemManager`, no un cambio de arquitectura.

### Fin automático del modo turnos (2026-08-01) — RESUELTO
Cerraba el círculo de "el DM no tiene que tocar nada durante un combate": arrancaba solo (golpear a un
monstruo), los monstruos actuaban solos en su turno, el turno pasaba solo al actuar — pero terminar el
encuentro seguía necesitando `/dndturns end` a mano. `Combatant` ahora guarda `isMonster`, fijado al armar
la iniciativa en `TurnCommand.startAt` (no se reinfiere después: si el DM borra al monstruo a mitad de
encuentro con la Vara de DM, ya no habría forma de preguntarle a `MonsterRegistry` qué era). Cada vez que
`beginTurn` va a darle el turno a alguien, primero comprueba `allEnemiesDefeated`: si el encuentro arrancó
con al menos un monstruo y ya no queda ninguno vivo (muerto o borrado del mundo), anuncia "¡Todos los
enemigos han caído!" y llama a `end()` en vez de continuar. **A propósito no cuenta jugadores**: que un
jugador llegue a 0 PG no termina el combate (sigue el flujo de `DeathSaveManager`), y un encuentro que
arrancó sin ningún monstruo (modo turnos usado para otra cosa) nunca dispara el auto-fin.

### Detalle técnico que vale la pena señalar
- ~~Todo el texto nuevo de combate/magia/muerte está en español fijo.~~ RESUELTO (2026-08-02):
  `ChatFeedback`, `CombatFx`, `CombatManager`, `SpellCastManager`, `MonsterActionManager`,
  `RestManager`, `TurnManager` y `ConcentrationManager` ya no usan `Component.literal` con frases
  en español para el texto fijo — todo el texto de conector ("ataca a", "vs CA", "¡Impacto!", los
  cuatro rótulos de categoría, los mensajes de descanso/turnos/concentración) pasa por
  `Component.translatable` con claves nuevas bajo `chat.dndsheets.*`, resueltas en
  `assets/dndsheets/lang/en_us.json` y `es_es.json` (es_es conserva el texto original tal cual
  para no cambiar nada para quien juega en español; en_us es la traducción nueva). Los nombres de
  personaje/monstruo/arma/hechizo (contenido dinámico de JSON o de la hoja) siguen viajando como
  argumentos de esos `translatable`, con su propio estilo — nunca se traducen, siguen siendo el
  texto que puso quien diseñó el contenido. `ChatFeedback.saveResult` cambió su parámetro
  `outcomeLabel` de `String` a `Component` para poder pasarle un `translatable` ya resuelto
  ("Salva a medias"/"Salva sin daño"/"Falla la salvación") sin resolverlo del lado del servidor
  (una traducción resuelta en el servidor se vería igual para todos los clientes, sin importar su
  idioma — el punto de `translatable` es que cada cliente la resuelve con su propio lang file).
  **Simplificación deliberada, fuera de los 8 archivos de arriba**: `RestType.label` (el
  "corto"/"largo" que ya usan `RestVoteScreen`/`RestChoiceScreen`, dos pantallas de cliente no
  tocadas esta pasada) se dejó como texto español fijo compartido — cambiar su significado a una
  clave de traducción habría obligado a tocar esas pantallas también, arriesgando más que lo que
  pedía este pase. `DeathSaveManager` (el único llamador de `CombatFx.saved`, con su propio
  `titleText` dinámico) tampoco se tocó por el mismo motivo: no estaba en la lista original de 8
  archivos que señalaba esta sección.

### Área de efecto con oclusión de terreno real (2026-08-02) — RESUELTO para hechizos de jugador
Section 3 señalaba esto como "posible mediante raycasts y colisión de bloques, pero no trivial". Resultó
serlo: `SpellCastManager.findAoeTargets` seguía usando una `AABB` inflada por el radio y una comparación
de distancia — un radio esférico ciego, una pared no protegía a nadie. Ahora, tras filtrar por radio como
antes, cada candidato pasa un segundo filtro (`hasClearPath`): un solo raycast de bloques
(`Level.clip` con `ClipContext.Block.COLLIDER`, el mismo mecanismo que ya usaba `findImpactPoint` para
saber dónde "aterriza" el hechizo si no hay nadie mirado directamente) desde el punto de impacto hasta el
centro de la hitbox de cada candidato; si el rayo choca con un bloque sólido antes de llegar, ese objetivo
queda fuera de la explosión aunque esté dentro del radio. **Simplificaciones deliberadas**: un solo rayo
al centro de la hitbox por objetivo, no varios puntos de su volumen ni un cálculo de cobertura parcial —
alcanza para "un muro entero bloquea, un hueco en la pared no" (el caso que pedía la sección 3: puerta sí,
muro no), pero una esquina asomando apenas por el borde de una pared puede dar un falso negativo (se lo
considera protegido cuando en la mesa real un DM podría discutirlo). Sigue siendo radio esférico puro para
la FORMA del área (no hay cono/línea/cubo — eso sigue siendo una extensión aparte, es una forma geométrica
distinta, no oclusión); lo que se resolvió es específicamente que una pared ya bloquea el daño. Solo toca
hechizos de jugador (`SpellCastManager`) porque es el único punto del código que usa `aoeRadius` —
`MonsterActionManager` no tiene hechizos de área todavía.

---

## 3. Qué NO se puede cubrir bien dentro de Minecraft (límites del motor)

Esto no es "falta implementarlo" — es que la naturaleza del juego empuja en contra.

- **Minecraft es en tiempo real, D&D es por turnos.** No hay pausa nativa del mundo mientras
  cada uno decide su turno. Se puede simular (congelar a los demás con efectos, como ya se
  hace con el jugador caído), pero un motor de turnos estricto es la pieza de ingeniería más
  grande que le queda al proyecto si de verdad se quiere.
- **Sin niebla de guerra.** Pero esto es en realidad una ventaja: el mundo ya es 3D y
  explorable, no hace falta simular lo que un mapa 2D con tokens sí necesita simular. No
  vale la pena perseguirlo — es una elección de diseño ya ganada gratis.
- ~~Área de efecto que siga el terreno con precisión (una explosión que se cuela por una puerta
  pero no atraviesa un muro).~~ RESUELTO (2026-08-02) en la forma que de verdad pedía este punto
  ("no atraviesa un muro") — ver sección 2, "Área de efecto con oclusión de terreno real". Lo que
  queda fuera, y sí sigue siendo un proyecto aparte: formas de área no esféricas (cono, línea,
  cubo) y cobertura parcial por esquina en vez de un solo rayo al centro.
- **Un motor de reglas de 5e completo y estricto** (qué acciones son legales cada turno,
  cuántas reacciones quedan, etc.) requeriría construir ese estado desde cero — Minecraft no
  modela nada parecido de forma nativa.

---

## 4. Qué delegar a un datapack o resource pack (cero código Java)

Esto es contenido vanilla de Minecraft que resuelve parte del pedido de "libertad total de
recursos" sin escribir una línea más de mod:

- **Loot en cofres de mazmorras.** Una loot table de datapack con la función `set_nbt` puede
  poner un ítem con la etiqueta `{dndsheets:{weapon:"..."}}` directamente en un cofre
  generado, usando el mismo sistema de etiquetas que ya usan `/dndweapons` y `/dndspells
  staff`. No hace falta ningún comando ni evento nuevo en el mod para esto.
- **Recetas de crafteo** para tus armas personalizadas (craftear una "Daga" con materiales
  concretos), definidas como receta de datapack cuyo resultado ya lleva la etiqueta NBT.
- **Funciones (`.mcfunction`)** que encadenen los comandos que ya existen — por ejemplo, una
  función `encuentro_goblins.mcfunction` que llame a `/dndmonsters spawn` varias veces con
  distintas coordenadas para montar un encuentro completo de un solo `/function`. Esto le da
  al DM "guardar una plantilla de encuentro" sin que el mod necesite saber qué es una
  plantilla.
- **Advancements** para hitos narrativos (llegar a nivel 5, derrotar a tu primer monstruo).
- **Estructuras** para salas de encuentro prediseñadas.
- **Resource pack** para reskinear visualmente las armas personalizadas (modelo/textura
  distinta para que una "Daga" no se vea como una espada de hierro sin más) — esto es
  puramente cosmético y no toca el mod para nada.

---

## 5. Orden recomendado si se sigue construyendo

1. ~~Arreglar el candado de espacios de conjuro.~~ RESUELTO — `/dndsheet setslots`.
2. ~~Descanso corto/largo.~~ RESUELTO — Kit de Descanso + votación (`RestManager`).
3. ~~Rastreador de iniciativa/turnos.~~ RESUELTO — `TurnManager` + `/dndturns`.
4. ~~Ventaja/desventaja y críticos.~~ RESUELTO — `DiceManager.rollAttack`/`rollDamage` +
   `/dndsheet advantage`.
5. ~~Tipos de daño, puntuaciones pasivas, concentración, área de efecto, economía.~~ RESUELTO
   esta misma sesión (`DamageTypes`, `PassiveScores`, `ConcentrationManager`, `aoeRadius` en
   hechizos, `/dndsheet gold`) — ver sección 2, "Impacto medio — RESUELTO".
6. ~~Reacciones (ataques de oportunidad, Escudo, Contrahechizo).~~ RESUELTO — `TurnManager.tryReact` +
   `ShieldManager`/`CounterspellManager`, ver sección 2.
7. ~~Nivel de personaje desacoplado del XP de Minecraft.~~ RESUELTO — `/dndsheet setlevel`, ver sección 2.
8. ~~Comportamiento automático de monstruos en su propio turno.~~ RESUELTO —
   `MonsterActionManager.autoAct` + `TurnManager.beginTurn`, ver sección 2.
9. ~~Arranque de combate sin operador (golpear a un monstruo arranca el modo turnos solo).~~ RESUELTO —
   `CombatManager.autoStartCombatIfNeeded` + `TurnCommand.startAt`, ver sección 2.
10. **Lo que sigue, en orden real de importancia ahora que el objetivo es "sin DM" (sección 0):**
    1. `/dndmonsters spawn` sigue exigiendo operador — el candado que queda de verdad es poner al
       monstruo en el mundo la primera vez (armar el encuentro), no arrancarle el combate una vez que
       ya está ahí. Delegable a datapack/función (sección 4) para encuentros preparados de antemano.
    2. **IGNORAR HASTA NUEVO AVISO (2026-08-02)**: Multi-hoja por jugador — cambio de arquitectura
       (todo el código asume 1 hoja = 1 UUID: `SheetLoader`, red, comandos). Decisión explícita del
       proyecto, no un descarte técnico — no tocar sin que se pida de nuevo.
    3. ~~Pase de localización (`Component.literal` en español fijo → `Component.translatable` +
       lang files) en `ChatFeedback`/`CombatFx`/`CombatManager`/`SpellCastManager`/
       `MonsterActionManager`/`RestManager`/`TurnManager`/`ConcentrationManager`.~~ RESUELTO
       (2026-08-02) para esos 8 archivos — ver sección 2, "Detalle técnico que vale la pena
       señalar". Quedan fuera, a propósito: `RestType.label` (compartido con dos pantallas de
       cliente no tocadas) y `DeathSaveManager` (no estaba en la lista original de 8 archivos).
    4. ~~Área de efecto con oclusión de terreno real.~~ RESUELTO (2026-08-02) para la forma esférica
       — ver sección 2, "Área de efecto con oclusión de terreno real". Formas no esféricas
       (cono/línea/cubo) y cobertura parcial por esquina siguen fuera, ver sección 3.
11. **Datapacks**: estructura preparada en `datapacks/dndsheets_loot/` (carpetas + `pack.mcmeta` +
    README con la convención NBT), lista para recibir loot tables/funciones reales en otra sesión.

---

## 6. Mapa de mecánicas de D&D 5e: qué tan lejos puede llegar este motor (2026-08-01)

Clasificación de TODO el reglamento de 5e (no solo lo ya construido) según qué tan bien encaja con
este motor concreto: Minecraft (mundo real-time, 3D, sin grid nativo) + la arquitectura ya elegida
(contenido JSON, managers por mecánica enganchados a eventos de Forge, `TurnManager` para el orden de
turnos, sin un DM humano en directo como objetivo). Verificado contra el código real (`grep` de
`grapple/shove/exhaustion/multiclass/feat/prone/cover/legendary/lair/heroic/downtime/alignment` → cero
resultados, confirmando que nada de la lista de abajo existe todavía salvo donde se marca `(ya hecho)`).

**Hallazgo transversal, ligado al objetivo "sin DM" de la sección 0**: la variable que más mueve una
mecánica entre columnas no es la dificultad técnica, es si depende de **juicio subjetivo humano**
(¿esto merece Inspiración? ¿fue un buen roleo? ¿es "razonable" este uso del entorno?) o de **estado
mecánico objetivo** (PG, CA, un contador de usos, una tirada). Lo segundo es lo que este motor
automatiza bien; lo primero es estructuralmente imposible de automatizar sin quitarle la palabra
"juicio" — por eso reaparece en "Fuera de alcance" una y otra vez, no por límite técnico de Minecraft.

### Perfectamente alcanzables
Mismo patrón que ya usa el código (JSON + un manager con contador/tirada); varias YA están hechas.

- **Núcleo de resolución**: características, modificadores, salvaciones, habilidades, tiradas con
  ventaja/desventaja, críticos (ya hecho — `DiceManager`).
- **CA/PG/dados de golpe/muerte por 0 PG con salvaciones de muerte** (ya hecho — `SheetLoader`,
  `DeathSaveManager`).
- **Tipos de daño y resistencia/vulnerabilidad/inmunidad** (ya hecho — `DamageTypes`).
- **Hechizos con tirada de ataque, de salvación, de curación; espacios de conjuro; concentración;
  cantrips** (ya hecho — `SpellCastManager`, `ConcentrationManager`, `SpellRegistry`).
- **Descanso corto/largo y la economía de recursos por clase que se recupera con ellos** (ya hecho —
  `RestManager`, `WizardArcaneRecoveryManager`, `WarlockPactMagicManager`).
- **Puntuaciones pasivas** (ya hecho — `PassiveScores`).
- **Recursos de clase "activar + contador/temporizador"**: Furia, Segundo Aliento, Inspiración
  Bárdica, Metamagia, Castigo Divino, Marca del Cazador (ya hecho, uno por manager) — y cualquier
  recurso nuevo de la misma forma (Ki del monje, Canalizar Divinidad del clérigo/paladín, Forma
  Salvaje con más usos) es una extensión del mismo patrón, no un subsistema nuevo.
- **Rasgos "dado que escala por nivel"**: Artes Marciales, Ataque Furtivo (ya hecho —
  `TraitRegistry`); Encantos de Runa del artífice, Furia Elemental, etc. encajan igual.
- **Área de efecto esférica, con oclusión de terreno real** (ya hecho — `aoeRadius` en `spells.json`,
  `SpellCastManager.hasClearPath`).
- **Orden de turnos, una acción/reacción por turno, presupuesto de movimiento por velocidad** (ya
  hecho — `TurnManager`).
- **Contenido 100% vía JSON** sin recompilar: armas, hechizos, monstruos, presets, rasgos (ya hecho).
- **Loot, recetas, funciones de encuentro, advancements, estructuras** vía datapack vanilla (ya
  documentado en sección 4, cero código Java).

### Alcanzables
Encajan con la arquitectura actual, pero cada uno es un manager/campo nuevo con alcance acotado —
no un subsistema, aunque toquen varios puntos de enganche.

- **Ataque múltiple (Extra Attack / multiattack)**: un campo `"attacks": N` y un bucle en
  `CombatManager.resolveAttackOnMonster`/PvP, mismo lugar donde ya vive el crítico.
- **Acción adicional (bonus action)** como segundo cupo de acción en `TurnManager`, paralelo a
  `actedThisTurn` — mismo patrón que ya se usó para separar reacciones de la acción normal.
- **Condiciones básicas** (envenenado, aturdido, apresado, asustado, paralizado, cegado, ensordecido,
  incapacitado, hechizado): generalizar `TurnManager.applyEffect`/`tickEffects` (hoy solo hace daño
  por turno) a también bloquear acciones/tiradas mientras la condición esté activa. Un motor de
  condiciones genérico, reutilizado por todo lo demás de esta lista que las necesita (agarrar,
  derribar, asustar).
- **Agarrar / derribar / empujar**: una tirada enfrentada (ya hay tiradas de ataque/salvación de
  sobra como plantilla) que aplica la condición "apresado" o "derribado" de arriba.
- **Legendarias/acciones de guarida** para jefes: extender el bloque de estadísticas de
  `MonsterRegistry` con un contador de usos y engancharlas en el mismo punto donde `TurnManager` ya
  sabe cuándo termina el turno de un jugador (para "acciones entre turnos").
- **Dotes (feats)**: mecánicamente casi todas son un Rasgo más (`TraitRegistry`) concedido por
  elección en vez de por preset de clase; un puñado (Con Suerte, Alerta) necesitan un gancho nuevo
  puntual, no una dote genérica.
- **Multiclase**: dejar de asumir "una clase, un nivel" en `characterLevel`/espacios de
  conjuro/dado de golpe — cambio de datos acotado, no de arquitectura (a diferencia de multi-hoja,
  que sí lo es), porque sigue siendo 1 hoja = 1 personaje.
- **Agotamiento (exhaustion)**: un contador 1–6 en la hoja con penalizaciones crecientes aplicadas
  como los modificadores que ya se leen en cada tirada — mismo lugar que la afinidad de daño.
- **Inspiración Heroica**: un flag de ventaja igual al que ya existe para el próximo ataque
  (`nextAttackAdvantage`), pero gastable en cualquier tirada, no solo ataque.
- **Combate montado y bajo el agua**: Minecraft ya modela montar y el agua de forma nativa; falta
  enganchar las reglas de bonificación/penalización de 5e en los mismos puntos donde ya se resuelve
  un ataque.

### Con limitaciones
Se puede construir, pero solo con una simplificación deliberada y declarada (mismo patrón que ya
usan Forma Salvaje, Contrahechizo o el AoE esférico) — no una versión completa de la regla.

- ~~Área de efecto con oclusión de terreno real~~ (para radio esférico): RESUELTO (2026-08-02), ver
  sección 2. Lo que sigue en "con limitaciones" es específicamente la FORMA del área (cono/línea/cubo
  que respeta paredes) — el radio esférico ya no ignora las paredes, pero solo sabe repartir daño en
  esfera, no en un cono o una línea.
- **Terreno difícil (mitad de movimiento)**: Minecraft no etiqueta bloques como "difícil" de forma
  nativa; se podría aproximar con un datapack de tags de bloque + un descuento en
  `TurnManager.enforceMovementBudget`, pero es una aproximación gruesa, no una regla de terreno real.
- **Sigilo y "no visto"**: se puede tirar Sigilo (ya es una tirada de habilidad cualquiera), pero no
  hay un estado continuo de "oculto para X" que el motor recuerde entre turnos — cada tirada es un
  evento aislado, no una condición persistente como en una mesa real.
- **Familiares y compañeros** (Encontrar Familiar, Bestia del explorador, Pacto de la Cadena, Forma
  Salvaje completa): ya limitado a propósito en Forma Salvaje (solo "las manos pegan como animal", sin
  transformar de verdad). Un familiar/compañero real necesitaría IA de mascota + UI de órdenes por
  turno — Minecraft no da esto gratis como sí da montar o nadar.
- **Resistencia legendaria** (fallar una salvación se convierte en éxito, N usos/día): un contador es
  trivial, pero hay que engancharlo en CADA punto donde se tira una salvación en el código (varios
  managers distintos), no en uno solo — factible, pero disperso.
- **Contrahechizo/Legalidad por nivel de hechizo**: ya simplificado a propósito (cualquier
  Contrahechizo listo anula cualquier hechizo, sin comparar niveles ni tirada de característica) —
  ver sección 2. Hacerlo exacto exigiría que el pool de espacios tuviera niveles por ranura, cosa que
  el resto del sistema no modela.
- **Trampas y desafíos de habilidad**: mecanismos físicos (redstone) + datapack pueden simular el
  efecto, pero "detectar/desactivar" como tirada de característica contra CD con herramientas de
  ladrón específicas necesita un gancho a medida por trampa, no una trampa genérica.

### Fuera de alcance
No es "falta tiempo" — es que la mecánica choca con la naturaleza del motor (tiempo real, sin grid,
sin narrador) o con el propio objetivo del proyecto (libertad total de contenido, sin DM en directo).

- **Cualquier mecánica que dependa de juicio narrativo subjetivo** (cuándo dar Inspiración por buen
  roleo, si una excusa social es "convincente", improvisar consecuencias de una acción no prevista en
  ningún JSON). Es el hallazgo transversal de arriba: sin un DM humano no hay quien emita ese juicio,
  y no hay forma de codificarlo sin que deje de ser juicio.
- **Un motor de reglas de 5e genérico y estricto** (qué acciones son legales en cada instante,
  ventanas de tiempo exactas para reacciones fuera de los tres casos ya cableados) — ya señalado en
  sección 3; requeriría modelar desde cero un estado que Minecraft no tiene ninguna razón para tener.
- **Posicionamiento táctico de rejilla real** (casillas de 5 pies, reglas de flanqueo por casilla,
  ocupar/comprimirse en un espacio): el motor es 3D libre, no una rejilla — el presupuesto de
  movimiento ya construido es una aproximación en línea recta, no un tracker de casillas.
- **IA de familiar/mascota controlable por turno con órdenes tácticas completas**: mismo límite que
  "con limitaciones" arriba, pero llevado a su forma completa (Encontrar Familiar como entidad jugable
  de verdad) — un proyecto de IA aparte, no una extensión de `TraitRegistry`.
- **Validación de legalidad de personaje** (prerrequisitos de dotes, restricciones de multiclase por
  puntuación mínima, límites de alineamiento en algunas ediciones/mesas): fuera de alcance **a
  propósito**, no por límite técnico — choca de frente con "libertad total de recursos vía JSON" que
  es el otro pilar declarado del proyecto (sección 0). Añadir un validador de reglas sería quitarle
  la libertad que se pidió expresamente.
- **Pausar el mundo de verdad mientras alguien piensa su turno**: se aproxima anclando a quien no
  tiene el turno (ya hecho), pero el resto del mundo — mobs vanilla fuera del combate, el reloj del
  juego — sigue corriendo; no hay una "pausa" real de Minecraft.
- **Ritmo asíncrono tipo play-by-post** (una partida que dura días entre turnos escritos): no es una
  limitación del motor, es que directamente no es lo que un servidor de Minecraft en vivo ofrece —
  fuera del concepto mismo de "VTT dentro de Minecraft".

---

## 7. Feedback de una segunda sesión de playtesting (2026-08-02)

19 reportes de jugadores reales, verificados uno por uno contra el código antes de tocar nada. La mayoría
eran bugs de verdad con causa raíz identificable; unos pocos resultaron ser huecos de diseño que se
resolvieron con la confirmación del usuario (ver preguntas más abajo). Compilación dejada para el usuario,
como en la sesión anterior.

### Bugs resueltos
- **Golpear a mano no activaba el modo turnos ni gastaba el turno** (`CombatManager.onAttackEntity`): un
  puñetazo sin Artes Marciales/Forma Salvaje devolvía `weapon == null` y el código salía ANTES de tocar
  `autoStartCombatIfNeeded`/`TurnManager.tryAct` — un jugador sin monje podía pegar indefinidamente sin que
  "sin DM" (sección 0) se enterara. Ahora el turno se gasta y el combate arranca igual; solo se salta la
  resolución 5e (Minecraft pega su golpe flojo de siempre) si no hay arma ni rasgo.
- **Saltar para forzar un crítico de Minecraft dejaba al jugador congelado en el aire** al terminar su
  turno (`TurnManager.onPlayerTick`): el ancla comparaba/reponía la posición en 3D completo; si el turno
  terminaba en el aire, la Y quedaba fijada ahí para siempre, peleando con la gravedad cada tick. Ahora solo
  se corrige el plano horizontal (X/Z); la Y queda libre y la gravedad aterriza sola.
- **Cambiar de preset acumulaba ítems** en vez de reemplazarlos (`PresetManager.applyPreset`): cambiar a un
  preset distinto ya retira el arma inicial y el ítem de recurso de clase del preset ANTERIOR (identificados
  por su etiqueta NBT exacta, nunca por heurística sobre ítems vanilla del jugador, para no borrar nada que
  no fuera del preset).
- **El Grimorio no recibía hechizos del preset**: `ClassPreset` no tenía ningún campo de hechizos —
  configuraba el CONTADOR de espacios pero nunca daba nada que gastarlos. Nuevo campo opcional `"spells":
  [...]` en `presets.json`, concedido con el mismo `SpellRegistry.learn` que ya usa `/dndspells learn`
  (extraído a un método compartido).
- **Borrar un enemigo a mitad de combate no terminaba el combate**: `TurnManager.checkAllEnemiesDefeated`
  (nuevo, público) se llama justo tras el borrado manual con la Vara de DM, en vez de esperar a que le
  tocara el turno a alguien de nuevo (el único punto que antes comprobaba esto).
- **Descansar durante el modo turnos aplicaba un reset no deseado**: `RestManager` bloquea proponer un
  descanso (ítem y mensaje de red) mientras `TurnManager.isActive()`.
- **Contrahechizo bloqueaba hechizos que no lo apuntaban a él** (p. ej. un ataque de un jugador contra un
  goblin, contrarrestado por el Contrahechizo listo de OTRO jugador): `findCounterer` ahora solo protege
  contra un lanzador ENEMIGO (monstruo del DM) — un hechizo lanzado por un jugador nunca dispara el
  Contrahechizo de otro jugador. PvP de Contrahechizo queda fuera a propósito.
- **Contrahechizo se podía "spamear"**: nunca se limpiaba `counterspellReady` tras dispararse, así que
  seguía listo para siempre (limitado solo por la reacción/espacios, que fuera de modo turnos no frenan
  nada). Ahora se consume al dispararse, como cualquier otro recurso de un solo uso.
- **Espadón a dos manos no cancelaba el ataque con algo en la otra mano**: simplificación deliberada de la
  sesión anterior, ahora resuelta — `blockedByOffhand` cancela el ataque (mensaje explicativo, sin gastar
  turno) si `hands=="two"` y la otra mano no está vacía.
- **Goblin (zombie) que se quema con el sol podía dejar el combate colgado** si moría justo al empezarle el
  turno: `markDefeated` solo se llamaba desde los `remove()` manuales del propio mod (nunca dispara
  `LivingDeathEvent`); una muerte vanilla de verdad (sol, ahogo, caída) nunca quedaba confirmada. Nuevo
  listener `TurnManager.onMonsterDeath` (`LivingDeathEvent`) cubre ese camino y revisa fin de combate al
  instante.
- **Monstruos muertos en combate no soltaban loot**: se los eliminaba con `entity.remove(RemovalReason
  .KILLED)`, que nunca pasa por el camino de muerte vanilla (loot table, XP). Ahora se llama
  `LivingEntity#die(DamageSource)` a mano en cuanto el HP de 5e (trackeado aparte) llega a 0 — nuestra
  salud real de Minecraft nunca baja sola, así que `die()` no podía dispararse solo.
- **Arcos/ballestas y escudos de otros mods no se reconocían**: la identificación era 100% por id explícito
  en config/JSON. Ahora, sin entrada explícita, se reconoce cualquier ítem que extienda `BowItem`/
  `CrossbowItem` (mismas estadísticas por defecto que el arco vanilla) y cualquier `ShieldItem` en la mano
  secundaria suma +2 CA (5e real) — `armorClassOf` nunca contaba un escudo, ni siquiera el vanilla, porque
  `player.getArmorValue()` no lo incluye.
- **Los bonos de tirada de ataque (ventaja, Inspiración Bárdica) no se aplicaban al clicar el botón de la
  pestaña Ataques de la hoja**: `RollAnnouncerProcedure` (el roller genérico de botones, usado también por
  Pruebas/Salvaciones/Habilidades) nunca pasaba por `CombatManager.consumeAdvantage`/
  `BardInspirationManager.consumeAttackBonus` — esos recursos solo se consumían al golpear físicamente al
  objetivo. Ahora, SOLO en la pestaña Ataques y SOLO si el grupo trae una tirada que empieza con "1d20", se
  consumen y se aplican a esa tirada (nunca a Pruebas/Salvaciones/Habilidades, que no son "próximo ataque").

### Features añadidas (confirmadas con el usuario antes de construirlas)
- **Restricción de armas por clase**: campo opcional `"classes": [...]` en `weapons.json` (subcadenas
  comparadas contra "Clase y Nivel", mismo patrón que `Config.hitDieFor`); sin el campo, cualquier clase
  puede usar el arma, como siempre. `CombatManager.blockedByClass` cancela el ataque con aviso si la clase
  del jugador no está en la lista. El DM sigue siendo quien rellena la lista real arma por arma.
- **Bloqueo de edición de fórmulas de tirada a jugadores normales**: `checks`/`saves`/`skills` ya no viajan
  en `SheetServerMessage.PLAYER_EDITABLE_KEYS` — solo un operador puede tocar la FÓRMULA detrás de un botón
  (antes cualquiera podía escribir "1d20 + 999" desde su propia hoja). `attacks` es la excepción a medias:
  `mergeAttacks` deja pasar entradas NUEVAS (auto-pobladas por arma reconocida en el inventario, con la
  expresión por defecto de la config) pero descarta cualquier cambio a una entrada YA existente si quien
  manda el paquete no es operador.
- **CA fijable a mano por DM/OP**: `/dndsheet setac <jugadores> <valor|auto>` — nuevo campo
  `armorClassOverride` en la hoja, comprobado primero en `CombatManager.armorClassOf` antes del cálculo
  normal (10 + Destreza + armadura real + escudo). `auto` quita el override.
- **CustomModelData por JSON para armas**: campo opcional `"customModelData": N` en `weapons.json` →
  `Config.WeaponGiveInfo` → etiqueta NBT `CustomModelData` en el ítem entregado, para que un resource pack
  reskinee un arma personalizada sin compartir la textura de su ítem base.

### Deliberadamente NO resuelto esta pasada (con motivo)
- **CustomModelData para monstruos**: no existe un equivalente vanilla — `CustomModelData` es un campo de
  `ItemStack`, no de una entidad viva. Reskinear un tipo de mob entero (todos los zombies iguales, p. ej.)
  ya es posible HOY sin tocar código, con un resource pack que reemplace la textura vanilla de esa entidad
  (`assets/minecraft/textures/entity/...`); reskinear instancias DISTINTAS del mismo tipo base de forma
  diferenciada (un goblin vs. un hobgoblin, ambos zombies) necesitaría un renderer de entidad a medida —
  fuera de alcance de un campo JSON.

### Ajuste remoto de DM sin retargetear la hoja (2026-08-02)
Pedido explícito de seguimiento: mantener la hoja con propiedad de quien la abre (sin construir el cambio
de arquitectura de la sección anterior), pero dar al DM una vía real de ajuste remoto. `/dndsheet setac`
(CA) ya cubría un caso; ahora se suma el que de verdad motivaba el pedido —las fórmulas de tirada que
`SheetServerMessage` acababa de bloquear para jugadores normales (ver arriba, "Bloqueo de edición de
fórmulas...")—:

- **`/dndsheet setroll <jugadores> <checks|saves|skills> <nombre|índice> <expresión...>`**
  (`command.SheetCommand.setRoll`): escribe directo en la hoja SERVIDOR del objetivo (no pasa por el
  candado de `SheetServerMessage`, que solo gatea lo que manda el CLIENTE) y le empuja la hoja actualizada.
  `nombre` acepta el nombre tal cual lo muestra `RollIndex.getBasicContext` ("Persuasion Check", "Wisdom
  Save"...) con autocompletado por tab que cambia según la categoría elegida (`RollIndex.basicNames`,
  nuevo, público), o el índice numérico para quien lo prefiera. No cubre `attacks` (formato anidado por
  arma con su propio merge de auto-poblado, ver arriba) — para eso el jugador sigue pudiendo llevar
  encima el arma y dejar que se auto-rellene sola con el dado por defecto de la config.

### Carpeta `templates/` (2026-08-02)
Pedido explícito: plantillas de referencia para los 5 tipos de contenido JSON (`weapons`, `spells`,
`monsters`, `presets`, `traits`) y para reskins de armas/monstruos, en la raíz del repo (no en `test/`, que
es contenido de ejemplo para un mundo real, ni en `datapacks/`, que es loot/funciones vanilla). Cada
entrada de ejemplo lleva un campo `"_note"` con la explicación inline (ignorado por los parsers — todos
leen con `.has("clave")`, nunca validan que no haya claves de más, así que es seguro y no rompe nada si
alguien copia el archivo tal cual). Incluye `templates/resourcepack/`, un resource pack 1.20.1 completo y
funcional (salvo la textura `.png` en sí, que no se puede generar acá) que demuestra el patrón real de
`customModelData` para un arma (override del modelo del ítem base + modelo/textura propios) y documenta
por qué los monstruos no necesitan ningún campo nuevo para reskinearse por tipo de entidad (ya es un
resource pack vanilla de toda la vida) ni pueden reskinearse por instancia sin un renderer a medida.
