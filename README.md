![DNDSheets](https://media.forgecdn.net/attachments/description/null/description_a4b20240-7c01-4f37-bb19-077c88bdb478.png "Optional Title")

## Description


D&D Sheets is a utility and QoL mod intended for tabletop players. It gives the player the ability to manage and roll from a character sheet accessible with the press of a key. You can set ability scores, customize skill checks and saving throws, and even make your own attack and damage rolls.

 

## What is this for?
You've probably never thought about using Minecraft for tabletop; after all, why would you when Foundry and Roll20 exist? There's even VTTs for 3D environments. You'd never think Minecraft.

But as it turns out, Minecraft is an excellent way to create an immersive experience. It is, however, difficult to use for tabletop normally. After all, you will have to ask your players to make rolls and manage a sheet outside of Minecraft. This adds unnecessary micromanagement on the players and DM both, takes up additional system resources, and demands constant alt tabbing to be efficient.

This mod seeks to change that.

Essentially, this mod is designed for DMs and players alike that wish to play D&D 5e using Minecraft as their VTT of choice.


## Current Features
- Fillable character sheets for each player on the server, accessible with a keypress.
- Buttons from the character sheet to make easy dice rolls, which output rolls server-wide, with colored chat feedback (hits, misses, damage, saves) instead of plain text.
- Customizable roll expressions, allowing players to fine-tune how the buttons on their sheet work.
- A dedicated tab for attack and damage rolls, not technically limited to the aforementioned, allowing you to make your own rolls for any need and any context.
- A simple /roll (shorthand /r) command to make dice rolls, using standard dice notation.
- Fields that mirror the real player automatically (and are locked/amber-colored so you can tell them apart from what you fill in by hand): current/max/temp HP, hunger, XP level, and proficiency bonus (computed from level).
- Passive AC computed from real Destreza + equipped armor, and max HP computed from class (hit die), level, and Constitution.
- **PvP and monster combat resolve as real 5e attacks**: an attack roll vs. the target's actual AC, and on a hit, real damage from the configured weapon dice. Weapons you don't configure behave like normal Minecraft, this only kicks in for recognized gear.
- **Custom weapons**, including reskinning any vanilla item (dagger, spear, dart, etc.) via an NBT tag, loadable in bulk from JSON and given out with `/dndweapons`.
- **Other mods' weapons and armor work out of the box, with no per-item setup.** Armor was always automatic (AC reads Minecraft's real armor attribute, which any mod's armor already feeds into). Weapons now are too: any held item — from Tinkers' Construct or any other mod — that already deals real bonus damage in vanilla combat (the same attribute that makes its tooltip say "X Attack Damage") is auto-treated as a Strength/physical weapon with a die that averages that same damage, no JSON needed. A weapon you *do* configure by hand (JSON or the `.toml`) always overrides the automatic guess — use that for a modded item that should use Dexterity, deal a different damage type, or just feel different from the default guess.
- **A Grimoire** (own window, opened from a button on the sheet) for known spells, with a spell-slot counter; spells resolve as attack-vs-AC or save-vs-DC, loadable in bulk from JSON via `/dndspells`, learned with `/dndspells learn`, and quick-cast from a "báculo" (staff) item via `/dndspells staff`.
- **Monsters with real stat blocks** (AC, HP, abilities, attacks, spells) spawned as AI-less vanilla mobs via `/dndmonsters`, loadable in bulk from JSON. Players fighting them works exactly like PvP (attack-vs-AC, real HP loss, removed on defeat).
- **A DM Wand** (`/dndmonsters dmtool`): right-click a spawned monster to open a menu of its attacks/spells and resolve them against the nearest player; shift+right-click a monster *or* an armor stand to delete it instantly (handy cleanup if you spawned too many).
- **A death save system**: dropping to 0 HP freezes you at 1 HP (blind, weak, nearly immobile) instead of dying, opens a window to roll death saves (3 successes stabilizes, 3 failures is real death, a natural 20 wakes you up), and any nearby player can revive you instantly by interacting with you.
- **Class presets**: pick a preset (Fighter, Wizard, etc.) from a sheet button to fill in class, hit die, and all six ability scores at once, optionally handing you a starting weapon. Loadable in bulk from JSON via `/dndpresets`.
- **A creative-mode inventory tab** ("D&D Sheets") listing the DM Wand, the DM Notebook, every custom weapon, one báculo per loaded spell, and a summon card per loaded monster (works like a vanilla spawn egg) — no need to remember any command IDs.
- **All content packs auto-load on server start**: drop your JSON files in `<world folder>/dndsheets/{weapons,spells,monsters,presets}/` and they're read automatically, no command required (the load commands still exist for hot-reloading without a restart).
- Particle and sound feedback (crits, spell casts, totem-of-undying effects on stabilizing, etc.) alongside the chat text, all using vanilla Minecraft assets.
- **`/dnddistance <target>`**: distance to any entity in feet (5 ft/block, rounded to the nearest 5, same grid the rest of the sheet uses).
- **AoE preview**: shift+click an area-spell báculo to see the blast radius (particle ring) at the point you're aiming, without actually casting — a normal click still casts for real.
- **A DM Notebook** (`/dndnotes give <players>`, operator-only): a renamed Book and Quill for private DM notes — private and persistent for free, since it's just a normal book.
- **Dungeon generation** on top of vanilla's jigsaw system: build a room, save it with a structure block, then right-click it (and its jigsaw blocks) with the DM Wand to capture/configure it without hand-editing datapack JSON or the jigsaw block's own GUI. Generate from the DM Panel or `/dnddungeon generate`. See `DUNGEON_GUIDE.md`.

## Content packs (JSON)
Weapons, spells, monsters, presets, and traits are all defined the same way: drop a JSON file in the matching subfolder of `<world folder>/dndsheets/` and it loads automatically on server start (or immediately with the matching `/dnd... load <filename>` command). See `/test/dndsheets` in this repo for ready-to-copy sample packs covering every content type — copy that folder's contents straight into your world's `dndsheets/` folder to try them.

Dice expressions accept the ability-score shorthand `$str`/`$dex`/`$con`/`$int`/`$wis`/`$cha`, plus `$prof` (proficiency bonus) and `$hprof` (half proficiency, rounded up) — e.g. a cleric's Cure Wounds might use `"1d8 + $wis"`. Expressions with more than one dice group (e.g. `1d20 + 1d4`) are fully supported.

## Planned Features
- Localization to languages other than English and Spanish.

## Technical Details
This mod needs to be on both the client and server. In multiplayer, character sheets are kept for each player on the server, associated by UUID and saved as JSON on the server end. This can be seen in a "charactersheets" folder in the server instance. This allows server owners (likely the DM) to see the sheets themselves. These files are loaded when a client joins, and are saved to in real time when players make changes to their sheets.

Content packs (weapons, spells, monsters, presets) live similarly under a `dndsheets/` folder in the server instance, with one subfolder per content type.

Unlike the original release of this mod, D&D Sheets now *does* touch normal gameplay once you configure it to: recognized weapons/spells turn PvP and monster fights into real 5e attack rolls, and dropping to 0 HP triggers the death save system instead of vanilla death. Anything you haven't configured (an un-tagged sword, an un-loaded spell) behaves exactly like normal Minecraft, so this is opt-in per weapon/monster/spell rather than a global rule change.

 

## I don't know how to play D&D, does this mod teach me? Can you make a version for Pathfinder? Can you update to 1.21.1? Will I always be a DM?
Please note that while this mod makes playing D&D much easier, it does not contain any resources to play the game with. You will need to legally obtain those yourself.

There are also no plans to expand to other tabletop systems or update to newer versions of Minecraft. But you're more than welcome to do so yourself! Feel free to make pull requests to the project GitHub if you'd like to take up the task.

You will always be a DM.
