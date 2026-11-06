# Data Model: Incorporación guiada al VTT

## OnboardingState (por persona, en el servidor)

Guardado en `player.getPersistentData()` → `Player.PERSISTED_NBT_TAG` → `dndsheets_onboarding` (ver research D1).

| Campo | Tipo NBT | Significado | Reglas |
|---|---|---|---|
| `v` | int | Versión del formato | Hoy `1`. Una versión desconocida se lee como estado vacío **sin borrar** el NBT |
| `welcome` | byte | 0 = no vista · 1 = vista/cerrada | Pasa a 1 al abrirse la bienvenida; nunca vuelve a 0 salvo con `RESET` |
| `done` | list&lt;string&gt; | Ids de hitos completados | Sin duplicados; un id desconocido se **conserva** (puede ser de un addon ausente) pero no se muestra |
| `doneAt` | compound string→long | Momento (epoch ms) en que se completó cada hito | Solo informativo; no lo lee la lógica |
| `pendingWelcome` | byte | 1 = bienvenida pospuesta por combate | Se pone si había combate al entrar; se limpia al abrirla |

**Transiciones**

```text
(sin estado) --login--> migrar() --> {welcome, done inferidos}
welcome=0 --login sin combate--> abrir bienvenida, welcome=1
welcome=0 --login en combate--> pendingWelcome=1 --fin del combate--> abrir, welcome=1, pendingWelcome=0
hito X no hecho --acción real--> done += X, doneAt[X]=ahora, SYNC al cliente
cualquier --RESET (desde la ruta)--> estado vacío, SYNC
```

**Migración** (`migrar`, función pura sobre la ficha activa):
- ficha con `onboarded` → `welcome=1`
- ficha con clase elegida → `character_created`
- nivel > 1, o algún ataque registrado → `first_attack`
- algún espacio de conjuro gastado alguna vez, si consta → `first_spell`; si no consta, no se infiere

## Milestone (registro en memoria, no persistido)

| Campo | Tipo | Ejemplo |
|---|---|---|
| `id` | string estable | `dice` |
| `role` | `PLAYER` \| `DM` | `PLAYER` |
| `order` | int | 10 |
| `titleKey` / `hintKey` | lang key | `gui.dndsheets.onboarding.m.dice` |
| `action` | qué abre la fila (cliente) | abrir `WelcomeScreen` / `CharacterSetupScreen` / `DmPanelScreen` … |
| `helpEntry` | entrada de la guía | `dndsheets:first_steps/dice` |
| `hidden` | bool | `true` en los hitos "visto" (`seen_*`) y en `menu_opened` (no salen en la ruta) |

Registro: `OnboardingMilestones.register(...)`. El core registra los suyos; el addon `dungeon` registra `dungeon_import`, `room_drawn`, `seen_import_help` y `seen_trace_tour`.

**Hitos iniciales**

| id | rol | Se completa cuando (servidor salvo indicación) |
|---|---|---|
| `dice` | PLAYER | `/roll` o `/r` con éxito |
| `character_created` | PLAYER | la ficha activa tiene clase, comprobado en `SheetLoader.saveServer`, por donde pasan todas las escrituras de ficha |
| `sheet_opened` | PLAYER | cliente: se abre `PlayerSheetScreen` → `MARK` |
| `first_attack` | PLAYER | se resuelve un ataque del jugador |
| `first_spell` | PLAYER | el jugador lanza un conjuro |
| `menu_opened` | PLAYER (oculto) | cliente: se abre el menú central → `MARK` en **cada** apertura; su `SYNC` mantiene al día `isDm`. No sale en la ruta |
| `dm_panel` | DM | cliente: se abre `DmPanelScreen` → `MARK` |
| `monster_spawned` | DM | `MonsterSpawnMessage` o `SpawnGenericMessage` aceptados |
| `combat_started` | DM | `TurnManager` arranca un encuentro iniciado por este DM |
| `dungeon_import` | DM | addon, cliente: `DungeonMapImportScreen` lee un mapa sin error → `MARK` |
| `room_drawn` | DM | addon, servidor: `PasteQueue` registra un pegado (`PasteHistory.record(dm, …)`) |

**Visibilidad (FR-007)**: los hitos `DM` se muestran solo si el último `SYNC` trae `isDm=true`. Si el rol cambia, el progreso se conserva y solo cambia lo que se ve.

## HelpTopic (estático)

`ResourceLocation` de una entrada del libro Patchouli `dndsheets:guide`. Cada pantalla devuelve la suya en `helpEntry()`; si no la sobrescribe, se usa la de su categoría. Las entradas nuevas (menú central, ruta, teclas) se añaden a `patchouli_books/guide/en_us/entries/first_steps/` y a la lista espejo de `GuideBook`, tal como exige su comentario.

## Preferencia de cliente

| Clave | Dónde | Defecto | Efecto |
|---|---|---|---|
| `showOnboardingHints` | configuración de cliente | `true` | `false` oculta los globos (`CoachMarks`) y las sugerencias automáticas. No oculta la ayuda manual ("?" / F4), la ruta ni el menú |
