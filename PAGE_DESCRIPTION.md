# VTT MineRPG

**Play D&D 5e in Minecraft. Not next to it — in it.**

Minecraft is already a 3D virtual tabletop: your dungeon is built, not drawn, and the wall you hide
behind is a real wall. What was missing was the game *on top* of it — sheets, dice, spells, monsters
and rules — so that nobody has to alt-tab to Roll20 mid-fight.

VTT MineRPG is that layer. Press **H** for your character sheet, **P** for the DM Panel, and play.

---

## What it does

**For players**

- **A real character sheet**, one per character, per player, saved on the server. Abilities, skills,
  saves, class, race, background — and roll any of them with a click.
- **Fields that fill themselves in** from the real player (HP, hunger, level, proficiency) in amber,
  so you always know what's yours to edit and what isn't.
- **Combat resolves as 5e**: an attack roll against the target's actual AC, then real weapon dice on a
  hit. PvP and monsters both. An item you haven't configured still behaves like plain Minecraft.
- **Cover is real geometry.** Crouch behind a wall and you get +2 or +5 to AC and Dexterity saves,
  measured by ray-casting the blocks actually between you and the attacker. Other VTTs approximate
  this with polygons; here the wall is just there.
- **A Grimoire** with known spells and spell slots. Spells resolve as attack-vs-AC or save-vs-DC,
  cast from the window or from a quick-cast staff — shift-click an area spell to preview the blast.
- **Death saves**: dropping to 0 HP freezes you instead of killing you, and opens the save window.
  Three successes stabilise, three failures don't, a natural 20 wakes you up, and any player nearby
  can revive you by interacting with you.
- **Levelling up** re-derives max HP, proficiency and slots, and hands you the Ability Score
  Improvement screen at 4/8/12/16/19.
- **Several characters per player**, each with their own level, HP, resources and **inventory** —
  switching characters puts one set of gear away and brings the other out.

**For the DM**

- **A DM Panel** (`P`) with a row for everything: hand out items, weapons and spells, spawn monsters,
  run turn order, generate dungeons, open the guide. Every row has a command equivalent; nothing
  forces you to type.
- **Monsters with real stat blocks** — AC, HP, abilities, attacks, spells, resistances, legendary
  actions — spawned as AI-less vanilla mobs. They are tokens: they do nothing until you decide.
- **A DM Wand**: right-click a monster for its attack and spell menu, resolved with real rolls.
  A Move Wand repositions them. Shift-right-click deletes.
- **Turn order** with initiative, ticking effects, and automatic end-of-combat detection.
- **Dungeon generation** on top of vanilla's jigsaw system: build a room, save it with a structure
  block, capture it with the DM Wand, generate. No datapack JSON by hand.
- **An in-game content creator**: weapons, spells, presets, traits and race/background/class options
  through forms. Monster stat blocks are made by spawning an NPC, configuring it live and saving it
  as a template.
- **`/dndmonsters gallery`** spawns the whole bestiary in a labelled grid, to see what you have.

**Content included** — 330 monsters, 87 spells, 362 magic items, plus weapons, class presets and
traits. All of it SRD 5.1, all of it editable, none of it required: your own JSON overrides any of it
by id.

**Addons need no Java.** Any mod or datapack can add spells, monsters, weapons, presets, traits and
magic items by shipping JSON under `data/<namespace>/dndsheets/<type>/`. See `ADDONS.md`.

---

## Requirements

Minecraft **1.20.1**, **Forge**. Needed on both client and server.

## Optional integrations — nothing here is required

Install any of these and VTT MineRPG uses it. Install none and everything still works.

| Mod | What it adds here |
|---|---|
| [Patchouli](https://www.curseforge.com/minecraft/mc-mods/patchouli) | The in-game Guide opens as a proper manual — index, categories, search, bookmarks, a book item you can keep — instead of a written book. Same text either way. |
| [Curios API](https://www.curseforge.com/minecraft/mc-mods/curios) | Magic items use real accessory slots (ring, necklace, belt, cloak) instead of attunement alone. |

### Creature mods as the token library

VTT MineRPG ships **no models of its own** — Minecraft's creature-mod ecosystem already is a token
catalogue far bigger than any it could draw. What it ships is a **skin pack** per mod: install one and
the matching monsters switch to its models on world load, automatically. **Only the model changes** —
stat blocks, rules and the AI-less token behaviour stay the mod's own — and if the mod isn't there,
the monster keeps its vanilla model and works exactly the same.

| Creature mod | Covers | Entries |
|---|---|---|
| [Ice and Fire: Dragons](https://www.curseforge.com/minecraft/mc-mods/ice-and-fire-dragons) | All 40 dragons by breath weapon, plus hydra, medusa, troll, cyclops, purple worm, cockatrice, griffon, couatl, kraken, lich, ghoul, wight, ghost, sprite, merfolk | 59 |
| [Alex's Mobs](https://www.curseforge.com/minecraft/mc-mods/alexs-mobs) | Beasts vanilla has none of: bear, crocodile, elephant, gorilla, eagle, komodo, shark, orca, moose, vulture | 37 |
| [Naturalist](https://www.curseforge.com/minecraft/mc-mods/naturalist) | Real fauna: lion, rhino, deer, alligator, snakes, bear, boar, ostrich, firefly | 22 |
| [The Twilight Forest](https://www.curseforge.com/minecraft/mc-mods/the-twilight-forest) | Classic fantasy: minotaur, lich, hydra, wraith, kobold, goblin, winter wolf, troll | 15 |
| [Mowzie's Mobs](https://www.curseforge.com/minecraft/mc-mods/mowzies-mobs) | Animated armour, shield guardian, remorhaz, shambling mound, nagas, xorn | 8 |
| [Guard Villagers](https://www.curseforge.com/minecraft/mc-mods/guard-villagers) | An actually-armed human for guard, knight, veteran, gladiator, bandit captain | 6 |
| [L_Ender's Cataclysm](https://www.curseforge.com/minecraft/mc-mods/lends-cataclysm) | Bosses that should be intimidating on sight: tarrasque, kraken, efreeti, fire elemental | 6 |

You can also point any stat block at any entity id from any mod yourself — that's one line of JSON.

---

## Credits

### The mod

VTT MineRPG was created by **Hawthorn** and **Cosmic**, and is released under the **MIT License**
(Copyright © 2025 Inkshriek). Contributors to the original repository: **Inkshriek**, **Salem**,
**Ardun**. This version continues that work — see the commit history for what changed.

Source: <https://github.com/Inkshriek/dndsheets>

### Game content

This work includes material taken from the System Reference Document 5.1 ("SRD 5.1") by Wizards of
the Coast LLC and available at <https://dnd.wizards.com/resources/systems-reference-document>. The
SRD 5.1 is licensed under the Creative Commons Attribution 4.0 International License, available at
<https://creativecommons.org/licenses/by/4.0/legalcode>.

Spells, monsters and magic items were imported from the JSON transcription of the SRD 5.1 maintained
by [5e-bits/5e-database](https://github.com/5e-bits/5e-database), also under CC-BY-4.0. The Spanish
translations and the adaptation to this mod's content schemas are this project's own work.

**Nothing from a closed D&D book is included, and nothing will be** — no Xanathar's, Tasha's or
Volo's material, no monsters or subclasses outside the SRD. This mod is distributed publicly and can
only carry redistributable material.

### Bundled library

Dice expressions are parsed by [DiceBot](https://github.com/tfriedrichs/dicebot) by **Torben
Friedrichs**, MIT License. It is the only third-party library shipped inside the jar (relocated under
the mod's own package so it can't collide with anyone else's copy).

### Art

Every texture, item icon and model file in this mod was made for it. No third-party art is
redistributed — which is exactly why the bestiary borrows *installed* creature mods for its models
instead of shipping copies of them.

### The optional mods above

VTT MineRPG is not affiliated with any of them and bundles none of them. It only reads their entity
ids when they happen to be installed. Full credit to their authors:

- **Patchouli** — Vazkii
- **Curios API** — C4 (TheIllusiveC4)
- **Ice and Fire: Dragons** — Alexthe666, TheBv (requires Citadel, by Alexthe666)
- **Alex's Mobs** — Alexthe666, Carro1001, Paint_Ninja (requires Citadel)
- **Naturalist** — Starfish Studios (requires GeckoLib)
- **The Twilight Forest** — Benimatic, AtomicBlom, Drullkus, Killer_Demon, quadraxis, Tamaized,
  williewillus and the Twilight Forest team
- **Mowzie's Mobs** — BobMowzie, Wadoo, Vakypanda, Noonyez, pau101 (requires GeckoLib)
- **Guard Villagers** — TallestEgg, textures by HadeZ/SadNya69
- **L_Ender's Cataclysm** — L_Ender (requires LionfishAPI)

---

## Notes

This mod makes playing D&D easier; it does not teach you to play, and it is not a substitute for the
rulebooks. You'll need to obtain those legally yourself.

There are no plans to support other tabletop systems or newer Minecraft versions — but the source is
MIT, so you're welcome to take that up yourself.

You will always be a DM.
