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

### Why this is the recommended path

- **No compile-time dependency.** You never import a dndsheets class, so a signature change here
  cannot break your addon.
- **No load-order problem.** You are not racing anyone's setup event.
- **Datapack authors can do it too.** Adding a bestiary does not require being a modder.
- **One file, one entry** is the datapack convention and is supported; an array of entries also works,
  which is how the mod's own packs are written.

### Rules of the road

- **Namespace your ids** (`miaddon:something`). Ids are global; two addons claiming the same id is a
  real collision, and the loader warns naming both files.
- **A DM's hand-written files win.** Datapacks load before the world's `dndsheets/` folder, so
  whoever runs the game has the last word over your content. That is deliberate.
- **A broken file skips itself**, not the rest — per entry, and per file. Check your server log.
- **Fields you omit take a default.** Adding a field to a schema is safe for you; the mod never makes
  an existing field mandatory (invariant 8).

The schemas are documented in `.claude/agents/srd-content-builder.md` and, authoritatively, in the
parsers themselves: `SpellRegistry.parse`, `MonsterRegistry.parse`, `PresetRegistry.parse`,
`TraitRegistry.parse`, `MagicItemRegistry.parse` and `Config.registerWeapon`.

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
