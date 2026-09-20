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
 * <p>Single folder for all of the mod's JSON-loadable content: {@code <world>/dndsheets/weapons},
 * {@code /spells}, {@code /monsters} and {@code /presets}. They're created on their own when any server
 * starts (singleplayer or multiplayer), and EVERY .json inside gets loaded automatically, with no need
 * to run {@code /dndweapons load} (etc.) by hand — those commands still work for hot-reloading without
 * restarting the server.</p>
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
	public static final Path ENCOUNTERS_DIR = ROOT.resolve("encounters");
	public static final Path FEATS_DIR = ROOT.resolve("feats");
	public static final Path RACES_DIR = ROOT.resolve("races");
	public static final Path BACKGROUNDS_DIR = ROOT.resolve("backgrounds");
	public static final Path CLASSES_DIR = ROOT.resolve("classes");
	/**
	 * <p>Library of {@code .nbt} structures the DM brings in from outside, to import as dungeon pieces
	 * (see {@code DungeonManager.importStructure}). It lives here and not in the world folder <b>on
	 * purpose</b>: pieces belong to one campaign, but a downloaded house is useful in all of them.</p>
	 */
	public static final Path STRUCTURES_DIR = ROOT.resolve("structures");

	/** The DM's appearance packs: see {@link MonsterSkins}. Not content, it's "which model each block uses." */
	public static final Path SKINS_DIR = ROOT.resolve("skins");

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
		createIfMissing(ENCOUNTERS_DIR);
		createIfMissing(FEATS_DIR);
		createIfMissing(RACES_DIR);
		createIfMissing(BACKGROUNDS_DIR);
		createIfMissing(CLASSES_DIR);
		createIfMissing(STRUCTURES_DIR);
		createIfMissing(SKINS_DIR);

		//Default content: so a new player doesn't have to write weapons/spells/monsters/presets/traits
		//from scratch before being able to play (the same pack bundled inside the mod).
		//Races/backgrounds/classes don't need this: CharacterOptionsRegistry already ships a default
		//list in code, with no JSON involved.
		refreshDefaultsLogging(WEAPONS_DIR, "weapons.json");
		refreshDefaultsLogging(SPELLS_DIR, "spells.json");
		refreshDefaultsLogging(ITEMS_DIR, "items.json");
		refreshDefaultsLogging(MONSTERS_DIR, "monsters.json");
		refreshDefaultsLogging(TRAITS_DIR, "traits.json");
		refreshDefaultsLogging(PRESETS_DIR, "presets.json");
		refreshDefaultsLogging(ENCOUNTERS_DIR, "encounters.json");
		refreshDefaultsLogging(FEATS_DIR, "feats.json");

		autoLoadAll(WEAPONS_DIR, Config::loadFile, "weapons");
		autoLoadAll(SPELLS_DIR, SpellRegistry::loadFile, "spells");
		autoLoadAll(ITEMS_DIR, MagicItemRegistry::loadFile, "magic items");
		autoLoadAll(MONSTERS_DIR, MonsterRegistry::loadFile, "monsters");
		autoLoadAll(TRAITS_DIR, TraitRegistry::loadFile, "traits");
		autoLoadAll(PRESETS_DIR, PresetRegistry::loadFile, "presets");
		autoLoadAll(ENCOUNTERS_DIR, EncounterRegistry::loadFile, "encounters");
		autoLoadAll(FEATS_DIR, FeatRegistry::loadFile, "feats");
		//RACES_DIR and BACKGROUNDS_DIR are no longer loaded here: race and background moved to the
		//dndsheets_species addon (Origins picks, the addon applies the SRD) — see
		//RaceRegistry/BackgroundRegistry, which listen for their own ServerStartingEvent over those same
		//folders. Only Class remains here.
		autoLoadAll(CLASSES_DIR, file -> CharacterOptionsRegistry.loadFile(CharacterOptionsRegistry.CLASS, file), "classes");

		//The LAST thing that touches the bestiary: it changes the model of whatever is already
		//registered, whether it came from the mod's pack, a datapack, or the DM. If this ran earlier, the
		//next load would overwrite it.
		MonsterSkins.applyAll();

		//Dungeon piece loading used to live here (per-world, with the real path from
		//server.getWorldPath(...)) until the dungeon toolkit moved to its own addon (dndsheets_dungeon,
		//see Modularity Map). That addon listens for this same event on its own — this mod can no longer
		//import its class without inverting the core→addon dependency.
	}

	//Single file where the in-game content creator (see ContentPackFile) saves everything a DM creates
	//from within the game, per type — kept separate from any hand-written pack to avoid risking overwriting it.
	public static Path dmCreatedFile(Path dir) {
		return dir.resolve("dm_created.json");
	}

	//Public: every *Command uses it so the "file" argument of its "load" tab-completes in chat, instead
	//of leaving the DM to guess the exact .json name from memory.
	public static List<String> jsonFileNames(Path dir) {
		return fileNames(dir, ".json");
	}

	/** The names (without extension) of the files of a type in a folder, for tab-completion. */
	public static List<String> fileNames(Path dir, String extension) {
		try (Stream<Path> files = Files.list(dir)) {
			return files.filter(p -> p.toString().endsWith(extension))
				.map(p -> {
					String name = p.getFileName().toString();
					return name.substring(0, name.length() - extension.length());
				})
				.toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	private static void createIfMissing(Path dir) {
		try {
			Files.createDirectories(dir);
		} catch (IOException e) {
			//Nothing serious happens: the /dnd* commands fail with a clear message if it truly doesn't exist on read.
		}
	}

	/** {@link ContentDefaults#refresh} with the log warning, the one part that can't be checked by the self-test. */
	private static void refreshDefaultsLogging(Path dir, String resourceFileName) {
		try {
			Path retired = ContentDefaults.refresh(dir, resourceFileName);
			if (retired == null) return;
			DndsheetsMod.LOGGER.warn("dndsheets: {} was the default pack of a previous version and has been set aside as {}. "
				+ "The mod's content now lives in {}, which is refreshed on every startup. If you had edited it by hand, "
				+ "rename it to something of your own (e.g. my_{}) and it will load again, overriding the mod's entries by id.",
				dir.resolve(resourceFileName), retired.getFileName(), ContentDefaults.FILE, resourceFileName);
		} catch (IOException e) {
			DndsheetsMod.LOGGER.warn("dndsheets: could not update the default content of {}: {}", dir, e.getMessage());
		}
	}

	private static void autoLoadAll(Path dir, FileLoader loader, String label) {
		int filesLoaded = 0;
		int itemsLoaded = 0;
		try (Stream<Path> files = Files.list(dir)) {
			//The mod's pack ALWAYS first, and the rest afterward by name: NamedRegistry.register
			//overwrites by id, so whoever loads last wins. That ordering is what makes "we rewrite the
			//mod's pack on every startup" safe — whatever the DM writes in their own file still wins.
			//Without sorting, the order was up to the filesystem and who won was a matter of luck.
			for (Path file : files.filter(p -> p.toString().endsWith(".json"))
					.sorted(java.util.Comparator.comparing((Path p) -> ContentDefaults.FILE.equals(p.getFileName().toString()) ? 0 : 1)
						.thenComparing(p -> p.getFileName().toString()))
					.toList()) {
				try {
					itemsLoaded += loader.load(file);
					filesLoaded++;
				} catch (IOException | RuntimeException e) {
					DndsheetsMod.LOGGER.warn("dndsheets: could not preload {}: {}", file.getFileName(), e.getMessage());
				}
			}
		} catch (IOException e) {
			DndsheetsMod.LOGGER.warn("dndsheets: could not list {}", dir);
			return;
		}
		if (filesLoaded > 0) {
			DndsheetsMod.LOGGER.info("dndsheets: preloaded {} {} from {} file(s) in {}", itemsLoaded, label, filesLoaded, dir);
		}
	}
}
