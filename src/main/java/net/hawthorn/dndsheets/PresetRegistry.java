package net.hawthorn.dndsheets;

import javax.annotation.Nullable;
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
 * <p>Class presets hot-loaded by {@code /dndpresets load}, in memory (same as
 * {@link MonsterRegistry}/{@link SpellRegistry}). Picking one fills in the sheet's general values
 * (class, hit dice, ability scores) instead of writing them by hand field by field, and grants whatever
 * traits (passives/features) it's configured with — see {@link TraitRegistry}.</p>
 */
//No stability contract: this mod doesn't publish a versioned API (the DndSheetsApi facade was
//deleted — 233 lines that not a single caller used, addons included, which come in through here).
//An external mod calling these methods risks their signature changing without notice. The only
//thing meant for external consumption is the api/event events, which do have real consumers.
public class PresetRegistry {
	/**
	 * <p>A subclass (archetype): the second half of what a 5e character is, chosen a few levels after the
	 * class itself. It lives INSIDE its preset rather than in its own registry because a subclass without
	 * its class means nothing — "School of Evocation" isn't eligible for a barbarian, and a separate
	 * registry would force keeping the pairing consistent by hand in both directions.</p>
	 *
	 * <p>It grants exactly the same things a preset does (traits and spells) through the same path, so it
	 * adds no new way of granting anything. {@code criticalFrom} is the sole exception and exists for one
	 * single case: the fighter's Champion crits on 19, which is the one SRD subclass feature this engine
	 * can actually support without inventing a whole subsystem. Zero means "doesn't touch it".</p>
	 */
	public record Subclass(String id, String name, int level, List<String> traits, List<String> spells, int criticalFrom) {}

	public record ClassPreset(String id, String name, String hitDiceType, Map<String, Integer> abilities, String startingWeaponId, List<String> startingGear, int spellSlotsMax, List<String> traits, List<String> spells, List<Subclass> subclasses) {
		public int ability(String key) {
			Integer score = abilities.get(key);
			return score == null ? 10 : score;
		}
	}

	private static final NamedRegistry<ClassPreset> REGISTRY = new NamedRegistry<>("preset", ClassPreset::id);

	public static void register(ClassPreset preset) {
		REGISTRY.register(preset);
	}

	@Nullable
	public static ClassPreset get(String id) {
		return REGISTRY.get(id);
	}

	public static Set<String> ids() {
		return REGISTRY.ids();
	}

	public static boolean remove(String id) {
		return REGISTRY.remove(id);
	}

	//Public: used by PresetCommand (/dndpresets load) and by DndPaths to preload all the folder's
	//.json files on server startup, without DndPaths having to depend on the command layer.
	private static final JsonRegistryLoader<ClassPreset> LOADER = new JsonRegistryLoader<>("preset", PresetRegistry::parse, PresetRegistry::register);

	/** Loads from an already-parsed JSON (datapack or another mod's jar) — see ContentDatapackLoader. */
	public static int loadJson(com.google.gson.JsonElement root, String source, java.util.function.Consumer<String> onId) {
		return LOADER.loadJson(root, source, onId);
	}

	public static int loadFile(Path file) throws IOException {
		return LOADER.loadFile(file);
	}

	public static ClassPreset parse(JsonObject json) {
		String id = json.get("id").getAsString();
		String name = json.has("name") ? json.get("name").getAsString() : id;
		String hitDiceType = json.has("hitDiceType") ? json.get("hitDiceType").getAsString() : "1d8";

		Map<String, Integer> abilities = new LinkedHashMap<>();
		JsonObject abilitiesJson = json.has("abilities") ? json.getAsJsonObject("abilities") : null;
		for (String key : Combatant.ABILITIES) {
			abilities.put(key, abilitiesJson != null && abilitiesJson.has(key) ? abilitiesJson.get(key).getAsInt() : 10);
		}

		String startingWeaponId = json.has("startingWeapon") ? json.get("startingWeapon").getAsString() : null;
		int spellSlotsMax = json.has("spellSlotsMax") ? json.get("spellSlotsMax").getAsInt() : 0;

		List<String> traits = new ArrayList<>();
		if (json.has("traits")) {
			for (JsonElement el : json.getAsJsonArray("traits")) traits.add(el.getAsString());
		}

		List<String> spells = new ArrayList<>();
		if (json.has("spells")) {
			for (JsonElement el : json.getAsJsonArray("spells")) spells.add(el.getAsString());
		}

		//Starting gear: what the starting weapon doesn't cover and yet decides half the sheet. Armor from
		//here actually raises AC, because AC comes from a real Minecraft attribute — a freshly created
		//fighter was stuck at 10 + Dexterity until a DM remembered to give them a chestplate.
		List<String> startingGear = new ArrayList<>();
		if (json.has("startingGear")) {
			for (JsonElement el : json.getAsJsonArray("startingGear")) startingGear.add(el.getAsString());
		}

		List<Subclass> subclasses = new ArrayList<>();
		if (json.has("subclasses")) {
			for (JsonElement element : json.getAsJsonArray("subclasses")) {
				JsonObject entry = element.getAsJsonObject();
				List<String> subTraits = new ArrayList<>();
				if (entry.has("traits")) for (JsonElement el : entry.getAsJsonArray("traits")) subTraits.add(el.getAsString());
				List<String> subSpells = new ArrayList<>();
				if (entry.has("spells")) for (JsonElement el : entry.getAsJsonArray("spells")) subSpells.add(el.getAsString());
				subclasses.add(new Subclass(
					entry.get("id").getAsString(),
					entry.has("name") ? entry.get("name").getAsString() : entry.get("id").getAsString(),
					//Level 3 by default: that's what most SRD classes use, and a subclass with no level
					//written is almost always one that follows the norm.
					entry.has("level") ? entry.get("level").getAsInt() : 3,
					subTraits, subSpells,
					entry.has("criticalFrom") ? entry.get("criticalFrom").getAsInt() : 0));
			}
		}

		return new ClassPreset(id, name, hitDiceType, abilities, startingWeaponId, startingGear, spellSlotsMax, traits, spells, subclasses);
	}

	//Fills in the sheet's general fields. Doesn't touch "attacks" (see PresetManager, which also grants the actual starting weapon).
	public static void applyToSheet(JsonObject sheet, ClassPreset preset) {
		revokePreviousTraits(sheet);
		sheet.addProperty("appliedPresetId", preset.id());
		sheet.addProperty("characterClass", preset.name());
		sheet.addProperty("hitDiceTypes", preset.hitDiceType());
		sheet.addProperty("strength", String.valueOf(preset.ability("str")));
		sheet.addProperty("dexterity", String.valueOf(preset.ability("dex")));
		sheet.addProperty("constitution", String.valueOf(preset.ability("con")));
		sheet.addProperty("intelligence", String.valueOf(preset.ability("int")));
		sheet.addProperty("wisdom", String.valueOf(preset.ability("wis")));
		sheet.addProperty("charisma", String.valueOf(preset.ability("cha")));
		if (preset.spellSlotsMax() > 0) {
			//The preset's spellSlotsMax used to be the total for a LEVEL-1 character and never scaled. Now
			//the class decides the whole table; the field is kept only as a marker that "this class casts".
			SpellSlots.applyProgression(sheet, preset.name(), CharacterRules.levelOf(sheet));
		}
		for (String traitId : preset.traits()) TraitRegistry.grant(sheet, traitId);
		//Signature feature of a caster preset: without this the Spellbook stayed empty despite having
		//spell slots — the preset configured the slot COUNTER but never gave any spell to spend them on.
		for (String spellId : preset.spells()) SpellRegistry.learn(sheet, spellId);
	}

	/**
	 * <p>The subclasses this character can pick right now: the ones from their preset whose level they've
	 * already reached. With no preset applied there are none, which is correct — a subclass is a branch
	 * of the class, so you need a class first.</p>
	 */
	public static List<Subclass> availableSubclasses(JsonObject sheet) {
		if (sheet == null || !sheet.has("appliedPresetId")) return List.of();
		ClassPreset preset = get(sheet.get("appliedPresetId").getAsString());
		if (preset == null) return List.of();

		int level = CharacterRules.levelOf(sheet);
		List<Subclass> available = new ArrayList<>();
		for (Subclass subclass : preset.subclasses()) {
			if (level >= subclass.level()) available.add(subclass);
		}
		return available;
	}

	/**
	 * <p>Applies a subclass to the sheet. Returns false if that subclass doesn't belong to this
	 * character's class or if they don't have the level for it yet: this is checked here and not just
	 * when rendering the list, because a modified client can request any id — the same boundary as
	 * always.</p>
	 *
	 * <p><b>The choice is permanent</b>, like the warlock's pact: the previous one isn't revoked when
	 * picking another. Switching subclass means remaking the character, and stripping someone's traits
	 * mid-campaign because they clicked a row is worse than leaving them a subclass they didn't want,
	 * which the DM can fix.</p>
	 */
	public static boolean applySubclass(JsonObject sheet, String subclassId) {
		for (Subclass subclass : availableSubclasses(sheet)) {
			if (!subclass.id().equals(subclassId)) continue;

			sheet.addProperty("appliedSubclassId", subclass.id());
			//The name too, not just the id: that's what the client screen reads, since it doesn't have the
			//registry. Same pairing as appliedPresetId/characterClass.
			sheet.addProperty("characterSubclass", subclass.name());
			if (subclass.criticalFrom() > 0) sheet.addProperty("criticalFrom", String.valueOf(subclass.criticalFrom()));
			for (String traitId : subclass.traits()) TraitRegistry.grant(sheet, traitId);
			for (String spellId : subclass.spells()) SpellRegistry.learn(sheet, spellId);
			return true;
		}
		return false;
	}

	//Before granting the NEW preset's traits, revoke the previous preset's (if one was recorded and its id
	//is still loaded): without this, switching from "monk" to "wizard" left Martial Arts granted forever,
	//since TraitRegistry.grant only knows how to add. "appliedPresetId" is the only thing we need to store
	//to know what it was — no need to track the full trait list separately.
	private static void revokePreviousTraits(JsonObject sheet) {
		if (!sheet.has("appliedPresetId")) return;
		ClassPreset previous = get(sheet.get("appliedPresetId").getAsString());
		if (previous == null) return;
		for (String traitId : previous.traits()) TraitRegistry.revoke(sheet, traitId);
	}
}
