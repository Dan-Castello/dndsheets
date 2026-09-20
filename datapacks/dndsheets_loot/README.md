# dndsheets_loot (scaffold)

A structure ready to delegate 100% vanilla content (loot, encounters, recipes) without touching the Java
mod. Empty folders (with `.gitkeep`) ready to receive real files in another session.

- `data/dndsheets_loot/loot_table/chests/` — chest loot tables that inject weapons with
  `set_nbt`/`set_components`, using the same `{dndsheets:{weapon:"id"}}` tag that `/dndweapons give`
  and the creative-tab cards already produce. An item with that tag is already a weapon
  recognized by `CombatManager`/`Config` with no code change.
- `data/dndsheets_loot/function/` — `.mcfunction` files that chain existing commands (e.g.
  `/dndmonsters spawn` several times with relative coordinates) to set up a whole encounter with
  a single `/function dndsheets_loot:encounter_name`.
- `data/dndsheets_loot/tags/function/` — function tags (e.g. `#minecraft:load`) if an encounter
  needs to run automatically when the world loads.

To enable this pack: copy it to `saves/<world>/datapacks/` (or `world/datapacks/` on a server) and
`/reload` or `/datapack enable "file/dndsheets_loot"`.
