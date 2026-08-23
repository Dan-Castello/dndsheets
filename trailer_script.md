# dndsheets — Guion de trailer de lanzamiento (60-75s)

Mod de Forge 1.20.1 que convierte Minecraft en una VTT de D&D 5e: ficha de personaje,
combate 5e opt-in sobre el combate vanilla, mazmorras generadas y panel de DM.

## Estructura (7 tomas)

**1. Gancho (0:00-0:06)**
- Pantalla en negro → texto: "¿Y si Minecraft fuera tu mesa de D&D?"
- Corte a: jugador abre la ficha de personaje con una tecla (CharacterSheetScreen).
- VO: "Minecraft, ahora con reglas de D&D 5e de verdad."

**2. Ficha de personaje (0:06-0:18)**
- Recorrido rápido por CharacterSheetScreen: stats, HP, dados de golpe, inventario de clase.
- Subir de nivel en vivo (AbilityImprovementScreen / SubclassScreen).
- Texto en pantalla: "Ficha completa. Un botón."

**3. Combate real (0:18-0:32)**
- Pelea contra un mob: tirada de ataque con ventaja/desventaja (RollEditorScreen), daño con
  resistencias, casteo de un hechizo (GrimoireScreen) con coste de espacio.
- Iniciativa y turnos (TurnControlScreen / TurnActionScreen).
- VO: "Ataques, salvaciones, hechizos, iniciativa — 5e de verdad, encima del combate que ya conoces."
- Texto: "Compatible con cualquier mob o arma sin configurar."

**4. Herramientas de DM (0:32-0:48)**
- DmPanelScreen: spawnear un monstruo con stat block real (MonsterSpawnListScreen).
- Generar una mazmorra al vuelo (DungeonGenerateScreen) — corte rápido mostrando el resultado.
- Diario de sesión (JournalScreen) y encuentro con varios jugadores (PartyScreen).
- VO: "Para el DM: monstruos con stat block real, mazmorras generadas, diario de sesión."

**5. Contenido propio (0:48-0:56)**
- ContentFormScreen: crear un hechizo o un objeto mágico nuevo desde el propio juego, sin salir.
- Texto: "Crea tu propio contenido. Sin editar JSON a mano."

**6. Multijugador (0:56-1:04)**
- Dos-tres jugadores en la misma partida, cada uno con su ficha, votando un descanso (RestVoteScreen).
- VO: "Una mesa de rol, dentro de tu servidor."

**7. Cierre (1:04-1:15)**
- Logo del mod + nombre "dndsheets".
- Texto: "Forge 1.20.1 · Cliente y servidor · Descárgalo ahora."
- Link/plataforma de descarga en pantalla.

## Notas de grabación
- Graba en runClient con un mundo ya preparado (personaje de nivel 3+, inventario con un par de
  hechizos/objetos, un segundo jugador o bot para las tomas de grupo).
- Usa `/dnd monster spawn` y `/dnd dungeon generate` antes de grabar para tener el resultado listo,
  luego repite en vivo para la toma real.
- Música: algo tipo fantasía/aventura, sin voz, para no chocar con la VO.
- Exporta en 1080p60; recorta cada toma a 1-2s más de lo que se ve arriba y ajusta en edición.

→ Guion listo para grabar. Falta: grabar el material (no puedo operar tu Minecraft/OBS desde aquí).
