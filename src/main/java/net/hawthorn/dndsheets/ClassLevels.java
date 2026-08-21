package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <p>Multiclase: los niveles de un personaje repartidos entre varias clases, escritos en la hoja como
 * {@code "classLevels": {"fighter": 3, "wizard": 2}}.</p>
 *
 * <p><b>Se dejó para el final del roadmap a propósito</b> y por una razón concreta: es lo único que
 * rehace tablas que ya estaban fijadas nivel por nivel ({@link SpellSlots}, el bono de competencia, los PG
 * máximos). Por eso entra como <b>un campo opcional que manda cuando está</b> y no como un cambio del
 * modelo: una hoja sin {@code classLevels} —o sea, todas las que existen hoy— se comporta exactamente
 * igual que antes, y el resto del mod no se entera de que la multiclase existe. El único punto que sí
 * cambia es que, cuando está, {@code characterLevel} se reescribe como la suma, para que los ~70 sitios
 * que leen el nivel total sigan leyendo un número y no tengan que aprender nada.</p>
 *
 * <p><b>Lo que no se modela, y por qué:</b></p>
 * <ul>
 *   <li><b>Los requisitos de característica</b> (13 en las dos clases). En la mesa eso lo mira el DM antes
 *       de conceder el nivel, y aquí el nivel <em>lo concede el DM</em>: una comprobación automática solo
 *       podría estorbar a una mesa que juega con otra regla.</li>
 *   <li><b>Los rasgos de la clase nueva</b> no se conceden solos. Aplicar su preset entero sería peor que
 *       no hacer nada: reescribiría las seis características, el dado de golpe y el equipo del personaje
 *       que ya existe. El DM concede los rasgos que toquen, que es lo que ya hace con {@code /dndtraits}.</li>
 *   <li><b>Los espacios de pacto no se apilan</b> con los de un lanzador. Un brujo multiclase tiene en 5e
 *       dos reservas distintas y esta hoja solo sabe llevar una, así que se lleva la del lanzador. Quedarse
 *       corto es la dirección segura — la contraria sería regalar espacios que el personaje no tiene.</li>
 * </ul>
 */
public final class ClassLevels {

	static final String FIELD = "classLevels";

	private ClassLevels() {
	}

	/**
	 * <p>Los niveles por clase de esta hoja, en el orden en que se escribieron. El orden importa: el
	 * <b>primero</b> es la clase con la que empezó el personaje, y en 5e esa es la única que da el dado de
	 * golpe entero a su nivel 1.</p>
	 */
	public static Map<String, Integer> of(JsonObject sheet) {
		Map<String, Integer> levels = new LinkedHashMap<>();
		if (sheet == null || !sheet.has(FIELD) || !sheet.get(FIELD).isJsonObject()) return levels;
		JsonObject json = sheet.getAsJsonObject(FIELD);
		for (String classId : json.keySet()) {
			try {
				int level = json.get(classId).getAsInt();
				if (level > 0) levels.put(classId, level);
			} catch (RuntimeException ignored) {
				//Una entrada que no es un número se salta, como cualquier otra línea rota de contenido: el
				//resto del reparto sigue valiendo.
			}
		}
		return levels;
	}

	public static boolean isMulticlass(JsonObject sheet) {
		return of(sheet).size() > 1;
	}

	public static int total(Map<String, Integer> levels) {
		int total = 0;
		for (int level : levels.values()) total += level;
		return total;
	}

	/**
	 * <p>El nivel de lanzador de un multiclase: los niveles de lanzador completo enteros, más la
	 * <b>mitad hacia abajo</b> de los de semilanzador. Los de brujo no entran — el pacto es otra reserva.</p>
	 *
	 * <p>Ese redondeo es la trampa clásica de la regla y por eso está escrito aparte de
	 * {@link SpellSlots#maxSlots}: un semilanzador <em>de una sola clase</em> usa la mitad hacia
	 * <b>arriba</b> (un paladín de nivel 2 ya lanza), y multiclase usa la mitad hacia <b>abajo</b> (un
	 * paladín 2 aporta 1). Escribir las dos con el mismo redondeo da una tabla que cuadra en la mitad de
	 * los casos, que es la peor clase de error: parece que funciona.</p>
	 */
	public static int casterLevel(Map<String, Integer> levels) {
		int caster = 0;
		for (Map.Entry<String, Integer> entry : levels.entrySet()) {
			switch (SpellSlots.casterFor(entry.getKey())) {
				case FULL -> caster += entry.getValue();
				case HALF -> caster += entry.getValue() / 2;
				default -> { }
			}
		}
		return caster;
	}

	/**
	 * <p>PG máximos de un reparto: el dado entero de la <b>primera</b> clase, y media del dado + 1 por cada
	 * nivel restante, cada uno con el dado de <em>su</em> clase. El modificador de Constitución entra una
	 * vez por nivel, y cada nivel da al menos 1 PG aunque la Constitución sea penosa — las mismas reglas que
	 * {@code CharacterRules.maxHitPointsFor}, aplicadas dado a dado en vez de con uno solo.</p>
	 */
	public static int maxHitPoints(Map<String, Integer> levels, int constitution) {
		if (levels.isEmpty()) return 1;
		int conMod = Math.floorDiv(constitution - 10, 2);
		int maxHp = 0;
		boolean first = true;

		for (Map.Entry<String, Integer> entry : levels.entrySet()) {
			int hitDie = Config.hitDieFor(entry.getKey());
			for (int level = 0; level < entry.getValue(); level++) {
				if (first) {
					maxHp += hitDie + conMod;
					first = false;
				} else {
					maxHp += Math.max(1, (hitDie / 2 + 1) + conMod);
				}
			}
		}
		return Math.max(1, maxHp);
	}

	/** Cómo se lee un reparto en la hoja y en el chat: "Guerrero 3 / Mago 2". */
	public static String describe(Map<String, Integer> levels) {
		StringBuilder text = new StringBuilder();
		for (Map.Entry<String, Integer> entry : levels.entrySet()) {
			PresetRegistry.ClassPreset preset = PresetRegistry.get(entry.getKey());
			if (text.length() > 0) text.append(" / ");
			text.append(preset != null ? preset.name() : entry.getKey()).append(' ').append(entry.getValue());
		}
		return text.toString();
	}

	/**
	 * <p>Añade un nivel en {@code classId} y deja la hoja coherente: el reparto, el nivel total y el texto de
	 * la clase. No toca PG ni espacios — de eso se encargan sus dueños ({@code SheetLoader.applyClassHitPoints}
	 * y {@link SpellSlots#applyProgression}), que ya saben leer el reparto.</p>
	 *
	 * <p>Si la hoja todavía no tenía reparto, se siembra con la clase que ya llevaba y su nivel actual: sin
	 * eso, multiclasar a un guerrero de nivel 5 lo convertiría en un guerrero 0 / mago 1.</p>
	 */
	public static Map<String, Integer> addLevel(JsonObject sheet, String classId, String currentClassId, int currentLevel) {
		Map<String, Integer> levels = of(sheet);
		if (levels.isEmpty() && currentClassId != null && !currentClassId.isBlank()) {
			levels.put(currentClassId, Math.max(1, currentLevel));
		}
		levels.merge(classId, 1, Integer::sum);

		JsonObject json = new JsonObject();
		for (Map.Entry<String, Integer> entry : levels.entrySet()) json.addProperty(entry.getKey(), entry.getValue());
		sheet.add(FIELD, json);
		sheet.addProperty("characterLevel", String.valueOf(total(levels)));
		sheet.addProperty("characterClass", describe(levels));
		return levels;
	}
}
