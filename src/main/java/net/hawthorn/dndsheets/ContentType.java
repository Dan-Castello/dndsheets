package net.hawthorn.dndsheets;

import com.google.gson.JsonElement;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * <p>Los 5 tipos de contenido cargados por id (weapon/spell/preset/trait/monster comparten forma: un
 * array JSON de objetos con "id", registrados en un {@link NamedRegistry} o equivalente) que el creador
 * de contenido in-game sabe editar de forma genérica — ver {@code network.ContentEntrySaveMessage}/
 * {@code ContentEntryRemoveMessage}. Razas/trasfondos/clases (ver {@link CharacterOptionsRegistry}) NO
 * están acá: su {@code loadFile} reemplaza la categoría entera en vez de fusionar por id, mecánica
 * distinta que se maneja aparte (ver {@code network.OptionsSaveMessage}).</p>
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
	//Al final, y no en su sitio "lógico": este enum viaja por el cable (readEnum va por ordinal), así que
	//insertar una constante en medio renumera las de detrás en silencio. Ver invariante 2.
	ENCOUNTER(DndPaths.ENCOUNTERS_DIR) {
		public int load(Path file) throws IOException { return EncounterRegistry.loadFile(file); }
		public boolean remove(String id) { return EncounterRegistry.remove(id); }
		public int loadJson(JsonElement root, String source, Consumer<String> onId) { return EncounterRegistry.loadJson(root, source, onId); }
	};

	public final Path dir;

	ContentType(Path dir) {
		this.dir = dir;
	}

	public abstract int load(Path file) throws IOException;
	public abstract boolean remove(String id);

	/**
	 * <p>Carga entradas de un JSON ya leído —de un datapack o del jar de otro mod— en vez de de un archivo
	 * del mundo. Ver {@link ContentDatapackLoader}.</p>
	 *
	 * @param onId se llama con el id de cada entrada cargada, para que quien llame pueda detectar choques
	 *             entre dos fuentes distintas sin que este enum tenga que saber qué es un datapack.
	 */
	public abstract int loadJson(JsonElement root, String source, Consumer<String> onId);

	public Path dmCreatedFile() {
		return DndPaths.dmCreatedFile(dir);
	}
}
