package net.hawthorn.dndsheets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * <p>Rasgos (pasivas/habilidades de clase) cargados en caliente por {@code /dndtraits load}, en memoria
 * (igual que {@link SpellRegistry}/{@link MonsterRegistry}: se pierden al reiniciar a menos que se
 * recargue el mismo archivo). Un preset de clase los concede solos por id (campo {@code "traits"} en su
 * JSON, ver {@link PresetRegistry}); {@code /dndtraits grant} los concede a mano.</p>
 *
 * <p>Dos efectos por ahora, ambos "dado que escala por nivel" — {@link LevelDice} es la misma forma para
 * los dos, solo cambia qué campo del JSON se lee y dónde se consume:</p>
 * <ul>
 *   <li>{@code unarmedDiceByLevel}: sustituye el golpe a mano desnuda por una tirada real de 5e (Artes
 *   Marciales del monje) — ver {@link #unarmedProfileFor}, consumido por {@link CombatManager}.</li>
 *   <li>{@code sneakAttackDiceByLevel}: dados extra que se SUMAN a la tirada de daño cuando el ataque se
 *   hizo con ventaja (Ataque Furtivo del pícaro) — ver {@link #sneakAttackDiceFor}, consumido también por
 *   {@link CombatManager}. Simplificación deliberada: 5e también lo permite con un aliado adyacente sin
 *   desventaja; aquí solo cuenta la ventaja real, no hay noción de "aliado adyacente" en el motor.</li>
 * </ul>
 * <p>Añadir un tipo de efecto nuevo es el mismo patrón: un campo más aquí, una rama más donde se consuma
 * — no hace falta un motor de reglas genérico para esto.</p>
 */
//Interno: no forma parte de la API pública versionada del mod (ver net.hawthorn.dndsheets.api.DndSheetsApi
//y su API_VERSION). Un mod externo que llame estos métodos directo en vez de a través de la fachada se
//expone a que cambien de firma sin aviso.
public class TraitRegistry {
	public record LevelDice(int level, String dice) {}
	public record Trait(String id, String name, String unarmedAbility, List<LevelDice> unarmedDiceByLevel, List<LevelDice> sneakAttackDiceByLevel) {}
	public record UnarmedProfile(String dice, String ability) {}

	private static final NamedRegistry<Trait> REGISTRY = new NamedRegistry<>("rasgo", Trait::id);

	public static void register(Trait trait) {
		REGISTRY.register(trait);
	}

	public static Trait get(String id) {
		return REGISTRY.get(id);
	}

	public static Set<String> ids() {
		return REGISTRY.ids();
	}

	public static boolean remove(String id) {
		return REGISTRY.remove(id);
	}

	//Público: usado por TraitCommand (/dndtraits load) y por DndPaths para precargar solo todos los .json
	//de la carpeta al arrancar el servidor, sin que DndPaths tenga que depender de la capa de comandos —
	//ver AUDIT_TECHNICAL.md M-ARQ-1.
	private static final JsonRegistryLoader<Trait> LOADER = new JsonRegistryLoader<>("rasgo", TraitRegistry::parse, TraitRegistry::register);

	/** Carga desde un JSON ya leído (datapack o jar de otro mod) — ver ContentDatapackLoader. */
	public static int loadJson(com.google.gson.JsonElement root, String source, java.util.function.Consumer<String> onId) {
		return LOADER.loadJson(root, source, onId);
	}

	public static int loadFile(Path file) throws IOException {
		return LOADER.loadFile(file);
	}

	public static Trait parse(JsonObject json) {
		String id = json.get("id").getAsString();
		String name = json.has("name") ? json.get("name").getAsString() : id;
		String unarmedAbility = json.has("unarmedAbility") ? json.get("unarmedAbility").getAsString().toLowerCase(Locale.ROOT) : "str";

		List<LevelDice> unarmedTiers = parseLevelDice(json, "unarmedDiceByLevel");
		List<LevelDice> sneakAttackTiers = parseLevelDice(json, "sneakAttackDiceByLevel");

		return new Trait(id, name, unarmedAbility, unarmedTiers, sneakAttackTiers);
	}

	private static List<LevelDice> parseLevelDice(JsonObject json, String field) {
		List<LevelDice> tiers = new ArrayList<>();
		if (!json.has(field)) return tiers;

		for (JsonElement el : json.getAsJsonArray(field)) {
			JsonObject tier = el.getAsJsonObject();
			tiers.add(new LevelDice(tier.has("level") ? tier.get("level").getAsInt() : 1, tier.get("dice").getAsString()));
		}
		tiers.sort((a, b) -> b.level() - a.level()); //Descendente: el primer nivel que "quepa" es el más alto que corresponde.
		return tiers;
	}

	//--- Sheet: lista de ids de rasgos concedidos en "traits" (ver SheetLoader.validateSheet) ---

	//Añade el rasgo si no lo tenía ya; usado tanto al aplicar un preset (PresetRegistry.applyToSheet)
	//como por /dndtraits grant, para no duplicar entradas si se concede dos veces por error.
	public static void grant(JsonObject sheet, String traitId) {
		if (!sheet.has("traits")) sheet.add("traits", new JsonArray());
		JsonArray granted = sheet.getAsJsonArray("traits");
		for (JsonElement el : granted) {
			if (el.getAsString().equals(traitId)) return;
		}
		granted.add(traitId);
	}

	//Usado por PresetRegistry.applyToSheet al cambiar de preset: sin esto, cambiar de "monje" a "mago"
	//dejaba Artes Marciales concedido para siempre, ya que grant() solo sabe añadir, nunca quitar.
	public static void revoke(JsonObject sheet, String traitId) {
		if (!sheet.has("traits")) return;
		JsonArray granted = sheet.getAsJsonArray("traits");
		JsonArray kept = new JsonArray();
		for (JsonElement el : granted) {
			if (!el.getAsString().equals(traitId)) kept.add(el);
		}
		sheet.add("traits", kept);
	}

	//Golpe a mano desnuda: recorre los rasgos concedidos buscando uno que defina dado por nivel (p.ej.
	//Artes Marciales); devuelve el de mayor nivel aplicable, o null si ninguno de los rasgos concedidos
	//toca esto (comportamiento normal de Minecraft para el puñetazo, sin cambios).
	public static UnarmedProfile unarmedProfileFor(JsonObject sheet, int level) {
		if (sheet == null || !sheet.has("traits")) return null;

		for (JsonElement el : sheet.getAsJsonArray("traits")) {
			Trait trait = REGISTRY.get(el.getAsString());
			if (trait == null || trait.unarmedDiceByLevel().isEmpty()) continue;

			for (LevelDice tier : trait.unarmedDiceByLevel()) {
				if (level >= tier.level()) return new UnarmedProfile(tier.dice(), trait.unarmedAbility());
			}
		}
		return null;
	}

	//Ataque furtivo: dado extra que se suma a la tirada de daño (no la sustituye) cuando el ataque se hizo
	//con ventaja. Null si ninguno de los rasgos concedidos lo define, o si el personaje aún no llega al
	//nivel de la primera entrada de la tabla.
	public static String sneakAttackDiceFor(JsonObject sheet, int level) {
		if (sheet == null || !sheet.has("traits")) return null;

		for (JsonElement el : sheet.getAsJsonArray("traits")) {
			Trait trait = REGISTRY.get(el.getAsString());
			if (trait == null || trait.sneakAttackDiceByLevel().isEmpty()) continue;

			for (LevelDice tier : trait.sneakAttackDiceByLevel()) {
				if (level >= tier.level()) return tier.dice();
			}
		}
		return null;
	}
}
