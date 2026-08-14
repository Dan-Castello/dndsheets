# Deuda técnica pendiente — dndsheets

Este archivo era la auditoría completa del 2026-08-07 (26 hallazgos, F1-F26). Verificado contra el código el 2026-08-12: **16 ya estaban resueltos** (F1, F2, F3, F5, F6, F8, F10, F13, F15, F16, F17, F19, F20, F21, F24, F25) y se quitaron de aquí. Pasada del 2026-08-14 (refactor dirigido, ver commit correspondiente): **resueltos F4, F7, F9, F11, F12, F14, F18, F22**; **F23 ya estaba resuelto** desde antes de esta pasada (el código ya tenía `panelActive`/`editMode` como `private static`) y se retira de la lista de abiertos. Queda **1 abierto** (parcial, fuera de alcance de la pasada del 2026-08-14: es trabajo de tests, no de refactor).

| ID | Prioridad | Categoría | Ubicación | Descripción | Propuesta | Riesgo | Esfuerzo |
|---|---|---|---|---|---|---|---|
| F26 | Media (parcial) | Testabilidad | `DiceManager.rollAttack`/`rollDamage` | `JsonContentSelfTest.checkDice()` ya cubre `roll()` a fondo, pero no `rollAttack`/`rollDamage`. | Extender `checkDice()` (o método hermano) cubriendo esos dos. | Bajo | S |

## Resueltos en la pasada del 2026-08-14

| ID | Ubicación | Qué se hizo |
|---|---|---|
| F4 | `CharacterSheetScreen.java` `updateTabs()` | Extraído `setActiveVisible(boolean, AbstractWidget...)`; reemplazan los ~16+5 pares `x.active=/x.visible=` repetidos (incluida la sección "Side Panel", con el mismo patrón). |
| F7 | `DiceManager.java` | Eliminado el constructor público vacío (sin usos), la clase anidada `ForgeBusEvents` y el hook `init()` vacíos, y la anotación `@Mod.EventBusSubscriber` de clase que quedó sin ningún `@SubscribeEvent` real. |
| F9 | `GrimoireScreen.java` | `emptyMessage()` ya no reconstruye `knownSpells()` entero; usa `hasNoKnownSpells()`, una comprobación de vacío directa sobre el JSON de la hoja. |
| F11 | `DndsheetsMod.java` (`tick()`) | Salida temprana si `workQueue` está vacío; recorrido con `Iterator.remove()` en vez de construir un `ArrayList` + `removeAll` (O(n·m)) cada tick. |
| F12 | `CharacterSheetScreen.java`, `RollScrollWidget.java` | `getEditBoxes()` (reconstruía `ArrayList`+array en cada llamada) reemplazado por `RollScrollWidget.tickNameBoxes()` y `forwardKeyToFocusedNameBox(...)`, que iteran la lista interna directo sin materializar un array nuevo. |
| F14 | `network/SheetGoldMessage`, `SheetLevelMessage`, `SheetSlotsMessage`, `SheetAdvantageMessage`, `SheetDamageAffinityMessage`, `SheetPactMessage` | Fusionados en `network/SheetAdjustMessage`, parametrizado por un enum `Field` (`GOLD`, `LEVEL`, `SLOTS`, `ADVANTAGE`, `DAMAGE_AFFINITY`, `PACT`), mismo patrón que `ScreenActionMessage`. **Breaking change de protocolo**: `DndsheetsMod.messageID` se asigna por orden de registro, no por constante fija por clase, así que fusionar 6 entradas en 1 no solo cambia el id de esas 6 acciones sino que renumera TODO lo registrado después en `registerNetworkMessages()` (`SheetClientMessage` en adelante). Para que un cliente/servidor de versión distinta se rechace limpio al conectar en vez de desalinear ids en silencio, se subió `PROTOCOL_VERSION` de `"1"` a `"2"` en el mismo cambio. |
| F18 | `CharacterSheetScreen.java` | Eliminada la línea comentada con la clave de lang muerta `gui.dndsheets.character_sheet.label_character_sheet` (0 referencias activas en `en_us.json`/`es_es.json`, confirmado antes de tocar nada). |
| F22 | `Config.java` (`registerWeapon`) | De los 5 overloads (hasta 10 parámetros `String` posicionales), solo el de 10 parámetros tenía llamadas reales (`Config.loadFile` y `DndSheetsApi.registerWeapon`, que ya usa `WeaponRegistration`); se eliminaron los otros 4, sin overloads muertos. Se ajustó `JsonContentSelfTest.checkWeapons()` a la firma resultante. |
| F23 | `CharacterSheetScreen.java` | Verificado: `panelActive` y `editMode` ya eran `private static` en el código actual — no había nada que hacer, se retira de "abiertos". |

Nota sobre I-1/I-2 y el punto 3 de la lista de trabajo (saneo de nombre de pool de mazmorras, `ProjectileImpactEvent.setCanceled` deprecado, y el `AbstractMap.SimpleEntry` sin genéricos en `DndsheetsMod.queueServerWork`): no forman parte de la tabla F1-F26 original de este archivo, se aplicaron igual como parte de la misma pasada — ver el resumen del refactor para el detalle.
