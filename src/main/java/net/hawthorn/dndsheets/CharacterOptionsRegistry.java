package net.hawthorn.dndsheets;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>List of the player's Class options: it used to be free text in {@link
 * net.hawthorn.dndsheets.client.gui.CharacterSheetScreen} (a new player had no way to guess what to
 * write, and it actually mattered — see {@link Config#hitDieFor},
 * {@link WarlockPactMagicManager}, {@link WizardArcaneRecoveryManager}, which compare by substring).
 * Now it's chosen with a list GUI (see {@code BrowseActionMessage.CHARACTER_OPTIONS} and
 * {@code client.gui.CharacterOptionListScreen}), instead of being typed by hand.</p>
 *
 * <p><b>Race and Background no longer live here</b>: they moved to the {@code dndsheets_species} addon
 * (separate modid, hard dependency on Origins) — see Modularity Map / Library Audit. Origins picks, that
 * addon applies the SRD's Ability Score Increase/proficiencies with its own {@code RaceRegistry}/
 * {@code BackgroundRegistry}, same mold as this one.</p>
 *
 * <p>Ships with default values (SRD 5e; these are exactly the ones {@code Config.hitDieFor} and the
 * warlock/wizard managers already recognize by substring) so it works with no JSON involved at all. A
 * pack in {@code dndsheets/classes/*.json} (see {@code command.CharacterOptionsCommand}) REPLACES the
 * whole list, it doesn't extend it — there's no "id" separate from the text here, the chosen value is
 * literally what gets written on the sheet, so there's nothing to merge between files.</p>
 */
public class CharacterOptionsRegistry {
	public static final String CLASS = "class";
	//Race and background moved to the species addon (Origins picks, the addon applies the mechanics),
	//but core-only is also a supported configuration and without these lists its character-creation
	//steps pointed at a command that doesn't exist. These are the name-only FALLBACK (9 races and 13
	//backgrounds, same names as the addon) — no Ability Score Increase or traits, that's the addon's job.
	public static final String RACE = "race";
	public static final String BACKGROUND = "background";

	private static final Map<String, List<String>> OPTIONS = new LinkedHashMap<>();

	static {
		OPTIONS.put(CLASS, List.of(
			"Barbarian", "Bard", "Cleric", "Druid", "Fighter", "Monk",
			"Paladin", "Ranger", "Rogue", "Sorcerer", "Warlock", "Wizard"
		));
		OPTIONS.put(RACE, List.of(
			"Dwarf", "Elf", "Halfling", "Human", "Dragonborn", "Gnome", "Half-Elf", "Half-Orc", "Tiefling"
		));
		//The 13 PHB backgrounds, SAME NAMES as the species addon (see its BackgroundRegistry): with the
		//addon installed Origins picks them and the addon applies proficiencies/gold, without it this
		//just writes the name onto the sheet and that's it. With only "Acolyte" in the list, "choosing a
		//background" wasn't a choice — you'd open the selector and only find the one you already had.
		OPTIONS.put(BACKGROUND, List.of(
			"Acolyte", "Charlatan", "Criminal", "Entertainer", "Folk Hero", "Guild Artisan",
			"Hermit", "Noble", "Outlander", "Sage", "Sailor", "Soldier", "Urchin"
		));
	}

	public static boolean isValidCategory(String category) {
		return OPTIONS.containsKey(category);
	}

	public static List<String> get(String category) {
		return OPTIONS.getOrDefault(category, List.of());
	}

	//Public: also used by CharacterOptionsCommand when loading a file for the category.
	public static void replace(String category, List<String> values) {
		OPTIONS.put(category, values);
	}

	//Public: used by CharacterOptionsCommand (/dndoptions load) and by DndPaths to preload all of the
	//folder's .json files on server startup, without DndPaths having to depend on the command layer.
	//REPLACES the category's whole list, it doesn't extend it (see class comment).
	public static int loadFile(String category, Path file) throws IOException {
		String json = Files.readString(file);
		JsonArray array = JsonParser.parseString(json).getAsJsonArray();
		List<String> values = new ArrayList<>();
		for (var element : array) values.add(element.getAsString());
		replace(category, values);
		return values.size();
	}
}
