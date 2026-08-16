package net.hawthorn.dndsheets;

import java.text.Normalizer;
import java.util.Locale;

/**
 * <p>Los catorce tipos de criatura de 5e, más {@link #UNKNOWN} para lo que no declara ninguno.</p>
 *
 * <p>No es una etiqueta decorativa: hay reglas enteras que solo funcionan si se puede preguntar de qué es
 * algo. El Castigo Divino suma un dado contra no-muertos e inmundos ({@link PaladinSmiteManager}), y esa
 * regla estuvo sin escribir precisamente porque no había nada que consultar. Inmovilizar Persona, Hechizar
 * Persona o Dominar Bestia son la siguiente tanda: cada uno solo afecta a un tipo, y hasta ahora afectaban
 * a cualquier cosa.</p>
 *
 * <p>Un enum y no una cadena suelta porque el conjunto está cerrado desde 2014 y no lo amplía nadie: con
 * una cadena, un {@code "no muerto"} sin guion en un pack de un DM sería un tipo nuevo silencioso que no
 * casa con ninguna regla. {@link #parse} normaliza acentos, mayúsculas, guiones y los nombres en inglés,
 * porque un DM que escribe {@code "Undead"} o {@code "No-Muerto"} está diciendo lo mismo.</p>
 *
 * <p>Un tipo desconocido <b>no</b> es un error: un mob de otro mod, un PNJ genérico o un pack anterior a
 * este campo siguen funcionando exactamente como antes. Lo único que pierden es acceso a las reglas que
 * preguntan por el tipo, y ese es el comportamiento correcto — ninguna regla debería dispararse por
 * adivinar.</p>
 */
public enum CreatureType {
	UNKNOWN(""),
	ABERRATION("aberracion"),
	BEAST("bestia"),
	CELESTIAL("celestial"),
	CONSTRUCT("automata"),
	DRAGON("dragon"),
	ELEMENTAL("elemental"),
	FEY("hada"),
	FIEND("inmundo"),
	GIANT("gigante"),
	HUMANOID("humanoide"),
	MONSTROSITY("monstruosidad"),
	OOZE("cieno"),
	PLANT("planta"),
	UNDEAD("nomuerto");

	/** Cómo se escribe en el JSON de contenido, ya normalizado (sin acentos, sin guiones, en minúscula). */
	private final String key;

	//Los mismos tipos en inglés: el SRD original está en inglés y un DM puede copiar de ahí. Se aceptan
	//porque rechazarlos no protege de nada — el resultado sería un monstruo sin tipo, en silencio.
	private static final String[] ENGLISH = {
		"", "aberration", "beast", "celestial", "construct", "dragon", "elemental", "fey", "fiend",
		"giant", "humanoid", "monstrosity", "ooze", "plant", "undead",
	};

	CreatureType(String key) {
		this.key = key;
	}

	/** ¿Le suma su dado extra el Castigo Divino? En 5e: no-muertos e inmundos. */
	public boolean isSmiteFavoredTarget() {
		return this == UNDEAD || this == FIEND;
	}

	/**
	 * <p>Lee un tipo del JSON. Devuelve {@link #UNKNOWN} para null, vacío o cualquier cosa que no case:
	 * un tipo mal escrito deja al monstruo sin tipo, que es como estaban todos hasta ahora, en vez de
	 * tumbar la carga del pack entero por una palabra.</p>
	 */
	public static CreatureType parse(String raw) {
		if (raw == null) return UNKNOWN;
		String normalized = normalize(raw);
		if (normalized.isEmpty()) return UNKNOWN;

		for (CreatureType type : values()) {
			if (type != UNKNOWN && (type.key.equals(normalized) || ENGLISH[type.ordinal()].equals(normalized))) return type;
		}
		return UNKNOWN;
	}

	/** Cómo se escribe en el JSON, en español y con su acento. Vacío para {@link #UNKNOWN}. */
	public String label() {
		return switch (this) {
			case UNKNOWN -> "";
			case ABERRATION -> "aberración";
			case CONSTRUCT -> "autómata";
			case DRAGON -> "dragón";
			case UNDEAD -> "no-muerto";
			default -> key;
		};
	}

	//Acentos fuera y guiones/espacios fuera: "no-muerto", "No Muerto" y "nomuerto" son la misma palabra
	//escrita por tres personas distintas, y ninguna de las tres está equivocada.
	private static String normalize(String raw) {
		String stripped = Normalizer.normalize(raw.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
			.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
		return stripped.replaceAll("[\\s_-]", "");
	}
}
