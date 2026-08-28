package net.hawthorn.dndsheets;

import java.text.Normalizer;
import java.util.Locale;

/**
 * <p>Las ocho escuelas de magia de 5e, más {@link #UNKNOWN} para el conjuro que no declara ninguna.</p>
 *
 * <p>Aquí la escuela no gatea ninguna regla: es <b>identidad</b>. Hasta ahora los 87 conjuros del pack
 * arrancaban con el mismo remolino morado y el mismo sonido de evocador ({@link CombatFx#spellCast}), así
 * que Revivir, Rayo de Escarcha y Bola de Fuego se veían idénticos en el único instante en el que un
 * conjuro se ve: al lanzarlo. El tipo de daño ya distinguía el IMPACTO (ver {@code FX_BY_DAMAGE_TYPE});
 * lo que faltaba era distinguir el LANZAMIENTO, y un conjuro de utilidad no tiene tipo de daño del que
 * sacarlo.</p>
 *
 * <p>Mismo molde que {@link CreatureType} y por las mismas razones: un enum y no una cadena suelta porque
 * el conjunto está cerrado desde 2014, {@link #parse} normaliza acentos, mayúsculas, guiones y los
 * nombres en inglés (el SRD original está en inglés y un DM copia de ahí), y una escuela desconocida
 * <b>no</b> es un error — cae a {@link #UNKNOWN} y el conjuro se lanza con el efecto genérico de siempre,
 * que es exactamente como se comportaba el pack entero antes de que este campo existiera (invariante 8).</p>
 */
public enum MagicSchool {
	UNKNOWN(""),
	ABJURATION("abjuracion"),
	CONJURATION("conjuracion"),
	DIVINATION("adivinacion"),
	ENCHANTMENT("encantamiento"),
	EVOCATION("evocacion"),
	ILLUSION("ilusion"),
	NECROMANCY("nigromancia"),
	TRANSMUTATION("transmutacion");

	/** Cómo se escribe en el JSON de contenido, ya normalizado (sin acentos, sin guiones, en minúscula). */
	private final String key;

	//Las mismas escuelas en inglés, indexadas por ordinal igual que en CreatureType.ENGLISH.
	private static final String[] ENGLISH = {
		"", "abjuration", "conjuration", "divination", "enchantment", "evocation", "illusion",
		"necromancy", "transmutation",
	};

	MagicSchool(String key) {
		this.key = key;
	}

	/**
	 * <p>Las escuelas como se escriben en el JSON, para el ciclador del editor in-game
	 * ({@code ContentTypeForms.spellFields}). La cadena vacía va primera a propósito: "sin escuela" es el
	 * valor por defecto de un conjuro y tiene que ser lo primero que ofrezca el botón, no algo a lo que
	 * haya que dar la vuelta entera para volver.</p>
	 */
	public static final String[] KEYS = buildKeys();

	private static String[] buildKeys() {
		String[] keys = new String[values().length];
		for (MagicSchool school : values()) keys[school.ordinal()] = school.key;
		return keys;
	}

	/**
	 * <p>Lee una escuela del JSON. Devuelve {@link #UNKNOWN} para null, vacío o cualquier cosa que no
	 * case: una escuela mal escrita deja al conjuro sin escuela —como estaban todos hasta ahora— en vez
	 * de tumbar la carga del pack entero por una palabra.</p>
	 */
	public static MagicSchool parse(String raw) {
		if (raw == null) return UNKNOWN;
		String normalized = normalize(raw);
		if (normalized.isEmpty()) return UNKNOWN;

		for (MagicSchool school : values()) {
			if (school != UNKNOWN && (school.key.equals(normalized) || ENGLISH[school.ordinal()].equals(normalized))) return school;
		}
		return UNKNOWN;
	}

	/** Cómo se escribe en el JSON, en español y con su acento. Vacío para {@link #UNKNOWN}. */
	public String label() {
		return switch (this) {
			case UNKNOWN -> "";
			case ABJURATION -> "abjuración";
			case CONJURATION -> "conjuración";
			case DIVINATION -> "adivinación";
			case EVOCATION -> "evocación";
			case ILLUSION -> "ilusión";
			default -> key;
		};
	}

	//Acentos fuera y guiones/espacios fuera: "adivinación", "Adivinacion" y "adivinacion" son la misma
	//palabra escrita por tres personas distintas, y ninguna de las tres está equivocada.
	private static String normalize(String raw) {
		String stripped = Normalizer.normalize(raw.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
			.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
		return stripped.replaceAll("[\s_-]", "");
	}
}
