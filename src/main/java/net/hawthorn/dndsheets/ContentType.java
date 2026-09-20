package net.hawthorn.dndsheets;

import com.google.gson.JsonElement;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * <p>The 5 content types loaded by id (weapon/spell/preset/trait/monster share a shape: a JSON array of
 * objects with an "id", registered in a {@link NamedRegistry} or equivalent) that the in-game content
 * creator knows how to edit generically — see {@code network.ContentEntrySaveMessage}/
 * {@code ContentEntryRemoveMessage}. Races/backgrounds/classes (see {@link CharacterOptionsRegistry}) are
 * NOT here: their {@code loadFile} replaces the entire category instead of merging by id, a different
 * mechanic handled separately (see {@code network.OptionsSaveMessage}).</p>
 */
public enum ContentType {
	WEAPON(DndPaths.WEAPONS_DIR) {
		public int load(Path file) throws IOException { return Config.loadFile(file); }
		public boolean remove(String id) { return Config.removeWeapon(id); }
		public int loadJson(JsonElement root, String source, Consumer<String> onId) { return Config.loadJson(root, source, onId); }
	},
	SPELL(DndPaths.SPELLS_DIR) {
		public int load(Path file) throws IOException { return SpellRegistry.loadFile(file); }
		public boolean remove(String id) { return SpellRegistry.remove(id); }
		public int loadJson(JsonElement root, String source, Consumer<String> onId) { return SpellRegistry.loadJson(root, source, onId); }
	},
	PRESET(DndPaths.PRESETS_DIR) {
		public int load(Path file) throws IOException { return PresetRegistry.loadFile(file); }
		public boolean remove(String id) { return PresetRegistry.remove(id); }
		public int loadJson(JsonElement root, String source, Consumer<String> onId) { return PresetRegistry.loadJson(root, source, onId); }
	},
	TRAIT(DndPaths.TRAITS_DIR) {
		public int load(Path file) throws IOException { return TraitRegistry.loadFile(file); }
		public boolean remove(String id) { return TraitRegistry.remove(id); }
		public int loadJson(JsonElement root, String source, Consumer<String> onId) { return TraitRegistry.loadJson(root, source, onId); }
	},
	MONSTER(DndPaths.MONSTERS_DIR) {
		public int load(Path file) throws IOException { return MonsterRegistry.loadFile(file); }
		public boolean remove(String id) { return MonsterRegistry.remove(id); }
		public int loadJson(JsonElement root, String source, Consumer<String> onId) { return MonsterRegistry.loadJson(root, source, onId); }
	},
	//At the end, and not in its "logical" spot: this enum travels over the wire (readEnum goes by
	//ordinal), so inserting a constant in the middle silently renumbers the ones after it. See invariant 2.
	ENCOUNTER(DndPaths.ENCOUNTERS_DIR) {
		public int load(Path file) throws IOException { return EncounterRegistry.loadFile(file); }
		public boolean remove(String id) { return EncounterRegistry.remove(id); }
		public int loadJson(JsonElement root, String source, Consumer<String> onId) { return EncounterRegistry.loadJson(root, source, onId); }
	},
	FEAT(DndPaths.FEATS_DIR) {
		public int load(Path file) throws IOException { return FeatRegistry.loadFile(file); }
		public boolean remove(String id) { return FeatRegistry.remove(id); }
		public int loadJson(JsonElement root, String source, Consumer<String> onId) { return FeatRegistry.loadJson(root, source, onId); }
	};

	public final Path dir;

	ContentType(Path dir) {
		this.dir = dir;
	}

	public abstract int load(Path file) throws IOException;
	public abstract boolean remove(String id);

	/**
	 * <p>Loads entries from an already-parsed JSON — from a datapack or another mod's jar — instead of
	 * from a world file. See {@link ContentDatapackLoader}.</p>
	 *
	 * @param onId called with the id of each loaded entry, so the caller can detect clashes
	 *             between two different sources without this enum having to know what a datapack is.
	 */
	public abstract int loadJson(JsonElement root, String source, Consumer<String> onId);

	public Path dmCreatedFile() {
		return DndPaths.dmCreatedFile(dir);
	}
}
