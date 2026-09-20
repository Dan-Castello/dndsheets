# Reskins: custom weapons and monsters

This is a **separate resource pack** (not a mod, and it doesn't go in `dndsheets/`) — it is installed like any
Minecraft resource pack: `.minecraft/resourcepacks/` (client) or `world/resourcepacks/`
(server with forced resources), and enabled from Options → Resource Packs. Format
1.20.1 (`pack_format: 15`).

It includes no real texture (`.png`) — the `.json` files here are working templates, and the
image is yours to draw/find and put at the path indicated.

## Weapons: `customModelData`

It lets a custom weapon (id `dndsheets:...`) avoid sharing the texture of its base Minecraft
item — e.g. so a "Dagger" doesn't just look like an iron sword.

1. In your real `weapons.json` (not in this template), put a unique `"customModelData": N` on the
   weapon you want to reskin — see `templates/weapons.json`, entry `dndsheets:enchanted_rapier`
   (it uses `100001` on the base item `minecraft:iron_sword`).
2. This resource pack already ships the complete pattern for THAT exact example:
   - `assets/minecraft/models/item/iron_sword.json` — patches the vanilla base item model
     (`minecraft:iron_sword`) so that, when the NBT tag's `CustomModelData` is
     `100001`, it uses ANOTHER model instead of the normal iron sword one.
   - `assets/dndsheets/models/item/enchanted_rapier.json` — the "own" model that override
     points to, with its own texture.
3. Only one real file is missing: the texture itself, at
   `assets/dndsheets/textures/item/enchanted_rapier.png` (16×16, the standard Minecraft item
   format). Without it, the item looks invisible/broken — this pack ships no image.

### For your own weapon
Copy the pattern: a new override inside `overrides` in the model of the SAME base item
(`assets/minecraft/models/item/<baseItem>.json` — if you have several custom weapons on the same
base item, they ALL go in the same `overrides` array, one per `customModelData`), a new model in
`assets/dndsheets/models/item/<your_weapon>.json`, and its texture at
`assets/dndsheets/textures/item/<your_weapon>.png`.

## Monsters

**No `customModelData` or any new field is needed** — `MonsterStatBlock.baseEntity` is already a real
vanilla entity type (zombie, skeleton, spider...), so reskinning that WHOLE entity type
is a plain old resource pack, without touching the mod:

- Replace `assets/minecraft/textures/entity/zombie/zombie.png` (or the base mob you use) with your
  own texture, and ANY `dndsheets` monster that uses `"baseEntity": "minecraft:zombie"` looks
  like that texture — including the normal vanilla zombies in the world, since it is the SAME
  entity, with no per-instance distinction.

**Real limit**: if you want TWO monsters that share the same `baseEntity` (e.g. a goblin
and a hobgoblin, both zombies) to look DIFFERENT from each other, a vanilla resource pack isn't enough —
you'd need a custom entity renderer (code, not JSON), or to give each one a different vanilla
`baseEntity` so they can be reskinned separately (e.g. goblin = zombie, hobgoblin =
husk/zombie villager).
