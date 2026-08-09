# Referencia de GUIs — dndsheets

Catálogo de todas las pantallas del mod (`net.hawthorn.dndsheets.client.gui`) con sus tamaños, posiciones y texturas, pensado para consultarse antes de crear o modificar una interfaz — tanto por personas como por una IA — en vez de rastrear el código Java disperso.

El mod usa la API de GUI **vanilla de Minecraft/Forge** (`net.minecraft.client.gui.*`), sin motor de layout ni JSON: cada pantalla define sus posiciones como constantes `private static final int ..._X/_Y` (a veces `_SIZE_X/_Y`, `_SEPARATION`) codificadas a mano en Java.

## Sistema de coordenadas

- **Pantallas de contenedor** (`AbstractContainerScreen<T>`, registradas como menú): el origen es `this.leftPos`/`this.topPos` (esquina superior izquierda del panel, centrado automáticamente por Minecraft según `imageWidth`/`imageHeight`). Todas las constantes `_OFFSET_X/_OFFSET_Y` de estas pantallas son relativas a ese origen.
- **Pantallas planas** (`Screen`, abiertas a mano con `Minecraft.getInstance().setScreen(...)`): no hay panel de fondo fijo; los widgets se centran calculando contra `this.width`/`this.height` en cada `init()`.
- **Diálogos modales** (extienden `ModalDialogScreen`): caja centrada de tamaño fijo `dialogWidth x dialogHeight`; los botones se añaden con `addModalButton(x, y, width, height, message, onPress)`, donde x/y ya son relativos a la esquina superior izquierda de la caja (ver sección `ModalDialogScreen` más abajo).

## Widgets y bases compartidas

Documentados una sola vez aquí; las pantallas que los usan solo indican qué instancia crean (posición/tamaño), no repiten su comportamiento.

- `ModalDialogScreen` (`client/gui/ModalDialogScreen.java`) — base para diálogos centrados de tamaño fijo. Usada por `RestChoiceScreen`, `RestVoteScreen`, `DeathSaveScreen`.
- `AdjustableImageButton` (`client/gui/components/AdjustableImageButton.java`)
- `ButtonListWidget` (`client/gui/components/ButtonListWidget.java`)
- `RollScrollWidget` (`client/gui/components/RollScrollWidget.java`)

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

Solo 3 de las 20 pantallas son `AbstractContainerScreen` registradas como menú real (`init/DndsheetsModScreens.java`): `CharacterSheetScreen`, `RollEditorScreen`, `AdvancedRollEditorScreen`. El resto son `Screen` planas abiertas imperativamente vía un método estático `open(...)`.

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
- **Tipo:** `Screen` (plana, no registrada como menú)
- **Cómo se abre:** `DmPanelScreen.open()`, disparado por el keybind `DndsheetsModKeyMappings.DM_PANEL` (comprueba permisos de operador antes)
- **Textura de fondo:** ninguna (fondo estándar de `Screen`)
- **Tamaño del panel:** pantalla completa, contenido centrado por `this.width`/`this.height`

| Widget | Tipo | X | Y | Ancho | Alto | Notas |
|---|---|---|---|---|---|---|
| 5 botones ("Modo turnos", "Invocar NPC genérico", "Conceder rasgo", "Ajustes de hoja", "Aplicar preset a jugador") | Button | (width-200)/2 | centrado verticalmente, fila n: `startY + n×(20+4)` | BUTTON_WIDTH=200 | BUTTON_HEIGHT=20 | `SPACING=4` entre filas; `startY = (height - 5×24)/2` |

- Acciones: abren `TurnControlScreen`, `SpawnGenericScreen`, o `PlayerPickerScreen` (para elegir jugador antes de rasgo/ajustes/preset).

---

## ModalDialogScreen

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/ModalDialogScreen.java`
- **Tipo:** clase base abstracta, extiende `Screen`
- **Propósito:** caja de diálogo de tamaño fijo centrada en pantalla; evita repetir el mismo esqueleto en cada diálogo modal (antes duplicado en `RestChoiceScreen`/`RestVoteScreen`/`DeathSaveScreen` — ver `AUDIT_TECHNICAL.md M-DUP-7`)
- **Constructor:** `ModalDialogScreen(Component title, int dialogWidth, int dialogHeight)`
- **Geometría:** `dialogLeft() = (width - dialogWidth) / 2`, `dialogTop() = (height - dialogHeight) / 2`
- **API para subclases:** `addModalButton(int x, int y, int width, int height, Component message, Button.OnPress onPress)` — x/y son relativos a la esquina superior izquierda del diálogo (`dialogLeft()+x`, `dialogTop()+y`), no a la pantalla completa.
- Usada por `RestChoiceScreen`, `RestVoteScreen`, `DeathSaveScreen` (ver sus secciones abajo).

## AddMonsterAttackScreen

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/AddMonsterAttackScreen.java`
- **Tipo:** `Screen` (plana)
- **Cómo se abre:** `AddMonsterAttackScreen.open(int entityId)` — desde el botón "+ Añadir ataque" de `MonsterActionScreen`
- **Textura de fondo:** ninguna
- **Tamaño del panel:** full-screen centrado; `centerX = width/2`; `y0 = height/2 - ROW_HEIGHT*3` (`ROW_HEIGHT=26` → `height/2 - 78`)

Constantes: `FIELD_WIDTH=160`, `FIELD_HEIGHT=20`, `ROW_HEIGHT=26`

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
- **Tipo:** `Screen` (plana)
- **Cómo se abre:** `SpawnGenericScreen.open()` — desde el Panel de DM
- **Textura de fondo:** ninguna
- **Tamaño del panel:** full-screen centrado; `centerX = width/2`; `y0 = height/2 - ROW_HEIGHT*2` (= `height/2 - 52`)

Constantes: `FIELD_WIDTH=160`, `FIELD_HEIGHT=20`, `ROW_HEIGHT=26`

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
- **Tipo:** `Screen` (plana)
- **Cómo se abre:** `AddTurnEffectScreen.open(String targetUuid)` — callback pasado a `PlayerPickerScreen.open(...)` desde el botón "Aplicar efecto" de `TurnControlScreen`
- **Textura de fondo:** ninguna
- **Tamaño del panel:** full-screen centrado; `centerX = width/2`; `y0 = height/2 - ROW_HEIGHT*2` (= `height/2 - 52`)

Constantes: `FIELD_WIDTH=160`, `FIELD_HEIGHT=20`, `ROW_HEIGHT=26`

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
- **Tipo:** `Screen` (plana)
- **Cómo se abre:** `TurnControlScreen.open()` — desde el Panel de DM (equivalente GUI de `/dndturns start|next|cancel|end`)
- **Textura de fondo:** ninguna
- **Tamaño del panel:** full-screen centrado; `BUTTON_WIDTH=200`, `BUTTON_HEIGHT=20`, `SPACING=4`; `totalHeight = 5*24 = 120`; `startY = (height-120)/2`

| Widget | Tipo | X | Y | Ancho | Alto | Notas |
|---|---|---|---|---|---|---|
| Botones de acción (bucle ×4) | Button | (width-200)/2 | startY + i×24 (i=0..3) | 200 | 20 | ACTIONS=[start,next,cancel,end], LABELS=["Iniciar turnos","Siguiente turno","Saltar (cancelar)","Terminar turnos"]; envían `TurnControlMessage(action)`, cierran |
| Aplicar efecto | Button | (width-200)/2 | startY + 4×24 | 200 | 20 | abre `PlayerPickerScreen.open("Elige a quién aplicar el efecto", AddTurnEffectScreen::open)` |

## PresetScreen

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/PresetScreen.java`
- **Tipo:** `Screen` (plana)
- **Cómo se abre:** `PresetScreen.open(String targetUuid, List<String> ids, List<String> names)` — al recibir `PresetListMessage` del servidor (pedida al pulsar "Presets" en la hoja, o por un DM tras elegir jugador en `PlayerPickerScreen`)
- **Textura de fondo:** ninguna
- **Tamaño del panel:** full-screen; lista con scroll `ButtonListWidget`

Constantes: `BUTTON_WIDTH=200`, `BUTTON_HEIGHT=20`, `SPACING=4`

| Widget | Tipo | X | Y | Ancho | Alto | Notas |
|---|---|---|---|---|---|---|
| Título "Elige un preset de clase" | drawCenteredString | width/2 | 16 | — | — | color `0xFFFFFF` |
| list (ButtonListWidget) | contenedor scroll | (width-200)/2 | 30 | 200 | height-44 | fila = 24 |
| Botones de preset (bucle) | Button (en `list`) | 0 rel. | 0 rel. | 200 | 20 | uno por preset; onClick envía `PresetApplyMessage`/`PresetApplyToMessage`, cierra |
| Mensaje "sin presets" | drawCenteredString | width/2 | height/2 | — | — | solo si vacío, color `0x888888` |

- **Notas:** `mouseScrolled` forzado siempre hacia `list` (mismo arreglo que `CharacterSheetScreen.mouseScrolled`).

## TraitGrantScreen

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/TraitGrantScreen.java`
- **Tipo:** `Screen` (plana)
- **Cómo se abre:** `TraitGrantScreen.open(String targetUuid, List<String> ids, List<String> names)` — último paso de conceder un rasgo desde el Panel de DM, tras elegir jugador en `PlayerPickerScreen`
- **Textura de fondo:** ninguna
- **Tamaño del panel:** full-screen, centrado

| Widget | Tipo | X | Y | Ancho | Alto | Notas |
|---|---|---|---|---|---|---|
| Título "Elige un rasgo para conceder" | drawCenteredString | width/2 | 16 | — | — | color `0xFFFFFF` |
| Lista de rasgos | ButtonListWidget | (width-200)/2 | 30 | 200 | height-44 | fila=24; onClick envía `TraitGrantMessage`, cierra |
| Mensaje "No hay rasgos cargados" | drawCenteredString | width/2 | height/2 | — | — | solo si vacío, color `0x888888` |

## PlayerPickerScreen

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/PlayerPickerScreen.java`
- **Tipo:** `Screen` (plana)
- **Cómo se abre:** `PlayerPickerScreen.open(String prompt, Consumer<String> onPick)` — primer paso genérico de cualquier herramienta del Panel de DM que actúa sobre otro jugador
- **Textura de fondo:** ninguna
- **Tamaño del panel:** full-screen, centrado

| Widget | Tipo | X | Y | Ancho | Alto | Notas |
|---|---|---|---|---|---|---|
| Título (`prompt`, dinámico) | drawCenteredString | width/2 | 16 | — | — | color `0xFFFFFF` |
| Lista de jugadores conectados | ButtonListWidget | (width-200)/2 | 30 | 200 | height-44 | fila=24; una fila por jugador de la tablist; onClick llama `onPick.accept(uuid)` (no cierra por sí misma) |

## CharacterOptionListScreen

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/CharacterOptionListScreen.java`
- **Tipo:** `Screen` (plana)
- **Cómo se abre:** `CharacterOptionListScreen.open(CharacterSheetScreen returnTo, String category, List<String> options)` — selector de Raza/Trasfondo/Clase invocado desde `CharacterSheetScreen`
- **Textura de fondo:** ninguna
- **Tamaño del panel:** full-screen, centrado

| Widget | Tipo | X | Y | Ancho | Alto | Notas |
|---|---|---|---|---|---|---|
| Título (dinámico: "Elige una raza/un trasfondo/una clase/una opción") | drawCenteredString | width/2 | 16 | — | — | color `0xFFFFFF` |
| Lista de opciones | ButtonListWidget | (width-200)/2 | 30 | 200 | height-44-20-4 | fila=24; deja hueco para "Cancelar" fijo debajo; onClick escribe el campo y envía `SheetServerMessage`, cierra |
| Botón "Cancelar" | Button | (width-200)/2 | height-20-8 | 200 | 20 | fijo, fuera de la lista; `onClose()` |
| Mensaje "No hay opciones cargadas" | drawCenteredString | width/2 | height/2 | — | — | solo si vacío, color `0x888888` |

- **Notas:** `onClose()` vuelve a `returnTo` (misma instancia de `CharacterSheetScreen`) en vez de cerrar todo, tanto al elegir como al cancelar/Escape.

## ManageCustomAttacksScreen

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/ManageCustomAttacksScreen.java`
- **Tipo:** `Screen` (plana)
- **Cómo se abre:** `ManageCustomAttacksScreen.open(int entityId, List<String> customAttackNames)` — desde `MonsterActionScreen` ("Gestionar ataques personalizados")
- **Textura de fondo:** ninguna
- **Tamaño del panel:** full-screen, centrado

| Widget | Tipo | X | Y | Ancho | Alto | Notas |
|---|---|---|---|---|---|---|
| Lista de ataques + "Borrar todos" | ButtonListWidget | (width-200)/2 | 30 | 200 | height-44 | fila=24; una fila "Quitar: {name}" por ataque (envía `RemoveCustomAttackMessage`, cierra), más fila final fija "Borrar todos" (`ClearCustomAttacksMessage`, cierra) |
| Mensaje "Este monstruo no tiene ataques personalizados" | drawCenteredString | width/2 | 16 | — | — | solo si vacío, color `0x888888` (sin título de texto separado en esta pantalla) |

## MonsterActionScreen

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/MonsterActionScreen.java`
- **Tipo:** `Screen` (plana)
- **Cómo se abre:** `MonsterActionScreen.open(int entityId, List<String> actionNames, List<String> customAttackNames)` — al hacer clic derecho con la Vara de DM sobre un monstruo invocado
- **Textura de fondo:** ninguna
- **Tamaño del panel:** full-screen, centrado

| Widget | Tipo | X | Y | Ancho | Alto | Notas |
|---|---|---|---|---|---|---|
| Lista de acciones | ButtonListWidget | (width-200)/2 | 30 | 200 | height-44 | fila=24; una fila por acción (envía `MonsterActionChooseMessage(entityId, i)`, cierra); fila fija "+ Añadir ataque" (abre `AddMonsterAttackScreen`); fila fija "Gestionar ataques personalizados" (abre `ManageCustomAttacksScreen`) |

- **Notas:** sin título ni mensajes de estado vacío (no sobreescribe `render()`).

## GrimoireScreen

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/GrimoireScreen.java`
- **Tipo:** `Screen` (plana)
- **Cómo se abre:** sin `open()` estático; instanciado directo con `new GrimoireScreen()` desde el botón `grimoireButton` de `CharacterSheetScreen`
- **Textura de fondo:** ninguna
- **Tamaño del panel:** full-screen centrado; lista de ancho fijo `WIDTH=220`, alto dinámico según `this.height`

| Widget | Tipo | X | Y | Ancho | Alto | Notas |
|---|---|---|---|---|---|---|
| Título "Grimorio" | drawCenteredString | width/2 | 12 | — | — | color `0xFFFFFF` |
| "Espacios de conjuro: n/max" | drawCenteredString | width/2 | 26 | — | — | color `0xAAAAAA` |
| Lista de hechizos | ButtonListWidget | (width-220)/2 | LIST_TOP=40 | 220 | max(20, (height-28)-40) | scrollable; fila=24 |
| Fila de hechizo (bucle) | Button (en lista) | 0 rel. | 0 rel. | 220 | 20 | label "<nombre> (nv. <nivel>)" |
| Botón "Elige un hechizo / Lanzar: X" | Button | (width-220)/2 | LIST_TOP+listHeight+SPACING | 220 | 20 | inactivo hasta seleccionar hechizo |
| Mensaje "No conoces ningún hechizo..." | drawCenteredString | width/2 | height/2 | — | — | solo si vacío, color `0x888888` |

- **Colores especiales:** `0xFFFFFF` título, `0xAAAAAA` subtítulo, `0x888888` mensaje vacío.

## SheetAdjustScreen

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/SheetAdjustScreen.java`
- **Tipo:** `Screen` (plana)
- **Cómo se abre:** `SheetAdjustScreen.open(targetUuid, targetName, gold, slotsMax, slotsCurrent, hp, maxHp, ac)` — desde el Panel de DM tras elegir jugador en `PlayerPickerScreen`
- **Textura de fondo:** ninguna
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
| Botón "Cerrar" | Button | centerX-95 | y0+186 | 190 | 20 | `onClose()` |

- **Colores especiales:** `0xFFFFFF` título, `0xFFAA00` línea PG/CA de solo lectura.

## RestChoiceScreen

- **Archivo:** `src/main/java/net/hawthorn/dndsheets/client/gui/RestChoiceScreen.java`
- **Tipo:** extiende `ModalDialogScreen`
- **Cómo se abre:** `RestChoiceScreen.open()` — para quien usó el Kit de Descanso
- **Textura de fondo:** ninguna
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
- **Textura de fondo:** ninguna
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
- **Textura de fondo:** ninguna; dibuja panel propio con `guiGraphics.fill(...)`
- **Tamaño del panel:** `dialogWidth x dialogHeight = 240 x 90`, centrado

| Widget | Tipo | X (rel. diálogo) | Y (rel. diálogo) | Ancho | Alto | Notas |
|---|---|---|---|---|---|---|
| Fondo del diálogo | guiGraphics.fill | dialogLeft() | dialogTop() | 240 | 90 | color `0xCC000000` |
| Título "¡Estás caído!" | drawCenteredString | width/2 (absoluto) | dialogTop()+8 | — | — | color `0xFFFFFF` |
| "Éxitos: ●●○  Fallos: ●○○" | drawCenteredString | width/2 (absoluto) | dialogTop()+24 | — | — | color `0xAAAAAA`; símbolos según `deathSaveSuccesses`/`deathSaveFailures` de la hoja |
| "Otro jugador puede reanimarte interactuando contigo." | drawCenteredString | width/2 (absoluto) | dialogTop()+38 | — | — | color `0x888888` |
| Botón "Tirar salvación de muerte" | addModalButton | 20 | 60 | 200 | 20 | `DeathSaveRollMessage()` |

- **Colores especiales:** `0xCC000000` panel de fondo, `0xFFFFFF` título, `0xAAAAAA` marcadores éxito/fallo, `0x888888` texto de ayuda.
- **Notas:** `shouldCloseOnEsc()` e `isPauseScreen()` devuelven `false`.
