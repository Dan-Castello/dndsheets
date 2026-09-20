package net.hawthorn.dndsheets;

import java.text.Normalizer;
import java.util.Locale;

/**
 * <p>The fourteen 5e creature types, plus {@link #UNKNOWN} for anything that declares none.</p>
 *
 * <p>It's not a decorative label: whole rules only work if you can ask what something is. Divine Smite
 * adds an extra die against undead and fiends ({@link PaladinSmiteManager}), and that rule went
 * unwritten precisely because there was nothing to query. Hold Person, Charm Person or Dominate
 * Beast are the next batch: each only affects one type, and until now they affected anything.</p>
 *
 * <p>An enum and not a loose string because the set has been closed since 2014 and nobody extends it:
 * with a string, a {@code "un dead"} spaced out in a DM's pack would be a silent new type that
 * matches no rule. {@link #parse} normalizes accents, casing, hyphens and the English names,
 * because a DM who writes {@code "Undead"} or {@code "No-Muerto"} (legacy) is saying the same thing.</p>
 *
 * <p>An unknown type is <b>not</b> an error: a mob from another mod, a generic NPC, or a pack from
 * before this field existed all keep working exactly as before. The only thing they lose is access to
 * the rules that ask for the type, and that's the correct behavior — no rule should fire on a guess.</p>
 */
public enum CreatureType {
	UNKNOWN(""),
	ABERRATION("aberration"),
	BEAST("beast"),
	CELESTIAL("celestial"),
	CONSTRUCT("construct"),
	DRAGON("dragon"),
	ELEMENTAL("elemental"),
	FEY("fey"),
	FIEND("fiend"),
	GIANT("giant"),
	HUMANOID("humanoid"),
	MONSTROSITY("monstrosity"),
	OOZE("ooze"),
	PLANT("plant"),
	UNDEAD("undead");

	/** How it's written in content JSON, already normalized (no accents, no hyphens, lowercase). */
	private final String key;

	//The legacy Spanish names, still accepted so packs written before the English switch keep loading (and
	//because a DM may still write them). Rejecting them protects nothing — the result would be a monster with no type, silently.
	private static final String[] LEGACY_SPANISH = {
		"", "aberracion", "bestia", "celestial", "automata", "dragon", "elemental", "hada", "inmundo",
		"gigante", "humanoide", "monstruosidad", "cieno", "planta", "nomuerto",
	};


	CreatureType(String key) {
		this.key = key;
	}

	/** Does Divine Smite add its extra die against it? In 5e: undead and fiends. */
	public boolean isSmiteFavoredTarget() {
		return this == UNDEAD || this == FIEND;
	}

	/**
	 * <p>Reads a list of types from JSON (e.g. {@code ["undead", "construct"]}). Unrecognized ones are
	 * discarded: a misspelled word only takes down its own entry, not the whole list or the spell.</p>
	 */
	public static java.util.Set<CreatureType> parseAll(com.google.gson.JsonArray raw) {
		java.util.Set<CreatureType> types = java.util.EnumSet.noneOf(CreatureType.class);
		if (raw == null) return types;
		for (com.google.gson.JsonElement element : raw) {
			CreatureType type = parse(element.getAsString());
			if (type != UNKNOWN) types.add(type);
		}
		return types;
	}

	/**
	 * <p>Reads a type from JSON. Returns {@link #UNKNOWN} for null, empty, or anything that doesn't match:
	 * a misspelled type leaves the monster without a type, which is how they all were until now, instead
	 * of bringing down the whole pack's load over one word.</p>
	 */
	public static CreatureType parse(String raw) {
		if (raw == null) return UNKNOWN;
		String normalized = normalize(raw);
		if (normalized.isEmpty()) return UNKNOWN;

		for (CreatureType type : values()) {
			if (type != UNKNOWN && (type.key.equals(normalized) || LEGACY_SPANISH[type.ordinal()].equals(normalized))) return type;
		}
		return UNKNOWN;
	}

	/** English name to display to the player/DM. Empty for {@link #UNKNOWN}. */
	public String label() {
		return switch (this) {
			case UNKNOWN -> "";
			case ABERRATION -> "Aberration";
			case BEAST -> "Beast";
			case CELESTIAL -> "Celestial";
			case CONSTRUCT -> "Construct";
			case DRAGON -> "Dragon";
			case ELEMENTAL -> "Elemental";
			case FEY -> "Fey";
			case FIEND -> "Fiend";
			case GIANT -> "Giant";
			case HUMANOID -> "Humanoid";
			case MONSTROSITY -> "Monstrosity";
			case OOZE -> "Ooze";
			case PLANT -> "Plant";
			case UNDEAD -> "Undead";
		};
	}

	//Accents stripped and hyphens/spaces stripped: "undead", "Un-dead" and "UNDEAD" are the same
	//word written by three different people, and none of the three is wrong.
	private static String normalize(String raw) {
		String stripped = Normalizer.normalize(raw.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
			.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
		return stripped.replaceAll("[\\s_-]", "");
	}
}
