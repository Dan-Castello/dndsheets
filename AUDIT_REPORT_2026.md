# Deuda técnica pendiente — dndsheets

Este archivo era la auditoría completa del 2026-08-07 (26 hallazgos, F1-F26). Verificado contra el código el 2026-08-12: **16 ya están resueltos** (F1, F2, F3, F5, F6, F8, F10, F13, F15, F16, F17, F19, F20, F21, F24, F25) y se quitaron de aquí. Quedan los **9 abiertos** + **1 parcial**.

| ID | Prioridad | Categoría | Ubicación | Descripción | Propuesta | Riesgo | Esfuerzo |
|---|---|---|---|---|---|---|---|
| F4 | Media | Duplicado (GUI) | `CharacterSheetScreen.java` `updateTabs()` | ~16 pares idénticos `x.active=isActive; x.visible=isActive;` repetidos a mano; ya causó un bug real de UI superpuesta documentado en el propio código. | Helper `setActiveVisible(boolean, AbstractWidget...)` con varargs. | Bajo | S |
| F7 | Baja | Código muerto | `DiceManager.java` | Constructor público vacío nunca instanciado + clase anidada `ForgeBusEvents` con métodos de hook vacíos (stubs de MCreator). | Eliminar constructor y `ForgeBusEvents`/`init()`. | Bajo | S |
| F9 | Media | Rendimiento (Render) | `GrimoireScreen.java` | `render()` llama `knownSpells()` cada frame solo para `.isEmpty()`, reconstruyendo la lista aunque `init()` ya la calculó. | Guardar la lista de `init()` en un campo y reusarla. | Bajo | S |
| F11 | Baja | Rendimiento (Tick) | `DndsheetsMod.java` (`tick()`) | `ArrayList` nueva incondicional cada tick de servidor, incluso con `workQueue` vacío; `removeAll` es O(n·m). | Salida temprana si vacío; `Iterator.remove()` en vez de `removeAll`. | Bajo | S |
| F12 | Media | Rendimiento (Tick) | `CharacterSheetScreen.java`, `RollScrollWidget.getEditBoxes()` | Reconstruye `ArrayList`+array nuevo cada tick de cliente (20/s) mientras la pestaña de ataques está abierta. | Cachear el array cuando la lista cambia. | Bajo | S |
| F14 | Media | Duplicado (Networking) | `SheetGoldMessage`, `SheetLevelMessage`, `SheetSlotsMessage`, `SheetAdvantageMessage`, `SheetDamageAffinityMessage`, `SheetPactMessage` | 6 mensajes casi idénticos delegando a `withDmTarget`, mismo patrón que `ScreenActionMessage` ya resolvió con un enum. | Fusionar en `SheetAdjustMessage(targetUuid, field enum, payload)`. | Medio | M |
| F18 | Media | Recursos (lang) | `en_us.json`/`es_es.json`, comentada en `CharacterSheetScreen.java:470` | Clave `gui.dndsheets.character_sheet.label_character_sheet` sin ninguna referencia activa. | Eliminar la clave, o descomentar y arreglar la línea. | Bajo | S |
| F22 | Media | Diseño de API | `Config.java` (`registerWeapon`, 5 overloads, hasta 10 `String` posicionales) | Parámetros consecutivos del mismo tipo intercambiables sin que el compilador lo detecte. | Builder o record `WeaponDefinition` con campos nombrados. | Bajo | M |
| F23 | Media | Superficie no pensada como API | `CharacterSheetScreen.java` (`public static PanelStatus panelActive`, `public static boolean editMode`) | Campos estáticos mutables de una pantalla GUI, visibles/mutables desde cualquier mod en el classpath. | Encapsular como privado con getter/setter. | Bajo | S |
| F26 | Media (parcial) | Testabilidad | `DiceManager.rollAttack`/`rollDamage` | `JsonContentSelfTest.checkDice()` ya cubre `roll()` a fondo, pero no `rollAttack`/`rollDamage`. | Extender `checkDice()` (o método hermano) cubriendo esos dos. | Bajo | S |

Todos menos F14/F22 son Esfuerzo S — quick wins sueltos, no requieren tocar arquitectura.
