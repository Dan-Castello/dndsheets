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
 * <p>Razas del SRD 5e: mismo molde que {@code TraitRegistry}/{@code PresetRegistry} del core (registro
 * en memoria, cargado en caliente desde JSON, sin motor genérico — un campo por efecto, una rama donde se
 * consume). El Aumento de Característica y la velocidad se escriben directo en la ficha (igual que ya
 * hace {@code PresetRegistry.applyToSheet} con la clase); los rasgos de comportamiento (Ascendencia
 * Feérica, Suerte, Resistencia Implacable...) se conceden como {@link TraitRegistry} descriptivos, sin
 * automatizar mecánica que el motor de combate no modela todavía — mismo criterio que ya documenta
 * {@code TraitRegistry} para los rasgos de clase.</p>
 *
 * <p>La visión en la oscuridad NO se toca aquí: {@code CharacterRules.darkvisionFeetFor} ya la deriva del
 * texto de {@code characterRace} por subcadena (elfo/enano/gnomo/orco/tiefling), así que escribir el
 * nombre de la raza alcanza — duplicarla en un campo nuevo sería dos fuentes para lo mismo.</p>
 */
@Mod.EventBusSubscriber
public class RaceRegistry {
	public record Race(String id, String name, Map<String, Integer> abilityBonus, Integer speed, List<String> traits, String note) {}

	private static final NamedRegistry<Race> REGISTRY = new NamedRegistry<>("raza", Race::id);
	private static final List<String> ABILITY_KEYS = List.of("strength", "dexterity", "constitution", "intelligence", "wisdom", "charisma");

	//9 razas del SRD 5.1, en Java y no en JSON a proposito (mismo motivo que el CharacterOptionsRegistry
	//original: "para que funcione sin ningun JSON de por medio"). /dndspecies load sigue sirviendo para
	//que un DM reemplace una entrada por id con su propia version (homebrew), igual que FeatRegistry.
	static {
		register(new Race("human", "Humano", Map.of(
			"strength", 1, "dexterity", 1, "constitution", 1, "intelligence", 1, "wisdom", 1, "charisma", 1
		), 30, List.of(), null));
		register(new Race("elf", "Elfo", Map.of("dexterity", 2), 30,
			List.of("dndsheets_species:fey_ancestry", "dndsheets_species:trance"), null));
		register(new Race("dwarf", "Enano", Map.of("constitution", 2), 25,
			List.of("dndsheets_species:dwarven_resilience", "dndsheets_species:stonecunning"), null));
		register(new Race("halfling", "Mediano", Map.of("dexterity", 2), 25,
			List.of("dndsheets_species:lucky", "dndsheets_species:brave"), null));
		register(new Race("dragonborn", "Dracónido", Map.of("strength", 2, "charisma", 1), 30,
			List.of("dndsheets_species:draconic_ancestry"), null));
		register(new Race("gnome", "Gnomo", Map.of("intelligence", 2), 25,
			List.of("dndsheets_species:gnome_cunning"), null));
		register(new Race("half_elf", "Semielfo", Map.of("charisma", 2), 30,
			List.of("dndsheets_species:fey_ancestry", "dndsheets_species:skill_versatility"),
			"El SRD también da +1 a otras dos características a elección — ajustalas a mano en Ajustar Ficha."));
		register(new Race("half_orc", "Semiorco", Map.of("strength", 2, "constitution", 1), 30,
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

	private static final JsonRegistryLoader<Race> LOADER = new JsonRegistryLoader<>("raza", RaceRegistry::parse, RaceRegistry::register);

	public static int loadFile(Path file) throws IOException {
		return LOADER.loadFile(file);
	}

	//Homebrew opcional: un JSON en dndsheets/races/<id>.json reemplaza esa raza SRD por la del DM (mismo
	//id de NamedRegistry.register: pisa con aviso, no funde campos). Sin carpeta o sin archivos, las 9
	//del static{} de arriba quedan como están — no hace falta ningún JSON para jugar.
	@SubscribeEvent
	public static void onServerStarting(ServerStartingEvent event) {
		if (!Files.isDirectory(DndPaths.RACES_DIR)) return;
		try (Stream<Path> files = Files.list(DndPaths.RACES_DIR)) {
			for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
				try {
					loadFile(file);
				} catch (IOException | RuntimeException e) {
					net.hawthorn.dndsheets.DndsheetsMod.LOGGER.warn("dndsheets_species: no pude cargar {}: {}", file, e.toString());
				}
			}
		} catch (IOException e) {
			net.hawthorn.dndsheets.DndsheetsMod.LOGGER.warn("dndsheets_species: no pude listar {}: {}", DndPaths.RACES_DIR, e.toString());
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

	//--- Sheet: aplicar/reemplazar la raza elegida ---

	public enum ApplyOutcome {APPLIED, NO_CHANGE, UNKNOWN_RACE}

	public record ApplyResult(ApplyOutcome outcome, Race race) {}

	/**
	 * <p>Aditivo, no absoluto (a diferencia de {@code PresetRegistry.applyToSheet}): el Aumento de
	 * Característica del SRD se suma sobre lo que ya haya en la ficha, no lo reemplaza. Por eso, si ya
	 * había OTRA raza aplicada, primero le resta su bono — sin esto, cambiar de Humano a Elfo dejaría los
	 * dos bonos apilados.</p>
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
