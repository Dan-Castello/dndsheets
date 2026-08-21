package net.hawthorn.dndsheets.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.client.gui.ContentFormScreen.FieldSpec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>Definición de campos + (de)serialización JSON para los 3 tipos de contenido que encajan en
 * {@link ContentFormScreen} (esquema plano, sin listas anidadas): armas, hechizos, presets. Cada método
 * {@code xFields()} es lo que arma la pantalla, {@code xPrefill} lee una entrada existente (tal cual llegó
 * por red desde {@code dm_created.json}) de vuelta a los mismos campos para editar, {@code xToJson} arma
 * lo que se manda a guardar — mismos nombres de campo que ya leen {@code Config.loadFile}/
 * {@code SpellRegistry.parse}/{@code PresetRegistry.parse}, ver esos métodos.</p>
 *
 * <p>Recorte deliberado: para no amontonar más de ~10 filas en un formulario de una sola columna (ver
 * {@code SmallFormScreen}), listas de varios valores (clases de un arma, rasgos/hechizos de un preset,
 * las 6 características) se escriben como texto separado por comas en un solo campo, y hechizos con
 * {@code appliesEffect}/{@code aoeRadius}/{@code concentration} (Bola de Fuego, Rayo de Luna...) siguen
 * necesitando el JSON a mano por ahora — cubre el caso común, no cada campo del esquema.</p>
 */
final class ContentTypeForms {
	private static final String[] ABILITIES = {"str", "dex", "con", "int", "wis", "cha"};
	private static final String[] DAMAGE_TYPES = {
		"fisico", "cortante", "perforante", "contundente", "fuego", "frio", "rayo",
		"acido", "veneno", "psiquico", "radiante", "necrotico", "fuerza", "trueno"
	};
	private static final String[] BOOL_OPTIONS = {"si", "no"};

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

	// --- Armas (ver command.WeaponCommand / Config.loadFile) ---

	static List<FieldSpec> weaponFields() {
		return List.of(
			FieldSpec.text("id", "Id (espacioDeNombres:ruta)", ""),
			FieldSpec.text("name", "Nombre", ""),
			FieldSpec.text("item", "Ítem base (id de Minecraft/mod)", "minecraft:stick"),
			FieldSpec.text("dice", "Dado de daño", "1d6"),
			FieldSpec.cycle("ability", "Característica", new String[]{"str", "dex"}),
			FieldSpec.cycle("damageType", "Tipo de daño", DAMAGE_TYPES),
			FieldSpec.cycle("hands", "Manos", new String[]{"one", "two", "versatile"}),
			FieldSpec.text("versatileDice", "Dado versátil (si aplica)", ""),
			FieldSpec.text("classes", "Clases permitidas (vacío = todas, separadas por coma)", "")
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

	// --- Hechizos (ver command.SpellCommand / SpellRegistry.parse) ---

	static List<FieldSpec> spellFields() {
		return List.of(
			FieldSpec.text("id", "Id (espacioDeNombres:ruta)", ""),
			FieldSpec.text("name", "Nombre", ""),
			FieldSpec.intField("level", "Nivel (0 = truco)", "0"),
			FieldSpec.cycle("mode", "Modo", new String[]{"attack", "save", "heal"}),
			FieldSpec.cycle("castingAbility", "Característica de lanzamiento", ABILITIES),
			FieldSpec.cycle("saveAbility", "Característica de salvación (si modo=save)", ABILITIES),
			FieldSpec.text("dice", "Dado", "1d8"),
			FieldSpec.cycle("damageType", "Tipo de daño", DAMAGE_TYPES),
			FieldSpec.cycle("halfOnSave", "Mitad de daño si salva", BOOL_OPTIONS)
		);
	}

	static Map<String, String> spellPrefill(JsonObject entry) {
		Map<String, String> map = new LinkedHashMap<>();
		for (String key : new String[]{"id", "name", "mode", "castingAbility", "saveAbility", "dice", "damageType"}) {
			putIfPresent(map, entry, key);
		}
		if (entry.has("level")) map.put("level", entry.get("level").getAsString());
		if (entry.has("halfOnSave")) map.put("halfOnSave", entry.get("halfOnSave").getAsBoolean() ? "si" : "no");
		return map;
	}

	static JsonObject spellToJson(Map<String, String> values) {
		JsonObject entry = new JsonObject();
		entry.addProperty("id", values.get("id"));
		addIfNotBlank(entry, "name", values.get("name"));
		entry.addProperty("level", parseIntOr(values.get("level"), 0));
		entry.addProperty("mode", values.get("mode"));
		entry.addProperty("castingAbility", values.get("castingAbility"));
		if ("save".equals(values.get("mode"))) entry.addProperty("saveAbility", values.get("saveAbility"));
		entry.addProperty("dice", values.get("dice"));
		addIfNotBlank(entry, "damageType", values.get("damageType"));
		entry.addProperty("halfOnSave", "si".equals(values.get("halfOnSave")));
		return entry;
	}

	// --- Presets de clase (ver command.PresetCommand / PresetRegistry.parse) ---

	static List<FieldSpec> presetFields() {
		return List.of(
			FieldSpec.text("id", "Id", ""),
			FieldSpec.text("name", "Nombre", ""),
			FieldSpec.cycle("hitDiceType", "Dado de golpe", new String[]{"1d6", "1d8", "1d10", "1d12"}),
			FieldSpec.text("abilities", "Fue,Des,Con,Int,Sab,Car (separadas por coma)", "10, 10, 10, 10, 10, 10"),
			FieldSpec.text("startingWeapon", "Arma inicial (id, opcional)", ""),
			FieldSpec.text("startingGear", "Equipo inicial (ids, separados por coma)", ""),
			FieldSpec.intField("spellSlotsMax", "Espacios de conjuro máx.", "0"),
			FieldSpec.text("traits", "Rasgos concedidos (ids, separados por coma)", ""),
			FieldSpec.text("spells", "Hechizos conocidos (ids, separados por coma)", "")
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
		for (String key : ABILITIES) {
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
		for (int i = 0; i < ABILITIES.length; i++) {
			abilities.addProperty(ABILITIES[i], i < parts.length ? parseIntOr(parts[i], 10) : 10);
		}
		entry.add("abilities", abilities);

		addIfNotBlank(entry, "startingWeapon", values.get("startingWeapon"));
		entry.addProperty("spellSlotsMax", parseIntOr(values.get("spellSlotsMax"), 0));
		addCommaArray(entry, "startingGear", values.get("startingGear"));
		addCommaArray(entry, "traits", values.get("traits"));
		addCommaArray(entry, "spells", values.get("spells"));
		return entry;
	}

	// --- Dotes (ver FeatRegistry.parse) ---

	static List<FieldSpec> featFields() {
		return List.of(
			FieldSpec.text("id", "Id", ""),
			FieldSpec.text("name", "Nombre", ""),
			FieldSpec.text("description", "Descripción", ""),
			//Las mismas seis en el mismo orden que el preset: aquí son el BONO que suma, no la puntuación.
			FieldSpec.text("abilities", "Bonos Fue,Des,Con,Int,Sab,Car (separados por coma)", "0, 0, 0, 0, 0, 0"),
			FieldSpec.text("traits", "Rasgos concedidos (ids, separados por coma)", ""),
			FieldSpec.text("spells", "Hechizos concedidos (ids, separados por coma)", ""),
			//Sin este campo, editar aquí un Don Épico importado le borraba el nivel 19: el formulario
			//reescribe la entrada entera, así que lo que no pregunta lo pierde.
			FieldSpec.text("minLevel", "Nivel mínimo", "1")
		);
	}

	static Map<String, String> featPrefill(JsonObject entry) {
		Map<String, String> map = new LinkedHashMap<>();
		putIfPresent(map, entry, "id");
		putIfPresent(map, entry, "name");
		putIfPresent(map, entry, "description");
		StringBuilder abilities = new StringBuilder();
		JsonObject scores = entry.has("abilities") ? entry.getAsJsonObject("abilities") : null;
		for (String key : ABILITIES) {
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
		for (int i = 0; i < ABILITIES.length; i++) {
			int bonus = i < parts.length ? parseIntOr(parts[i], 0) : 0;
			//Solo se escriben los bonos que existen: un cero en el formulario es "esta no", no "+0".
			if (bonus != 0) abilities.addProperty(ABILITIES[i], bonus);
		}
		if (abilities.size() > 0) entry.add("abilities", abilities);

		addCommaArray(entry, "traits", values.get("traits"));
		addCommaArray(entry, "spells", values.get("spells"));
		//1 es lo normal y no se escribe, igual que un bono de 0: el campo ausente ya significa "desde nivel 1".
		int minLevel = parseIntOr(values.getOrDefault("minLevel", "1"), 1);
		if (minLevel > 1) entry.addProperty("minLevel", minLevel);
		return entry;
	}

	// --- Encuentros (ver command.EncounterCommand / EncounterRegistry.parse) ---

	static List<FieldSpec> encounterFields() {
		return List.of(
			FieldSpec.text("id", "Id", ""),
			FieldSpec.text("name", "Nombre", ""),
			//La misma sintaxis que en el JSON: un parser y una forma de escribirlo, no dos.
			FieldSpec.text("monsters", "Monstruos (id x cantidad, separados por coma)", "")
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

	// --- Rasgos (ver command.TraitCommand / TraitRegistry.parse) — solo el alta inicial. Las listas de
	// nivel/dado y la edición posterior viven en TraitEditScreen, no acá (ver esa clase). ---

	static List<FieldSpec> traitCreateFields() {
		return List.of(
			FieldSpec.text("id", "Id", ""),
			FieldSpec.text("name", "Nombre", ""),
			FieldSpec.cycle("unarmedAbility", "Característica (golpe desarmado)", ABILITIES)
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
