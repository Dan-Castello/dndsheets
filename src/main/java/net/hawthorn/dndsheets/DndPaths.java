package net.hawthorn.dndsheets;

import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * <p>Carpeta única para todo el contenido cargable por JSON del mod: {@code <mundo>/dndsheets/weapons},
 * {@code /spells}, {@code /monsters} y {@code /presets}. Se crean solas al arrancar cualquier servidor
 * (mono o multijugador), y CADA .json que haya dentro se carga solo, sin necesidad de correr
 * {@code /dndweapons load} (etc.) a mano — esos comandos siguen sirviendo para recargar en caliente sin
 * reiniciar el servidor.</p>
 */
@Mod.EventBusSubscriber
public class DndPaths {
	public static final Path ROOT = SheetLoader.GAME_DIR.resolve("dndsheets");
	public static final Path WEAPONS_DIR = ROOT.resolve("weapons");
	public static final Path SPELLS_DIR = ROOT.resolve("spells");
	public static final Path ITEMS_DIR = ROOT.resolve("items");
	public static final Path MONSTERS_DIR = ROOT.resolve("monsters");
	public static final Path PRESETS_DIR = ROOT.resolve("presets");
	public static final Path TRAITS_DIR = ROOT.resolve("traits");
	public static final Path RACES_DIR = ROOT.resolve("races");
	public static final Path BACKGROUNDS_DIR = ROOT.resolve("backgrounds");
	public static final Path CLASSES_DIR = ROOT.resolve("classes");

	@FunctionalInterface
	private interface FileLoader {
		int load(Path file) throws IOException;
	}

	@SubscribeEvent
	public static void onServerStarting(ServerStartingEvent event) {
		createIfMissing(WEAPONS_DIR);
		createIfMissing(SPELLS_DIR);
		createIfMissing(ITEMS_DIR);
		createIfMissing(MONSTERS_DIR);
		createIfMissing(PRESETS_DIR);
		createIfMissing(TRAITS_DIR);
		createIfMissing(RACES_DIR);
		createIfMissing(BACKGROUNDS_DIR);
		createIfMissing(CLASSES_DIR);

		//Contenido por defecto: para que un jugador nuevo no tenga que escribir armas/hechizos/monstruos/
		//presets/rasgos desde cero antes de poder jugar (el mismo pack empaquetado dentro del mod).
		//Razas/trasfondos/clases no lo necesitan: CharacterOptionsRegistry ya trae una lista por defecto en
		//código, sin JSON de por medio.
		refreshDefaultsLogging(WEAPONS_DIR, "weapons.json");
		refreshDefaultsLogging(SPELLS_DIR, "spells.json");
		refreshDefaultsLogging(ITEMS_DIR, "items.json");
		refreshDefaultsLogging(MONSTERS_DIR, "monsters.json");
		refreshDefaultsLogging(TRAITS_DIR, "traits.json");
		refreshDefaultsLogging(PRESETS_DIR, "presets.json");

		autoLoadAll(WEAPONS_DIR, Config::loadFile, "armas");
		autoLoadAll(SPELLS_DIR, SpellRegistry::loadFile, "hechizos");
		autoLoadAll(ITEMS_DIR, MagicItemRegistry::loadFile, "objetos mágicos");
		autoLoadAll(MONSTERS_DIR, MonsterRegistry::loadFile, "monstruos");
		autoLoadAll(TRAITS_DIR, TraitRegistry::loadFile, "rasgos");
		autoLoadAll(PRESETS_DIR, PresetRegistry::loadFile, "presets");
		//Categorías del selector de Raza/Trasfondo/Clase (ver CharacterOptionsRegistry): reemplazan la
		//lista por defecto entera si hay un .json en la carpeta, no la extienden.
		autoLoadAll(RACES_DIR, file -> CharacterOptionsRegistry.loadFile(CharacterOptionsRegistry.RACE, file), "razas");
		autoLoadAll(BACKGROUNDS_DIR, file -> CharacterOptionsRegistry.loadFile(CharacterOptionsRegistry.BACKGROUND, file), "trasfondos");
		autoLoadAll(CLASSES_DIR, file -> CharacterOptionsRegistry.loadFile(CharacterOptionsRegistry.CLASS, file), "clases");

		//Por-mundo, no bajo ROOT: las piezas de mazmorra referencian .nbt publicados en el datapack DE LA
		//PARTIDA actual (ver DungeonManager), así que necesitan la ruta real del server.getWorldPath(...) en
		//vez de la carpeta de instancia compartida que usa el resto de esta clase.
		DungeonPieceRegistry.load(event.getServer());
	}

	//Archivo único donde el creador de contenido in-game (ver ContentPackFile) guarda todo lo que un DM crea
	//desde el juego, por tipo — separado de cualquier pack escrito a mano para no arriesgarnos a pisarlo.
	public static Path dmCreatedFile(Path dir) {
		return dir.resolve("dm_created.json");
	}

	//Público: cada comando *Command lo usa para que el argumento "archivo" de su "load" se autocomplete
	//con tab en el chat, en vez de dejar al DM adivinar de memoria el nombre exacto del .json.
	public static List<String> jsonFileNames(Path dir) {
		try (Stream<Path> files = Files.list(dir)) {
			return files.filter(p -> p.toString().endsWith(".json"))
				.map(p -> p.getFileName().toString().replace(".json", ""))
				.toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	private static void createIfMissing(Path dir) {
		try {
			Files.createDirectories(dir);
		} catch (IOException e) {
			//No pasa nada grave: los comandos /dnd* fallan con un mensaje claro si de verdad no existe al leer.
		}
	}

	/** {@link ContentDefaults#refresh} con el aviso al log, que es lo único que no se puede comprobar en el self-test. */
	private static void refreshDefaultsLogging(Path dir, String resourceFileName) {
		try {
			Path retired = ContentDefaults.refresh(dir, resourceFileName);
			if (retired == null) return;
			DndsheetsMod.LOGGER.warn("dndsheets: {} era el pack por defecto de una versión anterior y se ha apartado como {}. "
				+ "El contenido del mod vive ahora en {}, que se actualiza solo en cada arranque. Si lo habías editado a mano, "
				+ "renómbralo a algo propio (p. ej. mis_{}) y volverá a cargarse, pisando lo del mod por id.",
				dir.resolve(resourceFileName), retired.getFileName(), ContentDefaults.FILE, resourceFileName);
		} catch (IOException e) {
			DndsheetsMod.LOGGER.warn("dndsheets: no pude actualizar el contenido por defecto de {}: {}", dir, e.getMessage());
		}
	}

	private static void autoLoadAll(Path dir, FileLoader loader, String label) {
		int filesLoaded = 0;
		int itemsLoaded = 0;
		try (Stream<Path> files = Files.list(dir)) {
			//El pack del mod SIEMPRE primero, y el resto detrás por nombre: NamedRegistry.register pisa por
			//id, así que quien carga el último gana. Ese orden es lo que convierte "reescribimos el pack del
			//mod en cada arranque" en algo seguro — lo que el DM escriba en su propio archivo sigue mandando.
			//Sin ordenar, el orden lo decidía el sistema de archivos y quién ganaba era cuestión de suerte.
			for (Path file : files.filter(p -> p.toString().endsWith(".json"))
					.sorted(java.util.Comparator.comparing((Path p) -> ContentDefaults.FILE.equals(p.getFileName().toString()) ? 0 : 1)
						.thenComparing(p -> p.getFileName().toString()))
					.toList()) {
				try {
					itemsLoaded += loader.load(file);
					filesLoaded++;
				} catch (IOException | RuntimeException e) {
					DndsheetsMod.LOGGER.warn("dndsheets: no pude precargar {}: {}", file.getFileName(), e.getMessage());
				}
			}
		} catch (IOException e) {
			DndsheetsMod.LOGGER.warn("dndsheets: no pude listar {}", dir);
			return;
		}
		if (filesLoaded > 0) {
			DndsheetsMod.LOGGER.info("dndsheets: precargados {} {} desde {} archivo(s) en {}", itemsLoaded, label, filesLoaded, dir);
		}
	}
}
