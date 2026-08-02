# dndsheets_loot (scaffold)

Estructura preparada para delegar contenido 100% vanilla (loot, encuentros, recetas) sin tocar el mod
Java. Carpetas vacías (con `.gitkeep`) listas para recibir archivos reales en otra sesión — ver
`AUDIT.md` sección 4 en la raíz del repo para el detalle de qué va en cada una.

- `data/dndsheets_loot/loot_table/chests/` — loot tables de cofres que inyectan armas con
  `set_nbt`/`set_components`, usando la misma etiqueta `{dndsheets:{weapon:"id"}}` que ya generan
  `/dndweapons give` y las cartas de la pestaña creativa. Un ítem con esa etiqueta ya es un arma
  reconocida por `CombatManager`/`Config` sin ningún cambio de código.
- `data/dndsheets_loot/function/` — `.mcfunction` que encadenan comandos ya existentes (p.ej.
  `/dndmonsters spawn` varias veces con coordenadas relativas) para montar un encuentro completo con
  un solo `/function dndsheets_loot:nombre_encuentro`.
- `data/dndsheets_loot/tags/function/` — tags de función (p.ej. `#minecraft:load`) si algún encuentro
  necesita ejecutarse automáticamente al cargar el mundo.

Para activar este pack: copiarlo a `saves/<mundo>/datapacks/` (o `world/datapacks/` en servidor) y
`/reload` o `/datapack enable "file/dndsheets_loot"`.
