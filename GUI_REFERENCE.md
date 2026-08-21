# Referencia de GUIs — dndsheets

Catálogo de todas las pantallas del mod (`net.hawthorn.dndsheets.client.gui`) con sus tamaños, posiciones y texturas, pensado para consultarse antes de crear o modificar una interfaz — tanto por personas como por una IA — en vez de rastrear el código Java disperso.

El mod usa la API de GUI **vanilla de Minecraft/Forge** (`net.minecraft.client.gui.*`), sin motor de layout ni JSON: cada pantalla define sus posiciones como constantes `private static final int ..._X/_Y` (a veces `_SIZE_X/_Y`, `_SEPARATION`) codificadas a mano en Java.

## Sistema de coordenadas

- **Pantallas de contenedor** (`AbstractContainerScreen<T>`, registradas como menú): el origen es `this.leftPos`/`this.topPos` (esquina superior izquierda del panel, centrado automáticamente por Minecraft según `imageWidth`/`imageHeight`). Todas las constantes `_OFFSET_X/_OFFSET_Y` de estas pantallas son relativas a ese origen.
- **Pantallas planas** (`Screen`, abiertas a mano con `Minecraft.getInstance().setScreen(...)`): no hay panel de fondo fijo; los widgets se centran calculando contra `this.width`/`this.height` en cada `init()`.
- **Diálogos modales** (extienden `ModalDialogScreen`): caja centrada de tamaño fijo `dialogWidth x dialogHeight`; los botones se añaden con `addModalButton(x, y, width, height, message, onPress)`, donde x/y ya son relativos a la esquina superior izquierda de la caja (ver sección `ModalDialogScreen` más abajo).

## Widgets y bases compartidas

Documentados una sola vez aquí; las pantallas que los usan solo indican qué instancia crean (posición/tamaño), no repiten su comportamiento.

- `GuiStyle` (`client/gui/GuiStyle.java`) — colores y panel de fondo compartidos por toda pantalla plana sin textura propia.
- `TomeButton` (`client/gui/components/TomeButton.java`) — fila y botón con la identidad del mod: tira de pergamino sobre cuero, filete de latón a la izquierda y biselado de Minecraft. **Sustituye al botón gris de vanilla en todo el mod: no queda ni un `Button.builder` en `src/main/java`.** Las tres bases (`ListPickerScreen.addRow`, `SmallFormScreen`, `ModalDialogScreen.addModalButton`) cubren la mayoría, pero **seis pantallas fabrican los suyos a mano** —`SheetAdjustScreen` (10), `CharacterSheetScreen` (4), `CharacterListScreen` (3), `GrimoireScreen` (2), `RollEditorScreen` y `AdvancedRollEditorScreen`— y hay que convertirlas una a una. Al añadir un botón nuevo, usar `TomeButton.of(mensaje, onPress, x, y, w, h)`; `Button.builder(...)` sale gris y desentona.
- `DirectionalCycleButton` **extiende `TomeButton`**, no `Button`: siendo un `Button` pelado se pintaba gris pese al rediseño. El foco se marca por **dos** vías —fondo y filete encendido, más texto aclarado— porque un solo cambio de tono sobre fondo oscuro no se distingue con brillo bajo. El texto se centra en el hueco que queda tras el filete, no en el botón entero, o quedaría descuadrado.
- `ListPickerScreen` (`client/gui/ListPickerScreen.java`) — base para pantallas de lista/menú vertical de botones. Maneja la navegación "&lt; Atrás" (ver más abajo). Dibuja un filete de latón bajo el título: separa la cabecera sin gastar una fila entera de alto.
- `ModalDialogScreen` (`client/gui/ModalDialogScreen.java`) — base para diálogos centrados de tamaño fijo. Usada por `RestChoiceScreen`, `RestVoteScreen`, `DeathSaveScreen`.
- `SmallFormScreen` (`client/gui/SmallFormScreen.java`) — base para formularios cortos de una columna. Usada por `SpawnGenericScreen`, `AddTurnEffectScreen`, `AddMonsterAttackScreen`, `DungeonPieceAddScreen`, `DungeonPieceEditScreen`, `DungeonGenerateScreen`, `DungeonJigsawConfigureScreen`. Misma navegación "&lt; Atrás" que `ListPickerScreen`.
- `AdjustableImageButton` (`client/gui/components/AdjustableImageButton.java`)
- `ButtonListWidget` (`client/gui/components/ButtonListWidget.java`)
- `RollScrollWidget` (`client/gui/components/RollScrollWidget.java`)

### GuiStyle

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/GuiStyle.java`
- **Propósito:** único sitio que define el aspecto de las pantallas "planas" (sin textura de fondo): un panel `0xCC101010` con borde de 1px `0xFF3E3E3E` (método estático `panel(guiGraphics, left, top, right, bottom)`), más las constantes de color `TITLE_COLOR` (`0xFFFFFF`), `SUBTITLE_COLOR` (`0xAAAAAA`) y `MUTED_COLOR` (`0x888888`, texto de estado vacío). Antes cada pantalla flotaba con sus botones sueltos sobre el fondo borroso vanilla sin ningún panel — la única excepción ad hoc era el relleno manual que tenía `DeathSaveScreen`.
- **Consumido por:** `ListPickerScreen`, `ModalDialogScreen.renderPanel()`, `SmallFormScreen`, `SheetAdjustScreen` — es decir, todas las pantallas sin textura del mod.
- **Identidad visual (rediseño):** tomo encuadernado en cuero con cantoneras de latón, dibujado con el **biselado de Minecraft** (claro arriba/izquierda, oscuro abajo/derecha). El tema de D&D entra por el color y las cantoneras, no por romper esa gramática — un panel que no bisela se lee como una ventana pegada encima del juego. El relleno es casi opaco a propósito: translúcido sobre un bioma nevado deja el texto ilegible.
- **Colores:** `TITLE_COLOR` es pergamino y no blanco puro (el blanco absoluto sobre cuero oscuro vibra); `ACCENT_COLOR` es latón envejecido. `rule()` dibuja un filete horizontal para separar secciones sin que cada pantalla invente su propio color de línea.

### ListPickerScreen

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/ListPickerScreen.java`
- **Extiende:** `net.minecraft.client.gui.screens.Screen`
- **Propósito:** título centrado (`y=16`) + panel `GuiStyle` + una `ButtonListWidget` con scroll automático. Cubre tanto los selectores "elige uno de varios" como los menús de botón fijo (Panel de DM, Modo turnos) — un menú fijo es solo una lista que nunca llega a desbordar.
- **API para subclases:** `buildRows()` (abstracto, llamado desde `init()`, añade filas con `addRow(Component, Button.OnPress)`); `buttonWidth()` (default 200, sobrescribible — `GrimoireScreen` usa 220); `listTop()`/`listHeight()` (sobrescribibles para dejar hueco a un subtítulo o a un botón fijo bajo la lista); `emptyMessage()` (texto centrado si la lista queda vacía, default ninguno).
- **Notable:** `init()`/`render()` no son `final` — una pantalla con contenido extra (subtítulo de `GrimoireScreen`, botón fijo bajo la lista de `GrimoireScreen`) sobrescribe, llama a `super` primero y añade lo suyo. `mouseScrolled` e `isPauseScreen() -> false` ya están resueltos aquí, no hace falta repetirlos.
- **Navegación (constructor `(Component title, Screen parent)`, o `(Component title)` para una pantalla raíz):** si `parent` no es null, `init()` añade un botón "&lt; Atrás" en la esquina superior izquierda del panel, y `onClose()` (Escape, o cualquier fila/botón que llame a `this.onClose()`) vuelve a `parent` en vez de cerrar el menú entero. Cada pantalla captura su `parent` en su propio `open(...)` estático con `Minecraft.getInstance().screen` — la pantalla que estaba visible en el momento de abrir esta — así que los sitios que llaman a `open(...)` no necesitan pasar nada extra. Sin `parent` (pantallas raíz: `DmPanelScreen`, `MonsterActionScreen`) no hay botón "Atrás" y Escape cierra el menú, igual que antes.
- **Usada por:** `DmPanelScreen`, `TurnControlScreen`, `PlayerPickerScreen`, `TraitGrantScreen`, `CharacterOptionListScreen`, `ManageCustomAttacksScreen`, `MonsterActionScreen`, `PresetScreen`, `GrimoireScreen`, `DungeonPieceListScreen`, `ConditionListScreen`, `CharacterListScreen`, `PartyScreen`, `CompendiumScreen`, `CompendiumListScreen`.

### Pantallas de personajes y condiciones

Las tres cuelgan de `ListPickerScreen` sin layout propio; lo único reseñable de cada una es de dónde saca sus datos y por qué.

| Pantalla | Se abre desde | Datos | Notas |
|---|---|---|---|
| `ConditionListScreen` | `SheetAdjustScreen` → «Condiciones…» | `conditionsCsv` del `SheetSummaryMessage` que ya traía oro/PG/CA | Alterna las 14 condiciones de 5e. **Sin buscador a propósito**: alternar una reconstruye la pantalla (`rebuildWidgets()`) y eso vaciaría la caja en cada clic. |
| `CharacterListScreen` | `/dndchar` sin argumentos | `RosterListMessage(MINE, …)` | Pulsar una fila manda `SWITCH`; **no** repinta en local, espera la lista nueva del servidor, que es quien decide si el personaje era tuyo. |
| `PartyScreen` | Panel de DM → «Grupo» | `BrowseListMessage(PARTY, …)` | Solo lectura. `buttonWidth()` a 260: a 200 se cortaba la fila (nombre + PG + CA + condiciones). |
| `CompendiumScreen` | `/dndcompendium`, Panel de DM → «Compendio» | ninguno (solo elige categoría) | Menú fijo de cuatro filas. |
| `CompendiumListScreen` | `CompendiumScreen` | `BrowseListMessage(CONTENT, …)` | **Con buscador**: 330 monstruos sin filtrar no son consultables. Los ids llegan como `categoria\|id` y se devuelven tal cual, así que la pantalla no deduce nada. |
| `CompendiumEntryScreen` | pulsar una fila del compendio | `BrowseListMessage(DETAIL, …)` | `ModalDialogScreen`, no `ListPickerScreen`. El ajuste de línea se hace en `init()`, no en `render()`, que corre 60 veces por segundo. |

El estado llega **ya formateado desde el servidor** en todas: el cliente solo lo pinta, así que componer el texto allí evita mandar media hoja de personaje por la red. Y ninguna registró un mensaje nuevo salvo el par `Roster*`, que no tenía equivalente reutilizable — `ConditionListScreen` viaja entera sobre `SheetSummaryMessage` y `SheetAdjustMessage`, que ya existían.

### AdjustableImageButton

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/components/AdjustableImageButton.java`
- **Extiende:** `net.minecraft.client.gui.components.Button`
- **Propósito:** botón con textura de imagen (icono/sprite) en vez del rectángulo vanilla, con color de texto ajustable.
- **Constructor (forma completa):** `(x, y, width, height, xTexStart, yTexStart, yDiffTex, resourceLocation, textureWidth, textureHeight, onPress, message)` — `xTexStart/yTexStart` origen del sprite en el atlas, `yDiffTex` desplazamiento vertical por estado (hover/pressed, patrón vanilla de 3 franjas), `textureWidth/textureHeight` tamaño total del atlas (default 256x256 en las sobrecargas cortas).
- **Notable:** color de texto por defecto `0xF4F3F3` combinado con alpha animado. Método público `setImage(resourceLocation, xTexStart, yTexStart, yDiffTex, textureWidth, textureHeight)` para cambiar el sprite en caliente — usado por `CharacterSheetScreen.updateTabs()` para alternar el icono de pestaña activa/inactiva.

### ButtonListWidget

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/components/ButtonListWidget.java`
- **Extiende:** `net.minecraft.client.gui.components.AbstractScrollWidget`
- **Propósito:** lista vertical de botones de ancho completo con scroll automático; usada por las pantallas "elegir uno de varios" del Panel de DM (`PlayerPickerScreen`, `TraitGrantScreen`, `CharacterOptionListScreen`, `ManageCustomAttacksScreen`, `MonsterActionScreen`, `PresetScreen`, `GrimoireScreen`).
- **Constructor:** `(x, y, width, height, rowHeight)`.
- **Notable:** scissor/clipping con margen de 1px; solo renderiza/posiciona filas dentro del rango visible (optimización para listas largas); `mouseClicked` siempre `false` (los clics van a los botones hijos). Método público: `addRow(Button button)`. Los botones deben registrarse también en la pantalla vía `Screen#addWidget` (no `addRenderableWidget`).
- **Convención de uso repetida en las pantallas que la usan:** `x=(width-200)/2, y=30, w=BUTTON_WIDTH=200, h=height-44` (o `height-44-BUTTON_HEIGHT-SPACING` si hay un botón fijo debajo tipo "Cancelar"/"Borrar todos"), paso de fila `BUTTON_HEIGHT(20)+SPACING(4)=24`.

### RollScrollWidget

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/components/RollScrollWidget.java`
- **Extiende:** `net.minecraft.client.gui.components.AbstractScrollWidget`
- **Propósito:** lista scrolleable de "items de tirada" (pestaña Ataques de `CharacterSheetScreen`) — cada fila agrupa un `EditBox` de nombre + botones de tirada + botones de edición + botón de borrar, con modo edición.
- **Constructor:** `(x, y, width, height, message)`. Campos ajustables: `separation` (alto de fila, default 20), `scrollCutoff` (nº de items a partir del cual aparece scroll, default 8).
- **Notable:** scissor/clipping igual que `ButtonListWidget`. Layout de fila: `deleteButton` en `x+4`; botones roll/edit apilados desde la derecha (`width - 20*j - 20`); `EditBox` de nombre en `x+16`. Solo se muestran botones de rol si `!editMode`, solo de edición si `editMode`.
- **Métodos públicos:** `addListItem(EditBox nameBox, List<Button> rollButtons, List<Button> editButtons, Button deleteButton)`, `int removeListItem(Button deleteButton)`, `int getListSize()`, `void setActive(boolean)`, `void setEditMode(boolean)`, `String[] getNames()`, `EditBox[] getEditBoxes()`, `int getIndex(Button)`. Todos los widgets internos deben registrarse primero con `Screen#addWidget`.

## Registro de pantallas

Solo 3 de las 25 pantallas son `AbstractContainerScreen` registradas como menú real (`init/DndsheetsModScreens.java`): `CharacterSheetScreen`, `RollEditorScreen`, `AdvancedRollEditorScreen`. El resto son `Screen` planas abiertas imperativamente vía un método estático `open(...)`.

---

## CharacterSheetScreen

**Fondos (rediseño).** Los tres PNG (`character_sheet.png`, `_2`, `_3`) se generan con `tools/make_sheet_bg.py`: pergamino envejecido dentro del mismo marco de cuero y latón que dibuja `GuiStyle`. Se dibujan a 4× (1592×1152) del tamaño lógico del blit (398×288) porque Minecraft los reduce, y ese supersampling evita que el grano se vea a bloques con GUI Scale alto.

Dos cosas que el fondo **ya no lleva**, y no es un olvido:

- **Texto.** Las etiquetas las dibuja `renderLabels()` desde claves de traducción. Tenerlas también en el PNG las duplicaba y las dejaba en inglés para siempre.
- **Casillas.** Los `EditBox` de Minecraft pintan su propio marco encima, así que las casillas del fondo eran redundantes — y obligaban a que el dibujo cuadrara al píxel con unas coordenadas que solo viven en el código. Sin ellas no hay nada que alinear ni que se pueda desalinear al mover un campo.

**Tinta.** `renderLabels()` usaba dos colores (`lightColor` blanco y `darkColor` casi negro) repartidos sin criterio aparente entre etiquetas vecinas, sobre un fondo **blanco**: la mayoría eran blanco sobre blanco, invisibles, y lo que las tapaba era el texto horneado del PNG. Ahora hay una sola tinta (`INK_COLOR`), y `AUTO_FIELD_COLOR` pasó de ámbar claro (pensado para fondo oscuro) a ámbar quemado, legible sobre pergamino.

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/CharacterSheetScreen.java`
- **Tipo:** `AbstractContainerScreen<CharacterSheetMenu>` (registrada como menú)
- **Cómo se abre:** vía `CharacterSheetMenu` / `CharacterSheetOpenMessage` (paquete de red), no por `open()` estático
- **Texturas de fondo:** 3 fondos según la pestaña activa (398x288, dibujados en `leftPos-24, topPos-24`):
  - MAIN: `dndsheets:textures/screens/character_sheet.png`
  - SKILLS: `dndsheets:textures/screens/character_sheet_2.png`
  - ATTACKS: `dndsheets:textures/screens/character_sheet_3.png`
- **Iconos de habilidad** (16x16, en `ABILITY_OFFSET_X + 25`, `ABILITY_OFFSET_Y + n*ABILITY_SEPARATION`): `str.png`, `dex.png`, `cons.png`, `int.png`, `wis.png`, `cha.png`
- **Tamaño del panel:** `imageWidth = 350`, `imageHeight = 240`

### Panel lateral (compartido entre las 3 pestañas)

| Widget | Tipo | X | Y | Ancho | Alto | Notas |
|---|---|---|---|---|---|---|
| characterName | EditBox (placeholder) | NAME_OFFSET_X=15 | NAME_OFFSET_Y=20 | 85 | 18 | |
| strength | EditBox (placeholder) | ABILITY_OFFSET_X=57 | ABILITY_OFFSET_Y=55 | ABILITY_SIZE_X=20 | ABILITY_SIZE_Y=18 | |
| dexterity | EditBox (placeholder) | 57 | 55 + 1×ABILITY_SEPARATION(22) | 20 | 18 | |
| constitution | EditBox (placeholder) | 57 | 55 + 2×22 | 20 | 18 | |
| intelligence | EditBox (placeholder) | 57 | 55 + 3×22 | 20 | 18 | |
| wisdom | EditBox (placeholder) | 57 | 55 + 4×22 | 20 | 18 | |
| charisma | EditBox (placeholder) | 57 | 55 + 5×22 | 20 | 18 | |
| mainTab / skillsTab / attacksTab | AdjustableImageButton | — | topPos-12 (inactiva) / topPos-17 (activa) | 30 | 15 (inactiva) / 20 (activa) | textura `atlas/imagebutton_tabbutton.png` (inactiva) o `_active.png` (activa); ver `updateTabs()` |

### Pestaña MAIN

| Widget | Tipo | X | Y | Ancho | Alto | Notas |
|---|---|---|---|---|---|---|
| armorClass | EditBox | ACHP_OFFSET_X=125 | ACHP_OFFSET_Y=90 | — | — | color etiqueta ámbar (auto-calculado) |
| hitPoints | EditBox | 125 + 1×ACHP_SEPARATION(45) | 90 | — | — | ámbar, sincronizado desde `entity.getHealth()` |
| hitPointsMax | EditBox | 125 + 2×45 | 90 | — | — | ámbar, desde `entity.getMaxHealth()` |
| hitPointsTemp | EditBox | 125 + 3×45 | 90 | — | — | ámbar, desde `entity.getAbsorptionAmount()` |
| speed | EditBox | 125 + 4×45 | 90 | — | — | etiqueta color normal |
| characterRace | EditBox | RACE_OFFSET_X=125 | RACE_OFFSET_Y=20 | — | — | |
| characterClass | EditBox | CLASS_OFFSET_X=125 | CLASS_OFFSET_Y=55 | — | — | |
| background | EditBox | BACKG_OFFSET_X=235 | BACKG_OFFSET_Y=20 | — | — | |
| proficiency | EditBox | PROF_OFFSET_X=125 | PROF_OFFSET_Y=165 | — | — | ámbar, auto-calculado (2 + (nivel-1)/4) |
| hitDice / hitDiceTypes | EditBox | HITDICE_OFFSET_X=125 | HITDICE_OFFSET_Y=125 | — | — | |
| level | EditBox | LEVEL_OFFSET_X=125 | LEVEL_OFFSET_Y=205 | — | — | ámbar; sin hueco dedicado en la textura todavía, colocado en margen inferior |
| hunger | EditBox | HUNGER_OFFSET_X=220 | HUNGER_OFFSET_Y=205 | — | — | ámbar |
| initiativeButton / initiativeEditButton | ImageButton | INITIATIVE_OFFSET_X=304 | INITIATIVE_OFFSET_Y=165 | 16 | 16 | |
| grimoireButton | Button | GRIMOIRE_OFFSET_X=10 | BOTTOM_ROW_Y | BOTTOM_BUTTON_WIDTH=72 | BOTTOM_BUTTON_HEIGHT=16 | abre `GrimoireScreen` |
| charactersButton | Button | +BOTTOM_BUTTON_STEP=86 por botón | BOTTOM_ROW_Y | 72 | 16 | pide `BrowseActionMessage(LIST_MINE)`: abre `CharacterListScreen`, donde se cambia, se crea y se borra |
| presetsButton | Button | PRESETS_OFFSET_X=180 | PRESETS_OFFSET_Y=228 | 80 | 16 | abre `PresetScreen` |
| checkButtons/saveButtons (modo lectura) o checkEditButtons/saveEditButtons (modo edición) | ImageButton (d20) | — | — | 16 | 16 | `imagebutton_d20*.png`; visibilidad exclusiva según `editMode` |

### Pestaña SKILLS

Dos columnas de 9 filas cada una, separación vertical `SKILL_SEPARATION = 20`.

| Widget | Tipo | X | Y | Notas |
|---|---|---|---|---|
| Columna 1 (Atletismo, Acrobacias, Juego de Manos, Sigilo, Arcanos, Historia, Investigación, Naturaleza, Religión) | Label + skillButtons | SKILL_LIST1_OFFSET_X=135 | SKILL_LIST1_OFFSET_Y=15 + n×20 | fila 0 color claro, resto según habilidad |
| Columna 2 (Trato con Animales, Perspicacia, Medicina, Percepción, Supervivencia, Engaño, Intimidación, Interpretación, Persuasión) | Label + skillButtons | SKILL_LIST2_OFFSET_X=255 | SKILL_LIST2_OFFSET_Y=15 + n×20 | |

### Pestaña ATTACKS

| Widget | Tipo | X | Y | Notas |
|---|---|---|---|---|
| attackRolls | `RollScrollWidget` | — | — | lista con scroll (scissor), filas: nombre + botones de tirada/edición/borrado |
| addButton | Button | — | — | añade fila nueva a `attackRolls` |

- **Tabs/paneles:** `PanelStatus { MAIN, SKILLS, ATTACKS, NONE }`; `updateTabs()` alterna `active` **y** `visible` de cada widget según la pestaña — ambos flags son necesarios o el widget sigue dibujándose encima aunque esté inactivo.
- **Colores especiales:** `AUTO_FIELD_COLOR = 0xFFD37F` (ámbar, campos autocalculados/sincronizados: CA, PG, PG máx, PG temp, competencia, nivel, hambre); texto normal `0xFFFFFF`; texto oscuro secundario en SKILLS `0x1F1F1F`.

---

## RollEditorScreen

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/RollEditorScreen.java`
- **Tipo:** `AbstractContainerScreen<RollEditorMenu>` (registrada como menú)
- **Cómo se abre:** `RollEditorOpenMessage` (red); estado de trabajo en `workingIndex`/`workingCategory` (estáticos)
- **Textura de fondo:** `dndsheets:textures/screens/roll_editor.png`, dibujada a tamaño completo del panel
- **Tamaño del panel:** `imageWidth = 200`, `imageHeight = 175`

| Widget | Tipo | X | Y | Ancho | Alto | Notas |
|---|---|---|---|---|---|---|
| rollExpression | EditBox | X_OFFSET=30 | EXPRESSION_Y=37 | 134 | 18 | sugerencia de ejemplo cuando está vacío |
| adder_str | Button | 30 | BUTTONS_Y=82 | 40 | 20 | añade `" + $str"` |
| adder_dex | Button | 30+47=77 | 82 | 40 | 20 | añade `" + $dex"` |
| adder_con | Button | 30+94=124 | 82 | 40 | 20 | añade `" + $con"` |
| adder_int | Button | 30 | 82 + BUTTONS_SEPARATION(22) = 104 | 40 | 20 | añade `" + $int"` |
| adder_wis | Button | 77 | 104 | 40 | 20 | añade `" + $wis"` |
| adder_cha | Button | 124 | 104 | 40 | 20 | añade `" + $cha"` |
| adder_hprof | Button | 30 | 82 + 2×22 = 126 | 77 | 20 | añade `" + $hprof"` |
| adder_prof | Button | 30+83=113 | 126 | 51 | 20 | añade `" + $prof"` |

- **Etiquetas:** "Roll Expression" en `(X_OFFSET, EXPRESSION_Y-10)`, "Insert Modifiers" en `(X_OFFSET, BUTTONS_Y-10)`, color `0xFFFFFF`.

---

## AdvancedRollEditorScreen

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/AdvancedRollEditorScreen.java`
- **Tipo:** `AbstractContainerScreen<AdvancedRollEditorMenu>` (registrada como menú)
- **Cómo se abre:** `AdvancedRollEditorOpenMessage` (red); estado en `workingIndex`/`workingCategory`/`workingSubIndex` (estáticos)
- **Textura de fondo:** `dndsheets:textures/screens/advanced_roll_editor.png`, tamaño completo del panel
- **Tamaño del panel:** `imageWidth = 350`, `imageHeight = 224`
- **Estructura:** dos columnas idénticas (tiradas 1 y 2), mismas Y, X ancladas en `FIRSTROLL_X=29` y `SECONDROLL_X=185`

| Widget | Tipo | X (col.1 / col.2) | Y | Ancho | Alto | Notas |
|---|---|---|---|---|---|---|
| rollContext1 / rollContext2 | EditBox | 29 / 185 | CONTEXT_Y=52 | 134 | 18 | |
| rollExpression1 / rollExpression2 | EditBox | 29 / 185 | EXPRESSION_Y=84 | 134 | 18 | |
| adder_str(_2) | Button | 29 / 185 | BUTTONS_Y=130 | 40 | 20 | |
| adder_dex(_2) | Button | +47 | 130 | 40 | 20 | |
| adder_con(_2) | Button | +94 | 130 | 40 | 20 | |
| adder_int(_2) | Button | +0 | 130 + BUTTONS_SEPARATION(22) = 152 | 40 | 20 | |
| adder_wis(_2) | Button | +47 | 152 | 40 | 20 | |
| adder_cha(_2) | Button | +94 | 152 | 40 | 20 | |
| adder_hprof(_2) | Button | +0 | 130 + 2×22 = 174 | 77 | 20 | |
| adder_prof(_2) | Button | +83 | 174 | 51 | 20 | |

- **Etiquetas:** "Roll Editor 1/2" en Y=26, "Roll Context" en `CONTEXT_Y-10`, "Roll Expression" en `EXPRESSION_Y-10`, "Insert Modifiers" en `BUTTONS_Y-10`, color `0xFFFFFF`, replicadas en ambas columnas.

---

## DmPanelScreen

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/DmPanelScreen.java`
- **Tipo:** extiende `ListPickerScreen` (ver esa sección) — no registrada como menú
- **Cómo se abre:** `DmPanelScreen.open()`, disparado por el keybind `DndsheetsModKeyMappings.DM_PANEL` (comprueba permisos de operador antes)
- **Textura de fondo:** ninguna — panel `GuiStyle` dibujado por `ListPickerScreen`
- **Tamaño del panel:** pantalla completa, lista top-anchored en `y=30` (ver `ListPickerScreen`)

| Widget | Tipo | Notas |
|---|---|---|
| 6 filas ("Modo turnos", "Invocar NPC genérico", "Conceder rasgo", "Ajustes de hoja", "Aplicar preset a jugador", guía) | fila de `ListPickerScreen` | `buttonWidth()`/`BUTTON_HEIGHT`/`SPACING` por defecto (200/20/4) |

- Acciones: abren `TurnControlScreen`, `SpawnGenericScreen`, o `PlayerPickerScreen` (para elegir jugador antes de rasgo/ajustes/preset).

---

## ModalDialogScreen

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/ModalDialogScreen.java`
- **Tipo:** clase base abstracta, extiende `Screen`
- **Propósito:** caja de diálogo de tamaño fijo centrada en pantalla; evita repetir el mismo esqueleto en cada diálogo modal (antes duplicado en `RestChoiceScreen`/`RestVoteScreen`/`DeathSaveScreen` — ver `AUDIT_TECHNICAL.md M-DUP-7`)
- **Constructor:** `ModalDialogScreen(Component title, int dialogWidth, int dialogHeight)`
- **Geometría:** `dialogLeft() = (width - dialogWidth) / 2`, `dialogTop() = (height - dialogHeight) / 2`
- **API para subclases:** `addModalButton(int x, int y, int width, int height, Component message, Button.OnPress onPress)` — x/y son relativos a la esquina superior izquierda del diálogo (`dialogLeft()+x`, `dialogTop()+y`), no a la pantalla completa. `renderPanel(guiGraphics)` — primera línea del `render()` de cada subclase: fondo borroso vanilla + panel `GuiStyle` del tamaño del diálogo (antes cada subclase hacía `renderBackground` suelto y solo `DeathSaveScreen` dibujaba panel propio).
- Usada por `RestChoiceScreen`, `RestVoteScreen`, `DeathSaveScreen` (ver sus secciones abajo).

## AddMonsterAttackScreen

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/AddMonsterAttackScreen.java`
- **Tipo:** extiende `SmallFormScreen` (ver esa sección), `titleRows=3`
- **Cómo se abre:** `AddMonsterAttackScreen.open(int entityId)` — desde el botón "+ Añadir ataque" de `MonsterActionScreen`
- **Textura de fondo:** ninguna — panel `GuiStyle` dibujado por `SmallFormScreen`
- **Tamaño del panel:** full-screen centrado; `centerX = width/2`; `y0 = height/2 - ROW_HEIGHT*3` (`ROW_HEIGHT=30` → `height/2 - 90`)

Constantes (en `SmallFormScreen`): `FIELD_WIDTH=160`, `FIELD_HEIGHT=20`, `ROW_HEIGHT=30`. Campos/cíclicos añadidos con `addField(...)`/`addCycleButton(...)`, Confirmar/Cancelar generados automáticamente por la base.

| Widget | Tipo | X | Y | Ancho | Alto | Notas |
|---|---|---|---|---|---|---|
| Título "Añadir ataque" | drawCenteredString | width/2 | y0-16 | — | — | color `0xFFFFFF` |
| nameBox | EditBox | centerX-80 | y0 | 160 | 20 | default "Ataque", maxLen 40, foco inicial |
| diceBox | EditBox | centerX-80 | y0+26 | 160 | 20 | default "1d6", maxLen 20 |
| toHitButton | Button (cíclico) | centerX-80 | y0+52 | 160 | 20 | "Ataque con: X", ciclo sobre ABILITIES (str/dex/con/int/wis/cha) |
| damageAbilityButton | Button (cíclico) | centerX-80 | y0+78 | 160 | 20 | "Daño con: X", mismo array ABILITIES |
| damageTypeButton | Button (cíclico) | centerX-80 | y0+104 | 160 | 20 | "Tipo: X", ciclo sobre 14 DAMAGE_TYPES |
| Confirmar | Button | centerX-80 | y0+130 | 78 | 20 | envía `AddCustomAttackMessage(entityId, name, ability, dice, damageAbility, damageType)`, cierra |
| Cancelar | Button | centerX+2 | y0+130 | 78 | 20 | `onClose()` |

- **Colores especiales:** `0xFFFFFF` título.

## SpawnGenericScreen

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/SpawnGenericScreen.java`
- **Tipo:** extiende `SmallFormScreen` (ver esa sección), `titleRows=2`
- **Cómo se abre:** `SpawnGenericScreen.open()` — desde el Panel de DM
- **Textura de fondo:** ninguna — panel `GuiStyle` dibujado por `SmallFormScreen`
- **Tamaño del panel:** full-screen centrado; `centerX = width/2`; `y0 = height/2 - ROW_HEIGHT*2` (= `height/2 - 60`)

Constantes (en `SmallFormScreen`): `FIELD_WIDTH=160`, `FIELD_HEIGHT=20`, `ROW_HEIGHT=30`

| Widget | Tipo | X | Y | Ancho | Alto | Notas |
|---|---|---|---|---|---|---|
| Título "NPC genérico" | drawCenteredString | width/2 | y0-16 | — | — | color `0xFFFFFF` |
| nameBox | EditBox | centerX-80 | y0 | 160 | 20 | default "NPC", maxLen 40, foco inicial |
| baseEntityBox | EditBox | centerX-80 | y0+26 | 160 | 20 | default "minecraft:villager", maxLen 64 |
| acBox | EditBox | centerX-80 | y0+52 | 160 | 20 | default "10", maxLen 3 |
| hpBox | EditBox | centerX-80 | y0+78 | 160 | 20 | default "10", maxLen 4 |
| Invocar | Button | centerX-80 | y0+104 | 78 | 20 | parsea AC/HP (fallback 10, HP mín 1), envía `SpawnGenericMessage(name, baseEntity, ac, hp)`, cierra |
| Cancelar | Button | centerX+2 | y0+104 | 78 | 20 | `onClose()` |

- **Colores especiales:** `0xFFFFFF` título.

## AddTurnEffectScreen

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/AddTurnEffectScreen.java`
- **Tipo:** extiende `SmallFormScreen` (ver esa sección), `titleRows=2`
- **Cómo se abre:** `AddTurnEffectScreen.open(String targetUuid)` — callback pasado a `PlayerPickerScreen.open(...)` desde el botón "Aplicar efecto" de `TurnControlScreen`
- **Textura de fondo:** ninguna — panel `GuiStyle` dibujado por `SmallFormScreen`
- **Tamaño del panel:** full-screen centrado; `centerX = width/2`; `y0 = height/2 - ROW_HEIGHT*2` (= `height/2 - 60`)

Constantes (en `SmallFormScreen`): `FIELD_WIDTH=160`, `FIELD_HEIGHT=20`, `ROW_HEIGHT=30`

| Widget | Tipo | X | Y | Ancho | Alto | Notas |
|---|---|---|---|---|---|---|
| Título "Aplicar efecto" | drawCenteredString | width/2 | y0-16 | — | — | color `0xFFFFFF` |
| nameBox | EditBox | centerX-80 | y0 | 160 | 20 | default "veneno", maxLen 40, foco inicial |
| diceButton | Button (cíclico) | centerX-80 | y0+26 | 160 | 20 | "Dado: X", ciclo sobre DICE_OPTIONS (1d4/1d6/1d8/1d10/1d12/2d6/2d8) |
| turnsBox | EditBox | centerX-80 | y0+52 | 160 | 20 | default "3", maxLen 2 |
| Confirmar | Button | centerX-80 | y0+78 | 78 | 20 | fallback turnos=3, envía `TurnEffectApplyMessage(targetUuid, name, dice, turns)`, cierra |
| Cancelar | Button | centerX+2 | y0+78 | 78 | 20 | `onClose()` |

- **Colores especiales:** `0xFFFFFF` título.

## TurnControlScreen

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/TurnControlScreen.java`
- **Tipo:** extiende `ListPickerScreen` (ver esa sección)
- **Cómo se abre:** `TurnControlScreen.open()` — desde el Panel de DM (equivalente GUI de `/dndturns start|next|cancel|end`)
- **Textura de fondo:** ninguna — panel `GuiStyle` dibujado por `ListPickerScreen`
- **Tamaño del panel:** full-screen; lista top-anchored en `y=30`, botones por defecto (200/20/4)

| Widget | Tipo | Notas |
|---|---|---|
| 4 filas de acción | fila de `ListPickerScreen` | ACTIONS=[start,next,cancel,end], LABELS=["Iniciar turnos","Siguiente turno","Saltar (cancelar)","Terminar turnos"]; envían `TurnControlMessage(action)`, cierran |
| Aplicar efecto | fila de `ListPickerScreen` | abre `PlayerPickerScreen.open("Elige a quién aplicar el efecto", AddTurnEffectScreen::open)` |

## PresetScreen

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/PresetScreen.java`
- **Tipo:** extiende `ListPickerScreen` (ver esa sección)
- **Cómo se abre:** `PresetScreen.open(String targetUuid, List<String> ids, List<String> names)` — al recibir `PresetListMessage` del servidor (pedida al pulsar "Presets" en la hoja, o por un DM tras elegir jugador en `PlayerPickerScreen`)
- **Textura de fondo:** ninguna — panel `GuiStyle` dibujado por `ListPickerScreen`
- **Tamaño del panel:** full-screen; lista top-anchored en `y=30`, botones por defecto (200/20/4)

| Widget | Tipo | Notas |
|---|---|---|
| Filas de preset (bucle) | fila de `ListPickerScreen` | una por preset; onClick envía `PresetApplyMessage`/`PresetApplyToMessage`, cierra |
| Mensaje "sin presets" | `emptyMessage()` | solo si `names` vacío |

## TraitGrantScreen

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/TraitGrantScreen.java`
- **Tipo:** extiende `ListPickerScreen` (ver esa sección)
- **Cómo se abre:** `TraitGrantScreen.open(String targetUuid, List<String> ids, List<String> names)` — último paso de conceder un rasgo desde el Panel de DM, tras elegir jugador en `PlayerPickerScreen`
- **Textura de fondo:** ninguna — panel `GuiStyle` dibujado por `ListPickerScreen`
- **Tamaño del panel:** full-screen; lista top-anchored en `y=30`, botones por defecto (200/20/4)

| Widget | Tipo | Notas |
|---|---|---|
| Lista de rasgos | fila de `ListPickerScreen` | onClick envía `TraitGrantMessage`, cierra |
| Mensaje "No hay rasgos cargados" | `emptyMessage()` | solo si `names` vacío |

## PlayerPickerScreen

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/PlayerPickerScreen.java`
- **Tipo:** extiende `ListPickerScreen` (ver esa sección) — el `prompt` dinámico se pasa como título de la pantalla
- **Cómo se abre:** `PlayerPickerScreen.open(String prompt, Consumer<String> onPick)` — primer paso genérico de cualquier herramienta del Panel de DM que actúa sobre otro jugador
- **Textura de fondo:** ninguna — panel `GuiStyle` dibujado por `ListPickerScreen`
- **Tamaño del panel:** full-screen; lista top-anchored en `y=30`, botones por defecto (200/20/4)

| Widget | Tipo | Notas |
|---|---|---|
| Lista de jugadores conectados | fila de `ListPickerScreen` | una fila por jugador de la tablist; onClick llama `onPick.accept(uuid)` (no cierra por sí misma) |

## CharacterOptionListScreen

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/CharacterOptionListScreen.java`
- **Tipo:** extiende `ListPickerScreen` (ver esa sección); `returnTo` (la `CharacterSheetScreen` que la abrió) se pasa como `parent` al constructor de la base — nada bespoke aquí, es el mecanismo genérico de navegación
- **Cómo se abre:** `CharacterOptionListScreen.open(CharacterSheetScreen returnTo, String category, List<String> options)` — selector de Raza/Trasfondo/Clase invocado desde `CharacterSheetScreen`
- **Textura de fondo:** ninguna — panel `GuiStyle` dibujado por `ListPickerScreen`
- **Tamaño del panel:** full-screen; lista top-anchored en `y=30`, botones por defecto (200/20/4)

| Widget | Tipo | Notas |
|---|---|---|
| Lista de opciones | fila de `ListPickerScreen` | onClick escribe el campo, envía `SheetServerMessage`, `onClose()` |
| Botón "&lt; Atrás" | fila de `ListPickerScreen` (base) | esquina superior izquierda; vuelve a `returnTo` |
| Mensaje "No hay opciones cargadas" | `emptyMessage()` | solo si `options` vacío |

- **Notas:** vuelve a `returnTo` (misma instancia de `CharacterSheetScreen`) tanto al elegir una opción como al pulsar "&lt; Atrás" o Escape — su `init()` se re-ejecuta al reabrirla, releyendo la hoja actualizada.

## ManageCustomAttacksScreen

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/ManageCustomAttacksScreen.java`
- **Tipo:** extiende `ListPickerScreen` (ver esa sección)
- **Cómo se abre:** `ManageCustomAttacksScreen.open(int entityId, List<String> customAttackNames)` — desde `MonsterActionScreen` ("Gestionar ataques personalizados")
- **Textura de fondo:** ninguna — panel `GuiStyle` dibujado por `ListPickerScreen`
- **Tamaño del panel:** full-screen; lista top-anchored en `y=30`, botones por defecto (200/20/4)

| Widget | Tipo | Notas |
|---|---|---|
| Lista de ataques + "Borrar todos" | fila de `ListPickerScreen` | una fila "Quitar: {name}" por ataque (envía `RemoveCustomAttackMessage`, cierra), más fila final fija "Borrar todos" (`ClearCustomAttacksMessage`, cierra) — esta última se añade siempre, incluso con la lista vacía |
| Mensaje "Este monstruo no tiene ataques personalizados" | `emptyMessage()` | solo si `customAttackNames` vacío |

## MonsterActionScreen

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/MonsterActionScreen.java`
- **Tipo:** extiende `ListPickerScreen` (ver esa sección)
- **Cómo se abre:** `MonsterActionScreen.open(int entityId, List<String> actionNames, List<String> customAttackNames)` — al hacer clic derecho con la Vara de DM sobre un monstruo invocado
- **Textura de fondo:** ninguna — panel `GuiStyle` dibujado por `ListPickerScreen`
- **Tamaño del panel:** full-screen; lista top-anchored en `y=30`, botones por defecto (200/20/4)

| Widget | Tipo | Notas |
|---|---|---|
| Lista de acciones | fila de `ListPickerScreen` | una fila por acción (envía `MonsterActionChooseMessage(entityId, i)`, cierra); fila fija "+ Añadir ataque" (abre `AddMonsterAttackScreen`); fila fija "Gestionar ataques personalizados" (abre `ManageCustomAttacksScreen`) |

- **Notas:** título "Acciones del monstruo" ahora visible (antes no se dibujaba: la pantalla no sobrescribía `render()`) — efecto colateral positivo de heredar de `ListPickerScreen`, no un cambio deliberado de contenido.

## GrimoireScreen

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/GrimoireScreen.java`
- **Tipo:** extiende `ListPickerScreen` (ver esa sección); sobrescribe `buttonWidth()` (220), `listTop()` (deja hueco a la tabla de espacios, que ocupa dos filas) y `listHeight()` (deja hueco a los DOS botones fijos de abajo: nivel de espacio y lanzar), más `init()`/`render()` para esos botones y la tabla
- **Cómo se abre:** sin `open()` estático; instanciado directo con `new GrimoireScreen(this)` desde el botón `grimoireButton` de `CharacterSheetScreen` — `this` (la hoja) es el `parent`, así que "&lt; Atrás"/Escape vuelven a ella
- **Textura de fondo:** ninguna — panel `GuiStyle` dibujado por `ListPickerScreen`
- **Tamaño del panel:** full-screen centrado; ancho fijo 220 (vía `buttonWidth()`)

| Widget | Tipo | X | Y | Ancho | Alto | Notas |
|---|---|---|---|---|---|---|
| Título "Grimorio" | `ListPickerScreen` (base) | width/2 | 16 | — | — | color `GuiStyle.TITLE_COLOR` |
| Tabla de espacios por nivel | `renderSlotTable` | centrada, `SLOT_COL_WIDTH=24` por columna | SUBTITLE_Y=30 (nivel) y +`SLOT_ROW_STEP=10` (quedan/máx) | — | — | una columna por nivel con máximo > 0; nivel agotado en `MUTED_COLOR`, con espacios en `SUBTITLE_COLOR`. Sustituye al total plano "Espacios: n/max", que desde que los espacios son por nivel ya no dice si puedes lanzar tu Bola de Fuego |
| Lista de hechizos | fila de `ListPickerScreen` | (width-220)/2 | listTop()=54 | 220 | listHeight() | scrollable |
| Botón "Espacio: nv. N" | Button | (width-220)/2 | listTop()+listHeight()+SPACING | 220 | 20 | cicla el nivel de espacio (lanzar a nivel superior); solo entre los niveles que le QUEDAN al personaje, dando la vuelta al del propio hechizo. Inactivo para trucos y si no hay ningún nivel superior con espacios |
| Botón "Elige un hechizo / Lanzar: X (nv. N)" | Button | (width-220)/2 | fila anterior + 20 + SPACING | 220 | 20 | inactivo hasta seleccionar hechizo; manda `SpellCastMessage(id, nivelElegido)`. El nivel del rótulo es el ELEGIDO, no el propio del hechizo: enseñando el suyo, este botón contradecía al de arriba ("Lanzar: Bola de Fuego (nv. 3)" bajo "Espacio: nv. 5") y el número equivocado estaba en el botón que actúa. Es el mismo que sale luego en el chat |
| Mensaje "No conoces ningún hechizo..." | `emptyMessage()` | — | — | — | — | solo si no hay hechizos conocidos |

## TurnActionScreen

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/TurnActionScreen.java`
- **Tipo:** extiende `ModalDialogScreen` (mismo patron exacto que `RestChoiceScreen`: tres `addModalButton` y un titulo dibujado en `render`)
- **Como se abre:** `ScreenActionMessage.Action.TURN_ACTION_OPEN`, que manda el servidor al usar el item "Acciones de Turno" (ver `TurnActionManager.tryUse`)
- **Textura de fondo:** ninguna - panel de `ModalDialogScreen`
- **Tamano del panel:** 260x104

| Widget | Tipo | X | Y | Ancho | Alto | Notas |
|---|---|---|---|---|---|---|
| Titulo "Gasta tu accion de este turno en:" | drawCenteredString | width/2 | dialogTop()+8 | - | - | color `0xFFFFFF` |
| "Esquivar - te atacan con desventaja" | `addModalButton` | 20 | 30 | 220 | 20 | manda `TurnActionMessage(DODGE)` |
| "Correr - el doble de movimiento" | `addModalButton` | 20 | 54 | 220 | 20 | manda `TurnActionMessage(DASH)` |
| "Desengancharse - alejarte no provoca ataques" | `addModalButton` | 20 | 78 | 220 | 20 | manda `TurnActionMessage(DISENGAGE)` |

El rotulo dice lo que HACE cada accion y no solo como se llama: "Esquivar" a secas no le dice nada a quien nunca jugo a D&D, y este mod se juega sobre todo con gente que no lo ha jugado.

## AbilityImprovementScreen

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/AbilityImprovementScreen.java`
- **Tipo:** extiende `ModalDialogScreen`
- **Como se abre:** `ScreenActionMessage.Action.ABILITY_IMPROVEMENT_OPEN`, que manda el servidor al conceder una Mejora de Puntuacion de Caracteristica (nivel 4, 8, 12, 16 o 19), y tambien con `/dndchar mejora` si quedo alguna sin gastar
- **Tamano del panel:** 280x152

| Widget | Tipo | X | Y | Ancho | Alto | Notas |
|---|---|---|---|---|---|---|
| Titulo (cambia con el estado) | drawCenteredString | width/2 | dialogTop()+8 | - | - | "Elige: +2 a una..." / "Ahora elige la segunda..." |
| "El maximo de una caracteristica es 20." | drawCenteredString | width/2 | dialogTop()+20 | - | - | `GuiStyle.MUTED_COLOR` |
| Seis botones de caracteristica | `addModalButton` | 16 / 142 | 34 + fila*24 | 122 | 20 | rotulo "Fuerza 15 -> 17"; inactivo si ya esta en 20; ">" marca la elegida |
| "Confirmar +2 a la elegida" | `addModalButton` | 16 | 116 | 248 | 20 | inactivo hasta elegir una |

Cada boton ensena la puntuacion actual Y a cuanto subiria: la decision no se toma sobre el nombre de la caracteristica sino sobre si el modificador cruza un numero par (15->16 da +1 al modificador, 16->17 no da nada), y eso no se ve si el boton solo dice "Destreza". Volver a pulsar la caracteristica ya elegida la deselecciona, que es la salida obvia de "me equivoque".

## NewCharacterScreen

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/NewCharacterScreen.java`
- **Tipo:** extiende `SmallFormScreen` (un solo campo)
- **Como se abre:** boton "+ Personaje nuevo..." de `CharacterListScreen`; vuelve a ella al confirmar o cancelar
- **Campos:** "Nombre del personaje" (vacio por defecto, max 40)

Manda `BrowseActionMessage(CREATE, nombre)` — el campo de texto del mensaje es libre, asi que crear cabe sin registrar un mensaje mas (invariante 3). Solo pide el nombre: clase y caracteristicas salen del preset que se elija despues desde la hoja, y preguntarlo aqui seria preguntar dos veces por lo mismo con peor informacion.

Un nombre vacio no se manda: el servidor lo rechazaria igual, y un viaje de ida y vuelta para que no pase nada se lee como que el boton esta roto.

## SheetAdjustScreen

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/SheetAdjustScreen.java`
- **Tipo:** `Screen` (plana) — layout propio, no encaja en `ListPickerScreen`/`SmallFormScreen`; dibuja el panel `GuiStyle` directamente en `render()`; captura `parent = Minecraft.getInstance().screen` en `open()` y sobrescribe `onClose()` a mano (mismo mecanismo que `ListPickerScreen`, sin heredar de ella)
- **Cómo se abre:** `SheetAdjustScreen.open(targetUuid, targetName, gold, slotsMax, slotsCurrent, hp, maxHp, ac)` — desde el Panel de DM tras elegir jugador en `PlayerPickerScreen` (que queda como `parent`)
- **Textura de fondo:** ninguna — panel `GuiStyle.panel(...)` de `(centerX-WIDE_WIDTH/2-14, y0-40)` a `(centerX+WIDE_WIDTH/2+14, formBottom)`
- **Tamaño del panel:** full-screen centrado; `centerX=width/2`, `y0 = height/2 - ROW_HEIGHT*6` (`ROW_HEIGHT=26`), `FIELD_WIDTH=90`, `WIDE_WIDTH=190`, `FIELD_HEIGHT=20`

| Widget | Tipo | X | Y | Ancho | Alto | Notas |
|---|---|---|---|---|---|---|
| Título "Ajustes de X (oro actual: n)" | drawCenteredString | width/2 | y0-26 | — | — | color `0xFFFFFF` |
| "PG h/maxHp · CA ac" (solo lectura) | drawCenteredString | width/2 | y0-16 | — | — | color `0xFFAA00` |
| goldAmountBox | EditBox | centerX-95 | y0 | 90 | 20 | default "0", maxLen 10, foco inicial |
| Botón "Añadir" (oro) | Button | centerX+ 4 | y0 | 40 | 20 | `SheetGoldMessage(uuid,"add",valor)` |
| Botón "Fijar" (oro) | Button | centerX+43 | y0 | 40 | 20 | `SheetGoldMessage(uuid,"set",valor)` |
| slotsMaxBox | EditBox | centerX-95 | y0+26 | 90 | 20 | valor=slotsMax, maxLen 3 |
| slotsCurrentBox | EditBox | centerX-1 | y0+26 | 90 | 20 | valor=slotsCurrent, maxLen 3 |
| Botón "Aplicar" (slots) | Button | centerX+93 | y0+26 | 2 | 20 | `SheetSlotsMessage` |
| advantageButton (cíclico normal/ventaja/desventaja) | Button | centerX-95 | y0+52 | 130 | 20 | afecta solo próxima tirada |
| Botón "Aplicar" (ventaja) | Button | centerX+39 | y0+52 | 56 | 20 | `SheetAdvantageMessage` |
| damageTypeButton (cíclico 14 tipos) | Button | centerX-95 | y0+78 | 90 | 20 | |
| affinityButton (cíclico normal/resistant/vulnerable/immune) | Button | centerX-1 | y0+78 | 90 | 20 | multiplicadores de daño |
| Botón "Aplicar" (daño) | Button | centerX+93 | y0+78 | 2 | 20 | `SheetDamageAffinityMessage` |
| pactButton (cíclico cadena/hoja/vara, permanente) | Button | centerX-95 | y0+104 | 130 | 20 | |
| Botón "Aplicar" (pacto) | Button | centerX+39 | y0+104 | 56 | 20 | `SheetPactMessage` |
| levelBox | EditBox | centerX-95 | y0+130 | 90 | 20 | default "1", maxLen 2 |
| Botón "Fijar nivel" (permanente) | Button | centerX-1 | y0+130 | 96 | 20 | `SheetLevelMessage` |
| Botón "Ver percepción pasiva (solo tú la ves)" | Button | centerX-95 | y0+156 | 190 | 20 | `PassivePerceptionRequestMessage` |
| Botón "&lt; Atrás" | Button | centerX-95 | y0+186 | 190 | 20 | `onClose()` — vuelve a `parent` (normalmente `PlayerPickerScreen`) |

- **Colores especiales:** `0xFFFFFF` título, `0xFFAA00` línea PG/CA de solo lectura.

## RestChoiceScreen

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/RestChoiceScreen.java`
- **Tipo:** extiende `ModalDialogScreen`
- **Cómo se abre:** `RestChoiceScreen.open()` — para quien usó el Kit de Descanso
- **Textura de fondo:** ninguna — panel `GuiStyle` vía `renderPanel()`
- **Tamaño del panel:** `dialogWidth x dialogHeight = 220 x 80`, centrado

| Widget | Tipo | X (rel. diálogo) | Y (rel. diálogo) | Ancho | Alto | Notas |
|---|---|---|---|---|---|---|
| Título "¿Qué tipo de descanso propones?" | drawCenteredString | width/2 (absoluto) | dialogTop()+8 | — | — | color `0xFFFFFF` |
| Botón "Descanso corto" | addModalButton | 20 | 30 | 180 | 20 | `RestProposeMessage(false)`, luego `onClose()` |
| Botón "Descanso largo" | addModalButton | 20 | 54 | 180 | 20 | `RestProposeMessage(true)`, luego `onClose()` |

- **Colores especiales:** `0xFFFFFF` título.

## RestVoteScreen

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/RestVoteScreen.java`
- **Tipo:** extiende `ModalDialogScreen`
- **Cómo se abre:** `RestVoteScreen.open(proposerName, typeLabel)` — a todos al recibir una propuesta de descanso; `RestVoteScreen.close()` fuerza el cierre
- **Textura de fondo:** ninguna — panel `GuiStyle` vía `renderPanel()`
- **Tamaño del panel:** `dialogWidth x dialogHeight = 240 x 90`, centrado

| Widget | Tipo | X (rel. diálogo) | Y (rel. diálogo) | Ancho | Alto | Notas |
|---|---|---|---|---|---|---|
| Título "X propone un descanso Y." | drawCenteredString | width/2 (absoluto) | dialogTop()+8 | — | — | color `0xFFFFFF` |
| "Se aplicará solo si todos aceptan." | drawCenteredString | width/2 (absoluto) | dialogTop()+22 | — | — | color `0xAAAAAA` |
| Botón "Aceptar" | addModalButton | 20 | 60 | 95 | 20 | `RestVoteResponseMessage(true)`, `onClose()` |
| Botón "Rechazar" | addModalButton | 125 | 60 | 95 | 20 | `RestVoteResponseMessage(false)`, `onClose()` |

- **Colores especiales:** `0xFFFFFF` título, `0xAAAAAA` subtítulo.
- **Notas:** `shouldCloseOnEsc()` devuelve `false`.

## DeathSaveScreen

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/DeathSaveScreen.java`
- **Tipo:** extiende `ModalDialogScreen`
- **Cómo se abre:** `DeathSaveScreen.open()` — forzada mientras el personaje está a 0 PG; `DeathSaveScreen.close()` solo lo cierra el servidor
- **Textura de fondo:** ninguna — panel `GuiStyle` vía `renderPanel()` (antes dibujaba su propio `guiGraphics.fill(...)`, ahora unificado con el resto de modales)
- **Tamaño del panel:** `dialogWidth x dialogHeight = 240 x 90`, centrado

| Widget | Tipo | X (rel. diálogo) | Y (rel. diálogo) | Ancho | Alto | Notas |
|---|---|---|---|---|---|---|
| Título "¡Estás caído!" | drawCenteredString | width/2 (absoluto) | dialogTop()+8 | — | — | color `0xFFFFFF` |
| "Éxitos: ●●○  Fallos: ●○○" | drawCenteredString | width/2 (absoluto) | dialogTop()+24 | — | — | color `0xAAAAAA`; símbolos según `deathSaveSuccesses`/`deathSaveFailures` de la hoja |
| "Otro jugador puede reanimarte interactuando contigo." | drawCenteredString | width/2 (absoluto) | dialogTop()+38 | — | — | color `0x888888` |
| Botón "Tirar salvación de muerte" | addModalButton | 20 | 60 | 200 | 20 | `DeathSaveRollMessage()` |

- **Colores especiales:** `0xFFFFFF` título, `0xAAAAAA` marcadores éxito/fallo, `0x888888` texto de ayuda.
- **Notas:** `shouldCloseOnEsc()` e `isPauseScreen()` devuelven `false`.

## DungeonPieceListScreen

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/DungeonPieceListScreen.java`
- **Tipo:** extiende `ListPickerScreen` (ver esa sección); sobrescribe `listTop()` (deja hueco al aviso de Structurize/BlockUI si no están instalados) y `render()` para ese aviso
- **Cómo se abre:** `DungeonPieceListScreen.open(List<DungeonPieceRegistry.DungeonPiece> pieces)` — al recibir `DungeonPieceListMessage` del servidor (pedida desde el botón "Mazmorras" del Panel de DM, o como eco tras capturar/editar una pieza)
- **Textura de fondo:** ninguna — panel `GuiStyle` dibujado por `ListPickerScreen`
- **Tamaño del panel:** full-screen; lista top-anchored en `y=30` (o `y=44` si falta el aviso de Structurize/BlockUI), botones por defecto (200/20/4)

| Widget | Tipo | Notas |
|---|---|---|
| Aviso "Structurize + BlockUI no detectados..." | drawCenteredString | solo si `DungeonManager.structurizeAvailable()` es falso; color `GuiStyle.MUTED_COLOR` |
| Filas de pieza (bucle) | fila de `ListPickerScreen` | una por pieza: `id — pool (peso n)`, con sufijo ` [inicio]` si `DungeonManager.hasStartJigsaw` es true para esa pieza (ver `DungeonPieceListMessage.hasStart`); onClick abre `DungeonPieceEditScreen`, que tiene el botón "Borrar pieza" (ver `SmallFormScreen.showDeleteButton()`) |
| "+ Añadir pieza" | fila de `ListPickerScreen` | abre `DungeonPieceAddScreen` |
| "Generar mazmorra" | fila de `ListPickerScreen` | abre `DungeonGenerateScreen` |
| Mensaje "Sin piezas todavía..." | `emptyMessage()` | solo si `pieces` vacío |

## DungeonPieceAddScreen

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/DungeonPieceAddScreen.java`
- **Tipo:** extiende `SmallFormScreen` (ver esa sección), `titleRows=3`
- **Cómo se abre:** `DungeonPieceAddScreen.open()` sin prellenar — desde "+ Añadir pieza" en `DungeonPieceListScreen`; o `DungeonPieceAddScreen.open(structureId, suggestedId)` prellenado — al recibir `DungeonPieceAddOpenMessage`, disparado por clic derecho con la Vara de DM sobre un bloque de estructura ya nombrado (ver `DungeonToolManager`), para no tener que retipear a mano el id que ya se escribió una vez al guardar la estructura
- **Textura de fondo:** ninguna — panel `GuiStyle` dibujado por `SmallFormScreen`
- **Tamaño del panel:** full-screen centrado; `centerX = width/2`; `y0 = height/2 - ROW_HEIGHT*3` (`ROW_HEIGHT=30` → `height/2 - 90`)

| Widget | Tipo | Notas |
|---|---|---|
| idBox | EditBox | prellenado con `suggestedId` (último segmento de la ruta) si vino de la Vara de DM, si no vacío; maxLen 32, foco inicial |
| structureBox | EditBox | "Estructura (namespace:ruta)"; prellenado con `structureId` si vino de la Vara de DM, si no vacío (el DM lo escribe a mano, el mismo id que le puso al bloque de estructura al escanear); maxLen 64 |
| poolBox | EditBox | maxLen 32 |
| weightBox | EditBox | default "1", maxLen 4 |
| tagsBox | EditBox | maxLen 64 |
| Confirmar | Button | envía `DungeonPieceCaptureMessage(id, structure, pool, weight, tags)`, cierra |
| Cancelar | Button | `onClose()` |

## DungeonPieceEditScreen

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/DungeonPieceEditScreen.java`
- **Tipo:** extiende `SmallFormScreen` (ver esa sección), `titleRows=2`
- **Cómo se abre:** `DungeonPieceEditScreen.open(DungeonPieceRegistry.DungeonPiece piece)` — al elegir una fila de pieza en `DungeonPieceListScreen`; los valores prellenados vienen del objeto `piece` recibido por red, no de una relectura local (`DungeonPieceRegistry` solo vive en memoria del servidor)
- **Textura de fondo:** ninguna — panel `GuiStyle` dibujado por `SmallFormScreen`
- **Tamaño del panel:** full-screen centrado; `centerX = width/2`; `y0 = height/2 - ROW_HEIGHT*2` (`ROW_HEIGHT=30` → `height/2 - 60`)

| Widget | Tipo | Notas |
|---|---|---|
| poolBox | EditBox | prellenado con `piece.pool()`, maxLen 32, foco inicial |
| weightBox | EditBox | prellenado con `piece.weight()`, maxLen 4 |
| tagsBox | EditBox | prellenado con `piece.tags()`, maxLen 64 |
| Confirmar | Button | envía `DungeonPieceUpdateMessage(id, pool, weight, tags)`, cierra |
| Cancelar | Button | `onClose()` |

- **Notas:** sin botón de borrar en este formulario — `SmallFormScreen.init()` es `final` (solo Confirmar/Cancelar); borrar es una fila propia en `DungeonPieceListScreen` (ver esa sección), no algo de esta pantalla.

## DungeonGenerateScreen

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/DungeonGenerateScreen.java`
- **Tipo:** extiende `SmallFormScreen` (ver esa sección), `titleRows=3`
- **Cómo se abre:** `DungeonGenerateScreen.open()` — desde "Generar mazmorra" en `DungeonPieceListScreen`
- **Textura de fondo:** ninguna — panel `GuiStyle` dibujado por `SmallFormScreen`
- **Tamaño del panel:** full-screen centrado; `centerX = width/2`; `y0 = height/2 - ROW_HEIGHT*3` (`ROW_HEIGHT=30` → `height/2 - 90`)

| Widget | Tipo | Notas |
|---|---|---|
| poolBox | EditBox | maxLen 32, foco inicial |
| maxDepthBox | EditBox | default "7", maxLen 2 |
| xBox / yBox / zBox | EditBox | prellenados con `Minecraft.getInstance().player.blockPosition()` al abrir |
| Confirmar | Button | envía `DungeonGenerateMessage(pool, maxDepth, pos)` (publica los pools y corre `/reload` en el servidor antes de generar), cierra |
| Cancelar | Button | `onClose()` |

## DungeonJigsawConfigureScreen

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/DungeonJigsawConfigureScreen.java`
- **Tipo:** extiende `SmallFormScreen` (ver esa sección), `titleRows=1`
- **Cómo se abre:** `DungeonJigsawConfigureScreen.open(BlockPos pos, String currentPool, boolean currentIsStart)` — al recibir `DungeonJigsawConfigureOpenMessage`, disparado por clic derecho (sin agachar) con la Vara de DM sobre un jigsaw block (ver `DungeonToolManager`); prellenado con el portapapeles del DM si copió otro jigsaw antes (agachado + clic derecho sobre uno ya configurado), si no con lo que ya tuviera guardado ESTE jigsaw si era de nuestro namespace, vacío/"No" si es un jigsaw recién colocado sin nada copiado
- **Textura de fondo:** ninguna — panel `GuiStyle` dibujado por `SmallFormScreen`
- **Tamaño del panel:** full-screen centrado; `centerX = width/2`; `y0 = height/2 - ROW_HEIGHT*1` (`ROW_HEIGHT=30` → `height/2 - 30`)

| Widget | Tipo | Notas |
|---|---|---|
| poolBox | EditBox | "Pool destino" — a qué pool debería tirar esta salida; maxLen 32, foco inicial |
| isStart | Button (cíclico) | "Pieza de inicio: Sí/No" |
| Confirmar | Button | envía `DungeonJigsawConfigureMessage(pos, pool, isStart)` — el servidor escribe `Name`/`Target`/`Pool`/`Joint` directo en el block entity, sin abrir la GUI vanilla del jigsaw (ver `DungeonManager.configureJigsaw`), cierra |
| Cancelar | Button | `onClose()` |

- **Notas:** `Target` siempre queda en `dndsheets:connector` (no se pide) y `Joint` siempre en `ALIGNED` (no se expone) — simplificación deliberada, ver comentario en `DungeonManager.configureJigsaw`.

### Pestañas de la hoja (`AdjustableImageButton`)

Las tres pestañas (Principal / Habilidades / Ataques) no son `TomeButton`: son texturas, así que no salen
en un grep de `Button.builder` y hay que repintarlas aparte. Los PNG los genera
`tools/make_tab_textures.py` con la paleta de `GuiStyle`.

**Un `ImageButton` tiene TRES estados apilados en vertical, no dos**: normal en `v=0`, hover en
`v=yDiffTex` y **deshabilitado** en `v=yDiffTex*2` (`AbstractWidget.renderTexture`). Y aquí eso pesa más
de lo normal, porque `updateTabs()` marca la pestaña **seleccionada** con `active = false` para que no se
pueda pulsar la que ya estás viendo: **la pestaña abierta se dibuja siempre con la tercera fila**. Los PNG
heredados de MCreator solo traían dos, así que la seleccionada muestreaba fuera de la imagen y salía
plana, sin fallar ni avisar. `JsonContentSelfTest.checkTabTextures` lo comprueba en cada `gradlew build`.

Al regenerarlos hay que actualizar también el alto declarado en `CharacterSheetScreen` (el último
argumento de `setImage` y del constructor), que es lo que se muestrea.

### Sombra del texto: nunca sobre pergamino

La sombra de Minecraft no es un desenfoque, es **una copia del texto un píxel abajo y a la derecha**, en
el color oscurecido a la cuarta parte. Sobre cuero oscuro con texto claro eso es relieve y ayuda a leer.
Sobre **pergamino con tinta oscura** —las etiquetas de la hoja y el rótulo de la pestaña abierta— la copia
queda tan oscura como el original y **la palabra se lee escrita dos veces**.

Lo traicionero es que las dos formas cómodas de centrar texto encienden la sombra sin dejar apagarla:

| Llamada | Sombra | Sobre pergamino |
|---|---|---|
| `drawCenteredString(...)` | forzada a `true` | ✗ se duplica |
| `AbstractWidget.renderString(...)` | acaba en la anterior | ✗ se duplica |
| `drawString(..., false)` | apagada | ✓ |

Sobre pergamino hay que **centrar a mano** (`x - font.width(texto) / 2`) y usar `drawString(..., false)`.
`AdjustableImageButton` expone `txtShadow` justo para eso. `JsonContentSelfTest.checkParchmentTextHasNoShadow`
lo comprueba en cada build sobre los dos ficheros que pintan encima del pergamino.

Los `EditBox` no entran aquí: pintan su propio fondo negro, así que su texto claro con sombra es correcto.

### Iconos de la hoja (`tools/make_icon_buttons.py`)

Los once iconos de 16×16 (tirar, salvación, ataque, daño, añadir, borrar, modo edición) se generan en
latón y tinta con la paleta de `GuiStyle`. Los de MCreator eran d20 magenta, una cruz verde y un
engranaje gris.

Se dibujan **directamente a 16×16, sin supersampling**: a ese tamaño el suavizado emborrona una silueta
que solo tiene 16 píxeles de ancho. Los polígonos de PIL son de borde duro, que es justo lo que se quiere
(al revés que los fondos, que sí se dibujan a 4× porque Minecraft los reduce).

Llevan **dos** filas de estado, no tres como las pestañas, porque `setActiveVisible` y
`RollScrollWidget.setInactive` apagan siempre `active` y `visible` a la vez: un icono deshabilitado no
llega a dibujarse. Si algún día se apaga solo `active`, hay que añadir la tercera fila.

Cada icono debe **cambiar** entre reposo y ratón encima. Las variantes `_edit` salieron primero con
pergamino en los dos estados y el botón se veía idéntico apuntado y sin apuntar: no rompe nada, no avisa
y solo se nota pasando el ratón en el juego. `JsonContentSelfTest.checkIconButtons` lo comprueba.

Las `_edit` comparten silueta con su versión normal y se distinguen por ser huecas más la plumilla: son
el mismo botón en el mismo sitio alternando con el modo edición, así que tienen que decir "esto edita lo
de siempre", no parecer otra cosa.

### Los editores de tirada ya no traen PNG

`RollEditorScreen` y `AdvancedRollEditorScreen` usaban `roll_editor.png` y `advanced_roll_editor.png`
—el panel azul marino con remaches rojos de MCreator— y ahora llaman a `GuiStyle.panel()` sobre el mismo
rectángulo, como las otras cuarenta pantallas. Los dos PNG están borrados. Ningún offset cambió: el panel
se dibuja en `(leftPos, topPos)`–`(leftPos + imageWidth, topPos + imageHeight)`, exactamente donde iba el
blit.

### Retícula de la pestaña principal

Las posiciones de la pestaña principal ya no son números sueltos ajustados a ojo contra la textura. Antes
las filas caían en y = 20, 55, 90, 125, 165, 205 (ritmo 35, 35, 35, 40, 40) y las columnas en x = 125,
220, 235, 304 sin relación entre ellas: mover un campo obligaba a recolocar sus vecinos a mano.

Ahora todo sale de una retícula de seis constantes en `CharacterSheetScreen`:

| | y |
|---|---|
| `SEC1_Y` **IDENTIDAD** | 8 |
| `ROW1_Y` Raza · Clase · Trasfondo | 30 |
| `SEC2_Y` **COMBATE** | 68 |
| `ROW2_Y` CA · PG · PG Máx · PG Temp | 90 |
| `ROW3_Y` Velocidad · Competencia · Iniciativa | 126 |
| `SEC3_Y` **RECURSOS** | 164 |
| `ROW4_Y` Nivel · Hambre · Dados de Golpe | 186 |
| `BOTTOM_ROW_Y` Grimorio · Presets · Guía | 216 |

Para cambiar el alto de una fila se toca `ROW_STEP`, no seis constantes.

**Velocidad salió del grupo de CA/PG.** Con cinco huecos de 45, el rótulo del quinto (`Velocidad`, 54 px)
empezaba en x=305 y acababa en 359, fuera del panel de 350 — un desbordamiento que solo aparecía en
español. Ahora ese grupo tiene cuatro huecos de 54 y Velocidad va con Iniciativa.

**`Bono de Competencia` (114 px) pasó a `Competencia` (66).** No cabía como rótulo de un campo de 14 px de
ancho, y el `+` que se dibuja pegado al campo ya dice que es un bono.

Un bloque `static` comprueba al cargar la clase que la retícula cabe en el panel. No es una comprobación
de compilación: salta al abrir la hoja. Se puso porque el desbordamiento pasó al escribir esta retícula
(los botones caían en y=246 sobre un panel de 240) y ya había pasado antes (y=228 sobre un fondo de 200),
y en los dos casos no rompe nada, no avisa y solo se ve mirando.

### Los campos: marco de latón encima del anillo gris

Vanilla pinta cada `EditBox` como un **anillo gris de un píxel** (blanco al tener el foco) alrededor de un
**relleno negro**, y los dos colores están fijos dentro de `EditBox.renderWidget`. Sobre el pergamino eso
se leía como widgets prestados de otra interfaz: la hoja parecía dos diseños a la vez.

`setBordered(false)` **no** sirve como arreglo. Quita el borde, sí, pero también el relleno negro, y además
mueve el texto: de estar centrado con margen pasa a pegarse a la esquina superior izquierda. Y sin fondo
oscuro detrás, el texto tendría que ser tinta sobre pergamino — que con la sombra fija de Minecraft se ve
duplicado (ver la sección de sombras más arriba).

Así que el anillo no se quita: **se repinta encima**. Ocupa exactamente un píxel por fuera de la caja, o
sea que taparlo no toca ni el texto ni el interior. `CharacterSheetScreen.frameField` dibuja latón apagado,
latón encendido con el foco (conservando la señal que daba el blanco de vanilla) y una línea oscura por
fuera arriba y a la izquierda, que es lo que hace que el campo se lea hundido en la hoja.

Los campos se recogen del `guistate` al final de `init()`, no se registran a mano en los trece sitios que
los crean. **Ojo:** los campos de nombre de la pestaña de Ataques se crean dentro de `RollScrollWidget` y
no pasan por `guistate`, así que esos siguen con el anillo gris.

### Bandas de sección

Cada sección de la pestaña principal se dibuja sobre un rectángulo apenas más oscuro que el pergamino, con
luz arriba y sombra abajo. Un título y un filete solos dejan la sección sin superficie y la hoja entera se
lee plana.

Van en `renderBg` y no en `renderLabels` porque `renderLabels` corre **después** de los widgets: dibujadas
allí taparían los propios campos que envuelven. Sus coordenadas salen de las constantes de la retícula, así
que no pueden desalinearse de las filas al cambiar `ROW_STEP`.

### Retícula de la pestaña de Habilidades

Las dieciocho habilidades se agrupan por característica —como en 5e y como se imprimen en una hoja de
verdad—, con cabecera y filete por grupo. Esa agrupación **ya estaba en el código, pero solo como
comentarios** (`//STR`, `//DEX`, `//INT`…): en pantalla eran dos columnas de nueve filas seguidas, sin
decir de qué característica tira cada una, que es la mitad de la información de una lista de habilidades.

Las cabeceras reutilizan los `LABEL_ABILITY_*` que ya usaban las tiradas de característica del panel
lateral, así que no hay claves de traducción nuevas y traducir una traduce los dos sitios.

Las posiciones **se calculan**, no se escriben: `skillRowY(grupos, hueco)` cuenta las cabeceras que quedan
por encima. Los grupos no son del mismo tamaño (Fuerza tiene una habilidad e Inteligencia cinco), así que
con posiciones a mano, mover una obliga a recolocar todas las de debajo.

Aquí no hay bandas como en la pestaña principal: cinco bandas en dos columnas se leen como rayas, y las
cabeceras con filete ya separan los grupos.

**Las columnas van muy justas, y es inevitable.** Los rótulos en español casi llenan el ancho: entre
`Juego de Manos` (84 px) en la primera columna y `Trato con Animales` (108) en la segunda, más los botones
de tirada, se comen 228 de los 244 disponibles. Con las columnas a la misma anchura, `Trato con Animales`
llegaba a x=363 sobre un ancho de 350 — otro desbordamiento que solo aparecía en español, igual que el de
`Velocidad` en la pestaña principal. Por eso la columna 2 arranca más a la derecha de lo que parecería
simétrico, y la 1 pegada al borde del panel lateral (en esta pestaña no hay filete vertical de fondo: solo
lo lleva `character_sheet.png`, la principal).

El bloque `static` comprueba que cada columna reparte nueve huecos y que cabe en el alto del panel.

### Pestaña de Ataques

La lista se alineaba con sus propios números (x=125, ancho 210) en vez de con el panel, así que quedaba
unos píxeles descuadrada respecto a las otras dos pestañas. Ahora usa las mismas columnas (`PANEL_X` a
`PANEL_RIGHT`) y abre con cabecera, como las otras dos.

`AbstractScrollWidget` pinta su fondo igual que `EditBox` —gris de un píxel, negro por dentro, colores
fijos— así que era el widget más grande de la pestaña y el que más desentonaba. `RollScrollWidget` ahora
sobreescribe `renderBackground` con cuero y marco de latón, y enmarca el nombre de cada fila.

Ese marco vive en `components/TomeField` y no junto a `GuiStyle` porque lo usan los dos paquetes: la
pantalla de la hoja para sus campos y `RollScrollWidget` para la lista. Desde `components` no se ve
`GuiStyle`.

Sin ataques, la lista era un hueco oscuro sin decir qué hacer con él (el botón de añadir está debajo, pero
es un icono de 16 px sin rótulo). Ahora lleva mensaje de estado vacío.

### Rótulos que no caben: `warnIfLabelsOverflow`

En un solo rediseño se colaron **cuatro** rótulos más anchos que su hueco —`Velocidad`,
`Bono de Competencia`, `Trato con Animales` y el mensaje de "sin ataques"— y los cuatro **solo en
español**: los huecos se ajustan mirando la pantalla en un idioma y el desbordamiento aparece en otro.

`CharacterSheetScreen.warnIfLabelsOverflow` recorre cada rótulo con su hueco y avisa por el log si se
pasa. Va en `init()` y no en el bloque `static` porque necesita `this.font`, que no existe hasta que hay
pantalla. Y **avisa en vez de reventar**: un rótulo recortado por una traducción larga es un defecto
cosmético, no motivo para dejar sin hoja a quien juega.

Los límites salen de las constantes de la retícula, así que siguen a la maquetación solos.

### Qué se oculta al cambiar de pestaña

Los widgets del panel principal se capturan **por diferencia** sobre `children()` dentro de
`initMainPanel()`: todo lo que se cree ahí dentro se oculta solo al cambiar de pestaña.

No es una florituría. La lista escrita a mano en `updateTabs` falló **dos veces**, y las dos igual:
alguien añade un campo, no se acuerda de apuntarlo en el sitio lejano donde se oculta, y el campo se queda
dibujado encima de Habilidades y Ataques — sin rótulo, sin hacer nada y sin que falle nada. Le pasó
primero a la tanda entera (`AUDIT_UX.md`) y después a **Nivel, Hambre y el botón de Guía**, que se veían
como dos recuadros vacíos junto al `+` de Ataques.

Las otras dos pestañas no tienen este problema porque sus listas (`skillButtons`, `skillEditButtons`) las
rellena `makeRollButton` sola, y Ataques solo tiene dos widgets.

Los dos botones de iniciativa se afinan **después** del bucle: comparten sitio y se turnan según el modo
edición, así que el bucle los pone a todos por igual y esas dos líneas los ajustan.

### Panel lateral

Era lo único de la hoja sin cabecera, y se ve en las tres pestañas — por eso su `section()` se dibuja
fuera del `switch` de `renderLabels`.

El botón de modo edición estaba en `x = leftPos - 6`: **fuera del panel**, sobre el margen del pergamino,
donde se leía como un icono suelto sin relación con nada. Ahora va alineado con la columna del nombre y de
los dados de característica, justo debajo de la última.

`section()` **solo dibuja el filete si queda sitio**. `GuiGraphics.fill` con el borde izquierdo pasado del
derecho no deja de dibujar: pinta el rectángulo al revés. En el panel lateral el título casi llena el
ancho (`CARACTERÍSTICAS` mide 90 px de los 89 disponibles hasta el borde del bloque), así que en español
la cabecera va sin filete y en inglés con él.

### Iconos de característica (`tools/make_ability_icons.py`)

Los seis del panel lateral. Los de MCreator eran dibujos saturados y de estilos distintos entre sí —brazo
rojo, conejo blanco, cerebro azul, búho— que no pertenecían a la paleta del mod.

**La tentación al tematizarlos era pasarlos todos a latón, como los iconos de tirada. Habría sido un
error:** son lo único que identifica cada fila, y seis siluetas del mismo color se convierten en seis
manchas parecidas. Lo que los hace legibles de un vistazo es el color.

Así que cada uno conserva su tono, pero como pigmento de manuscrito: todos apagados al mismo nivel y todos
con el mismo contorno de tinta que el resto de la hoja. Contorno común, saturación común, tono y silueta
distintos — eso es lo que convierte seis dibujos sueltos en un juego.

| | silueta | pigmento |
|---|---|---|
| FUE | brazo flexionado | óxido |
| DES | flecha | cardenillo |
| CON | corazón | burdeos |
| INT | libro abierto | lapislázuli |
| SAB | ojo | verde azulado |
| CAR | corona | berenjena |

Flecha y no pluma porque a 16 px se confundiría con el icono de modo edición, que ya es una. Ojo y no
búho porque un búho a ese tamaño es una mancha redonda. Y el brazo va con máscara de píxel y no con
polígono: lo que distingue un brazo de una herradura son dos detalles asimétricos —el puño arriba y el
bulto del bíceps abajo— y con un polígono salía un arco simétrico.

Carisma es berenjena y no dorado a propósito: el latón ya significa "esto se pulsa" en esta interfaz.

`JsonContentSelfTest.checkAbilityIcons` exige que los seis pigmentos sean distintos. Repetir uno no rompe
nada, no avisa, y deja dos características indistinguibles en la columna.

### Estado del rediseño

Todas las pantallas están cubiertas. `GuideBook` es la única que usa una pantalla de vanilla
(`BookViewScreen`) y es deliberado: un libro escrito ya se lee como un tomo, y de paso sale gratis la
paginación.
