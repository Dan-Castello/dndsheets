# Project context — dndsheets

Deep-dive reference for anyone (human or AI) picking up this codebase cold. It used to carry a full
narrative log of every bug fixed and every roadmap decision made, in essay form — that made it the single largest file in the repo and unreadable as
a map. It has been purged: the log below states symptom → cause → fix in one or two lines each: the
code, the self-tests and `git log` are the source of truth for the story, this file is the index.

## What this is

A Minecraft Forge mod (**1.20.1**, Forge `47.2.0`/`47.4.10`/`47.4.22` for dev tooling, mod id
`dndsheets`) that turns Minecraft into a D&D 5e VTT: a fillable character sheet bound to a keypress,
real 5e combat resolution layered on top of vanilla PvP/mob combat (opt-in per weapon/monster/spell
— anything unconfigured behaves like normal Minecraft), and a full DM toolkit (spawn monsters with
real stat blocks, run initiative, generate dungeons from vanilla's jigsaw system, create content
in-game). It needs to be installed on both client and server. It is now three Gradle modules — the
core (`dndsheets`) plus two hard-dependent addons, `dungeon` and `species` — see "Modularity Map"
below. It targets 1.20.1 today and **the door to future Minecraft versions is deliberately left
open** — see "Portability to future Minecraft versions". Other tabletop systems are out of scope.

## Invariants — break these and it fails silently

Every one of these has already cost someone a debugging session, or would. Read this list before
your first edit. The numbering is load-bearing: code comments cite these by number.

1. **Never reorder or delete an entry in `DndsheetsMod.registerNetworkMessages`.** *Machine-enforced:*
   `JsonContentSelfTest.checkNetworkShape` pins `NETWORK_SHAPE`/`NETWORK_ORDER`/`NETWORK_WIRE` against
   the source. Message id = registration order; an insertion silently renumbers everything after it.
   Add new entries at the end.
2. **Never insert a constant into the middle of an enum that crosses the network.**
   `FriendlyByteBuf.writeEnum`/`readEnum` travel by ordinal. `SheetAdjustMessage.Field` is the live
   example; `CONDITION` is last for exactly this reason.
3. **Prefer extending a parameterized message over registering a new class.** `SheetAdjustMessage`
   already merges six former message classes behind a `Field` enum; bulk "give one of N similar
   things" flows follow the same shape.
4. **Anything that mutates a sheet must reach `SheetLoader.saveServer`.** *Machine-enforced:*
   `JsonContentSelfTest.checkSheetWritesArePersisted` fails the build when a file mutates a sheet with
   a `ServerPlayer` in hand and never saves. Use `SheetLoader.saveAndSync(player, sheet)` — persists
   and syncs in one call. The 5-minute autosave is a backstop, not the write path.
5. **`/reload` can never publish a new or edited dungeon pool in the same session.**
   `Registries.TEMPLATE_POOL` is worldgen; it loads with the world. Tell the DM to re-enter the world.
6. **A dungeon start pool must contain only pieces that have the start jigsaw.** Vanilla picks one
   random piece from the pool and searches only inside it, never retrying. Mixed pools fail
   non-deterministically.
7. **Never hand-roll a screen's layout: extend one of the four bases.** `FormPanelScreen` (parchment
   panel, frame, title, rule, `Math.max(44, ...)` floor, `addFieldRow(...)`); `SmallFormScreen`
   (Confirm/Cancel/Delete on top of it); `ListPickerScreen` (scrollable row list); `ModalDialogScreen`
   (fixed-size dialog). **No concrete screen extends bare `Screen`.** The worst layout bugs in the
   project's history came from the one screen that used to (see bug #2 below).
8. **Content JSON is user data.** Adding a field is fine; renaming or requiring one breaks every pack
   a DM already wrote. Every parser defaults missing fields and isolates errors per element.
9. **Leave vanilla alone when nothing is configured.** An unregistered weapon, a mob with no stat
   block, a player with no sheet — all must behave exactly like normal Minecraft.
10. **Content JSON is formatted by hand, compactly.** Never rewrite these files with a `json.dump` —
    it reflows everything and makes the diff unreadable. Insert respecting the style.
11. **Every non-trivial rule gets a case in `JsonContentSelfTest`.** Runs on `gradlew build`, no Forge
    runtime needed. Logic that can't be reached without a running game goes behind a pure helper
    class that can (`CharacterRules`, `SpellSlots`, `Cover`, `Condition`, `AttackRules`/`SaveRules`,
    `CreatureType` — see "Portability" below for why that split also pays for the port).

## Repository layout

```
src/main/java/net/hawthorn/dndsheets/     Core module.
  *.java                    Managers, registries (SpellRegistry, MonsterRegistry, PresetRegistry,
                             TraitRegistry), and cross-cutting pieces (SheetLoader, CombatManager,
                             TurnManager, Config). ~83 clases planas en este paquete raíz — no hay
                             subpaquetes de dominio; es el punto más denso del repo.
  api/                       DndSheetsApi — the ONLY surface other mods should call (versioned,
                             API_VERSION). Everything else can change signature without notice.
  client/gui/, client/gui/components/, client/procedures/, command/, network/, init/,
  world/inventory/, procedures/    Same shape as always — see invariant 7 for screens, invariant 1/2
                             for network.

dungeon/                     Addon module, modid `dndsheets_dungeon`. Own build.gradle, own
                             src/main + src/test. See "Modularity Map".
species/                     Addon module, modid `dndsheets_species`. Own build.gradle, own
                             src/main + src/test. See "Modularity Map".
dependencies/                Local jars for compileOnly integrations that have no usable Maven
                             artifact (Origins and Apoli, referenced from species/build.gradle) —
                             see "Library Audit" for the mods evaluated and rejected.
tools/                       Python scripts: import_srd.py (SRD importer), sync_lang_variants.py
                             (regional lang copies), icon generators, preflight_mods.py.

src/main/resources/
  assets/dndsheets/lang/     en_us.json, es_es.json (+ the 6 other es_* regional copies, see bug
                             #16) — includes the in-game Guide book's page text.
  assets/dndsheets/textures/ screens/ (GUI backgrounds+atlas), sounds, etc.
  dndsheets/defaults/        The mod's own content packs. Rewritten to <world>/dndsheets/<type>/
                             mod_defaults.json on EVERY server start (see ContentDefaults).

test/dndsheets/              One hand-written "ejemplo.json" per content type — the fixture
                             JsonContentSelfTest parses. Not the bulk packs (see appendix note below).
templates/                   Starter files for mod-pack authors extending content by hand.
datapacks/dndsheets_loot/    A loot-table datapack bundled with the mod.
runClient/, runServer/       Local dev run directories — see "Library Audit" for why they stay
                             empty of production mods.
PROJECT_CONTEXT.md           This file.
```

## Modularity Map

Three Gradle modules, one repo: the core (`dndsheets`) and two addons, `dungeon` and `species`. Each
addon is a **hard dependency on the core**, not an independent mod — it compiles against the root
project's source set (`implementation project(':')`), not a published jar, so a change in either
module is visible without building the other first.

- **`dungeon`** (`dndsheets_dungeon`) owns the jigsaw/structure toolkit: `DungeonManager`,
  `DungeonPieceRegistry`, `DungeonToolManager`, `EncounterPopulator`, `GridToStructure`, the
  trace/capture subsystem (`DungeonTraceScreen`/`DungeonTraceCaptureMessage`/
  `DungeonTracePreviewRenderer`/`DungeonPiecePlacer`, ~1.000 LOC: capturar una construcción marcando
  dos esquinas en el mundo), plus its own GUI/command/network packages. It reaches into the core for
  `DndsheetsMod.LOGGER`/`PRETTY_GSON`/`MODID`, `DndPaths`, the shared GUI bases (`SmallFormScreen`/
  `ListPickerScreen`/`ModalDialogScreen`/`GuiStyle`/`TomeButton`), `network.NetworkUtil` (el guard de
  permisos DM, una vez por mensaje), `MonsterRegistry`, `SheetLoader` and `InteractionEvents` — it
  does not duplicate any of them.
- **`species`** (`dndsheets_species`) bridges [Origins](https://github.com/apace100/origins-forge)
  (race/background selection) into this mod's rules: `RaceRegistry`, `BackgroundRegistry`,
  `OriginBridge`, `/dndspecies sync`. Origins is a **hard dependency of this addon only** — the core
  never references it. Origins picks the race; this addon applies the Ability Score Improvement and
  the SRD racial traits through the core's own write path (`SheetLoader`/
  `DndsheetsMod.sendSheetFieldUpdate`, invariant 4) — Origins never writes the sheet directly.
- **Why split at all, and why now:** the core was carrying two features (a full jigsaw dungeon
  generator, an Origins bridge) that are large, optional in spirit, and each pull in their own GUI
  surface. Splitting them out is what lets a pack ship the core without either, and lets each addon's
  pure logic (`DungeonManager.buildPoolJsons`/`GridToStructure`, `RaceRegistry.apply`) get its own
  `check`-wired self-test (`DungeonSelfTest`, `SpeciesSelfTest`) — same pattern as the core's
  `JsonContentSelfTest`, run with `./gradlew testDungeonSelf`/`testSpeciesSelf`, no Forge runtime.
- **Neither addon can be dev-tested loaded together with a mixin-based mod** (Origins/Apoli included)
  — see "Library Audit" for why `runClient` stays otherwise empty and what that means for testing
  `species` against the real Origins jar.

## Library Audit

What this mod actually links against, and how each was evaluated — since `dependencies/` holds jars
with no other paper trail:

- **dicebot** (`io.github.tfriedrichs:dicebot`) — hard dependency, shaded and relocated into the jar
  (`shadowJar`, package `net.hawthorn.dndsheets.relocated.dicebot`) so it never collides with another
  mod's copy.
- **Curios API**, **Patchouli** — soft dependencies. `compileOnly` against the `api` classifier only
  (the two or three methods actually called: `CuriosApi.getCuriosInventory`/`getEquippedCurios`,
  `PatchouliAPI.get()`), pinned to an early 5.x for Curios so the mod compiles against — and works
  with — whatever version a player has. `CuriosCompat`/`PatchouliCompat` contain **zero** references
  to the real types outside an `isLoaded()` guard (verified with `javap`), so a missing jar degrades
  to "no Curios slots" / "Guide opens as a written book" instead of `NoClassDefFoundError`.
- **Origins + Apoli** — the `species` addon's hard dependency, `compileOnly` against local jars in
  `dependencies/` (no usable Forge-1.20.1 Maven coordinate found for this evaluation). Same isolation
  discipline as Curios: `OriginBridge` degrades gracefully if Origins is absent, but `species` itself
  is meaningless without it.
- **Caelus, Iron's Spellbooks, origins-classes, apoli_stream_leak** — evaluated and **not**
  integrated. Their jars (plus stale local copies of Curios/Patchouli, which now come from Maven as
  `compileOnly`) were deleted from `dependencies/`; this paragraph is the record of that evaluation.
  `dependencies/` holds only what a build file actually references: Origins and Apoli.
- **Why none of these run in `runClient`:** every one of them (plus Oculus, LionfishAPI, Citadel,
  tried the same way) mixes into vanilla classes using SRG names (`f_19803_`, `m_91087_`), and this
  dev environment runs Parchment mappings, where those names don't exist. `fg.deobf` fixes the jar's
  bytecode but not its mixins; forcing `mixin.env.remapRefMap` fixes that jar's mixins and breaks
  every other mixin mod in the same client. No combination works, so `runClient/mods` stays empty on
  purpose — these integrations are tested against a real `./gradlew build` install instead, and the
  structural isolation (`CuriosCompat`, `PatchouliCompat`, `OriginBridge`) is checked with `javap`
  rather than by running it.

## The rules core (read this before touching combat)

Three files carry the 5e rules layer; read them before assuming a pattern from an older file still
applies.

**`Combatant`** is the single abstraction over "anything that participates in 5e rules": a player
(backed by a `JsonObject` sheet) or a monster (backed by a `MonsterStatBlock` + entity NBT). Before
it existed, that split was a `boolean isMonster` and every rule needing "the target's AC" or "take N
hit points off it" was written twice — monsters had no resistances, no defensive reactions, no
concentration. `Combatant.of(entity)` returns null for anything outside the rules; callers fall back
to vanilla behaviour. Implementations hold no state of their own — they read/write where the state
already lived. Three implementations, and the two sheet-backed ones share `SheetBacked`:
`PlayerCombatant`, `NpcCombatant` (a character with a body, not a player behind it), `MonsterCombatant`.

**`Condition`** is the 14-condition table of 5e, one `switch` per rule rather than a positional
constructor. Storage is `label` or `label@sourceEntityId` in the sheet's JSON array / monster's NBT
string — the suffix is optional so pre-existing data still loads. `TurnManager.applyEffect` treats
any effect name that *is* a condition as one automatically — no new command, no new JSON field.
Three choke points enforce them: `TurnManager.tryAct` (can't act), `MovementAnchorTracker.enforceBudget`
(speed 0), `CombatManager.resolveAttack` (advantage/auto-crit). Charmed is checked at the attack entry
points instead, since it depends on *who* the target is.

**`CharacterRules`** holds "whose character is this" plus the max-HP formula — split out of
`SheetLoader` because `SheetLoader` resolves `FMLPaths.GAMEDIR` at class-init and can't load outside
a running Forge instance. `SheetLoader.sheets` is keyed by **character id**, not player UUID, with a
binding **derived** from an `active` field on each sheet (never a separate index that can drift out
of sync). A player UUID is still a valid character id, so pre-existing sheets need no migration.

**Techo conocido — un combate por servidor.** Todo el estado de `TurnManager` es estático y único por
proceso (~18 campos), así que solo puede existir UN encuentro en modo turnos a la vez en todo el
servidor. Decisión deliberada (un servidor = una mesa): si algún día dos grupos juegan encuentros
simultáneos, eso es estado por-encuentro y una reescritura grande, no un parche. Está anotado con un
comentario `ponytail:` en la propia clase.

## Architecture and the patterns worth reusing

**Content registries** (weapons, spells, monsters, presets, traits, feats) share one shape: an
in-memory map (`NamedRegistry<T>`) loaded from a JSON array via `JsonRegistryLoader<T>` (per-element
error isolation). `Config` (weapons) predates this and still hand-rolls its own maps — a known
inconsistency, not worth unifying alone. Race/background/class options (`CharacterOptionsRegistry`)
*replace* the whole category on load rather than merging by id, because there's no id, just a flat
string list.

**`DndPaths`** owns every `<world>/dndsheets/<type>/` folder and auto-loads every `.json` file found.
**Load order is `mod_defaults.json` first, then everything else by name**, and `NamedRegistry.register`
overwrites by id — a DM's file always wins over what the mod ships, which is what makes rewriting
`mod_defaults.json` on every start safe.

**The in-game content creator** (`ContentType` + `ContentPackFile` + `ContentFormScreen`):
`ContentType` wraps each id-keyed type's `load`/`remove`. `ContentPackFile` read-modify-writes a
dedicated `dm_created.json` per type, then re-invokes the type's normal `loadFile` — no separate
persistence layer. `ContentFormScreen` is a generic data-driven form for the flat-schema types;
traits/monsters/options get bespoke screens because their shape genuinely differs.

**GUI architecture**: three real `AbstractContainerScreen`s (character sheet, roll editor, advanced
roll editor); everything else is a plain `Screen` opened via a static `open(...)`, built on the four
bases in invariant 7. `GuiStyle` is the single source of the shared panel look; `TomeButton` la fila
estándar; `CommandListScreen` es la lista genérica cuyas filas disparan un comando de chat (camino GUI
barato para cualquier comando existente — el permiso sigue en el comando). En el HUD viven tres
overlays con el mismo aspecto de tomo: `TurnHudOverlay` (tablero de iniciativa + economía de acción,
solo en combate), `ResourceHudOverlay` (recursos/estados, siempre) y `CombatLogOverlay` (últimas
líneas de combate, efímero — se alimenta reconociendo las etiquetas `chat.dndsheets.tag.*` de
`ChatFeedback` en el cliente, sin red propia).

**Networking**: every message is a small hand-written class registered once in
`DndsheetsMod.registerNetworkMessages` (alphabetical, message id = registration order — invariant 1;
`PROTOCOL_VERSION` makes a client/server mismatch fail clean). `DndsheetsMod.withDmTarget` is the
shared op-check + target-resolution helper for DM-acts-on-another-player messages. Bulk "give one of
N similar things" actions use one message parameterized by an enum/type field (invariant 3).

**Sheet persistence**: `SheetLoader` keeps every character's sheet as an in-memory `JsonObject`,
backed by one JSON file under `<server>/charactersheets/`. The single write path is
`SheetLoader.saveServer` (invariant 4) — DM-initiated edits relying on the 5-minute autosave instead
was a real, since-fixed bug (bug #5).

**Dungeon generation** now lives in the `dungeon` addon: `DungeonPieceRegistry` (per-world) holds
captured pieces, `DungeonManager` converts them into vanilla `template_pool` datapack JSON and calls
`JigsawPlacement.generateJigsaw`. Most vanilla-API sharp edges in the project live here — see bugs
#7-#11 and invariants 5/6 before assuming `/reload` or a captured piece is trustworthy.

## Bugs fixed — condensed log

Each line is symptom → root cause → fix, in the order found. Full narrative (why, what was tried,
what a mutation-tested self-test catches) lived here before the purge; it's in `git log` on this file
and in the self-test names cited, not repeated below. Numbering is stable — cited by number in code
comments and in this file, don't renumber.

### List A — first testing pass

1. Cyclic buttons only advanced forward → `DirectionalCycleButton` (left = next, right = previous).
2. Forms clipped off the top of the screen (`SheetAdjustScreen`/`SmallFormScreen` centered on
   `height/2` with no floor) → `Math.max(44, ...)` floor. Not the actual cause of "slots don't apply"
   — that was #3, found the next round.
3. **The real "spell slots won't apply" bug: a 2px-wide Apply button** — its width formula left
   almost nothing after two input fields. Fixed by giving Apply its own full-width row.
4. DM Panel actions gave zero feedback (a working change looked broken) → one confirmation chat
   message covering all six actions.
5. DM-side sheet edits didn't survive a restart — `sendSheetUpdate` never called `saveServer`, only
   the 5-minute autosave did → fixed in the shared method (invariant 4).
6. Delete UX wasted a full extra row per list item → `SmallFormScreen.showDeleteButton()`/`onDelete()`.
7. Dungeon pieces captured a stale `.nbt` (Structure Block "Save" is an instant snapshot, jigsaw
   config done after it never got captured) → DM Wand calls `saveStructure()` itself on capture.
8. `/reload` cannot publish a new/edited dungeon pool, ever, in the same session —
   `Registries.TEMPLATE_POOL` is worldgen, confirmed by decompiling `ReloadableServerResources`. Fix
   is telling the DM to re-enter the world (invariant 5), not retry logic.
9. Real "No starting jigsaw found" cause: a start pool mixing an entrance piece with plain connectors
   — vanilla picks one random piece and never retries → `DungeonManager.hasStartJigsaw()` validates
   up front; piece list marks `[inicio]` (invariant 6).
10. Unhelpful pool-name validation message (DMs typed a structure's namespace into a bare-word field)
    → one centralized `DungeonManager.poolNameError` across 6 call sites.
11. Orphaned `template_pool` files accumulated forever → `publish()` deletes stale pool files first.
12. Long GUI lists had no filter → opt-in `ListPickerScreen.searchable()`.
13. In-game tutorial was written into `README.md`, which never ships in the jar → pivoted to the
    existing `GuideBook`, auto-opened on genuine first join.
14. The Guide read as a reference card and part of it was invisible: (a) a written book silently
    truncates past 14 lines — `GuideBook` now measures and splits pages, Patchouli pages capped at
    320 chars since Patchouli can't split; (b) flat 31-page strip → indexed categories with
    `CHANGE_PAGE` links; (c) three shipped subsystems and 6 commands were undocumented; (d) opening
    page never named `CharacterSetupScreen`; (e) the book taught the mod, never D&D — added a
    "Primera partida" category defining AC/saves/advantage before anything else.
15. Half the mod was hardcoded in one language: the roll editor's 8 keys held English text despite
    `es_es.json` being "complete" (the check compares keys, not values), and 48 `Component.literal`
    sites held Spanish prose the language files never saw → all moved to keys;
    `checkChatMessagesAreTranslatable` now fails on any literal carrying prose outside `command/`.
16. Minecraft has no regional locale fallback — a player on `es_mx` got vanilla in Spanish and the mod
    in English, with a perfectly valid `es_es.json` → `tools/sync_lang_variants.py` copies it to the
    other 6 `es_*` locales, `checkLanguageFiles` fails if a copy drifts.
17. NPCs were all `NoAI:1` and invisible to the party roster → `"ai": true` on monster JSON keeps
    real AI; `TurnManager.freeze`'s gate changed from "no stat block" to "AI on"; `/dndmonsters bind`
    (and the DM Wand's right-click on a stat-block-less creature) attaches a stat block to *any*
    existing entity instead of porting a whole NPC-mod integration.
18. DM Wand sneak+click never deleted an armor stand — `ArmorStand.interactAt` consumes client-side
    before `PlayerInteractEvent.EntityInteract` ever fires → also subscribed to
    `EntityInteractSpecific`, filtered to armor stands only (avoids a double-fire on every other
    entity).
19. Wild Shape: `RenderPlayerEvent.Pre` swaps the rendered entity (zero offsets — already inside the
    dispatcher's translation); rules-side, the beast's numbers are written onto the **sheet** and the
    druid's stashed underneath — no new `Combatant`, because every rule already reads AC/HP/STR from
    there. Reverting is a `HIGHEST`-priority `LivingDeathEvent` cancel, not a new death path.
20. Legendary bosses queued like everyone else — `"ownClock": true` removes a creature from the turn
    **order** while keeping it in the encounter roster, ticking every 120 ticks (one 5e round) instead
    of waiting a turn. Aggro is nailed with `goalSelector` pruning (`PanicGoal`/`AvoidEntityGoal`) so
    a burning boss doesn't flee the fight.

### List B — Fase 4 engine backlog (numbered `punto N` in code comments; keep numbers stable)

1. Proficiency bonus was frozen at +2 forever → `CharacterRules.proficiencyBonusFor` (tiered by
   level).
2. Cantrips consumed a spell slot → `Spell.level()` now gates `SpellCastManager`.
3. Spell slots were one flat pool (same cost at every level, no scaling with character level) →
   `SpellSlots`, a per-level table per caster type (full/half/pact), spending the lowest slot that
   works; half-casters round **up** in single-class play.
4. Upcasting (`upcastDice`) — a higher slot spent when no lower one was left didn't scale the spell.
   `SpellSlots.spend` returns the level actually spent; `Spell.upcastTo` returns a copy (not a dice
   string, since `dice` is read from 8 different casting modes).
5. Cantrips didn't scale with caster level (`Spell.atCasterLevel`, +1 die at 5/11/17) — derived from
   level rather than declared per-spell, so it can't drift per entry.
6. Three frozen class constants: Rage damage, Bardic Inspiration die, Divine Smite — each now reads
   the caster's level via `CharacterRules`, the same defect as item 1, three more times.
7. `CreatureType` (14 types, normalized parser) — unblocks type-gated rules; unknown type = no rule
   fires, never a guess.
8. Type-gated spell targeting (`affectsTypes`/`immuneTypes`) — Hold Person/Hold Monster etc. actually
   check the target's type now; a wrong-type target costs no slot.
9. Turn Undead — needed item 7 to know what "undead" means.
10. The attack that starts combat could vanish if the attacker lost their own initiative roll —
    `tryAct` rejected the very swing that triggered auto-start → the attacker now **opens** the turn
    order (`startAt(..., initiator)`) instead of resolving outside it.
11. Cover (`Cover`) — 5-ray geometry, perpendicular side rays (not axis-aligned), applies to weapon
    attacks/spell attacks/Dex saves only, subtracted from the DC on saves rather than added to a
    roll.
12. Dodge/Dash/Disengage (`TurnActionManager`) — plug into the existing movement budget, opportunity
    tracker and advantage machinery; no new rules.
13. Help (`HelpActionManager`) — the 4th action, entity-interact rather than a menu row (needs a
    target), writes to the existing `nextAttackAdvantage` flag.
14. One attack resolution instead of two (`AttackRules`) — player/monster attack logic had drifted
    three times in a row; unified. `combineAdvantage` deliberately can't be nested (a normal from two
    cancelling sources is indistinguishable from "no source"), asserted structurally since the
    breaking case needs live turn state.
15. Same unification for saves (`SaveRules`) — cover had only applied on the player-casts side.
16. Levelling up (`LevelUpManager`) — Ability Score Improvements at 4/8/12/16/19 were never granted;
    pending improvements live on the sheet so they survive a restart or a level jump.
17. Deleting a character — renames to `<id>.json.deleted` (recoverable, not destroyed); a player is
    never left with zero characters.
18. Switching characters didn't refresh the open sheet screen and then **overwrote the new character
    with stale data** on the first interaction → `CharacterSheetScreen.refreshIfOpen()` on every full
    sheet push (not on single-field patches, which would repaint over mid-combat typing).
19. Commands took character ids, not names → name resolution: exact id → exact name → unique prefix;
    ambiguous = refuse rather than guess.
20. Two characters with the same name were unreachable — autocomplete offered an option the resolver
    then refused → suggestions carry `[id]` only when another candidate shares the name.
21. Deleting your active character resurrected it on next join — the "do you have a character" check
    used the XP-fallback-shaped `activeCharacterOf` (fine for display, wrong for deciding) → checked
    against the explicit binding instead.
22. Swept every compatibility fallback for the same shape (*"a plausible default is fine for display,
    dangerous for deciding"*): found levelling reading Minecraft XP level instead of `characterLevel`
    in two decision sites; found (and left alone, correctly) several read-only uses of the same
    fallback.
23. Creating a character from the screen — "+ Personaje nuevo..." row on `CharacterListScreen`, name
    only; class/abilities come from the preset chosen afterward.
24. Legendary Resistance — a boss turns a failed save into a success, 3×/day, tracked per-entity NBT
    (not per species).
25. Sheet coordinate-space bug: the empty-attacks notice was drawn in screen coordinates using
    sheet-space grid constants (no `leftPos`/`topPos` translation) → `checkSheetCoordinateSpaces`
    asserts `render()` never names a grid constant. Also: **characters are reachable from the sheet**
    itself via a "Personajes" button opening `CharacterListScreen` (this is `punto 25`, cited in
    `CharacterListScreen.java`).
26. Legendary Actions — a boss acts at the end of another creature's turn, budget refills on its own
    turn; deliberately reduced to "one attack, cost 1" rather than the SRD's per-boss cost table.
27. Three defects from one play log: HUD showed more spell slots than the max (`clientPatch` was
    missing the max field); "no spell slots left" while holding four (they were the wrong level, so
    the message now names the level required); a `WARN` logged on every summon re-registration (now
    `NamedRegistry.replace()`, since re-registering on cast is deliberate).
28. Corrected my own boss annotations: Legendary Actions had zero data on any monster (a feature that
    could never fire); Legendary Resistance had been put on 4 creatures that actually have **Magic
    Resistance** (a different, unimplemented rule) — removed.
29. Multiattack — adult dragons made one attack where 5e gives three; one integer field, defaults to
    1 (old behaviour), clamped to 6 as a chat-spam firebreak.
30. "Incapacitated" only blocked normal actions, not reactions or legendary actions — a paralysed
    monster still made opportunity attacks, a stunned player still cast Shield → one
    `isIncapacitated()` gates all three action types.
31. A restrained monster still walked away (this mod's own monsters move via a teleport in
    `MonsterActionManager`, which `cannotMove` never reached) and monster attacks ignored their own
    attacker-side conditions (`ownAttackAdvantage` was player-only) — both fixed.
32. A player couldn't see their own conditions — visible only in the DM Panel → now shown on the HUD
    in red, patched via the existing `setConditionSources` write point.
33. Concentration, an armed Divine Smite, a Bardic Inspiration die, pending advantage were all
    invisible to the player who held them → all four now patch to the client and show on the HUD.
34. A new character was born at the creator's Minecraft XP level (same fallback-used-to-decide shape
    as item 22, missed there because it only mattered once characters became independent of players)
    → creation now stamps level 1 explicitly; wearing a level-less legacy sheet stamps its current XP
    level once rather than resetting it.
35. Current HP and once-per-rest resources (Second Wind, Channel Divinity, Arcane Recovery) were
    keyed by player, not character — switching carried wounds and refunded resources on a server
    restart → moved onto the sheet, saved on the way out and restored (clamped ≥1) on the way in.
36. The inventory was still shared across characters (item 35 was wrong to call it player-owned) →
    swapped via SNBT on the sheet on switch. **The riskiest change here: persist-first-then-empty is
    the only safe order**, pinned by its own check — the reversed order compiles and passes every
    other assertion while silently deleting items.
37. Concentration, rage, wild shape and Hunter's Mark were still keyed by player → all four are cut on
    character switch.
38. The swapped inventory didn't visually refresh until reopened manually — it's the one piece of
    state Minecraft doesn't auto-sync for a wholesale server-side replacement →
    `broadcastFullState()` forces it.
39. The death-save screen didn't follow the character on switch → resend on join and on switch, both
    directions (open or close).
40. Nobody was told any of this existed — the in-game Guide had 19 pages and mentioned none of two
    weeks of work → 7 pages added; `checkLanguageFiles` now asserts every registered Guide page has a
    translation.
41. Addon/datapack content path: any JSON under `data/<namespace>/dndsheets/<type>/` now loads for all
    content types, single-object-or-array both accepted, datapacks load before the world folder so a
    DM's own file still wins on an id clash.

**Also found along the way** (each fixed, not separately numbered): a monster's own spell ignored
cover on its target's Dex save, and combat log lines used the Minecraft account name instead of the
character name — both only broke on the monster-casts-at-player path, since the same code was
unified for players first. Monster attacks used a hardcoded `Advantage.NORMAL`, silently discarding
half of what conditions mean (prone/restrained/paralyzed/blinded grant advantage *to the attacker*,
which for a player is almost always a monster) — now read from the target's own `Combatant`.
`AbilityItemDispatcher`'s interaction chain was copied three times across three interaction events and
had drifted — Divine Smite/Twinned Spell/Counterspell/Shield did nothing when clicking an entity or
block — unified into one chain, checked structurally. The bulk content packs existed twice
(`test/dndsheets/` and `resources/dndsheets/defaults/`) and had already drifted, with the self-test
reading the stale copy — duplicates deleted, `test/` keeps only the hand-written examples. Los dos
HUD (`TurnHudOverlay`/`ResourceHudOverlay`) — lo único permanentemente visible del mod — tenían todo
su texto en español fijo: pintan con `drawString(String)`, así que el lint de bug #15 (anclado a
`Component.literal`) nunca los vio; pasados a claves `hud.dndsheets.*` y el lint extendido a
cualquier literal con prosa en `*Overlay.java`.

### List C — pasada VTT 2026-08-24 (experiencia + red)

1. Los dos HUD hardcodeados en español → claves `hud.dndsheets.*` + lint extendido (ver arriba).
2. El dato de combate solo vivía en el chat → `CombatLogOverlay` (los últimos 4 eventos en pantalla
   como RESUMEN SEMÁNTICO — "Mago ▶ Creeper · 17 ✓ 6" —, el detalle entero queda solo en el chat, a
   pedido. El resumen lo compone `ChatFeedback.withSummary` y viaja INVISIBLE dentro del propio
   Component de chat como `insertion`: cero mensajes de red nuevos, una sola fuente de verdad, y las
   líneas sin resumen caen a la línea completa recortada. Reconocidos en el cliente por la etiqueta
   `chat.dndsheets.tag.*`, `push` privado a propósito); barra de PG en el tablero de turnos y en el nombre
   flotante (`NameTagHp`, `RosterRow.currentHp/maxHp` al final del payload; `setResult(ALLOW)` fuerza
   el nametag sobre mobs SIN nombre custom — sin eso el PG solo salía sobre jugadores, nunca sobre los
   enemigos; los mobs de compatibilidad sin bloque de estadísticas caen a su vida vanilla, solo para
   mostrar); latido de sincronización de 20 ticks en `TurnManager` mientras hay combate (el daño no
   siempre coincide con un evento de turno).
3. Las 8 parejas List/ListRequest (16 clases, ~500 LOC, incluida la pareja `CharacterOptions*` que ya
   no mandaba nadie) → fundidas como acciones/kinds de `BrowseActionMessage`/`BrowseListMessage`
   (campo `context` nuevo al final). **Renumeración deliberada de ids** en un solo commit, hashes
   re-pinnados, `PROTOCOL_VERSION` 22.
4. Los botones del Panel de DM fallaban en silencio sin permiso → `handleOnServerAsDm` avisa
   (`chat.dndsheets.dm_required`) en vez de descartar.
5. Panel de DM: filas nuevas para encuentros (lista servida por el servidor, el clic dispara
   `/dndencounters spawn`), dificultad y registro de tiradas — vía `CommandListScreen`, una lista
   genérica cuyas filas mandan un comando de chat (el permiso sigue en el comando). `/dndnotes` se
   quedó sin fila a propósito: solo tiene `give <jugador> <texto>` y una GUI aportaría poco.
6. `PartyScreen` ahora es clicable: la fila de un jugador abre sus Ajustes de hoja (atajo al flujo
   existente, mismo `SheetSummaryRequestMessage`); los PNJ llegan con id vacío y siguen solo-lectura.
7. Crear personaje sin el addon species mandaba un comando inexistente ("Comando desconocido" ×3) →
   respaldo del core: raza/trasfondo vía `CHARACTER_OPTIONS` (listas SRD re-añadidas a
   `CharacterOptionsRegistry`, nombre-solo), clase vía el selector de presets (el mecanismo real).
   Con species instalado, al cerrar el selector de Origins se **vuelve al checklist**
   (`CharacterSetupScreen.ReturnFromOrigins`, reconocido por paquete `io.github.apace100`).
8. `CharacterSetupScreen.step()` pintaba la CLAVE de traducción cruda en cada fila → `translatable`.
9. La Guía se abría sola a los 3s del primer ingreso (se cerraba por reflejo) → toast de esquina con
   la tecla (`TutorialOpenMessage.firstJoin`); `/dndguide` sigue abriendo el libro.

Older, already-resolved technical debt (naming, duplication, dead code — not user-facing bugs) lived
in a separate ledger, `AUDIT_REPORT_2026.md`, now closed and removed; one item (F26, test coverage
for `rollAttack`/`rollDamage`) is still open.

## Commit history

140+ commits, two eras: **2025-09-19 MCreator origin** (`6c01dd2`-`9a4de9f`, one day, commit messages
like *"welp"*) — `a9c061d` (2026-08-02) is the real break, "Full refactor". From there, a steady
cadence of focused passes with real commit messages: content imports, GUI unification (`GuiStyle`,
`ListPickerScreen`), the dungeon/jigsaw system, the `dungeon`/`species` addon split, and the
sin-DM/motor-de-reglas passes. Work that only exists in the working tree is work `git clean`
deletes; a tree that big can't be split into honest commits after the fact.

## The VTT roadmap and why it is in this order

Goal: a VTT competing with Roll20/Foundry/TaleSpire/Owlbear. The gap was never code quality — it was
scope, and three measured structural defects. Order is forced by dependencies, not preference.

- **Fase 0 — `Combatant` + conditions. DONE.** Without it every rule is written three times.
- **Fase 1 — character identity. DONE, GUI included.** Characters keyed independently of players;
  `/dndchar list|new|switch|npc|spawn`. `TurnManager.isMonster` (drives end-of-combat) is deliberately
  separate from `isCombatTarget` (a valid 5e target) — merging them would mean a friendly NPC in the
  room keeps combat from ever ending.
- **Fase 2 — SRD content. Imported in four batches; the bestiary is done.** 87 spells, 330 monsters,
  362 magic items (145 with resistances) from SRD 5.1, CC-BY-4.0 (see Attribution below). Sequencing
  after Fase 0 was load-bearing: the first batch alone found that damage was mandatory (blocking
  no-damage spells) and only applied `if (finalDamage > 0)` (imposing conditions on targets who
  **passed** their save).

  **The engine backlog this unlocked, all DONE:** area shapes (line/cone, originating at the caster
  along its view vector, not a radius — a flattened cone would hit the caster's own party); walls as
  persistent regions (damage whoever *starts their turn* inside, horizontal-only distance so standing
  on top doesn't burn); temporary HP + weapon buffs (`Combatant.takeDamage`
  default method, `absorbWithTemporaryHp` for the one PvP path that can't use it); summons that act on
  later turns (targeting had to exclude the summoner, and a summon must not count as an enemy or
  combat never ends); multi-round zones (`ZoneManager` — shape as a field, persistence
  (`mode: "zone"`) as its own field rather than inferred; absorbed the earlier `WallManager`, which
  no longer exists as a class); magic items (**the
  SRD publishes them as prose only** — reference half fully automatic, mechanical half derived from
  a second-person subject ("**you** gain...") and reviewed, 80 of 362 mechanical; potions are
  consumables, not passive, since their effect comes from drinking; Curios is a soft dependency for
  real ring/charm slots, attunement standing in for "worn" without it).

- **Fase 3 — the table layer. DONE.** Searchable compendium (`/dndcompendium`) for 779 entries;
  journal + handouts turned out to be one mechanism (title/body/visibility) rather than two, verified
  before writing. Visibility is filtered **server-side**, never on the client. Both ride the existing
  `Browse*` message pair — no new network message.

- **Fase 4 — progression fidelity. DONE.** See "Bugs fixed — condensed log" List B above; that whole
  list is Fase 4.

- **Fase 5 — the table you can actually sit down at. Items 2-4 and 6 done; 1 is the modpack, 5 is out
  of scope.** Ordered by how many people are lost at each step, not by how interesting the step is.

  1. **Getting in has to be one click** (not built) — a published Modrinth/CurseForge modpack pinning
     Forge + this mod + optional soft-deps, not an installer of our own.
  2. **Creating a character, not levelling one — done.** Starting gear per preset (barbarian/monk get
     none, on purpose — unarmoured defence *is* their class feature); skill proficiencies write
     `+ $prof` into the roll expression as an added/removed term, never a rebuild (would erase a
     manual edit); `CharacterSetupScreen` is a checklist of 4 existing screens, not a wizard, so a
     table can still fill a sheet in its own order; subclasses live inside their preset (a subclass
     without its class means nothing), grant exactly what a preset grants, gate the level **on the
     server**; feats spend the same pending Ability Score Improvement (two resources would defeat the
     point of a feat), only Grappler ships from SRD 5.1; multiclass enters as an optional field that
     wins when present (an unset sheet behaves exactly as before), with the two *different* rounding
     rules kept separate (single-class half-caster rounds up, multiclassed contributes half rounded
     down) and Warlock levels deliberately excluded from the caster-level sum (Pact Magic is a second
     pool this sheet can't carry).
  3. **The DM's prep loop — done.** Encounters (`EncounterRegistry`, text composition `"id xN"`,
     spawn in a ring not a stack) add no new rule — initiative already auto-starts on first hit.
     Imported builds: any `.nbt` (no `.schem`/`.litematic` converter — that's what the structure block
     already exports to) pastes or registers as a piece directly; import refuses outright when a pool
     is requested and the structure has zero jigsaws, rather than failing silently at generation time.
  4. **Light and vision — done** (`Light`, `VisionManager`, `/dndvision`, off by default). Adds no new
     condition — below light level 4 is just `Condition.CEGADO`, thresholds are vanilla's own light
     levels. Darkvision comes from race (`CharacterRules.darkvisionFeetFor`) with a `-1` sentinel
     override to explicitly remove it.
  5. **Positional voice — out of scope, by decision.** Simple Voice Chat already does this; an
     integration would buy a soft dependency for what two mods already do side by side.
  6. **SRD 5.2 importer + community JSON — done** (`tools/import_srd.py`). Detects 5e-bits, Open5e, or
     this mod's own schema from the first record; `--into` splices new entries as text rather than
     `json.dump`-ing the file (would reflow hand-written entries, invariant 10); `DamageTypes.normalize`
     fixed a real bug the importer exposed — an English-language pack's damage type never matched a
     Spanish sheet's resistance key. SRD 5.2 is where the other 15 feats (origin feats, fighting
     styles, Epic Boons) came from — 5.1 shipped exactly one.

  **Still out of scope:** fog of war, a token layer, the mod's own creature models, a web client,
  other tabletop systems — the 3D map, real line of sight and real lighting are native here, which is
  what the competition emulates with polygons and fog layers. Do not build a fog-of-war system.

## Roadmap 2026-08+ — de mod completo a producto

El roadmap original (Fases 0-5 de arriba) está cerrado salvo el modpack. Esta es la continuación,
decidida tras la auditoría doble del 2026-08-24 (código + experiencia; conclusión: el motor está
por delante de su ventana al usuario). Orden por impacto por hora de sesión, igual que siempre.

### R1 — Rediseño de interfaz (items 1-4 HECHOS 2026-08-24; queda el 5)

La palanca es estructural, no pantalla a pantalla: las 4 bases de la invariante 7 + `GuiStyle` +
`TomeButton` repintan las 45+ pantallas que cuelgan de ellas de un solo golpe (ya pasó dos veces:
los marcos de `GuiStyle` y el reemplazo del botón vanilla por `TomeButton`).

1. **`GuiStyle` v2** — el panel dejó de ser un rectángulo pintado: textura de cuero tileable
   (generada por `tools/make_panel_texture.py` — ruido rosa por FFT, sin costuras por construcción,
   semilla fija = PNG reproducible; nada descargado de internet, nada con licencia ajena) + gradiente
   de profundidad translúcido encima + filete ornamentado (rombo de latón) bajo los títulos. Firma de
   `panel()`/`rule()` intacta: nada de las ~45 pantallas ni de los 3 overlays cambia una línea.
2. **`ListPickerScreen` v2** — (a) buscador **automático** cuando la lista pasa de ~14 filas (antes
   era opt-in por pantalla y la mayoría de listas largas no lo pedía); (b) cabeceras de sección
   (`addHeader`/`SectionHeader`) para menús largos, no clicables y excluidas del filtro; (c) el texto
   del buscador sobrevive a `rebuildWidgets()` (las pantallas que se refrescan al llegar la hoja lo
   borraban a mitad de búsqueda).
3. **Panel de DM seccionado** — 17 filas planas → 5 secciones (Grupo y turnos / Invocar / Entregar y
   ajustar / Mundo y mesa / Contenido y ayuda).
4. **Checklist de personaje** con marcas de progreso (✔ hecho / ○ pendiente).
5. **Coherencia ficha↔paneles — HECHA.** Las 3 láminas de la hoja (`character_sheet*.png`) y el atlas
   de pestañas se regeneran con `tools/make_sheet_textures.py`: marco = el mismo cuero del panel,
   página = pergamino con grano y viñeta, escuadras = el latón 0xC9A227 (que ya era el oro del
   original). La geometría (ventana (41,41)-(1551,1111), divisoria x=552) es la MEDIDA del original:
   los ~105 offsets hardcodeados de `CharacterSheetScreen` no se tocan. Los PNG anteriores viven en
   el historial de git; la herramienta es la fuente desde ahora (semilla fija, PNG reproducible).

Verificación: `gradlew build` + arranque de runClient + revisión visual del dueño (los cambios son
de píxeles; el self-test no los ve).

### R2 — Combate: los dos candidatos anotados en la pasada anterior

- **Indicador de alcance** del arma/hechizo al apuntar (cliente puro: la hoja sincronizada ya trae
  el alcance; pintar es cosa de `CombatFx`/overlay).
- **Preview del área de un conjuro** antes de lanzarlo (partículas vanilla vía `CombatFx`, mismas
  formas que ya calcula el motor de áreas).

Criterio de entrada: solo si tras jugar con la barra de PG + el log en pantalla sigue haciendo
falta — se anotaron precisamente para no construirlos por especulación.

### R3 — Des-MCreatorizar la ficha (la antigua Fase 4)

Sustituir `CharacterSheetMenu.guistate` (`HashMap<String,Object>` global, ~108 accesos entre
`CharacterSheetScreen` y `CharacterSheetLoad/SaveProcedure`) por una clase tipada `SheetState`.
El resto del legado MCreator (`init/`, `world/inventory/`, `procedures/`) se queda: funciona y está
referenciado. ⚠️ Invariante 8: el formato de guardado de la hoja es user data — solo cambia cómo la
pantalla lee su estado en memoria. Verificación obligatoria: round-trip (abrir/editar/guardar/
recargar) con una ficha creada ANTES del cambio.

### R4 — Distribución (la antigua Fase 5.1, único item del roadmap viejo sin cerrar)

Modpack publicado en Modrinth: Forge 47.x + core + addons + Origins/Apoli + soft-deps opcionales
(Curios, Patchouli) + los mods de criaturas elegidos para los packs de aspecto.
`tools/preflight_mods.py` es la base de la doc de instalación de servidor. Ningún código nuevo.
Momento: DESPUÉS de R1 — publicar antes de arreglar la primera impresión quema el lanzamiento.
`PROTOCOL_VERSION` 22 ya rompe limpio con cualquier build anterior, así que la primera publicación
no arrastra compatibilidad.
Requiere cuentas del dueño (Modrinth); no es automatizable desde aquí.

### R5 — Techos documentados (NO hacer sin una decisión nueva y explícita)

`TurnManager` multi-encuentro (un combate por servidor, ver "The rules core") · fog of war · token
layer · modelos de criatura propios · web client · otros sistemas de mesa · migración masiva a JUnit
(los self-tests en `check` cumplen) · GUI para los 24 subcomandos de `/dndsheet` (uso raro, el
comando queda).

## Portability to future Minecraft versions

Targets **1.20.1/Forge**; the door to a future version is deliberately left open — a statement about
coupling, not a promised date.

**What already makes it cheap:** zero mixins, zero access transformers, zero reflection into vanilla
internals — Forge events and public API throughout. The 5e rules live in pure classes untouched by
Minecraft (`CharacterRules`, `SpellSlots`, `Cover`, `Condition`, `AttackRules`/`SaveRules`,
`CreatureType`) and already run outside a Forge runtime. All content is JSON and crosses unchanged.

**What the port actually costs, largest first:**
1. **Networking** — 64+ message classes on `SimpleChannel`, becomes the NeoForge payload API from
   1.20.5. Mechanical but bulky; invariants 1/2 matter *more* during a bulk rewrite, not less.
2. **Item NBT → data components (1.20.5+)** — small because it was kept small: everything this mod
   writes onto an item lives in one compound (`dndsheets`), so it's one migration, not one per item
   kind.
3. **Metadata/toolchain** — `mods.toml` → `neoforge.mods.toml`, Java 17 → 21, Parchment version.
4. **Worldgen/jigsaw** (now in the `dungeon` addon) — the area with the most vanilla sharp edges
   already (bugs #7-#11); assume the API moved and re-verify against decompiled source.

`versionRange="[1.20.1]"` stays strict on purpose — 1.20.2 changed networking and 1.20.5 changed item
data, so widening it would load the mod into a game where it can't work. `checkPortabilityCoupling`
asserts the absence of mixins/access-transformers and that item NBT stays touched only from the
annotated file set — it can't make the port cheaper, it stops the coupling from growing unnoticed
before someone does it.

## Where to go next

- Touching combat, damage, AC, hit points or conditions → go through `Combatant`. An `instanceof
  Player` branch to decide how to read a stat already has a home behind the interface.
- Adding a screen or touching layout → invariant 7: extend one of the four bases, never bare `Screen`.
- Adding a content type or command → check whether `ContentType`/`NamedRegistry`/`JsonRegistryLoader`
  already fits before writing a parallel pattern.
- Touching item NBT, or reaching for a mixin/access transformer → read "Portability" first;
  `checkPortabilityCoupling` fails the build rather than let one in unnoticed.
- Touching dungeons → they live in the `dungeon` addon now. The "regla de oro" (invariant 6) and bugs
  #7-#11, before trusting `/reload` or a captured piece.
- Touching race/background/Origins → the `species` addon; Origins picks, this addon writes the sheet
  through the core's normal path — see "Modularity Map".
- Adding or evaluating a soft dependency → "Library Audit" for the compileOnly/isolation pattern and
  why it can't be tested in `runClient`.
- Anything DM-facing that hands out an item/teaches a spell/spawns a monster → there's almost
  certainly an existing `GiveableItem`-style pattern or DM Panel row to extend.
- Cómo se ve un monstruo → `MonsterRegistry.Appearance` (equipo, cría, brillo) y `baseEntity`, que
  acepta la entidad de cualquier mod instalado — el mod no trae modelos propios. Comprobar si dibuja
  el equipo y si actúa por su cuenta antes de elegir uno. Comprobado en `checkMonsterAppearance`.

## Atribución de contenido de terceros (obligación de licencia)

Esto no es cortesía: es la condición bajo la que se puede redistribuir el contenido.
`JsonContentSelfTest` comprueba que las citas literales sigan presentes, así que borrarlas tumba el
build.

### SRD 5.1

Parte del contenido incluido en este mod (hechizos, monstruos y sus estadísticas) deriva del
**System Reference Document 5.1 ("SRD 5.1")** de Wizards of the Coast LLC, bajo licencia
**Creative Commons Attribution 4.0 International (CC-BY-4.0)**.

> This work includes material taken from the System Reference Document 5.1 ("SRD 5.1") by
> Wizards of the Coast LLC and available at
> https://dnd.wizards.com/resources/systems-reference-document.
> The SRD 5.1 is licensed under the Creative Commons Attribution 4.0 International License,
> available at https://creativecommons.org/licenses/by/4.0/legalcode.

Los datos se importaron de la transcripción JSON de
[5e-bits/5e-database](https://github.com/5e-bits/5e-database), también CC-BY-4.0. Los nombres se
tradujeron al español y las estadísticas se adaptaron a los esquemas de este mod; esas adaptaciones
y traducciones son obra de este proyecto.

### SRD 5.2

Las **dotes** que se envían (`feats.json`) derivan del **System Reference Document 5.2
("SRD 5.2")**, la publicación de 2024 de Wizards of the Coast LLC, también bajo **CC-BY-4.0**.

> This work includes material from the System Reference Document 5.2 ("SRD 5.2") by Wizards of the
> Coast LLC and available at https://www.dndbeyond.com/srd. The SRD 5.2 is licensed under the
> Creative Commons Attribution 4.0 International License, available at
> https://creativecommons.org/licenses/by/4.0/legalcode.

Hubo que ir a buscarlo porque el SRD 5.1 traía **una sola dote** (Luchador). El SRD 5.2 publicó la
lista entera, y de ahí salen las 15 restantes.

### Qué NO está incluido, y no va a estarlo

Contenido de manuales cerrados de D&D (Xanathar's, Tasha's, Volo's, monstruos y subclases fuera del
SRD). Este mod se distribuye públicamente y solo puede llevar material redistribuible.

### Archivos afectados

- `src/main/resources/dndsheets/defaults/spells.json` (SRD 5.1)
- `src/main/resources/dndsheets/defaults/monsters.json` (SRD 5.1)
- `src/main/resources/dndsheets/defaults/items.json` (SRD 5.1)
- `src/main/resources/dndsheets/defaults/feats.json` (SRD 5.2)
- `tools/lang/srd52_feats_es.json` (traducción de este proyecto del texto del SRD 5.2)
- `test/dndsheets/*/ejemplo.json`

Cualquier ampliación futura de estos packs desde el SRD queda cubierta por esta misma atribución.

### Cómo se reproduce la importación

No es prosa, es un comando. Los packs se regeneran con `tools/import_srd.py`, que lee la
transcripción JSON del SRD (5.1 o 5.2) y la traduce a los esquemas de este mod:

```
python tools/import_srd.py --kind feat --from https://raw.githubusercontent.com/5e-bits/5e-database/main/src/2024/en/5e-SRD-Feats.json --lang tools/lang/srd52_feats_es.json --into src/main/resources/dndsheets/defaults/feats.json
```

Las traducciones viven en `tools/lang/` precisamente para que ese comando devuelva el archivo que se
envía y no una versión en inglés. El mismo importador acepta [Open5e](https://open5e.com/) para
contenido **OGL** de la comunidad — ese material NO se envía con el mod; lo trae cada mesa a su mundo
bajo la licencia que le corresponda.
