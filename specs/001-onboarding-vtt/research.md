# Research: Incorporación guiada al VTT

Fase 0 del plan. Cada decisión parte de lo que el código ya tiene; las rutas citan el estado del repo el 2026-11-06.

## Lo que ya existe (y cambia el punto de partida)

| Pieza | Dónde | Qué hace hoy | Hueco frente a la spec |
|---|---|---|---|
| Toast de primera entrada | `SheetLoader` (~l.157) → `TutorialOpenMessage(isDm, firstJoin=true)` | Toast de esquina con la tecla de la ficha | Nada que hacer; se conserva |
| Pantalla de bienvenida | `WelcomeScreen` (ModalDialogScreen), abierta por `ScreenActionMessage.ONBOARDING_OPEN` | Una vez por **personaje** (flag `onboarded` en la ficha), lista 4 teclas, botones "Configurar personaje" / "Guía" / "Cerrar" | Va por personaje, no por persona; no nombra el rol; no propone tirar un dado; no se reabre; no se pospone en combate |
| Lista de progreso del personaje | `CharacterSetupScreen` | ✔/○ por paso de creación | Solo cubre el personaje |
| Panel de jugador | `PlayerPanelScreen` (ListPickerScreen, secciones) | Se abre desde la ficha (H) | No es la puerta de entrada (P abre el del DM) |
| Panel del DM | `DmPanelScreen` (5 secciones, 17 filas) | Tecla P para todos; el servidor filtra cada acción | El cliente no sabe el rol |
| Guía | `GuideBook` + libro Patchouli (`dndsheets:guide`) | Patchouli es dependencia **obligatoria** (`build.gradle` hardDeps) | `PatchouliBook` solo abre el libro entero, nunca una entrada |
| HUD de combate | `TurnHudOverlay`, `ResourceHudOverlay`, `DiceRollOverlay`, `NameTagHp` | Orden de iniciativa, turno, recursos, dado animado | FR-019 está casi cubierto; solo verificar |
| Navegación | `ListPickerScreen.parent`, `FormPanelScreen.parentScreen()`; `ModalDialogScreen` sin padre | Esc vuelve al padre | Sin ruta visible ni navegación con flechas (`DESIGN.md` §8 lo anota como pendiente) |

**Lección ya aprendida en el código** (`TutorialOpenMessage`): un libro a pantalla completa al entrar "se cerraba por reflejo sin leerse". La bienvenida debe ser corta, con una sola acción, y no abrirse en el mismo tick que la carga.

---

## D1. Dónde guardar el progreso por persona

- **Decision**: NBT persistente del jugador en el servidor (`player.getPersistentData()` bajo `Player.PERSISTED_NBT_TAG` → `dndsheets_onboarding`).
- **Rationale**: Va con la persona (UUID) y no con el personaje: un jugador puede tener varios (`SheetLoader.activeCharacter`) y `makeNew` crea una ficha desde cero, lo que borraría un progreso guardado en ella. Minecraft lo guarda solo con los datos del jugador, sobrevive a la muerte (por eso `PERSISTED_NBT_TAG`) y no necesita código de ficheros. No toca el formato de la ficha (invariante 8).
- **Alternatives considered**: (a) Una clave en la ficha: descartada porque es por personaje y se perdería con `makeNew`. Habría sido segura frente al guardado completo desde el cliente, porque `SheetServerMessage` solo acepta `PLAYER_EDITABLE_KEYS`. (b) Un JSON por jugador en `charactersheets/`: más código de E/S para lo mismo. (c) Solo en el cliente: se pierde al reinstalar y el servidor no podría marcar hitos.
- **Migración**: al entrar, si no hay estado y la ficha activa ya tiene `onboarded`, la bienvenida cuenta como vista y se infieren los hitos que la ficha delata (clase elegida → personaje creado, etc.). Cubre el caso límite del "usuario veterano".

## D2. Red: un mensaje parametrizado

- **Decision**: un único `OnboardingMessage` nuevo, registrado **al final** de `registerNetworkMessages`, con `Action` {`SYNC`, `MARK`, `DISMISS_WELCOME`, `OPEN_WELCOME`, `RESET`} y una carga de texto. `SYNC` (servidor→cliente) lleva también `isDm`. Ver `contracts/onboarding-message.md`.
- **Rationale**: invariantes 1-3. Uno parametrizado en vez de cinco clases. Mandar el rol en `SYNC` resuelve que el cliente no sepa si es DM (`PlayerPanelScreen` lo comenta) sin tocar los permisos: el servidor sigue filtrando cada acción.
- **Alternatives considered**: ampliar `ScreenActionMessage` (servidor→cliente solamente, sin carga útil) o `SheetAdjustMessage` (es de la ficha, con `targetUuid`); ninguno encaja sin forzarlo.
- **Obligatorio**: actualizar `NETWORK_SHAPE`, `NETWORK_ORDER` y `NETWORK_WIRE` (los comprueba `checkNetworkShape`) y subir la versión del protocolo como en los cambios anteriores.

## D3. Cómo se detecta que un hito está hecho

- **Decision**: el servidor marca en los puntos por los que ya pasa la acción real: `/roll` (`RollCommand.roll`), la resolución de ataques, el lanzamiento de conjuros, la invocación de monstruos, el inicio de combate (`TurnManager`), la importación de mapas y el pegado de salas (addon `dungeon`). El cliente solo envía `MARK` para hitos que únicamente él ve (abrir la ficha, abrir el menú central, los `seen_*`) y para `dungeon_import`, porque `DungeonMapImporter.read` solo corre en el cliente. `character_created` se marca en `SheetLoader.saveServer`, por donde pasan todas las escrituras de ficha. El servidor descarta los ids que no conoce. La lista exacta está en `contracts/onboarding-message.md`.
- **Rationale**: FR-004 pide que cuente "por cualquier vía". Marcar donde la acción ocurre lo cumple sin duplicar lógica en cada botón.
- **Modularidad**: el core no puede depender del addon. Hay un registro `OnboardingMilestones.register(...)`: el core registra los suyos y el addon `dungeon` los dos suyos al arrancar. Sin el addon, esos hitos no existen ni se muestran.

## D4. El menú central (FR-021..023)

- **Decision**: P abre un menú según el rol, que es `PlayerPanelScreen` ampliado (se mantiene el nombre de la clase). Secciones: Primeros pasos (bienvenida, ruta, teclas, guía) · Personaje · Tiradas y combate · Magia · Mesa · y, si `isDm`, **Herramientas del DM**, con una fila por cada sección de `DmPanelScreen` que lo abre desplazado hasta esa sección (`DmPanelScreen.open(parent, section)`). Se descartó una sola fila "Panel del DM" porque FR-021 pide ver las funciones agrupadas desde el menú, y se descartó copiar las 17 filas del panel porque duplicaría la pantalla y su mantenimiento. Cada fila muestra su atajo a la derecha, en color apagado, si tiene uno.
- **Rationale**: reutiliza `ListPickerScreen` (secciones, búsqueda automática desde 14 filas) y el panel del DM ya agrupado. Un solo punto de entrada sin perder ningún acceso.
- **Coste mitigado**: desde P, el DM necesita un clic más para llegar a su panel. Por eso se añade la tecla `key.dndsheets.dmpanel_direct` ("Panel del DM", sin asignar por defecto, así que no choca con nada), que abre el panel directamente como hacía P. Si el clic extra molesta en las pruebas, la sección "Herramientas del DM" va primero cuando `isDm`.
- **Hallazgo aparte**: P también es la tecla por defecto de *Interacciones sociales* en vanilla 1.20.1. Este choque ya existe hoy y no se arregla aquí; se anota para R4 (distribución).

## D5. Ruta de pantallas bajo el título (FR-024, FR-025)

- **Decision**: una interfaz mínima `NavParent { Screen parentScreen(); }` en las cuatro bases (`ModalDialogScreen` gana un padre opcional). Un componente `Breadcrumbs` recorre la cadena de padres, toma el `getTitle()` de cada pantalla, dibuja "Menú › Panel de DM › Mazmorras" en `SUBTITLE_COLOR` bajo la regla del título y, al pulsar un tramo, hace `setScreen(ancestro)`. Si no cabe, se recorta por el principio con "…" y el tramo actual nunca se pierde.
- **Rationale**: la información ya existe (`parent`); solo hay que mostrarla. Al hacerlo en las bases llega a las 68 subclases de una vez (`R1` del roadmap: "la palanca es estructural").
- **Riesgo**: la línea añade unos 10 px de cabecera. Las pantallas que sobrescriben `listTop()`/`contentTop()` deben revisarse a escala 2 y 3. Las pantallas que el servidor vuelve a abrir ya conservan su padre (`DESIGN.md` §8), así que la ruta se mantiene.
- **Lógica pura**: el recorte de la ruta es una función sin Minecraft y se prueba en el autotest.

## D6. Flechas y Enter en listas (FR-026, FR-027)

- **Decision**: en `ListPickerScreen.keyPressed`: ↑/↓ (y RePág/AvPág, Inicio/Fin) mueven un índice seleccionado entre las filas visibles, activas y no cabecera, del filtro actual. Enter pulsa la fila. La lista se desplaza para mantenerla visible. La selección usa el estado de foco que `TomeButton` debe distinguir del estado bajo el cursor. Con la búsqueda enfocada, las flechas mueven la selección y las letras siguen yendo al cuadro. Enter pulsa la seleccionada o, si no hay, la primera coincidencia (el comportamiento de hoy).
- **Alternatives considered**: la navegación por foco de vanilla 1.20 (Tab/flechas sobre los widgets). Se descarta porque no sabe desplazar `ButtonListWidget` ni saltarse las cabeceras.
- **Ficha**: ←/→ cambian de pestaña en `PlayerSheetScreen` (FR-028).

## D7. Ayuda contextual (FR-011, FR-030) y referencia de teclas (FR-012, FR-013)

- **Decision**: cada base expone `protected ResourceLocation helpEntry()`, por defecto la entrada de la categoría más cercana, y las pantallas clave la sobrescriben. Un `IconButton` "?" con tooltip en la esquina superior derecha del panel y una `KeyMapping` "Ayuda" (por defecto **F4**) abren la entrada con `PatchouliAPI.get().openBookEntry(book, entry, 0)`. Para volver, se guarda la pantalla de origen y, cuando se cierra el libro de Patchouli (`ScreenEvent.Closing`), se restaura.
- **Por qué F4**: vanilla no la usa sola (solo en la combinación F3+F4) y, al no ser una letra, funciona aunque haya un campo de texto con foco. Es reasignable como cualquier tecla.
- **Referencia de teclas**: una `ListPickerScreen` que recorre `Minecraft.options.keyMappings` filtrando la categoría del mod y la de la vista previa del addon. Muestra `getTranslatedKeyMessage()` en vivo, así que una tecla reasignada se ve tal cual (FR-012/013). `WelcomeScreen` ya dibuja así sus teclas.
- **Sin Patchouli**: no puede pasar (es obligatorio), pero `GuideBook` mantiene la vía del libro escrito; se abre en la página de la entrada.

## D8. Bienvenida (FR-001, FR-002, FR-016)

- **Decision**: se reutiliza `WelcomeScreen`. Pasa a ser por persona (D1), nombra el rol y su botón principal es **"Tirar un d20"**, que manda `roll 1d20`: el resultado sale por la vía de siempre y marca el hito `dice`. Debajo, "Ver mis primeros pasos" lleva a la ruta. Se reabre desde el menú central (`OPEN_WELCOME`).
- **Combate (FR-016)**: antes de abrirla, el servidor comprueba `TurnManager.isParticipant(player)` (método nuevo: hay combate activo y el jugador está en el orden de turnos); si es así la pospone hasta que termine el combate.
- **Resultado legible**: hoy `/roll` responde en el chat. Para cumplir FR-001 escenario 2 se envía también el `DiceRollMessage` existente cuando la expresión es un solo d20. Es un mensaje que ya existe, no uno nuevo.

## D9. Primer uso del importador y del editor de salas (FR-008..010)

- **Decision**: un componente compartido en el core, `CoachMarks`: una secuencia de 3 a 5 globos que señalan widgets por su rectángulo, con "Siguiente" y "Saltar". Se muestra una vez por persona, controlado por un hito "visto" (`seen_import_help`, `seen_trace_tour`). `DungeonMapImportScreen` lo usa para formatos, ejemplo y qué pasa después; `DungeonTraceScreen` para dibujar, borrar, puertas, niveles y vista previa.
- **Mapa de ejemplo**: las muestras de `dungeongen/` (donjon, Universal VTT, Dungeon Scrawl) son de prueba y su procedencia no está documentada. **No se publican** hasta comprobar su licencia; mientras tanto, la ayuda explica dónde generar uno gratis (donjon) y con qué formato.
- **Deshacer (FR-010)**: `PasteHistory` ya guarda los bloques; solo faltan los avisos. En la confirmación previa: "Podrás deshacerlo con …". Al terminar, en la barra de acción (`DESIGN.md` §7): "Pegado. Deshacer: …".

## D10. Desactivar los consejos (FR-018)

- **Decision**: una opción de la configuración de cliente (`showOnboardingHints`, verdadera por defecto). Al desactivarla se ocultan los globos y las sugerencias de la ruta; la ayuda manual ("?" y F4) sigue funcionando.
- **Rationale**: es una preferencia de presentación de esa máquina; el cliente es el sitio más simple para guardarla. El progreso sigue en el servidor.

## D11. Repaso visual de todas las pantallas (FR-020, FR-028, SC-007)

- **Decision**: primero las bases (ruta, "?", estado de foco, recorte con tooltip) y después pantalla por pantalla: `/gui-review <ruta>` como lista estática y `runClient` a escala 2 y 3 con textos en español (el ciclo de revisión de `DESIGN.md`). También se salda la deuda que `DESIGN.md` §2 anota: colores escritos a mano en `SheetAdjustScreen`, `TomeField` e `IconButton`. Los HUD conservan su paleta propia, como dice la guía.
- **Ficha**: `PlayerSheetScreen` ya está rehecha sobre `ModalDialogScreen` y se adapta a la ventana. Gana la ruta, el "?" y las flechas entre pestañas; el formato de la ficha no cambia (FR-029 se cumple solo). `PROJECT_CONTEXT.md` R3 se actualiza porque todavía describe la ficha antigua.

## D12. Pruebas

- **Decision**: la lógica que no depende de Minecraft va en clases puras y se prueba en `JsonContentSelfTest` (lo ejecuta `gradlew check`): el estado de los hitos y sus transiciones, la (de)serialización del NBT, el recorte de la ruta, la migración desde `onboarded` y la forma de red. `checkLanguageFiles` ya obliga a tener las claves en los 8 idiomas (FR-017). Lo visual se valida con `quickstart.md` en `runClient`.

## Sin resolver (no bloquea)

- Procedencia y licencia de las muestras de `dungeongen/` (D9). Hasta resolverlo, no se publica ninguna muestra.
- Objetivo de rendimiento: no aplica; no hay cálculos pesados. Se pide que abrir el menú y la ruta no se note (menos de un fotograma de diferencia).
