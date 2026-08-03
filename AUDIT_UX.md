# Auditoría UX: experiencia de jugador y de DM

Complementa a `AUDIT.md` (que audita *qué reglas de 5e están implementadas*). Este documento
audita *qué tan fácil es usar y manipular lo que ya existe* — como jugador y como DM — y qué hay
en el código que hoy es invisible o inalcanzable sin comandos de memoria. Verificado leyendo el
código real (GUIs, comandos, managers, loaders), no es una lista de deseos.

Formato por hallazgo: problema → archivo → escenario concreto → dirección de arreglo. Ordenado
por impacto dentro de cada sección: **fricción alta** (bloquea o confunde de entrada), **fricción
media** (molesta pero se aprende), **pulido** (detalle).

## Estado (2026-08-01): qué de esto ya se arregló

Pasada de implementación sobre esta misma auditoría. Resueltos: Jugador #1 (ítem de recurso de
clase entregado al aplicar preset), Jugador #2 (Grimorio ya no lanza al primer clic — selecciona y
pide confirmar), Jugador #10 (TestGUIScreen/TestGUIMenu/TestGUIButtonMessage/TestProcedure,
código muerto, borrados); DM #1 (PG/CA reales añadidos a `SheetAdjustScreen`); Autoría #1 (README
apunta a `test/dndsheets`, ya no a `/patch`, y documenta `$str`.../`$prof`/`$hprof` y el bug de
dados múltiples), Autoría #2 y #3 (WeaponCommand ya loguea entradas malformadas con índice;
Monster/Spell/Preset/Trait ahora también avisan cuando falta "id", antes se saltaba en silencio
total); Transversal #1 (`CombatFx.activate()` da sonido+partícula a los 9 recursos de clase que
antes eran mudos), #2 (lore en la Vara de DM), #3 (`ChatFeedback.RESOURCE` centraliza el color de
activación de recurso, ya no cada manager el suyo), #4 (`CombatFx.hit(target, critical)` da
partículas/sonido distintos a un crítico), #5 (`/dndsheet advantage` ahora rechaza un estado no
reconocido en vez de caer en "normal" en silencio; `/dndsheet setslots` ya no expone el error en
inglés de Brigadier, delega en el mismo clamp que ya usaba el Panel de DM).

Segunda pasada (2026-08-02): DM #2 (`DmPanelScreen` → "Aplicar preset a jugador" → `PlayerPickerScreen`
→ `PresetScreen` reutilizando el mismo viaje ida-vuelta cliente/servidor que ya tenía conceder rasgo),
DM #3 (pacto del brujo y nivel de personaje ahora son botón cíclico/campo + "Aplicar" en
`SheetAdjustScreen`, en vez de solo `/dndsheet pact`/`setlevel` tecleados), DM #4 (`TurnControlScreen` →
"Aplicar efecto" → `PlayerPickerScreen` → `AddTurnEffectScreen`, dado por botón cíclico igual que
`AddMonsterAttackScreen`).

Lo demás (catálogo de contenido navegable, scroll del Grimorio, lista de votantes de descanso) sigue
pendiente: son pantallas nuevas de tamaño no trivial, no correcciones de una función — quedan para
una pasada aparte si se quiere seguir.

---

## 1. Jugador

### Fricción alta

1. **Los recursos de clase no llegan solos al aplicar un preset — contradice el objetivo "sin
   DM" de `AUDIT.md` §0.** `SheetCommand` exige nivel de operador para `rageitem`,
   `secondwinditem`, `wildshapeitem`, `metamagicitem`, `smiteitem`, `huntermarkitem`,
   `shielditem`, `counterspellitem`; `PresetRegistry.applyToSheet` rellena clase/características/
   rasgos pero nunca entrega el ítem que activa el recurso de esa clase. Un jugador que elige
   "Bárbaro" en `PresetScreen` jamás recibe el Tótem de Furia salvo que el DM se acuerde de
   dárselo a mano, por jugador, cada vez. **Arreglo**: entregar el ítem de recurso correspondiente
   dentro de `applyToSheet`, igual que ya se hace con el arma inicial del preset.
2. **`GrimoireScreen` lanza el hechizo al primer clic, sin confirmación ni distinción cantrip
   vs. con coste.** Un jugador que clica para leer qué hace un hechizo gasta un espacio real sin
   poder deshacerlo.
3. **El editor de tiradas es sintaxis cruda, alcanzable por error.** El icono de "editar" está
   pegado al de "tirar" con el mismo tamaño en `CharacterSheetScreen`; `RollEditorScreen` no
   explica $str/$prof más allá del texto del botón, y el bug conocido del motor de dados
   (grupos múltiples de dados) puede arruinar en silencio una expresión hecha a mano.

### Fricción media

4. El color ámbar de "campo automático" está en la etiqueta, no en el número dentro del
   `EditBox` — hay que leer la etiqueta chica, no el dato que se mira de verdad.
5. `/roll`/`/r` no autocompletan ni dan ejemplo en el error ("expresión inválida"), y el
   resultado se difunde a todo el servidor incluso si era solo para probar sintaxis.
6. `GrimoireScreen` no tiene scroll — un lanzador con muchos hechizos aprendidos tendrá botones
   fuera de pantalla (la pestaña de Ataques sí resuelve esto con `RollScrollWidget`).
7. Aplicar un preset no refresca la hoja ya abierta (reconocido en un comentario del propio
   código) — hay que cerrarla y reabrirla para ver el cambio.
8. La votación de descanso no muestra quién falta por votar ni tiene límite de tiempo — un
   jugador AFK bloquea el descanso sin explicación visible.

### Pulido

9. Solo existen dos keybinds (H hoja, P panel DM); abrir el Grimorio son dos pasos pese a ser
   una acción de cada turno de un lanzador.
10. `TestGUIScreen`/`TestGUIMenu` es un resto de plantilla de otro proyecto MCreator, sigue
    registrado con una textura que no existe en este mod. Nada lo abre hoy, pero es código muerto
    con una textura rota latente — candidato a borrar.
11. Las pantallas nuevas (`DeathSaveScreen`, `RestChoiceScreen`, `RestVoteScreen`,
    `GrimoireScreen`, `PresetScreen`) tienen sus títulos/textos en español fijo, mismo patrón que
    `AUDIT.md` ya señaló para el chat de combate pero en una superficie distinta (interfaz, no
    solo chat).

---

## 2. DM

### Fricción alta

1. **No hay forma de ver la hoja de otro jugador (PG/CA) desde ningún GUI.** `SheetAdjustScreen`
   solo muestra oro y espacios de conjuro. En pleno combate, saber si un jugador está a punto de
   caer exige pedirle que abra su propia hoja y la enseñe — no hay comando ni pantalla para
   consultarlo. **Arreglo**: sumar PG/PG máx/CA a `SheetSummaryMessage` y mostrarlos de solo
   lectura en `SheetAdjustScreen`.
2. **Aplicar un preset a OTRO jugador sigue siendo solo por comando.** `PresetScreen` solo se
   abre desde la propia hoja (autoservicio); `DmPanelScreen` no tiene un botón que lleve a
   `/dndpresets apply`. Un jugador nuevo obliga al DM a teclear el comando completo de memoria.
3. **`pact` y `setlevel` (decisiones permanentes de personaje) no tienen ítem ni botón**, a
   diferencia de los ~12 recursos de clase que sí están en la pestaña creativa. Solo existen como
   `/dndsheet pact ...` / `/dndsheet setlevel ...` tecleados a mano.
4. **`/dndturns effect` (aplicar veneno/estado) no tiene GUI**, solo comando con autocompletado
   parcial (nombre y dado, no duración). **Arreglo para 2-4**: extender `SheetAdjustScreen` y
   `TurnControlScreen` con el mismo patrón de botones cíclicos + `PlayerPickerScreen` que ya
   usa `AddMonsterAttackScreen`.

### Fricción media

5. **No existe catálogo/navegador de contenido cargado.** La única forma de ver qué
   monstruos/hechizos/armas/presets/rasgos existen es `/dndX list`, un muro de texto plano sin
   stats ni búsqueda. Confirma la sospecha de `AUDIT.md`.
6. `/dndmonsters spawn <id>` de catálogo real solo es alcanzable por comando o por la carta de
   invocación de la pestaña creativa — `DmPanelScreen` solo cubre el NPC genérico en blanco.
7. El radio de `/dndturns start` desde `TurnControlScreen` está fijo en 30 bloques; otro radio
   sigue exigiendo el comando (ya señalado en `AUDIT.md`, confirmado en código).

### Pulido

8. `AddMonsterAttackScreen`: el dado es texto libre sin validar formato — un error como "1d6d"
   falla en silencio hasta que alguien tira ese ataque, no al confirmarlo.
9. El botón "Saltar (cancelar)" de `TurnControlScreen` puede confundirse con "terminar el modo
   turnos" cuando en realidad es "pasar de turno sin actuar" — solo naming.
10. La tecla **P** del Panel de DM no se anuncia en pantalla; un DM nuevo la descubre solo yendo
    a Opciones > Controles.

---

## 3. Autoría de contenido (JSON)

### Fricción alta

1. **El README promete ejemplos que no están donde dice.** `README.md` remite a `/patch` para
   ejemplos de formato JSON, pero `patch/` contiene un parche Java sin relación (sincronizar
   vida/hambre/nivel), cero JSON de ejemplo. Los ejemplos reales viven en
   `test/dndsheets/{weapons,spells,monsters,presets,traits}/ejemplo.json`, pero esa carpeta está
   sin trackear en git — en un clone limpio no existe. **Arreglo**: commitear `test/` como
   carpeta oficial de ejemplos o corregir la referencia del README.
2. **`WeaponCommand.loadFile` descarta armas malformadas en total silencio** — ni consola ni
   chat — a diferencia de Monster/Spell/Preset/Trait, que al menos imprimen un aviso por ítem
   saltado. Un típo como `"habilidad"` en vez de `"ability"` hace desaparecer un arma del pack sin
   ningún rastro más que un conteo final más bajo de lo esperado.
3. **Cuando falta el campo `id`, el error es un `NullPointerException` crudo** capturado como
   texto, sin decir qué posición del array falló — para un DM no programador es indistinguible de
   un error interno del mod, y no ayuda a encontrar la entrada rota en un archivo con muchas.

### Fricción media

4. La sintaxis de dados (`$str`/`$prof`/`$hprof`, y el bug de dados-múltiples-por-expresión) solo
   está documentada en Javadoc de `DiceManager`, invisible para un DM no programador; el README
   menciona el bug solo como "known bug" de tiradas manuales, sin aclarar que también rompe
   `dice`/`versatileDice`/`appliesEffect.dice` en JSON de contenido.
5. Existen dos sistemas paralelos para armas: `Config.java`/`.toml` (formato
   `"item;dado;característica"`, más visible por estar en la carpeta de config) y `/dndweapons`
   JSON (más completo: `hands`, `versatileDice`, `damageType`). Un DM que tropieza primero con el
   `.toml` topa con un techo de funcionalidad sin saber que existe la vía mejor.
6. Logging inconsistente entre loaders: `DndPaths` usa `LOGGER.warn` correctamente, pero
   Monster/Spell/Preset/Trait usan `System.out.println` sin nivel ni prefijo, dificultando filtrar
   logs de servidor.

### Pulido

7. `datapacks/dndsheets_loot` es un scaffold vacío cuyo README remite a una sección de
   `AUDIT.md` (documento interno) para saber qué poner ahí — no se menciona en el README
   principal.

---

## 4. Transversal (jugador + DM)

### Fricción alta

1. **Activar un rasgo de clase es sonoramente mudo; golpear con la espada no.** Ninguno de los
   ~11 managers de recurso de clase (Furia, Segundo Aliento, Inspiración Bárdica, Forma Salvaje,
   Metamagia, Castigo Divino, Marca del Cazador, Escudo, Contrahechizo, Descanso) llama nunca a
   `CombatFx`; todo el sonido/partícula del mod vive solo en combate/hechizos/muerte. Activar la
   Furia da una línea de chat roja; el siguiente golpe normal con espada da partículas y sonido.
   **Arreglo**: un `CombatFx.activate(Entity)` genérico (partícula+sonido corto) reutilizado por
   cada manager al activar su recurso.
2. **La Vara de DM es el único ítem del mod sin lore explicativo.** Todos los demás ítems
   custom (Tótem de Furia, Escudo, Contrahechizo, Cuerno de Inspiración, Kit de Descanso...)
   tienen "Clic derecho: ..." en gris; la Vara de DM solo tiene nombre. Un DM nuevo no tiene forma
   in-game de saber que abre un menú o que agachado+clic borra al monstruo.

### Fricción media

3. **Los colores de chat de los rasgos de clase son arbitrarios**, no pasan por la paleta
   centralizada de `ChatFeedback` (verde=impacto, rojo=daño...): Furia sale en rojo — el mismo
   rojo que ya significa "daño recibido" en el resto del mod — sin relación real con esa paleta.
4. **Ningún crítico tiene feedback sensorial distinto.** `CombatFx.hit()` usa las mismas
   partículas y sonido tanto si el golpe fue un 20 natural como un roce normal; el crítico solo se
   nota en el número de daño.
5. **`/dndsheet advantage` reporta éxito aunque el valor no exista.** Una errata como "vantaje"
   cae en NORMAL en silencio y el comando igual confirma "aplicado" — el DM cree que surtió efecto
   y no.
6. Los errores de rango de Brigadier (p.ej. `/dndsheet setslots` fuera de 0-99) salen en inglés
   genérico ("Integer must not be less than...") en medio de un mod que, para el resto de errores
   escritos a mano, sí es español y accionable.

### Pulido

7. El patrón `Component.literal` en vez de `Component.translatable` (que `AUDIT.md` ya señaló
   para el log de combate) se confirma también en el 100% de títulos de pantalla, tooltips de
   ítems y mensajes de comando revisados — bloquea localización de toda la interfaz, no solo del
   combate.
8. **Positivo, sin fricción**: todas las pantallas tienen título propio; las teclas H/P no
   chocan entre sí ni con binds vanilla; ningún estado crítico (HUD de turnos, muerte) depende
   solo del color, todos llevan texto.

---

## 5. Qué priorizar primero

Los cinco cambios de mayor impacto por esfuerzo, cruzando las cuatro secciones:

1. **Entregar el ítem de recurso de clase al aplicar un preset** (Jugador #1) — sin esto, media
   docena de mecánicas ya construidas (Furia, Segundo Aliento, etc.) son invisibles para un
   jugador que no sepa que existen.
2. **Sumar PG/CA al panel de ajuste de hoja del DM** (DM #1) — la pieza que más se echa en falta
   en pleno combate.
3. **Loguear armas malformadas por nombre/índice** (Autoría #2) — mismo estándar que ya tienen
   los otros cuatro loaders, una línea de código.
4. **Confirmar-antes-de-lanzar en el Grimorio** (Jugador #2) — evita perder espacios de conjuro
   por curiosidad.
5. **Lore en la Vara de DM** (Transversal #2) — el único ítem sin autoexplicación, arreglo de una
   línea.

El resto son extensiones del mismo patrón ya usado en el código (más pantallas con
`PlayerPickerScreen` + botones cíclicos, más colores centralizados en `ChatFeedback`, más
`LOGGER.warn` en vez de `System.out`) — no hace falta inventar mecanismos nuevos, solo aplicar
los que ya existen de forma más pareja.
