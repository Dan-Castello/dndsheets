package net.hawthorn.dndsheets.species;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.DndPaths;
import net.hawthorn.dndsheets.JsonRegistryLoader;
import net.hawthorn.dndsheets.NamedRegistry;
import net.hawthorn.dndsheets.RollIndex;
import net.hawthorn.dndsheets.TraitRegistry;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * <p>SRD 5e backgrounds: same mold as {@link RaceRegistry} (in-memory registry, hot-loaded from JSON,
 * no generic engine). Unlike race, background DOES have an existing, automatable mechanic: the two
 * skill proficiencies (see {@link RollIndex#setSkillProficiency}). The background's feature (Shelter of
 * the Faithful, False Identity...) is narrative in the SRD — it's granted as a descriptive
 * {@link TraitRegistry} entry, same as racial traits.</p>
 *
 * <p>Deliberately does NOT automate the background's starting equipment/tools/languages: nothing in the
 * engine consumes that today (unlike proficiencies, which the whole roll system already reads), so it
 * would be mechanics with no one using them — same criterion as {@code TraitRegistry}.</p>
 */
@Mod.EventBusSubscriber
public class BackgroundRegistry {
	public record Background(String id, String name, int skillA, int skillB, List<String> traits) {}

	private static final NamedRegistry<Background> REGISTRY = new NamedRegistry<>("background", Background::id);

	static {
		register(new Background("acolyte", "Acolyte", 10, 8, List.of("dndsheets_species:shelter_of_the_faithful")));
		register(new Background("charlatan", "Charlatan", 14, 2, List.of("dndsheets_species:false_identity")));
		register(new Background("criminal", "Criminal", 14, 3, List.of("dndsheets_species:criminal_contact")));
		register(new Background("entertainer", "Entertainer", 1, 16, List.of("dndsheets_species:by_popular_demand")));
		register(new Background("folk_hero", "Folk Hero", 9, 13, List.of("dndsheets_species:rustic_hospitality")));
		register(new Background("guild_artisan", "Guild Artisan", 10, 17, List.of("dndsheets_species:guild_membership")));
		register(new Background("hermit", "Hermit", 11, 8, List.of("dndsheets_species:discovery")));
		register(new Background("noble", "Noble", 5, 17, List.of("dndsheets_species:position_of_privilege")));
		register(new Background("outlander", "Outlander", 0, 13, List.of("dndsheets_species:wanderer")));
		register(new Background("sage", "Sage", 4, 5, List.of("dndsheets_species:researcher")));
		register(new Background("sailor", "Sailor", 0, 12, List.of("dndsheets_species:ships_passage")));
		register(new Background("soldier", "Soldier", 0, 15, List.of("dndsheets_species:military_rank")));
		register(new Background("urchin", "Urchin", 2, 3, List.of("dndsheets_species:city_secrets")));
	}

	public static void register(Background background) {
		REGISTRY.register(background);
	}

	public static Background get(String id) {
		return REGISTRY.get(id);
	}

	public static Set<String> ids() {
		return REGISTRY.ids();
	}

	private static final JsonRegistryLoader<Background> LOADER = new JsonRegistryLoader<>("background", BackgroundRegistry::parse, BackgroundRegistry::register);

	public static int loadFile(Path file) throws IOException {
		return LOADER.loadFile(file);
	}

	private static Background parse(JsonObject json) {
		String id = json.get("id").getAsString();
		String name = json.get("name").getAsString();
		int skillA = json.get("skillA").getAsInt();
		int skillB = json.get("skillB").getAsInt();
		List<String> traits = new ArrayList<>();
		if (json.has("traits")) for (JsonElement el : json.getAsJsonArray("traits")) traits.add(el.getAsString());
		return new Background(id, name, skillA, skillB, traits);
	}

	//--- Sheet: apply/replace the chosen background ---

	public enum ApplyOutcome {APPLIED, NO_CHANGE, UNKNOWN_BACKGROUND}

	public record ApplyResult(ApplyOutcome outcome, Background background) {}

	public static ApplyResult apply(JsonObject sheet, String backgroundId) {
		Background background = get(backgroundId);
		if (background == null) return new ApplyResult(ApplyOutcome.UNKNOWN_BACKGROUND, null);

		String previousId = sheet.has("appliedBackgroundId") ? sheet.get("appliedBackgroundId").getAsString() : "";
		if (previousId.equals(backgroundId)) return new ApplyResult(ApplyOutcome.NO_CHANGE, background);

		Background previous = previousId.isBlank() ? null : get(previousId);
		if (previous != null) {
			RollIndex.setSkillProficiency(sheet, previous.skillA(), false);
			RollIndex.setSkillProficiency(sheet, previous.skillB(), false);
			for (String traitId : previous.traits()) TraitRegistry.revoke(sheet, traitId);
		}

		RollIndex.setSkillProficiency(sheet, background.skillA(), true);
		RollIndex.setSkillProficiency(sheet, background.skillB(), true);
		for (String traitId : background.traits()) TraitRegistry.grant(sheet, traitId);
		sheet.addProperty("background", background.name());
		sheet.addProperty("appliedBackgroundId", backgroundId);

		return new ApplyResult(ApplyOutcome.APPLIED, background);
	}

	//Optional homebrew: same pattern as RaceRegistry.onServerStarting, over dndsheets/backgrounds/*.json.
	@SubscribeEvent
	public static void onServerStarting(ServerStartingEvent event) {
		if (!Files.isDirectory(DndPaths.BACKGROUNDS_DIR)) return;
		try (Stream<Path> files = Files.list(DndPaths.BACKGROUNDS_DIR)) {
			for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
				try {
					loadFile(file);
				} catch (IOException | RuntimeException e) {
					net.hawthorn.dndsheets.DndsheetsMod.LOGGER.warn("dndsheets_species: could not load {}: {}", file, e.toString());
				}
			}
		} catch (IOException e) {
			net.hawthorn.dndsheets.DndsheetsMod.LOGGER.warn("dndsheets_species: could not list {}: {}", DndPaths.BACKGROUNDS_DIR, e.toString());
		}
	}
}
