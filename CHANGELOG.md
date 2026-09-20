# Changelog

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
