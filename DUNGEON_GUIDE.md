# Guía del DM — Piezas de mazmorra y generación jigsaw

Cómo construir habitaciones reutilizables y generar dungeons a partir de ellas, sin editar datapacks a mano. El sistema se apoya 100% en el jigsaw/structure block vanilla de Minecraft — el mod solo añade una capa de conveniencia encima (captura sin retipear, configuración de jigsaw sin su GUI, copiar/pegar conexiones, publicar y generar desde una GUI).

Ver también `GUI_REFERENCE.md` (referencia técnica de cada pantalla) si estás modificando el código en vez de usándolo.

## 0. Antes de empezar

- Necesitás permiso de operador.
- Conseguí la **Vara de DM**: `/dndmonsters dmtool <tu nombre>`. Es la misma herramienta que ya usás para controlar monstruos — acá además sirve para capturar piezas y configurar jigsaws.

## 1. Construir y guardar una habitación

1. Construí la sala con bloques normales, como cualquier construcción.
2. Colocá un **bloque de estructura** (`Structure Block`) cerca, en modo **SAVE**.
3. Nombralo con un id único, formato `espacioDeNombres:ruta` — por ejemplo `dndsheets_dm:rooms/entrance`. Podés usar cualquier espacio de nombres propio, no hace falta que sea `dndsheets`.
4. Ajustá el tamaño (botón **Detect** si la sala está delimitada por aire/bloques, o a mano) para que cubra toda la habitación.
5. **Save**. Esto escribe el `.nbt` en `<mundo>/generated/<espacioDeNombres>/structures/<ruta>.nbt` — normal de Minecraft, nada del mod todavía.

## 2. Marcar las conexiones (jigsaw blocks)

Colocá un **bloque jigsaw** en cada punto donde la sala debería conectar con otra pieza (una puerta, el final de un pasillo, etc.), mirando hacia afuera de la sala.

En vez de abrir la GUI vanilla del jigsaw y tipear `Name`/`Target`/`Pool` a mano:

1. Con la Vara de DM equipada, **clic derecho** sobre el jigsaw block.
2. Se abre un formulario corto:
   - **Pool destino**: a qué pool de piezas debería tirar Minecraft cuando genere algo a partir de esta salida (ej. `corridor`).
   - **Pieza de inicio**: marcá **Sí** únicamente en el/los jigsaw(s) por los que la mazmorra debería *empezar* a generarse (normalmente uno solo, en tu pieza de entrada).
3. **Confirmar.** El mod escribe `Name`, `Target`, `Pool` y `Joint` directo en el bloque — no hace falta la GUI vanilla ni recordar el espacio de nombres `dndsheets:`.

### Varias salidas al mismo pool (copiar/pegar)

Si una pieza tiene varias salidas que deberían tirar todas al mismo pool (ej. 3 puertas de una sala grande, todas hacia `corridor`):

1. Configurá una normalmente (paso anterior).
2. **Agachado + clic derecho** sobre ESE mismo jigsaw con la Vara de DM → lo copia a tu portapapeles (mensaje de confirmación en el chat).
3. Clic derecho (sin agacharte) en cada uno de los otros jigsaws → el formulario se abre **prellenado** con lo copiado. Solo hace falta **Confirmar** en cada uno — nunca escribe solo, así que podés ajustar antes de confirmar si hace falta.

El portapapeles es por DM y no se borra solo — seguís pudiendo pegarlo en piezas nuevas hasta que copies otra cosa.

## 3. Capturar la pieza en el mod

1. Con la Vara de DM, **clic derecho sobre el bloque de estructura** (no el jigsaw) que ya guardaste en el paso 1.
2. Se abre "Añadir pieza" **prellenado** con el id de la estructura y una sugerencia de nombre (el último segmento de la ruta — `rooms/entrance` → `entrance`).
3. Completá:
   - **Pool**: a qué pool pertenece ESTA pieza (para que otros jigsaws puedan tirar de ella con su campo "Pool destino" del paso 2).
   - **Peso** (1-150): probabilidad relativa frente a otras piezas del mismo pool. `1` para la mayoría, más alto si querés que aparezca más seguido.
   - **Tags**: libre, no se usa todavía para filtrar generación — solo para tu propia organización.
4. **Confirmar.** Esto copia el `.nbt` al datapack de la partida actual y registra la pieza. Si el bloque de estructura todavía no tiene nombre, o el `.nbt` no se guardó, te avisa en vez de fallar en silencio.

Repetí los pasos 1-3 para cada habitación que quieras tener disponible.

## 4. Generar la mazmorra

**Panel de DM (P) → Mazmorras → Generar mazmorra:**
- **Pool**: el pool de tu pieza de *entrada* (la que tiene el jigsaw marcado "Pieza de inicio: Sí").
- **Profundidad máx.** (1-7): cuántos pasos de piezas puede encadenar como máximo antes de parar.
- **X/Y/Z**: dónde empezar a generar (prellenado con tu posición actual).

Al confirmar, el mod publica todos los pools (escribe los JSON del datapack + corre `/reload`) y dispara la generación. Vas a ver `¡Recargando!` seguido de un resultado: `Mazmorra generada en ...` o un mensaje de error concreto.

**Equivalente por comando** (útil para depurar o automatizar): `/dnddungeon generate <pool> <maxDepth> <x> <y> <z>`.

## 5. Gestionar piezas ya capturadas

- **Panel de DM → Mazmorras** lista todas las piezas; tocar una abre "Editar pieza" (pool/peso/tags — no el id ni la estructura, esos son fijos desde la captura).
- **Borrar una pieza** solo por comando: `/dnddungeon piece remove <id>` (acción rara de operador, no tiene botón en la GUI a propósito).
- `/dnddungeon piece list` / `/dnddungeon publish` también están disponibles si preferís la terminal.

## Solución de problemas

| Síntoma | Causa probable |
|---|---|
| "No encontré una estructura escaneada como..." al capturar | El id que escribiste no coincide *exactamente* con el que le pusiste al bloque de estructura, o todavía no le diste Save. |
| "Este jigsaw todavía no está configurado — nada que copiar" | Intentaste copiar (agachado + clic) un jigsaw que nunca configuraste con la Vara de DM (ni con la GUI vanilla usando nuestro namespace). |
| `No starting jigsaw dndsheets:dungeon_start found in start pool ...` (log del servidor) + "La generación falló" en el chat | El **pool** que pusiste en "Generar mazmorra" no es el pool de tu pieza de entrada, o ningún jigsaw de esa pieza tiene "Pieza de inicio: Sí". |
| "No encontré el pool \"X\" tras publicar" | Ninguna pieza capturada tiene ese pool exacto (revisá mayúsculas/espacios) — publicar solo escribe pools que tengan al menos una pieza. |
| La mazmorra generó menos de lo esperado / se cortó pronto | Subí "Profundidad máx.", o revisá que los jigsaws de tus piezas intermedias (no solo la de entrada) también tengan Pool configurado — un jigsaw sin pool propio no sigue expandiendo. |

## Referencia rápida — convención de nombres

- Todo pool vive en el espacio de nombres del mod: al escribir `pool: X` en cualquier formulario, se guarda como `dndsheets:X` — no hace falta (ni corresponde) escribir el prefijo vos mismo.
- El único jigsaw `Name` reservado es `dndsheets:dungeon_start` (piezas de arranque). El resto usa `dndsheets:connector` para tanto `Name` como `Target` — por eso cualquier par de jigsaws "normales" siempre puede conectar entre sí.
- El `Joint` queda fijo en `ALIGNED` (sin rotación libre) — pensado para salas armadas a mano con una abertura fija. Si necesitás piezas que roten libremente, configuralas con la GUI vanilla del jigsaw en vez de la Vara de DM.
