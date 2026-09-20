package net.hawthorn.dndsheets;

import java.text.Normalizer;
import java.util.Locale;

/**
 * <p>The six 5e size categories, plus {@link #UNKNOWN} for whatever declares none. Exactly the same
 * mold as {@link CreatureType}: a closed enum, a {@link #parse} tolerant of accents, casing,
 * hyphens and the legacy Spanish names, and an unknown value that triggers nothing.</p>
 *
 * <p><b>Why it exists.</b> The bestiary has 330 creatures and <b>149 are larger than Medium</b> —102
 * Large, 32 Huge, 15 Gargantuan—, and until now none of them looked any different from a villager: the only
 * thing the mod could say about size was {@code appearance.baby}, a boolean to make something smaller.
 * An ancient dragon and a kobold occupied the same body.</p>
 *
 * <p><b>The scale comes from the rule, not from the eye.</b> 5e doesn't describe sizes in meters but in the
 * space they take up on the grid: Tiny 2.5 ft, Small and Medium 5, Large 10, Huge 15,
 * Gargantuan 20. {@link #spaceScale()} is exactly that number divided by 5, so a Large comes out
 * double and a Gargantuan quadruple because the rulebook says it takes up four times as much, not because
 * it looked nice.</p>
 *
 * <p><b>Small is 1.0 on purpose</b>, even though a goblin is shorter than a human: in 5e it takes up the
 * same 5 ft square as a Medium. Shrinking it here would also <b>double</b> the effect on the
 * creatures that already use {@code appearance.baby} to look small (the goblin is exactly one of them), and
 * you'd get a goblin the size of a rat. Anyone who wants a shorter Small has that field, which is
 * where that decision already lived.</p>
 *
 * <p>Only {@link net.hawthorn.dndsheets.compat.PehkuiCompat} consumes it: <b>no rule</b> asks
 * about size today. If one ever does (grapple a Large, tight spaces, shoving), the datum is already
 * in place and no pack needs migrating.</p>
 */
public enum CreatureSize {
	UNKNOWN("", 1.0f),
	TINY("tiny", 0.5f),
	SMALL("small", 1.0f),
	MEDIUM("medium", 1.0f),
	LARGE("large", 2.0f),
	HUGE("huge", 3.0f),
	GARGANTUAN("gargantuan", 4.0f);

	/** How it's written in content JSON, already normalized (no accents, no hyphens, lowercase). */
	private final String key;
	private final float spaceScale;

	//The legacy Spanish names, still accepted so packs written before the English switch keep loading.
	//Same criterion as CreatureType — rejecting them would protect nothing, it would just leave a missing size
	//silently. The order is that of values().
	private static final String[] LEGACY_SPANISH = {
		"", "diminuto", "pequeno", "mediano", "grande", "enorme", "gargantuesco",
	};

	CreatureSize(String key, float spaceScale) {
		this.key = key;
		this.spaceScale = spaceScale;
	}

	/** Name shown to the player/DM. Empty for {@link #UNKNOWN}. */
	public String label() {
		return switch (this) {
			case UNKNOWN -> "";
			case TINY -> "Tiny";
			case SMALL -> "Small";
			case MEDIUM -> "Medium";
			case LARGE -> "Large";
			case HUGE -> "Huge";
			case GARGANTUAN -> "Gargantuan";
		};
	}

	/**
	 * <p>How many times a Medium's space this creature takes up, which is the multiplier it is
	 * drawn with. 1.0 means "don't touch it": it is what {@link #UNKNOWN}, {@link #MEDIUM} and
	 * {@link #SMALL} return, and that's why a creature with no declared size looks exactly the same as before
	 * this field existed (invariant 9).</p>
	 */
	public float spaceScale() {
		return spaceScale;
	}

	/** {@code UNKNOWN} if the text is empty or names no size — it never fails, never guesses. */
	public static CreatureSize parse(String raw) {
		if (raw == null) return UNKNOWN;
		String normalized = normalize(raw);
		if (normalized.isEmpty()) return UNKNOWN;
		CreatureSize[] values = values();
		for (int i = 0; i < values.length; i++) {
			if (values[i].key.equals(normalized) || LEGACY_SPANISH[i].equals(normalized)) return values[i];
		}
		return UNKNOWN;
	}

	//Accents out, hyphens and spaces out, lowercase: "Gargantuan", "gargantuan" and "GARGANTUAN"
	//are the same word, and "undead" taught CreatureType that the hyphen shows up on its own.
	private static String normalize(String raw) {
		String decomposed = Normalizer.normalize(raw.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
		return decomposed.replaceAll("\\p{M}", "").replaceAll("[\\s_-]", "");
	}
}
