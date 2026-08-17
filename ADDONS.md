# Writing an addon for dndsheets

There are two ways to extend this mod, and **most addons only need the first**.

---

## 1. Ship JSON. No Java, no dependency.

Put content files in your own mod's jar (or in a plain datapack) under:

```
data/<your-namespace>/dndsheets/<type>/<anything>.json
```

where `<type>` is one of `weapons`, `spells`, `monsters`, `presets`, `traits`, `items`.

That is the whole integration. dndsheets loads it on world load and on `/reload`, exactly like recipes.

### Example: a spell

`data/miaddon/dndsheets/spells/lightning_lance.json`

```json
{
  "id": "miaddon:lightning_lance",
  "name": "Lightning Lance",
  "level": 1,
  "mode": "attack",
  "castingAbility": "int",
  "dice": "2d8",
  "damageType": "rayo",
  "upcastDice": "1d8"
}
```

A working example lives in `src/test/resources/addon_example/` — copy that folder into a
`datapacks/` directory of any world to see it load.

### Example: a monster that looks like something

`baseEntity` accepts **any registered entity id, including one from another mod**. That is the single
most important sentence in this file. dndsheets ships no models of its own and never will — what it
does is let you point a stat block at whatever is installed:

```json
{
  "id": "miaddon:frost_knight",
  "name": "Caballero de Escarcha",
  "type": "no-muerto",
  "baseEntity": "iceandfire:deathworm",
  "ac": 18, "hp": 90,
  "abilities": { "str": 18, "dex": 11, "con": 16, "int": 8, "wis": 12, "cha": 9 },
  "proficiencyBonus": 3,
  "attacks": [
    { "name": "Espada larga", "toHitAbility": "str", "dice": "1d10", "damageAbility": "str", "damageType": "cortante" }
  ],
  "appearance": { "mainHand": "minecraft:iron_sword", "helmet": "minecraft:iron_helmet", "glowing": true }
}
```

If that entity is not installed, the monster **still spawns** — as a zombie, with a warning in the
log. It is never silently missing. So a bestiary pack can recommend a creature mod without requiring it.

`appearance` is optional, and so is every field inside it: `mainHand`, `offHand`, `helmet`,
`chestplate`, `leggings`, `boots` (item ids), plus `baby` and `glowing` (booleans). Equipment is never
dropped on death — it is a visual decision, not loot the DM did not hand out.

Two warnings that save wasted work:

- **Not every model draws equipment.** Villager, iron golem, ravager, vex, allay, slime, phantom,
  guardian and witch ignore it. The mod's self-test enforces this against its own bestiary.
- **Pick models that do not act on their own.** The warden applies Darkness within 20 blocks, the
  elder guardian Mining Fatigue within 50, the enderman teleports away when hit by a projectile, and
  the snow golem melts in a desert. All four were rejected from the shipped bestiary for that reason:
  at a table the DM decides what happens, not the model.

**This is the mod's answer to the Roll20 or Foundry token catalogue.** Not an art library of its own —
Minecraft's creature-mod ecosystem already *is* that library, and here you only have to write its id.

---

## Model packs — creature mods as the token library

Installing one of the mods below is enough. dndsheets ships a **skin pack** for each: with the mod
present, the matching monsters switch to its models on world load. Nothing needs configuring, and
**only the model changes** — HP, AC, attacks, type and the AI-less token behaviour stay dndsheets'.

All of them are free, on Forge 1.20.1, and every one is optional.

| Mod | What it covers here | Entries |
|---|---|---|
| [Ice and Fire: Dragons](https://www.curseforge.com/minecraft/mc-mods/ice-and-fire-dragons) (needs Citadel) | **All 40 dragons**, by breath weapon: fire, ice, lightning. Plus hydra, medusa, troll, cyclops-as-hill-giant, purple worm, cockatrice, hippogriff/griffon, couatl, kraken, lich, ghoul, wight, ghost, sprite, merfolk | 59 |
| [Alex's Mobs](https://www.curseforge.com/minecraft/mc-mods/alexs-mobs) | Beasts — the SRD has 95 and vanilla has no bear, crocodile, elephant, gorilla, eagle, komodo, centipede, shark, orca, moose, mosquito, vulture, snow leopard, void worm | 37 |
| [Naturalist](https://www.curseforge.com/minecraft/mc-mods/naturalist) | Real fauna: lion, hyena, rhino, deer, alligator, snakes, vulture, bear, owl, lizard, boar | 22 |
| [The Twilight Forest](https://www.curseforge.com/minecraft/mc-mods/the-twilight-forest) | Classic fantasy vanilla lacks: minotaur, lich, hydra, wraith, kobold, redcap-as-goblin, winter wolf, troll, fire beetle | 15 |
| [Mowzie's Mobs](https://www.curseforge.com/minecraft/mc-mods/mowzies-mobs) | Animated armor / shield guardian, remorhaz, shambling mound, nagas, xorn | 8 |
| [Guard Villagers](https://www.curseforge.com/minecraft/mc-mods/guard-villagers) | An actually-armed human for guard, knight, veteran, gladiator, bandit captain — and it draws the `appearance` gear, so each stays distinct | 6 |
| [L_Ender's Cataclysm](https://www.curseforge.com/minecraft/mc-mods/lends-cataclysm) | Bosses that should be intimidating on sight: tarrasque, kraken, efreeti, fire elemental, iron golem, shield guardian | 6 |

Packs load in that order and later ones win, so with both Alex's Mobs and Naturalist the overlaps
(bear, elephant, crocodile, snakes, boar, vulture) use Naturalist.

### Nothing here can break a monster

A skin line is applied **only** if the mod is loaded *and* the entity actually exists
(`MonsterRegistry.reskin` checks the registry first). A wrong id, a mod that renames its entities
between versions, or a pack for a mod you do not have leaves the monster exactly as it was —
vanilla model, working stat block. The server log names every line that did not apply.

### Write your own

Drop a file in `<world>/dndsheets/skins/anything.json`. DM files load after the built-in ones, so
yours wins:

```json
{
  "mod": "yourmod",
  "name": "Your Mod",
  "skins": {
    "dndsheets:ancient_red_dragon": "yourmod:elder_wyrm",
    "dndsheets:goblin": "yourmod:goblin_grunt"
  }
}
```

`"mod": "minecraft"` is allowed, for a pack that only reshuffles vanilla models.

---

## 2. Call the API, for things data cannot express

`net.hawthorn.dndsheets.api.DndSheetsApi` is the only package with a compatibility promise: methods
are added, never re-signed. Everything else (`SheetLoader`, the registries, `Config`) is internal and
may change without notice.

Use it when you need to *do* something rather than *declare* something — read or modify a character
sheet, roll with the mod's dice syntax, spawn a configured monster, register content computed at
runtime.

```gradle
dependencies {
    compileOnly fg.deobf("net.hawthorn:dndsheets:<version>")
}
```

```java
if (ModList.get().isLoaded("dndsheets")) {
    DndSheetsApi.registerSpell(/* ... */);
}
```

Guard every call with that `isLoaded` check and keep the dependency `compileOnly`, so your mod still
runs when dndsheets is absent.

---

## What an addon cannot do yet

Being honest about the edges, so nobody discovers them the hard way:

- **New rules.** You can add a monster with new numbers, not a monster with a new *mechanic*. There is
  no hook for "when this creature is hit, do X". If you need one, open an issue describing the
  mechanic — the rules core (`AttackRules`, `SaveRules`, `Combatant`) is where it would go.
- **New content *types*.** The six folders above are fixed; you cannot register a seventh kind of
  thing.
- **Client-side screens.** The GUI is not extensible; a screen must live in this mod.
- **Race/background/class options** load by replacing the whole category rather than merging by id,
  so they are not in the datapack loader yet.

The first of these is the one worth asking for. The others are mostly absent because nobody has
needed them.
