# Implementation Plan: Incorporación guiada al VTT

**Branch**: `001-onboarding-vtt` | **Date**: 2026-11-06 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/001-onboarding-vtt/spec.md`

## Summary

Que un jugador o un DM nuevo descubra y use todas las funciones del mod sin documentación externa. El mod ya tiene buena parte de las piezas (toast de entrada, `WelcomeScreen`, lista de progreso del personaje, paneles por secciones, guía Patchouli, HUD de combate). El plan las **conecta y completa** en lugar de rehacerlas:

1. Un estado de incorporación **por persona** en el NBT persistente del jugador, con hitos que el servidor marca donde ocurre la acción real.
2. Un único mensaje de red parametrizado.
3. P abre un **menú central** según el rol.
4. Las cuatro bases de pantalla ganan **ruta pulsable bajo el título, botón "?" y tecla de ayuda, y flechas/Enter en las listas**. Con eso llegan a las 68 pantallas de una vez.
5. Globos de primer uso en el importador y en el editor de salas.
6. Un repaso visual de todas las pantallas, incluida la ficha.

Detalle de cada decisión en [research.md](research.md).

## Technical Context

**Language/Version**: Java 17 (toolchain de Forge 1.20.1)

**Primary Dependencies**: Minecraft Forge 1.20.1 (47.x); Patchouli 1.20.1-85 (obligatorio, `compileOnly` API); GeckoLib, Curios, Jade, Pehkui (ya presentes, no se tocan). Ninguna dependencia nueva.

**Storage**: NBT persistente del jugador (`Player.PERSISTED_NBT_TAG`) para el progreso; configuración de cliente de Forge para `showOnboardingHints`. Las fichas (`charactersheets/*.json`) no cambian.

**Testing**: `JsonContentSelfTest` (`gradlew testJsonContent`, dentro de `check`) para la lógica pura y la forma de red; `runClient` más `/gui-review` para lo visual ([quickstart.md](quickstart.md)).

**Target Platform**: cliente y servidor de Minecraft 1.20.1 (el mod va en los dos); multijugador y un jugador.

**Project Type**: mod de Minecraft multimódulo (core `dndsheets` + addons `dungeon` y `species`).

**Performance Goals**: abrir el menú, la ruta o la ayuda no debe notarse (menos de 1 fotograma); los hitos solo escriben NBT cuando cambian.

**Constraints**: invariantes de `PROJECT_CONTEXT.md` (1-3 red, 4 escrituras de ficha, 7 bases de pantalla, 8 formato de ficha); `DESIGN.md` (sin colores escritos a mano en las pantallas, 8 idiomas, escala 2 y 3); el core no depende de los addons.

**Scale/Scope**: unas 70 pantallas (68 heredan de las bases), 9 atajos (los 7 actuales más Ayuda y "Panel del DM" directo) más 7 de la vista previa del addon, 11 hitos iniciales, 8 archivos de idioma.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` sigue siendo la **plantilla sin rellenar**: no hay principios ratificados. Como puerta se usan las reglas que el repo ya hace cumplir:

| Puerta | Fuente | Antes | Después del diseño |
|---|---|---|---|
| Mensajes nuevos solo al final; extender uno parametrizado antes que crear clases | Invariantes 1-3 | ✅ | ✅ Un único `OnboardingMessage` con `Action` al final (contrato) |
| Cada mutación de ficha llega a `saveServer` | Invariante 4 | ✅ | ✅ El progreso no vive en la ficha; la ficha no cambia |
| Pantallas solo sobre las 4 bases | Invariante 7 | ✅ | ✅ Menú, ruta, teclas y globos usan `ListPickerScreen`/`ModalDialogScreen`; `CoachMarks` es un componente, no una pantalla |
| Formato de la ficha = datos del usuario | Invariante 8 | ✅ | ✅ Sin claves nuevas; la migración solo **lee** `onboarded` |
| Core sin dependencia de los addons | Mapa de modularidad | ✅ | ✅ El addon registra sus hitos en el core |
| Colores por roles de `GuiStyle`, 8 idiomas, tooltips | `DESIGN.md` | ✅ | ✅ |

Sin violaciones. Recomendación aparte: ratificar una constitución con `/speckit-constitution` a partir de estas mismas reglas.

## Project Structure

### Documentation (this feature)

```text
specs/001-onboarding-vtt/
├── spec.md
├── plan.md              # este archivo
├── research.md          # Fase 0
├── data-model.md        # Fase 1
├── quickstart.md        # Fase 1
├── contracts/
│   ├── onboarding-message.md
│   └── screen-navigation.md
├── checklists/requirements.md
└── tasks.md             # Fase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
src/main/java/net/hawthorn/dndsheets/
├── onboarding/                      # NUEVO
│   ├── Onboarding.java              # servidor: mark/dismiss/reset, login, combate pospuesto, SYNC
│   ├── OnboardingState.java         # puro: NBT <-> estado, transiciones, migración desde la ficha
│   └── OnboardingMilestones.java    # registro de hitos (el core y el addon registran)
├── network/OnboardingMessage.java   # NUEVO (al final del registro)
├── DndsheetsMod.java                # registro del mensaje + versión del protocolo
├── SheetLoader.java                 # login: Onboarding en vez del flag "onboarded"
├── command/RollCommand.java         # hito "dice" + DiceRollMessage en d20 sueltos
├── TurnManager.java / CombatManager / SpellCast… # puntos de marca (first_attack, first_spell, combat_started)
├── network/MonsterSpawnMessage.java, SpawnGenericMessage.java  # monster_spawned
├── init/DndsheetsModKeyMappings.java # P → menú central; nueva KeyMapping de ayuda (F4)
└── client/
    ├── OnboardingClient.java        # NUEVO: último SYNC (estado + isDm)
    └── gui/
        ├── NavParent.java           # NUEVO: interfaz de una sola función
        ├── components/Breadcrumbs.java   # NUEVO: dibujo + clic; recorte puro y probado
        ├── components/CoachMarks.java    # NUEVO: globos con Siguiente/Saltar
        ├── components/TomeButton.java    # estado de selección con teclado
        ├── components/ButtonListWidget.java  # scrollTo(Button): desplazar hasta una fila
        ├── ListPickerScreen.java    # ruta, "?", F4, flechas/Enter, atajo a la derecha de la fila
        ├── FormPanelScreen.java / ModalDialogScreen.java / ItemPickerScreen.java  # ruta, "?", F4
        ├── PlayerPanelScreen.java   # → menú central por rol
        ├── DmPanelScreen.java       # open(parent, sección): abre desplazado a esa sección
        ├── OnboardingPathScreen.java     # NUEVO: "Mis primeros pasos"
        ├── KeyReferenceScreen.java       # NUEVO: referencia de teclas
        ├── WelcomeScreen.java       # por persona, rol, "Tirar un d20"
        ├── PlayerSheetScreen.java   # ruta, "?", ←/→
        └── … las ~68 subclases: helpEntry() donde haga falta + repaso visual
dungeon/src/main/java/net/hawthorn/dndsheets/dungeon/
├── DndsheetsDungeonMod.java         # registra sus hitos
└── client/gui/DungeonMapImportScreen.java, DungeonTraceScreen.java  # CoachMarks + avisos de deshacer
src/main/resources/assets/dndsheets/
├── lang/*.json (8)                  # todas las claves nuevas
└── patchouli_books/guide/en_us/entries/first_steps/  # menu.json, path.json, keys.json (+ espejo en GuideBook)
src/test/java/net/hawthorn/dndsheets/JsonContentSelfTest.java  # forma de red + pruebas de lógica pura
```

**Structure Decision**: se mantiene el diseño multimódulo. Lo nuevo va en un paquete `onboarding/` del core más dos componentes de interfaz. Todo lo demás son cambios en las bases y en los puntos de marca que ya existen. El addon `dungeon` solo **llama** al core.

## Fases de entrega

| Fase | Contenido | Historias | Se puede publicar sola |
|---|---|---|---|
| A | Estado, mensaje, hitos y migración · bienvenida por persona con "Tirar un d20" · ruta · menú central (P) | US1, US2, FR-021..023 | Sí (MVP) |
| B | Bases: ruta pulsable, "?", F4, flechas/Enter, selección visible · referencia de teclas · listas vacías | US4, FR-024..027, FR-030 | Sí |
| C | `CoachMarks` + importador y editor de salas + avisos de deshacer | US3 | Sí |
| D | Repaso visual de todas las pantallas y adaptación de la ficha · `PROJECT_CONTEXT.md` R3 | US5, FR-020, FR-028, FR-029 | Sí |

Orden A → B → C → D: cada fase se apoya en la anterior (C usa la ruta y el "?" de B; D revisa pantallas que B ya cambió).

## Complexity Tracking

No hay violaciones de las puertas. Una decisión con un coste conocido:

| Decisión | Por qué | Alternativa más simple descartada |
|---|---|---|
| Estado en el NBT del jugador, no en la ficha | Va por persona y sobrevive a `makeNew` y a tener varios personajes | En la ficha: se perdería al crear un personaje nuevo |
