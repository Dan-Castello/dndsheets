package net.hawthorn.dndsheets.client.gui;

import net.minecraft.client.resources.language.I18n;

import java.util.Locale;

/**
 * <p>What a DM or player reads instead of a storage code. Content and network keep short lowercase codes
 * ("str", "versatile", "frightened", "very rare"); screens used to print them as-is, so a Spanish DM cycled
 * through "one / two / versatile" and picked "bludgeoning" from a list. Client-only ({@link I18n}): the
 * stored value never changes, only what's drawn.</p>
 *
 * <p>Both lookups fall back to the raw text when there's no translation, so a code or a DM-made id that
 * the lang files don't know still shows something instead of a key.</p>
 */
public final class OptionLabels {

	private OptionLabels() {
	}

	/** {@code gui.dndsheets.option.<code>} (spaces become underscores), or the code itself. */
	public static String of(String code) {
		if (code == null || code.isEmpty()) return "";
		String key = "gui.dndsheets.option." + code.toLowerCase(Locale.ROOT).replace(' ', '_');
		return I18n.exists(key) ? I18n.get(key) : code;
	}

	/** The same mapping for a whole option array, in order (for {@code addCycleButton}'s display labels). */
	public static String[] of(String[] codes) {
		String[] labels = new String[codes.length];
		for (int i = 0; i < codes.length; i++) labels[i] = of(codes[i]);
		return labels;
	}

	/**
	 * <p>A content id's name: packs name their entries {@code content.dndsheets.<kind>.<path>} ("dndsheets:goblin" →
	 * "content.dndsheets.monster.goblin"), so lists that only receive ids from the server can still show "Goblin".
	 * An id with no such key (DM-made content) stays as the id.</p>
	 */
	public static String contentName(String kind, String id) {
		if (id == null) return "";
		String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
		String key = "content.dndsheets." + kind + "." + path;
		return I18n.exists(key) ? I18n.get(key) : id;
	}
}
