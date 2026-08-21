package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.Set;

/**
 * <p>Resistencias/vulnerabilidades/inmunidades por tipo de daño, guardadas en la hoja del jugador
 * como {@code damageAffinities: {"fuego":"resistant", ...}} (ver {@code /dndsheet damagetype}). Solo
 * protege/perjudica al jugador que la tiene — monstruos y armor stands no llevan esta capa.</p>
 *
 * <p>La Furia del bárbaro ({@link BarbarianRageManager}) se suma aquí en vez de escribirse como una
 * entrada más de "damageAffinities": es temporal y no debería sobrevivir a un reinicio de servidor ni
 * aparecer como algo que el DM "fijó a mano" con {@code /dndsheet damagetype}.</p>
 */
public class DamageTypes {
	private static final Set<String> PHYSICAL_TYPES = Set.of("fisico", "cortante", "perforante", "contundente");

	/**
	 * <p>Los catorce tipos de dano de 5e como se escriben en este mod: sin acentos, en minusculas y en
	 * espanol. Vive aqui y no en el comando que los sugiere porque es la misma lista que decide si una
	 * resistencia aplica — dos copias que se separen serian una resistencia que el DM ve sugerida y que
	 * luego no hace nada.</p>
	 */
	public static final String[] CANONICAL = {
		"fisico", "cortante", "perforante", "contundente", "fuego", "frio", "rayo",
		"acido", "veneno", "psiquico", "radiante", "necrotico", "fuerza", "trueno"
	};

	private static final Map<String, String> ENGLISH = Map.ofEntries(
		Map.entry("physical", "fisico"), Map.entry("slashing", "cortante"),
		Map.entry("piercing", "perforante"), Map.entry("bludgeoning", "contundente"),
		Map.entry("fire", "fuego"), Map.entry("cold", "frio"), Map.entry("lightning", "rayo"),
		Map.entry("acid", "acido"), Map.entry("poison", "veneno"), Map.entry("psychic", "psiquico"),
		Map.entry("radiant", "radiante"), Map.entry("necrotic", "necrotico"),
		Map.entry("force", "fuerza"), Map.entry("thunder", "trueno"));

	/**
	 * <p><b>Un tipo de dano escrito de cualquier manera, siempre igual.</b> Un tipo de dano no es una
	 * etiqueta que se imprime: es una CLAVE que se compara contra las resistencias de la hoja y del bloque
	 * de monstruo ({@code damageAffinities}). Por eso {@code "Fire"}, {@code "fuego"} y {@code "fuego "}
	 * tienen que dar la misma cadena — si no, un pack importado en ingles atraviesa la resistencia al fuego
	 * de un personaje sin que salte nada, que es la peor forma de fallar: el numero sale, y sale mal.</p>
	 *
	 * <p>Un tipo que no esta en la tabla <b>no se descarta</b>, se devuelve normalizado. Una mesa que se
	 * invente "sangrado" sigue teniendo su tipo, y lo tiene igual en las dos puntas de la comparacion, que
	 * es lo unico que hace falta para que su resistencia casera funcione.</p>
	 */
	public static String normalize(String raw) {
		if (raw == null) return "fisico";
		String stripped = java.text.Normalizer.normalize(raw.trim().toLowerCase(java.util.Locale.ROOT),
				java.text.Normalizer.Form.NFD)
			.replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
			.replaceAll("[\\s_-]", "");
		if (stripped.isEmpty()) return "fisico";
		return ENGLISH.getOrDefault(stripped, stripped);
	}

	public static double multiplierFor(Entity target, JsonObject sheet, String damageType) {
		double multiplier = sheetMultiplierFor(sheet, damageType);
		if (target instanceof ServerPlayer player && BarbarianRageManager.isRaging(player) && PHYSICAL_TYPES.contains(damageType)) {
			multiplier = Math.min(multiplier, 0.5); //Ya inmune/resistente por otra vía no empeora; normal/vulnerable sí baja a resistente.
		}
		return multiplier;
	}

	private static double sheetMultiplierFor(JsonObject sheet, String damageType) {
		if (sheet == null || damageType == null || !sheet.has("damageAffinities")) return 1.0;
		JsonObject affinities = sheet.getAsJsonObject("damageAffinities");
		String key = normalize(damageType);
		//Las claves de una hoja escrita a mano antes de que esto existiera pueden llevar acentos o mayusculas.
		for (String written : affinities.keySet()) {
			if (normalize(written).equals(key)) return multiplierForLabel(affinities.get(written).getAsString());
		}
		return 1.0;
	}

	/**
	 * <p>Traduce una afinidad declarada ({@code resistant}/{@code vulnerable}/{@code immune}) a su
	 * multiplicador. Público porque los bloques de monstruo guardan las suyas en un mapa propio, no en una
	 * hoja JSON: sin esto, el mismo vocabulario acabaría parseado en dos sitios que podrían discrepar.</p>
	 */
	public static double multiplierForLabel(String affinity) {
		if (affinity == null) return 1.0;
		return switch (affinity.toLowerCase(java.util.Locale.ROOT)) {
			case "resistant" -> 0.5;
			case "vulnerable" -> 2.0;
			case "immune" -> 0.0;
			default -> 1.0;
		};
	}

	public static int applyMultiplier(int amount, double multiplier) {
		return (int) Math.floor(amount * multiplier);
	}
}
