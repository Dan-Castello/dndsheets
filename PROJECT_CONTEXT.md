# Project context — dndsheets

Deep-dive reference for anyone (human or AI) picking up this codebase cold. `README.md` is the player/DM-facing pitch; `GUI_REFERENCE.md` and `DUNGEON_GUIDE.md` are authoritative for their areas. This file is the map that ties them together, plus the parts none of them cover: architecture, why things are shaped the way they are, every real bug found during the most recent testing pass (with root causes, not just symptoms), and the commit history.

## What this is

A Minecraft Forge mod (**1.20.1**, Forge `47.2.0`/`47.4.10`, mod id `dndsheets`) that turns Minecraft into a D&D 5e VTT: a fillable character sheet bound to a keypress, real 5e combat resolution layered on top of vanilla PvP/mob combat (opt-in per weapon/monster/spell — anything unconfigured behaves like normal Minecraft), and a full DM toolkit (spawn monsters with real stat blocks, run initiative, generate dungeons from vanilla's jigsaw system, create content in-game). It needs to be installed on both client and server. No plans to port to other MC versions or other tabletop systems (see the FAQ in `README.md`) — PRs welcome if someone wants to.

## Invariants — break these and it fails silently

Every one of these has already cost someone a debugging session, or would. They are cheap to
respect and expensive to discover. If you are an agent or a new contributor, read this list
before your first edit.

1. **Never reorder or delete an entry in `DndsheetsMod.registerNetworkMessages`.** The message id
   is the registration order, so an insertion silently renumbers everything after it. Add new
   entries at the end.
2. **Never insert a constant into the middle of an enum that crosses the network.**
   `FriendlyByteBuf.writeEnum`/`readEnum` travel by ordinal — same failure as above.
   `SheetAdjustMessage.Field` is the live example; `CONDITION` is last for exactly this reason.
3. **Prefer extending a parameterized message over registering a new class.**
   `SheetAdjustMessage` already merges six former message classes behind a `Field` enum; the
   bulk "give one of N similar things" flows follow the same shape.
4. **Anything that mutates a sheet must reach `SheetLoader.saveServer`.** The 5-minute autosave is
   a backstop, not the write path — relying on it already lost DM-side edits once.
5. **`/reload` can never publish a new or edited dungeon pool in the same session.**
   `Registries.TEMPLATE_POOL` is worldgen; it loads with the world. Tell the DM to re-enter the
   world; don't add retry logic.
6. **A dungeon start pool must contain only pieces that have the start jigsaw.** Vanilla picks one
   random piece from the pool and searches only inside it, never retrying. Mixed pools fail
   non-deterministically.
7. **Read `GUI_REFERENCE.md` before adding or moving a screen.** Almost every screen should extend
   `ListPickerScreen` or `SmallFormScreen`; the two worst layout bugs in the project's history
   both came from a screen fighting its base's math instead of using it.
8. **Content JSON is user data.** Adding a field is fine; renaming or requiring one breaks every
   pack a DM already wrote. Every parser defaults missing fields and isolates errors per element.
9. **Leave vanilla alone when nothing is configured.** An unregistered weapon, a mob with no stat
   block, a player with no sheet — all must behave exactly like normal Minecraft. This is the
   mod's core compatibility promise.
10. **Content JSON is formatted by hand, compactly.** Never rewrite these files with a
    `json.dump` — it reflows everything and makes the diff unreadable. Insert respecting the style.
11. **Every non-trivial rule gets a case in `JsonContentSelfTest`.** It runs on `gradlew build`,
    needs no Forge runtime, and has already caught a real bug (accented NPC names slugging to
    `npc-capit-n`). Logic that can't be reached without a running game belongs behind a pure
    helper class that can — that is why `CharacterRules` exists. It has caught four real bugs so
    far, two of them in code written minutes earlier.

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

test/dndsheets/              One hand-written "ejemplo.json" per content type: the minimal
                             example of each schema, and the fixture JsonContentSelfTest
                             parses. The bulk packs used to be duplicated here as well, and
                             the two copies had already drifted — the self-test was blessing
                             a file no player ever loads. There is now one copy of each bulk
                             pack, the shipped one under resources/dndsheets/defaults/, and
                             the self-test reads that.
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

## The rules core (read this before touching combat)

Three files carry the 5e rules layer. They are newer than everything else described below and
they supersede the older duplicated shapes, so read them before assuming a pattern from an
older file is still the way to do something.

**`Combatant`** is the single abstraction over "anything that participates in 5e rules": a
player (backed by a `JsonObject` sheet) or a monster (backed by a `MonsterStatBlock` + entity
NBT). Before it existed, that split was a `boolean isMonster` and every rule that needed "the
target's AC" or "take N hit points off it" was written twice. That cost was real and was never
a design decision: monsters had **no** resistances (`DamageTypes.multiplierFor` required a
sheet), **no** defensive reactions (`ShieldManager.effectiveAc` required a `ServerPlayer`), and
**no** concentration (`ConcentrationManager.onDamageTaken` did a hard cast). All three are fixed
in one place now. `Combatant.of(entity)` returns null for anything outside the rules (a modded
mob with no stat block, a practice armor stand, a player whose sheet failed to load) — callers
must fall back to vanilla Minecraft behaviour exactly as before.

Implementations hold **no state of their own**: they read and write where that state already
lived, so they can be constructed and thrown away per call, and conditions survive restarts by
the same path hit points already did.

**`Condition`** is the 14-condition table of 5e. Each rule is a `switch` inside the method that
applies it rather than a seven-boolean positional constructor, so each line reads as the manual
sentence it encodes. What existed before was `TurnManager.StatusEffect(name, dice, turns)` — a
damage-over-time timer whose `name` was free text nothing in the engine ever read. `"aturdido"`
was a tab-completion suggestion, not a mechanic.

Conditions reach the engine through `TurnManager.applyEffect`, which checks whether the effect
name *is* a condition. That means every existing path that could already apply an effect
(`/dndturns effect`, monster attacks and spells, player spells) now produces real conditions
with no new command and no new JSON field, and a free-text name (`"fuego"`, `"sangrado"`) still
behaves exactly as it always did. They are lifted again when the timer expires or concentration
breaks — the condition is persisted and the timer is not, so without that closing step you stay
paralysed forever.

Three single choke points enforce conditions, one hook each rather than one per caller:
`TurnManager.tryAct` (cannot act), `MovementAnchorTracker.enforceBudget` (speed 0), and
`CombatManager.resolveAttack` (advantage/auto-crit). Charmed is the exception — it depends on
*who* the target is, so it is checked at the three attack entry points next to the existing
grip/class restrictions, before the turn is spent or vanilla damage is let through.

Storage format is `label` or `label@sourceEntityId` in both the sheet's JSON array and the
monster's NBT string. The suffix is optional so anything written before sources existed still
loads. Entity ids do not survive a server restart, so after one a condition keeps its effect but
forgets who it pointed at; the two rules that use the source treat "can't see it" as "doesn't
apply", which is the safe side.

`Combatant` has three implementations, and the two sheet-backed ones share `SheetBacked` so the
split they replaced cannot creep back in: `PlayerCombatant` (hit points from the real Minecraft
health attribute, AC includes equipped armour and shield, can react), `NpcCombatant` (hit points
and AC from the sheet, because the entity is the body and the character is the sheet — it
survives the entity being unloaded or re-spawned), and `MonsterCombatant` (stat block + NBT).

**`CharacterRules`** holds the "whose character is this and which one are they wearing" rules
plus the max-hit-points formula, split out of `SheetLoader` because `SheetLoader` resolves
`FMLPaths.GAMEDIR` at class-init and
therefore cannot even be loaded outside a running Forge instance — these are exactly the rules
with branches worth pinning in `JsonContentSelfTest`. `SheetLoader.sheets` is now keyed by
**character id**, not player UUID, with a derived `player → active character` binding. A player
UUID is still a valid character id, which is what makes every sheet written before this change
resolve itself with no migration: its file was already named that way.

The binding is **derived** from an `active` field on each sheet rather than stored as a separate
index — an index can drift out of sync with the sheets and leave someone unable to play; a field
inside the sheet cannot contradict itself. `saveServer` resolves its id the same way reads do:
its three callers pass a player UUID, and without resolving it they would write over the legacy
sheet instead of the character being worn. `saveAll` must use `saveCharacter` directly for the
mirror-image reason.

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

**A large amount of work is uncommitted.** The content creator, the DM-Panel command-parity pass, the search bars, every bug fix in the numbered list above, and all of Fase 0 + the Fase 1 core are working-tree changes. Worth a deliberate, logically-split set of commits rather than one giant diff, given the size.

## The VTT roadmap and why it is in this order

The goal is a VTT that competes with Roll20/Foundry/TaleSpire/Owlbear. The gap was never code
quality — it was scope, and three measured structural defects. The order below is forced by
dependencies, not preference.

- **Fase 0 — `Combatant` + conditions. DONE.** Without it every rule is written three times
  (player / monster / armor stand). See the rules-core section above.
- **Fase 1 — character identity. DONE except its GUI.** Characters keyed independently of players;
  `/dndchar list|new|switch|npc|spawn`. NPC sheets get a body via `SheetLoader.spawnNpc`, are tagged
  onto the entity's NBT (`dndsheets.character`) and resolve through `Combatant.of` as
  `NpcCombatant` — a character with no one sitting behind it, playing by the full PC rules rather
  than degrading to a monster stat block. Note `TurnManager.isMonster` (is it an **enemy**, drives
  end-of-combat) is deliberately separate from `isCombatTarget` (is it a valid 5e target): merging
  them would mean a friendly innkeeper in the room keeps combat from ever ending. `/dndchar` with no
  arguments opens the character switcher, and the DM Panel's first row is the party roster (each
  player's character, real hit points, AC and active conditions). Both ride on one parameterized
  `RosterActionMessage`/`RosterListMessage` pair, registered **at the end** of the list rather than
  in its alphabetical slot — see invariant 1.
- **Fase 2 — SRD content. Imported in four batches; the bestiary is done.** 24 → **87 spells**
  13 → **330 monsters** and 0 → **362 magic items** (145 with resistances, 68 of them conditional). Content comes from
  SRD 5.1 under CC-BY-4.0 — see `ATTRIBUTION.md`, which is a licence obligation, not a courtesy.

  Putting content **after** Fase 0 was load-bearing, not tidiness. The very first batch found two
  engine bugs that would have silently ruined a bulk import: `dice` was mandatory so a
  no-damage spell could not even be written, and the effect only applied `if (finalDamage > 0)` —
  which both blocked condition-only spells *and* imposed conditions on targets who had **passed**
  their save. Batch 4 was likewise gated on conditional resistances: 58 of its 152 monsters need
  them, so importing earlier would have left the whole upper bestiary softer than its stat block.

  **The engine backlog, ordered by how much content each item unlocks.** Every entry comes from a
  converter's "requires engine" list, which is the most valuable output of an import run:
  1. ~~Area shapes: line and cone~~ — **done**. `aoeShape` on the spell schema, geometry in
     `SpellCastManager.inShape`. Spheres originate at the impact point; lines and cones originate
     at the **caster** and run along their view vector, which is the whole reason they could not be
     approximated with a radius: a cone flattened into one hits everything behind the caster,
     including their own party. Unblocked Lightning Bolt, Cone of Cold, Fear, Sunbeam, Prismatic
     Spray and Wind Wall, and corrected Burning Hands, which batch 1 had imported as a sphere.
  2. ~~Walls~~ — **done**, and confirmed to be a different capability rather than another shape:
     a wall is a persistent surface you *place*, and it damages whoever **starts their turn** inside
     it for several rounds. `WallManager` holds them as regions rather than placing real blocks —
     changing the world would mean cleaning it up, and deciding what happens when someone mines it
     or the chunk unloads. It hooks `TurnManager.beginTurn` (the exact moment 5e calls for the save),
     `ConcentrationManager.stopConcentrating` (losing concentration puts the wall out) and combat
     end. Wall geometry measures distance to the axis **horizontally only**, with height checked
     separately: measured in 3D like a line it would be a tube, and someone standing on top of the
     wall would burn without touching it.
  3. ~~Temporary hit points and weapon buffs~~ — **done**. Temp HP is a pool absorbed *before* real
     hit points, so it belongs in `Combatant.takeDamage` as a default method: the rule is identical
     for player, NPC and monster, and writing it three times is what `Combatant` exists to prevent.
     One path can't use it — PvP with a weapon lives inside vanilla's `LivingHurtEvent` and delivers
     damage via `setAmount` — so `absorbWithTemporaryHp` exists for callers that apply damage
     themselves. Weapon buffs (`mode: "buff"`) reuse the smite rider shape but are **not** consumed
     per hit: they last rounds, and making them consumable would silently turn Divine Favor from
     "1 minute" into "one swing". Aid stays out: raising the party's HP *maximum* is a third
     mechanic, neither temp HP nor a buff.
  4. ~~Summoned entities that act on later turns~~ — **done**, and it turned out to be mostly wiring:
     `MonsterRegistry.spawnAt` already spawns stat-blocked entities, `TurnManager.addLateMonster`
     already inserts them mid-encounter and `MonsterActionManager.autoAct` already makes a monster
     attack on its own turn. What was actually missing was **who** it attacks — `autoAct` targets the
     nearest *player*, which for a player's own Spiritual Weapon is exactly backwards. Two traps
     found while wiring it: a summon must **not** count as an enemy or combat never ends while it
     lasts (same split as friendly NPCs, `isMonster` vs `isCombatTarget`), and it must be tagged with
     its owner **before** joining initiative, which is why `spawnAt` grew a `configure` hook.
  5. ~~Multi-round area effects~~ — **done**, and it was a generalisation rather than a new capability:
     `WallManager` already was "a persistent region that damages whoever starts their turn inside, for
     N rounds, tied to concentration". A Moonbeam is that with a sphere instead of a surface. It is now
     `ZoneManager`, the shape is a field, and `inShape` is reused untouched. Persistence became its own
     field (`mode: "zone"`) rather than being inferred from the shape — inferring it is what would stop
     a spherical zone from persisting. `followsCaster` covers Spirit Guardians, which re-centres on its
     caster each round. `aoeShape: "wall"` still implies a zone, so packs written before the field
     existed keep working.
  6. ~~Magic items~~ — **done**, and the import worked differently from every other batch, which is the
     point worth remembering: **the SRD publishes magic items as prose only.** Name, rarity, category
     and a paragraph — there is no bonus field or resistance field to read, so their mechanics cannot
     be derived without inventing them. So an item has two halves, imported by different routes: the
     **reference** half (name, rarity, description) is fully automatic and already useful — a DM can
     look all 362 up and hand them out — and the **mechanical** half (AC, saves, resistances, granted
     spell) is *derived from the prose and then reviewed*, never trusted blind. The derivation demands a
     second-person subject ("**you** gain resistance to fire") to avoid picking up effects that describe
     something other than the wearer, and review still caught three classes of false positive worth
     remembering: potions (their effect comes from *drinking*, so a passive affinity would protect
     whoever carries the bottle — they are now **consumables**, see below), generic family entries
     whose concrete variants are the real items, and
     the Defender sword (+2 AC **in exchange for** attack bonus — applying it unconditionally makes the
     item strictly better than it is, the same kind of lie as inflating a die). **362 imported, 80 mechanical.**
     An item with no mechanics is not broken, it is an item the DM narrates, which is how most magic
     items work at a real table anyway.

     Attunement is not decoration: 5e's limit of 3 is what stops a character hoarding bonuses.
     Non-attunement items apply while held or worn.

     **Curios API is a soft dependency** and solves the slot problem properly: with Curios installed,
     magic items go in real ring/charm slots and attunement items require being *both* attuned **and**
     worn, exactly as 5e says. Without Curios there is nowhere to wear a ring, so attunement stands in
     for both — requiring wear would make those items permanently useless. The isolation is structural,
     not a convention: `CuriosCompat` contains **zero** references to Curios types (verified with
     `javap` — 0 in its constant pool against 20 in `CuriosSlots`), because a class that mentions a
     missing type blows up with `NoClassDefFoundError` the moment it loads. Everything touching the API
     lives in package-private `CuriosSlots`, reached only after `isLoaded()`, and Java's lazy class
     loading does the rest. In the build it is `compileOnly` (plus `runtimeOnly` purely so the dev
     client can test it) and in `mods.toml` it is `mandatory=false`; it never ships inside the jar.

     **Consumables** (`ConsumableManager`) are the capability that let the ~40 potions stop being
     narrative: their effect comes from *using them up*, not from carrying them. It invents almost
     nothing — healing goes through vanilla health, temporary hit points through
     `Combatant.grantTemporaryHp`, and a granted condition through `TurnManager.applyEffect`, which
     already knows how to lift it when the timer runs out. The only genuinely new storage is temporary
     resistances, because there was nowhere to record "resistant to fire for 10 rounds"; they merge with
     every other resistance source by taking the **most protective**, so drinking two of the same potion
     is still resistance, not immunity.

  Silvered/adamantine resistance variants collapse into plain "nonmagical" on purpose: the mod has
  no such materials, and the alternative was discarding the resistance entirely.
- **Fase 3 — the table layer. DONE.** The searchable in-game **compendium** (`/dndcompendium`) is what
  makes 779 imported entries usable: before it, looking a spell up meant remembering its id and typing
  a command. The **journal and handouts turned out to be one thing**, verified before writing: an entry
  with a title, a body and a visibility — a journal note is visible to the party, a handout only to who
  you gave it to, a DM note to nobody else. Splitting them would have duplicated persistence, GUI and
  network message to vary only who may read them.

  Two decisions worth keeping: the text comes from a **vanilla Book and Quill**, because Minecraft
  already ships a multi-line editor and the mod already hands one out — far better than paragraphs
  through a command argument or a one-line text box. And **visibility is filtered server-side**, never
  on the client: shipping entries that the client then hides would leave the DM's secrets in the memory
  of people who must not have them, which is not hiding them. The detail request re-checks
  readability too, because a client can ask for any id.

  Neither piece registered a new network message: the `Browse*` pair (renamed from `Roster*` when it
  widened) already had the right shape — "show me a list only the server knows" — and now carries the
  roster, the compendium and the journal.

- **Fase 4 — progression fidelity. Started.** With the roadmap through Fase 3 closed, the remaining
  gaps are no longer missing features but numbers that never scaled. Two were found by tracing what
  `characterLevel` actually drives, and both were live:

  1. **Proficiency bonus was frozen at +2.** Hit points scaled with level (`maxHitPointsFor`) but
     nothing ever computed proficiency: the sheet defaulted it to the string `"2"` and it stayed there,
     so a level-20 character attacked with a level-1 bonus. It feeds every attack roll, every save DC
     and every proficient check. The tell was in the GUI: the sheet already painted that field amber and
     non-editable — marked as *derived* — while promising a calculation that did not exist. It is now
     `CharacterRules.proficiencyBonusFor` (2/3/4/5/6 by tier), written where max HP is already derived
     because that is the one place that resolves the effective level.
  2. **Cantrips consumed a spell slot.** `Spell.level()` existed from the first import and
     `SpellCastManager` never read it, so a cantrip both required and spent a slot — meaning a caster
     who ran dry lost their *at-will* attack, which is the one thing a cantrip cannot do. 11 of the 87
     imported spells are cantrips, so this was live content, not a hypothetical. All casting routes
     (Grimoire, wands, magic items via `QuickSpellManager`) funnel through one `handleCastRequest`, so
     there was exactly one gate to fix.

  3. **Slots became per spell level (`SpellSlots`).** They were a single pool fixed once by the class
     preset, which broke two rules at once: a 3rd-level spell cost the same as a 1st, and a level-10
     wizard had a level-1 wizard's slots. Now the class decides the whole table and the character level
     scales it — full casters, half casters and Pact Magic each get their own progression.

     Three things worth keeping:

     - **The old scalar fields are still maintained** as the sum of the table. Everything that only
       shows "how many do I have left" — HUD, Grimoire, sheet summary, `/dndsheet` — keeps reading them
       unchanged. Changing those too would have doubled the size of the change without improving
       anything anyone sees.
     - **Half casters are the full table at `ceil(level/2)`**, verified level by level against the SRD
       before relying on it: rounding the other way shifts the whole progression by one level and
       nothing fails.
     - **Spending takes the lowest slot that works.** 5e lets you cast with a higher slot, but burning
       an expensive one while a cheap one would do is throwing the resource away.

     It also unlocked a rule that could not be written before: **Arcane Recovery** is a budget of
     *summed levels*, not a count of slots. With one pool there was no "which level" to give back, so
     it counted slots; now it restores the most expensive slots the budget affords, capped at 5th.

     Both tables are asserted at **every one of the 20 levels** in the self-test, not just at sample
     rows — the first version spot-checked four levels and a deliberately moved digit at an unchecked
     level slipped straight through.

  4. **Upcasting (`upcastDice`).** The gap left open by item 3: a higher slot was spent when no lower
     one was left, but the spell still resolved at its base level, so the expensive slot bought
     nothing. A spell now declares what it gains per level above its own, and 40 of the 87 shipped
     spells scale.

     Three things worth keeping:

     - **The level is chosen on the client, in the message.** Spending a 5th-level slot on Fireball
       for two extra dice is exactly the trade the server cannot infer. `SpellCastMessage` carries the
       chosen level; `0` still means "the lowest that works", which is what every other casting route
       (wands, magic items) sends.
     - **`SpellSlots.spend` returns the level it spent**, and that return is what makes the rule
       writable: the caster asked for a 3rd, but if 3rds were dry it went out on a 4th, and the spell
       has to scale with the slot that was *actually* spent, not the one requested.
     - **`Spell.upcastTo` returns a copy of the spell, not a dice string.** `dice` is read from eight
       places (attack, save, heal, temp HP, weapon buff, zone, summon, twinned), so a new parameter
       would have been eight signatures changed for one idea. Upcasting therefore works in every mode,
       including ones not written yet. The copy also carries the level in its name, because that name
       goes straight to chat — without it, two Fireballs rolling different damage read as a bug.

     Half of the SRD does not scale at all (Meteor Swarm, Finger of Death, Power Word): `upcastDice`
     is absent there, and pretending otherwise would break them.

     **Still simplified:** scaling is linear per level. Spiritual Weapon (+1d8 every *two* levels)
     therefore ships without upcasting rather than with the wrong number.

  **Found while doing item 4:** the bulk content packs existed **twice** — once under
  `test/dndsheets/<type>/` and once in `src/main/resources/dndsheets/defaults/` — and the self-test
  read the `test/` copy. The two had already drifted (five monsters had `damageAffinities` only in the
  shipped copy), so the suite was blessing a file no player ever loads. It surfaced because adding
  `upcastDice` to the shipped `spells.json` changed nothing in the check. The duplicates are deleted;
  `test/dndsheets/` now holds only the one hand-written `ejemplo.json` per type, and the self-test
  reads the shipped packs.

**What already beats the competition and should be leaned on, not rebuilt:** the 3D map, real
line of sight, real lighting and real movement are *native*. That is literally what Roll20 and
Foundry emulate with polygons and fog layers. Do not build a fog-of-war system; do not build a
token layer. Compete where Minecraft already wins.

## Where to go next

- Touching combat, damage, AC, hit points or conditions → go through `Combatant`. If you find
  yourself writing `instanceof Player` to decide how to read a stat, that branch already exists
  behind the interface.
- Adding a screen or touching layout → read `GUI_REFERENCE.md` first, reuse `ListPickerScreen`/`SmallFormScreen`.
- Adding a content type or command → check whether it fits the `ContentType`/`NamedRegistry`/`JsonRegistryLoader` pattern before writing a parallel one.
- Touching dungeons → read `DUNGEON_GUIDE.md`'s "regla de oro" and bugs #7-#11 above before assuming `/reload` or a captured piece is trustworthy without checking.
- Anything DM-facing that hands out an item/teaches a spell/spawns a monster → there's almost certainly an existing `GiveableItem`-style pattern or DM Panel row to extend rather than a new one-off.
