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
- **Cover is real geometry, not a checkbox**: crouch behind a wall and you get 5e cover (+2 or +5 to AC and Dexterity saves), measured by ray-casting the actual blocks between attacker and target. Other VTTs simulate this with polygons; here the wall is just there.
- **Levelling up**: `/dndsheet levelup <players>` raises the level, re-derives max HP, proficiency and spell slots, and announces what changed. At levels 4/8/12/16/19 the player gets an **Ability Score Improvement** screen (+2 to one ability or +1 to two, capped at 20). Unspent improvements are remembered on the sheet — reopen with `/dndchar mejora`.
- **Characters can be deleted**: `/dndchar delete <id>`, or the "Borrar un personaje..." toggle in `/dndchar`. The sheet file is renamed to `.json.deleted` rather than removed, so a mistake is recoverable by hand, and you are never left with no character — deleting your only one leaves you a fresh blank sheet, which is how you reset.
- **Your active conditions show on the HUD** (in red, above spell slots): paralysed, restrained, frightened and the rest drive real rules, so you can see them happening instead of finding out because your clicks stopped working.
- **Each character has their own everything**: level, current hit points, once-per-rest resources and **inventory**. Switching characters puts the previous one's gear away and brings out theirs — nothing is lost, it is stored with the character.
- **Addons need no Java**: any mod or datapack can add spells, monsters, weapons, presets, traits and magic items by shipping JSON under `data/<namespace>/dndsheets/<type>/`. Loads on world load and `/reload`. See `ADDONS.md`.
- **All content packs auto-load on server start**: drop your JSON files in `<world folder>/dndsheets/{weapons,spells,monsters,presets}/` and they're read automatically, no command required (the load commands still exist for hot-reloading without a restart).
- Particle and sound feedback (crits, spell casts, totem-of-undying effects on stabilizing, etc.) alongside the chat text, all using vanilla Minecraft assets.
- **`/dnddistance <target>`**: distance to any entity in feet (5 ft/block, rounded to the nearest 5, same grid the rest of the sheet uses).
- **AoE preview**: shift+click an area-spell báculo to see the blast radius (particle ring) at the point you're aiming, without actually casting — a normal click still casts for real.
- **A DM Notebook** (`/dndnotes give <players>`, operator-only): a renamed Book and Quill for private DM notes — private and persistent for free, since it's just a normal book.
- **Dungeon generation** on top of vanilla's jigsaw system: build a room, save it with a structure block, then right-click it (and its jigsaw blocks) with the DM Wand to capture/configure it without hand-editing datapack JSON or the jigsaw block's own GUI. Generate from the DM Panel or `/dnddungeon generate`. See `DUNGEON_GUIDE.md`.
- **An in-game content creator** (DM Panel → "Crear contenido"): create, edit, and delete weapons, spells, class presets, traits, and race/background/class options entirely with forms — no hand-written JSON required. Monster templates are created by spawning a generic NPC, configuring it live, and saving it as a reusable stat block. Everything you create is written to a dedicated `dm_created.json` per content type and hot-loads through the same pipeline as a hand-authored pack.
- **Full DM Panel parity with the command set**: every `/dnd...` action that hands out an item, teaches a spell, or spawns a loaded monster also has a DM Panel row (pick a player, then pick from a searchable list) — commands stay available for anyone who prefers typing, but nothing requires it.
- **Search bars** on every DM Panel list that can get long (players, weapons, spells, monsters, presets, traits, content-creator lists) — filters live as you type.
- **`/dndguide`**: reopens the in-game Guide (same content as the sheet's/DM Panel's Guide button) on demand, from anywhere.

## Tutorial — first time in the world

Everyone gets a short reminder in chat on login (repeats every login, so it's never a one-shot you can miss), and a full **Guide** button lives on the character sheet (players) and on the DM Panel (DM-only pages included) — open it as many times as you want, it never runs out. This section is the same walkthrough in longer form, plus the full, validated command list.

### Players — step by step

1. **Press `H`** to open your character sheet. Fill in your ability scores, race, class, background — the amber-colored fields (HP, AC, proficiency, level, hunger) fill themselves in, don't edit those by hand.
2. **Roll** either by clicking a d20 button on the sheet, or typing `/roll 1d20+5` (short form `/r`). `Shift+click` a dice button, or use `/rollprivate` (`/rp`), to keep a roll (Stealth, Investigation…) visible only to you and any connected operator.
3. Open your **Grimoire** from the sheet button once you know a spell (a DM teaches you one with `/dndspells learn`) to see slots and known spells; look at a target and cast to resolve it as attack-vs-AC or save-vs-DC. A quick-cast staff item lets you cast without opening the Grimoire; `shift+click` an area-spell staff to preview its blast radius without casting.
4. Right-click a **Rest Kit** to propose a short or long rest — it only happens once every player accepts.
5. Dropping to 0 HP opens the **death save** window automatically: 3 successes stabilizes you, 3 failures is real death, a natural 20 wakes you up, and any nearby player can revive you by interacting with you.
6. `/dnddistance <target>` tells you the distance to any entity in feet.

### DM — step by step

1. **Press `P`** (needs operator permission) to open the **DM Panel** — everything below this list is a row in that menu; nothing requires typing a command.
2. Get the **DM Wand** with `/dndmonsters dmtool <your name>` (or DM Panel → "Dar objeto" → Vara de DM): right-click a spawned monster to resolve its attacks/spells or manage its custom attacks; `shift+right-click` a monster or armor stand to delete it instantly.
3. **Create content in-game**: DM Panel → "Crear contenido" to build weapons, spells, presets, traits, and race/background/class options with forms, or drop JSON files under `<world folder>/dndsheets/{weapons,spells,monsters,presets,traits,races,backgrounds,classes}/` — both auto-load on server start. The mod's own packs are written to `mod_defaults.json` in each of those folders on every start (don't edit that file — your files load after it and override it by id).
4. **Spawn a monster**: DM Panel → "Invocar monstruo cargado" (pick from a loaded stat block) or "Invocar NPC genérico" for a blank one to configure live — either way, add ad-hoc attacks from the DM Wand's monster menu, then optionally "Guardar como plantilla" to turn a configured NPC into a reusable stat block.
5. **Hand out items, weapons, and spells**: DM Panel → "Dar objeto" (Rest Kit, class-resource items, DM Wand, DM Notebook, …), "Dar arma", or "Enseñar/dar hechizo" — pick a player, then pick from a searchable list.
6. **Run combat**: DM Panel → "Modo turnos" to start/advance/end turn order and apply status effects, or the equivalent `/dndturns` command.
7. **Dungeons**: build a room, save it with a vanilla structure block, capture it with the DM Wand, configure its jigsaw connections with the DM Wand, then generate from DM Panel → "Mazmorras". Full walkthrough (including the start-pool pitfall that trips people up most) in `DUNGEON_GUIDE.md`.

### Full command reference

All commands below except `/roll`, `/r`, `/rollprivate`, `/rp`, `/dnddistance` and `/dndguide` require operator permission. `<players>` accepts a Minecraft player-selector (name, `@a`, `@p`, etc.) and can target multiple players at once. Every command that hands out an item, teaches a spell, or spawns a loaded monster also has a DM Panel equivalent (see the DM walkthrough above) — the table is complete either way, since the GUI rows call the same underlying logic.

| Command | Subcommand | Arguments | What it does |
|---|---|---|---|
| `/roll` (`/r`) | — | `<expression>` | Rolls dice notation server-wide, with colored hit/miss/damage feedback. |
| `/rollprivate` (`/rp`) | — | `<expression>` | Same, but only the roller and connected operators see it. |
| `/dnddistance` | — | `<target>` | Distance to any entity, in feet (5 ft/block grid). |
| `/dndsheet` | `setslots` | `<players> <max> [current]` | Sets max (and optionally current) spell slots. |
| | `restkit` | `<players>` | Gives a Rest Kit item. |
| | `advantage` | `<players> <normal\|ventaja\|desventaja>` | Sets advantage/disadvantage for the *next* attack roll. |
| | `damagetype` | `<players> <type> <normal\|resistant\|vulnerable\|immune>` | Sets damage-type resistance/vulnerability/immunity. |
| | `gold` | `<players> <add\|set> <amount>` | Adjusts the gold counter. |
| | `passive` | `<player>` | Shows passive Perception (only to whoever runs the command). |
| | `pact` | `<players> <cadena\|hoja\|vara>` | Sets a Warlock's Pact Boon. |
| | `setlevel` | `<players> <1-20>` | Sets character level (decoupled from Minecraft XP). |
| | `setac` | `<players> <value\|auto>` | Overrides AC, or clears the override with `auto`. |
| | `setroll` | `<players> <checks\|saves\|skills> <name> <expression>` | Rewrites one roll-button's dice expression remotely. |
| | `turnitems` / `rageitem` / `secondwinditem` / `inspirationitem` / `wildshapeitem` / `metamagicitem` / `smiteitem` / `huntermarkitem` / `shielditem` / `counterspellitem` | `<players>` | Hands out the matching class-resource item (Barbarian Rage, Fighter Second Wind, Bard Inspiration, Druid Wild Shape, Sorcerer Metamagic, Paladin Smite, Ranger Hunter's Mark, Shield, Counterspell, or generic turn-order items). |
| `/dndweapons` | `load` | `<file>` | Loads a weapons JSON pack. |
| | `list` | — | Lists loaded weapon IDs. |
| | `give` | `<players> <weaponId> [count]` | Gives a configured weapon. |
| `/dndspells` | `load` | `<file>` | Loads a spells JSON pack. |
| | `list` | — | Lists loaded spell IDs. |
| | `learn` | `<players> <spellId>` | Teaches a spell; the first spell also grants 1 spell slot and a quick-cast staff. |
| | `staff` | `<players> <spellId> [baseItem]` | Gives a quick-cast staff for a spell (default item: blaze rod). |
| `/dndmonsters` | `load` | `<file>` | Loads a monster stat-block JSON pack. |
| | `list` | — | Lists loaded monster IDs. |
| | `spawn` | `<monsterId> [count]` | Spawns a loaded monster (AI-less, real stat block). |
| | `spawn generic` | `<name> [baseEntity] [ac] [hp]` | Spawns a blank NPC (default: villager, AC 10, 10 HP) to fill in live. |
| | `attack add` | `<target> <name> <toHitAbility> <dice> <damageAbility> <damageType>` | Adds a custom attack to one spawned monster. |
| | `attack remove` | `<target> <name>` | Removes one custom attack. |
| | `attack clear` | `<target>` | Clears all custom attacks on that monster. |
| | `dmtool` | `<players>` | Gives the DM Wand. |
| | `movetool` | `<players>` | Gives the Move Wand (select a monster, then click a block to move it there). |
| `/dndpresets` | `load` | `<file>` | Loads a class-preset JSON pack. |
| | `list` | — | Lists loaded preset IDs. |
| | `apply` | `<players> <presetId>` | Applies a preset (class, hit die, abilities, starting weapon) to a sheet. |
| `/dndtraits` | `load` | `<file>` | Loads a traits JSON pack. |
| | `list` | — | Lists loaded trait IDs. |
| | `grant` | `<players> <traitId>` | Grants a trait to a sheet. |
| `/dndoptions` | `load` | `<race\|background\|class> <file>` | Loads race/background/class picker options. |
| | `list` | `<race\|background\|class>` | Lists loaded options for a category. |
| `/dndturns` | `start` | `[radius]` | Rolls initiative and starts turn order (default radius if omitted). |
| | `next` | — | Advances to the next combatant. |
| | `cancel` | — | Skips the current combatant (e.g. AFK) without ending turns. |
| | `end` | — | Ends turn mode. |
| | `effect` | `<players> <name> <dice> <turns>` | Applies a ticking status effect for N turns. |
| `/dnddungeon` | `piece capture` | `<id> <structure> <pool> <weight 1-150>` | Registers a saved structure as a jigsaw piece. |
| | `piece list` | — | Lists captured pieces. |
| | `piece remove` | `<id>` | Deletes a captured piece. |
| | `publish` | — | Writes datapack JSON for all pools and reloads. |
| | `generate` | `<pool> <maxDepth 1-7> <pos>` | Generates a dungeon from the given entry pool. |
| `/dndnotes` | `give` | `<players>` | Gives a DM Notebook (renamed Book and Quill). |
| `/dndguide` | — | — | Reopens the in-game Guide (DM pages included if you're an operator). |

### Keeping this tutorial in sync

Whenever a command, subcommand, keybind, or DM Panel row is added, changed, or removed, update all three of:
1. This section (steps + command table).
2. The in-game Guide book pages (`GuideBook.java` and the `gui.dndsheets.guide.page.*` / `chat.dndsheets.welcome.*` keys in both `en_us.json` and `es_es.json`).
3. `GUI_REFERENCE.md` if a screen or widget changed, or `DUNGEON_GUIDE.md` if the dungeon flow changed.

## Content packs (JSON)
Weapons, spells, monsters, presets, and traits are all defined the same way: drop a JSON file in the matching subfolder of `<world folder>/dndsheets/` and it loads automatically on server start (or immediately with the matching `/dnd... load <filename>` command). You don't need to copy anything to get started: the mod's own packs (87 spells, 330 monsters, 362 magic items, weapons, presets, traits) are written to `<world folder>/dndsheets/<type>/mod_defaults.json` on **every** server start, so an existing world picks up new content when you update the mod. That one filename is the mod's — don't edit it, it gets overwritten. Put your own content in any other `.json` in the same folder: those load **after** it and override it by id, so you can replace a shipped spell or monster just by writing your own entry with the same id. `/test/dndsheets` in this repo holds one minimal `ejemplo.json` per content type as a schema reference — the shortest correct example of each format.

You don't have to write JSON by hand: DM Panel → "Crear contenido" builds weapons, spells, presets, race/background/class options, and trait level-progressions with in-game forms, and writes them to a `dm_created.json` file per type using this exact same format — the hand-written and in-game-created files are interchangeable and load the same way. Monster stat blocks are created differently (spawn + configure + "Guardar como plantilla" from the DM Wand's menu) since a monster's attack list doesn't fit a flat form.

Dice expressions accept the ability-score shorthand `$str`/`$dex`/`$con`/`$int`/`$wis`/`$cha`, plus `$prof` (proficiency bonus) and `$hprof` (half proficiency, rounded up) — e.g. a cleric's Cure Wounds might use `"1d8 + $wis"`. Expressions with more than one dice group (e.g. `1d20 + 1d4`) are fully supported.

## Planned Features
- Localization to languages other than English and Spanish.

## Working on this codebase

If you're a contributor (human or AI) picking this project up, `PROJECT_CONTEXT.md` is the deep-dive: architecture and package layout, the shared patterns worth reusing before writing something new (`ListPickerScreen`/`SmallFormScreen`, `NamedRegistry`/`JsonRegistryLoader`, the `ContentType`/`GiveableItem` enums), every real bug found and fixed across recent testing with root causes, and a summary of the commit history. `GUI_REFERENCE.md` and `DUNGEON_GUIDE.md` stay the authoritative references for their areas; `PROJECT_CONTEXT.md` is the map that tells you those exist.

## Technical Details
This mod needs to be on both the client and server. In multiplayer, character sheets are kept for each player on the server, associated by UUID and saved as JSON on the server end. This can be seen in a "charactersheets" folder in the server instance. This allows server owners (likely the DM) to see the sheets themselves. These files are loaded when a client joins, and are saved to in real time when players make changes to their sheets.

Content packs (weapons, spells, monsters, presets) live similarly under a `dndsheets/` folder in the server instance, with one subfolder per content type.

Unlike the original release of this mod, D&D Sheets now *does* touch normal gameplay once you configure it to: recognized weapons/spells turn PvP and monster fights into real 5e attack rolls, and dropping to 0 HP triggers the death save system instead of vanilla death. Anything you haven't configured (an un-tagged sword, an un-loaded spell) behaves exactly like normal Minecraft, so this is opt-in per weapon/monster/spell rather than a global rule change.

 

## I don't know how to play D&D, does this mod teach me? Can you make a version for Pathfinder? Can you update to 1.21.1? Will I always be a DM?
Please note that while this mod makes playing D&D much easier, it does not contain any resources to play the game with. You will need to legally obtain those yourself.

There are also no plans to expand to other tabletop systems or update to newer versions of Minecraft. But you're more than welcome to do so yourself! Feel free to make pull requests to the project GitHub if you'd like to take up the task.

You will always be a DM.
