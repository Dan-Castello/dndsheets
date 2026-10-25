# Changelog

## Unreleased

**GeckoLib 4 is now a required dependency** (for the creature models below). Install it alongside the mod.

### Animated creature models

- 15 animated creature models of the mod's own: dragons, goblinoids, brutes and giants, kobolds and lizardfolk,
  devils and demons, undead, golems, wolves and big cats, crocodiles and lizards, snakes and nagas, spiders, oozes,
  elementals and mephits, birds, and sharks. **144 monsters** use them, each in its own colours.
- Each model has idle, attack, hurt and death animations, and flying creatures have a flight animation. The attack
  animation plays on every monster attack, including legendary and opportunity attacks.
- Weapons in a monster's hands are drawn, in the right hand.
- Wyrmlings and pseudodragons are drawn small; the size category still scales everything else with Pehkui.
- A colour is a palette, not another texture: one model per creature type, and a pack can give a monster any colour
  with `"variant": "#RRGGBB"`.
- Every monster keeps its vanilla entity as a fallback. New config `ownCreatureModels` (default true): false spawns
  them all as before. Skin packs for other creature mods still win, and a model missing on a client draws as the
  dragon instead of crashing.
- Monsters that don't fit a model (equipped humanoids, skeletons, horses, composite creatures…) stay vanilla.

### Item models

- 3D held models, in a dark arcane style, for the DM wand, move wand, rest kit, summon card, spell staff and the
  ability medallions.
- 21 weapons from the weapon list get their own model in hand.
- The staff and the summon card look different once they hold something.

### One staff, one card

- **Multi-spell staff:** one staff holds up to 9 spells and casts the active one. Change the active spell with Shift+scroll or
  the new X / Z keys; the action bar shows it. "Bind to staff" in the Spellbook adds a spell to the staff in your
  hand, or removes it if it's already there. The creative tab now has one empty staff instead of one per spell.
- **Multi-monster summon card:** one summon card holds several monsters. With the card in hand, clicking a monster
  in the DM's monster list adds it to the card or removes it. The creative tab now has one empty card instead of
  330+.
- Old single-spell staffs and single-monster cards keep working.

### Combat

- Hitting a creature that isn't a monster (a cow, a villager) no longer starts an encounter. Before, it started one
  that never ended and left you stuck on the movement budget.
- Fleeing works: if no monster of the encounter is near any player for a while, the encounter ends ("You escaped
  the encounter."). For a short time after, a chasing monster's hit doesn't start a new one; you attacking, or the
  DM, still can. New configs: `fleeDistance`, `fleeEndSeconds`, `fleeWindowSeconds` (0 turns each one off).
- Dash and Disengage no longer end your turn: their effect applies to the movement you make after using them.

### Screens

- Character sheet:
  - the hit-point value no longer overlaps its label;
  - long lines wrap inside the panel;
  - conditions are shown on the Overview tab;
  - hovering a saving-throw pip says whether you are proficient.
- Lists:
  - an empty list says so;
  - a search with no matches says "No results for …";
  - Enter in the search box picks the first match.
- Forms confirm with Enter.
- Truncated button labels show the full text in a tooltip.
- The resource HUD hides behind open menus instead of showing through them.

## 2.3.0

Single file: the Dungeon Toolkit and Species addons are now bundled in this jar (CurseForge allows one
file per project). They keep their own mod ids (`dndsheets_dungeon`, `dndsheets_species`). Curios, Patchouli,
Jade, Pehkui and Origins are required dependencies. If you had the separate addon jars installed, delete them.

Rules and content in this release:

- Spells now carry casting time, range, components and duration; range limits how far a spell can be aimed.
- Monsters: Recharge 5-6 abilities, legendary action costs, and the dragon breath weapons and Wing Attack.
- Short rests spend Hit Dice (a long rest returns half); Exhaustion is now a condition with its six levels.
- Rage and Bardic Inspiration have limited uses per rest.
- Fighter Action Surge and Monk ki (Patient Defense, Step of the Wind) in the Turn Actions menu.
- Wild Shape has 2 uses per rest and Twinned Spell costs sorcery points (a long rest returns them).
- Death saves: giving up and three failed saves always kill (even creative, invulnerable or with a totem), a revived character is protected for 5 seconds, monsters stop targeting a downed character, and the downed state is cleared on respawn instead of on the death event.
- Damage while down is a failed death save, and massive damage kills outright.
- Search, Hide (Stealth against nearby monsters' passive Perception: advantage on the next attack) and Ready (one action out of turn, using the reaction) in the Turn Actions menu.
- Difficult terrain (water, cobwebs, soul sand, honey, powder snow, mud, berry bushes, vines) costs double movement in turn mode.
- Brass dragons' sleep breath and silver dragons' paralyzing breath.
- Player experience: a rebuilt character sheet (H key) with Overview, Skills, Combat and Spells tabs, visible modifiers and bonuses, class resource pips and condition glyphs; game-icons.net icons (CC BY 3.0, credited in CREDITS.md) for conditions, spell schools and actions; an animated d20 on attack, spell-attack and death-save rolls; condition glyphs over combatants; a welcome screen on a character's first login; spell details in the Grimoire; Cinzel (OFL) screen titles.
- Slowed and Weakened conditions, a push effect, Flurry of Blows, and lair actions that fire at the start of each round.
- Surprise (a condition the DM sets before the encounter), Magic Resistance on the monsters that have it, and total cover now blocks single-target spells. A creature that can't act has its turn skipped.

## 2.2.1

Metadata only — the mod itself is byte-for-byte the same as 2.2.0.

The optional-dependency list on the mod pages was wrong: it advertised mods this project does not
actually talk to. It now lists exactly what the mod references — the four soft integrations (Curios,
Patchouli, Jade, Pehkui) and the seven creature mods its shipped appearance packs draw from. Mods that
are merely nice to have alongside it are not listed as dependencies, because they are not.

If you are on 2.2.0 there is nothing to gain from updating.

## 2.2.0

**The mod is now called VTT MineRPG.** Only the displayed name changed — the mod id is still
`dndsheets`, so existing character sheets, content packs and worlds keep working untouched. No
migration, nothing to re-create.

### Creatures are finally the right size — with Pehkui (optional)

Every monster now declares its 5e size category, filled in from the SRD for 329 of the 330 shipped
creatures. **149 of them are larger than Medium** (102 Large, 32 Huge, 15 Gargantuan) and until now
an ancient dragon stood exactly as tall as a goblin.

Install [Pehkui](https://modrinth.com/mod/pehkui) and they are drawn at their real scale — the
multiplier comes from the grid space 5e gives each size, so a Large creature is twice a human and a
Gargantuan one is four times. Without Pehkui nothing changes and nothing breaks.

Content packs gain an optional `"size"` field next to `"type"`. Packs written before this release
load exactly as before.

### Stat blocks on your crosshair — with Jade (optional)

Install [Jade](https://modrinth.com/mod/jade) and pointing at a creature shows its **AC, HP and
active conditions** straight on the HUD, no menus. Works for monsters, NPCs and other players' sheets.
Without Jade, nothing is added.

Both integrations are soft dependencies: the mod runs exactly as before if you do not install them.

### Under the hood

- Automated tests that run inside a real game world (`runGameTestServer`), covering the link between
  the rules engine and live entities — the part that previously could only be checked by playing.
- Publishing to CurseForge and Modrinth is now a single build command.
- Origins and Apoli resolve from Maven instead of jars checked into the repository.
- Build upgraded to Gradle 8.8.

### Compatibility

Minecraft 1.20.1, Forge. Requires both client and server, as always. Optional: Curios, Patchouli,
Jade, Pehkui.
