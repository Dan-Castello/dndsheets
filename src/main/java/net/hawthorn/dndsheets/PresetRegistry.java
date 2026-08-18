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
 * <p>Presets de clase cargados en caliente por {@code /dndpresets load}, en memoria (igual que
 * {@link MonsterRegistry}/{@link SpellRegistry}). Elegir uno rellena los valores generales de la hoja
 * (clase, dado de golpe, características) en vez de escribirlos a mano campo por campo, y concede los
 * rasgos (pasivas/habilidades) que tenga configurados — ver {@link TraitRegistry}.</p>
 */
//Interno: no forma parte de la API pública versionada del mod (ver net.hawthorn.dndsheets.api.DndSheetsApi
//y su API_VERSION). Un mod externo que llame estos métodos directo en vez de a través de la fachada se
//expone a que cambien de firma sin aviso.
public class PresetRegistry {
	public record ClassPreset(String id, String name, String hitDiceType, Map<String, Integer> abilities, String startingWeaponId, List<String> startingGear, int spellSlotsMax, List<String> traits, List<String> spells) {
		public int ability(String key) {
			Integer score = abilities.get(key);
			return score == null ? 10 : score;
		}
	}

	private static final NamedRegistry<ClassPreset> REGISTRY = new NamedRegistry<>("preset", ClassPreset::id);

	public static void register(ClassPreset preset) {
		REGISTRY.register(preset);
	}

	public static ClassPreset get(String id) {
		return REGISTRY.get(id);
	}

	public static Set<String> ids() {
		return REGISTRY.ids();
	}

	public static boolean remove(String id) {
		return REGISTRY.remove(id);
	}

	//Público: usado por PresetCommand (/dndpresets load) y por DndPaths para precargar solo todos los
	//.json de la carpeta al arrancar el servidor, sin que DndPaths tenga que depender de la capa de
	//comandos — ver AUDIT_TECHNICAL.md M-ARQ-1.
	private static final JsonRegistryLoader<ClassPreset> LOADER = new JsonRegistryLoader<>("preset", PresetRegistry::parse, PresetRegistry::register);

	/** Carga desde un JSON ya leído (datapack o jar de otro mod) — ver ContentDatapackLoader. */
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
		for (String key : new String[]{"str", "dex", "con", "int", "wis", "cha"}) {
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

		//Equipo inicial: lo que el arma inicial no cubre y sin embargo decide la mitad de la ficha. La
		//armadura de aquí sube la CA de verdad, porque la CA sale del atributo real de Minecraft — un
		//guerrero recién creado valía 10 + Destreza hasta que un DM se acordaba de darle una cota.
		List<String> startingGear = new ArrayList<>();
		if (json.has("startingGear")) {
			for (JsonElement el : json.getAsJsonArray("startingGear")) startingGear.add(el.getAsString());
		}

		return new ClassPreset(id, name, hitDiceType, abilities, startingWeaponId, startingGear, spellSlotsMax, traits, spells);
	}

	//Rellena los campos generales de la hoja. No toca "attacks" (ver PresetManager, que además entrega el arma inicial real).
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
			//spellSlotsMax del preset era el total de un personaje de NIVEL 1 y no escalaba nunca. Ahora la
			//clase decide la tabla entera; el campo se conserva solo como marca de "esta clase lanza".
			SpellSlots.applyProgression(sheet, preset.name(), CharacterRules.levelOf(sheet));
		}
		for (String traitId : preset.traits()) TraitRegistry.grant(sheet, traitId);
		//Rasgo icónico de un preset caster: sin esto el Grimorio se quedaba vacío pese a tener espacios de
		//conjuro — el preset configuraba el CONTADOR de espacios pero nunca daba ningún hechizo que gastarlos.
		for (String spellId : preset.spells()) SpellRegistry.learn(sheet, spellId);
	}

	//Antes de conceder los rasgos del preset NUEVO, quita los del preset anterior (si había uno registrado
	//y su id sigue cargado): sin esto, cambiar de "monje" a "mago" dejaba Artes Marciales concedido para
	//siempre, ya que TraitRegistry.grant solo sabe añadir. "appliedPresetId" es lo único que necesitamos
	//guardar para saber cuál era — no hace falta trackear la lista completa de rasgos por separado.
	private static void revokePreviousTraits(JsonObject sheet) {
		if (!sheet.has("appliedPresetId")) return;
		ClassPreset previous = get(sheet.get("appliedPresetId").getAsString());
		if (previous == null) return;
		for (String traitId : previous.traits()) TraitRegistry.revoke(sheet, traitId);
	}
}
