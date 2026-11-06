# Quickstart: validar la incorporación guiada

## Requisitos

- JDK 17 (lo pide Forge 1.20.1) y el wrapper de Gradle del repo.
- Un perfil de jugador **sin** estado de incorporación: un mundo nuevo, o `RESET` desde "Mis primeros pasos".

## 1. Comprobaciones automáticas

```powershell
./gradlew check            # compila e incluye testJsonContent
./gradlew testJsonContent  # solo el autotest
```

Esperado: pasa limpio. Cubre la forma de red de `OnboardingMessage` ([contrato](contracts/onboarding-message.md)), las transiciones y la migración del [modelo de datos](data-model.md), el recorte de la ruta y las claves en los 8 idiomas.

## 2. Primer minuto del jugador (US1, SC-001)

`./gradlew runClient` → mundo nuevo, sin abrir nada antes.

1. A los ~3 s sale el toast y después la bienvenida, que nombra el rol. **Esperado:** botón principal "Tirar un d20".
2. Púlsalo. **Esperado:** el dado animado cae y en el chat salen el dado, el modificador y el total, todo en menos de 1 minuto desde que entraste.
3. Sal y vuelve a entrar. **Esperado:** la bienvenida no vuelve a salir. Con P → Primeros pasos → Bienvenida se reabre.
4. Entra en mitad de un combate con un segundo cliente. **Esperado:** la bienvenida se pospone y sale al terminar el combate.

## 3. Ruta de primeros pasos (US2)

1. P → "Mis primeros pasos". **Esperado:** "Tirar un dado" ya aparece con ✔.
2. Abre la ficha con H (no desde la ruta). **Esperado:** "Abrir la ficha" pasa a ✔ sin hacer nada más.
3. Haz OP al jugador. **Esperado:** aparece la sección "DM" sin perder el progreso. Invoca un monstruo desde el panel: el hito se marca.
4. Reinicia el cliente. **Esperado:** el progreso sigue.

## 4. Importador y editor de salas (US3, SC-003, SC-004)

1. P → Mundo y mesa (en Herramientas del DM) → Mazmorras (piezas y generación) → Importar mapa de generador... → elige un archivo en "Mapas de generador". **Esperado:** los globos explican los formatos, cómo conseguir un mapa y qué pasará; "Saltar" los cierra para siempre.
2. Importa `dungeongen/donjon.json` (muestra local de pruebas, no se publica). Un archivo inválido debe decir qué hacer.
3. Editor de salas: el recorrido presenta dibujar, borrar, puertas, niveles y vista previa. Dibuja una sala de 5×5 y pégala. **Esperado:** antes y después de pegar se indica cómo deshacer; deshacer deja el terreno exactamente como estaba.

## 5. Navegación y ayuda (US4, FR-021..030)

1. Llega a P → Mundo y mesa → Mazmorras (piezas y generación) → Mapas de generador. **Esperado:** bajo el título se lee "Menú › Panel de DM › Mazmorras › Mapas de generador"; pulsar "Panel de DM" vuelve ahí. Esc retrocede de una en una y solo cierra desde el menú.
2. En cualquier lista: ↑/↓ seleccionan (borde de latón), Enter abre. Escribe en la búsqueda y usa ↑/↓: el texto no se borra.
3. Pulsa F4 y luego "?" en 5 pantallas distintas. **Esperado:** se abre la entrada de esa pantalla y al cerrar se vuelve a ella.
4. Reasigna la tecla de ayuda a F6. **Esperado:** el tooltip de "?", la referencia de teclas y la bienvenida muestran F6.
5. Abre una lista vacía (Personajes en un mundo nuevo, piezas de dungeon). **Esperado:** dice por qué está vacía y qué hacer.

## 6. Repaso visual (US5, FR-020, FR-028, SC-007)

Para **cada** pantalla: `/gui-review <ruta>` y después `runClient` a escala de interfaz 2 y 3, con el idioma en `es_es`.

- Ningún texto se corta sin "…" y tooltip.
- Se distinguen los 4 estados de los controles.
- Cada acción confirma algo visible.
- La ficha: ruta, "?", ←/→ entre pestañas. Abre una ficha creada **antes** del cambio: todos sus datos se ven (FR-029).

## 7. Desactivar los consejos (FR-018)

Configuración de cliente → `showOnboardingHints=false`. **Esperado:** no salen globos ni sugerencias; "?" y F4 siguen funcionando.
