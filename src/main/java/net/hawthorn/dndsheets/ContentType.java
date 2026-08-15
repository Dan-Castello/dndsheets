package net.hawthorn.dndsheets;

import java.io.IOException;
import java.nio.file.Path;

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
	},
	SPELL(DndPaths.SPELLS_DIR) {
		public int load(Path file) throws IOException { return SpellRegistry.loadFile(file); }
		public boolean remove(String id) { return SpellRegistry.remove(id); }
	},
	PRESET(DndPaths.PRESETS_DIR) {
		public int load(Path file) throws IOException { return PresetRegistry.loadFile(file); }
		public boolean remove(String id) { return PresetRegistry.remove(id); }
	},
	TRAIT(DndPaths.TRAITS_DIR) {
		public int load(Path file) throws IOException { return TraitRegistry.loadFile(file); }
		public boolean remove(String id) { return TraitRegistry.remove(id); }
	},
	MONSTER(DndPaths.MONSTERS_DIR) {
		public int load(Path file) throws IOException { return MonsterRegistry.loadFile(file); }
		public boolean remove(String id) { return MonsterRegistry.remove(id); }
	};

	public final Path dir;

	ContentType(Path dir) {
		this.dir = dir;
	}

	public abstract int load(Path file) throws IOException;
	public abstract boolean remove(String id);

	public Path dmCreatedFile() {
		return DndPaths.dmCreatedFile(dir);
	}
}
