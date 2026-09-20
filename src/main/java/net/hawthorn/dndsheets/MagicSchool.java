package net.hawthorn.dndsheets;

import java.text.Normalizer;
import java.util.Locale;

/**
 * <p>The eight 5e schools of magic, plus {@link #UNKNOWN} for a spell that declares none.</p>
 *
 * <p>Here the school gates no rule: it's <b>identity</b>. Until now the pack's 87 spells all launched
 * with the same purple swirl and the same evoker sound ({@link CombatFx#spellCast}), so Revivify, Ray of
 * Frost and Fireball looked identical at the one moment a spell is actually seen: when it's cast. Damage
 * type already distinguished the IMPACT (see {@code FX_BY_DAMAGE_TYPE}); what was missing was
 * distinguishing the CAST itself, and a utility spell has no damage type to pull that from.</p>
 *
 * <p>Same mold as {@link CreatureType} and for the same reasons: an enum and not a loose string because
 * the set has been closed since 2014, {@link #parse} normalizes accents, casing, hyphens and the English
 * names (the original SRD is in English and a DM copies from there), and an unknown school is <b>not</b>
 * an error — it falls back to {@link #UNKNOWN} and the spell is cast with the usual generic effect, which
 * is exactly how the whole pack behaved before this field existed (invariant 8).</p>
 */
public enum MagicSchool {
	UNKNOWN(""),
	ABJURATION("abjuration"),
	CONJURATION("conjuration"),
	DIVINATION("divination"),
	ENCHANTMENT("enchantment"),
	EVOCATION("evocation"),
	ILLUSION("illusion"),
	NECROMANCY("necromancy"),
	TRANSMUTATION("transmutation");

	/** How it's written in content JSON, already normalized (no accents, no hyphens, lowercase). */
	private final String key;

	//The legacy Spanish names, indexed by ordinal, still accepted so packs written before the English switch keep loading.
	private static final String[] LEGACY_SPANISH = {
		"", "abjuracion", "conjuracion", "adivinacion", "encantamiento", "evocacion", "ilusion",
		"nigromancia", "transmutacion",
	};


	MagicSchool(String key) {
		this.key = key;
	}

	/**
	 * <p>The schools as they're written in JSON, for the in-game editor's cycle button
	 * ({@code ContentTypeForms.spellFields}). The empty string goes first on purpose: "no school" is a
	 * spell's default value and has to be the first thing the button offers, not something you have to
	 * cycle all the way around to get back to.</p>
	 */
	public static final String[] KEYS = buildKeys();

	private static String[] buildKeys() {
		String[] keys = new String[values().length];
		for (MagicSchool school : values()) keys[school.ordinal()] = school.key;
		return keys;
	}

	/**
	 * <p>Reads a school from JSON. Returns {@link #UNKNOWN} for null, empty, or anything that doesn't
	 * match: a misspelled school leaves the spell with no school — as they all were until now — instead
	 * of bringing down the whole pack's load over one word.</p>
	 */
	public static MagicSchool parse(String raw) {
		if (raw == null) return UNKNOWN;
		String normalized = normalize(raw);
		if (normalized.isEmpty()) return UNKNOWN;

		for (MagicSchool school : values()) {
			if (school != UNKNOWN && (school.key.equals(normalized) || LEGACY_SPANISH[school.ordinal()].equals(normalized))) return school;
		}
		return UNKNOWN;
	}

	/** English name to display to the player/DM. Empty for {@link #UNKNOWN}. */
	public String label() {
		return switch (this) {
			case UNKNOWN -> "";
			case ABJURATION -> "Abjuration";
			case CONJURATION -> "Conjuration";
			case DIVINATION -> "Divination";
			case ENCHANTMENT -> "Enchantment";
			case EVOCATION -> "Evocation";
			case ILLUSION -> "Illusion";
			case NECROMANCY -> "Necromancy";
			case TRANSMUTATION -> "Transmutation";
		};
	}

	//Accents stripped and hyphens/spaces stripped: "divination", "Divination" and "DIVINATION" are the
	//same word written by three different people, and none of the three is wrong.
	private static String normalize(String raw) {
		String stripped = Normalizer.normalize(raw.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
			.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
		return stripped.replaceAll("[\s_-]", "");
	}
}
