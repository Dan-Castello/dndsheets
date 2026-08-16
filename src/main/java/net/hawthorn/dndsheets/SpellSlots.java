package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;

import java.util.Locale;

/**
 * <p>Espacios de conjuro por nivel de conjuro, que es como funcionan en 5e: un conjuro de nivel 3 gasta un
 * espacio de nivel 3 o superior, no "un espacio" a secas.</p>
 *
 * <p>Antes la hoja llevaba una bolsa única ({@code spellSlotsMax}/{@code spellSlotsCurrent}), fijada una
 * vez por el preset de clase y nunca escalada. Eso rompía dos cosas a la vez: un Bola de Fuego costaba lo
 * mismo que un Proyectil Mágico, y un mago de nivel 10 tenía los mismos espacios que uno de nivel 1.</p>
 *
 * <p><b>Los totales antiguos se siguen manteniendo</b> ({@link #syncTotals}) como suma de la tabla nueva.
 * No es deuda: el HUD, el Grimorio, el resumen de hoja y {@code /dndsheet} solo enseñan "cuántos me
 * quedan", y esa pregunta sigue teniendo la misma respuesta. Cambiar también todo eso a la vez habría
 * hecho el cambio mucho más grande sin mejorar nada de lo que se ve.</p>
 *
 * <p>Clase pura, sin nada de Minecraft, para poder comprobar las tablas en el self-test.</p>
 *
 * <p>Tablas del SRD 5.1 (CC-BY-4.0), ver ATTRIBUTION.md.</p>
 */
public final class SpellSlots {

	/** Nivel de conjuro más alto que existe. El índice 0 no se usa: los trucos no gastan espacio. */
	public static final int MAX_SPELL_LEVEL = 9;

	public enum Caster { NONE, FULL, HALF, PACT }

	//Un dígito por nivel de conjuro, empezando por el 1. FULL[nivel de personaje] — el índice 0 está vacío
	//para que el nivel 1 sea FULL[1] y no haya que restar uno en cada uso.
	private static final String[] FULL = {
		"", "2", "3", "42", "43", "432", "433", "4331", "4332", "43331", "43332",
		"433321", "433321", "4333211", "4333211", "43332111", "43332111",
		"433321111", "433331111", "433332111", "433332211",
	};

	//Magia de Pacto del brujo: pocos espacios, TODOS del mismo nivel, y se recuperan con un descanso
	//corto. Por eso es una tabla aparte y no un caso de FULL — no es "menos espacios", es otro recurso.
	private static final int[] PACT_COUNT = {0, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4};
	private static final int[] PACT_LEVEL = {0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5};

	private SpellSlots() {
	}

	/**
	 * <p>Qué tipo de lanzador es una clase. Se compara contra el id inglés del preset Y contra su nombre
	 * mostrado, porque la hoja guarda en {@code characterClass} el nombre traducido ("Mago", no "wizard")
	 * y {@code Config.hitDieFor} ya sufre lo mismo. Por subcadena y en minúsculas, igual que aquella.</p>
	 */
	public static Caster casterFor(String characterClass) {
		if (characterClass == null) return Caster.NONE;
		String c = characterClass.toLowerCase(Locale.ROOT);
		if (contains(c, "warlock", "brujo")) return Caster.PACT;
		if (contains(c, "paladin", "paladín", "ranger", "explorador")) return Caster.HALF;
		if (contains(c, "wizard", "mago", "cleric", "clérigo", "clerigo", "bard", "bardo",
				"druid", "druida", "sorcerer", "hechicero")) return Caster.FULL;
		return Caster.NONE;
	}

	private static boolean contains(String haystack, String... needles) {
		for (String needle : needles) {
			if (haystack.contains(needle)) return true;
		}
		return false;
	}

	/**
	 * <p>Espacios máximos por nivel de conjuro. El índice es el nivel de conjuro (1..9); el 0 va siempre a
	 * cero porque los trucos son a voluntad.</p>
	 */
	public static int[] maxSlots(Caster caster, int characterLevel) {
		int level = Math.min(20, Math.max(1, characterLevel));
		int[] slots = new int[MAX_SPELL_LEVEL + 1];

		switch (caster) {
			case FULL -> fill(slots, FULL[level]);
			//Un semilanzador es exactamente un lanzador completo a la mitad de nivel, redondeando hacia
			//arriba — comprobado nivel por nivel contra la tabla del SRD antes de apoyarse en ello, porque
			//"la mitad" con el redondeo al revés desplaza toda la progresión un nivel. Y no lanza nada
			//hasta el nivel 2, que es el único punto donde la regla no es la división.
			case HALF -> { if (level >= 2) fill(slots, FULL[(level + 1) / 2]); }
			case PACT -> slots[PACT_LEVEL[level]] = PACT_COUNT[level];
			case NONE -> { }
		}
		return slots;
	}

	private static void fill(int[] slots, String row) {
		for (int i = 0; i < row.length(); i++) slots[i + 1] = row.charAt(i) - '0';
	}

	/**
	 * <p>Gasta un espacio para un conjuro de nivel {@code spellLevel}, cogiendo <b>el más bajo que sirva</b>.
	 * Devuelve false si no queda ninguno.</p>
	 *
	 * <p>El más bajo y no el exacto porque en 5e se puede lanzar con un espacio superior, y gastar el más
	 * alto disponible pudiendo usar uno bajo es tirar el recurso caro. Que se pueda subir de nivel el
	 * conjuro al hacerlo (más dados de daño) es otra regla y no está aquí.</p>
	 */
	public static boolean spend(JsonObject sheet, int spellLevel) {
		if (spellLevel <= 0) return true; //Truco: a voluntad, no gasta nada.
		int[] current = currentSlots(sheet);
		for (int level = spellLevel; level <= MAX_SPELL_LEVEL; level++) {
			if (current[level] > 0) {
				current[level]--;
				writeSlots(sheet, "spellSlotsByLevel", current);
				syncTotals(sheet);
				return true;
			}
		}
		return false;
	}

	/** ¿Queda algún espacio con el que lanzar esto? Misma regla que {@link #spend}, sin gastar. */
	public static boolean hasSlotFor(JsonObject sheet, int spellLevel) {
		if (spellLevel <= 0) return true;
		int[] current = currentSlots(sheet);
		for (int level = spellLevel; level <= MAX_SPELL_LEVEL; level++) {
			if (current[level] > 0) return true;
		}
		return false;
	}

	/**
	 * <p>Recupera espacios gastados hasta agotar un <b>presupuesto de niveles sumados</b>, no un número de
	 * espacios: es como funciona la Recuperación Arcana del mago (recuperas espacios cuyos niveles sumen
	 * la mitad de tu nivel, ninguno por encima del 5º). Devuelve cuántos ha devuelto.</p>
	 *
	 * <p>Coge primero los más altos que quepan, que es lo que elegiría cualquiera en la mesa: con el mismo
	 * presupuesto, un espacio de nivel 3 vale más que tres de nivel 1. Hasta que existió la tabla por
	 * niveles esta regla no se podía escribir — con una bolsa única no hay "de qué nivel" que recuperar.</p>
	 */
	public static int restoreBudget(JsonObject sheet, int levelBudget, int maxLevel) {
		int[] max = maxSlotsOf(sheet);
		int[] current = currentSlots(sheet);
		int budget = levelBudget;
		int restored = 0;

		for (int level = Math.min(maxLevel, MAX_SPELL_LEVEL); level >= 1; level--) {
			while (budget >= level && current[level] < max[level]) {
				current[level]++;
				budget -= level;
				restored++;
			}
		}

		if (restored > 0) {
			writeSlots(sheet, "spellSlotsByLevel", current);
			syncTotals(sheet);
		}
		return restored;
	}

	/** Descanso: los espacios vuelven a su máximo. */
	public static void restoreAll(JsonObject sheet) {
		writeSlots(sheet, "spellSlotsByLevel", maxSlotsOf(sheet));
		syncTotals(sheet);
	}

	/**
	 * <p>Recalcula el máximo desde clase y nivel, y ajusta lo que queda para que nunca supere al máximo
	 * nuevo. Al subir de nivel los espacios nuevos entran <b>llenos</b>: en 5e se ganan al terminar el
	 * descanso largo con el que se sube, así que darlos vacíos obligaría a otro descanso para estrenarlos.</p>
	 */
	public static void applyProgression(JsonObject sheet, String characterClass, int characterLevel) {
		Caster caster = casterFor(characterClass);
		if (caster == Caster.NONE) {
			//Una clase que no lanza no lleva progresión, pero eso NO significa "sin espacios": el DM puede
			//habérselos puesto a mano (un guerrero con un objeto, una clase de la casa). Recalcular a cero
			//le borraría la configuración en el primer sincronizado.
			migrateFlatPool(sheet);
			return;
		}

		int[] max = maxSlots(caster, characterLevel);
		int[] before = readSlots(sheet, "spellSlotsMaxByLevel");
		int[] current = currentSlots(sheet);

		for (int level = 1; level <= MAX_SPELL_LEVEL; level++) {
			int gained = Math.max(0, max[level] - before[level]);
			current[level] = Math.min(max[level], current[level] + gained);
		}

		writeSlots(sheet, "spellSlotsMaxByLevel", max);
		writeSlots(sheet, "spellSlotsByLevel", current);
		syncTotals(sheet);
	}

	/**
	 * <p>Hojas anteriores a la tabla: llevan solo la bolsa única. Sin esto, {@link #hasSlotFor} las vería
	 * vacías y el personaje no podría lanzar nada pese a que su hoja dice que le quedan espacios.</p>
	 *
	 * <p>Se colocan como espacios de <b>nivel 1</b>. Es la lectura conservadora: la bolsa no decía de qué
	 * nivel eran, y repartirlos hacia arriba les daría un poder que nunca tuvieron. Para una clase
	 * lanzadora esto ni se llega a usar — la progresión recalcula su tabla de verdad.</p>
	 */
	private static void migrateFlatPool(JsonObject sheet) {
		if (sheet == null || sheet.has("spellSlotsMaxByLevel")) return;
		int flatMax = sheet.has("spellSlotsMax") ? sheet.get("spellSlotsMax").getAsInt() : 0;
		if (flatMax <= 0) return;
		int flatCurrent = sheet.has("spellSlotsCurrent") ? sheet.get("spellSlotsCurrent").getAsInt() : 0;

		int[] max = new int[MAX_SPELL_LEVEL + 1];
		int[] current = new int[MAX_SPELL_LEVEL + 1];
		max[1] = flatMax;
		current[1] = Math.max(0, Math.min(flatMax, flatCurrent));
		writeSlots(sheet, "spellSlotsMaxByLevel", max);
		writeSlots(sheet, "spellSlotsByLevel", current);
		syncTotals(sheet);
	}

	/** Espacios fijados a mano por el DM ({@code /dndsheet setslots} y el Panel de DM), todos de nivel 1. */
	public static void setFlat(JsonObject sheet, int max, int current) {
		int[] maxSlots = new int[MAX_SPELL_LEVEL + 1];
		int[] currentSlots = new int[MAX_SPELL_LEVEL + 1];
		maxSlots[1] = Math.max(0, max);
		currentSlots[1] = Math.max(0, Math.min(max, current));
		writeSlots(sheet, "spellSlotsMaxByLevel", maxSlots);
		writeSlots(sheet, "spellSlotsByLevel", currentSlots);
		syncTotals(sheet);
	}

	public static int[] maxSlotsOf(JsonObject sheet) {
		return readSlots(sheet, "spellSlotsMaxByLevel");
	}

	public static int[] currentSlots(JsonObject sheet) {
		return readSlots(sheet, "spellSlotsByLevel");
	}

	/**
	 * <p>Mantiene {@code spellSlotsMax}/{@code spellSlotsCurrent} como la suma de la tabla. Todo lo que
	 * solo enseña "cuántos me quedan" (HUD, Grimorio, resumen de hoja, {@code /dndsheet}) sigue leyendo
	 * esos dos sin cambiar.</p>
	 */
	public static void syncTotals(JsonObject sheet) {
		sheet.addProperty("spellSlotsMax", total(maxSlotsOf(sheet)));
		sheet.addProperty("spellSlotsCurrent", total(currentSlots(sheet)));
	}

	public static int total(int[] slots) {
		int sum = 0;
		for (int level = 1; level <= MAX_SPELL_LEVEL; level++) sum += slots[level];
		return sum;
	}

	private static int[] readSlots(JsonObject sheet, String key) {
		int[] slots = new int[MAX_SPELL_LEVEL + 1];
		if (sheet == null || !sheet.has(key) || !sheet.get(key).isJsonObject()) return slots;
		JsonObject stored = sheet.getAsJsonObject(key);
		for (int level = 1; level <= MAX_SPELL_LEVEL; level++) {
			String name = String.valueOf(level);
			if (stored.has(name)) {
				try {
					slots[level] = Math.max(0, stored.get(name).getAsInt());
				} catch (RuntimeException ignored) {
					//Una hoja tocada a mano no debe impedir jugar: ese nivel se queda a cero.
				}
			}
		}
		return slots;
	}

	private static void writeSlots(JsonObject sheet, String key, int[] slots) {
		JsonObject stored = new JsonObject();
		//Solo los niveles con espacios: una hoja de guerrero no necesita nueve ceros, y así se ve de un
		//vistazo qué tiene de verdad al abrir el .json.
		for (int level = 1; level <= MAX_SPELL_LEVEL; level++) {
			if (slots[level] > 0) stored.addProperty(String.valueOf(level), slots[level]);
		}
		sheet.add(key, stored);
	}
}
