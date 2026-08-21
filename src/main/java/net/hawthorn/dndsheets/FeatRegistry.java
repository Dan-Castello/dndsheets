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
 * <p>Dotes: lo que 5e te deja coger <b>en vez de</b> la Mejora de Característica de los niveles 4, 8, 12,
 * 16 y 19. No son un recurso aparte — gastan la misma mejora pendiente que ya lleva {@link LevelUpManager},
 * que es exactamente lo que las hace una decisión: subir dos puntos de característica o hacer otra cosa.</p>
 *
 * <p><b>El SRD 5.1 traía una sola dote</b> (Luchador), y durante un tiempo eso fue todo lo que se podía
 * enviar. El <b>SRD 5.2</b> (2024, también CC-BY-4.0) publicó la lista entera, así que ahora vienen las 16
 * que se pueden redistribuir: las de origen, los estilos de combate y los Dones Épicos. Se importaron con
 * {@code tools/import_srd.py} (ver {@code ATTRIBUTION.md}), y una mesa o un addon siguen pudiendo añadir
 * las suyas <b>poniendo un JSON en una carpeta</b>, igual que con hechizos, monstruos o encuentros.</p>
 *
 * <p>Lo que el SRD 5.2 obligó a modelar es el <b>nivel mínimo</b>: los Dones Épicos son de nivel 19 y los
 * estilos de combate de nivel 1, y ofrecerlos todos juntos en la mejora del nivel 4 convertía la lista en
 * una tienda de cosas que el personaje no puede tener.</p>
 *
 * <p>Una dote concede lo mismo que un preset o una subclase —rasgos y hechizos— más lo único que las dotes
 * hacen y aquellos no: <b>subir características</b>. Ese es el trozo con mecánica de verdad, porque las
 * seis puntuaciones ya mueven todo lo demás; el tope de 20 es el mismo que el de la mejora, y por el mismo
 * motivo.</p>
 */
public class FeatRegistry {

	public record Feat(String id, String name, String description, Map<String, Integer> abilities,
			List<String> traits, List<String> spells, int minLevel) {
	}

	private static final NamedRegistry<Feat> REGISTRY = new NamedRegistry<>("dote", Feat::id);

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
		new JsonRegistryLoader<>("dote", FeatRegistry::parse, FeatRegistry::register);

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

		//Nivel minimo. Ausente = 1 = se puede coger en cuanto haya una mejora pendiente, que es como se
		//comportaban todas antes de que el SRD 5.2 trajera dotes que NO son de nivel 1.
		int minLevel = json.has("minLevel") ? Math.max(1, json.get("minLevel").getAsInt()) : 1;

		return new Feat(id, name, description, abilities, traits, spells, minLevel);
	}

	/** ¿Puede este personaje coger esta dote todavía? Solo el nivel — lo de "ya la tiene" es {@link #takenBy}. */
	public static boolean availableAt(Feat feat, int characterLevel) {
		return feat != null && characterLevel >= feat.minLevel();
	}

	/** Las dotes que ya tiene esta hoja. Una dote no se coge dos veces. */
	public static List<String> takenBy(JsonObject sheet) {
		List<String> taken = new ArrayList<>();
		if (sheet == null || !sheet.has("feats")) return taken;
		for (JsonElement el : sheet.getAsJsonArray("feats")) taken.add(el.getAsString());
		return taken;
	}

	/**
	 * <p>Escribe la dote en la hoja y concede lo suyo. No comprueba si había una mejora pendiente: de eso se
	 * ocupa {@link LevelUpManager#applyFeat}, que es quien la gasta — aquí solo está lo que una dote <em>es</em>,
	 * para que se pueda comprobar sin un servidor delante.</p>
	 *
	 * @return false si la dote no existe, si este personaje ya la tenía o si aún no tiene nivel para ella.
	 */
	public static boolean grant(JsonObject sheet, String featId, int maxAbility, int characterLevel) {
		Feat feat = get(featId);
		if (sheet == null || feat == null || takenBy(sheet).contains(featId)) return false;
		//El nivel se comprueba AQUI y no solo en la pantalla que la ofrece: la lista es una sugerencia, la
		//concesión es la regla. Un Don Épico de nivel 19 llega por el mismo mensaje de red que los demás.
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
				//Una hoja sin esa característica escrita se queda en 10, que es lo que vale por defecto.
			}
			//Tope 20, el mismo que la mejora: una dote que lo saltara sería una mejora mejor que la mejora.
			sheet.addProperty(key, String.valueOf(Math.min(maxAbility, score + bonus.getValue())));
		}
		for (String traitId : feat.traits()) TraitRegistry.grant(sheet, traitId);
		for (String spellId : feat.spells()) SpellRegistry.learn(sheet, spellId);
		return true;
	}
}
