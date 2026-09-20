package net.hawthorn.dndsheets.species;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.DndPaths;
import net.hawthorn.dndsheets.JsonRegistryLoader;
import net.hawthorn.dndsheets.NamedRegistry;
import net.hawthorn.dndsheets.TraitRegistry;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * <p>SRD 5e races: same mold as the core's {@code TraitRegistry}/{@code PresetRegistry} (in-memory
 * registry, hot-loaded from JSON, no generic engine — one field per effect, one branch where it's
 * consumed). The Ability Score Increase and speed are written directly onto the sheet (same as
 * {@code PresetRegistry.applyToSheet} already does for class); behavioral traits (Fey Ancestry, Lucky,
 * Relentless Endurance...) are granted as descriptive {@link TraitRegistry} entries, without automating
 * mechanics the combat engine doesn't model yet — same criterion {@code TraitRegistry} already documents
 * for class traits.</p>
 *
 * <p>Darkvision is NOT touched here: {@code CharacterRules.darkvisionFeetFor} already derives it from the
 * {@code characterRace} text by substring match (elf/dwarf/gnome/orc/tiefling), so writing the race name
 * is enough — duplicating it into a new field would be two sources of truth for the same thing.</p>
 */
@Mod.EventBusSubscriber
public class RaceRegistry {
	public record Race(String id, String name, Map<String, Integer> abilityBonus, Integer speed, List<String> traits, String note) {}

	private static final NamedRegistry<Race> REGISTRY = new NamedRegistry<>("race", Race::id);
	private static final List<String> ABILITY_KEYS = List.of("strength", "dexterity", "constitution", "intelligence", "wisdom", "charisma");

	//9 SRD 5.1 races, in Java and not JSON on purpose (same reason as the original CharacterOptionsRegistry:
	//"so it works with no JSON involved"). /dndspecies load still serves to let a DM replace an entry by
	//id with their own version (homebrew), same as FeatRegistry.
	static {
		register(new Race("human", "Human", Map.of(
			"strength", 1, "dexterity", 1, "constitution", 1, "intelligence", 1, "wisdom", 1, "charisma", 1
		), 30, List.of(), null));
		register(new Race("elf", "Elf", Map.of("dexterity", 2), 30,
			List.of("dndsheets_species:fey_ancestry", "dndsheets_species:trance"), null));
		register(new Race("dwarf", "Dwarf", Map.of("constitution", 2), 25,
			List.of("dndsheets_species:dwarven_resilience", "dndsheets_species:stonecunning"), null));
		register(new Race("halfling", "Halfling", Map.of("dexterity", 2), 25,
			List.of("dndsheets_species:lucky", "dndsheets_species:brave"), null));
		register(new Race("dragonborn", "Dragonborn", Map.of("strength", 2, "charisma", 1), 30,
			List.of("dndsheets_species:draconic_ancestry"), null));
		register(new Race("gnome", "Gnome", Map.of("intelligence", 2), 25,
			List.of("dndsheets_species:gnome_cunning"), null));
		register(new Race("half_elf", "Half-Elf", Map.of("charisma", 2), 30,
			List.of("dndsheets_species:fey_ancestry", "dndsheets_species:skill_versatility"),
			"The SRD also gives +1 to two other abilities of your choice — adjust them by hand on the character sheet."));
		register(new Race("half_orc", "Half-Orc", Map.of("strength", 2, "constitution", 1), 30,
			List.of("dndsheets_species:relentless_endurance", "dndsheets_species:savage_attacks"), null));
		register(new Race("tiefling", "Tiefling", Map.of("charisma", 2, "intelligence", 1), 30,
			List.of("dndsheets_species:hellish_resistance", "dndsheets_species:infernal_legacy"), null));
	}

	public static void register(Race race) {
		REGISTRY.register(race);
	}

	public static Race get(String id) {
		return REGISTRY.get(id);
	}

	public static Set<String> ids() {
		return REGISTRY.ids();
	}

	private static final JsonRegistryLoader<Race> LOADER = new JsonRegistryLoader<>("race", RaceRegistry::parse, RaceRegistry::register);

	public static int loadFile(Path file) throws IOException {
		return LOADER.loadFile(file);
	}

	//Optional homebrew: a JSON in dndsheets/races/<id>.json replaces that SRD race with the DM's own (same
	//id as NamedRegistry.register: overwrites with a warning, doesn't merge fields). With no folder or no
	//files, the 9 from the static{} block above stay as they are — no JSON is required to play.
	@SubscribeEvent
	public static void onServerStarting(ServerStartingEvent event) {
		if (!Files.isDirectory(DndPaths.RACES_DIR)) return;
		try (Stream<Path> files = Files.list(DndPaths.RACES_DIR)) {
			for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
				try {
					loadFile(file);
				} catch (IOException | RuntimeException e) {
					net.hawthorn.dndsheets.DndsheetsMod.LOGGER.warn("dndsheets_species: could not load {}: {}", file, e.toString());
				}
			}
		} catch (IOException e) {
			net.hawthorn.dndsheets.DndsheetsMod.LOGGER.warn("dndsheets_species: could not list {}: {}", DndPaths.RACES_DIR, e.toString());
		}
	}

	private static Race parse(JsonObject json) {
		String id = json.get("id").getAsString();
		String name = json.get("name").getAsString();
		Map<String, Integer> abilityBonus = new LinkedHashMap<>();
		if (json.has("abilityBonus")) {
			JsonObject bonuses = json.getAsJsonObject("abilityBonus");
			for (String key : ABILITY_KEYS) if (bonuses.has(key)) abilityBonus.put(key, bonuses.get(key).getAsInt());
		}
		Integer speed = json.has("speed") ? json.get("speed").getAsInt() : null;
		List<String> traits = new ArrayList<>();
		if (json.has("traits")) for (JsonElement el : json.getAsJsonArray("traits")) traits.add(el.getAsString());
		String note = json.has("note") ? json.get("note").getAsString() : null;
		return new Race(id, name, abilityBonus, speed, traits, note);
	}

	//--- Sheet: apply/replace the chosen race ---

	public enum ApplyOutcome {APPLIED, NO_CHANGE, UNKNOWN_RACE}

	public record ApplyResult(ApplyOutcome outcome, Race race) {}

	/**
	 * <p>Additive, not absolute (unlike {@code PresetRegistry.applyToSheet}): the SRD's Ability Score
	 * Increase adds onto whatever is already on the sheet, it doesn't replace it. That's why, if ANOTHER
	 * race was already applied, its bonus is subtracted first — without this, switching from Human to Elf
	 * would leave both bonuses stacked.</p>
	 */
	public static ApplyResult apply(JsonObject sheet, String raceId) {
		Race race = get(raceId);
		if (race == null) return new ApplyResult(ApplyOutcome.UNKNOWN_RACE, null);

		String previousId = sheet.has("appliedRaceId") ? sheet.get("appliedRaceId").getAsString() : "";
		if (previousId.equals(raceId)) return new ApplyResult(ApplyOutcome.NO_CHANGE, race);

		Race previous = previousId.isBlank() ? null : get(previousId);
		if (previous != null) {
			addAbilityBonus(sheet, previous, -1);
			for (String traitId : previous.traits()) TraitRegistry.revoke(sheet, traitId);
		}

		addAbilityBonus(sheet, race, 1);
		for (String traitId : race.traits()) TraitRegistry.grant(sheet, traitId);
		if (race.speed() != null) sheet.addProperty("speed", String.valueOf(race.speed()));
		sheet.addProperty("characterRace", race.name());
		sheet.addProperty("appliedRaceId", raceId);

		return new ApplyResult(ApplyOutcome.APPLIED, race);
	}

	private static void addAbilityBonus(JsonObject sheet, Race race, int sign) {
		for (Map.Entry<String, Integer> entry : race.abilityBonus().entrySet()) {
			String key = entry.getKey();
			int current = sheet.has(key) ? parseIntSafe(sheet.get(key).getAsString(), 10) : 10;
			sheet.addProperty(key, String.valueOf(current + sign * entry.getValue()));
		}
	}

	private static int parseIntSafe(String value, int fallback) {
		try {
			return Integer.parseInt(value.trim());
		} catch (RuntimeException e) {
			return fallback;
		}
	}
}
