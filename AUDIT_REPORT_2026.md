# Auditoría Integral — dndsheets (2026-08-07)

## 0. Inventario del proyecto (verificado)

| Campo | Valor | Fuente |
|---|---|---|
| Mod loader | Forge (`javafml`) | `src/main/resources/META-INF/mods.toml:1` |
| Rango de loader | `[47,)` | `mods.toml:2` |
| Forge | `1.20.1-47.2.0` | `build.gradle:55` |
| Minecraft | `1.20.1` | `mods.toml:16`, `build.gradle:15` |
| Java | 17 | `build.gradle:12` |
| Mappings | Parchment `2023.09.03-1.20.1` | `build.gradle:15` |
| Gradle wrapper | `8.1.1` | `gradle-wrapper.properties` |
| Módulos | Único (sin `common`/`forge`/`fabric`) | `settings.gradle` |
| Tamaño | 132 archivos Java, ~14.780 líneas | `src/main/java/net/hawthorn/dndsheets/` |
| Tests | 1 (`JsonContentSelfTest.java`, fuera del runtime de Forge, atado a `check`) | `build.gradle:86-94` |
| Dependencia externa | `io.github.tfriedrichs:dicebot:1.0.0` (shaded/relocada) | `build.gradle:57-58,69` |

**Nota sobre auditorías previas:** el último commit (`8fa8aae`, "Resolver hallazgos del audit tecnico (bloques 1-17)") indica que ya hubo una pasada de limpieza basada en un `AUDIT_TECHNICAL.md` que varios comentarios del código siguen citando (`M-DUP-7`, `A-DUP-4`, `M-EVT-1`...). **Ese archivo no existe en el repo ni en el historial de git** (`git log --all --diff-filter=A -- AUDIT_TECHNICAL.md` → vacío) — no se pudo verificar su contenido y no se asumió nada sobre él. `AUDIT.md`/`AUDIT_UX.md` sí existieron y fueron borrados del working tree (recuperables con `git show HEAD:AUDIT_UX.md`).

---

## 1. Resumen ejecutivo

El proyecto es un mod Forge de un solo módulo, de tamaño moderado (~14.8K líneas), que ya pasó por al menos una ronda de limpieza técnica documentada en el propio código (comentarios `ver AUDIT_TECHNICAL.md`). Esa base previa se nota: los managers de tick están casi todos migrados a event-driven, hay clases base ya extraídas para patrones de GUI repetidos (`ModalDialogScreen`, `placeholderEditBox`), y el sistema de red usa un dispatcher centralizado en vez de auto-registro disperso.

Dicho esto, la auditoría encontró **un hallazgo crítico real de seguridad**: el mensaje de red que un jugador usa para guardar su propia hoja de personaje (`SheetServerMessage`) no valida rangos en las características (Fuerza/Destreza/Constitución/...), y esos valores alimentan directamente el cálculo real de Clase de Armadura, Puntos de Golpe máximos y modificadores de tirada en combate — un jugador puede volverse prácticamente invulnerable e imparable con un paquete de red manipulado a mano, sin necesitar permisos de operador. Es el hallazgo de mayor prioridad de todo el informe y debería resolverse antes que cualquier otra recomendación de esta auditoría.

Fuera de eso, el resto de hallazgos son de mantenibilidad (algunas migraciones de una limpieza previa quedaron a medias, dos "god classes" —`TurnManager` y `CharacterSheetScreen`—, boilerplate duplicado en red y GUI), un par de problemas de robustez menores (excepciones no capturadas en casos de borde), y varios detalles de configuración (`relocate` de Gradle mal escrito, un recurso muerto empaquetado en el jar, un `.gitignore` incompleto para `runServer/`). No se encontraron problemas de arquitectura general que requieran una reestructuración completa: la organización por paquete es razonable, aunque la raíz del paquete (40 archivos) se beneficiaría de subpaquetes por dominio si el proyecto sigue creciendo.

## 2. Métricas estimadas de calidad

**Estas son estimaciones cualitativas de los 5 agentes de auditoría a partir de lectura directa del código — no provienen de herramientas de análisis estático ni de profiling real.**

| Dimensión | Estimación | Base |
|---|---|---|
| Mantenibilidad | Media-Alta | Convenciones consistentes, comentarios explicando decisiones no obvias, pero 2 "god classes" (`TurnManager` 729 líneas, `CharacterSheetScreen` 1128 líneas) concentran riesgo |
| Complejidad | Media | Sin anidamiento excesivo verificado (p.ej. `SheetLoader.validateSheet` es lineal, no complejo pese a su longitud); la complejidad real está en el volumen de estado estático compartido de `TurnManager` |
| Deuda técnica | Media | Una migración de auditoría previa (`AbilityItemDispatcher`) quedada a medias en 6 de 13 managers; boilerplate duplicado en 3 pantallas de formulario y en `network/` |
| Rendimiento | Buena, con 1 punto de I/O real | Managers ya event-driven, texturas cacheadas, listas con culling; el único problema con impacto medible es I/O de disco síncrona en cambios de dimensión |
| Seguridad | **Crítica en un punto** | Validación de operador correcta en 9+ mensajes de DM verificados uno por uno; falla en el mensaje de edición de la propia hoja del jugador |
| Cobertura de pruebas | Baja | Un solo test fuera del runtime de Forge; lógica testeable sin runtime (p.ej. `DiceManager`) no tiene cobertura |

## 3. Problemas críticos (priorizados)

1. **[Crítica]** `SheetServerMessage` permite a cualquier jugador fijar sus propias características sin límite, afectando CA/PG/daño reales — escalada de poder total sin permisos. (§ Hallazgos, red F1)
2. **[Alta]** El mismo camino de datos puede lanzar una excepción no capturada (`UnsupportedOperationException`) dentro de `LivingHurtEvent`, tumbando lógica del hilo principal del servidor. (F2)
3. **[Alta]** `SheetLoader.clientJoinedServer` reparsea TODAS las hojas del servidor desde disco en cada cambio de dimensión/respawn de cualquier jugador, no solo en login. (F8)
4. **[Alta]** El `relocate` de la dependencia `dicebot` en `build.gradle` está mal escrito (usa `:` en vez de `.`) y no reubica ninguna clase — riesgo real de colisión de classpath con otros mods del modpack. (F16)
5. **[Alta]** `DndSheetsApi.getSheet()` puede lanzar NPE si se llama cuando la hoja no está cargada — riesgo para cualquier mod externo que ya dependa de la API pública nueva. (F17)

## 4. Quick wins (bajo esfuerzo, alto impacto)

Todos con Esfuerzo **S** y Riesgo **Bajo**:

- Clampear ability scores en `SheetServerMessage` (cierra el hallazgo crítico) — F1
- Envolver `sheetInt`/`abilityModifier` en `catch (RuntimeException e)` en vez de solo `NumberFormatException` — F2
- Arreglar el patrón de `relocate` en `build.gradle:69` (`:` → `.`) — F16
- Corregir el null-check en `DndSheetsApi.getSheet()` — F17
- Eliminar `DiceManager` constructor vacío + clase `ForgeBusEvents` stub — F7
- Eliminar `samplesheet.json` sin referencia del jar — F19
- Eliminar la clave de lang muerta `label_character_sheet` — F18
- Helper `setActiveVisible(...)` para `CharacterSheetScreen.updateTabs()` — F4
- Cachear `knownSpells()` en `GrimoireScreen` en vez de recalcular por frame — F9
- Salida temprana en `DndsheetsMod.tick()` cuando `workQueue` está vacío — F11
- Ampliar `.gitignore` para `runServer/` (configs de terceros y estado de servidor) — F20

## 5. Refactorizaciones estructurales recomendadas

- **Completar la migración a `AbilityItemDispatcher`** en los 6 managers que quedaron fuera (F5) — mismo patrón ya aplicado con éxito al resto, cierra una deuda técnica documentada por el propio código.
- **Extraer `SmallFormScreen`** como clase base para `SpawnGenericScreen`/`AddTurnEffectScreen`/`AddMonsterAttackScreen` (F6), análoga a `ModalDialogScreen`.
- **Dividir `TurnManager`** (F3): separar seguimiento de ataques de oportunidad / presupuesto de movimiento del orden de turnos propiamente dicho.
- **Fusionar los 6 mensajes `Sheet*Message`** de ajuste de DM en uno solo parametrizado por campo (F14), siguiendo el patrón ya usado por `ScreenActionMessage`.
- **Endurecer el límite de compatibilidad de la API pública** (F21): los métodos internos que la fachada `DndSheetsApi` envuelve siguen siendo `public` y accesibles saltándose la fachada versionada.
- **Extraer helper `giveItemToTargets`** en `SheetCommand` para los 10 métodos `give*Item` casi idénticos (F10).

## 6. Plan de implementación por fases

### Fase 1 — Limpieza y seguridad urgente
Incluye el hallazgo crítico porque es la prioridad real del proyecto, no solo limpieza de código muerto:
- F1 (clamp de ability scores), F2 (catch de excepción), F7, F18, F19, F20.
- **Objetivo:** cerrar el vector de exploit y eliminar ruido sin tocar lógica de negocio compleja.

### Fase 2 — Optimización (rendimiento/memoria)
- F8 (I/O de `clientJoinedServer`), F9 (`GrimoireScreen`), F12 (`RollScrollWidget.getEditBoxes()` por tick), F11.
- **Objetivo:** eliminar el único punto de I/O bloqueante real y el trabajo redundante por tick/frame ya identificado.

### Fase 3 — Arquitectura (duplicación, SOLID, robustez de red)
- F5 (dispatcher), F6 (`SmallFormScreen`), F4 (`updateTabs`), F10 (`SheetCommand`), F14 (fusión de mensajes), F13 (helper de red), F15 (UUID try/catch), F22 (afinar tipos en API), F23-F24 (API pública encapsulación).
- **Objetivo:** reducir superficie de mantenimiento sin cambiar comportamiento observable.

### Fase 4 — Extensibilidad (API pública)
- F17 (NPE), F21 (límite de compatibilidad real), F23 (tipos con nombre en vez de posicionales), F25 (evento no debería exponer referencia mutable sin garantía), F26 (no reexportar tipo de dependencia externa), F27 (test de `DiceManager`), F3 (split de `TurnManager`, si no se hizo en fase 3 por ser L).
- **Objetivo:** dejar `api/` lista para que otros mods se acoplen sin sorpresas de compatibilidad.

## 7. Riesgos técnicos y regresiones por fase

| Fase | Riesgo principal | Mitigación |
|---|---|---|
| 1 | Clampear ability scores puede romper hojas ya guardadas con valores fuera de rango (poco probable en juego legítimo, pero posible tras el propio exploit) | Clampear también en `SheetLoader.validateSheet` (lectura), no solo al guardar, para autocorregir hojas existentes |
| 2 | Cambiar el evento de carga de hojas (`EntityJoinLevelEvent`→`PlayerRespawnEvent`/mtime-check) puede alterar cuándo se refleja en memoria un cambio hecho por otro proceso (edición manual de archivo) | Probar explícitamente respawn y viaje Nether/End con 2+ jugadores en servidor dedicado antes de mergear |
| 3 | Fusionar 6 mensajes de red rompe compatibilidad de protocolo con cualquier build de cliente/servidor no sincronizada (no afecta guardado, sí una sesión mixta) | Requiere que cliente y servidor se actualicen juntos; documentarlo en el changelog de versión |
| 4 | Cambios en `DndSheetsApi` (tipos de retorno, encapsulación) rompen compatibilidad binaria con mods externos que ya la usen | Ninguno la usa todavía (paquete sin commitear) — es el momento más barato de corregir el diseño, antes de publicar una versión que otros consuman |

## 8. Recomendaciones de pruebas

- **F1/F2 (crítico):** prueba manual en cliente/servidor dedicado enviando un `SheetServerMessage` con valores fuera de rango (requiere cliente modificado o herramienta de paquetes) para confirmar el clamp; añadir caso a `JsonContentSelfTest` si se extrae la lógica de validación a un método puro.
- **F27:** extender `JsonContentSelfTest` con un `checkDice()` que cubra `DiceManager.roll`/`rollAttack`/`rollDamage` con expresiones válidas e inválidas, siguiendo el patrón self-check ya usado (sin JUnit).
- **F8:** prueba manual con 2+ jugadores viajando entre dimensiones en un servidor dedicado, confirmando que las hojas no se recargan de disco innecesariamente (verificable con logging temporal).
- **F5/F6/F14 (refactors mecánicos):** prueba manual en cliente de cada pantalla/manager afectado tras el cambio — son refactors de forma, no de comportamiento, así que basta con confirmar que el comportamiento observable no cambió.
- **General:** no hay gametests (`GameTestServer`) en el proyecto; dado el tamaño del mod, no se recomienda introducirlos solo para esta auditoría — el patrón `JsonContentSelfTest` (self-check sin runtime) ya cubre lo que es barato de testear sin depender de Minecraft.

## 9. Preguntas abiertas / información faltante

- `AUDIT_TECHNICAL.md`, citado por comentarios en el código (`M-DUP-7`, `A-DUP-4`, `M-EVT-1`), no existe en el repo ni en el historial de git — no se pudo verificar qué cubrió exactamente la limpieza previa ni si algún hallazgo de este informe ya fue "descartado a propósito" allí.
- No se ejecutó `gradle build`/`gradle check` como parte de esta auditoría (solo lectura de código); el hallazgo del `relocate` roto (F16) se verificó inspeccionando un jar ya existente en `build/libs/`, no uno generado para esta auditoría — recomendable confirmar con un build limpio.
- No hay entorno de ejecución disponible para medir impacto real en FPS/TPS; todas las clasificaciones de impacto de rendimiento son estimaciones razonadas por frecuencia de invocación, no profiling.
- `runServer/dndsheets/` (contenido cargado en el servidor de pruebas, ej. `bandit.json`) — no quedó claro si es un fixture deliberado a mantener versionado o solo estado de desarrollo local; se dejó como pregunta en vez de asumir (F20).
- No se auditó `templates/*.json` ni `datapacks/dndsheets_loot/` en profundidad (fuera del foco de los 5 agentes lanzados) — si se quiere cobertura completa de "Recursos" (§3.11 del prompt original) sobre datapacks/loot tables, requeriría una pasada adicional.

---

## 10. Hallazgos completos (tabla)

| ID | Prioridad | Categoría | Ubicación | Descripción | Impacto | Propuesta de solución | Beneficio esperado | Riesgo | Esfuerzo |
|---|---|---|---|---|---|---|---|---|---|
| F1 | Crítica | Seguridad (Networking) | `network/SheetServerMessage.java:28-33,80-82` | `PLAYER_EDITABLE_KEYS` permite a un jugador fijar sus propias características (fuerza/destreza/...) sin ningún límite de rango, mergeadas directo del JSON del cliente. | Esos valores alimentan CA real (`CombatManager.armorClassOf`), PG máximo real (`SheetLoader.applyClassHitPoints`) y todas las tiradas (`DiceManager.roll` sustituye `$str/$dex/...`) — un jugador puede volverse invulnerable/imparable sin permisos de operador. | Clampear cada ability score a un rango razonable (1-30) al mergear, igual que ya se hace para `level` en `SheetLevelMessage`. | Seguridad/Integridad de juego | Bajo | S |
| F2 | Alta | Robustez (Networking) | `SheetLoader.java:95-102` (`sheetInt`), `CombatManager.java:328-335` (`abilityModifier`) | Solo capturan `NumberFormatException`; un valor `JsonObject`/`JsonArray` en vez de primitivo lanza `UnsupportedOperationException`, no capturada. | Un jugador manda `"dexterity":{}`; la siguiente vez que alguien lo golpea, `LivingHurtEvent`→`armorClassOf`→`abilityModifier` lanza una excepción sin capturar en el hilo principal del servidor. | Validar `isJsonPrimitive()` en `SheetServerMessage.handle`, o ampliar el catch a `RuntimeException`. | Seguridad/Robustez | Bajo | S |
| F3 | Media | Arquitectura/SRP | `TurnManager.java:65-166,592-673` | God class de 729 líneas: orden de turnos, anclaje de posición, presupuesto de movimiento, ataques de oportunidad y callbacks de ronda, todo como estado estático compartido. | Difícil de testear/aislar; cambios en movimiento arriesgan romper el orden de turnos por proximidad de código. | Extraer `MovementAnchor`/`OpportunityAttackTracker` con estado propio, dejar `TurnManager` solo con orden/ronda/turno. | Mantenibilidad/Testabilidad | Medio | L |
| F4 | Media | Duplicado (GUI) | `CharacterSheetScreen.java:543-655` (`updateTabs()`) | ~16 pares idénticos `x.active=isActive; x.visible=isActive;` repetidos manualmente; ya causó un bug real de UI superpuesta documentado en el propio código. | Alto riesgo de recurrencia del mismo bug al añadir un campo nuevo y olvidar `.visible`. | Helper `setActiveVisible(boolean, AbstractWidget...)` con varargs. | Mantenibilidad/Riesgo de bug | Bajo | S |
| F5 | Alta | Duplicado (migración incompleta) | `FighterSecondWindManager.java`, `PaladinSmiteManager.java`, `SorcererMetamagicManager.java`, `DruidWildShapeManager.java`, `BardInspirationManager.java`, `QuickSpellManager.java` | 6 managers siguen con su propio `@Mod.EventBusSubscriber` duplicando el patrón que ya se centralizó en `AbilityItemDispatcher` para otros 7+ managers (comentario en `BarbarianRageManager.java:66-68` lo confirma). | Hasta 14 handlers de evento redundantes en cada clic derecho de cualquier ítem del juego. | Añadir los flags/casos faltantes a `AbilityItemDispatcher`, eliminar los `@SubscribeEvent` de los 6 managers. | Mantenibilidad/Consistencia | Bajo | M |
| F6 | Alta | Duplicado (GUI) | `SpawnGenericScreen.java`, `AddTurnEffectScreen.java`, `AddMonsterAttackScreen.java` | 3 pantallas duplican constantes de layout, `cycleLabel`, `parseIntOr` y el patrón render/tick de formulario corto. | ~90 líneas de boilerplate casi idéntico en 3 archivos. | Extraer `SmallFormScreen` base (análoga a `ModalDialogScreen`) con helpers de fila de campo/botón cíclico. | Mantenibilidad | Bajo | M |
| F7 | Baja | Código muerto | `DiceManager.java:34-35,216-231` | Constructor público vacío nunca instanciado (verificado por grep) + clase anidada `ForgeBusEvents` con métodos de hook completamente vacíos (stubs de MCreator). | Ninguno funcional; ruido y falsa sensación de hook point. | Eliminar constructor y `ForgeBusEvents`/`init()`. | Legibilidad | Bajo | S |
| F8 | Alta | Rendimiento (I/O) | `SheetLoader.java:61-91` (`clientJoinedServer`) | `EntityJoinLevelEvent` (dispara en login, respawn Y cambio de dimensión) llama `load()`, que hace `Files.walk` + reparseo JSON de TODAS las hojas del servidor, no solo la del jugador que entra. | I/O de disco síncrona bloqueante en el hilo del servidor, repetida en cada viaje Nether/End de cualquier jugador. | Filtrar a solo carga inicial (`sheets` vacío) o cachear por mtime de archivo. | Rendimiento | Bajo | S |
| F9 | Media | Rendimiento (Render) | `GrimoireScreen.java:99,106` | `render()` llama `knownSpells()` cada frame solo para `.isEmpty()`, reconstruyendo la lista completa cuando ya se calculó en `init()`. | Trabajo O(n hechizos) redundante por frame mientras la pantalla está abierta. | Reusar la lista de `init()` guardada en un campo. | Rendimiento (bajo, pero gratis de arreglar) | Bajo | S |
| F10 | Media | Duplicado | `SheetCommand.java:231-372` (10 métodos `give*Item`) | Mismo cuerpo de 5 líneas repetido 10 veces, variando solo el builder de ítem y el mensaje. | ~70 líneas de boilerplate; cada ítem nuevo implica copy-paste. | Helper `giveItemToTargets(ctx, stackSupplier, label)`. | Mantenibilidad | Bajo | S |
| F11 | Baja | Rendimiento (Tick) | `DndsheetsMod.java:141-152` (`tick`) | `ArrayList` nueva incondicional cada tick de servidor, incluso con `workQueue` vacío; `removeAll` sobre `ConcurrentLinkedQueue` es O(n·m) cuando sí hay trabajo. | Presión de GC constante y evitable durante toda la vida del servidor. | Salida temprana si vacío; usar `Iterator.remove()` en vez de `removeAll`. | Rendimiento/GC | Bajo | S |
| F12 | Media | Rendimiento (Tick) | `CharacterSheetScreen.java:402-406`, `RollScrollWidget.java:157-164` (`getEditBoxes`) | Reconstruye `ArrayList`+array nuevo cada tick de cliente (20/s) mientras la pantalla de ataques está abierta, solo para iterar y llamar `.tick()`. | 2 allocations + iteración redundante 20 veces/segundo, escala con nº de ataques. | Cachear el array cuando la lista cambia, o exponer `tickAll()` interno sin copiar. | Rendimiento | Bajo | S |
| F13 | Baja | Duplicado (Networking) | `network/*.java` (41 archivos) | Todos repiten el mismo esqueleto `enqueueWork`+`setPacketHandled` (y `DistExecutor.unsafeRunWhenOn` en 11 de ellos) — patrón de framework, no decisión de diseño. | Cualquier cambio de convención (logging, manejo de errores) obliga a tocar 41 archivos. | Helper `NetworkUtil.handleOnServer/handleOnClient(Runnable)`. | Mantenibilidad | Bajo | M |
| F14 | Media | Duplicado (Networking) | `SheetGoldMessage.java`, `SheetLevelMessage.java`, `SheetSlotsMessage.java`, `SheetAdvantageMessage.java`, `SheetDamageAffinityMessage.java`, `SheetPactMessage.java` | 6 mensajes casi idénticos (`targetUuid`+1-2 campos) delegando a `withDmTarget`, mismo patrón que `ScreenActionMessage` ya resolvió con un enum. | ~180 líneas de boilerplate calcado en 6 archivos, cada uno registrado a mano. | Fusionar en `SheetAdjustMessage(targetUuid, field enum, payload)`. | Mantenibilidad | Medio | M |
| F15 | Baja | Robustez (Networking) | `DndsheetsMod.java:123`, `TurnEffectApplyMessage.java:45`, `PassivePerceptionRequestMessage.java:37`, `PresetListRequestMessage.java:45` | `UUID.fromString(targetUuid)` sin try/catch (solo alcanzable por un operador ya autenticado, `hasPermissions` corre antes). | Excepción no capturada auto-infligida por un operador con cliente roto/modificado. | Try/catch descartando el mensaje si el UUID es inválido. | Robustez | Bajo | S |
| F16 | Alta | Configuración (Gradle) | `build.gradle:69` | `relocate 'io.github.tfriedrichs:dicebot', ...` usa `:` (coordenada Maven) en vez de `.` (paquete Java) — verificado que las clases de dicebot NO quedan reubicadas en el jar final. | Riesgo de colisión de classpath si otro mod del modpack empaqueta la misma librería sin relocar. | Cambiar a `'io.github.tfriedrichs.dicebot'` y confirmar con un build limpio. | Seguridad/Estabilidad de modpack | Bajo | S |
| F17 | Alta | Bug/Robustez (API) | `api/DndSheetsApi.java:45-47` (`getSheet`), `SheetLoader.java:148-156` (`getServerSheet`) | `getSheet` llama `.deepCopy()` directo sobre un resultado que puede ser `null` si la hoja no está cargada o se llama desde cliente. | Cualquier mod externo que llame `getSheet` en el momento equivocado recibe NPE en vez de un valor manejable. | Chequear null antes de `deepCopy()`, documentar/devolver `null` u `Optional`. | Robustez de API pública | Bajo | S |
| F18 | Media | Recursos (lang) | `en_us.json:9`, `es_es.json` (misma clave), referenciada solo comentada en `CharacterSheetScreen.java:464` | Clave `gui.dndsheets.character_sheet.label_character_sheet` sin ninguna referencia activa en código. | Clave muerta que infla el lang file. | Eliminar la clave, o descomentar y arreglar la línea si se quiere el título de vuelta. | Legibilidad/Mantenibilidad | Bajo | S |
| F19 | Media | Recursos | `src/main/resources/samplesheet.json` | Archivo sin referencia desde ningún código Java (verificado por grep), se empaqueta igual en el jar final. | Peso muerto en el jar distribuible, confusión sobre cuál es la fuente real de contenido de ejemplo (`templates/`). | Eliminar, o mover a `templates/` si tenía valor documental. | Legibilidad/Tamaño de jar | Bajo | S |
| F20 | Baja | Configuración (.gitignore) | `.gitignore` vs `runServer/` (tracked: `config/CoroUtil/`, `config/watut-*`, `banned-*.json`, `ops.json`, `whitelist.json`, `servers.dat*`, `eula.txt`, `imgui.ini`...) | Configs de mods de terceros y estado de servidor regenerable quedan versionados, sin cobertura en `.gitignore`. | Ruido de repo en cada arranque local del servidor de pruebas. | Ampliar `.gitignore` para `runServer/` (excepto fixtures deliberados como `dndsheets-common.toml`). | Higiene de repo | Bajo | S |
| F21 | Alta | Diseño de API | `api/DndSheetsApi.java:24-26,40` vs `SpellRegistry`/`TraitRegistry`/`MonsterRegistry`/`PresetRegistry`/`Config`/`DiceManager`/`SheetLoader` (métodos internos ya `public`) | La promesa de compatibilidad de `API_VERSION` es solo documental: nada impide a un mod externo llamar directo a las clases internas que la fachada envuelve, saltándose el versionado. | Mods externos pueden acoplarse a clases que cambian sin aviso, rompiendo la garantía declarada. | Restringir visibilidad de los métodos de uso interno, o documentar explícitamente "no usar directo, ver DndSheetsApi". | Robustez del contrato de API | Medio | M |
| F22 | Media | Diseño de API | `Config.java:189-205` (5 overloads de `registerWeapon`, hasta 10 parámetros `String` posicionales) | Parámetros consecutivos del mismo tipo (`damageType`, `hands`, ...) intercambiables sin que el compilador lo detecte. | Errores de integración silenciosos en mods externos. | Builder o record `WeaponDefinition` con campos nombrados. | Robustez de API/Legibilidad | Bajo | M |
| F23 | Media | Superficie no pensada como API | `CharacterSheetScreen.java:46-47` (`public static PanelStatus panelActive`, `public static boolean editMode`) | Campos estáticos mutables de una pantalla GUI, visibles y mutables desde cualquier mod en el classpath, sin relación con el diseño de `api/` pero con el mismo riesgo. | Mutación no intencional del estado de navegación de la hoja desde fuera de su ciclo de vida esperado. | Encapsular como privado con getter/setter. | Robustez | Bajo | S |
| F24 | Media | Diseño de API/invariantes | `api/event/SheetValidateEvent.java:20-22`, posteado en `SheetLoader.java:323` tras rellenar defaults | El evento expone el `JsonObject` mutable real (no copia); un listener con bug puede borrar campos base ya validados sin que nada lo re-verifique. | Hoja puede quedar inválida por un mod de contenido de terceros, sin error visible hasta que otro sistema falle al leer el campo. | Documentar la prohibición de borrar campos base, o repetir validación mínima tras postear el evento. | Robustez/Integridad de datos | Bajo | S |
| F25 | Media | Diseño de API (acoplamiento externo) | `DiceManager.java:13,42`, expuesto vía `api/DndSheetsApi.java:57-59` (`roll`) | El tipo de retorno público reexporta `DiceResult` de la librería externa `dicebot` en vez de un tipo propio. | Un cambio/sombreado de la dependencia puede romper el contrato de `roll` sin que `API_VERSION` lo refleje. | Envolver en un tipo propio de `dndsheets` (total, tiradas, texto formateado). | Aislamiento de API pública | Bajo | S/M |
| F26 | Media | Testabilidad | `DiceManager.java:63,91,117` vs `JsonContentSelfTest.java` | Firmas sin dependencia de runtime (mismo perfil que los `*Registry` ya testeados), pero `roll`/`rollAttack`/`rollDamage` no tienen ningún caso cubierto, pese a ser la lógica que la API pública expone. | Lógica de parseo de expresiones de dado puede romperse sin detección hasta pruebas manuales in-game. | Añadir `checkDice()` a `JsonContentSelfTest` con el mismo patrón self-check. | Cobertura de pruebas | Bajo | S |

## 11. Bloque JSON equivalente

```json
{
  "audit": "dndsheets",
  "date": "2026-08-07",
  "project": {
    "loader": "forge",
    "loaderVersionRange": "[47,)",
    "forgeVersion": "1.20.1-47.2.0",
    "minecraftVersion": "1.20.1",
    "javaVersion": 17,
    "mappings": "parchment-2023.09.03-1.20.1",
    "gradleWrapper": "8.1.1",
    "modules": "single",
    "javaFiles": 132,
    "linesOfCode": 14780,
    "tests": 1
  },
  "findings": [
    {"id": "F1", "priority": "Crítica", "category": "Seguridad (Networking)", "location": "network/SheetServerMessage.java:28-33,80-82", "description": "PLAYER_EDITABLE_KEYS permite a un jugador fijar sus propias características sin límite de rango.", "impact": "Alimenta CA/PG/daño reales; escalada de poder total sin permisos de operador.", "solution": "Clampear ability scores (1-30) al mergear.", "benefit": "Seguridad/Integridad de juego", "risk": "Bajo", "effort": "S"},
    {"id": "F2", "priority": "Alta", "category": "Robustez (Networking)", "location": "SheetLoader.java:95-102; CombatManager.java:328-335", "description": "Solo se captura NumberFormatException; un JsonObject/JsonArray en vez de primitivo lanza UnsupportedOperationException no capturada.", "impact": "Excepción sin capturar en LivingHurtEvent puede afectar el hilo principal del servidor.", "solution": "Validar isJsonPrimitive() o ampliar el catch a RuntimeException.", "benefit": "Seguridad/Robustez", "risk": "Bajo", "effort": "S"},
    {"id": "F3", "priority": "Media", "category": "Arquitectura/SRP", "location": "TurnManager.java:65-166,592-673", "description": "God class de 729 líneas con 5 responsabilidades mezcladas como estado estático compartido.", "impact": "Difícil de testear/aislar; riesgo de romper orden de turnos al tocar movimiento.", "solution": "Extraer MovementAnchor/OpportunityAttackTracker con estado propio.", "benefit": "Mantenibilidad/Testabilidad", "risk": "Medio", "effort": "L"},
    {"id": "F4", "priority": "Media", "category": "Duplicado (GUI)", "location": "CharacterSheetScreen.java:543-655", "description": "updateTabs() repite ~16 pares idénticos active/visible manualmente; ya causó un bug real de UI superpuesta.", "impact": "Alto riesgo de recurrencia del mismo bug.", "solution": "Helper setActiveVisible(boolean, AbstractWidget...) con varargs.", "benefit": "Mantenibilidad/Riesgo de bug", "risk": "Bajo", "effort": "S"},
    {"id": "F5", "priority": "Alta", "category": "Duplicado (migración incompleta)", "location": "FighterSecondWindManager.java; PaladinSmiteManager.java; SorcererMetamagicManager.java; DruidWildShapeManager.java; BardInspirationManager.java; QuickSpellManager.java", "description": "6 managers no migrados a AbilityItemDispatcher, mantienen su propio EventBusSubscriber duplicado.", "impact": "Hasta 14 handlers de evento redundantes por cada clic derecho de cualquier ítem.", "solution": "Añadir flags/casos faltantes a AbilityItemDispatcher y eliminar los subscribers redundantes.", "benefit": "Mantenibilidad/Consistencia", "risk": "Bajo", "effort": "M"},
    {"id": "F6", "priority": "Alta", "category": "Duplicado (GUI)", "location": "SpawnGenericScreen.java; AddTurnEffectScreen.java; AddMonsterAttackScreen.java", "description": "3 pantallas duplican constantes de layout, cycleLabel, parseIntOr y patrón render/tick de formulario corto.", "impact": "~90 líneas de boilerplate casi idéntico en 3 archivos.", "solution": "Extraer clase base SmallFormScreen.", "benefit": "Mantenibilidad", "risk": "Bajo", "effort": "M"},
    {"id": "F7", "priority": "Baja", "category": "Código muerto", "location": "DiceManager.java:34-35,216-231", "description": "Constructor público vacío nunca instanciado y clase anidada ForgeBusEvents con métodos de hook vacíos.", "impact": "Ninguno funcional; ruido y falso hook point.", "solution": "Eliminar constructor y ForgeBusEvents/init().", "benefit": "Legibilidad", "risk": "Bajo", "effort": "S"},
    {"id": "F8", "priority": "Alta", "category": "Rendimiento (I/O)", "location": "SheetLoader.java:61-91", "description": "clientJoinedServer reparsea todas las hojas del servidor desde disco en cada login/respawn/cambio de dimensión.", "impact": "I/O de disco síncrona bloqueante en el hilo del servidor, repetida por cualquier viaje entre dimensiones.", "solution": "Filtrar a solo carga inicial o cachear por mtime.", "benefit": "Rendimiento", "risk": "Bajo", "effort": "S"},
    {"id": "F9", "priority": "Media", "category": "Rendimiento (Render)", "location": "GrimoireScreen.java:99,106", "description": "render() reconstruye la lista de hechizos conocidos cada frame solo para comprobar isEmpty().", "impact": "Trabajo O(n hechizos) redundante por frame mientras la pantalla está abierta.", "solution": "Reusar la lista calculada en init().", "benefit": "Rendimiento", "risk": "Bajo", "effort": "S"},
    {"id": "F10", "priority": "Media", "category": "Duplicado", "location": "SheetCommand.java:231-372", "description": "10 métodos give*Item con el mismo cuerpo de 5 líneas.", "impact": "~70 líneas de boilerplate; cada ítem nuevo implica copy-paste.", "solution": "Helper giveItemToTargets(ctx, stackSupplier, label).", "benefit": "Mantenibilidad", "risk": "Bajo", "effort": "S"},
    {"id": "F11", "priority": "Baja", "category": "Rendimiento (Tick)", "location": "DndsheetsMod.java:141-152", "description": "ArrayList nueva incondicional cada tick de servidor, incluso con workQueue vacío.", "impact": "Presión de GC constante y evitable.", "solution": "Salida temprana si vacío; Iterator.remove() en vez de removeAll.", "benefit": "Rendimiento/GC", "risk": "Bajo", "effort": "S"},
    {"id": "F12", "priority": "Media", "category": "Rendimiento (Tick)", "location": "CharacterSheetScreen.java:402-406; RollScrollWidget.java:157-164", "description": "getEditBoxes() reconstruye ArrayList+array nuevo cada tick de cliente mientras la pestaña de ataques está abierta.", "impact": "2 allocations + iteración redundante 20 veces/segundo.", "solution": "Cachear el array o exponer tickAll() sin copiar.", "benefit": "Rendimiento", "risk": "Bajo", "effort": "S"},
    {"id": "F13", "priority": "Baja", "category": "Duplicado (Networking)", "location": "network/*.java (41 archivos)", "description": "Mismo esqueleto enqueueWork+setPacketHandled repetido en cada mensaje (boilerplate de framework).", "impact": "Cualquier cambio de convención obliga a tocar 41 archivos.", "solution": "Helper NetworkUtil.handleOnServer/handleOnClient(Runnable).", "benefit": "Mantenibilidad", "risk": "Bajo", "effort": "M"},
    {"id": "F14", "priority": "Media", "category": "Duplicado (Networking)", "location": "SheetGoldMessage.java; SheetLevelMessage.java; SheetSlotsMessage.java; SheetAdvantageMessage.java; SheetDamageAffinityMessage.java; SheetPactMessage.java", "description": "6 mensajes casi idénticos delegando a withDmTarget.", "impact": "~180 líneas de boilerplate calcado en 6 archivos.", "solution": "Fusionar en SheetAdjustMessage(targetUuid, field enum, payload).", "benefit": "Mantenibilidad", "risk": "Medio", "effort": "M"},
    {"id": "F15", "priority": "Baja", "category": "Robustez (Networking)", "location": "DndsheetsMod.java:123; TurnEffectApplyMessage.java:45; PassivePerceptionRequestMessage.java:37; PresetListRequestMessage.java:45", "description": "UUID.fromString sin try/catch (solo alcanzable por operador ya autenticado).", "impact": "Excepción no capturada auto-infligida por un operador con cliente roto.", "solution": "Try/catch descartando el mensaje si el UUID es inválido.", "benefit": "Robustez", "risk": "Bajo", "effort": "S"},
    {"id": "F16", "priority": "Alta", "category": "Configuración (Gradle)", "location": "build.gradle:69", "description": "relocate usa ':' (coordenada Maven) en vez de '.' (paquete Java); dicebot no queda reubicado en el jar.", "impact": "Riesgo de colisión de classpath con otros mods del modpack.", "solution": "Cambiar a 'io.github.tfriedrichs.dicebot' y confirmar con build limpio.", "benefit": "Seguridad/Estabilidad de modpack", "risk": "Bajo", "effort": "S"},
    {"id": "F17", "priority": "Alta", "category": "Bug/Robustez (API)", "location": "api/DndSheetsApi.java:45-47; SheetLoader.java:148-156", "description": "getSheet llama deepCopy() sobre un resultado que puede ser null.", "impact": "NPE para cualquier mod externo que llame getSheet en el momento equivocado.", "solution": "Chequear null antes de deepCopy(); documentar/devolver null u Optional.", "benefit": "Robustez de API pública", "risk": "Bajo", "effort": "S"},
    {"id": "F18", "priority": "Media", "category": "Recursos (lang)", "location": "en_us.json:9; es_es.json", "description": "Clave de lang sin referencia activa en código (solo comentada).", "impact": "Clave muerta que infla el lang file.", "solution": "Eliminar la clave, o descomentar y arreglar el uso.", "benefit": "Legibilidad/Mantenibilidad", "risk": "Bajo", "effort": "S"},
    {"id": "F19", "priority": "Media", "category": "Recursos", "location": "src/main/resources/samplesheet.json", "description": "Archivo sin referencia desde código Java, empaquetado igual en el jar.", "impact": "Peso muerto en el jar; confusión sobre la fuente real de contenido de ejemplo.", "solution": "Eliminar, o mover a templates/.", "benefit": "Legibilidad/Tamaño de jar", "risk": "Bajo", "effort": "S"},
    {"id": "F20", "priority": "Baja", "category": "Configuración (.gitignore)", "location": ".gitignore vs runServer/", "description": "Configs de mods de terceros y estado de servidor regenerable quedan versionados sin cobertura en .gitignore.", "impact": "Ruido de repo en cada arranque local.", "solution": "Ampliar .gitignore para runServer/ salvo fixtures deliberados.", "benefit": "Higiene de repo", "risk": "Bajo", "effort": "S"},
    {"id": "F21", "priority": "Alta", "category": "Diseño de API", "location": "api/DndSheetsApi.java:24-26,40 vs clases internas *Registry/Config/DiceManager/SheetLoader", "description": "La promesa de compatibilidad de API_VERSION es solo documental; nada impide llamar directo a las clases internas.", "impact": "Mods externos pueden acoplarse a clases que cambian sin aviso.", "solution": "Restringir visibilidad de métodos internos o documentar explícitamente la prohibición.", "benefit": "Robustez del contrato de API", "risk": "Medio", "effort": "M"},
    {"id": "F22", "priority": "Media", "category": "Diseño de API", "location": "Config.java:189-205", "description": "5 overloads de registerWeapon con hasta 10 parámetros String posicionales, varios consecutivos del mismo tipo.", "impact": "Errores de integración silenciosos en mods externos.", "solution": "Builder o record WeaponDefinition con campos nombrados.", "benefit": "Robustez de API/Legibilidad", "risk": "Bajo", "effort": "M"},
    {"id": "F23", "priority": "Media", "category": "Superficie no pensada como API", "location": "CharacterSheetScreen.java:46-47", "description": "Campos estáticos mutables (panelActive, editMode) visibles desde cualquier mod en el classpath.", "impact": "Mutación no intencional del estado de navegación de la hoja desde fuera de su ciclo de vida esperado.", "solution": "Encapsular como privado con getter/setter.", "benefit": "Robustez", "risk": "Bajo", "effort": "S"},
    {"id": "F24", "priority": "Media", "category": "Diseño de API/invariantes", "location": "api/event/SheetValidateEvent.java:20-22; SheetLoader.java:323", "description": "El evento expone el JsonObject mutable real sin garantía de invariantes tras postearlo.", "impact": "Un listener con bug puede dejar la hoja en estado inválido sin error visible.", "solution": "Documentar la prohibición de borrar campos base, o re-validar tras postear.", "benefit": "Robustez/Integridad de datos", "risk": "Bajo", "effort": "S"},
    {"id": "F25", "priority": "Media", "category": "Diseño de API (acoplamiento externo)", "location": "DiceManager.java:13,42; api/DndSheetsApi.java:57-59", "description": "El tipo de retorno público reexporta DiceResult de la librería externa dicebot.", "impact": "Un cambio/sombreado de la dependencia puede romper el contrato de roll sin reflejarse en API_VERSION.", "solution": "Envolver en un tipo propio de dndsheets.", "benefit": "Aislamiento de API pública", "risk": "Bajo", "effort": "S/M"},
    {"id": "F26", "priority": "Media", "category": "Testabilidad", "location": "DiceManager.java:63,91,117", "description": "Lógica sin dependencia de runtime (mismo perfil que los *Registry ya testeados) sin ningún caso de prueba.", "impact": "Lógica de parseo de dados expuesta por la API pública puede romperse sin detección.", "solution": "Añadir checkDice() a JsonContentSelfTest.", "benefit": "Cobertura de pruebas", "risk": "Bajo", "effort": "S"}
  ]
}
```
