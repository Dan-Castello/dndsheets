package net.hawthorn.dndsheets.client.gui;

import net.minecraft.client.resources.language.I18n;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.Combatant;
import net.hawthorn.dndsheets.DamageTypes;
import net.hawthorn.dndsheets.MagicSchool;
import net.hawthorn.dndsheets.client.gui.ContentFormScreen.FieldSpec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>Field definitions + JSON (de)serialization for the 3 content types that fit
 * {@link ContentFormScreen} (flat schema, no nested lists): weapons, spells, presets. Each
 * {@code xFields()} method is what builds the screen, {@code xPrefill} reads an existing entry (as it
 * arrived over the network from {@code dm_created.json}) back into the same fields for editing,
 * {@code xToJson} builds what gets sent to save — same field names already read by
 * {@code Config.loadFile}/{@code SpellRegistry.parse}/{@code PresetRegistry.parse}, see those methods.</p>
 *
 * <p>Deliberate cut: to avoid piling up more than ~10 rows in a single-column form (see
 * {@code SmallFormScreen}), multi-value lists (a weapon's classes, a preset's traits/spells, the 6
 * ability scores) are written as comma-separated text in a single field, and spells with
 * {@code appliesEffect}/{@code aoeRadius}/{@code concentration} (Fireball, Moonbeam...) still need
 * hand-written JSON for now — this covers the common case, not every field of the schema.</p>
 */
final class ContentTypeForms {
	private static final String[] BOOL_OPTIONS = {"yes", "no"};

	//Cap for fields holding a comma-separated LIST. The normal text field's 64 characters are enough for
	//a name, not for three namespaced ids: "dndsheets:goblin x4, dndsheets:wolf x2, dndsheets:dire_wolf"
	//already exceeds it, and whatever overflows is silently lost. An encounter like that gets saved with
	//the last id truncated and it's only discovered when invoking it, as "a monster that doesn't exist".
	private static final int LIST_LENGTH = 256;

	private ContentTypeForms() {
	}

	private static int parseIntOr(String value, int fallback) {
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static void putIfPresent(Map<String, String> map, JsonObject entry, String key) {
		if (entry.has(key)) map.put(key, entry.get(key).getAsString());
	}

	private static String joinArray(JsonObject entry, String key) {
		if (!entry.has(key)) return "";
		StringBuilder sb = new StringBuilder();
		for (JsonElement el : entry.getAsJsonArray(key)) {
			if (sb.length() > 0) sb.append(", ");
			sb.append(el.getAsString());
		}
		return sb.toString();
	}

	private static void addCommaArray(JsonObject entry, String key, String csv) {
		if (csv == null || csv.isBlank()) return;
		JsonArray array = new JsonArray();
		for (String piece : csv.split(",")) {
			String trimmed = piece.trim();
			if (!trimmed.isEmpty()) array.add(trimmed);
		}
		if (array.size() > 0) entry.add(key, array);
	}

	private static void addIfNotBlank(JsonObject entry, String key, String value) {
		if (value != null && !value.isBlank()) entry.addProperty(key, value);
	}

	// --- Weapons (see command.WeaponCommand / Config.loadFile) ---

	static List<FieldSpec> weaponFields() {
		return List.of(
			FieldSpec.text("id", I18n.get("gui.dndsheets.form.id_path"), ""),
			FieldSpec.text("name", I18n.get("gui.dndsheets.form.name"), ""),
			FieldSpec.text("item", I18n.get("gui.dndsheets.form.base_item"), "minecraft:stick"),
			FieldSpec.text("dice", I18n.get("gui.dndsheets.form.damage_dice"), "1d6"),
			FieldSpec.cycle("ability", I18n.get("gui.dndsheets.form.ability"), new String[]{"str", "dex"}),
			FieldSpec.cycle("damageType", I18n.get("gui.dndsheets.form.damage_type"), DamageTypes.CANONICAL),
			FieldSpec.cycle("hands", I18n.get("gui.dndsheets.form.hands"), new String[]{"one", "two", "versatile"}),
			FieldSpec.text("versatileDice", I18n.get("gui.dndsheets.form.versatile_dice"), ""),
			FieldSpec.text("classes", I18n.get("gui.dndsheets.form.allowed_classes"), "", LIST_LENGTH)
		);
	}

	static Map<String, String> weaponPrefill(JsonObject entry) {
		Map<String, String> map = new LinkedHashMap<>();
		for (String key : new String[]{"id", "name", "item", "dice", "ability", "damageType", "hands", "versatileDice"}) {
			putIfPresent(map, entry, key);
		}
		map.put("classes", joinArray(entry, "classes"));
		return map;
	}

	static JsonObject weaponToJson(Map<String, String> values) {
		JsonObject entry = new JsonObject();
		entry.addProperty("id", values.get("id"));
		addIfNotBlank(entry, "name", values.get("name"));
		addIfNotBlank(entry, "item", values.get("item"));
		entry.addProperty("dice", values.get("dice"));
		entry.addProperty("ability", values.get("ability"));
		addIfNotBlank(entry, "damageType", values.get("damageType"));
		addIfNotBlank(entry, "hands", values.get("hands"));
		addIfNotBlank(entry, "versatileDice", values.get("versatileDice"));
		addCommaArray(entry, "classes", values.get("classes"));
		return entry;
	}

	// --- Spells (see command.SpellCommand / SpellRegistry.parse) ---

	static List<FieldSpec> spellFields() {
		return List.of(
			FieldSpec.text("id", I18n.get("gui.dndsheets.form.id_path"), ""),
			FieldSpec.text("name", I18n.get("gui.dndsheets.form.name"), ""),
			FieldSpec.intField("level", I18n.get("gui.dndsheets.form.level_cantrip"), "0"),
			FieldSpec.cycle("school", I18n.get("gui.dndsheets.form.school"), MagicSchool.KEYS),
			FieldSpec.cycle("mode", I18n.get("gui.dndsheets.form.mode"), new String[]{"attack", "save", "heal"}),
			FieldSpec.cycle("castingAbility", I18n.get("gui.dndsheets.form.casting_ability"), Combatant.ABILITIES),
			FieldSpec.cycle("saveAbility", I18n.get("gui.dndsheets.form.save_ability"), Combatant.ABILITIES),
			FieldSpec.text("dice", I18n.get("gui.dndsheets.form.dice"), "1d8"),
			FieldSpec.cycle("damageType", I18n.get("gui.dndsheets.form.damage_type"), DamageTypes.CANONICAL),
			FieldSpec.cycle("halfOnSave", I18n.get("gui.dndsheets.form.half_on_save"), BOOL_OPTIONS)
		);
	}

	static Map<String, String> spellPrefill(JsonObject entry) {
		Map<String, String> map = new LinkedHashMap<>();
		for (String key : new String[]{"id", "name", "school", "mode", "castingAbility", "saveAbility", "dice", "damageType"}) {
			putIfPresent(map, entry, key);
		}
		if (entry.has("level")) map.put("level", entry.get("level").getAsString());
		if (entry.has("halfOnSave")) map.put("halfOnSave", entry.get("halfOnSave").getAsBoolean() ? "yes" : "no");
		return map;
	}

	static JsonObject spellToJson(Map<String, String> values) {
		JsonObject entry = new JsonObject();
		entry.addProperty("id", values.get("id"));
		addIfNotBlank(entry, "name", values.get("name"));
		entry.addProperty("level", parseIntOr(values.get("level"), 0));
		addIfNotBlank(entry, "school", values.get("school"));
		entry.addProperty("mode", values.get("mode"));
		entry.addProperty("castingAbility", values.get("castingAbility"));
		if ("save".equals(values.get("mode"))) entry.addProperty("saveAbility", values.get("saveAbility"));
		entry.addProperty("dice", values.get("dice"));
		addIfNotBlank(entry, "damageType", values.get("damageType"));
		entry.addProperty("halfOnSave", "yes".equals(values.get("halfOnSave")));
		return entry;
	}

	// --- Class presets (see command.PresetCommand / PresetRegistry.parse) ---

	static List<FieldSpec> presetFields() {
		return List.of(
			FieldSpec.text("id", I18n.get("gui.dndsheets.form.id"), ""),
			FieldSpec.text("name", I18n.get("gui.dndsheets.form.name"), ""),
			FieldSpec.cycle("hitDiceType", I18n.get("gui.dndsheets.form.hit_die"), new String[]{"1d6", "1d8", "1d10", "1d12"}),
			FieldSpec.text("abilities", I18n.get("gui.dndsheets.form.abilities_csv"), "10, 10, 10, 10, 10, 10"),
			FieldSpec.text("startingWeapon", I18n.get("gui.dndsheets.form.starting_weapon"), ""),
			FieldSpec.text("startingGear", I18n.get("gui.dndsheets.form.starting_gear"), "", LIST_LENGTH),
			FieldSpec.intField("spellSlotsMax", I18n.get("gui.dndsheets.form.spell_slots_max"), "0"),
			FieldSpec.text("traits", I18n.get("gui.dndsheets.form.granted_traits"), "", LIST_LENGTH),
			FieldSpec.text("spells", I18n.get("gui.dndsheets.form.known_spells"), "", LIST_LENGTH)
		);
	}

	static Map<String, String> presetPrefill(JsonObject entry) {
		Map<String, String> map = new LinkedHashMap<>();
		putIfPresent(map, entry, "id");
		putIfPresent(map, entry, "name");
		putIfPresent(map, entry, "hitDiceType");
		putIfPresent(map, entry, "startingWeapon");
		if (entry.has("spellSlotsMax")) map.put("spellSlotsMax", entry.get("spellSlotsMax").getAsString());

		StringBuilder abilities = new StringBuilder();
		JsonObject abilitiesJson = entry.has("abilities") ? entry.getAsJsonObject("abilities") : null;
		for (String key : Combatant.ABILITIES) {
			if (abilities.length() > 0) abilities.append(", ");
			abilities.append(abilitiesJson != null && abilitiesJson.has(key) ? abilitiesJson.get(key).getAsInt() : 10);
		}
		map.put("abilities", abilities.toString());
		map.put("startingGear", joinArray(entry, "startingGear"));
		map.put("traits", joinArray(entry, "traits"));
		map.put("spells", joinArray(entry, "spells"));
		return map;
	}

	static JsonObject presetToJson(Map<String, String> values) {
		JsonObject entry = new JsonObject();
		entry.addProperty("id", values.get("id"));
		addIfNotBlank(entry, "name", values.get("name"));
		addIfNotBlank(entry, "hitDiceType", values.get("hitDiceType"));

		JsonObject abilities = new JsonObject();
		String[] parts = values.getOrDefault("abilities", "").split(",");
		for (int i = 0; i < Combatant.ABILITIES.length; i++) {
			abilities.addProperty(Combatant.ABILITIES[i], i < parts.length ? parseIntOr(parts[i], 10) : 10);
		}
		entry.add("abilities", abilities);

		addIfNotBlank(entry, "startingWeapon", values.get("startingWeapon"));
		entry.addProperty("spellSlotsMax", parseIntOr(values.get("spellSlotsMax"), 0));
		addCommaArray(entry, "startingGear", values.get("startingGear"));
		addCommaArray(entry, "traits", values.get("traits"));
		addCommaArray(entry, "spells", values.get("spells"));
		return entry;
	}

	// --- Feats (see FeatRegistry.parse) ---

	static List<FieldSpec> featFields() {
		return List.of(
			FieldSpec.text("id", I18n.get("gui.dndsheets.form.id"), ""),
			FieldSpec.text("name", I18n.get("gui.dndsheets.form.name"), ""),
			FieldSpec.text("description", I18n.get("gui.dndsheets.form.description"), ""),
			//The same six in the same order as the preset: here they're the BONUS added, not the score.
			FieldSpec.text("abilities", I18n.get("gui.dndsheets.form.ability_bonuses_csv"), "0, 0, 0, 0, 0, 0"),
			FieldSpec.text("traits", I18n.get("gui.dndsheets.form.granted_traits"), "", LIST_LENGTH),
			FieldSpec.text("spells", I18n.get("gui.dndsheets.form.granted_spells"), "", LIST_LENGTH),
			//Without this field, editing an imported Epic Boon here would erase its level-19 requirement:
			//the form rewrites the entire entry, so whatever it doesn't ask for gets lost.
			FieldSpec.text("minLevel", I18n.get("gui.dndsheets.form.min_level"), "1")
		);
	}

	static Map<String, String> featPrefill(JsonObject entry) {
		Map<String, String> map = new LinkedHashMap<>();
		putIfPresent(map, entry, "id");
		putIfPresent(map, entry, "name");
		putIfPresent(map, entry, "description");
		StringBuilder abilities = new StringBuilder();
		JsonObject scores = entry.has("abilities") ? entry.getAsJsonObject("abilities") : null;
		for (String key : Combatant.ABILITIES) {
			if (abilities.length() > 0) abilities.append(", ");
			abilities.append(scores != null && scores.has(key) ? scores.get(key).getAsInt() : 0);
		}
		map.put("abilities", abilities.toString());
		map.put("traits", joinArray(entry, "traits"));
		map.put("spells", joinArray(entry, "spells"));
		map.put("minLevel", String.valueOf(entry.has("minLevel") ? entry.get("minLevel").getAsInt() : 1));
		return map;
	}

	static JsonObject featToJson(Map<String, String> values) {
		JsonObject entry = new JsonObject();
		entry.addProperty("id", values.get("id"));
		addIfNotBlank(entry, "name", values.get("name"));
		addIfNotBlank(entry, "description", values.get("description"));

		JsonObject abilities = new JsonObject();
		String[] parts = values.getOrDefault("abilities", "").split(",");
		for (int i = 0; i < Combatant.ABILITIES.length; i++) {
			int bonus = i < parts.length ? parseIntOr(parts[i], 0) : 0;
			//Only bonuses that exist get written: a zero in the form means "not this one", not "+0".
			if (bonus != 0) abilities.addProperty(Combatant.ABILITIES[i], bonus);
		}
		if (abilities.size() > 0) entry.add("abilities", abilities);

		addCommaArray(entry, "traits", values.get("traits"));
		addCommaArray(entry, "spells", values.get("spells"));
		//1 is the default and doesn't get written, just like a 0 bonus: the absent field already means "from level 1".
		int minLevel = parseIntOr(values.getOrDefault("minLevel", "1"), 1);
		if (minLevel > 1) entry.addProperty("minLevel", minLevel);
		return entry;
	}

	// --- Encounters (see command.EncounterCommand / EncounterRegistry.parse) ---

	static List<FieldSpec> encounterFields() {
		return List.of(
			FieldSpec.text("id", I18n.get("gui.dndsheets.form.id"), ""),
			FieldSpec.text("name", I18n.get("gui.dndsheets.form.name"), ""),
			//The same syntax as in the JSON: one parser and one way to write it, not two.
			FieldSpec.text("monsters", I18n.get("gui.dndsheets.form.monsters"), "", LIST_LENGTH)
		);
	}

	static Map<String, String> encounterPrefill(JsonObject entry) {
		Map<String, String> map = new LinkedHashMap<>();
		putIfPresent(map, entry, "id");
		putIfPresent(map, entry, "name");
		map.put("monsters", joinArray(entry, "monsters"));
		return map;
	}

	static JsonObject encounterToJson(Map<String, String> values) {
		JsonObject entry = new JsonObject();
		entry.addProperty("id", values.get("id"));
		addIfNotBlank(entry, "name", values.get("name"));
		addCommaArray(entry, "monsters", values.get("monsters"));
		return entry;
	}

	// --- Traits (see command.TraitCommand / TraitRegistry.parse) — initial creation only. Level/die
	// lists and later editing live in TraitEditScreen, not here (see that class). ---

	static List<FieldSpec> traitCreateFields() {
		return List.of(
			FieldSpec.text("id", I18n.get("gui.dndsheets.form.id"), ""),
			FieldSpec.text("name", I18n.get("gui.dndsheets.form.name"), ""),
			FieldSpec.cycle("unarmedAbility", I18n.get("gui.dndsheets.form.unarmed_ability"), Combatant.ABILITIES)
		);
	}

	static JsonObject traitCreateToJson(Map<String, String> values) {
		JsonObject entry = new JsonObject();
		entry.addProperty("id", values.get("id"));
		addIfNotBlank(entry, "name", values.get("name"));
		addIfNotBlank(entry, "unarmedAbility", values.get("unarmedAbility"));
		return entry;
	}
}
