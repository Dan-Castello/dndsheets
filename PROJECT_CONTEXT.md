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
  dndsheets/defaults/        The mod's own content packs. Written to <world>/dndsheets/<type>/
                             mod_defaults.json on EVERY server start (see ContentDefaults).

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

**`DndPaths`** owns every `<world>/dndsheets/<type>/` folder, creates them on server start, refreshes the mod's own pack (`ContentDefaults`), and auto-loads every `.json` file found — no command needed for the common case, the `/dnd... load` commands exist for *hot*-reloading a single file without a restart. **Load order is `mod_defaults.json` first, then everything else by name**, and `NamedRegistry.register` overwrites by id, so whatever the DM writes wins over what the mod ships. That order is what makes rewriting `mod_defaults.json` on every start safe.

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
- **Fase 1 — character identity. DONE, GUI included.** Characters keyed independently of players;
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

     **Reported broken after shipping, and the two causes were both bigger than the feature:**

     - **The world's content pack was frozen forever.** Defaults were seeded *once*, and only into an
       empty folder, so the copy in a world froze at the version it was created with. New spells,
       the resistances added to five monsters, and now upcasting never reached an existing game —
       the symptom was that raising the slot level changed nothing, because the server was still
       loading a pack written before the rule existed. The mod's pack now has a reserved name
       (`mod_defaults.json`, `ContentDefaults`) and is rewritten every start; DM files load after it
       and override by id, which is what makes rewriting safe. The old seeded `<type>.json` is
       renamed to `.old` once, never deleted.
     - **The slot patch only carried the total.** `sendSlotsUpdate` patched `spellSlotsCurrent`
       alone, so the client kept a stale `spellSlotsByLevel`: the Grimoire's per-level columns never
       moved and the level picker offered slots already spent. A short patch has to carry
       *everything* that changed, and since slots became per-level that is two fields
       (`SpellSlots.clientPatch`).

  5. **Cantrips scale with character level (`Spell.atCasterLevel`).** The other half of item 4: a
     cantrip cannot be upcast — it spends no slot — so 5e grows it with the caster instead, one extra
     die at character levels 5, 11 and 17. Without it a caster's *at-will* attack stayed frozen at
     level-1 damage while everything around it scaled: the same defect as the frozen proficiency
     bonus, in the attack a caster uses more than any other. A level-10 wizard's Fire Bolt is 2d10.

     Nothing is declared in the JSON for this. The progression is identical for every damage cantrip
     in the SRD, so it is derived from the level rather than repeated eleven times by hand — and a
     field would have been eleven chances to typo the same number.

     **Simplified:** Eldritch Blast gains *beams* (separate attack rolls), not dice on one roll. It
     scales as extra dice here, which is the right damage on a hit but one roll instead of several.

  6. **The three remaining frozen class numbers.** Each was a constant with a comment admitting it:
     `DAMAGE_BONUS = 2`, `DIE = "1d6"`, `DICE = "2d8"`. Same defect as the proficiency bonus, three
     more times, and in each case the number *is* the class's progression:

     - **Rage damage** +2 / +3 (9th) / +4 (16th). A barbarian has the fewest buttons of any class;
       frozen, a 20th-level one hit exactly like a 1st-level one apart from the weapon dice.
     - **Bardic Inspiration die** d6 / d8 (5th) / d10 (10th) / d12 (15th), taken from the **bard's**
       level, not the target's — the one inspiring sets the quality of the die even though someone
       else rolls it.
     - **Divine Smite** 2d8 +1d8 per slot level above 1st, capped at 5d8. This one was blocked, not
       forgotten: the comment said the slot pool was flat so there was no level to scale by. Once
       `SpellSlots.spend` returned the level it spent, the rule wrote itself — and it needs no level
       picker, because taking the lowest slot means the smite grows exactly when the paladin has run
       out of cheap slots, which is when a table would spend an expensive one anyway.

     The two level tables live in `CharacterRules` next to `proficiencyBonusFor`, which is where
     level-driven numbers belong and the reason they are testable without touching Minecraft.

  7. **Creature types (`CreatureType`).** The blocker named in item 6, removed: all 330 shipped
     monsters now declare one of 5e's fourteen types, and Divine Smite's +1d8 against undead and
     fiends works.

     - **An enum, not a free string.** The set has been closed since 2014 and nobody extends it; with
       a string, a DM writing `"no muerto"` without the hyphen would silently invent a fifteenth type
       that matches no rule. `parse` normalizes accents, case, hyphens and the English names, because
       someone writing `"Undead"` means the same thing.
     - **An unknown type is not an error.** A mob from another mod, a generic NPC or a pack written
       before the field keep working exactly as before; all they lose is access to type-gated rules,
       which is correct — no rule should fire on a guess. The self-test still *requires* a type on
       every shipped monster, because the realistic failure is forgetting one while extending the
       bestiary, not misspelling it.
     - **The smite's extra die is added after the 5d8 cap**, deliberately: in 5e that cap belongs to
       the slot-level scaling and the undead die is separate, so 6d8 is right, not an overflow.

     The classification was checked by what a human would get wrong, not by what is obvious: a blink
     dog is fey, an azer and a gargoyle are elementals, a flesh golem is a construct and not undead,
     an otyugh is an aberration, a centaur is a monstrosity, a green hag is fey while a night hag is
     a fiend, a will-o'-wisp is undead, and a lycanthrope is humanoid *even in animal form* — the one
     case where classifying by the shape gives the opposite of the right answer.

  8. **Type-gated spell targeting (`affectsTypes` / `immuneTypes`).** The rule item 7 unlocked. Ten
     shipped spells restrict who they touch; before this they all had the name of the rule without
     the rule — Hold Person worked on a skeleton, Blight killed undead.

     - **Two fields, not one.** Both shapes exist in the SRD and each written as the other is
       unreadable: Hold Person is "a humanoid" (a whitelist of one), Hold Monster is "any creature
       except an undead" (a blacklist of one, which as a whitelist would be thirteen).
     - **A player counts as humanoid** in `MonsterRegistry.typeOf`. Without it Hold Person would not
       work on a PC — the spell's most common use at a table — because a player has no stat block to
       read a type from. Every playable SRD race is humanoid.
     - **An unknown type is never filtered out.** The restriction applies only when the type is
       actually known, so a mob from another mod keeps behaving as it always did instead of turning
       immune to half the spell list for want of a label. Same rule as item 7 in the other direction:
       nothing fires — or gets blocked — on a guess.
     - **A wrong-type target costs no slot**, like having no target at all. Charging for it would
       punish the player for a rule the mod knows and the screen never showed; at a table the DM says
       "that isn't a humanoid" before anything is spent. In an *area*, the immune creature is simply
       dropped from the list and the blast rolls on.

  9. **Turn Undead — the cleric finally has a button.** Every other class with a preset had a
     resource item; a player picking cleric got a spell list and nothing else. Undead within 6 blocks
     roll a Wisdom save against the cleric's own spell DC and the failures are *frightened* for ten
     rounds, once per rest. It could not be written before item 7 — a "turn undead" that cannot tell
     what an undead is would just shove everyone.

     **Not implemented:** Destroy Undead (the 5th-level upgrade that annihilates low-CR undead). Stat
     blocks here carry no challenge rating, and substituting hit points would vanish a legendary
     undead with few HP while sparing a weak one with many — the threshold would be measuring
     something else. It needs a new field, not an approximation.

  10. **The attack that starts combat no longer vanishes.** Reported from play: "attacks start turn
      mode, but the opening damage is lost." Auto-start created the encounter, rolled initiative, and
      then `tryAct` rejected the very attack that triggered it whenever the attacker did not win
      their own roll. The same click either worked or disappeared depending on a d20 nobody had asked
      to roll — and before auto-start existed that swing resolved in full, so the convenience of not
      typing `/dndturns start` was quietly costing an action.

      The attacker now **opens the turn order** (`startAt(..., initiator)`). Opening it rather than
      resolving "for free" outside it is what keeps every other rule standing: the action is spent,
      the turn ends, nobody swings twice. Whoever attacked first *did* act first, which is what
      initiative is trying to measure. The die is not faked — the announced score is the one actually
      rolled, the entry is just moved — and a chat line says who opened, because otherwise someone
      appearing first with a 7 reads as a broken sort. `/dndturns start` passes no initiator, so a
      DM-run encounter is pure initiative exactly as before. The mirror case is wired too: a monster
      that ambushes a player opens the order, since its blow has already landed by then.

      `moveToFront` is extracted and package-private so the ordering is checkable without a server.
      The half that matters is that **everyone else keeps their relative order** — swapping with the
      current first, the implementation that writes itself, scrambles the initiative of the rest.

  11. **Cover (`Cover`).** The rule this mod was best placed in the world to have and did not have.
      Roll20 and Foundry compute visibility with polygons and fog layers *in order to simulate* a 3D
      space; here the 3D space is the game. The half-height stone wall is already there with its real
      geometry — and until now shooting someone crouched behind it cost exactly what shooting someone
      standing in the open cost. Half cover is +2, three-quarters +5, total cover cannot be targeted.

      - **Five rays, five points inside the body.** Points are inset from the bounding box rather than
        placed at its corners, so the floor under the target's feet does not count as a parapet.
      - **The two side points are perpendicular to the line of fire**, not on world axes. With
        axis-aligned corners, shooting diagonally measures the wrong width, and a wall corner grants
        half cover or none depending on which way the map happens to face.
      - **Applies to weapon attacks, spell attacks, and Dexterity saves only** — a parapet helps you
        dodge, it does not help you resist a poison or a suggestion. On saves it is subtracted from
        the DC rather than added to the roll: same margin, and the target may have no sheet to write
        a bonus on.
      - **Shield still decides on its own number**: cover is subtracted from the attack roll passed to
        `reactiveArmorClass` instead of added to the AC, so the reaction never learns about parapets.
      - **`TOTAL.bonus()` is 5, not infinity.** One route reaches it anyway — an arrow that *already*
        hit — and adding an infinity there would make impossible a blow the world just permitted.

      The threshold table is pure and pinned in the self-test. Worth noting how: the `<=`→`<`
      mutation survived the five-sample cases and was caught only by the four-sample one, which is
      the case that proves the rule is written in *fractions of the body* rather than in a count of
      rays.

  12. **The turn actions: Dodge, Dash, Disengage (`TurnActionManager`).** A turn could only be spent
      attacking or casting, which made turn mode a "whose turn is it to hit" queue. These three are
      what make a turn a *decision*: a cornered character on low HP rarely wants to attack — they
      want out without eating an opportunity attack, or to hunker down and survive the round.

      None of them needed new rules. All three plug into machinery that already existed and was doing
      nothing else: the movement budget in `MovementAnchorTracker`, the reach registry in
      `OpportunityAttackTracker`, and advantage/disadvantage. They cost the action through the same
      `tryAct`, so you cannot dodge *and* attack, and the turn ends by itself as with any other action.

      One map keyed by entity, not three sets: all three expire at the same moment — the start of that
      creature's next turn, which is literally what Dodge says in 5e — so separate sets would only be
      three things to remember to clear instead of one.

  13. **Help (`HelpActionManager`), the fourth action.** Right-click an ally: their next attack has
      advantage, and it costs your action like the other three. It lives apart from the other three
      precisely because it is the only one that needs someone to *point at*, so its home is an
      entity-interact item rather than a menu row — the same shape of problem Bardic Inspiration has
      and already solved that way.

      No new mechanism: the advantage is written to `nextAttackAdvantage`, the one-shot sheet flag
      `CombatManager.consumeAdvantage` has always spent on the next attack roll — weapon, spell, or
      the sheet's own button. What was needed was using the flag `/dndsheet advantage` had been using
      all along, and patching it to the client so the ally sees it without reopening the sheet.

  14. **One attack resolution instead of two (`AttackRules`).** The three defects above were all the
      same defect: the hit-resolution logic was written *twice*, once for "a player attacks" and once
      for "a monster attacks", and the copies drifted — always in the same direction, with the new
      rule landing on the player path. Fixing them one at a time is exactly the signal that the fix
      was not that. `AttackRules` now owns advantage-from-target, cover, effective AC after defensive
      reactions, hit, and auto-crit; both paths call it. The next rule reaches both by construction.

      What deliberately stays split is what genuinely differs: how the roll is assembled (a player
      adds ability and proficiency from a sheet, a monster a fixed modifier) and how damage is built
      (weapons, sneak attack, smite, buffs vs. one stat-block die). Those are real differences, not
      duplication.

      **The refactor nearly introduced a rules bug, and it is worth recording.** `combineAdvantage`
      collapses "at least one advantage and at least one disadvantage" to normal — correct 5e, and
      exactly why it **cannot be nested**: a normal produced by two sources cancelling is
      indistinguishable from no source at all, so a later combination lets the attacker's advantage
      win alone. Target prone (advantage in melee) *and* dodging (disadvantage), attacked by someone
      with pending advantage: the answer is normal, and the first version of the extraction returned
      advantage. So the attacker's sources are passed *into* `advantageAgainst` as varargs and pooled
      in a single call.

      That near-miss also produced the sharper check. Three behavioural assertions were written
      first, and re-nesting the combination **passed all three** — the case that breaks needs both
      cancelling sources on the *target* side, which needs turn state that does not exist outside the
      game. So the invariant is now held structurally: `advantageAgainst` must contain exactly one
      `combineAdvantage(` call.

  15. **And one save resolution instead of two (`SaveRules`).** The other half of item 14, done for
      the same reason and before it could cost another bug: cover, the real DC, whether the save
      succeeds, and the final damage were written once for "a player casts" and once for "a monster
      casts". They had already drifted — cover counted only on the player side, so sheltering from a
      dragon's breath did nothing. What stays split is what genuinely differs: where the DC comes
      from (a monster carries it in its stat block, a player computes it from a sheet), how it is
      announced, and which condition hangs off a failure.

      Both paths now also apply spell damage through one implementation, so affinities, temporary hit
      points, concentration and death are resolved in a single place.

      One claim in the first draft of that class was **wrong and got corrected before committing**:
      it said the monster path made a mod's mob immune to monster spells because the tolerant "roll a
      bare d20" branch existed only on the player side. Tracing it, a monster's spell only ever
      targets a player, so that branch was unreachable and the behaviour was identical. The rule is
      unified anyway — but the comment now says what is true rather than what made a better story.

  16. **Levelling up (`LevelUpManager`).** Almost everything a level grants was already *derived*
      from `characterLevel` — max hit points, proficiency, spell slots, the Sneak Attack and Martial
      Arts dice. What was missing was the one thing that cannot be derived because it is a
      **decision**: the Ability Score Improvement at 4, 8, 12, 16 and 19. Without it a level-20
      character fought with a level-1 character's ability scores, which is the most visible number
      there is.

      And it was missing the *telling*. `/dndsheet setlevel 5` moved half the sheet in silence.
      Levelling is one of the few moments a table celebrates and it was the least visible one; now
      `/dndsheet levelup` announces the level and only the lines that actually changed — a list where
      three of four rows say "unchanged" buries the one that did.

      - **Pending improvements live on the sheet**, not in memory, so they survive closing the
        screen, disconnecting and a server restart — and so jumping from level 1 to 8 grants the two
        that are due instead of losing one. It is also what makes the client screen safe: the server
        applies an improvement only if one was actually owed.
      - **Granted from the single choke point** that changes level (`SheetCommand.applyLevel`), so
        the command and the DM Panel both grant them without a second copy of the rule.
      - **The DM levels you up; the player spends the improvement.** Whose decision each one is.
      - **Constitution re-derives max HP.** Without it, raising CON gave the new modifier to
        everything except the thing Constitution exists to give.

      The screen shows each ability's current score *and* what it would become, because the decision
      is not made on the ability's name: it is made on whether the modifier crosses an even number,
      and 15→16 does while 16→17 does not.

  17. **Deleting a character.** Found in play: there was no way to remove or reset one, so a testing
      session left characters that could only be switched between. `/dndchar delete <id>`, plus a
      "Borrar un personaje..." toggle on the character screen so it does not require knowing an id.

      - **It renames rather than deletes.** The file becomes `<id>.json.deleted` — no longer ending
        in `.json`, so it is never loaded again, but recoverable by hand. Deleting a character is the
        only action in the mod that destroys hours of play with no undo, and a copy costs one line.
        That is also why the screen guards it with a mode toggle rather than a per-row dialog: the
        protection needed is against a stray click, not a question answered yes without reading.
      - **Nobody is ever left with zero characters.** If the deleted one was active, another of
        theirs is put on; if none remain, a blank sheet is created immediately. That branch is what
        makes "delete" also mean "reset" for someone with a single character, without needing two
        concepts — and without it, hitting zero leaves `getServerSheet` returning null until the
        player reconnects, which half a dozen combat paths skip over in silence.
      - **A DM can delete NPC sheets, never another player's character.** Permission opens the door
        to ownerless sheets only.

  **Found by breaking it:** adding the chat strings for this, I put unescaped quotes inside a
  translation (`"Personaje "%1$s" borrado"`) and **the build stayed green**. Nothing reads the
  language files until the game starts, and then the failure is not an error but raw keys on screen —
  and a key present in one language and missing in the other fails the same silent way for half the
  users. `checkLanguageFiles` now parses every language file and asserts they carry the same key set.

  18. **Switching characters left the open sheet showing the old one — and then overwrote the new
      one with it.** Reported from play. The sheet screen fills its widgets in `init()` only, and
      `SheetClientMessage` did nothing but replace the cached JSON, so a full sheet arriving while
      the screen was open never reached the fields. That alone is a refresh bug; the damage is worse,
      because almost every interaction on that screen calls `CharacterSheetSaveProcedure`, so the
      first roll or tab click after switching wrote the *previous* character's values on top of the
      new one. It was data loss wearing a refresh bug's clothes.

      `CharacterSheetScreen.refreshIfOpen()` now runs whenever a **full** sheet arrives — switching,
      resting, applying a preset, spending an improvement. Single-field patches
      (`SheetFieldUpdateMessage`) deliberately do *not* trigger it: those land mid-combat and would
      repaint over whatever the player is typing.

  19. **Commands take names, not ids.** Character ids are derived from the player's UUID
      (`380df991-…-2`), so asking someone to type one to switch character is asking them to copy a
      string that means nothing to them. `/dndchar switch` and `/dndchar delete` now take a name
      (`greedyString`, so spaces need no quotes) and autocomplete by name with the id as the
      suggestion tooltip — suggesting ids was autocompleting the one thing the player does not
      recognise.

      The resolution order is deliberate and pinned: **exact id, exact name, then unique prefix**. A
      character named after another's id must still be reachable, and an exact name can never lose to
      someone else's prefix — typing "Ana" with an Ana and an Anabel present is Ana, not an ambiguity
      error. Ambiguous is treated as "no": choosing for the player would choose wrong half the time.
      The rule is pure and lives in `CharacterRules`, so all of that is checkable without a game.

  20. **Two characters with the same name were unreachable.** Reported from play with two sheets both
      called "Test": the completion offered "Test" twice — two identical entries — and picking either
      failed with "the name matches several". **The autocomplete was offering an option the command
      then refused.** A suggestion that does not work is worse than no suggestion.

      Rejecting ambiguity is still right; the mistake was leaving no way *out* of it. Suggestions now
      carry the id **only when another candidate shares the name** (`Test [380df…-2]`), so every
      suggestion is distinct and resolvable, and the id stays out of sight the rest of the time. The
      resolver accepts that form, and a character genuinely named `Bruno [el Bravo]` still matches by
      name because the bracket content has to be a real id.

      The check that matters is the round trip: for every candidate, resolving its own suggestion
      label must return that candidate. That is the whole bug in one assertion. `/dndchar list` and
      the character screen use the same label, so what you read is exactly what you type.

      Worth recording what was *not* wrong: the deleted character had not come back. The `.deleted`
      file was on disk as intended, and the second sheet was the blank one that deletion creates so
      nobody is left with zero — it just happened to get renamed "Test" too.

  21. **Deleting the character you were wearing resurrected it.** Reported from play: "deleting a
      character does not clear the sheet, so it gets recreated automatically when you press H."

      The delete was fine; **the question afterwards was wrong**. `ensureHasCharacter` asked
      `getServerSheet(uuid) != null`, and `activeCharacterOf` *falls back to the player's own UUID*
      when nothing is bound — that fallback exists so sheets written before characters existed keep
      working. So the check answered "yes, they still have a character" merely because a file with
      that id existed, even with nothing worn. Nothing was sent to the client, the sheet still open
      on H was the deleted one, and the next save wrote it back to disk under the fallback id. The
      character came back.

      The question is now asked against the **explicit binding** and pinned in `CharacterRules`, and
      the player is re-bound (which also pushes the sheet) rather than merely checked. Together with
      item 18's `refreshIfOpen`, the open screen repaints instead of holding a sheet that no longer
      exists — the two halves of the same failure, one on each side of the wire.

      The lesson worth keeping: a **compatibility fallback that invents a plausible answer** is fine
      for reading and dangerous for deciding. `activeCharacterOf` cannot distinguish "wearing this
      one" from "wearing none", so nothing that branches on existence may call it.

  22. **Sweep for the same failure shape.** After item 21, a deliberate pass over every
      compatibility fallback in the codebase, asking one question of each: *does anything branch on
      the invented answer?* Reading a plausible default is fine. Deciding on one is the bug.

      **Found live — levelling counted from Minecraft XP.** `characterLevelOf(sheet, player)` falls
      back to the player's XP level while no character level is set. That fallback is a deliberate
      feature for *showing* a number, and two places used it to *decide* one:
      `LevelUpManager.levelUp` and the improvement grant in `SheetCommand.applyLevel`. So a player
      who had mined to XP level 25 could never level up ("you are already 20"), and one at XP 7
      jumped straight to 8 with an Ability Score Improvement for it — earned by mining. Both now read
      the explicit `characterLevel`, which is 1 for a character nobody has levelled.

      **Checked and correct, worth recording so nobody re-litigates them:**
      - `allEnemiesDefeated` treats an entity it cannot find as *still standing*, because a null can
        be an unloaded chunk rather than a death. That is the right direction for an invented answer:
        it fails toward **doing nothing** instead of toward a plausible action.
      - `MonsterRegistry.typeOf` → `UNKNOWN`, and no rule fires or is blocked on it.
      - Rage bonus, Bardic die, Second Wind, Arcane Recovery budget, weapon resolution and cantrip
        scaling all use the XP fallback, but they *scale a number* rather than branch. A level-7-by-XP
        character getting level-7 numbers is the documented behaviour of the mirror.
      - `currentHpOf` returns 0 for an untagged entity, which would read as "defeated" — unreachable,
        because `Combatant.of` only builds a `MonsterCombatant` when the same tag produced a stat
        block. Noted rather than changed: the guard is upstream, not in the function.

      **Found and softened:** deleting an NPC leaves its body in the world, and `Combatant.of`
      silently degrades it to a vanilla mob — it stands there, hittable, playing by no rules. The
      delete now says so instead of letting the DM discover it mid-combat.

      The rule, now written down: *a fallback may invent an answer for display; nothing that branches
      on existence, identity or permission may call one.* Where the deciding path cannot be reached
      from the self-test, hold it structurally — `checkAbilityImprovements` asserts neither decision
      site calls the XP-shaped overload, because the behavioural assertions pass with the bad version.

  23. **Creating a character from the screen** — the last piece of character management that still
      required knowing a command. `CharacterListScreen` grows a "+ Personaje nuevo..." row above the
      delete toggle (the common action nearest to hand, the destructive one further away), opening a
      one-field form. It asks only for the name: class and abilities come from the preset chosen
      afterwards from the sheet, and asking here would ask twice for the same thing with worse
      information. No new message — `BrowseActionMessage`'s text field carries the name (invariant 3).

  **Found while doing it, and it was my own miss:** `BrowseActionMessage.Action` gained `DELETE` two
  commits earlier and `PROTOCOL_VERSION` was never bumped. Appending to an enum does not renumber the
  existing constants, but a *new* client sending that ordinal to an old server crashes on read — which
  is precisely what the handshake exists to prevent. Version bumped to 6 (covering `DELETE` and
  `CREATE`), and `checkNetworkShape` now counts the registered messages plus every constant of every
  enum that crosses the wire and compares it against a number written by hand next to
  `PROTOCOL_VERSION`. It cannot stop the mistake; it makes bumping — or deciding not to — deliberate
  instead of forgotten. Invariants 1 and 2 are the two that have cost the most debugging here, and
  both fail in total silence: everything compiles, the handshake passes, the desync comes later.

  24. **Legendary Resistance.** The first rule that the `SaveRules` unification paid for directly: a
      boss that fails a save may decide it did not, three times a day. It is one hook in the single
      place that decides whether a save succeeds — before the unification it would have been written
      twice, and the monster path would have been the one left behind, as everything else was.

      - **The uses belong to the individual, not the species**, so they live in the entity's own NBT
        beside its hit points; two adult dragons of the same id spend theirs separately.
      - **An untagged monster reports its full count**, not zero: the default for a boss summoned
        before the rule existed is "has them all", not "has none".
      - **The chat says it and says how many are left.** A boss that fails and takes nothing reads as
        a bug otherwise, and the count is exactly what makes the rule interesting to play against —
        the party is spending the saves as a resource.

      Annotated on the 30 SRD creatures that have it: every adult and ancient dragon, plus lich,
      tarrasque, mummy lord, both sphinxes, kraken, solar, planetar, balor and pit fiend. **Young
      dragons and wyrmlings do not have it**, which is the mistake to make when annotating 43 dragons
      and the one that turns a mid-level encounter into a wall; it is asserted in both directions.

      **Honest note on that check:** the first mutation run reported green because the edit never
      applied — it missed the accent in "Dragón". Re-run with the right text, it fails as it should.
      The runtime hook itself (spending a use) has no self-test: it needs a world entity. Only the
      data and the stat-block plumbing are covered.

  25. **Two reports from play, both on the character sheet.**

      **The empty-attacks notice was drawn over the ability scores.** The `case ATTACKS` block sat in
      `render()`, which runs in *screen* coordinates, while every grid constant it used
      (`PANEL_X`, `ATTACK_TOP`, `SEC1_Y`) is in *sheet* coordinates. Without the `leftPos`/`topPos`
      translation the text landed in the screen's top-left corner — on top of the side panel. The
      sheet draws in two coordinate spaces and nothing marked the boundary; `renderLabels` already
      runs translated, which is where it belongs and where it now is.

      `checkSheetCoordinateSpaces` asserts `render()` never names a grid constant. This bug is
      invisible to the compiler and leaves no trace in the log — it can only be *seen*, which is
      exactly the kind that needs a mechanical guard.

      **Characters are reachable from the sheet.** A "Personajes" button opens `CharacterListScreen`,
      which is where switching, creating and deleting already live. One door rather than three
      buttons: the bottom row was already full at three, four fit only by narrowing them (80 → 72,
      step 86, ending exactly on `PANEL_RIGHT`), and the list is where you can *see* which character
      you are wearing — half the decision when switching.

  26. **Legendary Actions.** The other half of the boss rules, and the one that changes how a fight
      *feels*: a legendary creature acts at the **end of another creature's turn**, so a dragon
      against four players stops being an exchange where the party swings four times per one of its.
      Hooked into `advance` at the exact moment 5e names, before the index moves so "whoever just
      played" is still who it is; the budget refills at the start of its own turn.

      **Deliberately reduced to "one attack".** In the SRD each boss has its own list with different
      costs (Attack for 1, Wing Attack for 2, Detect for 1). Here a legendary action is *one of its
      own attacks*, cost 1, until the round's budget runs out — the one nearly all of them share and
      the one that decides the fight. The exotic ones have nothing to point at in this mod anyway
      (move without provoking, detect, reshape terrain). If nobody is in range the use is **refunded**
      rather than burnt: spending it on nobody would punish the boss for where the party stands.

  27. **Three defects from one play log**, and the first was mine from two days earlier.

      **The HUD showed "Conjuros: 4/2"** — more available than the maximum. `SpellSlots.clientPatch`
      sent the per-level table and the current total but **not the maximum**, and the maximum is
      *derived* (class and level, recomputed server-side every time the sheet is saved). So it moved
      behind the client's back and the client kept a stale one forever. The patch now carries all
      four fields, and the check asserts current ≤ max on what the client rebuilds — the impossible
      state is the thing worth pinning, not the field list.

      **"You have no spell slots left" while holding four.** True and useless: they were level-1
      slots and Flaming Sphere is level 2. The rule worked exactly right; the message contradicted
      the HUD. It now names the level required.

      **A WARN per summon.** Re-registering the summon's stat block on every cast is deliberate — it
      picks up edits to the spell's JSON — but it went through the path that warns about a collision,
      so every Flaming Sphere logged a warning about something working correctly. `NamedRegistry`
      grows a `replace` for overwriting on purpose. Noise in a log is not free: it is how real
      warnings stop being read.

      And a fourth, cosmetic: applying a preset no longer says "close and reopen your sheet". Since
      item 18 the open sheet repaints itself, so the message was asking for a step that no longer
      exists.

  28. **Correcting my own boss data.** Two problems with the annotation from items 24 and 26, both
      found by re-reading it rather than by a report.

      **Legendary Actions had no data at all.** The field, the budget, the turn hook and the chat
      line all shipped, and not one monster declared the number — a feature that could not fire.
      Annotated on the 28 that have it.

      **Legendary Resistance was over-applied.** I had put it on the balor, the pit fiend, the
      planetar and the solar. Those have **Magic Resistance** — advantage on saves against spells —
      which is a different rule that this mod does not implement. Removed, leaving 26.

      The two lists are *not* the same list, and treating them as one is exactly the mistake I made:
      the **vampire has legendary actions and no legendary resistance**, and the solar the same. The
      check now asserts both directions of that mismatch, because a list that agrees with itself is
      indistinguishable from a list copied from the other one.

      Honest caveat: this is recall, not transcription. The dragons, lich, tarrasque, mummy lord,
      sphinxes and kraken I am confident about; anyone who cares about exactness should diff the
      annotated set against the SRD document. The field is optional and defaults to 0, so being
      conservative costs a boss some teeth rather than inventing rules it does not have.

  29. **Multiattack.** An adult dragon made **one** attack per turn where 5e gives it three (bite and
      two claws) — a third of its threat, and the gap that made the boss work of items 24 and 26 land
      softer than it should. One integer on the stat block, defaulting to 1, which is exactly how the
      whole bestiary behaved before.

      - **The attack is picked at random per swing**, so a monster with a bite and a claw does not
        repeat the same one three times.
      - **Checked between swings**: if the first blow kills the target, the rest do not happen. A
        corpse does not take two more attacks, and the chat would have announced them.
      - **Clamped to 6.** Not a 5e rule — a firebreak: an absurd number in a DM's JSON turns one turn
        into an unreadable burst of chat lines.
      - It is a **third axis**, independent of the two legendary ones: a young dragon multiattacks and
        is not legendary, which is the case the check uses, because a test where all three flags move
        together cannot tell them apart.

      Annotated only where the number is unambiguous and I am confident: adult and ancient dragons at
      3, young dragons, owlbear, ettin and hill giant at 2. Everything else stays at 1 and a DM can
      set it in JSON. Same reasoning as item 28 — falling short costs a monster some threat, inventing
      gives it threat it does not have, and the second is worse.

  30. **"Incapacitated" only worked one way out of three.** Found by reviewing the interactions of
      the last few features rather than from a report — the question was "what can a legendary
      creature do that it should not?", and the answer turned out to be older and wider than the
      boss work.

      5e says an incapacitated creature can take **no actions and no reactions**. The check lived in
      `tryAct` alone. There are three ways to do something here — action, reaction, legendary action —
      and the other two behaved as though the rule did not exist: a **paralysed monster still made
      opportunity attacks**, a **stunned player could still cast Shield and Counterspell**, and a
      sleeping dragon would still have handed out three attacks a round. That is half of what
      paralysed, stunned, petrified and unconscious *mean*.

      One `isIncapacitated` now answers for all three. The check pins both halves: which conditions
      count (paralysed/stunned/petrified/unconscious yes; poisoned, prone and blinded no — confusing
      those turns a hard condition into an inconvenience or the reverse), and structurally that all
      three gates consult it, since the paths themselves need world entities.

  31. **Two more from the same question**, asked of the other consequences a condition has. Both are
      the mod's own monsters missing a rule that players already obeyed.

      **A restrained monster walked out of the spell restraining it.** `cannotMove` was checked only
      in `MovementAnchorTracker`, which governs players and other mods' mobs. This mod's own monsters
      move somewhere else entirely — a teleport in `MonsterActionManager` — so the rule never reached
      them, and a monster inside an Entangle simply strolled off. That is precisely what the spell
      exists to prevent. It is worth noting *why* this one survived the previous sweep: grappled and
      restrained stop movement **without** incapacitating, so fixing "incapacitated can't act" left
      them untouched. Two rules that look like one.

      **A monster attacked without its own conditions.** `ownAttackAdvantage` — invisible attacks
      with advantage, frightened with disadvantage — was passed only from the player's side. Same
      asymmetry as `AttackRules` fixed for the *target*, one step earlier in the same roll: this one
      is about the attacker.

  32. **A player could not see their own conditions.** Asked the next question of the same shape —
      not "is the rule applied everywhere", but "**can the person it happens to see it?**" — and this
      one had been true since conditions were added.

      Conditions were visible **only through the DM Panel**. Half a dozen engine rules hang off them:
      paralysed means your clicks do nothing, restrained means you cannot walk, frightened means
      disadvantage you never see rolled. From the player's chair that is indistinguishable from a
      broken mod — which is, word for word, several of the reports that arrived during this work.

      They now show on the HUD, in red, above everything else. Two halves: the client's copy had to
      *arrive*, so the single write point (`SheetBacked.setConditionSources`, already documented as
      the only one) now sends a short patch to the player, and the HUD strips the `@id` that carries
      each condition's source — the person suffering it cares that they are frightened, not about the
      entity number that frightened them.

      This is the same class as items 18 and 21, one layer up: not stale client state, but state that
      was never shown at all. A rule the player cannot see is a rule they will report as a bug.

  33. **What the player is *holding* was invisible too.** Same question as item 32, asked of the
      one-shot state that decides the next roll: concentration, a Bardic Inspiration die, an armed
      Divine Smite, a pending advantage. **None of the four managers told the client anything.**

      So you received an Inspiration die and had no way to know; you armed a Smite and could not tell
      three turns later whether it was still armed; and concentration — one of the most consulted
      things at a real table — existed only as a chat line that scrolls away. A modifier you cannot
      see cannot be played around: you find out afterwards, in the result.

      All four now push a short patch and show on the HUD. Concentration gains a sheet field purely
      so it can travel the pipe that already exists — not because the sheet needs to remember it, as
      no concentration survives a restart anyway. Smite is cleared in the same patch that already
      cleared advantage and inspiration, so the HUD does not keep advertising a resource that was
      spent on the swing that just happened.

      Worth recording what was **already right**: `SheetServerMessage` merges only a whitelist of
      player-editable keys, so none of these flags could ever be wiped by a client saving a stale
      sheet. That defence is why this was only an invisibility bug and not a data-loss one.

  **The same asymmetry, twice more:** a monster's *spell* did not apply cover to its Dexterity save —
  sheltering from a dragon's breath is the textbook case, and it worked only when a player was the
  caster. And two chat lines announced the target by Minecraft account name instead of character name.
  Both fixed; the source-scan check now covers `resolveSpell` too. Worth recording how the check
  first failed: it asserted the *absence* of `target.getName()` and tripped over the comment
  explaining that very fix, so it now asserts the presence of the right call instead.

  **Found while wiring item 12 — and bigger than it:** monster attacks rolled with a hardcoded
  `Advantage.NORMAL`. That silently discarded **half of what conditions do in 5e**: "attacks against
  you have advantage" is half the definition of prone, restrained, paralyzed, blinded and
  unconscious, and the side that attacks a player is almost always the monster — the exact side that
  was ignoring it. So a monster swinging at a player lying on the floor rolled flat. Cover had the
  same shape of hole: applied only when a *player* attacked, so a parapet helped monsters hide from
  players and never the reverse, which is the only direction a player can actually play. Both are now
  read from the target's own `Combatant`, along with Dodge.

  **Found while wiring item 9:** `AbilityItemDispatcher` had the same if/else chain **copied three
  times**, one per interaction event, and the copies had drifted. Divine Smite, Twinned Spell,
  Counterspell and Shield existed only in the "clicked at thin air" chain, so those four items *did
  nothing while looking at a monster or a block* — which is precisely when a paladin smites and when
  a reaction fires. Now there is one shared chain plus the two genuinely entity-only items (Hunter's
  Mark, Bardic Inspiration), so a new item reaches all three events by construction rather than by
  remembering. `checkInteractHandlers` now asserts every `AbilityItem.build` flag is dispatched and
  that the chain is not duplicated again.

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
