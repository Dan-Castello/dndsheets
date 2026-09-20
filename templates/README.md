# dndsheets templates

A starting point for creating your own content (weapons, spells, monsters, presets, traits) without
having to guess the format by reading the source code. These files **do not load by themselves**:
they are templates to copy, not real content of a game.

## How to use them

1. Copy the `.json` you're interested in to the real world folder:
   `<world>/dndsheets/<type>/your_file.json` (e.g. `dndsheets/weapons/my_weapons.json`).
   That folder is created automatically when the server starts if it doesn't exist.
2. Edit whatever fields you want — any file name works, and any `.json` in there is loaded
   automatically when the server starts. To reload without restarting, use the `load` command
   of the matching type (`/dndweapons load`, `/dndspells load`, `/dndmonsters load`,
   `/dndpresets load`, `/dndtraits load`).
3. Delete the example entries you don't use — they are only there to show the shape of each field.

## Files

| File | Goes in | What it defines |
|---|---|---|
| `weapons.json` | `dndsheets/weapons/` | Melee/ranged weapons: damage die, ability, one/two hands, class restriction, reskin. |
| `spells.json` | `dndsheets/spells/` | Spells: attack, saving throw or healing; area of effect; concentration. |
| `monsters.json` | `dndsheets/monsters/` | Monster stat blocks: AC, HP, abilities, attacks and special spells. |
| `presets.json` | `dndsheets/presets/` | Class presets: starting ability scores, starting weapon, granted traits and spells, spell slots. |
| `traits.json` | `dndsheets/traits/` | Traits (class passives): bare-handed strike with its own die, extra Sneak Attack dice. |
| `resourcepack/` | a separate resource pack | How to reskin a custom weapon (`customModelData`) and how to reskin a whole monster type by texture. |

Each `.json` here has several example entries to show the variants of each field
(a one-handed weapon, a versatile one, a two-handed one, one restricted by class...) — you don't
have to use them all, it's a reference.

## Optional fields, default behavior

In every content type, a field you leave out takes a reasonable default (it is
annotated in each template). You never need to write the full JSON — only what you want to
change from the default.
