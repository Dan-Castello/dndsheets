# Project context — dndsheets

Deep-dive reference for anyone (human or AI) picking up this codebase cold. `README.md` is the player/DM-facing pitch; `GUI_REFERENCE.md` and `DUNGEON_GUIDE.md` are authoritative for their areas. This file is the map that ties them together, plus the parts none of them cover: architecture, why things are shaped the way they are, every real bug found during the most recent testing pass (with root causes, not just symptoms), and the commit history.

## What this is

A Minecraft Forge mod (**1.20.1**, Forge `47.2.0`/`47.4.10`, mod id `dndsheets`) that turns Minecraft into a D&D 5e VTT: a fillable character sheet bound to a keypress, real 5e combat resolution layered on top of vanilla PvP/mob combat (opt-in per weapon/monster/spell — anything unconfigured behaves like normal Minecraft), and a full DM toolkit (spawn monsters with real stat blocks, run initiative, generate dungeons from vanilla's jigsaw system, create content in-game). It needs to be installed on both client and server. No plans to port to other MC versions or other tabletop systems (see the FAQ in `README.md`) — PRs welcome if someone wants to.

## Repository layout

```
src/main/java/net/hawthorn/dndsheets/
  *.java                    Root package: managers (one per class-resource mechanic —
                             BarbarianRageManager, ShieldManager, ConcentrationManager, ...),
                             registries (SpellRegistry, MonsterRegistry, PresetRegistry,
                             TraitRegistry, DungeonPieceRegistry), and cross-cutting pieces
                             (SheetLoader, CombatManager, DungeonManager, Config).
  api/                       DndSheetsApi — the ONLY surface other mods should call
                             (versioned, see API_VERSION). Everything in the root/command/
                             network packages is internal and can change signature without notice.
  client/gui/                Every screen. See "GUI architecture" below.
  client/gui/components/     Shared widgets (ButtonListWidget, RollScrollWidget,
                             DirectionalCycleButton, AdjustableImageButton).
  client/procedures/         Client-side save/load glue for the character sheet screen.
  command/                   One class per /dnd... command (Brigadier registration + handlers).
  network/                   One class per network message (~50). Registered centrally in
                             DndsheetsMod.registerNetworkMessages — see "Networking" below.
  init/                      Forge registration boilerplate (menus, screens, keybinds,
                             creative tab, sounds).
  world/inventory/           Menu (container) classes for the 3 screens that are real
                             AbstractContainerScreens (CharacterSheet, RollEditor, AdvancedRollEditor).
  procedures/                Server-side leftovers from the project's MCreator origin
                             (RollAnnouncerProcedure etc.) — still load-bearing for /roll.

src/main/resources/
  assets/dndsheets/lang/     en_us.json, es_es.json — includes the in-game Guide book's page text.
  assets/dndsheets/textures/ screens/ (GUI backgrounds+atlas), sounds, etc.
  dndsheets/defaults/        Default content packs seeded into a fresh world's dndsheets/
                             folder on first server start (see DndPaths.seedDefaultsIfEmpty).

test/dndsheets/              Ready-to-copy sample content packs (weapons/spells/monsters/
                             presets/traits), one "ejemplo.json" + one bulk file per type.
templates/                   Starter files for mod-pack authors extending content by hand
                             (not loaded by the mod itself).
datapacks/dndsheets_loot/    A loot-table datapack bundled with the mod (separate from the
                             per-world dungeon datapack DungeonManager generates at runtime).
runClient/, runServer/       Local dev run directories (gitignored except structure) — this is
                             where `saves/<world>/dndsheets/` and `saves/<world>/datapacks/
                             dndsheets_dungeon/` actually live during testing.
AUDIT_REPORT_2026.md         Historical technical-debt ledger (F1-F26), mostly resolved.
GUI_REFERENCE.md             Every screen: file, type, exact widget coordinates. Consult before
                             touching layout instead of re-deriving it from code.
DUNGEON_GUIDE.md             DM-facing dungeon walkthrough + troubleshooting, kept in sync with
                             the actual jigsaw/reload/pool mechanics (see bugs #6-#9 below).
```

## Architecture and the patterns worth reusing

**Content registries** (weapons, spells, monsters, presets, traits) all follow the same shape: an in-memory map (`NamedRegistry<T>`, generic — `register`/`get`/`ids`/`remove`), loaded from a JSON array file via `JsonRegistryLoader<T>` (per-element error isolation: one malformed entry warns and gets skipped, doesn't abort the whole file). `Config` (weapons) predates this shared pattern and still hand-rolls its own two maps instead of using `NamedRegistry` — a known inconsistency, not worth unifying unless you're already touching weapon loading for another reason. Race/background/class options (`CharacterOptionsRegistry`) are the odd one out: `loadFile` *replaces* the whole category rather than merging by id, because there's no id, just a flat string list — this distinction has caused real bugs (see the content-creator design below) and is worth remembering before assuming all five content types behave identically.

**`DndPaths`** owns every `<world>/dndsheets/<type>/` folder, creates them on server start, seeds `test/dndsheets`-equivalent defaults into empty ones, and auto-loads every `.json` file found — no command needed for the common case, the `/dnd... load` commands exist for *hot*-reloading a single file without a restart.

**The in-game content creator** (`ContentType` enum + `ContentPackFile` + `ContentFormScreen`) is the newest major piece and worth understanding before extending it. `ContentType` has one constant per id-keyed content type (WEAPON/SPELL/PRESET/TRAIT/MONSTER), each wrapping that type's `load`/`remove`. `ContentPackFile.upsert`/`removeById` read-modify-write a dedicated `dm_created.json` per type (kept separate from hand-authored packs so the tool never overwrites a file a DM manages by hand), then the caller re-invokes the type's normal `loadFile` to hot-register — there is no separate persistence layer, it's the exact same pipeline as a hand-dropped file, just automated. `ContentFormScreen` is a **generic** data-driven form (`FieldSpec` list → `SmallFormScreen`) for the three flat-schema types (weapons/spells/presets); traits (nested level→dice tables) and monsters (attack lists, created by capturing a live-configured NPC instead of a from-scratch form) don't fit that shape and get bespoke screens (`TraitEditScreen`, `MonsterTemplateSaveScreen`). Race/background/class options use a separate `OptionsManageScreen`/`OptionsSaveMessage` pair because of the replace-not-merge semantics mentioned above — trying to fold them into the `ContentType` abstraction would be forcing two genuinely different mechanics into one shape.

**GUI architecture**: three real `AbstractContainerScreen`s (character sheet, roll editor, advanced roll editor); everything else is a plain `Screen` opened imperatively via a static `open(...)`. Two shared bases carry almost every "plain" screen: **`ListPickerScreen`** (title + bordered panel + scrollable button list, optional "< Atrás" back button when opened with a `parent`, optional search box via overriding `searchable()` — filters the button list live, see bug #12) and **`SmallFormScreen`** (a short vertical form of `EditBox`/cyclic-button fields + Confirm/Cancel, optional third "Borrar" button via overriding `showDeleteButton()`/`onDelete()`, see bug #6). `GuiStyle` is the single source of the shared panel look. Read `GUI_REFERENCE.md` before adding a screen — there's almost always an existing base or widget that fits; the two size bugs below (#2, #3) both came from a screen fighting its base's layout math instead of using it.

**Networking**: every message is a small hand-written class (constructor, `FriendlyByteBuf` constructor, `buffer`, `handler` — no codec), registered once in `DndsheetsMod.registerNetworkMessages` (alphabetical, message id assigned by registration order — **never reorder or delete an entry**, it silently renumbers everything after it for anyone on a mismatched client/server build; `PROTOCOL_VERSION` exists precisely so a mismatch fails clean instead of desyncing). `DndsheetsMod.withDmTarget(context, targetUuid, action)` is the shared op-check + target-resolution helper used by every DM-acts-on-another-player message. Bulk "give one of several similar items" actions (the class-resource items, dungeon piece list, content entries) consistently use one message parameterized by an enum/type field rather than one message class per variant — follow that when adding another "pick one of N similar things" flow instead of writing N message classes.

**Sheet persistence**: `SheetLoader` keeps every player's sheet as an in-memory `JsonObject` (keyed by UUID), backed by one JSON file per player under `<server>/charactersheets/`. The single write path is `SheetLoader.saveServer` — historically only called when a sheet was created or when the *owning player* saved their own sheet screen; DM-initiated edits (gold, slots, presets, traits, ...) relied on a 5-minute autosave timer + save-on-clean-shutdown instead, which was a real, since-fixed bug (#5 below). If you add a new way to mutate a sheet, make sure it ends up calling `saveServer` (directly or through `SheetCommand.sendSheetUpdate`) rather than assuming the autosave will catch it.

**Dungeon generation**: `DungeonPieceRegistry` (per-world, not per-instance like the content registries) holds captured pieces. `DungeonManager` converts them into vanilla `template_pool` datapack JSON (`publish`) and calls vanilla's `JigsawPlacement.generateJigsaw` (`generate`). This area has more vanilla-API sharp edges than anywhere else in the mod — see bugs #7-#10, all found by reading decompiled vanilla source and directly inspecting a broken world's files rather than guessing from the Java side alone. If you touch this again, `DUNGEON_GUIDE.md`'s "regla de oro" callout and the decompiled-source findings below are the fastest way back to speed.

## Bugs found and fixed (most recent testing pass)

Numbered in roughly the order found. Each was root-caused, not just patched at the symptom — several early "fixes" in this list turned out to be real but incomplete, with a deeper cause found on the next test round; that's noted where it happened.

1. **Cyclic buttons only advanced forward.** Every dice/ability/damage-type/advantage/pact picker looped through every remaining option to go "back" one step. Fixed with `client/gui/components/DirectionalCycleButton.java` (left-click = next, right-click = previous), wired into `SmallFormScreen.addCycleButton` (covers every screen built on it) and `SheetAdjustScreen`'s hand-rolled buttons.
2. **Forms clipped off the top of the screen.** `SheetAdjustScreen`/`SmallFormScreen` centered their content on `height/2` with no floor; enough rows (8+) pushed the top rows and the title above `y=0` on normal GUI Scale. Fixed with a `Math.max(44, ...)` floor in both `init()` methods. This was a real fix but **not** the cause of the "spell slots don't apply" report — that was bug #3, found on the next round.
3. **The actual "spell slots won't apply" bug: a 2-pixel-wide button.** `SheetAdjustScreen`'s slots and damage-affinity rows computed their Apply button's width as `WIDE_WIDTH(190) - (FIELD_WIDTH+4)*2(188)` = **2px** — the two input fields already consumed nearly the whole row. Practically unclickable. Fixed by giving each Apply button its own full-width row below its fields.
4. **DM Panel actions gave zero feedback.** `SheetAdjustMessage`'s handler ran gold/slots/advantage/damage-affinity/pact/level changes but never told the DM anything happened, so a *working* change looked broken. Fixed with one confirmation chat message covering all six actions.
5. **DM-side sheet edits didn't survive a restart.** `SheetCommand.sendSheetUpdate` (the shared exit point for gold/slots/advantage/damage-affinity/pact/level) only sent the network update to the target player — it never called `SheetLoader.saveServer`. Changes lived in memory only, reaching disk exclusively via the 5-minute autosave or a clean `/stop`. An abrupt restart during testing silently lost recent changes, which read as "gold resets with the server." Fixed by saving in that same shared method.
6. **Dungeon piece / content-entry delete UX wasted a full extra row per item.** Started as command-only, then a `"Borrar: id"` companion row per list entry — the user pointed out that doubled every list's height for no reason. Settled on a `SmallFormScreen.showDeleteButton()`/`onDelete()` hook (a third button alongside Confirm/Cancel on the *edit* screen, not the list), applied to `DungeonPieceEditScreen` and the content creator's weapon/spell/preset editor; `TraitEditScreen` (not a `SmallFormScreen`) gets an explicit row since it doesn't have the hook available.
7. **Dungeon pieces captured a stale `.nbt` snapshot.** A vanilla Structure Block's "Save" button snapshots the world *at that instant*, not continuously. `DUNGEON_GUIDE.md`'s original documented order — save the structure block, *then* place/configure jigsaws — meant jigsaw configuration done afterward via the DM Wand never made it into the captured piece unless the DM remembered to re-press Save by hand. Fixed by having the DM Wand call `StructureBlockEntity.saveStructure()` itself the instant it opens the capture form (`DungeonToolManager.onCaptureFromStructureBlock`), so order no longer matters. (The auto-resave can itself fail if the structure block isn't in SAVE mode at that moment — added a precise diagnostic for that case rather than a generic "didn't work.")
8. **`/reload` cannot make a new/edited dungeon pool available, ever, in the same session.** Confirmed by decompiling vanilla source: `ReloadableServerResources.listeners()` — the exact list `/reload` refreshes — is `[tagManager, lootData, recipes, functionLibrary, advancements]`. `Registries.TEMPLATE_POOL` is a "worldgen" registry, populated only when the *world itself* loads, never touched by `/reload`. `DungeonManager.publish()` still calls `/reload` (harmless, and it does register the datapack as "known" for next time) but the mod now tells the DM the true fix: leave to the main menu and re-enter the world (or restart a dedicated server) once per new/edited pool, not every generation.
9. **The real "No starting jigsaw found" bug: mixing pieces in the start pool.** Confirmed by decompressing the user's actual captured `.nbt` files and counting jigsaw markers directly (not guessable from Java-side reasoning alone). Vanilla's `JigsawPlacement.addPieces` picks **one random, weight-biased piece from the entire start pool** and searches *only inside that one piece* for the jigsaw named `dndsheets:dungeon_start` — it never retries with a different piece on failure. A pool containing both an entrance piece (has the jigsaw) and regular connector pieces (don't) had a real, non-intermittent probability of failing every single generation. Fixed with `DungeonManager.hasStartJigsaw()` — proactive validation (same NBT scan vanilla does internally, just done ahead of time) before ever calling vanilla's generator, with a message stating the actual problem and fix. `DungeonPieceListScreen` now also marks `[inicio]` on pieces that have the jigsaw, so the mixup is visible before it becomes a failure.
10. **Unhelpful pool-name validation message.** DMs repeatedly typed a structure's own namespace (e.g. `dndsheets_dm:dungeon`) into pool fields, which must be a bare word (auto-namespaced to `dndsheets:X`). Centralized one educational message (`DungeonManager.poolNameError`) across the 6 call sites that previously each had their own generic "not a valid pool name."
11. **Orphaned `template_pool` JSON files accumulated forever.** `publish()` only ever *wrote* the current pools, never removed a pool file whose last piece had been reassigned/deleted elsewhere — found a stale `start.json` from early testing sitting in a real datapack folder. Fixed by deleting any pool file not in the current pool set before writing.
12. **Long GUI lists had no way to filter.** Added an opt-in `searchable()` to `ListPickerScreen` (live-filters the existing scrollable button list, no new widget type) and enabled it on every screen backed by a potentially-long or data-driven list: player picker, weapon/spell/monster/preset/trait pickers, content-creator lists, race/background/class option lists. Left the dungeon piece list and Grimoire un-searchable — both already override `listTop()` for their own reasons and would need extra care to compose correctly with the search box's reserved space.
13. **In-game tutorial vs. README** (a design correction, not a bug): the first attempt at "add a tutorial" was a big walkthrough section added to `README.md` — which never ships with the compiled mod jar. Pivoted to make the *existing* `GuideBook` (already opened via a sheet/DM-Panel button, previously never triggered proactively) the actual source of truth: now auto-opens once on a player's genuine first join (reusing "no sheet file exists yet for this UUID" as the signal — no new persisted flag needed), reachable anytime via `/dndguide` or the existing buttons, and its page content was expanded to cover class-resource items and the previously-undocumented `/dndsheet`/`/dndweapons`/`/dndpresets` admin subcommands. `README.md`'s tutorial section was kept as repo/contributor-facing documentation, not treated as the in-game delivery mechanism.

For older, already-resolved technical debt (naming, duplication, dead code — not user-facing bugs), see `AUDIT_REPORT_2026.md`; only one item there (F26, test coverage for `rollAttack`/`rollDamage`) is still open.

## Commit history

31 commits total. Two very different eras:

- **2025-09-19 — MCreator origin** (`6c01dd2` through `9a4de9f`, all same day): the mod started as an MCreator export (commit messages like *"fuck it"*, *"welp"*, *"remnants of a certain bad program"* say it plainly). `cba86da`/`95f4a80` clean up the generated Gradle project and MCreator assets. `7e6a6db` (2025-09-25) adds the original README; `309da21` merges a small patch PR. No further activity for ~10 months.
- **2026-08-02 onward — current maintenance** (`a9c061d` through `dc4d637`, all Dan-Castello): `a9c061d` "Full refactor" is the real break from the MCreator-era code. Then a steady cadence of focused passes, each with a real commit message instead of "welp": `8fa8aae` resolves the first 17 blocks of a technical audit; `6a07ec3`/`afa9511` unify GUI visual identity (`GuiStyle`, `ListPickerScreen`); `d07cf75` adds distance measurement, AoE preview, the DM Notebook; `1b170f2` adds the entire dungeon/jigsaw system and applies a second audit pass; `6f1e354`/`dc4d637` are housekeeping (gitignore, cleanup).

**As of this file, none of the current session's work is committed** — the content creator, the full DM-Panel command-parity pass, the search bars, and every bug fix in the numbered list above are all uncommitted working-tree changes (`git status` shows ~40 modified files and ~30 new ones). Worth a deliberate, reviewed commit (or a few logically-split ones) rather than one giant diff, given the size.

## Where to go next

- Adding a screen or touching layout → read `GUI_REFERENCE.md` first, reuse `ListPickerScreen`/`SmallFormScreen`.
- Adding a content type or command → check whether it fits the `ContentType`/`NamedRegistry`/`JsonRegistryLoader` pattern before writing a parallel one.
- Touching dungeons → read `DUNGEON_GUIDE.md`'s "regla de oro" and bugs #7-#11 above before assuming `/reload` or a captured piece is trustworthy without checking.
- Anything DM-facing that hands out an item/teaches a spell/spawns a monster → there's almost certainly an existing `GiveableItem`-style pattern or DM Panel row to extend rather than a new one-off.
