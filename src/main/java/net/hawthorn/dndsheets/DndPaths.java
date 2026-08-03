package net.hawthorn.dndsheets;

import net.hawthorn.dndsheets.command.CharacterOptionsCommand;
import net.hawthorn.dndsheets.command.MonsterCommand;
import net.hawthorn.dndsheets.command.PresetCommand;
import net.hawthorn.dndsheets.command.SpellCommand;
import net.hawthorn.dndsheets.command.TraitCommand;
import net.hawthorn.dndsheets.command.WeaponCommand;
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
		createIfMissing(MONSTERS_DIR);
		createIfMissing(PRESETS_DIR);
		createIfMissing(TRAITS_DIR);
		createIfMissing(RACES_DIR);
		createIfMissing(BACKGROUNDS_DIR);
		createIfMissing(CLASSES_DIR);

		autoLoadAll(WEAPONS_DIR, WeaponCommand::loadFile, "armas");
		autoLoadAll(SPELLS_DIR, SpellCommand::loadFile, "hechizos");
		autoLoadAll(MONSTERS_DIR, MonsterCommand::loadFile, "monstruos");
		autoLoadAll(TRAITS_DIR, TraitCommand::loadFile, "rasgos");
		autoLoadAll(PRESETS_DIR, PresetCommand::loadFile, "presets");
		//Categorías del selector de Raza/Trasfondo/Clase (ver CharacterOptionsRegistry): reemplazan la
		//lista por defecto entera si hay un .json en la carpeta, no la extienden.
		autoLoadAll(RACES_DIR, file -> CharacterOptionsCommand.loadFile(CharacterOptionsRegistry.RACE, file), "razas");
		autoLoadAll(BACKGROUNDS_DIR, file -> CharacterOptionsCommand.loadFile(CharacterOptionsRegistry.BACKGROUND, file), "trasfondos");
		autoLoadAll(CLASSES_DIR, file -> CharacterOptionsCommand.loadFile(CharacterOptionsRegistry.CLASS, file), "clases");
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

	private static void autoLoadAll(Path dir, FileLoader loader, String label) {
		int filesLoaded = 0;
		int itemsLoaded = 0;
		try (Stream<Path> files = Files.list(dir)) {
			for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
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
