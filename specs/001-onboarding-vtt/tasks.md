---
description: "Task list for 001-onboarding-vtt"
---

# Tasks: Incorporación guiada al VTT

**Input**: Design documents from `specs/001-onboarding-vtt/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: la spec no pide TDD. Sí se incluyen pruebas de la lógica pura en `JsonContentSelfTest`, porque `gradlew check` es la verificación obligatoria del repo y la forma de red (invariante 1) ya la comprueba. Lo visual se valida con [quickstart.md](quickstart.md).

**Organization**: por historia de usuario de la spec (US1-US5).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: se puede hacer en paralelo (otro archivo, sin depender de tareas pendientes)
- **[Story]**: US1…US5 de la spec
- Rutas desde la raíz del repo. Abreviaturas: `CORE` = `src/main/java/net/hawthorn/dndsheets`, `GUI` = `CORE/client/gui`, `DUN` = `dungeon/src/main/java/net/hawthorn/dndsheets/dungeon`, `LANG` = `src/main/resources/assets/dndsheets/lang/` (los 8 archivos: `en_us`, `es_ar`, `es_cl`, `es_ec`, `es_es`, `es_mx`, `es_uy`, `es_ve`), `TEST` = `src/test/java/net/hawthorn/dndsheets/JsonContentSelfTest.java`

**Reglas que toda tarea respeta** (de `PROJECT_CONTEXT.md` y `DESIGN.md`): mensajes de red nuevos solo **al final** del registro; valores de enum nuevos solo **al final**; ninguna pantalla extiende `Screen` directamente; ningún color hex en pantallas (roles de `GuiStyle`); cada texto visible tiene clave en los 8 archivos de `LANG`.

---

## Phase 1: Setup

**Purpose**: dejar el repo listo para empezar

- [ ] T001 Verificar que `./gradlew check` pasa limpio en `main` antes de tocar nada, y anotar `NETWORK_SHAPE` (137), `NETWORK_ORDER` y `NETWORK_WIRE` actuales de `CORE/DndsheetsMod.java` (l.178-203) y `PROTOCOL_VERSION` ("30", l.165)
- [ ] T002 Crear el paquete `CORE/onboarding/` con un `package-info.java` que explique en 3 líneas qué guarda (progreso de incorporación por persona en el NBT del jugador) y que remita a `specs/001-onboarding-vtt/data-model.md`

---

## Phase 2: Foundational (bloquea todas las historias)

**Purpose**: estado, hitos, red y sincronización, que usan todas las historias

- [ ] T003 [P] Crear `CORE/onboarding/OnboardingState.java` (puro, sin clases de Minecraft salvo `CompoundTag`/`ListTag`): campos `v` (int, hoy 1), `welcome` (byte 0/1), `done` (lista de ids **sin duplicados**), `doneAt` (id→epoch ms), `pendingWelcome` (byte). Métodos: `fromTag(CompoundTag)`, `toTag()`, `mark(id, now)` que devuelve si cambió, `isDone(id)`, `dismissWelcome()`, `reset()`. Reglas de data-model.md: "Una versión desconocida se lee como estado vacío **sin borrar** el NBT" y "un id desconocido se **conserva** … pero no se muestra"
- [ ] T004 [P] Añadir a `CORE/onboarding/OnboardingState.java` la función pura `static OnboardingState migrate(JsonObject activeSheet)` según data-model.md: `onboarded` → `welcome=1`; clase elegida → `character_created`; nivel > 1 o algún ataque registrado → `first_attack`; `first_spell` solo si la ficha registra algún espacio de conjuro gastado, y si no consta, no se infiere. **Solo lee** la ficha (invariante 8)
- [ ] T005 [P] Crear `CORE/onboarding/OnboardingMilestones.java`: un `record Milestone(String id, Role role, int order, String titleKey, String hintKey, String helpEntry, boolean hidden, boolean clientMarkable)` con `enum Role { PLAYER, DM }`; un registro estático ordenado con `register(Milestone)` que rechaza ids repetidos, `byId(String)`, `visibleFor(boolean isDm)` y `clientMarkable(String id)`; y `registerCore()` con los hitos core de data-model.md (`dice`, `character_created`, `sheet_opened`, `first_attack`, `first_spell`, `menu_opened`, `dm_panel`, `monster_spawned`, `combat_started`). `clientMarkable=true` solo para `sheet_opened`, `menu_opened`, `dm_panel`. `menu_opened` lleva `hidden=true`: la ruta se abre desde el menú, así que siempre saldría hecho y no aporta nada al usuario; solo sirve para la sincronización
- [ ] T006 Crear `CORE/network/OnboardingMessage.java` según `contracts/onboarding-message.md`: `enum Action { SYNC, MARK, DISMISS_WELCOME, OPEN_WELCOME, RESET }` (orden fijo para siempre), cable `writeEnum(action)`, `writeUtf(payload, 8192)`, `writeBoolean(isDm)`. Handler: S→C con `NetworkUtil.handleOnClient` delega en `OnboardingClient.apply`; C→S con `NetworkUtil.handleOnServer` delega en `Onboarding` (T007). Un `MARK` con un id que no existe o que no es `clientMarkable` se descarta con `LOGGER.debug`, nunca lanza
- [ ] T007 Crear `CORE/onboarding/Onboarding.java` (servidor): `state(ServerPlayer)` lee y escribe `player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG)` → `dndsheets_onboarding`; `mark(ServerPlayer, String id)`, `dismissWelcome`, `reset` y `sync(ServerPlayer)`, que envía `SYNC` con JSON `{"welcome":bool,"done":[ids]}` e `isDm = DndsheetsMod.canActAsDm(player)`. Toda escritura pasa por estos métodos y después hace `sync` (contrato, "Garantías")
- [ ] T008 Registrar `OnboardingMessage` **al final** de `registerNetworkMessages` en `CORE/DndsheetsMod.java`, subir `PROTOCOL_VERSION` a "31" con un comentario como los anteriores, llamar a `OnboardingMilestones.registerCore()` en el arranque y actualizar `NETWORK_SHAPE`, `NETWORK_ORDER` y `NETWORK_WIRE` a los valores que pida `checkNetworkShape`
- [ ] T009 [P] Crear `CORE/client/OnboardingClient.java`: guarda el último `SYNC` (`welcomeSeen`, `Set<String> done`, `isDm`), `apply(...)`, `isDone(id)`, `isDm()`, `mark(id)` (envía `MARK` una sola vez por id y sesión, **salvo** `menu_opened`, que se envía en cada apertura del menú porque su respuesta `SYNC` es la que mantiene al día `isDm`, T033) y un contador de versión para que las pantallas abiertas se refresquen; se vacía al salir del mundo (`ClientPlayerNetworkEvent.LoggingOut`, como `ConfigSyncClient`)
- [ ] T010 En `CORE/SheetLoader.java#onPlayerLogin`: sustituir el bloque de `queueServerWork(60, …)` que mira `sheet.has("onboarded")` por una llamada a `Onboarding.onLogin(player)`. `onLogin` aplica `migrate` si no hay estado, hace `sync` y decide la bienvenida (eso lo completa US1). **No** borrar la clave `onboarded` de las fichas existentes
- [ ] T011 [P] Añadir a `TEST` las comprobaciones de la lógica pura: ida y vuelta `toTag`/`fromTag`; `mark` idempotente; versión desconocida → vacío sin perder el tag original; id desconocido conservado; `migrate` con tres fichas de ejemplo (nueva, con `onboarded`, con clase y nivel 3); `register` rechaza un id repetido
- [ ] T012 [P] Añadir a los 8 archivos de `LANG` las claves de título y descripción de los 9 hitos core (`gui.dndsheets.onboarding.m.<id>` y `.hint`), en inglés y en el español de cada variante

**Checkpoint**: `./gradlew check` pasa; al entrar en `runClient` llega un `SYNC` (ver con un log temporal) y la migración marca lo que delata la ficha.

---

## Phase 3: User Story 1 - Primera tirada en el primer minuto (P1) 🎯 MVP

**Goal**: el jugador nuevo ve una bienvenida que nombra su rol y le propone tirar un d20; la tirada se resuelve y se ve.

**Independent Test**: quickstart §2: en un mundo nuevo, sin documentación, primera tirada visible en menos de 1 minuto; la bienvenida no se repite y se puede reabrir; en combate se pospone.

- [ ] T013 [US1] En `CORE/onboarding/Onboarding.java#onLogin`: si `welcome=0`, programar con `DndsheetsMod.queueServerWork(60, …)` (volviendo a buscar al jugador por UUID, como `SheetLoader` l.157-170) la apertura con `ScreenActionMessage.Action.ONBOARDING_OPEN` y poner `welcome=1`. Si el jugador está en un combate, poner `pendingWelcome=1` y no abrirla. Para saberlo, añadir a `CORE/TurnManager.java` `public static boolean isParticipant(ServerPlayer player)`, que devuelve `active && order.stream().anyMatch(e -> player.getStringUUID().equals(e.playerUuid()))` (hoy `order` es privado y no hay ningún método público equivalente)
- [ ] T014 [US1] En `CORE/TurnManager.java`, en el punto donde termina un encuentro: para cada jugador con `pendingWelcome=1`, abrir la bienvenida y limpiar el flag mediante `Onboarding.openPendingWelcome(player)` (FR-016)
- [ ] T015 [US1] Rehacer `GUI/WelcomeScreen.java` (sigue sobre `ModalDialogScreen`): subtítulo con el rol según `OnboardingClient.isDm()` (claves `gui.dndsheets.welcome.role_player` / `role_dm`); botón principal "Tirar un d20" que hace `player.connection.sendCommand("roll 1d20")` y cierra; botón "Ver mis primeros pasos" que abre `OnboardingPathScreen` (US2; hasta entonces, `CharacterSetupScreen`); conservar las líneas de teclas, que ya muestran la tecla asignada (la línea de la tecla de ayuda la añade T052); "Cerrar" envía `DISMISS_WELCOME`
- [ ] T016 [US1] En `CORE/command/RollCommand.java#roll`: tras una tirada correcta, si la fuente es un `ServerPlayer`, llamar a `Onboarding.mark(player, "dice")`; y si la expresión es un único d20 (`1d20` con modificador o sin él), enviar también el `DiceRollMessage` existente con la misma lista de destinatarios que `CombatFx.diceRoll`, para que se vea el dado animado
- [ ] T017 [US1] Hacer que `OPEN_WELCOME` (C→S) responda con `ScreenActionMessage.Action.ONBOARDING_OPEN` en `CORE/network/OnboardingMessage.java`, para reabrir la bienvenida desde el menú central (FR-002)
- [ ] T018 [P] [US1] Añadir a los 8 archivos de `LANG` las claves nuevas de la bienvenida (`role_player`, `role_dm`, `roll_d20`, `open_path`) y reescribir `gui.dndsheets.welcome.intro` como una sola frase que proponga el primer paso
- [ ] T019 [US1] Validar quickstart §2 en `runClient` (escala 2 y 3, idioma `es_es`) y anotar el resultado en `specs/001-onboarding-vtt/checklists/requirements.md`, sección Notes

**Checkpoint**: US1 funciona sola: bienvenida → d20 → resultado; no se repite; se pospone en combate.

---

## Phase 4: User Story 2 - Ruta de primeros pasos por rol + menú central (P1)

**Goal**: P abre un menú central según el rol, y desde él "Mis primeros pasos" muestra los hitos con ✔/○, que se marcan solos.

**Independent Test**: quickstart §3: seguir la ruta de cada rol solo con lo que muestra; cada hito se marca al hacer la acción por cualquier vía; el progreso sobrevive a reiniciar.

- [ ] T020 [P] [US2] Marcar `first_attack` en `CORE/CombatManager.java#resolveAttack` (l.352) y en `#resolveAttackOnCreature` (l.396) cuando el atacante es un `ServerPlayer`
- [ ] T021 [P] [US2] Marcar `first_spell` en `CORE/SpellCastManager.java#handleCastRequest`, **solo** cuando el lanzamiento se acepta (después de las comprobaciones de espacio y concentración, no al recibir la petición)
- [ ] T022 [P] [US2] Marcar `monster_spawned` en `CORE/network/MonsterSpawnMessage.java#handler` y `CORE/network/SpawnGenericMessage.java#handler` tras invocar con éxito (dentro de `handleOnServerAsDm`), y en la rama de invocación de `CORE/command/MonsterCommand.java`
- [ ] T023 [P] [US2] Marcar `combat_started` en `CORE/TurnManager.java#startAt(ServerLevel, Vec3, double, Entity initiator)` (l.610) cuando `initiator` es un `ServerPlayer` y el encuentro arranca
- [ ] T024 [P] [US2] Marcar `character_created` en `CORE/SheetLoader.java#saveServer(JsonObject sheet, String uuid)` (l.525): toda escritura de ficha llega ahí (invariante 4), incluidas las que no pasan por `saveAndSync`, como `CORE/command/SheetCommand.java` (l.576). Si la ficha tiene `characterClass` no vacío, buscar el jugador conectado con ese UUID (`ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayer(UUID.fromString(uuid))`, sin hacer nada si `uuid` no es un UUID válido o el jugador no está conectado, como pasa con las fichas de PNJ) y, si `!Onboarding.isDone(player, "character_created")`, llamar a `Onboarding.mark(player, "character_created")`. La comprobación es barata porque `isDone` lee el estado en memoria
- [ ] T025 [P] [US2] Marcas de cliente: `OnboardingClient.mark("sheet_opened")` en `GUI/PlayerSheetScreen.java#init` y `OnboardingClient.mark("dm_panel")` en `GUI/DmPanelScreen.java#init`
- [ ] T026 [US2] Crear `GUI/OnboardingPathScreen.java` (extiende `ListPickerScreen`, `parent` obligatorio) según `contracts/screen-navigation.md` "Mis primeros pasos": `addHeader` "Jugador" y "DM" (este solo si `OnboardingClient.isDm()`), una fila por hito visible con "✔ " u "○ " + título; la fila ejecuta la acción del hito; mensaje de enhorabuena bajo el título si todos los hitos del rol están hechos; última fila "Reiniciar mis primeros pasos" → `ConfirmScreen` → `RESET`; `emptyMessage()` propio; se reconstruye cuando cambia la versión de `OnboardingClient`
- [ ] T027 [US2] Añadir a `CORE/onboarding/OnboardingMilestones.java` la acción de cliente de cada hito core (un `Consumer<Screen>` registrado solo en el cliente por `OnboardingClient`, para no cargar clases de cliente en el servidor): `dice` → `WelcomeScreen`; `character_created` → `CharacterSetupScreen.open(parent)`; `sheet_opened` → abrir la ficha como H; `first_attack` / `first_spell` → entrada de la guía; `menu_opened` → no-op; `dm_panel` → `DmPanelScreen.open(parent)`; `monster_spawned` → `MonsterSpawnListScreen.open(parent)`; `combat_started` → `TurnControlScreen.open(parent)` (las sobrecargas con padre las crea T047; hasta entonces, las versiones sin padre)
- [ ] T028 [US2] Convertir `GUI/PlayerPanelScreen.java` en el menú central de `contracts/screen-navigation.md`: secciones Primeros pasos (Bienvenida → `OPEN_WELCOME`, "Mis primeros pasos (n/m)", Teclas, Guía) · Personaje · Tiradas y combate · Magia · Mesa · "Herramientas del DM" solo si `isDm`, con una fila por sección de `DmPanelScreen` que reutiliza como texto su clave de cabecera (`gui.dndsheets.dm_panel.section_party`, `_spawn`, `_give`, `_world`, `_content`) y llama a `DmPanelScreen.open(this, "<esa clave>")`. Para eso: añadir a `GUI/DmPanelScreen.java` la sobrecarga `open(@Nullable Screen parent, @Nullable String sectionKey)` (la actual `open(Screen parent)` delega con `null`), que guarda el `Button` que devuelve `addHeader` para esa clave y, al terminar `init`, desplaza la lista para que esa cabecera quede arriba; y añadir a `GUI/components/ButtonListWidget.java` `scrollTo(Button row)`, que calcula la posición de la fila y usa el `scrollTo(double)` que ya existe (T050 lo reutiliza). Cambiar el título `gui.dndsheets.player_panel.title` a "Menú"/"Menu" en los 8 archivos de `LANG` (es el primer tramo de la ruta). `OnboardingClient.mark("menu_opened")` en `init`. Si el cliente no ha recibido aún ningún `SYNC`, mostrar la sección del DM como hoy (sin filtrar) para no esconder nada
- [ ] T029 [US2] En `GUI/ListPickerScreen.java`: sobrecarga `addRow(Component label, @Nullable ResourceLocation icon, @Nullable KeyMapping shortcut, Button.OnPress onPress)` que dibuja `shortcut.getTranslatedKeyMessage()` alineado a la derecha de la fila en `GuiStyle.MUTED_COLOR`; las sobrecargas actuales delegan con `null`. Usarla en el menú para Ficha [H], Grimorio [G] y Lanzamiento rápido [R] (FR-022)
- [ ] T030 [US2] En `CORE/init/DndsheetsModKeyMappings.java`: que `DM_PANEL` (P) abra `PlayerPanelScreen.open(null)` en vez de `DmPanelScreen.open()`, y cambiar su texto a "Menú" en los 8 archivos de `LANG` **sin** cambiar el id `key.dndsheets.dmpanel`, para que no se pierdan las teclas que los usuarios ya reasignaron. Añadir además `KeyMapping DM_PANEL_DIRECT = new KeyMapping("key.dndsheets.dmpanel_direct", InputConstants.UNKNOWN.getValue(), "key.categories.dndsheets")` (sin asignar por defecto), que abre `DmPanelScreen.open()` igual que la P de antes, registrada en `registerKeyMappings`: así el DM conserva un acceso directo (FR-022)
- [ ] T031 [US2] Quitar las teclas escritas a mano de los textos (FR-013) y corregir lo que dicen de P, en los 8 archivos de `LANG`: `chat.dndsheets.welcome.sheet_key`, `chat.dndsheets.welcome.dm_key`, `gui.dndsheets.welcome_toast.body`, `gui.dndsheets.guide.page.dm_first_1`, `gui.dndsheets.guide.page.dm_panel`, `gui.dndsheets.guide.page.first_ten` y `gui.dndsheets.guide.page.sheet` (más los que encuentre `grep -nE "\b(press|Press|key) \(?[HPGRXZ]\)?\b|the [HPGRXZ] key" LANG/en_us.json` y su equivalente en español, `pulsa|tecla [HPGRXZ]`). Los textos de chat y del aviso pasan a `%s`, rellenado al enviarlos con `Component.keybind("key.dndsheets.character")` / `Component.keybind("key.dndsheets.dmpanel")`, que el cliente traduce a la tecla asignada (cambiar `CORE/SheetLoader.java#onPlayerLogin` y `CORE/network/TutorialOpenMessage.java`). Las páginas de la guía, que no admiten parámetros, se reescriben sin nombrar la tecla ("la tecla de la ficha, que verás en Teclas"). Los textos de P dicen ahora que abre el **menú**, y que el panel del DM está en "Herramientas del DM"
- [ ] T032 [P] [US2] Añadir a los 8 archivos de `LANG` las claves del menú central, de sus secciones y de `OnboardingPathScreen`
- [ ] T033 [US2] Mantener `isDm` al día en el cliente, en `CORE/onboarding/Onboarding.java`: (1) suscribirse a `net.minecraftforge.event.entity.player.PermissionsChangedEvent` y hacer `sync(player)` al cambiar el nivel de permiso; (2) en `CORE/command/SoloModeCommand.java`, tras `Config.setSoloMode(enabled)` (l.34), hacer `sync` a todos los jugadores conectados, porque el modo solo cambia `canActAsDm` sin pasar por el permiso; (3) en el handler de `MARK menu_opened` responder siempre con `sync`, para que abrir el menú corrija cualquier desfase
- [ ] T034 [US2] Validar quickstart §3 en `runClient`, incluido dar OP en mitad de la sesión y activar `/dndsolo`: la sección del DM aparece sin reconectar

**Checkpoint**: US1 + US2 forman el MVP (fase A del plan).

---

## Phase 5: User Story 3 - Primer dungeon importado y primera sala sin documentación (P2)

**Goal**: el importador y el editor de salas se explican la primera vez y avisan de que se puede deshacer.

**Independent Test**: quickstart §4: un DM nuevo importa un mapa y pega una sala sin ayuda externa y sin dañar bloques.

- [ ] T035 [P] [US3] Añadir la opción de cliente `showOnboardingHints` (booleana, por defecto `true`): crear un `ForgeConfigSpec` de cliente en `CORE/ClientConfig.java` y registrarlo con `ModConfig.Type.CLIENT` en `CORE/DndsheetsMod.java` (hoy solo existe COMMON, l.72); `CoachMarks` y las sugerencias automáticas la consultan (FR-018)
- [ ] T036 [US3] Crear `GUI/components/CoachMarks.java`: una lista de pasos `(Supplier<Rect> target, Component text)` que dibuja sobre la pantalla un recuadro en `ACCENT_COLOR` alrededor del widget y un globo `GuiStyle.inset` con el texto, más los botones "Siguiente" y "Saltar" (`TomeButton`); Esc = Saltar; `onFinish` callback. **No se muestra** (y el recorrido queda pendiente para la próxima vez, sin marcarse como visto) si `ClientConfig.showOnboardingHints` es falso o si `CORE/client/TurnHudState.active()` es verdadero, para no interrumpir un combate (FR-016). Es un componente, no una pantalla (invariante 7)
- [ ] T037 [US3] En `DUN/DndsheetsDungeonMod.java`: al arrancar, registrar en `OnboardingMilestones` los hitos `dungeon_import` (DM, `clientMarkable=true`), `room_drawn` (DM), `seen_import_help` (hidden, `clientMarkable=true`) y `seen_trace_tour` (hidden, `clientMarkable=true`), con sus acciones de cliente (abrir `DungeonMapPickScreen` / `DungeonTraceScreen`)
- [ ] T038 [US3] En `DUN/client/gui/DungeonMapImportScreen.java`: si `!OnboardingClient.isDone("seen_import_help")`, mostrar `CoachMarks` (formatos aceptados: donjon .json, Universal VTT .dd2vtt, Dungeon Scrawl .ds; dónde generar uno gratis (donjon); qué pasará: se abre en el editor y nada toca el mundo hasta pegar) y al terminar o saltar enviar `MARK seen_import_help`. Tras un `DungeonMapImporter.read` correcto (l.43): `OnboardingClient.mark("dungeon_import")`
- [ ] T039 [US3] En `DUN/client/gui/DungeonMapImportScreen.java`: sustituir los mensajes de error de lectura por mensajes que digan qué hacer (formato no reconocido → lista de extensiones; archivo dañado → volver a exportarlo; escala inválida → rango permitido), con claves nuevas (FR-015)
- [ ] T040 [US3] En `DUN/client/gui/DungeonTraceScreen.java`: si `!OnboardingClient.isDone("seen_trace_tour")`, recorrido `CoachMarks` de 5 pasos sobre los botones reales de dibujar, borrar, puerta, nivel y vista previa; al terminar o saltar, `MARK seen_trace_tour`
- [ ] T041 [US3] Avisos de deshacer (FR-010): en la confirmación previa al pegado (la que abre `DUN/client/gui/DungeonTraceSaveScreen.java` o la que dispare el pegado) añadir la línea "Podrás deshacerlo con …"; en `DUN/PasteQueue.java`, al terminar un pegado, enviar al DM por la barra de acción "Pegado. Deshacer: …" con el botón o comando real de deshacer de `PasteHistory`; y llamar a `Onboarding.mark(dm, "room_drawn")` junto a `PasteHistory.record(dm, …)` (l.60)
- [ ] T042 [P] [US3] Añadir las claves nuevas de US3 (globos del importador y del editor, errores accionables, avisos de deshacer) a los 8 archivos de `LANG` del core: el addon `dungeon` no tiene recursos propios y sus textos ya viven ahí
- [ ] T043 [US3] Validar quickstart §4 y §7 en `runClient`, incluido deshacer un pegado y comprobar que el terreno queda como estaba

**Checkpoint**: US3 funciona sola sobre la base de la fase 2.

---

## Phase 6: User Story 4 - Ayuda contextual, navegación y descubrimiento de controles (P2)

**Goal**: en todas las pantallas: ruta pulsable bajo el título, "?" y F4 que abren su ayuda, flechas y Enter en las listas, referencia de teclas y listas vacías que dicen qué hacer.

**Independent Test**: quickstart §5.

### Navegación (FR-024, FR-025)

- [ ] T044 [US4] Crear `GUI/NavParent.java` (`interface NavParent { @Nullable Screen parentScreen(); }`) e implementarla en `GUI/ListPickerScreen.java` (devuelve `parent`), `GUI/FormPanelScreen.java` (ya tiene `parentScreen()`) y `GUI/ItemPickerScreen.java`; en `GUI/ModalDialogScreen.java`, añadir un constructor con `@Nullable Screen parent`, que `onClose` vuelva a él, e implementar `NavParent`. Los constructores actuales siguen existiendo y pasan `null`
- [ ] T045 [US4] Crear `GUI/components/Breadcrumbs.java`: función pura `static List<String> fit(List<String> titles, int maxWidth, ToIntFunction<String> width)` que une con " › " y recorta **por el principio** con "…" sin perder nunca el último tramo (el tramo actual se recorta con "…" solo si no cabe ni él solo); y la parte de dibujo y clic: `render(GuiGraphics, Font, Screen current, int x, int y, int maxWidth, int mouseX, int mouseY)` en `GuiStyle.SUBTITLE_COLOR`, con el tramo bajo el cursor en `ACCENT_COLOR`, y `mouseClicked(...)` que hace `Minecraft.setScreen(ancestro)`. Recorre `NavParent` con un límite de 16 niveles contra ciclos
- [ ] T046 [P] [US4] Añadir a `TEST` las pruebas de `Breadcrumbs.fit` (cabe entero; recorta por el principio; un solo tramo largo; lista vacía), con `String::length` como medida de ancho
- [ ] T047 [US4] Pasar siempre el padre al abrir una subpantalla (FR-024, FR-025): buscar `grep -rnE "\b[A-Z]\w+Screen\.open\(\)" src/main/java/net/hawthorn/dndsheets/client/gui dungeon/src/main/java species/src/main/java` (hoy hay 8, 5 en `GUI/DmPanelScreen.java` y `GUI/PlayerPanelScreen.java`: `TurnControlScreen`, `SpawnGenericScreen`, `CompendiumScreen`, `ContentTypeListScreen`, …). A cada una de esas pantallas, añadirle `open(@Nullable Screen parent)` que lo pase a su constructor (la `open()` sin argumentos queda para los atajos de teclado y los comandos) y cambiar cada llamada hecha desde otra pantalla a `open(this)`. Hacer lo mismo con las acciones de cliente de los hitos de T027, que reciben la pantalla de la ruta como padre
- [ ] T048 [US4] Dibujar la ruta bajo `GuiStyle.ruleOrnate` en las 4 bases (`GUI/ListPickerScreen.java`, `GUI/FormPanelScreen.java`, `GUI/ModalDialogScreen.java` mediante un helper `renderHeaderExtras` que las subclases llaman tras `renderPanel`, y `GUI/ItemPickerScreen.java`), y desplazar `contentTop()`/`listTop()` lo justo **solo cuando hay padre**. Revisar a escala 2 y 3 las subclases que sobrescriben `listTop()`/`contentTop()` (`grep -rn "listTop()\|contentTop()" GUI DUN/client/gui`)

### Teclado (FR-026, FR-027)

- [ ] T049 [US4] En `GUI/components/TomeButton.java`: estado "seleccionada con el teclado" (borde de 1 px en `ACCENT_COLOR` + fondo de hover), distinto del estado bajo el cursor, usando el `isFocused()` de vanilla
- [ ] T050 [US4] En `GUI/ListPickerScreen.java#keyPressed`: ↑/↓, RePág/AvPág e Inicio/Fin mueven un índice seleccionado entre las filas visibles, activas y no cabecera del filtro actual; la lista se desplaza para que la fila se vea (con `ButtonListWidget.scrollTo(Button)`, que crea T028); Enter pulsa la seleccionada o, si no hay ninguna, conserva el comportamiento actual (primera coincidencia de la búsqueda). Con la búsqueda enfocada, las flechas no se envían al cuadro de texto ni lo vacían. La selección se reinicia al cambiar el filtro
- [ ] T051 [US4] En `GUI/PlayerSheetScreen.java#keyPressed`: ←/→ cambian a la pestaña anterior o siguiente de `enum Tab { OVERVIEW, SKILLS, COMBAT, SPELLS }`, salvo si un `EditBox` tiene el foco

### Ayuda (FR-011, FR-030) y teclas (FR-012, FR-013)

- [ ] T052 [US4] En `CORE/init/DndsheetsModKeyMappings.java`: nueva `KeyMapping HELP = new KeyMapping("key.dndsheets.help", GLFW.GLFW_KEY_F4, "key.categories.dndsheets")`, registrada en `registerKeyMappings`; fuera de cualquier pantalla abre la ayuda general. Añadir también a `GUI/WelcomeScreen.java` una línea de teclas para la ayuda con `HELP.getTranslatedKeyMessage()`, junto a las demás
- [ ] T053 [US4] En `CORE/compat/PatchouliBook.java` y `CORE/compat/PatchouliCompat.java`: `openEntry(ResourceLocation entry)` → `PatchouliAPI.get().openBookEntry(BOOK, entry, 0)`, que guarda la pantalla de origen; un listener de `ScreenEvent.Closing` (solo cliente) la restaura cuando se cierra el libro abierto así. Sin Patchouli, que `GuideBook` abra el libro escrito en la página de esa entrada
- [ ] T054 [US4] En las 4 bases (`GUI/ListPickerScreen.java`, `GUI/FormPanelScreen.java`, `GUI/ModalDialogScreen.java`, `GUI/ItemPickerScreen.java`): `protected ResourceLocation helpEntry()` con un valor por defecto por base; un `IconButton` "?" en la esquina superior derecha del panel con tooltip "Ayuda (<tecla>)" que usa `HELP.getTranslatedKeyMessage()`; y en `keyPressed`, `HELP.matches(keyCode, scanCode)` → `PatchouliCompat.openEntry(helpEntry())`
- [ ] T055 [US4] Sobrescribir `helpEntry()` en las pantallas que tienen entrada propia en la guía: ficha (`GUI/PlayerSheetScreen.java` → `character/sheet`), `GUI/DmPanelScreen.java` → `dm/panel`, `GUI/GrimoireScreen.java` → `combat/magic`, `GUI/CharacterSetupScreen.java` → `first_steps/getting_started`, `GUI/OnboardingPathScreen.java` y el menú central → entradas nuevas (T057), `DUN/client/gui/DungeonMapImportScreen.java` y `DUN/client/gui/DungeonTraceScreen.java` → `dm/dungeons`
- [ ] T056 [US4] Crear `GUI/KeyReferenceScreen.java` (extiende `ListPickerScreen`; las teclas sin asignar, como "Panel del DM" directo, se muestran como "Sin asignar" con la indicación de dónde asignarlas): recorre `Minecraft.getInstance().options.keyMappings`, filtra la categoría `key.categories.dndsheets` y la categoría `CATEGORY` de `DUN/client/gui/DungeonTracePreview.java`, y muestra por fila el nombre traducido y `getTranslatedKeyMessage()` en vivo, agrupadas con `addHeader` por categoría; enlazarla desde el menú central (Primeros pasos → Teclas)
- [ ] T057 [P] [US4] Crear las entradas de la guía `src/main/resources/assets/dndsheets/patchouli_books/guide/en_us/entries/first_steps/menu.json`, `path.json` y `keys.json` (menú central, primeros pasos, teclas), añadir su espejo en la lista de `GUI/GuideBook.java` como exige su comentario (la entrada `path.json` explica cómo reabrir la bienvenida desde el menú central, FR-002), y añadir los textos a los 8 archivos de `LANG` (`checkLanguageFiles` en `TEST` lo comprueba)

### Listas vacías (FR-014) y errores (FR-015)

- [ ] T058 [US4] Revisar todas las subclases de `ListPickerScreen` (`grep -rln "extends ListPickerScreen" src dungeon species`) y sobrescribir `emptyMessage()` donde hoy cae en el genérico "Nothing here yet.", con motivo y siguiente paso (por ejemplo: Personajes → "Aún no tienes personajes: créalo en Configurar personaje"); las claves van a los 8 archivos de `LANG`
- [ ] T059 [P] [US4] Añadir a los 8 archivos de `LANG` las claves de la ruta, el "?", la tecla de ayuda y la referencia de teclas
- [ ] T060 [US4] Validar quickstart §5 en `runClient` (escala 2 y 3, `es_es`), incluido reasignar la ayuda a F6 y comprobar que la ven el tooltip, la referencia de teclas y la bienvenida

**Checkpoint**: navegación y ayuda iguales en todas las pantallas.

---

## Phase 7: User Story 5 - Comodidad visual y de juego (P3)

**Goal**: todas las pantallas, incluida la ficha, cumplen `DESIGN.md`; en combate se ve sin menús el turno, la vida y la última tirada.

**Independent Test**: quickstart §6.

- [ ] T061 [US5] Listar todas las pantallas (`ls GUI/*Screen.java DUN/client/gui/*Screen.java` y las de `species`) en una tabla en `specs/001-onboarding-vtt/checklists/screens.md` con las columnas: pantalla, base, `/gui-review` hecho, escala 2 OK, escala 3 OK, `es_es` OK, **cada acción confirma algo visible** (FR-020, `DESIGN.md` §7), abre con padre (Esc no saca al mundo) y fallos encontrados
- [ ] T062 [US5] Pasar `/gui-review <ruta>` a cada pantalla de `checklists/screens.md` y anotar los hallazgos en la tabla
- [ ] T063 [US5] Corregir en las **bases y componentes** los hallazgos que se repiten en varias pantallas (recorte con "…" y tooltip en etiquetas y títulos, estados de control) antes de tocar pantallas sueltas: `GUI/ListPickerScreen.java`, `GUI/FormPanelScreen.java`, `GUI/ModalDialogScreen.java`, `GUI/components/*`
- [ ] T064 [P] [US5] Eliminar los colores hex escritos a mano que `DESIGN.md` §2 anota como deuda en `GUI/SheetAdjustScreen.java`, `GUI/components/TomeField.java` y `GUI/components/IconButton.java`, usando roles de `CORE/client/gui/GuiStyle.java` (no tocar la paleta propia de `TurnHudOverlay`/`ResourceHudOverlay`)
- [ ] T065 [US5] Corregir pantalla por pantalla los hallazgos restantes de `checklists/screens.md` hasta que todas las filas estén OK (ciclo de `DESIGN.md`: compilar → `runClient` → captura con F2 → arreglar los 3 peores → repetir, como mucho 3 rondas por pantalla)
- [ ] T066 [US5] Adaptar `GUI/PlayerSheetScreen.java` (FR-028): ruta y "?" (de US4), textos que no se cortan en las 4 pestañas a escala 2 y 3 en `es_es`, estados de control visibles; abrir una ficha guardada **antes** del cambio y comprobar que se ven todos sus datos sin migración (FR-029)
- [ ] T067 [US5] Verificar FR-019 en un combate de `runClient`: si es su turno, el jugador ve sin abrir menús que lo es (`CORE/client/TurnHudOverlay.java`), su vida (`CORE/client/ResourceHudOverlay.java` / `NameTagHp.java`) y su última tirada (`CORE/client/DiceRollOverlay.java`); si falta la última tirada cuando el dado ya desapareció, añadir al `TurnHudOverlay` una línea "Última tirada: N" con el último `DiceRollMessage` propio
- [ ] T068 [US5] Confirmación visible de cada acción (FR-020, `DESIGN.md` §7): para cada fila de `checklists/screens.md` con la columna de confirmación en fallo, añadir la confirmación en el chat o la barra de acción en el handler de servidor de esa acción (empezando por `GUI/DmPanelScreen.java` y el menú central)

**Checkpoint**: todas las filas de `checklists/screens.md` en OK.

---

## Phase 8: Polish & Cross-Cutting

- [ ] T069 [P] Actualizar `PROJECT_CONTEXT.md`: sección R3 (la ficha ya está rehecha sobre `ModalDialogScreen`; las "~105 posiciones" son de la ficha antigua); sección nueva "Onboarding" (estado en el NBT del jugador, `OnboardingMessage`, registro de hitos y cómo un addon añade los suyos); invariante 1, con la nueva entrada de red
- [ ] T070 [P] Actualizar `DESIGN.md`: §4, el componente `Breadcrumbs`, `CoachMarks` y el estado de selección con teclado de `TomeButton`; §8, flechas/Enter en listas, ←/→ en la ficha, F4 de ayuda (y quitar "Arrow-key navigation of lists is still missing"); §5, la línea de ruta en la cabecera
- [ ] T071 [P] Añadir una entrada a `CHANGELOG.md` con lo nuevo visible para el usuario (menú central en P, primeros pasos, ayuda F4/"?", ruta de navegación, flechas en listas)
- [ ] T072 Anotar en `PROJECT_CONTEXT.md` > R4 (distribución) que P choca con *Interacciones sociales* de vanilla, como decisión pendiente (no se cambia aquí)
- [ ] T073 Ejecutar `./gradlew check` limpio y el [quickstart.md](quickstart.md) completo, §1 a §7, y marcar el resultado en `specs/001-onboarding-vtt/checklists/requirements.md`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (1)** → **Foundational (2)** → historias
- **US1 (3)** y **US2 (4)**: dependen solo de la fase 2. Una sola persona: US1 → US2 (T015 enlaza con `OnboardingPathScreen`, que crea T026; mientras no exista, el botón abre `CharacterSetupScreen`)
- **US3 (5)**: depende de la fase 2. Su `CoachMarks` es independiente. El "?" de las pantallas del addon llega con US4
- **US4 (6)**: depende de la fase 2. T055 y T056 usan pantallas de US2 (menú, ruta); si US2 no está, esas filas se omiten
- **US5 (7)**: después de US4, porque revisa pantallas que US4 cambia (cabecera, "?", foco)
- **Polish (8)**: al final

### Within Each Story

- Lógica pura y registro → servidor → red → pantallas → idioma → validación en `runClient`
- Las tareas de `LANG` [P] se pueden hacer en cualquier momento de su fase, pero antes de su validación

### Parallel Opportunities

- Fase 2: T003, T004, T005, T009, T011 y T012 van en paralelo (archivos distintos); T006 → T007 → T008 en orden
- US2: los 6 puntos de marca T020-T025 tocan archivos distintos → en paralelo; T030 → T031 (los textos hablan de lo que hace P después de T030)
- US3: T035 (opción de cliente) antes de T036 (`CoachMarks`), que la consulta; T042 (idioma) en paralelo con ambas
- US4: T046, T057 y T059 en paralelo; T044 → T045 → T047 (padres en cada subpantalla) → T048 en orden; T049 → T050
- US5: T064 en paralelo con T062

---

## Parallel Example: User Story 2

```text
Task: "T020 Marcar first_attack en CORE/CombatManager.java"
Task: "T021 Marcar first_spell en CORE/SpellCastManager.java"
Task: "T022 Marcar monster_spawned en MonsterSpawnMessage/SpawnGenericMessage/MonsterCommand"
Task: "T023 Marcar combat_started en CORE/TurnManager.java#startAt"
Task: "T024 Marcar character_created en CORE/SheetLoader.java#saveServer"
Task: "T025 Marcas de cliente en PlayerSheetScreen y DmPanelScreen"
```

## Parallel Example: Foundational

```text
Task: "T003 OnboardingState (puro)"
Task: "T005 OnboardingMilestones (registro)"
Task: "T009 OnboardingClient"
Task: "T012 Claves de idioma de los hitos core"
```

---

## Implementation Strategy

### MVP First (fase A del plan = US1 + US2)

1. Fases 1 y 2.
2. US1 → **validar quickstart §2**.
3. US2 → **validar quickstart §3**.
4. Publicable: bienvenida por persona, primera tirada, ruta y menú central.

### Incremental Delivery

1. MVP (US1 + US2)
2. US4 (fase B: navegación y ayuda en todas las pantallas)
3. US3 (fase C: importador y editor de salas)
4. US5 (fase D: repaso visual)

US4 se adelanta a US3 a pesar de compartir prioridad: llega a las 68 pantallas a la vez y US3 aprovecha su "?".

---

## Notes

- Las tareas [P] tocan archivos distintos y no dependen de nada pendiente.
- Cada historia termina con una validación en `runClient` porque el autotest no ve píxeles.
- Un commit por tarea o grupo lógico; `./gradlew check` antes de cada commit que toque red o `LANG`.
