package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * <p>Las reglas de personaje que no dependen de Minecraft: de quién es cada hoja, cuál lleva puesta su
 * dueño, y los PG máximos que salen de clase/nivel/Constitución. Separadas de {@link SheetLoader}, separadas de {@link SheetLoader}
 * porque este resuelve {@code FMLPaths.GAMEDIR} al inicializarse y por tanto
 * ni siquiera se puede cargar fuera de una instancia de Forge arrancada — y estas son justo las reglas
 * con ramas que conviene poder fijar en {@code JsonContentSelfTest}.</p>
 *
 * <p>Todo aquí son funciones puras sobre el mapa de hojas: no hay estado propio que pueda
 * desincronizarse del de {@code SheetLoader}.</p>
 */
final class CharacterRules {

	private CharacterRules() {}

	/**
	 * <p>PG máximos por clase, nivel y Constitución, con la regla de media del SRD: dado de golpe completo
	 * a nivel 1, y media del dado + 1 (más el modificador de Constitución) por cada nivel siguiente, con un
	 * mínimo de 1 PG por nivel aunque la Constitución sea penosa.</p>
	 */
	static int maxHitPointsFor(JsonObject sheet, int level) {
		int con = intField(sheet, "constitution", 10);
		int hitDie = Config.hitDieFor(sheet != null && sheet.has("characterClass") ? sheet.get("characterClass").getAsString() : "");
		int conMod = Math.floorDiv(con - 10, 2);

		int maxHp = hitDie + conMod;
		for (int lvl = 2; lvl <= Math.max(1, level); lvl++) {
			maxHp += Math.max(1, (hitDie / 2 + 1) + conMod);
		}
		return Math.max(1, maxHp);
	}

	/**
	 * <p>Nivel de personaje de una hoja sin jugador detrás. La versión con {@code Player} cae al XP real de
	 * Minecraft cuando el DM no fijó un nivel; una ficha de PNJ no tiene XP del que caer, así que empieza en
	 * 1 — en 5e ningún personaje es de nivel 0.</p>
	 */
	/**
	 * <p>Bono de competencia por nivel: +2 del 1 al 4, +3 del 5 al 8, +4 del 9 al 12, +5 del 13 al 16 y +6
	 * del 17 al 20. Es la tabla de 5e, y no es un detalle menor — entra en toda tirada de ataque, toda CD
	 * de salvación y toda prueba con competencia.</p>
	 *
	 * <p>Antes no lo calculaba nadie: la hoja arrancaba con "2" fijo y ahí se quedaba, así que un
	 * personaje de nivel 20 atacaba con el bono de uno de nivel 1. La hoja además ya pintaba el campo en
	 * ámbar y sin poder editarlo, o sea marcado como "se rellena solo" — prometía un cálculo que no
	 * existía.</p>
	 */
	static int proficiencyBonusFor(int level) {
		return 2 + (Math.min(20, Math.max(1, level)) - 1) / 4;
	}

	/**
	 * <p>Bono de daño de la Furia del bárbaro: +2 hasta el nivel 8, +3 del 9 al 15 y +4 del 16 en adelante.</p>
	 *
	 * <p>Estaba fijo en +2 y anotado como simplificación. Un bárbaro es la clase que menos botones tiene:
	 * su progresión <em>es</em> este número, así que congelarlo dejaba a un bárbaro de nivel 20 pegando
	 * exactamente igual que uno de nivel 1 salvo por los dados del arma.</p>
	 */
	static int rageDamageBonusFor(int level) {
		if (level >= 16) return 4;
		if (level >= 9) return 3;
		return 2;
	}

	/**
	 * <p>Dado de Inspiración Bárdica: d6, y sube a d8/d10/d12 en los niveles 5, 10 y 15.</p>
	 *
	 * <p>También estaba fijo, con la misma consecuencia: el recurso que define a la clase no mejoraba
	 * nunca.</p>
	 */
	static String bardicInspirationDieFor(int level) {
		if (level >= 15) return "1d12";
		if (level >= 10) return "1d10";
		if (level >= 5) return "1d8";
		return "1d6";
	}

	static int levelOf(JsonObject sheet) {
		if (sheet != null && sheet.has("characterLevel")) return Math.max(1, sheet.get("characterLevel").getAsInt());
		return 1;
	}

	//Las características se guardan como cadena en la hoja, y una hoja vieja puede tener ahí cualquier cosa.
	private static int intField(JsonObject sheet, String key, int fallback) {
		if (sheet == null || !sheet.has(key)) return fallback;
		try {
			return Integer.parseInt(sheet.get(key).getAsString());
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	/**
	 * <p>Dueño de una hoja, o {@code null} si no es de nadie (un PNJ). Sin campo {@code ownerUuid} —toda
	 * hoja anterior a que existieran los personajes— el dueño es el propio id, porque entonces el id de
	 * una hoja <em>era</em> el UUID de su jugador. Ese fallback es lo que hace que no haga falta migrar
	 * nada en disco.</p>
	 */
	static String ownerOf(String characterId, JsonObject sheet) {
		if (sheet != null && sheet.has("ownerUuid")) {
			String owner = sheet.get("ownerUuid").getAsString();
			return owner.isEmpty() ? null : owner;
		}
		return characterId;
	}

	/** Ids de los personajes de ese jugador, en orden estable. */
	static List<String> ownedBy(Map<String, JsonObject> sheets, String playerUuid) {
		List<String> owned = new ArrayList<>();
		for (Map.Entry<String, JsonObject> entry : sheets.entrySet()) {
			if (playerUuid.equals(ownerOf(entry.getKey(), entry.getValue()))) owned.add(entry.getKey());
		}
		Collections.sort(owned);
		return owned;
	}

	/**
	 * <p>Binding jugador → personaje activo, derivado del campo {@code active} de cada hoja en vez de
	 * guardado como índice aparte: un índice puede desincronizarse de las hojas y dejar a alguien sin poder
	 * jugar, mientras que el campo dentro de la propia hoja no puede contradecirse a sí mismo.</p>
	 *
	 * <p>Si un jugador acabara con dos hojas marcadas activas (edición manual del JSON), gana la de id
	 * menor. El desempate importa que sea determinista, no cuál gane: sin ordenar, el jugador se
	 * encontraría con un personaje distinto según el arranque.</p>
	 */
	static Map<String, String> buildActive(Map<String, JsonObject> sheets) {
		Map<String, String> active = new HashMap<>();
		List<String> ids = new ArrayList<>(sheets.keySet());
		Collections.sort(ids);
		for (String characterId : ids) {
			JsonObject sheet = sheets.get(characterId);
			if (sheet == null || !sheet.has("active") || !sheet.get("active").getAsBoolean()) continue;
			String owner = ownerOf(characterId, sheet);
			if (owner == null) continue; //PNJ: no lo lleva puesto ningún jugador.
			active.putIfAbsent(owner, characterId);
		}
		return active;
	}

	/**
	 * Id para un personaje más de ese jugador. Derivado de su UUID, así que es único entre jugadores sin
	 * necesitar un contador global, y sigue siendo un nombre de archivo válido en cualquier sistema.
	 */
	static String nextCharacterId(Set<String> existing, String playerUuid) {
		for (int n = 2; ; n++) {
			String candidate = playerUuid + "-" + n;
			if (!existing.contains(candidate)) return candidate;
		}
	}

	/**
	 * <p>Id para una ficha de PNJ, legible y apta como nombre de archivo. Los acentos se descomponen y se
	 * quitan ANTES de filtrar caracteres: sin eso, "Capitán" daba {@code npc-capit-n}, porque la "á" no
	 * entra en {@code [a-z0-9]} y se convertía en separador. En un mod en español eso afecta a la mayoría
	 * de los nombres, no a un caso raro.</p>
	 */
	static String npcIdFor(Set<String> existing, String characterName) {
		String withoutAccents = characterName == null ? "" : java.text.Normalizer
			.normalize(characterName, java.text.Normalizer.Form.NFD)
			.replaceAll("\\p{M}+", "");
		String slug = withoutAccents.toLowerCase(Locale.ROOT)
			.replaceAll("[^a-z0-9]+", "-")
			.replaceAll("(^-|-$)", "");
		if (slug.isEmpty()) slug = "pnj"; //Un nombre entero en caracteres no latinos no debe dar un id vacío.
		String candidate = "npc-" + slug;
		for (int n = 2; existing.contains(candidate); n++) candidate = "npc-" + slug + "-" + n;
		return candidate;
	}
}
