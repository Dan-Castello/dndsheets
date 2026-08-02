# Templates de dndsheets

Punto de partida para crear contenido propio (armas, hechizos, monstruos, presets, rasgos) sin
tener que adivinar el formato leyendo el código fuente. Estos archivos **no se cargan solos**:
son plantillas para copiar, no contenido real de una partida.

## Cómo usarlos

1. Copiá el `.json` que te interese a la carpeta del mundo real:
   `<mundo>/dndsheets/<tipo>/tu_archivo.json` (p. ej. `dndsheets/weapons/mis_armas.json`).
   Esa carpeta se crea sola al arrancar el servidor si no existe.
2. Editá los campos que quieras — cualquier nombre de archivo vale, y cualquier `.json` que haya
   ahí dentro se carga solo al arrancar el servidor. Para recargar sin reiniciar, usá el comando
   `load` del tipo correspondiente (`/dndweapons load`, `/dndspells load`, `/dndmonsters load`,
   `/dndpresets load`, `/dndtraits load`).
3. Borrá las entradas de ejemplo que no uses — son solo para mostrar la forma de cada campo.

## Archivos

| Archivo | Va en | Qué define |
|---|---|---|
| `weapons.json` | `dndsheets/weapons/` | Armas cuerpo a cuerpo/a distancia: dado de daño, característica, a una/dos manos, restricción por clase, reskin. |
| `spells.json` | `dndsheets/spells/` | Hechizos: ataque, salvación o curación; área de efecto; concentración. |
| `monsters.json` | `dndsheets/monsters/` | Bloques de estadísticas de monstruo: CA, PG, características, ataques y hechizos especiales. |
| `presets.json` | `dndsheets/presets/` | Presets de clase: características de partida, arma inicial, rasgos y hechizos concedidos, espacios de conjuro. |
| `traits.json` | `dndsheets/traits/` | Rasgos (pasivas de clase): golpe a mano desnuda con dado propio, dados extra de Ataque Furtivo. |
| `resourcepack/` | un resource pack aparte | Cómo reskinear un arma personalizada (`customModelData`) y cómo reskinear un tipo de monstruo entero por textura. |

Cada `.json` de acá tiene varias entradas de ejemplo para mostrar las variantes de cada campo
(un arma a una mano, una versátil, una a dos manos, una restringida por clase...) — no hace falta
usarlas todas, es una referencia.

## Campos opcionales, comportamiento por defecto

En todos los tipos de contenido, un campo que no pongas toma un valor por defecto razonable (está
anotado en cada plantilla). Nunca hace falta escribir el JSON completo — solo lo que quieras
cambiar respecto al default.
