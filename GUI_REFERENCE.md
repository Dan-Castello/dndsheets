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
- `ListPickerScreen` (`client/gui/ListPickerScreen.java`) — base para pantallas de lista/menú vertical de botones. Maneja la navegación "&lt; Atrás" (ver más abajo).
- `ModalDialogScreen` (`client/gui/ModalDialogScreen.java`) — base para diálogos centrados de tamaño fijo. Usada por `RestChoiceScreen`, `RestVoteScreen`, `DeathSaveScreen`.
- `SmallFormScreen` (`client/gui/SmallFormScreen.java`) — base para formularios cortos de una columna. Usada por `SpawnGenericScreen`, `AddTurnEffectScreen`, `AddMonsterAttackScreen`, `DungeonPieceAddScreen`, `DungeonPieceEditScreen`, `DungeonGenerateScreen`, `DungeonJigsawConfigureScreen`. Misma navegación "&lt; Atrás" que `ListPickerScreen`.
- `AdjustableImageButton` (`client/gui/components/AdjustableImageButton.java`)
- `ButtonListWidget` (`client/gui/components/ButtonListWidget.java`)
- `RollScrollWidget` (`client/gui/components/RollScrollWidget.java`)

### GuiStyle

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/GuiStyle.java`
- **Propósito:** único sitio que define el aspecto de las pantallas "planas" (sin textura de fondo): un panel `0xCC101010` con borde de 1px `0xFF3E3E3E` (método estático `panel(guiGraphics, left, top, right, bottom)`), más las constantes de color `TITLE_COLOR` (`0xFFFFFF`), `SUBTITLE_COLOR` (`0xAAAAAA`) y `MUTED_COLOR` (`0x888888`, texto de estado vacío). Antes cada pantalla flotaba con sus botones sueltos sobre el fondo borroso vanilla sin ningún panel — la única excepción ad hoc era el relleno manual que tenía `DeathSaveScreen`.
- **Consumido por:** `ListPickerScreen`, `ModalDialogScreen.renderPanel()`, `SmallFormScreen`, `SheetAdjustScreen` — es decir, todas las pantallas sin textura del mod.

### ListPickerScreen

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/ListPickerScreen.java`
- **Extiende:** `net.minecraft.client.gui.screens.Screen`
- **Propósito:** título centrado (`y=16`) + panel `GuiStyle` + una `ButtonListWidget` con scroll automático. Cubre tanto los selectores "elige uno de varios" como los menús de botón fijo (Panel de DM, Modo turnos) — un menú fijo es solo una lista que nunca llega a desbordar.
- **API para subclases:** `buildRows()` (abstracto, llamado desde `init()`, añade filas con `addRow(Component, Button.OnPress)`); `buttonWidth()` (default 200, sobrescribible — `GrimoireScreen` usa 220); `listTop()`/`listHeight()` (sobrescribibles para dejar hueco a un subtítulo o a un botón fijo bajo la lista); `emptyMessage()` (texto centrado si la lista queda vacía, default ninguno).
- **Notable:** `init()`/`render()` no son `final` — una pantalla con contenido extra (subtítulo de `GrimoireScreen`, botón fijo bajo la lista de `GrimoireScreen`) sobrescribe, llama a `super` primero y añade lo suyo. `mouseScrolled` e `isPauseScreen() -> false` ya están resueltos aquí, no hace falta repetirlos.
- **Navegación (constructor `(Component title, Screen parent)`, o `(Component title)` para una pantalla raíz):** si `parent` no es null, `init()` añade un botón "&lt; Atrás" en la esquina superior izquierda del panel, y `onClose()` (Escape, o cualquier fila/botón que llame a `this.onClose()`) vuelve a `parent` en vez de cerrar el menú entero. Cada pantalla captura su `parent` en su propio `open(...)` estático con `Minecraft.getInstance().screen` — la pantalla que estaba visible en el momento de abrir esta — así que los sitios que llaman a `open(...)` no necesitan pasar nada extra. Sin `parent` (pantallas raíz: `DmPanelScreen`, `MonsterActionScreen`) no hay botón "Atrás" y Escape cierra el menú, igual que antes.
- **Usada por:** `DmPanelScreen`, `TurnControlScreen`, `PlayerPickerScreen`, `TraitGrantScreen`, `CharacterOptionListScreen`, `ManageCustomAttacksScreen`, `MonsterActionScreen`, `PresetScreen`, `GrimoireScreen`, `DungeonPieceListScreen`, `ConditionListScreen`, `CharacterListScreen`, `PartyScreen`.

### Pantallas de personajes y condiciones

Las tres cuelgan de `ListPickerScreen` sin layout propio; lo único reseñable de cada una es de dónde saca sus datos y por qué.

| Pantalla | Se abre desde | Datos | Notas |
|---|---|---|---|
| `ConditionListScreen` | `SheetAdjustScreen` → «Condiciones…» | `conditionsCsv` del `SheetSummaryMessage` que ya traía oro/PG/CA | Alterna las 14 condiciones de 5e. **Sin buscador a propósito**: alternar una reconstruye la pantalla (`rebuildWidgets()`) y eso vaciaría la caja en cada clic. |
| `CharacterListScreen` | `/dndchar` sin argumentos | `RosterListMessage(MINE, …)` | Pulsar una fila manda `SWITCH`; **no** repinta en local, espera la lista nueva del servidor, que es quien decide si el personaje era tuyo. |
| `PartyScreen` | Panel de DM → «Grupo» | `RosterListMessage(PARTY, …)` | Solo lectura. `buttonWidth()` a 260: a 200 se cortaba la fila (nombre + PG + CA + condiciones). |

El estado llega **ya formateado desde el servidor** en las tres: el cliente solo lo pinta, así que componer el texto allí evita mandar media hoja de personaje por la red. Y ninguna registró un mensaje nuevo salvo el par `Roster*`, que no tenía equivalente reutilizable — `ConditionListScreen` viaja entera sobre `SheetSummaryMessage` y `SheetAdjustMessage`, que ya existían.

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
| grimoireButton | Button | GRIMOIRE_OFFSET_X=90 | GRIMOIRE_OFFSET_Y=228 | BOTTOM_BUTTON_WIDTH=80 | BOTTOM_BUTTON_HEIGHT=16 | abre `GrimoireScreen` |
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
- **Tamaño del panel:** full-screen centrado; `centerX = width/2`; `y0 = height/2 - ROW_HEIGHT*3` (`ROW_HEIGHT=26` → `height/2 - 78`)

Constantes (en `SmallFormScreen`): `FIELD_WIDTH=160`, `FIELD_HEIGHT=20`, `ROW_HEIGHT=26`. Campos/cíclicos añadidos con `addField(...)`/`addCycleButton(...)`, Confirmar/Cancelar generados automáticamente por la base.

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
- **Tamaño del panel:** full-screen centrado; `centerX = width/2`; `y0 = height/2 - ROW_HEIGHT*2` (= `height/2 - 52`)

Constantes (en `SmallFormScreen`): `FIELD_WIDTH=160`, `FIELD_HEIGHT=20`, `ROW_HEIGHT=26`

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
- **Tamaño del panel:** full-screen centrado; `centerX = width/2`; `y0 = height/2 - ROW_HEIGHT*2` (= `height/2 - 52`)

Constantes (en `SmallFormScreen`): `FIELD_WIDTH=160`, `FIELD_HEIGHT=20`, `ROW_HEIGHT=26`

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
- **Tipo:** extiende `ListPickerScreen` (ver esa sección); sobrescribe `buttonWidth()` (220), `listTop()` (deja hueco al subtítulo) y `listHeight()` (deja hueco al botón de lanzar), más `init()`/`render()` para el botón y el subtítulo
- **Cómo se abre:** sin `open()` estático; instanciado directo con `new GrimoireScreen(this)` desde el botón `grimoireButton` de `CharacterSheetScreen` — `this` (la hoja) es el `parent`, así que "&lt; Atrás"/Escape vuelven a ella
- **Textura de fondo:** ninguna — panel `GuiStyle` dibujado por `ListPickerScreen`
- **Tamaño del panel:** full-screen centrado; ancho fijo 220 (vía `buttonWidth()`)

| Widget | Tipo | X | Y | Ancho | Alto | Notas |
|---|---|---|---|---|---|---|
| Título "Grimorio" | `ListPickerScreen` (base) | width/2 | 16 | — | — | color `GuiStyle.TITLE_COLOR` |
| "Espacios de conjuro: n/max" | drawCenteredString | width/2 | SUBTITLE_Y=30 | — | — | color `GuiStyle.SUBTITLE_COLOR` |
| Lista de hechizos | fila de `ListPickerScreen` | (width-220)/2 | listTop()=44 | 220 | listHeight() | scrollable |
| Botón "Elige un hechizo / Lanzar: X" | Button | (width-220)/2 | listTop()+listHeight()+SPACING | 220 | 20 | inactivo hasta seleccionar hechizo |
| Mensaje "No conoces ningún hechizo..." | `emptyMessage()` | — | — | — | — | solo si no hay hechizos conocidos |

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
