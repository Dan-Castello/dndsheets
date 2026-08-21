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

El clic derecho del paso 1 también **re-guarda el bloque de estructura en ese mismo instante**, así que no importa si configuraste los jigsaws (paso 2) antes o después del último "Save" manual — la foto que se captura siempre es la más reciente, jigsaws incluidos.

Repetí los pasos 1-3 para cada habitación que quieras tener disponible.

## 4. Generar la mazmorra

**Regla de oro, léela antes de generar: el pool de entrada debe contener SOLO la(s) pieza(s) que tienen el jigsaw de inicio — nunca mezcles ahí piezas normales.** Minecraft, al arrancar la generación, elige **una pieza al azar (pesada por "Peso") de todo el pool** y busca el jigsaw de inicio *solo dentro de esa*. Si el pool tiene la pieza de entrada mezclada con piezas normales, cada generación tiene una probabilidad real de que le toque una pieza sin ese jigsaw — y falla. No es intermitencia rara ni un problema de tu configuración: es tirar una moneda cada vez que generás. Dale a tu pieza de entrada un pool **propio y dedicado** (llamalo `entrada` o `start`, y no lo uses para nada más) al capturarla en el paso 3.

`DungeonPieceListScreen` (Panel de DM → Mazmorras) marca `[inicio]` junto a cada pieza que tiene el jigsaw — mirá esa lista antes de generar: si un pool tiene piezas con y sin `[inicio]` mezcladas, separalas primero. `Generar mazmorra` también valida esto solo y te avisa con el motivo exacto en vez de dejar que falle en silencio.

**Panel de DM (P) → Mazmorras → Generar mazmorra:**
- **Pool**: el pool *dedicado* de tu pieza de entrada (ver regla de arriba) — no el pool al que sus jigsaws tiran hacia afuera, ese es otro campo del paso 2.
- **Profundidad máx.** (1-7): cuántos pasos de piezas puede encadenar como máximo antes de parar.
- **X/Y/Z**: dónde empezar a generar (prellenado con tu posición actual).

Al confirmar, el mod valida el pool (ver arriba), publica todos los pools (escribe los JSON del datapack) y corre `/reload`. Vas a ver `¡Recargando!` seguido de un resultado: `Mazmorra generada en ...` o un mensaje de error concreto.

**Importante — la primera vez que generás con un pool nuevo (o después de editar/agregar piezas a uno existente), va a fallar la primera vez.** No es un error de configuración: Minecraft solo lee los pools de estructura (`template_pool`) al cargar el mundo, y `/reload` no los toca — recarga recetas, loot tables, tags, funciones y logros nada más. El datapack queda escrito bien en disco, pero el pool sigue "invisible" para la generación hasta que el mundo se recarga de verdad. El mensaje de error te lo dice: **salí al menú principal y volvé a entrar al mundo** (en un server dedicado, reinicialo) y generá de nuevo — ahí sí va a encontrar el pool. Esto solo hace falta una vez por pool nuevo/editado, no en cada generación.

**Equivalente por comando** (útil para depurar o automatizar): `/dnddungeon generate <pool> <maxDepth> <x> <y> <z>`.

## 4b. Traer una construcción de fuera (importar)

No hace falta construir cada sala: cualquier estructura `.nbt` de Minecraft sirve. Es el formato al que
exportan Litematica ("Export to vanilla structure"), los editores de mapas y los propios bloques de
estructura, así que una casa descargada entra sin conversor de por medio.

1. Copiá el `.nbt` en `<carpeta del juego>/dndsheets/structures/`. Esa carpeta es **compartida entre
   partidas** a propósito: las piezas son de un mundo, pero una casa descargada sirve en todos.
2. `/dnddungeon import "<nombre del archivo>"` (sin el `.nbt`; con comillas si tiene espacios) la pega
   donde estás parado y te dice el tamaño y **cuántos jigsaw trae**.
3. Si trae 0 jigsaws —lo normal en algo exportado de un editor— seguí desde el paso 2 de esta guía:
   ponele los jigsaw con la Vara de DM y capturala con un bloque de estructura como cualquier sala tuya.
4. Si el `.nbt` **ya trae jigsaws** (una estructura de vanilla, o de un pack de mazmorras),
   `/dnddungeon import "<archivo>" pool <pool> [peso]` la registra directamente como pieza, sin pegarla ni
   volver a escanearla.

El nombre del archivo se convierte solo en un id válido (`Casa Grande (v2).nbt` → `casa_grande_v2`), y
todo lo importado vive en el espacio de nombres `dndsheets_import:` para que se distinga de un vistazo de
lo que escaneaste vos.

## 5. Gestionar piezas ya capturadas

- **Panel de DM → Mazmorras** lista todas las piezas; tocar una abre "Editar pieza" (pool/peso/tags — no el id ni la estructura, esos son fijos desde la captura), con un botón "Borrar pieza" ahí mismo.
- `/dnddungeon piece remove <id>` hace lo mismo por comando, si preferís la terminal — igual que `/dnddungeon piece list` / `/dnddungeon publish`.

## Solución de problemas

| Síntoma | Causa probable |
|---|---|
| "No encontré una estructura escaneada como..." al capturar | El id que escribiste no coincide *exactamente* con el que le pusiste al bloque de estructura, o todavía no le diste Save. |
| "Este jigsaw todavía no está configurado — nada que copiar" | Intentaste copiar (agachado + clic) un jigsaw que nunca configuraste con la Vara de DM (ni con la GUI vanilla usando nuestro namespace). |
| "Ninguna pieza en el pool ... tiene el jigsaw de inicio" | Ninguna pieza de ese pool tiene un jigsaw `Name=dndsheets:dungeon_start` en su `.nbt` capturado. O nunca marcaste "Pieza de inicio: Sí" en ningún jigsaw, o lo marcaste pero nunca volviste a capturar la pieza (clic con la Vara en el bloque de estructura) después — `DungeonPieceListScreen` marca `[inicio]` en las piezas que sí lo tienen, revisá ahí antes de generar. |
| "El pool ... mezcla tu pieza de entrada con N pieza(s) sin jigsaw de inicio" | Exactamente la regla de oro de "4. Generar la mazmorra": tenés piezas normales compartiendo pool con tu pieza de entrada. Movelas a otro pool (editalas desde `DungeonPieceListScreen` → tocar la pieza → cambiar Pool), o recapturá la de entrada con un pool nuevo y dedicado. |
| `No starting jigsaw dndsheets:dungeon_start found in start pool ...` (log del servidor) pese a que la validación de arriba no avisó nada | Recargaste el mundo *después* de la última captura/publicación pero *antes* de que el `.nbt` reflejara el jigsaw — volvé a capturar la pieza (clic con la Vara en el bloque de estructura, ahora re-guarda solo antes de copiar) y generá de nuevo. |
| "Pool \"X\" publicado pero todavía no cargado" | Esperado la primera vez que usás un pool nuevo (o justo después de editarlo/agregarle piezas) — ver el aviso de arriba en "4. Generar la mazmorra". Salí al menú principal, volvé a entrar al mundo, y generá de nuevo. Si el mensaje sigue apareciendo después de recargar el mundo, ahí sí revisá que alguna pieza tenga exactamente ese pool (mayúsculas/espacios). |
| "no es una estructura de Minecraft válida" al importar | El archivo es un `.schem` (WorldEdit) o un `.litematic` (Litematica), que no son el formato de vanilla. Abrilo en Litematica y usá "Export to vanilla structure" — el mod no trae conversor porque el botón de exportar de ellos llega al mismo sitio. |
| "Esa estructura no tiene ningún jigsaw" al importar con `pool` | Lo exportado de un editor no trae jigsaws. Pegala con `/dnddungeon import "<archivo>"` sin pool, ponele los jigsaw con la Vara y capturala normal. |
| La mazmorra generó menos de lo esperado / se cortó pronto | Subí "Profundidad máx.", o revisá que los jigsaws de tus piezas intermedias (no solo la de entrada) también tengan Pool configurado — un jigsaw sin pool propio no sigue expandiendo. |

## Referencia rápida — convención de nombres

- Todo pool vive en el espacio de nombres del mod: al escribir `pool: X` en cualquier formulario, se guarda como `dndsheets:X` — no hace falta (ni corresponde) escribir el prefijo vos mismo.
- El único jigsaw `Name` reservado es `dndsheets:dungeon_start` (piezas de arranque). El resto usa `dndsheets:connector` para tanto `Name` como `Target` — por eso cualquier par de jigsaws "normales" siempre puede conectar entre sí.
- El `Joint` queda fijo en `ALIGNED` (sin rotación libre) — pensado para salas armadas a mano con una abertura fija. Si necesitás piezas que roten libremente, configuralas con la GUI vanilla del jigsaw en vez de la Vara de DM.
