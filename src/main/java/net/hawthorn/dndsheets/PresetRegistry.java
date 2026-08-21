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
	/**
	 * <p>Una subclase (arquetipo): la segunda mitad de lo que es un personaje en 5e, elegida unos niveles
	 * después de la clase. Vive DENTRO de su preset y no en un registro propio porque una subclase sin su
	 * clase no significa nada — "Escuela de Evocación" no es elegible por un bárbaro, y un registro aparte
	 * obligaría a llevar la pareja a mano en los dos sentidos.</p>
	 *
	 * <p>Concede exactamente lo mismo que un preset (rasgos y hechizos) por el mismo camino, así que no
	 * añade ninguna forma nueva de conceder nada. {@code criticalFrom} es la única excepción y existe por
	 * un solo caso: el Campeón del guerrero critica con 19, que es el rasgo de subclase del SRD que este
	 * motor sí puede sostener sin inventarse un subsistema. Cero significa "no lo toca".</p>
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
	//comandos.
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

		//Equipo inicial: lo que el arma inicial no cubre y sin embargo decide la mitad de la ficha. La
		//armadura de aquí sube la CA de verdad, porque la CA sale del atributo real de Minecraft — un
		//guerrero recién creado valía 10 + Destreza hasta que un DM se acordaba de darle una cota.
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
					//Nivel 3 por defecto: es el de la mayoría de las clases del SRD, y una subclase sin nivel
					//escrito es casi siempre una que sigue la norma.
					entry.has("level") ? entry.get("level").getAsInt() : 3,
					subTraits, subSpells,
					entry.has("criticalFrom") ? entry.get("criticalFrom").getAsInt() : 0));
			}
		}

		return new ClassPreset(id, name, hitDiceType, abilities, startingWeaponId, startingGear, spellSlotsMax, traits, spells, subclasses);
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

	/**
	 * <p>Las subclases que este personaje puede elegir ahora mismo: las de su preset cuyo nivel ya alcanzó.
	 * Sin preset aplicado no hay ninguna, que es correcto — la subclase es una rama de la clase, así que
	 * primero hay que tener clase.</p>
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
	 * <p>Aplica una subclase a la hoja. Devuelve false si esa subclase no es de la clase de este personaje o
	 * si todavía no tiene nivel para ella: se comprueba aquí y no solo al pintar la lista, porque un cliente
	 * modificado puede pedir cualquier id — la misma frontera de siempre.</p>
	 *
	 * <p><b>La elección es permanente</b>, como el pacto del brujo: no se revoca la anterior al elegir otra.
	 * Cambiar de subclase es rehacer el personaje, y quitarle rasgos a alguien a mitad de campaña porque
	 * pulsó una fila es peor que dejarle una subclase que no quería, que el DM puede arreglar.</p>
	 */
	public static boolean applySubclass(JsonObject sheet, String subclassId) {
		for (Subclass subclass : availableSubclasses(sheet)) {
			if (!subclass.id().equals(subclassId)) continue;

			sheet.addProperty("appliedSubclassId", subclass.id());
			//El nombre también, y no solo el id: es lo que lee la pantalla del cliente, que no tiene el
			//registro. Mismo par que appliedPresetId/characterClass.
			sheet.addProperty("characterSubclass", subclass.name());
			if (subclass.criticalFrom() > 0) sheet.addProperty("criticalFrom", String.valueOf(subclass.criticalFrom()));
			for (String traitId : subclass.traits()) TraitRegistry.grant(sheet, traitId);
			for (String spellId : subclass.spells()) SpellRegistry.learn(sheet, spellId);
			return true;
		}
		return false;
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
