# Feature Specification: Incorporación guiada al VTT (onboarding)

**Feature Branch**: `001-onboarding-vtt`

**Created**: 2026-11-06

**Status**: Draft

**Input**: User description: "Quiero que los jugadores nuevos puedan empezar a usar todas las funciones, desde la mas sencilla como el tiro de dados, como la importación de dungeons, dibujo de salas, etc, reducir la curva de ingreso a este proyecto para ser un VTT agradable y comodo de usar tanto en lo visual como en gameplay"

## Contexto

El mod ya convierte Minecraft en una mesa virtual (VTT) de D&D 5e completa: ficha de personaje, tiradas, combate por turnos, magia, herramientas del DM, importación de mapas de dungeon y editor de salas. Ya existen una guía en forma de libro, un aviso de esquina al entrar por primera vez, una pantalla de bienvenida (una vez por personaje), una lista de progreso para crear el personaje y un panel del DM dividido en secciones. El problema no es lo que falta, sino lo que no se descubre: el usuario nuevo no sabe qué puede hacer, qué tecla lo abre ni cuál es el siguiente paso. Esta funcionalidad cubre la **guía y el descubrimiento**, no añade mecánicas de juego.

## Clarifications

### Session 2026-11-06

- Q: ¿Cómo debería llegar el usuario a todas las funciones del mod desde un solo lugar? → A: Un menú central (la tecla P) que reúne todas las funciones del rol, agrupadas por tarea, con los atajos actuales como accesos directos.
- Q: Cuando el usuario entra en varias pantallas una dentro de otra, ¿cómo debe saber dónde está y volver atrás? → A: Esc vuelve a la pantalla anterior, y bajo el título se muestra la ruta ("Menú › Panel de DM › Mazmorras") con cada tramo pulsable para volver a esa pantalla.
- Q: ¿Hasta dónde se debe poder usar la interfaz solo con el teclado? → A: Flechas y Enter para recorrer y elegir en el menú central y en todas las listas, con indicador claro de la opción seleccionada; los editores de rejilla y la ficha siguen con ratón.
- Q: ¿Qué pantallas debe cubrir el repaso de diseño visual? → A: Todas, incluida la ficha de personaje, que se adapta (y se rehace donde haga falta) para seguir las mismas reglas que el resto.
- Q: ¿Cómo debe pedir ayuda el usuario desde cualquier pantalla del mod? → A: Las dos vías: un botón "?" siempre en la misma esquina y una tecla reasignable, que abren la misma ayuda.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Primera tirada en el primer minuto (Priority: P1)

Un jugador que nunca usó el mod entra al mundo. En lugar de no saber por dónde empezar, recibe una bienvenida breve que le dice qué es esto, cuál es su rol (jugador o DM) y le propone una única primera acción: tirar un dado. La hace con una tecla o un clic, ve el resultado de forma clara y sabe dónde seguir.

**Why this priority**: Es la puerta de entrada. Si el primer contacto es confuso, el resto de funciones no llega a usarse. La tirada de dados es la acción más simple y da una victoria inmediata.

**Independent Test**: Con un perfil de jugador nuevo, entrar a un mundo con el mod y comprobar que, sin abrir ninguna documentación, completa una tirada de dados y ve su resultado.

**Acceptance Scenarios**:

1. **Given** un jugador que entra por primera vez a un mundo con el mod, **When** termina de cargar, **Then** ve una bienvenida que nombra su rol y propone un único primer paso (tirar un dado), con la tecla o el botón para hacerlo.
2. **Given** la bienvenida abierta, **When** el jugador sigue el primer paso, **Then** la tirada se resuelve, el resultado se muestra legible (dados, modificador y total) y se le sugiere el siguiente paso.
3. **Given** un jugador que ya completó o descartó la bienvenida, **When** vuelve a entrar al mundo, **Then** no se le vuelve a mostrar, pero puede reabrirla desde la guía o el menú central.
4. **Given** la bienvenida abierta, **When** el jugador la cierra sin hacer nada, **Then** no vuelve a interrumpirle y queda un acceso visible para retomarla.

---

### User Story 2 - Ruta de primeros pasos por rol con progreso (Priority: P1)

Tanto el jugador como el DM tienen una ruta de primeros pasos para su rol: una lista corta y ordenada de hitos (jugador: tirar dados, crear personaje, abrir la ficha, hacer un ataque, lanzar un conjuro; DM: abrir el panel, invocar un monstruo, iniciar un combate, importar un dungeon, dibujar una sala). Cada hito se marca solo cuando el usuario realmente lo hace, y cada uno lleva directamente a la función.

**Why this priority**: Convierte "todas las funciones" en un camino recorrible. Reutiliza el patrón de lista con progreso que ya existe para el personaje y lo extiende a todo el mod.

**Independent Test**: Seguir la ruta de un rol de principio a fin solo con lo que muestra la ruta, comprobando que cada hito se marca al completar la acción real y que cada uno abre la función correspondiente.

**Acceptance Scenarios**:

1. **Given** un jugador, **When** abre su ruta de primeros pasos, **Then** ve los hitos de jugador en orden, con su estado (hecho / pendiente) y una acción para ir a cada uno.
2. **Given** un DM, **When** abre su ruta, **Then** ve los hitos de jugador y además los de DM, agrupados por separado.
3. **Given** un hito pendiente, **When** el usuario realiza esa acción por cualquier vía (la ruta, una tecla o un menú), **Then** el hito se marca como hecho y el cambio persiste entre sesiones.
4. **Given** todos los hitos de su rol completos, **When** abre la ruta, **Then** ve que la terminó y la ruta deja de sugerirse sola.

---

### User Story 3 - Primer dungeon importado y primera sala dibujada sin documentación (Priority: P2)

Un DM nuevo quiere usar un mapa hecho en otra herramienta, o dibujar una sala propia. Las pantallas de importar mapa y de dibujar salas le explican, en el momento, qué formatos acepta, qué hace cada herramienta y qué pasa después (vista previa, pegar en el mundo, deshacer). Un primer uso guiado le acompaña paso a paso la primera vez.

**Why this priority**: Son las funciones más potentes y las de curva más empinada. Importan mucho, pero solo al DM y después de lo básico.

**Independent Test**: Con un DM que nunca usó estas pantallas, pedirle que importe un mapa de ejemplo y que dibuje y coloque una sala simple; medir si lo consigue sin ayuda externa y sin perder bloques del mundo.

**Acceptance Scenarios**:

1. **Given** la pantalla de importar mapa por primera vez, **When** se abre, **Then** indica qué formatos acepta, cómo conseguir un archivo de ejemplo y qué ocurrirá al importarlo.
2. **Given** un archivo no válido o de un formato no soportado, **When** se intenta importar, **Then** el mensaje dice qué falló y qué hacer a continuación.
3. **Given** el editor de salas por primera vez, **When** se abre, **Then** un recorrido breve presenta las herramientas principales (dibujar, borrar, puertas, niveles, vista previa) y puede saltarse.
4. **Given** una sala dibujada, **When** el DM la pega en el mundo, **Then** se le indica que puede deshacerlo y cómo, antes de y después de pegar.

---

### User Story 4 - Ayuda contextual y descubrimiento de controles (Priority: P2)

En cualquier pantalla del mod, el usuario puede pedir ayuda sobre esa pantalla y saber qué teclas existen. Los botones con solo un icono explican qué hacen, las listas vacías dicen por qué están vacías y cuál es el siguiente paso, y hay una referencia rápida de todas las teclas del mod.

**Why this priority**: Reduce la fricción continua tras el primer día. Se apoya en reglas que el proyecto ya tiene (tooltips, mensajes de lista vacía) y las completa.

**Independent Test**: Recorrer todas las pantallas del mod y comprobar que cada una ofrece ayuda contextual que lleva a la sección correcta de la guía, y que la referencia de teclas lista todos los atajos del mod con su tecla actual.

**Acceptance Scenarios**:

1. **Given** cualquier pantalla del mod, **When** el usuario pulsa el botón "?" o la tecla de ayuda, **Then** se abre la explicación de esa pantalla concreta (no el índice general) y al cerrarla vuelve a donde estaba.
2. **Given** el usuario reasignó una tecla, **When** consulta la referencia rápida o un consejo, **Then** se muestra la tecla actual, no la predeterminada.
3. **Given** una lista vacía (sin personajes, sin monstruos, sin piezas de dungeon), **When** se muestra, **Then** explica el motivo y ofrece la acción para llenarla.

---

### User Story 5 - Comodidad visual y de juego (Priority: P3)

Todas las pantallas del mod, incluida la ficha de personaje, se leen bien y se comportan igual: mismo estilo, textos que no se cortan sin aviso, controles que muestran su estado y confirmación clara de cada acción. En partida, la información que importa (turno, vida, resultado de tiradas) está a la vista sin abrir menús.

**Why this priority**: Pule la experiencia una vez que el usuario ya sabe moverse. Mucha base ya existe (estilo compartido, guía de diseño); aquí se cierran las incoherencias que más molestan.

**Independent Test**: Revisar todas las pantallas del mod a dos escalas de interfaz y con textos largos en español, y una sesión de combate corta, comprobando contra los criterios de la guía de diseño del proyecto.

**Acceptance Scenarios**:

1. **Given** cualquier pantalla del mod, incluida la ficha, a escala de interfaz 2 y 3 y con textos en español, **When** se muestra, **Then** ningún texto se corta sin indicarlo y sin poder leerse completo.
2. **Given** un combate en curso, **When** es el turno de un jugador, **Then** este ve sin abrir menús que es su turno, su vida y el resultado de su última tirada.
3. **Given** cualquier acción que cambie el estado del juego, **When** se ejecuta, **Then** produce una confirmación visible; ninguna acción es silenciosa.

---

### Edge Cases

- Un usuario que ya usaba el mod antes de esta funcionalidad no debe recibir la bienvenida como si fuera nuevo: los hitos que ya cumplió por su uso real deberían figurar como hechos, o al menos la bienvenida debe poder descartarse de una vez.
- Un jugador al que el DM asciende a DM durante la partida (o al revés): la ruta debe reflejar el rol actual sin perder el progreso anterior.
- En un servidor con varios jugadores, la bienvenida y la ruta son de cada persona; nada interrumpe a los demás.
- Si la guía en libro extendida (manual con índice) no estuviera disponible (hoy es obligatoria, pero se defiende igual), la ayuda contextual debe seguir funcionando con la guía básica.
- Un consejo o recorrido que tocaría mostrarse durante un combate no se muestra y queda pendiente para la próxima vez.
- Un hito que depende de algo que el servidor no permite (por ejemplo, el jugador no tiene permiso para invocar monstruos): no debe mostrarse como pendiente imposible.
- Entrar en mitad de un combate ya iniciado: la bienvenida no debe tapar la interfaz de combate; se pospone hasta un momento tranquilo.
- Una pantalla que el servidor vuelve a abrir tras guardar o borrar debe conservar su ruta de navegación, no reiniciarla ni duplicar el tramo actual.
- Idioma: todos los textos nuevos existen en todos los idiomas que el mod ya soporta.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El sistema MUST mostrar a cada usuario, la primera vez que entra a un mundo con el mod, una bienvenida breve que identifique su rol (jugador o DM) y proponga un único primer paso.
- **FR-002**: La bienvenida MUST poder cerrarse, MUST NOT volver a aparecer sola tras completarse o cerrarse, y MUST poder reabrirse desde la guía o el menú central.
- **FR-003**: El sistema MUST ofrecer una ruta de primeros pasos por rol: hitos de jugador para todos, y además hitos de DM para quien tenga ese rol.
- **FR-004**: Cada hito MUST marcarse como hecho al realizar la acción real, por cualquier vía, no solo desde la ruta.
- **FR-005**: El progreso de la bienvenida y de la ruta MUST conservarse por usuario entre sesiones y reinicios del servidor.
- **FR-006**: Cada hito de la ruta MUST ofrecer una acción que abra directamente la función correspondiente o explique cómo llegar a ella.
- **FR-007**: Los hitos MUST ocultarse o explicarse cuando el usuario no tiene permiso para realizarlos.
- **FR-008**: La primera vez que un DM abre la pantalla de importar mapa, el sistema MUST indicar los formatos aceptados, cómo obtener un mapa de ejemplo y qué ocurrirá al importar.
- **FR-009**: La primera vez que un DM abre el editor de salas, el sistema MUST ofrecer un recorrido breve y omitible por sus herramientas principales.
- **FR-010**: Antes y después de pegar una sala o dungeon en el mundo, el sistema MUST indicar que la acción se puede deshacer y cómo.
- **FR-011**: Cada pantalla del mod MUST ofrecer ayuda por dos vías equivalentes: un botón "?" siempre en la misma esquina de la pantalla y una tecla de ayuda reasignable; ambas abren la explicación de esa pantalla en la guía, y al cerrarla se vuelve a la pantalla de origen.
- **FR-012**: El sistema MUST ofrecer una referencia rápida de todas las teclas del mod que muestre la tecla asignada en ese momento.
- **FR-013**: Todo consejo o texto de ayuda que mencione una tecla MUST mostrar la tecla asignada en ese momento.
- **FR-014**: Toda lista vacía en las pantallas del mod MUST explicar por qué está vacía y, cuando exista, ofrecer la acción para llenarla.
- **FR-015**: Los mensajes de error de las funciones cubiertas MUST decir qué hacer a continuación, no solo que algo falló.
- **FR-016**: La guía y la ayuda MUST NOT interrumpir un combate en curso; los avisos que lleguen durante un combate se posponen.
- **FR-017**: Todo texto nuevo MUST existir en todos los idiomas que el mod soporta hoy.
- **FR-018**: El usuario MUST poder desactivar por completo los consejos y avisos de la guía.
- **FR-019**: En combate, el jugador MUST ver sin abrir menús si es su turno, su vida actual y el resultado de su última tirada.
- **FR-020**: Todas las pantallas del mod MUST cumplir la guía de diseño del proyecto: ningún texto cortado sin aviso, estados de control distinguibles y confirmación visible de cada acción.
- **FR-021**: El sistema MUST ofrecer un menú central, abierto con la tecla del panel (P por defecto), que reúna todas las funciones disponibles para el rol del usuario, agrupadas por tarea (los grupos concretos están en `contracts/screen-navigation.md`).
- **FR-022**: Los atajos de teclado actuales MUST seguir funcionando como accesos directos, con una excepción decidida: P pasa a abrir el menú central, y el panel del DM conserva un acceso directo con una tecla propia, sin asignar por defecto. El menú central muestra junto a cada función su atajo asignado, si lo tiene.
- **FR-023**: La bienvenida, la ruta de primeros pasos, la ayuda y la referencia de teclas MUST poder abrirse desde el menú central.
- **FR-024**: Toda pantalla abierta desde otra MUST mostrar bajo su título la ruta de pantallas desde el menú central (por ejemplo "Menú › Panel de DM › Mazmorras"); cada tramo MUST poder pulsarse para volver directamente a esa pantalla, y una ruta demasiado larga se acorta por el principio con "…" sin perder el tramo actual.
- **FR-025**: Esc MUST volver siempre a la pantalla anterior de la ruta, y cerrar solo desde la primera; ningún flujo de varias pantallas devuelve al mundo de golpe salvo que el usuario lo pida.
- **FR-026**: En el menú central y en todas las listas del mod, el usuario MUST poder moverse entre opciones con las flechas y elegir con Enter; la opción seleccionada MUST distinguirse a simple vista del resto y del estado de cursor encima.
- **FR-027**: Con un cuadro de búsqueda activo, las flechas MUST mover la selección entre los resultados filtrados sin borrar el texto buscado.
- **FR-028**: La ficha de personaje MUST seguir las mismas reglas de diseño y navegación que el resto de pantallas (estilo, textos que no se cortan, ruta bajo el título, Esc, flechas entre pestañas y listas), sin perder ningún dato ni función que tenga hoy; lo que no las cumpla se rehace.
- **FR-029**: La ficha MUST mostrar los datos de los personajes ya existentes sin pedir al usuario ninguna migración ni acción.
- **FR-030**: La tecla de ayuda MUST NOT coincidir por defecto con ninguna tecla de Minecraft ni con otro atajo del mod.

### Key Entities

- **Progreso de incorporación (por usuario)**: si vio o descartó la bienvenida y qué hitos completó y cuándo. Va ligado a la persona, no al personaje.
- **Preferencia de consejos (por equipo)**: si el usuario desactivó los consejos (FR-018). Se guarda en el equipo donde juega, no con el progreso.
- **Hito**: un paso de la ruta: rol al que pertenece, orden, acción real que lo completa, función a la que lleva y permiso que requiere.
- **Tema de ayuda**: la explicación de una pantalla o función concreta, enlazada desde esa pantalla y presente en la guía.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Un jugador nuevo hace su primera tirada de dados en menos de 1 minuto desde que entra al mundo, sin consultar documentación externa.
- **SC-002**: Un jugador nuevo crea su personaje y hace su primer ataque en menos de 10 minutos.
- **SC-003**: Un DM nuevo importa un mapa de ejemplo e invoca su primer monstruo en menos de 15 minutos, sin ayuda externa.
- **SC-004**: Un DM nuevo dibuja una sala y la coloca en el mundo en menos de 10 minutos, sin perder ni dañar bloques que no quería cambiar.
- **SC-005**: En pruebas con al menos 5 personas nuevas por rol, el 80 % completa la ruta de su rol en su primera sesión.
- **SC-006**: El 100 % de las pantallas del mod ofrece ayuda contextual y el 100 % de las listas vacías explica el siguiente paso.
- **SC-007**: Ningún texto de ninguna pantalla del mod, incluida la ficha, queda cortado sin aviso a escala de interfaz 2 o 3, en ninguno de los idiomas soportados.
- **SC-008**: Las personas de prueba valoran la facilidad de empezar con al menos 4 sobre 5 de media.
- **SC-009**: Un jugador que ya usaba la ficha encuentra en la ficha adaptada cada dato que consultaba antes en menos de 10 segundos.

## Assumptions

- "Jugadores nuevos" incluye tanto a jugadores como a DMs: las funciones mencionadas (importar dungeons, dibujar salas) son del DM, así que la ruta distingue los dos roles.
- La guía en libro existente es la fuente de las explicaciones; la ayuda contextual enlaza a ella en lugar de duplicar el texto.
- Ya existe una lista de progreso para crear el personaje; la ruta de primeros pasos sigue ese mismo patrón y lo amplía.
- "Visual" significa coherencia y legibilidad sobre la identidad visual actual (guía de diseño del proyecto), no un cambio de estética: la ficha se adapta a esa misma identidad, no a una nueva.
- El editor de salas, el importador y la ficha de personaje quedan fuera del manejo completo por teclado: siguen pensados para ratón, salvo cambiar de pestaña en la ficha con las flechas (FR-028).
- No se añaden mecánicas de juego ni reglas nuevas; solo guía, ayuda y pulido de la experiencia sobre las funciones existentes.
- La ayuda del importador explica dónde generar gratis un mapa de ejemplo y en qué formato. Publicar un mapa de ejemplo dentro del mod queda pendiente de confirmar la licencia de las muestras actuales; hasta entonces, SC-003 se mide con un mapa generado por la persona de prueba.
- El progreso se guarda por persona, en el servidor donde juega; no se sincroniza entre servidores distintos.
- La validación con personas reales (SC-005, SC-008) la organiza el responsable del proyecto; no es parte de la funcionalidad.
