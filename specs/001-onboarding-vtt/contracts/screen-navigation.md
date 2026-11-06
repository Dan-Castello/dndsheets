# Contract: navegación y ayuda en las pantallas

Lo cumple **toda** pantalla del mod a través de su base (`ListPickerScreen`, `SmallFormScreen`/`FormPanelScreen`, `ModalDialogScreen`; `ItemPickerScreen` lo replica). Una pantalla nueva lo hereda sin escribir nada.

## Cabecera

```text
┌──────────────────────────────────────────────┐
│ [< Atrás]          Título              [ ? ] │
│ ─────────────────◆──────────────────         │  ← GuiStyle.ruleOrnate
│ Menú › Panel de DM › Mazmorras               │  ← Breadcrumbs (SUBTITLE_COLOR; el tramo bajo el cursor en ACCENT_COLOR)
│ …contenido…                                  │
```

| Elemento | Regla |
|---|---|
| Ruta | Títulos de la cadena `NavParent.parentScreen()` hasta la raíz, separados por " › ". Cada tramo menos el último se puede pulsar → `setScreen(ancestro)`. Si no cabe: "… › B › C", y el tramo actual siempre entero (recortado con "…" y tooltip si hace falta). Si no hay padre, no se dibuja ni ocupa espacio |
| "?" | `IconButton` en la esquina superior derecha del panel, con tooltip "Ayuda (F4)" que muestra la tecla asignada en ese momento |
| Tecla de ayuda | `KeyMapping` "key.dndsheets.help", F4 por defecto, categoría del mod. Cada base la comprueba en `keyPressed` |
| Abrir ayuda | `PatchouliAPI.openBookEntry(dndsheets:guide, helpEntry(), 0)`; al cerrar el libro se vuelve a la pantalla de origen |

## Teclado

| Tecla | Dónde | Efecto |
|---|---|---|
| Esc | todas | Vuelve a `parentScreen()`; cierra solo si es la raíz (FR-025) |
| ↑ / ↓ | listas (`ListPickerScreen`) | Mueve la selección entre las filas visibles y activas; salta las cabeceras; desplaza la lista para que la fila se vea |
| RePág / AvPág, Inicio / Fin | listas | Saltan de página o a los extremos |
| Enter | listas | Pulsa la fila seleccionada; si no hay selección y la búsqueda tiene texto, la primera coincidencia (comportamiento actual) |
| letras | listas con búsqueda | Siguen escribiendo en el cuadro; las flechas no lo vacían (FR-027) |
| ← / → | ficha (`PlayerSheetScreen`) | Pestaña anterior / siguiente |
| F4 | todas | Ayuda de la pantalla actual |

**Estados visibles** (DESIGN.md §4): reposo, cursor encima, **seleccionada con el teclado** (borde de latón, distinto del estado bajo el cursor) y desactivada.

## Menú central (tecla P)

```text
Menú
  Primeros pasos   — Bienvenida · Mis primeros pasos (n/m) · Teclas · Guía
  Personaje        — Ficha [H] · Personajes · Configurar · Presets · Mejora de característica
  Tiradas y combate— Tirar dados · Registro de tiradas
  Magia            — Grimorio [G] · Lanzamiento rápido [R]
  Mesa             — Diario · Compendio · Objetos
  Herramientas del DM (solo si isDm)
                   — Grupo y turnos · Invocar · Entregar y ajustar · Mundo y mesa · Contenido y ayuda
                     (los mismos textos que las cabeceras de DmPanelScreen: gui.dndsheets.dm_panel.section_*)
```

- Los atajos entre corchetes muestran la tecla asignada en ese momento, en `MUTED_COLOR` y alineados a la derecha.
- El título de la pantalla es "Menú" (`gui.dndsheets.player_panel.title`, que hoy dice "Menú del jugador"); es el primer tramo de toda ruta que empiece aquí.
- Cada fila de "Herramientas del DM" abre `DmPanelScreen.open(menú, sección)`, que conserva sus 5 secciones y se desplaza para que la cabecera de esa sección quede arriba de la lista. Las 5 filas llevan así a 5 sitios distintos del mismo panel, y la ruta sigue siendo "Menú › Panel de DM › …".
- Acceso directo opcional: la tecla `key.dndsheets.dmpanel_direct` ("Panel del DM", sin asignar por defecto) abre `DmPanelScreen` sin pasar por el menú, como hacía P antes.
- Abrirlo envía `MARK menu_opened` en **cada** apertura; la respuesta `SYNC` actualiza `isDm` (ver `onboarding-message.md`).

## Mis primeros pasos (ruta)

`ListPickerScreen` con una cabecera por rol ("Jugador", y "DM" si `isDm`). Cada fila: "✔ Tirar un dado" / "○ Invocar un monstruo". Al pulsarla se ejecuta la `action` del hito. Al final: "Reiniciar mis primeros pasos" (con `ConfirmScreen`). Si todos los hitos del rol están hechos, la ruta muestra un mensaje de enhorabuena bajo el título y deja de sugerirse en la bienvenida y en los globos.
