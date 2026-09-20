package net.hawthorn.dndsheets;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <p>Feats: what 5e lets you take <b>instead of</b> the Ability Score Improvement at levels 4, 8, 12, 16
 * and 19. They're not a separate resource — they spend the same pending improvement
 * {@link LevelUpManager} already tracks, which is exactly what makes it a decision: raise two ability
 * points or do something else.</p>
 *
 * <p><b>SRD 5.1 shipped a single feat</b> (Grappler), and for a while that was all that could be
 * distributed. <b>SRD 5.2</b> (2024, also CC-BY-4.0) published the full list, so now there are 16 that can
 * be handed out: origin feats, combat fighting styles, and Epic Boons. They were imported with
 * {@code tools/import_srd.py} (see the SRD attribution in {@code PROJECT_CONTEXT.md}), and a table or an
 * addon can still add their own <b>by dropping a JSON into a folder</b>, same as with spells, monsters or
 * encounters.</p>
 *
 * <p>What SRD 5.2 forced this to model is the <b>minimum level</b>: Epic Boons are level 19 and fighting
 * styles are level 1, and offering all of them together at the level 4 improvement would turn the list
 * into a shop full of things the character can't actually have.</p>
 *
 * <p>A feat grants the same things a preset or a subclass does — traits and spells — plus the one thing
 * feats do that those don't: <b>raising ability scores</b>. That's the part with real mechanical weight,
 * because the six scores already drive everything else; the cap of 20 is the same as the improvement's,
 * and for the same reason.</p>
 */
public class FeatRegistry {

	public record Feat(String id, String name, String description, Map<String, Integer> abilities,
			List<String> traits, List<String> spells, int minLevel) {
	}

	private static final NamedRegistry<Feat> REGISTRY = new NamedRegistry<>("feat", Feat::id);

	public static void register(Feat feat) {
		REGISTRY.register(feat);
	}

	public static Feat get(String id) {
		return REGISTRY.get(id);
	}

	public static Set<String> ids() {
		return REGISTRY.ids();
	}

	public static boolean remove(String id) {
		return REGISTRY.remove(id);
	}

	private static final JsonRegistryLoader<Feat> LOADER =
		new JsonRegistryLoader<>("feat", FeatRegistry::parse, FeatRegistry::register);

	public static int loadFile(Path file) throws IOException {
		return LOADER.loadFile(file);
	}

	public static int loadJson(JsonElement root, String source, java.util.function.Consumer<String> onId) {
		return LOADER.loadJson(root, source, onId);
	}

	public static Feat parse(JsonObject json) {
		String id = json.get("id").getAsString();
		String name = json.has("name") ? json.get("name").getAsString() : id;
		String description = json.has("description") ? json.get("description").getAsString() : "";

		Map<String, Integer> abilities = new LinkedHashMap<>();
		if (json.has("abilities")) {
			JsonObject scores = json.getAsJsonObject("abilities");
			for (String key : scores.keySet()) abilities.put(key, scores.get(key).getAsInt());
		}

		List<String> traits = new ArrayList<>();
		if (json.has("traits")) for (JsonElement el : json.getAsJsonArray("traits")) traits.add(el.getAsString());
		List<String> spells = new ArrayList<>();
		if (json.has("spells")) for (JsonElement el : json.getAsJsonArray("spells")) spells.add(el.getAsString());

		//Minimum level. Absent = 1 = can be taken as soon as there's a pending improvement, which is how
		//they all behaved before SRD 5.2 brought feats that are NOT level 1.
		int minLevel = json.has("minLevel") ? Math.max(1, json.get("minLevel").getAsInt()) : 1;

		return new Feat(id, name, description, abilities, traits, spells, minLevel);
	}

	/** Can this character still take this feat? Level only — "already has it" is {@link #takenBy}. */
	public static boolean availableAt(Feat feat, int characterLevel) {
		return feat != null && characterLevel >= feat.minLevel();
	}

	/** The feats this sheet already has. A feat isn't taken twice. */
	public static List<String> takenBy(JsonObject sheet) {
		List<String> taken = new ArrayList<>();
		if (sheet == null || !sheet.has("feats")) return taken;
		for (JsonElement el : sheet.getAsJsonArray("feats")) taken.add(el.getAsString());
		return taken;
	}

	/**
	 * <p>Writes the feat onto the sheet and grants what it gives. It doesn't check whether there was a
	 * pending improvement: that's {@link LevelUpManager#applyFeat}'s job, since it's the one that spends
	 * it — here there's only what a feat <em>is</em>, so it can be checked without a server in front of it.</p>
	 *
	 * @return false if the feat doesn't exist, if this character already had it, or if they're not high enough level for it yet.
	 */
	public static boolean grant(JsonObject sheet, String featId, int maxAbility, int characterLevel) {
		Feat feat = get(featId);
		if (sheet == null || feat == null || takenBy(sheet).contains(featId)) return false;
		//Level is checked HERE and not just in the screen that offers it: the list is a suggestion, the
		//grant is the rule. A level 19 Epic Boon arrives through the same network message as the rest.
		if (characterLevel < feat.minLevel()) return false;

		com.google.gson.JsonArray feats = sheet.has("feats") ? sheet.getAsJsonArray("feats") : new com.google.gson.JsonArray();
		feats.add(featId);
		sheet.add("feats", feats);

		for (Map.Entry<String, Integer> bonus : feat.abilities().entrySet()) {
			String key = CharacterRules.abilityFieldFor(bonus.getKey());
			if (key == null) continue;
			int score = 10;
			try {
				score = Integer.parseInt(sheet.get(key).getAsString());
			} catch (RuntimeException ignored) {
				//A sheet with that ability score not written stays at 10, which is the default value.
			}
			//Cap of 20, same as the improvement: a feat that skipped it would be a better improvement than the improvement.
			sheet.addProperty(key, String.valueOf(Math.min(maxAbility, score + bonus.getValue())));
		}
		for (String traitId : feat.traits()) TraitRegistry.grant(sheet, traitId);
		for (String spellId : feat.spells()) SpellRegistry.learn(sheet, spellId);
		return true;
	}
}
