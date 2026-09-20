# dndsheets — Launch trailer script (60-75s)

A Forge 1.20.1 mod that turns Minecraft into a D&D 5e VTT: character sheet,
opt-in 5e combat on top of vanilla combat, generated dungeons and a DM panel.

## Structure (7 shots)

**1. Hook (0:00-0:06)**
- Black screen → text: "What if Minecraft were your D&D table?"
- Cut to: a player opens the character sheet with a key (CharacterSheetScreen).
- VO: "Minecraft, now with real D&D 5e rules."

**2. Character sheet (0:06-0:18)**
- Quick tour of CharacterSheetScreen: stats, HP, hit dice, class inventory.
- Level up live (AbilityImprovementScreen / SubclassScreen).
- On-screen text: "A complete sheet. One button."

**3. Real combat (0:18-0:32)**
- Fight against a mob: attack roll with advantage/disadvantage (RollEditorScreen), damage with
  resistances, casting a spell (GrimoireScreen) with a slot cost.
- Initiative and turns (TurnControlScreen / TurnActionScreen).
- VO: "Attacks, saves, spells, initiative — real 5e, on top of the combat you already know."
- Text: "Works with any mob or weapon with no setup."

**4. DM tools (0:32-0:48)**
- DmPanelScreen: spawn a monster with a real stat block (MonsterSpawnListScreen).
- Generate a dungeon on the fly (DungeonGenerateScreen) — a quick cut showing the result.
- Session journal (JournalScreen) and an encounter with several players (PartyScreen).
- VO: "For the DM: monsters with real stat blocks, generated dungeons, a session journal."

**5. Your own content (0:48-0:56)**
- ContentFormScreen: create a new spell or magic item from inside the game, without leaving.
- Text: "Create your own content. No hand-editing JSON."

**6. Multiplayer (0:56-1:04)**
- Two or three players in the same game, each with their own sheet, voting on a rest (RestVoteScreen).
- VO: "A tabletop RPG, inside your server."

**7. Closing (1:04-1:15)**
- Mod logo + the name "dndsheets".
- Text: "Forge 1.20.1 · Client and server · Download it now."
- Download link/platform on screen.

## Recording notes
- Record in runClient with an already prepared world (a level 3+ character, an inventory with a couple of
  spells/items, a second player or bot for the group shots).
- Use `/dnd monster spawn` and `/dnd dungeon generate` before recording to have the result ready,
  then repeat live for the real shot.
- Music: something fantasy/adventure, without vocals, so it doesn't clash with the VO.
- Export at 1080p60; trim each shot 1-2s longer than shown above and adjust in editing.

→ Script ready to record. Missing: recording the footage (I can't operate your Minecraft/OBS from here).
